"""Compare a session's attendance in SQLite against its sheet in the workbook.

WHY THIS EXISTS
---------------
The registerer screen shows the same class through two different sources:

    Current tab  ->  /api/show_attendance      ->  SQLite `attendance`
    Lists tab    ->  /api/get_sheet_content    ->  live_attendance_master.xlsx

When those disagree, a registerer sees people vanish or appear twice, and there
is nothing in the system that notices. `reconcile_live_workbook_from_db` only
BACKFILLS rows missing from Excel — it is blind to the other three ways the two
sides drift apart, and the one it is blind to that actually loses people is a
session sheet existing under a near-miss name (23rd March 2026: one class, three
sheets, four people filed in the wrong one).

This module is the DETECTOR only. It computes the difference and classifies it;
it never writes. That split is deliberate — the same one the calling backend uses
for `auditCentre()` vs `reconcileCentre()` — so the drift can be measured against
live data before anything is allowed to heal it automatically.

PURE ON PURPOSE
---------------
No Flask, no SQLAlchemy, no openpyxl imports. Callers hand in plain row dicts, so
the comparison can be unit-tested without a database or a workbook, and so the
same logic can be reused by an endpoint, a background sweep, and a CLI check.

THE NORMALISATION IS THE HARD PART
----------------------------------
Every field crosses a boundary that mangles it, and each of these was a real bug
in this system before it was a rule here:

* Mobile numbers land in a numeric cell and read back as 9.23479505E+09 floats.
* Dates land as OLE serial numbers or datetimes, not the string that was written.
* A session NAMED like a date ("9 Aug 2026") is silently coerced by Excel into a
  real datetime, so it stops matching its own DB row — that is what appended a
  second copy of all 14 marks on 2026-08-09.

Comparing raw values would report drift that isn't there and miss drift that is.
"""

import datetime
import re

# Workbook column order, fixed by the writers (COM + openpyxl both emit this).
HEADERS = ["Name", "Number", "Address", "Occupation",
           "Session", "Batch", "Rounds", "Remark", "Date"]

# Drift classes. Stable strings — they go into the journal and the API response.
MISSING_IN_EXCEL = "missing_in_excel"    # committed in SQLite, absent from the sheet
MISSING_IN_DB = "missing_in_db"          # on the sheet, no matching SQLite row
FIELD_MISMATCH = "field_mismatch"        # present on both sides, values disagree
DUPLICATE_IN_EXCEL = "duplicate_in_excel"  # same person marked twice on one sheet

# Fields compared for FIELD_MISMATCH. Name/address/occupation live on the person,
# not the mark, and are repaired by their own mechanism, so a difference there is
# reported but is not what this is primarily looking for.
COMPARED_FIELDS = ("name", "rounds", "remark")


def norm_mobile(value):
    """A phone number reduced to its digits, however Excel mangled it.

    A numeric cell reads back as a float (9.23479505E+09 for a 10-digit number),
    so the digits have to be recovered before any comparison.
    """
    if value is None:
        return ""
    if isinstance(value, float):
        # %.0f, not int(), so no exponent notation sneaks through.
        value = "%.0f" % value
    text = str(value).strip()
    if text.endswith(".0"):
        text = text[:-2]
    digits = re.sub(r"\D", "", text)
    # Indian numbers arrive with and without the country code; compare the last 10.
    return digits[-10:] if len(digits) > 10 else digits


def norm_day(value):
    """A date reduced to YYYY-MM-DD, from a datetime, an OLE serial, or text."""
    if value is None:
        return ""
    if isinstance(value, datetime.datetime):
        return value.strftime("%Y-%m-%d")
    if isinstance(value, datetime.date):
        return value.strftime("%Y-%m-%d")
    if isinstance(value, float) or isinstance(value, int):
        # Excel serial date: day 1 is 1899-12-31 in Excel's (leap-bug) reckoning.
        try:
            base = datetime.datetime(1899, 12, 30)
            return (base + datetime.timedelta(days=float(value))).strftime("%Y-%m-%d")
        except Exception:
            return str(value).strip()
    return str(value).strip()[:10]


def norm_text(value):
    """Trimmed, case-folded text — for comparing names/remarks across sources."""
    if value is None:
        return ""
    if isinstance(value, float) and value == int(value):
        value = int(value)
    return " ".join(str(value).split()).strip().casefold()


def coerced_session_day(value):
    """The calendar day a session name collapsed to, or "" if it is not date-like.

    A session named "9 Aug 2026" comes back out of Excel as a datetime. Reducing
    both sides to a day makes them comparable without rewriting either.
    """
    if isinstance(value, (datetime.datetime, datetime.date)):
        return norm_day(value)
    return ""


def session_key(value):
    """Comparison key for a session cell: its coerced day if Excel ate it, else text."""
    day = coerced_session_day(value)
    return day if day else norm_text(value)


