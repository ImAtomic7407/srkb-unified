package com.example.data

/**
 * Type-ahead for the free-text profile fields on the registration screen (address, occupation).
 *
 * WHY THIS EXISTS. At a festival one centre registers people from the same dozen localities over
 * and over, and the operator was typing each address out in full every time. The values are
 * already in the system — roster.json carries `address` and `occupation` for every member, and
 * the app holds all of them in `AppDatabase.registrations` — they were simply never offered.
 * The address field read ONLY the admin-curated `dropdownOptions` (context "address"), which is a
 * short hand-maintained list, and occupation had no suggestions at all.
 *
 * So suggestions are the UNION of three sources, deliberately, in this order of authority:
 *   1. what this operator typed most recently  (MRU — the "same locality 100 times" case)
 *   2. what the whole centre has already recorded  (frequency across the roster — "hotness")
 *   3. the admin's curated list  (kept, so a deliberate entry never drops off)
 *
 * Everything is computed on the device from data already fetched. No new endpoint, no server
 * change, and it works offline — which matters, because the offline queue is the thing that keeps
 * a festival's entries safe when the tunnel wobbles.
 */
object FieldSuggestions {

    const val ADDRESS = "address"
    const val OCCUPATION = "occupation"

    /** How many recently-typed values to remember per field, per centre+category. */
    private const val MRU_LIMIT = 24

    /**
     * Unit Separator. Chosen over a comma or pipe because an address legitimately contains both,
     * and a separator that can appear in the data silently splits one entry into two.
     */
    private const val SEP = "\u001F"

