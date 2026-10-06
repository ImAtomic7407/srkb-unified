package com.example

import com.example.data.CallOutcomeQueue
import com.example.data.OfflineQueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the one-response-per-person rule inside the offline queue.
 *
 * Once the network write happens in the background, two things can touch the queue for the
 * same person: the caller changing their answer, and a successful sync removing the item.
 * If either stacks or removes the wrong entry, the person's recorded outcome depends on
 * drain order — which is exactly the bug that made someone show two different "last
 * responses" for the same day.
 */
class CallOutcomeQueueTest {

    private fun item(assignmentId: String, outcome: String, id: String = "off_1") =
        OfflineQueueItem(
            id = id,
            type = CallOutcomeQueue.TYPE,
            label = "Call outcome: $outcome",
            payload = "$assignmentId<--SEP-->$outcome<--SEP-->",
        )

    @Test
    fun `assignment id is read back from the payload`() {
        assertEquals("a1", CallOutcomeQueue.assignmentIdOf(item("a1", "Yes")))
    }

    @Test
    fun `re-answering the same person replaces the parked item`() {
        val queue = listOf(item("a1", "Yes", id = "off_1"))
        val result = CallOutcomeQueue.supersede(queue, item("a1", "Not interested", id = "off_2"))
        assertEquals(1, result.size)
        assertEquals("off_2", result[0].id)
        assertEquals("a1<--SEP-->Not interested<--SEP-->", result[0].payload)
    }

    @Test
    fun `a different person is left untouched`() {
        val queue = listOf(item("a1", "Yes", id = "off_1"))
        val result = CallOutcomeQueue.supersede(queue, item("a2", "Busy", id = "off_2"))
        assertEquals(2, result.size)
        assertEquals(listOf("off_1", "off_2"), result.map { it.id })
    }

    @Test
    fun `items of other types are never disturbed`() {
        val other = OfflineQueueItem("off_9", "mark_attendance", "Attendance", "a1<--SEP-->x")
        val result = CallOutcomeQueue.supersede(listOf(other), item("a1", "Yes", id = "off_2"))
        assertEquals(2, result.size)
        assertEquals("off_9", result[0].id)
    }

    @Test
    fun `remove drops only the named person's outcome`() {
        val queue = listOf(item("a1", "Yes", "off_1"), item("a2", "Busy", "off_2"))
        val result = CallOutcomeQueue.remove(queue, "a1")
        assertEquals(1, result.size)
        assertEquals("off_2", result[0].id)
    }

    @Test
    fun `remove leaves other types alone`() {
        val other = OfflineQueueItem("off_9", "mark_attendance", "Attendance", "a1<--SEP-->x")
        val result = CallOutcomeQueue.remove(listOf(other), "a1")
        assertEquals(1, result.size)
    }

    // ── The parked payload ───────────────────────────────────────────────────────────────────
    // Positional fields, written by one build and read by another after an upgrade. A shift in
    // any slot silently rewrites what the outcome MEANS, so encode and decode are checked as a
    // pair and every historical shape is checked explicitly.

    @Test
    fun `a full outcome survives the round trip`() {
        val parked = CallOutcomeQueue.decode(
            CallOutcomeQueue.encode("a1", "Not interested", "Rude", answered = true, dropdownId = "42")
        )!!
        assertEquals("a1", parked.assignmentId)
        assertEquals("Not interested", parked.outcome)
        assertEquals("Rude", parked.remark)
        assertEquals(true, parked.answered)
        assertEquals(42, parked.dropdownId)
    }

    @Test
    fun `the response id does not slide into the pick-up answer when there is no answer`() {
        // The trap: skipping the empty 4th field would put "42" where `answered` is read, and the
        // outcome would come back claiming the person picked up when nobody said they did.
        val parked = CallOutcomeQueue.decode(
            CallOutcomeQueue.encode("a1", "Wrong number", null, answered = null, dropdownId = "42")
        )!!
        assertEquals(null, parked.answered)
        assertEquals(42, parked.dropdownId)
    }

    @Test
    fun `an outcome with no id still decodes`() {
        val parked = CallOutcomeQueue.decode(
            CallOutcomeQueue.encode("a1", "Busy", null, answered = false, dropdownId = null)
        )!!
        assertEquals(false, parked.answered)
        assertEquals(null, parked.dropdownId)
    }

