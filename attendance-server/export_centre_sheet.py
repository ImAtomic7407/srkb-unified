#!/usr/bin/env python3
"""
export_centre_sheet.py
======================

Publish ONE centre's full picture into that centre's OWN Google Sheet.

The local SQLite DB (database/attendance.db) is the source of truth; this reads
from it and rewrites the destination tabs. It is IDEMPOTENT — every tab it owns
is cleared and rewritten on each run — so it is safe to re-run any time to
refresh a centre's sheet. (Contrast with the live mirror path in app.py, which
appends row-by-row as attendance is marked.)

Tabs written, per centre:

  SYNC SUMMARY       index + counts, so the sheet explains itself
  MEMBERS            every active person on the centre roster
  BATCH - <name>     current members of each batch in that centre
  CALLING SUMMARY    every calling session for the centre, with call outcomes
  CALL - <name>      one tab per calling session: who calls whom, and the result
  MASTER_ALL         every attendance record for the centre
  <session>          one tab per session, that session's attendance

Calling data lives in the SEPARATE SRKB calling backend (a Prisma/SQLite DB), not
in attendance.db. It is opened READ-ONLY here; this script never writes to it.
The two systems are joined on centre code: SRKB `centres.code` == AttendEase
`centres.centre_id`. If that DB is missing or unreadable the calling tabs are
skipped with a warning and the rest of the export still runs.

Which spreadsheet a centre publishes to comes from centres.google_sheet_id —
the same per-centre routing the live sync uses. A centre with no sheet linked is
skipped rather than falling back to another centre's spreadsheet.

USAGE
-----
  python export_centre_sheet.py --centre centre_a_Base            # dry run
  python export_centre_sheet.py --centre centre_a_Base --apply
  python export_centre_sheet.py --all --apply                       # every linked centre
"""

import argparse
import os
import socket
import sqlite3
import sys
from datetime import datetime

import gspread

# Hard network timeout: gspread's default is unbounded, and a hung Sheets call
# in a batch export is indistinguishable from a crash.
socket.setdefaulttimeout(60)

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.path.join(SCRIPT_DIR, "database", "attendance.db")

# The SRKB calling backend's Prisma DB. Read-only, and optional — see module docstring.
SRKB_DB_PATH = os.environ.get(
    "SRKB_DB_PATH",
    os.environ.get("CALLING_BACKEND_DB_PATH", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "calling-backend", "prisma", "dev.db")),
)

sys.path.insert(0, SCRIPT_DIR)
from sheets_handler import get_sheets_client, ATTENDANCE_SHEET_HEADERS  # noqa: E402

MEMBER_HEADERS = ["Name", "Number", "Address", "Occupation", "Rounds",
                  "Remark", "Batch", "Status", "Added On"]
BATCH_ROSTER_HEADERS = ["Name", "Number", "Address", "Occupation", "Rounds",
                        "Remark", "Added On"]
CALL_HEADERS = ["Member", "Number", "Batch", "Assigned Caller", "Call Status",
                "Outcome", "Called At", "Assigned On", "Removed On"]
CALLING_SUMMARY_HEADERS = ["Calling Session", "Batch", "Scheduled", "Status", "Type",
                           "Callers", "Assigned", "Pending", "Called", "Will Attend",
                           "Wont Attend", "No Answer", "Attended"]

# Tab-title rules. Google allows long titles, but these are kept to 31 chars to
# stay aligned with the Excel mirror, where 31 is a hard limit.
MAX_TAB = 31
_BAD_TAB_CHARS = "/\\?*[]:"

# The index tab this tool maintains. NOT called "SUMMARY": Centre B's sheet
# already had a hand-maintained 'Summary' tab (995 rows), and Google treats tab
# names as case-INSENSITIVE for uniqueness — so asking for "SUMMARY" neither
# found it nor could create it, and every publish failed on that one tab. A
# distinct, obviously-owned name keeps this tool clear of anything a human made.
INDEX_TAB = "SYNC SUMMARY"