    /**
     * Trim and collapse internal runs of whitespace. Defined once because every entry point that
     * accepts a typed value has to apply the SAME normalisation — a field that tidies on display
     * but not on storage produces two candidates that look identical in the list.
     */
    private fun tidy(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    /**
     * Case- and whitespace-insensitive identity. "Centre A  Colony", "sample colony" and
     * " Sample Colony " are one place, and offering them as three suggestions is worse than
     * offering none — the operator has to read all three to discover they are the same.
     */
    fun normKey(raw: String): String = tidy(raw).lowercase()

    /** One suggestable value: how it should be shown, and how often the centre has used it. */
    data class Candidate(val label: String, val count: Int)

    /**
     * Collapse raw field values into ranked candidates, most-used first.
     *
     * EXPENSIVE — it walks every roster row. Call it from `remember(...)` keyed on something that
     * changes only when the roster does, never per keystroke: 1800 rows growing past 2800 during
     * the event, on a cheap phone, with the keyboard up, is exactly where a per-keystroke rebuild
     * turns into visible input lag.
     *
     * The displayed label is the most common original casing rather than the first one seen, so a
     * single stray "SAMPLE COLONY" cannot rename a locality that 200 rows spell normally.
     */
    fun buildCorpus(values: List<String>): List<Candidate> {
        val byKey = HashMap<String, HashMap<String, Int>>()
        for (v in values) {
            val label = tidy(v)
            if (label.isEmpty()) continue
            // Guard against a whole address being pasted into the field, and against the
            // placeholder junk ("-", "na", "n/a") that accumulates in any free-text column.
            if (label.length > 120) continue
            if (normKey(label) in JUNK) continue
            byKey.getOrPut(normKey(label)) { HashMap() }.merge(label, 1, Int::plus)
        }
        return byKey.values
            .map { spellings ->
                Candidate(
                    label = spellings.maxByOrNull { it.value }!!.key,
                    count = spellings.values.sum(),
                )
            }
            .sortedWith(compareByDescending<Candidate> { it.count }.thenBy { it.label.lowercase() })
    }

    /** Values that are technically present but mean "nobody filled this in". */
    private val JUNK = setOf("", "-", "--", "na", "n/a", "nil", "none", "null", "no", "x", ".")

    /**
     * Rank [corpus] against what the operator has typed so far.
     *
     * Cheap: a filter and a sort over an already-built list, so this one IS safe per keystroke.
     *
     * Ordering, highest first:
     *   - a value this operator typed recently, most recent first
     *   - then a prefix match ahead of a mid-string match ("Ram" should offer "Rampur" before
     *     "Shri Rampur", because prefix is what the operator is steering towards)
     *   - then how often the centre has used it
     *
     * An empty query returns the hot list itself, which is the point: opening the dropdown before
     * typing anything shows the localities this centre actually uses.
     */
    fun rank(
        query: String,
        corpus: List<Candidate>,
        recents: List<String>,
        limit: Int = 8,
    ): List<String> {
        val q = normKey(query)

        // Fast path: nothing typed yet, so the answer IS the hot list. `corpus` already arrives
        // sorted by count, so this avoids re-sorting a few thousand candidates on every
        // recomposition while the dropdown sits open with an empty field.
        if (q.isEmpty()) {
            val seen = HashSet<String>()
            val out = ArrayList<String>(limit)
            for (r in recents) {
                val label = tidy(r)
                if (label.isNotEmpty() && seen.add(normKey(label))) out.add(label)
                if (out.size == limit) return out
            }
            for (c in corpus) {
                if (seen.add(normKey(c.label))) out.add(c.label)
                if (out.size == limit) return out
            }
            return out
        }

        // Recents are merged INTO the corpus, not concatenated onto it, so a value that is both
        // recently typed and common appears once, at its recent rank.
        val recentRank = HashMap<String, Int>()
        recents.forEachIndexed { i, r -> recentRank.putIfAbsent(normKey(r), i) }

        val pool = LinkedHashMap<String, Candidate>()
        for (r in recents) {
            val label = tidy(r)
            if (label.isNotEmpty()) pool.putIfAbsent(normKey(label), Candidate(label, 0))
        }
        for (c in corpus) pool.putIfAbsent(normKey(c.label), c)
        // A recent value also carries the centre's count, so the two rankings agree.
        val counts = corpus.associate { normKey(it.label) to it.count }

        return pool.values
            .mapNotNull { c ->
                val key = normKey(c.label)
                val matched = when {
                    q.isEmpty() -> 2
                    key.startsWith(q) -> 2
                    key.contains(q) -> 1
                    else -> return@mapNotNull null
                }
                // Never offer back exactly what is already typed — it is not a suggestion, it
                // just costs the operator a line of the list.
                if (q.isNotEmpty() && key == q) return@mapNotNull null
                Triple(c.label, matched, key)
            }
            .sortedWith(
                compareByDescending<Triple<String, Int, String>> { recentRank.containsKey(it.third) }
                    .thenBy { recentRank[it.third] ?: Int.MAX_VALUE }
                    .thenByDescending { it.second }
                    .thenByDescending { counts[it.third] ?: 0 }
                    .thenBy { it.first.lowercase() }
            )
            .map { it.first }
            .take(limit)
    }

    /**
     * The values this operator typed most recently for [field], newest first.
     *
     * Stored through `AppDatabase.setUiSelection`, so it inherits the existing per-centre and
     * per-category scoping and the logout wipe for free rather than opening a second prefs file
     * with its own lifecycle.
     */
    fun recents(field: String): List<String> =
        AppDatabase.uiSelection(mruKey(field))
            ?.split(SEP)
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    /**
     * Promote [value] to the front of [field]'s recent list.
     *
     * Called on submit, which is what makes the second person from a locality nearly free to
     * enter: the address the operator just typed is the first thing offered next time.
     */
    fun recordUse(field: String, value: String) {
        val label = tidy(value)
        if (label.isEmpty() || normKey(label) in JUNK) return
        val key = normKey(label)
        // Drop any earlier spelling of the same value before prepending, so re-entering a
        // locality moves it to the front instead of accumulating duplicate rows.
        val next = (listOf(label) + recents(field).filterNot { normKey(it) == key })
            .take(MRU_LIMIT)
        AppDatabase.setUiSelection(mruKey(field), next.joinToString(SEP))
    }

    private fun mruKey(field: String) = "reg.mru.$field"

    /**
     * Shortest number query that is allowed to suggest anybody.
     *
     * Not a style choice. The centre roster holds ~1,800 people; a one-digit query matches
     * several hundred of them, which is a useless list to read and a needless sort on every
     * keystroke. Three digits is the point where the list becomes short enough to be worth
     * looking at.
     */
    const val MOBILE_MIN_QUERY = 3

    /**
     * Shortest number query allowed to leave this device and ask about OTHER centres.
     *
     * Twice MOBILE_MIN_QUERY, deliberately. The in-centre lookup reads a local file, so a loose
     * threshold costs nothing and only ever shows the operator people from their own centre. The
     * cross-centre one reaches a server that will answer about people at centres this operator
     * has no access to; at three digits that is an endpoint you could enumerate names and
     * addresses out of. At six the caller must already substantially know the number they are
     * asking about, which is the difference between a lookup and a listing.
     */
    const val CROSS_CENTRE_MIN_QUERY = 6

    /**
     * Rank roster people against a partially-typed mobile number.
     *
     * Matching is on DIGITS ONLY, on both sides. Stored mobiles carry country prefixes and
     * spaces ("+91 98350 00001"), so comparing the raw strings misses exactly the rows that need
     * finding — the same trap `lastTenDigits` exists for elsewhere.
     *
     * Three match classes, in this order:
     *   - prefix  — the operator is typing the number from the front, the normal case
     *   - suffix  — an operator often knows only the last four digits of a number they are
     *               checking against a slip of paper, and typing those must find the person
     *   - contains
     *
     * Pure, so the min-length rule and the ordering can be tested without a device or a mirror.
     */
    fun rankPeople(
        query: String,
        people: List<RosterPerson>,
        limit: Int = 5,
    ): List<RosterPerson> {
        val q = query.filter { it.isDigit() }
        if (q.length < MOBILE_MIN_QUERY) return emptyList()
        val hits = ArrayList<Pair<RosterPerson, Int>>()
        for (p in people) {
            val d = p.digits
            val rank = when {
                d.startsWith(q) -> 0
                d.endsWith(q) -> 1
                d.contains(q) -> 2
                // Deliberately no early exit once `limit` hits are found: a prefix match sitting
                // later in the roster must still outrank a contains match found first.
                else -> continue
            }
            hits.add(p to rank)
        }
        return hits
            .sortedWith(compareBy({ it.second }, { it.first.name.lowercase() }))
            .map { it.first }
            .take(limit)
    }
}
