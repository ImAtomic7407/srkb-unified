package com.example.data

/**
 * Which meanings each behaviour can legally carry — the app's copy of `src/lib/response-rules.ts`
 * in the calling backend.
 *
 * This exists so the editor can make a contradiction **unselectable** rather than accept it and
 * warn about it afterwards. A centre admin picking "Keep calling them" should simply never see
 * "They said yes" offered; being told off for choosing it is a worse experience and leaves the
 * screen showing a state the server will silently rewrite.
 *
 * The server still normalises every write. This copy is for the UI and is NEVER the authority:
 * if the two ever disagree, the server's answer is what the row ends up holding. Keeping them in
 * step is a maintenance duty, which is why both files carry the same table in the same order.
 */
object ResponseRules {

    /**
     * ORDER MATTERS: entry [0] is the fail-safe the server coerces to when a sentiment is missing
     * or illegal, so it must always be the least destructive option for that behaviour. Wrongly
     * striking someone eventually removes them from the programme; wrongly not striking merely
     * leaves them in the calling list where a human sees them again.
     */
    private val SENTIMENTS_BY_ACTION: Map<String, List<String>> = mapOf(
        ResponseMeaning.ACTION_MOVE_TO_FOLLOWUP to listOf(ResponseMeaning.POSITIVE),
        ResponseMeaning.ACTION_FOLLOWUP_CALLBACK to listOf(ResponseMeaning.POSITIVE),
        ResponseMeaning.ACTION_DEFER_CALLING to listOf(ResponseMeaning.POSITIVE),
        ResponseMeaning.ACTION_CONTINUE_CALLING to listOf(ResponseMeaning.NOT_REACHED, ResponseMeaning.NEGATIVE),
        ResponseMeaning.ACTION_REMOVE_FROM_RUNNING to listOf(ResponseMeaning.NEGATIVE),
        ResponseMeaning.ACTION_MARK_INVALID to listOf(ResponseMeaning.NEGATIVE),
    )

    /** The six behaviours that apply to calling, in the order the editor offers them. */
    val CALLING_ACTIONS: List<String> = listOf(
        ResponseMeaning.ACTION_MOVE_TO_FOLLOWUP,
        ResponseMeaning.ACTION_FOLLOWUP_CALLBACK,
        ResponseMeaning.ACTION_DEFER_CALLING,
        ResponseMeaning.ACTION_CONTINUE_CALLING,
        ResponseMeaning.ACTION_REMOVE_FROM_RUNNING,
        ResponseMeaning.ACTION_MARK_INVALID,
    )

