import os
import json
import io
import hashlib
import queue
import shutil
import glob
import time
import signal
import threading
import pandas as pd
import socket
from datetime import datetime, date
from flask import Flask, request, jsonify, render_template, session, send_file, redirect, url_for, g
import jwt as _pyjwt
from flask_sqlalchemy import SQLAlchemy
from flask_compress import Compress
from waitress import serve
from sqlalchemy.engine import Engine
from sqlalchemy import event
import re
import subprocess
import sys
import logging
import webbrowser
import tempfile
import urllib.request
from openpyxl import load_workbook, Workbook
from sheets_handler import append_row_to_google_sheet

# Host-side Roster Inject. Pure helpers only — roster_inject imports nothing from
# this module, so its parsing/validation can be unit-tested without starting the
# Excel sync workers that fire on `import app`.
# normalize_import_mobile now lives there as the single canonical implementation.
import roster_inject
import session_naming
from roster_inject import normalize_import_mobile

# Session sheet <-> database drift detector. Pure comparison logic, no Flask/DB,
# so the normalisation rules can be unit-tested without a workbook or a database.
import session_sync

# Automatic recovery from Excel file locks. Pure helpers, no Flask/DB imports, so
# the detect/kill/sweep logic can be exercised without starting this app.
import excel_lock_guard

# Load .env file if present (additive — does not override vars already set in environment)
try:
    _env_path = os.path.join(os.path.dirname(__file__), '.env')
    if os.path.exists(_env_path):
        with open(_env_path) as _ef:
            for _line in _ef:
                _line = _line.strip()
                if _line and not _line.startswith('#') and '=' in _line:
                    _k, _, _v = _line.partition('=')
                    os.environ.setdefault(_k.strip(), _v.strip())
except Exception:
    pass

# Suppress Waitress queue warnings
logging.getLogger("waitress.queue").setLevel(logging.ERROR)

# ── Crash / error log setup ───────────────────────────────────────────────────
# Configured properly after LOG_PATH is defined (see _setup_file_logging below).
import traceback as _traceback

def _setup_file_logging(log_path):
    """Wire Python logging + sys.excepthook to write everything to server_logs.txt."""
    file_handler = logging.FileHandler(log_path, encoding='utf-8')
    file_handler.setLevel(logging.WARNING)
    file_handler.setFormatter(logging.Formatter(
        '[%(asctime)s] %(levelname)s %(name)s: %(message)s',
        datefmt='%Y-%m-%d %H:%M:%S'
    ))
    # Attach to root logger — catches Waitress, SQLAlchemy, Flask internals
    root_logger = logging.getLogger()
    if not any(isinstance(h, logging.FileHandler) for h in root_logger.handlers):
        root_logger.addHandler(file_handler)
    root_logger.setLevel(logging.WARNING)

    # Capture completely unhandled exceptions (process-level crashes)
    def _excepthook(exc_type, exc_value, exc_tb):
        if issubclass(exc_type, KeyboardInterrupt):
            sys.__excepthook__(exc_type, exc_value, exc_tb)
            return
        msg = ''.join(_traceback.format_exception(exc_type, exc_value, exc_tb))
        try:
            with open(log_path, 'a', encoding='utf-8') as f:
                from datetime import datetime as _dt
                f.write(f"[{_dt.now().strftime('%Y-%m-%d %H:%M:%S')}] FATAL UNHANDLED EXCEPTION:\n{msg}\n")
        except Exception:
            pass
        sys.__excepthook__(exc_type, exc_value, exc_tb)

    sys.excepthook = _excepthook
# ─────────────────────────────────────────────────────────────────────────────

sys.dont_write_bytecode = True

if getattr(sys, 'frozen', False):
    basedir = os.path.abspath(os.path.dirname(sys.executable))
else:
    basedir = os.path.abspath(os.path.dirname(__file__))

# Central Path Definitions
DB_DIR = os.path.join(basedir, 'database')
SERVER_DIR = os.path.join(basedir, 'Server file')
LOG_DIR = os.path.join(basedir, 'logs')
BACKUP_DIR = os.path.join(basedir, 'backups')
DATA_DIR = os.path.join(basedir, 'Data')
TEMP_DIR = os.path.join(basedir, 'temp')

def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

LOCAL_LAN_IP = get_local_ip()

def is_host_device():
    # Identify if requester is the physical machine running the server
    # Now supports LAN IP as well as loopback
    remote = request.remote_addr
    return remote in ['127.0.0.1', '::1', 'localhost', LOCAL_LAN_IP]

# P4.1 FIX: Threading lock for multi-sheet Excel file manipulations.
# MUST be reentrant (RLock): the sync worker holds it while calling
# append_attendance_to_live_workbook(), whose openpyxl fallback re-acquires the
# same lock on the same thread. A plain Lock() would self-deadlock there.
EXCEL_WRITE_LOCK = threading.RLock()

# Background Sync Queue (P4.1, P4.2)
excel_sync_queue = queue.Queue()
storage_sync_queue = queue.Queue()

# Durable pending-sync store (2026-06-29): when a row cannot be mirrored to the
# live Excel and/or Google Sheets (e.g. the workbook is open/locked in desktop
# Excel), we persist it here instead of dropping it. A background retry worker
# re-attempts these every PENDING_SYNC_INTERVAL seconds until they succeed, so
# rows auto-flush once the blocking condition clears — and survive a restart.
PENDING_SYNC_PATH = os.path.join(DB_DIR, 'pending_sync.jsonl')
PENDING_SYNC_LOCK = threading.Lock()
PENDING_SYNC_INTERVAL = 60

# Calling Backend webhook queue — fire-and-forget events to the Node.js backend
_calling_backend_webhook_queue = queue.Queue()

# Durable webhook outbox (2026-07-04): the live webhook POST is fire-and-forget
# with a 0.5s timeout and no retry — a single missed delivery (CB restarting,
# a slow response, a network blip) silently loses the event until the next
# daily reconcile picks it up. On failure we persist here instead of dropping.
# DEDUPED BY (event_type, entity_type, entity_id): if the same centre (or any
# entity) fails repeatedly, each new failure OVERWRITES its existing entry
# rather than appending another one — so a centre stuck failing for hours
# produces exactly one retry entry, not one per attempt.
CB_WEBHOOK_OUTBOX_PATH = os.path.join(DB_DIR, 'cb_webhook_outbox.jsonl')
CB_WEBHOOK_OUTBOX_LOCK = threading.Lock()
CB_WEBHOOK_RETRY_INTERVAL = 60

# ── Cross-system sync journal ────────────────────────────────────────────────
# Single shared append-only log that BOTH AE and the calling backend (CB) write
# to for every cross-system connection point: webhook fires, outbox job
# attempts (both directions), reconcile runs/findings, and internal endpoint
# calls. Added 2026-07-20 after tracing every AE<->CB connection point and
# finding several classes of gap (a create path that never enqueued its sync
# job, a job kind with no delivery handler, jobs that exhaust retries with only
# a log line nobody watches). None of those individual fixes generalize — what
# does is one place a human (or a script) can grep/replay from regardless of
# which side or which specific job kind failed. Every line carries the full
# original payload specifically so a failed entry can be reprocessed later
# without having to reconstruct what was being attempted.
# Lives outside both repos (shared user-writable location) since either
# codebase could be redeployed/cleaned independently of this history.
SYNC_JOURNAL_PATH = os.path.join(os.path.expanduser("~"), "cross_system_sync_journal.jsonl")
SYNC_JOURNAL_LOCK = threading.Lock()


def journal_write(system, direction, connection, kind, status, entity=None, detail=None,
                   payload=None, error=None, attempt=None, source=None):
    """Append one structured record to the shared cross-system sync journal.

    system:     'AE' | 'CB'
    direction:  'AE->CB' | 'CB->AE' | 'internal'
    connection: 'webhook' | 'outbox' | 'reconcile' | 'endpoint'
    kind:       event_type / outbox job kind / reconcile check name / endpoint path
    status:     'attempted' | 'success' | 'failed' | 'self_healed' | 'skipped' | 'needs_attention'
    entity:     best-effort id (username, session_id, batch_id, centre_id, ...)
    payload:    full original payload — this is what makes a failed entry replayable
    """
    from datetime import datetime as _dt, timezone as _tz
    record = {
        "ts": _dt.now(_tz.utc).isoformat(timespec="milliseconds"),
        "system": system,
        "direction": direction,
        "connection": connection,
        "kind": kind,
        "status": status,
        "entity": entity,
        "detail": detail,
        "payload": payload,
        "error": str(error)[:1000] if error else None,
        "attempt": attempt,
        "source": source,
    }
    try:
        line = json.dumps(record, ensure_ascii=False, default=str)
    except Exception as e:
        line = json.dumps({**{k: v for k, v in record.items() if k != "payload"},
                            "payload": "<unserializable>", "journal_write_error": str(e)})
    with SYNC_JOURNAL_LOCK:
        try:
            with open(SYNC_JOURNAL_PATH, "a", encoding="utf-8") as f:
                f.write(line + "\n")
        except Exception as e:
            write_log(f"CRITICAL: sync journal write failed: {e}")


# ── Excel lock self-healing ──────────────────────────────────────────────────
# An orphan COM-spawned EXCEL.EXE left holding the workbook open is the confirmed
# root cause of the recurring "data is lost" reports: every openpyxl save then
# raises PermissionError forever, so rows stay stuck in the pending store and
# never reach Excel or Google Sheets. See excel_lock_guard.py for the full
# reasoning. EVERY openpyxl save of a real workbook must go through
# save_workbook_safely() so the lock is detected, cleared and the write retried
# with no human step — including the write paths added in future.

def _excel_guard_journal(path, reason):
    """Adapter so the guard reports through the shared cross-system journal."""
    def _record(status, detail, error=None, reason=reason):
        try:
            journal_write(
                system="AE", direction="internal", connection="endpoint",
                kind="excel_lock_guard", status=status, entity=os.path.basename(path),
                detail=detail, payload={"path": path, "reason": reason}, error=error,
            )
        except Exception:
            pass
    return _record


def save_workbook_safely(wb, path, reason="", attempts=3):
    """wb.save(path), self-healing an orphan Excel lock and retrying.

    Re-raises if the lock cannot be cleared (i.e. a human genuinely has the
    workbook open) so callers keep their existing failure handling — for
    attendance that is the durable pending store, whose 60s retry worker re-runs
    this guard and completes the write by itself once the file is free.
    """
    return excel_lock_guard.safe_excel_write(
        path, lambda: _atomic_workbook_save(wb, path),
        log=write_log, journal=_excel_guard_journal(path, reason),
        attempts=attempts, reason=reason,
    )


def _atomic_workbook_save(wb, path):
    """Save to a sibling temp file, then swap it over `path` in one step.

    wb.save(path) writes straight into the live file, so anything that stops it
    part-way leaves a file that is half one save and half another. On 2026-09-28
    at 17:35 exactly that happened — the lock guard killed an Excel that was
    saving while the reconcile saved too — and live_attendance_master.xlsx became
    an unreadable zip: every Lists tab and download went blank until it was
    repaired by hand. With the swap the file on disk is always one whole save.

    os.replace fails with a sharing violation while another handle is open (a
    read_only reader serving a download, or Excel). A reader lets go within
    moments, so wait briefly; anything longer is re-raised as the PermissionError
    the lock guard already knows how to handle.
    """
    tmp = f"{path}.~saving-{os.getpid()}-{threading.get_ident()}.xlsx"
    try:
        wb.save(tmp)
        for attempt in range(20):
            try:
                os.replace(tmp, path)
                return
            except PermissionError:
                if attempt == 19:
                    raise
                time.sleep(0.25)
    finally:
        if os.path.exists(tmp):
            try:
                os.remove(tmp)
            except OSError:
                pass


def _run_com_helper(*args, **kwargs):
    """subprocess.run for an Excel COM helper, marked as an in-flight COM write.

    Must be used for EVERY PowerShell COM helper launch. While one is running the
    workbook is legitimately locked by that Excel, and the lock guard must not
    mistake it for an orphan and kill it mid-save. See excel_lock_guard.com_write().

    It also holds EXCEL_WRITE_LOCK. It used not to, so a reconcile save could
    run while Excel was saving the same file — the two writes overlapped and
    corrupted the workbook on 2026-09-28 (see _atomic_workbook_save). Every other
    writer of the workbook already takes this lock; the lock is reentrant, so a
    caller that holds it already is unaffected.
    """
    with EXCEL_WRITE_LOCK, excel_lock_guard.com_write():
        return subprocess.run(*args, **kwargs)


def _after_com_failure(err, path, reason):
    """Clear the orphan a TIMED-OUT Excel COM helper almost certainly just made.

    subprocess.run(timeout=30) kills the PowerShell helper, but Excel is launched
    by DCOM and is NOT its child — it survives with the workbook still open,
    holding an exclusive handle that blocks every later write. Every COM failure
    in this system's recorded history is exactly this timeout (13 of them, on the
    dates of the reported data-loss incidents), which is why this is worth doing
    at the moment we know an orphan was created rather than waiting for some
    later write to trip over it. Only reacts to a timeout: any other COM failure
    (bad sheet name, permissions) leaves no orphan and must not trigger a kill.
    """
    if isinstance(err, subprocess.TimeoutExpired):
        clear_excel_lock_if_blocked(path, reason=f"com_timeout:{reason}")


def clear_excel_lock_if_blocked(path, reason=""):
    """Best-effort lock clear for write paths that are not a plain wb.save()
    (Excel COM helpers, external tools). Never raises."""
    try:
        return excel_lock_guard.clear_excel_lock(
            path, log=write_log, journal=_excel_guard_journal(path, reason), reason=reason)
    except Exception as e:
        write_log(f"Excel lock guard: clear failed for {path}: {e}")
        return "error"


if os.name == "nt":
    WINDOWS_STARTUPINFO = subprocess.STARTUPINFO()
    WINDOWS_STARTUPINFO.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    WINDOWS_STARTUPINFO.wShowWindow = subprocess.SW_HIDE
    WINDOWS_NO_WINDOW_KWARGS = {
        "startupinfo": WINDOWS_STARTUPINFO,
        "creationflags": subprocess.CREATE_NO_WINDOW,
    }
else:
    WINDOWS_NO_WINDOW_KWARGS = {}


def _hidden_process_kwargs():
    """Return Windows flags that suppress console windows for child processes."""
    return dict(WINDOWS_NO_WINDOW_KWARGS)

def _build_sync_data_row(task):
    """Normalise a queue/pending task into the canonical mirror row."""
    return {
        "name": task.get("name", "Unknown"),
        "mobile": str(task.get("mobile", "")).strip(),
        "address": task.get("address", ""),
        "occupation": task.get("occupation", ""),
        "session": task.get("session_name") or task.get("session_id") or "",
        "batch": task.get("batch_name") or task.get("batch") or "",
        "rounds": task.get("rounds", ""),
        "remark": task.get("remark", ""),
        "date": task.get("date", datetime.now().strftime("%Y-%m-%d")),
    }


def _try_mirror_destinations(data_row, sheet_name, need_excel, need_sheets, centre_id=None,
                             pending_tabs=None):
    """Attempt the still-needed mirror destinations INDEPENDENTLY.

    Excel and Google Sheets are decoupled: a failure on one (e.g. the live
    workbook is open/locked in desktop Excel) never blocks the other. Returns
    the updated (need_excel, need_sheets, pending_tabs) — True means STILL pending.

    centre_id decides WHICH Google spreadsheet this row goes to. Without it
    every centre wrote into one shared sheet; see get_google_sheet_id_for_centre.

    pending_tabs: which Google tabs this row still owes. None means "not started
    yet" -> MASTER_ALL plus the session tab. On a retry it holds ONLY the tabs
    that actually failed, so re-running can never duplicate a tab that already
    succeeded. Returned so the caller can persist it.

    Runs exclusively on the background sync threads (excel_sync_worker and
    pending_sync_retry_worker) — never on a request path, so nothing here can
    add latency to a client response.
    """
    mob = data_row.get("mobile", "")
    if need_excel:
        try:
            # EXCEL_WRITE_LOCK is reentrant; the writer's openpyxl fallback
            # re-acquires it on this same thread.
            with EXCEL_WRITE_LOCK:
                append_attendance_to_live_workbook(data_row, sheet_name=sheet_name)
            write_log(f"Async Excel Sync (LOG): {mob} in {sheet_name} [SUCCESS]")
            need_excel = False
        except Exception as e:
            write_log(f"Excel mirror still pending ({mob}): {e}")
    if need_sheets:
        spreadsheet_id = get_google_sheet_id_for_centre(centre_id)
        if not spreadsheet_id:
            # Resolve to "done, nothing to do" rather than "pending". Leaving it
            # pending would grow the durable pending-sync store without bound for
            # a centre that may never get a sheet, and retrying can't help — the
            # row is safe in SQLite and is backfillable via reconcile_sync_audit
            # once a sheet is linked.
            write_log(
                f"Google Sheets skipped ({mob}): centre '{centre_id or 'unknown'}' "
                f"has no Google Sheet linked — row is safe in the database."
            )
            need_sheets = False
            pending_tabs = []
        else:
            # append_row_to_google_sheet NEVER raises — it swallows the error and
            # returns False. The old code wrapped it in try/except and logged
            # SUCCESS whenever nothing was raised, i.e. always, and cleared
            # need_sheets unconditionally so the row was never handed to the
            # pending store either. Check the RETURN VALUE, per tab.
            tabs = pending_tabs if pending_tabs is not None else (
                ["MASTER_ALL"] + ([sheet_name] if sheet_name else []))
            failed = []
            for tab in tabs:
                try:
                    wrote = append_row_to_google_sheet(
                        data_row, sheet_name=tab, spreadsheet_id=spreadsheet_id,
                        on_error=write_log)
                except Exception as e:
                    # Defensive: a future edit could make it raise after all.
                    wrote = False
                    write_log(f"Google Sheets mirror raised ({mob}, tab '{tab}'): {e}")
                if not wrote:
                    failed.append(tab)
            pending_tabs = failed
            if failed:
                need_sheets = True
                write_log(
                    f"Google Sheets Sync: {mob} -> {centre_id} [FAILED] "
                    f"tab(s) {', '.join(failed)} not written — row is safe in the "
                    f"database and queued for retry."
                )
            else:
                written = ", ".join(tabs) if tabs else "nothing outstanding"
                write_log(f"Google Sheets Sync: {mob} -> {centre_id} [SUCCESS] ({written})")
                need_sheets = False
    return need_excel, need_sheets, pending_tabs


def _persist_pending_sync(data_row, sheet_name, need_excel, need_sheets,
                          centre_id="", session_id="", task_date="", pending_tabs=None):
    """Append one unresolved mirror row to the durable pending-sync store."""
    entry = {
        "data_row": data_row,
        "sheet_name": sheet_name,
        "need_excel": bool(need_excel),
        "need_sheets": bool(need_sheets),
        # Only the tabs that actually failed, so the retry cannot duplicate a
        # tab that already went in. Absent on entries written before this
        # existed — _process_pending_sync_once treats that as "retry both".
        "pending_tabs": list(pending_tabs) if pending_tabs is not None else None,
        "centre_id": centre_id,
        "session_id": session_id,
        "date": task_date,
        "mobile": data_row.get("mobile", ""),
    }
    try:
        with PENDING_SYNC_LOCK:
            with open(PENDING_SYNC_PATH, "a", encoding="utf-8") as f:
                f.write(json.dumps(entry, ensure_ascii=False, default=str) + "\n")
    except Exception as e:
        write_log(f"CRITICAL: failed to persist pending sync for {entry['mobile']}: {e}")


def _handle_sync_task(task):
    """Mirror a single attendance row; persist to the pending store on failure."""
    task_centre_id = task.get("centre_id") or DEFAULT_CENTRE_ID
    task_session_id = task.get("session_id") or ""
    task_mobile = str(task.get("mobile", "")).strip()
    task_date = task.get("date", datetime.now().strftime("%Y-%m-%d"))

    # Skip stale jobs if the attendance row has already been deleted.
    if task_mobile and task_session_id:
        still_exists = Attendance.query.filter(
            Attendance.mobile == task_mobile,
            Attendance.session_id == task_session_id,
            Attendance.date == task_date,
            Attendance.centre_id == task_centre_id
        ).first()
        if not still_exists:
            write_log(
                f"Async Excel Sync skipped stale task for {task_mobile} "
                f"in {task_session_id} on {task_date}"
            )
            return

    # A correction updates the cells of a row that is already on the sheet. It must NOT
    # fall through to the append path below, which would put the same person on the
    # sheet twice — visibly worse than the stale cell it set out to fix.
    if task.get("op") == "update_cells":
        try:
            n = update_attendance_cells_in_live_workbook(
                task.get("sheet_name"), task_mobile, task_date,
                name=task.get("name"), rounds=task.get("rounds"),
                remark=task.get("remark"))
            write_log(
                f"Async Excel correction: {task_mobile} in {task_session_id} "
                f"-> {n} row(s) updated in the live workbook")
        except Exception as e:
            # The database already holds the corrected value and the sync verifier
            # reports the sheet as drifted, so this is visible rather than silent.
            write_log(f"Async Excel correction FAILED for {task_mobile}: {e}")
        return

    data_row = _build_sync_data_row(task)
    sheet_name = task.get("sheet_name")

    need_excel, need_sheets, pending_tabs = _try_mirror_destinations(
        data_row, sheet_name, True, True, centre_id=task_centre_id)

    if need_excel or need_sheets:
        # Don't drop it — persist so the retry worker finishes it later.
        _persist_pending_sync(data_row, sheet_name, need_excel, need_sheets,
                              task_centre_id, task_session_id, task_date,
                              pending_tabs=pending_tabs)
        write_log(
            f"Async Sync deferred to pending store ({task_mobile}): "
            f"excel={'PENDING' if need_excel else 'ok'}, "
            f"sheets={'PENDING' if need_sheets else 'ok'}"
        )
    else:
        write_log(f"Async Sync ({task_mobile} in {sheet_name}) [SUCCESS]")


def excel_sync_worker():
    """
    Persistent background worker that handles all Excel + Google Sheets mirroring.
    A single wedged/failed row can never freeze the pipeline or be lost: the COM
    write is time-bounded, the destinations are decoupled, and any failure is
    persisted to the durable pending store for the retry worker to finish.
    """
    while True:
        try:
            task = excel_sync_queue.get()
        except Exception as e:
            print(f"[!] Critical Sync Worker Error (get): {e}")
            continue
        if task is None:
            break  # Shutdown signal
        try:
            with app.app_context():
                _handle_sync_task(task)
        except Exception as e:
            # Last resort: never lose the row — persist what we have.
            write_log(f"Async Sync unexpected error, persisting to pending store: {e}")
            try:
                _persist_pending_sync(
                    _build_sync_data_row(task), task.get("sheet_name"), True, True,
                    task.get("centre_id", ""), task.get("session_id", ""),
                    task.get("date", ""),
                )
            except Exception:
                pass
        finally:
            excel_sync_queue.task_done()


def _process_pending_sync_once():
    """Re-attempt every entry in the durable pending store; keep the unresolved."""
    tmp_path = PENDING_SYNC_PATH + ".processing"
    with PENDING_SYNC_LOCK:
        if not os.path.exists(PENDING_SYNC_PATH):
            return
        # Atomic move: the main worker keeps appending to a fresh file while we work.
        try:
            os.replace(PENDING_SYNC_PATH, tmp_path)
        except Exception as e:
            write_log(f"Pending sync: could not claim store: {e}")
            return

    entries = []
    try:
        with open(tmp_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    entries.append(json.loads(line))
                except Exception:
                    pass
    except Exception as e:
        write_log(f"Pending sync: could not read store: {e}")

    remaining, flushed = [], 0
    for e in entries:
        ne, ns, pt = _try_mirror_destinations(
            e.get("data_row", {}), e.get("sheet_name"),
            e.get("need_excel", False), e.get("need_sheets", False),
            # Entries persisted before per-centre routing existed have no
            # centre_id; treat them as the default centre they were written for.
            centre_id=e.get("centre_id") or DEFAULT_CENTRE_ID,
            # Absent on pre-existing entries -> None -> retry both tabs, which is
            # the old behaviour and the only safe assumption for those.
            pending_tabs=e.get("pending_tabs"),
        )
        if ne or ns:
            e["need_excel"], e["need_sheets"], e["pending_tabs"] = ne, ns, pt
            remaining.append(e)
        else:
            flushed += 1

    # Re-append still-unresolved entries to the live store, then drop the temp.
    if remaining:
        with PENDING_SYNC_LOCK:
            try:
                with open(PENDING_SYNC_PATH, "a", encoding="utf-8") as f:
                    for e in remaining:
                        f.write(json.dumps(e, ensure_ascii=False, default=str) + "\n")
            except Exception as ex:
                write_log(f"Pending sync: could not re-persist remaining: {ex}")
    try:
        os.remove(tmp_path)
    except Exception:
        pass

    if flushed or remaining:
        write_log(f"Pending sync pass: {flushed} flushed, {len(remaining)} still pending.")


def pending_sync_retry_worker():
    """Periodically flush the durable pending-sync store and reconcile Excel workbook."""
    while True:
        time.sleep(PENDING_SYNC_INTERVAL)
        try:
            with app.app_context():
                _process_pending_sync_once()
                reconcile_live_workbook_from_db()
        except Exception as e:
            print(f"[!] Pending sync retry worker error: {e}")



for d in [DB_DIR, SERVER_DIR, LOG_DIR, BACKUP_DIR, DATA_DIR, TEMP_DIR]:
    if not os.path.exists(d): os.makedirs(d, exist_ok=True)

DB_PATH = os.path.join(DB_DIR, 'attendance.db').replace('\\', '/')
LIVE_FILE = os.path.join(SERVER_DIR, 'live_attendance_master.xlsx')
LOG_PATH = os.path.join(LOG_DIR, 'server_logs.txt')
API_LOG_PATH = os.path.join(LOG_DIR, 'attendance-api.log')
_setup_file_logging(LOG_PATH)

# Prevent running under pythonw.exe under PM2 to avoid infinite loops of detached processes
if os.path.basename(sys.executable).lower() == "pythonw.exe":
    try:
        ts = datetime.now().strftime("[%Y-%m-%d %H:%M:%S]")
        with open(LOG_PATH, 'a', encoding='utf-8') as f:
            f.write(f"{ts} CRITICAL: app.py cannot be run with 'pythonw.exe' under PM2 as it causes infinite restart loops.\n")
            f.write(f"{ts} Please re-register this process using 'python' (python.exe) as the interpreter.\n")
            f.write(f"{ts} Process is entering safety-sleep to prevent system resource exhaustion.\n")
    except Exception:
        pass
    while True:
        time.sleep(86400)



def _current_date_str():
    from datetime import datetime as _dt
    return _dt.now().strftime('%Y-%m-%d')


def write_api_log(method, path, status, duration_ms, username='anon', extra=''):
    """Append one line to the daily attendance API log."""
    from datetime import datetime as _dt
    ts = _dt.now().strftime('%Y-%m-%d %H:%M:%S')
    level = 'ERROR' if status >= 500 else ('WARN ' if status >= 400 else 'INFO ')
    parts = [f"{method} {path}", f"status={status}", f"{duration_ms}ms", f"user={username}"]
    if extra:
        parts.append(extra)
    line = f"[{ts}] {level} | {' | '.join(parts)}\n"
    print(line, end='')
    try:
        date_str = _current_date_str()
        day_log = os.path.join(LOG_DIR, f"attendance-{date_str}.log")
        with open(day_log, 'a', encoding='utf-8') as _f:
            _f.write(line)
    except Exception:
        pass

if getattr(sys, 'frozen', False):
    template_dir = os.path.join(os.path.dirname(sys.executable), 'templates')
    static_dir = os.path.join(os.path.dirname(sys.executable), 'static')
    app = Flask(__name__, template_folder=template_dir, static_folder=static_dir)
else:
    app = Flask(__name__, template_folder='templates', static_folder='static')
Compress(app) # Enable gzip compression for all responses

# Start the Excel Sync Worker thread now that 'app' is defined
threading.Thread(target=excel_sync_worker, daemon=True).start()
# Start the durable pending-sync retry worker: flushes rows that couldn't be
# mirrored (e.g. Excel was open/locked) once the blocking condition clears.
threading.Thread(target=pending_sync_retry_worker, daemon=True).start()

def _cb_webhook_key(event):
    """Identity used for outbox dedup — one retry slot per distinct entity+event."""
    return f"{event.get('event_type', '')}:{event.get('entity_type', '')}:{event.get('entity_id', '')}"


def _persist_pending_cb_webhook(event):
    """
    Append one failed webhook event to the durable outbox, DEDUPED against
    every entry already sitting in the file: any existing entry with the same
    (event_type, entity_type, entity_id) key is dropped and replaced by this
    newer attempt. Without this, a centre (or any entity) that keeps failing
    would grow one queued entry per attempt — e.g. every 60s retry tick for
    hours — instead of the single up-to-date entry it actually needs.
    """
    key = _cb_webhook_key(event)
    try:
        with CB_WEBHOOK_OUTBOX_LOCK:
            existing = []
            if os.path.exists(CB_WEBHOOK_OUTBOX_PATH):
                with open(CB_WEBHOOK_OUTBOX_PATH, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if not line:
                            continue
                        try:
                            entry = json.loads(line)
                        except Exception:
                            continue
                        if _cb_webhook_key(entry) != key:
                            existing.append(entry)
            existing.append(event)
            with open(CB_WEBHOOK_OUTBOX_PATH, "w", encoding="utf-8") as f:
                for entry in existing:
                    f.write(json.dumps(entry, ensure_ascii=False, default=str) + "\n")
    except Exception as e:
        write_log(f"CRITICAL: failed to persist pending CB webhook ({key}): {e}")


def _post_cb_webhook(event, timeout):
    import urllib.request, json as _json
    body = _json.dumps(event).encode()
    req = urllib.request.Request(
        CALLING_BACKEND_WEBHOOK_URL,
        data=body,
        headers={
            'Content-Type': 'application/json',
            'x-internal-token': CALLING_BACKEND_INTERNAL_TOKEN,
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
        },
        method='POST',
    )
    urllib.request.urlopen(req, timeout=timeout)


def _calling_backend_webhook_worker():
    """Drain the webhook queue and POST events to the Calling Backend.
    Fire-and-forget — on failure the event is persisted to the durable,
    deduped outbox instead of being silently dropped."""
    while True:
        event = _calling_backend_webhook_queue.get()
        try:
            _post_cb_webhook(event, timeout=5.0)
            journal_write("AE", "AE->CB", "webhook", event.get("event_type"), "success",
                          entity=event.get("entity_id"), payload=event, source="_calling_backend_webhook_worker")
        except Exception as _e:
            write_log(f"Calling backend webhook failed, deferred to outbox: {_e}")
            journal_write("AE", "AE->CB", "webhook", event.get("event_type"), "failed",
                          entity=event.get("entity_id"), detail="deferred to durable retry outbox",
                          payload=event, error=_e, source="_calling_backend_webhook_worker")
            _persist_pending_cb_webhook(event)
        finally:
            _calling_backend_webhook_queue.task_done()

threading.Thread(target=_calling_backend_webhook_worker, daemon=True).start()


def _process_cb_webhook_outbox_once():
    """Re-attempt every deduped entry in the outbox; keep only what still fails."""
    tmp_path = CB_WEBHOOK_OUTBOX_PATH + ".processing"
    with CB_WEBHOOK_OUTBOX_LOCK:
        if not os.path.exists(CB_WEBHOOK_OUTBOX_PATH):
            return
        try:
            os.replace(CB_WEBHOOK_OUTBOX_PATH, tmp_path)
        except Exception as e:
            write_log(f"CB webhook outbox: could not claim store: {e}")
            return

    entries = []
    try:
        with open(tmp_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    entries.append(json.loads(line))
                except Exception:
                    pass
    except Exception as e:
        write_log(f"CB webhook outbox: could not read store: {e}")

    remaining, flushed = [], 0
    for event in entries:
        try:
            _post_cb_webhook(event, timeout=3)
            flushed += 1
            journal_write("AE", "AE->CB", "webhook", event.get("event_type"), "success",
                          entity=event.get("entity_id"), detail="delivered on retry",
                          payload=event, source="_process_cb_webhook_outbox_once")
        except Exception as _e:
            remaining.append(event)
            journal_write("AE", "AE->CB", "webhook", event.get("event_type"), "failed",
                          entity=event.get("entity_id"), detail="still failing on retry — remains in durable outbox",
                          payload=event, error=_e, source="_process_cb_webhook_outbox_once")

    # Re-persist only the still-failing entries — goes through the same
    # dedup path so a fresh live failure for the same entity (queued by the
    # main worker while this pass was running) doesn't end up duplicated.
    if remaining:
        with CB_WEBHOOK_OUTBOX_LOCK:
            try:
                with open(CB_WEBHOOK_OUTBOX_PATH, "a", encoding="utf-8") as f:
                    for event in remaining:
                        f.write(json.dumps(event, ensure_ascii=False, default=str) + "\n")
            except Exception as ex:
                write_log(f"CB webhook outbox: could not re-persist remaining: {ex}")
    try:
        os.remove(tmp_path)
    except Exception:
        pass

    if flushed or remaining:
        write_log(f"CB webhook outbox pass: {flushed} flushed, {len(remaining)} still pending.")


def cb_webhook_retry_worker():
    """Periodically flush the durable CB webhook outbox until it's empty."""
    while True:
        time.sleep(CB_WEBHOOK_RETRY_INTERVAL)
        try:
            _process_cb_webhook_outbox_once()
        except Exception as e:
            print(f"[!] CB webhook retry worker error: {e}")

threading.Thread(target=cb_webhook_retry_worker, daemon=True).start()

def _fire_webhook(event_type, entity_type, entity_id, payload):
    """Enqueue a webhook event to the Calling Backend. Never blocks, never raises."""
    from datetime import datetime as _dt
    _calling_backend_webhook_queue.put({
        'event_type': event_type,
        'entity_type': entity_type,
        'entity_id': str(entity_id),
        'payload': payload,
        'fired_at': _dt.utcnow().isoformat() + 'Z',
    })
# In a real app, use a secure random secret key.
app.secret_key = os.environ.get('SECRET_KEY')
if not app.secret_key:
    # No SECRET_KEY configured: use a random one. Logins last until the server restarts.
    import secrets as _secrets
    app.secret_key = _secrets.token_hex(32)
    print(' WARNING: SECRET_KEY is not set - set it in .env so sessions survive restarts.')

# --- Session persistence -----------------------------------------------------
# Flask's default session cookie carries NO Expires/Max-Age, which makes it a
# browser-session cookie: it is discarded the moment the browser — or an installed
# PWA — is closed. On a phone that is constantly, and it caused two symptoms that
# looked unrelated:
#   1. "it does not stay logged in on iPhone/Mac"
#   2. "it opens on the Centre B page" — because get_selected_centre_id() reads
#      session["selected_centre_id"], and with the cookie gone it falls through to
#      DEFAULT_CENTRE_ID = "centre_b_Base". The user was not being moved to
#      another centre; they were being logged out and handed the default one.
# Making the session permanent fixes both at the source.
#
# SESSION_COOKIE_SECURE is deliberately NOT set. The Android Capacitor wrapper runs
# cleartext over LAN HTTP (androidScheme 'http', cleartext: true), and a Secure
# cookie would simply never be sent by those devices — they would stop being able to
# authenticate at all, silently. This is a real trade-off, not an oversight.
from datetime import timedelta as _timedelta  # noqa: E402
app.permanent_session_lifetime = _timedelta(days=60)
app.config.update(
    SESSION_COOKIE_HTTPONLY=True,
    SESSION_COOKIE_SAMESITE='Lax',
    # Re-send the cookie on every response so the 60 days is a rolling window from
    # last use, not a hard expiry 60 days after login.
    SESSION_REFRESH_EACH_REQUEST=True,
)

TUNNEL_URL = None
PUBLIC_BASE_URL = os.environ.get('PUBLIC_BASE_URL', 'https://attendance.example.com')

# Calling Backend integration — base URL, webhook URL, and shared secret
CALLING_BACKEND_BASE_URL = os.environ.get('CALLING_BACKEND_BASE_URL', 'http://127.0.0.1:3000')
CALLING_BACKEND_WEBHOOK_URL = os.environ.get('CALLING_BACKEND_WEBHOOK_URL', 'http://127.0.0.1:3000/internal/attendease-event')
CALLING_BACKEND_INTERNAL_TOKEN = os.environ.get('INTERNAL_TOKEN', '')
APP_ENV = os.environ.get('APP_ENV', 'development')
PORT = int(os.environ.get('PORT', '6061'))


def bind_exclusive_server_socket(host, port):
    """Bind the listening socket with SO_EXCLUSIVEADDRUSE so a second instance
    can never silently share the port.

    Windows normally lets multiple processes LISTEN on the same port at once
    (unlike Linux's exclusive bind) — that's what let 3 copies of this server
    run simultaneously on 2026-07-27 after PM2 restarts left old instances
    alive, each with its own Excel-sync worker independently writing the same
    live_attendance_master.xlsx with no cross-process lock. Rows silently
    overwrote each other (each process logged its own [SUCCESS] truthfully; a
    later save from a different process just clobbered it) — 15 of 17 rows
    lost in one session, all 13 in another.
    SO_EXCLUSIVEADDRUSE makes a second instance's bind() fail immediately
    instead of quietly co-existing: it crashes fast at startup, PM2's normal
    crash-retry handles it, and by the next retry the stale predecessor is
    gone. This does not kill any other process — unlike the 2026-07-04 fix
    attempt (which actively killed whatever held the port and caused a
    self-sustaining restart loop by fighting PM2's own tracked process), a
    losing bind() only ever affects the process making the call.
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
    sock.bind((host, port))
    sock.listen(1024)
    return sock


def warn_if_port_already_held(port):
    """Log (never kill) any other process already bound to `port`.

    Windows lets multiple processes LISTEN on the same port without erroring
    (unlike Linux's exclusive bind), so a stale predecessor can silently keep
    answering requests alongside a fresh instance. Detection is safe; auto-killing
    is not — PM2 on Windows doesn't reliably finish tearing down the previous
    instance before spawning a new one, so a kill here can hit the process PM2 is
    still tracking as "the app", which PM2 then treats as a crash and immediately
    relaunches, whose own kill repeats the same thing against its predecessor —
    a self-sustaining restart loop (this happened in production on 2026-07-04).
    If this logs a stale PID, a human should check and kill it manually.
    Retained alongside bind_exclusive_server_socket() purely as a diagnostic —
    the exclusive bind is what actually prevents the double-listen now.
    """
    my_pid = os.getpid()
    try:
        result = subprocess.run(["netstat", "-ano"], capture_output=True, text=True, timeout=10)
    except Exception as e:
        write_log(f"warn_if_port_already_held: netstat failed: {e}")
        return
    stale_pids = set()
    for line in result.stdout.splitlines():
        parts = line.split()
        if len(parts) < 5 or parts[0] != "TCP" or parts[3] != "LISTENING":
            continue
        if not parts[1].endswith(f":{port}"):
            continue
        try:
            pid = int(parts[-1])
        except ValueError:
            continue
        if pid != my_pid:
            stale_pids.add(pid)
    for pid in stale_pids:
        write_log(f"warn_if_port_already_held: PID {pid} is ALSO bound to port {port} — manual review needed, not auto-killed")


TUNNEL_PROCESS = None
TUNNEL_PROCESS_LOCK = threading.Lock()
TUNNEL_LAST_START_AT = 0.0
TUNNEL_MONITOR_STARTED = False
REPLICA_BACKEND_PM2_NAME = "replica-excel-management"
REPLICA_TUNNEL_PM2_NAME = "replica-excel-management-tunnel"
REPLICA_TUNNEL_LAUNCHER = os.path.join(basedir, ".pm2", "run-replica-tunnel.js")
PM2_CMD = os.environ.get("PM2_CMD", "pm2")
TUNNEL_CONFIG_CANDIDATES = (
    os.path.join(basedir, "config-onlineattendanceapp.yml"),
    os.path.join(basedir, "config.yml"),
)
TUNNEL_STDOUT_LOG = os.path.join(basedir, "cloudflared-onlineattendanceapp.out.log")
TUNNEL_STDERR_LOG = os.path.join(basedir, "cloudflared-onlineattendanceapp.err.log")

DEFAULT_CENTRE_ID = "centre_b_Base"
DEFAULT_CENTRE_NAME = "Centre B"

def sanitize_storage_name(value):
    """
    Return a filesystem-safe storage component.
    The helper is intentionally pure and does not touch the filesystem.
    """
    text = str(value or "").strip()
    if not text:
        return "unnamed"

    # Replace Windows-illegal characters and normalize whitespace.
    text = re.sub(r'[<>:"/\\|?*]+', "_", text)
    text = re.sub(r"\s+", "_", text)
    text = text.strip(" ._")
    return text or "unnamed"


def safe_timestamp():
    """Return a filesystem-safe timestamp string."""
    return datetime.now().strftime("%Y%m%d_%H%M%S")


def _pm2_start_or_restart(process_name, start_args):
    """
    Best-effort PM2 repair helper.
    If the named process exists, restart it; otherwise start it.
    """
    try:
        restart_result = subprocess.run(
            ["cmd.exe", "/c", PM2_CMD, "restart", process_name],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            **_hidden_process_kwargs(),
            timeout=20
        )
        if restart_result.returncode == 0:
            return True
    except Exception as e:
        write_log(f"PM2 restart attempt failed for {process_name}: {e}")

    try:
        start_result = subprocess.run(
            ["cmd.exe", "/c", PM2_CMD, *start_args[1:]],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            cwd=basedir,
            **_hidden_process_kwargs(),
            timeout=30
        )
        if start_result.returncode == 0:
            return True
        write_log(
            f"PM2 start failed for {process_name}: "
            f"{(start_result.stderr or start_result.stdout or '').strip()}"
        )
    except Exception as e:
        write_log(f"PM2 start exception for {process_name}: {e}")
    return False


def _public_health_is_ok():
    health_url = PUBLIC_BASE_URL.rstrip("/") + "/health"
    try:
        req = urllib.request.Request(health_url, method="GET")
        with urllib.request.urlopen(req, timeout=5) as resp:
            return 200 <= getattr(resp, "status", 0) < 300
    except Exception:
        return False


def _local_health_is_ok():
    health_url = f"http://127.0.0.1:{PORT}/health"
    try:
        req = urllib.request.Request(health_url, method="GET")
        with urllib.request.urlopen(req, timeout=5) as resp:
            return 200 <= getattr(resp, "status", 0) < 300
    except Exception:
        return False


def _get_tunnel_config_path():
    for config_path in TUNNEL_CONFIG_CANDIDATES:
        if os.path.exists(config_path):
            return config_path
    return None


def _get_cloudflared_path():
    import platform

    is_windows = platform.system() == "Windows"
    ext = ".exe" if is_windows else ""
    cloudflared_path = os.path.join(basedir, f"cloudflared{ext}")

    if os.path.exists(cloudflared_path):
        return cloudflared_path

    print(f"\n[i] Detecting system: {platform.system()} {platform.machine()}")
    print(f"[i] Downloading cloudflared for secure tunnel...")
    try:
        import urllib.request

        machine = platform.machine().lower()
        if is_windows:
            url = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"
        elif "arm" in machine or "aarch64" in machine:
            url = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64"
        else:
            url = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64"

        urllib.request.urlretrieve(url, cloudflared_path)
        if not is_windows:
            os.chmod(cloudflared_path, 0o755)
        print("[+] Download complete!\n")
        return cloudflared_path
    except Exception as e:
        print(f"[!] Failed to download cloudflared: {e}")
        return None


def _tunnel_process_wmi_filter(our_pid):
    """Return a PowerShell Where-Object filter that matches cloudflared processes for our tunnel."""
    # Match by primary config filename OR by basedir path fragment (*replica*excel*managment*).
    # The basedir clause catches the fallback config.yml and any other config file in the app dir.
    return (
        f"$_.Name -ieq 'cloudflared.exe' "
        f"-and ($_.CommandLine -like '*config-onlineattendanceapp*' "
        f"-or $_.CommandLine -like '*replica*excel*managment*') "
        f"-and $_.ParentProcessId -ne {our_pid}"
    )


def _count_external_tunnel_processes():
    """Returns the count of cloudflared processes for our tunnel config not owned by this Flask process."""
    try:
        our_pid = os.getpid()
        wmi_filter = _tunnel_process_wmi_filter(our_pid)
        result = subprocess.run(
            [
                "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                f"(Get-CimInstance Win32_Process | Where-Object {{ {wmi_filter} }} | Measure-Object).Count",
            ],
            capture_output=True, text=True, check=False, **_hidden_process_kwargs(),
        )
        return int(result.stdout.strip()) if result.stdout.strip().isdigit() else 0
    except Exception:
        return 0


def _cleanup_stale_tunnel_processes():
    """
    Remove duplicate cloudflared instances only when multiple are running for our tunnel.
    If exactly one external cloudflared is already running (e.g. via PM2), leave it alone.
    """
    try:
        count = _count_external_tunnel_processes()
        if count <= 1:
            return  # Zero or one external process — nothing to clean up
        our_pid = os.getpid()
        wmi_filter = _tunnel_process_wmi_filter(our_pid)
        powershell = [
            "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
            f"Get-CimInstance Win32_Process | Where-Object {{ {wmi_filter} }} | Sort-Object CreationDate | Select-Object -Skip 1 | ForEach-Object {{ try {{ Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop }} catch {{}} }}",
        ]
        subprocess.run(powershell, capture_output=True, text=True, check=False, **_hidden_process_kwargs())
    except Exception as exc:
        write_log(f"Tunnel cleanup skipped: {exc}")


def _spawn_tunnel_process():
    global TUNNEL_PROCESS, TUNNEL_URL, TUNNEL_LAST_START_AT

    config_path = _get_tunnel_config_path()
    if not config_path:
        write_log("Tunnel start failed: config-onlineattendanceapp.yml/config.yml not found.")
        return False

    cloudflared_path = _get_cloudflared_path()
    if not cloudflared_path:
        return False

    # If an external cloudflared (e.g. from PM2) is already running for our tunnel, don't spawn a duplicate
    if _count_external_tunnel_processes() > 0:
        return True

    _cleanup_stale_tunnel_processes()

    with TUNNEL_PROCESS_LOCK:
        if TUNNEL_PROCESS and TUNNEL_PROCESS.poll() is None:
            return True

        now_ts = time.time()
        if TUNNEL_LAST_START_AT and (now_ts - TUNNEL_LAST_START_AT) < 15:
            return False
        TUNNEL_LAST_START_AT = now_ts

        TUNNEL_URL = PUBLIC_BASE_URL.rstrip("/")

        try:
            stdout_log = open(TUNNEL_STDOUT_LOG, "a", encoding="utf-8")
        except Exception:
            stdout_log = subprocess.DEVNULL
        try:
            stderr_log = open(TUNNEL_STDERR_LOG, "a", encoding="utf-8")
        except Exception:
            stderr_log = subprocess.DEVNULL

        try:
            TUNNEL_PROCESS = subprocess.Popen(
                [
                    cloudflared_path,
                    "tunnel",
                    "--config",
                    config_path,
                    "run",
                    "online-attendance-app",
                ],
                stdout=stdout_log,
                stderr=stderr_log,
                cwd=basedir,
                **_hidden_process_kwargs(),
            )
            return True
        except Exception as e:
            write_log(f"Critical error starting tunnel process: {e}")
            TUNNEL_PROCESS = None
            return False
        finally:
            try:
                if stdout_log not in (None, subprocess.DEVNULL):
                    stdout_log.close()
            except Exception:
                pass
            try:
                if stderr_log not in (None, subprocess.DEVNULL):
                    stderr_log.close()
            except Exception:
                pass


def ensure_replica_tunnel_process():
    """
    If the local backend is healthy and the tunnel process is not running,
    start or repair the cloudflared process directly.
    """
    _cleanup_stale_tunnel_processes()

    if not _local_health_is_ok():
        return False

    with TUNNEL_PROCESS_LOCK:
        if TUNNEL_PROCESS and TUNNEL_PROCESS.poll() is None:
            return True

    return _spawn_tunnel_process()


def replica_supervisor_worker():
    """
    Lightweight best-effort watchdog for the replica tunnel.
    It only starts the tunnel if the backend is healthy and the public
    health endpoint is not answering.
    """
    while True:
        try:
            ensure_replica_tunnel_process()
        except Exception as e:
            write_log(f"Replica supervisor error: {e}")
        time.sleep(60)


# normalize_import_mobile moved to roster_inject.py (imported at the top of this file)
# so the folder importer, the inject page and its unit tests all share ONE
# implementation of the rule. Callers here are unchanged.


def get_legacy_global_workbook_path():
    """Compatibility helper for the existing global workbook path."""
    return LIVE_FILE


def get_storage_root():
    """Root folder for future structured storage."""
    return SERVER_DIR


def get_master_storage_dir(create=False):
    path = os.path.join(get_storage_root(), "master")
    if create and not os.path.exists(path):
        os.makedirs(path, exist_ok=True)
    return path


def get_student_master_path(create_parent=False):
    if create_parent:
        get_master_storage_dir(create=True)
    return os.path.join(get_master_storage_dir(create=False), "student_master.xlsx")


def get_centres_storage_root(create=False):
    path = os.path.join(get_storage_root(), "centres")
    if create and not os.path.exists(path):
        os.makedirs(path, exist_ok=True)
    return path


def get_centre_storage_dir(centre_id, create=False):
    centre_component = sanitize_storage_name(centre_id)
    path = os.path.join(get_centres_storage_root(create=create), centre_component)
    if create and not os.path.exists(path):
        os.makedirs(path, exist_ok=True)
    return path


def get_centre_master_path(centre_id, create_parent=False):
    if create_parent:
        get_centre_storage_dir(centre_id, create=True)
    return os.path.join(get_centre_storage_dir(centre_id, create=False), "centre_master.xlsx")


def category_folder_component(category):
    """Human-readable folder name for a category track ('Youth' / 'Congregation').

    The export mirror under Server file/centres/<centre>/ is split by track so a
    human browsing the files can tell youth and congregation data apart. Anything
    unrecognized (or missing) defaults to Youth, matching the system-wide rule
    that legacy/unmapped rows are youth.
    """
    cat = str(category or "youth").strip().lower()
    return "Congregation" if cat == "congregation" else "Youth"


def get_batches_storage_root(centre_id, category="youth", create=False):
    # Category partition sits between the centre folder and its batches:
    #   centres/<centre>/<Youth|Congregation>/batches/<batch>/...
    category_component = category_folder_component(category)
    category_root = os.path.join(
        get_centre_storage_dir(centre_id, create=create), category_component
    )
    if create and not os.path.exists(category_root):
        os.makedirs(category_root, exist_ok=True)
    path = os.path.join(category_root, "batches")
    if create and not os.path.exists(path):
        os.makedirs(path, exist_ok=True)
    return path


def get_batch_storage_dir(centre_id, batch_id, batch_name=None, category=None, create=False):
    batch_id_text = str(batch_id or "").strip() or "unnamed"

    # Resolve batch name + category together from the DB when not supplied, so
    # every batch (and its sessions/files below) lands under the right
    # youth/congregation partition without callers having to thread category
    # through every path helper.
    resolved_batch_name = batch_name
    resolved_category = category
    if not resolved_batch_name or resolved_category is None:
        try:
            resolved = resolve_storage_context_for_batch(batch_id_text)
            if resolved:
                if not resolved_batch_name:
                    resolved_batch_name = resolved.get("batch_name")
                if resolved_category is None:
                    resolved_category = resolved.get("category")
        except Exception:
            pass
    resolved_category = resolved_category or "youth"

    batches_root = get_batches_storage_root(centre_id, category=resolved_category, create=create)

    readable_folder_name = None
    if resolved_batch_name:
        readable_folder_name = make_readable_batch_folder_name(batch_id_text, resolved_batch_name)

    readable_path = (
        os.path.join(batches_root, readable_folder_name)
        if readable_folder_name
        else None
    )
    legacy_numeric_path = os.path.join(batches_root, sanitize_storage_name(batch_id_text))

    if create:
        target = readable_path or legacy_numeric_path
        os.makedirs(target, exist_ok=True)
        return target

    if readable_path and os.path.isdir(readable_path):
        return readable_path

    existing_folder = find_existing_batch_folder(centre_id, batch_id_text, category=resolved_category)
    if existing_folder:
        return existing_folder

    if os.path.isdir(legacy_numeric_path):
        return legacy_numeric_path

    return readable_path or legacy_numeric_path


def get_batch_master_path(centre_id, batch_id, batch_name=None, create_parent=False):
    if create_parent:
        get_batch_storage_dir(centre_id, batch_id, batch_name=batch_name, create=True)
    return os.path.join(
        get_batch_storage_dir(centre_id, batch_id, batch_name=batch_name, create=False),
        "batch_master.xlsx"
    )


def get_session_storage_dir(centre_id, batch_id, batch_name=None, create=False):
    path = os.path.join(
        get_batch_storage_dir(centre_id, batch_id, batch_name=batch_name, create=create),
        "sessions"
    )
    if create and not os.path.exists(path):
        os.makedirs(path, exist_ok=True)
    return path


def make_readable_session_folder_name(session_id, session_name=None):
    session_id_text = sanitize_storage_name(session_id)
    readable_name = sanitize_storage_name(session_name if session_name else session_id)
    if readable_name == "unnamed":
        readable_name = "unnamed_session"
    return f"{session_id_text}__{readable_name}"


def find_existing_session_folder(centre_id, batch_id, session_id, batch_name=None):
    """Find a session folder by session_id, supporting readable and legacy names."""
    sessions_root = get_session_storage_dir(centre_id, batch_id, batch_name=batch_name, create=False)
    if not os.path.exists(sessions_root):
        return None

    session_id_text = sanitize_storage_name(session_id)
    readable_prefix = f"{session_id_text}__"
    legacy_folder = os.path.join(sessions_root, session_id_text)
    readable_name = make_readable_session_folder_name(session_id_text, session_id_text)
    readable_folder = os.path.join(sessions_root, readable_name)

    try:
        for name in sorted(os.listdir(sessions_root)):
            full_path = os.path.join(sessions_root, name)
            if os.path.isdir(full_path) and name.startswith(readable_prefix):
                return full_path
    except Exception:
        pass

    if os.path.isdir(readable_folder):
        return readable_folder

    if os.path.isdir(legacy_folder):
        return legacy_folder

    return None


def get_session_storage_path(centre_id, batch_id, session_id, session_name=None, create_parent=False):
    session_component = make_readable_session_folder_name(session_id, session_name)
    if create_parent:
        get_session_folder_path(centre_id, batch_id, session_id, session_name=session_name, create=True)
    return os.path.join(get_session_folder_path(centre_id, batch_id, session_id, session_name=session_name, create=False), f"{session_component}.xlsx")


def get_session_attendance_path(centre_id, batch_id, session_id, session_name=None, batch_name=None, create_parent=False):
    if create_parent:
        get_session_folder_path(
            centre_id,
            batch_id,
            session_id,
            session_name=session_name,
            batch_name=batch_name,
            create=True
        )
    return os.path.join(
        get_session_folder_path(
            centre_id,
            batch_id,
            session_id,
            session_name=session_name,
            batch_name=batch_name,
            create=False
        ),
        "session_attendance.xlsx"
    )


def get_session_folder_path(centre_id, batch_id, session_id, session_name=None, batch_name=None, create=False):
    sessions_root = get_session_storage_dir(
        centre_id,
        batch_id,
        batch_name=batch_name,
        create=create
    )
    folder_name = make_readable_session_folder_name(session_id, session_name)
    folder_path = os.path.join(sessions_root, folder_name)
    if create:
        os.makedirs(folder_path, exist_ok=True)
    return folder_path


def get_session_info_path(centre_id, batch_id, session_id, session_name=None, create_parent=False, batch_name=None):
    if create_parent:
        get_session_folder_path(
            centre_id,
            batch_id,
            session_id,
            session_name=session_name,
            batch_name=batch_name,
            create=True
        )
    return os.path.join(
        get_session_folder_path(
            centre_id,
            batch_id,
            session_id,
            session_name=session_name,
            batch_name=batch_name,
            create=False
        ),
        "session_info.json"
    )


def write_session_info_file(
    centre_id,
    batch_id,
    session_id,
    session_name=None,
    batch_name=None,
    status="active",
    deleted_at=None,
    create_parent=False
):
    """Write session metadata for human inspection without creating workbook files."""
    try:
        batch_id_int = int(batch_id) if str(batch_id).strip().isdigit() else batch_id
    except Exception:
        batch_id_int = batch_id

    resolved_session_name = session_name
    resolved_batch_name = batch_name

    if not resolved_batch_name or not resolved_session_name:
        try:
            mapping = SessionMapping.query.filter_by(session_id=str(session_id).strip()).first()
            if mapping and not resolved_batch_name:
                batch = Batch.query.filter_by(id=mapping.batch_id).first()
                resolved_batch_name = batch.name if batch else resolved_batch_name
                centre_id = mapping.centre_id or centre_id
                batch_id_int = mapping.batch_id
            if mapping and not resolved_session_name:
                resolved_session_name = mapping.session_id
        except Exception:
            pass

    if not resolved_session_name:
        resolved_session_name = str(session_id).strip() or "unnamed_session"

    session_folder = get_session_folder_path(
        centre_id,
        batch_id_int,
        session_id,
        session_name=resolved_session_name,
        batch_name=resolved_batch_name,
        create=create_parent
    )
    if not os.path.exists(session_folder):
        if status == "deleted":
            write_log(f"Session info write skipped; folder missing for deleted session {session_id}")
        return None

    payload = {
        "centre_id": centre_id,
        "batch_id": batch_id_int,
        "batch_name": resolved_batch_name or "Unnamed Batch",
        "session_id": session_id,
        "session_name": resolved_session_name or str(session_id),
        "folder_name": os.path.basename(session_folder),
        "status": status,
        "updated_at": safe_timestamp(),
    }
    if deleted_at:
        payload["deleted_at"] = deleted_at

    info_path = os.path.join(session_folder, "session_info.json")
    try:
        with open(info_path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
        return info_path
    except Exception as e:
        write_log(f"Session info write failed for {centre_id}/{batch_id}/{session_id}: {e}")
        return None


def get_data_import_root():
    """Global Data import root. Kept as a top-level folder, not centre-wise."""
    return DATA_DIR


def get_data_incoming_dir(create=False):
    path = os.path.join(get_data_import_root(), "incoming")
    if create and not os.path.exists(path):
        os.makedirs(path, exist_ok=True)
    return path


def get_data_processed_dir(create=False):
    path = os.path.join(get_data_import_root(), "processed")
    if create and not os.path.exists(path):
        os.makedirs(path, exist_ok=True)
    return path


def get_data_failed_dir(create=False):
    path = os.path.join(get_data_import_root(), "failed")
    if create and not os.path.exists(path):
        os.makedirs(path, exist_ok=True)
    return path


IMPORT_TEMPLATE_FILENAME = "_IMPORT TEMPLATE.xlsx"
IMPORT_COLUMNS = ["Mobile", "Name", "Address", "Occupation", "Rounds", "Remark"]


def _write_import_template(folder_path, scope_label):
    """
    Drop a pre-filled header template into folder_path so the user knows
    exactly which columns to fill.  Never overwrites an existing template.
    """
    dest = os.path.join(folder_path, IMPORT_TEMPLATE_FILENAME)
    if os.path.exists(dest):
        return
    try:
        wb = Workbook()
        ws = wb.active
        ws.title = "Import Data"

        from openpyxl.styles import Font, PatternFill, Alignment
        header_font  = Font(bold=True, color="FFFFFF")
        header_fill  = PatternFill("solid", fgColor="2E75B6")
        header_align = Alignment(horizontal="center", vertical="center")

        for col_idx, col_name in enumerate(IMPORT_COLUMNS, start=1):
            cell = ws.cell(row=1, column=col_idx, value=col_name)
            cell.font      = header_font
            cell.fill      = header_fill
            cell.alignment = header_align
            ws.column_dimensions[cell.column_letter].width = 18

        ws.row_dimensions[1].height = 20
        ws.freeze_panes = "A2"
        save_workbook_safely(wb, dest, reason="import_template")
    except Exception as e:
        write_log(f"Template creation failed for {scope_label}: {e}")


def get_data_import_centre_dir(centre_id, create=False):
    """Return (and optionally create) Data/<centre_id>/ for drop-in imports."""
    path = os.path.join(get_data_import_root(), centre_id)
    if create:
        os.makedirs(path, exist_ok=True)
        _write_import_template(path, centre_id)
    return path


def get_data_import_batch_dir(centre_id, batch_name, create=False):
    """
    Return (and optionally create) Data/<centre_id>/<safe_batch_name>/ for
    batch-scoped imports. The batch name is sanitized the same way the Excel-
    output batch folder already is (make_readable_batch_folder_name) — a raw
    name containing a Windows-illegal character (e.g. a colon in "BG 7:30 pm")
    otherwise makes os.makedirs raise, silently dropping this folder while the
    Excel-output side (which already sanitizes) succeeds.
    """
    safe_batch_name = sanitize_storage_name(batch_name)
    path = os.path.join(get_data_import_root(), centre_id, safe_batch_name)
    if create:
        os.makedirs(path, exist_ok=True)
        _write_import_template(path, f"{centre_id}/{batch_name}")
    return path


def resolve_storage_context_for_batch(batch_id):
    """Read-only batch resolver for future storage paths."""
    try:
        batch_id_int = int(batch_id)
    except (TypeError, ValueError):
        return {
            "centre_id": None,
            "batch_id": None,
            "batch_name": None,
        }

    batch = Batch.query.filter_by(id=batch_id_int).first()
    if not batch:
        return {
            "centre_id": None,
            "batch_id": None,
            "batch_name": None,
            "category": "youth",
        }

    return {
        "centre_id": batch.centre_id,
        "batch_id": batch.id,
        "batch_name": batch.name,
        "category": (getattr(batch, "category", None) or "youth"),
    }


def sanitize_sheet_name(name):
    """
    Excel sheet names can't contain / \\ : ? * and are capped at 31 chars.
    Every place that derives a sheet name from a session_id must apply this
    identically — sync_offline_queue() used to compute its own unsanitized
    `session_id[:31]` instead of calling this, so a session_id containing any
    of those characters (e.g. a date-based id like "04/07/26") produced a
    DIFFERENT sheet name than the one the live mark_attendance() path (and
    the session-creation code that set session['sheet_name']) actually used
    — offline-synced records silently landed in a sheet the user never saw.
    """
    return str(name or "")[:31].replace('/', '-').replace('\\', '-').replace(':', '-').replace('?', '').replace('*', '')


def resolve_storage_context_for_session(session_id):
    """Read-only session resolver for future storage paths."""
    session_key = str(session_id or "").strip()
    if not session_key:
        return {
            "centre_id": None,
            "batch_id": None,
            "batch_name": None,
            "session_id": None,
        }

    mapping = SessionMapping.query.filter_by(session_id=session_key).first()
    if not mapping:
        return {
            "centre_id": None,
            "batch_id": None,
            "batch_name": None,
            "session_id": session_key,
        }

    batch = Batch.query.filter_by(id=mapping.batch_id).first()
    return {
        "centre_id": mapping.centre_id,
        "batch_id": mapping.batch_id,
        "batch_name": batch.name if batch else None,
        "session_id": mapping.session_id,
    }


def make_roster_scope_key(batch_id=None):
    if batch_id is None:
        return "centre"
    batch_text = str(batch_id).strip()
    if not batch_text:
        return "centre"
    return f"batch:{batch_text}"


def _normalize_profile_text(value):
    if value is None:
        return ""
    return str(value).strip()


def merge_user_profile_from_payload(user, payload, preserve_existing=True, log_prefix="Profile"):
    """
    Fill blank user fields from payload data while logging conflicts.
    Returns a list of human-readable conflict strings.
    """
    conflicts = []
    payload = payload or {}

    def fill(field_name, incoming_value):
        nonlocal conflicts
        incoming = _normalize_profile_text(incoming_value)
        current = _normalize_profile_text(getattr(user, field_name, ""))
        if not incoming:
            return
        if not current:
            setattr(user, field_name, incoming)
        elif current != incoming:
            conflicts.append(f"{field_name}: existing='{current}' incoming='{incoming}'")
            if not preserve_existing:
                setattr(user, field_name, incoming)

    fill("name", payload.get("name"))
    fill("address", payload.get("address"))
    fill("occupation", payload.get("occupation"))
    fill("rounds", payload.get("rounds"))
    fill("remark", payload.get("remark"))
    fill("cheating", payload.get("cheating"))
    if not _normalize_profile_text(getattr(user, "name", "")):
        user.name = "Unknown"
    if not _normalize_profile_text(getattr(user, "cheating", "")):
        user.cheating = "No"

    if conflicts:
        write_log(
            f"{log_prefix} conflict for {user.mobile}: " + "; ".join(conflicts)
        )
    return conflicts


def get_best_profile_for_mobile(mobile, centre_id=None, batch_id=None):
    """
    Build the best available profile for autofill.
    Prefer global User data, then fill blanks from a matching centre roster row.
    """
    mobile = _normalize_profile_text(mobile)
    profile = {
        "mobile": mobile,
        "name": "",
        "address": "",
        "occupation": "",
        "rounds": "",
        "remark": "",
        "cheating": "No",
    }

    user = User.query.filter_by(mobile=mobile).first()
    if user:
        profile.update(user.to_dict())

    # Centre-scoped only — no cross-centre fallback. Autofill must never pull
    # another centre's roster row into this centre's form; the separate
    # /api/registration_status warning is the only cross-centre signal a
    # registerer should see (matches SRKB's default-isolated model, where
    # cross-centre reads require an explicit bypassCentreScope()).
    roster_query = CentreRoster.query.filter_by(mobile=mobile)
    if centre_id:
        roster_query = roster_query.filter_by(centre_id=centre_id)
    if batch_id is not None and str(batch_id).strip():
        roster_query = roster_query.filter(
            (CentreRoster.scope_key == make_roster_scope_key(batch_id)) |
            (CentreRoster.scope_key == "centre")
        )
    roster_entry = roster_query.order_by(
        CentreRoster.updated_at.desc(),
        CentreRoster.id.desc()
    ).first()

    if roster_entry:
        for field_name in ("name", "address", "occupation", "rounds", "remark", "cheating"):
            current = _normalize_profile_text(profile.get(field_name))
            incoming = _normalize_profile_text(getattr(roster_entry, field_name, ""))
            if incoming and not current:
                profile[field_name] = incoming

    profile["source"] = "user" if user else ("roster" if roster_entry else "none")
    profile["roster"] = roster_entry.to_dict() if roster_entry else None
    return profile


def get_registration_status_payload(mobile, centre_id=None, batch_id=None):
    mobile = _normalize_profile_text(mobile)
    if not mobile:
        return {
            "ok": False,
            "error": "Mobile number is required",
        }

    centre_id = (centre_id or get_selected_centre_id() or DEFAULT_CENTRE_ID).strip()
    batch_obj = None
    batch_name = None
    if batch_id is not None and str(batch_id).strip():
        try:
            batch_obj = Batch.query.filter_by(id=int(batch_id)).first()
            if batch_obj:
                batch_name = batch_obj.name
        except Exception:
            batch_obj = None

    current_scope_key = make_roster_scope_key(batch_obj.id if batch_obj else batch_id)
    current_entries = CentreRoster.query.filter_by(
        mobile=mobile,
        centre_id=centre_id,
        scope_key=current_scope_key
    ).order_by(CentreRoster.updated_at.desc(), CentreRoster.id.desc()).all()
    centre_entries = CentreRoster.query.filter_by(
        mobile=mobile,
        centre_id=centre_id
    ).order_by(CentreRoster.updated_at.desc(), CentreRoster.id.desc()).all()
    other_entries = CentreRoster.query.filter(
        CentreRoster.mobile == mobile,
        CentreRoster.centre_id != centre_id
    ).order_by(CentreRoster.updated_at.desc(), CentreRoster.id.desc()).all()

    def enrich(entry):
        data = entry.to_dict()
        data["centre_name"] = get_centre_name_by_id(data.get("centre_id"))
        return data

    def enrich_other_centre_only(entry):
        # Cross-centre warning must name WHICH centre, never that centre's
        # actual roster data (address/occupation/rounds/remark/cheating) —
        # this is the only field set the frontend warning banner needs.
        return {
            "centre_id": entry.centre_id,
            "centre_name": get_centre_name_by_id(entry.centre_id),
        }

    return {
        "ok": True,
        "mobile": mobile,
        "centre_id": centre_id,
        "centre_name": get_centre_name_by_id(centre_id),
        "batch_id": batch_obj.id if batch_obj else (int(batch_id) if batch_id and str(batch_id).strip().isdigit() else None),
        "batch_name": batch_name or (batch_obj.name if batch_obj else None),
        "current_scope_key": current_scope_key,
        "registered_here": bool(current_entries),
        "registered_in_centre": bool(centre_entries),
        "registered_in_other_centres": bool(other_entries),
        "current_memberships": [enrich(entry) for entry in current_entries],
        "centre_memberships": [enrich(entry) for entry in centre_entries],
        "other_memberships": [enrich_other_centre_only(entry) for entry in other_entries],
        "warning": (
            "Already registered in another centre"
            if other_entries
            else (
                "Already registered in this centre/batch"
                if current_entries
                else ""
            )
        ),
    }


def category_for_batch(batch_id):
    """The class a roster row belongs to, read from the row's OWN batch.

    A roster row that has a batch_id is not ambiguous about its class: the batch already
    says which one it is, and CentreRoster.category is documented as mirroring
    batches.category for exactly those rows. Reading it per-row is therefore propagating an
    assignment that already exists, not guessing — which is the difference between this and
    backfill_roster_categories(), whose centre-level inference has to refuse multi-class
    centres because there the answer genuinely is not knowable.

    Returns None for a centre-level row (no batch), an unknown batch, or a batch whose own
    category is blank/unrecognised. None means "leave it NULL", which preserves the
    deliberate policy on centre-level rows described on the CentreRoster.category column.
    """
    if batch_id is None or str(batch_id).strip() == "":
        return None
    try:
        batch = Batch.query.filter_by(id=int(batch_id)).first()
    except (TypeError, ValueError):
        return None
    if not batch:
        return None
    cat = (batch.category or "").strip().lower()
    return cat if cat in ("youth", "congregation") else None


def backfill_roster_categories_from_batch(centre_id=None, actor="system:auto"):
    """Label unlabelled BATCH-LEVEL roster rows from their own batch.

    WHY THIS EXISTS, and why backfill_roster_categories() was not enough. That function
    infers the class from the CENTRE, so it deliberately skips any centre running more than
    one class. Centre A runs three batches across two classes, so it was skipped forever —
    and on 2026-08-21 it was the ONLY centre in the system with unlabelled rows: 109 of them,
    every single one carrying a batch whose category was known all along. The centre-level
    pass could not touch them and nothing else ever would.

    This pass asks a different, always-answerable question: not "what class is this centre?"
    but "what class is this row's batch?". Every row it touches has a batch_id, so there is
    no inference and no ambiguity, and it is safe to run on multi-class centres.

    Note this does NOT change who is callable, unlike the centre-level pass. The calling
    backend's per-batch roster loop (reconcile.ts reconcileRosterMembers) takes the category
    from the BATCH and only skips a row for a missing mobile; the row.category skip applies
    solely to its centre-level loop, which by definition never sees these rows. What the
    label actually fixes is category filtering on the AttendEase side — notably the
    per-category split on the Downloads tab, which had nothing to filter these rows by.

    Returns {centre_id: rows_labelled} for the centres it touched.
    """
    query = (
        CentreRoster.query
        .filter(CentreRoster.batch_id.isnot(None))
        .filter(db.or_(CentreRoster.category.is_(None), db.func.trim(CentreRoster.category) == ""))
    )
    if centre_id is not None:
        query = query.filter(CentreRoster.centre_id == centre_id)
    rows = query.all()
    if not rows:
        return {}

    cache = {}
    touched = {}
    for row in rows:
        if row.batch_id not in cache:
            cache[row.batch_id] = category_for_batch(row.batch_id)
        cat = cache[row.batch_id]
        if not cat:
            continue  # batch itself is unlabelled — nothing to propagate, leave it alone
        row.category = cat
        touched[row.centre_id] = touched.get(row.centre_id, 0) + 1

    if not touched:
        return {}
    try:
        db.session.commit()
    except Exception as exc:
        db.session.rollback()
        print(f"[roster-category] batch-level pass FAILED, rolled back: {exc}")
        return {}
    for cid, count in touched.items():
        print(f"[roster-category] {cid}: labelled {count} batch-level row(s) from their own batch ({actor})")
    return touched


def upsert_centre_roster_entry(mobile, centre_id, user, batch_id=None, batch_name=None, payload=None):
    payload = payload or {}
    scope_key = make_roster_scope_key(batch_id)
    roster = CentreRoster.query.filter_by(
        mobile=mobile,
        centre_id=centre_id,
        scope_key=scope_key
    ).first()

    if not roster:
        roster = CentreRoster(
            mobile=mobile,
            centre_id=centre_id,
            name=user.name or payload.get("name") or "Unknown",
            batch_id=batch_id,
            batch_name=batch_name,
            scope_key=scope_key,
            address=user.address or payload.get("address"),
            occupation=user.occupation or payload.get("occupation"),
            rounds=user.rounds or payload.get("rounds"),
            remark=user.remark or payload.get("remark"),
            cheating=user.cheating or payload.get("cheating") or "No",
            status="active",
            # Stamp the class at write time from the batch we are filing them under. Leaving
            # it NULL here is what produced Centre A's 109 unlabelled rows: the centre-level
            # backfill refuses multi-class centres, so nothing downstream ever filled it in.
            # None for a centre-level row, which keeps that column's deliberate-NULL policy.
            category=category_for_batch(batch_id),
        )
        db.session.add(roster)
        return roster, True

    merge_payload = {
        "name": payload.get("name") or user.name,
        "address": payload.get("address") or user.address,
        "occupation": payload.get("occupation") or user.occupation,
        "rounds": payload.get("rounds") or user.rounds,
        "remark": payload.get("remark") or user.remark,
        "cheating": payload.get("cheating") or user.cheating,
    }
    merge_user_profile_from_payload(roster, merge_payload, preserve_existing=True, log_prefix="Roster")
    roster.batch_id = batch_id
    roster.batch_name = batch_name
    roster.status = "active"
    # Heal a row written before the insert above learned to stamp this. Only ever FILLS a
    # blank — an existing label is left alone, because a row moved between classes by hand
    # must not be silently reverted by the next ordinary re-registration.
    if not (roster.category or "").strip():
        roster.category = category_for_batch(batch_id)
    return roster, False


def backfill_roster_categories(centre_id=None, actor="system:auto"):
    """Give every unlabelled roster row the class of its centre — but only where that is
    unambiguous, i.e. the centre runs exactly ONE class.

    WHY THIS EXISTS. `_process_centre_excel_import` writes centre-level roster rows with no
    category, because a bulk sheet rarely says which class each person is in. That was fine
    while a centre ran one class — everything blank was implicitly that class — but it left
    a growing pile of unlabelled people (1,525 by 2026-08-14) that nothing would ever resolve.
    The day a centre starts a second class, those people become genuinely ambiguous: there is
    no longer any way to tell which class they belonged to, because the only evidence was
    "the centre only had one".

    So the label is applied CONTINUOUSLY while the answer is still knowable, rather than at
    the moment a second class appears. By the time a centre has two classes there is nothing
    left unlabelled to guess about. A centre that already runs two classes is deliberately
    left alone — guessing there would be inventing data.

    Note this is the centre's OWN sole class, not a hardcoded "youth": a congregation-only
    centre that later adds youth backfills its existing people as CONGREGATION.

    CONSEQUENCE, and why this is not merely cosmetic: the calling backend's reconcile pass
    skips roster rows with no category (see reconcile.ts — `if (!row.mobile || !row.category)
    continue`). Labelling a row therefore makes that person materialise as a registration and
    appear in the calling pool. That is intended — they are real people at the centre who have
    never been called — but it means this function changes who is callable, not just how rows
    filter. It is why it only ever runs where the class is certain.

    Returns {centre_id: rows_labelled} for the centres it touched.
    """
    rows = (
        db.session.query(Batch.centre_id, Batch.category)
        .filter(Batch.category.isnot(None), db.func.trim(Batch.category) != "")
        .distinct()
        .all()
    )
    cats_by_centre = {}
    for cid, cat in rows:
        cats_by_centre.setdefault(cid, set()).add((cat or "").strip().lower())

    touched = {}
    for cid, cats in cats_by_centre.items():
        if centre_id is not None and cid != centre_id:
            continue
        if len(cats) != 1:
            continue  # two classes — the answer is no longer knowable, so do not guess
        only = next(iter(cats))
        updated = (
            CentreRoster.query
            .filter(CentreRoster.centre_id == cid)
            .filter(db.or_(CentreRoster.category.is_(None), db.func.trim(CentreRoster.category) == ""))
            .update({CentreRoster.category: only}, synchronize_session=False)
        )
        if updated:
            touched[cid] = updated
            print(f"[roster-category] {cid}: labelled {updated} row(s) as {only} ({actor})")
    if touched:
        try:
            db.session.commit()
        except Exception as exc:
            db.session.rollback()
            print(f"[roster-category] FAILED, rolled back: {exc}")
            return {}
    return touched


def make_readable_batch_folder_name(batch_id, batch_name):
    batch_id_text = str(batch_id or "").strip() or "unnamed"
    safe_batch_name = sanitize_storage_name(batch_name)
    if safe_batch_name == "unnamed":
        safe_batch_name = "unnamed_batch"
    return f"{batch_id_text}__{safe_batch_name}"


def find_existing_batch_folder(centre_id, batch_id, category=None):
    """Find a batch folder by batch_id, supporting legacy numeric folders.

    Searches the batch's category partition first, then falls back to the old
    flat centres/<centre>/batches/ location so folders written before the
    youth/congregation split (or not yet migrated) are still located.
    """
    if category is None:
        try:
            resolved = resolve_storage_context_for_batch(batch_id)
            category = resolved.get("category") if resolved else None
        except Exception:
            category = None

    batch_id_text = sanitize_storage_name(batch_id)
    readable_prefix = f"{batch_id_text}__"

    # Candidate roots: the category partition, plus the legacy flat root.
    roots = [get_batches_storage_root(centre_id, category=(category or "youth"), create=False)]
    legacy_flat = os.path.join(get_centre_storage_dir(centre_id, create=False), "batches")
    if legacy_flat not in roots:
        roots.append(legacy_flat)

    for batches_root in roots:
        if not os.path.exists(batches_root):
            continue
        try:
            for name in sorted(os.listdir(batches_root)):
                full_path = os.path.join(batches_root, name)
                if os.path.isdir(full_path) and name.startswith(readable_prefix):
                    return full_path
        except Exception:
            pass
        numeric_folder = os.path.join(batches_root, batch_id_text)
        if os.path.isdir(numeric_folder):
            return numeric_folder

    return None


def write_centre_info_file(centre_id, centre_name=None, status="active", create_parent=False):
    """Write a small human-readable metadata file for a centre folder."""
    if create_parent:
        get_centre_storage_dir(centre_id, create=True)
    centre_dir = get_centre_storage_dir(centre_id, create=False)
    if not os.path.exists(centre_dir):
        return None

    resolved_name = centre_name or get_centre_name_by_id(centre_id) or centre_id
    payload = {
        "centre_id": centre_id,
        "centre_name": resolved_name,
        "folder_name": os.path.basename(centre_dir),
        "status": status,
        "updated_at": safe_timestamp(),
    }
    info_path = os.path.join(centre_dir, "centre_info.json")
    try:
        with open(info_path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
        return info_path
    except Exception as e:
        write_log(f"Centre info write failed for {centre_id}: {e}")
        return None


def write_batch_info_file(centre_id, batch_id, batch_name=None, status="active", create_parent=False):
    """Write a small human-readable metadata file for a batch folder."""
    folder_path = get_batch_storage_dir(
        centre_id,
        batch_id,
        batch_name=batch_name,
        create=create_parent
    )
    if not folder_path or not os.path.exists(folder_path):
        return None

    resolved_name = batch_name
    if not resolved_name:
        try:
            resolved = resolve_storage_context_for_batch(batch_id)
            resolved_name = resolved.get("batch_name") if resolved else None
        except Exception:
            resolved_name = None

    payload = {
        "centre_id": centre_id,
        "batch_id": int(batch_id) if str(batch_id).strip().isdigit() else batch_id,
        "batch_name": resolved_name or "Unnamed Batch",
        "folder_name": os.path.basename(folder_path),
        "status": status,
        "updated_at": safe_timestamp(),
    }
    info_path = os.path.join(folder_path, "batch_info.json")
    try:
        with open(info_path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
        return info_path
    except Exception as e:
        write_log(f"Batch info write failed for {centre_id}/{batch_id}: {e}")
        return None


def enqueue_storage_sync_job(job_type, payload=None):
    """Queue a non-blocking folder-synchronization job."""
    storage_sync_queue.put({
        "job_type": job_type,
        "payload": payload or {},
        "attempt": 0,
    })


def storage_sync_worker():
    """Background worker for non-blocking folder synchronization jobs."""
    while True:
        task = storage_sync_queue.get()
        if task is None:
            storage_sync_queue.task_done()
            break

        job_type = task.get("job_type")
        payload = task.get("payload") or {}
        try:
            with app.app_context():
                if job_type == "ensure_storage_skeleton":
                    ensure_storage_folder_skeleton()
                elif job_type == "ensure_centre_folder":
                    centre_id = payload.get("centre_id")
                    centre_name = payload.get("centre_name")
                    status = payload.get("status") or "active"
                    if centre_id:
                        get_centre_storage_dir(centre_id, create=True)
                        get_batches_storage_root(centre_id, create=True)
                        write_centre_info_file(
                            centre_id,
                            centre_name=centre_name,
                            status=status,
                            create_parent=True
                        )
                        # Create Data import folder for active centres; leave it
                        # in place when deactivated so drop files are preserved.
                        if status == "active":
                            get_data_import_centre_dir(centre_id, create=True)
                elif job_type == "ensure_batch_folder":
                    centre_id = payload.get("centre_id")
                    batch_id = payload.get("batch_id")
                    batch_name = payload.get("batch_name")
                    status = payload.get("status") or "active"
                    if centre_id and batch_id is not None:
                        write_batch_info_file(
                            centre_id,
                            batch_id,
                            batch_name=batch_name,
                            status=status,
                            create_parent=True
                        )
                        # Create Data import folder for active batches; leave it
                        # in place when deleted so drop files are preserved.
                        if status == "active" and batch_name:
                            get_data_import_batch_dir(centre_id, batch_name, create=True)
                elif job_type == "ensure_session_folder":
                    centre_id = payload.get("centre_id")
                    batch_id = payload.get("batch_id")
                    batch_name = payload.get("batch_name")
                    session_id = payload.get("session_id")
                    session_name = payload.get("session_name")
                    status = payload.get("status") or "active"
                    if centre_id and batch_id is not None and session_id:
                        write_session_info_file(
                            centre_id,
                            batch_id,
                            session_id,
                            session_name=session_name,
                            batch_name=batch_name,
                            status=status,
                            create_parent=True
                        )
                elif job_type == "rename_session_folder":
                    centre_id = payload.get("centre_id")
                    batch_id = payload.get("batch_id")
                    batch_name = payload.get("batch_name")
                    session_id = payload.get("session_id")
                    session_name = payload.get("session_name")
                    old_session_name = payload.get("old_session_name")
                    status = payload.get("status") or "active"
                    if centre_id and batch_id is not None and session_id:
                        desired_folder_path = get_session_folder_path(
                            centre_id,
                            batch_id,
                            session_id,
                            session_name=session_name or session_id,
                            batch_name=batch_name,
                            create=True
                        )
                        existing_folder = find_existing_session_folder(
                            centre_id,
                            batch_id,
                            session_id,
                            batch_name=batch_name
                        )
                        if existing_folder and os.path.abspath(existing_folder) != os.path.abspath(desired_folder_path):
                            try:
                                if not os.path.exists(desired_folder_path):
                                    os.replace(existing_folder, desired_folder_path)
                                else:
                                    write_log(
                                        f"Session folder rename skipped because destination exists: "
                                        f"{desired_folder_path}"
                                    )
                            except Exception as e:
                                write_log(
                                    f"Session folder rename failed for {centre_id}/{batch_id}/{session_id} "
                                    f"({old_session_name} -> {session_name}): {e}"
                                )
                        os.makedirs(desired_folder_path, exist_ok=True)
                        write_session_info_file(
                            centre_id,
                            batch_id,
                            session_id,
                            session_name=session_name,
                            batch_name=batch_name,
                            status=status,
                            create_parent=True
                        )
                elif job_type == "mark_session_deleted":
                    centre_id = payload.get("centre_id")
                    batch_id = payload.get("batch_id")
                    batch_name = payload.get("batch_name")
                    session_id = payload.get("session_id")
                    session_name = payload.get("session_name")
                    deleted_at = payload.get("deleted_at") or safe_timestamp()
                    if centre_id and batch_id is not None and session_id:
                        write_session_info_file(
                            centre_id,
                            batch_id,
                            session_id,
                            session_name=session_name,
                            batch_name=batch_name,
                            status="deleted",
                            deleted_at=deleted_at,
                            create_parent=False
                        )
                elif job_type == "rename_batch_folder":
                    centre_id = payload.get("centre_id")
                    batch_id = payload.get("batch_id")
                    batch_name = payload.get("batch_name")
                    old_batch_name = payload.get("old_batch_name")
                    status = payload.get("status") or "active"
                    if not centre_id or batch_id is None:
                        continue

                    # Rename the Data import subfolder to match the new batch name.
                    if old_batch_name and batch_name and old_batch_name != batch_name:
                        old_data_dir = get_data_import_batch_dir(centre_id, old_batch_name)
                        new_data_dir = get_data_import_batch_dir(centre_id, batch_name)
                        if os.path.exists(old_data_dir):
                            if not os.path.exists(new_data_dir):
                                try:
                                    os.replace(old_data_dir, new_data_dir)
                                except Exception as e:
                                    write_log(
                                        f"Data import folder rename failed "
                                        f"({old_batch_name} -> {batch_name}): {e}"
                                    )
                            else:
                                write_log(
                                    f"Data import folder rename skipped — "
                                    f"destination already exists: {new_data_dir}"
                                )
                        else:
                            # Old folder doesn't exist yet; create the new one.
                            get_data_import_batch_dir(centre_id, batch_name, create=True)

                    resolved_ctx = resolve_storage_context_for_batch(batch_id)
                    batch_category = resolved_ctx.get("category") if resolved_ctx else "youth"
                    batches_root = get_batches_storage_root(centre_id, category=batch_category, create=True)
                    desired_folder_name = make_readable_batch_folder_name(batch_id, batch_name)
                    desired_folder_path = os.path.join(batches_root, desired_folder_name)
                    existing_folder = find_existing_batch_folder(centre_id, batch_id, category=batch_category)

                    if existing_folder and os.path.abspath(existing_folder) != os.path.abspath(desired_folder_path):
                        try:
                            if not os.path.exists(desired_folder_path):
                                os.replace(existing_folder, desired_folder_path)
                            else:
                                write_log(
                                    f"Batch folder rename skipped because destination exists: "
                                    f"{desired_folder_path}"
                                )
                        except Exception as e:
                            write_log(
                                f"Batch folder rename failed for {centre_id}/{batch_id} "
                                f"({old_batch_name} -> {batch_name}): {e}"
                            )

                    os.makedirs(desired_folder_path, exist_ok=True)
                    write_batch_info_file(
                        centre_id,
                        batch_id,
                        batch_name=batch_name,
                        status=status,
                        create_parent=True
                    )
                    try:
                        session_mappings = SessionMapping.query.filter_by(
                            centre_id=centre_id,
                            batch_id=batch_id
                        ).all()
                        for mapping in session_mappings:
                            write_session_info_file(
                                centre_id,
                                batch_id,
                                mapping.session_id,
                                session_name=mapping.session_id,
                                batch_name=batch_name,
                                status="active",
                                create_parent=True
                            )
                    except Exception as e:
                        write_log(
                            f"Session metadata refresh failed after batch rename {centre_id}/{batch_id}: {e}"
                        )
                else:
                    write_log(f"Unknown storage sync job: {job_type}")
        except Exception as e:
            write_log(f"Storage sync job failed ({job_type}): {e}")
        finally:
            storage_sync_queue.task_done()

threading.Thread(target=storage_sync_worker, daemon=True).start()

def migrate_export_mirror_layout():
    """One-time (idempotent) relocation of the export mirror into category
    subfolders.

    Older builds wrote every batch to centres/<centre>/batches/<batch>/. The
    youth/congregation split moves them to
    centres/<centre>/<Youth|Congregation>/batches/<batch>/. This walks each
    centre's legacy flat batches/ folder and moves every batch folder into the
    partition matching that batch's DB category (defaulting to Youth for
    orphans/unknown ids). Safe to run on every startup — once the flat folder is
    empty it is removed and there is nothing left to move.
    """
    try:
        centres_root = get_centres_storage_root(create=False)
    except Exception:
        return
    if not os.path.isdir(centres_root):
        return

    # (sanitized centre folder name, batch_id text) -> 'Youth' | 'Congregation'
    cat_lookup = {}
    try:
        with app.app_context():
            for b in Batch.query.all():
                cat_lookup[(sanitize_storage_name(b.centre_id), str(b.id))] = (
                    category_folder_component(getattr(b, "category", None))
                )
    except Exception as e:
        write_log(f"Export mirror migration skipped (DB read failed): {e}")
        return

    moved = 0
    try:
        centre_components = os.listdir(centres_root)
    except OSError:
        return

    for centre_component in centre_components:
        centre_dir = os.path.join(centres_root, centre_component)
        legacy_batches = os.path.join(centre_dir, "batches")
        if not os.path.isdir(legacy_batches):
            continue
        try:
            entries = [
                e for e in os.listdir(legacy_batches)
                if os.path.isdir(os.path.join(legacy_batches, e))
            ]
        except OSError:
            continue

        for folder_name in entries:
            # Batch id is the prefix before '__' (readable folders) or the whole
            # name (legacy numeric folders).
            batch_id_text = folder_name.split("__", 1)[0]
            category_component = cat_lookup.get(
                (centre_component, batch_id_text), "Youth"
            )
            dest_batches = os.path.join(centre_dir, category_component, "batches")
            src = os.path.join(legacy_batches, folder_name)
            dest = os.path.join(dest_batches, folder_name)
            if os.path.abspath(src) == os.path.abspath(dest):
                continue
            try:
                os.makedirs(dest_batches, exist_ok=True)
                if not os.path.exists(dest):
                    os.replace(src, dest)
                    moved += 1
                else:
                    write_log(
                        f"Export mirror migration: destination exists, leaving {src}"
                    )
            except Exception as e:
                write_log(f"Export mirror migration failed for {src}: {e}")

        # Drop the now-empty legacy flat folder so it doesn't linger.
        try:
            if not os.listdir(legacy_batches):
                os.rmdir(legacy_batches)
        except OSError:
            pass

    if moved:
        write_log(
            f"Export mirror migration: relocated {moved} batch folder(s) into "
            f"youth/congregation subfolders."
        )


def ensure_storage_folder_skeleton():
    """
    Create the initial future storage folder skeleton without moving or
    creating any Excel files.
    """
    ensured_paths = []
    centre_count = 0
    batch_count = 0
    session_count = 0

    # Top-level future storage roots.
    ensured_paths.append(get_master_storage_dir(create=True))
    ensured_paths.append(get_centres_storage_root(create=True))

    # Centre folders and their batches roots for active centres only.
    active_centres = Centre.query.filter(Centre.status == 'active').order_by(
        Centre.name.asc(),
        Centre.centre_id.asc()
    ).all()

    for centre in active_centres:
        centre_dir = get_centre_storage_dir(centre.centre_id, create=True)
        batches_dir = get_batches_storage_root(centre.centre_id, category="youth", create=True)
        ensured_paths.extend([centre_dir, batches_dir])
        # Surface the Congregation partition up front for centres that use it so
        # the split is visible in the file browser before any congregation batch
        # exists.
        if getattr(centre, "congregation_enabled", False):
            ensured_paths.append(
                get_batches_storage_root(centre.centre_id, category="congregation", create=True)
            )
        centre_count += 1
        write_centre_info_file(
            centre.centre_id,
            centre_name=centre.name,
            status=centre.status,
            create_parent=True
        )

        # Data import drop folder for this centre.
        get_data_import_centre_dir(centre.centre_id, create=True)

        # Existing batches should also have folder placeholders.
        active_batches = Batch.query.filter_by(centre_id=centre.centre_id).order_by(Batch.id.asc()).all()
        for batch in active_batches:
            get_batch_storage_dir(centre.centre_id, batch.id, batch_name=batch.name, create=True)
            write_batch_info_file(
                centre.centre_id,
                batch.id,
                batch_name=batch.name,
                status="active",
                create_parent=True
            )
            # Data import drop folder for this batch.
            get_data_import_batch_dir(centre.centre_id, batch.name, create=True)
            batch_count += 1

            try:
                linked_sessions = SessionMapping.query.filter_by(
                    centre_id=centre.centre_id,
                    batch_id=batch.id
                ).all()
                for mapping in linked_sessions:
                    enqueue_storage_sync_job(
                        "ensure_session_folder",
                        {
                            "centre_id": centre.centre_id,
                            "batch_id": batch.id,
                            "batch_name": batch.name,
                            "session_id": mapping.session_id,
                            "session_name": mapping.session_id,
                            "status": "active",
                        }
                    )
                    session_count += 1
            except Exception as e:
                write_log(
                    f"Session folder enqueue failed for {centre.centre_id}/{batch.id}: {e}"
                )

    # Keep the legacy workbook intact and active.
    if os.path.exists(LIVE_FILE):
        ensured_paths.append(LIVE_FILE)

    unique_paths = []
    for path in ensured_paths:
        if path and path not in unique_paths:
            unique_paths.append(path)

    write_log(
        f"Storage skeleton ensured for {centre_count} centres, {batch_count} batches and {session_count} sessions: "
        + ", ".join(unique_paths)
    )
    return unique_paths


EXCEL_OUTPUT_QUEUE = queue.Queue()

OUTPUT_EXCEL_HEADERS = [
    "centre_id",
    "centre_name",
    "batch_id",
    "batch_name",
    "session_id",
    "session_name",
    "date",
    "mobile",
    "name",
    "address",
    "occupation",
    "rounds",
    "remark",
    "cheating",
    "timestamp",
]

STUDENT_MASTER_HEADERS = [
    "mobile",
    "name",
    "address",
    "occupation",
    "rounds",
    "remark",
    "cheating",
]


def enqueue_excel_output_refresh(job_type, payload=None):
    EXCEL_OUTPUT_QUEUE.put({
        "job_type": job_type,
        "payload": payload or {},
        "attempt": 0,
    })


def build_student_master_rows(centre_id=None):
    rows = []
    if centre_id:
        # Only include users who have attendance at this centre
        mobiles = [
            m[0] for m in db.session.query(Attendance.mobile)
            .filter(Attendance.centre_id == centre_id)
            .distinct().all()
            if m[0]
        ]
        users = User.query.filter(User.mobile.in_(mobiles)).order_by(User.mobile.asc()).all() if mobiles else []
    else:
        users = User.query.order_by(User.mobile.asc()).all()
    for user in users:
        rows.append({
            "mobile": user.mobile,
            "name": user.name or "",
            "address": user.address or "",
            "occupation": user.occupation or "",
            "rounds": user.rounds or "",
            "remark": user.remark or "",
            "cheating": user.cheating or "No",
        })
    return rows


def _attendance_row_to_output(att, centre_name, user_map, batch_map, session_map):
    user = user_map.get(att.mobile)
    batch = batch_map.get(att.batch_name)
    mapping = session_map.get(att.session_id)
    session_name = att.session_id
    if mapping and getattr(mapping, "session_id", None):
        session_name = mapping.session_id

    batch_id = None
    if mapping and getattr(mapping, "batch_id", None) is not None:
        batch_id = mapping.batch_id
    elif batch:
        batch_id = batch.id

    return {
        "centre_id": att.centre_id or "",
        "centre_name": centre_name or att.centre_id or "",
        "batch_id": batch_id if batch_id is not None else "",
        "batch_name": batch.name if batch else (att.batch_name or ""),
        "session_id": att.session_id or "",
        "session_name": session_name or "",
        "date": att.date or "",
        "mobile": att.mobile or "",
        "name": user.name if user else "",
        "address": user.address if user else "",
        "occupation": user.occupation if user else "",
        "rounds": att.rounds or (user.rounds if user else ""),
        "remark": att.remark or (user.remark if user else ""),
        "cheating": att.cheating or (user.cheating if user else "No"),
        "timestamp": att.timestamp.strftime("%Y-%m-%d %H:%M:%S") if att.timestamp else "",
    }


def build_attendance_rows_for_centre(centre_id):
    centre = Centre.query.filter_by(centre_id=centre_id).first()
    centre_name = centre.name if centre else centre_id
    attendances = Attendance.query.filter_by(centre_id=centre_id).order_by(
        Attendance.timestamp.asc(),
        Attendance.id.asc()
    ).all()
    mobiles = {att.mobile for att in attendances if att.mobile}
    batch_names = {att.batch_name for att in attendances if att.batch_name}
    session_ids = {att.session_id for att in attendances if att.session_id}

    users = User.query.filter(User.mobile.in_(mobiles)).all() if mobiles else []
    user_map = {u.mobile: u for u in users}
    batches = Batch.query.filter(Batch.centre_id == centre_id).filter(Batch.name.in_(batch_names)).all() if batch_names else []
    batch_map = {b.name: b for b in batches}
    mappings = SessionMapping.query.filter(
        SessionMapping.centre_id == centre_id,
        SessionMapping.session_id.in_(session_ids)
    ).all() if session_ids else []
    session_map = {m.session_id: m for m in mappings}

    return [
        _attendance_row_to_output(att, centre_name, user_map, batch_map, session_map)
        for att in attendances
    ]


def build_attendance_rows_for_batch(centre_id, batch_id):
    batch = Batch.query.filter_by(id=batch_id, centre_id=centre_id).first()
    batch_name = batch.name if batch else None
    if not batch_name:
        resolved = resolve_storage_context_for_batch(batch_id)
        batch_name = resolved.get("batch_name") or ""

    centre = Centre.query.filter_by(centre_id=centre_id).first()
    centre_name = centre.name if centre else centre_id
    attendances = Attendance.query.filter_by(
        centre_id=centre_id,
        batch_name=batch_name
    ).order_by(Attendance.timestamp.asc(), Attendance.id.asc()).all()

    mobiles = {att.mobile for att in attendances if att.mobile}
    session_ids = {att.session_id for att in attendances if att.session_id}
    users = User.query.filter(User.mobile.in_(mobiles)).all() if mobiles else []
    user_map = {u.mobile: u for u in users}
    batch_map = {batch_name: batch} if batch and batch_name else {}
    mappings = SessionMapping.query.filter(
        SessionMapping.centre_id == centre_id,
        SessionMapping.session_id.in_(session_ids)
    ).all() if session_ids else []
    session_map = {m.session_id: m for m in mappings}

    return [
        _attendance_row_to_output(att, centre_name, user_map, batch_map, session_map)
        for att in attendances
    ]


def build_attendance_rows_for_session(centre_id, batch_id, session_id):
    batch = Batch.query.filter_by(id=batch_id, centre_id=centre_id).first()
    batch_name = batch.name if batch else None
    mapping = SessionMapping.query.filter_by(session_id=session_id, centre_id=centre_id).first()
    if not batch_name and mapping:
        batch = Batch.query.filter_by(id=mapping.batch_id, centre_id=centre_id).first()
        batch_name = batch.name if batch else None

    centre = Centre.query.filter_by(centre_id=centre_id).first()
    centre_name = centre.name if centre else centre_id
    attendances = Attendance.query.filter_by(
        centre_id=centre_id,
        session_id=session_id
    ).order_by(Attendance.timestamp.asc(), Attendance.id.asc()).all()
    if batch_name:
        attendances = [att for att in attendances if att.batch_name == batch_name]

    mobiles = {att.mobile for att in attendances if att.mobile}
    users = User.query.filter(User.mobile.in_(mobiles)).all() if mobiles else []
    user_map = {u.mobile: u for u in users}
    batch_map = {batch_name: batch} if batch and batch_name else {}
    session_map = {session_id: mapping} if mapping else {}

    return [
        _attendance_row_to_output(att, centre_name, user_map, batch_map, session_map)
        for att in attendances
    ]


def write_rows_to_xlsx(path, headers, rows, sheet_title="Data"):
    final_dir = os.path.dirname(path)
    if final_dir and not os.path.exists(final_dir):
        raise FileNotFoundError(f"Target folder missing: {final_dir}")

    tmp_fd, tmp_path = tempfile.mkstemp(prefix="excel_output_", suffix=".xlsx", dir=TEMP_DIR)
    os.close(tmp_fd)
    try:
        wb = Workbook()
        ws = wb.active
        ws.title = sheet_title
        ws.append(headers)
        for row in rows:
            if isinstance(row, dict):
                ws.append([row.get(h, "") for h in headers])
            else:
                ws.append(list(row))
        wb.save(tmp_path)
        wb.close()
        return tmp_path
    except Exception:
        try:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)
        except Exception:
            pass
        raise


def safe_replace_xlsx(temp_path, final_path):
    try:
        os.makedirs(os.path.dirname(final_path), exist_ok=True)
        os.replace(temp_path, final_path)
        return True
    except Exception as e:
        write_log(f"Excel mirror replace failed for {final_path}: {e}")
        return False
    finally:
        try:
            if temp_path and os.path.exists(temp_path):
                os.remove(temp_path)
        except Exception:
            pass


def refresh_student_master_excel():
    path = get_student_master_path(create_parent=True)
    rows = build_student_master_rows()
    temp_path = write_rows_to_xlsx(path, STUDENT_MASTER_HEADERS, rows, sheet_title="Users")
    if not safe_replace_xlsx(temp_path, path):
        raise IOError(f"Failed to replace {path}")
    return True


def refresh_centre_master_excel(centre_id):
    write_centre_info_file(centre_id, create_parent=True)
    path = get_centre_master_path(centre_id, create_parent=False)
    if not os.path.isdir(os.path.dirname(path)):
        raise FileNotFoundError(f"Centre folder missing for {centre_id}")
    rows = build_attendance_rows_for_centre(centre_id)
    temp_path = write_rows_to_xlsx(path, OUTPUT_EXCEL_HEADERS, rows, sheet_title="Attendance")
    if not safe_replace_xlsx(temp_path, path):
        raise IOError(f"Failed to replace {path}")
    return True


def refresh_batch_master_excel(centre_id, batch_id, batch_name=None):
    write_batch_info_file(
        centre_id,
        batch_id,
        batch_name=batch_name,
        status="active",
        create_parent=True
    )
    path = get_batch_master_path(centre_id, batch_id, batch_name=batch_name, create_parent=False)
    if not os.path.isdir(os.path.dirname(path)):
        raise FileNotFoundError(f"Batch folder missing for {centre_id}/{batch_id}")
    rows = build_attendance_rows_for_batch(centre_id, batch_id)
    temp_path = write_rows_to_xlsx(path, OUTPUT_EXCEL_HEADERS, rows, sheet_title="Attendance")
    if not safe_replace_xlsx(temp_path, path):
        raise IOError(f"Failed to replace {path}")
    return True


def refresh_session_attendance_excel(centre_id, batch_id, session_id, session_name=None, batch_name=None):
    write_session_info_file(
        centre_id,
        batch_id,
        session_id,
        session_name=session_name,
        batch_name=batch_name,
        status="active",
        create_parent=True
    )
    path = get_session_attendance_path(
        centre_id,
        batch_id,
        session_id,
        session_name=session_name,
        batch_name=batch_name,
        create_parent=False
    )
    if not os.path.isdir(os.path.dirname(path)):
        raise FileNotFoundError(f"Session folder missing for {centre_id}/{batch_id}/{session_id}")
    rows = build_attendance_rows_for_session(centre_id, batch_id, session_id)
    temp_path = write_rows_to_xlsx(path, OUTPUT_EXCEL_HEADERS, rows, sheet_title="Attendance")
    if not safe_replace_xlsx(temp_path, path):
        raise IOError(f"Failed to replace {path}")
    return True


def _queue_batch_session_refreshes(
    centre_id,
    batch_id,
    batch_name=None,
    session_id=None,
    session_name=None,
    session_ids=None,
    refresh_student=False,
    status="active"
):
    enqueue_excel_output_refresh(
        "refresh_centre_master_excel",
        {
            "centre_id": centre_id,
            "status": status,
        }
    )
    enqueue_excel_output_refresh(
        "refresh_batch_master_excel",
        {
            "centre_id": centre_id,
            "batch_id": batch_id,
            "batch_name": batch_name,
            "status": status,
        }
    )
    if session_ids:
        for sid in session_ids:
            enqueue_excel_output_refresh(
                "refresh_session_attendance_excel",
                {
                    "centre_id": centre_id,
                    "batch_id": batch_id,
                    "batch_name": batch_name,
                    "session_id": sid,
                    "session_name": sid,
                    "status": status,
                }
            )
    elif session_id:
        enqueue_excel_output_refresh(
            "refresh_session_attendance_excel",
            {
                "centre_id": centre_id,
                "batch_id": batch_id,
                "batch_name": batch_name,
                "session_id": session_id,
                "session_name": session_name or session_id,
                "status": status,
            }
        )
    if refresh_student:
        enqueue_excel_output_refresh("refresh_student_master_excel", {"reason": "profile_change"})


def excel_output_worker():
    while True:
        task = EXCEL_OUTPUT_QUEUE.get()
        if task is None:
            EXCEL_OUTPUT_QUEUE.task_done()
            break

        job_type = task.get("job_type")
        payload = task.get("payload") or {}
        attempt = int(task.get("attempt", 0) or 0)
        try:
            with app.app_context():
                if job_type == "refresh_student_master_excel":
                    refresh_student_master_excel()
                elif job_type == "refresh_centre_master_excel":
                    refresh_centre_master_excel(payload.get("centre_id"))
                elif job_type == "refresh_batch_master_excel":
                    refresh_batch_master_excel(
                        payload.get("centre_id"),
                        payload.get("batch_id"),
                        batch_name=payload.get("batch_name")
                    )
                elif job_type == "refresh_session_attendance_excel":
                    refresh_session_attendance_excel(
                        payload.get("centre_id"),
                        payload.get("batch_id"),
                        payload.get("session_id"),
                        session_name=payload.get("session_name"),
                        batch_name=payload.get("batch_name")
                    )
                elif job_type == "refresh_attendance_mirrors_for_attendance_change":
                    centre_id = payload.get("centre_id")
                    batch_id = payload.get("batch_id")
                    batch_name = payload.get("batch_name")
                    session_id = payload.get("session_id")
                    session_name = payload.get("session_name") or session_id
                    session_ids = payload.get("session_ids") or []
                    refresh_student = bool(payload.get("refresh_student_master"))
                    status = payload.get("status") or "active"

                    if refresh_student:
                        refresh_student_master_excel()
                    if centre_id:
                        refresh_centre_master_excel(centre_id)
                    if centre_id and batch_id is not None:
                        refresh_batch_master_excel(centre_id, batch_id, batch_name=batch_name)
                    if centre_id and batch_id is not None and session_id:
                        refresh_session_attendance_excel(
                            centre_id,
                            batch_id,
                            session_id,
                            session_name=session_name,
                            batch_name=batch_name
                        )
                    if centre_id and batch_id is not None and session_ids:
                        for sid in session_ids:
                            refresh_session_attendance_excel(
                                centre_id,
                                batch_id,
                                sid,
                                session_name=sid,
                                batch_name=batch_name
                            )
                else:
                    write_log(f"Unknown excel output job: {job_type}")
        except Exception as e:
            if attempt < 3:
                retry_task = dict(task)
                retry_task["attempt"] = attempt + 1
                write_log(f"Excel output retry {attempt + 1}/3 scheduled after error ({job_type}): {e}")
                time.sleep(2)
                EXCEL_OUTPUT_QUEUE.put(retry_task)
            else:
                write_log(f"Excel output job failed ({job_type}): {e}")
        finally:
            EXCEL_OUTPUT_QUEUE.task_done()


threading.Thread(target=excel_output_worker, daemon=True).start()


def enqueue_initial_excel_output_refreshes():
    """Queue a lightweight refresh for all visible Excel mirrors without blocking startup."""
    enqueue_excel_output_refresh("refresh_student_master_excel", {"reason": "startup"})
    centres = Centre.query.filter(Centre.status == 'active').order_by(Centre.name.asc(), Centre.centre_id.asc()).all()
    for centre in centres:
        enqueue_excel_output_refresh("refresh_centre_master_excel", {"centre_id": centre.centre_id})
        batches = Batch.query.filter_by(centre_id=centre.centre_id).order_by(Batch.id.asc()).all()
        for batch in batches:
            enqueue_excel_output_refresh(
                "refresh_batch_master_excel",
                {
                    "centre_id": centre.centre_id,
                    "batch_id": batch.id,
                    "batch_name": batch.name,
                }
            )
            sessions = SessionMapping.query.filter_by(centre_id=centre.centre_id, batch_id=batch.id).order_by(SessionMapping.created_at.asc()).all()
            for mapping in sessions:
                enqueue_excel_output_refresh(
                    "refresh_session_attendance_excel",
                    {
                        "centre_id": centre.centre_id,
                        "batch_id": batch.id,
                        "batch_name": batch.name,
                        "session_id": mapping.session_id,
                        "session_name": mapping.session_id,
                    }
                )


def start_tunnel_background():
    def run():
        global TUNNEL_MONITOR_STARTED, TUNNEL_URL, TUNNEL_PROCESS
        with TUNNEL_PROCESS_LOCK:
            if TUNNEL_MONITOR_STARTED:
                return
            TUNNEL_MONITOR_STARTED = True

        # Keep the public URL visible immediately while the tunnel spins up.
        TUNNEL_URL = PUBLIC_BASE_URL.rstrip("/")
        print("\n" + "!" * 60)
        print(" PRODUCTION MODE ACTIVE")
        print(f" LIVE PRODUCTION LINK: {TUNNEL_URL}")
        print(f" Mobile workers:      {TUNNEL_URL}/register")
        print(" " + "!" * 58)
        print(" Note: Temp links are DISABLED. Using permanent domain.")
        print("!" * 60 + "\n", flush=True)

        while True:
            try:
                _cleanup_stale_tunnel_processes()
                if _local_health_is_ok():
                    with TUNNEL_PROCESS_LOCK:
                        tunnel_alive = TUNNEL_PROCESS and TUNNEL_PROCESS.poll() is None
                    if not tunnel_alive:
                        _spawn_tunnel_process()
                time.sleep(15)
            except Exception as e:
                write_log(f"Tunnel monitor error: {e}")
                time.sleep(15)

    threading.Thread(target=run, daemon=True).start()

def start_terminal_listener():
    def listener():
        while True:
            try:
                cmd = input().strip()
                if cmd.lower() == "change address list":
                    print("\n" + "="*40)
                    print(" " * 10 + "ADDRESS MANAGER")
                    print("="*40)
                    with app.app_context():
                        while True:
                            options = AddressOption.query.order_by(AddressOption.id).all()
                            if not options:
                                print("\nNo addresses configured.")
                            else:
                                print("\nCurrent Address Options:")
                                for addr in options:
                                    scope = "shared" if addr.centre_id is None else addr.centre_id
                                    print(f"  [{addr.id}] {addr.text}  ({scope})")
                            
                            print("\nCommands:")
                            print("  add <text>   - Add new address")
                            print("  del <id>     - Delete address by ID")
                            print("  clear        - Delete all addresses")
                            print("  exit         - Return to main server log")
                            
                            subcmd = input("\nAddressManager> ").strip()
                            if subcmd.lower() == "exit":
                                print("Exiting Address Manager...\n")
                                break
                            elif subcmd.lower().startswith("add "):
                                text = subcmd[4:].strip()
                                if text:
                                    # The console is the host's, so it manages the shared list.
                                    if AddressOption.query.filter_by(text=text, centre_id=None).first():
                                        print(f"Error: Address '{text}' already exists.")
                                    else:
                                        new_addr = AddressOption(text=text, centre_id=None)
                                        db.session.add(new_addr)
                                        db.session.commit()
                                        print(f"Success: Added '{text}'")
                            elif subcmd.lower().startswith("del "):
                                try:
                                    addr_id = int(subcmd[4:].strip())
                                    addr = db.session.get(AddressOption, addr_id)
                                    if addr:
                                        db.session.delete(addr)
                                        db.session.commit()
                                        print(f"Success: Deleted '{addr.text}'")
                                    else:
                                        print("Error: Invalid ID.")
                                except ValueError:
                                    print("Error: Please provide a valid numeric ID.")
                            elif subcmd.lower() == "clear":
                                AddressOption.query.delete()
                                db.session.commit()
                                print("Success: All addresses cleared.")
                            else:
                                if subcmd:
                                    print("Unknown command. Type 'exit' to leave.")
                elif cmd.lower() == "change remark list":
                    print("\n" + "="*40)
                    print(" " * 10 + "REMARK MANAGER")
                    print("="*40)
                    with app.app_context():
                        while True:
                            options = RemarkOption.query.order_by(RemarkOption.id).all()
                            if not options:
                                print("\nNo remarks configured.")
                            else:
                                print("\nCurrent Remark Options:")
                                for rem in options:
                                    scope = "shared" if rem.centre_id is None else rem.centre_id
                                    print(f"  [{rem.id}] {rem.text}  ({scope})")
                            
                            print("\nCommands:")
                            print("  add <text>   - Add new remark")
                            print("  del <id>     - Delete remark by ID")
                            print("  clear        - Delete all remarks")
                            print("  exit         - Return to main server log")
                            
                            subcmd = input("\nRemarkManager> ").strip()
                            if subcmd.lower() == "exit":
                                print("Exiting Remark Manager...\n")
                                break
                            elif subcmd.lower().startswith("add "):
                                text = subcmd[4:].strip()
                                if text:
                                    # The console is the host's, so it manages the shared list.
                                    if RemarkOption.query.filter_by(text=text, centre_id=None).first():
                                        print(f"Error: Remark '{text}' already exists.")
                                    else:
                                        new_rem = RemarkOption(text=text, centre_id=None)
                                        db.session.add(new_rem)
                                        db.session.commit()
                                        print(f"Success: Added '{text}'")
                            elif subcmd.lower().startswith("del "):
                                try:
                                    rem_id = int(subcmd[4:].strip())
                                    rem = db.session.get(RemarkOption, rem_id)
                                    if rem:
                                        db.session.delete(rem)
                                        db.session.commit()
                                        print(f"Success: Deleted '{rem.text}'")
                                    else:
                                        print("Error: Invalid ID.")
                                except ValueError:
                                    print("Error: Please provide a valid numeric ID.")
                            elif subcmd.lower() == "clear":
                                RemarkOption.query.delete()
                                db.session.commit()
                                print("Success: All remarks cleared.")
                            else:
                                if subcmd:
                                    print("Unknown command. Type 'exit' to leave.")
                else:
                    pass # Ignore other random keystrokes
            except EOFError:
                break
            except Exception as e:
                print(f"CLI Error: {e}")
                
    threading.Thread(target=listener, daemon=True).start()


app.config['SQLALCHEMY_DATABASE_URI'] = f'sqlite:///{DB_PATH}'
app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False

# Global state for active session (Synchronized across all clients)
# Per-centre active session state — keyed by centre_id so multiple
# centres can have independent active sessions simultaneously.
ACTIVE_SESSION_BY_CENTRE = {}  # { centre_id: session_state_dict }

def get_active_session(centre_id=None, category='youth'):
    """Return the active session dict for a (centre, category); create default if absent.

    Youth/Congregation separation: the active session is now nested per category so a
    youth session and a congregation session can both be live at once. Every legacy
    call site that passes no category resolves against the youth slot, so youth-only
    centres behave exactly as before.
    """
    cid = centre_id or get_selected_centre_id()
    cat = (category or 'youth').strip().lower()
    by_cat = ACTIVE_SESSION_BY_CENTRE.setdefault(cid, {})
    if cat not in by_cat:
        by_cat[cat] = {
            "centre_id": cid,
            "centre_name": DEFAULT_CENTRE_NAME,
            "category": cat,
            "session_id": None,
            "batch_id": None,
            "batch_name": None,
            "date": None,
            "sheet_name": None,
        }
    return by_cat[cat]

def set_active_session(centre_id, data, category='youth'):
    """Store a new session state dict for a (centre, category) and return it."""
    cid = centre_id or get_selected_centre_id()
    cat = (category or 'youth').strip().lower()
    if isinstance(data, dict):
        data.setdefault('category', cat)
    ACTIVE_SESSION_BY_CENTRE.setdefault(cid, {})[cat] = data
    return ACTIVE_SESSION_BY_CENTRE[cid][cat]

# Global state for connected devices: { ip: {name, user_agent, active, last_seen} }
CONNECTED_DEVICES = {}

db = SQLAlchemy(app)

# Database Models
class User(db.Model):
    __tablename__ = 'users'
    mobile = db.Column(db.String(20), primary_key=True)
    name = db.Column(db.String(100), nullable=False)
    address = db.Column(db.String(200), nullable=True)
    occupation = db.Column(db.String(100), nullable=True)
    rounds = db.Column(db.String(50), nullable=True)
    remark = db.Column(db.String(200), nullable=True)
    cheating = db.Column(db.String(10), nullable=True, default="No")
    
    # Relationships to attendance and centre registrations with cascading delete
    attendances = db.relationship('Attendance', backref='user', cascade="all, delete-orphan", lazy=True, passive_deletes=True)
    roster_memberships = db.relationship('CentreRoster', backref='user', cascade="all, delete-orphan", lazy=True, passive_deletes=True)

    def __init__(self, mobile, name="Unknown", address=None, occupation=None, rounds=None, remark=None, cheating="No"):
        self.mobile = mobile
        self.name = name
        self.address = address
        self.occupation = occupation
        self.rounds = rounds
        self.remark = remark
        self.cheating = cheating

    def to_dict(self):
        return {
            'mobile': self.mobile,
            'name': self.name,
            'address': self.address,
            'occupation': self.occupation,
            'rounds': self.rounds,
            'remark': self.remark,
            'cheating': self.cheating
        }

class RemarkOption(db.Model):
    __tablename__ = 'remark_options'
    id = db.Column(db.Integer, primary_key=True)
    text = db.Column(db.String(100), nullable=False)
    # NULL = shared by every centre. A centre's own rows appear alongside the shared ones for that
    # centre only, so a centre adding to its list never changes what another centre sees.
    centre_id = db.Column(
        db.String(100), db.ForeignKey('centres.centre_id', ondelete='CASCADE'), nullable=True
    )
    # Uniqueness is per list, not global — and IFNULL, not a plain UNIQUE(centre_id, text), because
    # SQLite counts NULLs as distinct and the plain form would let the shared list hold duplicates
    # the old schema forbade. See ensure_option_centre_scope().
    __table_args__ = (
        db.Index('ux_remark_options_centre_text', db.text("IFNULL(centre_id, '')"), 'text', unique=True),
    )

    def to_dict(self):
        return {'id': self.id, 'text': self.text, 'centre_id': self.centre_id,
                'is_shared': self.centre_id is None}

class Batch(db.Model):
    __tablename__ = 'batches'
    id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    name = db.Column(db.String(100), unique=True, nullable=False)
    # BUG-5 fix: enforce referential integrity at DB level
    centre_id = db.Column(db.String(100), db.ForeignKey('centres.centre_id', ondelete='CASCADE'), nullable=False, default="centre_b_Base")
    # Phase 4: batch membership metadata mirrored to Calling Backend
    category = db.Column(db.String(50), nullable=False, default='youth')
    is_default = db.Column(db.Boolean, nullable=False, default=False)
    status = db.Column(db.String(50), nullable=False, default='active')
    # Short code used to build standardised session ids ("2026-08-12 CTRA BG").
    # Assigned automatically the first time a session is created for this batch
    # (see _ensure_batch_code) and then STABLE — changing it changes the ids of
    # future sessions only, never existing ones. Unique per centre.
    short_code = db.Column(db.String(12), nullable=True)
    created_at = db.Column(db.DateTime, default=datetime.now)

    def to_dict(self):
        return {
            'id': self.id, 'name': self.name, 'centre_id': self.centre_id,
            'category': self.category or 'youth',
            'is_default': bool(self.is_default),
            'status': self.status or 'active',
            'short_code': self.short_code,
        }

class Centre(db.Model):
    __tablename__ = 'centres'
    centre_id = db.Column(db.String(100), primary_key=True)
    name = db.Column(db.String(150), nullable=False)
    status = db.Column(db.String(50), nullable=False, default='active')
    google_sheet_id = db.Column(db.String(200), nullable=True)
    google_sheet_enabled = db.Column(db.Boolean, nullable=False, default=False)
    # Youth/Congregation separation: youth is always on; this flag turns on the
    # second (congregation) track for a centre. When true, the registerer shows a
    # category toggle and the host shows a category selector.
    congregation_enabled = db.Column(db.Boolean, nullable=False, default=False)
    google_sheet_last_sync_at = db.Column(db.DateTime, nullable=True)
    google_sheet_last_sync_status = db.Column(db.String(100), nullable=True)
    google_sheet_last_error = db.Column(db.Text, nullable=True)
    # Short code used to build standardised session ids — see Batch.short_code.
    short_code = db.Column(db.String(8), nullable=True)
    created_at = db.Column(db.DateTime, default=datetime.now)
    updated_at = db.Column(db.DateTime, default=datetime.now, onupdate=datetime.now)

    def to_dict(self, include_sheet=False):
        """
        include_sheet=False REDACTS the Google Sheet key. Default is redacted on purpose.

        2026-08-17: /api/centres (GET) has no role check and served this dict to every logged-in
        registerer, handing out all three centres' google_sheet_id — and the app persisted them to
        an on-disk cache on every phone (AppData.kt ~1514). A sheet key IS the sheet whenever link
        sharing is on, and one centre's sheet was in fact world-readable, so this was a live route
        from "any registerer" to the full attendance history. Every OTHER caller of this method is
        already host-gated and passes include_sheet=True.

        google_sheet_enabled / _last_sync_at / _last_sync_status stay: they are status, not a key,
        and the host UI needs them. google_sheet_last_error is redacted with the id because Google's
        error strings routinely quote the spreadsheet id back.
        """
        data = {
            'centre_id': self.centre_id,
            'name': self.name,
            'status': self.status,
            'google_sheet_enabled': bool(self.google_sheet_enabled),
            'congregation_enabled': bool(self.congregation_enabled),
            'google_sheet_last_sync_at': self.google_sheet_last_sync_at.strftime("%Y-%m-%d %H:%M:%S") if self.google_sheet_last_sync_at else None,
            'google_sheet_last_sync_status': self.google_sheet_last_sync_status,
            'created_at': self.created_at.strftime("%Y-%m-%d %H:%M:%S") if self.created_at else None,
            'updated_at': self.updated_at.strftime("%Y-%m-%d %H:%M:%S") if self.updated_at else None
        }
        if include_sheet:
            data['google_sheet_id'] = self.google_sheet_id
            data['google_sheet_last_error'] = self.google_sheet_last_error
        return data

class SessionMapping(db.Model):
    __tablename__ = 'session_mappings'
    session_id = db.Column(db.String(150), primary_key=True)  # Sanitised sheet name (≤31 chars)
    # BUG-2 fix: store original full name separately so truncation is never exposed to users
    display_name = db.Column(db.String(255), nullable=True)
    batch_id = db.Column(db.Integer, db.ForeignKey('batches.id', ondelete='CASCADE'), nullable=False)
    centre_id = db.Column(db.String(100), nullable=False, default="centre_b_Base")
    # Phase 4: session metadata mirrored to Calling Backend
    category = db.Column(db.String(50), nullable=True)   # inherits from batch if null
    date = db.Column(db.String(20), nullable=True)
    status = db.Column(db.String(50), nullable=False, default='active')
    session_type = db.Column(db.String(20), nullable=False, default='ad_hoc')  # 'ad_hoc' | 'planned'
    scheduled_at = db.Column(db.DateTime, nullable=True)  # for planned sessions
    created_at = db.Column(db.DateTime, default=datetime.now)

    def to_dict(self):
        return {
            'session_id': self.session_id,
            'display_name': self.display_name or self.session_id,
            'batch_id': self.batch_id,
            'centre_id': self.centre_id,
            'category': self.category or 'youth',
            'date': self.date,
            'status': self.status or 'active',
            'session_type': self.session_type or 'ad_hoc',
            'scheduled_at': self.scheduled_at.isoformat() if self.scheduled_at else None,
        }

class AddressOption(db.Model):
    __tablename__ = 'address_options'
    id = db.Column(db.Integer, primary_key=True)
    text = db.Column(db.String(200), nullable=False)
    # Same rule as RemarkOption: NULL = shared by every centre.
    centre_id = db.Column(
        db.String(100), db.ForeignKey('centres.centre_id', ondelete='CASCADE'), nullable=True
    )
    __table_args__ = (
        db.Index('ux_address_options_centre_text', db.text("IFNULL(centre_id, '')"), 'text', unique=True),
    )

    def to_dict(self):
        return {'id': self.id, 'text': self.text, 'centre_id': self.centre_id,
                'is_shared': self.centre_id is None}

class Attendance(db.Model):
    __tablename__ = 'attendance'
    id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    mobile = db.Column(db.String(20), db.ForeignKey('users.mobile', ondelete='CASCADE'), nullable=False)
    date = db.Column(db.String(50), nullable=False)
    batch_name = db.Column(db.String(100), nullable=False)
    session_id = db.Column(db.String(150), nullable=False)
    centre_id = db.Column(db.String(100), nullable=False, default="centre_b_Base")
    remark = db.Column(db.String(255), nullable=True)
    rounds = db.Column(db.String(50), nullable=True)
    cheating = db.Column(db.String(10), nullable=True, default="No")
    # Youth/Congregation separation: denormalized copy of the batch/session
    # category, stamped at mark-time so recall/filter/export is a plain
    # WHERE category=? with no session->batch join. Batch stays the source of truth.
    category = db.Column(db.String(50), nullable=False, default='youth')
    timestamp = db.Column(db.DateTime, nullable=False, default=datetime.now)
    # These four are added to the TABLE by the migration list below and written by the
    # raw-SQL upsert in persist_attendance — but they were never declared on this model,
    # so the ORM could not read them back. Every path that re-submits an existing mark
    # (an operator correcting rounds/remark, the Android client retrying a mark that
    # already landed, an offline replay, a second device) reads `existing.capture_ts`
    # first and died with AttributeError -> HTTP 500. The submission is logged to
    # attendance_marks BEFORE that point, so the client's retry then collapsed to
    # "Already recorded" and the correction was silently dropped. Declared here so the
    # model matches the schema the migration actually creates.
    capture_ts = db.Column(db.String(40), nullable=True)
    device_id = db.Column(db.String(80), nullable=True)
    marked_by = db.Column(db.String(100), nullable=True)
    updated_at = db.Column(db.DateTime, nullable=True)

    def __init__(self, mobile, date, batch_name, session_id, remark=None, rounds=None, cheating="No", timestamp=None, centre_id="centre_b_Base", category="youth"):
        self.mobile = mobile
        self.date = date
        self.batch_name = batch_name
        self.session_id = session_id
        self.centre_id = centre_id
        self.category = (category or "youth").strip().lower()
        self.remark = remark
        self.rounds = rounds
        self.cheating = cheating
        self.timestamp = timestamp or datetime.now()

class AttendanceMark(db.Model):
    """Append-only record of every attendance submission the server ever accepted.

    `attendance` and this table do two different jobs, and making one table do both is what
    let the 2026-08-03 incident happen. `attendance` is the RESOLVED TRUTH: exactly one row
    per (mobile, session_id, date, centre_id), enforced by a unique index. This table is the
    EVENT LOG: every physical tap, every offline replay, every duplicate, stored verbatim and
    never overwritten.

    That split is what allows the two things a registerer needs at once — the input is always
    accepted (nothing is ever rejected, so an operator never fights the app mid-class), while
    the stored record still converges on the correct value rather than on whichever submission
    happened to arrive last.

    `applied` marks which submission currently wins resolution. A mark with applied=0 was
    recorded but superseded — almost always a stale offline replay that reached the server
    after a newer correction. Keeping it visible is the point: "who marked this, when, from
    which device, and which version won" becomes one query instead of an evening spent
    correlating server logs against device logs.
    """
    __tablename__ = 'attendance_marks'

    id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    # The resolution key — identical to the unique key on `attendance`.
    mobile = db.Column(db.String(20), nullable=False)
    session_id = db.Column(db.String(150), nullable=False)
    date = db.Column(db.String(50), nullable=False)
    centre_id = db.Column(db.String(100), nullable=False)

    category = db.Column(db.String(50), nullable=True)
    batch_name = db.Column(db.String(100), nullable=True)
    remark = db.Column(db.String(255), nullable=True)
    rounds = db.Column(db.String(50), nullable=True)
    cheating = db.Column(db.String(10), nullable=True)
    name = db.Column(db.String(100), nullable=True)
    address = db.Column(db.String(200), nullable=True)
    occupation = db.Column(db.String(100), nullable=True)

    # Who and what produced this mark. Several operators share one login, so the account name
    # alone cannot identify a source; device_id is what makes a row traceable.
    device_id = db.Column(db.String(80), nullable=True)
    marked_by = db.Column(db.String(100), nullable=True)

    # capture_ts is when the operator actually tapped, as reported by the client.
    # received_at is when it reached the server. These differ by hours for an offline replay,
    # and resolution keys off capture_ts precisely so that a late replay cannot overwrite a
    # correction that was made after it. Deriving from arrival order is the same mistake that
    # produced the wrong batch_id on 2026-08-03.
    capture_ts = db.Column(db.String(40), nullable=True)
    received_at = db.Column(db.DateTime, nullable=False, default=datetime.now)

    client_offline_id = db.Column(db.String(80), nullable=True)
    source = db.Column(db.String(40), nullable=True)      # live | offline_replay | register_student
    applied = db.Column(db.Boolean, nullable=False, default=False)
    superseded_reason = db.Column(db.String(120), nullable=True)
    attendance_id = db.Column(db.Integer, nullable=True)


class ParkedRegistration(db.Model):
    """A registration that arrived with nowhere valid to put it.

    Same reasoning as a PARKED attendance mark (see the ambiguity branch in
    mark_attendance): rejecting is not the safe option it looks like. The Android client
    treats any non-2xx as failure and re-queues a payload that will fail identically
    forever — a July dump still carries a registration at 62 attempts for exactly this
    reason — so a 400 does not protect the data, it strands it on somebody's phone.

    So the submission is accepted and held here, whole, with the device and operator that
    sent it, and returned as 200 + pending_batch. Nothing lands in the wrong batch, nothing
    is lost, and the record sits on the server where it can be drained the moment a default
    batch exists, instead of decaying on a device nobody can reach.

    The raw payload is kept verbatim in payload_json on purpose: whatever a future
    diagnosis needs is more likely to be in the original body than in the columns we
    happened to think of today.
    """
    __tablename__ = 'parked_registrations'

    id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    mobile = db.Column(db.String(20), nullable=False)
    name = db.Column(db.String(100), nullable=True)
    address = db.Column(db.String(200), nullable=True)
    occupation = db.Column(db.String(100), nullable=True)
    remark = db.Column(db.String(200), nullable=True)
    centre_id = db.Column(db.String(100), nullable=True)
    # Exactly as the client sent it — '' when the client sent none, which is itself the
    # diagnostic (a pre-v12 build that only sent category alongside mark_present).
    category = db.Column(db.String(50), nullable=True)
    requested_batch_id = db.Column(db.String(40), nullable=True)
    payload_json = db.Column(db.Text, nullable=True)
    device_id = db.Column(db.String(80), nullable=True)
    submitted_by = db.Column(db.String(100), nullable=True)
    user_agent = db.Column(db.String(300), nullable=True)
    reason = db.Column(db.String(120), nullable=True)
    status = db.Column(db.String(40), nullable=False, default='awaiting_batch')
    received_at = db.Column(db.DateTime, default=datetime.now)
    resolved_at = db.Column(db.DateTime, nullable=True)
    resolved_batch_id = db.Column(db.Integer, nullable=True)

    def to_dict(self):
        return {
            'id': self.id, 'mobile': self.mobile, 'name': self.name,
            'address': self.address, 'occupation': self.occupation, 'remark': self.remark,
            'centre_id': self.centre_id, 'category': self.category,
            'requested_batch_id': self.requested_batch_id,
            'device_id': self.device_id, 'submitted_by': self.submitted_by,
            'user_agent': self.user_agent, 'reason': self.reason, 'status': self.status,
            'received_at': self.received_at.strftime("%Y-%m-%d %H:%M:%S") if self.received_at else None,
            'resolved_at': self.resolved_at.strftime("%Y-%m-%d %H:%M:%S") if self.resolved_at else None,
            'resolved_batch_id': self.resolved_batch_id,
        }


class CentreRoster(db.Model):
    __tablename__ = 'centre_roster'
    __table_args__ = (
        db.UniqueConstraint('mobile', 'centre_id', 'scope_key', name='uq_centre_roster_scope'),
    )

    id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    mobile = db.Column(db.String(20), db.ForeignKey('users.mobile', ondelete='CASCADE'), nullable=False)
    centre_id = db.Column(db.String(100), db.ForeignKey('centres.centre_id', ondelete='CASCADE'), nullable=False)
    batch_id = db.Column(db.Integer, db.ForeignKey('batches.id', ondelete='SET NULL'), nullable=True)
    scope_key = db.Column(db.String(120), nullable=False, default='centre')
    batch_name = db.Column(db.String(100), nullable=True)
    name = db.Column(db.String(100), nullable=False)
    address = db.Column(db.String(200), nullable=True)
    occupation = db.Column(db.String(100), nullable=True)
    rounds = db.Column(db.String(50), nullable=True)
    remark = db.Column(db.String(200), nullable=True)
    cheating = db.Column(db.String(10), nullable=True, default="No")
    status = db.Column(db.String(50), nullable=False, default="active")
    # Track this person belongs to (youth/congregation). For a row that has a batch this
    # mirrors batches.category; for a centre-level row (batch_id IS NULL) it is the ONLY
    # record of the track, which is why the inject page always asks for it.
    # Deliberately nullable: the 1,525 pre-existing centre-level rows are left NULL so the
    # calling backend's roster reconcile skips them. Materialising that many people at once
    # would dump them into callers' queues as fresh never-called work — the 2026-08-01
    # incident at scale. Backfilling them is a separate, deliberate operation.
    category = db.Column(db.String(20), nullable=True)
    # Classes this person attended BEFORE they existed in this system, carried over from a
    # centre's previous records at import time. Deliberately not derived from and never mixed
    # into the `attendance` table: those rows are real dated marks, these are a bare total with
    # no dates behind it, and merging the two would invent attendance that was never recorded.
    # NULL means no legacy history was imported for this person — different from 0, which means
    # they were on the old list and had attended nothing.
    legacy_classes = db.Column(db.Integer, nullable=True)
    created_at = db.Column(db.DateTime, default=datetime.now)
    updated_at = db.Column(db.DateTime, default=datetime.now, onupdate=datetime.now)

    def __init__(
        self,
        mobile,
        centre_id,
        name,
        batch_id=None,
        batch_name=None,
        scope_key="centre",
        address=None,
        occupation=None,
        rounds=None,
        remark=None,
        cheating="No",
        status="active",
        category=None,
        legacy_classes=None,
    ):
        self.mobile = mobile
        self.centre_id = centre_id
        self.batch_id = batch_id
        self.batch_name = batch_name
        self.scope_key = scope_key or "centre"
        self.name = name or "Unknown"
        self.address = address
        self.occupation = occupation
        self.rounds = rounds
        self.remark = remark
        self.cheating = cheating
        self.status = status
        # This class defines an explicit __init__, which REPLACES SQLAlchemy's default
        # kwarg constructor — so a new column is invisible to callers until it is also
        # accepted and assigned here. Adding the column alone made every inject commit
        # raise "unexpected keyword argument 'category'". Add both, always.
        self.category = category
        self.legacy_classes = legacy_classes

    def to_dict(self):
        return {
            'id': self.id,
            'mobile': self.mobile,
            'centre_id': self.centre_id,
            'batch_id': self.batch_id,
            'batch_name': self.batch_name,
            'scope_key': self.scope_key,
            'name': self.name,
            'address': self.address,
            'occupation': self.occupation,
            'rounds': self.rounds,
            'remark': self.remark,
            'cheating': self.cheating,
            'status': self.status,
            'category': self.category,
            'legacy_classes': self.legacy_classes,
            'created_at': self.created_at.strftime("%Y-%m-%d %H:%M:%S") if self.created_at else None,
            'updated_at': self.updated_at.strftime("%Y-%m-%d %H:%M:%S") if self.updated_at else None,
        }

ATTENDANCE_EXCEL_HEADERS = [
    "Name",
    "Number",
    "Address",
    "Occupation",
    "Session",
    "Batch",
    "Rounds",
    "Remark",
    "Date",
]

class RegistererAccount(db.Model):
    __tablename__ = 'registerer_accounts'
    username = db.Column(db.String(100), primary_key=True)
    role = db.Column(db.String(50), default='registerer')
    status = db.Column(db.String(50), default='pending')
    created_at = db.Column(db.DateTime, default=datetime.now)

    def __init__(self, username, role='registerer', status='pending'):
        self.username = username
        self.role = role
        self.status = status
        self.created_at = datetime.now()

    def to_dict(self):
        return {
            'username': self.username,
            'role': self.role,
            'status': self.status,
            'created_at': self.created_at.strftime("%Y-%m-%d %H:%M:%S")
        }

class CentreAccess(db.Model):
    __tablename__ = 'centre_access'
    __table_args__ = (
        db.UniqueConstraint('username', 'centre_id', name='uq_centre_access_username_centre'),
    )

    id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    username = db.Column(db.String(100), nullable=False)
    display_name = db.Column(db.String(150), nullable=True)
    centre_id = db.Column(db.String(100), db.ForeignKey('centres.centre_id', ondelete='CASCADE'), nullable=False)
    role = db.Column(db.String(50), nullable=False, default='registerer')
    status = db.Column(db.String(50), nullable=False, default='active')
    can_create_batch = db.Column(db.Boolean, nullable=False, default=True)
    can_create_session = db.Column(db.Boolean, nullable=False, default=True)
    can_edit_attendance = db.Column(db.Boolean, nullable=False, default=True)
    can_delete_attendance = db.Column(db.Boolean, nullable=False, default=False)
    approved_by = db.Column(db.String(100), nullable=True)
    approved_at = db.Column(db.DateTime, nullable=True)
    last_seen_at = db.Column(db.DateTime, nullable=True)
    created_at = db.Column(db.DateTime, default=datetime.now)
    updated_at = db.Column(db.DateTime, default=datetime.now, onupdate=datetime.now)

    def to_dict(self):
        return {
            'id': self.id,
            'username': self.username,
            'display_name': self.display_name,
            'centre_id': self.centre_id,
            'role': self.role,
            'status': self.status,
            'can_create_batch': bool(self.can_create_batch),
            'can_create_session': bool(self.can_create_session),
            'can_edit_attendance': bool(self.can_edit_attendance),
            'can_delete_attendance': bool(self.can_delete_attendance),
            'approved_by': self.approved_by,
            'approved_at': self.approved_at.strftime("%Y-%m-%d %H:%M:%S") if self.approved_at else None,
            'last_seen_at': self.last_seen_at.strftime("%Y-%m-%d %H:%M:%S") if self.last_seen_at else None,
            'created_at': self.created_at.strftime("%Y-%m-%d %H:%M:%S") if self.created_at else None,
            'updated_at': self.updated_at.strftime("%Y-%m-%d %H:%M:%S") if self.updated_at else None
        }

class CentreChangeRequest(db.Model):
    __tablename__ = 'centre_change_requests'

    id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    username = db.Column(db.String(100), nullable=False)
    request_type = db.Column(db.String(50), nullable=False, default='centre_change')
    current_centre_id = db.Column(
        db.String(100),
        db.ForeignKey('centres.centre_id', ondelete='SET NULL'),
        nullable=True
    )
    requested_centre_id = db.Column(
        db.String(100),
        db.ForeignKey('centres.centre_id', ondelete='CASCADE'),
        nullable=False
    )
    status = db.Column(db.String(50), nullable=False, default='pending')
    reason = db.Column(db.Text, nullable=True)
    reviewed_by = db.Column(db.String(100), nullable=True)
    reviewed_at = db.Column(db.DateTime, nullable=True)
    created_at = db.Column(db.DateTime, default=datetime.now)
    updated_at = db.Column(db.DateTime, default=datetime.now, onupdate=datetime.now)

    def to_dict(self):
        return {
            'id': self.id,
            'username': self.username,
            'request_type': self.request_type,
            'current_centre_id': self.current_centre_id,
            'requested_centre_id': self.requested_centre_id,
            'status': self.status,
            'reason': self.reason,
            'reviewed_by': self.reviewed_by,
            'reviewed_at': self.reviewed_at.strftime("%Y-%m-%d %H:%M:%S") if self.reviewed_at else None,
            'created_at': self.created_at.strftime("%Y-%m-%d %H:%M:%S") if self.created_at else None,
            'updated_at': self.updated_at.strftime("%Y-%m-%d %H:%M:%S") if self.updated_at else None
        }

KANKARBAGH_CENTRE_ID = "centre_a_Base"
KANKARBAGH_CENTRE_NAME = "Centre A Base"
KANKARBAGH_CENTRE_GOOGLE_SHEET_ID = "1JyeVT6Yi5Shz_DwCcw8n9iRyF8iCOmJjl_9nB0jM8VU"
LEGACY_BAZAR_GOOGLE_SHEET_ID = KANKARBAGH_CENTRE_GOOGLE_SHEET_ID

# The spreadsheet every attendance row has actually been landing in since the
# system went live. It predates multi-centre support: sheets_handler.py had this
# ID hardcoded as SPREADSHEET_ID and the mirror path wrote to it unconditionally,
# with no idea which centre a row came from. It is Centre B's sheet in
# practice, so that is the centre it is now explicitly assigned to.
CENTRE_B_GOOGLE_SHEET_ID = os.environ.get("CENTRE_B_GOOGLE_SHEET_ID", "")

def get_selected_centre_id():
    return session.get("selected_centre_id") or DEFAULT_CENTRE_ID

def get_centre_name_by_id(centre_id):
    if not centre_id:
        return DEFAULT_CENTRE_NAME
    centre = Centre.query.filter_by(centre_id=centre_id).first()
    return centre.name if centre and centre.name else DEFAULT_CENTRE_NAME

def get_centre_display_name_by_id(centre_id):
    if not centre_id:
        return DEFAULT_CENTRE_NAME
    centre = Centre.query.filter_by(centre_id=centre_id).first()
    if centre and centre.name:
        return centre.name
    return centre_id or DEFAULT_CENTRE_NAME

def get_first_active_centre(exclude_centre_id=None):
    query = Centre.query.filter(Centre.status == 'active')
    if exclude_centre_id:
        query = query.filter(Centre.centre_id != exclude_centre_id)
    return query.order_by(Centre.name.asc(), Centre.centre_id.asc()).first()

def get_fallback_active_centre(exclude_centre_id=None):
    default_centre = Centre.query.filter_by(centre_id=DEFAULT_CENTRE_ID, status='active').first()
    if default_centre and default_centre.centre_id != exclude_centre_id:
        return default_centre
    return get_first_active_centre(exclude_centre_id=exclude_centre_id)

def get_active_centre_access_rows(username):
    if not username:
        return []
    return CentreAccess.query.filter_by(username=username, status='active').order_by(
        CentreAccess.created_at.asc(),
        CentreAccess.id.asc()
    ).all()

def get_primary_centre_access_context(username):
    # OPTIMIZED: Use JOIN instead of N+1 queries for better performance
    from sqlalchemy import and_
    try:
        access_centre_pairs = db.session.query(
            CentreAccess, Centre
        ).join(
            Centre,
            and_(
                CentreAccess.centre_id == Centre.centre_id,
                Centre.status == 'active'
            )
        ).filter(
            CentreAccess.username == username,
            CentreAccess.status == 'active'
        ).order_by(
            CentreAccess.created_at.asc(),
            CentreAccess.id.asc()
        ).all()
    except Exception as e:
        print(f"[ERROR] Failed to fetch centre access for {username}: {e}")
        return None, None, []

    allowed_centres = []
    primary_access = None
    primary_centre = None

    for access, centre in access_centre_pairs:
        if not primary_access:
            primary_access = access
            primary_centre = centre
        allowed_centres.append({
            'centre_id': centre.centre_id,
            'centre_name': centre.name
        })

    return primary_access, primary_centre, allowed_centres

def apply_centre_access_to_session(username, access, centre, allowed_centres=None):
    if not access or not centre:
        return None

    if access.role in ('host', 'centre_admin'):
        resolved_role = 'host'
    elif access.role == 'admin':
        resolved_role = 'admin'
    else:
        resolved_role = 'registerer'
    session['username'] = username
    session['role'] = resolved_role
    session['selected_centre_id'] = centre.centre_id
    session['selected_centre_name'] = centre.name
    if allowed_centres is not None:
        session['allowed_centres'] = allowed_centres
    return resolved_role


def centre_id_authorized(centre_id):
    """
    True if the current session may read/write this centre_id.
    Added 2026-07-04 — many registerer-facing endpoints resolved centre_id as
    `request.args.get('centre_id') or get_selected_centre_id()`, meaning a
    logged-in registerer could pass ANY other centre's centre_id and read (or
    in POST endpoints, write) that centre's data — nothing checked it against
    the caller's own CentreAccess. Host role stays cross-centre by design;
    every other role must have explicit access to the requested centre.
    """
    if not centre_id:
        return True  # nothing requested — caller's own default applies downstream
    if is_host_device() or session.get('role') == 'host':
        return True
    # JWT-authenticated requests (SRKB calling app, API clients) bypass the
    # session checks in restrict_access() entirely (app.py ~5510-5523) and never
    # populate session['allowed_centres'] / session['role'] — falling through to
    # the session-only checks below rejected every centre-scoped JWT request
    # with 403, which the Android client then mislabels as "check your connection".
    jwt_username = getattr(g, 'jwt_username', None)
    if jwt_username:
        jwt_role = getattr(g, 'jwt_role', None)
        if jwt_role in ('host', 'centre_admin'):
            return True
        _, _, jwt_allowed_centres = get_primary_centre_access_context(jwt_username)
        return centre_id in {c.get('centre_id') for c in jwt_allowed_centres}
    allowed = session.get('allowed_centres') or []
    allowed_ids = {c.get('centre_id') for c in allowed if isinstance(c, dict)}
    if centre_id in allowed_ids:
        return True
    # Covers sessions established before allowed_centres was populated.
    return centre_id == session.get('selected_centre_id')

def caller_is_host():
    """
    Host check that also understands JWT callers.

    The bare `is_host_device() or session.get('role') == 'host'` idiom used throughout this file
    predates JWT auth and is False for every Bearer-only caller, which is correct for the write
    endpoints (a phone should not create centres) but too blunt where a real host is driving the
    Android app. Mirrors the JWT branch of centre_id_authorized().
    """
    if is_host_device():
        return True

    jwt_username = getattr(g, 'jwt_username', None)
    if jwt_username:
        return getattr(g, 'jwt_role', None) == 'host'

    if session.get('role') != 'host':
        return False
    # Session role 'host' is NOT proof of a host: apply_centre_access_to_session() maps
    # CentreAccess role 'centre_admin' onto session role 'host' too, so a centre admin would
    # otherwise pass this check and be handed every centre's sheets and sheet keys. Same
    # disambiguation option_write_context() already uses — the CentreAccess row can tell them
    # apart when the session cannot. No rows carry 'centre_admin' in this database today (all 3
    # admins are stored as 'admin', which maps to session role 'admin'), so this closes a latent
    # hole rather than changing anyone's current access.
    roles = {a.role for a in get_active_centre_access_rows(session.get('username'))}
    # No CentreAccess rows at all is the built-in host login (ihost / physicalhost).
    return ('host' in roles) or (not roles)


def get_active_centre_by_google_sheet_id(google_sheet_id, exclude_centre_id=None):
    if not google_sheet_id:
        return None

    query = Centre.query.filter(
        Centre.status == "active",
        Centre.google_sheet_id == google_sheet_id
    )
    if exclude_centre_id:
        query = query.filter(Centre.centre_id != exclude_centre_id)
    return query.first()

def set_centre_google_sheet_config(centre, google_sheet_id, enabled, sync_status, last_error=None):
    centre.google_sheet_id = google_sheet_id
    centre.google_sheet_enabled = enabled
    centre.google_sheet_last_sync_status = sync_status
    centre.google_sheet_last_error = last_error
    return centre


# A client that has no date sends the *string* "null" (JSON.stringify of a JS
# null, or String(undefined)), which is truthy in Python. Every `data.get('date')
# or <fallback>` chain therefore accepted it and stored the word "null" in the
# date column — 21 rows landed that way before this was caught. Normalising the
# known placeholder spellings to "" makes those chains fall through to the real
# fallback, and makes the required-field checks on the bulk endpoints reject the
# row instead of persisting junk.
_PLACEHOLDER_DATE_VALUES = {"null", "none", "undefined", "nan", "nat", "-", "0000-00-00"}


def sanitize_date_input(value):
    """Return a trimmed date string, or "" if the client sent a placeholder."""
    text = str(value or "").strip()
    return "" if text.lower() in _PLACEHOLDER_DATE_VALUES else text


def _date_prefix_of(session_id):
    """The YYYY-MM-DD a generated session id starts with, or "" if it has none.

    Session ids built by _next_session_id look like "2026-08-15 CTRA BG", so the day the class
    was for is already encoded in the key. That is the only date some sessions have: the
    calling backend creates sessions without one, and every session predating the generated-id
    scheme carries a free-text name instead ("Bz 23rd March Evening"), which is deliberately
    NOT parsed here — guessing a date out of prose is how marks end up on the wrong day, and a
    missing date simply leaves the existing fallback chain in place.

    Validated rather than sliced, so a name that merely begins with ten digit-ish characters
    cannot produce a nonsense date.
    """
    text = str(session_id or "").strip()
    if len(text) < 10:
        return ""
    head = text[:10]
    try:
        datetime.strptime(head, "%Y-%m-%d")
    except ValueError:
        return ""
    return head


def resolve_session_date(session_id, centre_id):
    """The date a session is FOR, read from the session's own mapping row.

    The client's date header is NOT authoritative and must not be trusted for this.
    sync.js:175 fills #attendance-date with TODAY whenever a session is picked, and it
    only ever fills that field — it never resets it (see the long comment at
    registerer.html:1519, which documents why writing a session's real date into the
    header was tried and reverted: the value goes sticky across sessions).

    The consequence, which that fix left standing: marking YESTERDAY's session TODAY
    files the mark under today. One session's attendance then splits across two dates,
    and since /api/show_attendance and /api/live_count both filter by date, no single
    view shows all of it. Verified 2026-08-16 — session '2026-08-15 CTRA BG' ended up
    with 2 rows dated 08-16 and 4 dated 08-15, and the operator reported the marks
    "disappearing" because the view he was on could only ever show one of the two sets.

    Fixing it in the header is what failed before. The session itself already knows the
    day it is for, so resolve it here and file against that instead. Returns "" when
    there is no mapping (an ad-hoc session that predates mappings), leaving every
    existing fallback in the caller's chain untouched.
    """
    if not session_id:
        return ""
    try:
        mapping = SessionMapping.query.filter_by(
            session_id=session_id, centre_id=centre_id
        ).first()
    except Exception:
        # A lookup failure must never block a mark being recorded; fall back to the
        # caller's existing chain rather than losing the tap.
        return ""
    return sanitize_date_input(mapping.date) if mapping else ""


# Accepts either a bare spreadsheet key or a full browser URL such as
# https://docs.google.com/spreadsheets/d/<KEY>/edit#gid=0 — the host pastes a
# link, not an ID, so requiring a bare key just invites silent misconfiguration.
_GOOGLE_SHEET_URL_RE = re.compile(r'/spreadsheets/d/([a-zA-Z0-9_-]+)')


def parse_google_sheet_id(value):
    """Normalise a pasted Google Sheet link (or bare ID) to a spreadsheet key."""
    text = str(value or "").strip()
    if not text:
        return None
    match = _GOOGLE_SHEET_URL_RE.search(text)
    if match:
        return match.group(1)
    # A bare key: Google keys are URL-safe base64-ish and always long. Anything
    # containing a slash or space is a malformed URL, not a key.
    if '/' in text or ' ' in text:
        return None
    return text


def get_google_sheet_id_for_centre(centre_id):
    """The spreadsheet a centre mirrors into, or None if it has none configured.

    Returning None means "do NOT sync this row to Google Sheets" — deliberately
    NOT "fall back to the default sheet". Falling back to one shared default is
    exactly what broke centre isolation: sheets_handler.SPREADSHEET_ID was used
    for every centre, so the moment a second centre started marking attendance
    its rows piled into the first centre's spreadsheet (reported 2026-07-28:
    Centre A rows appearing in the Centre B master list).

    The local SQLite DB remains the source of truth, so a centre with no sheet
    configured loses nothing — its rows can be backfilled once a sheet is linked.
    """
    cid = str(centre_id or "").strip()
    if not cid:
        return None
    try:
        centre = Centre.query.filter_by(centre_id=cid).first()
    except Exception as e:
        write_log(f"Google Sheet routing lookup failed for centre '{cid}': {e}")
        return None
    if not centre or not centre.google_sheet_id or not bool(centre.google_sheet_enabled):
        return None
    return str(centre.google_sheet_id).strip() or None

def ensure_default_centre():
    try:
        centre = db.session.get(Centre, DEFAULT_CENTRE_ID)
        if not centre:
            # Fresh install: the default centre owns the original spreadsheet.
            db.session.add(Centre(
                centre_id=DEFAULT_CENTRE_ID,
                name=DEFAULT_CENTRE_NAME,
                status="active",
                google_sheet_id=CENTRE_B_GOOGLE_SHEET_ID,
                google_sheet_enabled=True,
                google_sheet_last_sync_status="configured",
            ))
            db.session.commit()
        else:
            changed = False
            # LEGACY_BAZAR_GOOGLE_SHEET_ID is Centre A's key, which was once
            # wrongly recorded here. Correct it to the sheet this centre's rows
            # actually go to rather than blanking the config (blanking now means
            # "stop syncing", which would silently cut this centre off).
            if centre.google_sheet_id == LEGACY_BAZAR_GOOGLE_SHEET_ID:
                centre.google_sheet_id = CENTRE_B_GOOGLE_SHEET_ID
                centre.google_sheet_enabled = True
                centre.google_sheet_last_sync_status = "configured"
                centre.google_sheet_last_error = None
                changed = True
            # Otherwise the sheet config is the host's to set via
            # /api/centres/<id>/sheet — startup must not fight it.
            if changed:
                db.session.commit()
    except Exception as e:
        db.session.rollback()
        write_log(f"Default centre seed skipped/failed: {e}")

def ensure_centre_a_centre():
    try:
        centre = db.session.get(Centre, KANKARBAGH_CENTRE_ID)
        if not centre:
            # Seed the centre only. Its Google Sheet is deliberately NOT set
            # here — see the note below.
            db.session.add(Centre(
                centre_id=KANKARBAGH_CENTRE_ID,
                name=KANKARBAGH_CENTRE_NAME,
                status="active",
                google_sheet_id=None,
                google_sheet_enabled=False,
                google_sheet_last_sync_status="not_configured",
            ))
            db.session.commit()
            return

        changed = False
        if not centre.name:
            centre.name = KANKARBAGH_CENTRE_NAME
            changed = True
        if not centre.status:
            centre.status = "active"
            changed = True

        # This function used to force centre.google_sheet_id back to the
        # hardcoded KANKARBAGH_CENTRE_GOOGLE_SHEET_ID on EVERY startup, which
        # made the sheet unconfigurable: anything the host set was reverted on
        # the next restart. That constant also pointed at a spreadsheet nothing
        # ever actually wrote to, since the mirror path ignored per-centre
        # config entirely. Sheet linking now belongs to the host, via
        # /api/centres/<id>/sheet — so clear the stale value once and then leave
        # the config alone.
        if centre.google_sheet_id == KANKARBAGH_CENTRE_GOOGLE_SHEET_ID:
            centre.google_sheet_id = None
            centre.google_sheet_enabled = False
            centre.google_sheet_last_sync_status = "not_configured"
            centre.google_sheet_last_error = None
            changed = True
            write_log(
                f"Cleared stale hardcoded Google Sheet ID from {KANKARBAGH_CENTRE_ID}; "
                f"link a sheet from the host dashboard."
            )

        if changed:
            db.session.commit()
    except Exception as e:
        db.session.rollback()
        write_log(f"Centre A centre seed skipped/failed: {e}")

def ensure_centre_google_sheet_columns():
    from sqlalchemy import text

    def table_columns(table_name):
        rows = db.session.execute(text(f'PRAGMA table_info("{table_name}")')).fetchall()
        return {row[1] for row in rows}

    migrations = [
        ("centres", "google_sheet_id", 'ALTER TABLE centres ADD COLUMN google_sheet_id VARCHAR(200)'),
        ("centres", "google_sheet_enabled", 'ALTER TABLE centres ADD COLUMN google_sheet_enabled BOOLEAN DEFAULT 0'),
        ("centres", "google_sheet_last_sync_at", 'ALTER TABLE centres ADD COLUMN google_sheet_last_sync_at DATETIME'),
        ("centres", "google_sheet_last_sync_status", 'ALTER TABLE centres ADD COLUMN google_sheet_last_sync_status VARCHAR(100)'),
        ("centres", "google_sheet_last_error", 'ALTER TABLE centres ADD COLUMN google_sheet_last_error TEXT'),
    ]

    for table_name, column_name, alter_sql in migrations:
        try:
            columns = table_columns(table_name)
            if column_name not in columns:
                db.session.execute(text(alter_sql))
                db.session.commit()
        except Exception as e:
            db.session.rollback()
            write_log(f"Centre google sheet migration skipped/failed for {table_name}.{column_name}: {e}")

def ensure_concurrent_marking_schema():
    """Schema for safe concurrent marking: resolution columns, then the unique index.

    ORDER MATTERS, and not only within this function. The unique index is the *enforcement*
    of a guarantee `persist_attendance` must already provide. Creating it while the old
    read-then-write duplicate check was still in place would have converted a silent bug into
    a loud outage: an ordinary offline replay re-sending a mark would raise IntegrityError and
    surface as a 500 to an operator mid-class. It is only safe here because the upsert path
    ships in the same file.

    The index is what makes the write genuinely race-free rather than merely careful. Without
    it, `query(...).first()` followed by `add()` has a window between the read and the write in
    which a second request reads the same "not found" — which is exactly how attendance ids
    7584 and 7585 were both created for 7033000022 on 2026-08-03, 74 ms apart. With it, the
    second writer hits ON CONFLICT and updates instead of inserting.
    """
    from sqlalchemy import text

    def table_columns(table_name):
        rows = db.session.execute(text(f'PRAGMA table_info("{table_name}")')).fetchall()
        return {row[1] for row in rows}

    migrations = [
        # Which submission currently resolves this row, and where it came from. NULL on every
        # historical row, which is correct — those predate device tracking and must not be
        # made to look as though they carry it.
        ("attendance", "capture_ts", 'ALTER TABLE attendance ADD COLUMN capture_ts VARCHAR(40)'),
        ("attendance", "device_id", 'ALTER TABLE attendance ADD COLUMN device_id VARCHAR(80)'),
        ("attendance", "marked_by", 'ALTER TABLE attendance ADD COLUMN marked_by VARCHAR(100)'),
        ("attendance", "updated_at", 'ALTER TABLE attendance ADD COLUMN updated_at DATETIME'),
    ]

    for table_name, column_name, alter_sql in migrations:
        try:
            if column_name not in table_columns(table_name):
                db.session.execute(text(alter_sql))
                db.session.commit()
        except Exception as e:
            db.session.rollback()
            write_log(f"Concurrent-marking migration skipped/failed for {table_name}.{column_name}: {e}")

    # Helpful, non-unique indexes. `attendance` had NO indexes of any kind before this.
    for idx_sql in (
        'CREATE INDEX IF NOT EXISTS ix_attendance_lookup '
        'ON attendance (centre_id, session_id, date)',
        'CREATE INDEX IF NOT EXISTS ix_attendance_marks_key '
        'ON attendance_marks (mobile, session_id, date, centre_id)',
        # A replayed offline item carries the id it was queued under, so this makes a repeat
        # delivery of the SAME physical tap a no-op rather than a second logged event.
        'CREATE UNIQUE INDEX IF NOT EXISTS uq_attendance_marks_offline_id '
        'ON attendance_marks (client_offline_id) WHERE client_offline_id IS NOT NULL',
    ):
        try:
            db.session.execute(text(idx_sql))
            db.session.commit()
        except Exception as e:
            db.session.rollback()
            write_log(f"Index creation skipped/failed ({idx_sql.split()[5]}): {e}")

    # The one that matters. Report failure loudly rather than crashing the server: if a
    # duplicate somehow survives, the upsert path still prevents new ones, but the guarantee
    # is then unenforced and somebody needs to know that rather than find out later.
    try:
        db.session.execute(text(
            'CREATE UNIQUE INDEX IF NOT EXISTS uq_attendance_person_session '
            'ON attendance (mobile, session_id, date, centre_id)'
        ))
        db.session.commit()
    except Exception as e:
        db.session.rollback()
        dupes = db.session.execute(text(
            'SELECT COUNT(*) FROM (SELECT 1 FROM attendance '
            'GROUP BY mobile, session_id, date, centre_id HAVING COUNT(*) > 1)'
        )).scalar()
        write_log(
            f"CRITICAL: could not create uq_attendance_person_session ({e}). "
            f"{dupes} duplicate group(s) remain in `attendance`. Duplicate marking is NOT "
            f"enforced at the database level until these are collapsed."
        )


def ensure_centre_mapping_columns():
    from sqlalchemy import text

    def table_columns(table_name):
        rows = db.session.execute(text(f'PRAGMA table_info("{table_name}")')).fetchall()
        return {row[1] for row in rows}

    migrations = [
        ("batches", "centre_id", 'ALTER TABLE batches ADD COLUMN centre_id VARCHAR(100)'),
        ("session_mappings", "centre_id", 'ALTER TABLE session_mappings ADD COLUMN centre_id VARCHAR(100)'),
        ("attendance", "centre_id", 'ALTER TABLE attendance ADD COLUMN centre_id VARCHAR(100)'),
        # BUG-2: store original session name before sheet-name truncation
        ("session_mappings", "display_name", 'ALTER TABLE session_mappings ADD COLUMN display_name VARCHAR(255)'),
        # Phase 4: batch membership metadata for Calling Backend mirror
        ("batches", "category", "ALTER TABLE batches ADD COLUMN category VARCHAR(50) DEFAULT 'youth'"),
        ("batches", "is_default", "ALTER TABLE batches ADD COLUMN is_default BOOLEAN DEFAULT 0"),
        ("batches", "status", "ALTER TABLE batches ADD COLUMN status VARCHAR(50) DEFAULT 'active'"),
        ("session_mappings", "category", "ALTER TABLE session_mappings ADD COLUMN category VARCHAR(50) DEFAULT NULL"),
        ("session_mappings", "date", "ALTER TABLE session_mappings ADD COLUMN date VARCHAR(20) DEFAULT NULL"),
        ("session_mappings", "status", "ALTER TABLE session_mappings ADD COLUMN status VARCHAR(50) DEFAULT 'active'"),
        # Youth/Congregation separation: denormalized category on each attendance
        # row; per-centre flag to turn on the congregation track.
        ("attendance", "category", "ALTER TABLE attendance ADD COLUMN category VARCHAR(50) DEFAULT 'youth'"),
        ("centres", "congregation_enabled", "ALTER TABLE centres ADD COLUMN congregation_enabled BOOLEAN DEFAULT 0"),
    ]

    for table_name, column_name, alter_sql in migrations:
        try:
            columns = table_columns(table_name)
            if column_name not in columns:
                db.session.execute(text(alter_sql))
                db.session.commit()
                columns = table_columns(table_name)

            if column_name in columns:
                db.session.execute(
                    text(
                        f'UPDATE {table_name} '
                        'SET centre_id = :default_centre '
                        "WHERE centre_id IS NULL OR TRIM(COALESCE(centre_id, '')) = ''"
                    ),
                    {"default_centre": DEFAULT_CENTRE_ID},
                )
                db.session.commit()
        except Exception as e:
            db.session.rollback()
            write_log(f"Centre mapping migration skipped/failed for {table_name}: {e}")

def ensure_centre_access_table():
    try:
        CentreAccess.__table__.create(bind=db.engine, checkfirst=True)
    except Exception as e:
        db.session.rollback()
        write_log(f"CentreAccess table init skipped/failed: {e}")

def ensure_centre_change_request_table():
    try:
        CentreChangeRequest.__table__.create(bind=db.engine, checkfirst=True)
    except Exception as e:
        db.session.rollback()
        write_log(f"CentreChangeRequest table init skipped/failed: {e}")

def ensure_registerer_password_columns():
    """Additive migration: add password fields to registerer_accounts if missing."""
    import sqlite3 as _sqlite3
    db_path = app.config['SQLALCHEMY_DATABASE_URI'].replace('sqlite:///', '')
    conn = None
    try:
        conn = _sqlite3.connect(db_path)
        cur = conn.cursor()
        cur.execute("PRAGMA table_info(registerer_accounts)")
        existing = {row[1] for row in cur.fetchall()}
        additions = []
        if 'password_hash' not in existing:
            additions.append("ALTER TABLE registerer_accounts ADD COLUMN password_hash TEXT")
        if 'password_updated_at' not in existing:
            additions.append("ALTER TABLE registerer_accounts ADD COLUMN password_updated_at DATETIME")
        if 'must_change_password' not in existing:
            additions.append("ALTER TABLE registerer_accounts ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT 1")
        if 'plain_password' not in existing:
            additions.append("ALTER TABLE registerer_accounts ADD COLUMN plain_password TEXT")
        for stmt in additions:
            conn.execute(stmt)
        conn.commit()
        if additions:
            write_log(f"ensure_registerer_password_columns: added {len(additions)} column(s)")
    except Exception as e:
        write_log(f"ensure_registerer_password_columns failed: {e}")
    finally:
        if conn:
            conn.close()

def ensure_option_centre_scope():
    """
    Per-centre remark and address lists.

    Both tables shipped global — `UNIQUE (text)` and no centre column — so one centre adding an
    address changed what every other centre saw. Each row now carries a nullable `centre_id`, where
    NULL means "shared by every centre". Every existing row is backfilled to NULL, so nothing
    changes for anyone on day one; a centre only ever gains rows of its own.

    SQLite cannot drop a constraint, so the tables are rebuilt per the SQLite manual's ordering.
    The replacement uniqueness is an expression index over IFNULL(centre_id, ''), NOT a plain
    UNIQUE(centre_id, text): SQLite treats NULLs as distinct inside a UNIQUE, so the plain form
    would silently permit duplicates in the shared list that the old schema forbade.

    Idempotent — a table that already has `centre_id` is left alone, and a fresh database gets the
    right shape from db.create_all() before this ever runs.
    """
    import sqlite3 as _sqlite3
    db_path = app.config['SQLALCHEMY_DATABASE_URI'].replace('sqlite:///', '')

    specs = [
        ('remark_options', 'VARCHAR(100)', 'ux_remark_options_centre_text'),
        ('address_options', 'VARCHAR(200)', 'ux_address_options_centre_text'),
    ]

    conn = None
    try:
        conn = _sqlite3.connect(db_path)
        conn.isolation_level = None  # manage BEGIN/COMMIT ourselves; PRAGMA cannot run inside one
        for table, text_type, index_name in specs:
            existing = {row[1] for row in conn.execute(f"PRAGMA table_info({table})").fetchall()}
            if not existing or 'centre_id' in existing:
                continue

            before = conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
            conn.execute("PRAGMA foreign_keys=OFF")
            conn.execute("BEGIN")
            try:
                conn.execute(f"""
                    CREATE TABLE {table}__new (
                        id INTEGER NOT NULL,
                        text {text_type} NOT NULL,
                        centre_id VARCHAR(100),
                        PRIMARY KEY (id),
                        FOREIGN KEY(centre_id) REFERENCES centres (centre_id) ON DELETE CASCADE
                    )
                """)
                conn.execute(
                    f"INSERT INTO {table}__new (id, text, centre_id) SELECT id, text, NULL FROM {table}"
                )
                conn.execute(f"DROP TABLE {table}")
                conn.execute(f"ALTER TABLE {table}__new RENAME TO {table}")
                conn.execute(
                    f"CREATE UNIQUE INDEX {index_name} ON {table} (IFNULL(centre_id, ''), text)"
                )
                conn.execute("COMMIT")
            except Exception:
                conn.execute("ROLLBACK")
                raise
            finally:
                conn.execute("PRAGMA foreign_keys=ON")

            after = conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
            scoped = conn.execute(
                f"SELECT COUNT(*) FROM {table} WHERE centre_id IS NOT NULL"
            ).fetchone()[0]
            write_log(
                f"ensure_option_centre_scope: {table} rebuilt, rows {before}->{after}, "
                f"centre-scoped {scoped} (expected 0)"
            )
    except Exception as e:
        write_log(f"ensure_option_centre_scope failed: {e}")
    finally:
        if conn:
            conn.close()


def get_request_centre_id():
    """
    Which centre this request belongs to, for endpoints that predate centres.

    An explicit `centre_id` wins. A JWT client (the Android app) resolves to its own primary centre
    rather than to `get_selected_centre_id()`, which reads a Flask session the JWT path never
    populates and would otherwise hand every app user the default centre's list.
    """
    explicit = (request.args.get('centre_id') or '').strip()
    if not explicit and request.method in ('POST', 'PUT', 'DELETE'):
        body = request.get_json(silent=True) or {}
        explicit = str(body.get('centre_id') or '').strip()
    if explicit:
        return explicit

    jwt_username = getattr(g, 'jwt_username', None)
    if jwt_username:
        _, primary_centre, _ = get_primary_centre_access_context(jwt_username)
        if primary_centre:
            return primary_centre.centre_id
    return get_selected_centre_id()


def option_write_context():
    """
    (is_host, centre_id) for a request that wants to change an option list.

    `is_host` is None when the request may not write at all. The distinction matters because
    apply_centre_access_to_session() collapses `centre_admin` into session role 'host', so the
    session alone cannot tell a real host from a centre admin — the CentreAccess row can.
    A centre admin owns their own centre's rows; only a host may touch a shared one.
    """
    centre_id = get_request_centre_id()

    if is_host_device():
        return True, centre_id

    jwt_username = getattr(g, 'jwt_username', None)
    if jwt_username:
        jwt_role = getattr(g, 'jwt_role', None)
        if jwt_role == 'host':
            return True, centre_id
        # 'admin' and 'centre_admin' are the SAME role under two spellings. The databases store
        # 'admin' (registerer_accounts.role, centre_access.role — verified: every centre admin in
        # this system is stored as 'admin', none as 'centre_admin'), while this gate only ever
        # accepted 'centre_admin'. So every centre admin fell through to `return None` and was told
        # they had no access at all — reading option lists worked, adding to one never did. SRKB's
        # own sync handler already treats the two spellings as equal; this is the other half of it.
        if jwt_role in ('centre_admin', 'admin'):
            return False, centre_id
        return None, centre_id

    # Web-session login. apply_centre_access_to_session() maps CentreAccess role 'centre_admin' to
    # session role 'host', but 'admin' to session role 'admin' — so the same person hits a different
    # branch depending only on which spelling their row happens to carry. Both mean centre admin.
    session_role = session.get('role')
    if session_role in ('host', 'admin'):
        roles = {a.role for a in get_active_centre_access_rows(session.get('username'))}
        # No CentreAccess rows at all is the built-in host login (ihost / physicalhost), which has
        # no centre and is not a centre admin.
        if session_role == 'host' and ('host' in roles or not roles):
            return True, centre_id
        return False, centre_id

    return None, centre_id


def authorize_option_write(row=None):
    """
    Shared gate for the six option-list write routes.

    Returns (error_response, status, is_host, centre_id); error_response is None when allowed.
    Editing a shared row as a centre admin is REFUSED with an explanation rather than silently
    no-op'd or silently forked, so the admin knows to add their own instead.
    """
    is_host, centre_id = option_write_context()
    if is_host is None:
        return jsonify({"error": "Unauthorized"}), 403, None, None
    if not is_host:
        if not centre_id or not centre_id_authorized(centre_id):
            return jsonify({"error": "Forbidden"}), 403, None, None
        if row is not None:
            if row.centre_id is None:
                return jsonify({
                    "error": "This is a shared option used by every centre, so only the host can "
                             "change it. Add your own to this centre's list instead."
                }), 403, None, None
            if row.centre_id != centre_id:
                return jsonify({"error": "Forbidden"}), 403, None, None
    return None, 200, is_host, centre_id


def ensure_legacy_registerers_have_default_centre_access():
    try:
        default_centre = Centre.query.filter_by(centre_id=DEFAULT_CENTRE_ID).first()
        if not default_centre:
            return

        approved_accounts = RegistererAccount.query.filter_by(status='approved').all()
        excluded_usernames = {'ihost', 'physicalhost', 'host'}
        created_count = 0
        reactivated_count = 0
        updated_count = 0

        for account in approved_accounts:
            username = (account.username or '').strip()
            if not username or username.lower() in excluded_usernames:
                continue

            access = CentreAccess.query.filter_by(username=username, centre_id=DEFAULT_CENTRE_ID).first()
            if not access:
                db.session.add(CentreAccess(
                    username=username,
                    display_name=username,
                    centre_id=DEFAULT_CENTRE_ID,
                    role='registerer',
                    status='active',
                    can_create_batch=True,
                    can_create_session=True,
                    can_edit_attendance=True,
                    can_delete_attendance=False,
                    approved_by='system_migration',
                    approved_at=datetime.now()
                ))
                created_count += 1
                continue

            changed = False
            if access.status != 'active':
                access.status = 'active'
                reactivated_count += 1
                changed = True
            if not (access.display_name or '').strip():
                access.display_name = username
                changed = True
            # Preserve intentionally elevated roles; only fix invalid/unexpected values
            if access.role not in ('registerer', 'admin', 'host', 'centre_admin'):
                access.role = 'registerer'
                changed = True
            if not bool(access.can_create_batch):
                access.can_create_batch = True
                changed = True
            if not bool(access.can_create_session):
                access.can_create_session = True
                changed = True
            if not bool(access.can_edit_attendance):
                access.can_edit_attendance = True
                changed = True
            if bool(access.can_delete_attendance):
                access.can_delete_attendance = False
                changed = True
            if changed:
                updated_count += 1

        if created_count or reactivated_count or updated_count:
            db.session.commit()
            write_log(
                f"Legacy registerer backfill ensured {created_count} created, "
                f"{reactivated_count} reactivated, {updated_count} updated for {DEFAULT_CENTRE_ID}"
            )
    except Exception as e:
        db.session.rollback()
        write_log(f"Legacy registerer backfill skipped/failed: {e}")

def write_log(msg):
    timestamp = datetime.now().strftime("[%Y-%m-%d %H:%M:%S]")
    print(f"{timestamp} {msg}")
    try:
        with open(LOG_PATH, 'a', encoding='utf-8') as f:
            f.write(f"{timestamp} {msg}\n")
    except:
        pass

@event.listens_for(Engine, "connect")
def set_sqlite_pragma(dbapi_connection, connection_record):
    cursor = dbapi_connection.cursor()
    cursor.execute("PRAGMA foreign_keys=ON")
    cursor.close()


def ae_last_ten(value):
    """Last ten digits of a mobile - the ONLY correct identity key for a person here.

    centre_roster does not normalise the mobile column at write time, so the same person can be
    stored as "+91 98350 00001" and as "9835000001". Anything that counts people (the registration
    card, the day-sheet list, the day-sheet contents) must key on this, or two numbers on the same
    screen disagree about the same people.
    """
    digits = ''.join(ch for ch in (value or '') if ch.isdigit())
    return digits[-10:] if len(digits) >= 10 else digits


class RegistrationSheetName(db.Model):
    """A human-chosen label for one day's registrations at one centre, in one track.

    Display name ONLY. The identity of a day-sheet is (centre_id, category, day); renaming must
    never move a person between days or touch centre_roster. That is the whole point - the ask was
    to rename the sheet precisely so the session/batch relationships underneath stay untouched.
    """
    __tablename__ = 'registration_sheet_names'
    __table_args__ = (
        db.UniqueConstraint('centre_id', 'category', 'day', name='uq_registration_sheet_name'),
    )

    id = db.Column(db.Integer, primary_key=True, autoincrement=True)
    centre_id = db.Column(db.String(100), nullable=False)
    category = db.Column(db.String(20), nullable=False)
    day = db.Column(db.String(10), nullable=False)          # 'YYYY-MM-DD', or 'unknown'
    name = db.Column(db.String(120), nullable=False)
    updated_at = db.Column(db.DateTime, default=datetime.now, onupdate=datetime.now)


# Initialize database & defaults
with app.app_context():
    db.create_all()
    # Must run before the first ORM read of either option table: the models now select
    # `centre_id`, which an un-migrated database does not have.
    ensure_option_centre_scope()
    # Initial defaults for remarks
    if not RemarkOption.query.first():
        try:
            defaults = ["Present", "Left Early", "Came Late", "Sick", "No Show"]
            for d in defaults:
                db.session.add(RemarkOption(text=d))
            db.session.commit()
            print("[+] Remark defaults initialized.")
        except Exception as e:
            db.session.rollback()
            print(f"[-] Failed to initialize remarks: {e}")
    ensure_centre_google_sheet_columns()
    ensure_default_centre()
    ensure_centre_a_centre()
    ensure_centre_mapping_columns()
    # After the mapping columns: this one indexes `attendance`, so it must run once that
    # table has reached its final shape.
    ensure_concurrent_marking_schema()
    ensure_centre_access_table()
    ensure_legacy_registerers_have_default_centre_access()
    ensure_centre_change_request_table()
    ensure_registerer_password_columns()
    # Label any roster row a bulk import left blank, while the centre still runs one class and
    # the answer is knowable. Runs last: it reads batches, which must have reached final shape.
    try:
        backfill_roster_categories(actor="system:startup")
        backfill_roster_categories_from_batch(actor="system:startup")
    except Exception as exc:
        # Never let a housekeeping pass stop the server from coming up.
        print(f"[roster-category] startup pass skipped: {exc}")

@app.route('/api/centres', methods=['GET'])
def api_get_centres():
    try:
        centres = Centre.query.filter(Centre.status == 'active').order_by(Centre.name.asc()).all()
        # Sheet key only for a host. This endpoint is deliberately NOT host-gated — every
        # registerer needs the centre list to work — so the redaction happens per field.
        include_sheet = caller_is_host()
        return jsonify([c.to_dict(include_sheet=include_sheet) for c in centres])
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/api/centres', methods=['POST'])
def api_create_centre():
    if not is_host_device() and session.get('role') != 'host':
        return jsonify({'ok': False, 'message': 'Unauthorized'}), 403

    data = request.json or {}
    centre_id = (data.get('centre_id') or '').strip()
    name = (data.get('name') or '').strip()
    google_sheet_id = (data.get('google_sheet_id') or '').strip() or None
    google_sheet_enabled_raw = data.get('google_sheet_enabled')
    if google_sheet_enabled_raw is None:
        google_sheet_enabled = bool(google_sheet_id)
    else:
        google_sheet_enabled = str(google_sheet_enabled_raw).strip().lower() in {'1', 'true', 'yes', 'on'}
    congregation_enabled = str(data.get('congregation_enabled') or '').strip().lower() in {'1', 'true', 'yes', 'on'}

    if not centre_id:
        return jsonify({'ok': False, 'message': 'centre_id is required'}), 400
    if not name:
        return jsonify({'ok': False, 'message': 'name is required'}), 400

    existing = Centre.query.filter_by(centre_id=centre_id).first()
    if existing:
        return jsonify({'ok': False, 'message': 'Centre ID already exists'}), 409

    if google_sheet_id:
        conflict = Centre.query.filter(Centre.google_sheet_id == google_sheet_id).first()
        if conflict:
            return jsonify({
                'ok': False,
                'message': f'Google Sheet ID already assigned to centre {conflict.centre_id}'
            }), 409

    centre = Centre(
        centre_id=centre_id,
        name=name,
        status='active',
        google_sheet_id=google_sheet_id,
        google_sheet_enabled=google_sheet_enabled,
        congregation_enabled=congregation_enabled,
        google_sheet_last_sync_status='configured' if google_sheet_id and google_sheet_enabled else 'not_configured',
        google_sheet_last_error=None
    )
    db.session.add(centre)
    try:
        db.session.commit()
        enqueue_storage_sync_job(
            "ensure_centre_folder",
            {
                "centre_id": centre.centre_id,
                "centre_name": centre.name,
                "status": centre.status,
            }
        )
        enqueue_excel_output_refresh(
            "refresh_centre_master_excel",
            {
                "centre_id": centre.centre_id,
            }
        )
        _fire_webhook('centre.created', 'centre', centre.centre_id, {
            'centre_id': centre.centre_id, 'name': centre.name, 'status': centre.status,
        })
        return jsonify({'ok': True, 'centre': centre.to_dict(include_sheet=True)})
    except Exception as e:
        db.session.rollback()
        return jsonify({'ok': False, 'message': str(e)}), 500

@app.route('/api/centres/<centre_id>/categories', methods=['POST'])
def api_set_centre_categories(centre_id):
    """Youth/Congregation: turn the congregation track on/off for a centre.

    Youth is always on. When congregation is enabled the registerer shows a
    category toggle and the host shows a category selector. Disabling does not
    delete congregation data — it only hides the toggle/selector.
    """
    if not is_host_device() and session.get('role') != 'host':
        return jsonify({'ok': False, 'message': 'Unauthorized'}), 403
    centre = Centre.query.filter_by(centre_id=centre_id).first()
    if not centre:
        return jsonify({'ok': False, 'message': 'Centre not found'}), 404
    data = request.json or {}
    centre.congregation_enabled = str(data.get('congregation_enabled')).strip().lower() in {'1', 'true', 'yes', 'on'}
    try:
        db.session.commit()
        return jsonify({'ok': True, 'centre': centre.to_dict(include_sheet=True)})
    except Exception as e:
        db.session.rollback()
        return jsonify({'ok': False, 'message': str(e)}), 500

@app.route('/api/centres/<centre_id>/sheet', methods=['POST'])
def api_set_centre_google_sheet(centre_id):
    """Link (or unlink) the Google Sheet a centre's attendance mirrors into.

    Before this existed a centre's sheet could only be set at creation time,
    which is why the live centres were mis-linked with no way to correct them.
    Accepts a pasted spreadsheet URL or a bare key.
    """
    if not is_host_device() and session.get('role') != 'host':
        return jsonify({'ok': False, 'message': 'Unauthorized'}), 403

    centre = Centre.query.filter_by(centre_id=centre_id).first()
    if not centre:
        return jsonify({'ok': False, 'message': 'Centre not found'}), 404

    data = request.json or {}
    raw_value = data.get('google_sheet_id')
    enabled_raw = data.get('google_sheet_enabled')

    # An explicitly empty value means "unlink this centre" — that centre then
    # stops mirroring to Sheets entirely rather than falling back to anyone
    # else's spreadsheet.
    if raw_value is None or not str(raw_value).strip():
        set_centre_google_sheet_config(centre, None, False, 'not_configured', None)
        try:
            db.session.commit()
        except Exception as e:
            db.session.rollback()
            return jsonify({'ok': False, 'message': str(e)}), 500
        return jsonify({
            'ok': True,
            'message': f'{centre.name} unlinked from Google Sheets',
            'centre': centre.to_dict(include_sheet=True)
        })

    sheet_id = parse_google_sheet_id(raw_value)
    if not sheet_id:
        return jsonify({
            'ok': False,
            'message': 'Could not read a Google Sheet ID from that value. Paste the '
                       'full sheet link or just the ID.'
        }), 400

    # Two centres sharing one spreadsheet is the exact failure being fixed here,
    # so it is rejected outright rather than warned about.
    conflict = Centre.query.filter(
        Centre.google_sheet_id == sheet_id,
        Centre.centre_id != centre_id
    ).first()
    if conflict:
        return jsonify({
            'ok': False,
            'message': f'That sheet is already linked to centre "{conflict.name}". '
                       f'Each centre needs its own sheet.'
        }), 409

    if enabled_raw is None:
        enabled = True
    else:
        enabled = str(enabled_raw).strip().lower() in {'1', 'true', 'yes', 'on'}

    set_centre_google_sheet_config(
        centre, sheet_id, enabled,
        'configured' if enabled else 'not_configured', None
    )
    try:
        db.session.commit()
    except Exception as e:
        db.session.rollback()
        return jsonify({'ok': False, 'message': str(e)}), 500

    write_log(f"Host linked centre {centre_id} to Google Sheet {sheet_id} (enabled={enabled})")
    _fire_webhook('centre.sheet_linked', 'centre', centre.centre_id, {
        'centre_id': centre.centre_id,
        'google_sheet_id': sheet_id,
        'google_sheet_enabled': enabled,
    })
    return jsonify({
        'ok': True,
        'message': f'{centre.name} linked to its Google Sheet',
        'centre': centre.to_dict(include_sheet=True)
    })


@app.route('/api/centres/select', methods=['POST'])
def api_select_centre():
    if not is_host_device() and session.get('role') != 'host':
        return jsonify({'ok': False, 'message': 'Unauthorized'}), 403

    data = request.json or {}
    centre_id = (data.get('centre_id') or '').strip()
    if not centre_id:
        return jsonify({'ok': False, 'message': 'centre_id required'}), 400

    centre = Centre.query.filter_by(centre_id=centre_id).first()
    if not centre or centre.status != 'active':
        return jsonify({'ok': False, 'message': 'Centre not found or inactive'}), 404

    session['selected_centre_id'] = centre.centre_id
    session['selected_centre_name'] = centre.name
    session.pop('active_session_id', None)
    session.pop('active_session_sheet', None)
    set_active_session(centre.centre_id, {
        'centre_id': centre.centre_id,
        'centre_name': centre.name,
        'session_id': None,
        'batch_id': None,
        'batch_name': None,
        'date': None,
        'sheet_name': None,
    })
    return jsonify({
        'ok': True,
        'centre_id': centre.centre_id,
        'centre_name': centre.name,
        'message': 'Centre selected',
        'active_session_cleared': True
    })

@app.route('/api/centres/<centre_id>/rename', methods=['POST'])
def api_rename_centre(centre_id):
    if not is_host_device() and session.get('role') != 'host':
        return jsonify({'ok': False, 'message': 'Unauthorized'}), 403

    data = request.json or {}
    new_name = (data.get('name') or '').strip()
    if not new_name:
        return jsonify({'ok': False, 'message': 'name is required'}), 400

    centre = Centre.query.filter_by(centre_id=centre_id).first()
    if not centre:
        return jsonify({'ok': False, 'message': 'Centre not found'}), 404

    centre.name = new_name
    try:
        db.session.commit()
    except Exception as e:
        db.session.rollback()
        return jsonify({'ok': False, 'message': str(e)}), 500

    if session.get('selected_centre_id') == centre_id:
        session['selected_centre_name'] = new_name
    if get_active_session().get('centre_id') == centre_id:
        get_active_session()['centre_name'] = new_name
    allowed_centres = session.get('allowed_centres')
    if isinstance(allowed_centres, list):
        for item in allowed_centres:
            if isinstance(item, dict) and item.get('centre_id') == centre_id:
                item['centre_name'] = new_name
        session['allowed_centres'] = allowed_centres

    enqueue_storage_sync_job(
        "ensure_centre_folder",
        {
            "centre_id": centre_id,
            "centre_name": new_name,
            "status": centre.status,
        }
    )
    enqueue_excel_output_refresh(
        "refresh_centre_master_excel",
        {
            "centre_id": centre_id,
        }
    )
    _fire_webhook('centre.renamed', 'centre', centre_id, {
        'centre_id': centre_id, 'new_name': new_name,
    })

    return jsonify({'ok': True, 'centre': centre.to_dict(include_sheet=True)})

@app.route('/api/centres/<centre_id>/delete', methods=['POST'])
def api_delete_centre(centre_id):
    if not is_host_device() and session.get('role') != 'host':
        return jsonify({'ok': False, 'message': 'Unauthorized'}), 403

    if centre_id == DEFAULT_CENTRE_ID:
        return jsonify({
            'ok': False,
            'message': 'Default centre cannot be deactivated'
        }), 400

    centre = Centre.query.filter_by(centre_id=centre_id).first()
    if not centre:
        return jsonify({'ok': False, 'message': 'Centre not found'}), 404

    selected_centre_id = session.get('selected_centre_id') or get_active_session().get('centre_id')
    fallback_centre = None
    if selected_centre_id == centre_id:
        fallback_centre = get_fallback_active_centre(exclude_centre_id=centre_id)
        if not fallback_centre:
            return jsonify({'ok': False, 'message': 'No active centre available for fallback'}), 400

    if centre.status != 'inactive':
        centre.status = 'inactive'
        try:
            db.session.commit()
        except Exception as e:
            db.session.rollback()
            return jsonify({'ok': False, 'message': str(e)}), 500

    enqueue_storage_sync_job(
        "ensure_centre_folder",
        {
            "centre_id": centre_id,
            "centre_name": centre.name,
            "status": centre.status,
        }
    )
    enqueue_excel_output_refresh(
        "refresh_centre_master_excel",
        {
            "centre_id": centre_id,
        }
    )

    if selected_centre_id == centre_id:
        session['selected_centre_id'] = fallback_centre.centre_id
        session['selected_centre_name'] = fallback_centre.name
        session.pop('active_session_id', None)
        session.pop('active_session_sheet', None)
        set_active_session(fallback_centre.centre_id, {
            'centre_id': fallback_centre.centre_id,
            'centre_name': fallback_centre.name,
            'session_id': None,
            'batch_id': None,
            'batch_name': None,
            'date': None,
            'sheet_name': None,
        })

    _fire_webhook('centre.status_changed', 'centre', centre_id, {
        'centre_id': centre_id, 'new_status': 'inactive',
    })
    return jsonify({
        'ok': True,
        'centre_id': centre_id,
        'centre_name': centre.name,
        'status': 'inactive',
        'fallback_centre_id': fallback_centre.centre_id if fallback_centre else None,
        'fallback_centre_name': fallback_centre.name if fallback_centre else None,
        'message': 'Centre deactivated'
    })

@app.route('/api/centre-access', methods=['GET'])
def api_get_centre_access():
    if not is_host_device() and session.get('role') != 'host':
        return jsonify({'ok': False, 'message': 'Unauthorized'}), 403

    centre_id = (request.args.get('centre_id') or get_selected_centre_id()).strip()
    centre = Centre.query.filter_by(centre_id=centre_id).first()
    if not centre:
        return jsonify({'ok': False, 'message': 'Centre not found'}), 404

    rows = CentreAccess.query.filter_by(centre_id=centre_id, role='registerer').all()
    rows = sorted(
        rows,
        key=lambda row: (
            row.status != 'active',
            row.created_at or datetime.min,
            row.id or 0
        )
    )
    return jsonify({
        'ok': True,
        'centre_id': centre.centre_id,
        'centre_name': centre.name,
        'registerers': [row.to_dict() for row in rows]
    })

def get_google_sheet_metadata(spreadsheet_id):
    """Open a Google Sheet by ID and return safe metadata only."""
    from sheets_handler import get_sheets_client

    client = get_sheets_client()
    spreadsheet = client.open_by_key(spreadsheet_id)
    tabs = [ws.title for ws in spreadsheet.worksheets()]
    return spreadsheet.title, tabs

@app.route('/api/centres/<centre_id>/test-sheet', methods=['POST'])
def api_test_centre_google_sheet(centre_id):
    if not is_host_device() and session.get('role') != 'host':
        return jsonify({
            'ok': False,
            'centre_id': centre_id,
            'message': 'Unauthorized. Only the host can test centre Google Sheets.'
        }), 403

    try:
        centre = Centre.query.filter_by(centre_id=centre_id).first()
        if not centre:
            return jsonify({
                'ok': False,
                'centre_id': centre_id,
                'message': 'Centre not found'
            }), 404

        if not bool(centre.google_sheet_enabled):
            return jsonify({
                'ok': False,
                'centre_id': centre_id,
                'centre_name': centre.name,
                'message': 'Google Sheet is not enabled for this centre',
                'error': 'google_sheet_enabled is false'
            }), 400

        if not centre.google_sheet_id:
            return jsonify({
                'ok': False,
                'centre_id': centre_id,
                'centre_name': centre.name,
                'message': 'Google Sheet is not configured for this centre',
                'error': 'google_sheet_id is missing'
            }), 400

        spreadsheet_title = None
        tabs = []
        try:
            spreadsheet_title, tabs = get_google_sheet_metadata(centre.google_sheet_id)
            centre.google_sheet_last_sync_at = datetime.now()
            centre.google_sheet_last_sync_status = 'tested'
            centre.google_sheet_last_error = None
            db.session.commit()
            return jsonify({
                'ok': True,
                'centre_id': centre.centre_id,
                'centre_name': centre.name,
                'google_sheet_id': centre.google_sheet_id,
                'message': 'Google Sheet connection successful',
                'spreadsheet_title': spreadsheet_title,
                'tabs': tabs
            })
        except Exception as sheet_error:
            safe_error = str(sheet_error)[:1000]
            try:
                centre.google_sheet_last_sync_status = 'test_failed'
                centre.google_sheet_last_error = safe_error
                db.session.commit()
            except Exception:
                db.session.rollback()
            return jsonify({
                'ok': False,
                'centre_id': centre.centre_id,
                'centre_name': centre.name,
                'google_sheet_id': centre.google_sheet_id,
                'message': 'Google Sheet connection failed',
                'error': safe_error
            }), 500
    except Exception as e:
        db.session.rollback()
        return jsonify({
            'ok': False,
            'centre_id': centre_id,
            'message': 'Google Sheet connection failed',
            'error': str(e)[:1000]
        }), 500

def sync_attendance_to_excel(ws, data_row):
    """
    Appends a row to the worksheet in long format (transactional/log style).
    data_row: dict containing Name, Number, Address, Occupation, Session, Batch, Rounds, Remark, Date
    """
    headers = ATTENDANCE_EXCEL_HEADERS

    # Initialize headers if the sheet header is missing or incorrect.
    current_headers = []
    if ws.max_row > 0:
        current_headers = [str(cell.value).strip().lower() if cell.value is not None else "" for cell in ws[1]]

    if current_headers[:len(headers)] != [h.lower() for h in headers]:
        if ws.max_row > 0:
            ws.delete_rows(1, ws.max_row)
        for c, h in enumerate(headers, 1):
            ws.cell(row=1, column=c, value=h)

    # Prepare row data from dict in the same order as the headers.
    row = [
        data_row.get('name', 'Unknown'),
        str(data_row.get('number') or data_row.get('mobile', '')),
        data_row.get('address', ''),
        data_row.get('occupation', ''),
        data_row.get('session', ''),
        data_row.get('batch', ''),
        data_row.get('rounds', ''),
        data_row.get('remark', ''),
        data_row.get('date', '')
    ]
    
    # Only if it is not already there.
    #
    # This used to append unconditionally, and the reconciler was the only thing in the
    # system that checked. That left a race the two of them lost together on 2026-08-09:
    # the reconciler ran mid-burst, backfilled the 9 marks the live writer had not reached
    # yet, and then the live writer appended those 9 again on arrival — 14 marks, 23 rows
    # in the session sheet. A mark is unique on (mobile, date, session) — the DB carries
    # exactly that unique index — so a second row for the same three values is never a
    # second attendance, only a second copy of one.
    new_mob = str(row[1] or '').strip()
    new_dt = str(row[8] or '').strip()[:10]
    new_sess = str(row[4] or '').strip()
    new_day = _coerced_session_day(new_sess)
    if new_mob:
        for existing in ws.iter_rows(min_row=2, values_only=True):
            if not existing or not any(existing):
                continue
            cells = (list(existing) + [None]*9)[:9]
            if str(cells[1] or '').strip() != new_mob:
                continue
            if str(cells[8] or '').strip()[:10] != new_dt:
                continue
            old_sess = str(cells[4] or '').strip()
            # Same session under its own name, or under the datetime Excel coerced that
            # name into. An empty session cell on either side cannot disambiguate, so a
            # mobile+date match is taken as the same mark.
            if (old_sess == new_sess
                    or not old_sess or not new_sess
                    or (new_day and _coerced_session_day(old_sess) == new_day)):
                return

    # Append the new transactional row
    ws.append(row)

def append_attendance_to_live_workbook(data_row, sheet_name=None):
    """
    Write a single attendance row directly into the live workbook.
    This is the reliable path used by attendance submission.
    """
    if os.name == "nt":
        payload = {
            "path": LIVE_FILE,
            "sheet_name": sheet_name,
            "row": data_row,
        }
        payload_path = None
        try:
            with tempfile.NamedTemporaryFile("w", delete=False, suffix=".json", encoding="utf-8") as tmp:
                json.dump(payload, tmp, ensure_ascii=False)
                payload_path = tmp.name

            ps_script = r"""
param([string]$PayloadPath)
$payload = Get-Content -Raw -LiteralPath $PayloadPath | ConvertFrom-Json
$Path = $payload.path
$SheetName = $payload.sheet_name
$Row = $payload.row
# Ownership flags for the try/finally at the bottom. We only ever close a
# workbook THIS script opened and only quit an Excel THIS script created, so a
# workbook a human has open on this machine is never touched.
$OwnsExcel = $false
$OwnsWb = $false
try {
    $excel = [Runtime.InteropServices.Marshal]::GetActiveObject('Excel.Application')
} catch {
    $excel = New-Object -ComObject Excel.Application
    # This script created the instance, so this script must end it. The old
    # behaviour deliberately left it resident for reuse, which is exactly what
    # strands a windowless EXCEL.EXE still holding the workbook open whenever
    # this script dies before its close (most often the caller's 30s timeout).
    # Excel is launched by DCOM, NOT as a child of PowerShell, so killing our
    # process tree does not take it with us. That orphan holds an exclusive
    # handle on the .xlsx and blocks every later write to it, permanently --
    # the confirmed root cause of the recurring attendance data-loss reports.
    # Reuse was a speed optimisation on a background worker; correctness wins.
    # See excel_lock_guard.py for the runtime backstop that cleans up any
    # orphan a hard kill still manages to leave behind.
    $OwnsExcel = $true
}
$excel.Visible = $false
$excel.DisplayAlerts = $false
$wb = $null
foreach ($w in $excel.Workbooks) {
    if ($w.FullName -ieq $Path) { $wb = $w; break }
}
if (-not $wb) {
    $wb = $excel.Workbooks.Open($Path)
    $OwnsWb = $true
}
try {
$Headers = @('Name','Number','Address','Occupation','Session','Batch','Rounds','Remark','Date')
# A cell that Excel coerced to a date comes back from Value2 as an OLE Automation serial
# NUMBER, not as the text that was written. Comparing raw values would therefore never
# match a coerced row against the string it came from, which is how duplicates survived
# unnoticed. Everything is normalised to yyyy-MM-dd (or plain trimmed text) before compare.
function Norm-Day($v) {
    if ($null -eq $v) { return '' }
    if ($v -is [double]) { return [DateTime]::FromOADate($v).ToString('yyyy-MM-dd') }
    if ($v -is [DateTime]) { return $v.ToString('yyyy-MM-dd') }
    $s = ([string]$v).Trim()
    if ($s -eq '') { return '' }
    $parsed = [DateTime]::MinValue
    if ([DateTime]::TryParse($s, [ref]$parsed)) { return $parsed.ToString('yyyy-MM-dd') }
    # Anything that is not a date is returned WHOLE. An earlier version truncated to the
    # first 10 characters here, which is harmless for dates and destructive for session
    # names: "B.G EVENING" became "B.G EVENIN", so two sessions sharing a long prefix
    # would have compared equal and one class's marks could have suppressed another's.
    return $s
}
# Mobile numbers stored in a numeric cell come back as doubles; F0 keeps all 10 digits
# instead of letting them render as 9.23479505E+09.
function Norm-Mobile($v) {
    if ($null -eq $v) { return '' }
    if ($v -is [double]) { return $v.ToString('F0') }
    return ([string]$v).Trim()
}
function Write-Row($ws, $rowData) {
    if ([string]::IsNullOrEmpty($ws.Cells(1,1).Value2)) {
        $headerRange = $ws.Range($ws.Cells(1,1), $ws.Cells(1,9))
        $headerRange.Value2 = $Headers
    }
    $last = $ws.Cells($ws.Rows.Count, 1).End(-4162).Row
    if ($last -lt 1) { $last = 1 }

    # Never write a mark that is already on this sheet. This path used to append blind,
    # so when the self-healing reconciler backfilled a row a moment before the live write
    # arrived, both copies stayed. Keyed on mobile + date + session + batch: batch is what
    # keeps a youth and a congregation class held on the SAME date from collapsing into
    # one another, since both sessions can be date-named.
    if ($last -ge 2) {
        $mobWanted   = Norm-Mobile ([string]$rowData.mobile)
        $dayWanted   = Norm-Day ([string]$rowData.date)
        $sessWanted  = Norm-Day ([string]$rowData.session)
        $batchWanted = ([string]$rowData.batch).Trim()
        $block = $ws.Range($ws.Cells(2,1), $ws.Cells($last,9)).Value2
        $rowCount = $block.GetLength(0)
        for ($i = 1; $i -le $rowCount; $i++) {
            if ((Norm-Mobile $block[$i,2]) -ne $mobWanted) { continue }
            if ((Norm-Day $block[$i,9]) -ne $dayWanted) { continue }
            $sessHere  = Norm-Day $block[$i,5]
            $batchHere = ([string]$block[$i,6]).Trim()
            $sessSame  = ($sessHere -eq $sessWanted) -or ($sessHere -eq '') -or ($sessWanted -eq '')
            $batchSame = ($batchHere -eq $batchWanted) -or ($batchHere -eq '') -or ($batchWanted -eq '')
            if ($sessSame -and $batchSame) { return }
        }
    }

    $next = $last + 1
    $values = @(
        [string]$rowData.name,
        [string]$rowData.mobile,
        [string]$rowData.address,
        [string]$rowData.occupation,
        [string]$rowData.session,
        [string]$rowData.batch,
        [string]$rowData.rounds,
        [string]$rowData.remark,
        [string]$rowData.date
    )
    $range = $ws.Range($ws.Cells($next,1), $ws.Cells($next,9))
    # Force the whole row to TEXT before assigning. Value2 hands the string to Excel's
    # own parser, and Excel silently converts anything date-shaped into a real date: a
    # session named "9 Aug 2026" came back out of the workbook as the datetime
    # 2026-08-09 00:00:00. reconcile_live_workbook_from_db keys existing rows on the
    # STRING session_id, so that coerced cell never matched its own DB row and the
    # reconciler appended a second copy of all 14 marks on 2026-08-09.
    #
    # Text format also keeps mobile numbers intact — 10 digits was one step away from
    # being rendered in scientific notation, and a leading zero would have been dropped.
    $range.NumberFormat = "@"
    $range.Value2 = $values
}
Write-Row $wb.Worksheets.Item('MASTER_ALL_SESSIONS') $Row
if ($SheetName) {
    try {
        $ws = $wb.Worksheets.Item($SheetName)
    } catch {
        $ws = $wb.Worksheets.Add()
        $ws.Name = $SheetName
    }
    Write-Row $ws $Row
}
$wb.Save()
# Close the workbook (but leave the Excel.Application COM server itself
# running for reuse) so it is never left holding a stale in-memory copy —
# without this, GetActiveObject() above kept reusing THIS SAME workbook
# object indefinitely across every future write, and any change made via a
# different path (the openpyxl fallback, manual edits, etc.) was invisible
# to it; the next COM save then overwrote the file with this stale copy,
# silently discarding those other changes. This was confirmed live: rows
# that logged [SUCCESS] here were nonetheless missing from disk.
$wb.Close($false)
} finally {
    # Runs on success, on any terminating error, and on an early `return` -- so a
    # workbook this script opened can never be left open inside a surviving,
    # windowless EXCEL.EXE. (A hard process kill still bypasses this; that
    # residual case is what excel_lock_guard.py detects and clears at runtime.)
    if ($OwnsWb -and $wb) { try { $wb.Close($false) } catch {} }
    if ($OwnsExcel -and $excel) {
        try { $excel.Quit() } catch {}
        try { [Runtime.InteropServices.Marshal]::ReleaseComObject($excel) | Out-Null } catch {}
    }
}
"""
            ps_path = None
            with tempfile.NamedTemporaryFile("w", delete=False, suffix=".ps1", encoding="utf-8") as tmp_ps:
                tmp_ps.write(ps_script)
                ps_path = tmp_ps.name

            _run_com_helper(
                ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ps_path, "-PayloadPath", payload_path],
                check=True,
                # NOTE (2026-06-29 wedge fix): use DEVNULL, NOT capture_output/PIPE.
                # When this PowerShell helper blocks inside a COM call to a Excel
                # that a human has open, capture_output's post-timeout pipe drain
                # (communicate) can deadlock PAST timeout=30, which froze the single
                # excel_sync_worker thread and silently dropped every later record.
                # DEVNULL has no pipe to drain, so the timeout reliably fires and the
                # except-> openpyxl fallback below runs instead of wedging forever.
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=30,
                **_hidden_process_kwargs(),
            )
            return
        except Exception as e:
            write_log(f"Excel COM sync failed, falling back to openpyxl: {e}")
            _after_com_failure(e, LIVE_FILE, "attendance_append")
        finally:
            if 'ps_path' in locals() and ps_path and os.path.exists(ps_path):
                try:
                    os.remove(ps_path)
                except Exception:
                    pass
            if payload_path and os.path.exists(payload_path):
                try:
                    os.remove(payload_path)
                except Exception:
                    pass

    with EXCEL_WRITE_LOCK:
        if os.path.exists(LIVE_FILE):
            wb = load_workbook(LIVE_FILE)
        else:
            wb = Workbook()
            if wb.active:
                wb.remove(wb.active)

        try:
            master_sheet = "MASTER_ALL_SESSIONS"
            if master_sheet not in wb.sheetnames:
                ws_master = wb.create_sheet(title=master_sheet, index=0)
            else:
                ws_master = wb[master_sheet]
            sync_attendance_to_excel(ws_master, data_row)

            if sheet_name:
                if sheet_name not in wb.sheetnames:
                    ws_session = wb.create_sheet(title=sheet_name)
                else:
                    ws_session = wb[sheet_name]
                sync_attendance_to_excel(ws_session, data_row)

            save_workbook_safely(wb, LIVE_FILE, reason="attendance_append")
        finally:
            wb.close()


def _coerced_session_day(value):
    """The calendar day a session cell collapsed to, as YYYY-MM-DD — or None.

    Excel parses whatever Value2 hands it, so a session NAMED like a date ("9 Aug 2026")
    is stored as a real datetime and reads back as 2026-08-09 00:00:00, not as the
    session_id the row was written under. The reconciler keys existing rows on the exact
    session string, so that coerced cell stopped matching its own DB row and a second
    copy of every mark was appended — 14 marks became 28 in MASTER_ALL_SESSIONS on
    2026-08-09.

    Reducing both sides to a plain day makes them comparable without rewriting either.
    Returns None when the value is not date-shaped ("B.G EVENING", "session_42"), because
    those never coerced and must keep matching on their exact string — collapsing them
    would merge genuinely different sessions.
    """
    if value is None:
        return None
    if isinstance(value, datetime):
        return value.strftime('%Y-%m-%d')
    if isinstance(value, date):
        return value.strftime('%Y-%m-%d')
    text = str(value).strip()
    if not text:
        return None
    for fmt in ('%Y-%m-%d %H:%M:%S', '%Y-%m-%d %H:%M', '%Y-%m-%d',
                '%d %b %Y', '%d %B %Y', '%b %d %Y', '%B %d %Y'):
        try:
            return datetime.strptime(text, fmt).strftime('%Y-%m-%d')
        except ValueError:
            continue
    return None


_EMPTY_SHEET_XML = (
    b'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    b'<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData/></worksheet>'
)


def _salvage_corrupt_workbook(path, err):
    """Open an unreadable live workbook, keeping every sheet that can still be read.

    Called by the reconcile when load_workbook fails. Without it the reconcile just
    logs the error every minute while every Lists tab and download stays blank.

    Two saves overlapping leave the zip with a second copy of its tail after the
    real end, and some parts garbled. This cuts at the first end-of-archive record,
    turns each unreadable part into an empty sheet and then drops the empty
    sheets. The reconcile recreates those sheets (MASTER_ALL_SESSIONS included)
    from the database with a proper header row. If nothing can be read, it starts
    a blank workbook, and the reconcile rebuilds it all from the database.

    The broken file is always copied aside first as .corrupt_<timestamp>.
    """
    import io, struct, zipfile
    ts = datetime.now().strftime('%Y%m%d_%H%M%S')
    with open(path, 'rb') as f:
        raw = f.read()
    backup = f"{path}.corrupt_{ts}"
    with open(backup, 'wb') as f:
        f.write(raw)
    write_log(f"Workbook unreadable ({err}); copied to {backup} and salvaging readable sheets")
    try:
        end = raw.find(b'PK\x05\x06')
        if end != -1 and len(raw) >= end + 22:
            comment_len = struct.unpack('<H', raw[end + 20:end + 22])[0]
            raw = raw[:end + 22 + comment_len]
        src = zipfile.ZipFile(io.BytesIO(raw))
        buf = io.BytesIO()
        stubbed = []
        with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as out:
            for info in src.infolist():
                try:
                    data = src.read(info.filename)
                except Exception:
                    data = _EMPTY_SHEET_XML
                    stubbed.append(info.filename)
                out.writestr(info.filename, data)
        wb = load_workbook(io.BytesIO(buf.getvalue()))
        dropped = []
        if stubbed:
            for ws in list(wb.worksheets):
                if ws.max_row <= 1 and ws.max_column <= 1 and ws.cell(1, 1).value is None:
                    dropped.append(ws.title)
                    wb.remove(ws)
        write_log(f"Workbook salvage: kept {len(wb.sheetnames)} sheets; unreadable parts {stubbed}; "
                  f"rebuilding from database: {dropped}")
        return wb
    except Exception as salvage_err:
        write_log(f"Workbook salvage failed ({salvage_err}); rebuilding the whole workbook from the database")
        wb = Workbook()
        if wb.active:
            wb.remove(wb.active)
        return wb


_workbook_ok_stamp = None


def ensure_live_workbook_readable():
    """Repair the live workbook NOW if it cannot be read. Call before reading it.

    The Lists tab, file list and downloads all read live_attendance_master.xlsx,
    and each one turns a read error into an empty answer — so a broken file
    blanks the app for everyone (2026-09-28, 17:35 to 22:08). The minute worker
    repairs it too, but a user should not see a blank screen while waiting.

    A zip CRC check of the whole file is a few milliseconds, and the result is
    remembered per (mtime, size), so it only runs again after the file changes.
    Never raises: at worst the caller hits the same error it would have hit.
    """
    global _workbook_ok_stamp
    try:
        st = os.stat(LIVE_FILE)
    except OSError:
        return
    stamp = (st.st_mtime_ns, st.st_size)
    if stamp == _workbook_ok_stamp:
        return
    import zipfile
    try:
        with zipfile.ZipFile(LIVE_FILE) as z:
            broken = z.testzip()
    except OSError:
        return  # locked by a writer, not broken
    except Exception as e:
        broken = str(e)
    if broken is None:
        _workbook_ok_stamp = stamp
        return
    write_log(f"Live workbook unreadable ({broken}); repairing before serving the request")
    try:
        with app.app_context():
            reconcile_live_workbook_from_db()
    except Exception as e:
        write_log(f"Live workbook on-demand repair failed: {e}")


def reconcile_live_workbook_from_db(target_session_id=None, target_centre_id=None):
    """
    Durable Self-Healing Sync: Reconciles live_attendance_master.xlsx against
    database/attendance.db (the single source of truth).

    Ensures that every attendance row committed in SQLite exists in both:
      1. MASTER_ALL_SESSIONS sheet
      2. The specific session sheet (e.g. B.G EVENING)
    
    This guarantees zero data loss and prevents in-memory queue drops / process
    restarts from creating discrepancies between SQLite and Excel.
    """
    with EXCEL_WRITE_LOCK:
        try:
            db_path = app.config['SQLALCHEMY_DATABASE_URI'].replace('sqlite:///', '')
            import sqlite3 as _sqlite3
            conn = _sqlite3.connect(db_path)
            conn.row_factory = _sqlite3.Row

            where_clauses = []
            params = []
            if target_session_id:
                where_clauses.append("a.session_id = ?")
                params.append(target_session_id)
            if target_centre_id:
                where_clauses.append("a.centre_id = ?")
                params.append(target_centre_id)
            
            where_sql = (" WHERE " + " AND ".join(where_clauses)) if where_clauses else ""

            sql = f"""
                SELECT a.id, a.mobile, a.date, a.batch_name, a.session_id,
                       a.centre_id, a.rounds, a.remark,
                       u.name, u.address, u.occupation
                FROM attendance a
                LEFT JOIN users u ON u.mobile = a.mobile
                {where_sql}
                ORDER BY a.id ASC
            """
            db_rows = conn.execute(sql, params).fetchall()
            conn.close()

            if not db_rows:
                return 0

            salvaged = False
            if os.path.exists(LIVE_FILE):
                try:
                    wb = load_workbook(LIVE_FILE)
                except OSError:
                    raise  # locked, not broken: the lock guard's business
                except Exception as load_err:
                    wb = _salvage_corrupt_workbook(LIVE_FILE, load_err)
                    salvaged = True
            else:
                wb = Workbook()
                if wb.active:
                    wb.remove(wb.active)

            master_sheet = "MASTER_ALL_SESSIONS"
            if master_sheet not in wb.sheetnames:
                ws_master = wb.create_sheet(title=master_sheet, index=0)
                ws_master.append(ATTENDANCE_EXCEL_HEADERS)
            else:
                ws_master = wb[master_sheet]

            # Index existing rows in MASTER_ALL_SESSIONS: (mobile, date, session_id)
            #
            # Second index, master_coerced, catches the rows Excel mangled on the way in:
            # a date-named session stored as a real datetime (see _coerced_session_day).
            # It is keyed (mobile, date, day, BATCH) rather than (mobile, date, day) on
            # purpose — this centre runs a youth and a congregation class on the same date,
            # and two date-named sessions one day apart in name but identical in day would
            # otherwise collapse into one key, letting a youth row suppress a congregation
            # row for the same person. Batch is what keeps two same-day classes apart.
            master_existing = set()
            master_coerced = set()
            for row in ws_master.iter_rows(min_row=2, values_only=True):
                if row and any(row):
                    cells = (list(row) + [None]*9)[:9]
                    _, mob, _, _, sess, batch, _, _, dt = cells
                    mob_str = str(mob or '').strip()
                    sess_str = str(sess or '').strip()
                    dt_str = str(dt or '').strip()[:10]
                    batch_str = str(batch or '').strip()
                    if mob_str and sess_str:
                        master_existing.add((mob_str, dt_str, sess_str))
                        sess_day = _coerced_session_day(sess)
                        if sess_day:
                            master_coerced.add((mob_str, dt_str, sess_day, batch_str))

            added_count = 0
            session_sheets_existing = {}

            for r in db_rows:
                mob_str = str(r['mobile'] or '').strip()
                sess_str = str(r['session_id'] or '').strip()
                dt_str = str(r['date'] or '').strip()[:10]
                sheet_name = sanitize_sheet_name(sess_str)

                data_row = [
                    r['name'] or 'Unknown',
                    mob_str,
                    r['address'] or '',
                    r['occupation'] or '',
                    sess_str,
                    r['batch_name'] or '',
                    r['rounds'] or '',
                    r['remark'] or '',
                    dt_str
                ]

                # Present under its own name, or present as the datetime Excel turned that
                # name into — either way the mark is already in the sheet and appending
                # would duplicate it. batch_str comes from the DB row being placed, so the
                # coerced lookup only matches a twin from the SAME class.
                batch_str = str(r['batch_name'] or '').strip()
                sess_day = _coerced_session_day(sess_str)
                already_present = (
                    (mob_str, dt_str, sess_str) in master_existing
                    or (sess_day is not None
                        and (mob_str, dt_str, sess_day, batch_str) in master_coerced)
                )
                if not already_present:
                    ws_master.append(data_row)
                    master_existing.add((mob_str, dt_str, sess_str))
                    if sess_day:
                        master_coerced.add((mob_str, dt_str, sess_day, batch_str))
                    added_count += 1

                # Excel sheet names are case-INSENSITIVE, and openpyxl quietly renames a new sheet
                # whose title collides with an existing one rather than raising. A session_id of
                # "Testing" could therefore never be found again beside the workbook's existing
                # "testing" sheet: `'Testing' not in wb.sheetnames` was True, create_sheet handed
                # back "Testing71" instead, the 6 rows went into that, and the next pass looked for
                # "Testing", still did not find it, and made "Testing72". Seventy such sheets had
                # accumulated, and the workbook was growing by 6 rows and one junk sheet EVERY
                # MINUTE — the reconcile log reported "backfilled 6 missing rows" forever without
                # the count ever falling, which is what a self-healing pass that never converges
                # looks like from the outside.
                #
                # Resolving against whatever case the sheet already has fixes it. Nothing is lost
                # by folding the two: Excel cannot hold "Testing" and "testing" at once anyway.
                sheet_key = sheet_name.lower()
                if sheet_key not in session_sheets_existing:
                    actual_title = next((t for t in wb.sheetnames if t.lower() == sheet_key), None)
                    if actual_title is None:
                        ws_sess = wb.create_sheet(title=sheet_name)
                        ws_sess.append(ATTENDANCE_EXCEL_HEADERS)
                    else:
                        ws_sess = wb[actual_title]

                    existing_set = set()
                    for srow in ws_sess.iter_rows(min_row=2, values_only=True):
                        if srow and any(srow):
                            smob = str(srow[1] or '').strip()
                            sdt = str(srow[8] or '').strip()[:10]
                            if smob:
                                existing_set.add((smob, sdt))
                    # Keyed by the title actually in use, so a later lookup cannot miss it again.
                    session_sheets_existing[sheet_key] = (ws_sess, existing_set)

                ws_sess, existing_set = session_sheets_existing[sheet_key]
                if (mob_str, dt_str) not in existing_set:
                    ws_sess.append(data_row)
                    existing_set.add((mob_str, dt_str))
                    added_count += 1

            if added_count > 0 or salvaged:
                save_workbook_safely(wb, LIVE_FILE, reason="reconcile_backfill")
                write_log(f"Self-healing Excel Reconcile: backfilled {added_count} missing rows into {LIVE_FILE}")
            wb.close()
            return added_count
        except Exception as e:
            write_log(f"Self-healing Excel Reconcile error: {e}")
            return 0


# ── Session sheet <-> database sync verifier ─────────────────────────────────
# The registerer screen shows the same class through two different sources:
#     Current tab -> /api/show_attendance    -> SQLite `attendance`
#     Lists tab   -> /api/get_sheet_content  -> live_attendance_master.xlsx
# When they disagree a registerer sees people vanish or appear twice, and until
# now nothing in the system noticed. reconcile_live_workbook_from_db only
# BACKFILLS rows missing from Excel; it cannot see the other drift classes.
#
# WHAT THIS HEALS, AND WHAT IT DELIBERATELY WILL NOT.
# Measured against live data on 2026-08-13 before any of this was allowed to
# write: 10 rows missing in Excel, 8 field mismatches, 9 duplicates, 2 sessions
# with no sheet at all — but 727 rows that exist in Excel and NOWHERE in the
# attendance table, plus 33 more filed in the DB under a near-miss session name.
# Those 760 are real people on real dates. A healer that "fixed" them by deleting
# the Excel row would have destroyed 727 historical attendance marks that exist
# in only one place. So this heals ADDITIVELY ONLY — it can add a missing row or
# create a missing sheet, and it can never delete or overwrite one. Everything
# else is reported for a human. Same split as the calling backend's
# auditCentre() vs reconcileCentre().

def _session_sync_excel_rows(session_id):
    """The session sheet's rows, or None when the sheet does not exist at all.

    None and [] mean different things: no sheet is a drift class of its own (the
    23rd March case — one class, three sheets, four people in the wrong one),
    while an empty sheet is a sheet that simply has no marks yet.
    """
    ensure_live_workbook_readable()
    if not os.path.exists(LIVE_FILE):
        return None
    sheet = sanitize_sheet_name(session_id)
    wb = load_workbook(LIVE_FILE, read_only=True, data_only=True)
    try:
        # Excel sheet names are case-INSENSITIVE, so the tab for session
        # "Testing" may be titled "testing". Matching case-sensitively here
        # reported that session as having no sheet at all when it had one all
        # along — a false "6 rows missing" that would have sent the repair off
        # to create a duplicate tab. reconcile_live_workbook_from_db already
        # resolves titles this way (see the seventy "Testing71..." sheets it
        # once accumulated); this must resolve them identically or the detector
        # and the healer disagree about which sheet they are even looking at.
        actual = session_sync.resolve_sheet_title(wb.sheetnames, sheet)
        if actual is None:
            return None
        rows = []
        for row in wb[actual].iter_rows(min_row=2, values_only=True):
            if not row or not any(row):
                continue
            c = (list(row) + [None] * 9)[:9]
            rows.append({
                "name": c[0], "mobile": c[1], "address": c[2], "occupation": c[3],
                "session": c[4], "batch": c[5], "rounds": c[6], "remark": c[7],
                "date": c[8],
            })
        return rows
    finally:
        wb.close()


def _session_sync_db_rows(session_id, centre_id=None):
    """The attendance marks SQLite holds for this session.

    Raw sqlite3 rather than SQLAlchemy so this is callable from a background
    thread with no Flask application context — the same reason
    reconcile_live_workbook_from_db does it this way.
    """
    db_path = app.config['SQLALCHEMY_DATABASE_URI'].replace('sqlite:///', '')
    import sqlite3 as _sqlite3
    conn = _sqlite3.connect(db_path)
    conn.row_factory = _sqlite3.Row
    try:
        sql = ("SELECT a.mobile, a.date, a.rounds, a.remark, a.batch_name, "
               "a.session_id, u.name FROM attendance a "
               "LEFT JOIN users u ON u.mobile = a.mobile WHERE a.session_id = ?")
        params = [session_id]
        if centre_id:
            sql += " AND a.centre_id = ?"
            params.append(centre_id)
        return [dict(r) for r in conn.execute(sql, params).fetchall()]
    finally:
        conn.close()


# An audit costs ~90ms, almost all of it opening the workbook. That is nothing
# once, but the registerer badge polls it from EVERY device, so without a cache
# ten devices would each pay it. Marking and registration must never get slower
# because of a diagnostic, so the badge is served from here: one workbook read
# per window no matter how many devices ask, and a COM write in flight is
# answered from the last known result instead of contending for the file.
SESSION_SYNC_CACHE_TTL = 60
_SESSION_SYNC_CACHE = {}          # (session_id, centre_id) -> (timestamp, report)
_SESSION_SYNC_CACHE_LOCK = threading.Lock()


def _session_sync_cache_get(key, max_age):
    with _SESSION_SYNC_CACHE_LOCK:
        entry = _SESSION_SYNC_CACHE.get(key)
    if not entry:
        return None
    stamp, report = entry
    if max_age is not None and (time.time() - stamp) > max_age:
        return None
    return report


def _session_sync_cache_put(key, report):
    with _SESSION_SYNC_CACHE_LOCK:
        _SESSION_SYNC_CACHE[key] = (time.time(), report)
        if len(_SESSION_SYNC_CACHE) > 200:      # bounded; sessions come and go
            oldest = sorted(_SESSION_SYNC_CACHE.items(), key=lambda kv: kv[1][0])
            for k, _ in oldest[:50]:
                _SESSION_SYNC_CACHE.pop(k, None)


def audit_session_sync_cached(session_id, centre_id=None, max_age=SESSION_SYNC_CACHE_TTL):
    """audit_session_sync with a short TTL cache. Use this for anything user-facing."""
    key = (session_id, centre_id)
    cached = _session_sync_cache_get(key, max_age)
    if cached is not None:
        return dict(cached, cached=True)
    if excel_lock_guard.com_writes_in_flight() > 0:
        # The workbook is legitimately busy. Prefer a stale answer to competing
        # with a live attendance write for the same file.
        stale = _session_sync_cache_get(key, None)
        if stale is not None:
            return dict(stale, cached=True, stale=True)
    report = audit_session_sync(session_id, centre_id)
    _session_sync_cache_put(key, report)
    return dict(report, cached=False)


def audit_session_sync(session_id, centre_id=None):
    """Read-only: how the session sheet and the database differ. Never writes."""
    excel_rows = _session_sync_excel_rows(session_id)
    report = session_sync.audit(
        _session_sync_db_rows(session_id, centre_id),
        excel_rows or [],
        sheet_exists=excel_rows is not None,
    )
    report["session_id"] = session_id
    report["centre_id"] = centre_id
    report["summary"] = session_sync.summarize(report)
    report["in_sync"] = session_sync.is_clean(report)
    return report


def heal_session_sync(session_id, centre_id=None, source="manual"):
    """Audit, additively repair what is safe, then re-audit to prove it worked.

    Returns (report_after, healed_count). The re-audit is the point: a heal that
    silently failed to land would otherwise look identical to a heal that worked,
    and this system has been bitten by exactly that before.
    """
    before = audit_session_sync(session_id, centre_id)
    if before["in_sync"]:
        return before, 0

    # Only the additive classes justify a write. Ghost rows (present in Excel,
    # absent from the DB) and field mismatches are REPORTED, never auto-applied:
    # deleting is unrecoverable, and overwriting a cell could discard a Lists-tab
    # edit that failed to reach the DB.
    needs_write = bool(before[session_sync.MISSING_IN_EXCEL]) or not before["sheet_exists"]
    healed = 0
    if needs_write:
        # Reuse the existing, proven backfill rather than adding a second writer.
        # It creates the sheet when absent and appends only rows it cannot find,
        # and it now saves through save_workbook_safely, so an Excel lock
        # self-heals instead of failing.
        reconcile_live_workbook_from_db(
            target_session_id=session_id, target_centre_id=centre_id)

    after = audit_session_sync(session_id, centre_id) if needs_write else before
    # Count what landed on THIS session's sheet, by difference between the two audits —
    # NOT the backfill's own return value. That writer mirrors every row into
    # MASTER_ALL_SESSIONS as well as the session tab, so it returns two writes per
    # person: it reported "added 6 row(s)" for a 3-person session, which is the number
    # a registerer would then fail to find on their sheet. Deriving it from the audit
    # keeps the badge honest and is immune to how many sheets the writer touches.
    healed = max(0, len(before[session_sync.MISSING_IN_EXCEL])
                 - len(after[session_sync.MISSING_IN_EXCEL])) if needs_write else 0
    after["healed_rows"] = healed
    # Push the fresh result into the cache so the badge shows the repaired state
    # immediately rather than serving a stale "out of sync" for up to a minute.
    _session_sync_cache_put((session_id, centre_id), after)

    if needs_write and after[session_sync.MISSING_IN_EXCEL]:
        # The backfill ran and rows are STILL missing — that is not normal drift,
        # it means the repair itself is not working. Say so loudly.
        journal_write(
            system="AE", direction="internal", connection="reconcile",
            kind="session_sync", status="needs_attention", entity=session_id,
            detail=(f"backfill ran but {len(after[session_sync.MISSING_IN_EXCEL])} row(s) "
                    f"are still missing from the sheet: {after['summary']}"),
            payload={"session_id": session_id, "centre_id": centre_id}, source=source,
        )
        write_log(f"Session sync: {session_id!r} STILL out of sync after repair — {after['summary']}")
    elif healed:
        journal_write(
            system="AE", direction="internal", connection="reconcile",
            kind="session_sync", status="self_healed", entity=session_id,
            detail=f"added {healed} missing row(s) to the sheet; {after['summary']}",
            payload={"session_id": session_id, "centre_id": centre_id}, source=source,
        )
        write_log(f"Session sync: repaired {session_id!r} — added {healed} row(s); {after['summary']}")

    # Drift we deliberately do not touch still gets recorded once per run, so it
    # is visible without anyone having to go looking for it.
    unhealable = (len(after[session_sync.MISSING_IN_DB])
                  + len(after[session_sync.FIELD_MISMATCH])
                  + len(after[session_sync.DUPLICATE_IN_EXCEL]))
    if unhealable:
        journal_write(
            system="AE", direction="internal", connection="reconcile",
            kind="session_sync", status="needs_attention", entity=session_id,
            detail=f"{after['summary']} (not auto-repaired: needs a human decision)",
            payload={"session_id": session_id, "centre_id": centre_id}, source=source,
        )
    return after, healed


SESSION_SYNC_INTERVAL = 120


def session_sync_worker():
    """Keep the ACTIVE sessions' sheets and the database in step, continuously.

    Scoped to the active sessions on purpose. A full sweep of all 164 tabs takes
    seconds and reads the whole workbook; doing that on a loop would fight with
    every attendance write for the same file. The active session is the only one
    a registerer is looking at, and the one being written to right now.

    Never runs while an Excel COM write is in flight — the workbook is
    legitimately busy then, and reading a half-written sheet would report drift
    that does not exist.
    """
    first = True
    while True:
        try:
            time.sleep(SESSION_SYNC_INTERVAL)
            if excel_lock_guard.com_writes_in_flight() > 0:
                continue
            targets = []
            for cid, by_cat in list(ACTIVE_SESSION_BY_CENTRE.items()):
                for _cat, state in list((by_cat or {}).items()):
                    sid = (state or {}).get("session_id")
                    if sid:
                        targets.append((sid, cid))
            if first:
                # Prove the loop actually ticks. A scheduled job in this system
                # has silently not run before; finding its call site is not
                # evidence, its log output is.
                write_log(f"Session sync verifier: first tick OK, "
                          f"{len(targets)} active session(s) to check "
                          f"(every {SESSION_SYNC_INTERVAL}s)")
                first = False
            for sid, cid in targets:
                try:
                    report, _ = heal_session_sync(sid, cid, source="session_sync_worker")
                    if not report["in_sync"]:
                        write_log(f"Session sync ({sid!r}): {report['summary']}")
                except Exception as e:
                    # One bad session must never stop the others being checked.
                    write_log(f"Session sync failed for {sid!r}: {e}")
        except Exception as e:
            write_log(f"Session sync worker error: {e}")


def ensure_session_sheet_in_live_workbook(sheet_name, headers=None):
    """Create a session sheet in the live workbook, using Excel COM on Windows when available."""
    headers = headers or ATTENDANCE_EXCEL_HEADERS

    if os.name == "nt":
        payload = {
            "path": LIVE_FILE,
            "sheet_name": sheet_name,
            "headers": headers,
        }
        payload_path = None
        try:
            with tempfile.NamedTemporaryFile("w", delete=False, suffix=".json", encoding="utf-8") as tmp:
                json.dump(payload, tmp, ensure_ascii=False)
                payload_path = tmp.name

            ps_script = r"""
param([string]$PayloadPath)
$payload = Get-Content -Raw -LiteralPath $PayloadPath | ConvertFrom-Json
$Path = $payload.path
$SheetName = $payload.sheet_name
$Headers = @()
if ($payload.headers) {
    $Headers = @($payload.headers)
}
# Ownership flags for the try/finally at the bottom. We only ever close a
# workbook THIS script opened and only quit an Excel THIS script created, so a
# workbook a human has open on this machine is never touched.
$OwnsExcel = $false
$OwnsWb = $false
try {
    $excel = [Runtime.InteropServices.Marshal]::GetActiveObject('Excel.Application')
} catch {
    $excel = New-Object -ComObject Excel.Application
    # This script created the instance, so this script must end it. The old
    # behaviour deliberately left it resident for reuse, which is exactly what
    # strands a windowless EXCEL.EXE still holding the workbook open whenever
    # this script dies before its close (most often the caller's 30s timeout).
    # Excel is launched by DCOM, NOT as a child of PowerShell, so killing our
    # process tree does not take it with us. That orphan holds an exclusive
    # handle on the .xlsx and blocks every later write to it, permanently --
    # the confirmed root cause of the recurring attendance data-loss reports.
    # Reuse was a speed optimisation on a background worker; correctness wins.
    # See excel_lock_guard.py for the runtime backstop that cleans up any
    # orphan a hard kill still manages to leave behind.
    $OwnsExcel = $true
}
$excel.Visible = $false
$excel.DisplayAlerts = $false
$wb = $null
foreach ($w in $excel.Workbooks) {
    if ($w.FullName -ieq $Path) { $wb = $w; break }
}
if (-not $wb) {
    $wb = $excel.Workbooks.Open($Path)
    $OwnsWb = $true
}
try {
$created = $false
try {
    $ws = $wb.Worksheets.Item($SheetName)
} catch {
    $ws = $wb.Worksheets.Add()
    $ws.Name = $SheetName
    $created = $true
}
if ($created -and $Headers.Count -gt 0) {
    $headerValues = @()
    foreach ($h in $Headers) {
        $headerValues += [string]$h
    }
    $range = $ws.Range($ws.Cells(1,1), $ws.Cells(1,$headerValues.Count))
    $range.Value2 = $headerValues
}
$wb.Save()
$wb.Close($false)
} finally {
    # Runs on success, on any terminating error, and on an early `return` -- so a
    # workbook this script opened can never be left open inside a surviving,
    # windowless EXCEL.EXE. (A hard process kill still bypasses this; that
    # residual case is what excel_lock_guard.py detects and clears at runtime.)
    if ($OwnsWb -and $wb) { try { $wb.Close($false) } catch {} }
    if ($OwnsExcel -and $excel) {
        try { $excel.Quit() } catch {}
        try { [Runtime.InteropServices.Marshal]::ReleaseComObject($excel) | Out-Null } catch {}
    }
}
"""
            ps_path = None
            with tempfile.NamedTemporaryFile("w", delete=False, suffix=".ps1", encoding="utf-8") as tmp_ps:
                tmp_ps.write(ps_script)
                ps_path = tmp_ps.name

            _run_com_helper(
                ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ps_path, "-PayloadPath", payload_path],
                check=True,
                # NOTE (2026-06-29 wedge fix): use DEVNULL, NOT capture_output/PIPE.
                # When this PowerShell helper blocks inside a COM call to a Excel
                # that a human has open, capture_output's post-timeout pipe drain
                # (communicate) can deadlock PAST timeout=30, which froze the single
                # excel_sync_worker thread and silently dropped every later record.
                # DEVNULL has no pipe to drain, so the timeout reliably fires and the
                # except-> openpyxl fallback below runs instead of wedging forever.
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=30,
                **_hidden_process_kwargs(),
            )
            return
        except Exception as e:
            write_log(
                "Excel COM session-sheet create failed, falling back to openpyxl: "
                f"{e}; stdout={getattr(e, 'stdout', '')}; stderr={getattr(e, 'stderr', '')}"
            )
            _after_com_failure(e, LIVE_FILE, "session_sheet_create")
        finally:
            if 'ps_path' in locals() and ps_path and os.path.exists(ps_path):
                try:
                    os.remove(ps_path)
                except Exception:
                    pass
            if payload_path and os.path.exists(payload_path):
                try:
                    os.remove(payload_path)
                except Exception:
                    pass

    with EXCEL_WRITE_LOCK:
        if os.path.exists(LIVE_FILE):
            wb = load_workbook(LIVE_FILE)
        else:
            wb = Workbook()
            if wb.active:
                wb.remove(wb.active)

        try:
            if sheet_name not in wb.sheetnames:
                ws = wb.create_sheet(title=sheet_name)
                if headers:
                    ws.append(headers)
            save_workbook_safely(wb, LIVE_FILE, reason="session_sheet_create")
        finally:
            wb.close()


def delete_session_sheet_from_live_workbook(sheet_name):
    """Delete a session sheet from the live workbook, preferring the Excel COM path on Windows."""
    if os.name == "nt":
        payload = {
            "path": LIVE_FILE,
            "sheet_name": sheet_name,
        }
        payload_path = None
        try:
            with tempfile.NamedTemporaryFile("w", delete=False, suffix=".json", encoding="utf-8") as tmp:
                json.dump(payload, tmp, ensure_ascii=False)
                payload_path = tmp.name

            ps_script = r"""
param([string]$PayloadPath)
$payload = Get-Content -Raw -LiteralPath $PayloadPath | ConvertFrom-Json
$Path = $payload.path
$SheetName = $payload.sheet_name
# Ownership flags for the try/finally at the bottom. We only ever close a
# workbook THIS script opened and only quit an Excel THIS script created, so a
# workbook a human has open on this machine is never touched.
$OwnsExcel = $false
$OwnsWb = $false
try {
    $excel = [Runtime.InteropServices.Marshal]::GetActiveObject('Excel.Application')
} catch {
    $excel = New-Object -ComObject Excel.Application
    # This script created the instance, so this script must end it. The old
    # behaviour deliberately left it resident for reuse, which is exactly what
    # strands a windowless EXCEL.EXE still holding the workbook open whenever
    # this script dies before its close (most often the caller's 30s timeout).
    # Excel is launched by DCOM, NOT as a child of PowerShell, so killing our
    # process tree does not take it with us. That orphan holds an exclusive
    # handle on the .xlsx and blocks every later write to it, permanently --
    # the confirmed root cause of the recurring attendance data-loss reports.
    # Reuse was a speed optimisation on a background worker; correctness wins.
    # See excel_lock_guard.py for the runtime backstop that cleans up any
    # orphan a hard kill still manages to leave behind.
    $OwnsExcel = $true
}
$excel.Visible = $false
$excel.DisplayAlerts = $false
$wb = $null
foreach ($w in $excel.Workbooks) {
    if ($w.FullName -ieq $Path) { $wb = $w; break }
}
if (-not $wb) {
    $wb = $excel.Workbooks.Open($Path)
    $OwnsWb = $true
}
try {
try {
    $ws = $wb.Worksheets.Item($SheetName)
    $ws.Delete()
} catch {
    # Sheet already missing; treat as success.
}
if ($wb.Worksheets.Count -lt 1) {
    $empty = $wb.Worksheets.Add()
    $empty.Name = "Empty"
}
$wb.Save()
$wb.Close($false)
} finally {
    # Runs on success, on any terminating error, and on an early `return` -- so a
    # workbook this script opened can never be left open inside a surviving,
    # windowless EXCEL.EXE. (A hard process kill still bypasses this; that
    # residual case is what excel_lock_guard.py detects and clears at runtime.)
    if ($OwnsWb -and $wb) { try { $wb.Close($false) } catch {} }
    if ($OwnsExcel -and $excel) {
        try { $excel.Quit() } catch {}
        try { [Runtime.InteropServices.Marshal]::ReleaseComObject($excel) | Out-Null } catch {}
    }
}
"""
            ps_path = None
            with tempfile.NamedTemporaryFile("w", delete=False, suffix=".ps1", encoding="utf-8") as tmp_ps:
                tmp_ps.write(ps_script)
                ps_path = tmp_ps.name

            _run_com_helper(
                ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ps_path, "-PayloadPath", payload_path],
                check=True,
                # NOTE (2026-06-29 wedge fix): use DEVNULL, NOT capture_output/PIPE.
                # When this PowerShell helper blocks inside a COM call to a Excel
                # that a human has open, capture_output's post-timeout pipe drain
                # (communicate) can deadlock PAST timeout=30, which froze the single
                # excel_sync_worker thread and silently dropped every later record.
                # DEVNULL has no pipe to drain, so the timeout reliably fires and the
                # except-> openpyxl fallback below runs instead of wedging forever.
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=30,
                **_hidden_process_kwargs(),
            )
            return
        except Exception as e:
            write_log(
                "Excel COM session-sheet delete failed, falling back to openpyxl: "
                f"{e}; stdout={getattr(e, 'stdout', '')}; stderr={getattr(e, 'stderr', '')}"
            )
            _after_com_failure(e, LIVE_FILE, "session_sheet_delete")
        finally:
            if 'ps_path' in locals() and ps_path and os.path.exists(ps_path):
                try:
                    os.remove(ps_path)
                except Exception:
                    pass
            if payload_path and os.path.exists(payload_path):
                try:
                    os.remove(payload_path)
                except Exception:
                    pass

    with EXCEL_WRITE_LOCK:
        if not os.path.exists(LIVE_FILE):
            return

        wb = load_workbook(LIVE_FILE)
        try:
            if sheet_name in wb.sheetnames:
                del wb[sheet_name]
            if len(wb.sheetnames) == 0:
                wb.create_sheet("Empty")
            save_workbook_safely(wb, LIVE_FILE, reason="session_sheet_delete")
        finally:
            wb.close()


def delete_attendance_rows_from_live_workbook(session_id, date_str, batch_name):
    """Delete attendance rows from a session sheet in the live workbook."""
    def _normalize_py_date(value):
        if value is None:
            return ""
        if isinstance(value, datetime):
            return value.strftime("%Y-%m-%d")
        if isinstance(value, date):
            return value.strftime("%Y-%m-%d")
        text = str(value).strip()
        if not text:
            return ""
        for fmt in (
            "%Y-%m-%d",
            "%d/%m/%Y",
            "%d-%m-%Y",
            "%d %b %Y",
            "%d %B %Y",
            "%a, %d %b %Y %H:%M:%S GMT",
        ):
            try:
                return datetime.strptime(text, fmt).strftime("%Y-%m-%d")
            except Exception:
                pass
        return text[:10]

    target_date_norm = _normalize_py_date(date_str)

    if os.name == "nt":
        payload = {
            "path": LIVE_FILE,
            "session_id": session_id,
            "date_str": date_str,
            "batch_name": batch_name,
        }
        payload_path = None
        try:
            with tempfile.NamedTemporaryFile("w", delete=False, suffix=".json", encoding="utf-8") as tmp:
                json.dump(payload, tmp, ensure_ascii=False)
                payload_path = tmp.name

            ps_script = r"""
param([string]$PayloadPath)
$payload = Get-Content -Raw -LiteralPath $PayloadPath | ConvertFrom-Json
$Path = $payload.path
$SessionId = $payload.session_id
$TargetDate = [string]$payload.date_str
$BatchName = [string]$payload.batch_name
# Ownership flags for the try/finally at the bottom. We only ever close a
# workbook THIS script opened and only quit an Excel THIS script created, so a
# workbook a human has open on this machine is never touched.
$OwnsExcel = $false
$OwnsWb = $false
try {
    $excel = [Runtime.InteropServices.Marshal]::GetActiveObject('Excel.Application')
} catch {
    $excel = New-Object -ComObject Excel.Application
    # This script created the instance, so this script must end it. The old
    # behaviour deliberately left it resident for reuse, which is exactly what
    # strands a windowless EXCEL.EXE still holding the workbook open whenever
    # this script dies before its close (most often the caller's 30s timeout).
    # Excel is launched by DCOM, NOT as a child of PowerShell, so killing our
    # process tree does not take it with us. That orphan holds an exclusive
    # handle on the .xlsx and blocks every later write to it, permanently --
    # the confirmed root cause of the recurring attendance data-loss reports.
    # Reuse was a speed optimisation on a background worker; correctness wins.
    # See excel_lock_guard.py for the runtime backstop that cleans up any
    # orphan a hard kill still manages to leave behind.
    $OwnsExcel = $true
}
$excel.Visible = $false
$excel.DisplayAlerts = $false
$wb = $null
foreach ($w in $excel.Workbooks) {
    if ($w.FullName -ieq $Path) { $wb = $w; break }
}
if (-not $wb) {
    $wb = $excel.Workbooks.Open($Path)
    $OwnsWb = $true
}
try {
try {
    $ws = $wb.Worksheets.Item($SessionId)
} catch {
    $wb.Save()
    $wb.Close($false)
    return
}
$headers = @()
for ($c = 1; $c -le $ws.UsedRange.Columns.Count; $c++) {
    $headers += [string]$ws.Cells(1, $c).Text
}
try {
    $dateCol = [array]::IndexOf($headers, 'Date') + 1
    $batchCol = [array]::IndexOf($headers, 'Batch') + 1
    if ($dateCol -le 0 -or $batchCol -le 0) {
        $wb.Save()
        $wb.Close($false)
        return
    }
} catch {
    $wb.Save()
    $wb.Close($false)
    return
}
for ($row = $ws.UsedRange.Rows.Count; $row -ge 2; $row--) {
    $cellVal = $ws.Cells($row, $dateCol).Value2
    $rowDate = ""
    if ($null -ne $cellVal) {
        try {
            if ($cellVal -is [double] -or $cellVal -is [int]) {
                $rowDate = [datetime]::FromOADate([double]$cellVal).ToString('yyyy-MM-dd')
            } else {
                $rowDate = ([datetime]$cellVal).ToString('yyyy-MM-dd')
            }
        } catch {
            $rowDate = [string]$ws.Cells($row, $dateCol).Text
        }
    }
    $rowBatch = [string]$ws.Cells($row, $batchCol).Text
    if ($rowDate -eq $TargetDate -and $rowBatch -eq $BatchName) {
        $ws.Rows($row).Delete()
    }
}
$wb.Save()
$wb.Close($false)
} finally {
    # Runs on success, on any terminating error, and on an early `return` -- so a
    # workbook this script opened can never be left open inside a surviving,
    # windowless EXCEL.EXE. (A hard process kill still bypasses this; that
    # residual case is what excel_lock_guard.py detects and clears at runtime.)
    if ($OwnsWb -and $wb) { try { $wb.Close($false) } catch {} }
    if ($OwnsExcel -and $excel) {
        try { $excel.Quit() } catch {}
        try { [Runtime.InteropServices.Marshal]::ReleaseComObject($excel) | Out-Null } catch {}
    }
}
"""
            ps_path = None
            with tempfile.NamedTemporaryFile("w", delete=False, suffix=".ps1", encoding="utf-8") as tmp_ps:
                tmp_ps.write(ps_script)
                ps_path = tmp_ps.name

            _run_com_helper(
                ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ps_path, "-PayloadPath", payload_path],
                check=True,
                # NOTE (2026-06-29 wedge fix): use DEVNULL, NOT capture_output/PIPE.
                # When this PowerShell helper blocks inside a COM call to a Excel
                # that a human has open, capture_output's post-timeout pipe drain
                # (communicate) can deadlock PAST timeout=30, which froze the single
                # excel_sync_worker thread and silently dropped every later record.
                # DEVNULL has no pipe to drain, so the timeout reliably fires and the
                # except-> openpyxl fallback below runs instead of wedging forever.
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=30,
                **_hidden_process_kwargs(),
            )
            return
        except Exception as e:
            write_log(
                "Excel COM batch-delete row sync failed, falling back to openpyxl: "
                f"{e}; stdout={getattr(e, 'stdout', '')}; stderr={getattr(e, 'stderr', '')}"
            )
            _after_com_failure(e, LIVE_FILE, "batch_delete_rows")
        finally:
            if 'ps_path' in locals() and ps_path and os.path.exists(ps_path):
                try:
                    os.remove(ps_path)
                except Exception:
                    pass
            if payload_path and os.path.exists(payload_path):
                try:
                    os.remove(payload_path)
                except Exception:
                    pass

    with EXCEL_WRITE_LOCK:
        if not os.path.exists(LIVE_FILE):
            return

        try:
            wb = openpyxl.load_workbook(LIVE_FILE)
            if session_id in wb.sheetnames:
                sheet = wb[session_id]
                headers = [cell.value for cell in sheet[1]]
                try:
                    date_col = headers.index("Date") + 1
                    batch_col = headers.index("Batch") + 1
                except ValueError:
                    wb.close()
                    return

                to_delete = []
                for row_idx in range(2, sheet.max_row + 1):
                    row_date = _normalize_py_date(sheet.cell(row=row_idx, column=date_col).value)
                    row_batch = str(sheet.cell(row=row_idx, column=batch_col).value)
                    if row_date == target_date_norm and row_batch == batch_name:
                        to_delete.append(row_idx)

                for row_idx in reversed(to_delete):
                    sheet.delete_rows(row_idx)

                save_workbook_safely(wb, LIVE_FILE, reason="batch_delete_rows")
            wb.close()
        except Exception as e:
            write_log(f"Openpyxl batch-delete row sync failed: {e}")


def update_row_in_live_workbook(sheet_name, row_idx, updated_data, old_mobile=None):
    """Update a workbook row using Excel COM when available, with an openpyxl fallback."""
    if os.name == "nt":
        payload = {
            "path": LIVE_FILE,
            "sheet_name": sheet_name,
            "row_idx": int(row_idx),
            "updated_data": updated_data or {},
            "old_mobile": str(old_mobile) if old_mobile else "",
        }
        payload_path = None
        try:
            with tempfile.NamedTemporaryFile("w", delete=False, suffix=".json", encoding="utf-8") as tmp:
                json.dump(payload, tmp, ensure_ascii=False)
                payload_path = tmp.name

            ps_script = r"""
param([string]$PayloadPath)
$payload = Get-Content -Raw -LiteralPath $PayloadPath | ConvertFrom-Json
$Path = $payload.path
$SheetName = $payload.sheet_name
$RowIdx = [int]$payload.row_idx
$OldMobile = [string]$payload.old_mobile
$Updated = $payload.updated_data
# Ownership flags for the try/finally at the bottom. We only ever close a
# workbook THIS script opened and only quit an Excel THIS script created, so a
# workbook a human has open on this machine is never touched.
$OwnsExcel = $false
$OwnsWb = $false
try {
    $excel = [Runtime.InteropServices.Marshal]::GetActiveObject('Excel.Application')
} catch {
    $excel = New-Object -ComObject Excel.Application
    # This script created the instance, so this script must end it. The old
    # behaviour deliberately left it resident for reuse, which is exactly what
    # strands a windowless EXCEL.EXE still holding the workbook open whenever
    # this script dies before its close (most often the caller's 30s timeout).
    # Excel is launched by DCOM, NOT as a child of PowerShell, so killing our
    # process tree does not take it with us. That orphan holds an exclusive
    # handle on the .xlsx and blocks every later write to it, permanently --
    # the confirmed root cause of the recurring attendance data-loss reports.
    # Reuse was a speed optimisation on a background worker; correctness wins.
    # See excel_lock_guard.py for the runtime backstop that cleans up any
    # orphan a hard kill still manages to leave behind.
    $OwnsExcel = $true
}
$excel.Visible = $false
$excel.DisplayAlerts = $false
$wb = $null
foreach ($w in $excel.Workbooks) {
    if ($w.FullName -ieq $Path) { $wb = $w; break }
}
if (-not $wb) {
    $wb = $excel.Workbooks.Open($Path)
    $OwnsWb = $true
}
try {
function Update-SheetRow($ws, $targetRow, $updateData) {
    $headers = @()
    for ($c = 1; $c -le $ws.UsedRange.Columns.Count; $c++) {
        $headers += [string]$ws.Cells(1, $c).Text
    }
    foreach ($prop in $updateData.PSObject.Properties) {
        $colIdx = [array]::IndexOf($headers, [string]$prop.Name) + 1
        if ($colIdx -gt 0) {
            $ws.Cells($targetRow, $colIdx).Value2 = $prop.Value
        }
    }
}
try {
    $ws = $wb.Worksheets.Item($SheetName)
    Update-SheetRow $ws $RowIdx $Updated
} catch {
    $wb.Save()
    $wb.Close($false)
    return
}
if ($SheetName -ne 'MASTER_ALL_SESSIONS' -and $OldMobile) {
    try {
        $master = $wb.Worksheets.Item('MASTER_ALL_SESSIONS')
        $masterHeaders = @()
        for ($c = 1; $c -le $master.UsedRange.Columns.Count; $c++) {
            $masterHeaders += [string]$master.Cells(1, $c).Text
        }
        $mobileCol = [array]::IndexOf($masterHeaders, 'Number') + 1
        if ($mobileCol -le 0) { $mobileCol = 2 }
        for ($r = 2; $r -le $master.UsedRange.Rows.Count; $r++) {
            if ([string]$master.Cells($r, $mobileCol).Text -eq $OldMobile) {
                Update-SheetRow $master $r $Updated
                break
            }
        }
    } catch {
        # Best effort; the session sheet update already completed.
    }
}
$wb.Save()
$wb.Close($false)
} finally {
    # Runs on success, on any terminating error, and on an early `return` -- so a
    # workbook this script opened can never be left open inside a surviving,
    # windowless EXCEL.EXE. (A hard process kill still bypasses this; that
    # residual case is what excel_lock_guard.py detects and clears at runtime.)
    if ($OwnsWb -and $wb) { try { $wb.Close($false) } catch {} }
    if ($OwnsExcel -and $excel) {
        try { $excel.Quit() } catch {}
        try { [Runtime.InteropServices.Marshal]::ReleaseComObject($excel) | Out-Null } catch {}
    }
}
"""
            ps_path = None
            with tempfile.NamedTemporaryFile("w", delete=False, suffix=".ps1", encoding="utf-8") as tmp_ps:
                tmp_ps.write(ps_script)
                ps_path = tmp_ps.name

            _run_com_helper(
                ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ps_path, "-PayloadPath", payload_path],
                check=True,
                # NOTE (2026-06-29 wedge fix): use DEVNULL, NOT capture_output/PIPE.
                # When this PowerShell helper blocks inside a COM call to a Excel
                # that a human has open, capture_output's post-timeout pipe drain
                # (communicate) can deadlock PAST timeout=30, which froze the single
                # excel_sync_worker thread and silently dropped every later record.
                # DEVNULL has no pipe to drain, so the timeout reliably fires and the
                # except-> openpyxl fallback below runs instead of wedging forever.
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=30,
                **_hidden_process_kwargs(),
            )
            return
        except Exception as e:
            write_log(
                "Excel COM row update failed, falling back to openpyxl: "
                f"{e}; stdout={getattr(e, 'stdout', '')}; stderr={getattr(e, 'stderr', '')}"
            )
            _after_com_failure(e, LIVE_FILE, "row_update")
        finally:
            if 'ps_path' in locals() and ps_path and os.path.exists(ps_path):
                try:
                    os.remove(ps_path)
                except Exception:
                    pass
            if payload_path and os.path.exists(payload_path):
                try:
                    os.remove(payload_path)
                except Exception:
                    pass

    with EXCEL_WRITE_LOCK:
        wb = load_workbook(LIVE_FILE)
        try:
            if sheet_name not in wb.sheetnames:
                return

            ws = wb[sheet_name]
            headers = [str(cell.value) for cell in ws[1]]
            for col_name, new_val in (updated_data or {}).items():
                if col_name in headers:
                    col_idx = headers.index(col_name) + 1
                    ws.cell(row=int(row_idx), column=col_idx, value=new_val)

            if sheet_name != "MASTER_ALL_SESSIONS" and old_mobile:
                try:
                    master = wb["MASTER_ALL_SESSIONS"]
                    master_headers = [str(cell.value) for cell in master[1]]
                    mobile_col = master_headers.index("Number") + 1 if "Number" in master_headers else 2
                    target_row = None
                    for r in range(2, master.max_row + 1):
                        if str(master.cell(row=r, column=mobile_col).value).strip() == str(old_mobile).strip():
                            target_row = r
                            break
                    if target_row:
                        for col_name, new_val in (updated_data or {}).items():
                            if col_name in master_headers:
                                col_idx = master_headers.index(col_name) + 1
                                master.cell(row=target_row, column=col_idx, value=new_val)
                except Exception as e:
                    write_log(f"Openpyxl master update failed: {e}")

            save_workbook_safely(wb, LIVE_FILE, reason="row_update")
        finally:
            wb.close()


def delete_row_from_live_workbook(sheet_name, row_idx):
    """Delete a single row from a workbook sheet using Excel COM when available."""
    if os.name == "nt":
        payload = {
            "path": LIVE_FILE,
            "sheet_name": sheet_name,
            "row_idx": int(row_idx),
        }
        payload_path = None
        try:
            with tempfile.NamedTemporaryFile("w", delete=False, suffix=".json", encoding="utf-8") as tmp:
                json.dump(payload, tmp, ensure_ascii=False)
                payload_path = tmp.name

            ps_script = r"""
param([string]$PayloadPath)
$payload = Get-Content -Raw -LiteralPath $PayloadPath | ConvertFrom-Json
$Path = $payload.path
$SheetName = $payload.sheet_name
$RowIdx = [int]$payload.row_idx
# Ownership flags for the try/finally at the bottom. We only ever close a
# workbook THIS script opened and only quit an Excel THIS script created, so a
# workbook a human has open on this machine is never touched.
$OwnsExcel = $false
$OwnsWb = $false
try {
    $excel = [Runtime.InteropServices.Marshal]::GetActiveObject('Excel.Application')
} catch {
    $excel = New-Object -ComObject Excel.Application
    # This script created the instance, so this script must end it. The old
    # behaviour deliberately left it resident for reuse, which is exactly what
    # strands a windowless EXCEL.EXE still holding the workbook open whenever
    # this script dies before its close (most often the caller's 30s timeout).
    # Excel is launched by DCOM, NOT as a child of PowerShell, so killing our
    # process tree does not take it with us. That orphan holds an exclusive
    # handle on the .xlsx and blocks every later write to it, permanently --
    # the confirmed root cause of the recurring attendance data-loss reports.
    # Reuse was a speed optimisation on a background worker; correctness wins.
    # See excel_lock_guard.py for the runtime backstop that cleans up any
    # orphan a hard kill still manages to leave behind.
    $OwnsExcel = $true
}
$excel.Visible = $false
$excel.DisplayAlerts = $false
$wb = $null
foreach ($w in $excel.Workbooks) {
    if ($w.FullName -ieq $Path) { $wb = $w; break }
}
if (-not $wb) {
    $wb = $excel.Workbooks.Open($Path)
    $OwnsWb = $true
}
try {
try {
    $ws = $wb.Worksheets.Item($SheetName)
    if ($RowIdx -gt 1 -and $RowIdx -le $ws.UsedRange.Rows.Count) {
        $ws.Rows($RowIdx).Delete()
    }
    $wb.Save()
} catch {
    $wb.Save()
}
$wb.Close($false)
} finally {
    # Runs on success, on any terminating error, and on an early `return` -- so a
    # workbook this script opened can never be left open inside a surviving,
    # windowless EXCEL.EXE. (A hard process kill still bypasses this; that
    # residual case is what excel_lock_guard.py detects and clears at runtime.)
    if ($OwnsWb -and $wb) { try { $wb.Close($false) } catch {} }
    if ($OwnsExcel -and $excel) {
        try { $excel.Quit() } catch {}
        try { [Runtime.InteropServices.Marshal]::ReleaseComObject($excel) | Out-Null } catch {}
    }
}
"""
            ps_path = None
            with tempfile.NamedTemporaryFile("w", delete=False, suffix=".ps1", encoding="utf-8") as tmp_ps:
                tmp_ps.write(ps_script)
                ps_path = tmp_ps.name

            _run_com_helper(
                ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ps_path, "-PayloadPath", payload_path],
                check=True,
                # NOTE (2026-06-29 wedge fix): use DEVNULL, NOT capture_output/PIPE.
                # When this PowerShell helper blocks inside a COM call to a Excel
                # that a human has open, capture_output's post-timeout pipe drain
                # (communicate) can deadlock PAST timeout=30, which froze the single
                # excel_sync_worker thread and silently dropped every later record.
                # DEVNULL has no pipe to drain, so the timeout reliably fires and the
                # except-> openpyxl fallback below runs instead of wedging forever.
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=30,
                **_hidden_process_kwargs(),
            )
            return
        except Exception as e:
            write_log(
                "Excel COM row delete failed, falling back to openpyxl: "
                f"{e}; stdout={getattr(e, 'stdout', '')}; stderr={getattr(e, 'stderr', '')}"
            )
            _after_com_failure(e, LIVE_FILE, "row_delete")
        finally:
            if 'ps_path' in locals() and ps_path and os.path.exists(ps_path):
                try:
                    os.remove(ps_path)
                except Exception:
                    pass
            if payload_path and os.path.exists(payload_path):
                try:
                    os.remove(payload_path)
                except Exception:
                    pass

    with EXCEL_WRITE_LOCK:
        if not os.path.exists(LIVE_FILE):
            return
        wb = load_workbook(LIVE_FILE)
        try:
            if sheet_name not in wb.sheetnames:
                return
            ws = wb[sheet_name]
            if int(row_idx) <= ws.max_row:
                ws.delete_rows(int(row_idx))
                save_workbook_safely(wb, LIVE_FILE, reason="row_delete")
        finally:
            wb.close()


def update_attendance_cells_in_live_workbook(sheet_name, mobile, date, name=None,
                                             rounds=None, remark=None):
    """Push a CORRECTED mark onto the row that already exists in the live workbook.

    Marking someone a second time (an operator fixing rounds/remark, a late offline
    replay) updates the database but appends nothing — the row is already on the sheet.
    Nothing then carried the new value across: the correction path enqueues an output
    refresh that writes the per-session file under Data/, not this workbook, and
    sheet_autosync only publishes to Google Sheets. Measured over 4.5 minutes on a live
    session, the Lists tab kept the pre-correction rounds/remark indefinitely while the
    Current tab showed the new ones — precisely the two-sources disagreement the sync
    badge exists to report.

    Updates ONLY the fields a re-mark can legitimately change (the same three
    session_sync compares), and only on rows that already match this person+day, so it
    can never create a row or move one. A row that is genuinely absent is left to the
    additive backfill, which is the only thing allowed to add.
    """
    from openpyxl import load_workbook

    updated = 0
    with EXCEL_WRITE_LOCK:
        if not os.path.exists(LIVE_FILE):
            return 0
        wb = load_workbook(LIVE_FILE)
        try:
            want_mobile = session_sync.norm_mobile(mobile)
            want_day = session_sync.norm_day(date)
            # The session tab AND the master log, which mirrors every row.
            titles = [t for t in (
                session_sync.resolve_sheet_title(wb.sheetnames, sheet_name),
                session_sync.resolve_sheet_title(wb.sheetnames, "MASTER_ALL_SESSIONS"),
            ) if t]
            for title in titles:
                ws = wb[title]
                for row in ws.iter_rows(min_row=2):
                    cells = list(row) + [None] * 9
                    if session_sync.norm_mobile(cells[1].value if cells[1] else None) != want_mobile:
                        continue
                    if session_sync.norm_day(cells[8].value if cells[8] else None) != want_day:
                        continue
                    if name and cells[0] is not None:
                        cells[0].value = name
                    if rounds is not None and cells[6] is not None:
                        cells[6].value = rounds
                    if remark is not None and cells[7] is not None:
                        cells[7].value = remark
                    updated += 1
            if updated:
                save_workbook_safely(wb, LIVE_FILE, reason="attendance_correction")
        finally:
            wb.close()
    return updated


def enqueue_attendance_mirror_update(workbook_row, sheet_name):
    """Queue a correction so the request returns as fast as a first-time mark does."""
    excel_sync_queue.put({
        "op": "update_cells",
        "mobile": workbook_row.get("mobile", ""),
        "name": workbook_row.get("name", ""),
        "rounds": workbook_row.get("rounds", ""),
        "remark": workbook_row.get("remark", ""),
        "date": workbook_row.get("date", datetime.now().strftime("%Y-%m-%d")),
        "session_id": workbook_row.get("session_id", ""),
        "centre_id": workbook_row.get("centre_id") or get_selected_centre_id(),
        "sheet_name": sheet_name,
        "attempt": 0,
    })


def enqueue_attendance_mirror_task(workbook_row, sheet_name):
    """Queue workbook + Google Sheets mirror work so the API can respond quickly."""
    excel_sync_queue.put({
        "name": workbook_row.get("name", "Unknown"),
        "mobile": workbook_row.get("mobile") or workbook_row.get("number", ""),
        "address": workbook_row.get("address", ""),
        "occupation": workbook_row.get("occupation", ""),
        "batch": workbook_row.get("batch", ""),
        "batch_name": workbook_row.get("batch_name", workbook_row.get("batch", "")),
        # Prefer the real session_id (used for the staleness DB lookup in
        # _handle_sync_task) over "session", which is display text and can
        # be a placeholder like "-- Select Session --".
        "session_id": workbook_row.get("session_id") or workbook_row.get("session", ""),
        "session_name": workbook_row.get("session_name", workbook_row.get("session", "")),
        "centre_id": workbook_row.get("centre_id") or get_selected_centre_id(),
        "rounds": workbook_row.get("rounds", ""),
        "remark": workbook_row.get("remark", ""),
        "date": workbook_row.get("date", datetime.now().strftime("%Y-%m-%d")),
        # Youth/Congregation: carry the category downstream to the calling-backend mirror.
        "category": workbook_row.get("category", "youth"),
        "sheet_name": sheet_name,
        "attempt": 0,
    })
    # Nudge the sheet auto-syncer so the roster/summary tabs catch up in seconds
    # rather than at the next poll tick. Fire-and-forget: it only sets a flag,
    # and the poll loop would find the change anyway if this never ran.
    try:
        import sheet_autosync
        sheet_autosync.request_refresh(
            workbook_row.get("centre_id") or get_selected_centre_id(), "attendance")
    except Exception:
        pass

def migrate_excel_to_long_format():
    """One-time conversion of existing live attendance file to the new log-based format."""
    if not os.path.exists(LIVE_FILE):
        return
        
    try:
        from openpyxl import load_workbook
        wb = load_workbook(LIVE_FILE)
        if "MASTER_ALL_SESSIONS" not in wb.sheetnames:
            wb.close()
            return
            
        ws = wb["MASTER_ALL_SESSIONS"]
        first_row = [str(cell.value).lower() for cell in ws[1]]
        
        expected_headers = [h.lower() for h in ATTENDANCE_EXCEL_HEADERS]

        # If the workbook already matches the current schema, nothing to do.
        if first_row[:len(expected_headers)] == expected_headers:
            wb.close()
            return

        print("\n" + "!"*60)
        print(" [!] MIGRATING EXCEL DATA TO NEW LONG FORMAT...")
        print("!"*60)
        
        # Backup before migration
        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
        backup = LIVE_FILE + f".wide_format.bak_{ts}"
        shutil.copy2(LIVE_FILE, backup)
        
        # We rebuild from SQLite database as it holds the source of truth for all events
        new_wb = Workbook()
        if new_wb.active: new_wb.remove(new_wb.active)
        
        headers = ATTENDANCE_EXCEL_HEADERS
        
        with app.app_context():
            # Get all attendance records ordered by time
            all_records = Attendance.query.order_by(Attendance.timestamp).all()
            
            # 1. Master Sheet
            ws_m = new_wb.create_sheet("MASTER_ALL_SESSIONS", 0)
            ws_m.append(headers)
            
            user_cache = {}
            for att in all_records:
                if att.mobile not in user_cache:
                    user_cache[att.mobile] = db.session.get(User, att.mobile)
                u = user_cache[att.mobile]
                
                # Fetch batch object to get its name
                batch_obj = db.session.get(Batch, att.batch_id) if hasattr(att, 'batch_id') and att.batch_id is not None else None
                batch_display_name = batch_obj.name if batch_obj else att.batch_name
                
                row_data = [
                    u.name if u else "Unknown",
                    str(att.mobile),
                    u.address if u else "",
                    u.occupation if u else "",
                    att.session_id,
                    batch_display_name,
                    att.rounds or "",
                    att.remark or "",
                    att.date
                ]
                ws_m.append(row_data)
                
                # 2. Add to Session Sheet
                sh_name = sanitize_sheet_name(att.session_id)
                if sh_name not in new_wb.sheetnames:
                    ws_s = new_wb.create_sheet(title=sh_name)
                    ws_s.append(headers)
                else:
                    ws_s = new_wb[sh_name]
                ws_s.append(row_data)
        
        save_workbook_safely(new_wb, LIVE_FILE, reason="migrate_long_format")
        new_wb.close()
        wb.close()
        print("[+] Migration to Long Format COMPLETE.")
        write_log("MIGRATION: live_attendance_master.xlsx migrated to LONG format.")
    except Exception as e:
        print(f"[-] Migration error: {e}")
        write_log(f"MIGRATION ERROR: {e}")


# --- Centre-Scoped Data Import ---
def import_from_data_folder():
    """
    Scans Data/<centre_id>/ subfolders for Excel files and imports them into
    CentreRoster (centre-level scope).  Optionally Data/<centre_id>/<batch_name>/
    for batch-scoped roster entries.
    Files placed directly in Data/ root are skipped with a warning.
    """
    print(f"\n[i] Scanning Data/ folder for centre-scoped Excel files...")

    data_dir = get_data_import_root()
    if not os.path.exists(data_dir):
        os.makedirs(data_dir)
        return

    # Warn about flat-root files (old behaviour no longer supported)
    root_excels = glob.glob(os.path.join(data_dir, '*.xls*'))
    if root_excels:
        print(
            f"[!] {len(root_excels)} file(s) found directly in Data/ root — "
            f"move them into Data/<centre_id>/ subfolders "
            f"(e.g. Data/centre_a_Base/). Skipping."
        )

    with app.app_context():
        active_centres = {
            c.centre_id: c.name
            for c in Centre.query.filter_by(status='active').all()
        }

    if not active_centres:
        print("[i] No active centres in DB. Skipping data import.")
        return

    # Load the import tracker once for the whole scan to prevent double-imports
    tracker_file = os.path.join(data_dir, '.imported_files.json')
    tracker = {}
    if os.path.exists(tracker_file):
        try:
            with open(tracker_file, 'r') as f:
                tracker = json.load(f)
        except Exception:
            tracker = {}

    any_found = False
    for centre_id in active_centres:
        centre_dir = os.path.join(data_dir, centre_id)
        if not os.path.exists(centre_dir):
            continue
        any_found = True

        # Centre-level Excel files (directly inside Data/<centre_id>/)
        _import_from_dir(centre_id, centre_dir, batch_id=None, batch_name=None,
                         tracker=tracker, tracker_file=tracker_file)

        # Batch-level subfolders: Data/<centre_id>/<batch_name>/
        try:
            subdirs = [e for e in os.scandir(centre_dir) if e.is_dir()]
        except OSError:
            subdirs = []

        for entry in subdirs:
            batch_name_raw = entry.name
            with app.app_context():
                # Folder names are sanitized (see get_data_import_batch_dir) so a
                # batch name like "BG 7:30 pm" lives in a "BG_7_30_pm" folder —
                # match against the sanitized form of each batch's real name,
                # not a raw string-equals against the unsanitized name.
                batch = next(
                    (b for b in Batch.query.filter_by(centre_id=centre_id).all()
                     if sanitize_storage_name(b.name) == batch_name_raw),
                    None
                )
            if batch:
                _import_from_dir(
                    centre_id, entry.path,
                    batch_id=batch.id, batch_name=batch.name,
                    tracker=tracker, tracker_file=tracker_file,
                )
            else:
                print(
                    f"[!] Subfolder '{batch_name_raw}' under Data/{centre_id}/ "
                    f"does not match any batch — skipping."
                )

    if not any_found:
        print(
            "[i] No centre subfolders found in Data/. "
            "Create Data/<centre_id>/ (e.g. Data/centre_a_Base/) "
            "and place Excel files there."
        )

    with app.app_context():
        db.session.remove()
    print("[i] Data/ import scan complete.\n")


@app.route('/api/trigger_data_import', methods=['POST'])
def api_trigger_data_import():
    """
    Host-only: re-run the Data/ folder import scan on demand.
    Added 2026-07-04 — import_from_data_folder() previously only ran once, in a
    background thread at server startup, with no periodic re-scan and no manual
    trigger anywhere. Any batch or Data/ file added after that single scan could
    only ever be picked up by a full server restart. This lets a host force a
    fresh scan immediately after dropping in new import files.
    """
    if not is_host_device() and session.get('role') != 'host':
        return jsonify({'ok': False, 'message': 'Unauthorized'}), 403
    threading.Thread(target=import_from_data_folder, daemon=True).start()
    return jsonify({'ok': True, 'message': 'Import scan started'})


def _import_from_dir(centre_id, dir_path, batch_id, batch_name, tracker, tracker_file):
    """Process all .xls/.xlsx files in dir_path into CentreRoster.

    tracker/tracker_file are passed in from import_from_data_folder so the dict is
    shared across all centre/batch calls in one scan — avoids re-reading from disk
    on every call, which could cause a file to be imported twice if two dirs are
    processed in the same run.
    """
    excel_files = [
        f for f in glob.glob(os.path.join(dir_path, '*.xls*'))
        if not os.path.basename(f).startswith('~$')
    ]
    if not excel_files:
        return

    for filepath in excel_files:
        rel_key = os.path.relpath(filepath, get_data_import_root()).replace('\\', '/')
        mtime = os.path.getmtime(filepath)
        if rel_key in tracker and tracker[rel_key] == mtime:
            print(f"  [i] Skipping {rel_key} (already imported).")
            continue

        print(f"  --> {rel_key}", end="", flush=True)
        try:
            with app.app_context():
                _process_centre_excel_import(
                    filepath, centre_id,
                    batch_id=batch_id, batch_name=batch_name
                )
            tracker[rel_key] = mtime
            with open(tracker_file, 'w') as f:
                json.dump(tracker, f)
            print(" [DONE]")
        except Exception as e:
            print(f" [FAILED: {e}]")


def _process_centre_excel_import(file, centre_id, batch_id=None, batch_name=None):
    """
    Import one Excel file into CentreRoster for centre_id (and optional batch).
    Creates a minimal User record as an FK anchor; all profile data lives in
    CentreRoster so each centre's view stays isolated.
    """
    try:
        df = pd.read_excel(file)
        if df.empty:
            return

        mob_col = next(
            (c for c in df.columns if str(c).lower() == 'mobile'), None
        )
        if mob_col is None:
            print(f"\n  Skipping {os.path.basename(file)}: no 'Mobile' column.")
            return

        scope_key   = make_roster_scope_key(batch_id)
        scope_label = f"batch '{batch_name}'" if batch_name else "centre-level"
        # Resolved once, not per row: every row of this import lands in the same batch, so
        # they all share its class. None for a centre-level import, where there is no batch
        # to read it from and NULL remains the correct, deliberate answer.
        import_row_category = category_for_batch(batch_id)

        def get_val(row, *names):
            for name in names:
                col = next(
                    (c for c in df.columns if str(c).lower() == name.lower()), None
                )
                if col is not None:
                    val = row[col]
                    if not pd.isna(val):
                        if isinstance(val, float) and val.is_integer():
                            return str(int(val))
                        res = str(val).strip()
                        return "" if res.lower() == 'nan' else res
            return ""

        loaded = updated = 0
        # Collected so the same instant push the registration path does can be fired after
        # the commit below. Only NEW rows: a re-import of people who are already on the
        # roster has nothing to tell CB that it was not told the first time.
        pushed = []

        for _, row in df.iterrows():
            mobile = normalize_import_mobile(row.get(mob_col))
            if not mobile:
                continue

            name    = get_val(row, 'Name')       or "Unknown"
            address = get_val(row, 'Address')    or None
            occ     = get_val(row, 'Occupation') or None
            rounds  = get_val(row, 'Rounds')     or None
            remark  = get_val(row, 'Remark')     or None

            # Minimal User record — exists only as FK anchor for CentreRoster/Attendance
            user = User.query.filter_by(mobile=mobile).first()
            if not user:
                user = User(mobile=mobile, name=name)
                db.session.add(user)

            # One CentreRoster row per (mobile, centre_id, scope_key)
            roster = CentreRoster.query.filter_by(
                mobile=mobile,
                centre_id=centre_id,
                scope_key=scope_key,
            ).first()

            if not roster:
                roster = CentreRoster(
                    mobile=mobile,
                    centre_id=centre_id,
                    name=name,
                    batch_id=batch_id,
                    batch_name=batch_name,
                    scope_key=scope_key,
                    address=address,
                    occupation=occ,
                    rounds=rounds,
                    remark=remark,
                    # An import INTO A BATCH does know the class, whatever the sheet says —
                    # the batch says it. This is the write that created the 77 unlabelled
                    # Centre A rows on 2026-08-21. Still None for a centre-level import.
                    category=import_row_category,
                )
                db.session.add(roster)
                loaded += 1
                pushed.append((mobile, name))
            else:
                def _fill(field, value, _r=roster):
                    if value and not getattr(_r, field, None):
                        setattr(_r, field, value)
                _fill('name', name)
                _fill('address', address)
                _fill('occupation', occ)
                _fill('rounds', rounds)
                _fill('remark', remark)
                _fill('category', import_row_category)
                if batch_id and not roster.batch_id:
                    roster.batch_id  = batch_id
                    roster.batch_name = batch_name
                updated += 1

        db.session.commit()

        # After the commit, never inside it — same rule as the inject page.
        #
        # This is the write that stranded 75 people at Centre A on 2026-08-21: an import
        # created them at 17:01 and nothing told the calling backend, so they sat invisible
        # waiting for the next daily reconcile. One durable outbox job each, drained 20 every
        # 5 seconds, so even a large import is across in a couple of minutes instead of a day.
        for _mobile, _name in pushed:
            try:
                _enqueue_cb_outbox('roster.injected', {
                    'mobile': _mobile,
                    'name': _name,
                    'centre_id': centre_id,
                    'batch_id': batch_id,
                    'category': import_row_category,
                })
            except Exception as _e:
                write_log(f"WARN import push to CB failed to enqueue for {_mobile}: {_e}")

        print(
            f"\n  [{centre_id} / {scope_label}] "
            f"{os.path.basename(file)}: +{loaded} new, ~{updated} updated"
        )
        write_log(
            f"Data import [{centre_id}/{scope_label}] "
            f"{os.path.basename(file)}: +{loaded} new, ~{updated} updated"
        )
        enqueue_excel_output_refresh(
            "refresh_student_master_excel",
            {"reason": "data_import", "source": os.path.basename(file)},
        )
        enqueue_excel_output_refresh(
            "refresh_centre_master_excel",
            {"centre_id": centre_id},
        )
        # This import is the thing that creates unlabelled roster rows: a bulk sheet rarely
        # says which class each person is in, so the rows land with no category. Label them
        # straight away while the centre still runs one class and the answer is certain,
        # rather than leaving a pile that becomes unguessable the day a second class starts.
        try:
            backfill_roster_categories(centre_id=centre_id, actor="system:excel-import")
            backfill_roster_categories_from_batch(centre_id=centre_id, actor="system:excel-import")
        except Exception as exc:
            print(f"[roster-category] post-import pass skipped: {exc}")
    except Exception as e:
        print(f"\n  Error importing {os.path.basename(file)}: {e}")
        db.session.rollback()

# ---------------------------------------------------------------------------
# Host-side Roster Inject  (design: docs/superpowers/specs/2026-08-11-roster-inject-design.md)
#
# A web front door for the same job the Data Import folders do, but drivable
# remotely, with a full preview before anything is written. Parsing/validation
# live in roster_inject.py (pure, unit-tested); everything here is auth + DB.
# ---------------------------------------------------------------------------

INJECT_MAX_UPLOAD_BYTES = 5 * 1024 * 1024


def _inject_is_host():
    """Same gate the other host-only routes use."""
    return bool(is_host_device() or session.get('role') == 'host')


def _inject_scope(centre_id, batch_id):
    """Resolve (batch, scope_key, category) for a target scope, or raise ValueError.

    A batch must belong to the chosen centre — otherwise a host could tag people
    into another centre's batch, which centre_roster's unique key would happily allow.
    """
    centre = Centre.query.filter_by(centre_id=centre_id).first()
    if not centre:
        raise ValueError("Unknown centre")

    batch = None
    if batch_id:
        batch = Batch.query.filter_by(id=int(batch_id), centre_id=centre_id).first()
        if not batch:
            raise ValueError("That batch does not belong to the selected centre")

    return centre, batch, make_roster_scope_key(batch.id if batch else None)


def _inject_existing_mobiles(centre_id, scope_key):
    """Mobiles already present in THIS scope.

    Scoped to (centre, scope_key) deliberately, NOT to the whole centre: 1,525 of
    the roster's rows are centre-level, so a centre-wide check would make a batch
    inject a silent no-op for anyone already known to the centre.
    """
    rows = db.session.query(CentreRoster.mobile).filter_by(
        centre_id=centre_id, scope_key=scope_key
    ).all()
    return {r[0] for r in rows}


def _inject_prepare(payload_rows, centre_id, scope_key):
    """Annotate rows with validity + whether they already exist in the target scope."""
    annotated = roster_inject.annotate(payload_rows)
    existing = _inject_existing_mobiles(centre_id, scope_key)

    for row in annotated:
        mobile = row.get("normalized_mobile")
        row["already_here"] = bool(mobile and mobile in existing)
        if row["invalid_reason"]:
            row["status"] = "invalid"
        elif row["already_here"]:
            row["status"] = "skip"
        else:
            row["status"] = "new"

    summary = roster_inject.summarize(annotated)
    summary["already_here"] = sum(1 for r in annotated if r["status"] == "skip")
    summary["new"] = sum(1 for r in annotated if r["status"] == "new")
    return annotated, summary


@app.route('/inject', methods=['GET'])
def inject_page():
    if not _inject_is_host():
        return redirect('/login')
    return render_template('inject.html')


@app.route('/api/inject/preview', methods=['POST'])
def api_inject_preview():
    """Parse + map + validate. Writes NOTHING."""
    if not _inject_is_host():
        return jsonify({"error": "Forbidden"}), 403

    upload = request.files.get('file')
    if upload is not None:
        centre_id = (request.form.get('centre_id') or '').strip()
        batch_id = (request.form.get('batch_id') or '').strip() or None
        raw = upload.read(INJECT_MAX_UPLOAD_BYTES + 1)
        if len(raw) > INJECT_MAX_UPLOAD_BYTES:
            return jsonify({"errors": ["That file is larger than 5 MB."]}), 400
        if not raw:
            return jsonify({"errors": ["That file is empty."]}), 400
        try:
            headers, body = roster_inject.parse_excel(raw)
        except Exception as e:
            return jsonify({"errors": [f"Could not read that file: {e}"]}), 400

        # Escape hatch for a sheet with no heading row: treat row 1 as data and
        # give the columns positional names, so a real person isn't eaten as a header.
        if (request.form.get('first_row_is_data') or '') in ('1', 'true', 'yes'):
            body = [headers] + body
            headers = [f"Column {i + 1}" for i in range(len(headers))]

        analysis = roster_inject.analyze_headers(headers, body)
        mapping = analysis["mapping"]

        # A host-supplied mapping always wins over the guess — this is the manual
        # override, and it must be able to correct the brain, not be re-guessed.
        override = request.form.get('mapping')
        if override:
            try:
                manual = {k: (int(v) if v not in (None, '', 'null') else None)
                          for k, v in json.loads(override).items()}
                for field, idx in manual.items():
                    if field in roster_inject.FIELDS:
                        mapping[field] = idx
                        analysis["confidence"][field] = "manual" if idx is not None else "none"
                taken = {i for i in mapping.values() if i is not None}
                analysis["unmapped_columns"] = [
                    {"index": i, "header": headers[i] if i < len(headers) else ""}
                    for i in range(len(headers)) if i not in taken
                ]
                analysis["needs_review"] = mapping.get("mobile") is None
                analysis["notes"] = ([] if mapping.get("mobile") is not None
                                     else ["No phone-number column is mapped yet."])
            except Exception:
                pass

        errors = []
        if mapping.get('mobile') is None:
            errors.append("No phone-number column is mapped — point one at Number below.")
        rows = roster_inject.rows_from_table(body, mapping) if not errors else []
    else:
        data = request.get_json(silent=True) or {}
        centre_id = (data.get('centre_id') or '').strip()
        batch_id = data.get('batch_id') or None
        # Paste mode maps itself — one textarea per field — so there is nothing to guess.
        headers, mapping, analysis = [], None, None
        rows, errors = roster_inject.rows_from_columns(data.get('columns') or {})

    try:
        centre, batch, scope_key = _inject_scope(centre_id, batch_id)
    except ValueError as e:
        return jsonify({"errors": [str(e)]}), 400

    if errors:
        return jsonify({"errors": errors, "headers": headers, "mapping": mapping,
                        "analysis": analysis, "rows": [], "summary": None})

    annotated, summary = _inject_prepare(rows, centre_id, scope_key)
    return jsonify({
        "errors": [],
        "headers": headers,
        "mapping": mapping,
        "analysis": analysis,
        "fields": roster_inject.FIELDS,
        "field_labels": roster_inject.FIELD_LABELS,
        "rows": annotated,
        "summary": summary,
        "scope": {
            "centre_id": centre_id,
            "centre_name": centre.name,
            "batch_id": batch.id if batch else None,
            "batch_name": batch.name if batch else None,
            "category": (batch.category if batch else None),
        },
    })


@app.route('/api/inject/commit', methods=['POST'])
def api_inject_commit():
    """Write the roster rows, then fire calling-backend events.

    Events are enqueued AFTER the DB commit, never inside the transaction: a CB
    outage must not be able to roll back or duplicate a roster write.
    """
    if not _inject_is_host():
        return jsonify({"error": "Forbidden"}), 403

    data = request.get_json(silent=True) or {}
    centre_id = (data.get('centre_id') or '').strip()
    batch_id = data.get('batch_id') or None
    rows = data.get('rows') or []

    try:
        centre, batch, scope_key = _inject_scope(centre_id, batch_id)
    except ValueError as e:
        return jsonify({"error": str(e)}), 400

    category = (batch.category if batch else (data.get('category') or '').strip().lower()) or None
    if not batch and category not in ('youth', 'congregation'):
        return jsonify({"error": "Pick a category for a centre-level inject."}), 400

    # Re-run the skip check against live data — the roster may have moved between
    # the preview call and this one.
    existing = _inject_existing_mobiles(centre_id, scope_key)

    injected, skipped, invalid = [], 0, 0
    seen = set()

    try:
        for raw in rows:
            mobile = normalize_import_mobile(raw.get('mobile'))
            if not mobile:
                invalid += 1
                continue
            if mobile in existing or mobile in seen:
                skipped += 1
                continue
            seen.add(mobile)

            def _clean(field):
                value = str(raw.get(field) or '').strip()
                return value or None

            # centre_roster.name is NOT NULL, so a nameless row stores "Unknown".
            # The attendance path overwrites it with the real name on first mark,
            # which is exactly the workflow the user described.
            name = str(raw.get('name') or '').strip() or "Unknown"

            user = User.query.filter_by(mobile=mobile).first()
            if not user:
                db.session.add(User(mobile=mobile, name=name))

            db.session.add(CentreRoster(
                mobile=mobile,
                centre_id=centre_id,
                name=name,
                batch_id=batch.id if batch else None,
                batch_name=batch.name if batch else None,
                scope_key=scope_key,
                address=_clean('address'),
                occupation=_clean('occupation'),
                rounds=_clean('rounds'),
                remark=_clean('remark'),
                category=category,
                status="active",
            ))
            injected.append((mobile, name))

        db.session.commit()
    except Exception as e:
        db.session.rollback()
        write_log(f"ERROR roster inject failed centre={centre_id} batch={batch_id}: {e}")
        return jsonify({"error": f"Nothing was written — {e}"}), 500

    scope_label = f"batch '{batch.name}'" if batch else "centre-level"
    write_log(
        f"Roster inject [{centre_id}/{scope_label}]: +{len(injected)} new, "
        f"{skipped} skipped, {invalid} invalid"
    )

    # After the commit, never inside it.
    #
    # `name` is sent even though CB could look it up, because that lookup goes through a
    # 60-second person-directory cache. Someone written here moments ago is absent from a
    # cache built before they existed, so a second import inside that window made CB store
    # the bare phone number as their PERMANENT display name (it never re-resolves once a
    # registration exists) — callers saw a number instead of a person. We know the name
    # right here; send it.
    for mobile, name in injected:
        _enqueue_cb_outbox('roster.injected', {
            'mobile': mobile,
            'name': name,
            'centre_id': centre_id,
            'batch_id': batch.id if batch else None,
            'category': category,
        })

    if injected:
        enqueue_excel_output_refresh(
            "refresh_student_master_excel",
            {"reason": "roster_inject", "source": f"{centre_id}/{scope_label}"},
        )
        enqueue_excel_output_refresh("refresh_centre_master_excel", {"centre_id": centre_id})

    return jsonify({
        "ok": True,
        "injected": len(injected),
        "skipped": skipped,
        "invalid": invalid,
        "centre_name": centre.name,
        "batch_name": batch.name if batch else None,
    })


def perform_startup_backup():
    """Create a physical backup of the master Excel file."""
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    backup_path = os.path.join(BACKUP_DIR, f"backup_{timestamp}.xlsx")
    
    if not os.path.exists(LIVE_FILE):
        return
    try:
        shutil.copy2(LIVE_FILE, backup_path)
        print(f"[+] Backup created: {backup_path}")
    except: pass

def get_client_ip():
    """Helper to get the real client IP, even behind Cloudflare or proxies."""
    # Check Cloudflare header first
    cf_ip = request.headers.get("CF-Connecting-IP")
    if cf_ip:
        return cf_ip
    
    # Check X-Forwarded-For
    x_forwarded = request.headers.get("X-Forwarded-For")
    if x_forwarded:
        # It can be a comma-separated list, the first one is the client
        return x_forwarded.split(',')[0].strip()
        
    return request.remote_addr

# --- Access Control Helpers ---
def is_host_device():
    """Determine if the requester is the actual Host computer."""
    # If it came through Cloudflare Tunnel or proxy headers, it's external by definition
    if request.headers.get("CF-Connecting-IP") or request.headers.get("X-Forwarded-For"):
        return False
    
    client_ip = request.remote_addr # We use remote_addr here because host is ALWAYS local
    if client_ip in ["127.0.0.1", "::1", "localhost"]:
        return True
        
    # Check if LAN IP matches the machine's own LAN IP
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        host_ip = s.getsockname()[0]
        s.close()
        if client_ip == host_ip:
            return True
    except:
        pass
        
    return False

# ── CB outbox — SQLite-backed, survives AE restarts (Phase 3) ────────────────
#
# Table: calling_backend_outbox
# Columns: id, kind, payload (JSON), status, attempts, next_attempt, last_error,
#          created_at, updated_at
#
# Worker polls every 5s, drains up to 20 pending jobs per tick.
# Backoff: 1s → 5s → 30s → 5m → 30m (capped). Max 10 attempts → status='failed'.

_CB_OUTBOX_DB_PATH = None
# Cloudflare sits in front of the public calling-backend hostname and BLOCKS the default
# "Python-urllib/x.y" User-Agent with a 403. Every AE->CB outbox job died on that for weeks and
# the error read "SRKB responded 403", which looks like an auth failure and is not — the request
# never reached SRKB. Always send a real UA.
_CB_HTTP_UA = "AttendEase-Sync/1.0"
_CB_OUTBOX_BACKOFF = [1, 5, 30, 300, 1800]
_CB_OUTBOX_MAX_ATTEMPTS = 10


def _get_cb_outbox_db_path():
    global _CB_OUTBOX_DB_PATH
    if _CB_OUTBOX_DB_PATH is None:
        _CB_OUTBOX_DB_PATH = app.config['SQLALCHEMY_DATABASE_URI'].replace('sqlite:///', '')
    return _CB_OUTBOX_DB_PATH


def _ensure_cb_outbox_table():
    """Create calling_backend_outbox table if it doesn't exist. Idempotent."""
    import sqlite3 as _sqlite3
    conn = _sqlite3.connect(_get_cb_outbox_db_path())
    conn.execute("""
        CREATE TABLE IF NOT EXISTS calling_backend_outbox (
            id           INTEGER PRIMARY KEY AUTOINCREMENT,
            kind         TEXT    NOT NULL,
            payload      TEXT    NOT NULL,
            status       TEXT    NOT NULL DEFAULT 'pending',
            attempts     INTEGER NOT NULL DEFAULT 0,
            next_attempt REAL    NOT NULL,
            last_error   TEXT,
            created_at   REAL    NOT NULL DEFAULT (unixepoch()),
            updated_at   REAL    NOT NULL DEFAULT (unixepoch())
        )
    """)
    conn.execute("CREATE INDEX IF NOT EXISTS idx_cbo_status_next ON calling_backend_outbox(status, next_attempt)")
    conn.commit()
    conn.close()


def _enqueue_cb_outbox(kind, payload):
    """Enqueue a job to the SQLite-backed CB outbox."""
    import sqlite3 as _sqlite3, json as _json, time as _time
    next_attempt = _time.time() + _CB_OUTBOX_BACKOFF[0]
    try:
        conn = _sqlite3.connect(_get_cb_outbox_db_path())
        conn.execute(
            "INSERT INTO calling_backend_outbox (kind, payload, status, attempts, next_attempt) VALUES (?,?,?,?,?)",
            (kind, _json.dumps(payload), 'pending', 0, next_attempt)
        )
        conn.commit()
        conn.close()
        write_log(f"INFO outbox: kind={kind} -> pending (enqueued)")
    except Exception as e:
        write_log(f"WARN outbox: enqueue failed kind={kind} error={e}")


def _cb_outbox_process_job(job_id, kind, payload_str, attempts):
    """Process one outbox job. Raises on failure so caller can update status."""
    import json as _json
    payload = _json.loads(payload_str)
    cb_base = CALLING_BACKEND_BASE_URL
    token = CALLING_BACKEND_INTERNAL_TOKEN

    if kind == 'sync_password_to_cb':
        import urllib.request as _ur, urllib.error as _ue
        body_dict = {
            'username': payload['username'],
            'password_updated_at': payload['password_updated_at'],
        }
        if 'password_hash' in payload:
            body_dict['password_hash'] = payload['password_hash']
        if 'plain_password' in payload:
            body_dict['plain_password'] = payload['plain_password']
        if 'must_change' in payload:
            body_dict['must_change'] = payload['must_change']
        body = _json.dumps(body_dict).encode()
        req = _ur.Request(
            f"{cb_base}/internal/auth/sync-password",
            data=body,
            headers={'Content-Type': 'application/json', 'x-internal-token': token,
                     'User-Agent': _CB_HTTP_UA},
            method='POST'
        )
        try:
            with _ur.urlopen(req, timeout=5) as resp:
                status_code = resp.status
        except _ue.HTTPError as e:
            status_code = e.code
            if status_code == 404:
                # CB doesn't know this user — log WARN, don't retry
                write_log(f"WARN sync_password: direction=ae_to_cb username={payload.get('username')} result=not_found_in_cb — no retry")
                return 'no_retry'
            raise Exception(f"CB responded {status_code}")
        except Exception as e:
            raise Exception(f"CB unreachable: {e}")
    elif kind in ('attendance.persisted', 'session_attendance_recorded',
                  # Host-side Roster Inject. CB already picks roster rows up via its
                  # 24h reconcile (reconcileRosterMembers -> onRosterMember); this event
                  # exists purely to make an inject callable IMMEDIATELY instead of
                  # tomorrow. The reconcile stays as the safety net behind it.
                  'roster.injected',
                  'batch.created', 'batch.renamed', 'batch.archived',
                  'batch.category_set', 'batch.default_set',
                  'session.created', 'session.deleted',
                  'registerer.account_created', 'registerer.account_approved',
                  'registerer.access_granted', 'registerer.access_revoked',
                  'centre.created', 'centre.renamed', 'centre.status_changed', 'centre.deleted'):
        # Forward generic attendease events to SRKB /internal/attendease-event
        import urllib.request as _ur, urllib.error as _ue
        import datetime as _dt_mod
        # Reconstruct the webhook envelope that SRKB expects
        entity_id = str(
            payload.get('session_id') or
            payload.get('ae_session_id') or
            payload.get('batch_id') or
            payload.get('centre_id') or
            payload.get('username') or '0'
        )
        envelope = _json.dumps({
            'event_type': kind,
            'entity_type': kind.split('.')[0] if '.' in kind else kind,
            'entity_id': entity_id,
            'payload': payload,
            'fired_at': _dt_mod.datetime.utcnow().isoformat() + 'Z',
        }).encode()
        req = _ur.Request(
            f"{cb_base}/internal/attendease-event",
            data=envelope,
            headers={'Content-Type': 'application/json', 'x-internal-token': token,
                     'User-Agent': _CB_HTTP_UA},
            method='POST'
        )
        try:
            with _ur.urlopen(req, timeout=10) as resp:
                write_log(f"INFO outbox: kind={kind} -> forwarded to SRKB ({resp.status})")
        except _ue.HTTPError as e:
            raise Exception(f"SRKB responded {e.code}")
        except Exception as e:
            raise Exception(f"SRKB unreachable: {e}")
    else:
        write_log(f"WARN outbox: unknown kind={kind} — skipping")
        return 'unknown_kind'

_CB_OUTBOX_REVIVE_EVERY = 900          # seconds between recovery sweeps
_CB_OUTBOX_LAST_REVIVE = 0.0


def _cb_outbox_revive_failed():
    """Bring permanently-'failed' jobs back once the calling backend is healthy again.

    A job gives up after _CB_OUTBOX_MAX_ATTEMPTS, and nothing ever looked at it again — so an
    outage longer than the retry ladder (~3.5h) silently and PERMANENTLY dropped those events.
    That is exactly what happened over 11-28 July: 79 attendance events died against a 403 and
    stayed dead long after the endpoint recovered.

    Only jobs that exhausted the ladder are revived (attempts >= MAX). Jobs failed by an explicit
    'unknown_kind' / 'no_retry' verdict never reach that count, so a deliberate decision is not
    second-guessed here. Gated on a real /health check plus a cooldown, so a genuinely poisonous
    job cannot spin: it simply gives up again and waits for the next sweep.
    """
    global _CB_OUTBOX_LAST_REVIVE
    import time as _time, sqlite3 as _sqlite3
    import urllib.request as _ur

    now = _time.time()
    if now - _CB_OUTBOX_LAST_REVIVE < _CB_OUTBOX_REVIVE_EVERY:
        return
    _CB_OUTBOX_LAST_REVIVE = now

    conn = None
    try:
        conn = _sqlite3.connect(_get_cb_outbox_db_path(), timeout=10)
        stuck = conn.execute(
            "SELECT COUNT(*) FROM calling_backend_outbox "
            "WHERE status='failed' AND attempts >= ? AND last_error IS NOT NULL",
            (_CB_OUTBOX_MAX_ATTEMPTS,)
        ).fetchone()[0]
    except Exception:
        stuck = 0
    finally:
        if conn:
            conn.close()
    if not stuck:
        return

    # Don't resurrect into a still-broken destination — that just burns the ladder again.
    try:
        req = _ur.Request(f"{CALLING_BACKEND_BASE_URL.rstrip('/')}/health",
                          headers={'User-Agent': _CB_HTTP_UA}, method='GET')
        with _ur.urlopen(req, timeout=8) as resp:
            if resp.status != 200:
                return
    except Exception:
        return

    conn = None
    try:
        conn = _sqlite3.connect(_get_cb_outbox_db_path(), timeout=10)
        cur = conn.execute(
            "UPDATE calling_backend_outbox SET status='pending', attempts=0, next_attempt=?, "
            "updated_at=? WHERE status='failed' AND attempts >= ? AND last_error IS NOT NULL",
            (now, now, _CB_OUTBOX_MAX_ATTEMPTS)
        )
        conn.commit()
        write_log(f"INFO outbox: calling backend healthy again - revived {cur.rowcount} given-up job(s) for retry")
    except Exception as e:
        write_log(f"WARN outbox: revive sweep failed: {e}")
    finally:
        if conn:
            conn.close()


def _cb_outbox_worker():
    while True:
        _cb_outbox_worker_tick()
        _cb_outbox_revive_failed()


def _cb_outbox_worker_tick():
    import time as _time, sqlite3 as _sqlite3
    try:
        now = _time.time()
        conn = None
        try:
            conn = _sqlite3.connect(_get_cb_outbox_db_path(), timeout=10)
            rows = conn.execute(
                "SELECT id, kind, payload, attempts FROM calling_backend_outbox "
                "WHERE status='pending' AND next_attempt <= ? ORDER BY next_attempt LIMIT 20",
                (now,)
            ).fetchall()
        finally:
            if conn:
                conn.close()

        for job_id, kind, payload_str, attempts in rows:
            try:
                job_payload = json.loads(payload_str)
            except Exception:
                job_payload = {"_raw": payload_str}
            try:
                result = _cb_outbox_process_job(job_id, kind, payload_str, attempts)
                new_status = 'failed' if result in ('unknown_kind', 'no_retry') else 'sent'
                conn2 = None
                try:
                    conn2 = _sqlite3.connect(_get_cb_outbox_db_path(), timeout=10)
                    conn2.execute(
                        "UPDATE calling_backend_outbox SET status=?, updated_at=? WHERE id=?",
                        (new_status, _time.time(), job_id)
                    )
                    conn2.commit()
                finally:
                    if conn2:
                        conn2.close()
                write_log(f"INFO outbox: id={job_id} kind={kind} pending -> {new_status}")
                journal_write("AE", "AE->CB", "outbox", kind, "success" if new_status == "sent" else "failed",
                              entity=job_payload.get("username") or job_payload.get("ae_session_id") or job_payload.get("centre_id"),
                              detail=f"job_id={job_id} result={result}", payload=job_payload,
                              attempt=attempts + 1, source="_cb_outbox_worker_tick")
            except Exception as err:
                new_attempts = attempts + 1
                err_msg = str(err)[:500]
                entity_id = job_payload.get("username") or job_payload.get("ae_session_id") or job_payload.get("centre_id")
                if new_attempts >= _CB_OUTBOX_MAX_ATTEMPTS:
                    write_log(f"WARN outbox: id={job_id} kind={kind} gave up after {new_attempts} attempts: {err_msg}")
                    conn2 = None
                    try:
                        conn2 = _sqlite3.connect(_get_cb_outbox_db_path(), timeout=10)
                        conn2.execute(
                            "UPDATE calling_backend_outbox SET status='failed', attempts=?, last_error=?, updated_at=? WHERE id=?",
                            (new_attempts, err_msg, _time.time(), job_id)
                        )
                        conn2.commit()
                    finally:
                        if conn2:
                            conn2.close()
                    journal_write("AE", "AE->CB", "outbox", kind, "needs_attention",
                                  entity=entity_id, detail=f"job_id={job_id} gave up after {new_attempts} attempts — replay payload against the same sync path to recover",
                                  payload=job_payload, error=err_msg, attempt=new_attempts, source="_cb_outbox_worker_tick")
                    # Phase 4 will fire the failure notification from here
                    _notify_password_sync_failed_to_cb(kind, payload_str, err_msg)
                else:
                    backoff = _CB_OUTBOX_BACKOFF[new_attempts] if new_attempts < len(_CB_OUTBOX_BACKOFF) else _CB_OUTBOX_BACKOFF[-1]
                    next_attempt = _time.time() + backoff
                    write_log(f"WARN outbox: id={job_id} kind={kind} attempt {new_attempts} failed: {err_msg} - retry in {backoff}s")
                    conn2 = None
                    try:
                        conn2 = _sqlite3.connect(_get_cb_outbox_db_path(), timeout=10)
                        conn2.execute(
                            "UPDATE calling_backend_outbox SET attempts=?, next_attempt=?, last_error=?, updated_at=? WHERE id=?",
                            (new_attempts, next_attempt, err_msg, _time.time(), job_id)
                        )
                        conn2.commit()
                    finally:
                        if conn2:
                            conn2.close()
                    journal_write("AE", "AE->CB", "outbox", kind, "failed",
                                  entity=entity_id, detail=f"job_id={job_id} retrying in {backoff}s",
                                  payload=job_payload, error=err_msg, attempt=new_attempts, source="_cb_outbox_worker_tick")
    except Exception as tick_err:
        write_log(f"WARN outbox: tick error: {tick_err}")
    _time.sleep(5)


def _notify_password_sync_failed_to_cb(kind, payload_str, last_error):
    """POST to CB to create password-sync-failed notifications for centre admins + hosts."""
    import json as _json, urllib.request as _ur, urllib.error as _ue
    try:
        payload = _json.loads(payload_str)
        username = payload.get('username', 'unknown')
        body = _json.dumps({
            'affected_username': username,
            'direction': 'ae_to_cb',
            'last_error': str(last_error)[:500],
        }).encode()
        req = _ur.Request(
            f"{CALLING_BACKEND_BASE_URL}/internal/notifications/password-sync-failed",
            data=body,
            headers={'Content-Type': 'application/json', 'x-internal-token': CALLING_BACKEND_INTERNAL_TOKEN},
            method='POST'
        )
        with _ur.urlopen(req, timeout=5) as r:
            write_log(f"WARN password_sync_failed: username={username} direction=ae_to_cb CB notified status={r.status}")
    except Exception as e:
        write_log(f"WARN password_sync_failed: CB notification POST failed: {e}")


# ── AE-access reconciler (revoke direction) ─────────────────────────────────
# Complements CB's src/modules/sync/ae-access-reconcile.ts, which independently
# verifies the grant/create direction (does AE have the login CB says it should).
# This is AE's own check of the opposite direction: does AE still grant access
# that CB no longer authorizes. Running it here too — not relying solely on CB's
# side — means a bug or outage in CB's reconciler doesn't leave stale AE access
# active indefinitely. Added 2026-07-19 after a code path in CB silently skipped
# the sync step that should have provisioned/revoked AE access; see AGENT_MEMORY.

_AE_ACCESS_HEALTH_SUMMARY = {}

def _ae_access_reconcile_tick():
    """Revoke any AE centre_access still active for a CB-managed username that CB
    no longer authorizes (deleted, deactivated, rejected, or ae_access toggled off).
    Usernames not present in CB's users table are untouched — they're AE-native
    registerer accounts, not CB-managed, and out of scope for this check."""
    import sqlite3 as _sqlite3
    summary = {"ranAt": datetime.now().isoformat(), "revoked": 0, "errors": [], "staleOutboxJobs": 0}
    cb_db_path = os.environ.get("CALLING_BACKEND_DB_PATH", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "calling-backend", "prisma", "dev.db"))
    if not os.path.exists(cb_db_path):
        write_log("WARN ae_access_health: CB db not found, skipping")
        _AE_ACCESS_HEALTH_SUMMARY.update(summary)
        return summary

    try:
        cb_conn = _sqlite3.connect(cb_db_path)
        cb_users = {
            row[0]: row for row in cb_conn.execute(
                "SELECT mobile, approval_status, is_active, attendance_system_access FROM users"
            ).fetchall()
        }
        cb_conn.close()

        db_path = app.config['SQLALCHEMY_DATABASE_URI'].replace('sqlite:///', '')
        conn = _sqlite3.connect(db_path)
        access_rows = conn.execute(
            "SELECT username, centre_id FROM centre_access WHERE status='active'"
        ).fetchall()

        for username, centre_id in access_rows:
            cb_row = cb_users.get(username)
            if not cb_row:
                continue
            _mobile, approval_status, is_active, ae_access = cb_row
            should_have = (approval_status == 'approved' and bool(is_active) and bool(ae_access))
            if should_have:
                continue
            try:
                conn.execute(
                    "UPDATE centre_access SET status='revoked', updated_at=? WHERE username=? AND centre_id=?",
                    (datetime.now().isoformat(), username, centre_id)
                )
                conn.commit()
                summary["revoked"] += 1
                write_log(f"INFO ae_access_health: revoked stale access username={username} centre={centre_id} (CB no longer grants it)")
                journal_write("AE", "internal", "reconcile", "ae_access_revoke", "self_healed",
                              entity=username, detail=f"revoked AE centre_access for {centre_id} — CB no longer grants it",
                              payload={"username": username, "centre_id": centre_id}, source="_ae_access_reconcile_tick")
            except Exception as e:
                summary["errors"].append({"username": username, "error": str(e)})
                write_log(f"WARN ae_access_health: revoke failed username={username}: {e}")
                journal_write("AE", "internal", "reconcile", "ae_access_revoke", "failed",
                              entity=username, payload={"username": username, "centre_id": centre_id},
                              error=e, source="_ae_access_reconcile_tick")
        conn.close()

        # Visibility only: AE->CB jobs that already exhausted retries need a human.
        conn3 = _sqlite3.connect(_get_cb_outbox_db_path())
        stale = conn3.execute(
            "SELECT id, kind, last_error FROM calling_backend_outbox WHERE status='failed' ORDER BY id DESC LIMIT 20"
        ).fetchall()
        conn3.close()
        summary["staleOutboxJobs"] = len(stale)
        if stale:
            write_log(f"WARN ae_access_health: {len(stale)} permanently-failed AE->CB outbox job(s)")
            for job_id, kind, last_error in stale:
                write_log(f"WARN ae_access_health:   job {job_id} ({kind}): {last_error}")
    except Exception as e:
        write_log(f"WARN ae_access_health: tick error: {e}")
        summary["errors"].append({"username": None, "error": str(e)})
        journal_write("AE", "internal", "reconcile", "ae_access_reconcile_tick", "failed", error=e, source="_ae_access_reconcile_tick")

    _AE_ACCESS_HEALTH_SUMMARY.clear()
    _AE_ACCESS_HEALTH_SUMMARY.update(summary)
    write_log(f"INFO ae_access_health: done revoked={summary['revoked']} errors={len(summary['errors'])} staleOutboxJobs={summary['staleOutboxJobs']}")
    journal_write("AE", "internal", "reconcile", "ae_access_reconcile_run", "success" if not summary["errors"] else "needs_attention",
                  detail=f"revoked={summary['revoked']} errors={len(summary['errors'])} staleOutboxJobs={summary['staleOutboxJobs']}",
                  payload=summary, source="_ae_access_reconcile_tick")
    return summary


def _ae_access_reconcile_worker():
    import time as _time
    _time.sleep(20)
    while True:
        try:
            _ae_access_reconcile_tick()
        except Exception as e:
            write_log(f"WARN ae_access_health: worker error: {e}")
        _time.sleep(180)


@app.route('/api/internal/ae_access_health', methods=['GET'])
def api_internal_ae_access_health():
    """Host-only visibility into the last AE-side access reconcile run."""
    if not is_host_device() and session.get('role') != 'host':
        return jsonify({"error": "Forbidden"}), 403
    return jsonify(_AE_ACCESS_HEALTH_SUMMARY or {"ranAt": None, "message": "reconcile has not run yet"})


# ── Unified auth helpers (Phase 2) ───────────────────────────────────────────

_JWT_SHARED_SECRET = None

def _get_jwt_secret():
    global _JWT_SHARED_SECRET
    if _JWT_SHARED_SECRET is None:
        _JWT_SHARED_SECRET = os.environ.get('JWT_SHARED_SECRET', '')
        if not _JWT_SHARED_SECRET:
            write_log("WARN: JWT_SHARED_SECRET is not set — JWT auth will always fail")
    return _JWT_SHARED_SECRET

def validate_jwt(token):
    """Verify a JWT signed with the shared secret. Returns decoded payload or None."""
    secret = _get_jwt_secret()
    if not secret:
        return None
    try:
        payload = _pyjwt.decode(token, secret, algorithms=['HS256'])
        return payload
    except _pyjwt.ExpiredSignatureError:
        write_log(f"WARN jwt_invalid: reason=expired")
        return None
    except _pyjwt.InvalidTokenError as e:
        write_log(f"WARN jwt_invalid: reason={e}")
        return None

def _ae_hash_password(password):
    """Hash a password with scrypt. Format: hex_salt:hex_derived_key (matches CB)."""
    import secrets as _secrets
    salt = _secrets.token_hex(16)
    derived = hashlib.scrypt(password.encode(), salt=salt.encode(), n=16384, r=8, p=1, dklen=64)
    return f"{salt}:{derived.hex()}"

def _ae_verify_password(password, stored_hash):
    """Verify a scrypt hash produced by _ae_hash_password or CB's hashPassword."""
    if not stored_hash or ':' not in stored_hash:
        return False
    salt, derived_hex = stored_hash.split(':', 1)
    try:
        expected = hashlib.scrypt(password.encode(), salt=salt.encode(), n=16384, r=8, p=1, dklen=64)
        actual = bytes.fromhex(derived_hex)
        # Constant-time comparison
        import hmac as _hmac
        return _hmac.compare_digest(expected, actual)
    except Exception:
        return False

def _ae_issue_jwt(username, role='registerer'):
    """Sign and return a JWT with sub=username, role, 7-day expiry."""
    import time as _time
    payload = {
        'sub': username,
        'role': role,
        'iat': int(_time.time()),
        'exp': int(_time.time()) + 7 * 24 * 3600,
    }
    return _pyjwt.encode(payload, _get_jwt_secret(), algorithm='HS256')

def _get_jwt_user_from_request():
    """Extract and validate JWT from Authorization header. Returns (username, role) or (None, None)."""
    auth = request.headers.get('Authorization', '')
    if not auth.startswith('Bearer '):
        return None, None
    token = auth[7:].strip()
    payload = validate_jwt(token)
    if not payload:
        return None, None
    username = payload.get('sub')
    role = payload.get('role', 'registerer')
    return username, role

@app.after_request
def log_error_responses(response):
    """Log all API requests; also log 4xx/5xx error details."""
    # The Android wrapper's offline bundle (Capacitor local origin) calls
    # these APIs cross-origin once reconnected — JWT auth already gates who
    # can call them, but without Access-Control-Allow-Origin on the actual
    # response (not just the OPTIONS preflight) the browser blocks the JS
    # from ever reading the response body, so a successfully-processed
    # request (e.g. attendance actually marked, queue actually flushed)
    # still looked like a failure client-side and got queued/retried again.
    if request.path.startswith('/api/'):
        origin = request.headers.get('Origin')
        if origin:
            response.headers['Access-Control-Allow-Origin'] = origin
    try:
        code = response.status_code
        duration_ms = round((time.time() - getattr(g, 't_start', time.time())) * 1000)
        username = getattr(g, 'log_user', 'anon')

        # Log every API request to the daily attendance API log
        if request.path.startswith('/api/'):
            extra = ''
            if code >= 400:
                try:
                    extra = 'body=' + response.get_data(as_text=True)[:200].replace('\n', ' ')
                except Exception:
                    pass
            write_api_log(request.method, request.path, code, duration_ms, username, extra)

        # Error-level write_log for non-API 4xx/5xx (keeps existing error log behaviour)
        if code >= 400 and not (code == 404 and request.path.startswith('/static/')):
            if not request.path.startswith('/api/'):
                body = ''
                write_log(f"HTTP {code} | {request.method} {request.path} | IP={get_client_ip()}{body}")

        # Warn on unexpected HTML from API routes
        if request.path.startswith('/api/') and code < 400:
            ct = response.content_type or ''
            if 'html' in ct:
                try:
                    body = response.get_data(as_text=True)[:200].replace('\n', ' ')
                except Exception:
                    body = '?'
                write_log(f"WARN: API returned HTML on {request.method} {request.path} | {code} | {body}")

        # Cross-system sync journal: every CB->AE internal call, generically —
        # covers all current /api/internal/* endpoints (and any added later)
        # from one place instead of instrumenting each handler individually.
        if request.path.startswith('/api/internal/'):
            try:
                req_body = request.get_json(silent=True) or {}
            except Exception:
                req_body = {}
            try:
                resp_body = json.loads(response.get_data(as_text=True))
            except Exception:
                resp_body = None
            entity = (req_body or {}).get('username') or (req_body or {}).get('centre_code') or (req_body or {}).get('old_username')
            if code >= 400:
                status = 'failed'
            elif isinstance(resp_body, dict) and resp_body.get('skipped'):
                status = 'skipped'
            else:
                status = 'success'
            journal_write("AE", "CB->AE", "endpoint", request.path, status,
                          entity=entity, detail=f"http {code}" + (f" skipped={resp_body.get('skipped')}" if isinstance(resp_body, dict) and resp_body.get('skipped') else ""),
                          payload=req_body, error=(resp_body.get('error') if isinstance(resp_body, dict) and code >= 400 else None),
                          source=request.path)
    except Exception:
        pass
    return response


@app.errorhandler(500)
def handle_500(e):
    """Log 500 errors with full traceback."""
    try:
        tb = _traceback.format_exc()
        write_log(f"SERVER ERROR 500 on {request.method} {request.path}\n{tb}")
    except Exception:
        pass
    # Return JSON for API routes, HTML for page routes
    if request.path.startswith('/api/'):
        return jsonify({"error": "Internal server error", "detail": str(e)}), 500
    from flask import make_response
    return make_response(f"<h2>500 Server Error</h2><pre>{e}</pre>"), 500


@app.before_request
def restrict_access():
    # CORS preflight: the Android wrapper's bridge.html fetches
    # /api/offline_snapshot cross-origin with a custom Authorization header,
    # which forces the browser to send an OPTIONS preflight first. That
    # preflight carries no auth of its own, so without this bypass it always
    # hit the 401 checks below and the browser blocked the real GET —
    # meaning the offline snapshot (centre/batches/roster) was never cached.
    if request.method == 'OPTIONS':
        from flask import make_response
        resp = make_response()
        resp.headers['Access-Control-Allow-Origin'] = request.headers.get('Origin', '*')
        resp.headers['Access-Control-Allow-Headers'] = 'Authorization, Content-Type'
        resp.headers['Access-Control-Allow-Methods'] = 'GET, POST, OPTIONS'
        resp.headers['Access-Control-Max-Age'] = '86400'
        return resp

    g.t_start = time.time()
    g.log_user = 'anon'

    # Opt every request into the permanent (60-day, rolling) session cookie configured
    # near app.secret_key. Without this flag Flask ignores permanent_session_lifetime
    # entirely and still emits a cookie that dies when the app is closed.
    # Logout is excluded: it calls session.clear(), and there is no reason to hand back
    # a freshly-stamped long-lived cookie on the way out.
    if not request.path.startswith('/api/auth/logout'):
        session.permanent = True

    global CONNECTED_DEVICES
    ip = get_client_ip()
    ua = request.headers.get('User-Agent', 'Unknown Device')
    
    # Track device
    if ip not in CONNECTED_DEVICES:
        name = "Other Device"
        if "Windows" in ua: name = "Windows PC"
        elif "Android" in ua: name = "Android Phone"
        elif "iPhone" in ua or "iPad" in ua: name = "iOS Device"
        elif "Macintosh" in ua: name = "Mac"
        CONNECTED_DEVICES[ip] = {
            "name": name,
            "ua": ua,
            "active": True,
            "last_seen": datetime.now().strftime("%H:%M:%S"),
            "last_seen_ts": time.time()
        }
    else:
        CONNECTED_DEVICES[ip]["last_seen"] = datetime.now().strftime("%H:%M:%S")
        CONNECTED_DEVICES[ip]["last_seen_ts"] = time.time()
        # Update user-agent if it changed (unlikely but good for debugging if someone spoofs)
        CONNECTED_DEVICES[ip]["ua"] = ua

    # Check if blocked — checked before any auth so admins can still unblock via session
    role = session.get('role')
    is_host_role = (role == 'host')

    if not CONNECTED_DEVICES[ip].get("active", True) and not is_host_role:
        return "Your device has been disabled by the administrator.", 403

    # --- Gatekeeper Logic ---
    # 1. Skip auth check for open paths (login page, static assets, health, internal)
    open_paths = ('/login', '/waiting', '/static', '/manifest',
                  '/api/auth/status', '/api/auth/login', '/api/auth/logout', '/health',
                  '/register-bundle', '/service-worker.js', '/sw.js', '/api/internal/',
                  # iOS fetches the home-screen icon with no session cookie. Gated,
                  # it would 302 to /login and the install would fall back to a
                  # screenshot of the page — the exact bug the icon set exists to fix.
                  '/apple-touch-icon',
                  # /desktop = electron-updater feed (latest.yml + installer); the
                  # desktop app polls it with no session cookie, so it must be public
                  # or auto-update silently 302s to /login and never happens.
                  '/desktop/', '/app', '/download/',
                  # Forced-update check. Must be public: a build old enough to be
                  # refused entry may also be too old to authenticate, and if the
                  # check itself 401'd the user would be stuck with no way to learn
                  # that an update is what they need.
                  '/api/app/version',
                  # Not session/JWT-gated here — it enforces is_host_device()/role
                  # itself, same pattern as /api/internal/, so it stays reachable
                  # from a local admin curl call without first needing a browser
                  # session cookie.
                  '/api/admin/resync_attendance_mirror')
    if any(request.path.startswith(p) for p in open_paths):
        return

    # 2. JWT auth — accept Bearer tokens (used by SRKB calling app and API clients)
    jwt_username, jwt_role = _get_jwt_user_from_request()
    if jwt_username:
        # A valid shared-secret JWT is sufficient authority for API access.
        # Only reject if a RegistererAccount explicitly exists AND is suspended/pending.
        account = RegistererAccount.query.filter_by(username=jwt_username).first()
        if account and account.status != 'approved':
            if request.path.startswith('/api/'):
                return jsonify({"error": "Forbidden", "message": "Account not approved"}), 403
            return redirect(url_for('waiting_view'))
        g.jwt_username = jwt_username
        g.jwt_role = account.role if account else jwt_role
        g.log_user = jwt_username
        return  # JWT auth successful — bypass session checks below

    # 3. Flask session auth — used by web browser users
    username = session.get('username')
    if not username:
        if request.path.startswith('/api/'):
            return jsonify({"error": "Authentication required"}), 401
        return redirect(url_for('login_view'))

    g.log_user = username
    # 4. Registerer approval gate
    if role == 'registerer':
        account = RegistererAccount.query.filter_by(username=username).first()
        if not account or account.status != 'approved':
            return redirect(url_for('waiting_view'))

    # 5. Host-only pages — redirect registerers away
    if request.path in ('/', '/logs', '/downloads'):
        if not is_host_role:
            return redirect(url_for('registerer_view'))

# --- Routes ---

@app.route('/')
def index():
    # Fetch canonical batches for the dashboard filter.
    try:
        selected_centre_id = get_selected_centre_id()
        selected_centre_name = get_centre_name_by_id(selected_centre_id)
        batches = [
            b for b in Batch.query.filter_by(centre_id=selected_centre_id).order_by(Batch.name.asc()).all()
            if b.name
        ]
        centres = Centre.query.filter(Centre.status == 'active').order_by(Centre.name.asc()).all()
        registerers = CentreAccess.query.filter_by(centre_id=selected_centre_id, role='registerer').order_by(
            CentreAccess.status.desc(),
            CentreAccess.created_at.asc(),
            CentreAccess.id.asc()
        ).all()
    except Exception:
        batches = []
        centres = []
        registerers = []
        selected_centre_id = DEFAULT_CENTRE_ID
        selected_centre_name = get_centre_name_by_id(DEFAULT_CENTRE_ID)
    return render_template(
        'index.html',
        batches=batches,
        centres=centres,
        registerers=registerers,
        selected_centre_id=selected_centre_id,
        selected_centre_name=selected_centre_name
    )

@app.route('/register')
def registerer_view():
    return render_template('registerer.html')


@app.route('/register-bundle')
def register_bundle():
    """
    Returns a pre-processed registerer.html for Capacitor offline caching.
    - No auth required (HTML contains no sensitive data)
    - Injects a fetch interceptor so relative API calls are prefixed with the server URL
    - Used by the Android wrapper (www/index.html) to cache the app for offline use
    """
    try:
        html = render_template('registerer.html')
        interceptor = """<script id="ae-offline-interceptor">
(function(){
  var base = localStorage.getItem('ae_server_url') || '';
  if (!base) return;
  window._AE_BASE = base;
  // Redirect relative fetch calls to the configured server, and attach the
  // bridged JWT (see bridge.html) as a Bearer token. This is the one true
  // chokepoint every relative call passes through in bundle mode — without
  // auth attached HERE (not just inside offline-sync.js's own fetch wrapper),
  // any other plain fetch() on this page — e.g. the Lists tab's
  // /api/list_server_files — was silently 401ing cross-origin and just
  // showing empty, even well after connectivity returned.
  var _f = window.fetch;
  window.fetch = function(url, opts) {
    if (typeof url === 'string' && url.charAt(0) === '/' && url.charAt(1) !== '/') {
      url = base + url;
      opts = opts || {};
      var token = localStorage.getItem('ae_offline_jwt') || localStorage.getItem('ae_jwt');
      if (token && !(opts.headers && opts.headers['Authorization'])) {
        opts.headers = Object.assign({}, opts.headers, { 'Authorization': 'Bearer ' + token });
      }
    }
    return _f.call(this, url, opts);
  };
  // Also fix XHR (used by some Capacitor plugins)
  var _xhrOpen = XMLHttpRequest.prototype.open;
  var _xhrSetHeader = XMLHttpRequest.prototype.setRequestHeader;
  var _xhrIsRewritten = new WeakSet();
  XMLHttpRequest.prototype.open = function(method, url) {
    if (typeof url === 'string' && url.charAt(0) === '/' && url.charAt(1) !== '/') {
      _xhrIsRewritten.add(this);
      arguments[1] = base + url;
    }
    return _xhrOpen.apply(this, arguments);
  };
  var _xhrSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function() {
    if (_xhrIsRewritten.has(this)) {
      var token = localStorage.getItem('ae_offline_jwt') || localStorage.getItem('ae_jwt');
      if (token) {
        try { _xhrSetHeader.call(this, 'Authorization', 'Bearer ' + token); } catch (e) {}
      }
    }
    return _xhrSend.apply(this, arguments);
  };
  // This bundle runs on the Capacitor app's own local origin, never the
  // remote server's — so even once connectivity returns, the rewritten
  // fetch() calls above are genuine cross-origin requests with no session
  // cookie attached (fetch's default credentials mode doesn't send cookies
  // cross-origin), meaning the server always sees them as unauthenticated
  // and this page can never actually "go live" in place, no matter how long
  // you wait. The reliable fix is a real top-level navigation back to the
  // authenticated remote page — that carries the session cookie normally,
  // same as any other site visit — rather than trying to keep everything
  // working from this isolated local-origin context indefinitely.
  // Go to /login rather than straight to /register: this origin has no
  // session cookie yet (only a JWT bridged in from the original online
  // login), and /login now auto-exchanges that JWT for a real session
  // before continuing — /register alone would just 401 and bounce there
  // anyway, but with an extra useless round trip.
  window.addEventListener('online', function () {
    if (localStorage.getItem('ae_offline_mode') === 'true') {
      localStorage.removeItem('ae_offline_mode');
      window.location.href = base + '/login';
    }
  });
})();
</script>"""
        # Inject interceptor right after <head>
        html = html.replace('<head>', '<head>\n' + interceptor, 1)
        # Change local static JS to absolute (they will be served from bundled www/js/)
        html = html.replace(
            "{{ url_for('static', filename='js/sync.js') }}",
            "/js/sync.js"
        )
        html = html.replace(
            "{{ url_for('static', filename='js/offline-sync.js') }}",
            "/js/offline-sync.js"
        )
        from flask import make_response
        resp = make_response(html)
        resp.headers['Content-Type'] = 'text/html; charset=utf-8'
        resp.headers['Access-Control-Allow-Origin'] = '*'
        resp.headers['Cache-Control'] = 'no-store'
        return resp
    except Exception as e:
        return str(e), 500

@app.route('/downloads')
def downloads():
    return render_template('downloads.html')

@app.route('/logs')
def view_logs():
    return render_template('logs.html')

@app.route('/login')
def login_view():
    return render_template('login.html')

@app.route('/waiting_approval')
def waiting_view():
    return render_template('waiting_approval.html')

@app.route('/health')
def health():
    webhook_queue_depth = _calling_backend_webhook_queue.qsize()
    return jsonify({
        "ok": True,
        "service": "AttendEase",
        "status": "running",
        # Number of CB webhook events waiting to be delivered.
        # Normally 0-2. If this is growing, CB may be unreachable or slow.
        "webhook_queue_depth": webhook_queue_depth,
        "webhook_queue_warning": webhook_queue_depth > 50,
    })

@app.route('/api/auth/login', methods=['POST'])
def api_auth_login():
    data = request.json or {}
    username = data.get('username', '').strip()
    password = data.get('password', '')

    if not username:
        write_log(f"INFO login: username=(empty) result=fail reason=missing_username | IP={get_client_ip()}")
        return jsonify({"error": "Username required"}), 400
    if not password:
        write_log(f"INFO login: username={username} result=fail reason=missing_password | IP={get_client_ip()}")
        return jsonify({"error": "Password required"}), 400

    # Look up the user — check RegistererAccount (username/password store)
    import sqlite3 as _sqlite3
    db_path = app.config['SQLALCHEMY_DATABASE_URI'].replace('sqlite:///', '')
    conn = None
    try:
        conn = _sqlite3.connect(db_path)
        # Exact (case-sensitive) match first — preserves behaviour for accounts that differ ONLY by
        # case (this DB has two 'krishna' and two 'krsna' rows). Only when there is no exact match do
        # we fall back to a case-insensitive lookup, and we accept it ONLY when it resolves to exactly
        # ONE account. That fixes the classic 'ihost' vs 'Ihost' trap (a DB restore can silently
        # re-introduce it) without ever guessing between ambiguous same-name rows.
        row = conn.execute(
            "SELECT username, role, status, password_hash, must_change_password FROM registerer_accounts WHERE username=?",
            (username,)
        ).fetchone()
        if row is None:
            _ci = conn.execute(
                "SELECT username, role, status, password_hash, must_change_password FROM registerer_accounts WHERE username=? COLLATE NOCASE",
                (username,)
            ).fetchall()
            if len(_ci) == 1:
                row = _ci[0]
            elif len(_ci) > 1:
                write_log(f"INFO login: username={username} result=fail reason=ambiguous_case_insensitive_match count={len(_ci)} | IP={get_client_ip()}")
                # leave row=None → falls through to the generic invalid-credentials response below
    except Exception as e:
        write_log(f"INFO login: username={username} result=fail reason=db_error error={e}")
        return jsonify({"error": "Internal error"}), 500
    finally:
        if conn:
            conn.close()

    if not row:
        write_log(f"INFO login: username={username} result=fail reason=not_found | IP={get_client_ip()}")
        return jsonify({"error": "Invalid username or password"}), 401

    _username, _role, _status, _password_hash, _must_change = row
    # Normalise to the stored username casing so all downstream centre-access / session logic keys off
    # the canonical value, even when the user logged in via the case-insensitive fallback above.
    username = _username

    if not _password_hash:
        write_log(f"INFO login: username={username} result=fail reason=no_password_set | IP={get_client_ip()}")
        return jsonify({"error": "PASSWORD_NOT_SET", "message": "Contact your centre admin to set your initial password."}), 403

    if not _ae_verify_password(password, _password_hash):
        write_log(f"INFO login: username={username} result=fail reason=wrong_password | IP={get_client_ip()}")
        return jsonify({"error": "Invalid username or password"}), 401

    if _status == 'pending':
        write_log(f"INFO login: username={username} result=fail reason=pending_approval | IP={get_client_ip()}")
        return jsonify({"error": "Your account is pending approval"}), 403
    if _status != 'approved':
        write_log(f"INFO login: username={username} result=fail reason=status={_status} | IP={get_client_ip()}")
        return jsonify({"error": "Account not active"}), 403

    # Auth passed — build session and issue JWT
    must_change = bool(_must_change)
    primary_access, primary_centre, allowed_centres = get_primary_centre_access_context(username)
    if primary_access and primary_centre:
        resolved_role = apply_centre_access_to_session(username, primary_access, primary_centre, allowed_centres)
        try:
            primary_access.last_seen_at = datetime.now()
            db.session.commit()
        except Exception:
            db.session.rollback()
        jwt_token = _ae_issue_jwt(username, resolved_role or 'registerer')
        write_log(f"INFO login: username={username} result=success role={resolved_role} centre={primary_centre.centre_id} | IP={get_client_ip()}")
        return jsonify({
            "status": "approved",
            "role": resolved_role or "registerer",
            "centre_id": primary_centre.centre_id,
            "centre_name": primary_centre.name,
            "allowed_centres": allowed_centres,
            "jwt": jwt_token,
            "must_change_password": must_change,
        })

    # User is approved but has no CentreAccess yet (rare — admin just approved them)
    session['username'] = username
    session['role'] = _role
    jwt_token = _ae_issue_jwt(username, _role)
    write_log(f"INFO login: username={username} result=success role={_role} no_centre | IP={get_client_ip()}")
    return jsonify({
        "status": "approved",
        "role": _role,
        "jwt": jwt_token,
        "must_change_password": must_change,
    })

@app.route('/api/auth/session_from_jwt', methods=['POST'])
def api_auth_session_from_jwt():
    """
    Exchanges a Bearer JWT for a real Flask session cookie on THIS origin.
    Used when the Android wrapper's offline bundle (a different origin) sends
    the user back to /login on reconnect: that page already holds this
    origin's own `ae_jwt` in localStorage from the original online login, so
    it can silently re-establish the session here instead of forcing a
    manual re-login. A plain navigation back to /register can't do this on
    its own — there is no session cookie yet, only the JWT.
    """
    username, role = _get_jwt_user_from_request()
    if not username:
        return jsonify({"error": "Invalid or expired token"}), 401

    primary_access, primary_centre, allowed_centres = get_primary_centre_access_context(username)
    if primary_access and primary_centre:
        resolved_role = apply_centre_access_to_session(username, primary_access, primary_centre, allowed_centres)
        return jsonify({
            "status": "approved",
            "role": resolved_role or "registerer",
            "centre_id": primary_centre.centre_id,
            "centre_name": primary_centre.name,
        })

    session['username'] = username
    session['role'] = role
    return jsonify({"status": "approved", "role": role})


@app.route('/api/auth/change-password', methods=['POST'])
def api_auth_change_password():
    """Change the current user's password. Requires JWT or session auth."""
    # Identify caller — JWT takes priority, then session
    jwt_username, _ = _get_jwt_user_from_request()
    username = jwt_username or session.get('username')
    if not username:
        return jsonify({"error": "Authentication required"}), 401

    data = request.json or {}
    new_password = data.get('new_password', '')
    old_password = data.get('old_password', '')
    if not new_password or len(new_password) < 8:
        return jsonify({"error": "new_password must be at least 8 characters"}), 422

    import sqlite3 as _sqlite3
    db_path = app.config['SQLALCHEMY_DATABASE_URI'].replace('sqlite:///', '')
    conn = None
    try:
        conn = _sqlite3.connect(db_path)
        row = conn.execute(
            "SELECT password_hash, must_change_password FROM registerer_accounts WHERE username=?",
            (username,)
        ).fetchone()
        if not row:
            return jsonify({"error": "User not found"}), 404
        current_hash, must_change = row

        # If not a forced change, old_password must be provided and correct
        if not must_change:
            if not old_password:
                return jsonify({"error": "old_password is required"}), 422
            if not _ae_verify_password(old_password, current_hash):
                write_log(f"INFO password_change: username={username} result=fail reason=wrong_old_password")
                return jsonify({"error": "Current password is incorrect"}), 401

        new_hash = _ae_hash_password(new_password)
        now_iso = datetime.now().isoformat()
        conn.execute(
            "UPDATE registerer_accounts SET password_hash=?, must_change_password=0, password_updated_at=? WHERE username=?",
            (new_hash, now_iso, username)
        )
        conn.commit()
    except Exception as e:
        write_log(f"INFO password_change: username={username} result=fail reason=db_error error={e}")
        return jsonify({"error": "Internal error"}), 500
    finally:
        if conn:
            conn.close()

    write_log(f"INFO password_change: username={username} initiated_via=ae mustChange={bool(must_change)}")

    # Enqueue sync to CB: send the hash (CB and AE share the same scrypt format, so CB stores it
    # directly). No plaintext is placed in the outbox or sent over the wire.
    # must_change=False because the user initiated this change themselves.
    try:
        _enqueue_cb_outbox('sync_password_to_cb', {
            'username': username,
            'password_hash': new_hash,
            'must_change': False,
            'password_updated_at': now_iso,
        })
    except Exception as e:
        write_log(f"WARN password_change: sync enqueue failed for {username}: {e}")

    return jsonify({"ok": True, "message": "Password changed successfully"})


@app.route('/api/internal/sync-password', methods=['POST'])
def api_internal_sync_password():
    """Receive a password hash from CB after a CB-side password change. Internal only."""
    import os
    if os.path.exists("sync-password-failed.lock"):
        return jsonify({"error": "Simulated password sync RPC outage"}), 500

    # Internal token guard (same pattern as batch membership endpoints)
    token = request.headers.get('x-internal-token', '')
    if token != CALLING_BACKEND_INTERNAL_TOKEN:
        return jsonify({"error": "Unauthorized"}), 401
    client_ip = request.remote_addr
    if client_ip not in ('127.0.0.1', '::1', '::ffff:127.0.0.1'):
        return jsonify({"error": "Forbidden"}), 403

    data = request.json or {}
    username = data.get('username', '').strip()
    password_hash = data.get('password_hash', '')
    password_updated_at = data.get('password_updated_at', '')
    must_change = bool(data.get('must_change', False))
    plain_password = data.get('plain_password', '')  # defensive fallback for in-flight legacy jobs only

    if not username or not password_updated_at or (not password_hash and not plain_password):
        return jsonify({"error": "username, password_updated_at, and password_hash are required"}), 400

    # CB and AE share the same scrypt format (N=16384, r=8, p=1, dklen=64, "salt:hexkey"), and each
    # side's verify accepts the other's hash — so CB's hash is stored directly. Plaintext is never
    # transmitted; only re-hash from plaintext if a hash wasn't provided (legacy in-flight job).
    if not password_hash and plain_password:
        password_hash = _ae_hash_password(plain_password)

    import sqlite3 as _sqlite3
    db_path = app.config['SQLALCHEMY_DATABASE_URI'].replace('sqlite:///', '')
    conn = None
    try:
        conn = _sqlite3.connect(db_path)
        row = conn.execute("SELECT username FROM registerer_accounts WHERE username=?", (username,)).fetchone()
        if not row:
            # Auto-create account so CB→AE sync never silently drops a new user
            conn.execute(
                "INSERT INTO registerer_accounts (username, role, status, password_hash, password_updated_at, must_change_password, created_at) VALUES (?, 'registerer', 'approved', ?, ?, ?, datetime('now'))",
                (username, password_hash, password_updated_at, 1 if must_change else 0)
            )
            write_log(f"INFO sync_password: direction=cb_to_ae username={username} result=auto_created mustChange={must_change}")
        else:
            conn.execute(
                "UPDATE registerer_accounts SET password_hash=?, must_change_password=?, password_updated_at=? WHERE username=?",
                (password_hash, 1 if must_change else 0, password_updated_at, username)
            )
        conn.commit()
    except Exception as e:
        write_log(f"WARN sync_password: direction=cb_to_ae username={username} result=db_error error={e}")
        return jsonify({"error": "Internal error"}), 500
    finally:
        if conn:
            conn.close()

    write_log(f"INFO sync_password: direction=cb_to_ae username={username} result=success")
    return jsonify({"ok": True})


# ──────────────────────────────────────────────────────────────────────────────
# INTERNAL SYNC ENDPOINTS — called by SRKB outbox worker for bidirectional sync
# All require x-internal-token header and localhost origin.
# ──────────────────────────────────────────────────────────────────────────────

def _parse_iso_timestamp(ts_str):
    if not ts_str:
        import datetime as _dt_mod
        return _dt_mod.datetime.min
    import datetime as _dt_mod
    cleaned = str(ts_str).replace('Z', '')
    if '+' in cleaned:
        cleaned = cleaned.split('+')[0]
    elif '-' in cleaned and len(cleaned.split('-')) > 3:
        parts = cleaned.rsplit('-', 1)
        cleaned = parts[0]
    
    for fmt in ("%Y-%m-%dT%H:%M:%S.%f", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%d %H:%M:%S"):
        try:
            return _dt_mod.datetime.strptime(cleaned, fmt)
        except ValueError:
            continue
    try:
        return _dt_mod.datetime.fromisoformat(cleaned[:26])
    except Exception:
        return _dt_mod.datetime.min


def _cb_event_is_fresh(conn, entity_type, entity_id, occurred_at):
    """
    Conflict resolution: return True only if this event is newer than the
    last recorded event for this entity.  Records the event if fresh.
    """
    conn.execute("""
        CREATE TABLE IF NOT EXISTS cb_sync_events (
            entity_type TEXT NOT NULL,
            entity_id   TEXT NOT NULL,
            last_event_at TEXT NOT NULL,
            last_event_type TEXT NOT NULL,
            PRIMARY KEY (entity_type, entity_id)
        )
    """)
    row = conn.execute(
        "SELECT last_event_at FROM cb_sync_events WHERE entity_type=? AND entity_id=?",
        (entity_type, entity_id)
    ).fetchone()
    if row:
        last_dt = _parse_iso_timestamp(row[0])
        curr_dt = _parse_iso_timestamp(occurred_at)
        if last_dt >= curr_dt:
            return False  # stale: a newer or equal event was already applied
    conn.execute("""
        INSERT INTO cb_sync_events (entity_type, entity_id, last_event_at, last_event_type)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(entity_type, entity_id) DO UPDATE SET
            last_event_at=excluded.last_event_at,
            last_event_type=excluded.last_event_type
    """, (entity_type, entity_id, occurred_at, entity_type))
    return True


def _require_internal(request):
    """Return error response tuple if not an internal caller, else None."""
    token = request.headers.get("x-internal-token", "")
    if token != CALLING_BACKEND_INTERNAL_TOKEN:
        return jsonify({"error": "Unauthorized"}), 401
    return None


@app.route("/api/internal/delete-user", methods=["POST"])
def api_internal_delete_user():
    """SRKB → AE: remove a user from centre_access and registerer_accounts."""
    err = _require_internal(request)
    if err:
        return err
    data = request.json or {}
    username = str(data.get("username", "")).strip()
    occurred_at = str(data.get("occurred_at", "")).strip()
    if not username or not occurred_at:
        return jsonify({"error": "username and occurred_at required"}), 400

    import sqlite3 as _sqlite3
    db_path = app.config["SQLALCHEMY_DATABASE_URI"].replace("sqlite:///", "")
    conn = _sqlite3.connect(db_path)
    try:
        if not _cb_event_is_fresh(conn, "user_delete", username, occurred_at):
            conn.close()
            write_log(f"INFO internal_delete_user: username={username} result=skipped_stale")
            return jsonify({"ok": True, "skipped": "stale"})
        conn.execute("DELETE FROM centre_access WHERE username=?", (username,))
        conn.execute("DELETE FROM registerer_accounts WHERE username=?", (username,))
        conn.commit()
        write_log(f"INFO internal_delete_user: username={username} result=deleted")
        return jsonify({"ok": True})
    except Exception as e:
        conn.rollback()
        write_log(f"ERROR internal_delete_user: {e}")
        return jsonify({"error": "Internal error"}), 500
    finally:
        conn.close()


@app.route("/api/internal/grant-centre-admin", methods=["POST"])
def api_internal_grant_centre_admin():
    """
    SRKB → AE: sync a centre-admin assignment made in SRKB into AE's centre_access.
    Added 2026-07-04 — until now, assigning a centre admin in SRKB (isAdmin=true on
    UserCentreAccess) never propagated to AE at all; logging into AE directly showed
    the user's old/default centre because AE's own centre_access table never got a
    row for the new assignment.
    """
    err = _require_internal(request)
    if err:
        return err
    data = request.json or {}
    username = str(data.get("username", "")).strip()
    centre_code = str(data.get("centre_code", "")).strip()
    display_name = str(data.get("display_name", "")).strip() or None
    occurred_at = str(data.get("occurred_at", "")).strip()
    if not username or not centre_code or not occurred_at:
        return jsonify({"error": "username, centre_code, occurred_at required"}), 400

    import sqlite3 as _sqlite3
    db_path = app.config["SQLALCHEMY_DATABASE_URI"].replace("sqlite:///", "")
    conn = _sqlite3.connect(db_path)
    try:
        entity_id = f"{username}:{centre_code}"
        if not _cb_event_is_fresh(conn, "centre_admin_grant", entity_id, occurred_at):
            conn.close()
            write_log(f"INFO internal_grant_centre_admin: username={username} centre={centre_code} result=skipped_stale")
            return jsonify({"ok": True, "skipped": "stale"})

        # Ensure the account exists (skeleton if brand new — password syncs via the
        # separate sync-password path, so we never overwrite an existing hash here).
        conn.execute("""
            INSERT OR IGNORE INTO registerer_accounts
                (username, role, status, must_change_password, created_at)
            VALUES (?, 'registerer', 'approved', 1, datetime('now'))
        """, (username,))

        updated = conn.execute(
            "UPDATE centre_access SET role='admin', status='active', "
            "display_name=COALESCE(?, display_name) WHERE username=? AND centre_id=?",
            (display_name, username, centre_code)
        ).rowcount
        if not updated:
            conn.execute("""
                INSERT INTO centre_access
                    (username, display_name, centre_id, role, status,
                     can_create_batch, can_create_session, can_edit_attendance, can_delete_attendance)
                VALUES (?, ?, ?, 'admin', 'active', 1, 1, 1, 0)
            """, (username, display_name, centre_code))
        conn.commit()
        write_log(f"INFO internal_grant_centre_admin: username={username} centre={centre_code} result=ok")
        return jsonify({"ok": True})
    except Exception as e:
        conn.rollback()
        write_log(f"ERROR internal_grant_centre_admin: {e}")
        return jsonify({"error": "Internal error"}), 500
    finally:
        conn.close()


@app.route("/api/internal/create-user", methods=["POST"])
def api_internal_create_user():
    """SRKB → AE: create registerer_account + centre_access when AE access is granted."""
    err = _require_internal(request)
    if err:
        return err
    data = request.json or {}
    username    = str(data.get("username", "")).strip()
    name        = str(data.get("name", "")).strip()
    centre_code = str(data.get("centre_code", "")).strip()
    role        = str(data.get("role", "registerer")).strip()
    plain_pw    = str(data.get("plain_password", "")).strip()
    occurred_at = str(data.get("occurred_at", "")).strip()
    if not username or not centre_code or not occurred_at:
        return jsonify({"error": "username, centre_code, occurred_at required"}), 400

    import sqlite3 as _sqlite3
    db_path = app.config["SQLALCHEMY_DATABASE_URI"].replace("sqlite:///", "")
    conn = _sqlite3.connect(db_path)
    try:
        if not _cb_event_is_fresh(conn, "user_create", username, occurred_at):
            conn.close()
            write_log(f"INFO internal_create_user: username={username} result=skipped_stale")
            return jsonify({"ok": True, "skipped": "stale"})

        pw_hash = _ae_hash_password(plain_pw) if plain_pw else None
        if pw_hash:
            conn.execute("""
                INSERT OR IGNORE INTO registerer_accounts
                    (username, role, status, password_hash, plain_password, must_change_password, created_at)
                VALUES (?, ?, 'approved', ?, ?, 1, datetime('now'))
            """, (username, role, pw_hash, plain_pw))
        else:
            # No password yet — create a skeleton; login will fail until password is synced
            conn.execute("""
                INSERT OR IGNORE INTO registerer_accounts
                    (username, role, status, must_change_password, created_at)
                VALUES (?, ?, 'approved', 1, datetime('now'))
            """, (username, role))

        conn.execute("""
            INSERT OR IGNORE INTO centre_access
                (username, display_name, centre_id, role, status,
                 can_create_batch, can_create_session, can_edit_attendance, can_delete_attendance)
            VALUES (?, ?, ?, ?, 'active', 0, 0, 1, 0)
        """, (username, name, centre_code, role))
        # INSERT OR IGNORE leaves a pre-existing row untouched — so a centre_access
        # row previously set to 'revoked' would stay revoked and the grant would
        # silently no-op. Reactivate it explicitly so re-granting access (or the
        # reconciler self-healing) actually restores the login.
        conn.execute("""
            UPDATE centre_access SET status='active', role=?, display_name=COALESCE(NULLIF(?, ''), display_name)
            WHERE username=? AND centre_id=?
        """, (role, name, username, centre_code))
        conn.commit()
        write_log(f"INFO internal_create_user: username={username} centre={centre_code} result=created")
        return jsonify({"ok": True})
    except Exception as e:
        conn.rollback()
        write_log(f"ERROR internal_create_user: {e}")
        return jsonify({"error": "Internal error"}), 500
    finally:
        conn.close()


@app.route("/api/internal/revoke-ae-access", methods=["POST"])
def api_internal_revoke_ae_access():
    """SRKB → AE: remove centre_access when AE access is revoked (keep registerer_account)."""
    err = _require_internal(request)
    if err:
        return err
    data = request.json or {}
    username    = str(data.get("username", "")).strip()
    centre_code = str(data.get("centre_code", "")).strip()
    occurred_at = str(data.get("occurred_at", "")).strip()
    if not username or not occurred_at:
        return jsonify({"error": "username and occurred_at required"}), 400

    import sqlite3 as _sqlite3
    db_path = app.config["SQLALCHEMY_DATABASE_URI"].replace("sqlite:///", "")
    conn = _sqlite3.connect(db_path)
    try:
        if not _cb_event_is_fresh(conn, "user_revoke_ae", username, occurred_at):
            conn.close()
            return jsonify({"ok": True, "skipped": "stale"})
        if centre_code:
            conn.execute("DELETE FROM centre_access WHERE username=? AND centre_id=?", (username, centre_code))
        else:
            conn.execute("DELETE FROM centre_access WHERE username=?", (username,))
        conn.commit()
        write_log(f"INFO internal_revoke_ae: username={username} centre={centre_code} result=revoked")
        return jsonify({"ok": True})
    except Exception as e:
        conn.rollback()
        write_log(f"ERROR internal_revoke_ae: {e}")
        return jsonify({"error": "Internal error"}), 500
    finally:
        conn.close()


@app.route("/api/internal/update-username", methods=["POST"])
def api_internal_update_username():
    """SRKB → AE: rename a user's login mobile/username in registerer_accounts + centre_access."""
    err = _require_internal(request)
    if err:
        return err
    data = request.json or {}
    old_username = str(data.get("old_username", "")).strip()
    new_username = str(data.get("new_username", "")).strip()
    occurred_at  = str(data.get("occurred_at", "")).strip()
    if not old_username or not new_username or not occurred_at:
        return jsonify({"error": "old_username, new_username, occurred_at required"}), 400

    import sqlite3 as _sqlite3
    db_path = app.config["SQLALCHEMY_DATABASE_URI"].replace("sqlite:///", "")
    conn = _sqlite3.connect(db_path)
    try:
        if not _cb_event_is_fresh(conn, "user_rename", old_username, occurred_at):
            conn.close()
            write_log(f"INFO internal_update_username: {old_username}→{new_username} result=skipped_stale")
            return jsonify({"ok": True, "skipped": "stale"})
        conn.execute("UPDATE registerer_accounts SET username=? WHERE username=?", (new_username, old_username))
        conn.execute("UPDATE centre_access SET username=? WHERE username=?", (new_username, old_username))
        conn.commit()
        write_log(f"INFO internal_update_username: {old_username}→{new_username} result=renamed")
        return jsonify({"ok": True})
    except Exception as e:
        conn.rollback()
        write_log(f"ERROR internal_update_username: {e}")
        return jsonify({"error": "Internal error"}), 500
    finally:
        conn.close()


@app.route("/api/internal/create-centre", methods=["POST"])
def api_internal_create_centre():
    """SRKB → AE: create a centre record so AE knows about it immediately (no 24h wait)."""
    err = _require_internal(request)
    if err:
        return err
    data = request.json or {}
    centre_code = str(data.get("centre_code", "")).strip()
    centre_name = str(data.get("centre_name", "")).strip()
    occurred_at = str(data.get("occurred_at", "")).strip()
    if not centre_code or not centre_name or not occurred_at:
        return jsonify({"error": "centre_code, centre_name, occurred_at required"}), 400

    existing = Centre.query.filter_by(centre_id=centre_code).first()
    if existing:
        return jsonify({"ok": True, "skipped": "already_exists"})

    try:
        new_centre = Centre(centre_id=centre_code, name=centre_name, status="active")
        db.session.add(new_centre)
        db.session.commit()
        write_log(f"INFO internal_create_centre: code={centre_code} name={centre_name} result=created")
        return jsonify({"ok": True}), 201
    except Exception as e:
        db.session.rollback()
        write_log(f"ERROR internal_create_centre: {e}")
        return jsonify({"error": "Internal error"}), 500


    return jsonify({"ok": True})


@app.route('/api/auth/status')
def api_auth_status():
    username = session.get('username')
    if not username: return jsonify({"status": "unauthorized"}), 401
    
    if is_host_device() or session.get('role') == 'host':
        centre_id = session.get('selected_centre_id') or DEFAULT_CENTRE_ID
        centre_name = session.get('selected_centre_name') or get_centre_name_by_id(centre_id)
        session['selected_centre_id'] = centre_id
        session['selected_centre_name'] = centre_name
        return jsonify({
            "status": "approved",
            "role": "host",
            "centre_id": centre_id,
            "centre_name": centre_name
        })

    primary_access, primary_centre, allowed_centres = get_primary_centre_access_context(username)
    if primary_access and primary_centre:
        resolved_role = apply_centre_access_to_session(username, primary_access, primary_centre, allowed_centres)
        try:
            primary_access.last_seen_at = datetime.now()
            db.session.commit()
        except Exception:
            db.session.rollback()
        return jsonify({
            "status": "approved",
            "role": resolved_role or "registerer",
            "centre_id": primary_centre.centre_id,
            "centre_name": primary_centre.name
        })
        
    user = RegistererAccount.query.filter_by(username=username).first()
    if not user: return jsonify({"status": "deleted"}), 404
    if user.status == 'approved':
        centre_name = get_centre_name_by_id(DEFAULT_CENTRE_ID)
        session['selected_centre_id'] = DEFAULT_CENTRE_ID
        session['selected_centre_name'] = centre_name
        return jsonify({
            "status": "approved",
            "role": user.role,
            "centre_id": DEFAULT_CENTRE_ID,
            "centre_name": centre_name
        })
    
    return jsonify({"status": user.status, "role": user.role})

@app.route('/api/auth/approve', methods=['POST'])
def api_auth_approve():
    if not is_host_device() and session.get('role') != 'host':
        return jsonify({"error": "Unauthorized"}), 403
        
    data = request.json
    username = data.get('username')
    action = data.get('action') # 'approve' or 'reject'
    centre_id = (data.get('centre_id') or DEFAULT_CENTRE_ID).strip()
    role = (data.get('role') or 'registerer').strip() or 'registerer'
    
    user = RegistererAccount.query.filter_by(username=username).first()
    if not user: return jsonify({"error": "User not found"}), 404
    
    if action == 'approve':
        centre = Centre.query.filter_by(centre_id=centre_id).first()
        if not centre:
            return jsonify({"error": "Centre not found"}), 404

        user.status = 'approved'
        approved_by = session.get('username') or ("PhysicalHost" if is_host_device() else None)
        access = CentreAccess.query.filter_by(username=username, centre_id=centre_id).first()
        if not access:
            access = CentreAccess(
                username=username,
                display_name=username,
                centre_id=centre_id,
            )
            db.session.add(access)

        access.display_name = access.display_name or username
        access.role = role or 'registerer'
        access.status = 'active'
        access.can_create_batch = True
        access.can_create_session = True
        access.can_edit_attendance = True
        access.can_delete_attendance = False
        access.approved_by = approved_by
        access.approved_at = datetime.now()
    elif action == 'reject':
        db.session.delete(user)

    db.session.commit()

    if action == 'approve':
        _fire_webhook('registerer.account_approved', 'user', username, {
            'username': username,
        })
        _fire_webhook('registerer.access_granted', 'user_centre_access', f"{username}:{centre_id}", {
            'username': username, 'centre_id': centre_id,
            'display_name': access.display_name if access else username,
        })

    return jsonify({"success": True})

@app.route('/api/auth/pending')
def api_auth_pending():
    if not is_host_device() and session.get('role') != 'host':
        return jsonify({"error": "Unauthorized"}), 403

    pending = RegistererAccount.query.filter_by(status='pending').all()
    return jsonify([u.to_dict() for u in pending])

@app.route('/api/auth/logout', methods=['POST', 'GET'])
def api_auth_logout():
    """Clear Flask session and redirect to login."""
    session.clear()
    if request.method == 'GET' or request.headers.get('Accept', '').startswith('text/html'):
        return redirect(url_for('login_view'))
    return jsonify({"ok": True, "message": "Logged out"})

@app.route('/api/auth/register', methods=['POST'])
def api_auth_register():
    """Public JSON endpoint for new account requests (Android native app)."""
    data = request.json or {}
    username = (data.get('username') or '').strip().lower()
    password = (data.get('password') or '')
    name = (data.get('name') or '').strip()
    centre_id = (data.get('centre_id') or DEFAULT_CENTRE_ID).strip()
    role = (data.get('role') or 'registerer').strip().lower()

    if not username:
        return jsonify({"error": "Username required"}), 400
    if not password or len(password) < 6:
        return jsonify({"error": "Password must be at least 6 characters"}), 400
    if not name:
        return jsonify({"error": "Name required"}), 400

    existing = RegistererAccount.query.filter_by(username=username).first()
    if existing:
        return jsonify({"error": "Username already taken"}), 409

    try:
        import sqlite3 as _sqlite3
        db_path = app.config['SQLALCHEMY_DATABASE_URI'].replace('sqlite:///', '')
        conn = _sqlite3.connect(db_path)
        pw_hash = _ae_hash_password(password)
        conn.execute(
            "INSERT INTO registerer_accounts (username, role, status, password_hash, must_change_password, created_at) VALUES (?, ?, 'pending', ?, 0, CURRENT_TIMESTAMP)",
            (username, role, pw_hash)
        )
        conn.commit()
        conn.close()

        # Create a pending CentreAccess for the requested centre
        centre = Centre.query.filter_by(centre_id=centre_id).first()
        if centre:
            access = CentreAccess(
                username=username,
                display_name=name,
                centre_id=centre_id,
                role=role,
                status='pending'
            )
            db.session.add(access)
            db.session.commit()

        write_log(f"INFO api_register: username={username} role={role} centre={centre_id} | IP={get_client_ip()}")
        return jsonify({"status": "success", "message": "Account request submitted. Awaiting admin approval."})
    except Exception as e:
        try:
            db.session.rollback()
        except Exception:
            pass
        write_log(f"ERROR api_register: username={username} error={e}")
        return jsonify({"error": "Registration failed. Try again."}), 500


@app.route('/api/auth/status/check', methods=['GET'])
def api_auth_status_check():
    """Public endpoint — check account approval status by username (Android polling)."""
    username = (request.args.get('username') or '').strip().lower()
    if not username:
        return jsonify({"status": "unknown"}), 400
    account = RegistererAccount.query.filter_by(username=username).first()
    if not account:
        return jsonify({"status": "not_found"}), 404
    return jsonify({"status": account.status, "role": account.role})


@app.route('/api/caller/queue', methods=['GET'])
def api_caller_queue():
    """Caller's assigned batch members — proxied from the calling backend."""
    centre_id = request.args.get('centre_id') or get_selected_centre_id()
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403
    category = request.args.get('category', 'youth').lower()
    caller_id = request.args.get('caller_id', '')
    try:
        import requests as _requests
        params = {'centre_id': _get_cb_centre_id(centre_id), 'category': category}
        if caller_id:
            params['caller_id'] = caller_id
        resp = _requests.get(
            f"{CALLING_BACKEND_BASE_URL}/internal/batch-members",
            params=params,
            headers={'x-internal-token': CALLING_BACKEND_INTERNAL_TOKEN},
            timeout=3.0
        )
        if resp.ok:
            data = resp.json()
            members = data.get('members', data) if isinstance(data, dict) else data
            return jsonify(members)
    except Exception as e:
        write_log(f"api_caller_queue proxy failed (non-fatal): {e}")
    return jsonify([])


@app.route('/api/caller/followups', methods=['GET'])
def api_caller_followups():
    """Students needing followup — proxied from the calling backend."""
    centre_id = request.args.get('centre_id') or get_selected_centre_id()
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403
    category = request.args.get('category', 'youth').lower()
    try:
        import requests as _requests
        resp = _requests.get(
            f"{CALLING_BACKEND_BASE_URL}/internal/batch-members",
            params={'centre_id': _get_cb_centre_id(centre_id), 'category': category},
            headers={'x-internal-token': CALLING_BACKEND_INTERNAL_TOKEN},
            timeout=3.0
        )
        if resp.ok:
            data = resp.json()
            members = data.get('members', data) if isinstance(data, dict) else data
            followups = [m for m in (members if isinstance(members, list) else [])
                         if 'followup' in str(m.get('remark', '')).lower()
                         or 'callback' in str(m.get('remark', '')).lower()]
            return jsonify(followups)
    except Exception as e:
        write_log(f"api_caller_followups proxy failed (non-fatal): {e}")
    return jsonify([])


@app.route('/api/auth/create-admin', methods=['POST'])
def api_auth_create_admin():
    """SUPER_ADMIN endpoint — directly creates an active centre admin account."""
    if session.get('role') != 'host':
        return jsonify({"error": "Forbidden"}), 403
    data = request.json or {}
    name      = str(data.get('name', '')).strip()
    username  = str(data.get('username', '')).strip().lower()
    password  = str(data.get('password', '')).strip()
    centre_id = str(data.get('centre_id', '')).strip()
    if not all([name, username, password, centre_id]):
        return jsonify({"error": "name, username, password and centre_id are required"}), 400
    if len(password) < 6:
        return jsonify({"error": "Password must be at least 6 characters"}), 400
    centre = Centre.query.filter_by(centre_id=centre_id, status='active').first()
    if not centre:
        return jsonify({"error": "Centre not found"}), 404
    if RegistererAccount.query.filter_by(username=username).first():
        return jsonify({"error": "Username already taken"}), 409
    try:
        pw_hash = _ae_hash_password(password)
        account = RegistererAccount(
            username=username,
            name=name,
            password_hash=pw_hash,
            role='admin',
            status='active',
            centre_id=centre_id
        )
        db.session.add(account)
        access = CentreAccess(
            username=username,
            centre_id=centre_id,
            status='active'
        )
        db.session.add(access)
        db.session.commit()
        write_log(f"SUPER_ADMIN created admin account '{username}' for centre '{centre_id}'")
        return jsonify({"ok": True, "username": username}), 201
    except Exception as e:
        db.session.rollback()
        write_log(f"api_auth_create_admin error: {e}")
        return jsonify({"error": "Failed to create admin account."}), 500


@app.route('/api/auth/managed-accounts', methods=['GET'])
def api_auth_managed_accounts():
    """Return accounts this user can manage, including their plain_password.
    host  → all centre admins (role='admin' in centre_access)
    admin → all registerers in their own centre
    """
    role = session.get('role')
    username = session.get('username')
    if not username:
        return jsonify({"error": "Not logged in"}), 401

    import sqlite3 as _sqlite3
    db_path = app.config['SQLALCHEMY_DATABASE_URI'].replace('sqlite:///', '')
    conn = _sqlite3.connect(db_path)
    conn.row_factory = _sqlite3.Row
    cur = conn.cursor()

    try:
        if role == 'host':
            cur.execute("""
                SELECT ca.username, ca.display_name, ca.centre_id, ca.role, ca.status,
                       ra.plain_password, c.name AS centre_name
                FROM centre_access ca
                LEFT JOIN registerer_accounts ra ON ra.username = ca.username
                LEFT JOIN centres c ON c.centre_id = ca.centre_id
                WHERE ca.role = 'admin'
                ORDER BY ca.centre_id, ca.display_name
            """)
        elif role == 'admin':
            centre_id = session.get('selected_centre_id')
            if not centre_id:
                conn.close()
                return jsonify({"error": "No centre selected"}), 400
            cur.execute("""
                SELECT ca.username, ca.display_name, ca.centre_id, ca.role, ca.status,
                       ra.plain_password, c.name AS centre_name
                FROM centre_access ca
                LEFT JOIN registerer_accounts ra ON ra.username = ca.username
                LEFT JOIN centres c ON c.centre_id = ca.centre_id
                WHERE ca.role = 'registerer' AND ca.centre_id = ?
                ORDER BY ca.display_name
            """, (centre_id,))
        else:
            conn.close()
            return jsonify({"error": "Forbidden"}), 403

        rows = cur.fetchall()
        result = [{
            "username": r["username"],
            "name": r["display_name"] or r["username"],
            "centre_id": r["centre_id"],
            "centre_name": r["centre_name"] or r["centre_id"],
            "role": r["role"],
            "status": r["status"],
            "plain_password": r["plain_password"] or "",
        } for r in rows]
        return jsonify(result)
    finally:
        conn.close()


@app.route('/api/auth/reset-managed-password', methods=['POST'])
def api_auth_reset_managed_password():
    """Host resets an admin's password; admin resets a registerer's password.
    Returns the new plain password on success.
    """
    role = session.get('role')
    username = session.get('username')
    if not username:
        return jsonify({"error": "Not logged in"}), 401

    data = request.json or {}
    target_username = str(data.get('username', '')).strip()
    new_password = str(data.get('new_password', '')).strip()
    if not target_username or not new_password:
        return jsonify({"error": "username and new_password required"}), 400
    if len(new_password) < 6:
        return jsonify({"error": "Password must be at least 6 characters"}), 400

    import sqlite3 as _sqlite3
    db_path = app.config['SQLALCHEMY_DATABASE_URI'].replace('sqlite:///', '')
    conn = _sqlite3.connect(db_path)
    cur = conn.cursor()
    try:
        # Verify scope: host can reset admins, admin can reset registerers in their centre
        if role == 'host':
            cur.execute("SELECT username FROM centre_access WHERE username=? AND role='admin' LIMIT 1", (target_username,))
        elif role == 'admin':
            centre_id = session.get('selected_centre_id')
            cur.execute("SELECT username FROM centre_access WHERE username=? AND role='registerer' AND centre_id=? LIMIT 1", (target_username, centre_id))
        else:
            conn.close()
            return jsonify({"error": "Forbidden"}), 403

        if not cur.fetchone():
            conn.close()
            return jsonify({"error": "User not found or not in your scope"}), 404

        pw_hash = _ae_hash_password(new_password)
        cur.execute("""
            INSERT INTO registerer_accounts (username, role, status, password_hash, plain_password, must_change_password)
            VALUES (?, 'registerer', 'approved', ?, ?, 1)
            ON CONFLICT(username) DO UPDATE SET
                password_hash=excluded.password_hash,
                plain_password=excluded.plain_password,
                password_updated_at=CURRENT_TIMESTAMP,
                must_change_password=1
        """, (target_username, pw_hash, new_password))
        conn.commit()

        # Sync to calling backend (best-effort). Send the hash — CB stores it directly (shared
        # scrypt format); no plaintext goes into the outbox or over the wire.
        try:
            _enqueue_cb_outbox('sync_password_to_cb', {
                'username': target_username,
                'password_hash': pw_hash,
                'must_change': True,
                'password_updated_at': datetime.utcnow().isoformat(),
            })
        except Exception:
            pass

        write_log(f"Password reset for '{target_username}' by '{username}' (role={role})")
        return jsonify({"ok": True, "plain_password": new_password})
    except Exception as e:
        conn.rollback()
        write_log(f"reset_managed_password error: {e}")
        return jsonify({"error": "Failed to reset password"}), 500
    finally:
        conn.close()


@app.route('/api/auth/create-managed-account', methods=['POST'])
def api_auth_create_managed_account():
    """Host creates a centre admin; admin creates a registerer in their centre."""
    role = session.get('role')
    acting_username = session.get('username')
    if not acting_username:
        return jsonify({"error": "Not logged in"}), 401

    data = request.json or {}
    name     = str(data.get('name', '')).strip()
    uname    = str(data.get('username', '')).strip().lower()
    password = str(data.get('password', '')).strip()
    centre_id = str(data.get('centre_id', '')).strip()

    if not all([name, uname, password, centre_id]):
        return jsonify({"error": "name, username, password and centre_id required"}), 400
    if len(password) < 6:
        return jsonify({"error": "Password must be at least 6 characters"}), 400

    if role == 'host':
        target_role = 'admin'
    elif role == 'admin':
        target_role = 'registerer'
        centre_id = session.get('selected_centre_id') or centre_id
    else:
        return jsonify({"error": "Forbidden"}), 403

    centre = Centre.query.filter_by(centre_id=centre_id, status='active').first()
    if not centre:
        return jsonify({"error": "Centre not found"}), 404

    import sqlite3 as _sqlite3
    db_path = app.config['SQLALCHEMY_DATABASE_URI'].replace('sqlite:///', '')
    conn = _sqlite3.connect(db_path)
    cur = conn.cursor()
    try:
        cur.execute("SELECT username FROM registerer_accounts WHERE username=?", (uname,))
        if cur.fetchone():
            conn.close()
            return jsonify({"error": "Username already taken"}), 409

        pw_hash = _ae_hash_password(password)
        cur.execute("""
            INSERT INTO registerer_accounts (username, role, status, password_hash, plain_password, must_change_password)
            VALUES (?, ?, 'approved', ?, ?, 1)
        """, (uname, target_role, pw_hash, password))

        cur.execute("""
            INSERT OR IGNORE INTO centre_access (username, display_name, centre_id, role, status,
                can_create_batch, can_create_session, can_edit_attendance, can_delete_attendance)
            VALUES (?, ?, ?, ?, 'active', 1, 1, 1, 0)
        """, (uname, name, centre_id, target_role))
        conn.commit()
        # No outbox sync to SRKB: when the Android app calls this, SRKB already owns this
        # user with its own hash. Syncing AE's incompatible hash back would break SRKB login.

        write_log(f"Account created: username='{uname}' role={target_role} centre={centre_id} by '{acting_username}'")
        return jsonify({"ok": True, "username": uname, "plain_password": password}), 201
    except Exception as e:
        conn.rollback()
        write_log(f"create_managed_account error: {e}")
        return jsonify({"error": "Failed to create account"}), 500
    finally:
        conn.close()


@app.route('/api/auth/centres', methods=['GET'])
def api_auth_centres():
    """Return the list of centres the logged-in user can access."""
    username = session.get('username')
    if not username:
        return jsonify({"error": "Not logged in"}), 401
    if session.get('role') == 'host':
        # Host sees all active centres
        centres = Centre.query.filter_by(status='active').order_by(Centre.name).all()
        return jsonify({
            "centres": [{"centre_id": c.centre_id, "centre_name": c.name} for c in centres]
        })
    _, _, allowed_centres = get_primary_centre_access_context(username)
    return jsonify({"centres": allowed_centres})


@app.route('/api/auth/select_centre', methods=['POST'])
def api_auth_select_centre():
    """Set the active centre for the current session (native app use)."""
    username = session.get('username')
    if not username:
        return jsonify({"error": "Not logged in"}), 401
    data = request.json or {}
    centre_id = str(data.get('centre_id') or '').strip()
    if not centre_id:
        return jsonify({"error": "centre_id required"}), 400
    centre = Centre.query.filter_by(centre_id=centre_id, status='active').first()
    if not centre:
        return jsonify({"error": "Centre not found"}), 404
    # Verify the user actually has access (unless host)
    if session.get('role') != 'host':
        access = CentreAccess.query.filter_by(
            username=username, centre_id=centre_id, status='active'
        ).first()
        if not access:
            return jsonify({"error": "Access denied"}), 403
    session['selected_centre_id'] = centre_id
    session['selected_centre_name'] = centre.name
    return jsonify({"ok": True, "centre_id": centre_id, "centre_name": centre.name})


@app.route('/api/centre-change/request', methods=['POST'])
def api_request_centre_change():
    username = session.get('username')
    if not username or session.get('role') != 'registerer':
        return jsonify({"ok": False, "message": "Unauthorized"}), 403

    data = request.json or {}
    requested_centre_id = (data.get('requested_centre_id') or '').strip()
    reason = (data.get('reason') or '').strip() or None
    current_centre_id = session.get('selected_centre_id') or DEFAULT_CENTRE_ID

    user = RegistererAccount.query.filter_by(username=username).first()
    access = CentreAccess.query.filter_by(username=username, status='active').first()
    if not user and not access:
        return jsonify({"ok": False, "message": "Unauthorized"}), 403

    current_centre = Centre.query.filter_by(centre_id=current_centre_id).first()
    if not current_centre:
        return jsonify({"ok": False, "message": "Current centre not found."}), 404

    requested_centre = Centre.query.filter_by(centre_id=requested_centre_id).first()
    if not requested_centre:
        return jsonify({"ok": False, "message": "Requested centre not found."}), 404

    if requested_centre_id == current_centre_id:
        return jsonify({"ok": False, "message": "Requested centre is the same as current centre."}), 400

    pending_request = CentreChangeRequest.query.filter_by(
        username=username,
        request_type='centre_change',
        status='pending'
    ).first()
    if pending_request:
        return jsonify({
            "ok": False,
            "message": "You already have a pending centre change request."
        }), 409

    try:
        new_request = CentreChangeRequest(
            username=username,
            request_type='centre_change',
            current_centre_id=current_centre_id,
            requested_centre_id=requested_centre_id,
            status='pending',
            reason=reason
        )
        db.session.add(new_request)
        db.session.commit()
        return jsonify({
            "ok": True,
            "message": "Centre change request submitted. You will remain in your current centre until approved."
        })
    except Exception as e:
        db.session.rollback()
        return jsonify({"ok": False, "message": "Failed to submit centre change request.", "error": str(e)}), 500

@app.route('/api/centre-change/pending', methods=['GET'])
def api_centre_change_pending():
    if not is_host_device() and session.get('role') != 'host':
        return jsonify({"ok": False, "message": "Unauthorized"}), 403

    try:
        requests_q = CentreChangeRequest.query.filter_by(
            request_type='centre_change',
            status='pending'
        ).order_by(CentreChangeRequest.created_at.asc(), CentreChangeRequest.id.asc()).all()
        requests_data = []
        for req in requests_q:
            requests_data.append({
                "id": req.id,
                "username": req.username,
                "current_centre_id": req.current_centre_id,
                "current_centre_name": get_centre_display_name_by_id(req.current_centre_id),
                "requested_centre_id": req.requested_centre_id,
                "requested_centre_name": get_centre_display_name_by_id(req.requested_centre_id),
                "reason": req.reason,
                "created_at": req.created_at.strftime("%Y-%m-%d %H:%M:%S") if req.created_at else None,
                "status": req.status
            })
        return jsonify({"ok": True, "requests": requests_data})
    except Exception as e:
        return jsonify({"ok": False, "message": "Failed to load centre change requests.", "error": str(e)}), 500

@app.route('/api/centre-change/<int:request_id>/review', methods=['POST'])
def api_review_centre_change_request(request_id):
    if not is_host_device() and session.get('role') != 'host':
        return jsonify({"ok": False, "message": "Unauthorized"}), 403

    data = request.json or {}
    action = (data.get('action') or '').strip().lower()
    if action not in ('approve', 'reject'):
        return jsonify({"ok": False, "message": "Invalid action."}), 400

    req_row = CentreChangeRequest.query.filter_by(
        id=request_id,
        request_type='centre_change',
        status='pending'
    ).first()
    if not req_row:
        return jsonify({"ok": False, "message": "Centre change request not found."}), 404

    reviewer = session.get('username') or ("PhysicalHost" if is_host_device() else None)
    now = datetime.now()

    try:
        if action == 'reject':
            req_row.status = 'rejected'
            req_row.reviewed_by = reviewer
            req_row.reviewed_at = now
            db.session.commit()
            return jsonify({"ok": True, "message": "Centre change request rejected."})

        requested_centre = Centre.query.filter_by(centre_id=req_row.requested_centre_id).first()
        if not requested_centre:
            return jsonify({"ok": False, "message": "Requested centre no longer exists."}), 404

        current_access = CentreAccess.query.filter_by(
            username=req_row.username,
            centre_id=req_row.current_centre_id,
            status='active'
        ).first()
        if current_access:
            current_access.status = 'inactive'

        requested_access = CentreAccess.query.filter_by(
            username=req_row.username,
            centre_id=req_row.requested_centre_id
        ).first()

        old_role = current_access.role if current_access and current_access.role else 'registerer'
        if not requested_access:
            requested_access = CentreAccess(
                username=req_row.username,
                display_name=(current_access.display_name if current_access and current_access.display_name else req_row.username),
                centre_id=req_row.requested_centre_id,
                role=old_role
            )
            db.session.add(requested_access)

        if not requested_access.display_name:
            requested_access.display_name = current_access.display_name if current_access and current_access.display_name else req_row.username
        requested_access.status = 'active'
        requested_access.role = requested_access.role or old_role or 'registerer'
        requested_access.can_create_batch = True
        requested_access.can_create_session = True
        requested_access.can_edit_attendance = True
        requested_access.can_delete_attendance = False
        requested_access.approved_by = reviewer
        requested_access.approved_at = now
        requested_access.last_seen_at = requested_access.last_seen_at or now

        req_row.status = 'approved'
        req_row.reviewed_by = reviewer
        req_row.reviewed_at = now
        db.session.commit()
        return jsonify({"ok": True, "message": "Centre change request approved."})
    except Exception as e:
        db.session.rollback()
        return jsonify({"ok": False, "message": "Failed to review centre change request.", "error": str(e)}), 500

@app.route('/manifest.json')
def serve_manifest():
    return send_file(os.path.join(app.static_folder, 'manifest.json'), mimetype='application/json')

@app.route('/apple-touch-icon.png')
@app.route('/apple-touch-icon-precomposed.png')
@app.route('/apple-touch-icon-180x180.png')
def serve_apple_touch_icon():
    # Safety net, not the primary path. iOS prefers the <link rel="apple-touch-icon">
    # tag on the page being installed, and every user-facing template now carries one.
    # But when a page has no such tag, iOS falls back to probing these exact filenames
    # at the ORIGIN ROOT — so pages nobody thought about (downloads, logs, inject) and
    # any future template still get a real icon instead of a screenshot of themselves,
    # and the 404s stop cluttering the access log either way.
    return send_file(os.path.join(app.static_folder, 'icons', 'apple-touch-icon.png'),
                     mimetype='image/png')

@app.route('/sw.js')
def serve_sw():
    # no-store, because this file is how an already-installed wrapper gets fixed.
    # The old Capacitor bundle registers /sw.js on every load, and until 2026-08-10 the
    # script it received reloaded the page from its activate handler — an endless refresh
    # on the device. Correcting the file only helps if the WebView actually re-fetches it;
    # a cached copy of the looping version would keep the loop alive with no way to break
    # in. send_file would otherwise happily serve a 304 off the HTTP cache.
    from flask import make_response
    response = make_response(
        send_file(os.path.join(app.static_folder, 'sw.js'), mimetype='application/javascript'))
    response.headers['Cache-Control'] = 'no-store, no-cache, must-revalidate, max-age=0'
    response.headers['Service-Worker-Allowed'] = '/'
    return response

@app.route('/service-worker.js')
def serve_service_worker():
    from flask import make_response, send_from_directory
    try:
        response = make_response(send_from_directory('static', 'service-worker.js'))
        response.headers['Service-Worker-Allowed'] = '/'
        response.headers['Cache-Control'] = 'no-cache'
        response.headers['Content-Type'] = 'application/javascript'
        return response
    except Exception:
        return '', 404

# --- Shutdown & Status Endpoints ---
SHUTTING_DOWN = False
SHUTDOWN_TIMER_ACTIVE = False

def shutdown_handler(signum, frame):
    global SHUTTING_DOWN, SHUTDOWN_TIMER_ACTIVE
    if SHUTDOWN_TIMER_ACTIVE:
        print("\nForce killing server...")
        os._exit(0)
        
    print("\n[!] Ctrl+C pressed. Initiating graceful shutdown sequence.")
    print("Waiting 6 seconds for clients to 'Extend' before dying...")
    print("Press Ctrl+C again to force quit immediately.")
    
    SHUTTING_DOWN = True
    SHUTDOWN_TIMER_ACTIVE = True
    
    def kill_server():
        global SHUTTING_DOWN, SHUTDOWN_TIMER_ACTIVE
        time.sleep(6)
        if SHUTTING_DOWN:
            print("\nTime's up. Shutting down now.")
            write_log("Server manually shut down by host via terminal.")
            os._exit(0)
        else:
            SHUTDOWN_TIMER_ACTIVE = False
            
    threading.Thread(target=kill_server, daemon=True).start()

signal.signal(signal.SIGINT, shutdown_handler)

@app.route('/api/status')
def get_status():
    return jsonify({"shutting_down": SHUTTING_DOWN})

@app.route('/api/tunnel_url')
def get_tunnel_url():
    return jsonify({"url": TUNNEL_URL})

@app.route('/api/extend_server', methods=['POST'])
def extend_server():
    global SHUTTING_DOWN
    if SHUTTING_DOWN:
        SHUTTING_DOWN = False
        write_log(f"Server shutdown cancelled by client IP: {request.remote_addr}")
        print("\n[+] Shutdown Cancelled by a remote client!")
        return jsonify({"message": "Server extended successfully."})
    return jsonify({"message": "Server was not shutting down."})

@app.route('/api/logs_data')
def api_logs_data():
    if os.path.exists(LOG_PATH):
        with open(LOG_PATH, 'r', encoding='utf-8') as f:
            return jsonify({"logs": f.read()})
    return jsonify({"logs": "No logs recorded yet."})


@app.route('/api/logs/today')
def api_logs_today():
    """Return today's API request log as JSON array of lines."""
    date_str = _current_date_str()
    day_log = os.path.join(LOG_DIR, f"attendance-{date_str}.log")
    if not os.path.exists(day_log):
        return jsonify({"success": True, "date": date_str, "lines": []})
    with open(day_log, 'r', encoding='utf-8') as f:
        lines = [l.rstrip('\n') for l in f if l.strip()]
    return jsonify({"success": True, "date": date_str, "lines": lines})


@app.route('/api/logs/list')
def api_logs_list():
    """List available daily attendance API log files."""
    files = []
    if os.path.exists(LOG_DIR):
        files = sorted(
            [fn for fn in os.listdir(LOG_DIR) if fn.startswith('attendance-') and fn.endswith('.log')],
            reverse=True
        )
    return jsonify({"success": True, "files": files})


@app.route('/api/logs/date/<date_str>')
def api_logs_date(date_str):
    """Return attendance API log for a specific date (YYYY-MM-DD)."""
    import re
    if not re.match(r'^\d{4}-\d{2}-\d{2}$', date_str):
        return jsonify({"error": "Invalid date format"}), 400
    day_log = os.path.join(LOG_DIR, f"attendance-{date_str}.log")
    if not os.path.exists(day_log):
        return jsonify({"success": True, "date": date_str, "lines": []})
    with open(day_log, 'r', encoding='utf-8') as f:
        lines = [l.rstrip('\n') for l in f if l.strip()]
    return jsonify({"success": True, "date": date_str, "lines": lines})

# ── Android app release manifest + forced-update channel ─────────────────────
# The Unified app had no way to learn that a newer build existed: every APK ever
# shipped was versionCode 1, so nothing could compare versions and stale builds ran
# indefinitely. The app now reports its versionCode to /api/app/version and this
# manifest decides whether it may carry on.
#
# Edit Server file/app_release.json by hand when you publish a build:
#   latest_version_code  — the newest build that exists (drives "update available")
#   minimum_version_code — anything BELOW this is refused and must update (the
#                          mandatory lever). Leave it equal to the previous release
#                          until you actually want to force everyone forward.
#   apk_filename         — file inside apk/ that /download/unified-app serves
#   notes                — changelog lines shown on the /app page and in the app
APP_RELEASE_PATH = os.path.join(SERVER_DIR, "app_release.json")

_APP_RELEASE_DEFAULTS = {
    "latest_version_code": 1,
    "latest_version_name": "1.0",
    "minimum_version_code": 1,
    "apk_filename": "SRKB-Unified-latest.apk",
    "notes": [],
}


def read_app_release():
    """Load the release manifest, falling back to safe defaults."""
    data = dict(_APP_RELEASE_DEFAULTS)
    try:
        if os.path.exists(APP_RELEASE_PATH):
            with open(APP_RELEASE_PATH, "r", encoding="utf-8") as f:
                loaded = json.load(f)
            if isinstance(loaded, dict):
                data.update({k: v for k, v in loaded.items() if k in _APP_RELEASE_DEFAULTS})
    except Exception as e:
        write_log(f"app_release.json unreadable, using defaults: {e}")

    try:
        data["latest_version_code"] = int(data["latest_version_code"])
        data["minimum_version_code"] = int(data["minimum_version_code"])
    except (TypeError, ValueError):
        write_log("app_release.json: non-numeric version codes — falling back to 1/1")
        data["latest_version_code"] = 1
        data["minimum_version_code"] = 1

    # A manifest demanding a build that does not exist yet would lock every device
    # out of the app at once, with no way back in from the phone. Clamp rather than
    # honour it: a typo here must never be able to brick the fleet.
    if data["minimum_version_code"] > data["latest_version_code"]:
        write_log(
            f"app_release.json: minimum_version_code ({data['minimum_version_code']}) exceeds "
            f"latest ({data['latest_version_code']}) — clamping so nobody is locked out"
        )
        data["minimum_version_code"] = data["latest_version_code"]

    if not isinstance(data.get("notes"), list):
        data["notes"] = []
    return data


def _release_apk_path(rel=None):
    """Absolute path of the APK this release serves, or None when it isn't there.
    basename() so a bad manifest can't walk out of apk/ into the filesystem."""
    rel = rel or read_app_release()
    fname = os.path.basename(str(rel.get("apk_filename") or "").strip())
    if not fname:
        return None
    path = os.path.join(basedir, "apk", fname)
    return path if os.path.exists(path) else None


@app.route('/api/app/version', methods=['GET', 'POST'])
def api_app_version():
    """The app reports the build it is running; we answer whether it may continue.

    Deliberately public (listed in restrict_access open_paths): a device on a build
    too old to log in must still be able to discover that it needs updating.
    """
    payload = request.get_json(silent=True) or {}
    raw = (request.args.get('version_code')
           or payload.get('version_code')
           or payload.get('versionCode'))
    try:
        client_code = int(str(raw).strip())
    except (TypeError, ValueError):
        # Absent or unparseable means a build predating this endpoint. Treating it
        # as 0 puts it behind every real release, which is exactly right.
        client_code = 0

    rel = read_app_release()
    apk_available = _release_apk_path(rel) is not None

    # Log the build each phone is ACTUALLY running. Nothing else on either server records
    # it — /login carries no version, and the emergency client dump reports the literal
    # string "unified" — so when a fix has shipped and the symptom persists there is no way
    # to tell a broken fix from a phone that never took the update. On 2026-09-12 that cost a
    # whole re-investigation of the call-outcome race fixed in v44: the duplicate-save rate
    # was unchanged across the release and the only untested explanation was the version in
    # the field. One line here answers it on the next app start.
    try:
        write_api_log('GET', '/api/app/version', 200, 0, 'anon',
                      f"build=v{client_code} latest=v{rel['latest_version_code']} "
                      f"min=v{rel['minimum_version_code']} ip={get_client_ip()}")
    except Exception:
        pass  # a diagnostic must never be able to fail the version check itself

    return jsonify({
        "ok": True,
        "client_version_code": client_code,
        "latest_version_code": rel["latest_version_code"],
        "latest_version_name": rel["latest_version_name"],
        "minimum_version_code": rel["minimum_version_code"],
        "update_available": client_code < rel["latest_version_code"],
        # Never force an update we cannot actually serve — that would leave the user
        # blocked with a download link that 404s.
        "update_required": client_code < rel["minimum_version_code"] and apk_available,
        # Relative on purpose: the client prefixes its own configured backend URL,
        # which is the only host it is guaranteed to be able to reach.
        "download_page": "/app",
        "apk_url": "/download/unified-app" if apk_available else "",
        "notes": rel["notes"],
    })


@app.route('/download/unified-app')
def download_unified_app():
    """Serve the APK named by the release manifest."""
    rel = read_app_release()
    apk_path = _release_apk_path(rel)
    if not apk_path:
        return jsonify({
            "error": "APK not found — build it and copy it into apk/",
            "expected": rel.get("apk_filename"),
        }), 404
    from flask import send_file
    return send_file(apk_path, as_attachment=True,
                     download_name=os.path.basename(apk_path),
                     mimetype='application/vnd.android.package-archive')


@app.route('/app')
def app_download_page():
    """Landing page with APK download links."""
    import datetime as _dt
    from html import escape as html_escape

    def _apk_meta(path):
        try:
            size = os.path.getsize(path)
            mtime = os.path.getmtime(path)
            size_str = f"{size / (1024*1024):.1f} MB" if size >= 1024*1024 else f"{size/1024:.0f} KB"
            date_str = _dt.datetime.fromtimestamp(mtime).strftime("%-d %b %Y, %I:%M %p")
            return size_str, date_str
        except Exception:
            return "—", "—"

    # Driven by the release manifest rather than hardcoded, so publishing a build is
    # a one-file edit and this page can never drift from what /api/app/version tells
    # the app. It used to point at apk/SRKB-App.apk with a changelog frozen in the
    # source, and quietly served a build a month older than the current one.
    release = read_app_release()
    srkb_path = _release_apk_path(release)
    srkb_exists = srkb_path is not None
    srkb_size, srkb_date = _apk_meta(srkb_path) if srkb_exists else ("—", "—")
    version_label = f"v{release['latest_version_name']} (build {release['latest_version_code']})"

    changelog = release["notes"] or ["No release notes recorded for this build."]
    cl_items = "".join(f"<li>{html_escape(str(x))}</li>" for x in changelog)

    # Apple devices have NO installable file and never will: an .ipa can only be
    # produced and signed through Xcode on a Mac, and sideloading one needs either the
    # App Store or a paid developer profile. The PWA exists precisely to avoid that, so
    # for iPhone/iPad/Mac the "installer" IS the browser flow. What this card does is
    # make that flow discoverable from the same page as the Android APK, instead of
    # leaving Apple users to be told the steps by word of mouth.
    #
    # Built as a plain (non-f) string and interpolated as {apple_card} below, because
    # the surrounding html is an f-string — inline JS braces would otherwise all need
    # doubling, which is exactly how this kind of block gets silently broken later.
    apple_card = '''<div class="card">
  <h2>iPhone, iPad &amp; Mac</h2>
  <p><strong>Nothing to download.</strong> Apple devices install AttendEase straight
     from the browser — it then behaves like a normal app, with its own icon and no
     browser bars, and keeps working offline.</p>
  <a href="/register" class="btn" style="margin-bottom:14px">Step 1 &nbsp;·&nbsp; Open AttendEase</a>
  <div id="apple-steps" class="steps"></div>
</div>
<script>
(function () {
  var ua = navigator.userAgent;
  // iPadOS 13+ reports itself as "MacIntel"; maxTouchPoints is what separates an
  // iPad from a real Mac. Without this, iPads get shown the macOS instructions.
  var isIOS = /iPad|iPhone|iPod/.test(ua) ||
              (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  var isMac = !isIOS && /Macintosh|Mac OS X/.test(ua);
  // On iOS every browser is Safari underneath, but only real Safari exposes
  // "Add to Home Screen" in its share sheet — Chrome/Firefox/Edge on iOS do not.
  // Sending someone to the share sheet in the wrong browser is a dead end.
  var iosSafari = isIOS && !/CriOS|FxiOS|EdgiOS|OPiOS/.test(ua);
  var installed = window.matchMedia('(display-mode: standalone)').matches ||
                  navigator.standalone === true;
  var el = document.getElementById('apple-steps');
  var html;

  // SELF-REPAIR for icons that were pinned to THIS page.
  //
  // iOS "Add to Home Screen" and macOS Safari "Add to Dock" pin the URL that is on
  // screen at the time — Safari's Add to Dock ignores the manifest's start_url
  // entirely. So anyone who installed while reading these instructions got an icon
  // that opens the download page instead of the app, every single time.
  //
  // If we are running standalone, this page is not where the user meant to land, so
  // forward to the app. location.replace, not href: the launch URL must not stay in
  // the back stack, or the app's back gesture returns here.
  //
  // This repairs an already-broken Dock/home-screen icon with no reinstall — which
  // matters because telling every user to delete and re-add the icon is not a fix.
  if (installed) {
    location.replace('/register');
    return;
  }

  if (isIOS && !iosSafari) {
    html = '<p class="warn">⚠️ Open this page in <strong>Safari</strong>. ' +
           'Chrome, Firefox and Edge on iPhone/iPad cannot add apps to the home screen.</p>';
  } else if (isIOS) {
    html = '<p class="warn"><strong>Step 2 — do this on the app page, not here.</strong> ' +
           'iPhone saves whichever page is open when you tap Add, so installing from ' +
           'this page gives you an icon that keeps reopening this page.</p>' +
           '<ol><li>Tap <strong>Step 1</strong> above first.</li>' +
           '<li>On the page that opens, tap the <strong>Share</strong> button (the ' +
           'square with an arrow) at the bottom of Safari.</li>' +
           '<li>Scroll down and tap <strong>Add to Home Screen</strong>, then <strong>Add</strong>.</li>' +
           '<li>Open it from the new icon and <strong>log in there once</strong> — the ' +
           'installed app has its own separate login from Safari.</li></ol>';
  } else if (isMac) {
    html = '<p class="warn"><strong>Step 2 — do this on the app page, not here.</strong> ' +
           'Safari adds whichever page is open to the Dock, so installing from this ' +
           'page gives you a Dock icon that keeps reopening this page.</p>' +
           '<ol><li>Click <strong>Step 1</strong> above first.</li>' +
           '<li><strong>Safari:</strong> menu bar → <strong>File</strong> → ' +
           '<strong>Add to Dock…</strong> → <strong>Add</strong>.</li>' +
           '<li><strong>Chrome or Edge instead:</strong> click the install icon in the ' +
           'address bar (a screen with a down-arrow) → <strong>Install</strong>.</li>' +
           '<li>Launch it from the Dock, and <strong>log in there once</strong> — the ' +
           'installed app has its own separate login from Safari.</li></ol>' +
           '<p class="note">Needs macOS Sonoma (14) or newer for Safari’s Add to Dock. ' +
           'On older macOS use Chrome or Edge.</p>';
  } else {
    html = '<p class="note">These steps are for Apple devices. Open this page on an ' +
           'iPhone, iPad or Mac to see the exact steps for that device.</p>' +
           '<ol><li><strong>iPhone / iPad:</strong> Safari → Share → Add to Home Screen.</li>' +
           '<li><strong>Mac:</strong> Safari → File → Add to Dock.</li></ol>';
  }
  el.innerHTML = html;
})();
</script>'''

    def card(title, subtitle, size, date, url, exists):
        meta = f'<div class="meta">🗓 Built {date} &nbsp;·&nbsp; {size}</div>' if exists else ''
        btn  = (f'<a href="{url}" class="btn">⬇ Download</a>'
                if exists else '<span class="btn disabled">Not built yet</span>')
        return f'<div class="card"><h2>{title}</h2><p>{subtitle}</p>{meta}{btn}</div>'

    html = f'''<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Install AttendEase / SRKB</title>
<style>
  *{{box-sizing:border-box;margin:0;padding:0}}
  body{{font-family:system-ui,sans-serif;background:#f0f4f8;min-height:100vh;
       display:flex;flex-direction:column;align-items:center;padding:40px 16px}}
  h1{{color:#1a202c;font-size:1.6rem;margin-bottom:6px;text-align:center}}
  .sub{{color:#718096;margin-bottom:28px;text-align:center;font-size:.95rem}}
  .card{{background:#fff;border-radius:12px;padding:24px;width:100%;
         max-width:440px;box-shadow:0 2px 12px rgba(0,0,0,.08);margin-bottom:16px}}
  .card h2{{color:#2d3748;font-size:1.15rem;margin-bottom:6px}}
  .card p{{color:#718096;font-size:.88rem;margin-bottom:12px;line-height:1.5}}
  .meta{{font-size:.78rem;color:#a0aec0;margin-bottom:14px}}
  .steps{{margin-bottom:14px}}
  .steps ol{{padding-left:20px;color:#4a5568;font-size:.86rem;line-height:1.75}}
  .steps li{{margin-bottom:4px}}
  .steps .note{{font-size:.78rem;color:#a0aec0;margin-top:10px;line-height:1.5}}
  .steps .warn{{background:#fffaf0;border-left:3px solid #dd6b20;color:#7b341e;
                padding:10px 12px;border-radius:6px;font-size:.85rem;line-height:1.5}}
  .steps .ok{{background:#f0fff4;border-left:3px solid #38a169;color:#22543d;
              padding:10px 12px;border-radius:6px;font-size:.85rem}}
  .btn{{display:block;text-align:center;background:#3182ce;color:#fff;
        text-decoration:none;padding:13px 20px;border-radius:8px;font-weight:700;
        font-size:.95rem;transition:background .2s;letter-spacing:.02em}}
  .btn:hover{{background:#2b6cb0}}
  .btn.disabled{{background:#a0aec0;cursor:not-allowed;pointer-events:none}}
  .changelog{{background:#fff;border-radius:12px;padding:22px 24px;width:100%;
              max-width:440px;box-shadow:0 2px 12px rgba(0,0,0,.08);margin-bottom:16px}}
  .changelog h3{{color:#2d3748;font-size:1rem;font-weight:700;margin-bottom:12px;
                 display:flex;align-items:center;gap:6px}}
  .changelog ul{{padding-left:18px;color:#4a5568;font-size:.85rem;line-height:1.7}}
  footer{{color:#a0aec0;font-size:.78rem;margin-top:12px;text-align:center}}
</style>
</head>
<body>
<h1>Install the App</h1>
<!-- Not "SRKB App Downloads" any more. This page is now the install route for Apple
     devices too, where there is nothing to download at all — someone handed this link
     on an iPhone was landing on a page named after an Android APK and reasonably
     concluded they had the wrong link. -->
<p class="sub">Pick your device below</p>
{card(f"SRKB App &mdash; {version_label}", "Full-featured app for callers and attendance managers. Requires login credentials.", srkb_size, srkb_date, "/download/unified-app", srkb_exists)}
{apple_card}
<div class="changelog">
  <h3>📋 What&rsquo;s new in {version_label}</h3>
  <ul>{cl_items}</ul>
</div>
<footer>Android only: enable &ldquo;Install from unknown sources&rdquo; in settings before installing the APK.<br>
iPhone, iPad and Mac need no download and no such setting.</footer>
</body>
</html>'''
    from flask import Response
    return Response(html, mimetype='text/html')


@app.route('/download/srkb-app')
def download_srkb_app():
    apk_path = os.path.join(basedir, 'apk', 'SRKB-App.apk')
    if not os.path.exists(apk_path):
        return jsonify({"error": "APK not found — build it first"}), 404
    from flask import send_file
    return send_file(apk_path, as_attachment=True, download_name='SRKB-App.apk', mimetype='application/vnd.android.package-archive')


@app.route('/download/attendease-wrapper')
def download_attendease_wrapper():
    """
    Retired. This served the old legacy wrapper (apk/AttendEase-Wrapper.apk) —
    a plain WebView with none of the offline/JWT-bridge logic built into the
    current Capacitor-based wrapper (android-app/). Keeping both reachable
    caused real confusion (someone installing the stale one and seeing broken
    offline behavior). Disabled rather than deleted, in case the old file is
    ever needed for reference.
    """
    return jsonify({
        "error": "This build has been retired.",
        "message": "Get the current AttendEase app build from your administrator.",
    }), 410


@app.route('/download/app')
def download_app():
    """Legacy route — same as /download/srkb-app."""
    apk_path = os.path.join(basedir, 'apk', 'SRKB-App.apk')
    if not os.path.exists(apk_path):
        return jsonify({"error": "APK not found — build it first"}), 404
    from flask import send_file
    return send_file(apk_path, as_attachment=True, download_name='AttendEase.apk', mimetype='application/vnd.android.package-archive')


# --- Windows desktop app: update feed + installer -----------------------------
# The Electron wrapper (attendease-desktop/) self-updates via electron-updater,
# which polls  <this-domain>/desktop/latest.yml  and then downloads the .exe +
# .blockmap referenced there. We publish those three artifacts into the
# basedir/desktop/ folder (electron-builder writes them to attendease-desktop/
# release/ — copy them across on each release; see attendease-desktop/README.md).
DESKTOP_DIR = os.path.join(basedir, 'desktop')

# Only these extensions are ever served from the desktop folder, so the route
# can't be turned into an arbitrary-file reader.
_DESKTOP_ALLOWED_EXT = ('.yml', '.yaml', '.exe', '.blockmap')


@app.route('/desktop/<path:filename>')
def desktop_update_feed(filename):
    """Serve the electron-updater feed (latest.yml) and installer artifacts."""
    from flask import send_from_directory, abort
    # Reject path traversal and anything outside the small allowlist.
    if '/' in filename or '\\' in filename or filename.startswith('.'):
        abort(404)
    if not filename.lower().endswith(_DESKTOP_ALLOWED_EXT):
        abort(404)
    full = os.path.join(DESKTOP_DIR, filename)
    if not os.path.isfile(full):
        abort(404)
    if filename.lower().endswith(('.yml', '.yaml')):
        # The feed manifest must never be cached, or clients miss new releases.
        resp = send_from_directory(DESKTOP_DIR, filename, mimetype='text/yaml')
        resp.headers['Cache-Control'] = 'no-cache, no-store, must-revalidate'
        return resp
    return send_from_directory(DESKTOP_DIR, filename,
                               mimetype='application/octet-stream',
                               as_attachment=filename.lower().endswith('.exe'))


@app.route('/download/attendease-desktop')
def download_attendease_desktop():
    """Direct link to the latest Windows installer (for a downloads page button)."""
    from flask import send_from_directory, abort
    if not os.path.isdir(DESKTOP_DIR):
        return jsonify({"error": "Desktop build not published yet"}), 404
    exes = sorted(f for f in os.listdir(DESKTOP_DIR) if f.lower().endswith('.exe'))
    if not exes:
        return jsonify({"error": "Desktop installer not found"}), 404
    return send_from_directory(DESKTOP_DIR, exes[-1], as_attachment=True,
                               mimetype='application/octet-stream')


HOST_SHEET_SCOPE = "*"   # sentinel: this caller may see every sheet


def _download_centre_scope():
    """
    Which centres' sheets the current caller may touch. HOST_SHEET_SCOPE = all; otherwise a set
    of centre_ids (possibly empty, meaning "none — deny everything").

    2026-08-17 ROOT FIX. _sheet_belongs_to_caller() used to identify the caller with
    get_selected_centre_id(), which is `session["selected_centre_id"] or DEFAULT_CENTRE_ID` — a
    read of the FLASK SESSION COOKIE. The Android app never establishes one (no CookieJar anywhere
    in the Kotlin, so OkHttp defaults to NO_COOKIES) and restrict_access() returns the moment a JWT
    validates without populating the session. So every app user at every centre silently resolved
    to DEFAULT_CENTRE_ID and the guard compared the wrong identity.

    Measured live before this fix: a non-host Bearer caller was served 156 of 170 sheets — all of
    Centre B's — and 0 of Centre A's own 8. A leak and a lockout in one bug, which is why it
    went unreported: the 46 users at the default centre happened to get the right answer.

    Resolution order mirrors the JWT-aware pattern already used by centre_id_authorized():
      1. an explicit ?centre_id=, but only after centre_id_authorized() approves it
      2. a JWT caller's own CentreAccess rows (primary + every allowed centre)
      3. the Flask session, for browser callers, where it was always correct
    Step 2 means this works with the CURRENTLY INSTALLED APK — the app does not have to send
    anything new for the leak to close.
    """
    if caller_is_host():
        return HOST_SHEET_SCOPE

    explicit = (request.args.get('centre_id') or '').strip()
    if explicit:
        # Never trust a client-supplied centre in a guard without authorising it first.
        return {explicit} if centre_id_authorized(explicit) else set()

    jwt_username = getattr(g, 'jwt_username', None)
    if jwt_username:
        _access, primary_centre, allowed = get_primary_centre_access_context(jwt_username)
        ids = {c.get('centre_id') for c in (allowed or []) if isinstance(c, dict) and c.get('centre_id')}
        if primary_centre:
            ids.add(primary_centre.centre_id)
        # A JWT caller with no centre access gets nothing. Deliberately NOT a fallback to
        # DEFAULT_CENTRE_ID — that fallback is the entire bug this function exists to fix.
        return ids

    selected = get_selected_centre_id()
    return {selected} if selected else set()


def _sheet_belongs_to_caller(sheet_name, scope=None, mapping_by_sheet=None):
    """
    True if the current caller may see/download this sheet.

    Added 2026-07-04 — LIVE_FILE is a single Excel workbook shared across ALL
    centres (one sheet per session, mixed together); list_server_files and
    download_server_file previously returned/served every sheet name in it to
    any logged-in registerer with zero centre check — a full cross-centre
    attendance-data leak via the registerer's "sheet list" UI tab.

    `scope` / `mapping_by_sheet` let a caller in a loop resolve both once instead of per sheet;
    list_server_files was doing one SessionMapping query per sheet, 170 of them per page load.
    """
    if scope is None:
        scope = _download_centre_scope()
    if scope == HOST_SHEET_SCOPE:
        return True
    if not scope:
        return False
    if sheet_name == "MASTER_ALL_SESSIONS":
        return False  # aggregates every centre — host-only

    if mapping_by_sheet is not None:
        centre_of_sheet = mapping_by_sheet.get(sheet_name)
        found = sheet_name in mapping_by_sheet
    else:
        mapping = SessionMapping.query.filter_by(session_id=sheet_name).first()
        found = mapping is not None
        centre_of_sheet = mapping.centre_id if mapping else None

    if not found:
        # Legacy sheets created before per-centre tracking existed have no
        # mapping row — they all predate multi-centre support, so treat them
        # as belonging to the original default centre rather than exposing
        # them to every registerer by default. 121 of 170 sheets are in this
        # state; narrowing it further would hide sheets the default centre
        # actively uses, so that is a data decision, not a code one.
        return DEFAULT_CENTRE_ID in scope
    return centre_of_sheet in scope


def _sheet_category(sheet_name, category_by_sheet=None):
    """A sheet's track. Unmapped or unset means youth, the system-wide default rule."""
    if category_by_sheet is not None:
        return (category_by_sheet.get(sheet_name) or 'youth')
    m = SessionMapping.query.filter_by(session_id=sheet_name).first()
    return ((m.category if m else None) or 'youth')


def _sheet_category_allowed(sheet_name, category_by_sheet=None):
    """
    Enforce ?category= on a single-sheet fetch. Additive by design: with no category claimed
    nothing is filtered, so this cannot blank an existing screen. Only a caller that explicitly
    says "I am on the congregation track" is held to it.
    """
    claimed = (request.args.get('category') or '').strip().lower()
    if claimed not in ('youth', 'congregation'):
        return True
    return _sheet_category(sheet_name, category_by_sheet) == claimed


@app.route('/api/list_server_files')
def list_server_files():
    ensure_live_workbook_readable()
    if not os.path.exists(LIVE_FILE):
        return jsonify([])
    try:
        from openpyxl import load_workbook
        wb = load_workbook(LIVE_FILE, read_only=True)
        sheets = wb.sheetnames
        wb.close()

        # Youth/Congregation: when a category is supplied, only show that track's
        # sheets. Sheet name == session_id; look up its SessionMapping.category.
        # Unmapped sheets (e.g. MASTER_ALL_SESSIONS, legacy) default to youth.
        category = (request.args.get('category') or '').strip().lower()

        # One pass over SessionMapping instead of one query per sheet inside the loop
        # (170 sheets = 170 queries on every page load of the Downloads tab).
        centre_map = {}
        category_map = {}
        for m in SessionMapping.query.all():
            centre_map[m.session_id] = m.centre_id
            category_map[m.session_id] = (m.category or 'youth')

        scope = _download_centre_scope()

        files = []
        for s in sheets:
            if not _sheet_belongs_to_caller(s, scope=scope, mapping_by_sheet=centre_map):
                continue
            if category in ('youth', 'congregation'):
                if (category_map.get(s) or 'youth') != category:
                    continue
            files.append({
                "name": s,
                "size": os.path.getsize(LIVE_FILE) if s == "MASTER_ALL_SESSIONS" else 5120,
                "time": datetime.fromtimestamp(os.path.getmtime(LIVE_FILE)).strftime("%Y-%m-%d %H:%M")
            })
        return jsonify(files)
    except Exception as e:
        print(f"[ERROR] list_server_files: {str(e)}")
        return jsonify([])

@app.route('/api/download_file/<sheet_name>')
def download_server_file(sheet_name):
    if not _sheet_belongs_to_caller(sheet_name):
        return "Forbidden", 403
    if not _sheet_category_allowed(sheet_name):
        return "Forbidden", 403

    ensure_live_workbook_readable()
    # Reconcile from DB first to ensure zero data loss on download
    try:
        reconcile_live_workbook_from_db(target_session_id=sheet_name)
    except Exception:
        pass

    if not os.path.exists(LIVE_FILE):
        return "Master file not found", 404

    try:
        from openpyxl import load_workbook, Workbook
        wb = load_workbook(LIVE_FILE, read_only=True)
        if sheet_name not in wb.sheetnames:
            return "Sheet not found", 404
            
        # Extract just this sheet into a new workbook
        new_wb = Workbook()
        new_ws = new_wb.active
        new_ws.title = sheet_name
        
        # Copy data
        for row in wb[sheet_name].iter_rows(values_only=True):
            new_ws.append(row)
            
        wb.close()
        
        # Save temp file
        if not os.path.exists(TEMP_DIR): os.makedirs(TEMP_DIR, exist_ok=True)
        temp_path = os.path.join(TEMP_DIR, f"{sheet_name}.xlsx")
        new_wb.save(temp_path)
        new_wb.close()
        
        write_log(f"Registerer downloaded sheet: {sheet_name}")
        return send_file(temp_path, as_attachment=True, download_name=f"{sheet_name}.xlsx")
    except Exception as e:
        print(f"Error extracting sheet: {e}")
        return "Error extracting sheet", 500

@app.route('/api/get_last_update')
def get_last_update():
    """Returns the last modification timestamp of the master Excel file."""
    if os.path.exists(LIVE_FILE):
        mtime = os.path.getmtime(LIVE_FILE)
        return jsonify({"mtime": mtime})
    return jsonify({"mtime": 0})

# --- API Endpoints ---

@app.route('/api/set_session', methods=['POST'])
def set_session():
    data = request.json or {}
    batch_id = data.get('batch_id')
    batch_name = data.get('batch_name')
    date = data.get('date')
    session_id = data.get('session_id')
    selected_centre_id = data.get('centre_id') or get_selected_centre_id()
    if not centre_id_authorized(selected_centre_id):
        return jsonify({"error": "Forbidden"}), 403
    selected_centre_name = get_centre_name_by_id(selected_centre_id)
    # Youth/Congregation: which slot this session activates. Prefer the request's
    # toggle, but the resolved batch's own category is authoritative and overrides.
    active_category = str(data.get('category') or 'youth').strip().lower()
    if active_category not in ('youth', 'congregation'):
        active_category = 'youth'

    session['selected_centre_id'] = selected_centre_id
    session['selected_centre_name'] = selected_centre_name

    def resolve_batch_for_centre(batch_id_value=None, batch_name_value=None):
        batch_obj = None
        if batch_id_value:
            batch_obj = Batch.query.filter_by(id=batch_id_value, centre_id=selected_centre_id).first()
        if not batch_obj and batch_name_value:
            batch_obj = Batch.query.filter_by(name=batch_name_value, centre_id=selected_centre_id).first()
        return batch_obj

    if batch_id or batch_name:
        batch = resolve_batch_for_centre(batch_id, batch_name)
        if batch:
            batch_id = batch.id
            batch_name = batch.name
            active_category = (batch.category or active_category)
            selected_centre_id = batch.centre_id or selected_centre_id
            selected_centre_name = get_centre_name_by_id(selected_centre_id)
            session['selected_centre_id'] = selected_centre_id
            session['selected_centre_name'] = selected_centre_name
        else:
            return jsonify({"error": "Batch not found for selected centre"}), 404

    if session_id == "":
        if batch_id or batch_name:
            set_active_session(selected_centre_id, {
                "centre_id": selected_centre_id,
                "centre_name": selected_centre_name,
                "category": active_category,
                "session_id": None,
                "batch_id": batch_id,
                "batch_name": batch_name,
                "date": None,
                "sheet_name": None,
            }, active_category)
            session.pop('active_session_id', None)
            session.pop('active_session_sheet', None)
            regenerate_cache_files(selected_centre_id)
            return jsonify({
                "message": "Batch selected",
                "centre_id": selected_centre_id,
                "centre_name": selected_centre_name,
                "category": active_category,
                "batch_id": batch_id,
                "batch_name": batch_name
            })

        set_active_session(selected_centre_id, {
            "centre_id": selected_centre_id,
            "centre_name": selected_centre_name,
            "category": active_category,
            "session_id": None,
            "batch_id": None,
            "batch_name": None,
            "date": None,
            "sheet_name": None,
        }, active_category)
        session.pop('active_session_id', None)
        session.pop('active_session_sheet', None)
        regenerate_cache_files(selected_centre_id)
        return jsonify({
            "message": "Session cleared",
            "centre_id": selected_centre_id,
            "centre_name": selected_centre_name
        })

    if session_id:
        
        # In the new clean workflow, session_id is just the name (e.g. "Physics")
        # The date is a separate field.
        #
        # A session that has a mapping row has an authoritative date, and it wins over
        # whatever the client sent — the header auto-fills to today (sync.js:175), so
        # trusting it files yesterday's session under today. See resolve_session_date().
        mapped_date = resolve_session_date(session_id, selected_centre_id)
        if mapped_date:
            date = mapped_date
        elif not date:
            date = get_active_session(selected_centre_id, active_category).get('date') or datetime.now().strftime("%Y-%m-%d")

        # Look up batch for this session if the caller did not explicitly provide one.
        mapping = SessionMapping.query.filter_by(session_id=session_id, centre_id=selected_centre_id).first()
        if not batch_id and not batch_name and mapping:
            batch = Batch.query.filter_by(id=mapping.batch_id, centre_id=selected_centre_id).first()
            batch_name = batch.name if batch else None
            batch_id = batch.id if batch else mapping.batch_id
            if batch and batch.category:
                active_category = batch.category
        if not batch_name and get_active_session(selected_centre_id, active_category).get('batch_id') and get_active_session(selected_centre_id, active_category).get('batch_name'):
            preserved_batch_id = get_active_session(selected_centre_id, active_category).get('batch_id')
            preserved_batch_name = get_active_session(selected_centre_id, active_category).get('batch_name')
            preserved_batch = resolve_batch_for_centre(preserved_batch_id, preserved_batch_name)
            if preserved_batch:
                batch_id = batch_id or preserved_batch.id
                batch_name = batch_name or preserved_batch.name
    else:
        if not batch_name and not batch_id and not date:
            return jsonify({"error": "Batch Name and Date are required"}), 400
        session_id = batch_name or ""
    
    sheet_name = sanitize_sheet_name(session_id)
    
    # Update per-centre session state
    set_active_session(selected_centre_id, {
        "centre_id": selected_centre_id,
        "centre_name": selected_centre_name,
        "category": active_category,
        "session_id": session_id,
        "batch_id": batch_id,
        "batch_name": batch_name,
        "date": date,
        "sheet_name": sheet_name,
    }, active_category)
    
    session['selected_centre_id'] = selected_centre_id
    session['selected_centre_name'] = selected_centre_name
    session['active_session_id'] = session_id
    session['active_session_sheet'] = sheet_name
    
    regenerate_cache_files(selected_centre_id)
    return jsonify({
        "message": f"Session set to {session_id} on {date}",
        "sheet_name": sheet_name,
        "session_id": session_id,
        "centre_id": selected_centre_id,
        "centre_name": selected_centre_name
    })

# --- Calling Backend batch-member API ---

@app.route('/api/internal/batch_members/invalidate_cache', methods=['POST'])
def invalidate_batch_members_cache():
    """Called by Calling Backend outbox worker to signal membership changes.
    AttendEase pulls member lists on demand, so this is a no-op hint. Always 200.
    Auth: x-internal-token (from CB outbox worker).
    """
    token = request.headers.get('x-internal-token', '')
    if token != CALLING_BACKEND_INTERNAL_TOKEN:
        return jsonify({'ok': False, 'message': 'Invalid token'}), 401
    write_log(f"batch_members cache invalidation hint received: {request.json}")
    return jsonify({'ok': True})

@app.route('/api/internal/create-calling-session', methods=['POST'])
def create_calling_session():
    """Called by Calling Backend when a calling session (SessionCampaign) is created.
    Creates or updates the corresponding session in AttendEase so it appears in the
    registerer's Mark Attendance dropdown.
    Auth: x-internal-token (from CB outbox worker).
    """
    token = request.headers.get('x-internal-token', '')
    if token != CALLING_BACKEND_INTERNAL_TOKEN:
        return jsonify({'ok': False, 'message': 'Invalid token'}), 401

    data = request.json or {}
    ae_session_id = data.get('ae_session_id', '').strip()
    centre_id_numeric = data.get('centre_id')  # From CB (numeric ID)
    batch_id = data.get('batch_id')
    display_name = data.get('display_name', '').strip()

    if not ae_session_id or centre_id_numeric is None:
        return jsonify({'ok': False, 'message': 'Missing ae_session_id or centre_id'}), 400

    try:
        # Resolve the centre CB is talking about.
        #
        # This used to be `Centre.query.filter_by(id=centre_id_numeric)`, but Centre's primary key
        # is `centre_id` (the slug, e.g. "centre_a_Base") and the model has no `id` column at
        # all — so every call raised `Entity namespace for "centres" has no property "id"` and
        # returned 500. CB's outbox retried forever (job 1389 today, 1283 on 2026-08-13), and any
        # calling session created in CB for a session AE did not already have never appeared in
        # the registerer's Mark Attendance dropdown.
        #
        # CB now sends `centre_code`. The numeric fallback stays because jobs enqueued by an older
        # build are still queued with only `centre_id`.
        centre_code = (data.get('centre_code') or '').strip()
        if not centre_code and isinstance(centre_id_numeric, str) and not centre_id_numeric.isdigit():
            centre_code = centre_id_numeric.strip()  # already a slug
        if centre_code:
            centre = Centre.query.filter_by(centre_id=centre_code).first()
        else:
            # Legacy payload: only CB's integer PK. Ask CB which slug it maps to.
            centre = None
            resolved = _get_ae_centre_code(centre_id_numeric)
            if resolved:
                centre = Centre.query.filter_by(centre_id=resolved).first()
        if not centre:
            write_log(f"create_calling_session: centre {centre_code or centre_id_numeric} not found in AE")
            return jsonify({'ok': False, 'message': f'Centre {centre_code or centre_id_numeric} not found'}), 400
        centre_code = centre.centre_id

        # Check if session already exists
        existing = SessionMapping.query.filter_by(session_id=ae_session_id, centre_id=centre_code).first()
        if existing:
            # Update existing session with calling metadata.
            #
            # display_name is UPGRADE-ONLY, and specifically must never be downgraded to the
            # generated session id. This was:
            #
            #     existing.display_name = display_name or existing.display_name
            #
            # which overwrote whatever the registerer typed. When a session is created from the
            # app, AE generates the id ("YYYY-MM-DD <CENTRE> <BATCH>") and keeps the typed text as
            # display_name; CB then reverse-synced with display_name = that same id, and this line
            # replaced "testing01" with "2026-08-17 CTRB CELIBACY". Two live sessions were renamed
            # this way before it was found on 2026-08-17, and it is the reason creating a calling
            # session from the app appeared broken while doing it from iHost worked.
            #
            # CB no longer sends the id (it sends the mirrored display name). This guard stays
            # because jobs enqueued by the older build can still be sitting in the outbox carrying
            # the id, and because a rename this destructive should not depend on the sender being
            # correct. Refusing `incoming == ae_session_id` is safe for the CB-first path too:
            # there the session does not exist yet, so it is created below, not updated here.
            incoming = (display_name or '').strip()
            if not existing.display_name:
                existing.display_name = incoming or ae_session_id
            elif incoming and incoming != ae_session_id:
                existing.display_name = incoming
            # Fill the two fields that were never set here. Both are upgrade-only: a value the
            # session already holds always wins, so this can correct a gap but never overwrite a
            # deliberate answer. Without it a session that AE created first keeps a NULL date
            # forever and stays outside resolve_session_date()'s protection.
            if not existing.date:
                derived = _date_prefix_of(ae_session_id)
                if derived:
                    existing.date = derived
            if not existing.category and existing.batch_id:
                _b = Batch.query.filter_by(id=existing.batch_id, centre_id=centre_code).first()
                if _b and _b.category:
                    existing.category = _b.category
            db.session.commit()
            write_log(f"create_calling_session: updated existing session {ae_session_id}")
        else:
            # If batch_id provided, verify it exists; otherwise find or create a default batch for this centre
            resolved_batch_id = batch_id
            if batch_id is not None:
                batch_exists = Batch.query.filter_by(id=batch_id, centre_id=centre_code).first()
                if batch_exists:
                    resolved_batch_id = batch_id
                else:
                    write_log(f"create_calling_session: batch {batch_id} not found in AE, using default batch")
                    # Find or create a default batch for calling sessions
                    default_batch = Batch.query.filter_by(centre_id=centre_code).first()
                    resolved_batch_id = default_batch.id if default_batch else None
                    if not resolved_batch_id:
                        # Create a default batch if none exists
                        default_batch = Batch(centre_id=centre_code, name=f"Calling Sessions - {centre_code}", status='active')
                        db.session.add(default_batch)
                        db.session.flush()  # Get the ID without committing
                        resolved_batch_id = default_batch.id
                        write_log(f"create_calling_session: created default batch {resolved_batch_id} for calling sessions")

            # The session's category is its BATCH's category, never a constant.
            #
            # This was hardcoded to 'youth'. It has not bitten yet only because no congregation
            # calling session has been created through this path — the moment one is, it would be
            # filed as youth while its batch says congregation, and since every screen filters on
            # `category == selectedCategory` the whole session would be invisible in the track it
            # belongs to and would appear in the one it does not. The batch already knows the
            # answer and is the source of truth everywhere else (see create_session, which reads
            # `batch.category`), so read it here too.
            resolved_category = 'youth'
            if resolved_batch_id:
                _b = Batch.query.filter_by(id=resolved_batch_id, centre_id=centre_code).first()
                if _b and _b.category:
                    resolved_category = _b.category

            # A session with no date is unprotected by resolve_session_date(), which is the guard
            # that stops a mark being filed under the day the operator's screen happened to show
            # rather than the day the class was actually for. That guard returns "" for a dateless
            # session and the caller falls through to the client's header date — which is exactly
            # the failure that split '2026-08-15 CTRA BG' across two dates on 2026-08-16.
            #
            # CB does not send a date, but the generated session id begins with one
            # ("YYYY-MM-DD <CENTRE> <BATCH>"), so derive it. Sessions created under the older
            # scheme have a free-text id and yield nothing — they stay NULL, exactly as before.
            resolved_date = sanitize_date_input(data.get('date')) or _date_prefix_of(ae_session_id)

            session = SessionMapping(
                session_id=ae_session_id,
                centre_id=centre_code,  # Use centre_code (string), not numeric centre_id
                batch_id=resolved_batch_id,
                display_name=display_name or ae_session_id,
                category=resolved_category,
                date=resolved_date or None,
                session_type='planned',  # Calling sessions are "planned" type
                status='active'
            )
            db.session.add(session)
            db.session.commit()
            write_log(f"create_calling_session: created new session {ae_session_id} for calling campaign (batch_id={resolved_batch_id})")

        return jsonify({'ok': True})
    except Exception as e:
        write_log(f"ERROR create_calling_session: {e}")
        return jsonify({'ok': False, 'message': str(e)}), 500

@app.route('/api/internal/delete-calling-session', methods=['POST'])
def delete_calling_session():
    """Called by Calling Backend when a calling session (SessionCampaign) is deleted.
    Removes the corresponding session from AttendEase.
    Auth: x-internal-token (from CB outbox worker).
    """
    token = request.headers.get('x-internal-token', '')
    if token != CALLING_BACKEND_INTERNAL_TOKEN:
        return jsonify({'ok': False, 'message': 'Invalid token'}), 401

    data = request.json or {}
    ae_session_id = data.get('ae_session_id', '').strip()
    centre_id = data.get('centre_id')

    if not ae_session_id or centre_id is None:
        return jsonify({'ok': False, 'message': 'Missing ae_session_id or centre_id'}), 400

    try:
        session = SessionMapping.query.filter_by(session_id=ae_session_id, centre_id=centre_id).first()
        if session:
            db.session.delete(session)
            db.session.commit()
            write_log(f"delete_calling_session: deleted session {ae_session_id}")
        else:
            write_log(f"delete_calling_session: session {ae_session_id} not found (already deleted)")

        return jsonify({'ok': True})
    except Exception as e:
        write_log(f"ERROR delete_calling_session: {e}")
        return jsonify({'ok': False, 'message': str(e)}), 500

@app.route('/api/batch_members', methods=['GET'])
def get_batch_members():
    """Fetch active batch members from Calling Backend for batch attendance screen.
    Falls back to an empty list if CB is unreachable (non-fatal).
    Query params: centre_id, category, batch_id
    """
    centre_id = request.args.get('centre_id') or get_selected_centre_id()
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403
    category = request.args.get('category', 'youth')
    batch_id = request.args.get('batch_id', '')
    try:
        import requests as _requests
        params = {'centre_id': _get_cb_centre_id(centre_id), 'category': category}
        if batch_id:
            params['batch_id'] = batch_id
        resp = _requests.get(
            f"{CALLING_BACKEND_BASE_URL}/internal/batch-members",
            params=params,
            headers={'x-internal-token': CALLING_BACKEND_INTERNAL_TOKEN},
            timeout=2.0,
        )
        if resp.ok:
            return jsonify(resp.json())
    except Exception as _e:
        write_log(f"fetch_batch_members from CB failed (non-fatal): {_e}")
    return jsonify({'members': []})

# --- Batch API Endpoints ---

@app.route('/api/batches', methods=['GET'])
def get_batches():
    selected_centre_id = request.args.get('centre_id') or get_selected_centre_id()
    if not centre_id_authorized(selected_centre_id):
        return jsonify({"error": "Forbidden"}), 403
    # Youth/Congregation: when a category is supplied, only that track's batches
    # are returned (drives the registerer toggle + host selector). No param =
    # legacy behaviour (all batches).
    category = (request.args.get('category') or '').strip().lower()
    q = Batch.query.filter_by(centre_id=selected_centre_id)
    if category in ('youth', 'congregation'):
        q = q.filter_by(category=category)
    # Newest batch first, default batch pinned above everything.
    #
    # Was `Batch.name.asc()` — alphabetical, so a batch created today landed wherever its name
    # happened to sort and was rarely near the top of the registerer's picker.
    #
    # Ordering on `id` rather than the `created_at` column that also exists. `id` is the
    # autoincrement primary key, so it is insertion order by construction, monotonic, and never
    # null — whereas created_at carries a Python-side default that a future import could leave
    # unset. Checked on 2026-08-17: all 9 batches have created_at populated and id-desc gives the
    # identical order, so this is the same answer from the more robust column, not a compromise.
    # The is_default pin is deliberate and kept: the default batch is where walk-ins are filed and
    # registerers reach for it constantly.
    batches = q.order_by(Batch.is_default.desc(), Batch.id.desc()).all()
    return jsonify([b.to_dict() for b in batches])

@app.route('/api/batches', methods=['POST'])
def create_batch():
    data = request.json or {}
    name = (data.get('name') or '').strip()
    if not name:
        return jsonify({"error": "Name required"}), 400

    selected_centre_id = data.get('centre_id') or get_selected_centre_id()
    if not centre_id_authorized(selected_centre_id):
        return jsonify({"error": "Forbidden"}), 403
    # Youth/Congregation: the batch's category is fixed at creation and is the
    # source of truth for everything below it. Congregation is gated on the centre flag.
    category = str(data.get('category') or 'youth').strip().lower()
    if category not in ('youth', 'congregation'):
        return jsonify({"error": "Invalid category"}), 400
    if category == 'congregation':
        centre_obj = Centre.query.filter_by(centre_id=selected_centre_id).first()
        if not centre_obj or not centre_obj.congregation_enabled:
            return jsonify({"error": "Congregation is not enabled for this centre"}), 400
    normalized_name = " ".join(name.split()).casefold()
    for existing in Batch.query.filter_by(centre_id=selected_centre_id).all():
        existing_name = " ".join((existing.name or "").split()).casefold()
        if existing_name == normalized_name:
            return jsonify({"error": "Batch already exists"}), 400

    # THE MOMENT THIS MATTERS, and it must happen BEFORE the new batch exists.
    #
    # If this is the centre's SECOND class, then the instant it is committed there is no longer
    # any way to tell which class the centre's unlabelled people belonged to — the only evidence
    # was "this centre only runs one". So label them now, while that is still true. Running this
    # after the commit would be a no-op precisely when it is needed, which is the trap this
    # ordering exists to avoid.
    try:
        backfill_roster_categories(centre_id=selected_centre_id, actor="system:pre-batch-create")
        backfill_roster_categories_from_batch(centre_id=selected_centre_id, actor="system:pre-batch-create")
    except Exception as exc:
        print(f"[roster-category] pre-create pass skipped: {exc}")

    batch = Batch(name=name, centre_id=selected_centre_id, category=category)
    db.session.add(batch)
    db.session.commit()
    enqueue_storage_sync_job(
        "ensure_batch_folder",
        {
            "centre_id": selected_centre_id,
            "batch_id": batch.id,
            "batch_name": batch.name,
            "status": "active",
        }
    )
    _queue_batch_session_refreshes(
        selected_centre_id,
        batch.id,
        batch_name=batch.name,
        status="active"
    )
    regenerate_cache_files(selected_centre_id)
    _fire_webhook('batch.created', 'batch', batch.id, {
        'id': batch.id, 'name': batch.name, 'centre_id': selected_centre_id,
        'category': batch.category or 'youth',
        'is_default': bool(batch.is_default),
        'status': batch.status or 'active',
    })
    return jsonify(batch.to_dict())

@app.route('/api/batches/<int:batch_id>/rename', methods=['POST'])
def rename_batch(batch_id):
    if not is_host_device() and session.get('role') != 'host':
        return jsonify({'ok': False, 'message': 'Unauthorized'}), 403

    data = request.json or {}
    new_name = (data.get('name') or '').strip()
    if not new_name:
        return jsonify({'ok': False, 'message': 'name is required'}), 400

    selected_centre_id = get_selected_centre_id()
    batch = Batch.query.filter_by(id=batch_id, centre_id=selected_centre_id).first()
    if not batch:
        return jsonify({'ok': False, 'message': 'Batch not found for selected centre'}), 404

    normalized_new_name = " ".join(new_name.split()).casefold()
    normalized_old_name = " ".join((batch.name or "").split()).casefold()
    if normalized_new_name == normalized_old_name:
        return jsonify({'ok': True, 'batch': batch.to_dict(), 'message': 'Batch name unchanged'})

    for existing in Batch.query.filter(Batch.id != batch_id).all():
        existing_name = " ".join((existing.name or "").split()).casefold()
        if existing_name == normalized_new_name:
            return jsonify({'ok': False, 'message': 'Batch name already exists'}), 409

    old_name = batch.name
    batch.name = new_name

    Attendance.query.filter(
        Attendance.centre_id == selected_centre_id,
        Attendance.batch_name == old_name
    ).update({Attendance.batch_name: new_name}, synchronize_session=False)

    if get_active_session().get('batch_id') == batch_id:
        get_active_session()['batch_name'] = new_name

    try:
        db.session.commit()
        enqueue_storage_sync_job(
            "rename_batch_folder",
            {
                "centre_id": selected_centre_id,
                "batch_id": batch_id,
                "old_batch_name": old_name,
                "batch_name": new_name,
                "status": "active",
            }
        )
        linked_sessions = SessionMapping.query.filter_by(
            centre_id=selected_centre_id,
            batch_id=batch_id
        ).all()
        _queue_batch_session_refreshes(
            selected_centre_id,
            batch_id,
            batch_name=new_name,
            session_ids=[m.session_id for m in linked_sessions if m.session_id],
            status="active"
        )
        regenerate_cache_files(selected_centre_id)
        _fire_webhook('batch.renamed', 'batch', batch_id, {
            'id': batch_id, 'centre_id': selected_centre_id,
            'old_name': old_name, 'new_name': new_name,
        })
        return jsonify({
            'ok': True,
            'batch': batch.to_dict(),
            'old_name': old_name,
            'new_name': new_name
        })
    except Exception as e:
        db.session.rollback()
        return jsonify({'ok': False, 'message': str(e)}), 500

@app.route('/api/sessions_by_batch/<int:batch_id>', methods=['GET'])
def get_sessions_by_batch(batch_id):
    selected_centre_id = get_selected_centre_id()
    batch = Batch.query.filter_by(id=batch_id, centre_id=selected_centre_id).first()
    if not batch:
        return jsonify([])
    mappings = SessionMapping.query.filter_by(batch_id=batch_id, centre_id=selected_centre_id).all()
    return jsonify([m.session_id for m in mappings])


@app.route('/api/sessions_grouped_by_batch/<int:batch_id>', methods=['GET'])
def get_sessions_grouped_by_batch(batch_id):
    """Sessions for a batch, split into Calling (session_type='planned') and Direct
    (everything else), each limited to the 4 most-recent. Used by the registerer session
    picker so registerers can tell a calling session apart from a direct (attendance-only) one.
    """
    selected_centre_id = get_selected_centre_id()
    batch = Batch.query.filter_by(id=batch_id, centre_id=selected_centre_id).first()
    if not batch:
        return jsonify({"calling": [], "direct": []})
    mappings = SessionMapping.query.filter_by(batch_id=batch_id, centre_id=selected_centre_id).all()

    def _key(m):
        # Sort most-recent first: prefer scheduled_at (planned), then date, then creation time.
        return (m.scheduled_at or datetime.min, m.date or "", m.created_at or datetime.min)

    def _item(m):
        return {"session_id": m.session_id, "display_name": m.display_name or m.session_id, "date": m.date}

    calling = sorted([m for m in mappings if (m.session_type or 'ad_hoc') == 'planned'], key=_key, reverse=True)
    direct  = sorted([m for m in mappings if (m.session_type or 'ad_hoc') != 'planned'], key=_key, reverse=True)
    return jsonify({
        "calling": [_item(m) for m in calling[:4]],
        "direct":  [_item(m) for m in direct[:4]],
    })

# --- Batch Membership UI (multi-select, calls Calling Backend) ---

@app.route('/batch_membership', methods=['GET'])
def batch_membership_ui():
    """Minimal UI: show and edit a mobile's batch memberships via Calling Backend.
    Query params: mobile, centre_id, category
    """
    mobile = request.args.get('mobile', '').strip()
    centre_id = request.args.get('centre_id') or get_selected_centre_id()
    if not centre_id_authorized(centre_id):
        return "<h3>Forbidden</h3>", 403
    category = request.args.get('category', 'youth')

    if not mobile:
        return "<h3>mobile parameter required</h3>", 400

    # Fetch current memberships from CB
    current = []
    try:
        import requests as _requests
        r = _requests.get(
            f"{CALLING_BACKEND_BASE_URL}/internal/batch-members/by-mobile",
            params={'centre_id': _get_cb_centre_id(centre_id), 'category': category, 'mobile': mobile},
            headers={'x-internal-token': CALLING_BACKEND_INTERNAL_TOKEN},
            timeout=2.0,
        )
        if r.ok:
            current = r.json().get('members', [])
    except Exception as _e:
        write_log(f"batch_membership_ui: CB fetch failed: {_e}")

    # Fetch available batches from CB
    available = []
    try:
        import requests as _requests
        cb_centre = _get_cb_centre_id(centre_id)
        r = _requests.get(
            f"{CALLING_BACKEND_BASE_URL}/internal/batch-members",
            params={'centre_id': cb_centre, 'category': category},
            headers={'x-internal-token': CALLING_BACKEND_INTERNAL_TOKEN},
            timeout=2.0,
        )
        # Use local batches as fallback
    except Exception:
        pass

    local_batches = Batch.query.filter_by(centre_id=centre_id, category=category).all()
    current_ids = {m.get('batchId') for m in current}

    rows = ''.join(
        f'<tr><td><input type="checkbox" name="batch_ids" value="{b.id}"'
        f'{" checked" if b.id in current_ids else ""}>'
        f'</td><td>{b.name}</td>'
        f'<td>{"Default" if b.is_default else ""}</td></tr>'
        for b in local_batches
    )

    html = f"""<!DOCTYPE html>
<html><head><title>Batch Membership — {mobile}</title>
<style>body{{font-family:sans-serif;max-width:600px;margin:2rem auto;}}
table{{border-collapse:collapse;width:100%;}}td,th{{border:1px solid #ccc;padding:8px;}}
.btn{{padding:8px 16px;background:#2563eb;color:#fff;border:none;cursor:pointer;border-radius:4px;}}
</style></head><body>
<h2>Batch Membership</h2>
<p>Mobile: <strong>{mobile}</strong> | Centre: {centre_id} | Category: {category}</p>
<form method="POST" action="/batch_membership/save">
  <input type="hidden" name="mobile" value="{mobile}">
  <input type="hidden" name="centre_id" value="{centre_id}">
  <input type="hidden" name="category" value="{category}">
  <table><tr><th>Select</th><th>Batch</th><th>Default?</th></tr>
  {rows}
  </table>
  <br><button class="btn" type="submit">Save memberships</button>
  &nbsp;<a href="javascript:history.back()">Cancel</a>
</form>
</body></html>"""
    return html


def _get_cb_centre_id(ae_centre_id):
    """Map AttendEase centre_id slug to CB centre PK via internal resolve endpoint."""
    try:
        import requests as _requests
        r = _requests.get(
            f"{CALLING_BACKEND_BASE_URL}/internal/resolve-centre",
            params={'code': ae_centre_id},
            headers={'x-internal-token': CALLING_BACKEND_INTERNAL_TOKEN},
            timeout=2.0,
        )
        if r.ok:
            return r.json().get('centre', {}).get('id', ae_centre_id)
    except Exception:
        pass
    return ae_centre_id  # fallback


def _get_ae_centre_code(cb_centre_id):
    """Map a Calling Backend numeric centre PK back to this system's centre_id slug.

    The inverse of _get_cb_centre_id. Needed only for outbox jobs enqueued by a CB build that
    sent the integer PK alone; current CB builds put `centre_code` straight in the payload.
    Returns None when it cannot be resolved, so the caller answers 400 (a real, actionable
    error) instead of raising and turning it into a retried-forever 500.
    """
    try:
        import requests as _requests
        r = _requests.get(
            f"{CALLING_BACKEND_BASE_URL}/internal/resolve-centre",
            params={'id': cb_centre_id},
            headers={'x-internal-token': CALLING_BACKEND_INTERNAL_TOKEN},
            timeout=2.0,
        )
        if r.ok:
            return r.json().get('centre', {}).get('code')
    except Exception as _e:
        write_log(f"_get_ae_centre_code({cb_centre_id}) failed: {_e}")
    return None


@app.route('/batch_membership/save', methods=['POST'])
def batch_membership_save():
    """Save batch membership selection by calling CB manual-replace endpoint."""
    mobile = request.form.get('mobile', '').strip()
    centre_id = request.form.get('centre_id', '')
    category = request.form.get('category', 'youth')
    batch_ids = [int(v) for v in request.form.getlist('batch_ids') if v.isdigit()]

    if not centre_id_authorized(centre_id):
        return "<h3>Forbidden</h3>", 403
    if not mobile or not batch_ids:
        return "<h3>mobile and at least one batch required</h3>", 400

    cb_centre = _get_cb_centre_id(centre_id)

    try:
        import requests as _requests
        r = _requests.post(
            f"{CALLING_BACKEND_BASE_URL}/internal/batch-members/manual-replace",
            json={
                'centre_id': cb_centre,
                'category': category,
                'mobile': mobile,
                'batch_ids': batch_ids,
                'changed_by': f"attendease:{session.get('username', 'unknown')}",
            },
            headers={'x-internal-token': CALLING_BACKEND_INTERNAL_TOKEN, 'Content-Type': 'application/json'},
            timeout=3.0,
        )
        if not r.ok:
            return f"<h3>Error from Calling Backend: {r.text}</h3>", 502
    except Exception as _e:
        return f"<h3>Calling Backend unreachable: {_e}</h3>", 503

    return f"""<html><body style="font-family:sans-serif;max-width:600px;margin:2rem auto">
<h3>✓ Memberships saved for {mobile}</h3>
<a href="/batch_membership?mobile={mobile}&centre_id={centre_id}&category={category}">← Back</a>
</body></html>"""

# --- Editor API Endpoints ---

@app.route('/api/get_sheet_content/<sheet_name>', methods=['GET'])
def get_sheet_content(sheet_name):
    """Fetch all rows from a specific sheet for editing."""

    ensure_live_workbook_readable()
    if not os.path.exists(LIVE_FILE):
        return jsonify([])
    if not _sheet_belongs_to_caller(sheet_name):
        return jsonify({"error": "Forbidden"}), 403
    if not _sheet_category_allowed(sheet_name):
        return jsonify({"error": "Forbidden"}), 403

    try:
        wb = load_workbook(LIVE_FILE, data_only=True)
        if sheet_name not in wb.sheetnames:
            wb.close()
            return jsonify({"error": "Sheet not found"}), 404

        ws = wb[sheet_name]
        rows = list(ws.iter_rows(values_only=True))
        if not rows:
            wb.close()
            return jsonify([])

        headers = rows[0]
        data = []
        for i, row in enumerate(rows[1:], start=2):
            row_dict = {"_row_idx": i}
            for idx, h in enumerate(headers):
                if h:
                    row_dict[str(h)] = row[idx] if idx < len(row) else ""
            data.append(row_dict)

        wb.close()
        return jsonify({"headers": [str(h) for h in headers if h], "data": data})
    except Exception as e:
        print(f"[ERROR] get_sheet_content: {str(e)}")
        return jsonify({"error": str(e)}), 500

@app.route('/api/delete_sheet/<sheet_name>', methods=['DELETE'])
def delete_sheet(sheet_name):
    if not os.path.exists(LIVE_FILE):
        return jsonify({"error": "Master file not found"}), 404
    if not _sheet_belongs_to_caller(sheet_name):
        return jsonify({"error": "Forbidden"}), 403
    if not _sheet_category_allowed(sheet_name):
        return jsonify({"error": "Forbidden"}), 403
    try:
        with EXCEL_WRITE_LOCK:
            if os.name == "nt":
                # Use Excel COM on Windows (file may be held open by COM)
                ps_script = r"""
param([string]$FilePath, [string]$SheetName)
# Ownership flags for the try/finally at the bottom. We only ever close a
# workbook THIS script opened and only quit an Excel THIS script created, so a
# workbook a human has open on this machine is never touched.
$OwnsExcel = $false
$OwnsWb = $false
try {
    $excel = [Runtime.InteropServices.Marshal]::GetActiveObject('Excel.Application')
} catch {
    $excel = New-Object -ComObject Excel.Application
    # This script created the instance, so this script must end it. The old
    # behaviour deliberately left it resident for reuse, which is exactly what
    # strands a windowless EXCEL.EXE still holding the workbook open whenever
    # this script dies before its close (most often the caller's 30s timeout).
    # Excel is launched by DCOM, NOT as a child of PowerShell, so killing our
    # process tree does not take it with us. That orphan holds an exclusive
    # handle on the .xlsx and blocks every later write to it, permanently --
    # the confirmed root cause of the recurring attendance data-loss reports.
    # Reuse was a speed optimisation on a background worker; correctness wins.
    # See excel_lock_guard.py for the runtime backstop that cleans up any
    # orphan a hard kill still manages to leave behind.
    $OwnsExcel = $true
}
$excel.Visible = $false
$excel.DisplayAlerts = $false
$wb = $null
foreach ($w in $excel.Workbooks) {
    if ($w.FullName -eq $FilePath) { $wb = $w; break }
}
$opened = $false
if (-not $wb) { $wb = $excel.Workbooks.Open($FilePath); $opened = $true; $OwnsWb = $true }
try {
$ws = $null
foreach ($s in $wb.Sheets) { if ($s.Name -eq $SheetName) { $ws = $s; break } }
if ($ws) { $ws.Delete() }
$wb.Save()
$wb.Close($false)
} finally {
    # Runs on success, on any terminating error, and on an early `return` -- so a
    # workbook this script opened can never be left open inside a surviving,
    # windowless EXCEL.EXE. (A hard process kill still bypasses this; that
    # residual case is what excel_lock_guard.py detects and clears at runtime.)
    if ($OwnsWb -and $wb) { try { $wb.Close($false) } catch {} }
    if ($OwnsExcel -and $excel) {
        try { $excel.Quit() } catch {}
        try { [Runtime.InteropServices.Marshal]::ReleaseComObject($excel) | Out-Null } catch {}
    }
}
"""
                import subprocess, tempfile as _tf
                with _tf.NamedTemporaryFile("w", delete=False, suffix=".ps1", encoding="utf-8") as ps:
                    ps.write(ps_script)
                    ps_path = ps.name
                result = _run_com_helper(
                    ["powershell", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                     "-File", ps_path, "-FilePath", LIVE_FILE, "-SheetName", sheet_name],
                    capture_output=True, text=True, timeout=30
                )
                os.unlink(ps_path)
                if result.returncode != 0:
                    return jsonify({"error": result.stderr.strip() or "COM delete failed"}), 500
            else:
                wb = load_workbook(LIVE_FILE)
                if sheet_name not in wb.sheetnames:
                    wb.close()
                    return jsonify({"error": "Sheet not found"}), 404
                if len(wb.sheetnames) <= 1:
                    wb.close()
                    return jsonify({"error": "Cannot delete the only sheet"}), 400
                del wb[sheet_name]
                save_workbook_safely(wb, LIVE_FILE, reason="delete_sheet_endpoint")
                wb.close()
        write_log(f"Sheet deleted: {sheet_name}")
        return jsonify({"ok": True})
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/api/update_row', methods=['POST'])
def update_row():
    """Update a specific row in a sheet."""
        
    data = request.json
    sheet_name = data.get('sheet_name')
    row_idx = data.get('row_idx')
    updated_data = data.get('data', {})
    
    if not sheet_name or not row_idx:
        return jsonify({"error": "Missing parameters"}), 400
        
    try:
        # Determine Mobile before update if we need to sync to DB
        old_mobile = None
        try:
            from openpyxl import load_workbook
            wb_probe = load_workbook(LIVE_FILE, read_only=True, data_only=True)
            if sheet_name in wb_probe.sheetnames:
                ws_probe = wb_probe[sheet_name]
                old_mobile = str(ws_probe.cell(row=int(row_idx), column=2).value).strip() if ws_probe.max_column >= 2 else None
            wb_probe.close()
        except Exception:
            old_mobile = None

        update_row_in_live_workbook(sheet_name, row_idx, updated_data, old_mobile=old_mobile)
        
        # --- DB SYNC ---
        # If we updated Name/Address/etc., update the User table
        if old_mobile:
            user = User.query.filter_by(mobile=old_mobile).first()
            if user:
                if "Name" in updated_data: user.name = updated_data["Name"]
                if "Address" in updated_data: user.address = updated_data["Address"]
                if "Occupation" in updated_data: user.occupation = updated_data["Occupation"]
                if "Rounds" in updated_data: user.rounds = updated_data["Rounds"]
                if "Remark" in updated_data: user.remark = updated_data["Remark"]
                if "Cheating" in updated_data: user.cheating = updated_data["Cheating"]

                # --- Attendance record sync ---
                # Also update the Attendance record for this session if it exists
                # session_id matches sheet_name in the clean workflow
                att = Attendance.query.filter(
                    Attendance.mobile == old_mobile,
                    Attendance.session_id == sheet_name,
                    Attendance.centre_id == get_selected_centre_id()
                ).first()
                if att:
                    if "Rounds" in updated_data: att.rounds = updated_data["Rounds"]
                    if "Remark" in updated_data: att.remark = updated_data["Remark"]
                    if "Cheating" in updated_data: att.cheating = updated_data["Cheating"]
                
                db.session.commit()
                session_ctx = resolve_storage_context_for_session(sheet_name)
                if session_ctx.get("centre_id") and session_ctx.get("batch_id") is not None:
                    enqueue_excel_output_refresh(
                        "refresh_attendance_mirrors_for_attendance_change",
                        {
                            "centre_id": session_ctx.get("centre_id"),
                            "batch_id": session_ctx.get("batch_id"),
                            "batch_name": session_ctx.get("batch_name"),
                            "session_id": sheet_name,
                            "session_name": sheet_name,
                            "refresh_student_master": True,
                            "status": "active",
                        }
                    )

        # --- GOOGLE SHEETS SYNC ---
        if old_mobile:
            from sheets_handler import update_row_in_google_sheet
            # Edit the sheet belonging to the centre that OWNS this session, not
            # whichever centre the host happens to have selected — the host can
            # open another centre's session from the sheet list.
            edit_ctx = resolve_storage_context_for_session(sheet_name) or {}
            edit_centre_id = edit_ctx.get("centre_id") or get_selected_centre_id()
            edit_sheet_id = get_google_sheet_id_for_centre(edit_centre_id)
            if edit_sheet_id:
                # Update specific session sheet
                update_row_in_google_sheet(old_mobile, updated_data, sheet_name=sheet_name,
                                           spreadsheet_id=edit_sheet_id)
                # Update Master sheet
                if sheet_name != "MASTER_ALL_SESSIONS":
                    update_row_in_google_sheet(old_mobile, updated_data,
                                               sheet_name="MASTER_ALL_SESSIONS",
                                               spreadsheet_id=edit_sheet_id)
            else:
                write_log(
                    f"Google Sheets row edit skipped: centre '{edit_centre_id}' "
                    f"has no Google Sheet linked."
                )

        # --- SYNC TO MASTER EXCEL SHEET ---
        # If we edited a session sheet, also apply changes to MASTER_ALL_SESSIONS
        if sheet_name != "MASTER_ALL_SESSIONS" and old_mobile:
            try:
                wb_master = load_workbook(LIVE_FILE)
                if "MASTER_ALL_SESSIONS" in wb_master.sheetnames:
                    ws_master = wb_master["MASTER_ALL_SESSIONS"]
                    m_headers = [str(cell.value) for cell in ws_master[1]]
                    
                    # Find the row in Master with this mobile
                    target_row = None
                    for r in range(2, ws_master.max_row + 1):
                        m_val = str(ws_master.cell(row=r, column=2).value).strip()
                        if m_val == old_mobile:
                            target_row = r
                            break
                    
                    if target_row:
                        for col_name, new_val in updated_data.items():
                            if col_name in m_headers:
                                m_col_idx = m_headers.index(col_name) + 1
                                ws_master.cell(row=target_row, column=m_col_idx, value=new_val)
                        save_workbook_safely(wb_master, LIVE_FILE, reason="update_row_endpoint")
                wb_master.close()
            except Exception as e:
                write_log(f"Error syncing to Master Excel: {str(e)}")

        write_log(f"Admin updated row {row_idx} in sheet {sheet_name} (Mobile: {old_mobile})")
        return jsonify({"success": True})
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/api/batch_delete', methods=['POST'])
def batch_delete():
    if not is_host_device() and session.get('role') != 'host':
        return jsonify({"error": "Unauthorized"}), 403

    data = request.json
    session_id = data.get('session_id')
    date_str = data.get('date')
    batch_name = data.get('batch_name')

    if not session_id or not date_str or not batch_name:
        return jsonify({"success": False, "message": "Missing filters for batch delete"}), 400

    try:
        selected_centre_id = get_selected_centre_id()
        session_ctx = resolve_storage_context_for_session(session_id)
        batch_ctx = None
        if not session_ctx.get("batch_id") and batch_name:
            batch_obj = Batch.query.filter_by(name=batch_name, centre_id=selected_centre_id).first()
            if batch_obj:
                batch_ctx = {
                    "centre_id": selected_centre_id,
                    "batch_id": batch_obj.id,
                    "batch_name": batch_obj.name,
                }
        # 1. Delete matching records from SQLite
        Attendance.query.filter(
            Attendance.session_id == session_id,
            Attendance.date == date_str,
            Attendance.batch_name == batch_name,
            Attendance.centre_id == selected_centre_id
        ).delete(synchronize_session=False)
        db.session.commit()

        # 2. Delete matching rows from Excel
        if os.path.exists(LIVE_FILE):
            try:
                delete_attendance_rows_from_live_workbook(session_id, date_str, batch_name)
            except Exception as e:
                print(f"Error updating Excel during batch delete: {e}")
                # We still return success because DB was updated

        ctx = session_ctx if session_ctx.get("batch_id") is not None else (batch_ctx or {})
        if ctx.get("centre_id") and ctx.get("batch_id") is not None:
            enqueue_excel_output_refresh(
                "refresh_attendance_mirrors_for_attendance_change",
                {
                    "centre_id": ctx.get("centre_id"),
                    "batch_id": ctx.get("batch_id"),
                    "batch_name": ctx.get("batch_name") or batch_name,
                    "session_id": session_id,
                    "session_name": session_id,
                    "status": "active",
                }
            )

        return jsonify({"success": True, "message": f"Batch '{batch_name}' for {date_str} deleted successfully."})
    except Exception as e:
        db.session.rollback()
        return jsonify({"success": False, "message": str(e)}), 500

@app.route('/api/batches/<int:batch_id>/delete', methods=['POST'])
def delete_batch(batch_id):
    if not is_host_device() and session.get('role') != 'host':
        return jsonify({'ok': False, 'message': 'Unauthorized'}), 403

    selected_centre_id = get_selected_centre_id()
    batch = Batch.query.filter_by(id=batch_id, centre_id=selected_centre_id).first()
    if not batch:
        return jsonify({'ok': False, 'message': 'Batch not found for selected centre'}), 404

    backup_path = None
    linked_mappings = []
    session_ids = []
    batch_name = batch.name

    try:
        linked_mappings = SessionMapping.query.filter_by(batch_id=batch_id, centre_id=selected_centre_id).all()
        session_ids = [m.session_id for m in linked_mappings if m.session_id]

        if os.path.exists(LIVE_FILE):
            temp_dir = os.path.dirname(LIVE_FILE) or None
            backup_fd, backup_path = tempfile.mkstemp(prefix="delete_batch_backup_", suffix=".xlsx", dir=temp_dir)
            os.close(backup_fd)
            shutil.copy2(LIVE_FILE, backup_path)

            for sid in session_ids:
                delete_session_sheet_from_live_workbook(sid)

        Attendance.query.filter(
            Attendance.centre_id == selected_centre_id,
            Attendance.batch_name == batch.name
        ).delete(synchronize_session=False)
        SessionMapping.query.filter_by(batch_id=batch_id, centre_id=selected_centre_id).delete(synchronize_session=False)
        db.session.delete(batch)

        db.session.commit()

        enqueue_storage_sync_job(
            "ensure_batch_folder",
            {
                "centre_id": selected_centre_id,
                "batch_id": batch_id,
                "batch_name": batch_name,
                "status": "inactive",
            }
        )

        _queue_batch_session_refreshes(
            selected_centre_id,
            batch_id,
            batch_name=batch_name,
            session_ids=session_ids,
            status="deleted"
        )

        for sid in session_ids:
            enqueue_storage_sync_job(
                "mark_session_deleted",
                {
                    "centre_id": selected_centre_id,
                    "batch_id": batch_id,
                    "batch_name": batch_name,
                    "session_id": sid,
                    "session_name": sid,
                    "deleted_at": safe_timestamp(),
                }
            )

        if get_active_session(selected_centre_id).get('batch_id') == batch_id:
            preserved_centre_id = get_active_session(selected_centre_id).get('centre_id') or selected_centre_id
            preserved_centre_name = get_active_session(selected_centre_id).get('centre_name') or get_centre_name_by_id(preserved_centre_id)
            set_active_session(preserved_centre_id, {
                "centre_id": preserved_centre_id,
                "centre_name": preserved_centre_name,
                "session_id": None,
                "batch_id": None,
                "batch_name": None,
                "date": None,
                "sheet_name": None,
            })
            session['selected_centre_id'] = preserved_centre_id
            session['selected_centre_name'] = preserved_centre_name
            session.pop('active_session_id', None)
            session.pop('active_session_sheet', None)

        write_log(f"Batch '{batch.name}' (ID: {batch_id}) deleted by host.")
        regenerate_cache_files(selected_centre_id)
        _fire_webhook('batch.deleted', 'batch', batch_id, {
            'id': batch_id, 'name': batch_name, 'centre_id': selected_centre_id,
        })
        return jsonify({
            'ok': True,
            'message': f"Batch '{batch.name}' deleted successfully.",
            'batch_id': batch_id,
            'batch_name': batch.name,
            'deleted_sessions': len(session_ids)
        })
    except Exception as e:
        db.session.rollback()
        try:
            if backup_path and os.path.exists(backup_path):
                os.replace(backup_path, LIVE_FILE)
        except Exception:
            pass
        return jsonify({'ok': False, 'message': str(e)}), 500
    finally:
        for p in (backup_path,):
            try:
                if p and os.path.exists(p):
                    os.remove(p)
            except Exception:
                pass

@app.route('/api/delete_row', methods=['POST'])
def delete_row():
    """Delete a specific row from a sheet."""
    if not is_host_device() and session.get('role') != 'host':
        return jsonify({"error": "Unauthorized"}), 403
        
    data = request.json
    sheet_name = data.get('sheet_name')
    row_idx = data.get('row_idx')
    
    if not sheet_name or not row_idx:
        return jsonify({"error": "Missing parameters"}), 400
        
    try:
        mobile_to_delete = None
        try:
            from openpyxl import load_workbook
            wb_probe = load_workbook(LIVE_FILE, read_only=True, data_only=True)
            if sheet_name in wb_probe.sheetnames:
                ws_probe = wb_probe[sheet_name]
                if ws_probe.max_column >= 2:
                    mobile_to_delete = str(ws_probe.cell(row=int(row_idx), column=2).value).strip()
            wb_probe.close()
        except Exception:
            mobile_to_delete = None

        delete_row_from_live_workbook(sheet_name, row_idx)
        
        # --- DB SYNC ---
        delete_master = data.get('delete_master', False)
        if delete_master:
            write_log(f"Master delete disabled; ignoring delete_master flag for sheet {sheet_name}")
        
        if mobile_to_delete:
            # Normal deletion from a single sheet only; master User records are never deleted here.
            Attendance.query.filter(
                Attendance.mobile == mobile_to_delete,
                Attendance.session_id.like(f"{sheet_name}%"),
                Attendance.centre_id == get_selected_centre_id()
            ).delete(synchronize_session=False)
            db.session.commit()
            session_ctx = resolve_storage_context_for_session(sheet_name)
            if session_ctx.get("centre_id") and session_ctx.get("batch_id") is not None:
                enqueue_excel_output_refresh(
                    "refresh_attendance_mirrors_for_attendance_change",
                    {
                        "centre_id": session_ctx.get("centre_id"),
                        "batch_id": session_ctx.get("batch_id"),
                        "batch_name": session_ctx.get("batch_name"),
                        "session_id": sheet_name,
                        "session_name": sheet_name,
                        "status": "active",
                    }
                )

        write_log(f"Admin deleted row {row_idx} (Mobile: {mobile_to_delete}) from sheet {sheet_name}")
        session_ctx = resolve_storage_context_for_session(sheet_name)
        cid = session_ctx.get("centre_id") or get_selected_centre_id()
        if cid:
            regenerate_cache_files(cid)
        return jsonify({"success": True})
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route('/api/planned_sessions', methods=['GET'])
def planned_sessions():
    """List upcoming planned sessions for a centre, for registerers to select on class day."""
    selected_centre_id = request.args.get('centre_id') or get_selected_centre_id()
    if not centre_id_authorized(selected_centre_id):
        return jsonify({"error": "Forbidden"}), 403
    import datetime as _dt
    now = _dt.datetime.utcnow()
    try:
        mappings = (
            SessionMapping.query
            .filter_by(centre_id=selected_centre_id, session_type='planned', status='active')
            .filter(SessionMapping.scheduled_at > now)
            .order_by(SessionMapping.scheduled_at.asc())
            .all()
        )
        return jsonify([{
            'session_id': m.session_id,
            'display_name': m.display_name or m.session_id,
            'batch_id': m.batch_id,
            'centre_id': m.centre_id,
            'scheduled_at': m.scheduled_at.isoformat() if m.scheduled_at else None,
        } for m in mappings])
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/api/list_sessions', methods=['GET'])
def list_sessions():
    """List all sessions for the selected centre, with display names."""
    selected_centre_id = request.args.get('centre_id') or get_selected_centre_id()
    if not centre_id_authorized(selected_centre_id):
        return jsonify({"error": "Forbidden"}), 403
    # Optional track filter, and `category` on every row so a client can filter locally.
    # This endpoint returned every session at the centre regardless of track. The app's picker
    # happens to filter by batch, which hid the problem, but anything reading this list without
    # a batch in hand saw congregation and youth sessions mixed together.
    category = (request.args.get('category') or '').strip().lower()
    category = category if category in ('youth', 'congregation') else ''
    try:
        mappings = SessionMapping.query.filter_by(centre_id=selected_centre_id).all()
        result = [
            {
                "session_id": m.session_id,
                "display_name": m.display_name or m.session_id,
                "batch_id": m.batch_id,
                "centre_id": m.centre_id,
                # session_type drives the Calling (planned) vs Direct (ad_hoc) split in the
                # registerer session pickers (app + web).
                "session_type": m.session_type or 'ad_hoc',
                "category": (m.category or 'youth'),
                "date": m.date,
            }
            for m in mappings
            if not category or (m.category or 'youth').strip().lower() == category
        ]
        return jsonify(result)
    except Exception as e:
        # Fallback: read sheet names from Excel if DB query fails
        if os.path.exists(LIVE_FILE):
            try:
                from openpyxl import load_workbook
                wb = load_workbook(LIVE_FILE, read_only=True)
                sheets = [s for s in wb.sheetnames if _sheet_belongs_to_caller(s)]
                wb.close()
                return jsonify([{"session_id": s, "display_name": s} for s in sheets])
            except Exception:
                pass
        return jsonify([])

def _ensure_centre_code(centre):
    """Return the centre's short code, assigning and persisting one if it has none.

    Done lazily rather than at centre-creation time so a centre added by any path (UI,
    import, sync) still gets a code the first time it is actually needed. No admin step.
    """
    if centre.short_code:
        return centre.short_code
    base = session_naming.derive_centre_code(centre.name or centre.centre_id)
    taken = {c.short_code for c in Centre.query.filter(Centre.short_code.isnot(None)).all()}
    code, n = base, 1
    while code in taken:
        n += 1
        code = f"{base[:3]}{n}"
    centre.short_code = code
    db.session.commit()
    write_log(f"Assigned centre short_code {code} to {centre.centre_id}")
    return code


def _ensure_batch_code(batch):
    """Return the batch's short code, assigning and persisting one if it has none.

    Unique within the centre, which is all the session id needs — the centre code already
    separates centres.
    """
    if batch.short_code:
        return batch.short_code
    base = session_naming.derive_batch_code(batch.name or f"B{batch.id}")
    taken = {
        b.short_code for b in
        Batch.query.filter(Batch.centre_id == batch.centre_id, Batch.short_code.isnot(None)).all()
    }
    code, n = base, 1
    while code in taken:
        n += 1
        code = f"{base[:10]}{n}"
    batch.short_code = code
    db.session.commit()
    write_log(f"Assigned batch short_code {code} to batch {batch.id} ({batch.name})")
    return code


def _next_session_id(centre, batch, date_str):
    """Build the standardised id, adding a numeric suffix only if that exact id is taken
    by a DIFFERENT session (same centre+batch+date normally means the SAME session)."""
    base = session_naming.build_session_id(
        date_str, _ensure_centre_code(centre), _ensure_batch_code(batch)
    )
    candidate, n = base, 1
    while SessionMapping.query.filter_by(session_id=candidate).first():
        n += 1
        candidate = session_naming.build_session_id(
            date_str, centre.short_code, batch.short_code, suffix=str(n)
        )
    return candidate


@app.route('/api/create_session', methods=['POST'])
def create_session():
    """Create a brand new session sheet in the master Excel file."""
    data = request.json or {}
    session_name = data.get('session_name', '').strip()
    if not session_name:
        return jsonify({"error": "Session name is required"}), 400
    
    selected_centre_id = data.get('centre_id') or get_selected_centre_id()
    if not centre_id_authorized(selected_centre_id):
        return jsonify({"error": "Forbidden"}), 403
    batch_id_raw = data.get('batch_id')
    try:
        batch_id = int(batch_id_raw) if batch_id_raw not in (None, "") else None
    except (TypeError, ValueError):
        return jsonify({"error": "Valid batch_id is required"}), 400

    session_type = data.get('session_type', 'ad_hoc')
    if session_type not in ('ad_hoc', 'planned'):
        session_type = 'ad_hoc'
    scheduled_at_raw = data.get('scheduled_at')
    scheduled_at = None
    if scheduled_at_raw:
        try:
            from dateutil import parser as _dateparser
            scheduled_at = _dateparser.parse(scheduled_at_raw)
        except Exception:
            return jsonify({"error": "Invalid scheduled_at format — use ISO 8601"}), 400

    if not batch_id:
        return jsonify({"error": "Batch selection is required before creating a session"}), 400

    batch = Batch.query.filter_by(id=batch_id, centre_id=selected_centre_id).first()
    if not batch:
        return jsonify({"error": "Batch not found for selected centre"}), 404

    # ------------------------------------------------------------------
    # Identity is GENERATED, never typed.
    #
    # This used to be `sheet_name = sanitize_sheet_name(session_name)` — i.e. whatever the
    # operator typed became the primary key, the Excel tab and the Sheets tab. A typo could
    # not be corrected, it silently created a SECOND session and split the class between
    # them. Live evidence: 'Bz 23rd March Evening' (42 rows), 'BZ  23rd March' (4 rows) and
    # 'Bz 23rd March Evenin' (0 rows) are all the same class; four people are filed in the
    # wrong one. See session_naming.py.
    #
    # Now: id = "YYYY-MM-DD <CENTRE> <BATCH>" (sortable, unique per centre+batch+day) and
    # whatever the operator typed is kept as the display label, where a typo is harmless.
    # `date` is finally populated too — it was NULL on all 45 existing sessions, so the only
    # record of when a class happened was the text of its name.
    # ------------------------------------------------------------------
    session_date = (data.get('date') or '').strip()
    if not session_date and scheduled_at:
        session_date = scheduled_at.strftime('%Y-%m-%d')
    if not session_date:
        session_date = datetime.now().strftime('%Y-%m-%d')
    try:
        datetime.strptime(session_date, '%Y-%m-%d')
    except ValueError:
        return jsonify({"error": "date must be YYYY-MM-DD"}), 400

    centre_row = Centre.query.filter_by(centre_id=selected_centre_id).first()
    if not centre_row:
        return jsonify({"error": "Unknown centre"}), 400

    # Same centre + batch + day is the SAME class. Return the existing session instead of
    # creating a near-duplicate — this is the guard that would have prevented the March
    # split above. `force_new` is the deliberate escape hatch for a genuine second sitting.
    existing = SessionMapping.query.filter_by(
        centre_id=selected_centre_id, batch_id=batch_id, date=session_date
    ).first()
    if existing and not data.get('force_new'):
        return jsonify({
            "session_id": existing.session_id,
            "sheet_name": existing.session_id,
            "display_name": existing.display_name or existing.session_id,
            # `date` was absent here while the created branch returns it — harmless while the
            # date was always today, but the registerer can now pick one, and the client points
            # its attendance-date at this value. Echo the date that was actually matched.
            "date": existing.date or session_date,
            "reused": True,
            "message": f"A session already exists for this batch on {session_date} — using it.",
        })

    sheet_name = _next_session_id(centre_row, batch, session_date)
    full_session_id = session_name  # the human label; free text, never the key

    try:
        ensure_session_sheet_in_live_workbook(sheet_name, ATTENDANCE_EXCEL_HEADERS)

        mapping = SessionMapping.query.filter_by(session_id=sheet_name, centre_id=selected_centre_id).first()
        if not mapping:
            mapping = SessionMapping(
                session_id=sheet_name,
                display_name=full_session_id,  # BUG-2: persist original name
                batch_id=batch_id,
                centre_id=selected_centre_id,
                # Youth/Congregation: session inherits its batch's category (source of truth)
                category=(batch.category or 'youth'),
                session_type=session_type,
                scheduled_at=scheduled_at,
                date=session_date,
            )
            db.session.add(mapping)
        else:
            mapping.batch_id = batch_id
            mapping.category = (batch.category or 'youth')
            if not mapping.date:
                mapping.date = session_date
            # Backfill display_name if missing
            if not mapping.display_name:
                mapping.display_name = full_session_id
            if not getattr(mapping, 'centre_id', None):
                mapping.centre_id = selected_centre_id
        db.session.commit()

        enqueue_storage_sync_job(
            "ensure_session_folder",
            {
                "centre_id": selected_centre_id,
                "batch_id": batch_id,
                "batch_name": batch.name,
                "session_id": sheet_name,
                "session_name": full_session_id,
                "status": "active",
            }
        )
        enqueue_excel_output_refresh(
            "refresh_attendance_mirrors_for_attendance_change",
            {
                "centre_id": selected_centre_id,
                "batch_id": batch_id,
                "batch_name": batch.name,
                "session_id": sheet_name,
                "session_name": full_session_id,
                "status": "active",
            }
        )
                 
        regenerate_cache_files(selected_centre_id)
        _fire_webhook('session.created', 'session', sheet_name, {
            'session_id': sheet_name, 'display_name': full_session_id,
            'batch_id': batch_id, 'centre_id': selected_centre_id,
            'category': batch.category or 'youth',
            'date': session_date,
            'status': 'active',
        })
        return jsonify({
            "session_id": sheet_name,
            "sheet_name": sheet_name,
            "display_name": full_session_id,
            "date": session_date,
            "reused": False,
        })
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/api/sessions/<session_id>/rename', methods=['POST'])
def rename_session(session_id):
    """Host-only API to rename a session's display label.

    Renames SessionMapping.display_name ONLY — the session_id (the sanitized
    sheet name used as the Excel tab name, the Attendance.session_id foreign
    key, and the calling backend's linking key) never changes. This avoids
    touching the live workbook / Excel COM entirely (see the 2026-07-27
    incident in AGENT_MEMORY.md for why that path is fragile), and matches
    the existing BUG-2 design where display_name was added specifically so
    the full original name can be shown/edited without the 31-char sheet
    name truncation ever leaking into the UI.
    """
    if not is_host_device() and session.get('role') != 'host':
        return jsonify({'ok': False, 'message': 'Unauthorized. Only the host can rename sessions.'}), 403

    data = request.json or {}
    new_name = (data.get('name') or '').strip()
    if not new_name:
        return jsonify({'ok': False, 'message': 'name is required'}), 400

    selected_centre_id = get_selected_centre_id()
    mapping = SessionMapping.query.filter_by(session_id=session_id, centre_id=selected_centre_id).first()
    if not mapping:
        return jsonify({'ok': False, 'message': 'Session not found for selected centre'}), 404

    old_name = mapping.display_name or mapping.session_id
    normalized_new_name = " ".join(new_name.split()).casefold()
    normalized_old_name = " ".join(old_name.split()).casefold()
    if normalized_new_name == normalized_old_name:
        return jsonify({'ok': True, 'session': mapping.to_dict(), 'message': 'Session name unchanged'})

    for existing in SessionMapping.query.filter(
        SessionMapping.centre_id == selected_centre_id,
        SessionMapping.batch_id == mapping.batch_id,
        SessionMapping.session_id != session_id
    ).all():
        existing_name = " ".join((existing.display_name or existing.session_id).split()).casefold()
        if existing_name == normalized_new_name:
            return jsonify({'ok': False, 'message': 'A session with that name already exists in this batch'}), 409

    mapping.display_name = new_name

    try:
        db.session.commit()
        regenerate_cache_files(selected_centre_id)
        _fire_webhook('session.renamed', 'session', session_id, {
            'session_id': session_id, 'centre_id': selected_centre_id,
            'old_name': old_name, 'new_name': new_name,
        })
        return jsonify({
            'ok': True,
            'session': mapping.to_dict(),
            'old_name': old_name,
            'new_name': new_name,
        })
    except Exception as e:
        db.session.rollback()
        return jsonify({'ok': False, 'message': str(e)}), 500

@app.route('/api/delete_session', methods=['POST'])
def delete_session():
    """Host-only API to delete a session sheet from the master file."""
    if not is_host_device() and session.get('role') != 'host':
        return jsonify({"error": "Unauthorized. Only the host can delete sessions."}), 403
        
    data = request.json
    session_id = (data.get('session_id') or '').strip()
    if not session_id or session_id == "MASTER_ALL_SESSIONS":
        return jsonify({"error": "Invalid session ID"}), 400
        
    if not os.path.exists(LIVE_FILE):
        return jsonify({"error": "Master file not found"}), 404
        
    try:
        selected_centre_id = get_selected_centre_id()
        mapping = SessionMapping.query.filter_by(session_id=session_id, centre_id=selected_centre_id).first()
        if not mapping and session_id not in {m.session_id for m in SessionMapping.query.filter_by(centre_id=selected_centre_id).all()}:
            return jsonify({"error": "Session not found"}), 404

        batch_name = None
        # Read every ORM attribute still needed AFTER the delete into plain locals NOW.
        # `db.session.commit()` below expires all instances, and because the SessionMapping row is
        # deleted with synchronize_session=False the instance is never marked deleted — so the next
        # attribute access re-SELECTs a row that is gone and raises ObjectDeletedError. That fired
        # while building the webhook payload dict, BEFORE _fire_webhook was even entered, and the
        # bare `except Exception` below swallowed it. Result: between 2026-07-27 and 2026-08-14
        # three host deletions never reached the calling backend, leaving ghost calling sessions
        # visible in the unified app. Hoisting is the fix — never touch `mapping` past the commit.
        mapped_batch_id = mapping.batch_id if mapping else None
        if mapping:
            batch = Batch.query.filter_by(id=mapped_batch_id, centre_id=selected_centre_id).first()
            batch_name = batch.name if batch else None
        else:
            batch = None
        batch_pk = batch.id if batch else None

        backup_path = None
        temp_path = None
        try:
            temp_dir = os.path.dirname(LIVE_FILE) or None
            backup_fd, backup_path = tempfile.mkstemp(prefix="delete_session_backup_", suffix=".xlsx", dir=temp_dir)
            os.close(backup_fd)
            shutil.copy2(LIVE_FILE, backup_path)

            delete_session_sheet_from_live_workbook(session_id)

            Attendance.query.filter(
                Attendance.session_id == session_id,
                Attendance.centre_id == selected_centre_id
            ).delete(synchronize_session=False)
            SessionMapping.query.filter_by(session_id=session_id, centre_id=selected_centre_id).delete(synchronize_session=False)
            db.session.commit()
        except Exception:
            db.session.rollback()
            try:
                if backup_path and os.path.exists(backup_path):
                    os.replace(backup_path, LIVE_FILE)
            except Exception:
                pass
            raise
        finally:
            for p in (temp_path, backup_path):
                try:
                    if p and os.path.exists(p):
                        os.remove(p)
                except Exception:
                    pass

        try:
            # Reset active session state if they deleted the current one.
            _active = get_active_session(selected_centre_id)
            if _active.get('session_id') == session_id or session.get('active_session_id') == session_id:
                preserved_centre_id = _active.get('centre_id') or selected_centre_id
                preserved_centre_name = _active.get('centre_name') or get_centre_name_by_id(preserved_centre_id)
                preserved_batch_id = _active.get('batch_id')
                preserved_batch_name = _active.get('batch_name')
                set_active_session(preserved_centre_id, {
                    "centre_id": preserved_centre_id,
                    "centre_name": preserved_centre_name,
                    "session_id": None,
                    "batch_id": preserved_batch_id,
                    "batch_name": preserved_batch_name,
                    "date": None,
                    "sheet_name": None,
                })
                session['selected_centre_id'] = preserved_centre_id
                session['selected_centre_name'] = preserved_centre_name
                session.pop('active_session_id', None)
                session.pop('active_session_sheet', None)
            write_log(f"Session '{session_id}' DELETED by host.")
            _fire_webhook('session.deleted', 'session', session_id, {
                'session_id': session_id, 'centre_id': selected_centre_id,
                'batch_id': mapped_batch_id,
            })
        except Exception as _e:
            # NEVER swallow silently. The calling backend learns about deletions only through the
            # webhook above, so an exception here means the two systems have already diverged and
            # nobody is told. Three weeks of ghost calling sessions came from this being `pass`.
            write_log(f"Session '{session_id}' delete: post-delete notify FAILED: {_e!r}")

        enqueue_storage_sync_job(
            "mark_session_deleted",
            {
                "centre_id": selected_centre_id,
                # Hoisted locals, not `mapping.batch_id` — the row is deleted by now (see above).
                # This site sat OUTSIDE the try, so the same expired-instance access turned an
                # otherwise-successful deletion into an HTTP 500 and the host clicked Delete twice.
                "batch_id": mapped_batch_id if mapped_batch_id is not None else batch_pk,
                "batch_name": batch_name,
                "session_id": session_id,
                "session_name": session_id,
                "deleted_at": safe_timestamp(),
            }
        )
        enqueue_excel_output_refresh(
            "refresh_attendance_mirrors_for_attendance_change",
            {
                "centre_id": selected_centre_id,
                "batch_id": mapped_batch_id if mapped_batch_id is not None else batch_pk,
                "batch_name": batch_name,
                "session_id": session_id,
                "session_name": session_id,
                "status": "deleted",
            }
        )

        regenerate_cache_files(selected_centre_id)
        return jsonify({"message": f"Session '{session_id}' deleted successfully"})
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/api/get_session', methods=['GET'])
def get_session():
    centre_id_for_session = request.args.get("centre_id") or get_selected_centre_id()
    if not centre_id_authorized(centre_id_for_session):
        return jsonify({"error": "Forbidden"}), 403
    # Youth/Congregation: return the active session for the requested category slot
    # (defaults to youth). The registerer polls this per its current toggle.
    category = request.args.get("category") or 'youth'
    return jsonify(get_active_session(centre_id_for_session, category))

@app.route('/api/get_user/<mobile>', methods=['GET'])
def get_user(mobile):
    centre_id = get_selected_centre_id()
    batch_id = request.args.get('batch_id') or get_active_session().get('batch_id')
    profile = get_best_profile_for_mobile(mobile, centre_id=centre_id, batch_id=batch_id)
    if profile.get("source") != "none":
        write_log(f"User {mobile} profile retrieved from {profile.get('source')}.")
        return jsonify(profile)
    return jsonify({"error": "User not found"}), 404


@app.route('/api/update_user', methods=['POST'])
def update_user():
    data = request.json or {}
    mobile = data.get('mobile')
    updated_fields = data.get('data', {})

    if not mobile:
        return jsonify({"success": False, "error": "Mobile number is required"}), 400

    centre_id = get_selected_centre_id()

    try:
        user = User.query.filter_by(mobile=mobile).first()
        if not user:
            return jsonify({"success": False, "error": "User not found"}), 404

        for field, value in updated_fields.items():
            if hasattr(user, field) and field not in ['id', 'mobile', 'created_at']:
                setattr(user, field, value)

        db.session.commit()
        write_log(f"User {mobile} updated: {', '.join(updated_fields.keys())}")
        return jsonify({"success": True})
    except Exception as e:
        db.session.rollback()
        write_log(f"Error updating user {mobile}: {e}")
        return jsonify({"success": False, "error": str(e)}), 500


@app.route('/api/delete_attendance_record', methods=['POST'])
def delete_attendance_record():
    data = request.json or {}
    mobile = data.get('mobile')
    centre_id = get_selected_centre_id()

    if not mobile:
        return jsonify({"success": False, "error": "Mobile number is required"}), 400

    try:
        session_id = get_active_session()['session_id']
        date_str = get_active_session()['date']

        deleted = Attendance.query.filter(
            Attendance.mobile == mobile,
            Attendance.centre_id == centre_id,
            Attendance.session_id == session_id,
            Attendance.date == date_str
        ).delete(synchronize_session=False)

        db.session.commit()
        write_log(f"Deleted {deleted} attendance record(s) for {mobile}")
        return jsonify({"success": True, "deleted": deleted})
    except Exception as e:
        db.session.rollback()
        write_log(f"Error deleting attendance for {mobile}: {e}")
        return jsonify({"success": False, "error": str(e)}), 500


@app.route('/api/registration_status', methods=['GET'])
def registration_status():
    mobile = normalize_import_mobile(request.args.get('mobile'))
    if not mobile:
        return jsonify({"ok": False, "error": "Mobile number is required"}), 400

    centre_id = (request.args.get('centre_id') or get_selected_centre_id()).strip()
    if not centre_id_authorized(centre_id):
        return jsonify({"ok": False, "error": "Forbidden"}), 403
    batch_id = request.args.get('batch_id')
    payload = get_registration_status_payload(mobile, centre_id=centre_id, batch_id=batch_id)
    status_code = 200 if payload.get("ok") else 400
    return jsonify(payload), status_code


def park_registration(*, data, mobile, name, centre_id, category, requested_batch_id, reason):
    """Hold a registration that has no valid batch to land in.

    Stores the whole original body alongside the device and operator that sent it. The
    columns are for querying; payload_json is the record of record, because the thing a
    future diagnosis needs is usually not a field we thought to add today.

    Re-parks onto the existing row for the same person+centre rather than stacking
    duplicates — the same walk-in re-entered twice is one pending registration.
    """
    try:
        submitted_by = (getattr(g, 'jwt_username', None)
                        or session.get('username')
                        or session.get('user') or '')
    except Exception:
        submitted_by = ''
    try:
        payload_json = json.dumps(data, ensure_ascii=False, default=str)[:20000]
    except Exception:
        payload_json = None

    try:
        parked = ParkedRegistration.query.filter_by(
            mobile=mobile, centre_id=centre_id, status='awaiting_batch').first()
        if not parked:
            parked = ParkedRegistration(mobile=mobile, centre_id=centre_id)
            db.session.add(parked)
        parked.name = name
        parked.address = _normalize_profile_text(data.get('address'))
        parked.occupation = _normalize_profile_text(data.get('occupation'))
        parked.remark = str(data.get('remark') or '').strip() or None
        parked.category = category or ''
        parked.requested_batch_id = (
            str(requested_batch_id).strip()
            if requested_batch_id not in (None, '') else None)
        parked.payload_json = payload_json
        parked.device_id = str(data.get('device_id') or '').strip() or None
        parked.submitted_by = str(submitted_by or '').strip() or None
        parked.user_agent = str(request.headers.get('User-Agent') or '')[:300] or None
        parked.reason = reason
        parked.status = 'awaiting_batch'
        parked.received_at = datetime.now()
        db.session.commit()
        write_log(
            f"PARKED registration {parked.id}: {mobile} ({name}) at {centre_id} "
            f"category='{category or 'unset'}' reason={reason} — accepted and held; "
            f"it will register itself once that centre has a default batch."
        )
        return parked
    except Exception as e:
        db.session.rollback()
        write_log(f"Failed to park registration for {mobile} at {centre_id}: {e}")
        return None


def drain_parked_registrations(centre_id=None, limit=500):
    """Register everything parked whose centre+category now has a default batch.

    Goes through upsert_centre_roster_entry — the same internal register_student uses —
    rather than writing the roster row directly, so the roster/cache path stays identical
    to a live registration. That upsert is also what makes this safe to run repeatedly, and
    safe when the person was already registered by hand in the meantime.

    mark_present is deliberately NOT replayed: a parked row can be hours or days old, and
    an attendance mark for a class that has since finished would be a fabrication. What was
    parked is a registration, so a registration is what comes back out.
    """
    resolved, failed, touched = 0, 0, set()
    q = ParkedRegistration.query.filter_by(status='awaiting_batch')
    if centre_id:
        q = q.filter_by(centre_id=centre_id)
    for parked in q.order_by(ParkedRegistration.id.asc()).limit(limit).all():
        category = (parked.category or '').strip().lower()
        if category not in ('youth', 'congregation'):
            category = 'youth'
        batch = Batch.query.filter_by(
            centre_id=parked.centre_id, category=category,
            is_default=True, status='active').first()
        if not batch:
            continue
        try:
            payload = json.loads(parked.payload_json) if parked.payload_json else {}
        except Exception:
            payload = {}
        if not isinstance(payload, dict):
            payload = {}
        try:
            user = User.query.filter_by(mobile=parked.mobile).first()
            if not user:
                user = User(mobile=parked.mobile, name=parked.name or parked.mobile)
                db.session.add(user)
            merge_user_profile_from_payload(
                user, payload, preserve_existing=True, log_prefix="ParkedRegistration")
            if not _normalize_profile_text(user.name):
                user.name = parked.name or parked.mobile
            if not _normalize_profile_text(user.cheating):
                user.cheating = "No"
            upsert_centre_roster_entry(
                mobile=parked.mobile, centre_id=parked.centre_id, user=user,
                batch_id=batch.id, batch_name=batch.name, payload=payload)
            parked.status = 'registered'
            parked.resolved_at = datetime.now()
            parked.resolved_batch_id = batch.id
            db.session.commit()
            resolved += 1
            touched.add(parked.centre_id)
            write_log(
                f"Parked registration {parked.id} drained: {parked.mobile} "
                f"({parked.name}) -> batch {batch.id} ({batch.name}) [{category}] "
                f"at {parked.centre_id}"
            )
        except Exception as e:
            db.session.rollback()
            failed += 1
            write_log(f"Parked registration {parked.id} could not be drained: {e}")
    for cid in touched:
        try:
            regenerate_cache_files(cid)
        except Exception as e:
            write_log(f"Cache regen after parked drain failed for {cid}: {e}")
    if resolved or failed:
        write_log(f"Parked registration drain: {resolved} registered, {failed} failed.")
    return resolved


@app.route('/api/parked_registrations', methods=['GET'])
def parked_registrations():
    """Registrations accepted but not yet filed under a batch.

    Like parked marks, these are not errors and not lost data — they are people waiting on
    one decision (which batch), held server-side instead of decaying in a phone's retry
    queue. Pass ?status=all to include ones already drained.
    """
    centre_id = request.args.get('centre_id') or get_selected_centre_id()
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403
    wanted = (request.args.get('status') or 'awaiting_batch').strip().lower()
    q = ParkedRegistration.query.filter_by(centre_id=centre_id)
    if wanted != 'all':
        q = q.filter_by(status=wanted)
    rows = q.order_by(ParkedRegistration.received_at.asc()).all()
    return jsonify({
        "centre_id": centre_id,
        "status": wanted,
        "count": len(rows),
        "registrations": [r.to_dict() for r in rows],
    })


@app.route('/api/parked_registrations/drain', methods=['POST'])
def parked_registrations_drain():
    """Retry the parked registrations for a centre — use after setting a default batch."""
    data = request.json or {}
    centre_id = str(data.get('centre_id') or '').strip() or get_selected_centre_id()
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403
    resolved = drain_parked_registrations(centre_id=centre_id)
    remaining = ParkedRegistration.query.filter_by(
        centre_id=centre_id, status='awaiting_batch').count()
    return jsonify({"success": True, "registered": resolved, "still_waiting": remaining})


@app.route('/api/register_student', methods=['POST'])
def register_student():
    data = request.json or {}
    # Native Android app may pass centre_id in body; fall back to session.
    # mark_attendance already does exactly this — register_student was the odd
    # write endpoint that relied on the session's centre alone. For a Bearer-only
    # API call (the app never runs session_from_jwt before writing) the session has
    # no selected_centre_id, so get_selected_centre_id() returned DEFAULT_CENTRE_ID
    # (centre_b_Base) and a batch in the caller's real centre came back
    # "Batch not found for the selected centre" — which the app mislabels as
    # "not connected" and parks in its offline queue.
    req_centre_id = str(data.get('centre_id') or '').strip() or None
    if not centre_id_authorized(req_centre_id):
        return jsonify({"error": "Forbidden"}), 403
    # Resolve centre: explicit body value first, then any established session
    # centre, then — for a Bearer-only JWT call that never ran session_from_jwt —
    # the JWT user's own primary centre. Only after all of those fall through do
    # we hit DEFAULT_CENTRE_ID. This keeps a registerer whose primary centre is
    # not the platform default from silently writing into the wrong centre.
    selected_centre_id = req_centre_id or session.get("selected_centre_id")
    if not selected_centre_id:
        jwt_username = getattr(g, 'jwt_username', None)
        if jwt_username:
            _, primary_centre, _ = get_primary_centre_access_context(jwt_username)
            if primary_centre:
                selected_centre_id = primary_centre.centre_id
    selected_centre_id = selected_centre_id or get_selected_centre_id()

    mobile = normalize_import_mobile(data.get('mobile'))
    if not mobile:
        return jsonify({"error": "Mobile number is required"}), 400

    name = _normalize_profile_text(data.get('name'))
    if not name:
        return jsonify({"error": "Name is required"}), 400

    # Batch resolution, in descending order of how explicit the intent is:
    #   1. what the client actually sent
    #   2. the centre's active session
    #   3. the centre's default batch
    #
    # Step 3 exists because steps 1 and 2 can BOTH be empty at once, which is not the
    # exotic case it looks like. The app resolves its own batch as "explicit pick, else
    # the centre+category default" and sends "" when its cached batch list has not been
    # refreshed; the server then leant on the active session — which lives in the
    # in-memory ACTIVE_SESSION_BY_CENTRE and is therefore wiped by any restart. On
    # 2026-08-07 the backend restarted at 20:07:15 and the registerer's next two
    # submissions came back "Select a batch first"; he retried once and stopped. The
    # entries were not lost (the app parks a failed write in its retry queue) but they
    # replay with the same empty batch_id, so they could never drain on their own.
    #
    # Registering into the centre's default batch is what the registerer meant in every
    # one of those cases — that is what the Registration tab is for. A restart should not
    # be able to block registration.
    #
    # Category is only carried on the payload when the Attendance tab asks for a mark, so a
    # pre-v12 build's plain registration has none at all. An absent category resolves to
    # youth: that is what every category-less write has meant for the life of this system,
    # and the app's own selectedCategory defaults to "Youth", so this preserves the existing
    # behaviour exactly instead of guessing at it. Counting default batches instead ("use it
    # only if the centre has exactly one") looks safer and is worse — the moment a centre
    # gains a congregation default, every older client's plain registration stops resolving.
    # v12 onward always sends category, which is the only thing that can actually settle the
    # congregation case: the information is simply not present in an older request.
    batch_id_raw = data.get('batch_id')
    sent_category = str(data.get('category') or '').strip().lower()
    reg_category = sent_category if sent_category in ('youth', 'congregation') else 'youth'
    if batch_id_raw is None or str(batch_id_raw).strip() == "":
        # Pass the resolved centre explicitly: the bare get_active_session() read the
        # Flask session's centre, which for a Bearer-only app call is DEFAULT_CENTRE_ID,
        # i.e. potentially a different centre than the one being registered into.
        batch_id_raw = get_active_session(selected_centre_id, reg_category).get('batch_id')
    if batch_id_raw is None or str(batch_id_raw).strip() == "":
        default_batch = Batch.query.filter_by(
            centre_id=selected_centre_id, category=reg_category,
            is_default=True, status='active').first()
        if default_batch:
            batch_id_raw = default_batch.id
            write_log(
                f"Registration for {mobile}: no batch_id and no active session — "
                f"defaulted to batch {default_batch.id} ({default_batch.name}) "
                f"[{reg_category}] for centre {selected_centre_id}"
            )

    # Whatever came out of that has to resolve to a real batch in THIS centre. A malformed
    # or foreign batch_id used to answer 400/404, which is the same dead end as the missing
    # one: the app reads any non-2xx as failure and re-queues a payload that fails the same
    # way forever. Both now park instead.
    batch = None
    try:
        if batch_id_raw is not None and str(batch_id_raw).strip() != "":
            batch = Batch.query.filter_by(
                id=int(batch_id_raw), centre_id=selected_centre_id).first()
    except (TypeError, ValueError):
        batch = None

    if not batch:
        parked = park_registration(
            data=data, mobile=mobile, name=name, centre_id=selected_centre_id,
            category=sent_category, requested_batch_id=batch_id_raw,
            reason=('no_default_batch' if not str(batch_id_raw or '').strip()
                    else 'batch_not_found_for_centre'),
        )
        return jsonify({
            "message": "Saved on the server — waiting for a batch to file it under",
            "pending_batch": True,
            "parked_id": parked.id if parked else None,
            "mobile": mobile,
            "name": name,
        }), 200

    user = User.query.filter_by(mobile=mobile).first()
    if not user:
        user = User(mobile=mobile, name=name)
        db.session.add(user)

    merge_user_profile_from_payload(user, data, preserve_existing=True, log_prefix="Registration")
    if not _normalize_profile_text(user.name):
        user.name = name
    if not _normalize_profile_text(user.cheating):
        user.cheating = "No"

    roster, created = upsert_centre_roster_entry(
        mobile=mobile,
        centre_id=selected_centre_id,
        user=user,
        batch_id=batch.id,
        batch_name=batch.name,
        payload=data,
    )

    try:
        db.session.commit()
    except Exception as e:
        db.session.rollback()
        return jsonify({"error": str(e)}), 500

    # Tell the calling backend about this person NOW, instead of up to 24 hours from now.
    #
    # THE DEFECT THIS CLOSES (2026-08-21, Centre A). Nothing on the ordinary registration
    # path ever pushed to CB. The only thing that carried a plain registration across was
    # reconcileRosterMembers, which runs ONCE PER DAY (reconcile.ts, INTERVAL_MS = 24h) plus
    # once ten seconds after a CB restart. A centre registered ~109 people, an import landed
    # at 17:01, the day's only reconcile pass had already run at 16:22 — and the calling
    # system showed 21 of them, with the other 75 invisible until the following afternoon.
    # Nothing was lost, but to the people waiting to call them it was indistinguishable from
    # data loss, and they had no way to tell the difference.
    #
    # Reuses the exact path the Roster Inject page already uses rather than inventing one:
    # the SQLite-backed outbox survives an AE restart, retries on 1s/5s/30s/5m/30m backoff,
    # and lands on CB's existing "roster.injected" handler, which routes to onRosterMember —
    # additive, and explicitly refuses to resurrect anyone at stage removed/archived. The
    # daily reconcile stays exactly as it is and becomes the safety net behind this rather
    # than the only delivery mechanism.
    #
    # `name` rides along because CB's person-directory lookup is a 60s-TTL cache: someone
    # written here moments ago is absent from a cache built before they existed, and CB would
    # store the bare phone number as their permanent display name. Same reason the inject
    # page sends it.
    #
    # Category comes from the BATCH, not from the request, so this agrees with what the
    # reconcile would have sent for the same person (its per-batch loop passes batch.category).
    # Disagreeing would make the instant push and the safety net fight over the same row.
    #
    # Never allowed to break the registration: the person is already committed above.
    try:
        _enqueue_cb_outbox('roster.injected', {
            'mobile': mobile,
            'name': name,
            'centre_id': selected_centre_id,
            'batch_id': batch.id,
            'category': category_for_batch(batch.id) or reg_category,
        })
    except Exception as _e:
        write_log(f"WARN registration push to CB failed to enqueue for {mobile}: {_e}")

    # Registration does NOT mark attendance on its own.
    #
    # Registering someone means adding them to the selected batch, nothing more — that is what
    # the Registration tab is for, and it is used outside class hours where an attendance row
    # would be wrong. The combined act ("this person is standing here right now") belongs to the
    # Attendance tab, which asks for it explicitly by sending mark_present.
    #
    # This started as an unconditional auto-mark, after the operator performed 14 entries during
    # the Centre A BG class on 2026-08-01 and the host page showed 9 — the other 5 were door
    # registrations that recorded nobody as present. Making every registration a mark fixed that
    # case and broke the general one, so the decision now sits with the caller instead of being
    # inferred here.
    #
    # NOT gated on `created` any more. It was, and that silently dropped marks: on 2026-08-09
    # the registerer submitted 15 congregation attendees and only 14 landed. The fifteenth,
    # Gaurav Goswami, was already on this centre's roster, so `created` was False and his mark
    # was discarded — while the response still said "Registration updated successfully", giving
    # the operator no sign that anyone had been missed. It surfaced only as a count that was one
    # short.
    #
    # `created` was standing in for "is this a real attendance or just a details edit", but
    # mark_present already answers that question exactly: the Registration tab never sends it
    # and it is off by default, so it arrives only from "Register & mark present" on the
    # Attendance tab — an operator saying this person is in front of me now. Whether the roster
    # happens to know them already has no bearing on whether they turned up.
    #
    # Safe to mark unconditionally because persist_attendance is idempotent: a repeat mark for
    # the same (mobile, session, date, centre) updates the existing row rather than adding one,
    # so a double tap still cannot double-count.
    #
    # Never allowed to break the registration. The person is already committed to the roster
    # above; if marking fails, that is logged and the registration still stands.
    marked_present = False
    wants_mark = str(data.get('mark_present', '')).strip().lower() in ('1', 'true', 'yes')
    if wants_mark:
        try:
            mark_category = str(data.get('category') or 'youth').strip().lower()
            if mark_category not in ('youth', 'congregation'):
                mark_category = 'youth'
            active = get_active_session(selected_centre_id, mark_category)
            # A session must be genuinely selected. The client's value wins when it is a
            # real id; a spinner placeholder ("-- Select Session --", "-- No sessions --")
            # counts as NOT selected and falls through to whatever session is actually
            # active. If neither yields a real session, the person is registered and simply
            # not marked — registering outside a class must never invent an attendance.
            mark_session_id = (normalize_session_id(data.get('session_id'))
                               or normalize_session_id(active.get('session_id')))
            if mark_session_id:
                _, mark_status = persist_attendance(
                    mobile,
                    data=data,
                    selected_centre_id=selected_centre_id,
                    active_session_id=mark_session_id,
                    active_date=(resolve_session_date(mark_session_id, selected_centre_id)
                                 or sanitize_date_input(data.get('date'))
                                 or sanitize_date_input(active.get('date'))
                                 or datetime.now().strftime("%Y-%m-%d")),
                    active_batch_name=(batch.name or active.get('batch_name') or ""),
                    active_session_name=str(data.get('session_name') or mark_session_id).strip(),
                    active_category=mark_category,
                    remark_val=data.get('remark', '') or '',
                    rounds_val=data.get('rounds', '') or '',
                    cheating_val=("Yes" if str(data.get('cheating')).lower() in ['true', 'yes', '1']
                                  else "No"),
                    server_timestamp=datetime.now(),
                )
                # 409 = already had a row for this session; the intended end state either way.
                marked_present = mark_status in (200, 409)
            else:
                # Asked to mark, but no session is genuinely selected. Register and say so —
                # never invent a session to file the attendance against.
                write_log(
                    f"Registration {mobile}: mark_present requested but no session selected; "
                    f"registered only."
                )
        except Exception as e:
            db.session.rollback()
            write_log(f"Registration {mobile} saved, but auto-mark-present failed: {e}")

    warnings_payload = get_registration_status_payload(
        mobile,
        centre_id=selected_centre_id,
        batch_id=batch.id
    )

    message = "Student registered successfully" if created else "Registration updated successfully"
    if marked_present:
        message += " and marked present"
    response = {
        "ok": True,
        "message": message,
        "mobile": mobile,
        "user": user.to_dict(),
        "roster": roster.to_dict(),
        "warnings": warnings_payload.get("other_memberships", []),
        "registered_here": warnings_payload.get("registered_here", False),
        "registered_in_other_centres": warnings_payload.get("registered_in_other_centres", False),
        "warning": warnings_payload.get("warning", ""),
        # Lets the app show "registered and marked present" and refresh its count,
        # instead of the operator having to infer it from the attendance list.
        "marked_present": marked_present,
    }
    regenerate_cache_files(selected_centre_id)
    return jsonify(response)

@app.route('/api/check_session_user/<session_id>/<mobile>', methods=['GET'])
def check_session_user(session_id, mobile):
    att = Attendance.query.filter_by(mobile=mobile, session_id=session_id, centre_id=get_selected_centre_id()).first()
    return jsonify({"in_session": bool(att)})

# The Android session spinner emits these when nothing real is selected yet. They are
# NOT session ids, and treating one as an id files attendance against a session that does
# not exist — invisible on every screen that filters by a real session.
_SESSION_PLACEHOLDERS = {"-- Select Session --", "-- No sessions --", "select session", "no sessions"}


def sessions_in_play(centre_id, category, on_date=None):
    """Which sessions could plausibly be receiving marks right now at this centre.

    Used to decide whether the server is ALLOWED to guess a session for a client that did not
    send one. There is no reliable "open" flag to lean on: every one of the 41 rows in
    session_mappings is status='active' with a NULL date, because nothing ever closes a
    session. So "in play" is derived from two signals that ARE trustworthy:

      - the session was created today (`created_at`), i.e. someone set up a class today; and
      - the session already has attendance today.

    Either alone is insufficient. A class that has just started has no marks yet, and a class
    reusing a session created weeks ago never appears under the first test.

    On 2026-08-03 this returns both "3rd Aug 2026 OTP" (created 17:16) and "3rd Aug 2026 HMBG"
    (created 18:03) — which is precisely the ambiguity the server must refuse to resolve.
    """
    on_date = on_date or datetime.now().strftime("%Y-%m-%d")
    cat = (category or 'youth').strip().lower()
    found = set()
    try:
        for row in SessionMapping.query.filter_by(centre_id=centre_id).all():
            if (row.category or 'youth').strip().lower() != cat:
                continue
            created = getattr(row, 'created_at', None)
            if created and created.strftime("%Y-%m-%d") == on_date:
                found.add(row.session_id)
        for (sid,) in db.session.query(Attendance.session_id).filter_by(
            centre_id=centre_id, date=on_date, category=cat
        ).distinct().all():
            if sid:
                found.add(sid)
    except Exception as e:
        write_log(f"sessions_in_play failed for {centre_id}/{cat}: {e}")
    return sorted(found)


def resolve_session_for_unspecified_mark(centre_id, category, on_date=None):
    """Resolve a session for a client that sent none — or refuse, rather than guess wrong.

    Returns (session_id, error_or_None).

    The old behaviour was `requested_session_id or get_active_session(...)`, an unconditional
    fall back to the single shared slot. The Android client leans on it deliberately
    (AppData.kt: "Omit rather than invent. With no session_id the server resolves the centre's
    own active session, which is the right answer"). With one class running that is true. With
    two, the slot holds whichever class selected LAST, so the mark silently lands in the wrong
    class — and the operator gets a success message either way.

    So the fallback survives only where it is unambiguous:
      0 sessions in play -> use the slot; nothing else is happening, nothing to confuse.
      1 session in play  -> use it; there is only one possible answer.
      2+                 -> REFUSE, and name them, so the operator picks a class.

    Refusing is the whole point. A wrong-session mark is invisible and corrupts the batch,
    the sheet tab and the calling pool; a refusal is visible in the second it happens.
    """
    slot_session = get_active_session(centre_id, category).get('session_id')
    in_play = sessions_in_play(centre_id, category, on_date)

    if len(in_play) <= 1:
        return (in_play[0] if in_play else slot_session), None

    if slot_session in in_play:
        # The slot names one of them, but it is shared across every device at this centre, so
        # it describes whoever selected last — not necessarily whoever is marking now.
        write_log(
            f"Refused to guess a session for {centre_id}/{category}: {len(in_play)} sessions "
            f"in play ({', '.join(in_play)}). Shared slot holds '{slot_session}'."
        )
    return None, {
        "error": "Select a class before marking",
        "reason": "multiple_sessions_active",
        "message": (
            f"{len(in_play)} classes are running at this centre right now. "
            f"The app must say which one this attendance belongs to."
        ),
        "sessions_in_play": in_play,
    }


def normalize_capture_ts(raw, fallback):
    """When the operator actually tapped, as an ISO string that sorts correctly.

    Clients may send epoch milliseconds (the Android queue already stamps every item with
    System.currentTimeMillis()) or an ISO string. Both normalise to ISO here so resolution can
    be a plain string comparison, and so a mark whose client clock is unreadable still gets a
    usable value rather than silently becoming NULL and losing every comparison.
    """
    value = str(raw or '').strip()
    if value:
        # Epoch millis or seconds
        if value.isdigit():
            try:
                n = int(value)
                if n > 10 ** 12:            # milliseconds
                    return datetime.fromtimestamp(n / 1000.0).isoformat(timespec='microseconds')
                if n > 10 ** 9:             # seconds
                    return datetime.fromtimestamp(n).isoformat(timespec='microseconds')
            except (ValueError, OSError, OverflowError):
                pass
        else:
            try:
                return datetime.fromisoformat(value.replace('Z', '')).isoformat(timespec='microseconds')
            except ValueError:
                pass
    return (fallback or datetime.now()).isoformat(timespec='microseconds')


def record_attendance_mark(*, mobile, session_id, date, centre_id, category, batch_name,
                           remark, rounds, cheating, data, capture_ts, received_at,
                           client_offline_id, source):
    """Append this submission to the event log, before deciding whether it wins resolution.

    Always called, for every submission, including ones that will be superseded. That is the
    whole point: the operator's input lands unconditionally, and the question of which version
    is correct is answered separately, against the log rather than by discarding data.

    Returns the AttendanceMark, or None if this exact offline item was already logged — a
    replay of the same physical tap, which the unique index on client_offline_id collapses.
    """
    if client_offline_id:
        existing = AttendanceMark.query.filter_by(client_offline_id=client_offline_id).first()
        if existing:
            return None
    mark = AttendanceMark(
        mobile=mobile, session_id=session_id, date=date, centre_id=centre_id,
        category=category, batch_name=batch_name,
        remark=remark, rounds=rounds, cheating=cheating,
        name=data.get('name'), address=data.get('address'), occupation=data.get('occupation'),
        device_id=(str(data.get('device_id') or '').strip() or None),
        marked_by=(str(data.get('marked_by') or '').strip() or session.get('username')),
        capture_ts=capture_ts, received_at=received_at,
        client_offline_id=client_offline_id, source=source, applied=False,
    )
    db.session.add(mark)
    db.session.commit()
    return mark


def normalize_session_id(raw):
    """A real session id, or "" when nothing is genuinely selected.

    Shared by mark_attendance and register_student so the two cannot disagree about
    what counts as "no session selected". register_student needs it just as much:
    auto-marking a new registrant against the literal string "-- Select Session --"
    would create an attendance row nobody can ever see.
    """
    value = str(raw or '').strip()
    if value.lower().strip('- ') in {p.lower().strip('- ') for p in _SESSION_PLACEHOLDERS}:
        return ""
    return value


def persist_attendance(
    mobile,
    *,
    data,
    selected_centre_id,
    active_session_id,
    active_date,
    active_batch_name,
    active_session_name,
    active_category,
    remark_val,
    rounds_val,
    cheating_val,
    server_timestamp,
    client_offline_id=None,
):
    """Record one person as present, and do everything that has to follow from it.

    This is the whole tail of mark_attendance, lifted out so that /api/register_student
    can mark a first-time registrant present through the EXACT same path rather than
    writing its own Attendance row.

    That distinction matters more than it looks. Marking someone present is not one
    database insert — it is the insert plus the live-workbook mirror, the per-centre
    Google Sheet append, the Excel output refresh, the attendance.persisted webhook,
    the durable SRKB outbox entry, the planned-session calling-loop close, and the
    offline cache regeneration. A second copy of that list in register_student would
    have drifted from this one within weeks, and every past divergence of exactly this
    kind has shown up as attendance that exists in one system and not another (the
    MASTER_ALL_SESSIONS tab that was silently written instead of MASTER_ALL; the
    session_id staleness check that dropped a real record on 2026-07-04).

    Returns (payload, status_code). A repeat mark returns 200, not 409: the submission is
    always accepted, and whether it wins resolution is a separate question answered below.
    Callers already treated 409 as success (register_student's `mark_status in (200, 409)`,
    and the Android client's postMarkAttendanceIdempotent), so widening it to 200 loosens
    nothing — but it does mean a corrected remark now lands instead of being thrown away.
    """
    # Log the submission FIRST, before any decision about it. If the server dies halfway
    # through the downstream fan-out, the operator's tap is still on record.
    capture_ts = normalize_capture_ts(
        data.get('client_marked_at') or data.get('capture_ts') or data.get('client_ts'),
        server_timestamp,
    )
    mark = record_attendance_mark(
        mobile=mobile, session_id=active_session_id, date=active_date,
        centre_id=selected_centre_id, category=active_category,
        batch_name=active_batch_name, remark=remark_val, rounds=rounds_val,
        cheating=cheating_val, data=data, capture_ts=capture_ts,
        received_at=server_timestamp, client_offline_id=client_offline_id,
        source=(data.get('_source') or ('offline_replay' if client_offline_id else 'live')),
    )
    if mark is None:
        # Same physical tap delivered twice. Nothing to decide and nothing to redo.
        return {"message": "Already recorded", "duplicate_delivery": True}, 200

    existing = Attendance.query.filter_by(
        mobile=mobile, session_id=active_session_id, date=active_date, centre_id=selected_centre_id
    ).first()

    # Resolution is by CAPTURE time, never by arrival time. A phone that was offline during
    # class and syncs hours later must not overwrite a correction made in the meantime; its
    # mark is still recorded above, it simply does not win. Ordering by arrival is the same
    # mistake that shipped the wrong batch_id on 2026-08-03 — deriving a value from when the
    # delivery fired rather than from the event itself.
    stored_ts = (existing.capture_ts or (existing.timestamp.isoformat(timespec='microseconds')
                                         if existing and existing.timestamp else '')) if existing else ''
    wins = (not existing) or (capture_ts >= stored_ts)

    if not wins:
        mark.superseded_reason = f"older than resolved mark ({capture_ts} < {stored_ts})"
        db.session.commit()
        write_log(
            f"Stale mark recorded but not applied: {mobile} in {active_session_id} "
            f"(captured {capture_ts}, resolved row holds {stored_ts})."
        )
        return {"message": "Recorded; a newer mark already resolves this record",
                "applied": False}, 200

    # This mark wins, so the person's last-known details come from it too. Gating on `wins`
    # is deliberate: the old code updated the User row unconditionally and BEFORE the
    # duplicate check, so a stale replay silently overwrote current details.
    user = User.query.filter_by(mobile=mobile).first()
    if not user:
        user = User(mobile=mobile)
        db.session.add(user)
    user.name = data.get('name', user.name)
    user.address = data.get('address', user.address)
    user.occupation = data.get('occupation', user.occupation)
    user.rounds = rounds_val
    user.cheating = cheating_val
    user.remark = remark_val
    db.session.commit()

    from sqlalchemy import text
    params = {
        "mobile": mobile, "date": active_date, "batch_name": active_batch_name,
        "session_id": active_session_id, "centre_id": selected_centre_id,
        "category": active_category, "remark": remark_val, "rounds": rounds_val,
        "cheating": cheating_val, "ts": server_timestamp, "capture_ts": capture_ts,
        "device_id": mark.device_id, "marked_by": mark.marked_by,
    }

    # Whether THIS request created the row decides whether the downstream fan-out runs — the
    # Google Sheet append in particular, which would visibly duplicate a line if it ran twice
    # for one person in one session.
    #
    # It must be answered by the database, not inferred. An earlier version of this compared
    # capture_ts and got it wrong: when two devices race, the loser can still hold the winning
    # capture_ts, so it would have concluded "I inserted" and appended a second sheet row.
    # INSERT .. ON CONFLICT DO NOTHING reports rowcount 1 only for the request that actually
    # inserted, which is exactly the question being asked.
    inserted = False
    if existing is None:
        try:
            result = db.session.execute(text("""
                INSERT INTO attendance
                    (mobile, date, batch_name, session_id, centre_id, category,
                     remark, rounds, cheating, timestamp, capture_ts, device_id, marked_by, updated_at)
                VALUES
                    (:mobile, :date, :batch_name, :session_id, :centre_id, :category,
                     :remark, :rounds, :cheating, :ts, :capture_ts, :device_id, :marked_by, :ts)
                ON CONFLICT (mobile, session_id, date, centre_id) DO NOTHING
            """), params)
            db.session.commit()
            inserted = (result.rowcount == 1)
        except Exception as upsert_error:
            # ON CONFLICT needs the unique index to exist. If the migration could not create
            # it (duplicates left in the table), marking must still work for the operator in
            # front of a class — so degrade to a plain insert and say so loudly, rather than
            # failing the request or silently pretending the guarantee still holds.
            db.session.rollback()
            write_log(
                f"CRITICAL: attendance upsert failed ({upsert_error}); falling back to a "
                f"non-atomic insert for {mobile}/{active_session_id}. Duplicate protection is "
                f"NOT enforced until uq_attendance_person_session exists."
            )
            db.session.execute(text("""
                INSERT INTO attendance
                    (mobile, date, batch_name, session_id, centre_id, category,
                     remark, rounds, cheating, timestamp, capture_ts, device_id, marked_by, updated_at)
                VALUES
                    (:mobile, :date, :batch_name, :session_id, :centre_id, :category,
                     :remark, :rounds, :cheating, :ts, :capture_ts, :device_id, :marked_by, :ts)
            """), params)
            db.session.commit()
            inserted = True

    att = Attendance.query.filter_by(
        mobile=mobile, session_id=active_session_id, date=active_date,
        centre_id=selected_centre_id
    ).first()
    is_new = inserted

    if not inserted and att is not None:
        # The row already existed, or another device created it while this request was in
        # flight. Either way this submission still updates the fields if it is the newer one —
        # re-checked against the row as it stands NOW, because that other device may have
        # written a capture_ts newer than the one compared against `existing` above.
        current_ts = att.capture_ts or (att.timestamp.isoformat(timespec='microseconds')
                                        if att.timestamp else '')
        if capture_ts >= current_ts:
            att.remark = remark_val
            att.rounds = rounds_val
            att.cheating = cheating_val
            att.batch_name = active_batch_name
            att.category = active_category
            att.capture_ts = capture_ts
            att.device_id = mark.device_id
            att.marked_by = mark.marked_by
            att.updated_at = server_timestamp
            db.session.commit()
        else:
            mark.superseded_reason = f"lost race to newer mark ({capture_ts} < {current_ts})"
            db.session.commit()

    msg = "Attendance marked successfully" if is_new else "Attendance updated"

    # Resolved here because BOTH branches below need it to name the sheet. The slot is
    # only honoured when it actually describes this session — a centre runs two youth
    # classes at once and the slot holds just one, so reading it unconditionally would
    # aim a correction at the wrong tab.
    _active_slot_for_correction = get_active_session(selected_centre_id, active_category)

    mark.applied = True
    mark.attendance_id = att.id if att else None
    db.session.commit()

    if not is_new:
        # The row already existed, so the Google Sheet and the workbook already carry a line
        # for this person in this session. Appending again would duplicate it visually — so
        # the existing row is UPDATED in place instead.
        #
        # This used to rely on "sheet_autosync republishes each session tab FROM the
        # database", which is not true of this workbook: sheet_autosync publishes to Google
        # Sheets only, and the output refresh below writes the per-session file under Data/.
        # Nothing wrote the live master, so a correction never reached the Lists tab —
        # confirmed by watching a corrected row stay stale for 4.5 minutes.
        enqueue_attendance_mirror_update({
            "mobile": mobile, "name": user.name, "rounds": rounds_val,
            "remark": remark_val, "date": active_date, "session_id": active_session_id,
            "centre_id": selected_centre_id,
        }, sheet_name=((_active_slot_for_correction.get('sheet_name')
                        if _active_slot_for_correction.get('session_id') == active_session_id
                        else None) or sanitize_sheet_name(active_session_id)))
        enqueue_excel_output_refresh(
            "refresh_attendance_mirrors_for_attendance_change",
            {
                "centre_id": selected_centre_id,
                "session_id": active_session_id,
                "session_name": active_session_name or active_session_id,
                "batch_name": active_batch_name,
                "refresh_student_master": True,
                "status": "active",
            }
        )
        regenerate_cache_files(selected_centre_id)
        write_log(f"{msg}: {mobile} in {active_session_id} on {active_date} (corrected).")
        return {"message": msg, "applied": True, "updated": True}, 200

    # Everything downstream of this row — the sheet tab it lands in, the batch the
    # SRKB webhook reports, the Excel refresh — must be derived from THIS row's own
    # session, never from the centre's "currently active session" slot.
    #
    # ACTIVE_SESSION_BY_CENTRE holds exactly ONE session per (centre, category), but a
    # centre routinely runs two youth classes at once (OTP and H.M and B.G on 2026-08-03).
    # Whichever device selected last owns that slot, so reading batch/sheet from it makes
    # the value depend on *when the delivery fired* rather than on the attendance itself.
    # That is not a theoretical race: on 2026-08-03 the same mark, delivered twice (live
    # webhook + durable outbox), reported batch=7 on one delivery and batch=3 on the other,
    # and six people who attended OTP were evicted from the default pool into the H.M and
    # B.G batch as a result. The session->batch mapping below is immutable per session, so
    # it gives the same answer on every delivery, in any order, at any time.
    _session_ctx = resolve_storage_context_for_session(active_session_id)
    _active_slot = get_active_session(selected_centre_id, active_category)
    # The slot may still be consulted, but ONLY when it actually describes this session —
    # that keeps a host-chosen custom sheet name working without letting a different
    # session's slot leak in.
    _slot_matches_row = _active_slot.get('session_id') == active_session_id

    # Mirror the workbook / Google sync asynchronously so the user gets a
    # fast success response after the durable local DB write completes.
    sheet_name = (_active_slot.get('sheet_name') if _slot_matches_row else None) \
        or sanitize_sheet_name(active_session_id)
    workbook_row = {
        "mobile": mobile,
        "name": user.name,
        "address": user.address,
        "occupation": user.occupation,
        "batch": active_batch_name,
        "batch_name": active_batch_name,
        # "session" is the display text shown in the spreadsheet cell, which
        # can legitimately be a placeholder like "-- Select Session --" if
        # the client didn't pass a session_name. "session_id" below is the
        # real identifier the Attendance row was actually saved under —
        # enqueue_attendance_mirror_task() needs THIS one for its staleness
        # check, not the display text, or the check always mismatches and
        # silently drops the mirror write (confirmed live: this exact bug
        # dropped a real attendance record on 2026-07-04).
        "session": active_session_name or active_session_id,
        "session_name": active_session_name or active_session_id,
        "session_id": active_session_id,
        "rounds": rounds_val,
        "remark": remark_val,
        "date": active_date,
        # Youth/Congregation: carry the category to the mirror + export so the
        # SRKB calling backend and the Excel/Sheets output route into the right track.
        "category": active_category,
        # Decides which centre's Google Sheet this row mirrors into.
        "centre_id": selected_centre_id,
    }

    try:
        enqueue_attendance_mirror_task(workbook_row, sheet_name=sheet_name)
    except Exception as e:
        write_log(f"Async attendance mirror enqueue failed; falling back to sync path: {e}")
        try:
            append_attendance_to_live_workbook(workbook_row, sheet_name=sheet_name)
            centre_sheet_id = get_google_sheet_id_for_centre(selected_centre_id)
            if centre_sheet_id:
                # Same trap as the async worker: these return False on failure and
                # never raise, so the surrounding except cannot see a sheet error.
                # Record it honestly rather than letting the log imply a write.
                # No extra API calls — just reading the return value.
                sync_failed = [
                    tab for tab in (["MASTER_ALL"] + ([sheet_name] if sheet_name else []))
                    if not append_row_to_google_sheet(
                        workbook_row, sheet_name=tab,
                        spreadsheet_id=centre_sheet_id, on_error=write_log)
                ]
                if sync_failed:
                    write_log(
                        f"Google Sheets sync-fallback FAILED ({mobile}): tab(s) "
                        f"{', '.join(sync_failed)} not written. The attendance row IS "
                        f"saved in the database; sheet_autosync republishes the "
                        f"GOOGLE tab from the DB on its next full pass (it does not "
                        f"touch the live workbook — that one is covered by the "
                        f"additive backfill in reconcile_live_workbook_from_db)."
                    )
            else:
                write_log(
                    f"Google Sheets skipped (sync fallback): centre "
                    f"'{selected_centre_id}' has no Google Sheet linked."
                )
        except Exception as sync_error:
            write_log(f"Live workbook sync failed: {sync_error}")
            return {"error": "Attendance saved, but Excel sync failed"}, 500

    # Session mapping first (immutable, per-row correct); the shared slot only as a
    # fallback, and only when it describes this very session. Reversing this order is
    # what shipped the wrong batch_id to SRKB — see the note above the sheet_name block.
    active_batch_id = _session_ctx.get("batch_id")
    active_batch_name = _session_ctx.get("batch_name") or active_batch_name
    if not active_batch_id and _slot_matches_row:
        active_batch_id = _active_slot.get('batch_id')
        active_batch_name = _active_slot.get('batch_name') or active_batch_name

    enqueue_excel_output_refresh(
        "refresh_attendance_mirrors_for_attendance_change",
        {
            "centre_id": selected_centre_id,
            "batch_id": active_batch_id,
            "batch_name": active_batch_name,
            "session_id": active_session_id,
            "session_name": active_session_name or active_session_id,
            "refresh_student_master": True,
            "status": "active",
        }
    )

    write_log(f"{msg}: User {mobile} ({user.name}) in session {active_session_id} on {active_date}.")
    _fire_webhook('attendance.persisted', 'attendance', str(att.id), {
        'mobile': mobile,
        'centre_id': selected_centre_id,
        'batch_id': active_batch_id,
        'session_id': active_session_id,
        'date': active_date,
    })
    # Also enqueue persistently so SRKB gets the event even if it was briefly offline.
    import datetime as _dt_mod
    _enqueue_cb_outbox('attendance.persisted', {
        'mobile': mobile,
        'centre_id': selected_centre_id,
        'batch_id': active_batch_id,
        'session_id': active_session_id,
        'date': active_date,
        'occurred_at': _dt_mod.datetime.utcnow().isoformat() + 'Z',
    })
    # For planned sessions, also close the session calling loop in SRKB
    _session_mapping = SessionMapping.query.get(active_session_id)
    if _session_mapping and getattr(_session_mapping, 'session_type', 'ad_hoc') == 'planned':
        _enqueue_cb_outbox('session_attendance_recorded', {
            'mobile': mobile,
            'centre_id': selected_centre_id,
            'ae_session_id': active_session_id,
        })
    resp = {"message": msg}
    if client_offline_id:
        resp["_client_offline_id"] = client_offline_id
    regenerate_cache_files(selected_centre_id)
    return resp, 200


@app.route('/api/mark_attendance', methods=['POST'])
def mark_attendance():
    data = request.json
    # Phase 3: client may tag offline-queued records with a UUID so it can
    # match the server response back to its IndexedDB entry.
    client_offline_id = data.get('_client_offline_id')
    # Bug #2 fix: reject placeholder strings the Android spinner emits when
    # no session has been selected yet.  Treat them as "not provided" so the
    # server falls back to whichever session is actually active.
    requested_session_id = normalize_session_id(data.get('session_id'))
    # Native Android app may pass centre_id in body; fall back to session
    req_centre_id = str(data.get('centre_id') or '').strip() or None
    if not centre_id_authorized(req_centre_id):
        return jsonify({"error": "Forbidden"}), 403
    selected_centre_id = req_centre_id or get_selected_centre_id()
    # Youth/Congregation: the mark lands in the category slot the registerer's
    # toggle selected (defaults to youth). This drives both which active-session
    # slot we fall back to and the denormalized category stamped on the row.
    active_category = str(data.get('category') or 'youth').strip().lower()
    if active_category not in ('youth', 'congregation'):
        active_category = 'youth'
    # A session the client named is always honoured — that is the device's own choice and no
    # shared state may override it. Only when the client sent none does the server try to
    # resolve one, and then only if the answer is unambiguous.
    if requested_session_id:
        active_session_id = requested_session_id
    else:
        active_session_id, ambiguity = resolve_session_for_unspecified_mark(
            selected_centre_id, active_category, sanitize_date_input(data.get('date'))
        )
        if ambiguity:
            # PARK it — do not reject, and do not guess.
            #
            # Rejecting looks like the safe choice and is not. Deployed clients cannot be
            # changed: the Android app treats 409 as SUCCESS (postMarkAttendanceIdempotent),
            # so a 409 here would make the phone believe the mark landed and discard it, and
            # any other error code makes it re-queue a payload that will fail identically
            # forever, with nothing shown to the operator either way.
            #
            # So the submission is accepted and stored in the event log with no session
            # attached. Nothing lands in the wrong class, nothing is lost, and the record sits
            # on the server where the host can assign it — instead of stranded on a device.
            parked = record_attendance_mark(
                mobile=str(data.get('mobile') or '').strip(), session_id='',
                date=(sanitize_date_input(data.get('date')) or datetime.now().strftime("%Y-%m-%d")),
                centre_id=selected_centre_id, category=active_category,
                batch_name=str(data.get('batch_name') or '').strip(),
                remark=data.get('remark', ''), rounds=data.get('rounds', ''),
                cheating=("Yes" if str(data.get('cheating')).lower() in ['true', 'yes', '1'] else "No"),
                data=data, capture_ts=normalize_capture_ts(
                    data.get('client_marked_at') or data.get('capture_ts'), datetime.now()),
                received_at=datetime.now(), client_offline_id=client_offline_id,
                source='parked_no_session',
            )
            if parked:
                parked.superseded_reason = 'awaiting_session_assignment'
                db.session.commit()
            write_log(
                f"PARKED mark for {data.get('mobile')} at {selected_centre_id}: "
                f"{len(ambiguity['sessions_in_play'])} classes in play "
                f"({', '.join(ambiguity['sessions_in_play'])}) and the client named none. "
                f"Assign it from the host dashboard."
            )
            return jsonify({
                "message": "Recorded, but no class was specified — assign it from the host page",
                "pending_session": True,
                "sessions_in_play": ambiguity["sessions_in_play"],
                **({"_client_offline_id": client_offline_id} if client_offline_id else {}),
            }), 200

    if not active_session_id:
        return jsonify({"error": "No active session set globally."}), 400

    # The session's own date comes FIRST: a mark belongs to the day its session is for,
    # not the day the client's header happened to be showing. See resolve_session_date().
    active_date = (resolve_session_date(active_session_id, selected_centre_id)
                   or sanitize_date_input(data.get('date'))
                   or sanitize_date_input(get_active_session(selected_centre_id, active_category).get('date'))
                   or datetime.now().strftime("%Y-%m-%d"))
    active_batch_name = str(data.get('batch_name') or get_active_session(selected_centre_id, active_category).get('batch_name') or "").strip()
    active_batch_id = data.get('batch_id') or get_active_session(selected_centre_id, active_category).get('batch_id')
    if not active_batch_name and active_batch_id:
        try:
            batch_lookup = Batch.query.filter_by(id=int(active_batch_id), centre_id=selected_centre_id).first()
            if batch_lookup:
                active_batch_name = batch_lookup.name
        except Exception:
            pass
    active_session_name = str(data.get('session_name') or active_session_id or "").strip()
    server_timestamp = datetime.now()
        
    mobile = data.get('mobile')
    if not mobile:
        return jsonify({"error": "Mobile number is required"}), 400
    mobile = str(mobile).strip()
    if not re.fullmatch(r"\d{10}", mobile):
        return jsonify({"error": "Mobile number must be exactly 10 digits"}), 400
    
    # Session metadata from request
    remark_val = data.get('remark', '')
    rounds_val = data.get('rounds', '')
    # Handle "cheating" flexibly (bool or "Yes"/"No")
    cheating_input = data.get('cheating')
    cheating_val = "Yes" if str(cheating_input).lower() in ['true', 'yes', '1'] else "No"

    # Validate rounds if provided
    if rounds_val and str(rounds_val).strip():
        try:
            rounds_int = int(rounds_val)
            if rounds_int < 0 or rounds_int > 99:
                return jsonify({"error": "Rounds must be between 0 and 99"}), 400
        except (ValueError, TypeError):
            return jsonify({"error": "Rounds must be a whole number"}), 400
        
    payload, status = persist_attendance(
        mobile,
        data=data,
        selected_centre_id=selected_centre_id,
        active_session_id=active_session_id,
        active_date=active_date,
        active_batch_name=active_batch_name,
        active_session_name=active_session_name,
        active_category=active_category,
        remark_val=remark_val,
        rounds_val=rounds_val,
        cheating_val=cheating_val,
        server_timestamp=server_timestamp,
        client_offline_id=client_offline_id,
    )
    return jsonify(payload), status


@app.route('/api/parked_marks', methods=['GET'])
def parked_marks():
    """Marks accepted but not yet attached to a class.

    A mark is parked when a client sent no session_id while two or more classes were running,
    so the server could not resolve one without guessing. Parking keeps the operator's input —
    the alternative is a wrong-class mark, which is invisible, or a rejection the deployed
    Android client would read as success and discard.

    These are not errors and not lost data; they are attendance awaiting one decision.
    """
    centre_id = request.args.get('centre_id') or get_selected_centre_id()
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403
    on_date = sanitize_date_input(request.args.get('date')) or datetime.now().strftime("%Y-%m-%d")

    rows = AttendanceMark.query.filter_by(
        centre_id=centre_id, session_id='', applied=False, date=on_date
    ).order_by(AttendanceMark.received_at.asc()).all()

    return jsonify({
        "centre_id": centre_id,
        "date": on_date,
        "count": len(rows),
        "sessions_in_play": sessions_in_play(centre_id, 'youth', on_date),
        "marks": [{
            "id": r.id, "mobile": r.mobile, "name": r.name, "category": r.category,
            "batch_name": r.batch_name, "remark": r.remark, "rounds": r.rounds,
            "device_id": r.device_id, "marked_by": r.marked_by,
            "captured_at": r.capture_ts,
            "received_at": r.received_at.isoformat() if r.received_at else None,
        } for r in rows],
    })


@app.route('/api/parked_marks/assign', methods=['POST'])
def assign_parked_mark():
    """Attach a parked mark to a class, then run it through the normal marking path.

    Deliberately routed through persist_attendance rather than writing an Attendance row here.
    Marking someone present is not one insert — it is the workbook mirror, the per-centre Sheet
    append, the Excel refresh, the SRKB webhook and outbox, and the cache regeneration. A second
    copy of that list would drift, and every past divergence of exactly this kind has surfaced as
    attendance that exists in one system and not another.
    """
    data = request.json or {}
    mark_id = data.get('mark_id')
    target_session = normalize_session_id(data.get('session_id'))
    if not mark_id or not target_session:
        return jsonify({"error": "mark_id and session_id are required"}), 400

    mark = AttendanceMark.query.get(mark_id)
    if not mark:
        return jsonify({"error": "Mark not found"}), 404
    if not centre_id_authorized(mark.centre_id):
        return jsonify({"error": "Forbidden"}), 403
    if mark.applied or mark.session_id:
        return jsonify({"error": "That mark is already assigned"}), 409

    ctx = resolve_storage_context_for_session(target_session)
    payload, status = persist_attendance(
        mark.mobile,
        # Carry the ORIGINAL capture time through, so a parked mark assigned hours later still
        # resolves by when it was actually taken rather than by when someone got round to it.
        data={"name": mark.name, "address": mark.address, "occupation": mark.occupation,
              "device_id": mark.device_id, "marked_by": mark.marked_by,
              "capture_ts": mark.capture_ts, "_source": "parked_assignment"},
        selected_centre_id=mark.centre_id,
        active_session_id=target_session,
        active_date=mark.date,
        active_batch_name=(ctx.get("batch_name") or mark.batch_name or ""),
        active_session_name=target_session,
        active_category=(mark.category or 'youth'),
        remark_val=mark.remark or '',
        rounds_val=mark.rounds or '',
        cheating_val=mark.cheating or 'No',
        server_timestamp=datetime.now(),
    )

    if status in (200, 409):
        mark.session_id = target_session
        mark.applied = True
        mark.superseded_reason = None
        db.session.commit()
        write_log(f"Parked mark {mark.id} ({mark.mobile}) assigned to session '{target_session}'.")
    return jsonify({"assigned": status in (200, 409), "result": payload}), status


# ── Remark and address lists ─────────────────────────────────────────────────
#
# Both lists are per-centre now: a row with centre_id NULL is shared by every centre, a row with a
# centre_id belongs to that centre alone. A reader sees the shared rows plus its own; a centre admin
# may add to and edit its own, and only the host may touch a shared row.
#
# Every existing row is shared, so day-one behaviour is identical to the global lists these replaced.

def _visible_options(model, centre_id):
    """Shared rows first, then this centre's own — the order the pickers render."""
    return (
        model.query
        .filter(db.or_(model.centre_id.is_(None), model.centre_id == centre_id))
        .order_by(model.centre_id.isnot(None), model.id)
        .all()
    )


def _target_centre_for_new_option(is_host, centre_id):
    """
    Where a newly created row belongs.

    A host creating without naming a centre is the console/host behaviour that has always existed,
    and that has always meant "everyone", so it keeps meaning that. A centre admin can only ever
    create for their own centre.
    """
    if not is_host:
        return centre_id
    body = request.get_json(silent=True) or {}
    explicit = str(body.get('centre_id') or '').strip()
    return explicit or None


@app.route('/api/remarks', methods=['GET'])
def get_remarks():
    centre_id = get_request_centre_id()
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403
    return jsonify([r.to_dict() for r in _visible_options(RemarkOption, centre_id)])

@app.route('/api/remarks', methods=['POST'])
def add_remark():
    error, status, is_host, centre_id = authorize_option_write()
    if error is not None:
        return error, status
    data = request.json or {}
    text = data.get('text', '').strip()
    if not text:
        return jsonify({"error": "Text is required"}), 400
    target_centre = _target_centre_for_new_option(is_host, centre_id)
    if RemarkOption.query.filter_by(text=text, centre_id=target_centre).first():
        return jsonify({"error": "Remark already exists"}), 409
    remark = RemarkOption(text=text, centre_id=target_centre)
    db.session.add(remark)
    db.session.commit()
    return jsonify(remark.to_dict()), 201

@app.route('/api/remarks/<int:remark_id>', methods=['PUT'])
def update_remark(remark_id):
    remark = RemarkOption.query.get_or_404(remark_id)
    error, status, _is_host, _centre_id = authorize_option_write(remark)
    if error is not None:
        return error, status
    data = request.json or {}
    text = data.get('text', '').strip()
    if not text:
        return jsonify({"error": "Text is required"}), 400
    clash = RemarkOption.query.filter_by(text=text, centre_id=remark.centre_id).first()
    if clash and clash.id != remark.id:
        return jsonify({"error": "Remark already exists"}), 409
    remark.text = text
    db.session.commit()
    return jsonify(remark.to_dict())

@app.route('/api/remarks/<int:remark_id>', methods=['DELETE'])
def delete_remark(remark_id):
    remark = RemarkOption.query.get_or_404(remark_id)
    error, status, _is_host, _centre_id = authorize_option_write(remark)
    if error is not None:
        return error, status
    db.session.delete(remark)
    db.session.commit()
    return jsonify({"success": True})

@app.route('/api/addresses', methods=['GET'])
def get_addresses():
    centre_id = get_request_centre_id()
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403
    return jsonify([a.to_dict() for a in _visible_options(AddressOption, centre_id)])

@app.route('/api/addresses', methods=['POST'])
def add_address():
    error, status, is_host, centre_id = authorize_option_write()
    if error is not None:
        return error, status
    data = request.json or {}
    text = data.get('text', '').strip()
    if not text:
        return jsonify({"error": "Text is required"}), 400
    target_centre = _target_centre_for_new_option(is_host, centre_id)
    if AddressOption.query.filter_by(text=text, centre_id=target_centre).first():
        return jsonify({"error": "Address already exists"}), 409
    addr = AddressOption(text=text, centre_id=target_centre)
    db.session.add(addr)
    db.session.commit()
    return jsonify(addr.to_dict()), 201

@app.route('/api/addresses/<int:addr_id>', methods=['PUT'])
def update_address(addr_id):
    addr = AddressOption.query.get_or_404(addr_id)
    error, status, _is_host, _centre_id = authorize_option_write(addr)
    if error is not None:
        return error, status
    data = request.json or {}
    text = data.get('text', '').strip()
    if not text:
        return jsonify({"error": "Text is required"}), 400
    clash = AddressOption.query.filter_by(text=text, centre_id=addr.centre_id).first()
    if clash and clash.id != addr.id:
        return jsonify({"error": "Address already exists"}), 409
    addr.text = text
    db.session.commit()
    return jsonify(addr.to_dict())

@app.route('/api/addresses/<int:addr_id>', methods=['DELETE'])
def delete_address(addr_id):
    addr = AddressOption.query.get_or_404(addr_id)
    error, status, _is_host, _centre_id = authorize_option_write(addr)
    if error is not None:
        return error, status
    db.session.delete(addr)
    db.session.commit()
    return jsonify({"success": True})

@app.route('/api/recent_logs', methods=['GET'])
def get_recent_logs():
    """Returns the last 15 lines of the server log file for the mini-log box."""
    if not is_host_device() and session.get('role') != 'host':
        return jsonify([])
    if not os.path.exists(LOG_PATH): return jsonify([])
    try:
        with open(LOG_PATH, 'r', encoding='utf-8') as f:
            lines = f.readlines()
            return jsonify([ln.strip() for ln in lines[-15:]])
    except: return jsonify([])

@app.route('/api/devices', methods=['GET', 'POST'])
def handle_devices():
    """GET: List devices. POST: Toggle device active status."""
    global CONNECTED_DEVICES
    if not is_host_device() and session.get('role') != 'host':
        return jsonify({})
        
    if request.method == 'POST':
        data = request.json
        target_ip = data.get('ip')
        new_status = data.get('active')
        if target_ip in CONNECTED_DEVICES:
            # Special check to NEVER block the 127.0.0.1 or local machine IP if we are actually at the machine
            if target_ip in ['127.0.0.1', '::1', 'localhost']:
                return jsonify({"error": "Cannot disable the host device loopback"}), 400
            
            # Check if this IP is currently the one identifying as host (LAN IP)
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                s.connect(("8.8.8.8", 80))
                host_ip = s.getsockname()[0]
                s.close()
                if target_ip == host_ip:
                    return jsonify({"error": "Cannot disable the host device LAN IP"}), 400
            except: pass

            CONNECTED_DEVICES[target_ip]['active'] = bool(new_status)
            write_log(f"Device {target_ip} ({CONNECTED_DEVICES[target_ip]['name']}) set to active={new_status}")
            return jsonify({"success": True})
            
    devices_copy = {}
    my_lan_ip = ""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        my_lan_ip = s.getsockname()[0]
        s.close()
    except: pass

    now = time.time()
    for ip, info in CONNECTED_DEVICES.items():
        # Only show devices seen in the last 30 seconds
        last_seen_ts = info.get('last_seen_ts', 0)
        if now - float(last_seen_ts) > 30:
            continue
            
        is_host = (ip in ['127.0.0.1', '::1', 'localhost', my_lan_ip])
        # Also check if they don't have proxy headers as backup
        # Actually, let's just use the IP check as it's most reliable for identifying "self" in the list
        diag = info.copy()
        diag['is_host'] = is_host
        devices_copy[ip] = diag

    return jsonify(devices_copy)

@app.route('/api/live_count', methods=['GET'])
def live_count():
    # Attempt to get local filter from query parameters, else use global
    centre_id = request.args.get('centre_id') or get_selected_centre_id()
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403
    category = (request.args.get('category') or 'youth').strip().lower()
    session_id = request.args.get('session_id') or get_active_session(centre_id, category).get('session_id')
    date_str = request.args.get('date') or get_active_session(centre_id, category).get('date')
    batch_filter = request.args.get('batch')

    query = Attendance.query
    query = query.filter_by(centre_id=centre_id)
    if request.args.get('category'):
        query = query.filter_by(category=category)
    if session_id:
        query = query.filter_by(session_id=session_id)
    if date_str:
        query = query.filter_by(date=date_str)
    if batch_filter:
        query = query.filter_by(batch_name=batch_filter)

    count = query.count()
    return jsonify({"count": count, "session_id": session_id})

@app.route('/api/registration_counts', methods=['GET'])
def registration_counts():
    """How many people are registered - the number the web Registration tab had no way to show.

    WHY THIS EXISTS. applyRegistererModeUI() in registerer.html hides BOTH the live counter and
    the attendance-records table the moment the registerer switches to Registration mode, because
    both of those are fed by the active session and a registration drive has none. So a web
    registerer filling a festival batch gets a toast per submission and nothing else - no running
    total, no list - while the Android app shows both. During a festival that is the difference
    between "we have 137 so far" and having to ask someone to open the workbook.

    Deliberately NOT the calling backend's /dashboard/summary, which is where the app's
    "Registered" card gets its number. That is a different backend the web page holds no
    credentials for, it is centre+category scoped with no batch breakdown, and it would make a
    Flask page depend on CB being up to render a count of rows Flask itself owns.

    COUNTING RULE: distinct PEOPLE, keyed on the last ten digits - not rows. CentreRoster's
    unique key is (mobile, centre_id, scope_key), so one person carries a centre-level row PLUS
    one row per batch; and because the mobile column is not normalised at write time the same
    person can be stored as "+91 98350 00001" and as "9835000001" and occupy two rows at the
    same scope. Counting rows over-reports on both axes, and this is the number a registerer
    reads out loud, so it has to be people.

    Auth is the ordinary centre check. Unlike /api/roster/lookup, which exists precisely to read
    past the caller's own centre, this reads one centre's own roster and has no reason to skip
    centre_id_authorized().
    """
    centre_id = (request.args.get('centre_id') or '').strip() or get_selected_centre_id()
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403

    RECENT_LIMIT = 25
    category = (request.args.get('category') or '').strip().lower()
    batch_id_raw = (request.args.get('batch_id') or '').strip()

    # A batch_id that is not this centre's resolves to None rather than 403 or 404: the card is
    # informational, and a stale batch id in a browser tab should degrade to the centre-wide
    # total, not blank the panel out.
    batch = None
    if batch_id_raw:
        try:
            batch = Batch.query.filter_by(id=int(batch_id_raw), centre_id=centre_id).first()
        except (TypeError, ValueError):
            batch = None

    # A batch belonging to the OTHER track is treated as no batch at all.
    #
    # The page keeps the selected batch in window.AttendEaseBatchState, which survives a
    # youth<->congregation switch, so the browser really does send pairs like
    # category=congregation with the youth Festival batch. Answering that literally is what
    # produced the reported bug: the row filter keeps only congregation people, the batch
    # filter keeps only people in a youth batch, the two sets cannot intersect, and the card
    # rendered 0 over the name 'Festival' with an empty table - indistinguishable from
    # 'nobody has registered'. Degrading to the centre-wide answer is the same rule already
    # applied to a foreign batch id just above, and it means a browser that never reloads
    # still recovers on its next refresh tick.
    #
    # Both sides must be non-empty to reject: an absent category is 'no opinion', not a
    # conflict, and a batch that somehow carries no category must not be silently dropped.
    if batch is not None and category and (batch.category or '').strip().lower():
        if (batch.category or '').strip().lower() != category:
            batch = None

    q = CentreRoster.query.filter_by(centre_id=centre_id, status='active')
    if category in ('youth', 'congregation'):
        # Centre-level rows carry a NULL category for the legacy people (see the model comment),
        # and they are excluded from a category-filtered total on purpose: this number answers
        # "who is on this track", and a row that never declared a track is not evidence for it.
        q = q.filter(CentreRoster.category == category)
    rows = q.all()

    # One helper, module level, so this card and the day-sheet list can never drift.
    last_ten = ae_last_ten

    today_str = datetime.now().strftime('%Y-%m-%d')
    centre_people = set()
    batch_people = set()
    today_people = set()
    recent = {}

    for r in rows:
        key = last_ten(r.mobile)
        if not key:
            continue
        centre_people.add(key)
        in_batch = batch is not None and r.batch_id == batch.id
        if in_batch:
            batch_people.add(key)
        stamp = r.created_at or datetime.min
        # EVERYTHING BELOW THIS LINE IS SCOPED TO THE SELECTED BATCH - today's count and the
        # recent list both. The card puts the batch name over one number and "Today" over the
        # next, so "Today" has to mean today IN THAT BATCH. Counting today centre-wide here
        # rendered "Festival 153" beside "Today 156", which reads as the batch having lost
        # three people rather than as two different questions being answered side by side.
        # With no batch selected both fall back to the whole centre, matching the third card.
        if batch is not None and not in_batch:
            continue
        if r.created_at and r.created_at.strftime('%Y-%m-%d') == today_str:
            today_people.add(key)
        prev = recent.get(key)
        if prev is None or stamp > prev[0]:
            recent[key] = (stamp, {
                "mobile": key,
                "name": (r.name or '').strip() or 'Unknown',
                "batch_name": (r.batch_name or '').strip(),
                "registered_at": r.created_at.strftime('%d %b %H:%M') if r.created_at else '',
            })

    ordered = sorted(recent.values(), key=lambda item: item[0], reverse=True)
    return jsonify({
        "centre_id": centre_id,
        "category": category or None,
        "batch_id": batch.id if batch else None,
        "batch_name": batch.name if batch else None,
        "batch_total": len(batch_people) if batch else None,
        "centre_total": len(centre_people),
        "today_total": len(today_people),
        "recent": [item[1] for item in ordered[:RECENT_LIMIT]],
        "recent_truncated": len(ordered) > RECENT_LIMIT,
    })


@app.route('/api/field_corpus', methods=['GET'])
def field_corpus():
    """The centre's own address / occupation values, ranked by how often it uses them.

    WHY THIS EXISTS. The web registration form's address box reads ONLY /api/addresses - the
    short admin-curated dictionary - and the occupation box had no suggestions at all, so a web
    registerer typed every locality out in full while an app registerer picked it from a list.
    The values were already in the system (every CentreRoster row carries address and occupation);
    they were simply never offered on the web.

    This is the server-side twin of FieldSuggestions.buildCorpus in the Android app, and it
    returns the SAME thing the app computes on-device: distinct values, case- and
    whitespace-insensitive, ranked by centre-wide frequency. Computed here rather than in the
    browser on purpose - the app already holds the whole roster in memory, but the web page does
    not, and making it pull the 300-480 KB roster.json just to count strings would be a far
    heavier page load than the few KB this returns.

    The displayed label is the MOST COMMON original casing rather than the first one seen, so a
    single stray "SAMPLE COLONY" cannot rename a locality that 200 rows spell normally.
    """
    centre_id = (request.args.get('centre_id') or '').strip() or get_selected_centre_id()
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403

    field = (request.args.get('field') or 'address').strip().lower()
    if field not in ('address', 'occupation'):
        return jsonify({"error": "field must be address or occupation"}), 400

    MAX_ITEMS = 300
    MAX_LABEL = 120
    # Values that are technically present but mean "nobody filled this in". Same set the app
    # uses, kept in step deliberately - a suggestion list that differs between the two clients
    # is a support question waiting to happen.
    JUNK = {'', '-', '--', 'na', 'n/a', 'nil', 'none', 'null', 'no', 'x', '.'}

    category = (request.args.get('category') or '').strip().lower()
    q = CentreRoster.query.filter_by(centre_id=centre_id, status='active')
    if category in ('youth', 'congregation'):
        q = q.filter(CentreRoster.category == category)

    column = CentreRoster.address if field == 'address' else CentreRoster.occupation
    spellings = {}
    for (raw,) in q.with_entities(column).all():
        label = ' '.join((raw or '').split())
        if not label or len(label) > MAX_LABEL:
            continue
        key = label.lower()
        if key in JUNK:
            continue
        spellings.setdefault(key, {})
        spellings[key][label] = spellings[key].get(label, 0) + 1

    items = []
    for key, forms in spellings.items():
        best = max(forms.items(), key=lambda kv: kv[1])[0]
        items.append({"label": best, "count": sum(forms.values())})
    items.sort(key=lambda it: (-it["count"], it["label"].lower()))

    return jsonify({
        "field": field,
        "centre_id": centre_id,
        "category": category or None,
        "items": items[:MAX_ITEMS],
    })


# ---------------------------------------------------------------------------------------------
# REGISTRATION DAY-SHEETS
#
# The Lists tab lists one sheet per SESSION and fills it from build_attendance_rows_for_batch(),
# which reads the `attendance` table. Registrations live in centre_roster and have NO session link
# at all - the column does not exist - so a registration drive is structurally invisible there.
# Batch 20 is the proof: 434 registrations, 55 attendance rows, and a 56-row master workbook.
#
# So registrations get their OWN list rather than being folded into the attendance exports.
# Widening build_attendance_rows_for_batch() would have taken batch 20 from 55 rows to 434+ and
# silently rewritten every historical export at every centre.
#
# Grouped BY DAY, per centre, per track - the only grouping this data actually supports. A
# registration row knows its batch and its timestamp; it never knows a session.
# ---------------------------------------------------------------------------------------------

def _registration_day_rows(centre_id, category):
    """Active roster rows for this centre+track, each tagged with its day bucket."""
    q = CentreRoster.query.filter_by(centre_id=centre_id, status='active')
    if category in ('youth', 'congregation'):
        q = q.filter(CentreRoster.category == category)
    out = []
    for r in q.all():
        key = ae_last_ten(r.mobile)
        if not key:
            continue
        # created_at is a 26-char TEXT column. Rows without one are bucketed as 'unknown' rather
        # than dropped - centre_a has none, but other centres carry legacy rows, and a person
        # must never vanish from a list merely because their timestamp is missing.
        day = r.created_at.strftime('%Y-%m-%d') if r.created_at else 'unknown'
        out.append((day, key, r))
    return out


def _registration_sheet_labels(centre_id, category, days):
    """Custom names for these days, falling back to a readable date."""
    rows = RegistrationSheetName.query.filter_by(
        centre_id=centre_id, category=category).all()
    custom = {row.day: row.name for row in rows}
    labels = {}
    for day in days:
        if day in custom:
            labels[day] = custom[day]
        elif day == 'unknown':
            labels[day] = 'Undated registrations'
        else:
            try:
                labels[day] = datetime.strptime(day, '%Y-%m-%d').strftime('%d %b %Y')
            except (TypeError, ValueError):
                labels[day] = day
    return labels


def _registration_people_for_day(centre_id, category, day):
    """Distinct people registered on one day, newest row per person, newest person first."""
    best = {}
    for d, key, r in _registration_day_rows(centre_id, category):
        if d != day:
            continue
        stamp = r.created_at or datetime.min
        prev = best.get(key)
        if prev is None or stamp > prev[0]:
            best[key] = (stamp, r)
    return [item[1] for item in sorted(best.values(), key=lambda i: i[0], reverse=True)]


@app.route('/api/registration_sheets', methods=['GET'])
def registration_sheets():
    """One entry per day on which somebody registered at this centre, in this track."""
    centre_id = (request.args.get('centre_id') or '').strip() or get_selected_centre_id()
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403
    category = (request.args.get('category') or '').strip().lower()

    buckets = {}
    for day, key, r in _registration_day_rows(centre_id, category):
        buckets.setdefault(day, set()).add(key)

    labels = _registration_sheet_labels(centre_id, category, list(buckets.keys()))
    real_days = sorted([d for d in buckets if d != 'unknown'], reverse=True)
    days = real_days + (['unknown'] if 'unknown' in buckets else [])

    return jsonify([
        {
            "day": d,
            "name": labels.get(d, d),
            # DISTINCT PEOPLE, the same rule /api/registration_counts uses, via the same helper -
            # so this list and the registration card can never disagree about the same day.
            "count": len(buckets[d]),
        }
        for d in days
    ])


@app.route('/api/registration_sheet_content', methods=['GET'])
def registration_sheet_content():
    """The people registered on one day."""
    centre_id = (request.args.get('centre_id') or '').strip() or get_selected_centre_id()
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403
    category = (request.args.get('category') or '').strip().lower()
    day = (request.args.get('day') or '').strip()
    if not day:
        return jsonify({"error": "day is required"}), 400

    people = _registration_people_for_day(centre_id, category, day)
    labels = _registration_sheet_labels(centre_id, category, [day])
    return jsonify({
        "day": day,
        "name": labels.get(day, day),
        "count": len(people),
        "rows": [
            {
                "name": (r.name or '').strip() or 'Unknown',
                "mobile": ae_last_ten(r.mobile),
                "address": (r.address or '').strip(),
                "occupation": (r.occupation or '').strip(),
                "batch_name": (r.batch_name or '').strip(),
                "registered_at": r.created_at.strftime('%d %b %Y %H:%M') if r.created_at else '',
            }
            for r in people
        ],
    })


@app.route('/api/registration_sheet_rename', methods=['POST'])
def registration_sheet_rename():
    """Rename a day-sheet. Display name only - the day remains the identity."""
    data = request.json or {}
    centre_id = (data.get('centre_id') or '').strip() or get_selected_centre_id()
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403
    category = (data.get('category') or '').strip().lower()
    day = (data.get('day') or '').strip()
    name = (data.get('name') or '').strip()
    if not day:
        return jsonify({"error": "day is required"}), 400
    if len(name) > 120:
        return jsonify({"error": "Name is too long (max 120 characters)"}), 400

    row = RegistrationSheetName.query.filter_by(
        centre_id=centre_id, category=category, day=day).first()

    if not name:
        # An empty name clears back to the default label rather than storing a blank.
        if row:
            db.session.delete(row)
            db.session.commit()
        return jsonify({
            "ok": True,
            "name": _registration_sheet_labels(centre_id, category, [day]).get(day),
        })

    if row:
        row.name = name
    else:
        db.session.add(RegistrationSheetName(
            centre_id=centre_id, category=category, day=day, name=name))
    db.session.commit()
    return jsonify({"ok": True, "name": name})


@app.route('/api/download_registration_sheet', methods=['GET'])
def download_registration_sheet():
    """One day's registrations as .xlsx, generated in memory.

    Deliberately does NOT touch the live workbook or Server file/, so nothing here can disturb the
    attendance exports or what sheet-autosync publishes.
    """
    centre_id = (request.args.get('centre_id') or '').strip() or get_selected_centre_id()
    if not centre_id_authorized(centre_id):
        return "Forbidden", 403
    category = (request.args.get('category') or '').strip().lower()
    day = (request.args.get('day') or '').strip()
    if not day:
        return "day is required", 400

    people = _registration_people_for_day(centre_id, category, day)
    label = _registration_sheet_labels(centre_id, category, [day]).get(day, day)

    wb = Workbook()
    ws = wb.active
    ws.title = "Registrations"
    ws.append(["Name", "Number", "Address", "Occupation", "Batch", "Registered At"])
    for r in people:
        ws.append([
            (r.name or '').strip(),
            ae_last_ten(r.mobile),
            (r.address or '').strip(),
            (r.occupation or '').strip(),
            (r.batch_name or '').strip(),
            r.created_at.strftime('%d %b %Y %H:%M') if r.created_at else '',
        ])
    buf = io.BytesIO()
    wb.save(buf)
    buf.seek(0)
    safe = ''.join(ch if (ch.isalnum() or ch in ' -_') else '_' for ch in label).strip() or day
    return send_file(
        buf,
        mimetype='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        as_attachment=True,
        download_name=safe + '.xlsx',
    )


@app.route('/api/session_sync_status', methods=['GET'])
def session_sync_status():
    """Do this session's sheet and the database agree? Read-only by default.

    Backs the sync badge on the registerer screen, which is the whole point of
    the feature: the Current tab and the Lists tab read two different sources,
    so a registerer needs to be told when those two sources disagree instead of
    discovering it later. `?repair=1` additionally runs the additive repair.
    """
    session_id = (request.args.get('session_id') or '').strip()
    if not session_id:
        return jsonify({"error": "session_id is required"}), 400
    centre_id = request.args.get('centre_id') or get_selected_centre_id()
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403
    try:
        if request.args.get('repair') in ('1', 'true', 'yes'):
            report, _ = heal_session_sync(session_id, centre_id, source="api")
        else:
            # Cached: the badge polls this from every registerer device, and a
            # diagnostic must never cost the marking screen anything.
            report = audit_session_sync_cached(session_id, centre_id)
    except Exception as e:
        write_log(f"session_sync_status failed for {session_id!r}: {e}")
        return jsonify({"error": "sync check failed", "detail": str(e)}), 500

    # Trimmed for the UI: counts drive the badge, and a short sample keeps the
    # response small on a session with hundreds of rows.
    return jsonify({
        "session_id": session_id,
        "centre_id": centre_id,
        "in_sync": report["in_sync"],
        "cached": report.get("cached", False),
        "sheet_exists": report["sheet_exists"],
        "summary": report["summary"],
        "db_count": report["db_count"],
        "excel_count": report["excel_count"],
        "healed_rows": report.get("healed_rows", 0),
        "counts": {
            "missing_in_excel": len(report[session_sync.MISSING_IN_EXCEL]),
            "missing_in_db": len(report[session_sync.MISSING_IN_DB]),
            "field_mismatch": len(report[session_sync.FIELD_MISMATCH]),
            "duplicate_in_excel": len(report[session_sync.DUPLICATE_IN_EXCEL]),
        },
        "samples": {
            "missing_in_excel": report[session_sync.MISSING_IN_EXCEL][:5],
            "missing_in_db": report[session_sync.MISSING_IN_DB][:5],
            "field_mismatch": report[session_sync.FIELD_MISMATCH][:5],
            "duplicate_in_excel": report[session_sync.DUPLICATE_IN_EXCEL][:5],
        },
    })


@app.route('/api/show_attendance', methods=['GET'])
def show_attendance():
    centre_id = request.args.get('centre_id') or get_selected_centre_id()
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403
    category = (request.args.get('category') or 'youth').strip().lower()

    # scope=all means "the whole centre" — do NOT narrow to whatever class happens to be running.
    #
    # With no session_id or date this endpoint silently substitutes the ACTIVE session and its
    # date. That default is right for the screens asking "who is here now", and badly wrong for
    # the one caller that wants the centre's member list to power attendance lookup: between
    # classes it returns everybody, but the moment a class is made active it returns only the
    # people already marked in that class — precisely when a registerer is searching for the next
    # person at the door. There was no way to opt out, because an empty session_id is falsy and
    # falls straight back into the default.
    #
    # Merging the roster on the client is NOT a substitute: roster.json holds people who were
    # REGISTERED, and the attendance-only population is large (769 at Centre B by the app's
    # own measurement), so those people simply vanish from lookup while a class is live.
    #
    # The caller now says what it wants. Every existing caller passes nothing and behaves exactly
    # as before; the app's bulk member load passes scope=all and gets the centre.
    scope_all = (request.args.get('scope') or '').strip().lower() in ('all', 'centre')
    if scope_all:
        session_id = request.args.get('session_id') or None
        date_str = request.args.get('date') or None
    else:
        session_id = request.args.get('session_id') or get_active_session(centre_id, category).get('session_id')
        date_str = request.args.get('date') or get_active_session(centre_id, category).get('date')
    batch_filter = request.args.get('batch')

    query = Attendance.query
    query = query.filter_by(centre_id=centre_id)
    if request.args.get('category'):
        query = query.filter_by(category=category)
    if session_id:
        query = query.filter_by(session_id=session_id)
    if date_str:
        query = query.filter_by(date=date_str)
    if batch_filter:
        query = query.filter_by(batch_name=batch_filter)

    attendances = query.all()
    
    result = []
    for att in attendances:
        user = att.user
        if not user:
            continue
        # Historical session-specific data prioritizes Attendance fields
        # falling back to User fields if needed (though new system populated both)
        result.append({
            "mobile": user.mobile,
            "name": user.name,
            "address": user.address,
            "occupation": user.occupation,
            "session_id": att.session_id,
            "batch_name": att.batch_name,
            "rounds": att.rounds or user.rounds,
            "cheating": att.cheating or user.cheating,
            "remark": att.remark or user.remark,
            "centre_id": getattr(att, "centre_id", DEFAULT_CENTRE_ID),
            "category": getattr(att, "category", "youth"),
            "time_marked": att.timestamp.strftime("%Y-%m-%d %H:%M:%S") if att.timestamp else "N/A"
        })
        
    return jsonify(result)


@app.route('/api/centre_people', methods=['GET'])
def centre_people():
    """Return one record per distinct person who has ever attended this centre."""
    centre_id = request.args.get('centre_id') or get_selected_centre_id()
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403
    if not centre_id:
        return jsonify([])

    # All attendance rows for this centre, ordered oldest→newest so latest overwrites
    attendances = Attendance.query.filter_by(centre_id=centre_id).order_by(
        Attendance.timestamp.asc(), Attendance.id.asc()
    ).all()

    # Keep only the latest attendance record per mobile
    last_att: dict = {}
    for att in attendances:
        if att.mobile:
            last_att[att.mobile] = att

    if not last_att:
        return jsonify([])

    # Load user profiles for those mobiles
    user_map = {u.mobile: u for u in User.query.filter(User.mobile.in_(list(last_att.keys()))).all()}

    # Map batch names → Batch objects for this centre
    batch_names = {att.batch_name for att in last_att.values() if att.batch_name}
    batch_map = {b.name: b for b in Batch.query.filter(
        Batch.centre_id == centre_id, Batch.name.in_(batch_names)
    ).all()} if batch_names else {}

    result = []
    for mobile, att in sorted(last_att.items()):
        u = user_map.get(mobile)
        batch = batch_map.get(att.batch_name) if att.batch_name else None
        result.append({
            "mobile": mobile,
            "name": (u.name if u else None) or mobile,
            "address": (u.address if u else None) or "",
            "occupation": (u.occupation if u else None) or "",
            "rounds": att.rounds or (u.rounds if u else "") or "",
            "remark": att.remark or (u.remark if u else "") or "",
            "batch_name": att.batch_name or "",
            "batch_id": str(batch.id) if batch else "",
        })

    return jsonify(result)


@app.route('/api/search_mobile', methods=['GET'])
def search_mobile():
    query = request.args.get('q', '').strip()
    if not query:
        return jsonify([])

    # Search for mobiles starting with the query OR names containing the query
    # Prioritizing names that start with the query for better UX
    users_query = User.query.filter(
        (User.mobile.like(f"{query}%")) | (User.name.like(f"%{query}%"))
    )

    # User has no centre field — it's global. Without scoping, a registerer's
    # autocomplete could surface any mobile/name in the entire system regardless
    # of centre. Non-host callers are restricted to mobiles present in their
    # own centre's roster (CentreRoster); host keeps the unscoped search.
    # Youth/Congregation: the recommendation list is scoped centre-wise and then
    # category-wise. When the registerer is on the congregation track it must not
    # suggest youth registrants (and vice-versa). Category rides in as a query
    # param; anything else is treated as "no category filter" (legacy behaviour).
    category = (request.args.get('category') or '').strip().lower()
    category = category if category in ('youth', 'congregation') else ''

    if not (is_host_device() or session.get('role') == 'host'):
        caller_centre_id = get_selected_centre_id()
        if not caller_centre_id:
            return jsonify([])

        if category:
            # Attendance carries its own category column — filter directly.
            attendance_mobiles = {
                a.mobile for a in Attendance.query.filter_by(
                    centre_id=caller_centre_id, category=category
                ).all() if a.mobile
            }
            # CentreRoster has no category of its own; it inherits its batch's
            # category. Rows with no batch (or an unknown batch) are youth by the
            # system-wide default rule.
            batch_category = {
                b.id: ((b.category or 'youth').strip().lower())
                for b in Batch.query.filter_by(centre_id=caller_centre_id).all()
            }
            roster_mobiles = set()
            for r in CentreRoster.query.filter_by(centre_id=caller_centre_id).all():
                if not r.mobile:
                    continue
                row_category = batch_category.get(r.batch_id, 'youth') if r.batch_id else 'youth'
                if row_category == category:
                    roster_mobiles.add(r.mobile)
        else:
            roster_mobiles = {r.mobile for r in CentreRoster.query.filter_by(centre_id=caller_centre_id).all() if r.mobile}
            attendance_mobiles = {a.mobile for a in Attendance.query.filter_by(centre_id=caller_centre_id).all() if a.mobile}
        allowed_mobiles = roster_mobiles.union(attendance_mobiles)

        # Augment with what the CALLING BACKEND knows about this centre.
        #
        # ROOT FIX 2026-08-08. "Belongs to this centre" was defined as CentreRoster + Attendance +
        # THIS CALLER'S OWN session assignments. The calling backend's own record of who is
        # registered at the centre was never consulted at all. So a person the system plainly knew
        # belonged here — registered through the calling side, or removed from a calling list, or
        # simply never rostered — produced NO suggestion when their number was typed into attendance
        # marking, and the operator had to retype every detail by hand. Found live on 2026-08-08:
        # of 275 people on a centre's own calling sheet, 123 could not be suggested.
        #
        # Walk-ins are normal here (7 of 21 attendees on 01-08-26 were never on the calling list),
        # and this system's rule is that FIRST ATTENDANCE IS REGISTRATION — so a person being absent
        # from the roster, or removed from a calling list, is no reason to refuse to suggest them.
        # Someone dropped from calling can still walk into class, and must be markable.
        #
        # Centre scoping is preserved exactly: this only ever adds people registered at the caller's
        # OWN centre, resolved by centre code.
        cb_db_path = os.environ.get("CALLING_BACKEND_DB_PATH", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "calling-backend", "prisma", "dev.db"))
        if os.path.exists(cb_db_path):
            try:
                import sqlite3
                # READ-ONLY: the attendance side must never write the calling database.
                conn = sqlite3.connect(f"file:{cb_db_path}?mode=ro", uri=True)
                cursor = conn.cursor()
                cursor.execute("SELECT id FROM centres WHERE code = ?", (caller_centre_id,))
                centre_row = cursor.fetchone()
                if centre_row:
                    cb_centre_id = centre_row[0]
                    # Everyone the calling backend has registered at this centre. Category-filtered
                    # when the registerer is on a track: the old code skipped the congregation track
                    # entirely on the grounds that "the calling backend has no youth/congregation
                    # concept" — it does, registrations carry their own category column, so the
                    # exclusion is no longer needed and congregation gets the same benefit.
                    if category:
                        cursor.execute(
                            "SELECT normalized_mobile FROM registrations "
                            "WHERE centre_id = ? AND lower(COALESCE(category, 'youth')) = ?",
                            (cb_centre_id, category),
                        )
                    else:
                        cursor.execute(
                            "SELECT normalized_mobile FROM registrations WHERE centre_id = ?",
                            (cb_centre_id,),
                        )
                    allowed_mobiles = allowed_mobiles.union(
                        {row[0] for row in cursor.fetchall() if row[0]}
                    )

                    # Batch members at this centre. Covers anyone attached to a batch without a
                    # registration row of their own — the attendance-origin people.
                    if category:
                        cursor.execute(
                            "SELECT mobile FROM batch_members WHERE centre_id = ? AND removed_at IS NULL "
                            "AND lower(COALESCE(category, 'youth')) = ?",
                            (cb_centre_id, category),
                        )
                    else:
                        cursor.execute(
                            "SELECT mobile FROM batch_members WHERE centre_id = ? AND removed_at IS NULL",
                            (cb_centre_id,),
                        )
                    allowed_mobiles = allowed_mobiles.union(
                        {row[0] for row in cursor.fetchall() if row[0]}
                    )

                # The caller's own session assignments, as before. Kept because an assignment can
                # point at a batch member outside the two queries above.
                #
                # Category-filtered since 2026-08-09. This union was the one branch here that
                # ignored the track entirely, so a caller holding assignments in both tracks
                # leaked their congregation people into a youth lookup and vice versa. The
                # batch member carries its own category, which is what makes the filter exact.
                if session.get('username'):
                    cursor.execute("SELECT id FROM users WHERE mobile = ?", (session['username'],))
                    user_row = cursor.fetchone()
                    if user_row:
                        if category:
                            cursor.execute("""
                                SELECT m.mobile
                                FROM session_call_assignments a
                                JOIN batch_members m ON a.batch_member_id = m.id
                                WHERE a.caller_id = ?
                                  AND lower(COALESCE(m.category, 'youth')) = ?
                            """, (user_row[0], category))
                        else:
                            cursor.execute("""
                                SELECT m.mobile
                                FROM session_call_assignments a
                                JOIN batch_members m ON a.batch_member_id = m.id
                                WHERE a.caller_id = ?
                            """, (user_row[0],))
                        allowed_mobiles = allowed_mobiles.union(
                            {row[0] for row in cursor.fetchall() if row[0]}
                        )
                conn.close()
            except Exception as e:
                write_log(f"Failed to query calling backend database: {e}")

        if not allowed_mobiles:
            return jsonify([])
        users_query = users_query.filter(User.mobile.in_(allowed_mobiles))

    users = users_query.order_by(
        # Custom order: names starting with query first, then others
        User.name.like(f"{query}%").desc(),
        User.name.asc()
    ).limit(10).all()

    return jsonify([{"mobile": u.mobile, "name": u.name, "address": u.address, "occupation": u.occupation} for u in users])

# ============================================================
# PHASE 3 — OFFLINE-FIRST SUPPORT ENDPOINTS
# ============================================================

def regenerate_cache_files(centre_id):
    """Physically writes static state.json, roster.json, roster_<batch>.json, and checksum.json."""
    if not centre_id:
        return
    with app.app_context():
        try:
            centre_id_str = str(centre_id).strip()
            
            # 1. State
            # get_active_session() with no category returns the YOUTH slot, so this file only
            # ever described youth. A congregation client reading it silently received the
            # youth session. Both slots are written now, and each carries its own counts.
            state = get_active_session(centre_id_str)
            batches = Batch.query.filter_by(centre_id=centre_id_str).order_by(Batch.created_at.desc()).all()
            # `category` added 2026-08-09: this list feeds the batch pickers, and without it a
            # youth registerer was offered the congregation batch (and the reverse). A client
            # that ignores the key keeps exactly the behaviour it had.
            batch_list = [{"id": b.id, "name": b.name,
                           "category": (b.category or 'youth').strip().lower()} for b in batches]

            def counts_for(sess):
                """Counts for ONE session — never for "the centre".

                A centre runs two classes at once, so a centre-wide count is the wrong number
                on at least one device. Scoping by session_id is what lets each device show
                its own class.
                """
                if not (sess.get("session_id") and sess.get("date")):
                    return 0, []
                records = Attendance.query.filter_by(
                    centre_id=centre_id_str,
                    session_id=sess["session_id"],
                    date=sess["date"],
                ).all()
                return len(records), [r.mobile for r in records]

            marked_today_count, marked_today_mobiles = counts_for(state)

            by_category = {}
            for cat in ('youth', 'congregation'):
                cat_state = get_active_session(centre_id_str, cat)
                c_count, c_mobiles = counts_for(cat_state)
                by_category[cat] = {
                    "session_state": cat_state,
                    "marked_today_count": c_count,
                    "marked_today_mobiles": c_mobiles,
                }

            state_data = {
                "centre_id": centre_id_str,
                "session_state": state,
                "batch_list": batch_list,
                "marked_today_count": marked_today_count,
                "marked_today_mobiles": marked_today_mobiles,
                # Per-category slots, so a congregation client no longer has to read the youth
                # numbers. A client that ignores this key keeps the exact behaviour it had.
                "by_category": by_category,
                "server_time": datetime.now().isoformat(),
            }

            # 2. Checksum
            # The ROSTER fingerprint is part of this on purpose. It used to be session_id|batch_id|
            # date|marked_today_count — nothing about who is ON the roster. So adding people to a
            # centre changed nothing the client could detect, and a running app kept serving
            # autocomplete from a roster snapshot taken at login. Seen live on 2026-08-08: 125
            # people were added mid-class and none of them could be suggested on a device that was
            # already open. Counting rows AND taking the newest updated_at catches additions,
            # removals and edits alike.
            roster_rows = CentreRoster.query.filter_by(centre_id=centre_id_str).count()
            roster_stamp = db.session.query(db.func.max(CentreRoster.updated_at)).filter(
                CentreRoster.centre_id == centre_id_str
            ).scalar()
            raw = (f"{state.get('session_id') or ''}|{state.get('batch_id') or ''}|"
                   f"{state.get('date') or ''}|{marked_today_count}|{roster_rows}|{roster_stamp or ''}")
            checksum_val = hashlib.md5(raw.encode()).hexdigest()[:12]
            checksum_data = {
                "centre_id": centre_id_str,
                "checksum": checksum_val
            }

            # 3. Roster (full and per batch)
            query = CentreRoster.query.filter_by(centre_id=centre_id_str, status="active")
            students = query.order_by(CentreRoster.name).all()

            # Each row's OWN category, taken from its batch. CentreRoster has no category column
            # of its own, so without this the payload is category-blind and the client cannot
            # tell a youth member from a congregation one. The Android client's response to that
            # was to stamp every roster row with whatever track the user had selected, which made
            # its own category filter a no-op and put all 365 congregation people into youth
            # lookups (and the whole BG roster into congregation lookups). Same inheritance rule
            # used everywhere else: no batch, or an unknown batch, means youth.
            batch_category = {
                b.id: ((b.category or 'youth').strip().lower()) for b in batches
            }

            roster_data = [{
                "mobile": s.mobile,
                "name": s.name,
                "address": s.address,
                "occupation": s.occupation,
                "rounds": s.rounds,
                "remark": s.remark,
                "batch_id": s.batch_id,
                "batch_name": s.batch_name,
                "category": batch_category.get(s.batch_id, 'youth') if s.batch_id else 'youth',
                # Classes attended before this system existed, imported from a centre's old
                # records. Its own field rather than prose in `remark`, so it can be read,
                # sorted and totalled. None means "no legacy history was ever imported",
                # which is NOT the same as zero.
                "legacy_classes": getattr(s, 'legacy_classes', None),
            } for s in students]

            # Group roster by batch_id
            roster_by_batch = {}
            for s in roster_data:
                bid = s["batch_id"]
                if bid is not None:
                    roster_by_batch.setdefault(bid, []).append(s)

            # Write files atomically
            centre_cache_dir = os.path.join(SERVER_DIR, "cache", centre_id_str)
            os.makedirs(centre_cache_dir, exist_ok=True)

            def write_atomic(filename, data):
                filepath = os.path.join(centre_cache_dir, filename)
                fd, temp_path = tempfile.mkstemp(dir=centre_cache_dir, prefix=f"{filename}_tmp_", suffix=".json")
                try:
                    with os.fdopen(fd, 'w', encoding='utf-8') as f:
                        json.dump(data, f, indent=2)
                    if os.path.exists(filepath):
                        os.remove(filepath)
                    os.rename(temp_path, filepath)
                except Exception as e:
                    if os.path.exists(temp_path):
                        try:
                            os.remove(temp_path)
                        except:
                            pass
                    raise e

            # Write state, checksum, full roster
            write_atomic("state.json", state_data)
            write_atomic("checksum.json", checksum_data)
            write_atomic("roster.json", roster_data)

            # Write per-batch roster
            active_batch_ids = {b.id for b in batches}
            for fname in os.listdir(centre_cache_dir):
                if fname.startswith("roster_") and fname.endswith(".json"):
                    try:
                        bid_str = fname[len("roster_"):-len(".json")]
                        bid = int(bid_str)
                        if bid not in active_batch_ids:
                            os.remove(os.path.join(centre_cache_dir, fname))
                    except:
                        pass

            for bid, list_data in roster_by_batch.items():
                write_atomic(f"roster_{bid}.json", list_data)

            # Also write empty files for active batches with no students, to prevent fallback to SQL queries
            for bid in active_batch_ids:
                if bid not in roster_by_batch:
                    write_atomic(f"roster_{bid}.json", [])

            write_log(f"Cache files regenerated for centre {centre_id_str}")

        except Exception as e:
            write_log(f"Error regenerating cache files for centre {centre_id}: {str(e)}")


@app.route('/api/cache/state', methods=['GET'])
def cache_state():
    """
    Heartbeat endpoint polled every 15 s by clients.
    Returns active session state, batch list, and marked-today info.
    Any logged-in role may call this.
    """
    centre_id = request.args.get('centre_id') or get_selected_centre_id()
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403
    if not centre_id:
        return jsonify({"error": "centre_id required"}), 400

    # Youth/Congregation: regenerate_cache_files() writes state.json from
    # get_active_session(centre_id) with NO category, so the file only ever holds the
    # YOUTH slot. A client that asks for congregation must therefore bypass the file
    # and be answered live, or it would silently receive the youth session and stamp
    # its attendance into the wrong one. Callers that pass no category keep hitting
    # the cached file exactly as before, so the existing web client is unaffected.
    requested_category = str(request.args.get('category') or '').strip().lower()
    if requested_category not in ('youth', 'congregation'):
        requested_category = ''

    # A device that owns its own session asks about THAT session, not about "the centre".
    # Without this, every device at a centre running two classes gets the same number, and the
    # second class's operator watches the first class's count climb all evening.
    requested_session_id = normalize_session_id(request.args.get('session_id'))

    filepath = os.path.join(SERVER_DIR, "cache", str(centre_id), "state.json")
    if not requested_category and not requested_session_id and os.path.exists(filepath):
        try:
            with open(filepath, 'r', encoding='utf-8') as f:
                data = json.load(f)
            data["server_time"] = datetime.now().isoformat()
            return jsonify(data)
        except Exception as e:
            write_log(f"Failed to read cached state.json for {centre_id}: {str(e)}")

    state = get_active_session(centre_id, requested_category or 'youth')

    batches = Batch.query.filter_by(centre_id=centre_id).order_by(Batch.created_at.desc()).all()
    batch_list = [{"id": b.id, "name": b.name} for b in batches]

    # The session whose counts we report: the caller's own if it named one, otherwise the
    # shared slot's (unchanged behaviour for clients that do not yet send it).
    count_session_id = requested_session_id or state.get("session_id")
    count_date = (sanitize_date_input(request.args.get('date'))
                  or state.get("date")
                  or datetime.now().strftime("%Y-%m-%d"))

    marked_today_count = 0
    marked_today_mobiles = []
    if count_session_id and count_date:
        records = Attendance.query.filter_by(
            centre_id=centre_id,
            session_id=count_session_id,
            date=count_date
        ).all()
        marked_today_count = len(records)
        marked_today_mobiles = [r.mobile for r in records]

    return jsonify({
        "centre_id": centre_id,
        # Echo which category slot this state describes so a client that mirrors the
        # payload offline can tell whether the copy it holds matches the category the
        # user is currently working in, instead of assuming it does.
        "category": requested_category or 'youth',
        "session_state": state,
        "batch_list": batch_list,
        "marked_today_count": marked_today_count,
        "marked_today_mobiles": marked_today_mobiles,
        # Which session these counts actually describe. A device can compare this against its
        # own selection and know whether the number applies to it, rather than assuming.
        "counts_for_session_id": count_session_id,
        "counts_for_date": count_date,
        # How many classes are live here. A client that sees >1 knows the shared slot is not a
        # safe answer and that it must send its own session_id.
        "sessions_in_play": sessions_in_play(centre_id, requested_category or 'youth', count_date),
        "server_time": datetime.now().isoformat(),
    })


@app.route('/api/open_sessions', methods=['GET'])
def open_sessions():
    """The sessions a device may choose from — the replacement for asking "what is active?".

    This is the endpoint that makes several devices workable. `/api/get_session` answers "which
    session does this CENTRE have selected", and there is only one such slot per centre and
    category, so with two classes running it describes whichever device selected last. Every
    device polling it therefore converges on one answer, and the second class is wrong.

    Here the server offers the list and each device holds its own choice. The shared slot is
    still reported, but only as `suggested_session_id` — a sensible default for a device that
    has just connected and has nothing selected yet. It is never an instruction to a device
    that already has one.
    """
    centre_id = request.args.get('centre_id') or get_selected_centre_id()
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403
    if not centre_id:
        return jsonify({"error": "centre_id required"}), 400

    category = str(request.args.get('category') or 'youth').strip().lower()
    if category not in ('youth', 'congregation'):
        category = 'youth'
    on_date = sanitize_date_input(request.args.get('date')) or datetime.now().strftime("%Y-%m-%d")

    in_play = sessions_in_play(centre_id, category, on_date)
    slot = get_active_session(centre_id, category)

    # Counts per session so a device can show every class's progress, not just its own.
    counts = {}
    for sid in in_play:
        counts[sid] = Attendance.query.filter_by(
            centre_id=centre_id, session_id=sid, date=on_date).count()

    sessions = []
    for sid in in_play:
        ctx = resolve_storage_context_for_session(sid)
        mapping = SessionMapping.query.get(sid)
        sessions.append({
            "session_id": sid,
            "display_name": (getattr(mapping, 'display_name', None) or sid),
            "batch_id": ctx.get("batch_id"),
            "batch_name": ctx.get("batch_name"),
            "category": (getattr(mapping, 'category', None) or category),
            "session_type": getattr(mapping, 'session_type', 'ad_hoc'),
            "marked_count": counts.get(sid, 0),
            "created_at": (mapping.created_at.isoformat()
                           if mapping and getattr(mapping, 'created_at', None) else None),
        })
    # Most recently created first: the class a device is about to join is usually the newest.
    sessions.sort(key=lambda s: (s["created_at"] or ""), reverse=True)

    return jsonify({
        "centre_id": centre_id,
        "category": category,
        "date": on_date,
        "sessions": sessions,
        "count": len(sessions),
        # Default for a device with nothing selected. NOT an instruction to one that has.
        "suggested_session_id": (slot.get("session_id")
                                 or (sessions[0]["session_id"] if sessions else None)),
        # When true, a device MUST send its own session_id — the server will not guess.
        "ambiguous": len(sessions) > 1,
        "server_time": datetime.now().isoformat(),
    })


@app.route('/api/cache/roster', methods=['GET'])
def cache_roster():
    """
    Returns all registered active students for a centre/batch.
    Clients cache this in IndexedDB for offline autocomplete.
    """
    centre_id = request.args.get('centre_id') or get_selected_centre_id()
    batch_id = request.args.get('batch_id')
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403
    if not centre_id:
        return jsonify({"error": "centre_id required"}), 400

    # Optional track filter. A client that sends none gets the whole centre exactly as before —
    # every row now carries its own `category`, so an up-to-date client can also filter locally.
    category = (request.args.get('category') or '').strip().lower()
    category = category if category in ('youth', 'congregation') else ''

    batch_category = {
        b.id: ((b.category or 'youth').strip().lower())
        for b in Batch.query.filter_by(centre_id=centre_id).all()
    }

    def row_category(bid):
        return batch_category.get(bid, 'youth') if bid else 'youth'

    if batch_id:
        filepath = os.path.join(SERVER_DIR, "cache", str(centre_id), f"roster_{batch_id}.json")
    else:
        filepath = os.path.join(SERVER_DIR, "cache", str(centre_id), "roster.json")

    if os.path.exists(filepath):
        try:
            with open(filepath, 'r', encoding='utf-8') as f:
                data = json.load(f)
            if category:
                # Recomputed here rather than trusting the cached key, so a cache file written
                # before `category` existed still filters correctly instead of returning nothing.
                data = [r for r in data if row_category(r.get("batch_id")) == category]
            return jsonify(data)
        except Exception as e:
            write_log(f"Failed to read cached roster file {filepath}: {str(e)}")

    query = CentreRoster.query.filter_by(centre_id=centre_id, status="active")
    if batch_id:
        try:
            query = query.filter_by(batch_id=int(batch_id))
        except (ValueError, TypeError):
            pass

    students = query.order_by(CentreRoster.name).all()
    # Must stay field-for-field identical to regenerate_cache_files() — this is the same payload
    # served from a different place, and a client cannot tell which one answered it.
    return jsonify([{
        "mobile": s.mobile,
        "name": s.name,
        "address": s.address,
        "occupation": s.occupation,
        "rounds": s.rounds,
        "remark": s.remark,
        "batch_id": s.batch_id,
        "batch_name": s.batch_name,
        "category": row_category(s.batch_id),
        "legacy_classes": getattr(s, 'legacy_classes', None),
    } for s in students if not category or row_category(s.batch_id) == category])


@app.route('/api/roster/lookup', methods=['GET'])
def roster_lookup():
    """
    Find a person by mobile number ACROSS EVERY CENTRE, for the registration screen.

    Why this exists. /api/cache/roster is scoped to one centre, and so is the mirror the app
    builds its number suggestions from. That is correct for the roster itself — CentreRoster is
    scoped by centre and a person genuinely can belong to two of them — but it means a visitor
    from another region arrives at a festival as a total stranger: the operator re-types the name,
    address and occupation this system already knows, and the centre gains a second profile for
    somebody it has on file. This endpoint closes exactly that gap and nothing else.

    It DELIBERATELY does not call centre_id_authorized(). Reading beyond the caller's own centre
    is the entire point, so the usual centre guard cannot apply. What pays for that:

      - It is NOT in restrict_access()'s open_paths, so a caller must still hold a session or a
        valid JWT. It is not public.
      - MIN_DIGITS is 6, not the 3 the in-centre suggestions use. At 3 digits this would be a
        database enumeration endpoint returning hundreds of names and addresses per guess; at 6
        the caller must already substantially know the number they are asking about.
      - Results are capped, so no single query can be made to dump a centre.
      - It answers on mobile number only. There is no name or address search here, deliberately.

    Matching is done in PYTHON, on digits, not in SQL. Stored mobiles carry country codes and
    interior spaces ("+91 98350 00001"), so a LIKE on the raw column misses precisely the rows
    that need finding. The full scan is a few thousand rows and costs nothing, and it guarantees
    the server agrees with the app's own lastTenDigits rule rather than approximating it.
    """
    MIN_DIGITS = 6
    MAX_RESULTS = 10

    q = ''.join(ch for ch in (request.args.get('q') or '') if ch.isdigit())
    if len(q) < MIN_DIGITS:
        # Not an error. The app calls this on every keystroke past the threshold, and a short
        # query is simply "nothing to say yet".
        return jsonify([])

    # The centre the caller is already registering into. Its people are in the local mirror and
    # are already offered by the in-centre suggestions — returning them here would show every
    # returning member twice, once with a centre label that is their own centre.
    exclude = (request.args.get('exclude_centre_id') or '').strip()

    centre_names = {
        c.centre_id: c.name
        for c in Centre.query.filter_by(status='active').all()
    }

    # Keyed (last ten digits, centre) — NOT by mobile alone. The unique constraint on
    # centre_roster is (mobile, centre_id, scope_key), so one person at one centre is a
    # centre-level row PLUS one row per batch they are in; keying by person alone would offer
    # the same visitor three times. Rows are merged rather than first-wins so a blank address on
    # the centre-level row is filled from the batch row that has one.
    found = {}
    for r in CentreRoster.query.filter_by(status='active').all():
        if r.centre_id == exclude or r.centre_id not in centre_names:
            continue
        digits = ''.join(ch for ch in (r.mobile or '') if ch.isdigit())
        if len(digits) < 10:
            continue
        ten = digits[-10:]
        if q not in ten:
            continue
        key = (ten, r.centre_id)
        cur = found.get(key)
        if cur is None:
            found[key] = {
                "mobile": ten,
                "name": (r.name or '').strip(),
                "address": (r.address or '').strip(),
                "occupation": (r.occupation or '').strip(),
                "centre_id": r.centre_id,
                "centre_name": centre_names[r.centre_id],
            }
        else:
            for field, value in (("name", r.name), ("address", r.address),
                                 ("occupation", r.occupation)):
                if not cur[field] and (value or '').strip():
                    cur[field] = value.strip()

    out = sorted(found.values(), key=lambda p: (p["name"].lower(), p["centre_name"]))
    return jsonify(out[:MAX_RESULTS])


@app.route('/api/cache/checksum', methods=['GET'])
def cache_checksum():
    """
    Lightweight fingerprint of current session state for a centre.
    Clients compare this to their stored checksum — if it changed,
    they re-pull /api/cache/state without fetching the full payload first.
    """
    centre_id = request.args.get('centre_id') or get_selected_centre_id()
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403
    if not centre_id:
        return jsonify({"error": "centre_id required"}), 400

    filepath = os.path.join(SERVER_DIR, "cache", str(centre_id), "checksum.json")
    if os.path.exists(filepath):
        try:
            with open(filepath, 'r', encoding='utf-8') as f:
                data = json.load(f)
            return jsonify(data)
        except Exception as e:
            write_log(f"Failed to read cached checksum.json for {centre_id}: {str(e)}")

    state = get_active_session(centre_id)
    count = 0
    if state.get("session_id") and state.get("date"):
        count = Attendance.query.filter_by(
            centre_id=centre_id,
            session_id=state["session_id"],
            date=state["date"]
        ).count()

    # Must be computed EXACTLY as regenerate_cache_files() does, roster fingerprint included —
    # if the two formulas disagree the client flip-flops between them and re-pulls forever.
    roster_rows = CentreRoster.query.filter_by(centre_id=centre_id).count()
    roster_stamp = db.session.query(db.func.max(CentreRoster.updated_at)).filter(
        CentreRoster.centre_id == centre_id
    ).scalar()
    raw = (f"{state.get('session_id') or ''}|{state.get('batch_id') or ''}|"
           f"{state.get('date') or ''}|{count}|{roster_rows}|{roster_stamp or ''}")
    checksum = hashlib.md5(raw.encode()).hexdigest()[:12]
    return jsonify({"centre_id": centre_id, "checksum": checksum})


@app.route('/api/offline_snapshot', methods=['GET'])
def api_offline_snapshot():
    """
    Bearer-JWT-authenticated combined snapshot (centre + session state + batch
    list + roster) for the Android wrapper's launcher (www/index.html), which
    runs on a different origin (the Capacitor local origin) than the live
    session (the remote server domain). Cookies/localStorage from the live
    session are invisible there, so the launcher bridges a JWT across the
    origin boundary via a hidden iframe and calls this endpoint directly —
    no Flask session involved, only the JWT identifies the caller and their
    own centre. CORS is open since a valid JWT is the actual gate here, not
    the browser's cookie/origin trust.
    """
    username, role = _get_jwt_user_from_request()
    if not username:
        return jsonify({"error": "Invalid or missing token"}), 401

    if role == 'host':
        centre_id = request.args.get('centre_id')
        if not centre_id:
            return jsonify({"error": "centre_id required for host"}), 400
        centre = Centre.query.filter_by(centre_id=centre_id).first()
        centre_name = centre.name if centre else centre_id
    else:
        primary_access, primary_centre, _allowed = get_primary_centre_access_context(username)
        if not primary_access or not primary_centre:
            return jsonify({"error": "No centre access"}), 403
        centre_id = primary_centre.centre_id
        centre_name = primary_centre.name

    state = get_active_session(centre_id)
    batches = Batch.query.filter_by(centre_id=centre_id).order_by(Batch.created_at.desc()).all()
    batch_list = [{"id": b.id, "name": b.name} for b in batches]

    # Sessions grouped by batch (Calling vs Direct) — mirrors
    # /api/sessions_grouped_by_batch/<id> so the offline session picker has
    # the same data the live dropdown would show, keyed by batch id (as a
    # string, since JSON object keys are always strings).
    sessions_by_batch = {}
    for b in batches:
        mappings = SessionMapping.query.filter_by(batch_id=b.id, centre_id=centre_id).all()

        def _key(m):
            return (m.scheduled_at or datetime.min, m.date or "", m.created_at or datetime.min)

        def _item(m):
            return {"session_id": m.session_id, "display_name": m.display_name or m.session_id, "date": m.date}

        calling = sorted([m for m in mappings if (m.session_type or 'ad_hoc') == 'planned'], key=_key, reverse=True)
        direct = sorted([m for m in mappings if (m.session_type or 'ad_hoc') != 'planned'], key=_key, reverse=True)
        sessions_by_batch[str(b.id)] = {
            "calling": [_item(m) for m in calling[:4]],
            "direct": [_item(m) for m in direct[:4]],
        }

    roster = CentreRoster.query.filter_by(centre_id=centre_id, status="active").order_by(CentreRoster.name).all()
    roster_data = [{
        "mobile": s.mobile, "name": s.name, "address": s.address, "occupation": s.occupation,
        "rounds": s.rounds, "remark": s.remark, "batch_id": s.batch_id, "batch_name": s.batch_name,
    } for s in roster]

    resp = jsonify({
        "centre_id": centre_id,
        "centre_name": centre_name,
        "session_state": state,
        "batch_list": batch_list,
        "sessions_by_batch": sessions_by_batch,
        "roster": roster_data,
        "server_time": datetime.now().isoformat(),
    })
    resp.headers['Access-Control-Allow-Origin'] = '*'
    return resp


@app.route('/api/admin/resync_attendance_mirror', methods=['POST'])
def api_admin_resync_attendance_mirror():
    """
    Host-only recovery tool: re-enqueue the Excel/Google-Sheets mirror for
    Attendance rows that already exist in the database but never made it
    into the workbook.

    This gap is real and not hypothetical: excel_sync_queue is an in-memory
    queue.Queue() and ACTIVE_SESSION_BY_CENTRE is an in-memory dict — neither
    survives a process restart. A record can commit safely to the (durable)
    Attendance table via mark_attendance()/sync_offline_queue() and still
    lose its queued mirror task if the process restarts before the
    background worker drains it. The DB row is never at risk; only the
    Excel mirror can silently fall behind.

    Body: { "date": "YYYY-MM-DD", "session_id": "...", "centre_id": "...",
            "mobiles": ["...", ...] (optional — omit to resync every row for
            the date/session/centre) }
    """
    role = session.get('role') or getattr(g, 'jwt_role', None)
    if role != 'host' and not is_host_device():
        return jsonify({"error": "Forbidden"}), 403

    data = request.json or {}
    date_val = sanitize_date_input(data.get('date'))
    session_id = str(data.get('session_id', '')).strip()
    centre_id = str(data.get('centre_id', '')).strip()
    mobiles = data.get('mobiles')

    if not (date_val and session_id and centre_id):
        return jsonify({"error": "date, session_id, and centre_id are required"}), 400

    query = Attendance.query.filter_by(date=date_val, session_id=session_id, centre_id=centre_id)
    if mobiles:
        query = query.filter(Attendance.mobile.in_(mobiles))
    rows = query.all()

    resynced = []
    for att in rows:
        user = User.query.filter_by(mobile=att.mobile).first()
        workbook_row = {
            "mobile": att.mobile,
            "name": user.name if user else "Unknown",
            "address": getattr(user, "address", "") or "",
            "occupation": getattr(user, "occupation", "") or "",
            "batch": att.batch_name or "",
            "batch_name": att.batch_name or "",
            "session": att.session_id,
            "session_name": att.session_id,
            "rounds": att.rounds or "",
            "remark": att.remark or "",
            "date": att.date,
            "centre_id": att.centre_id,
        }
        enqueue_attendance_mirror_task(workbook_row, sheet_name=sanitize_sheet_name(session_id))
        resynced.append(att.mobile)

    write_log(f"Admin resync: re-enqueued {len(resynced)} attendance mirror task(s) for session={session_id} date={date_val}")
    return jsonify({"resynced": resynced, "count": len(resynced)})


@app.route('/api/sync/offline_queue', methods=['POST'])
def sync_offline_queue():
    """
    Accepts an array of offline-queued attendance records from a client.
    Deduplicates by (mobile, session_id, date, centre_id) - only inserts
    records that do not already exist.  Returns per-record disposition so
    the client can mark its IndexedDB records as committed or failed.

    Request body:
      { "records": [ { "client_id": "<uuid>", "mobile": "...",
                        "session_id": "...", "date": "YYYY-MM-DD",
                        "centre_id": "...", "batch_name": "...",
                        "name": "...", "address": "...",
                        "remark": "...", "rounds": "...",
                        "cheating": false } ... ] }
    Response:
      { "committed": [client_id, ...],
        "duplicates": [client_id, ...],
        "failed": [{ "client_id": ..., "reason": "..." }, ...] }
    """
    data = request.json or {}
    records = data.get("records", [])
    if not isinstance(records, list):
        return jsonify({"error": "records must be an array"}), 400

    committed = []
    duplicates = []
    failed = []
    unique_centres = set()

    for rec in records:
        client_id = rec.get("client_id", "")
        try:
            mobile = str(rec.get("mobile", "")).strip()
            if not re.fullmatch(r"\d{10}", mobile):
                raise ValueError("invalid mobile number")

            session_id = str(rec.get("session_id", "")).strip()
            date_val = sanitize_date_input(rec.get("date"))
            centre_id = str(rec.get("centre_id", "")).strip()

            if not all([session_id, date_val, centre_id]):
                raise ValueError("session_id, date, and centre_id are required")

            if centre_id:
                unique_centres.add(centre_id)

            # Dedup: skip if already committed by any path
            existing = Attendance.query.filter_by(
                mobile=mobile,
                session_id=session_id,
                date=date_val,
                centre_id=centre_id
            ).first()
            if existing:
                duplicates.append(client_id)
                continue

            # Upsert user record (create if first time we see this mobile)
            user = User.query.filter_by(mobile=mobile).first()
            if not user:
                name_val = str(rec.get("name", "")).strip() or "Unknown"
                user = User(mobile=mobile, name=name_val)
                db.session.add(user)
            else:
                if rec.get("name"):
                    user.name = rec["name"]
                if rec.get("address"):
                    user.address = rec["address"]
                if rec.get("occupation"):
                    user.occupation = rec["occupation"]

            rounds_val = str(rec.get("rounds", "")).strip()
            remark_val = str(rec.get("remark", "")).strip()
            cheating_raw = rec.get("cheating")
            cheating_val = "Yes" if str(cheating_raw).lower() in ["true", "yes", "1"] else "No"
            batch_name_val = str(rec.get("batch_name", "")).strip()
            category_val = str(rec.get("category", "")).strip().lower() or "youth"

            att = Attendance(
                mobile=mobile,
                date=date_val,
                batch_name=batch_name_val,
                session_id=session_id,
                centre_id=centre_id,
                remark=remark_val,
                rounds=rounds_val,
                cheating=cheating_val,
                category=category_val,
            )
            db.session.add(att)
            db.session.commit()

            # Bug #3 fix: mirror to live Excel workbook + Google Sheets,
            # exactly as the real-time mark_attendance route does. Must use
            # the same sanitize_sheet_name() as session-creation/mark_attendance
            # — an unsanitized session_id (e.g. containing "/") produced a
            # DIFFERENT sheet name here than the one the user was actually
            # viewing, so offline-synced records looked like they vanished.
            try:
                sheet_name = sanitize_sheet_name(session_id)
                workbook_row = {
                    "mobile": mobile,
                    "name": user.name,
                    "address": getattr(user, "address", "") or "",
                    "occupation": getattr(user, "occupation", "") or "",
                    "batch": batch_name_val,
                    "batch_name": batch_name_val,
                    "session": session_id,
                    "session_name": session_id,
                    "rounds": rounds_val,
                    "remark": remark_val,
                    "date": date_val,
                    "centre_id": centre_id,
                    "category": category_val,
                }
                enqueue_attendance_mirror_task(workbook_row, sheet_name=sheet_name)
            except Exception as mirror_exc:
                write_log(
                    f"Offline sync: mirror enqueue failed for {mobile} "
                    f"(session={session_id}): {mirror_exc}"
                )

            committed.append(client_id)

        except Exception as exc:
            db.session.rollback()
            failed.append({"client_id": client_id, "reason": str(exc)})
            write_log(
                f"Offline sync: record failed (client_id={client_id}, "
                f"mobile={rec.get('mobile')}, session_id={rec.get('session_id')}, "
                f"date={rec.get('date')}, centre_id={rec.get('centre_id')}): {exc}"
            )

    write_log(
        "Offline sync: " + str(len(committed)) + " committed, "
        + str(len(duplicates)) + " duplicates, "
        + str(len(failed)) + " failed."
    )

    for cid in unique_centres:
        regenerate_cache_files(cid)

    if committed:
        try:
            reconcile_live_workbook_from_db()
        except Exception as rec_e:
            write_log(f"Offline sync reconcile error: {rec_e}")

    return jsonify({
        "committed": committed,
        "duplicates": duplicates,
        "failed": failed,
    })


@app.route('/api/sync/recover_queue', methods=['POST'])
def recover_offline_queue():
    """
    Lenient last-resort sibling of /api/sync/offline_queue.

    That endpoint permanently drops a client-queued record's identity if it
    fails validation (e.g. no session_id because no session was active when
    the client marked attendance offline) — after 3 client-side retries the
    record goes silent forever with no server-side trace. This endpoint
    NEVER discards a record: anything that can't be committed outright is
    appended to database/recovered_offline_records.jsonl for manual review
    instead of being lost. The client should call this for any record whose
    normal sync attempts have been exhausted.

    Response: { "committed": [client_id...], "duplicates": [client_id...],
                "held": [client_id...] }  -- "held" means safely persisted
    server-side for manual review, not committed to attendance yet.
    """
    data = request.json or {}
    records = data.get("records", [])
    if not isinstance(records, list):
        return jsonify({"error": "records must be an array"}), 400

    committed = []
    duplicates = []
    held = []
    unique_centres = set()
    recover_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'database', 'recovered_offline_records.jsonl')

    for rec in records:
        client_id = rec.get("client_id", "")
        try:
            mobile = str(rec.get("mobile", "")).strip()
            session_id = str(rec.get("session_id", "")).strip()
            date_val = sanitize_date_input(rec.get("date"))
            centre_id = str(rec.get("centre_id", "")).strip()

            can_commit = (
                re.fullmatch(r"\d{10}", mobile) and session_id and date_val and centre_id
            )

            if can_commit:
                existing = Attendance.query.filter_by(
                    mobile=mobile, session_id=session_id, date=date_val, centre_id=centre_id
                ).first()
                if existing:
                    duplicates.append(client_id)
                    continue

                if centre_id:
                    unique_centres.add(centre_id)

                user = User.query.filter_by(mobile=mobile).first()
                if not user:
                    user = User(mobile=mobile, name=str(rec.get("name", "")).strip() or "Unknown")
                    db.session.add(user)
                else:
                    if rec.get("name"):
                        user.name = rec["name"]
                    if rec.get("address"):
                        user.address = rec["address"]
                    if rec.get("occupation"):
                        user.occupation = rec["occupation"]

                rounds_val = str(rec.get("rounds", "")).strip()
                remark_val = str(rec.get("remark", "")).strip()
                cheating_val = "Yes" if str(rec.get("cheating")).lower() in ["true", "yes", "1"] else "No"
                batch_name_val = str(rec.get("batch_name", "")).strip()
                category_val = str(rec.get("category", "")).strip().lower() or "youth"

                att = Attendance(
                    mobile=mobile, date=date_val, batch_name=batch_name_val,
                    session_id=session_id, centre_id=centre_id,
                    remark=remark_val, rounds=rounds_val, cheating=cheating_val,
                    category=category_val,
                )
                db.session.add(att)
                db.session.commit()

                try:
                    sheet_name = sanitize_sheet_name(session_id)
                    workbook_row = {
                        "mobile": mobile, "name": user.name,
                        "address": getattr(user, "address", "") or "",
                        "occupation": getattr(user, "occupation", "") or "",
                        "batch": batch_name_val, "batch_name": batch_name_val,
                        "session": session_id, "session_name": session_id,
                        "rounds": rounds_val, "remark": remark_val, "date": date_val,
                        "centre_id": centre_id, "category": category_val,
                    }
                    enqueue_attendance_mirror_task(workbook_row, sheet_name=sheet_name)
                except Exception as mirror_exc:
                    write_log(f"Recover queue: mirror enqueue failed for {mobile}: {mirror_exc}")

                committed.append(client_id)
                continue

            # Missing/invalid required fields — hold for manual review, never drop.
            entry = {
                "received_at": datetime.now().isoformat(),
                "client_id": client_id,
                "record": rec,
            }
            with open(recover_path, "a", encoding="utf-8") as f:
                f.write(json.dumps(entry, ensure_ascii=False) + "\n")
            held.append(client_id)
            write_log(
                f"Recover queue: held for review (client_id={client_id}, "
                f"mobile={rec.get('mobile')}, session_id={rec.get('session_id')!r}, "
                f"date={rec.get('date')}, centre_id={rec.get('centre_id')})"
            )

        except Exception as exc:
            db.session.rollback()
            entry = {
                "received_at": datetime.now().isoformat(),
                "client_id": client_id,
                "record": rec,
                "error": str(exc),
            }
            with open(recover_path, "a", encoding="utf-8") as f:
                f.write(json.dumps(entry, ensure_ascii=False) + "\n")
            held.append(client_id)
            write_log(f"Recover queue: held for review after error (client_id={client_id}): {exc}")

    write_log(
        "Recover queue: " + str(len(committed)) + " committed, "
        + str(len(duplicates)) + " duplicates, "
        + str(len(held)) + " held for review."
    )

    for cid in unique_centres:
        regenerate_cache_files(cid)

    return jsonify({
        "committed": committed,
        "duplicates": duplicates,
        "held": held,
    })


@app.route('/api/analytics/attendance-trend', methods=['GET'])
def attendance_trend():
    """
    Returns per-batch attendance counts grouped by date.
    Optional query params:
      - centre_id: filter to a specific centre
      - days: number of most-recent distinct dates to include (default 30)
    Response:
      { "status": "success", "data": [
          { "batch_name": "...", "centre_id": "...",
            "points": [{ "date": "YYYY-MM-DD", "count": N }, ...] }
        ]
      }
    """
    centre_id = request.args.get('centre_id')
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403
    if not centre_id and not (is_host_device() or session.get('role') == 'host'):
        centre_id = get_selected_centre_id()
    days = min(int(request.args.get('days', 30)), 90)

    q = db.session.query(
        Attendance.date,
        Attendance.batch_name,
        Attendance.centre_id,
        db.func.count(db.distinct(Attendance.mobile)).label('cnt')
    ).group_by(Attendance.date, Attendance.batch_name, Attendance.centre_id)

    if centre_id:
        q = q.filter(Attendance.centre_id == centre_id)

    q = q.order_by(Attendance.date.asc())
    rows = q.all()

    # Collect distinct dates and keep latest `days` of them
    all_dates = sorted({r.date for r in rows})
    recent_dates = set(all_dates[-days:]) if len(all_dates) > days else set(all_dates)

    batches: dict = {}
    for row in rows:
        if row.date not in recent_dates:
            continue
        key = f"{row.batch_name}|{row.centre_id}"
        if key not in batches:
            batches[key] = {
                'batch_name': row.batch_name,
                'centre_id': row.centre_id,
                'points': []
            }
        batches[key]['points'].append({'date': row.date, 'count': row.cnt})

    # Sort each batch's points by date
    for b in batches.values():
        b['points'].sort(key=lambda p: p['date'])

    return jsonify({'status': 'success', 'data': list(batches.values())})


@app.route('/api/analytics/centre-summary', methods=['GET'])
def analytics_centre_summary():
    """
    Returns per-centre student counts from the RegistererAccount table.
    Used by the admin analytics screen.
    Optional: ?centre_id=<id> to scope to a single centre.
    """
    centre_id = request.args.get('centre_id')
    if not centre_id_authorized(centre_id):
        return jsonify({"error": "Forbidden"}), 403
    if not centre_id and not (is_host_device() or session.get('role') == 'host'):
        centre_id = get_selected_centre_id()

    from sqlalchemy import func as sqlfunc
    q = db.session.query(
        RegistererAccount.centre_id,
        sqlfunc.count(RegistererAccount.mobile).label('total'),
        sqlfunc.sum(db.case((RegistererAccount.status == 'approved', 1), else_=0)).label('approved'),
        sqlfunc.sum(db.case((RegistererAccount.status == 'pending', 1), else_=0)).label('pending_count')
    ).group_by(RegistererAccount.centre_id)

    if centre_id:
        q = q.filter(RegistererAccount.centre_id == centre_id)

    rows = q.all()
    data = [
        {
            'centre_id': r.centre_id,
            'total': r.total or 0,
            'approved': int(r.approved or 0),
            'pending': int(r.pending_count or 0)
        }
        for r in rows
    ]
    return jsonify({'status': 'success', 'data': data})


def get_local_ip():
    try:
        # Create a dummy UDP socket to determine the correct local network interface IP
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

if __name__ == "__main__":
    # Initialize DB tables
    with app.app_context():
        db.create_all()
        # Simple schema migration for existing databases
        from sqlalchemy import text
        try:
            db.session.execute(text("ALTER TABLE attendance ADD COLUMN timestamp DATETIME DEFAULT '2026-01-01 00:00:00'"))
            db.session.commit()
        except Exception:
            db.session.rollback()
            
        try:
            db.session.execute(text("ALTER TABLE attendance ADD COLUMN remark VARCHAR(255)"))
            db.session.commit()
        except Exception:
            db.session.rollback()

        try:
            db.session.execute(text("ALTER TABLE attendance ADD COLUMN rounds VARCHAR(50)"))
            db.session.commit()
        except Exception:
            db.session.rollback()

        try:
            db.session.execute(text("ALTER TABLE attendance ADD COLUMN cheating VARCHAR(10) DEFAULT 'No'"))
            db.session.commit()
        except Exception:
            db.session.rollback()

        try:
            db.session.execute(text("ALTER TABLE users ADD COLUMN cheating VARCHAR(10) DEFAULT 'No'"))
            db.session.commit()
        except Exception:
            db.session.rollback()

        try:
            db.session.execute(text("ALTER TABLE attendance ADD COLUMN batch_name VARCHAR(100)"))
            db.session.commit()
        except Exception:
            db.session.rollback()

        try:
            db.session.execute(text("ALTER TABLE attendance ADD COLUMN session_id VARCHAR(150)"))
            db.session.commit()
        except Exception:
            db.session.rollback()

        ensure_default_centre()
        ensure_centre_google_sheet_columns()
        ensure_centre_a_centre()
        ensure_centre_mapping_columns()
        ensure_concurrent_marking_schema()
        ensure_centre_access_table()
        ensure_centre_change_request_table()
        # Relocate any pre-split flat batch folders into youth/congregation
        # subfolders BEFORE the skeleton ensure recreates paths, so the mirror
        # doesn't end up with both a flat and a partitioned copy.
        migrate_export_mirror_layout()
        ensure_storage_folder_skeleton()

        # --- Trigger Excel Migration ---
        migrate_excel_to_long_format()
        enqueue_initial_excel_output_refreshes()

        # Drain parked registrations BEFORE the cache regeneration below, so anyone who
        # registers on the way up is already in the roster the caches are built from.
        try:
            drained = drain_parked_registrations()
            if drained:
                write_log(f"Startup: {drained} parked registration(s) registered.")
        except Exception as startup_err:
            write_log(f"Startup parked-registration drain failed: {startup_err}")

        # Regenerate cache files on startup for all centres to ensure consistency
        try:
            centres = Centre.query.all()
            for c in centres:
                regenerate_cache_files(c.centre_id)
            write_log("Startup cache file regeneration complete.")
        except Exception as startup_err:
            write_log(f"Failed to regenerate cache files on startup: {startup_err}")

    # CLI Interactive Start-up
    print("\n" + "="*50)
    print("      ATTENDANCE MANAGEMENT SYSTEM STARTUP")
    print("="*50)

    # 0. Excel lock recovery — MUST run before anything that touches the workbook
    # (the startup backup and the self-healing reconcile below both do). A crash
    # or a hard restart is precisely when an orphan EXCEL.EXE gets stranded
    # holding the master open, and at this point there is definitionally no
    # in-flight write of ours to disturb. Synchronous on purpose: it is a single
    # file probe when nothing is wrong, and every later Excel write depends on it.
    try:
        _lock_recovery = excel_lock_guard.startup_recovery(
            [LIVE_FILE], log=write_log,
            journal=_excel_guard_journal(LIVE_FILE, "startup_recovery"))
        for _p, _st in (_lock_recovery or {}).items():
            if _st != excel_lock_guard.CLEAR_NOT_LOCKED:
                write_log(f"Excel lock guard (startup): {os.path.basename(_p)} -> {_st}")
    except Exception as _e:
        write_log(f"Excel lock guard (startup) failed, continuing: {_e}")

    # 1. Start Server Background Processes
    # Skip built-in tunnel management when PM2 is already handling cloudflared externally.
    # We also skip it if running under PM2 in general (detected by PM2_HOME) to prevent conflicts.
    if not os.environ.get("PM2_MANAGES_TUNNEL") and not os.environ.get("PM2_HOME"):
        start_tunnel_background()

    # 2. CB outbox worker — drains password sync and future outbox jobs
    _ensure_cb_outbox_table()
    threading.Thread(target=_cb_outbox_worker, daemon=True).start()

    # 2b. AE-access reconciler — independent revoke-direction safety net (see comment above _ae_access_reconcile_tick)
    threading.Thread(target=_ae_access_reconcile_worker, daemon=True).start()

    # 3. Data Auto-Import (Background, non-blocking)
    threading.Thread(target=import_from_data_folder, daemon=True).start()

    # 4. Startup Backup
    threading.Thread(target=perform_startup_backup, daemon=True).start()

    # 5. Google Sheet auto-sync — keeps the members, batch-roster and calling
    # tabs current on their own. Attendance already mirrors live via
    # excel_sync_worker; this covers everything that previously only updated
    # when someone ran export_centre_sheet.py by hand. Runs entirely on its own
    # thread, so no request ever waits on Google.
    try:
        import sheet_autosync
        sheet_autosync.start_autosync(log=write_log)
    except Exception as e:
        write_log(f"Google Sheet auto-sync did NOT start: {e}")

    # Startup Self-Healing Reconcile: ensure live_attendance_master.xlsx matches attendance.db 100%
    threading.Thread(target=reconcile_live_workbook_from_db, daemon=True).start()

    # 6. Session sheet <-> database verifier. The startup reconcile above runs
    # ONCE; this keeps the sessions a registerer is actually looking at in step
    # for the whole time the server is up, so the Current tab (database) and the
    # Lists tab (workbook) cannot quietly drift apart during a class.
    threading.Thread(target=session_sync_worker, daemon=True).start()

    # 4. Final Server Ready Feedback
    local_ip = get_local_ip()
    print("\n" + "="*60)
    print(" SERVER INITIALIZED SUCCESSFULLY")
    print(f" Local URL:   http://{local_ip}:6061")
    print(f" Domain:      {PUBLIC_BASE_URL}")
    print(f" Note:        Production server is now active (8 threads).")
    print("="*60 + "\n")

    # NOTE: an earlier version of this called free_server_port(PORT) here to auto-kill
    # anything already bound to the port. Removed: PM2 on Windows doesn't always finish
    # tearing down the previous instance before spawning a new one, so the new process's
    # kill would hit the instance PM2 was still tracking as "the app" — PM2 saw that as a
    # crash and immediately relaunched, whose own kill call repeated the same thing against
    # its predecessor, causing a self-sustaining restart loop (observed 2026-07-04, ~16:05).
    # Detection without killing is safe; only log so a human can decide.
    warn_if_port_already_held(PORT)

    # Exclusive bind: if another instance still holds this port, fail fast here
    # instead of silently running a second server that races the first on Excel
    # writes (see bind_exclusive_server_socket docstring for the 2026-07-27 incident).
    server_sock = bind_exclusive_server_socket('0.0.0.0', PORT)

    write_log("Server initialized. Waitress listening.")

    # Use Waitress for production-grade concurrency (8 parallel request threads)
    serve(app, sockets=[server_sock], threads=8)
