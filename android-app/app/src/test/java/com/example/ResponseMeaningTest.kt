package com.example

import com.example.data.ResponseMeaning
import com.example.data.ResponseRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replaces CallStatusRulesTest, which asserted the behaviour of a regex classifier that no longer
 * exists. The old tests passed while the app was still wrong: they only ever checked labels the
 * regexes were written for, and the classifier was wrong for 5 of the 11 labels the client actually
 * asked for. The point of these tests is the opposite property — that the WORDS cannot decide.
 *
 * The twelve rows below are the seeded response set, with the action and sentiment the admin
 * screen writes for each.
 */
class ResponseMeaningTest {

    // ── "Take them off after N in a row" ──────────────────────────────────────────────────────
    // The rule this app used to make impossible to configure. It was gated on the strike toggle,
    // and only a refusal may strike, so the one case it exists for — a phone that is switched off
    // every single time — could never carry it. These four tests pin the gate open.

    @Test
    fun `a run limit is allowed for could-not-reach without ever striking`() {
        assertTrue(ResponseRules.consecutiveRuleAllowed(ResponseMeaning.NOT_REACHED))
        assertFalse(
            "failing to reach someone is not their fault and must never strike",
            ResponseRules.strikeAllowed(ResponseMeaning.NOT_REACHED),
        )
        val n = ResponseRules.normalise(
            action = ResponseMeaning.ACTION_CONTINUE_CALLING,
            sentiment = ResponseMeaning.NOT_REACHED,
            countsAsStrike = false,
            removeAfterConsecutive = 3,
            answeredScope = ResponseMeaning.SCOPE_NOT_ANSWERED,
            deferDays = null,
        )
        assertEquals(3, n.removeAfterConsecutive)
        assertFalse(n.countsAsStrike)
    }

    @Test
    fun `a run limit can never be attached to a yes`() {
        val n = ResponseRules.normalise(
            action = ResponseMeaning.ACTION_MOVE_TO_FOLLOWUP,
            sentiment = ResponseMeaning.POSITIVE,
            countsAsStrike = true,
            removeAfterConsecutive = 3,
            answeredScope = ResponseMeaning.SCOPE_BOTH,
            deferDays = null,
        )
        assertEquals(null, n.removeAfterConsecutive)
        assertFalse(n.countsAsStrike)
    }

    @Test
    fun `a limit of one is dropped here rather than posted and rejected`() {
        // The server refuses anything outside 2..20. Sending 1 anyway is what surfaced as a bare
        // "Validation failed" with nothing on screen saying why.
        for (illegal in listOf(0, 1, 21, 99)) {
            val n = ResponseRules.normalise(
                action = ResponseMeaning.ACTION_CONTINUE_CALLING,
                sentiment = ResponseMeaning.NOT_REACHED,
                countsAsStrike = false,
                removeAfterConsecutive = illegal,
                answeredScope = ResponseMeaning.SCOPE_BOTH,
                deferDays = null,
            )
            assertEquals("$illegal must not be sent to the server", null, n.removeAfterConsecutive)
            assertTrue(ResponseRules.consecutiveOutOfRange(illegal.toString()))
        }
        assertFalse("blank means \"never\", not an error", ResponseRules.consecutiveOutOfRange(""))
        assertFalse(ResponseRules.consecutiveOutOfRange("2"))
        assertFalse(ResponseRules.consecutiveOutOfRange("20"))
    }

    /** label to (action, sentiment, expected local status) — the seeded defaults. */
    private val seeded = listOf(
        Row("Yes", "move_to_followup", "positive", "will_attend"),
        Row("Try to come", "move_to_followup", "positive", "will_attend"),
        Row("Next week", "defer_calling", "positive", "deferred"),
        Row("Will try next time", "continue_calling", "negative", "called"),
        Row("Not interested", "remove_from_running", "negative", "wont_attend"),
        Row("Wrong number", "mark_invalid", "negative", "wont_attend"),
        Row("Out of patna", "continue_calling", "not_reached", "no_answer"),
        Row("Busy", "continue_calling", "not_reached", "no_answer"),
        Row("Not response", "continue_calling", "not_reached", "no_answer"),
        Row("Switch off", "continue_calling", "not_reached", "no_answer"),
        Row("No incoming", "continue_calling", "not_reached", "no_answer"),
        Row("Others", "continue_calling", "not_reached", "no_answer"),
    )

    data class Row(val label: String, val action: String, val sentiment: String, val expected: String)

    @Test
    fun `every seeded response resolves to the status the backend records`() {
        seeded.forEach { r ->
            assertEquals(
                "\"${r.label}\" (${r.action}/${r.sentiment}) resolved wrongly",
                r.expected,
                ResponseMeaning.derive(r.action, r.sentiment),
            )
        }
    }

