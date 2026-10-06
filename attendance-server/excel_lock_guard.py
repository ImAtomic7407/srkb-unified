"""Automatic recovery from Excel file locks that block writes to a workbook.

WHY THIS EXISTS
---------------
The COM writer in app.py deliberately leaves an ``Excel.Application`` COM server
resident between writes (it calls ``$wb.Close($false)`` but never ``$excel.Quit()``)
so the next write can reuse it. That resident instance is WINDOWLESS and is the
normal steady state — it is not, by itself, a fault.

The fault is that same resident instance left with the WORKBOOK STILL OPEN. That
happens whenever the PowerShell helper dies between ``Workbooks.Open`` and
``$wb.Close`` — most commonly when ``subprocess.run(..., timeout=30)`` kills it
mid-COM-call. Excel is launched by DCOM, not as a child of PowerShell, so killing
the PowerShell process tree does NOT take Excel with it. The workbook then stays
open forever, holding an exclusive handle on the .xlsx plus a ``~$`` lock file.

Every later openpyxl ``wb.save()`` raises PermissionError. Rows are not lost —
they are safe in SQLite and land in the durable pending-sync store — but they
stop reaching Excel and Google Sheets, and because nothing ever clears the lock
they stay stuck forever. That is what a human experiences as "the data is lost".
Confirmed live in July and again on 2026-08-12 (orphan PID 180, no window).

THE TWO HALVES OF THE FIX
-------------------------
1. PRODUCER (in app.py's PowerShell helpers): try/finally that closes the
   workbook and quits the instance the script itself created, so orphans stop
   being manufactured in the first place.
2. THIS MODULE (backstop): when a write is blocked anyway, detect it, clear it,
   and retry — automatically, with no human step.

DESIGN RULES (each one is here because the obvious alternative is wrong)
-----------------------------------------------------------------------
* Decide from the FILE, not from the process. A cheap ``open(path, 'r+b')`` probe
  answers "is this actually blocked" with no process enumeration and no deps.
  Enumerating Excel processes and reasoning about them is only done AFTER the
  probe says the file is genuinely locked.
* The kill test is the process COMMAND LINE — `/automation -Embedding`, which
  Windows gives every Excel created through COM/DCOM and which nothing a human
  does can produce — and it is only ever applied to a file ALREADY proven
  locked. It was tempting to use "has no visible window" instead, but that is
  measurably unsafe: on this machine `MainWindowHandle` reads 0 even for a
  VISIBLE Excel when queried from a non-interactive session, so a window-only
  test would have killed a human's workbook. An instance whose command line
  cannot be read is treated as a human's and left alone.
* No CPU-idle check. It is what made the manual intervention feel safe, but it
  does not automate: an orphan caught mid-save shows CPU, and a janitor that
  backs off on CPU would back off forever.
* The ``~$`` file is NOT what blocks the write — the exclusive handle on the
  .xlsx is. Order is: probe -> kill holder -> then sweep ``~$`` -> retry. Sweeping
  ``~$`` first fixes nothing (and fails anyway, since a live Excel holds its own
  ``~$`` file exclusively). That property is exactly what makes the sweep safe:
  a ``~$`` file we can open and delete is stale BY DEFINITION.
* If the holder HAS a window, a human has the workbook open. Refuse. Killing it
  would discard their unsaved edits — trading one data loss for another. The row
  stays in the pending store and flushes when they close it.

Path-agnostic on purpose: takes the workbook path as an argument so it protects
ANY Excel sheet this system writes, not just the attendance master.

No third-party dependencies (no psutil) — this must not add an install step to a
system that is about to be deployed widely.
"""

import glob
import json
import os
import subprocess
import threading
import time

# Windows: keep every helper process windowless. On other platforms these flags
# do not exist and the whole lock problem does not either.
if os.name == "nt":
    _STARTUPINFO = subprocess.STARTUPINFO()
    _STARTUPINFO.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    _STARTUPINFO.wShowWindow = subprocess.SW_HIDE
    _NO_WINDOW = {
        "startupinfo": _STARTUPINFO,
        "creationflags": subprocess.CREATE_NO_WINDOW,
    }
else:
    _NO_WINDOW = {}

# Serialises the whole detect->kill->sweep->verify sequence. Several background
# threads (excel_sync_worker, pending_sync_retry_worker, the reconcilers) can hit
# the same lock at the same instant; without this they would each enumerate and
# kill concurrently and race on the ~$ sweep.
_GUARD_LOCK = threading.RLock()

# How long to wait for Windows to actually release the handle after a kill.
_RELEASE_TIMEOUT = 6.0
_RELEASE_POLL = 0.25