def clean(value):
    """Render one cell.

    The literal string 'null' is a known upstream data bug — a client posted the
    JS string where a date belonged. Show it as blank rather than printing the
    word "null", and never substitute a guessed value.
    """
    text = "" if value is None else str(value)
    return "" if text.strip().lower() == "null" else text


def tab_title(raw, used):
    """A unique, Sheets-legal tab title of at most MAX_TAB chars."""
    name = str(raw or "UNTITLED")
    for ch in _BAD_TAB_CHARS:
        name = name.replace(ch, "-")
    name = name.strip()[:MAX_TAB] or "UNTITLED"
    if name.lower() not in used:
        used.add(name.lower())
        return name
    # Collision after truncation — make room for a numeric suffix.
    for n in range(2, 100):
        suffix = f" ({n})"
        candidate = name[:MAX_TAB - len(suffix)] + suffix
        if candidate.lower() not in used:
            used.add(candidate.lower())
            return candidate
    raise ValueError(f"could not build a unique tab title for {raw!r}")


def ms_to_text(value):
    """SRKB stores timestamps as epoch milliseconds; render them readably."""
    if value in (None, "", 0):
        return ""
    try:
        return datetime.fromtimestamp(int(value) / 1000).strftime("%Y-%m-%d %H:%M")
    except (ValueError, OSError, OverflowError, TypeError):
        return str(value)


def fetch_calling(centre_id, quiet=False):
    """Calling sessions + per-person call outcomes for a centre.

    Returns (campaigns, assignments_by_campaign) or (None, None) if the calling
    backend's DB isn't available — calling is a separate system, and this export
    must still produce the attendance/roster tabs without it.
    """
    say = (lambda *a: None) if quiet else print
    if not os.path.exists(SRKB_DB_PATH):
        say(f"  (calling backend DB not found at {SRKB_DB_PATH} — calling tabs skipped)")
        return None, None
    try:
        # Read-only URI: this script must never mutate the calling system.
        conn = sqlite3.connect(f"file:{SRKB_DB_PATH}?mode=ro", uri=True)
    except sqlite3.Error as e:
        say(f"  (could not open calling backend DB read-only: {e} — calling tabs skipped)")
        return None, None

    try:
        # The two systems join on centre code, not on numeric id.
        row = conn.execute("SELECT id FROM centres WHERE code=?", (centre_id,)).fetchone()
        if not row:
            say(f"  (no matching centre '{centre_id}' in the calling backend — calling tabs skipped)")
            return [], {}
        srkb_centre_id = row[0]

        campaigns = conn.execute("""
            SELECT sc.id, sc.ae_session_id, COALESCE(b.name,''), sc.scheduled_at,
                   sc.status, sc.kind
              FROM session_campaigns sc
              LEFT JOIN batches b ON b.id = sc.batch_id
             WHERE sc.centre_id=?
             ORDER BY sc.scheduled_at
        """, (srkb_centre_id,)).fetchall()

        assignments = {}
        for camp in campaigns:
            assignments[camp[0]] = conn.execute("""
                SELECT COALESCE(r.name,''), bm.mobile, COALESCE(b.name,''),
                       COALESCE(u.name,''), sca.call_status,
                       COALESCE(sca.outcome_label,''), sca.called_at,
                       sca.created_at, sca.removed_at
                  FROM session_call_assignments sca
                  JOIN batch_members bm ON bm.id = sca.batch_member_id
                  LEFT JOIN registrations r
                         ON r.mobile = bm.mobile AND r.centre_id = bm.centre_id
                  LEFT JOIN batches b ON b.id = bm.batch_id
                  LEFT JOIN users u ON u.id = sca.caller_id
                 WHERE sca.campaign_id=?
                 ORDER BY COALESCE(u.name,''), COALESCE(r.name,'')
            """, (camp[0],)).fetchall()
        return campaigns, assignments
    except sqlite3.Error as e:
        say(f"  (calling backend query failed: {e} — calling tabs skipped)")
        return None, None
    finally:
        conn.close()


