/**
 * Offline Queue Management
 * Handles attendance marking when offline and syncing when online
 */

class OfflineQueue {
  constructor() {
    this.db = null;
    this.isOnline = navigator.onLine;
    this.initDB();
    this.setupEventListeners();
  }

  /**
   * Initialize IndexedDB for offline queue storage
   */
  async initDB() {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open('AttendanceDB', 1);

      request.onerror = () => {
        console.error('[OfflineQueue] Failed to open DB:', request.error);
        reject(request.error);
      };

      request.onsuccess = () => {
        this.db = request.result;
        console.log('[OfflineQueue] DB opened successfully');
        resolve(this.db);
      };

      request.onupgradeneeded = (event) => {
        const db = event.target.result;
        if (!db.objectStoreNames.contains('attendance')) {
          const store = db.createObjectStore('attendance', { keyPath: 'client_id' });
          store.createIndex('synced', 'synced', { unique: false });
          store.createIndex('timestamp', 'timestamp', { unique: false });
          console.log('[OfflineQueue] Created attendance object store');
        }
      };
    });
  }

  /**
   * Queue attendance record when offline
   */
  async queueAttendance(record) {
    if (!this.db) {
      console.error('[OfflineQueue] DB not initialized');
      return null;
    }

    const attendanceRecord = {
      client_id: this.generateUUID(),
      ...record,
      timestamp: new Date().toISOString(),
      synced: false,
      attempts: 0
    };

    return new Promise((resolve, reject) => {
      const transaction = this.db.transaction(['attendance'], 'readwrite');
      const store = transaction.objectStore('attendance');
      const request = store.add(attendanceRecord);

      request.onerror = () => {
        console.error('[OfflineQueue] Failed to queue record:', request.error);
        reject(request.error);
      };

      request.onsuccess = () => {
        console.log('[OfflineQueue] Record queued:', attendanceRecord.client_id);
        resolve(attendanceRecord);
      };
    });
  }

  /**
   * Get all unsynced records
   */
  async getUnsyncedRecords() {
    if (!this.db) return [];

    return new Promise((resolve, reject) => {
      const transaction = this.db.transaction(['attendance'], 'readonly');
      const store = transaction.objectStore('attendance');
      const index = store.index('synced');
      const request = index.getAll(false);

      request.onerror = () => reject(request.error);
      request.onsuccess = () => {
        console.log('[OfflineQueue] Found', request.result.length, 'unsynced records');
        resolve(request.result);
      };
    });
  }

  /**
   * Mark record as synced
   */
  async markSynced(client_id, serverResponse) {
    if (!this.db) return;

    return new Promise((resolve, reject) => {
      const transaction = this.db.transaction(['attendance'], 'readwrite');
      const store = transaction.objectStore('attendance');
      const getRequest = store.get(client_id);

      getRequest.onsuccess = () => {
        const record = getRequest.result;
        if (record) {
          record.synced = true;
          record.synced_at = new Date().toISOString();
          record.server_response = serverResponse;

          const updateRequest = store.put(record);
          updateRequest.onerror = () => reject(updateRequest.error);
          updateRequest.onsuccess = () => {
            console.log('[OfflineQueue] Marked synced:', client_id);
            resolve(record);
          };
        }
      };

      getRequest.onerror = () => reject(getRequest.error);
    });
  }

  /**
   * Sync offline queue to server
   */
  async syncToServer() {
    const records = await this.getUnsyncedRecords();
    if (records.length === 0) {
      console.log('[OfflineQueue] No records to sync');
      return { success: 0, failed: 0, duplicates: 0 };
    }

    console.log('[OfflineQueue] Syncing', records.length, 'records to server');

    try {
      const response = await fetch('/api/sync/offline_queue', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ records })
      });

      if (!response.ok) {
        throw new Error(`Server returned ${response.status}`);
      }

      const result = await response.json();

      // Mark committed records as synced
      for (const client_id of result.committed) {
        await this.markSynced(client_id, { status: 'synced' });
      }

      console.log('[OfflineQueue] Sync complete:', result);
      return {
        success: result.committed.length,
        failed: result.failed.length,
        duplicates: result.duplicates.length
      };
    } catch (error) {
      console.error('[OfflineQueue] Sync failed:', error);
      throw error;
    }
  }

  /**
   * Setup online/offline event listeners
   */
  setupEventListeners() {
    window.addEventListener('online', () => {
      console.log('[OfflineQueue] Back online!');
      this.isOnline = true;
      this.handleOnline();
    });

    window.addEventListener('offline', () => {
      console.log('[OfflineQueue] Gone offline');
      this.isOnline = false;
    });
  }

  /**
   * Handle coming back online
   */
  async handleOnline() {
    const unsyncedCount = (await this.getUnsyncedRecords()).length;

    if (unsyncedCount > 0) {
      console.log('[OfflineQueue] Found', unsyncedCount, 'records to sync');
      try {
        const result = await this.syncToServer();
        const message = `${result.success} records synced!`;
        console.log('[OfflineQueue]', message);

        // Refresh the app
        window.location.reload();
      } catch (error) {
        console.error('[OfflineQueue] Sync failed:', error);
        // Will retry on next online event
      }
    }
  }

  /**
   * Generate UUID for client-side records
   */
  generateUUID() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
      const r = Math.random() * 16 | 0;
      const v = c === 'x' ? r : (r & 0x3 | 0x8);
      return v.toString(16);
    });
  }

  /**
   * Check if offline
   */
  isOffline() {
    return !this.isOnline;
  }

  /**
   * Get queue stats
   */
  async getStats() {
    const total = await this.getUnsyncedRecords();
    return {
      totalQueued: total.length,
      isOnline: this.isOnline
    };
  }
}

// Initialize on load
const offlineQueue = new OfflineQueue();
console.log('[OfflineQueue] Initialized');
