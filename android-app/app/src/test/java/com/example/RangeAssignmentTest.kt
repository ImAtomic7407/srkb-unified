package com.example

import com.example.data.BatchMemberInfo
import com.example.data.RangeAssignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Assign rows 7–15 to Adidev" must hand Adidev rows 7–15 OF THE SHEET THE ADMIN IS LOOKING AT.
 *
 * THE BUG THIS EXISTS TO PREVENT (reported 2026-08-15, live data loss):
 * The pool table renders `currentItems` — the registration pool after the lower batch/session
 * filter — and numbers it 1..N in the "#" column. The range-assign block underneath was handed
 * `sessionMembers.map { it.id }`, the selected campaign's ENTIRE unfiltered batch roster (463
 * people for batch 18), in the backend's own order. The two lists share neither length, contents,
 * nor ordering.
 *
 * So an admin who filtered to one session's 15 attendees and typed 7–15 did not assign the nine
 * people numbered 7–15 on screen. They assigned `batchRoster[6..14]` — nine unrelated people from
 * the standing roster. Reported as "adidev got 9 callings of [someone else's list] instead of
 * getting from the current table". The assignment succeeded, so nothing looked wrong until the
 * caller opened their queue.
 *
 * Every test here pins the sheet — not the roster — as the thing row numbers count.
 */
class RangeAssignmentTest {

    /** A batch roster the backend hands back in ITS order, which is not the sheet's order. */
    private fun member(id: String, mobile: String) =
        BatchMemberInfo(id = id, mobile = mobile, name = "M$id", batchId = 18, joinedAt = "2026-05-15")

    private fun mobile(n: Int) = "98358%05d".format(n)

    /**
     * The live shape of the report: a 463-person batch roster, filtered down to the 15 people who
     * attended one session. The 15 sit at scattered positions in the roster.
     */
    private val roster: List<BatchMemberInfo> = (1..463).map { member(id = "bm$it", mobile = mobile(it)) }

    /** The 15 rows on screen, in the pool's order — deliberately NOT the roster's order. */
    private val visible: List<String> = listOf(200, 41, 383, 7, 118, 455, 62, 291, 15, 340, 99, 412, 8, 176, 233)
        .map { mobile(it) }

    private fun targets(visibleMobiles: List<String> = visible, r: List<BatchMemberInfo> = roster) =
        RangeAssignment.targetIdsForVisibleRows(visibleMobiles, r)

    private fun range(from: Int?, to: Int?, caller: String) =
        RangeAssignment.Range(from = from, to = to, callerId = caller)

    // ── The reported bug ──────────────────────────────────────────────────────

    @Test
    fun rowsSevenToFifteenAreTheSheetsRowsSevenToFifteen() {
        val plan = RangeAssignment.plan(listOf(range(7, 15, "adidev")), targets())

        // The nine people the admin could see at positions 7..15, in that order.
        val expected = visible.subList(6, 15).map { m -> roster.first { it.mobile == m }.id }
        assertEquals(expected, plan.byCaller["adidev"])
        assertEquals(9, plan.assignedCount)

        // And emphatically NOT roster[6..14], which is what shipped.
        val whatTheBugAssigned = roster.subList(6, 15).map { it.id }
        assertTrue(
            "assigned the raw batch roster instead of the sheet",
            plan.byCaller["adidev"] != whatTheBugAssigned
        )
    }

    @Test
    fun aFifteenRowSheetIsFifteenRowsLongNoMatterHowBigTheRosterIs() {
        // The roster has 463 people; the sheet has 15. Row 20 does not exist.
        val plan = RangeAssignment.plan(listOf(range(1, 20, "adidev")), targets())
        assertEquals(15, plan.assignedCount)
        assertEquals(5, plan.outOfRangeRows)
    }

    // ── Positional integrity: a hole must not shift everyone up ───────────────

    @Test
    fun aRowWithNoBatchMemberKeepsItsNumberAndOnlyItselfIsSkipped() {
        // Row 8 is someone in the pool who is not in this batch — pool rows are registrations and
        // need not be batch members at all. Rows 9..15 must still mean rows 9..15.
        val withStranger = visible.toMutableList().also { it[7] = "9999000014" }
        val plan = RangeAssignment.plan(listOf(range(7, 15, "adidev")), targets(withStranger))

        assertEquals(8, plan.assignedCount)
        assertEquals(1, plan.unassignableRows)

        val expected = (listOf(6) + (8..14)).map { i -> roster.first { it.mobile == withStranger[i] }.id }
        assertEquals(expected, plan.byCaller["adidev"])
    }

    @Test
    fun targetsAreOnePerVisibleRowInOrderSoRowNMapsToIndexNMinusOne() {
        val t = targets()
        assertEquals(visible.size, t.size)
        visible.forEachIndexed { i, m ->
            assertEquals("row ${i + 1}", roster.first { it.mobile == m }.id, t[i])
        }
    }

