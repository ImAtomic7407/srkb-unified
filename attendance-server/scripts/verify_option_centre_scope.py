"""
Per-centre remark and address lists — migration + proof.

Runs the same ensure_option_centre_scope() the server runs at startup (so this script and the
server can never drift), then asserts the three things that must hold for the live registration
path to be unaffected:

  1. every pre-existing row survived, and every one of them is shared (centre_id IS NULL)
  2. the shared list still cannot hold duplicates — the old UNIQUE(text) was replaced by an
     expression index, not by a plain UNIQUE(centre_id, text), which SQLite would not enforce
     across NULLs
  3. two centres can each own a row with the SAME text without colliding

Run: python scripts/verify_option_centre_scope.py
"""
import os
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

DB = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'database', 'attendance.db')

failed = 0


def check(name, cond):
    global failed
    if cond:
        print("ok:", name)
    else:
        print("FAIL:", name)
        failed += 1


def counts(conn):
    return {t: conn.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
            for t in ('remark_options', 'address_options')}


def main():
    pre = sqlite3.connect(DB)
    before = counts(pre)
    had_centre_col = {
        t: 'centre_id' in {r[1] for r in pre.execute(f"PRAGMA table_info({t})").fetchall()}
        for t in before
    }
    pre.close()
    print("before:", before, "already migrated:", had_centre_col)

    # Importing app runs its startup block, which calls ensure_option_centre_scope().
    import app  # noqa: F401

    conn = sqlite3.connect(DB)
    after = counts(conn)

    for t in ('remark_options', 'address_options'):
        cols = {r[1] for r in conn.execute(f"PRAGMA table_info({t})").fetchall()}
        check(f"{t} has centre_id", 'centre_id' in cols)
        # The seeder tops up remark_options when it is empty, so allow growth but never loss.
        check(f"{t} kept every row ({before[t]} -> {after[t]})", after[t] >= before[t])
        if not had_centre_col[t]:
            scoped = conn.execute(
                f"SELECT COUNT(*) FROM {t} WHERE centre_id IS NOT NULL"
            ).fetchone()[0]
            check(f"{t} backfilled entirely to shared ({scoped} scoped)", scoped == 0)

    # 2 — the shared list must still reject a duplicate.
    for t in ('remark_options', 'address_options'):
        sample = conn.execute(
            f"SELECT text FROM {t} WHERE centre_id IS NULL LIMIT 1"
        ).fetchone()
        if not sample:
            check(f"{t} has a shared row to test with", False)
            continue
        try:
            conn.execute(f"INSERT INTO {t} (text, centre_id) VALUES (?, NULL)", (sample[0],))
            conn.rollback()
            check(f"{t} rejects a duplicate shared row", False)
        except sqlite3.IntegrityError:
            check(f"{t} rejects a duplicate shared row", True)

    # 3 — the same text may exist once per centre.
    centres = [r[0] for r in conn.execute("SELECT centre_id FROM centres LIMIT 2").fetchall()]
    if len(centres) < 2:
        check("at least two centres to test scoping", False)
    else:
        probe = "__scope_probe__"
        try:
            conn.execute("INSERT INTO remark_options (text, centre_id) VALUES (?, ?)", (probe, centres[0]))
            conn.execute("INSERT INTO remark_options (text, centre_id) VALUES (?, ?)", (probe, centres[1]))
            check("two centres may each own the same text", True)
            try:
                conn.execute("INSERT INTO remark_options (text, centre_id) VALUES (?, ?)", (probe, centres[0]))
                check("one centre may not own it twice", False)
            except sqlite3.IntegrityError:
                check("one centre may not own it twice", True)
        finally:
            conn.rollback()
            conn.execute("DELETE FROM remark_options WHERE text = ?", (probe,))
            conn.commit()

    # 4 — the reader's filter: a centre sees shared + its own, never another centre's.
    if len(centres) >= 2:
        conn.execute("INSERT INTO remark_options (text, centre_id) VALUES ('__only_a__', ?)", (centres[0],))
        conn.commit()
        visible_b = [r[0] for r in conn.execute(
            "SELECT text FROM remark_options WHERE centre_id IS NULL OR centre_id = ?", (centres[1],)
        )]
        check("centre B cannot see centre A's private row", '__only_a__' not in visible_b)
        visible_a = [r[0] for r in conn.execute(
            "SELECT text FROM remark_options WHERE centre_id IS NULL OR centre_id = ?", (centres[0],)
        )]
        check("centre A can see its own private row", '__only_a__' in visible_a)
        check("centre A still sees the shared rows", len(visible_a) > 1)
        conn.execute("DELETE FROM remark_options WHERE text = '__only_a__'")
        conn.commit()

    conn.close()
    print("\nALL PASS" if failed == 0 else f"\n{failed} FAILED")
    sys.exit(0 if failed == 0 else 1)


if __name__ == '__main__':
    main()
