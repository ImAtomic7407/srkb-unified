#!/usr/bin/env python3
"""
reconcile_sync_audit.py
=======================

Standalone *sync reconciliation reader* for the attendance system.

WHY THIS EXISTS
---------------
The local SQLite DB (database/attendance.db) is the source of truth: every
attendance row is committed there BEFORE any mirroring happens. Mirroring to
the live Excel workbook and to Google Sheets is done asynchronously by a single
background worker (`excel_sync_worker` in app.py) draining an in-memory queue.

Two known ways data silently fails to reach Google Sheets:

  Mode A (stall / data loss): the worker wedges on the Excel COM step
    (append_attendance_to_live_workbook -> PowerShell/Excel) and stops draining.
    Everything queued after that point is marked in the DB but never mirrored,
    and is lost from the queue on the next server restart. This is what caused
    the June 29 gap ("synced 28 then stopped").

  Mode B (swallowed per-row error): the Google Sheets block is best-effort and
    NOT retried; if MASTER_ALL appends but the session-tab append throws, the
    row is permanently missing from the session tab with no record of it. This
    is the slow long-run drift ("server file full, google sheet has less").

This tool reconciles the DB (truth) against Google Sheets and reports EVERY DB
row that is missing from MASTER_ALL and/or its session tab, with full detail,
into a dedicated `SYNC_AUDIT` worksheet for later processing.

It is READ-ONLY by default. Pass --backfill to actually append the missing
rows to the correct destinations.

USAGE
-----
  python reconcile_sync_audit.py                 # audit today, write SYNC_AUDIT
  python reconcile_sync_audit.py --date 2026-06-29
  python reconcile_sync_audit.py --date 2026-06-29 --date 2026-06-28
  python reconcile_sync_audit.py --all-dates     # audit every date in the DB
  python reconcile_sync_audit.py --date 2026-06-29 --backfill   # also repair
  python reconcile_sync_audit.py --date 2026-06-29 --no-audit-sheet  # console only
"""

import argparse
import os
import re
import socket
import sqlite3
import sys
from datetime import datetime

# Hard network timeout so this auditor can never itself hang the way the
# production worker did (the original gspread calls had no timeout).
socket.setdefaulttimeout(45)

import gspread

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.path.join(SCRIPT_DIR, "database", "attendance.db")
AUDIT_SHEET = "SYNC_AUDIT"

# Reuse the project's existing Sheets configuration / auth.
sys.path.insert(0, SCRIPT_DIR)
from sheets_handler import (  # noqa: E402
    get_sheets_client,
    SPREADSHEET_ID,
    ATTENDANCE_SHEET_HEADERS,
)

MASTER_SHEET = "MASTER_ALL"

# Audit sheet schema: full row detail + diagnosis columns.
AUDIT_HEADERS = [
    "AuditedAt",
    "Date",
    "CentreId",
    "SessionId",
    "SessionTab",
    "BatchName",
    "Name",
    "Number",
    "Address",
    "Occupation",
    "Rounds",
    "Remark",
    "InMasterAll",       # YES / NO
    "InSessionTab",      # YES / NO / TAB_MISSING
    "MissingFrom",       # human summary: "MASTER_ALL, session tab"
    "Status",            # OK / MISSING / REPAIRED / REPAIR_FAILED
    "Note",
]


def norm_mobile(v):
    """Normalize a phone number to a bare digit string for reliable matching."""
    return re.sub(r"\D", "", str(v or ""))


def log(msg):
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}")


