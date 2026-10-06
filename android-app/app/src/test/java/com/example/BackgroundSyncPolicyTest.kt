package com.example

import com.example.data.BackgroundSyncPolicy
import com.example.data.BackgroundSyncPolicy.Gate
import com.example.data.OfflineQueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSyncPolicyTest {

    private fun item(attempts: Int = 0, status: String = "pending") =
        OfflineQueueItem(id = "off_1", type = "mark_attendance", label = "x", payload = "p",
            attempts = attempts, status = status)

    @Test fun `nobody logged in means do nothing and do not retry`() {
        assertEquals(Gate.SKIP_NOT_LOGGED_IN, BackgroundSyncPolicy.gate(hasLogin = false, serverReachable = true))
        assertEquals(Gate.SKIP_NOT_LOGGED_IN, BackgroundSyncPolicy.gate(hasLogin = false, serverReachable = false))
    }

    @Test fun `server not answering means retry later without touching the queue`() {
        assertEquals(Gate.RETRY_LATER, BackgroundSyncPolicy.gate(hasLogin = true, serverReachable = false))
    }

    @Test fun `logged in and server up proceeds`() {
        assertEquals(Gate.PROCEED, BackgroundSyncPolicy.gate(hasLogin = true, serverReachable = true))
    }

    @Test fun `a background failure never spends the attempt budget`() {
        val before = item(attempts = 3)
        val after = BackgroundSyncPolicy.afterFailedReplay(before, background = true, nowMillis = 99L, maxAttempts = 4)
        assertEquals(before, after)
        assertEquals("pending", after.status)
    }

    @Test fun `a foreground failure still counts and parks at the budget`() {
        val counted = BackgroundSyncPolicy.afterFailedReplay(item(attempts = 1), false, 99L, 4)
        assertEquals(2, counted.attempts)
        assertEquals(99L, counted.lastAttemptAt)
        assertEquals("pending", counted.status)

        val parked = BackgroundSyncPolicy.afterFailedReplay(item(attempts = 3), false, 99L, 4)
        assertEquals(4, parked.attempts)
        assertEquals("failed_needs_attention", parked.status)
    }

    @Test fun `a run gives up after several failures in a row`() {
        assertFalse(BackgroundSyncPolicy.shouldAbortRun(2))
        assertTrue(BackgroundSyncPolicy.shouldAbortRun(BackgroundSyncPolicy.MAX_CONSECUTIVE_FAILURES))
    }

    @Test fun `upload job retries only while writes remain and the cap is not hit`() {
        assertFalse(BackgroundSyncPolicy.shouldRetryUpload(pendingLeft = 0, runAttemptCount = 0))
        assertTrue(BackgroundSyncPolicy.shouldRetryUpload(pendingLeft = 2, runAttemptCount = 0))
        assertFalse(BackgroundSyncPolicy.shouldRetryUpload(pendingLeft = 2,
            runAttemptCount = BackgroundSyncPolicy.MAX_UPLOAD_RETRIES))
    }
}
