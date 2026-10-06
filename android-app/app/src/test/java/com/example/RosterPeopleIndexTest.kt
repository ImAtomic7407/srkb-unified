package com.example

import com.example.data.AppDatabase
import com.example.data.FieldSuggestions
import com.example.data.RosterPerson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the registration screen's NUMBER lookup — the index behind it and the ranking on top.
 *
 * The defining difference from [BatchRosterIndexTest] is the one thing most likely to be
 * "tidied" away by a later reader: this index KEEPS the rows whose `batch_id` is null. The
 * per-batch index drops them because counting them against a batch would inflate it; here they
 * are most of the centre — 1,601 of Centre B's 1,804 people have no batch at all — and
 * dropping them would make the number field unable to find five people out of six.
 *
 * Robolectric because org.json is a stub in a plain unit test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RosterPeopleIndexTest {

    /** The real roster.json row shape, as written by regenerate_cache_files. */
    private fun row(
        mobile: String,
        name: String,
        batchId: Int? = null,
        address: String = "",
        occupation: String = "",
        batchName: String? = null,
    ): String {
        val bid = batchId?.toString() ?: "null"
        val bname = batchName?.let { "\"$it\"" } ?: "null"
        return """{"mobile":"$mobile","name":"$name","address":"$address",""" +
            """"occupation":"$occupation","rounds":0,"remark":"","batch_id":$bid,""" +
            """"batch_name":$bname,"category":"youth","legacy_classes":null}"""
    }

    private fun json(vararg rows: String) = rows.joinToString(",", "[", "]")

    // ── The index ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `centre-level rows are kept, unlike the per-batch index`() {
        val raw = json(
            row("9835000001", "Ravi", batchId = null),
            row("9835000017", "Sita", batchId = 18),
        )
        val people = AppDatabase.indexRosterPeople(raw)!!
        assertEquals(2, people.size)
        // And the per-batch index over the SAME body still drops the batchless one — the two
        // answer different questions and must not be collapsed into one.
        assertEquals(1, AppDatabase.indexRosterJson(raw)!!.values.sumOf { it.size })
    }

    @Test
    fun `one row per person even when they are in several batches`() {
        // roster.json lists a membership per row, but the number field offers PEOPLE. Three
        // identical suggestions for one person is worse than none.
        val people = AppDatabase.indexRosterPeople(
            json(
                row("9835000001", "Ravi", batchId = 18),
                row("9835000001", "Ravi", batchId = 20),
                row("9835000001", "Ravi", batchId = null),
            )
        )!!
        assertEquals(1, people.size)
    }

    @Test
    fun `duplicate detection survives a stored country prefix`() {
        // The stored mobile is not normalised server-side. Matching the raw strings would treat
        // these as two people and offer the same person twice.
        val people = AppDatabase.indexRosterPeople(
            json(row("+91 98350 00001", "Ravi", 18), row("9835000001", "Ravi", 20))
        )!!
        assertEquals(1, people.size)
    }

    @Test
    fun `profile fields are carried through for prefill`() {
        val p = AppDatabase.indexRosterPeople(
            json(row("9835000001", "Ravi", 18, "Sample Colony", "Student", "BG"))
        )!!.single()
        assertEquals("Ravi", p.name)
        assertEquals("Sample Colony", p.address)
        assertEquals("Student", p.occupation)
        assertEquals("18", p.batchId)
        assertEquals("BG", p.batchName)
    }

    @Test
    fun `a batchless row reports a null batch, not a made-up one`() {
        val p = AppDatabase.indexRosterPeople(json(row("9835000001", "Ravi")))!!.single()
        assertNull(p.batchId)
        assertNull(p.batchName)
    }

    @Test
    fun `digits are stripped once, at index time`() {
        val p = AppDatabase.indexRosterPeople(json(row("+91 98350 00001", "Ravi")))!!.single()
        assertEquals("919835000001", p.digits)
        // The display form is left exactly as the server stored it.
        assertEquals("+91 98350 00001", p.mobile)
    }

    @Test
    fun `a row with no mobile is skipped`() {
        // There is nothing to look such a row up BY, and an empty suggestion that fills an empty
        // number would silently clear the field the operator is typing into.
        val people = AppDatabase.indexRosterPeople(
            json(row("", "No Number"), row("9835000001", "Ravi"))
        )!!
        assertEquals(listOf("Ravi"), people.map { it.name })
    }

    @Test
    fun `a blank name reads as Unknown rather than empty`() {
        assertEquals("Unknown", AppDatabase.indexRosterPeople(json(row("9835000001", "")))!!.single().name)
    }

    @Test
    fun `an unparseable body is null, never an empty roster`() {
        // A 502 HTML page from the tunnel must not read as "this centre has nobody in it".
        assertNull(AppDatabase.indexRosterPeople("<html>502 Bad Gateway</html>"))
        assertNull(AppDatabase.indexRosterPeople("""{"error":"unauthorized"}"""))
    }

    @Test
    fun `a blank body is an empty roster, which is a different thing`() {
        assertEquals(emptyList<RosterPerson>(), AppDatabase.indexRosterPeople(""))
    }

    @Test
    fun `holds a full centre, mostly batchless`() {
        // Centre B's real shape: 1,804 people, 1,601 of them with no batch.
        val rows = (1..1601).map { row("90000%05d".format(it), "Centre $it") } +
            (1..203).map { row("91000%05d".format(it), "Batched $it", 3) }
        val people = AppDatabase.indexRosterPeople(json(*rows.toTypedArray()))!!
        assertEquals(1804, people.size)
        assertEquals(1601, people.count { it.batchId == null })
    }

    // ── The ranking ─────────────────────────────────────────────────────────────────────────

    private val people = listOf(
        RosterPerson("9835000001", "Ravi", "", "", null, null),
        RosterPerson("9812345678", "Sita", "", "", null, null),
        RosterPerson("+91 78880 00001", "Gita", "", "", null, null),
        RosterPerson("9998000028", "Mohan", "", "", null, null),
    )

    @Test
    fun `too short a query suggests nobody`() {
        // At 1,800 people a one- or two-digit query matches hundreds. Below the floor the answer
        // is deliberately nothing rather than a list too long to read.
        assertTrue(FieldSuggestions.rankPeople("9", people).isEmpty())
        assertTrue(FieldSuggestions.rankPeople("98", people).isEmpty())
        assertTrue(FieldSuggestions.rankPeople("983", people).isNotEmpty())
    }

    @Test
    fun `a prefix match outranks a suffix match`() {
        // Named so that the ALPHABETICAL tiebreak would give the opposite answer — otherwise the
        // test passes on the fallback ordering and proves nothing about the match classes.
        val pair = listOf(
            RosterPerson("7000009835", "Abe", "", "", null, null), // ENDS with 9835
            RosterPerson("9835111111", "Zed", "", "", null, null), // STARTS with 9835
        )
        assertEquals(listOf("Zed", "Abe"), FieldSuggestions.rankPeople("9835", pair).map { it.name })
    }

    @Test
    fun `typing from the front finds the person`() {
        assertEquals("Ravi", FieldSuggestions.rankPeople("98350", people).first().name)
    }

    @Test
    fun `the last four digits are enough`() {
        // An operator reading a number off a slip often knows only the tail. Neither of these
        // two STARTS with 0001 — a prefix-only match would find neither of them.
        assertEquals(listOf("Gita", "Ravi"), FieldSuggestions.rankPeople("0001", people).map { it.name })
    }

    @Test
    fun `a stored country prefix does not hide the person`() {
        // Gita is stored as "+91 78880 00001". Typing her ten digits must find her.
        assertTrue(FieldSuggestions.rankPeople("7888000001", people).any { it.name == "Gita" })
    }

    @Test
    fun `non-digits in the query are ignored`() {
        assertEquals(
            FieldSuggestions.rankPeople("98350", people).map { it.name },
            FieldSuggestions.rankPeople("98 350", people).map { it.name },
        )
    }

    @Test
    fun `a prefix match later in the roster still outranks an earlier contains match`() {
        // The scan must not stop at `limit` hits: Zeta is last but is the only prefix match.
        val many = listOf(
            RosterPerson("1112223334", "Aa", "", "", null, null), // contains 2223
            RosterPerson("5552223339", "Bb", "", "", null, null), // contains 2223
            RosterPerson("2223338888", "Zeta", "", "", null, null), // PREFIX 2223
        )
        assertEquals("Zeta", FieldSuggestions.rankPeople("2223", many, limit = 1).single().name)
    }

    @Test
    fun `results are capped`() {
        val many = (1..40).map { RosterPerson("999%07d".format(it), "P$it", "", "", null, null) }
        assertEquals(5, FieldSuggestions.rankPeople("999", many).size)
        assertEquals(2, FieldSuggestions.rankPeople("999", many, limit = 2).size)
    }

    @Test
    fun `no match returns nothing`() {
        assertTrue(FieldSuggestions.rankPeople("0000000", people).isEmpty())
    }
}
