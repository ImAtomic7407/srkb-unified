package com.example

import com.example.data.PhoneNumbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the WhatsApp link format.
 *
 * The failure this protects against is not a crash — it is a link that opens WhatsApp and reaches
 * the wrong country. The caller sees "invalid country code" or "not on WhatsApp" and reports the
 * number as wrong, when the number is fine and the LINK was missing its country code.
 *
 * The numbers below are shapes taken from the live roster, not invented ones.
 */
class PhoneNumbersTest {

    @Test
    fun `a bare ten digit number gets the India country code`() {
        // What the calling backend stores: normalizeMobile strips a leading 91, so every number
        // arrives here as 10 bare digits and MUST have the code put back for wa.me.
        assertEquals("919835000001", PhoneNumbers.forWhatsApp("9835000001"))
        assertEquals("918877000008", PhoneNumbers.forWhatsApp("8877000008"))
    }

    @Test
    fun `the leading digits that used to be misread as other countries`() {
        // Each of these was silently resolving to a real foreign country code, which is why the
        // error message varied instead of being consistently "invalid".
        assertEquals("917004000002", PhoneNumbers.forWhatsApp("7004000002")) // was read as +7 Russia
        assertEquals("916203000003", PhoneNumbers.forWhatsApp("6203000003")) // was read as +62 Indonesia
        assertEquals("918212345678", PhoneNumbers.forWhatsApp("8212345678")) // was read as +82 South Korea
        assertEquals("919212345678", PhoneNumbers.forWhatsApp("9212345678")) // was read as +92 Pakistan
        assertEquals("919812345678", PhoneNumbers.forWhatsApp("9812345678")) // was read as +98 Iran
    }

    @Test
    fun `a number that already carries the country code is not prefixed twice`() {
        assertEquals("919835000001", PhoneNumbers.forWhatsApp("919835000001"))
        assertEquals("919835000001", PhoneNumbers.forWhatsApp("+91 98350 00001"))
        assertEquals("919835000001", PhoneNumbers.forWhatsApp("+91-98350-00001"))
    }

    @Test
    fun `the domestic trunk zero is dropped rather than dialled`() {
        // "0" before a 10-digit number is a domestic prefix. Keeping it would make an 11-digit
        // number that no country code explains.
        assertEquals("919835000001", PhoneNumbers.forWhatsApp("09835000001"))
    }

    @Test
    fun `punctuation and spacing do not change the result`() {
        assertEquals("919835000001", PhoneNumbers.forWhatsApp("98350 00001"))
        assertEquals("919835000001", PhoneNumbers.forWhatsApp("(98350) 00001"))
        assertEquals("919835000001", PhoneNumbers.forWhatsApp(" 9835000001 "))
    }

    @Test
    fun `a genuinely international number is passed through untouched`() {
        // Must not assume India and staple a second country code onto a number that has one.
        assertEquals("14155552671", PhoneNumbers.forWhatsApp("+1 415 555 2671"))   // US
        assertEquals("8613800138000", PhoneNumbers.forWhatsApp("+86 138 0013 8000")) // China
    }

    @Test
    fun `a truncated record refuses instead of dialling something wrong`() {
        // The roster holds a few 9-digit values. Prefixing 91 onto one would produce a real-looking
        // Indian number belonging to somebody else entirely.
        assertNull(PhoneNumbers.forWhatsApp("748857728"))
        assertNull(PhoneNumbers.forWhatsApp("957046087"))
        assertNull(PhoneNumbers.forWhatsApp(""))
        assertNull(PhoneNumbers.forWhatsApp(null))
        assertNull(PhoneNumbers.forWhatsApp("not a number"))
    }

    @Test
    fun `every number in a caller list produces a link with a country code`() {
        // The property that actually matters: whatever we return is long enough to carry a country
        // code, so wa.me can never fall back to reading one out of the subscriber digits.
        listOf("9835000001", "7004000002", "6203000003", "8877000008", "09835000001")
            .forEach { raw ->
                val out = PhoneNumbers.forWhatsApp(raw)!!
                assertEquals("$raw must be sent as an Indian number", "91", out.take(2))
                assertEquals("$raw must be 12 digits once the code is added", 12, out.length)
            }
    }

    // ── normalizeMobileInput: what a number FIELD keeps as the user types or pastes ──────────────
    //
    // These are the strings a registerer actually pastes. Before 2026-08-20 every one of them
    // except the bare ten digits produced an EMPTY field, because the old handler measured
    // length before stripping punctuation.

    @Test
    fun `the pastes that used to vanish now land as ten digits`() {
        assertEquals("9835000001", PhoneNumbers.normalizeMobileInput("+919835000001"))
        assertEquals("9835000001", PhoneNumbers.normalizeMobileInput("+91 98350 00001"))
        assertEquals("9835000001", PhoneNumbers.normalizeMobileInput("+91-98350-00001"))
        assertEquals("9835000001", PhoneNumbers.normalizeMobileInput("98350 00001"))
        assertEquals("9835000001", PhoneNumbers.normalizeMobileInput("(98350) 00001"))
        assertEquals("9835000001", PhoneNumbers.normalizeMobileInput("09835000001"))
    }

    @Test
    fun `a clean number copied from WhatsApp keeps its trailing space`() {
        // Eleven characters, ten digits. The old guard rejected it and the reporter saw nothing
        // appear even though what they copied looked perfect.
        assertEquals("9835000001", PhoneNumbers.normalizeMobileInput("9835000001 "))
        assertEquals("9835000001", PhoneNumbers.normalizeMobileInput(" 9835000001"))
        assertEquals("9835000001", PhoneNumbers.normalizeMobileInput("9835000001\n"))
    }

    @Test
    fun `typing an eleventh digit is refused, never absorbed`() {
        // The reason this is not `.filter { it.isDigit() }.takeLast(10)`. With takeLast the field
        // would become "8358000012" here — first digit silently gone, no feedback to the user.
        assertNull(PhoneNumbers.normalizeMobileInput("98350000019"))
        assertNull(PhoneNumbers.normalizeMobileInput("12345678901"))
    }

    @Test
    fun `a partly typed number is allowed through so the field can be filled one key at a time`() {
        assertEquals("", PhoneNumbers.normalizeMobileInput(""))
        assertEquals("9", PhoneNumbers.normalizeMobileInput("9"))
        assertEquals("98358", PhoneNumbers.normalizeMobileInput("98358"))
        assertEquals("983588861", PhoneNumbers.normalizeMobileInput("983588861"))
        // Clearing the field must work — an empty result is a valid state, not a rejection.
        assertEquals("", PhoneNumbers.normalizeMobileInput("   "))
    }

    @Test
    fun `a genuinely too-long value is refused rather than trimmed into a real someone else`() {
        // 13+ digits is not an Indian mobile with decoration; truncating would invent a number.
        assertNull(PhoneNumbers.normalizeMobileInput("+1 415 555 2671 999"))
        assertNull(PhoneNumbers.normalizeMobileInput("861380013800012"))
    }
}
