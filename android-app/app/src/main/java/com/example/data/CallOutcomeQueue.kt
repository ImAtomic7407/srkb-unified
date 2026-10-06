package com.example.data

/**
 * The one-response-per-person rule for queued call outcomes.
 *
 * Extracted from AppData so it can be tested directly: with the network write now running in
 * the background, the queue is mutated from two places (re-answering, and a successful sync)
 * and the ordering between them must not decide what the person's outcome ends up being.
 */
object CallOutcomeQueue {
    const val TYPE = "update_call_status"
    private const val SEP = "<--SEP-->"

    fun assignmentIdOf(item: OfflineQueueItem): String = item.payload.substringBefore(SEP)

    /**
     * One parked call outcome.
     *
     * [dropdownId] is what lets a parked outcome survive a rename. An item can sit in this queue
     * for hours while an admin edits the directory, and the label it was saved under is precisely
     * what stops matching in that window.
     */
    data class Parked(
        val assignmentId: String,
        val outcome: String,
        val remark: String?,
        val answered: Boolean?,
        val dropdownId: Int?,
    )

    /**
     * Positional, append-only, and NEVER reordered — items written by an older build are still in
     * the queue on upgrade and are decoded by this same function. A three-field item predates the
     * pick-up question; a four-field item predates the response id.
     *
     * The 4th slot is written EMPTY rather than skipped when there is an id but no pick-up answer,
     * because dropping it would shift the id into the answered position and a queued outcome would
     * come back claiming the person answered.
     */
    fun encode(
        assignmentId: String,
        outcome: String,
        remark: String?,
        answered: Boolean?,
        dropdownId: String?,
    ): String = buildString {
        append(assignmentId).append(SEP)
        append(outcome).append(SEP)
        append(remark ?: "")
        if (answered != null || dropdownId != null) append(SEP).append(answered?.toString() ?: "")
        if (dropdownId != null) append(SEP).append(dropdownId)
    }

    /** Null when the payload is too short to name a person and a response — those cannot be sent. */
    fun decode(payload: String): Parked? {
        val parts = payload.split(SEP)
        if (parts.size < 2) return null
        return Parked(
            assignmentId = parts[0],
            outcome = parts[1],
            remark = parts.getOrNull(2)?.ifBlank { null },
            answered = parts.getOrNull(3)?.toBooleanStrictOrNull(),
            dropdownId = parts.getOrNull(4)?.toIntOrNull(),
        )
    }

    /** Park [item], dropping any outcome already parked for the same person. */
    fun supersede(queue: List<OfflineQueueItem>, item: OfflineQueueItem): List<OfflineQueueItem> {
        val id = assignmentIdOf(item)
        return queue.filterNot { it.type == TYPE && assignmentIdOf(it) == id } + item
    }

    /** Drop the parked outcome for [assignmentId] — used once the server has accepted it. */
    fun remove(queue: List<OfflineQueueItem>, assignmentId: String): List<OfflineQueueItem> =
        queue.filterNot { it.type == TYPE && assignmentIdOf(it) == assignmentId }

    /** The status an assignment carries before anyone has answered for it. */
    const val PENDING = "pending"

    /**
     * Which optimistic paints may now be dropped, given what the SERVER just said.
     *
     * [serverStatusById] must come from a live /session-call-queue reply, never from the local
     * cache — the cache is our own last paint read back, so confirming against it would be the app
     * confirming itself.
     *
     * An override retires when the server either shows the row answered, or no longer lists it at
     * all (session completed, person taken out) — in the second case there is nothing left on
     * screen for the paint to cover, and keeping it would strand an optimistic follow-up card.
     *
     * A row the server still calls [PENDING] keeps its paint. That is the whole point: a reply
     * computed before the caller's write committed says "pending" for a person who has genuinely
     * been answered, and dropping the paint on the write's own acknowledgement is what let that
     * stale reply blank out a response the caller had just recorded.
     */
    fun confirmedOverrideIds(
        overrideIds: Set<String>,
        serverStatusById: Map<String, String>,
    ): Set<String> = overrideIds.filterTo(mutableSetOf()) { id ->
        val status = serverStatusById[id]
        status == null || status != PENDING
    }
}
