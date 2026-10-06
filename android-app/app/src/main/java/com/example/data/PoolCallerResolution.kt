package com.example.data

/**
 * Who — if anyone — is currently assigned to call each person listed in the Pool Assignment table.
 *
 * THE BUG THIS EXISTS TO PREVENT. The Caller column used to render
 * `AppDatabase.users.find { it.id == reg.callerAssignedId }`, i.e. the registration's
 * `current_assigned_caller_id` column. That column is a denormalised, pool-wide leftover: it is
 * written when someone is assigned but is NOT cleared when the assignment ends or the calling
 * session finishes. Measured against the live database on 2026-08-13, Centre A (centre 2, youth):
 *
 *     calling_assignments WHERE centre_id = 2                    ->    0 rows, any status
 *     registrations WITH current_assigned_caller_id set          ->  147 rows
 *
 * So 147 people displayed a caller's name while not one live assignment existed anywhere in the
 * centre. The names came from calling session 43, which had been *completed* weeks earlier. The
 * "Unassigned" tab was among the screens showing them, which is what made it look like the tab
 * itself was broken.
 *
 * THE RULE, as specified: a caller is shown only when a calling session is selected, that session
 * has assigned numbers, and the session is not closed.
 *
 * WHY AN ALLOWLIST ON "active". SessionCampaign.status has three values — "active", "closed"
 * (retrieval, locked) and "completed". Session 43 is `completed`, so a `status != "closed"` test
 * would still have leaked exactly the names being complained about. Only `active` means "callers
 * are working this list right now".
 *
 * SOURCE OF TRUTH. `SessionCampaign.assignmentDetails` comes from the backend's session-campaign
 * query, which already filters `removedAt: null` (sessionCampaign.service.ts:205), so a struck-off
 * assignment cannot resurface here. Each row carries the batch member's mobile and the caller's
 * name directly, so no join through batch-member ids is needed.
 *
 * This is deliberately a pure function over plain data: it is the one piece of this screen whose
 * correctness cannot be eyeballed, so it is unit-tested in PoolCallerResolutionTest.
 */
object PoolCallerResolution {

    /** The only campaign status for which callers are actively working the list. */
    private const val STATUS_ACTIVE = "active"

    /**
     * The key both sides of the match are reduced to: the last 10 digits.
     *
     * This mirrors the calling backend's own `digits10()` (`(value ?? "").replace(/\D/g,"").slice(-10)`)
     * exactly, including its behaviour on short values — JS `slice(-10)` on a 9-character string
     * returns the whole string, and so does `takeLast(10)`. That matters because `batch_members`
     * really does hold a handful of 9-digit entries (see PhoneNumbers). Matching on ALL digits
     * instead would silently fail for any number stored with a 91 prefix on one side only.
     */
    fun matchKey(raw: String?): String? {
        val digits = raw.orEmpty().filter { it.isDigit() }
        return if (digits.isEmpty()) null else digits.takeLast(10)
    }

    /**
     * mobile -> caller name, for the selected session's LIVE assignments only.
     *
     * Returns an empty map — meaning "show no caller for anyone" — when no session is selected,
     * when the selected session is closed or completed, or when it has no assignments yet. The
     * caller column and the Assigned counter are both driven from this single map so they can
     * never disagree with each other.
     */
    fun liveCallerByMobile(campaign: SessionCampaign?): Map<String, String> {
        if (campaign == null || campaign.status != STATUS_ACTIVE) return emptyMap()

        val byMobile = HashMap<String, String>()
        for (assignment in campaign.assignmentDetails) {
            val key = matchKey(assignment.batchMemberMobile) ?: continue
            if (assignment.callerName.isBlank()) continue
            byMobile[key] = assignment.callerName
        }
        return byMobile
    }

    /** The caller shown against one row, or null for the "—" badge. */
    fun callerFor(liveCallers: Map<String, String>, mobile: String?): String? =
        matchKey(mobile)?.let { liveCallers[it] }

    /** How many of the listed people are actually assigned in the live session. */
    fun assignedCount(liveCallers: Map<String, String>, mobiles: List<String>): Int =
        mobiles.count { callerFor(liveCallers, it) != null }
}
