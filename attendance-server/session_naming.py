"""Standardised session identifiers.

Why this exists
---------------
Until 2026-08-12 a session's IDENTITY was the text a human typed into the "new session" box:
`full_session_id = session_name`, and that same string became the SQLite key, the Excel tab name
and the Google Sheets tab name. A typo therefore did not get corrected — it silently created a
SECOND session and split the class across both. That is not hypothetical; it is in the live data:

    'Bz 23rd March Evening'  42 attendance rows
    'BZ  23rd March'          4 attendance rows   <- double space, same class
    'Bz 23rd March Evenin'    0 attendance rows   <- truncated typo

Four people's attendance sits in the wrong session because of a stray space. The same pattern
produced '29th June  OTP' (0 rows) next to '29th June HMBG'.

The fix is to stop deriving identity from free text. The ID is now generated from facts the
system already knows — date, centre, batch — and whatever the operator types becomes the
`display_name` label, where a typo is harmless.

Format: ``YYYY-MM-DD <CENTRE> <BATCH>``  e.g. ``2026-08-12 KKB BG``
  * sorts chronologically as plain text
  * unique per centre+batch+day, which is what makes duplicate detection possible
  * contains none of Excel's forbidden characters (/ \\ : ? *) and stays well inside the
    31-character sheet-name limit, so `sanitize_sheet_name` passes it through untouched

This module is PURE — no Flask, no DB — so it can be unit-tested without importing app.py
(which starts the Excel sync workers at import time). Uniqueness against real data lives in
app.py; everything here is string derivation.
"""

import re

# Words that carry no identifying information in a centre or batch name.
_STOPWORDS = {
    "AND", "THE", "OF", "A", "AN", "BATCH", "BASE", "BACE", "CENTRE", "CENTER",
    "PM", "AM", "CLASS", "GROUP", "FOR", "AT",
}

MAX_CENTRE_CODE = 4
MAX_BATCH_CODE = 12
# 10 (date) + 1 + 4 (centre) + 1 + 12 (batch) = 28, comfortably inside Excel's 31.
SHEET_NAME_LIMIT = 31


def _tokens(name):
    """Split a name into uppercase alphanumeric tokens, dropping noise words."""
    raw = re.findall(r"[A-Za-z]+|\d+", str(name or ""))
    out = [t.upper() for t in raw]
    kept = [t for t in out if t not in _STOPWORDS]
    # If stripping stopwords removed everything ("Batch"), keep the original tokens rather
    # than returning nothing — a weak code beats no code.
    return kept or out


def derive_centre_code(name, limit=MAX_CENTRE_CODE):
    """Short code for a centre: first letter, then consonants ('Centre B' -> CTB)."""
    letters = "".join(_tokens(name))
    letters = re.sub(r"[^A-Z0-9]", "", letters)
    if not letters:
        return "CTR"
    head = letters[0]
    rest = [c for c in letters[1:] if c not in "AEIOU"]
    code = (head + "".join(rest))[:limit]
    return code or letters[:limit]


def derive_batch_code(name, limit=MAX_BATCH_CODE):
    """Short code for a batch.

    Short names are kept whole ('BG', 'OTP', 'H.M and B.G Batch' -> 'HMBG'). Long ones collapse
    to initials plus any numbers, which is what keeps a name like
    'Habit Mastery Registration 18th May' inside the limit without becoming unreadable.
    """
    toks = _tokens(name)
    if not toks:
        return "BATCH"

    joined = "".join(toks)
    if len(joined) <= 8:
        return joined[:limit]

    if len(toks) >= 3:
        parts = []
        for t in toks:
            parts.append(t if t.isdigit() else t[0])
        collapsed = "".join(parts)
        if len(collapsed) >= 2:
            return collapsed[:limit]

    return joined[:8][:limit]


def build_session_id(date_str, centre_code, batch_code, suffix=None):
    """Assemble the identifier. `date_str` must already be YYYY-MM-DD."""
    parts = [str(date_str), str(centre_code or "CTR"), str(batch_code or "BATCH")]
    if suffix:
        parts.append(str(suffix))
    sid = " ".join(p for p in parts if p)
    # Belt and braces: never emit something the sheet-name sanitizer would alter, because a
    # sanitized-but-different string is exactly how offline records used to land in a tab
    # nobody could see.
    sid = re.sub(r"[\\/:?*\[\]]", "-", sid)
    return sid[:SHEET_NAME_LIMIT].strip()


def is_standard_session_id(session_id):
    """True if this id already follows the scheme — used to leave legacy sessions alone."""
    return bool(re.match(r"^\d{4}-\d{2}-\d{2}\s+\S+", str(session_id or "")))