    /**
     * Plain English for a non-technical centre admin. The old screen showed raw enum names
     * ("move_to_followup") to people who have never seen an enum.
     */
    fun actionLabel(action: String?): String = when (action) {
        ResponseMeaning.ACTION_MOVE_TO_FOLLOWUP -> "Move to follow-up"
        ResponseMeaning.ACTION_FOLLOWUP_CALLBACK -> "Ring them back"
        ResponseMeaning.ACTION_DEFER_CALLING -> "Call again later"
        ResponseMeaning.ACTION_CONTINUE_CALLING -> "Keep calling them"
        ResponseMeaning.ACTION_REMOVE_FROM_RUNNING -> "Stop calling them"
        ResponseMeaning.ACTION_MARK_INVALID -> "Number is no good"
        null -> "Not set"
        else -> action.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    fun actionHelp(action: String?): String = when (action) {
        ResponseMeaning.ACTION_MOVE_TO_FOLLOWUP -> "They are coming. Goes to Follow-ups."
        ResponseMeaning.ACTION_FOLLOWUP_CALLBACK -> "They asked to be rung back. Goes to Follow-ups under its own heading."
        ResponseMeaning.ACTION_DEFER_CALLING -> "Hidden from the calling list until the days below have passed."
        ResponseMeaning.ACTION_CONTINUE_CALLING -> "Stays in the calling list for the next round."
        ResponseMeaning.ACTION_REMOVE_FROM_RUNNING -> "Taken out of the calling list."
        ResponseMeaning.ACTION_MARK_INVALID -> "Wrong or dead number. Taken out and flagged."
        else -> ""
    }

    fun sentimentLabel(sentiment: String): String = when (sentiment) {
        ResponseMeaning.POSITIVE -> "They said yes"
        ResponseMeaning.NEGATIVE -> "They said no"
        ResponseMeaning.NOT_REACHED -> "Couldn't reach them"
        else -> sentiment
    }

    fun scopeLabel(scope: String): String = when (scope) {
        ResponseMeaning.SCOPE_ANSWERED -> "Only when they picked up"
        ResponseMeaning.SCOPE_NOT_ANSWERED -> "Only when they didn't pick up"
        ResponseMeaning.SCOPE_BOTH -> "Both"
        else -> scope
    }

    val ALL_SCOPES: List<String> = listOf(
        ResponseMeaning.SCOPE_ANSWERED,
        ResponseMeaning.SCOPE_NOT_ANSWERED,
        ResponseMeaning.SCOPE_BOTH,
    )

    val ALL_SENTIMENTS: List<String> = listOf(
        ResponseMeaning.POSITIVE,
        ResponseMeaning.NEGATIVE,
        ResponseMeaning.NOT_REACHED,
    )

    /** Meanings this behaviour permits. Everything else is greyed out, not merely warned about. */
    fun allowedSentiments(action: String?): List<String> =
        SENTIMENTS_BY_ACTION[action] ?: listOf(ResponseMeaning.NEGATIVE)

    /**
     * Only a refusal can count against someone. Failing to reach them is not their fault, and a
     * response meaning they SAID YES cannot simultaneously push them toward removal.
     */
    fun strikeAllowed(sentiment: String): Boolean = sentiment == ResponseMeaning.NEGATIVE

    /**
     * Which meanings may carry a "take them off after N of these in a row" rule.
     *
     * Deliberately WIDER than [strikeAllowed], and the difference is the whole point - it mirrors
     * consecutiveRuleAllowed in the backend's src/lib/response-rules.ts. A phone that is switched
     * off every single time is not a refusal: it must never inflate the absence tally that session
     * forecasts and tolerance archiving read, so it stays "couldn't reach them" and can never
     * strike. A centre may still decide that N unanswerable calls in a row means stop carrying the
     * person. That is a separate, explicitly configured rule, not a strike.
     *
     * This app previously gated the limit on `strike`, which - since only a refusal may strike -
     * made the rule UNREACHABLE for exactly the case it is most wanted for. The field simply never
     * appeared for "No answer", "Switch off" or "Busy", so a centre could not express the policy at
     * all. The server has allowed it since the response-semantics work; only the app said no.
     */
    fun consecutiveRuleAllowed(sentiment: String): Boolean =
        sentiment == ResponseMeaning.NEGATIVE || sentiment == ResponseMeaning.NOT_REACHED

    /**
     * Legal range for the consecutive limit, mirroring dropdowns.schema.ts on the server.
     *
     * 1 is refused on purpose: "remove after 1 in a row" is just an ordinary removal, and the
     * behaviour "Taken out of the calling list" already expresses that honestly. Sending 1 anyway
     * is what produced the bare "Validation failed" an admin hit while trying to configure an
     * immediate removal - so the value is dropped here rather than posted and rejected.
     */
    const val MIN_CONSECUTIVE = 2
    const val MAX_CONSECUTIVE = 20

    /** True when [text] is typed but could never be saved, so the editor can say why in words. */
    fun consecutiveOutOfRange(text: String): Boolean =
        text.isNotBlank() && (text.toIntOrNull() ?: 0) !in MIN_CONSECUTIVE..MAX_CONSECUTIVE

    /** A wrong number is discoverable whether or not anyone picked up, so its scope is fixed. */
    fun scopeLocked(action: String?): Boolean = action == ResponseMeaning.ACTION_MARK_INVALID

    /** Only "Call again later" has an interval, and it must be usable. */
    fun needsDeferDays(action: String?): Boolean = action == ResponseMeaning.ACTION_DEFER_CALLING

    /**
     * Coerce a configuration to the nearest legal one — the same rule the server applies on write.
     *
     * The editor calls this every time a field changes, so the screen can never sit in a state the
     * server would rewrite behind the admin's back: choosing a behaviour that forbids the current
     * meaning moves the meaning at the same moment, in front of them.
     */
    fun normalise(
        action: String?,
        sentiment: String?,
        countsAsStrike: Boolean,
        removeAfterConsecutive: Int?,
        answeredScope: String?,
        deferDays: Int?,
    ): Normalised {
        val allowed = allowedSentiments(action)
        val resolvedSentiment =
            if (sentiment != null && allowed.contains(sentiment)) sentiment
            else allowed.firstOrNull() ?: ResponseMeaning.NOT_REACHED
        val strike = strikeAllowed(resolvedSentiment) && countsAsStrike
        // Gated on MEANING, not on `strike` - see consecutiveRuleAllowed. Out-of-range values are
        // dropped to null rather than sent, so the server's min/max can no longer surface as an
        // unexplained "Validation failed" in front of the admin.
        val consecutive = if (consecutiveRuleAllowed(resolvedSentiment) &&
            removeAfterConsecutive != null &&
            removeAfterConsecutive in MIN_CONSECUTIVE..MAX_CONSECUTIVE
        ) removeAfterConsecutive else null
        val scope = if (scopeLocked(action)) ResponseMeaning.SCOPE_BOTH
            else answeredScope ?: ResponseMeaning.SCOPE_BOTH
        // 7 matches the server's fallback in resolveSessionOutcome, so the two agree rather
        // than drift.
        val days = if (needsDeferDays(action)) (deferDays?.takeIf { it > 0 } ?: 7) else null
        return Normalised(resolvedSentiment, strike, consecutive, scope, days)
    }

    data class Normalised(
        val sentiment: String,
        val countsAsStrike: Boolean,
        val removeAfterConsecutive: Int?,
        val answeredScope: String,
        val deferDays: Int?,
    )
}