# --------------------------------------------------------------------------- #
# DB side (source of truth)
# --------------------------------------------------------------------------- #
def load_db_rows(dates, all_dates=False):
    """Return list of dict rows from the DB joined with user + session info."""
    if not os.path.exists(DB_PATH):
        raise FileNotFoundError(f"DB not found at {DB_PATH}")

    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    cur = conn.cursor()

    where, params = "", []
    if not all_dates:
        placeholders = ",".join("?" for _ in dates)
        where = f"WHERE a.date IN ({placeholders})"
        params = list(dates)

    # session_mappings gives us the human display name (full session name) and
    # batch linkage; users gives name/address/occupation.
    cur.execute(
        f"""
        SELECT
            a.mobile        AS mobile,
            a.date          AS date,
            a.batch_name    AS batch_name,
            a.session_id    AS session_id,
            a.centre_id     AS centre_id,
            a.rounds        AS rounds,
            a.remark        AS remark,
            u.name          AS name,
            u.address       AS address,
            u.occupation    AS occupation,
            sm.display_name AS display_name
        FROM attendance a
        LEFT JOIN users u            ON u.mobile = a.mobile
        LEFT JOIN session_mappings sm ON sm.session_id = a.session_id
        {where}
        ORDER BY a.date, a.session_id, a.id
        """,
        params,
    )
    rows = [dict(r) for r in cur.fetchall()]
    conn.close()
    return rows


# --------------------------------------------------------------------------- #
# Sheets side
# --------------------------------------------------------------------------- #
def open_spreadsheet():
    client = get_sheets_client()
    return client.open_by_key(SPREADSHEET_ID)


def read_tab_values(sh, title):
    """Return (exists, all_values). One bulk read per tab — no per-row calls."""
    try:
        ws = sh.worksheet(title)
    except gspread.exceptions.WorksheetNotFound:
        return False, []
    try:
        return True, ws.get_all_values()
    except Exception as e:
        log(f"  ! failed reading tab '{title}': {e}")
        return True, []


def header_index(values):
    """Map lowercased header name -> column index from row 1."""
    if not values:
        return {}
    return {str(h).strip().lower(): i for i, h in enumerate(values[0])}


def candidate_tab_names(session_id, display_name):
    """Tab could be named by the full display name or the sanitized session id."""
    names = []
    for n in (display_name, session_id):
        if n and n not in names:
            names.append(str(n))
        # The writer truncates to 31 chars for Excel-compatible tab names.
        if n and str(n)[:31] not in names:
            names.append(str(n)[:31])
    return names


# --------------------------------------------------------------------------- #
# Reconciliation
# --------------------------------------------------------------------------- #
def get_centre_sheet_mappings():
    if not os.path.exists(DB_PATH):
        return {}
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    try:
        cur.execute("SELECT centre_id, google_sheet_id FROM centres WHERE google_sheet_id IS NOT NULL AND google_sheet_enabled = 1")
        mappings = {r[0]: r[1] for r in cur.fetchall()}
    except Exception as e:
        log(f"Failed to fetch centre sheet mappings: {e}")
        mappings = {}
    conn.close()
    return mappings