def fetch(conn, centre_id):
    """Everything this centre's sheet needs, straight from the DB."""
    q = conn.execute

    centre = q("SELECT centre_id, name, google_sheet_id, google_sheet_enabled "
               "FROM centres WHERE centre_id=?", (centre_id,)).fetchone()

    attendance = q("""
        SELECT COALESCE(u.name,''), a.mobile, COALESCE(u.address,''),
               COALESCE(u.occupation,''), COALESCE(a.session_id,''),
               COALESCE(a.batch_name,''), COALESCE(a.rounds,''),
               COALESCE(a.remark,''), COALESCE(a.date,'')
          FROM attendance a
          LEFT JOIN users u ON u.mobile = a.mobile
         WHERE a.centre_id=?
         ORDER BY a.timestamp ASC
    """, (centre_id,)).fetchall()

    members = q("""
        SELECT name, mobile, COALESCE(address,''), COALESCE(occupation,''),
               COALESCE(rounds,''), COALESCE(remark,''), COALESCE(batch_name,''),
               status, COALESCE(created_at,'')
          FROM centre_roster
         WHERE centre_id=? AND status='active'
         ORDER BY name COLLATE NOCASE
    """, (centre_id,)).fetchall()

    batches = q("""
        SELECT id, name, COALESCE(category,'youth')
          FROM batches
         WHERE centre_id=? AND (status IS NULL OR status='active')
         ORDER BY id
    """, (centre_id,)).fetchall()

    # A batch's CURRENT members are the roster rows scoped to that batch.
    # scope_key is authoritative here; batch_id is nullable on legacy rows.
    batch_members = {}
    for bid, bname, _cat in batches:
        batch_members[bid] = q("""
            SELECT name, mobile, COALESCE(address,''), COALESCE(occupation,''),
                   COALESCE(rounds,''), COALESCE(remark,''), COALESCE(created_at,'')
              FROM centre_roster
             WHERE centre_id=? AND status='active'
               AND (scope_key=? OR batch_id=?)
             ORDER BY name COLLATE NOCASE
        """, (centre_id, f"batch:{bid}", bid)).fetchall()

    return centre, attendance, members, batches, batch_members


def write_tab(spreadsheet, title, headers, rows, ws_lookup=None):
    """Clear and rewrite one tab in a single batched update.

    ws_lookup, when given, is a {title: worksheet} map fetched once by the
    caller. Without it every call costs an extra metadata read, which is how the
    read quota gets exhausted during a large publish.
    """
    ws = None
    if ws_lookup is not None:
        # Case-insensitive, because that is how Google decides whether a tab name
        # is already taken. Matching exactly meant we could miss an existing tab
        # and then fail trying to create it.
        ws = ws_lookup.get(title.lower())
    else:
        try:
            ws = spreadsheet.worksheet(title)
        except gspread.exceptions.WorksheetNotFound:
            ws = None
        # NOTE: only WorksheetNotFound may fall through to "create it". Catching
        # bare Exception here meant a transient 429 on the lookup was read as
        # "this tab doesn't exist", and the add then failed with 'a sheet with
        # that name already exists' — turning a retryable blip into a hard error.
        # Any other exception must propagate so the caller can retry.
    if ws is None:
        ws = spreadsheet.add_worksheet(title=title,
                                       rows=max(len(rows) + 20, 100), cols=max(len(headers) + 2, 12))
        if ws_lookup is not None:
            ws_lookup[title.lower()] = ws
    ws.clear()
    body = [headers] + [[clean(c) for c in r] for r in rows]
    ws.update(values=body, range_name="A1")
    try:
        ws.freeze(rows=1)
    except Exception:
        pass  # cosmetic only; never fail an export over a frozen header
    return len(rows)


