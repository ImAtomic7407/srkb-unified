package com.example

import com.example.data.PoolCallerResolution
import com.example.data.PoolCounts
import com.example.data.SessionCallAssignmentDetail
import com.example.data.SessionCampaign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The figures above the pool table must describe something the admin can point at.
 *
 * Live numbers on 2026-08-13 (Centre A, centre 2) used throughout:
 *   batch 18 "BG"  — 463 live members (571 ever added, 108 struck off on 08-08)
 *   2026-08-13     — 99 people newly registered, listed by the New Registered tab
 *   campaign 53    — "active", batch 18, 0 assignments
 * The bug: the New Registered tab listed the 99 and headlined 463.
 */
class PoolCountsTest {

    private fun assignment(mobile: String, caller: String, id: String) =
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
            id = "53",
            aeSessionId = "ae-1",
            batchId = "18",
            batchName = "BG",
            sessionName = "2026-08-14 CTRA BG",
            scheduledAt = "2026-08-14T09:00:00Z",
            status = status,
            assignmentCount = assignments.size,
            assignments = emptyMap(),
            assignmentDetails = assignments,
        )

    /** n distinct 10-digit mobiles, the shape the pool rows actually carry. */
    private fun mobiles(n: Int) = (1..n).map { "98358%05d".format(it) }

    private val ninetyNine = mobiles(99)

    // ── The reported bug ──────────────────────────────────────────────────────

    @Test
    fun newRegisteredTab_countsTheListNotTheBatchRoster() {
        // A calling session on batch 18 is selected at the top of the page, exactly as it was.
        val counts = PoolCounts.of(
            activeTab = PoolCounts.TAB_NEW_REGISTERED,
            visibleMobiles = ninetyNine,
            liveCallers = emptyMap(),
            sessionRosterCount = 463,
            sessionAssignedCount = 0,
        )
        assertEquals(99, counts.total)          // was 463
        assertEquals(99, counts.showing)
        assertEquals("Registered", counts.totalLabel)
        assertFalse(counts.describesSession)
    }

    @Test
    fun headlineNeverDisagreesWithTheRowsOnScreen() {
        // Whatever the tab and whatever is selected, `showing` is the rendered row count.
        for (tab in 0..3) {
            val counts = PoolCounts.of(tab, ninetyNine.take(7), emptyMap(), 463, 305)
            assertEquals("tab $tab", 7, counts.showing)
        }
    }

    // ── Positive control: the assignment tabs must KEEP the session figures ────
    // This is the 126-reported-as-105 fix. A change that made every tab count the visible
    // rows would pass every test above and silently undo it, so it has to fail here.

    @Test
    fun assignmentTabs_keepTheSessionsOwnFigures() {
        for (tab in listOf(0, 1, 2)) {
            val counts = PoolCounts.of(
                activeTab = tab,
                visibleMobiles = mobiles(105),  // the pool list drops follow-up / invalid
                liveCallers = emptyMap(),
                sessionRosterCount = 463,
                sessionAssignedCount = 126,             // the session's own total
            )
            assertEquals("tab $tab", 463, counts.total)
            assertEquals("tab $tab", 126, counts.assigned)
            assertEquals("tab $tab", 337, counts.pending)
            assertEquals("tab $tab", "In batch", counts.totalLabel)
            assertTrue("tab $tab", counts.describesSession)
        }
    }

    @Test
    fun sessionModeStillReportsHowManyRowsAreListed() {
        // The gap between 463 and the list is what the caption explains — it must be available.
        val counts = PoolCounts.of(0, ninetyNine, emptyMap(), 463, 126)
        assertEquals(463, counts.total)
        assertEquals(99, counts.showing)
    }

    // ── Assigned agrees with the Caller column ────────────────────────────────

    @Test
    fun newRegisteredTab_assignedCountsOnlyPeopleWithALiveCaller() {
        val live = PoolCallerResolution.liveCallerByMobile(
            campaign("active", ninetyNine.take(3).mapIndexed { i, m -> assignment(m, "Ravi", "$i") })
        )
        val counts = PoolCounts.of(PoolCounts.TAB_NEW_REGISTERED, ninetyNine, live, 463, 3)
        assertEquals(99, counts.total)
        assertEquals(3, counts.assigned)     // the 3 rows whose Caller column shows "Ravi"
        assertEquals(96, counts.pending)
    }

    @Test
    fun completedSession_leavesNobodyAssignedOnTheNewRegisteredTab() {
        // liveCallers is empty for a completed session, so the headline matches the blank column.
        val live = PoolCallerResolution.liveCallerByMobile(
            campaign("completed", ninetyNine.take(50).mapIndexed { i, m -> assignment(m, "Ravi", "$i") })
        )
        val counts = PoolCounts.of(PoolCounts.TAB_NEW_REGISTERED, ninetyNine, live, 463, 50)
        assertEquals(0, counts.assigned)
        assertEquals(99, counts.pending)
    }

    // ── The lower batch/session filter re-points the figures at the list ──────
    // Reported 2026-08-15: "the number above the sheet are not changing according to the sheet in
    // case of this session selection". Live case — campaign 54 (active, batch 18) selected at the
    // top, lower filter set to session 01-08-26: the sheet showed 21 rows and the tile read 463.

    @Test
    fun lowerFilterActive_figuresFollowTheFilteredList() {
        val counts = PoolCounts.of(
            activeTab = 0,
            visibleMobiles = mobiles(21),
            liveCallers = emptyMap(),
            sessionRosterCount = 463,
            sessionAssignedCount = 131,
            listIsFiltered = true,
        )
        assertEquals(21, counts.total)          // was 463
        assertEquals(21, counts.showing)
        assertEquals("Filtered", counts.totalLabel)
        assertFalse(counts.describesSession)
    }

    @Test
    fun clearingTheFilterRestoresTheSessionsFigures() {
        // Same inputs, filter off — the 126-reported-as-105 behaviour must come straight back.
        val counts = PoolCounts.of(0, mobiles(21), emptyMap(), 463, 131, listIsFiltered = false)
        assertEquals(463, counts.total)
        assertEquals(131, counts.assigned)
        assertTrue(counts.describesSession)
    }

    @Test
    fun filteredAssignedCountsOnlyTheFilteredRowsWithALiveCaller() {
        // Of the 21 filtered rows, 4 are assigned in the live session → "4 assigned, 17 to assign".
        val visible = mobiles(21)
        val live = PoolCallerResolution.liveCallerByMobile(
            campaign("active", visible.take(4).mapIndexed { i, m -> assignment(m, "Ravi", "$i") })
        )
        val counts = PoolCounts.of(0, visible, live, 463, 131, listIsFiltered = true)
        assertEquals(21, counts.total)
        assertEquals(4, counts.assigned)
        assertEquals(17, counts.pending)
    }

    @Test
    fun filterOnTheNewRegisteredTabChangesNothingItAlreadyCountedTheList() {
        val a = PoolCounts.of(PoolCounts.TAB_NEW_REGISTERED, ninetyNine, emptyMap(), 463, 0, listIsFiltered = true)
        val b = PoolCounts.of(PoolCounts.TAB_NEW_REGISTERED, ninetyNine, emptyMap(), 463, 0, listIsFiltered = false)
        assertEquals(99, a.total)
        assertEquals(b.total, a.total)
    }

    // ── No session selected ───────────────────────────────────────────────────

    @Test
    fun noSessionSelected_poolTabsCountThePool() {
        val counts = PoolCounts.of(0, mobiles(471), emptyMap(), null, null)
        assertEquals(471, counts.total)
        assertEquals(0, counts.assigned)
        assertEquals("In pool", counts.totalLabel)
        assertFalse(counts.describesSession)
    }

    @Test
    fun emptyListReadsAsZeroNotAsANegativePending() {
        val counts = PoolCounts.of(PoolCounts.TAB_NEW_REGISTERED, emptyList(), emptyMap(), 463, 305)
        assertEquals(0, counts.total)
        assertEquals(0, counts.assigned)
        assertEquals(0, counts.pending)
    }

    @Test
    fun pendingNeverGoesNegativeWhenTheRosterLagsTheAssignments() {
        val counts = PoolCounts.of(0, emptyList(), emptyMap(), 10, 40)
        assertEquals(0, counts.pending)
    }
}