    @Test
    fun mobilesMatchAcrossFormattingDifferencesTheTwoSidesReallyHave() {
        // batch_members holds "+91 98358-00007" shapes; the pool holds bare digits. Same person.
        val messyRoster = listOf(member("bm7", "+91 98358-00007"), member("bm8", "098358 00008"))
        val t = RangeAssignment.targetIdsForVisibleRows(listOf(mobile(7), mobile(8)), messyRoster)
        assertEquals(listOf("bm7", "bm8"), t)
    }

    // ── Ranges the admin can actually type ────────────────────────────────────

    @Test
    fun severalRangesEachGoToTheirOwnCaller() {
        val plan = RangeAssignment.plan(
            listOf(range(1, 6, "navneet"), range(7, 15, "adidev")),
            targets()
        )
        assertEquals(6, plan.byCaller["navneet"]?.size)
        assertEquals(9, plan.byCaller["adidev"]?.size)
        assertEquals(15, plan.assignedCount)
    }

    @Test
    fun aPersonCaughtByTwoRangesGoesToTheLastCallerNamedNotBoth() {
        // The backend upserts on (campaign, batch member), so sending one person twice silently
        // resolves to whichever write lands last. Decide it here instead, so the count we report
        // to the admin is the count that will exist.
        val plan = RangeAssignment.plan(
            listOf(range(1, 10, "navneet"), range(8, 15, "adidev")),
            targets()
        )
        assertEquals(7, plan.byCaller["navneet"]?.size)   // rows 1–7 survive
        assertEquals(8, plan.byCaller["adidev"]?.size)    // rows 8–15
        assertEquals(15, plan.assignedCount)              // nobody counted twice
        val all = plan.byCaller.values.flatten()
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun aReversedOrHalfFilledRangeIsIgnoredRatherThanGuessedAt() {
        assertEquals(0, RangeAssignment.plan(listOf(range(15, 7, "adidev")), targets()).assignedCount)
        assertEquals(0, RangeAssignment.plan(listOf(range(null, 15, "adidev")), targets()).assignedCount)
        assertEquals(0, RangeAssignment.plan(listOf(range(7, null, "adidev")), targets()).assignedCount)
        assertEquals(0, RangeAssignment.plan(listOf(range(7, 15, "")), targets()).assignedCount)
    }

    @Test
    fun rowZeroOrBelowIsClampedToTheFirstRowNotWrappedToTheEnd() {
        val plan = RangeAssignment.plan(listOf(range(0, 2, "adidev")), targets())
        assertEquals(2, plan.byCaller["adidev"]?.size)
        assertEquals(roster.first { it.mobile == visible[0] }.id, plan.byCaller["adidev"]?.first())
    }

    @Test
    fun anEmptySheetAssignsNobodyRatherThanThrowing() {
        val plan = RangeAssignment.plan(listOf(range(1, 5, "adidev")), emptyList())
        assertEquals(0, plan.assignedCount)
        assertTrue(plan.byCaller.isEmpty())
    }

    // ── What the admin is told ────────────────────────────────────────────────

    @Test
    fun theRangeActuallyCoveredIsReportedSoTheConfirmationCanNameIt() {
        // Feature ask: the allotment should read "rows 7–15", not just "9 assigned".
        val plan = RangeAssignment.plan(listOf(range(7, 15, "adidev")), targets())
        assertEquals(7, plan.coveredFrom)
        assertEquals(15, plan.coveredTo)
    }

    // ── Cross-batch allotment (reported 2026-08-23, Centre A, mid-session) ──────────────
    //
    // The screen lets an admin gather people from several batches into ONE calling session — that
    // is what the two dropdown pairs are for, and the backend has always accepted it
    // (assignSessionCallers takes any batch_member_id and does not check the campaign's batch).
    // But the screen resolved every visible row through the CAMPAIGN'S OWN roster only, so anyone
    // from another batch had no id, counted as unassignable, and was refused with "not in <batch>".
    //
    // The fix passes `sessionMembers + centreRoster`. These tests pin both halves of it.

    /** A member of some OTHER batch, e.g. batch 21 "Park Registration" beside the session's 20. */
    private fun otherBatchMember(id: String, mobile: String) =
        BatchMemberInfo(id = id, mobile = mobile, name = "O$id", batchId = 21, joinedAt = "2026-08-23")

    @Test
    fun aRowFromAnotherBatchIsAllottableOnceTheCentreRosterIsIncluded() {
        // Two people on screen: one in the session's batch, one only in another batch.
        val ownBatch = member(id = "bm7", mobile = mobile(7))
        val parkReg = otherBatchMember(id = "bm2853", mobile = "9304000015")
        val visibleRows = listOf(ownBatch.mobile, parkReg.mobile)

        // BEFORE the fix — the campaign's roster alone. The park-registration row is a dead slot.
        val sessionOnly = RangeAssignment.targetIdsForVisibleRows(visibleRows, listOf(ownBatch))
        assertEquals(listOf("bm7", null), sessionOnly)

        // AFTER — session roster first, then the centre-wide one.
        val widened = RangeAssignment.targetIdsForVisibleRows(visibleRows, listOf(ownBatch) + listOf(parkReg))
        assertEquals(listOf("bm7", "bm2853"), widened)

        val plan = RangeAssignment.plan(listOf(range(1, 2, "adidev")), widened)
        assertEquals(listOf("bm7", "bm2853"), plan.byCaller["adidev"])
        assertEquals(0, plan.unassignableRows)
    }

    @Test
    fun someoneInBothBatchesIsAllottedThroughTheSessionsOwnBatch() {
        // ORDER IS LOAD-BEARING. targetIdsForVisibleRows keeps the FIRST match per mobile, and the
        // centre roster necessarily repeats everyone already in the session's batch. Putting the
        // session's roster first is what keeps every previously-assignable row resolving to exactly
        // the id it resolved to before — and completion/eviction is scoped to campaign.batchId, so
        // the membership handed over must be the one in THAT batch.
        val shared = mobile(9)
        val inSessionBatch = member(id = "bm9", mobile = shared)             // batch 18
        val sameManOtherBatch = otherBatchMember(id = "bm9999", mobile = shared) // batch 21

        val ids = RangeAssignment.targetIdsForVisibleRows(
            listOf(shared),
            listOf(inSessionBatch) + listOf(sameManOtherBatch),
        )
        assertEquals(listOf("bm9"), ids)
    }

    @Test
    fun aPersonInNoBatchAtAllStillKeepsTheirRowAndOnlyTheyAreSkipped() {
        // Widening the roster does NOT invent a membership. Someone with no batch_members row
        // anywhere (5 such people at Centre A on 2026-08-23) has no id to allot and must still
        // hold their slot, or every row beneath them shifts up — the original bug, in miniature.
        val a = member(id = "bmA", mobile = mobile(1))
        val c = otherBatchMember(id = "bmC", mobile = mobile(3))
        val ids = RangeAssignment.targetIdsForVisibleRows(
            listOf(a.mobile, "9000000000", c.mobile),
            listOf(a) + listOf(c),
        )
        assertEquals(listOf("bmA", null, "bmC"), ids)

        val plan = RangeAssignment.plan(listOf(range(1, 3, "adidev")), ids)
        assertEquals(listOf("bmA", "bmC"), plan.byCaller["adidev"])
        assertEquals(1, plan.unassignableRows)
    }

    // ── One number, one place (2026-09-24) ─────────────────────────────────────────────────────
    // A person already in a follow-up, or waiting for a call in another session, is listed on the
    // sheet with their state but must not be allotted. Their row keeps its number.

    @Test
    fun `busy rows are skipped but keep their numbers`() {
        val targets = listOf("a", "b", "c", "d", "e")
        val plan = RangeAssignment.plan(
            listOf(RangeAssignment.Range(1, 5, "adidev")),
            targets,
            busyIndices = setOf(1, 3),
        )
        assertEquals(listOf("a", "c", "e"), plan.byCaller["adidev"])
        assertEquals(2, plan.busyRows)
        assertEquals(0, plan.unassignableRows)
        // The label names the rows actually allotted, not the busy ones in between.
        assertEquals("1, 3, 5", plan.rowsByCaller["adidev"])
        assertEquals(1, plan.coveredFrom)
        assertEquals(5, plan.coveredTo)
    }

    @Test
    fun `a busy row is counted as busy, not as missing from the batch`() {
        // Row 2 is both busy AND has no batch member: the admin needs the reason that applies
        // first — they are held somewhere else — not "add them to a batch".
        val plan = RangeAssignment.plan(
            listOf(RangeAssignment.Range(1, 3, "adidev")),
            listOf("a", null, null),
            busyIndices = setOf(1),
        )
        assertEquals(1, plan.busyRows)
        assertEquals(1, plan.unassignableRows)
        assertEquals(listOf("a"), plan.byCaller["adidev"])
    }

    @Test
    fun `busy rows outside every range change nothing`() {
        val plan = RangeAssignment.plan(
            listOf(RangeAssignment.Range(1, 2, "adidev")),
            listOf("a", "b", "c"),
            busyIndices = setOf(2),
        )
        assertEquals(0, plan.busyRows)
        assertEquals(listOf("a", "b"), plan.byCaller["adidev"])
    }
}