def build_plan(conn, centre_id, include_attendance=True, quiet=False):
    """Work out every tab this centre's sheet should contain, and its contents.

    Returns (centre_meta, plan, overview_rows) where plan is a list of
    (title, headers, rows, description) and centre_meta is the centres row, or
    (None, None, None) if the centre is missing or has no sheet linked.

    Split out from export_centre so the live auto-syncer (sheet_autosync.py) and
    the CLI compute an identical plan — two implementations would drift.

    include_attendance=False builds only the roster/calling/summary tabs. Those
    are the ones with no live mirror: attendance rows are already appended to the
    sheet as they are marked, so rebuilding 150 session tabs on every poll would
    be pure waste.
    """
    say = (lambda *a: None) if quiet else print
    centre, attendance, members, batches, batch_members = fetch(conn, centre_id)
    if not centre:
        say(f"  !! centre {centre_id} not found")
        return None, None, None
    _cid, centre_name, sheet_id, sheet_enabled = centre

    if not sheet_id or not sheet_enabled:
        say(f"  !! {centre_id} ({centre_name}) has no Google Sheet linked — SKIPPED.")
        say("     Link one from the host dashboard (Centre Settings) first.")
        return None, None, None

    # Sessions, in first-seen order, from the attendance rows themselves.
    sessions = []
    for row in attendance:
        if row[4] and row[4] not in sessions:
            sessions.append(row[4])

    used = set()
    plan = []  # (title, headers, rows, description)
    plan.append(("MEMBERS", MEMBER_HEADERS, members,
                 f"All active members of {centre_name}"))
    for bid, bname, cat in batches:
        rows = batch_members.get(bid, [])
        plan.append((tab_title(f"BATCH - {bname}", used), BATCH_ROSTER_HEADERS, rows,
                     f"Current members of batch '{bname}' ({cat})"))
    # --- Calling sessions (from the separate SRKB calling backend) ---
    campaigns, call_assignments = fetch_calling(centre_id, quiet=quiet)
    n_campaigns = 0
    if campaigns:
        n_campaigns = len(campaigns)
        summary_rows = []
        for cid_, name, batch_name, sched, status, kind in campaigns:
            rows = call_assignments.get(cid_, [])
            live = [r for r in rows if not r[8]]  # r[8] = removed_at; exclude unassigned
            counts = {}
            for r in live:
                counts[r[4]] = counts.get(r[4], 0) + 1
            callers = sorted({r[3] for r in live if r[3]})
            summary_rows.append([
                name, batch_name, ms_to_text(sched), status, kind,
                ", ".join(callers), len(live),
                counts.get("pending", 0), counts.get("called", 0),
                counts.get("will_attend", 0), counts.get("wont_attend", 0),
                counts.get("no_answer", 0), counts.get("attended", 0),
            ])
        plan.append(("CALLING SUMMARY", CALLING_SUMMARY_HEADERS, summary_rows,
                     "Every calling session for this centre"))

        for cid_, name, batch_name, sched, status, kind in campaigns:
            rows = call_assignments.get(cid_, [])
            if not rows:
                # Sessions with no assignments (e.g. attendance-derived ones) stay
                # listed in CALLING SUMMARY but get no near-empty tab of their own.
                continue
            body = [[r[0], r[1], r[2], r[3], r[4], r[5],
                     ms_to_text(r[6]), ms_to_text(r[7]), ms_to_text(r[8])] for r in rows]
            # "CALL - " prefix keeps these distinct from the attendance session
            # tab of the same name (e.g. 'BG 27-07-26' exists as both).
            plan.append((tab_title(f"CALL - {name}", used), CALL_HEADERS, body,
                         f"Calling session '{name}' ({status})"))

    if include_attendance:
        plan.append(("MASTER_ALL", ATTENDANCE_SHEET_HEADERS, attendance,
                     "Every attendance record for this centre"))
        for sid in sessions:
            rows = [r for r in attendance if r[4] == sid]
            plan.append((tab_title(sid, used), ATTENDANCE_SHEET_HEADERS, rows,
                         f"Attendance for session '{sid}'"))

    stamp = datetime.now().strftime("%Y-%m-%d %H:%M")
    overview_rows = [
        ["Centre", centre_name], ["Centre ID", centre_id],
        ["Updated", stamp], ["Source", "attendance.db + SRKB calling backend"],
        [], ["Totals", ""],
        ["Members (active)", len(members)],
        ["Batches", len(batches)],
        ["Attendance records", len(attendance)],
        ["Sessions", len(sessions)],
        ["Calling sessions", n_campaigns],
        [], ["Tab", "Contents", "Rows"],
    ] + [[t, d, len(r)] for t, _h, r, d in plan]

    return centre, plan, overview_rows