    @Test
    fun `an item parked by the build before the pick-up question still sends`() {
        val parked = CallOutcomeQueue.decode("a1<--SEP-->Yes<--SEP-->")!!
        assertEquals("Yes", parked.outcome)
        assertEquals(null, parked.remark)
        assertEquals(null, parked.answered)
        assertEquals(null, parked.dropdownId)
    }

    @Test
    fun `an item parked by the build before response ids still sends`() {
        val parked = CallOutcomeQueue.decode("a1<--SEP-->Yes<--SEP-->note<--SEP-->true")!!
        assertEquals(true, parked.answered)
        assertEquals(null, parked.dropdownId)
    }

    @Test
    fun `a payload too short to name a response is refused rather than half-sent`() {
        assertEquals(null, CallOutcomeQueue.decode("a1"))
    }

    @Test
    fun `encode still puts the person first so supersede keeps working`() {
        // supersede and remove both key on substringBefore(SEP); if the id ever stopped being
        // first, changing an answer would stack a second outcome instead of replacing it.
        val payload = CallOutcomeQueue.encode("a7", "Yes", null, answered = true, dropdownId = "3")
        assertEquals("a7", CallOutcomeQueue.assignmentIdOf(item("ignored", "x").copy(payload = payload)))
    }

    // ── Retiring the optimistic paint ──────────────────────────────────────────────
    // Observed live on 2026-08-30: a caller saved a response, watched it disappear off the row,
    // and had to record it a second time. Assignment 2878 was PATCHed at 10:45:34 and again at
    // 10:46:13; 2843, 2847, 2852 and 2856 show the same shape earlier the same morning. The cause
    // was the paint being dropped the instant the write was acknowledged, while a refetch issued
    // moments earlier — and computed by the server BEFORE that write committed — was still in
    // flight. It came back saying "pending", and with the paint gone there was nothing to cover
    // the stale row. These tests pin the rule that fixes it: only the server's own view retires a
    // paint, and a reply that still says pending never does.

    @Test
    fun `a reply that still says pending does not retire the paint`() {
        // The exact race. The caller HAS answered; this reply simply predates their write.
        val confirmed = CallOutcomeQueue.confirmedOverrideIds(
            overrideIds = setOf("2878"),
            serverStatusById = mapOf("2878" to "pending"),
        )
        assertTrue("a stale pending must never blank a saved answer", confirmed.isEmpty())
    }

    @Test
    fun `the paint retires once the server reports the row answered`() {
        val confirmed = CallOutcomeQueue.confirmedOverrideIds(
            overrideIds = setOf("2878"),
            serverStatusById = mapOf("2878" to "no_answer"),
        )
        assertEquals(setOf("2878"), confirmed)
    }

    @Test
    fun `a row the server no longer lists retires rather than stranding a follow-up card`() {
        // Session completed or the person taken out: nothing on screen for the paint to cover, and
        // localOptimisticFollowups() would otherwise render its card forever.
        val confirmed = CallOutcomeQueue.confirmedOverrideIds(
            overrideIds = setOf("2878"),
            serverStatusById = emptyMap(),
        )
        assertEquals(setOf("2878"), confirmed)
    }

    @Test
    fun `each row is judged on its own reply, not on the batch`() {
        // A drain sends many outcomes at once and a refetch lands mid-burst, so one reply routinely
        // carries some rows the server has caught up on and some it has not. Retiring all of them
        // together is what blanked whole screens during the 10:45 burst.
        val confirmed = CallOutcomeQueue.confirmedOverrideIds(
            overrideIds = setOf("a", "b", "c"),
            serverStatusById = mapOf("a" to "will_attend", "b" to "pending", "c" to "called"),
        )
        assertEquals(setOf("a", "c"), confirmed)
    }

    @Test
    fun `nothing is retired when the caller has nothing painted`() {
        assertTrue(
            CallOutcomeQueue.confirmedOverrideIds(
                overrideIds = emptySet(),
                serverStatusById = mapOf("a" to "called"),
            ).isEmpty()
        )
    }
}
