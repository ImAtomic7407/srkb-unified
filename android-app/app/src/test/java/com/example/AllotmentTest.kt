package com.example

import com.example.data.Allotment
import com.example.data.AllotmentSource
import com.example.data.SessionCallAssignmentDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allotment snapshot is what lets "tap the list name to get back to that list" work at all.
 * If it round-trips wrongly the admin is silently returned to the WRONG sheet — which is worse
 * than not offering the button, because the rows will look plausible.
 */
class AllotmentTest {

    private fun roundTrip(s: AllotmentSource) = Allotment.decode(Allotment.encode(s))

    @Test
    fun aSnapshotSurvivesTheRoundTripIntact() {
        val s = AllotmentSource(
            batchIds = listOf("18", "19"),
            sessionIds = listOf("01-08-26", "8 Aug BG"),
            include = "responded",
            rows = "7–15",
        )
        assertEquals(s, roundTrip(s))
    }

    @Test
    fun sessionNamesCarryingTheFormatsOwnPunctuationDoNotSplitTheFields() {
        // Real session ids are admin-typed free text. A comma or an equals sign in one of them must
        // not silently become a field boundary and hand back a different list.
        val s = AllotmentSource(
            sessionIds = listOf("BG, 27-07-26", "a=b", "semi;colon", "100%"),
            include = "both",
            rows = "1–3, 12–13",
        )
        val back = roundTrip(s)
        assertEquals(listOf("BG, 27-07-26", "a=b", "semi;colon", "100%"), back?.sessionIds)
        assertEquals("1–3, 12–13", back?.rows)
    }

    @Test
    fun batchesOnlyIsAValidSnapshot() {
        val s = AllotmentSource(batchIds = listOf("18"), sessionIds = emptyList(), rows = "1–20")
        assertEquals(s, roundTrip(s))
    }

    @Test
    fun aSnapshotNamingNothingDecodesToNullRatherThanClearingTheAdminsFilter() {
        // "Restore" that restores nothing would blank the dropdowns and look like a crash.
        assertNull(Allotment.decode(Allotment.encode(AllotmentSource())))
        assertNull(Allotment.decode(null))
        assertNull(Allotment.decode(""))
    }

    @Test
    fun rubbishDecodesToNullInsteadOfThrowing() {
        assertNull(Allotment.decode("not a snapshot"))
        assertNull(Allotment.decode(";;;;"))
        assertNull(Allotment.decode("batches=;sessions=;include=;rows="))
    }

    @Test
    fun anUnknownFieldFromANewerBuildIsIgnoredNotFatal() {
        val decoded = Allotment.decode("batches=18;sessions=01-08-26;include=attended;rows=1–5;futureThing=x")
        assertEquals(listOf("18"), decoded?.batchIds)
        assertEquals(listOf("01-08-26"), decoded?.sessionIds)
        assertEquals("1–5", decoded?.rows)
    }

    @Test
    fun aMissingIncludeFallsBackToAttendedRatherThanEmpty() {
        assertEquals("attended", Allotment.decode("batches=18")?.include)
    }

    // ── Headings ──────────────────────────────────────────────────────────────

    @Test
    fun theHeadingNamesTheSessionAndTheShowMode() {
        assertEquals("8 Aug BG · attended", Allotment.label(listOf("8 Aug BG"), listOf("BG"), "attended"))
        assertEquals("8 Aug BG · positive response", Allotment.label(listOf("8 Aug BG"), listOf("BG"), "responded"))
    }

    @Test
    fun withNoSessionTheHeadingIsTheBatchAndSaysNothingAboutResponses() {
        // A batch roster has no responses, so "· attended" there would be a lie.
        assertEquals("BG", Allotment.label(emptyList(), listOf("BG"), "attended"))
    }

    @Test
    fun manySessionsAreTruncatedSoTheCallerNameStaysVisible() {
        val names = listOf("01-08-26", "8 Aug BG", "BG 27-07-26", "20 Jul")
        assertEquals("01-08-26 + 8 Aug BG + 2 more · attended", Allotment.label(names, emptyList(), "attended"))
    }