# Outcome codes returned by clear_excel_lock(). Stable strings — they are written
# to the sync journal and can be grepped/alerted on.
CLEAR_NOT_LOCKED = "not_locked"        # nothing was holding it
CLEAR_SELF_HEALED = "self_healed"      # we killed an orphan and the file is writable
CLEAR_HELD_BY_HUMAN = "held_by_human"  # a visible Excel has it — deliberately refused
CLEAR_STILL_LOCKED = "still_locked"    # killed what we could, still not writable
CLEAR_UNKNOWN_HOLDER = "unknown_holder"  # locked, but no Excel process at all
CLEAR_BUSY = "com_write_in_flight"     # one of OUR OWN COM writers has it open right now

# Statuses that mean "the file is writable now, go ahead and retry".
_CLEARED = (CLEAR_NOT_LOCKED, CLEAR_SELF_HEALED)

# ── in-flight COM writes ─────────────────────────────────────────────────────
# An orphan and a healthy COM write look IDENTICAL from outside: both are an
# `/automation` EXCEL.EXE holding the workbook, and both make the file probe as
# locked. The only thing that separates them is time — an orphan is permanent, a
# real write finishes in seconds — and the only cheap way to know is to ask
# ourselves whether one of our own COM helpers is running right now.
#
# This matters because the COM subprocess deliberately runs OUTSIDE
# EXCEL_WRITE_LOCK, so a second thread (the startup reconcile, or an HTTP
# request doing update_row / delete_sheet / ensure_session_sheet) can reach a
# blocked save while the attendance writer is mid-`$wb.Save()`. Without this
# counter the guard would kill that live Excel and then let openpyxl write its
# whole in-memory copy over whatever half-saved state Excel left behind —
# destroying in-flight work, which is the exact failure the /automation test was
# chosen to prevent. Before this module existed the same collision was harmless:
# the openpyxl save simply failed into the pending store.
#
# While a COM write is in flight we therefore refuse to kill anything and report
# CLEAR_BUSY; the caller's normal failure path (durable pending store, 60s retry)
# picks the row up moments later, by which time the write has finished.
_INFLIGHT_LOCK = threading.Lock()
_inflight_com_writes = 0


class com_write(object):
    """Context manager marking an Excel COM subprocess as in flight.

    Wrap the actual subprocess.run of a COM helper. On an exception (including
    the TimeoutExpired that strands an orphan) the count is decremented as the
    exception propagates, i.e. BEFORE the caller's except block runs — so a
    timeout handler is still free to reap the orphan it just created.
    """

    def __enter__(self):
        global _inflight_com_writes
        with _INFLIGHT_LOCK:
            _inflight_com_writes += 1
        return self

    def __exit__(self, *exc):
        global _inflight_com_writes
        with _INFLIGHT_LOCK:
            _inflight_com_writes -= 1
        return False


def com_writes_in_flight():
    with _INFLIGHT_LOCK:
        return _inflight_com_writes


def _noop_log(_msg):
    pass


# ── detection ────────────────────────────────────────────────────────────────

def lock_error(path):
    """Return the OSError proving `path` is unwritable, or None if it is writable.

    Opens 'r+b' — read/write WITHOUT truncating — so this is a pure probe: it can
    never damage the workbook even if called on a file mid-write. A missing file
    is 'writable' (the caller is about to create it).
    """
    if not path or not os.path.exists(path):
        return None
    try:
        with open(path, "r+b"):
            return None
    except OSError as err:
        return err


def is_locked(path):
    """True if something currently holds `path` open for exclusive write."""
    return lock_error(path) is not None


