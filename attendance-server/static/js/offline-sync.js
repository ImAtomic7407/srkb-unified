/**
 * AttendEase Offline Sync — Phase 3
 * ===================================
 * Manages IndexedDB caching + offline attendance queue + server sync.
 *
 * IndexedDB schema  (DB: "attendease-offline"  version: 2)
 *   session_cache   keyPath: "centre_id"
 *                   { centre_id, session_state, batch_list, marked_today_mobiles,
 *                     marked_today_count, roster, synced_at }
 *   offline_queue   keyPath: "client_id"
 *                   { client_id, mobile, name, address, occupation,
 *                     centre_id, session_id, batch_id, batch_name,
 *                     date, remark, rounds, cheating,
 *                     _sync_status: "pending"|"committed"|"failed",
 *                     _created_at, _retry_count }
 *
 * Public API (window.AttendEaseOffline):
 *   .isReady          — true after init() resolves
 *   .init()           — open IndexedDB; must be awaited before anything else
 *   .isOnline()       — synchronous — window.navigator.onLine
 *   .checkServer()    — async ping to /health; true = reachable
 *   .pullState(centre_id)      — fetch /api/cache/state and cache it
 *   .pullRoster(centre_id, batch_id) — fetch /api/cache/roster and cache it
 *   .getCachedState(centre_id) — read IndexedDB session_cache
 *   .getCachedRoster(centre_id, batch_id) — read roster from session_cache
 *   .markAttendance(payload)   — tries server; falls back to queue
 *   .flushQueue(centre_id)     — push pending records to /api/sync/offline_queue
 *   .recoverExhaustedRecords(centre_id) — hand records that exhausted their
 *                                 3 retries to the lenient /api/sync/recover_queue
 *                                 endpoint so they're never silently lost
 *   .getPendingCount()         — number of _sync_status:"pending" records
 *   .getPendingRecords()       — array of pending records
 *   .onStatusChange(fn)        — register callback(status) where status is
 *                                 "online"|"offline"|"syncing"|"error"
 */

