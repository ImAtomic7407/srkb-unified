package com.example.data

/**
 * Turns a stored mobile number into the form each outbound intent actually requires.
 *
 * The bug this exists to prevent: every WhatsApp button in the app was built as
 * `https://wa.me/${mobile}` with the raw stored value. The calling backend normalises numbers to a
 * BARE 10-digit Indian number — normalizeMobile in src/utils/mobile.ts explicitly STRIPS a leading
 * 91 — but wa.me requires a full international number, country code included and no leading zero.
 * With no country code present WhatsApp parses the leading digits of the subscriber number as one:
 *
 *   9835000001  ->  wa.me reads +98 (Iran), 8 digits left over  -> "invalid"
 *   7004000002  ->  wa.me reads +7  (Russia), 9 digits left over -> "not on WhatsApp"
 *   6203000003  ->  wa.me reads +62 (Indonesia)                  -> "not on WhatsApp"
 *
 * Measured against the live database on 2026-08-01: all 382 numbers assigned to a caller produced
 * a broken link — 106 misread as Russia, 43 as Indonesia, 21 as South Korea, 18 as Pakistan, and 57
 * matched no country code at all. That is the difference between the two errors callers reported:
 * a number that lands on a real country code gets "not on WhatsApp", one that lands on nothing gets
 * "invalid country code". Dialling was unaffected, because a bare 10-digit `tel:` is valid
 * domestically — which is why only WhatsApp appeared broken.
 */
object PhoneNumbers {

    /** India. Every number this system holds is a local roster entry unless it says otherwise. */
    private const val DEFAULT_COUNTRY_CODE = "91"

    /**
     * The number as wa.me wants it: digits only, country code included, no `+` and no leading zero.
     * Returns null when the value cannot be a phone number at all, so the caller can say so instead
     * of opening a link that fails with a message about the wrong country.
     */
    fun forWhatsApp(raw: String?): String? {
        val digits = raw.orEmpty().filter { it.isDigit() }
        return when {
            // The overwhelmingly common case: what the backend stores.
            digits.length == 10 -> DEFAULT_COUNTRY_CODE + digits
            // Already carries +91, in whatever punctuation it arrived with.
            digits.length == 12 && digits.startsWith(DEFAULT_COUNTRY_CODE) -> digits
            // STD form "0" + 10 digits. The zero is a domestic trunk prefix and is never dialled
            // internationally, so it must be dropped rather than kept as part of the number.
            digits.length == 11 && digits.startsWith("0") -> DEFAULT_COUNTRY_CODE + digits.drop(1)
            // Long enough to already be international — pass it through rather than guessing that
            // it is Indian and prefixing a second country code onto a number that has one.
            digits.length in 11..15 -> digits
            // Fewer than 10 digits is a truncated record, not a number. The roster holds a handful
            // of these (9-digit values in batch_members). Refuse rather than dial something wrong.
            else -> null
        }
    }

    /**
     * What a mobile-number INPUT FIELD should hold after the user typed or pasted [raw].
     *
     * Returns null to mean "reject this edit and keep what was already there", which is what makes
     * it safe in an onValueChange.
     *
     * The defect this fixes (reported 2026-08-20, "can't paste the number in the registration
     * tab"): the field guarded length BEFORE stripping punctuation —
     * `if (it.length <= 10) mobile = it.filter { c -> c.isDigit() }` — so any paste longer than ten
     * CHARACTERS was thrown away whole and nothing appeared at all. Every realistic paste is longer
     * than ten characters: "+91 98350 00001", "98350 00001", even "9835000001 " with the single
     * trailing space that copying from WhatsApp leaves behind.
     *
     * Note it does NOT use `.filter { it.isDigit() }.takeLast(10)`, the idiom used elsewhere in this
     * codebase (AppData.lastTenDigits and friends). Those parse values that have already been
     * stored; this reads a field mid-edit. With takeLast, a full field plus an eleventh keystroke
     * silently drops the FIRST digit and appends the new one, quietly rewriting a number the user
     * believes they typed correctly. Overflow must be refused, not absorbed.
     */
    fun normalizeMobileInput(raw: String): String? {
        var d = raw.filter { it.isDigit() }
        // Same two prefix shapes forWhatsApp() recognises, in reverse: strip what it would add.
        // forWhatsApp itself is not reused here because it PREPENDS the country code.
        if (d.length == 12 && d.startsWith(DEFAULT_COUNTRY_CODE)) d = d.drop(2)
        else if (d.length == 11 && d.startsWith("0")) d = d.drop(1)
        return if (d.length <= 10) d else null
    }

    // Deliberately no dialer helper. `tel:` with a bare 10-digit number is correct domestic
    // dialling and is working today; rewriting it would risk the one outbound path callers rely on
    // to fix nothing. Only the three malformed 11-digit roster entries dial wrongly, and those are
    // bad data rather than a formatting bug.
}