def prune_stale_tabs(spreadsheet, expected_titles, quiet=False):
    """Delete tabs this exporter owns that are no longer in the plan.

    Without this, deleting a calling session or renaming a batch leaves a ghost
    tab behind that still shows the old data — worse than no tab at all, because
    it looks current. Only prefix-owned tabs are ever removed; anything a human
    added by hand is left strictly alone.
    """
    say = (lambda *a: None) if quiet else print
    removed = []
    keep = {t.lower() for t in expected_titles} | {INDEX_TAB.lower()}
    for ws in spreadsheet.worksheets():
        title = ws.title
        owned = title.upper().startswith(("BATCH - ", "CALL - ")) or \
            title.upper() in ("MEMBERS", "CALLING SUMMARY")
        if owned and title.lower() not in keep:
            try:
                spreadsheet.del_worksheet(ws)
                removed.append(title)
                say(f"    [removed stale] {title}")
            except Exception as e:
                say(f"    (could not remove stale tab {title!r}: {e})")
    return removed


def export_centre(conn, client, centre_id, apply_changes):
    centre, plan, overview_rows = build_plan(conn, centre_id)
    if not centre:
        return False
    _cid, centre_name, sheet_id, _enabled = centre

    print(f"  centre : {centre_name} ({centre_id})")
    print(f"  sheet  : {sheet_id}")
    print(f"  {INDEX_TAB:<34} {'index + counts':<46}")
    for title, _h, rows, desc in plan:
        flag = "   <-- EMPTY" if not rows else ""
        print(f"  {title:<34} {desc[:46]:<46} {len(rows):>5}{flag}")

    if not apply_changes:
        return True

    sh = client.open_by_key(sheet_id)
    ws_lookup = {w.title.lower(): w for w in sh.worksheets()}
    write_tab(sh, INDEX_TAB, ["Field", "Value", "Rows"], overview_rows, ws_lookup=ws_lookup)
    for title, headers, rows, _desc in plan:
        write_tab(sh, title, headers, rows, ws_lookup=ws_lookup)
        print(f"    [written] {title} ({len(rows)})")

    prune_stale_tabs(sh, [t for t, _h, _r, _d in plan])

    # Remove Google's default empty 'Sheet1' now that real tabs exist — but only
    # if it is genuinely empty, never if someone put data there.
    try:
        default_ws = sh.worksheet("Sheet1")
        if not any(any(str(c).strip() for c in row) for row in default_ws.get_all_values()):
            sh.del_worksheet(default_ws)
            print("    [removed] empty default 'Sheet1'")
    except Exception:
        pass
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--centre", action="append", default=[],
                    help="centre_id to export (repeatable)")
    ap.add_argument("--all", action="store_true",
                    help="export every centre that has a sheet linked")
    ap.add_argument("--apply", action="store_true",
                    help="actually write; without this it is a dry run")
    args = ap.parse_args()

    conn = sqlite3.connect(DB_PATH)
    if args.all:
        centres = [r[0] for r in conn.execute(
            "SELECT centre_id FROM centres WHERE status='active' "
            "AND google_sheet_id IS NOT NULL AND google_sheet_enabled=1 ORDER BY centre_id")]
    else:
        centres = args.centre
    if not centres:
        print("Nothing to do: pass --centre <id> or --all")
        return 1

    client = get_sheets_client() if args.apply else None
    print("MODE:", "APPLY (writing to Google Sheets)" if args.apply else "DRY RUN (no writes)")
    print()

    failures = 0
    for cid in centres:
        print(f"=== {cid}")
        if not export_centre(conn, client, cid, args.apply):
            failures += 1
        print()

    conn.close()
    print("Done." if not failures else f"Done, with {failures} centre(s) skipped.")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
