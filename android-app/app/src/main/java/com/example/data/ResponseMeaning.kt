package com.example.data

/**
 * What a response MEANS, read from the option the admin configured — never guessed from its words.
 *
 * This replaces CallStatusRules, which derived the coarse status by matching the label against
 * ordered regexes. That was wrong for 5 of the 11 labels the client actually asked for, and the
 * worst case inverted the meaning entirely: "No incoming" matched `coming` in the positive pattern,
 * so a person who has BLOCKED incoming calls was recorded as having confirmed attendance, given a
 * follow-up, and never struck. Any label a centre invents that the patterns do not recognise fell
 * through to a meaningless "called".
 *
 * The rule now: sentiment and action come from the server on every option, and this object only
 * combines them. Renaming a response therefore cannot change what it does — which is the whole
 * point of letting centres name their own.
 *
 * Kept as a pure object with no Android dependency so it can be unit-tested without the app, the
 * same property CallStatusRules had.
 */
object ResponseMeaning {

    // Mirrors DropdownAction on the server. Only the six that apply to calling appear here.
    const val ACTION_MOVE_TO_FOLLOWUP = "move_to_followup"
    const val ACTION_CONTINUE_CALLING = "continue_calling"
    const val ACTION_FOLLOWUP_CALLBACK = "followup_callback"
    const val ACTION_DEFER_CALLING = "defer_calling"
    const val ACTION_REMOVE_FROM_RUNNING = "remove_from_running"
    const val ACTION_MARK_INVALID = "mark_invalid"

    // Mirrors ResponseSentiment.
    const val POSITIVE = "positive"
    const val NEGATIVE = "negative"
    const val NOT_REACHED = "not_reached"

    // Mirrors AnsweredScope. `BOTH` shows under either branch of "Did they pick the call?".
    const val SCOPE_ANSWERED = "answered"
    const val SCOPE_NOT_ANSWERED = "not_answered"
    const val SCOPE_BOTH = "both"

    /**
     * The coarse status to paint the row with before the server replies.
     *
     * Must agree with resolveSessionStatusFrom() in the calling backend
     * (src/modules/sessionCampaign/sessionCampaign.service.ts). Where they disagree the server
     * wins, and the optimistic paint self-heals on the next queue refresh — but a disagreement is
     * a bug, not a tolerance.
     */
    fun derive(action: String?, sentiment: String?): String = when (action) {
        ACTION_DEFER_CALLING -> "deferred"
        ACTION_FOLLOWUP_CALLBACK -> "call_back_later"
        ACTION_MOVE_TO_FOLLOWUP -> "will_attend"
        ACTION_REMOVE_FROM_RUNNING, ACTION_MARK_INVALID -> "wont_attend"
        // Reached-but-negative and never-reached are different facts and must not collapse:
        // "Will try next time" is contact, "Switch off" is not.
        ACTION_CONTINUE_CALLING ->
            if (sentiment == NOT_REACHED) "no_answer" else "called"
        // No action at all — an option this build has never seen, or a cache written by an older
        // build. `called` is the neutral status: it records that a call happened without asserting
        // an outcome, so nothing is created and nobody is struck on a guess.
        else -> "called"
    }

    /** Statuses the backend treats as positive: they create a follow-up and never strike. */
    fun isPositive(status: String) = status == "will_attend" || status == "call_back_later"

    /**
     * Which Follow-ups heading a positive response files under. A call-back is positive but is not
     * a confirmed yes, so it must never be rendered beside "Said they will attend".
     */
    fun followupKind(status: String) =
        if (status == "call_back_later") "call_back_later" else "will_attend"

    /**
     * Does this response belong under the given branch of "Did they pick the call?".
     *
     * `answered = null` means the caller has not chosen yet, so nothing is shown.
     */
    fun showsUnder(scope: String?, answered: Boolean?): Boolean {
        if (answered == null) return false
        return when (scope) {
            SCOPE_ANSWERED -> answered
            SCOPE_NOT_ANSWERED -> !answered
            // Unset, or an unknown value from a newer server: showing it under both is the safe
            // failure. Hiding a response the caller needs is worse than offering an extra one.
            else -> true
        }
    }
}
