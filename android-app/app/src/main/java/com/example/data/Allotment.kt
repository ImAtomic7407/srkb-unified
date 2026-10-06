package com.example.data

/**
 * Where an allotment came from — the list the admin was looking at when they pressed Assign.
 *
 * WHY THIS IS STORED. An allotment used to be nothing but "caller X has N people". Three things
 * were impossible and all three were reported on 2026-08-15:
 *   - seeing WHICH list a caller's people were picked from,
 *   - seeing which rows (7–15) the admin actually typed,
 *   - getting back to that list afterwards, because opening the screen resets every dropdown.
 * And a caller given a second allotment from a different list had it silently merged into the first.
 *
 * So each assignment row carries a key (which allotment it belongs to), a label (what to show) and
 * this snapshot (how to rebuild the sheet). Rows written before the feature carry none of it and
 * group under their caller alone, exactly as they always did.
 *
 * NOT JSON ON PURPOSE. `org.json` comes from android.jar, which is stubbed in JVM unit tests and
 * throws "not mocked" — this is the one piece of the feature whose correctness cannot be eyeballed,
 * so it must be unit-testable without Robolectric. The format is a flat escaped key=value list.
 */
data class AllotmentSource(
    val batchIds: List<String> = emptyList(),
    val sessionIds: List<String> = emptyList(),
    /** An encoded PoolFilter, or an older build's attended | responded | need_check | both. */
    val include: String = AppDatabase.SESSION_PEOPLE_ATTENDED,
    /** The rows the admin typed, already collapsed for display: "7–15" or "1–3, 12–13". */
    val rows: String = "",
)

/** What the app sends when allotting, one per caller named in the same press of Assign. */
data class AllotmentWrite(
    val callerId: String,
    val key: String,
    val label: String,
    val filter: String,
    /** Soft-remove this caller's other people in this session first. Destructive — always prompted. */
    val replace: Boolean = false,
)

/**
 * One heading in the "Allotted callers" list: a caller, the list their people came from, and the
 * rows of it they were given. A caller allotted from two lists has two of these.
 */
data class AllotmentGroup(
    val callerId: String,
    val callerName: String,
    val key: String?,
    val label: String?,
    val filter: String?,
    val rows: List<SessionCallAssignmentDetail>,
) {
    /** "7–15", or null for a row written before provenance existed. */
    val rowRange: String? get() = Allotment.decode(filter)?.rows?.ifBlank { null }
}

object Allotment {

    // ── Wire format ───────────────────────────────────────────────────────────
    // batches=18,19;sessions=01-08-26,8%20Aug;include=attended;rows=7%E2%80%9315
    // Every value is escaped for the four characters that carry structure, so a session id
    // containing a comma or an equals sign cannot split a field. Unknown keys are ignored, so a
    // future field added by a newer build will not break an older one.

    private fun esc(s: String) = s
        .replace("%", "%25").replace(";", "%3B").replace(",", "%2C").replace("=", "%3D")

    private fun unesc(s: String) = s
        .replace("%3D", "=").replace("%2C", ",").replace("%3B", ";").replace("%25", "%")

    fun encode(source: AllotmentSource): String = listOf(
        "batches=" + source.batchIds.joinToString(",") { esc(it) },
        "sessions=" + source.sessionIds.joinToString(",") { esc(it) },
        "include=" + esc(source.include),
        "rows=" + esc(source.rows),
    ).joinToString(";")

