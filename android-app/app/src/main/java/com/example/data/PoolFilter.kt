package com.example.data

/**
 * The Pool page's "who from the selected sessions?" filter: which RESPONSES they gave when called
 * for the session, and whether they ATTENDED it.
 *
 * WHY THIS REPLACED THE THREE CHIPS. Attended / Positive response / Need check were three fixed
 * questions, and the admins' real questions sit between them: "who said Will attend and then did not
 * come?" (12 Sep BG: 80 positive, 1 attended), "who said no but turned up anyway?". Any mix of
 * response groups, single responses and attended / not attended can now be asked, and the three
 * old chips are exact special cases of it (verified against live data on 2026-09-27):
 *   Attended          = no response filter, attended only
 *   Positive response = Positive group, either attendance
 *   Need check        = Neutral group (which carries "Not called yet"), either attendance
 *
 * It travels through the page as the same String the old chip did (`filterInclude`), so the
 * allotment snapshot, the "put the filter back" tap and the filter signature all keep working
 * unchanged. The old values still decode, so an allotment made on an older build restores to
 * exactly the list it was made from. "both" is the one old value this model cannot express (it is
 * attended OR positive, not AND), so it is left as a legacy string and fetched the old way.
 *
 * Pure Kotlin, no org.json, so it can be unit-tested on the JVM like Allotment.
 */