def reconcile(dates, all_dates, write_audit_sheet, do_backfill):
    db_rows = load_db_rows(dates, all_dates)
    log(f"Loaded {len(db_rows)} attendance row(s) from DB "
        f"({'all dates' if all_dates else ', '.join(dates)}).")
    if not db_rows:
        log("Nothing to reconcile.")
        return []

    centre_sheets = get_centre_sheet_mappings()
    # Group rows by centre_id
    rows_by_centre = {}
    for row in db_rows:
        cid = row.get("centre_id") or "centre_b_Base"
        rows_by_centre.setdefault(cid, []).append(row)

    all_audit_records = []
    client = get_sheets_client()

    for centre_id, centre_rows in rows_by_centre.items():
        sheet_id = centre_sheets.get(centre_id)
        if not sheet_id:
            log(f"Skipping centre '{centre_id}': no Google Sheet configured/enabled.")
            continue

        log(f"Reconciling {len(centre_rows)} rows for centre '{centre_id}' against sheet '{sheet_id}'...")
        try:
            sh = client.open_by_key(sheet_id)
        except Exception as e:
            log(f"  ! Failed to open sheet for centre '{centre_id}': {e}")
            continue

        # --- Read MASTER_ALL once, index by (mobile, date) ---------------------- #
        master_exists, master_vals = read_tab_values(sh, MASTER_SHEET)
        master_idx = header_index(master_vals)
        m_num = master_idx.get("number", 1)
        m_date = master_idx.get("date", 8)
        master_keys = set()
        for r in master_vals[1:] if master_vals else []:
            num = norm_mobile(r[m_num]) if len(r) > m_num else ""
            dt = str(r[m_date]).strip() if len(r) > m_date else ""
            if num:
                master_keys.add((num, dt))
        log(f"  MASTER_ALL: {'found' if master_exists else 'MISSING'}, "
            f"{len(master_keys)} indexed rows.")

        # --- Cache session-tab membership (mobile-set) per resolved tab --------- #
        tab_cache = {}  # tab_title -> set(norm_mobile) or None if tab missing

        def session_members(session_id, display_name):
            for name in candidate_tab_names(session_id, display_name):
                if name in tab_cache:
                    if tab_cache[name] is not None:
                        return name, tab_cache[name]
                    continue
                exists, vals = read_tab_values(sh, name)
                if not exists:
                    tab_cache[name] = None
                    continue
                idx = header_index(vals)
                ncol = idx.get("number", 1)
                members = {
                    norm_mobile(r[ncol]) for r in vals[1:] if len(r) > ncol and norm_mobile(r[ncol])
                }
                tab_cache[name] = members
                return name, members
            return None, None  # no tab found under any candidate name

        centre_audit_records = []
        centre_missing_count = 0

        for row in centre_rows:
            mob = norm_mobile(row["mobile"])
            date = str(row["date"]).strip()
            session_id = row["session_id"]
            display_name = row.get("display_name")

            in_master = (mob, date) in master_keys
            tab_title, members = session_members(session_id, display_name)
            if members is None:
                in_session = "TAB_MISSING"
                session_tab_label = (display_name or session_id)
            else:
                in_session = "YES" if mob in members else "NO"
                session_tab_label = tab_title

            missing_from = []
            if not in_master:
                missing_from.append("MASTER_ALL")
            if in_session in ("NO", "TAB_MISSING"):
                missing_from.append(
                    "session tab" if in_session == "NO" else f"session tab (tab absent)"
                )

            status = "OK" if not missing_from else "MISSING"
            if missing_from:
                centre_missing_count += 1

            rec = {
                "AuditedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                "Date": date,
                "CentreId": row["centre_id"],
                "SessionId": session_id,
                "SessionTab": session_tab_label,
                "BatchName": row.get("batch_name", ""),
                "Name": row.get("name", ""),
                "Number": mob,
                "Address": row.get("address", ""),
                "Occupation": row.get("occupation", ""),
                "Rounds": row.get("rounds", ""),
                "Remark": row.get("remark", ""),
                "InMasterAll": "YES" if in_master else "NO",
                "InSessionTab": in_session,
                "MissingFrom": ", ".join(missing_from),
                "Status": status,
                "Note": "",
            }
            centre_audit_records.append(rec)
            all_audit_records.append(rec)

        log(f"  Reconciliation for '{centre_id}': {centre_missing_count} of {len(centre_rows)} DB rows are MISSING "
            f"from at least one destination.")

        if do_backfill:
            _backfill(sh, centre_audit_records)

        if write_audit_sheet:
            _write_audit_sheet(sh, centre_audit_records)

    # Console summary of the gaps, grouped by date+session.
    _print_summary(all_audit_records)
    return all_audit_records


def _rec_to_sheet_row(rec):
    """Build a row in the canonical ATTENDANCE_SHEET_HEADERS order."""
    return [
        rec["Name"], rec["Number"], rec["Address"], rec["Occupation"],
        rec["SessionTab"], rec["BatchName"], rec["Rounds"], rec["Remark"], rec["Date"],
    ]


def _ensure_tab(sh, title):
    """Return a worksheet, creating it with headers if absent."""
    try:
        return sh.worksheet(title)
    except gspread.exceptions.WorksheetNotFound:
        ws = sh.add_worksheet(title=title[:31], rows="200", cols="20")
        ws.append_row(ATTENDANCE_SHEET_HEADERS)
        return ws


