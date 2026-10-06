#!/usr/bin/env python3
"""
sheet_autosync.py
=================

Keeps every centre's Google Sheet current BY ITSELF, with no user action and no
cost to request latency.

WHY THIS EXISTS
---------------
Attendance rows already reach the sheet the moment they are marked: app.py drops
them on a background queue (excel_sync_worker) and the request returns straight
away. Everything else did not have that. Members, batch rosters and calling
sessions only appeared when somebody remembered to run

    python export_centre_sheet.py --centre <id> --apply

by hand. A sheet that is only correct when a human runs a script is a sheet
nobody can trust. This module closes that gap.

HOW IT WORKS
------------
A single daemon thread polls the databases and republishes what changed. It runs
entirely off the request path — no web request ever waits on Google.

Change detection is by CONTENT, not by hooking mutation sites. Every cycle it
rebuilds the intended tab contents and compares a hash per tab against the last
published hash. That choice is deliberate:

  * It cannot miss an update. Hooking every place that touches the roster means
    the day someone adds a new endpoint and forgets the hook, the sheet silently
    goes stale — the exact class of bug this whole effort started with.
  * It sees the OTHER system's writes. Calling data lives in the separate SRKB
    backend (a Node/Prisma process). Nothing in Flask is notified when a caller
    logs an outcome there, and polling content is the only thing that works
    across the process boundary without modifying that app.
  * Unchanged tabs cost ZERO Google API calls, so idling is genuinely free and
    the Sheets write quota is never at risk.

Two cadences, because the work is not uniform:

  FAST  (default 8s)  MEMBERS, BATCH - *, CALLING SUMMARY, CALL - *, SYNC SUMMARY.
                      These have no live mirror, so they are the actual gap.
                      Cheap to rebuild: a few hundred roster rows.
  FULL  (default 15m) The above plus MASTER_ALL and every per-session attendance
                      tab. Attendance is already mirrored live row-by-row; this
                      slower pass exists so any drift (a failed append, a manual
                      DB edit, a deleted row) heals on its own.

Stale tabs are pruned: delete a calling session and its CALL - tab disappears
instead of lingering with data that looks current but is not.

The thread is started from app.py at boot; see start_autosync().
"""

import hashlib
import json
import os
import sqlite3
import threading
import time

import export_centre_sheet as X

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
STATE_PATH = os.path.join(SCRIPT_DIR, "Server file", "sheet_autosync_state.json")

FAST_INTERVAL = int(os.environ.get("SHEET_AUTOSYNC_FAST_SECONDS", "8"))
FULL_INTERVAL = int(os.environ.get("SHEET_AUTOSYNC_FULL_SECONDS", str(15 * 60)))

# After a change is spotted, wait for this much quiet before publishing. Marking
# 40 people in a row should produce one rebuild, not forty.
DEBOUNCE_SECONDS = float(os.environ.get("SHEET_AUTOSYNC_DEBOUNCE", "4"))

# A centre that fails repeatedly backs off instead of spinning against the API.
# The pause is a COOLDOWN, not a permanent stop: transient causes (quota, a
# dropped connection, an expired token) clear by themselves, and requiring a
# restart to recover would mean a sheet quietly stops updating until someone
# notices — the exact failure mode this module exists to remove.
MAX_CONSECUTIVE_FAILURES = 5
FAILURE_COOLDOWN_SECONDS = int(os.environ.get("SHEET_AUTOSYNC_COOLDOWN", "300"))

# Google allows 60 write requests per minute per user. Publishing one tab costs
# three (clear + update + freeze), so ~20 tabs/min is the hard ceiling; 15 leaves
# headroom for the live attendance mirror, which shares the same quota. Without
# this the very first pass — 159 tabs across three centres, since nothing has
# been published by this module yet — would hit 429s and half-write the sheets.
# Instead the backlog drains over a few minutes and then sits at zero forever,
# because unchanged tabs are never rewritten.
MAX_TABS_PER_MINUTE = int(os.environ.get("SHEET_AUTOSYNC_TABS_PER_MIN", "15"))
MAX_TABS_PER_CYCLE = int(os.environ.get("SHEET_AUTOSYNC_TABS_PER_CYCLE", "8"))

