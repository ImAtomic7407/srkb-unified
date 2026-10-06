package com.example

import com.example.data.PoolCallerResolution
import com.example.data.SessionCallAssignmentDetail
import com.example.data.SessionCampaign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Caller column on Pool Assignment must show a caller ONLY when a calling session is selected,
 * it has assigned numbers, and it is not closed.
 *
 * The fixtures below are the real live shape as of 2026-08-13 (Centre A, centre 2):
 *   campaign 43 — status "completed", 305 assignments  (the source of the wrongly-shown names)
 *   campaign 53 — status "active",     0 assignments
 * Both were read back from the running backend; campaign 43's every row carries a batch-member
 * mobile and a caller name, so a fix that resolved to null for everyone would look like it worked.
 * That is what `activeSessionWithAssignments_showsCaller` exists to catch.
 */
class PoolCallerResolutionTest {

    private fun assignment(mobile: String, caller: String, id: String = "1") =
        SessionCallAssignmentDetail(
            id = id,
            batchMemberId = id,
            batchMemberName = null,
            batchMemberMobile = mobile,
            callerId = "14186",
            callerName = caller,
            callStatus = "pending",
        )

    private fun campaign(status: String, assignments: List<SessionCallAssignmentDetail>) =
        SessionCampaign(
            id = "43",
            aeSessionId = "ae-1",
            batchId = "18",
            batchName = "BG",
            sessionName = "BG calling",
            scheduledAt = "2026-07-31T12:15:00Z",
            status = status,
            assignmentCount = assignments.size,
            assignments = emptyMap(),
            assignmentDetails = assignments,
        )

    // ── The reported bug ──────────────────────────────────────────────────────

    @Test
    fun noSessionSelected_showsNobody() {
        val live = PoolCallerResolution.liveCallerByMobile(null)
        assertTrue(live.isEmpty())
        assertNull(PoolCallerResolution.callerFor(live, "9835000001"))
    }

    @Test
    fun completedSession_showsNobody() {
        // This is campaign 43 — the exact state that was leaking names across the pool.
        val live = PoolCallerResolution.liveCallerByMobile(
            campaign("completed", listOf(assignment("9835000001", "caller_a"))),
        )
        assertTrue("a completed session must not show callers", live.isEmpty())
        assertNull(PoolCallerResolution.callerFor(live, "9835000001"))
    }

    @Test
    fun closedSession_showsNobody() {
        val live = PoolCallerResolution.liveCallerByMobile(
            campaign("closed", listOf(assignment("9835000001", "caller_a"))),
        )
        assertTrue("a closed session must not show callers", live.isEmpty())
    }

    @Test
    fun activeSessionWithNoAssignments_showsNobody() {
        // Campaign 53: active, but nobody has been assigned yet.
        val live = PoolCallerResolution.liveCallerByMobile(campaign("active", emptyList()))
        assertTrue(live.isEmpty())
    }

    // ── The positive control: the fix must not simply blank everything ────────

    @Test
    fun activeSessionWithAssignments_showsCaller() {
        val live = PoolCallerResolution.liveCallerByMobile(
            campaign(
                "active",
                listOf(
                    assignment("9835000001", "caller_a", "1"),
                    assignment("6299000013", "caller_b", "2"),
                ),
            ),
        )
        assertEquals(2, live.size)
        assertEquals("caller_a", PoolCallerResolution.callerFor(live, "9835000001"))
        assertEquals("caller_b", PoolCallerResolution.callerFor(live, "6299000013"))
        // Someone in the pool but not assigned in this session still shows nothing.
        assertNull(PoolCallerResolution.callerFor(live, "9000000001"))
    }

    // ── Mobile matching mirrors the backend's digits10() ──────────────────────

    @Test
    fun matchesAcrossDifferentStoredMobileFormats() {
        val live = PoolCallerResolution.liveCallerByMobile(
            campaign("active", listOf(assignment("+91 98350-00001", "caller_a"))),
        )
        assertEquals("caller_a", PoolCallerResolution.callerFor(live, "9835000001"))
        assertEquals("caller_a", PoolCallerResolution.callerFor(live, "919835000001"))
        assertEquals("caller_a", PoolCallerResolution.callerFor(live, "09835000001"))
    }

    @Test
    fun shortRosterNumbersStillMatchThemselves() {
        // batch_members really does hold 9-digit entries; takeLast(10) must not mangle them,
        // exactly as the backend's JS slice(-10) does not.
        val live = PoolCallerResolution.liveCallerByMobile(
            campaign("active", listOf(assignment("983588861", "caller_a"))),
        )
        assertEquals("caller_a", PoolCallerResolution.callerFor(live, "983588861"))
    }

    @Test
    fun blankMobileNeverMatches() {
        assertNull(PoolCallerResolution.matchKey(""))
        assertNull(PoolCallerResolution.matchKey(null))
        assertNull(PoolCallerResolution.matchKey("n/a"))
        val live = PoolCallerResolution.liveCallerByMobile(
            campaign("active", listOf(assignment("9835000001", "caller_a"))),
        )
        assertNull(PoolCallerResolution.callerFor(live, ""))
    }

    @Test
    fun assignmentRowWithNoCallerNameIsIgnored() {
        val live = PoolCallerResolution.liveCallerByMobile(
            campaign("active", listOf(assignment("9835000001", "  "))),
        )
        assertTrue(live.isEmpty())
    }

    // ── The counter must agree with the column ───────────────────────────────

    @Test
    fun assignedCountMatchesWhatTheColumnShows() {
        val live = PoolCallerResolution.liveCallerByMobile(
            campaign(
                "active",
                listOf(
                    assignment("9835000001", "caller_a", "1"),
                    assignment("6299000013", "caller_b", "2"),
                ),
            ),
        )
        val listed = listOf("9835000001", "6299000013", "9000000001", "9000000002")
        assertEquals(2, PoolCallerResolution.assignedCount(live, listed))

        // And with a completed session the counter reads 0, like the column.
        val dead = PoolCallerResolution.liveCallerByMobile(
            campaign("completed", listOf(assignment("9835000001", "caller_a"))),
        )
        assertEquals(0, PoolCallerResolution.assignedCount(dead, listed))
    }
}