    /** Null for anything unparseable — a bad snapshot must never take the screen with it. */
    fun decode(encoded: String?): AllotmentSource? {
        if (encoded.isNullOrBlank()) return null
        return try {
            val fields = encoded.split(";")
                .mapNotNull { part ->
                    val i = part.indexOf('=')
                    if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
                }
                .toMap()
            // A snapshot naming neither a batch nor a session restores nothing, so treat it as absent
            // rather than handing back something that silently clears the admin's current filter.
            val batches = splitList(fields["batches"])
            val sessions = splitList(fields["sessions"])
            if (batches.isEmpty() && sessions.isEmpty()) return null
            AllotmentSource(
                batchIds = batches,
                sessionIds = sessions,
                include = fields["include"]?.let { unesc(it) }?.ifBlank { null }
                    ?: AppDatabase.SESSION_PEOPLE_ATTENDED,
                rows = fields["rows"]?.let { unesc(it) } ?: "",
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun splitList(raw: String?): List<String> =
        raw.orEmpty().split(",").map { unesc(it) }.filter { it.isNotBlank() }

    // ── What the admin reads ──────────────────────────────────────────────────

    /**
     * Session names are admin-typed and occasionally enormous. Bound them: an unbounded heading
     * both wraps the card into nonsense AND can push the whole payload past the server's length
     * limit — and provenance rides in the same request as the allotment itself.
     */
    private fun clamp(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

    /** "attended" -> "attended", for the heading. A PoolFilter value describes itself. */
    fun includeLabel(include: String): String = when (include) {
        AppDatabase.SESSION_PEOPLE_ATTENDED -> "attended"
        AppDatabase.SESSION_PEOPLE_RESPONDED -> "positive response"
        AppDatabase.SESSION_PEOPLE_NEED_CHECK -> "need check"
        AppDatabase.SESSION_PEOPLE_BOTH -> "attended or positive"
        else -> PoolFilter.decode(include)?.describe()?.replaceFirstChar { it.lowercase() } ?: "attended"
    }

    /**
     * The heading for one allotment, e.g. "8 Aug BG · attended" or "BG + 2 more".
     *
     * Names, never ids: an id in a heading tells the admin nothing about which list it was.
     * Truncated at two so a wide multi-select cannot push the caller's name off the card.
     */
    fun label(sessionNames: List<String>, batchNames: List<String>, include: String): String {
        val names = sessionNames.ifEmpty { batchNames }
        if (names.isEmpty()) return "All people"
        val head = names.take(2).joinToString(" + ") { clamp(it, 40) }
        val shown = if (names.size > 2) "$head + ${names.size - 2} more" else head
        // The Show mode only means something for sessions — a batch roster has no responses.
        return if (sessionNames.isEmpty()) shown else "$shown · ${includeLabel(include)}"
    }

    /**
     * Identifies one allotment. Scoped per caller and stamped with the moment it was made, so two
     * allotments to the same caller from two different lists never collide — that collision is
     * exactly what made the second one disappear into the first.
     */
    fun newKey(callerId: String, nowMillis: Long): String = "a$nowMillis-$callerId"

    // ── Grouping ──────────────────────────────────────────────────────────────

    /**
     * The "Allotted callers" list, one entry per (caller, allotment).
     *
     * Grouped by caller AND key, not by caller alone: that is what keeps a second allotment from a
     * different list visible instead of absorbed into the first. Rows with no key (everything
     * written before this feature) collapse into a single un-headed group per caller, so old
     * sessions look exactly as they did.
     *
     * Order is first-appearance, which is the order the server returns and therefore roughly the
     * order the allotments were made.
     */
    fun group(details: List<SessionCallAssignmentDetail>): List<AllotmentGroup> {
        val byKey = LinkedHashMap<Pair<String, String?>, MutableList<SessionCallAssignmentDetail>>()
        for (d in details) byKey.getOrPut(d.callerId to d.allotmentKey) { mutableListOf() }.add(d)
        return byKey.map { (id, rows) ->
            val first = rows.first()
            AllotmentGroup(
                callerId = id.first,
                callerName = first.callerName,
                key = id.second,
                // Take the label off whichever row carries one: a group written in one press shares
                // it, but a hand-edited row could be missing it without meaning "no heading".
                label = rows.firstNotNullOfOrNull { it.allotmentLabel },
                filter = rows.firstNotNullOfOrNull { it.allotmentFilter },
                rows = rows.toList(),
            )
        }
    }
}
