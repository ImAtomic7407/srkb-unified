"""Unit tests for session_sync — the DB-vs-Excel drift detector.

Pure: no database, no workbook, no Flask. Every case here is a shape that was
observed in the live data or a mangling that Excel is known to apply.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.abspath(os.path.join(HERE, "..")))

import session_sync as S  # noqa: E402

failures = []


def check(name, cond, detail=""):
    print(("  PASS  " if cond else "  FAIL  ") + name + (" :: " + detail if detail else ""))
    if not cond:
        failures.append(name)


def row(mobile, date, name="X", rounds=None, remark=None, batch=None, session=None):
    return {"mobile": mobile, "date": date, "name": name,
            "rounds": rounds, "remark": remark, "batch": batch, "session": session}


print("\nNormalisation (each of these was a real bug)")
check("float mobile keeps all 10 digits", S.norm_mobile(9234000026.0) == "9234000026",
      S.norm_mobile(9234000026.0))
check("scientific-notation float is not truncated", S.norm_mobile(9.234000026e9) == "9234000026",
      S.norm_mobile(9.234000026e9))
check("+91 prefix compares equal to bare number",
      S.norm_mobile("+919234000026") == S.norm_mobile("9234000026"))
check("spaces and dashes ignored", S.norm_mobile(" 92340-00026 ") == "9234000026")
check("trailing .0 stripped", S.norm_mobile("9234000026.0") == "9234000026")

import datetime  # noqa: E402
check("datetime date normalises", S.norm_day(datetime.datetime(2026, 3, 23, 0, 0)) == "2026-03-23")
check("text date normalises", S.norm_day("2026-03-23") == "2026-03-23")
check("timestamped text date truncates to the day", S.norm_day("2026-03-23 18:04:08") == "2026-03-23")
check("excel serial date decodes", S.norm_day(46104.0) == "2026-03-23", S.norm_day(46104.0))

# A session NAMED like a date is coerced by Excel into a real datetime; that is
# what duplicated all 14 marks on 2026-08-09.
check("coerced session name reduces to its day",
      S.session_key(datetime.datetime(2026, 8, 9, 0, 0)) == "2026-08-09")
check("normal session name compares as text",
      S.session_key("Bz 23rd March Evening") == "bz 23rd march evening")


print("\nDrift classification")
db = [row("9234000026", "2026-03-23", "Suraj"), row("7679000027", "2026-03-23", "Mukesh")]

rep = S.audit(db, list(db))
check("identical sides are clean", S.is_clean(rep), S.summarize(rep))

rep = S.audit(db, [db[0]])
check("row committed in DB but absent from the sheet is flagged",
      len(rep[S.MISSING_IN_EXCEL]) == 1 and not rep[S.MISSING_IN_DB], S.summarize(rep))

rep = S.audit([db[0]], db)
check("row on the sheet with no DB record is flagged",
      len(rep[S.MISSING_IN_DB]) == 1 and not rep[S.MISSING_IN_EXCEL], S.summarize(rep))

rep = S.audit(db, db + [row("9234000026", "2026-03-23", "Suraj")])
check("same person twice on one sheet is a duplicate, not a missing row",
      len(rep[S.DUPLICATE_IN_EXCEL]) == 1 and not rep[S.MISSING_IN_DB], S.summarize(rep))

# Excel mangles the stored form; the mark is the same mark.
mangled = [row(9234000026.0, datetime.datetime(2026, 3, 23), "Suraj"),
           row("+91 7679000027", "2026-03-23", "Mukesh")]
rep = S.audit(db, mangled)
check("Excel-mangled mobile/date still matches its own DB row", S.is_clean(rep), S.summarize(rep))

rep = S.audit([row("9234000026", "2026-03-23", "Suraj", rounds="16")],
              [row("9234000026", "2026-03-23", "Suraj", rounds="4")])
check("differing rounds is a field mismatch", len(rep[S.FIELD_MISMATCH]) == 1, S.summarize(rep))

rep = S.audit([row("9234000026", "2026-03-23", "Suraj", rounds="16")],
              [row("9234000026", "2026-03-23", "Suraj", rounds=None)])
check("blank on one side is NOT a mismatch (mirrors are written at different times)",
      not rep[S.FIELD_MISMATCH], S.summarize(rep))

rep = S.audit([row("9234000026", "2026-03-23", "suraj  modi")],
              [row("9234000026", "2026-03-23", "Suraj Modi")])
check("name differing only by case/spacing is not a mismatch", not rep[S.FIELD_MISMATCH])

rep = S.audit(db, [], sheet_exists=False)
check("a missing sheet reports every DB row as missing",
      len(rep[S.MISSING_IN_EXCEL]) == 2 and not rep["sheet_exists"], S.summarize(rep))
check("a missing sheet is never 'clean'", not S.is_clean(rep))

rep = S.audit([row("", "2026-03-23")], [row("", "2026-03-23")])
check("rows with no recoverable mobile are skipped, not falsely flagged",
      S.is_clean(rep), S.summarize(rep))

# The live 'Bz 23rd March Evening' shape: 42 in DB, 84 on the sheet — every
# person once with the real date and once with a bogus one.
db42 = [row(str(9000000000 + i), "2026-03-23") for i in range(42)]
bogus = [row(str(9000000000 + i), "2023-10-05") for i in range(42)]
rep = S.audit(db42, db42 + bogus)
check("live doubled-sheet shape: 42 ghosts, nothing missing, no false duplicates",
      len(rep[S.MISSING_IN_DB]) == 42 and not rep[S.MISSING_IN_EXCEL]
      and not rep[S.DUPLICATE_IN_EXCEL], S.summarize(rep))

print("\nSheet-title resolution (Excel tab names are case-insensitive)")
check("exact title resolves", S.resolve_sheet_title(["Testing", "Other"], "Testing") == "Testing")
check("differing case still resolves - the false 'sheet missing' bug",
      S.resolve_sheet_title(["testing", "Other"], "Testing") == "testing")
check("surrounding whitespace ignored", S.resolve_sheet_title([" testing "], "Testing") == " testing ")
check("genuinely absent sheet returns None", S.resolve_sheet_title(["a", "b"], "Testing") is None)
check("empty name never matches a real tab", S.resolve_sheet_title(["a"], "") is None)


print("\n" + ("ALL PASS" if not failures else "FAILURES: " + ", ".join(failures)))
sys.exit(1 if failures else 0)