(function () {
    'use strict';

    const DB_NAME = 'attendease-offline';
    const DB_VERSION = 2;
    const CACHE_STORE = 'session_cache';
    const QUEUE_STORE = 'offline_queue';

    // ── Utilities ────────────────────────────────────────────────────────────

    function uuid() {
        if (typeof crypto !== 'undefined' && crypto.randomUUID) {
            return crypto.randomUUID();
        }
        // Fallback for older WebViews
        return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
            const r = (Math.random() * 16) | 0;
            return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
        });
    }

    function fetchWithTimeout(url, options, timeoutMs) {
        // Cross-origin calls from the Capacitor offline bundle never carry the
        // Flask session cookie, so without a Bearer token every sync/mark call
        // 401'd once the device reconnected — the JWT bridged into this
        // origin's localStorage (see bridge.html) is the only credential
        // available here.
        options = options || {};
        var token = localStorage.getItem('ae_offline_jwt') || localStorage.getItem('ae_jwt');
        if (token) {
            options.headers = Object.assign({}, options.headers, { 'Authorization': 'Bearer ' + token });
        }
        return Promise.race([
            fetch(url, options),
            new Promise((_, reject) =>
                setTimeout(() => reject(new Error('timeout')), timeoutMs)
            )
        ]);
    }

    // ── IndexedDB helpers ─────────────────────────────────────────────────────

    function openDB() {
        return new Promise((resolve, reject) => {
            const req = indexedDB.open(DB_NAME, DB_VERSION);
            req.onupgradeneeded = function (evt) {
                const db = evt.target.result;
                if (!db.objectStoreNames.contains(CACHE_STORE)) {
                    db.createObjectStore(CACHE_STORE, { keyPath: 'centre_id' });
                }
                if (!db.objectStoreNames.contains(QUEUE_STORE)) {
                    const qs = db.createObjectStore(QUEUE_STORE, { keyPath: 'client_id' });
                    qs.createIndex('by_status', '_sync_status');
                    qs.createIndex('by_centre', 'centre_id');
                }
            };
            req.onsuccess = () => resolve(req.result);
            req.onerror = () => reject(req.error);
        });
    }

    function dbGet(db, store, key) {
        return new Promise((resolve, reject) => {
            const tx = db.transaction(store, 'readonly');
            const req = tx.objectStore(store).get(key);
            req.onsuccess = () => resolve(req.result || null);
            req.onerror = () => reject(req.error);
        });
    }

    function dbPut(db, store, value) {
        return new Promise((resolve, reject) => {
            const tx = db.transaction(store, 'readwrite');
            const req = tx.objectStore(store).put(value);
            req.onsuccess = () => resolve(req.result);
            req.onerror = () => reject(req.error);
        });
    }

    function dbGetAllByIndex(db, store, indexName, indexValue) {
        return new Promise((resolve, reject) => {
            const tx = db.transaction(store, 'readonly');
            const idx = tx.objectStore(store).index(indexName);
            const req = idx.getAll(indexValue);
            req.onsuccess = () => resolve(req.result || []);
            req.onerror = () => reject(req.error);
        });
    }

    // ── AttendEaseOfflineSync class ───────────────────────────────────────────

    class AttendEaseOfflineSync {
        constructor() {
            this._db = null;
            this.isReady = false;
            this._statusListeners = [];
            this._lastStatus = 'online';
        }

        async init() {
            this._db = await openDB();
            this.isReady = true;

            // Watch native online/offline events
            window.addEventListener('online', () => {
                this._emitStatus('online');
            });
            window.addEventListener('offline', () => {
                this._emitStatus('offline');
            });

            // Flush queue on app resume (covers Android WebView without BG Sync)
            document.addEventListener('visibilitychange', () => {
                if (!document.hidden && this.isOnline()) {
                    const centreId = this._getActiveCentreId();
                    if (centreId) {
                        this.flushQueue(centreId).catch(() => {});
                        this.recoverExhaustedRecords(centreId).catch(() => {});
                    }
                }
            });

            // Self-driving reconnect watchdog — the real fix for "pending data
            // doesn't sync until I reopen the app". The browser 'online' event
            // and navigator.onLine are unreliable inside WebViews / Electron and
            // with a Cloudflare-tunnelled origin: the event can fire before the
            // server is actually reachable, or never fire at all while the page
            // stays in the foreground. That left offline-marked records stuck at
            // "pending" until a reopen happened to trigger visibilitychange.
            // This watchdog does not trust onLine at all — while (and only while)
            // there are unsynced records, it actively probes the real server and
            // flushes the instant it answers.
            this._startReconnectWatchdog();

            return this;
        }

        _startReconnectWatchdog() {
            if (this._watchdogTimer) return;
            const tick = async () => {
                try {
                    if (!this.isReady || !this._db) return;
                    const centreId = this._getActiveCentreId();
                    if (!centreId) return;
                    // Cheap local check first — never touch the network unless
                    // there is actually queued work to sync.
                    const pending = await dbGetAllByIndex(this._db, QUEUE_STORE, 'by_status', 'pending');
                    const failed  = await dbGetAllByIndex(this._db, QUEUE_STORE, 'by_status', 'failed');
                    if (!pending.length && !failed.length) return;
                    // Real reachability probe (hits /health) — NOT navigator.onLine.
                    const reachable = await this.checkServer();
                    if (!reachable) return;
                    let res = { committed: 0, duplicates: 0, failed: 0 };
                    if (pending.length) res = await this.flushQueue(centreId);
                    if (failed.length)  await this.recoverExhaustedRecords(centreId);
                    // Notify the page so it can refresh its table / counts / toast
                    // without waiting for the next user interaction.
                    try {
                        window.dispatchEvent(new CustomEvent('attendease-autosynced', { detail: res }));
                    } catch (e) {}
                } catch (e) {}
            };
            // Probe every 12 s. Idle cost is one IndexedDB read; it only hits the
            // network when records are actually waiting to sync.
            this._watchdogTimer = setInterval(tick, 12000);
            // Fast first catch-up shortly after init, too.
            setTimeout(tick, 2500);
        }

        // Synchronous: navigator.onLine is updated immediately by the browser.
        isOnline() {
            return navigator.onLine !== false;
        }

        // Async: actually hit the server to confirm reachability.
        async checkServer() {
            try {
                const res = await fetchWithTimeout('/health', { method: 'GET' }, 3000);
                return res.ok;
            } catch {
                return false;
            }
        }

        /**
         * Pull the full session state for a centre from the server and
         * cache it in IndexedDB.  Returns the state object on success,
         * or the previously-cached value if the server is unreachable.
         */
        async pullState(centreId) {
            if (!centreId) return null;
            try {
                const res = await fetchWithTimeout(
                    `/api/cache/state?centre_id=${encodeURIComponent(centreId)}`,
                    { method: 'GET' },
                    8000
                );
                if (!res.ok) throw new Error(`HTTP ${res.status}`);
                const data = await res.json();
                const record = {
                    centre_id: centreId,
                    session_state: data.session_state || {},
                    batch_list: data.batch_list || [],
                    marked_today_count: data.marked_today_count || 0,
                    marked_today_mobiles: data.marked_today_mobiles || [],
                    server_time: data.server_time || new Date().toISOString(),
                    synced_at: Date.now(),
                };
                // Preserve existing roster if present
                const existing = await dbGet(this._db, CACHE_STORE, centreId);
                if (existing && existing.roster) {
                    record.roster = existing.roster;
                    record.roster_batch_id = existing.roster_batch_id;
                    record.roster_synced_at = existing.roster_synced_at;
                }
                await dbPut(this._db, CACHE_STORE, record);
                this._emitStatus('online');
                return record;
            } catch (err) {
                this._emitStatus('offline');
                return await dbGet(this._db, CACHE_STORE, centreId);
            }
        }

        /**
         * Pull the student roster for a centre+batch from the server.
         * Cached alongside the session_cache entry.
         */
        async pullRoster(centreId, batchId) {
            if (!centreId) return [];
            try {
                let url = `/api/cache/roster?centre_id=${encodeURIComponent(centreId)}`;
                if (batchId) url += `&batch_id=${encodeURIComponent(batchId)}`;
                const res = await fetchWithTimeout(url, { method: 'GET' }, 10000);
                if (!res.ok) throw new Error(`HTTP ${res.status}`);
                const students = await res.json();

                // Merge into session_cache entry
                const existing = (await dbGet(this._db, CACHE_STORE, centreId)) || { centre_id: centreId };
                existing.roster = students;
                existing.roster_batch_id = batchId || null;
                existing.roster_synced_at = Date.now();
                await dbPut(this._db, CACHE_STORE, existing);
                return students;
            } catch {
                // Fall back to cached roster
                const cached = await dbGet(this._db, CACHE_STORE, centreId);
                return (cached && cached.roster) ? cached.roster : [];
            }
        }

        /** Read cached session state from IndexedDB (no network call). */
        async getCachedState(centreId) {
            return await dbGet(this._db, CACHE_STORE, centreId);
        }

        /** Read cached roster from IndexedDB (no network call). */
        async getCachedRoster(centreId, batchId) {
            const cached = await dbGet(this._db, CACHE_STORE, centreId);
            if (!cached || !cached.roster) return [];
            // If a specific batchId is requested and the cached roster is for a different batch, return []
            if (batchId && cached.roster_batch_id && String(cached.roster_batch_id) !== String(batchId)) {
                return [];
            }
            return cached.roster;
        }

        /**
         * Mark attendance.
         * - Tries the server with a 5 s timeout.
         * - On success: returns { ok: true, source: "server", data }
         * - On failure: saves to offline_queue and returns
         *   { ok: true, source: "queue", client_id }
         */
        async markAttendance(payload) {
            const clientId = uuid();
            payload._client_offline_id = clientId;

            if (this.isOnline()) {
                try {
                    const res = await fetchWithTimeout('/api/mark_attendance', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(payload),
                    }, 6000);
                    const data = await res.json();
                    if (res.ok) {
                        this._emitStatus('online');
                        return { ok: true, source: 'server', data };
                    }
                    // Known server-side errors (e.g. "Already Marked") — don't queue
                    if (res.status === 409 || res.status === 400) {
                        return { ok: false, source: 'server', error: data.error || 'error', status: res.status };
                    }
                    throw new Error(`HTTP ${res.status}: ${data.error || 'unknown'}`);
                } catch (err) {
                    if (err.message && (err.message.includes('409') || err.message.includes('Already'))) {
                        return { ok: false, source: 'server', error: err.message };
                    }
                    // Network failure — fall through to queue
                }
            }

            // Queue the record offline
            const queueRecord = {
                client_id: clientId,
                mobile: payload.mobile || '',
                name: payload.name || '',
                address: payload.address || '',
                occupation: payload.occupation || '',
                centre_id: payload.centre_id || '',
                session_id: payload.session_id || '',
                batch_id: payload.batch_id || null,
                batch_name: payload.batch_name || '',
                date: payload.date || new Date().toISOString().slice(0, 10),
                remark: payload.remark || '',
                rounds: payload.rounds || '',
                cheating: payload.cheating || false,
                // Youth/Congregation: carry the category so the queued record
                // replays into the correct track when it syncs.
                category: payload.category || 'youth',
                _sync_status: 'pending',
                _created_at: Date.now(),
                _retry_count: 0,
            };
            await dbPut(this._db, QUEUE_STORE, queueRecord);
            this._emitStatus('offline');

            // Try to register a Background Sync for when connectivity returns
            if ('serviceWorker' in navigator) {
                navigator.serviceWorker.ready
                    .then(sw => sw.sync && sw.sync.register('flush-attendance-queue'))
                    .catch(() => {});
            }

            return { ok: true, source: 'queue', client_id: clientId };
        }

        /**
         * Upload all pending queue records to the server.
         * Returns { committed, duplicates, failed } counts.
         */
        async flushQueue(centreId) {
            const pending = await dbGetAllByIndex(this._db, QUEUE_STORE, 'by_status', 'pending');
            if (!pending.length) return { committed: 0, duplicates: 0, failed: 0 };

            this._emitStatus('syncing');

            try {
                const res = await fetchWithTimeout('/api/sync/offline_queue', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ records: pending, centre_id: centreId }),
                }, 15000);

                if (!res.ok) throw new Error(`HTTP ${res.status}`);
                const result = await res.json();

                // Update IndexedDB records based on server response
                const updatePromises = [];
                for (const clientId of (result.committed || [])) {
                    const rec = pending.find(r => r.client_id === clientId);
                    if (rec) {
                        rec._sync_status = 'committed';
                        updatePromises.push(dbPut(this._db, QUEUE_STORE, rec));
                    }
                }
                for (const clientId of (result.duplicates || [])) {
                    const rec = pending.find(r => r.client_id === clientId);
                    if (rec) {
                        rec._sync_status = 'committed'; // duplicate = already on server = success
                        updatePromises.push(dbPut(this._db, QUEUE_STORE, rec));
                    }
                }
                for (const failItem of (result.failed || [])) {
                    const rec = pending.find(r => r.client_id === failItem.client_id);
                    if (rec) {
                        rec._sync_status = 'failed';
                        rec._retry_count = (rec._retry_count || 0) + 1;
                        // Retry up to 3 times, then mark failed permanently
                        if (rec._retry_count < 3) rec._sync_status = 'pending';
                        updatePromises.push(dbPut(this._db, QUEUE_STORE, rec));
                    }
                }
                await Promise.all(updatePromises);
                this._emitStatus('online');

                return {
                    committed: (result.committed || []).length,
                    duplicates: (result.duplicates || []).length,
                    failed: (result.failed || []).length,
                };
            } catch (err) {
                this._emitStatus('offline');
                return { committed: 0, duplicates: 0, failed: pending.length };
            }
        }

        /**
         * Sweep records that exhausted their 3 retries (_sync_status:
         * "failed") and hand them to the lenient /api/sync/recover_queue
         * endpoint, which never drops a record's identity even if it can't
         * be committed outright (e.g. no session_id was set when marked
         * offline) — it persists it server-side for manual review instead.
         * Without this sweep, flushQueue() never looks at "failed" records
         * again (it only re-queries "pending"), so they'd sit invisibly in
         * IndexedDB forever. Safe to call every time the app resumes.
         */
        async recoverExhaustedRecords(centreId) {
            const exhausted = await dbGetAllByIndex(this._db, QUEUE_STORE, 'by_status', 'failed');
            if (!exhausted.length) return { committed: 0, duplicates: 0, held: 0 };

            try {
                const res = await fetchWithTimeout('/api/sync/recover_queue', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ records: exhausted, centre_id: centreId }),
                }, 15000);

                if (!res.ok) throw new Error(`HTTP ${res.status}`);
                const result = await res.json();

                // Every record the server acknowledges (committed, duplicate,
                // or held-for-review) is now safely captured server-side —
                // mark it "recovered" so it stops cluttering local storage
                // and is never resent.
                const acknowledged = [
                    ...(result.committed || []),
                    ...(result.duplicates || []),
                    ...(result.held || []),
                ];
                const updatePromises = [];
                for (const clientId of acknowledged) {
                    const rec = exhausted.find(r => r.client_id === clientId);
                    if (rec) {
                        rec._sync_status = 'recovered';
                        updatePromises.push(dbPut(this._db, QUEUE_STORE, rec));
                    }
                }
                await Promise.all(updatePromises);

                return {
                    committed: (result.committed || []).length,
                    duplicates: (result.duplicates || []).length,
                    held: (result.held || []).length,
                };
            } catch (err) {
                // Server unreachable — leave records as "failed"; next
                // resume will retry the sweep.
                return { committed: 0, duplicates: 0, held: 0 };
            }
        }

        /** Number of records with _sync_status === "pending" */
        async getPendingCount() {
            const records = await dbGetAllByIndex(this._db, QUEUE_STORE, 'by_status', 'pending');
            return records.length;
        }

        /** Full array of pending records (for display in UI) */
        async getPendingRecords() {
            return await dbGetAllByIndex(this._db, QUEUE_STORE, 'by_status', 'pending');
        }

        /**
         * Register a callback that fires whenever the sync status changes.
         * @param {function} fn  — called with one of: "online", "offline", "syncing", "error"
         */
        onStatusChange(fn) {
            if (typeof fn === 'function') this._statusListeners.push(fn);
        }

        // ── Internal helpers ──────────────────────────────────────────────────

        _emitStatus(status) {
            if (status === this._lastStatus) return;
            this._lastStatus = status;
            for (const fn of this._statusListeners) {
                try { fn(status); } catch {}
            }
        }

        _getActiveCentreId() {
            // Host page: set by sync.js
            if (window.AttendEaseCentreState && window.AttendEaseCentreState.id) {
                return window.AttendEaseCentreState.id;
            }
            // Registerer page: set by loadRegistererCentreBadge()
            if (window.AttendEaseRegistererCentre && window.AttendEaseRegistererCentre.centre_id) {
                return window.AttendEaseRegistererCentre.centre_id;
            }
            return null;
        }
    }

    // ── Singleton ────────────────────────────────────────────────────────────

    const instance = new AttendEaseOfflineSync();
    window.AttendEaseOffline = instance;

    // Auto-init on DOMContentLoaded so it's ready before page scripts need it
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => instance.init().catch(console.error));
    } else {
        instance.init().catch(console.error);
    }

})();
