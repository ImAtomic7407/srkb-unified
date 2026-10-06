package com.example.data

/**
 * Turning "rows 7–15 → Adidev" into the actual people that means.
 *
 * WHY THIS EXISTS. The Pool Assignment screen shows a table and numbers it 1..N in a "#" column.
 * Underneath it, the admin types row numbers and picks a caller. Those two things were reading
 * different lists:
 *
 *   the table  ->  `currentItems`  — the registration pool after the lower batch/session filter
 *   the ranges ->  `sessionMembers` — the selected campaign's whole unfiltered batch roster
 *
 * 15 rows on screen, 463 in the roster, unrelated orderings. Typing 7–15 assigned
 * `batchRoster[6..14]`: nine people the admin had never seen, while the nine on screen got nobody.
 * It reported success, because as far as the backend was concerned nine perfectly valid people had
 * been allotted. Reported live on 2026-08-15 after a caller was handed someone else's list.
 *
 * The fix is not "pass the other variable" — it is to make the mapping from *a row the admin can
 * point at* to *the id the backend wants* an explicit, tested step. Two things make that non-trivial
 * and are the reason this is a unit-tested pure function rather than a line of UI code:
 *
 *  1. The table lists REGISTRATIONS; assignment needs a BATCH MEMBER id. They are joined only by
 *     mobile number, in two different formats (see [PoolCallerResolution.matchKey]).
 *  2. Not every visible row has a batch member — the pool contains people in no batch at all. Such a
 *     row must still HOLD ITS NUMBER. Dropping it would shift every row beneath it up by one and
 *     re-create the original bug in a subtler form, so targets are positional and nullable: index
 *     i is always the row the admin sees as number i+1, and null means "this one can't be allotted".
 */
object RangeAssignment {

    /** One typed line of the assign block. Nulls are half-filled inputs, not zeroes. */
    data class Range(val from: Int?, val to: Int?, val callerId: String)

    data class Plan(
        /** callerId -> batch-member ids, in the order the rows appear on screen. */
        val byCaller: Map<String, List<String>>,
        /** callerId -> the rows it covers, e.g. "7–15" or "1–6, 12–15". For the allotment label. */
        val rowsByCaller: Map<String, String>,
        /** Rows inside a typed range that have no batch member, so cannot be allotted. */
        val unassignableRows: Int,
        /**
         * Rows inside a typed range whose person is held somewhere else (an open follow-up, or waiting
         * for a call in another session). One number, one place: they are skipped, and counted apart
         * from [unassignableRows] so the admin is told WHY rather than "not in any batch".
         */
        val busyRows: Int = 0,
        /** Row numbers typed past the end of the sheet. */
        val outOfRangeRows: Int,
        /** Lowest / highest row number covered by any range; 0 when nothing was covered. */
        val coveredFrom: Int,
        val coveredTo: Int,
    ) {
        val assignedCount: Int get() = byCaller.values.sumOf { it.size }
    }

    /**
     * One entry per visible row, in display order: the batch-member id to allot, or null if that
     * row has no batch member. Index i is the row shown as "#${i + 1}".
     */
    fun targetIdsForVisibleRows(
        visibleMobiles: List<String>,
        roster: List<BatchMemberInfo>,
    ): List<String?> {
        val idByMobile = HashMap<String, String>(roster.size)
        for (member in roster) {
            val key = PoolCallerResolution.matchKey(member.mobile) ?: continue
            // First wins: a roster that lists the same number twice is a data problem, but picking
            // deterministically beats letting the later duplicate quietly take over.
            if (!idByMobile.containsKey(key)) idByMobile[key] = member.id
        }
        return visibleMobiles.map { m -> PoolCallerResolution.matchKey(m)?.let { idByMobile[it] } }
    }

    /**
     * @param orderedTargetIds one per visible row, from [targetIdsForVisibleRows]
     * @param busyIndices rows (0-based, same indexing) whose person is busy elsewhere. They keep
     *   their number — dropping them would shift every row beneath — but are never allotted.
     */
    fun plan(ranges: List<Range>, orderedTargetIds: List<String?>, busyIndices: Set<Int> = emptySet()): Plan {
        val size = orderedTargetIds.size

        // Row index -> the caller who ends up with it. A later range overwrites an earlier one:
        // the backend upserts on (campaign, batch member), so a person named twice would land with
        // whichever write happened to go last. Resolve it here so the number we report is the
        // number that will exist, and so nobody is counted under two callers.
        val ownerByIndex = LinkedHashMap<Int, String>()
        var outOfRange = 0

        for (range in ranges) {
            val from = range.from ?: continue
            val to = range.to ?: continue
            if (range.callerId.isEmpty()) continue
            if (to < from) continue                       // reversed: a typo, not an instruction

            val firstIdx = (from - 1).coerceAtLeast(0)
            val lastIdx = (to - 1).coerceAtMost(size - 1)
            val typedRows = to - (from.coerceAtLeast(1)) + 1
            val coveredRows = if (size == 0 || firstIdx > lastIdx) 0 else lastIdx - firstIdx + 1
            outOfRange += (typedRows - coveredRows).coerceAtLeast(0)
            if (coveredRows == 0) continue

            for (i in firstIdx..lastIdx) ownerByIndex[i] = range.callerId
        }

        val byCaller = LinkedHashMap<String, MutableList<String>>()
        val rowsByCaller = LinkedHashMap<String, MutableList<Int>>()
        var unassignable = 0
        var busy = 0
        var coveredFrom = 0
        var coveredTo = 0

        // Walk the sheet top to bottom so each caller's people arrive in the order they were read
        // off the screen — the admin's "7 to 15" should look like 7 to 15 when they expand it.
        for (i in 0 until size) {
            val callerId = ownerByIndex[i] ?: continue
            val row = i + 1
            if (coveredFrom == 0 || row < coveredFrom) coveredFrom = row
            if (row > coveredTo) coveredTo = row
            if (i in busyIndices) { busy++; continue }
            rowsByCaller.getOrPut(callerId) { mutableListOf() }.add(row)
            val target = orderedTargetIds[i]
            if (target == null) { unassignable++; continue }
            byCaller.getOrPut(callerId) { mutableListOf() }.add(target)
        }

        return Plan(
            byCaller = byCaller.mapValues { it.value.toList() },
            rowsByCaller = rowsByCaller.mapValues { rowsLabel(it.value) },
            unassignableRows = unassignable,
            busyRows = busy,
            outOfRangeRows = outOfRange,
            coveredFrom = coveredFrom,
            coveredTo = coveredTo,
        )
    }

    /** [7,8,9,10,11,12,13,14,15] -> "7–15"; [1,2,3,12,13] -> "1–3, 12–13". */
    fun rowsLabel(rows: List<Int>): String {
        if (rows.isEmpty()) return ""
        val sorted = rows.sorted()
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var prev = start
        for (r in sorted.drop(1)) {
            if (r == prev + 1) { prev = r; continue }
            parts.add(if (start == prev) "$start" else "$start–$prev")
            start = r; prev = r
        }
        parts.add(if (start == prev) "$start" else "$start–$prev")
        return parts.joinToString(", ")
    }
}