def excel_processes(timeout=20):
    """Every running EXCEL.EXE as [{'pid', 'has_window', 'is_automation'}].

    `is_automation` is the one that decides whether we may kill it, and it is
    read from the process COMMAND LINE: Windows starts an Excel created through
    COM/DCOM as

        EXCEL.EXE /automation -Embedding

    A human's Excel — double-clicked, opened from the Start menu, or from the
    recent-files list — is never launched with those switches. So this is a
    positive identification of an automation server rather than a guess, and
    unlike a window check it does not depend on which window station or session
    the asking process happens to be running in. That matters: measured on this
    machine, `MainWindowHandle` reads 0 even for a VISIBLE Excel when queried
    from a non-interactive session, so a window-only test could have killed a
    human's Excel. `has_window` is still collected, but only for the log.

    Returns [] on any failure, and leaves `is_automation` False whenever the
    command line cannot be read — callers must treat "cannot identify" as
    "do not kill", never as "safe to kill".
    """
    if os.name != "nt":
        return []
    # Win32_Process carries CommandLine; Get-Process carries MainWindowHandle.
    # Join them on PID so one call yields both.
    cmd = (
        "$w = @{}; "
        "Get-CimInstance Win32_Process -Filter \"Name='EXCEL.EXE'\" "
        "-ErrorAction SilentlyContinue | ForEach-Object { $w[[int]$_.ProcessId] = $_.CommandLine }; "
        "Get-Process -Name EXCEL -ErrorAction SilentlyContinue | ForEach-Object { "
        "[pscustomobject]@{ Id = $_.Id; MainWindowHandle = [int64]$_.MainWindowHandle; "
        "CommandLine = $w[[int]$_.Id] } } | ConvertTo-Json -Compress"
    )
    try:
        proc = subprocess.run(
            ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", cmd],
            capture_output=True, text=True, timeout=timeout, **_NO_WINDOW
        )
    except Exception:
        return []
    raw = (proc.stdout or "").strip()
    if not raw:
        return []
    try:
        parsed = json.loads(raw)
    except Exception:
        return []
    # ConvertTo-Json emits a bare object for a single process and an array for many.
    if isinstance(parsed, dict):
        parsed = [parsed]
    out = []
    for item in parsed or []:
        try:
            pid = int(item.get("Id"))
        except Exception:
            continue
        try:
            handle = int(item.get("MainWindowHandle") or 0)
        except Exception:
            handle = 0
        cmdline = (item.get("CommandLine") or "").lower()
        out.append({
            "pid": pid,
            "has_window": handle != 0,
            # Both switches must be present. Excel writes them together for a
            # COM/DCOM-created instance; nothing a human does produces them.
            "is_automation": "/automation" in cmdline and "-embedding" in cmdline,
        })
    return out


# ── remediation ──────────────────────────────────────────────────────────────