    @Test
    fun `No incoming is not a confirmed attendance`() {
        // The regression that forced this rewrite: the old WILL regex contained "coming", so
        // "No incoming" — a person who has BLOCKED incoming calls — was scored will_attend,
        // given a follow-up, and never struck.
        val status = ResponseMeaning.derive("continue_calling", "not_reached")
        assertEquals("no_answer", status)
        assertFalse("a blocked number is not a yes", ResponseMeaning.isPositive(status))
    }

    @Test
    fun `the label cannot change the meaning`() {
        // Same action and sentiment, four wordings a centre might choose, including Hindi and the
        // exact strings that used to trip the regexes. All must resolve identically.
        val wordings = listOf("Out of patna", "gaon gaya hai", "Will attend", "yes coming")
        val statuses = wordings.map { ResponseMeaning.derive("continue_calling", "not_reached") }
        assertEquals(setOf("no_answer"), statuses.toSet())
        // ...and renaming an option cannot turn a negative into a positive.
        assertEquals("called", ResponseMeaning.derive("continue_calling", "negative"))
    }

    @Test
    fun `reached-but-refused and never-reached stay different facts`() {
        // Both are "keep calling", but only one of them means contact was made. Collapsing them
        // would make "Will try next time" indistinguishable from "Switch off" in reporting.
        assertEquals("called", ResponseMeaning.derive("continue_calling", "negative"))
        assertEquals("no_answer", ResponseMeaning.derive("continue_calling", "not_reached"))
    }

    @Test
    fun `an option this build has never seen degrades to neutral`() {
        // Offline first run, or a response added by an admin after this device last synced.
        // Neutral "called" records that a call happened without asserting an outcome.
        assertEquals("called", ResponseMeaning.derive(null, null))
        assertEquals("called", ResponseMeaning.derive(null, "positive"))
        assertEquals("called", ResponseMeaning.derive("some_future_action", "positive"))
        assertFalse(ResponseMeaning.isPositive(ResponseMeaning.derive(null, null)))
    }

    @Test
    fun `will attend and call back are both positive but file separately`() {
        assertTrue(ResponseMeaning.isPositive("will_attend"))
        assertTrue(ResponseMeaning.isPositive("call_back_later"))
        assertEquals("will_attend", ResponseMeaning.followupKind("will_attend"))
        assertEquals("call_back_later", ResponseMeaning.followupKind("call_back_later"))
        assertFalse(ResponseMeaning.isPositive("no_answer"))
        assertFalse(ResponseMeaning.isPositive("wont_attend"))
        assertFalse(ResponseMeaning.isPositive("deferred"))
        assertFalse(ResponseMeaning.isPositive("called"))
    }

    // ── The two-step split ───────────────────────────────────────────────────

    @Test
    fun `answered scope puts each response under the right branch`() {
        assertTrue(ResponseMeaning.showsUnder("answered", true))
        assertFalse(ResponseMeaning.showsUnder("answered", false))
        assertFalse(ResponseMeaning.showsUnder("not_answered", true))
        assertTrue(ResponseMeaning.showsUnder("not_answered", false))
        assertTrue(ResponseMeaning.showsUnder("both", true))
        assertTrue(ResponseMeaning.showsUnder("both", false))
    }

    @Test
    fun `nothing is offered until the caller answers step one`() {
        listOf("answered", "not_answered", "both", null).forEach { scope ->
            assertFalse(
                "scope $scope must not show before the pick-up question is answered",
                ResponseMeaning.showsUnder(scope, null),
            )
        }
    }

    @Test
    fun `an unknown scope shows under both rather than hiding the response`() {
        // A server newer than this build. Offering an extra response is recoverable; hiding the
        // one the caller needs leaves them with no way to record the call.
        assertTrue(ResponseMeaning.showsUnder("some_future_scope", true))
        assertTrue(ResponseMeaning.showsUnder("some_future_scope", false))
        assertTrue(ResponseMeaning.showsUnder(null, true))
    }

    @Test
    fun `every seeded response is reachable from at least one branch`() {
        // A response nobody can ever select is a configuration dead end.
        val scopes = listOf("answered", "answered", "answered", "answered", "answered",
            "both", "both", "both", "not_answered", "not_answered", "not_answered", "both")
        scopes.forEachIndexed { i, scope ->
            assertTrue(
                "${seeded[i].label} is unreachable",
                ResponseMeaning.showsUnder(scope, true) || ResponseMeaning.showsUnder(scope, false),
            )
        }
    }
}