def resolve_sheet_title(sheet_titles, sheet_name):
    """The workbook tab serving `sheet_name`, or None — honouring Excel's rules.

    Excel sheet names are case-INSENSITIVE, so the session "Testing" may live on
    a tab titled "testing". Every place that looks a sheet up must resolve it
    this way or the detector and the healer end up arguing about which sheet
    they are even looking at: a case-sensitive lookup reported a perfectly
    healthy session as having no sheet and 6 rows missing, and openpyxl's
    create_sheet would then have quietly made a SECOND tab rather than raising
    (that is how seventy "Testing71, Testing72, ..." sheets once accumulated).

    Single canonical implementation on purpose — the same lesson as
    sanitize_sheet_name, which broke offline sync when one call site rolled its
    own `session_id[:31]`.
    """
    target = str(sheet_name or "").strip().lower()
    if not target:
        return None
    for title in sheet_titles:
        if str(title).strip().lower() == target:
            return title
    return None


def row_key(row):
    """Identity of one attendance mark: person + day.

    Session is deliberately NOT in the key. Within a single session sheet the
    sheet itself already fixes the session, and including a cell Excel may have
    coerced into a datetime would split a row from its own DB record — the exact
    failure that duplicated 2026-08-09.
    """
    return (norm_mobile(row.get("mobile")), norm_day(row.get("date")))


def audit(db_rows, excel_rows, sheet_exists=True):
    """Classify every difference between the DB rows and the sheet rows.

    db_rows / excel_rows: dicts with keys mobile, date, name, rounds, remark.
    Returns a dict of drift class -> list of findings, plus counts. Read-only:
    this function decides nothing about what to do, only what is true.
    """
    result = {
        MISSING_IN_EXCEL: [],
        MISSING_IN_DB: [],
        FIELD_MISMATCH: [],
        DUPLICATE_IN_EXCEL: [],
        "sheet_exists": bool(sheet_exists),
        "db_count": len(db_rows),
        "excel_count": len(excel_rows),
    }

    db_by_key = {}
    for row in db_rows:
        db_by_key.setdefault(row_key(row), []).append(row)

    excel_by_key = {}
    for row in excel_rows:
        excel_by_key.setdefault(row_key(row), []).append(row)

    if not sheet_exists:
        # Every mark is missing; say so once rather than emitting N findings that
        # all have the same cause and the same single fix.
        result[MISSING_IN_EXCEL] = [dict(r, reason="sheet_missing") for r in db_rows]
        return result

    for key, rows in db_by_key.items():
        if not key[0]:
            continue  # a row with no recoverable mobile cannot be matched either way
        found = excel_by_key.get(key)
        if not found:
            result[MISSING_IN_EXCEL].append(dict(rows[0], reason="no_matching_excel_row"))
            continue
        excel_row = found[0]
        diffs = {}
        for field in COMPARED_FIELDS:
            a, b = norm_text(rows[0].get(field)), norm_text(excel_row.get(field))
            # An empty cell on either side is "not recorded here", not a conflict:
            # the mirrors are written at different times and a blank remark on one
            # side is the normal steady state, not drift worth a human's attention.
            if a and b and a != b:
                diffs[field] = {"db": rows[0].get(field), "excel": excel_row.get(field)}
        if diffs:
            result[FIELD_MISMATCH].append({
                "mobile": key[0], "date": key[1],
                "name": rows[0].get("name"), "fields": diffs,
            })

    for key, rows in excel_by_key.items():
        if not key[0]:
            continue
        if key not in db_by_key:
            result[MISSING_IN_DB].append(dict(rows[0], reason="no_matching_db_row"))
        if len(rows) > 1:
            result[DUPLICATE_IN_EXCEL].append({
                "mobile": key[0], "date": key[1],
                "name": rows[0].get("name"), "copies": len(rows),
            })

    return result


def is_clean(report):
    """True when the two sides agree on everything this module can see."""
    return (report.get("sheet_exists", False)
            and not report[MISSING_IN_EXCEL]
            and not report[MISSING_IN_DB]
            and not report[FIELD_MISMATCH]
            and not report[DUPLICATE_IN_EXCEL])


def summarize(report):
    """One-line human summary, for a log line or the API."""
    if not report.get("sheet_exists", False):
        return f"sheet MISSING; {report['db_count']} row(s) in the database"
    if is_clean(report):
        return f"in sync ({report['db_count']} row(s))"
    parts = []
    for label, key in (("missing in Excel", MISSING_IN_EXCEL),
                       ("missing in DB", MISSING_IN_DB),
                       ("field mismatch", FIELD_MISMATCH),
                       ("duplicated in Excel", DUPLICATE_IN_EXCEL)):
        if report[key]:
            parts.append(f"{len(report[key])} {label}")
    return f"db={report['db_count']} excel={report['excel_count']}: " + ", ".join(parts)