data class PoolFilter(
    /** Whole meaning groups: positive | negative | not_reached. A response added later is included. */
    val sentiments: Set<String> = emptySet(),
    /** Single responses picked from a group that is not wholly ticked. Option ids. */
    val responseIds: Set<Int> = emptySet(),
    /** Allotted to a caller for the session but never rung. Lives under Neutral in the sheet. */
    val neverCalled: Boolean = false,
    val attended: Boolean = true,
    val notAttended: Boolean = false,
) {
    val filtersByResponse: Boolean get() = sentiments.isNotEmpty() || responseIds.isNotEmpty() || neverCalled

    /** Server value: ticking both or neither means attendance is not a condition. */
    val attendance: String get() = when {
        attended && !notAttended -> "present"
        notAttended && !attended -> "absent"
        else -> "any"
    }

    fun encode(): String = buildString {
        append(PREFIX)
        append("|s:").append(sentiments.sorted().joinToString("+"))
        append("|r:").append(responseIds.sorted().joinToString("+"))
        append("|n:").append(if (neverCalled) "1" else "0")
        append("|a:").append(if (attended) "1" else "0")
        append("|x:").append(if (notAttended) "1" else "0")
    }

    /** Short, human description for the filter button and the allotment heading. */
    fun describe(responseLabels: Map<Int, String> = emptyMap()): String {
        val parts = mutableListOf<String>()
        for (s in SENTIMENTS) if (s in sentiments) parts += GROUP_NAMES.getValue(s)
        if (responseIds.isNotEmpty()) {
            // Labels are only known while the sheet's list is loaded. Without them, do not print an id
            // count: one response can sit behind two ids (a centre's override of a shared option).
            val named = responseIds.mapNotNull { responseLabels[it] }.distinct()
            parts += when {
                named.isEmpty() -> "selected responses"
                named.size <= 2 -> named.joinToString(", ")
                else -> "${named.size} responses"
            }
        }
        if (neverCalled && SENTIMENT_NEUTRAL !in sentiments) parts += "Not called yet"
        val who = if (parts.isEmpty()) "Everyone" else parts.joinToString(" + ")
        val att = when (attendance) {
            "present" -> "attended"
            "absent" -> "did not attend"
            else -> null
        }
        return when {
            parts.isEmpty() && att == "attended" -> "Attended"
            parts.isEmpty() && att == "did not attend" -> "Did not attend"
            att == null -> who
            else -> "$who · $att"
        }
    }

    /**
     * The old include= value that asks exactly this, or null if only the new endpoint can. Lets a
     * phone that updated before the server did still load the three filters the old server knows.
     */
    fun legacyEquivalent(): String? = when (this) {
        DEFAULT -> "attended"
        PoolFilter(sentiments = setOf(SENTIMENT_POSITIVE), attended = false) -> "responded"
        PoolFilter(sentiments = setOf(SENTIMENT_NEUTRAL), neverCalled = true, attended = false) -> "need_check"
        else -> null
    }

    companion object {
        const val PREFIX = "outcome"
        const val SENTIMENT_POSITIVE = "positive"
        const val SENTIMENT_NEGATIVE = "negative"
        const val SENTIMENT_NEUTRAL = "not_reached"
        val SENTIMENTS = listOf(SENTIMENT_POSITIVE, SENTIMENT_NEGATIVE, SENTIMENT_NEUTRAL)
        val GROUP_NAMES = mapOf(
            SENTIMENT_POSITIVE to "Positive",
            SENTIMENT_NEGATIVE to "Negative",
            SENTIMENT_NEUTRAL to "Neutral",
        )

        /** What the page opens on — the same list the old default "Attended" chip gave. */
        val DEFAULT = PoolFilter()

        /** A value the new model cannot express; it is fetched the old way. */
        fun isLegacy(value: String): Boolean = decode(value) == null

        /**
         * Old chip values map to their exact equivalent; "both" and anything unreadable give null.
         */
        fun decode(value: String?): PoolFilter? {
            if (value.isNullOrBlank()) return DEFAULT
            return when (value) {
                "attended" -> DEFAULT
                "responded" -> PoolFilter(sentiments = setOf(SENTIMENT_POSITIVE), attended = false)
                "need_check" -> PoolFilter(sentiments = setOf(SENTIMENT_NEUTRAL), neverCalled = true, attended = false)
                else -> if (value.startsWith("$PREFIX|")) parse(value) else null
            }
        }

        /** Rewrites an old chip value as the new format; "both" stays as it is. */
        fun normalize(value: String?): String = decode(value)?.encode() ?: value.orEmpty()

        private fun parse(value: String): PoolFilter? = try {
            val f = value.split("|").drop(1).mapNotNull { part ->
                val i = part.indexOf(':')
                if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
            }.toMap()
            fun list(k: String) = f[k].orEmpty().split("+").filter { it.isNotBlank() }
            PoolFilter(
                sentiments = list("s").filter { it in SENTIMENTS }.toSet(),
                responseIds = list("r").mapNotNull { it.toIntOrNull() }.toSet(),
                neverCalled = f["n"] == "1",
                attended = f["a"] != "0",
                notAttended = f["x"] == "1",
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** One row in the filter sheet: a response, the option ids behind it, and how many people gave it. */
data class SessionResponseOption(
    val label: String,
    /** positive | negative | not_reached, or null for a response with no declared meaning. */
    val sentiment: String?,
    val optionIds: List<Int>,
    val count: Int,
)

data class SessionResponses(
    val responses: List<SessionResponseOption>,
    val neverCalledCount: Int,
    /** False when none of the chosen sessions had calling — only attendance can be filtered then. */
    val anyCalling: Boolean,
)

/**
 * The tick logic of the filter sheet, kept here so it is tested rather than eyeballed.
 *
 * A group is ALL (its sentiment is in `sentiments`), SOME (some of its responses are picked singly)
 * or NONE. Unticking one response of a wholly ticked group turns the group into single picks of the
 * rest; ticking the last missing response turns it back into the whole group, so the saved filter
 * keeps following the group if the centre later adds a response to it.
 */
object PoolFilterTicks {
    enum class State { ALL, SOME, NONE }

    private fun groupIds(options: List<SessionResponseOption>, sentiment: String) =
        options.filter { it.sentiment == sentiment }.flatMap { it.optionIds }.toSet()

    private fun hasNeverCalledItem(sentiment: String) = sentiment == PoolFilter.SENTIMENT_NEUTRAL

    fun groupState(f: PoolFilter, options: List<SessionResponseOption>, sentiment: String): State {
        if (sentiment in f.sentiments) return State.ALL
        val anyPicked = groupIds(options, sentiment).any { it in f.responseIds } ||
            (hasNeverCalledItem(sentiment) && f.neverCalled)
        return if (anyPicked) State.SOME else State.NONE
    }

    fun toggleGroup(f: PoolFilter, options: List<SessionResponseOption>, sentiment: String): PoolFilter {
        val ids = groupIds(options, sentiment)
        val neverCalledHere = hasNeverCalledItem(sentiment)
        return if (groupState(f, options, sentiment) == State.ALL) {
            f.copy(
                sentiments = f.sentiments - sentiment,
                responseIds = f.responseIds - ids,
                neverCalled = if (neverCalledHere) false else f.neverCalled,
            )
        } else {
            f.copy(
                sentiments = f.sentiments + sentiment,
                responseIds = f.responseIds - ids,
                neverCalled = if (neverCalledHere) true else f.neverCalled,
            )
        }
    }

    fun isResponseTicked(f: PoolFilter, option: SessionResponseOption): Boolean =
        (option.sentiment != null && option.sentiment in f.sentiments) || option.optionIds.any { it in f.responseIds }

    fun toggleResponse(f: PoolFilter, options: List<SessionResponseOption>, option: SessionResponseOption): PoolFilter {
        val sentiment = option.sentiment
        if (sentiment != null && sentiment in f.sentiments) {
            // Break the whole group into single picks of everything except this one.
            val rest = groupIds(options, sentiment) - option.optionIds.toSet()
            return f.copy(sentiments = f.sentiments - sentiment, responseIds = f.responseIds + rest)
        }
        val next = if (isResponseTicked(f, option)) f.copy(responseIds = f.responseIds - option.optionIds.toSet())
        else f.copy(responseIds = f.responseIds + option.optionIds)
        return collapse(next, options, sentiment)
    }

    fun toggleNeverCalled(f: PoolFilter, options: List<SessionResponseOption>): PoolFilter {
        val neutral = PoolFilter.SENTIMENT_NEUTRAL
        if (neutral in f.sentiments) {
            return f.copy(sentiments = f.sentiments - neutral, responseIds = f.responseIds + groupIds(options, neutral), neverCalled = false)
        }
        return collapse(f.copy(neverCalled = !f.neverCalled), options, neutral)
    }

    /** Every response of a group picked singly == the whole group. */
    private fun collapse(f: PoolFilter, options: List<SessionResponseOption>, sentiment: String?): PoolFilter {
        if (sentiment == null) return f
        val ids = groupIds(options, sentiment)
        if (ids.isEmpty() || !f.responseIds.containsAll(ids)) return f
        if (hasNeverCalledItem(sentiment) && !f.neverCalled) return f
        return f.copy(sentiments = f.sentiments + sentiment, responseIds = f.responseIds - ids)
    }
}