def _backfill(sh, audit_records):
    """Bulk-append missing rows: one append_rows() call per destination tab."""
    # Group missing rows by destination tab.
    buckets = {}  # tab_title -> list[row]
    missing = [r for r in audit_records if r["Status"] == "MISSING"]
    for rec in missing:
        row = _rec_to_sheet_row(rec)
        if rec["InMasterAll"] == "NO":
            buckets.setdefault(MASTER_SHEET, []).append(row)
        if rec["InSessionTab"] in ("NO", "TAB_MISSING") and rec["SessionTab"]:
            buckets.setdefault(rec["SessionTab"][:31], []).append(row)

    repaired_tabs = 0
    for tab, rows in buckets.items():
        try:
            ws = _ensure_tab(sh, tab)
            ws.append_rows(rows, value_input_option="USER_ENTERED")
            log(f"  backfilled {len(rows)} row(s) into '{tab}'.")
            repaired_tabs += 1
        except Exception as e:
            log(f"  ! backfill FAILED for '{tab}': {e}")
            for rec in missing:
                rec["Note"] = (rec["Note"] + f"; {tab} failed: {e}")[:200]

    for rec in missing:
        rec["Status"] = "REPAIRED" if "failed" not in rec["Note"] else "REPAIR_FAILED"
    log(f"Backfill: wrote to {repaired_tabs} tab(s), {len(missing)} row(s) total.")


def _write_audit_sheet(sh, audit_records):
    """Write/replace the SYNC_AUDIT tab with all audited rows (missing first)."""
    try:
        ws = sh.worksheet(AUDIT_SHEET)
        ws.clear()
    except gspread.exceptions.WorksheetNotFound:
        ws = sh.add_worksheet(title=AUDIT_SHEET, rows="2000", cols=str(len(AUDIT_HEADERS)))

    ordered = sorted(audit_records, key=lambda r: (r["Status"] == "OK", r["Date"], r["SessionId"]))
    body = [[rec.get(h, "") for h in AUDIT_HEADERS] for rec in ordered]
    ws.update([AUDIT_HEADERS] + body)
    log(f"Wrote {len(ordered)} row(s) to '{AUDIT_SHEET}' tab.")


def _print_summary(audit_records):
    missing = [r for r in audit_records if r["Status"] in ("MISSING", "REPAIR_FAILED")]
    if not missing:
        log("All DB rows are present in Google Sheets. No gaps. [OK]")
        return
    log("---- GAP SUMMARY (DB row not fully mirrored) ----")
    by_group = {}
    for r in missing:
        key = (r["Date"], r["SessionTab"])
        by_group.setdefault(key, []).append(r)
    for (date, tab), rows in sorted(by_group.items()):
        log(f"  {date} | {tab}: {len(rows)} missing -> "
            f"{', '.join(sorted(set(r['MissingFrom'] for r in rows)))}")


def main():
    ap = argparse.ArgumentParser(description="Reconcile DB attendance against Google Sheets.")
    ap.add_argument("--date", action="append", default=[],
                    help="Date to audit (YYYY-MM-DD). Repeatable. Defaults to today.")
    ap.add_argument("--all-dates", action="store_true", help="Audit every date in the DB.")
    ap.add_argument("--no-audit-sheet", action="store_true",
                    help="Do not write the SYNC_AUDIT tab (console only).")
    ap.add_argument("--backfill", action="store_true",
                    help="Append missing rows to their destinations (mutates the sheet).")
    args = ap.parse_args()

    dates = args.date or [datetime.now().strftime("%Y-%m-%d")]
    log(f"Spreadsheet: {SPREADSHEET_ID}")
    if args.backfill:
        log("MODE: BACKFILL (will write missing rows to Google Sheets).")
    else:
        log("MODE: READ-ONLY audit.")

    reconcile(
        dates=dates,
        all_dates=args.all_dates,
        write_audit_sheet=not args.no_audit_sheet,
        do_backfill=args.backfill,
    )


if __name__ == "__main__":
    main()
