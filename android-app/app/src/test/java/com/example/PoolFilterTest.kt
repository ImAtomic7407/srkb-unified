package com.example

import com.example.data.Allotment
import com.example.data.AllotmentSource
import com.example.data.PoolFilter
import com.example.data.PoolFilterTicks
import com.example.data.PoolFilterTicks.State
import com.example.data.SessionResponseOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pool filter decides WHO a caller is given. A wrong decode or a wrong tick silently hands the
 * caller a plausible-looking but different list, so the round trips and the tick rules are pinned.
 */
class PoolFilterTest {

    private val yes = SessionResponseOption("Yes", "positive", listOf(55), 47)
    private val willAttend = SessionResponseOption("Will attend", "positive", listOf(32, 80), 10)
    private val notInterested = SessionResponseOption("Not interested", "negative", listOf(59), 13)
    private val notResponse = SessionResponseOption("Not response", "not_reached", listOf(63), 69)
    private val busy = SessionResponseOption("Busy", "not_reached", listOf(62), 9)
    private val options = listOf(yes, willAttend, notInterested, notResponse, busy)

    @Test
    fun theOldChipsDecodeToTheirExactEquivalents() {
        assertEquals(PoolFilter(attended = true), PoolFilter.decode("attended"))
        assertEquals(PoolFilter(sentiments = setOf("positive"), attended = false), PoolFilter.decode("responded"))
        assertEquals(
            PoolFilter(sentiments = setOf("not_reached"), neverCalled = true, attended = false),
            PoolFilter.decode("need_check"),
        )
        // attended OR positive cannot be written as response AND attendance - fetched the old way.
        assertNull(PoolFilter.decode("both"))
        assertTrue(PoolFilter.isLegacy("both"))
        assertEquals("both", PoolFilter.normalize("both"))
    }

    @Test
    fun onlyTheThreeOldQuestionsFallBackToTheOldEndpoint() {
        for (old in listOf("attended", "responded", "need_check")) {
            assertEquals(old, PoolFilter.decode(old)!!.legacyEquivalent())
        }
        // Anything the old server cannot answer must fail loudly, not come back as a different list.
        assertNull(PoolFilter(sentiments = setOf("positive"), attended = false, notAttended = true).legacyEquivalent())
        assertNull(PoolFilter(sentiments = setOf("positive")).legacyEquivalent())
    }

    @Test
    fun aFilterSurvivesItsOwnRoundTripAndTheAllotmentSnapshot() {
        val f = PoolFilter(
            sentiments = setOf("positive", "not_reached"),
            responseIds = setOf(59),
            neverCalled = true,
            attended = false,
            notAttended = true,
        )
        assertEquals(f, PoolFilter.decode(f.encode()))
        // The allotment snapshot escapes its own punctuation; the filter must come back untouched.
        val src = AllotmentSource(sessionIds = listOf("2026-09-12 CTRA BG"), include = f.encode(), rows = "1–20")
        assertEquals(f, PoolFilter.decode(Allotment.decode(Allotment.encode(src))!!.include))
    }

    @Test
    fun attendanceBothOrNeitherIsNoCondition() {
        assertEquals("present", PoolFilter(attended = true).attendance)
        assertEquals("absent", PoolFilter(attended = false, notAttended = true).attendance)
        assertEquals("any", PoolFilter(attended = true, notAttended = true).attendance)
        assertEquals("any", PoolFilter(attended = false, notAttended = false).attendance)
    }

    @Test
    fun tickingAGroupTicksAllOfItAndNeutralCarriesNotCalledYet() {
        var f = PoolFilter()
        f = PoolFilterTicks.toggleGroup(f, options, "not_reached")
        assertEquals(State.ALL, PoolFilterTicks.groupState(f, options, "not_reached"))
        assertTrue(f.neverCalled)
        assertTrue(PoolFilterTicks.isResponseTicked(f, busy))
        f = PoolFilterTicks.toggleGroup(f, options, "not_reached")
        assertEquals(State.NONE, PoolFilterTicks.groupState(f, options, "not_reached"))
        assertFalse(f.neverCalled)
    }

    @Test
    fun unTickingOneResponseOfAWholeGroupLeavesTheRestPickedSingly() {
        var f = PoolFilterTicks.toggleGroup(PoolFilter(), options, "positive")
        f = PoolFilterTicks.toggleResponse(f, options, yes)
        assertEquals(State.SOME, PoolFilterTicks.groupState(f, options, "positive"))
        assertFalse(PoolFilterTicks.isResponseTicked(f, yes))
        assertTrue(PoolFilterTicks.isResponseTicked(f, willAttend))
        // Both ids behind the merged "Will attend" are carried, or half its people would drop out.
        assertEquals(setOf(32, 80), f.responseIds)
    }

    @Test
    fun pickingEveryResponseOfAGroupBecomesTheWholeGroup() {
        var f = PoolFilterTicks.toggleResponse(PoolFilter(), options, yes)
        assertEquals(State.SOME, PoolFilterTicks.groupState(f, options, "positive"))
        f = PoolFilterTicks.toggleResponse(f, options, willAttend)
        assertEquals(State.ALL, PoolFilterTicks.groupState(f, options, "positive"))
        assertEquals(setOf("positive"), f.sentiments)
        assertTrue(f.responseIds.isEmpty())
    }

    @Test
    fun groupsAreIndependentAndMultiSelect() {
        var f = PoolFilterTicks.toggleGroup(PoolFilter(), options, "positive")
        f = PoolFilterTicks.toggleResponse(f, options, notInterested)
        assertEquals(State.ALL, PoolFilterTicks.groupState(f, options, "positive"))
        // The only negative response, picked singly, is the whole negative group.
        assertEquals(State.ALL, PoolFilterTicks.groupState(f, options, "negative"))
        assertEquals(State.NONE, PoolFilterTicks.groupState(f, options, "not_reached"))
    }

    @Test
    fun describeReadsLikeTheQuestionAsked() {
        assertEquals("Attended", PoolFilter().describe())
        assertEquals(
            "Positive · did not attend",
            PoolFilter(sentiments = setOf("positive"), attended = false, notAttended = true).describe(),
        )
        assertEquals(
            "Negative + Yes · attended",
            PoolFilter(sentiments = setOf("negative"), responseIds = setOf(55)).describe(mapOf(55 to "Yes")),
        )
        assertEquals("Neutral", PoolFilter(sentiments = setOf("not_reached"), neverCalled = true, attended = false).describe())
    }
}
