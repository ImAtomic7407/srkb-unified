/**
 * Shared synchronization and filter-locking logic for AttendEase.
 * Satisfies security auditor requirement (P4.3) to avoid code duplication 
 * between the main dashboard and registerer view.
 */

window.AttendEaseSync = {
    lastGlobalSessionId: null,
    filtersLocked: false,
    isManualDateSelection: false,
    // Populated when more than one class is live at this centre and this device has not yet
    // chosen. Non-null means "the operator must pick"; the poll will not pick for them.
    pendingSessionChoice: null,

    setSessionState(sessionId, date = "") {
        window.AttendEaseSessionState = {
            id: sessionId || null,
            date: date || "",
        };
    },

    getBatchContext() {
        return window.AttendEaseBatchState || { id: null, name: "" };
    },

    /**
     * Periodically checks the server for the currently active session for this
     * client's centre. Passes centre_id so multi-centre setups stay isolated.
     * Overwrites local view only if the user hasn't manually locked their filters.
     *
     * IMPORTANT: /api/get_session answers "which session does this CENTRE have selected", and
     * there is exactly one such slot per centre and category. When a centre runs two classes
     * at once, that slot holds whichever device selected LAST — so a poll that adopts it can
     * silently move this device into the other class between one tick and the next.
     *
     * So the poll now asks /api/open_sessions first and refuses to adopt anything while more
     * than one class is live. It offers, it never steers. Once the operator picks a session,
     * filtersLocked already keeps it; this closes the window BEFORE they pick, which is where
     * the wrong session used to arrive on its own.
     */
    async syncWithGlobalSession(callbacks = {}) {
        if (this.filtersLocked) return;

        try {
            // BUG-1 fix: scope the session poll to the client's current centre
            const centreId = window.AttendEaseCentreState && window.AttendEaseCentreState.id
                ? window.AttendEaseCentreState.id
                : '';

            // Do not auto-adopt a session while the answer is ambiguous. A failure here must
            // not block the poll, so it falls through to the previous behaviour.
            try {
                const openUrl = centreId
                    ? `/api/open_sessions?centre_id=${encodeURIComponent(centreId)}`
                    : '/api/open_sessions';
                const openRes = await fetch(openUrl);
                if (openRes.ok) {
                    const open = await openRes.json();
                    if (open && open.ambiguous) {
                        this.pendingSessionChoice = open.sessions || [];
                        if (callbacks.onAmbiguousSessions) {
                            callbacks.onAmbiguousSessions(open.sessions || []);
                        }
                        return;   // leave this device's view exactly as the operator left it
                    }
                    this.pendingSessionChoice = null;
                }
            } catch (probeErr) {
                console.warn('open_sessions probe failed; falling back to get_session', probeErr);
            }

            const url = centreId ? `/api/get_session?centre_id=${encodeURIComponent(centreId)}` : '/api/get_session';
            const res = await fetch(url);
            const data = await res.json();
            const sel = document.getElementById('session-select');
            const dateInput = document.getElementById('attendance-date');
            const batchState = this.getBatchContext();

            if (!data.session_id) {
                this.lastGlobalSessionId = null;
                this.setSessionState(null, "");
                if (sel && sel.value) {
                    sel.value = "";
                }
                if (dateInput) {
                    dateInput.value = "";
                    dateInput.disabled = true;
                    dateInput.classList.add('opacity-50', 'cursor-not-allowed');
                }
                if (data.batch_id && window.setSelectedBatchFromSync) {
                    window.setSelectedBatchFromSync(data.batch_id, data.batch_name || "");
                }
                if (callbacks.onSessionSynced) {
                    callbacks.onSessionSynced(null);
                }
                return;
            }

            if (data.session_id && (data.session_id !== this.lastGlobalSessionId || (dateInput && data.date !== dateInput.value))) {
                this.lastGlobalSessionId = data.session_id;
                this.setSessionState(data.session_id, data.date || "");
                
                if (data.batch_id && window.setSelectedBatchFromSync) {
                    window.setSelectedBatchFromSync(data.batch_id, data.batch_name || "");
                }

                if (sel && sel.value !== data.session_id) {
                    sel.value = data.session_id;
                }
                
                if (dateInput) {
                    dateInput.disabled = false;
                    dateInput.classList.remove('opacity-50', 'cursor-not-allowed');
                    if (data.date) {
                        dateInput.value = data.date;
                    }
                }
                
                if (callbacks.onSessionSynced) {
                    callbacks.onSessionSynced(data.session_id);
                }
            }
        } catch(e) {
            console.error("Sync error:", e);
        }
    },

    async onBatchSelected(batchId, batchName, callbacks = {}) {
        this.filtersLocked = false;
        this.lastGlobalSessionId = null;
        this.setSessionState(null, "");

        const sel = document.getElementById('session-select');
        const dateInput = document.getElementById('attendance-date');
        if (sel) {
            sel.value = "";
        }
        if (dateInput) {
            dateInput.value = "";
            dateInput.disabled = true;
            dateInput.classList.add('opacity-50', 'cursor-not-allowed');
        }

        try {
            const res = await fetch('/api/set_session', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({
                    batch_id: batchId,
                    batch_name: batchName,
                    session_id: ""
                })
            });
            if (res.ok && callbacks.onSuccess) {
                callbacks.onSuccess(batchId, batchName);
            }
        } catch (e) {
            console.error("Batch update error:", e);
        }
    },

    /**
     * Handles manual session selection from the dropdown. 
     * Sets the local filter lock.
     */
    async onSessionSelected(sessionId, callbacks = {}) {
        const dateInput = document.getElementById('attendance-date');
        
        if (sessionId) {
            this.filtersLocked = true;
            this.setSessionState(sessionId, dateInput ? dateInput.value : "");
            if (dateInput) {
                dateInput.disabled = false;
                dateInput.classList.remove('opacity-50', 'cursor-not-allowed');
                if (!dateInput.value) {
                    const today = new Date();
                    const offset = today.getTimezoneOffset() * 60000;
                    dateInput.value = (new Date(today - offset)).toISOString().split('T')[0];
                }
            }
        } else {
            this.filtersLocked = false;
            this.lastGlobalSessionId = null;
            this.setSessionState(null, "");
            if (dateInput) {
                dateInput.value = "";
                dateInput.disabled = true;
                dateInput.classList.add('opacity-50', 'cursor-not-allowed');
            }
            try {
                const batchState = this.getBatchContext();
                await fetch('/api/set_session', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({
                        session_id: "",
                        batch_id: batchState.id || null,
                        batch_name: batchState.name || null
                    })
                });
            } catch(e) {
                console.error("Session clear error:", e);
            }
            if (callbacks.onSuccess) {
                callbacks.onSuccess("");
            }
            return;
        }

        try {
            const batchState = this.getBatchContext();
            const res = await fetch('/api/set_session', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ 
                    session_id: sessionId,
                    date: dateInput ? dateInput.value : null,
                    batch_id: batchState.id || null,
                    batch_name: batchState.name || null
                })
            });
            if (res.ok && callbacks.onSuccess) {
                callbacks.onSuccess(sessionId);
            }
        } catch(e) {
            console.error("Session update error:", e);
        }
    },

    /**
     * Explicitly sets the manual date selection flag and locks filters.
     * Notifies the server about the new selected date.
     */
    async onDateSelected(date) {
        this.isManualDateSelection = true;
        this.filtersLocked = true;
        
        const sel = document.getElementById('session-select');
        if (sel && sel.value) {
            try {
                this.setSessionState(sel.value, date);
                const batchState = this.getBatchContext();
                await fetch('/api/set_session', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({ 
                        session_id: sel.value,
                        date: date,
                        batch_id: batchState.id || null,
                        batch_name: batchState.name || null
                    })
                });
            } catch(e) {
                console.error("Date update error:", e);
            }
        }
    }
};
