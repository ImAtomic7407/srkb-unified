"""Pure helpers for the host-side Roster Inject page.

This module deliberately imports NOTHING from app.py. Importing `app` starts the
Excel sync workers at module import time (they are not under the `__main__`
guard), so any logic living there cannot be unit-tested without side effects on a
running production server. Everything here is data-in / data-out: no Flask, no
DB, no globals.

`normalize_import_mobile` lives here as the single canonical implementation;
app.py imports it from this module.

Design: docs/superpowers/specs/2026-08-11-roster-inject-design.md
"""

import re
from io import BytesIO

from openpyxl import load_workbook


# The six fields the roster understands. Mirrors IMPORT_COLUMNS in app.py so the
# web page and the Data Import folder path accept exactly the same shape.
FIELDS = ["mobile", "name", "address", "occupation", "rounds", "remark"]

# Only the number is compulsory. A row with a number and no name is valid and is
# injected; the name gets filled in at attendance time. This matches the folder
# importer, which also accepts a nameless row.
REQUIRED_FIELDS = ["mobile"]

FIELD_LABELS = {
    "mobile": "Number",
    "name": "Name",
    "address": "Address",
    "occupation": "Occupation",
    "rounds": "Rounds",
    "remark": "Remark",
}

# Header spellings seen in real sheets. Matched case-insensitively after stripping
# punctuation and whitespace, so "Mobile No.", "mobile_no", "MOBILE NO" and "Ph. No."
# all canonicalise to the same token.
#
# STRONG = the whole header means this field. Matched exactly -> high confidence.
HEADER_ALIASES = {
    "mobile": [
        "mobile", "mobileno", "mobilenumber", "mobilenum", "mob", "mobno",
        "phone", "phoneno", "phonenumber", "ph", "phno", "phnumber", "phonenum",
        "contact", "contactno", "contactnumber", "contactdetails",
        "cell", "cellno", "cellnumber", "cellphone",
        "whatsapp", "whatsappno", "whatsappnumber", "wano",
        "tel", "telephone", "telno", "number", "primarynumber", "contactnum",
    ],
    "name": [
        "name", "fullname", "studentname", "studentsname", "personname",
        "candidatename", "participantname", "devoteename", "nameofstudent",
        "nameofperson", "membername",
    ],
    "address": [
        "address", "addr", "add", "fulladdress", "homeaddress", "residence",
        "location", "area", "city", "place", "locality",
    ],
    "occupation": [
        "occupation", "occ", "work", "job", "profession", "professiondetails",
        "workdetails", "employment", "designation", "education", "qualification",
        "study", "studies", "course",
    ],
    "rounds": [
        "rounds", "round", "chanting", "chantingrounds", "japa", "japarounds",
        "noofrounds", "numberofrounds", "dailyrounds",
    ],
    "remark": [
        "remark", "remarks", "note", "notes", "comment", "comments",
        "feedback", "observation", "description",
    ],
}

# WEAK = the header merely CONTAINS this token. Matched as a substring -> flagged
# "likely" so the host is told to eyeball it rather than trusting it silently.
HEADER_HINTS = {
    "mobile": ["mobile", "phone", "contact", "whatsapp", "cell", "number", "phno"],
    "name": ["name"],
    "address": ["address", "location", "area"],
    "occupation": ["occupation", "work", "job", "profession", "study", "education"],
    "rounds": ["round", "chanting", "japa"],
    "remark": ["remark", "note", "comment"],
}

# NEGATIVE = if the header contains one of these, it can NEVER be this field, by
# exact match or substring. This is what stops a "Sr No." / "S.No" serial column
# being read as a phone number (bare "no"/"number" is otherwise a mobile hint), and
# stops "Father Name" / "Referred By Name" hijacking the Name column. Without this
# the matcher is confidently wrong, which is worse than not matching at all.
NEGATIVE_HINTS = {
    "mobile": ["srno", "sno", "slno", "serial", "serialno", "rollno", "roll",
               "index", "sino", "srnumber", "slnumber", "batchno", "roomno",
               "housno", "houseno", "receipt", "aadhar", "aadhaar", "account",
               "alternate", "alternatenumber", "emergency", "parentnumber",
               "fathernumber", "mothernumber", "guardiannumber"],
    "name": ["father", "mother", "guardian", "parent", "spouse", "husband",
             "wife", "referred", "referrer", "caller", "centre", "center",
             "batch", "session", "username", "nickname", "surname"],
    "address": ["email", "ip"],
    "occupation": [],
    "rounds": [],
    "remark": [],
}


