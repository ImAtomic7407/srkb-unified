package com.example.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Background sync, deliberately small. It does exactly two things and nothing else:
 *
 *   UPLOAD   the durable offline write queue (registrations, attendance, call responses) —
 *            started as soon as a write joins the queue and the phone has a network, and
 *            re-checked every ~15 minutes.
 *   DOWNLOAD (periodic job only) the centre's session/roster state, which is gated on a ~70 byte
 *            checksum and pulls only when it changed, plus the caller's calling list.
 *
 * No foreground service, no notification, no wake locks of its own, no boot receiver of its own.
 * Android decides when a job really runs; on Doze or aggressive battery savers (some Xiaomi/Oppo
 * phones) it can be delayed. It is a safety net for work the app could not finish, not "instant".
 */
object BackgroundSync {
    private const val UNIQUE_UPLOAD = "srkb-upload-now"
    private const val UNIQUE_PERIODIC = "srkb-sync-periodic"
    internal const val KEY_MODE = "mode"
    internal const val MODE_UPLOAD = "upload"
    internal const val MODE_PERIODIC = "periodic"

    private val needsNetwork: Constraints
        get() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** A write just joined the queue: upload it the moment the phone has a network. Idempotent. */
    fun requestUpload(context: Context) {
        runCatching {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(needsNetwork)
                .setInputData(workDataOf(KEY_MODE to MODE_UPLOAD))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_UPLOAD, ExistingWorkPolicy.KEEP, req)
        }
    }

    /** The slow heartbeat: upload leftovers and refresh what the screens read. Idempotent. */
    fun ensurePeriodic(context: Context) {
        runCatching {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(needsNetwork)
                .setInputData(workDataOf(KEY_MODE to MODE_PERIODIC))
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    /** Logout: the login that these jobs run as is gone, so stop them. */
    fun cancelAll(context: Context) {
        runCatching {
            val wm = WorkManager.getInstance(context.applicationContext)
            wm.cancelUniqueWork(UNIQUE_UPLOAD)
            wm.cancelUniqueWork(UNIQUE_PERIODIC)
        }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // A cold process: nothing is loaded yet. Restore only the session and the queue — NOT the
        // 1-second connectivity monitor, which the foreground app owns.
        AppDatabase.ensureRestored(applicationContext)
        val periodic = inputData.getString(BackgroundSync.KEY_MODE) == BackgroundSync.MODE_PERIODIC

        if (!AppDatabase.hasSavedLogin()) return Result.success()
        // WorkManager's network constraint means the phone has a network, not that the home server
        // (behind a tunnel) is up. Ask before touching anything.
        val reachable = AppDatabase.probeBackendHealth()
        when (BackgroundSyncPolicy.gate(hasLogin = true, serverReachable = reachable)) {
            BackgroundSyncPolicy.Gate.SKIP_NOT_LOGGED_IN -> return Result.success()
            BackgroundSyncPolicy.Gate.RETRY_LATER -> return if (periodic) Result.success() else Result.retry()
            BackgroundSyncPolicy.Gate.PROCEED -> Unit
        }

        AppDatabase.drainOfflineQueue(background = true, shouldStop = { isStopped })
        if (periodic && !isStopped) AppDatabase.backgroundPull()

        if (periodic) return Result.success()
        return if (BackgroundSyncPolicy.shouldRetryUpload(AppDatabase.pendingQueueCount(), runAttemptCount))
            Result.retry() else Result.success()
    }
}