_write_times = []
_write_lock = threading.Lock()


def _await_write_slot():
    """Block this worker thread until another tab write fits inside the quota.

    Only ever called from the background thread, never from a request.
    """
    while True:
        with _write_lock:
            now = time.monotonic()
            while _write_times and now - _write_times[0] > 60.0:
                _write_times.pop(0)
            if len(_write_times) < MAX_TABS_PER_MINUTE:
                _write_times.append(now)
                return
            wait = 60.0 - (now - _write_times[0]) + 0.05
        time.sleep(max(wait, 0.1))

_state_lock = threading.Lock()
_wake = threading.Event()
_thread = None
_log = print

# centre_id -> monotonic deadline. Set by request_refresh() to pull a centre's
# next check forward; the poll loop would find it anyway, just later.
_nudged = {}
_nudge_lock = threading.Lock()


def _say(msg):
    try:
        _log(f"[sheet-autosync] {msg}")
    except Exception:
        pass


# --------------------------------------------------------------------------
# Published-state bookkeeping
# --------------------------------------------------------------------------

def _load_state():
    try:
        with open(STATE_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    except (FileNotFoundError, ValueError, OSError):
        return {}


def _save_state(state):
    tmp = STATE_PATH + ".tmp"
    try:
        os.makedirs(os.path.dirname(STATE_PATH), exist_ok=True)
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(state, f, ensure_ascii=False, indent=1, default=str)
        os.replace(tmp, STATE_PATH)
    except OSError as e:
        # Losing the state file only costs one redundant republish next cycle.
        _say(f"could not persist state: {e}")


def _tab_hash(headers, rows):
    """Stable fingerprint of a tab's rendered contents."""
    payload = json.dumps([list(headers)] + [[X.clean(c) for c in r] for r in rows],
                         ensure_ascii=False, sort_keys=False, default=str)
    return hashlib.sha1(payload.encode("utf-8")).hexdigest()


def _overview_hash(overview_rows):
    """Hash the index tab EXCLUDING its 'Updated' timestamp.

    The stamp changes every single cycle. Including it would mark the tab dirty
    forever and turn an idle system into a steady stream of API writes.
    """
    body = [r for r in overview_rows if not (r and str(r[0]) == "Updated")]
    return _tab_hash(["Field", "Value", "Rows"], body)


# --------------------------------------------------------------------------
# Publishing
# --------------------------------------------------------------------------

def _publish_centre(conn, client_factory, centre_id, state, full):
    """Republish only what actually changed. Returns (written, pruned, error)."""
    centre, plan, overview_rows = X.build_plan(
        conn, centre_id, include_attendance=full, quiet=True)
    if not centre:
        return 0, 0, None  # no sheet linked, or centre gone — nothing to do

    _cid, _name, sheet_id, _enabled = centre
    centre_state = state.setdefault(centre_id, {})
    # Keyed by sheet id: relinking a centre to a NEW spreadsheet must republish
    # everything rather than trusting hashes from the old one.
    tabs = centre_state.get("tabs", {}) if centre_state.get("sheet_id") == sheet_id else {}

    wanted = {}
    for title, headers, rows, _desc in plan:
        wanted[title] = _tab_hash(headers, rows)
    summary_hash = _overview_hash(overview_rows)

    changed = [(t, h, r) for t, h, r, _d in plan if tabs.get(t) != wanted[t]]
    summary_changed = tabs.get(X.INDEX_TAB) != summary_hash

    # On a fast pass the plan omits attendance tabs, so their absence must not be
    # read as "deleted" — only a full pass is allowed to prune.
    stale = []
    if full:
        stale = [t for t in tabs if t not in wanted and t != X.INDEX_TAB]

    if not changed and not summary_changed and not stale:
        return 0, 0, None

    # Spread a large backlog across cycles instead of firing hundreds of writes
    # at once. Progress is saved each cycle, so this resumes exactly where it
    # left off — including across a restart.
    backlog = max(0, len(changed) - MAX_TABS_PER_CYCLE)
    if backlog:
        _say(f"{centre_id}: {len(changed)} tabs to publish; doing "
             f"{MAX_TABS_PER_CYCLE} now, {backlog} queued for the next cycles")
        changed = changed[:MAX_TABS_PER_CYCLE]

    sh = client_factory().open_by_key(sheet_id)
    # One metadata read for the whole cycle. Resolving each tab individually cost
    # a read apiece and was what pushed the first big publish into 429 territory.
    # Keyed lowercase because Google's tab-name uniqueness is case-insensitive.
    live = sh.worksheets()
    ws_lookup = {ws.title.lower(): ws for ws in live}
    live_titles = {ws.title for ws in live}

    def _is_someone_elses(title):
        """True if a tab with this name exists under DIFFERENT capitalisation and
        we never published it — i.e. a person made it. Centre B has exactly
        such a tab ('Summary', 995 rows of hand-kept data). Clearing it to make
        room for ours would destroy real work, so we skip and say so."""
        if title in live_titles:
            return False
        clash = ws_lookup.get(title.lower())
        return clash is not None and clash.title not in tabs

    written = 0
    for title, _h, rows in changed:
        if _is_someone_elses(title):
            other = ws_lookup[title.lower()].title
            _say(f"{centre_id}: SKIPPING '{title}' — the sheet already has a tab "
                 f"named '{other}' that this sync did not create. Leaving it "
                 f"untouched; rename it if you want this tab published.")
            tabs[title] = wanted[title]  # don't retry every cycle
            continue
        headers = next(hh for tt, hh, _rr, _dd in plan if tt == title)
        _await_write_slot()
        X.write_tab(sh, title, headers, rows, ws_lookup=ws_lookup)
        tabs[title] = wanted[title]
        written += 1
        _say(f"{centre_id}: updated '{title}' ({len(rows)} rows)")

    pruned = 0
    if full and (stale or changed) and not backlog:
        removed = X.prune_stale_tabs(sh, list(wanted.keys()), quiet=True)
        for t in removed:
            tabs.pop(t, None)
            _say(f"{centre_id}: removed stale tab '{t}'")
        pruned = len(removed)
        for t in stale:
            tabs.pop(t, None)

    # SUMMARY last, and only once the backlog is drained: it indexes every other
    # tab, so it must never advertise a state the rest of the sheet has not
    # reached yet.
    if not backlog and (written or pruned or summary_changed):
        if _is_someone_elses(X.INDEX_TAB):
            _say(f"{centre_id}: not writing the index tab — '{X.INDEX_TAB}' "
                 f"collides with an existing tab this sync did not create.")
            tabs[X.INDEX_TAB] = summary_hash
        else:
            _await_write_slot()
            X.write_tab(sh, X.INDEX_TAB, ["Field", "Value", "Rows"], overview_rows,
                        ws_lookup=ws_lookup)
            written += 1
        tabs[X.INDEX_TAB] = summary_hash

    centre_state["sheet_id"] = sheet_id
    centre_state["tabs"] = tabs
    centre_state["last_published"] = time.strftime("%Y-%m-%d %H:%M:%S")
    return written, pruned, backlog


# Escape hatch: comma-separated centre_ids this syncer must leave alone. The
# sheet keeps whatever is in it and only the manual exporter can touch it. Use
# this if a centre's sheet is hand-maintained and must not be rewritten from the
# database.
EXCLUDED_CENTRES = {c.strip() for c in
                    os.environ.get("SHEET_AUTOSYNC_EXCLUDE", "").split(",") if c.strip()}


def _linked_centres(conn):
    rows = [r[0] for r in conn.execute(
        "SELECT centre_id FROM centres WHERE status='active' "
        "AND google_sheet_id IS NOT NULL AND google_sheet_enabled=1 "
        "ORDER BY centre_id")]
    return [c for c in rows if c not in EXCLUDED_CENTRES]


# --------------------------------------------------------------------------
# The worker
# --------------------------------------------------------------------------

def _worker():
    from sheets_handler import get_sheets_client

    _client = {"c": None}

    def client_factory():
        # Built lazily and reused: authenticating per cycle would be slow and
        # would burn quota for nothing.
        if _client["c"] is None:
            _client["c"] = get_sheets_client()
        return _client["c"]

    state = _load_state()
    failures = {}
    cooldown_until = {}
    last_full = 0.0
    _say(f"started (fast={FAST_INTERVAL}s, full={FULL_INTERVAL}s, debounce={DEBOUNCE_SECONDS}s)")

    while True:
        try:
            now = time.monotonic()
            full = (now - last_full) >= FULL_INTERVAL
            conn = sqlite3.connect(f"file:{X.DB_PATH}?mode=ro", uri=True)
            try:
                centres = _linked_centres(conn)
                total_written = 0
                any_backlog = False
                for centre_id in centres:
                    if failures.get(centre_id, 0) >= MAX_CONSECUTIVE_FAILURES:
                        if now - cooldown_until.get(centre_id, 0) < 0:
                            continue
                        _say(f"{centre_id}: cooldown over, retrying")
                        failures[centre_id] = 0
                    try:
                        written, _pruned, backlog = _publish_centre(
                            conn, client_factory, centre_id, state, full)
                        total_written += written
                        any_backlog = any_backlog or bool(backlog)
                        failures[centre_id] = 0
                    except Exception as e:
                        failures[centre_id] = failures.get(centre_id, 0) + 1
                        _say(f"{centre_id}: publish failed ({failures[centre_id]}/"
                             f"{MAX_CONSECUTIVE_FAILURES}): {e}")
                        # A dead/expired client is the usual cause; rebuild it.
                        _client["c"] = None
                        if failures[centre_id] >= MAX_CONSECUTIVE_FAILURES:
                            cooldown_until[centre_id] = now + FAILURE_COOLDOWN_SECONDS
                            _say(f"{centre_id}: backing off for "
                                 f"{FAILURE_COOLDOWN_SECONDS}s after repeated failures; "
                                 f"data is safe in the database and this will retry itself.")
            finally:
                conn.close()

            # A truncated full pass has not really completed, so don't reset the
            # clock — come straight back and finish the remaining tabs.
            if full and not any_backlog:
                last_full = now
            if total_written:
                with _state_lock:
                    _save_state(state)
        except Exception as e:
            any_backlog = False
            _say(f"cycle error (continuing): {e}")

        if any_backlog:
            continue  # more tabs waiting; the rate limiter sets the real pace

        # Sleep until the next tick, but wake early if someone nudged us. The
        # debounce is applied to the nudge, not the poll, so a burst of marks
        # collapses into a single publish.
        with _nudge_lock:
            pending = bool(_nudged)
            _nudged.clear()
        if pending:
            time.sleep(DEBOUNCE_SECONDS)
        else:
            _wake.wait(timeout=FAST_INTERVAL)
            _wake.clear()


def request_refresh(centre_id=None, reason=""):
    """Ask for a refresh soon. Returns immediately; never touches the network.

    Safe to call from a request handler — it sets a flag and returns. Purely an
    optimisation: the poll loop would notice the change on its own within
    FAST_INTERVAL anyway, so a missed call degrades latency, never correctness.
    """
    try:
        with _nudge_lock:
            _nudged[str(centre_id or "*")] = reason
        _wake.set()
    except Exception:
        pass


def start_autosync(log=None):
    """Start the background syncer once. Idempotent."""
    global _thread, _log
    if log is not None:
        _log = log
    if _thread is not None and _thread.is_alive():
        return _thread
    if os.environ.get("SHEET_AUTOSYNC_DISABLED", "").strip() == "1":
        _say("disabled via SHEET_AUTOSYNC_DISABLED=1")
        return None
    _thread = threading.Thread(target=_worker, name="sheet-autosync", daemon=True)
    _thread.start()
    return _thread


def status():
    """Current published state, for the host dashboard / debugging."""
    state = _load_state()
    return {
        "running": bool(_thread and _thread.is_alive()),
        "fast_interval_seconds": FAST_INTERVAL,
        "full_interval_seconds": FULL_INTERVAL,
        "centres": {
            cid: {
                "sheet_id": v.get("sheet_id"),
                "last_published": v.get("last_published"),
                "tabs_tracked": len(v.get("tabs", {})),
            }
            for cid, v in state.items()
        },
    }


if __name__ == "__main__":
    # Run standalone for a one-off check: prints what it would publish.
    import pprint
    start_autosync()
    try:
        while True:
            time.sleep(10)
            pprint.pprint(status())
    except KeyboardInterrupt:
        pass
