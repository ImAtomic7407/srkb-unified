package com.example

import com.example.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the per-batch counting rule behind the registration screen's picker and its live roll.
 *
 * The failure these protect against is a CONFIDENT WRONG NUMBER, which is what shipped before:
 * the picker read a `member_count` field that Flask's Batch.to_dict() has never emitted, so every
 * batch in every centre reported "0 members" — including Centre A's BG, which holds 698 people.
 * A count is only worth showing if it is right, so the shape of roster.json is pinned here.
 *
 * Robolectric because org.json is a stub in a plain unit test. Only the pure parse is covered;
 * the SharedPreferences mirror around it needs a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BatchRosterIndexTest {

    /** The real roster.json row shape, as written by regenerate_cache_files. */
    private fun row(mobile: String, name: String, batchId: Int?): String {
        val bid = batchId?.toString() ?: "null"
        return """{"mobile":"$mobile","name":"$name","address":"","occupation":"",""" +
            """"rounds":null,"remark":"","batch_id":$bid,"batch_name":"b","category":"youth",""" +
            """"legacy_classes":null}"""
    }

    private fun body(vararg rows: String) = rows.joinToString(",", "[", "]")

    @Test
    fun `rows are grouped by batch id`() {
        val idx = AppDatabase.indexRosterJson(
            body(
                row("9000000001", "Asha", 18),
                row("9000000002", "Bhola", 18),
                row("9000000003", "Chandan", 20),
            )
        )!!
        assertEquals(2, idx["18"]?.size)
        assertEquals(1, idx["20"]?.size)
    }

    @Test
    fun `centre-level rows are not counted against any batch`() {
        // 1,601 of Centre B's 1,804 roster rows have batch_id NULL — they belong to the
        // centre, not to a class. Counting them would inflate every number on the screen.
        val idx = AppDatabase.indexRosterJson(
            body(
                row("9000000001", "Asha", null),
                row("9000000002", "Bhola", null),
                row("9000000003", "Chandan", 3),
            )
        )!!
        assertEquals(1, idx.values.sumOf { it.size })
        assertEquals(listOf("3"), idx.keys.toList())
    }

    @Test
    fun `a batch nobody is in is absent, not zero`() {
        // Centre A's batch 22 'Festival' has no rows at all. The caller reads an absent key as
        // 0 only because it has separately confirmed the mirror exists.
        val idx = AppDatabase.indexRosterJson(body(row("9000000001", "Asha", 18)))!!
        assertNull(idx["22"])
    }

    @Test
    fun `name and mobile survive intact`() {
        val idx = AppDatabase.indexRosterJson(body(row("9835000001", "Rasamrita Sindhu das", 18)))!!
        val r = idx["18"]!!.single()
        assertEquals("9835000001", r.mobile)
        assertEquals("Rasamrita Sindhu das", r.name)
    }

    @Test
    fun `a nameless row is labelled rather than blank`() {
        val idx = AppDatabase.indexRosterJson(body(row("9000000001", "   ", 18)))!!
        assertEquals("Unknown", idx["18"]!!.single().name)
    }

    @Test
    fun `an unparseable body returns null, never an empty index`() {
        // This is the distinction that keeps "0 members" from coming back: null means "no usable
        // roster, show no number", whereas an empty map would be reported as a real zero.
        assertNull(AppDatabase.indexRosterJson("<html>502 Bad Gateway</html>"))
        assertNull(AppDatabase.indexRosterJson("{\"error\":\"Forbidden\"}"))
    }

    @Test
    fun `an absent mirror is an empty index, not a failure`() {
        assertEquals(emptyMap<String, List<Any>>(), AppDatabase.indexRosterJson(""))
    }

    @Test
    fun `order within a batch is preserved as the server sent it`() {
        // roster.json is ordered by name server-side; the roll relies on that ordering and must
        // not shuffle it.
        val idx = AppDatabase.indexRosterJson(
            body(
                row("9000000001", "Asha", 18),
                row("9000000002", "Bhola", 18),
                row("9000000003", "Chandan", 18),
            )
        )!!
        assertEquals(listOf("Asha", "Bhola", "Chandan"), idx["18"]!!.map { it.name })
    }

    // ── Forward compatibility ────────────────────────────────────────────────────────────────
    // roster.json carries neither of these fields today. The parser reads them if it ever does,
    // which is what lets the server half of "who filed this row, and when" ship without another
    // app release — so these tests pin the contract the server will have to meet.

    private fun datedRow(mobile: String, name: String, batchId: Int, at: String, by: String?): String {
        val byField = if (by == null) "null" else "\"$by\""
        return """{"mobile":"$mobile","name":"$name","address":"","occupation":"",""" +
            """"rounds":null,"remark":"","batch_id":$batchId,"batch_name":"b","category":"youth",""" +
            """"legacy_classes":null,"created_at":"$at","registered_by":$byField}"""
    }

    @Test
    fun `registered_by and created_at are read when the server sends them`() {
        val idx = AppDatabase.indexRosterJson(
            body(datedRow("9000000001", "Asha", 18, "2026-09-04 18:30:00", "Gaurav"))
        )!!
        val r = idx["18"]!!.single()
        assertEquals("Gaurav", r.registeredBy)
        assertEquals("2026-09-04 18:30:00", r.createdAt)
    }

    @Test
    fun `dated rows come back newest first`() {
        // Name order is what the server sends; once rows carry a timestamp the roll must lead
        // with the person who just walked in, not with whoever sorts first alphabetically.
        val idx = AppDatabase.indexRosterJson(
            body(
                datedRow("9000000001", "Asha", 18, "2026-09-04 18:00:00", "Gaurav"),
                datedRow("9000000002", "Bhola", 18, "2026-09-04 19:30:00", "Shivam"),
                datedRow("9000000003", "Chandan", 18, "2026-09-04 18:45:00", "Gaurav"),
            )
        )!!
        assertEquals(listOf("Bhola", "Chandan", "Asha"), idx["18"]!!.map { it.name })
    }

    @Test
    fun `undated rows sink below dated ones instead of shuffling`() {
        val idx = AppDatabase.indexRosterJson(
            body(
                row("9000000001", "Asha", 18),
                row("9000000002", "Bhola", 18),
                datedRow("9000000003", "Chandan", 18, "2026-09-04 19:00:00", "Shivam"),
            )
        )!!
        // The dated one leads; the undated pair keeps the server's own name order behind it.
        assertEquals(listOf("Chandan", "Asha", "Bhola"), idx["18"]!!.map { it.name })
    }

    @Test
    fun `a missing registered_by is null, not the string null`() {
        val idx = AppDatabase.indexRosterJson(
            body(datedRow("9000000001", "Asha", 18, "2026-09-04 18:00:00", null))
        )!!
        assertNull(idx["18"]!!.single().registeredBy)
    }

    @Test
    fun `counts scale to a real centre without dropping rows`() {
        val rows = (1..698).map { row("90000%05d".format(it), "P$it", 18) } +
            (1..434).map { row("91000%05d".format(it), "Q$it", 20) } +
            (1..1601).map { row("92000%05d".format(it), "R$it", null) }
        val idx = AppDatabase.indexRosterJson(body(*rows.toTypedArray()))!!
        assertEquals(698, idx["18"]?.size)
        assertEquals(434, idx["20"]?.size)
        // The 1,601 centre-level rows land nowhere at all — not under a batch, and not under
        // some placeholder key either. Asserting the TOTAL is what actually catches that;
        // asserting a key that was never a possible output passes vacuously.
        assertEquals(2, idx.size)
        assertEquals(698 + 434, idx.values.sumOf { it.size })
    }
}
