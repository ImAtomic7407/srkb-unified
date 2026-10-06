package com.example.data

/**
 * What the three figures above the pool table are counting.
 *
 * WHY THIS EXISTS. The stats card used to take its figures from the selected calling session
 * whenever one was picked, on EVERY tab. On the "New Registered" tab that put the session's whole
 * batch roster over a list of people registered in a date window — observed live on 2026-08-13:
 * the list showed the 99 people imported that day while the headline said 463, which is the entire
 * standing BG roster at Centre A going back to 15 May. Both numbers were correct and neither
 * described the other, so the screen looked broken.
 *
 * Relabelling had already been tried — the tile switches "Total" to "In batch" with a session
 * selected — and was not enough. A number sitting directly above a list is read as counting that
 * list, whatever the caption says. So the rule is now:
 *
 *   - Tabs 0/1/2 (the assignment tabs) with a session selected -> the SESSION's own figures:
 *     its batch roster and its assignment rows. This is deliberate and must not regress. Deriving
 *     "assigned" by intersecting the session's assignments with the visible pool rows undercounts,
 *     because the pool list drops anyone in follow-up or marked invalid (observed live: a session
 *     with 126 assigned reported 105). The session knows its own total; ask it.
 *
 *   - Everywhere else — and ALWAYS on the New Registered tab — the figures describe the rows on
 *     screen: how many are listed, and how many of those are being called right now. "Assigned"
 *     is counted with PoolCallerResolution over the same live-caller map that fills the Caller
 *     column, so the headline can never contradict the column beneath it.
 *
 * `showing` is always the number of rows rendered, in both modes. In session mode it is what keeps
 * the batch figure honest: the caption can say plainly that 463 is the roster while 99 are listed.
 */
object PoolCounts {

    /** The "New Registered" tab — a date-window browse, with no session assignment semantics. */
    const val TAB_NEW_REGISTERED = 3

    data class Counts(
        /** Rows actually rendered below, after search and every filter. Never disagrees with the list. */
        val showing: Int,
        val total: Int,
        val assigned: Int,
        val pending: Int,
        val totalLabel: String,
        /** true when the figures are the session's, so the caller can explain the gap to `showing`. */
        val describesSession: Boolean,
    )

    /**
     * @param sessionRosterCount   size of the selected session's batch roster, null if no session
     * @param sessionAssignedCount the session's own assignment-row count, null if no session
     * @param visibleMobiles       the mobiles of the rows rendered below, in order
     * @param liveCallers          from [PoolCallerResolution.liveCallerByMobile] — empty unless a
     *                             session is selected, still active, and holding assignments
     * @param listIsFiltered       the admin has narrowed the list with the lower batch/session
     *                             filter. See below — this flips the figures back onto the list.
     */
    fun of(
        activeTab: Int,
        visibleMobiles: List<String>,
        liveCallers: Map<String, String>,
        sessionRosterCount: Int?,
        sessionAssignedCount: Int?,
        listIsFiltered: Boolean = false,
    ): Counts {
        val showing = visibleMobiles.size
        // Session figures are the right default: on the assignment tabs the admin is working a
        // calling session, and "how much of THIS SESSION is assigned" is the question (that is the
        // 126-reported-as-105 fix, guarded by assignmentTabs_keepTheSessionsOwnFigures).
        //
        // But the moment they narrow the list with the lower batch/session filter they are asking
        // about the narrowed set, and a headline of 463 over a 21-row filtered sheet reads as
        // broken — reported exactly that way. An explicit filter is an explicit change of subject,
        // so the figures follow the rows. Clear the filter and the session's own figures return.
        val sessionMode = sessionRosterCount != null && activeTab != TAB_NEW_REGISTERED && !listIsFiltered

        val total = if (sessionMode) sessionRosterCount else showing
        val assigned =
            if (sessionMode) (sessionAssignedCount ?: 0)
            else PoolCallerResolution.assignedCount(liveCallers, visibleMobiles)

        return Counts(
            showing = showing,
            total = total,
            assigned = assigned,
            // Never render a negative "to assign": the roster can lag the assignment list for a
            // moment after assigning, and a roster that failed to load would show nonsense.
            pending = (total - assigned).coerceAtLeast(0),
            totalLabel = when {
                sessionMode -> "In batch"
                activeTab == TAB_NEW_REGISTERED -> "Registered"
                // Name it, so a figure that just dropped from 463 to 21 is obviously the filter
                // doing its job rather than the pool having emptied.
                listIsFiltered -> "Filtered"
                else -> "In pool"
            },
            describesSession = sessionMode,
        )
    }
}