def _kill(pid, timeout=15):
    """Force-kill one PID. True if it is gone afterwards."""
    try:
        subprocess.run(
            ["taskkill", "/PID", str(pid), "/F", "/T"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            timeout=timeout, **_NO_WINDOW
        )
    except Exception:
        return False
    return not any(p["pid"] == pid for p in excel_processes())


def sweep_stale_lock_files(directory, log=_noop_log):
    """Delete Excel owner-lock files (``~$*``) that no live process is holding.

    A running Excel keeps its own ``~$`` file open exclusively, so anything we can
    successfully open for write here is by definition abandoned. That test — not a
    filename guess — is what makes this safe to run unconditionally, and it is why
    this never has to know which ``~$`` name belongs to which workbook (Excel
    truncates them for long filenames).
    """
    removed = []
    try:
        candidates = glob.glob(os.path.join(directory, "~$*"))
    except Exception:
        return removed
    for candidate in candidates:
        try:
            with open(candidate, "r+b"):
                pass
        except OSError:
            continue  # still held by a live Excel — not ours to remove
        try:
            os.remove(candidate)
            removed.append(os.path.basename(candidate))
        except OSError:
            pass
    if removed:
        log(f"Excel lock guard: removed stale lock file(s): {', '.join(removed)}")
    return removed


def clear_excel_lock(path, log=_noop_log, journal=None, reason=""):
    """Make `path` writable again if an orphan Excel is blocking it.

    Returns one of the CLEAR_* codes. Only CLEAR_NOT_LOCKED and CLEAR_SELF_HEALED
    mean the caller may retry its write.

    Only ever kills an Excel positively identified as a COM/DCOM automation
    server by its `/automation -Embedding` command line. See excel_processes().
    """
    directory = os.path.dirname(os.path.abspath(path)) or "."

    with _GUARD_LOCK:
        # 1. Probe the FILE. Everything else is skipped if it is already writable
        #    (another thread may have cleared it while we waited on _GUARD_LOCK).
        err = lock_error(path)
        if err is None:
            sweep_stale_lock_files(directory, log)
            return CLEAR_NOT_LOCKED

        # 1b. Never kill while one of OUR OWN COM helpers is mid-write — that
        #     Excel is doing exactly what it should. See com_write() above.
        if com_writes_in_flight() > 0:
            detail = (
                f"{os.path.basename(path)} is locked by an Excel COM write of ours "
                f"that is still running; not touching it. The row stays queued and "
                f"the 60s retry will complete it once the write finishes."
            )
            log(f"Excel lock guard: {detail}")
            if journal:
                journal(status="skipped", detail=detail, error=err, reason=reason)
            return CLEAR_BUSY

        procs = excel_processes()
        # Killable == positively identified as a COM/DCOM automation server.
        # Anything else is presumed to be a human's Excel and is left alone,
        # including any instance whose command line we could not read.
        automation = [p["pid"] for p in procs if p.get("is_automation")]
        human = [p["pid"] for p in procs if not p.get("is_automation")]

        if not automation:
            if human:
                status, detail = CLEAR_HELD_BY_HUMAN, (
                    f"{os.path.basename(path)} is open in Excel on this machine "
                    f"(PID {', '.join(map(str, human))}, not an automation "
                    f"instance). Refusing to kill it - "
                    f"that would discard unsaved work. Rows stay queued and will "
                    f"flush automatically once it is closed."
                )
            else:
                status, detail = CLEAR_UNKNOWN_HOLDER, (
                    f"{os.path.basename(path)} is locked but no Excel process is "
                    f"running - another program (antivirus, backup, sync client) "
                    f"holds it. Rows stay queued and will retry."
                )
            log(f"Excel lock guard: {detail}")
            if journal:
                journal(status="needs_attention", detail=detail, error=err, reason=reason)
            return status

        # 2. Kill the automation holders. Safe even if one is merely an idle
        #    automation server rather than the actual holder: the next write
        #    recreates it. We only get here because the file is PROVEN locked.
        killed = [pid for pid in automation if _kill(pid)]
        log(
            f"Excel lock guard: {os.path.basename(path)} was locked "
            f"({type(err).__name__}); killed automation EXCEL.EXE "
            f"{killed or 'none'}"
            + (f"; left human-owned PID(s) {human} alone" if human else "")
        )

        # 3. Only NOW is the ~$ file removable — its holder is gone.
        deadline = time.time() + _RELEASE_TIMEOUT
        while time.time() < deadline:
            if lock_error(path) is None:
                sweep_stale_lock_files(directory, log)
                detail = (
                    f"cleared an orphan Excel lock on {os.path.basename(path)} "
                    f"(killed PID(s) {killed or 'none'}) and the write can proceed"
                )
                log(f"Excel lock guard: {detail}")
                if journal:
                    journal(status="self_healed", detail=detail, error=err, reason=reason)
                return CLEAR_SELF_HEALED
            time.sleep(_RELEASE_POLL)

        detail = (
            f"{os.path.basename(path)} is STILL locked after killing "
            f"{killed or 'no'} automation Excel process(es)"
            + (f"; a human-owned Excel (PID {human}) also has it" if human else "")
        )
        log(f"Excel lock guard: {detail}")
        if journal:
            journal(status="needs_attention", detail=detail, error=err, reason=reason)
        return CLEAR_HELD_BY_HUMAN if human else CLEAR_STILL_LOCKED


# ── the wrapper every write path should use ──────────────────────────────────

def safe_excel_write(path, write, log=_noop_log, journal=None, attempts=3, reason=""):
    """Run `write` (a zero-arg callable that saves `path`), self-healing lock errors.

    On a permission/sharing failure it clears the lock and retries. Any other
    exception is re-raised untouched on the first occurrence — this must never
    mask a real bug as a lock problem.

    If the lock genuinely cannot be cleared (a human has the workbook open) the
    final OSError is re-raised, so the caller's existing behaviour still applies:
    for attendance that means the row is persisted to the durable pending-sync
    store and retried every 60s — which is also what re-runs this guard, so the
    write completes by itself the moment the workbook is free. No human step, and
    no scheduler of its own.
    """
    last = None
    for attempt in range(1, max(1, attempts) + 1):
        try:
            return write()
        except OSError as err:
            if not _is_lock_error(err):
                raise
            last = err
            if attempt == attempts:
                break
            status = clear_excel_lock(path, log=log, journal=journal, reason=reason)
            if status not in _CLEARED:
                break
            log(
                f"Excel lock guard: retrying write to {os.path.basename(path)} "
                f"(attempt {attempt + 1}/{attempts}) after clearing the lock"
            )
    raise last


def _is_lock_error(err):
    """True if this OSError is a Windows sharing/permission violation.

    errno 13 (EACCES) covers PermissionError; winerror 32/33 are
    ERROR_SHARING_VIOLATION / ERROR_LOCK_VIOLATION, which surface when another
    process holds the file even though the ACLs allow us.
    """
    if isinstance(err, PermissionError):
        return True
    if getattr(err, "errno", None) == 13:
        return True
    return getattr(err, "winerror", None) in (32, 33)


def startup_recovery(paths, log=_noop_log, journal=None):
    """Clear any orphan lock left behind by a previous run, at process start.

    A crash or a hard restart is exactly when an orphan Excel gets stranded, and
    at startup there is definitionally no in-flight write of ours to disturb.
    """
    results = {}
    for path in paths:
        if not path:
            continue
        try:
            if lock_error(path) is None:
                # Still sweep: a stale ~$ makes Excel show a spurious
                # "file in use / read-only" prompt to the next human who opens it.
                sweep_stale_lock_files(os.path.dirname(os.path.abspath(path)) or ".", log)
                results[path] = CLEAR_NOT_LOCKED
                continue
            results[path] = clear_excel_lock(
                path, log=log, journal=journal, reason="startup_recovery")
        except Exception as err:  # never let recovery block startup
            log(f"Excel lock guard: startup recovery failed for {path}: {err}")
            results[path] = "error"
    return results