def normalize_import_mobile(value):
    """
    Normalize mobile input for profile import lookups.
    Returns a 10-digit mobile string or None if invalid.
    """
    if value is None:
        return None

    if isinstance(value, float) and value.is_integer():
        text = str(int(value))
    else:
        text = str(value).strip()
    if not text:
        return None

    # Trim a common Excel float suffix before stripping punctuation.
    if text.endswith(".0") and re.fullmatch(r"[\d\s()+\-]+\.0", text):
        text = text[:-2]

    digits = re.sub(r"\D", "", text)
    if not digits:
        return None

    # Handle common Indian formats such as +91XXXXXXXXXX, 0XXXXXXXXXX,
    # and Excel float artifacts like 9.87654321E9 once converted to text.
    if digits.startswith("91") and len(digits) > 10:
        digits = digits[2:]
    if digits.startswith("0") and len(digits) > 10:
        digits = digits.lstrip("0")
    if len(digits) > 10:
        digits = digits[-10:]

    return digits if len(digits) == 10 else None


def _canon(header):
    """Lowercase a header and drop everything that isn't a letter or digit."""
    return re.sub(r"[^a-z0-9]", "", str(header or "").lower())


def _blocked(field, canon):
    """True when a header is disqualified from a field by a negative hint."""
    return any(bad in canon for bad in NEGATIVE_HINTS.get(field, []))


def analyze_headers(headers, body=None):
    """Work out which sheet column feeds which field, and say how sure it is.

    Three passes, strongest first. A column is never bound to two fields, and a
    negative hint disqualifies a column outright — that is what stops "Sr No."
    reading as a phone number and "Father Name" hijacking Name.

    Returns mapping + per-field confidence so the page can tell the host what was
    guessed rather than silently importing under a wrong mapping:
        exact  — the whole header is a known name for this field
        likely — the header merely contains the token; eyeball it
        none   — nothing matched; the host must point it by hand
    """
    mapping = {field: None for field in FIELDS}
    confidence = {field: "none" for field in FIELDS}
    taken = set()
    canon_headers = [(idx, _canon(h)) for idx, h in enumerate(headers)]

    # Pass 1 — the entire header is a known name for the field.
    for field in FIELDS:
        for idx, canon in canon_headers:
            if idx in taken or not canon or _blocked(field, canon):
                continue
            if canon in HEADER_ALIASES[field]:
                mapping[field], confidence[field] = idx, "exact"
                taken.add(idx)
                break

    # Pass 2 — header CONTAINS a strong alias ("student mobile no").
    for field in FIELDS:
        if mapping[field] is not None:
            continue
        for idx, canon in canon_headers:
            if idx in taken or not canon or _blocked(field, canon):
                continue
            if any(alias in canon for alias in HEADER_ALIASES[field]):
                mapping[field], confidence[field] = idx, "likely"
                taken.add(idx)
                break

    # Pass 3 — looser hint tokens, last resort.
    for field in FIELDS:
        if mapping[field] is not None:
            continue
        for idx, canon in canon_headers:
            if idx in taken or not canon or _blocked(field, canon):
                continue
            if any(hint in canon for hint in HEADER_HINTS.get(field, [])):
                mapping[field], confidence[field] = idx, "likely"
                taken.add(idx)
                break

    unmapped = [
        {"index": idx, "header": (headers[idx] if idx < len(headers) else "")}
        for idx, _ in canon_headers if idx not in taken
    ]

    notes = []
    # A sheet whose first row is already data (no header row) would otherwise have
    # a real person silently eaten as the header. If nothing matched exactly AND
    # row 1 holds something that parses as a phone number, say so.
    header_looks_like_data = False
    if all(c != "exact" for c in confidence.values()):
        if any(normalize_import_mobile(h) for h in headers):
            header_looks_like_data = True
            notes.append(
                "The first row looks like DATA, not column headings — nothing in it "
                "matched a known heading and one cell is a valid phone number. If the "
                "sheet has no heading row, that person is currently being skipped."
            )

    if mapping["mobile"] is None:
        notes.append("No phone-number column was recognised — point one at Number below.")
    for field in FIELDS:
        if confidence[field] == "likely":
            notes.append(
                f"'{headers[mapping[field]]}' was guessed as {FIELD_LABELS[field]} — please confirm."
            )
    if unmapped:
        notes.append(
            "Not used: " + ", ".join(f"'{u['header'] or '(blank)'}'" for u in unmapped)
            + ". Map any of these below if they should come in."
        )

    needs_review = (
        mapping["mobile"] is None
        or header_looks_like_data
        or any(c == "likely" for c in confidence.values())
    )

    return {
        "mapping": mapping,
        "confidence": confidence,
        "unmapped_columns": unmapped,
        "needs_review": needs_review,
        "header_looks_like_data": header_looks_like_data,
        "notes": notes,
    }


