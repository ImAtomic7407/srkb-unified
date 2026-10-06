package com.example

import com.example.data.FieldSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the registration screen's address/occupation type-ahead.
 *
 * The failure these protect against is not a crash. It is an operator at a festival being offered
 * three spellings of one locality, or being offered nothing at all because the value they wanted
 * was recorded in a different case — either of which sends them back to typing the address out in
 * full, which is the entire cost this feature exists to remove.
 *
 * Only the pure ranking functions are covered here. `recents`/`recordUse` go through
 * AppDatabase's SharedPreferences and need an Android context, so they belong in an
 * instrumentation test rather than this one.
 */
class FieldSuggestionsTest {

    @Test
    fun `corpus collapses case and whitespace into one candidate`() {
        val corpus = FieldSuggestions.buildCorpus(
            listOf("Sample Colony", "sample colony", "  Sample   Colony  ")
        )
        assertEquals(1, corpus.size)
        assertEquals(3, corpus[0].count)
    }

    @Test
    fun `corpus displays the most common spelling, not the first seen`() {
        // A single shouted entry must not rename a locality that everyone else spells normally.
        val corpus = FieldSuggestions.buildCorpus(
            listOf("SAMPLE COLONY", "Sample Colony", "Sample Colony", "Sample Colony")
        )
        assertEquals("Sample Colony", corpus[0].label)
    }

    @Test
    fun `corpus drops blanks and placeholder junk`() {
        val corpus = FieldSuggestions.buildCorpus(listOf("", "  ", "-", "N/A", "na", "Patna"))
        assertEquals(listOf("Patna"), corpus.map { it.label })
    }

    @Test
    fun `hotter values rank first`() {
        val corpus = FieldSuggestions.buildCorpus(
            List(5) { "Rajendra Nagar" } + List(2) { "Rampur" } + listOf("Ranchi")
        )
        assertEquals(listOf("Rajendra Nagar", "Rampur", "Ranchi"), corpus.map { it.label })
    }

    @Test
    fun `empty query returns the hot list`() {
        val corpus = FieldSuggestions.buildCorpus(List(5) { "Patna" } + listOf("Gaya"))
        val out = FieldSuggestions.rank("", corpus, emptyList())
        assertEquals(listOf("Patna", "Gaya"), out)
    }

    @Test
    fun `recently typed values outrank more common ones`() {
        // The "everyone here is from the same locality" case: what this operator just typed is
        // the best prediction of what they are about to type again, even when the centre as a
        // whole uses something else far more often.
        val corpus = FieldSuggestions.buildCorpus(List(50) { "Patna" } + listOf("Danapur"))
        val out = FieldSuggestions.rank("", corpus, listOf("Danapur"))
        assertEquals("Danapur", out.first())
    }

    @Test
    fun `matching is case insensitive`() {
        val corpus = FieldSuggestions.buildCorpus(listOf("Sample Colony"))
        assertEquals(listOf("Sample Colony"), FieldSuggestions.rank("sample", corpus, emptyList()))
        assertEquals(listOf("Sample Colony"), FieldSuggestions.rank("SAMPLE", corpus, emptyList()))
    }

    @Test
    fun `prefix matches outrank mid-string matches`() {
        val corpus = FieldSuggestions.buildCorpus(listOf("Shri Rampur", "Rampur"))
        assertEquals("Rampur", FieldSuggestions.rank("ram", corpus, emptyList()).first())
    }

    @Test
    fun `an exact match is not offered back`() {
        // Re-offering what is already typed costs a row of the list and helps nobody.
        val corpus = FieldSuggestions.buildCorpus(listOf("Patna", "Patna City"))
        val out = FieldSuggestions.rank("Patna", corpus, emptyList())
        assertFalse(out.contains("Patna"))
        assertTrue(out.contains("Patna City"))
    }

    @Test
    fun `non-matching query returns nothing`() {
        val corpus = FieldSuggestions.buildCorpus(listOf("Patna", "Gaya"))
        assertTrue(FieldSuggestions.rank("zzz", corpus, emptyList()).isEmpty())
    }

    @Test
    fun `results are capped at the limit`() {
        val corpus = FieldSuggestions.buildCorpus((1..50).map { "Locality $it" })
        assertEquals(8, FieldSuggestions.rank("locality", corpus, emptyList()).size)
        assertEquals(8, FieldSuggestions.rank("", corpus, emptyList()).size)
    }

    @Test
    fun `a recent value absent from the corpus is still offered`() {
        // Offline, or simply before the roster refresh lands, the corpus can be empty. The
        // operator's own recent entries must still be there — that is the offline path.
        val out = FieldSuggestions.rank("", emptyList(), listOf("Boring Road"))
        assertEquals(listOf("Boring Road"), out)
    }

    @Test
    fun `a value both recent and common appears once`() {
        val corpus = FieldSuggestions.buildCorpus(List(9) { "Patna" })
        val out = FieldSuggestions.rank("", corpus, listOf("patna"))
        assertEquals(1, out.size)
    }
}
