package com.example.data

/**
 * The decisions behind background sync, kept free of Android so they can be unit-tested.
 *
 * WHY THESE RULES EXIST. The foreground drain parks a queued write as `failed_needs_attention`
 * after MAX_QUEUE_ATTEMPTS failed replays. That is safe there because it only runs when the app is
 * open and has just seen the server come back. A background job runs every ~15 minutes whether or
 * not the home server is up, so if its failures counted as attempts, one night with the server off
 * would park every pending write and stop retrying it. So the background job:
 *   - does nothing at all when nobody is logged in (the token is the only credential),
 *   - checks the server answers BEFORE touching the queue,
 *   - never counts a failure against an item's attempt budget (the foreground drain still does),
 *   - stops early when several replays in a row fail, rather than hammering a sick server.
 */
object BackgroundSyncPolicy {

    enum class Gate {
        /** No saved login: there is nothing to upload as, so do nothing and do not retry. */
        SKIP_NOT_LOGGED_IN,

        /** Phone has a network but the server did not answer: leave the queue untouched, try later. */
        RETRY_LATER,

        PROCEED,
    }

    /** Consecutive failed replays after which a background run gives up until its next turn. */
    const val MAX_CONSECUTIVE_FAILURES = 3

    /** Background retries of one upload job before leaving the rest to the periodic job. */
    const val MAX_UPLOAD_RETRIES = 5

    fun gate(hasLogin: Boolean, serverReachable: Boolean): Gate = when {
        !hasLogin -> Gate.SKIP_NOT_LOGGED_IN
        !serverReachable -> Gate.RETRY_LATER
        else -> Gate.PROCEED
    }

    fun shouldAbortRun(consecutiveFailures: Int): Boolean = consecutiveFailures >= MAX_CONSECUTIVE_FAILURES

    /**
     * The queue item as it should look after a failed replay.
     *
     * Foreground: count the attempt, and park it once the budget is spent (the pre-existing rule).
     * Background: unchanged — a background failure is not evidence the write is bad, it is far more
     * often a server that is down or a login that has lapsed.
     */
    fun afterFailedReplay(
        item: OfflineQueueItem,
        background: Boolean,
        nowMillis: Long,
        maxAttempts: Int,
    ): OfflineQueueItem {
        if (background) return item
        val next = item.attempts + 1
        return item.copy(
            attempts = next,
            lastAttemptAt = nowMillis,
            status = if (next >= maxAttempts) "failed_needs_attention" else "pending",
        )
    }

    /** Whether an upload job that still has writes left should ask WorkManager to run it again. */
    fun shouldRetryUpload(pendingLeft: Int, runAttemptCount: Int): Boolean =
        pendingLeft > 0 && runAttemptCount < MAX_UPLOAD_RETRIES
}