def guess_mapping(headers):
    """Backwards-compatible thin wrapper — mapping only."""
    return analyze_headers(headers)["mapping"]


def _cell_text(value):
    """Excel cell -> trimmed string, without pandas' NaN artifacts."""
    if value is None:
        return ""
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    text = str(value).strip()
    return "" if text.lower() in ("nan", "none", "nat") else text


def parse_excel(data):
    """bytes -> (headers, rows). Row 1 is treated as the header row.

    read_only so a large sheet doesn't get fully materialised, data_only so
    formula cells yield their cached value rather than the formula text.
    """
    workbook = load_workbook(BytesIO(data), read_only=True, data_only=True)
    try:
        sheet = workbook.active
        rows = [
            [_cell_text(cell) for cell in row]
            for row in sheet.iter_rows(values_only=True)
        ]
    finally:
        workbook.close()

    if not rows:
        return [], []

    headers = rows[0]
    body = [r for r in rows[1:] if any(cell for cell in r)]
    return headers, body


def rows_from_table(body, mapping):
    """Apply a {field: column_index} mapping to parsed sheet rows."""
    out = []
    for raw in body:
        record = {}
        for field in FIELDS:
            idx = mapping.get(field)
            record[field] = raw[idx] if idx is not None and idx < len(raw) else ""
        out.append(record)
    return out


def _split_column(text):
    """Pasted textarea -> list of cell values.

    Trailing blank lines are dropped (they are an artifact of pasting), but
    interior blanks are KEPT — a blank occupation in the middle of a column is
    real data, and silently dropping it would shift every row below it onto the
    wrong person.
    """
    if not text:
        return []
    lines = [line.strip() for line in str(text).replace("\r\n", "\n").replace("\r", "\n").split("\n")]
    while lines and not lines[-1]:
        lines.pop()
    return lines


def rows_from_columns(columns):
    """Paste mode: {field: pasted_text} -> (rows, blocking_errors).

    Columns are aligned strictly by position, so a length mismatch is fatal
    rather than padded. A two-row offset silently pairs every name below it with
    the wrong number, which is near-impossible to spot in a preview and painful
    to unwind once written.
    """
    split = {field: _split_column(columns.get(field, "")) for field in FIELDS}
    present = {field: values for field, values in split.items() if values}

    errors = []
    if not present:
        errors.append("Nothing pasted.")
        return [], errors

    if "mobile" not in present:
        errors.append("The Number column is required — paste it to continue.")
        return [], errors

    lengths = {field: len(values) for field, values in present.items()}
    if len(set(lengths.values())) > 1:
        detail = ", ".join(
            f"{FIELD_LABELS[field]}: {count} rows"
            for field, count in sorted(lengths.items(), key=lambda kv: FIELDS.index(kv[0]))
        )
        errors.append(
            "Columns are misaligned and would pair the wrong name with the wrong "
            f"number ({detail}). Fix the paste so every column has the same number of rows."
        )
        return [], errors

    total = next(iter(lengths.values()))
    rows = []
    for i in range(total):
        rows.append({field: (present[field][i] if field in present else "") for field in FIELDS})
    return rows, errors


def annotate(rows):
    """Attach per-row validity to parsed rows. No DB knowledge here.

    Sets:
      normalized_mobile — the 10-digit form, or None
      invalid_reason    — set when the row cannot be injected at all
      no_name           — valid, but the name will be stored as Unknown
      duplicate_in_file — a later row repeating an earlier row's number
    """
    annotated = []
    seen = set()

    for row in rows:
        record = dict(row)
        mobile = normalize_import_mobile(row.get("mobile"))
        record["normalized_mobile"] = mobile
        record["invalid_reason"] = None
        record["no_name"] = False
        record["duplicate_in_file"] = False

        if not mobile:
            raw = str(row.get("mobile") or "").strip()
            record["invalid_reason"] = (
                "No number" if not raw else f"Not a valid 10-digit number: {raw}"
            )
        elif mobile in seen:
            record["duplicate_in_file"] = True
            record["invalid_reason"] = "Repeated in this upload — first one wins"
        else:
            seen.add(mobile)
            record["no_name"] = not str(row.get("name") or "").strip()

        annotated.append(record)

    return annotated


def summarize(annotated):
    """Counts for the preview header line."""
    return {
        "total": len(annotated),
        "valid": sum(1 for r in annotated if not r["invalid_reason"]),
        "invalid": sum(1 for r in annotated if r["invalid_reason"] and not r["duplicate_in_file"]),
        "duplicate_in_file": sum(1 for r in annotated if r["duplicate_in_file"]),
        "no_name": sum(1 for r in annotated if r["no_name"]),
    }