    @Test
    fun anEnormousSessionNameIsClampedRatherThanCarriedWhole() {
        // Session names are admin-typed. An unbounded one wraps the card into nonsense AND can push
        // the payload past the server's length limit — and provenance travels in the SAME request
        // as the allotment, so an over-long heading must never cost the admin the allotment.
        val label = Allotment.label(listOf("x".repeat(500)), emptyList(), "attended")
        assertTrue("heading was ${label.length} chars", label.length <= 60)
        assertTrue("the name itself was not truncated: $label", label.contains("…"))
        // Two enormous names must not simply double it.
        val two = Allotment.label(listOf("x".repeat(500), "y".repeat(500)), emptyList(), "attended")
        assertTrue("heading was ${two.length} chars", two.length <= 110)
    }

    @Test
    fun noFilterAtAllStillReadsAsSomething() {
        assertEquals("All people", Allotment.label(emptyList(), emptyList(), "attended"))
    }

    // ── Keys ──────────────────────────────────────────────────────────────────

    @Test
    fun twoAllotmentsToTheSameCallerGetDifferentKeys() {
        // The whole point: a second allotment from a different list must not merge into the first.
        assertNotEquals(Allotment.newKey("7", 1_000L), Allotment.newKey("7", 2_000L))
    }

    @Test
    fun twoCallersInOnePressGetDifferentKeys() {
        assertNotEquals(Allotment.newKey("7", 1_000L), Allotment.newKey("8", 1_000L))
    }

    // ── Grouping ──────────────────────────────────────────────────────────────

    private fun row(
        id: String,
        caller: String,
        callerName: String = "caller$caller",
        key: String? = null,
        label: String? = null,
        filter: String? = null,
    ) = SessionCallAssignmentDetail(
        id = id,
        batchMemberId = id,
        batchMemberName = "P$id",
        batchMemberMobile = "98358000$id",
        callerId = caller,
        callerName = callerName,
        callStatus = "pending",
        allotmentKey = key,
        allotmentLabel = label,
        allotmentFilter = filter,
    )

    @Test
    fun oneCallerAllottedFromTwoListsGetsTwoHeadingsNotOne() {
        // The reported behaviour: the second allotment vanished into the first.
        val rows = listOf(
            row("1", "7", key = "k1", label = "8 Aug BG · attended"),
            row("2", "7", key = "k1", label = "8 Aug BG · attended"),
            row("3", "7", key = "k2", label = "01-08-26 · positive response"),
        )
        val groups = Allotment.group(rows)
        assertEquals(2, groups.size)
        assertEquals("8 Aug BG · attended", groups[0].label)
        assertEquals(2, groups[0].rows.size)
        assertEquals("01-08-26 · positive response", groups[1].label)
        assertEquals(1, groups[1].rows.size)
    }

    @Test
    fun legacyRowsWithNoKeyStayAsOneUnheadedGroupPerCaller() {
        // Every allotment made before this feature. They must look exactly as they always did.
        val rows = listOf(row("1", "7"), row("2", "7"), row("3", "8"))
        val groups = Allotment.group(rows)
        assertEquals(2, groups.size)
        assertEquals(2, groups[0].rows.size)
        assertNull(groups[0].label)
        assertNull(groups[0].key)
        assertEquals("8", groups[1].callerId)
    }

    @Test
    fun twoCallersSharingOneKeyAreStillTwoGroups() {
        // One press allotting three ranges to three callers writes one key per caller, but even if
        // a key were reused, a group is always one caller — never a merged card.
        val rows = listOf(row("1", "7", key = "k1"), row("2", "8", key = "k1"))
        assertEquals(2, Allotment.group(rows).size)
    }

    @Test
    fun theRowRangeIsReadBackOffTheGroupForTheHeading() {
        // Feature ask: show which rows were allotted, not just how many.
        val filter = Allotment.encode(
            AllotmentSource(sessionIds = listOf("8 Aug BG"), include = "attended", rows = "7–15")
        )
        val groups = Allotment.group(listOf(row("1", "7", key = "k1", label = "x", filter = filter)))
        assertEquals("7–15", groups[0].rowRange)
    }

    @Test
    fun aGroupWithNoSnapshotHasNoRowRangeRatherThanABlankOne() {
        assertNull(Allotment.group(listOf(row("1", "7"))).first().rowRange)
    }

    @Test
    fun groupingKeepsEveryRowExactlyOnce() {
        val rows = listOf(
            row("1", "7", key = "k1"), row("2", "7", key = "k2"), row("3", "8"),
            row("4", "8"), row("5", "7", key = "k1"),
        )
        assertEquals(rows.size, Allotment.group(rows).sumOf { it.rows.size })
    }

    @Test
    fun anEmptySessionHasNoGroups() {
        assertEquals(0, Allotment.group(emptyList()).size)
    }
}
