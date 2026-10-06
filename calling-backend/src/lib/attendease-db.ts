import Database from "better-sqlite3";
import { env } from "../config/env.js";

// Read-only connection to AttendEase's SQLite database.
// Used by: reconcile job, lazy-create, attendance cross-reads.
// Never write to this DB — AttendEase owns it.

let _db: Database.Database | null = null;

export const getAttendeaseDb = (): Database.Database => {
  if (!env.attendeaseDbPath) {
    throw new Error("ATTENDEASE_DB_PATH is not configured");
  }
  if (!_db) {
    _db = new Database(env.attendeaseDbPath, { readonly: true, fileMustExist: true });
  }
  return _db;
};

// Raw AttendEase row shapes (only the columns we use)

export type AECentreRow = {
  centre_id: string;
  name: string;
  status: string;
};

export type AEBatchRow = {
  id: number;
  name: string;
  centre_id: string;
  category: string;        // "youth" | "congregation" — Phase 4 adds this column; defaults to "youth" if missing
  is_default: number;      // SQLite boolean (0/1)
  status: string;          // "active" | "archived"
};

export type AESessionRow = {
  session_id: string;
  display_name: string;
  batch_id: number;
  centre_id: string;
  category: string;
  date: string | null;
  status: string;
};

export type AEUserRow = {
  username: string;
  role: string;
  status: string;
};

export type AEUserAccessRow = {
  username: string;
  centre_id: string;
  role: string;
  status: string | null;
  display_name: string | null;
  last_seen_at: string | null;
};

// "users" table in AttendEase is the master person directory (students/attendees),
// not to be confused with AEUserRow (registerer_accounts = AttendEase login accounts).
export type AEPersonRow = {
  mobile: string;
  name: string;
  address: string | null;
  occupation: string | null;
  rounds: string | null;
  cheating: string | null;
  remark: string | null;
};

// "attendance" table is the raw check-in log - one row per scan, fields denormalized.
export type AEAttendanceRow = {
  id: number;
  mobile: string;
  date: string | null;
  batch_name: string | null;
  session_id: string | null;
  timestamp: string | null;
  remark: string | null;
  rounds: string | null;
  cheating: string | null;
  name: string | null;
  address: string | null;
  occupation: string | null;
  centre_id: string;
};

// "centre_roster" table is the current batch-membership roster (who is in which batch right now).
export type AERosterRow = {
  id: number;
  mobile: string;
  centre_id: string;
  /** null for centre-level rows (people imported without a batch). */
  batch_id: number | null;
  scope_key: string;
  batch_name: string;
  /**
   * youth | congregation, or null. Mirrors batches.category for a row with a
   * batch; for a centre-level row it is the ONLY record of the track. Null on
   * the legacy centre-level rows written before the Inject page existed.
   */
  category: string | null;
  name: string;
  address: string | null;
  occupation: string | null;
  rounds: string | null;
  remark: string | null;
  cheating: string | null;
  status: string;
  created_at: string;
  updated_at: string;
};

export const attendeaseRepo = {
  allCentres: (): AECentreRow[] => {
    return getAttendeaseDb().prepare("SELECT centre_id, name, status FROM centres").all() as AECentreRow[];
  },

  allBatches: (): AEBatchRow[] => {
    // category/is_default/status columns added in Phase 4; use COALESCE for backward compat
    return getAttendeaseDb()
      .prepare(`SELECT id, name, centre_id,
        COALESCE(category, 'youth') AS category,
        COALESCE(is_default, 0) AS is_default,
        COALESCE(status, 'active') AS status
        FROM batches`)
      .all() as AEBatchRow[];
  },

  allSessions: (): AESessionRow[] => {
    return getAttendeaseDb()
      .prepare(`SELECT session_id, display_name, batch_id, centre_id,
        COALESCE(category, 'youth') AS category,
        date,
        COALESCE(status, 'active') AS status
        FROM session_mappings`)
      .all() as AESessionRow[];
  },

  batchById: (batchId: number): AEBatchRow | undefined => {
    return getAttendeaseDb()
      .prepare(`SELECT id, name, centre_id,
        COALESCE(category, 'youth') AS category,
        COALESCE(is_default, 0) AS is_default,
        COALESCE(status, 'active') AS status
        FROM batches WHERE id = ?`)
      .get(batchId) as AEBatchRow | undefined;
  },

  sessionById: (sessionId: string): AESessionRow | undefined => {
    return getAttendeaseDb()
      .prepare(`SELECT session_id, display_name, batch_id, centre_id,
        COALESCE(category, 'youth') AS category,
        date,
        COALESCE(status, 'active') AS status
        FROM session_mappings WHERE session_id = ?`)
      .get(sessionId) as AESessionRow | undefined;
  },

  allUsers: (): AEUserRow[] => {
    return getAttendeaseDb()
      .prepare("SELECT username, role, status FROM registerer_accounts")
      .all() as AEUserRow[];
  },

  allUserAccess: (): AEUserAccessRow[] => {
    return getAttendeaseDb()
      .prepare("SELECT username, centre_id, COALESCE(role, 'registerer') AS role, status, display_name, last_seen_at FROM centre_access")
      .all() as AEUserAccessRow[];
  },

  centreById: (centreId: string): AECentreRow | undefined => {
    return getAttendeaseDb()
      .prepare("SELECT centre_id, name, status FROM centres WHERE centre_id = ?")
      .get(centreId) as AECentreRow | undefined;
  },

  userByUsername: (username: string): AEUserRow | undefined => {
    return getAttendeaseDb()
      .prepare("SELECT username, role, status FROM registerer_accounts WHERE username = ?")
      .get(username) as AEUserRow | undefined;
  },

  // "users" table - master person directory. Named allPersons to avoid clashing
  // with allUsers() above (which reads registerer_accounts, i.e. login accounts).
  allPersons: (): AEPersonRow[] => {
    return getAttendeaseDb()
      .prepare("SELECT mobile, name, address, occupation, rounds, cheating, remark FROM users")
      .all() as AEPersonRow[];
  },

  personByMobile: (mobile: string): AEPersonRow | undefined => {
    return getAttendeaseDb()
      .prepare("SELECT mobile, name, address, occupation, rounds, cheating, remark FROM users WHERE mobile = ?")
      .get(mobile) as AEPersonRow | undefined;
  },

  allAttendance: (filters?: {
    centreId?: string;
    batchName?: string;
    sessionId?: string;
    mobile?: string;
    since?: string;
    date?: string;
  }): AEAttendanceRow[] => {
    const clauses: string[] = [];
    const params: Record<string, string> = {};
    if (filters?.centreId) {
      clauses.push("centre_id = @centreId");
      params.centreId = filters.centreId;
    }
    if (filters?.batchName) {
      clauses.push("batch_name = @batchName");
      params.batchName = filters.batchName;
    }
    if (filters?.sessionId) {
      clauses.push("session_id = @sessionId");
      params.sessionId = filters.sessionId;
    }
    if (filters?.mobile) {
      clauses.push("mobile = @mobile");
      params.mobile = filters.mobile;
    }
    if (filters?.date) {
      clauses.push("date = @date");
      params.date = filters.date;
    } else if (filters?.since) {
      clauses.push("date >= @since");
      params.since = filters.since;
    }
    const where = clauses.length > 0 ? `WHERE ${clauses.join(" AND ")}` : "";
    return getAttendeaseDb()
      .prepare(
        `SELECT id, mobile, date, batch_name, session_id, timestamp, remark, rounds, cheating, name, address, occupation, centre_id
         FROM attendance ${where}
         ORDER BY date DESC, timestamp DESC`,
      )
      .all(params) as AEAttendanceRow[];
  },

  allRosterMembers: (filters?: {
    centreId?: string;
    batchId?: number;
    /**
     * Centre-level rows (batch_id IS NULL). These are invisible to the per-batch
     * loop in reconcileRosterMembers, so before this existed a centre-level import
     * reached the calling backend never — not in 24h, not ever.
     *
     * Deliberately paired with `category IS NOT NULL`: the ~1,525 pre-existing
     * centre-level rows carry no category, and materialising them all at once
     * would dump them into callers' queues as fresh never-called work. Only rows
     * written by the Inject page (which always records a category) are picked up.
     */
    batchless?: boolean;
  }): AERosterRow[] => {
    const clauses: string[] = ["status = 'active'"];
    const params: Record<string, string | number> = {};
    if (filters?.centreId) {
      clauses.push("centre_id = @centreId");
      params.centreId = filters.centreId;
    }
    if (filters?.batchless) {
      clauses.push("batch_id IS NULL", "category IS NOT NULL");
    } else if (filters?.batchId !== undefined) {
      clauses.push("batch_id = @batchId");
      params.batchId = filters.batchId;
    }
    const where = `WHERE ${clauses.join(" AND ")}`;
    return getAttendeaseDb()
      .prepare(
        `SELECT id, mobile, centre_id, batch_id, scope_key, batch_name, name, address, occupation, rounds, remark, cheating, category, status, created_at, updated_at
         FROM centre_roster ${where}`,
      )
      .all(params) as AERosterRow[];
  },

  // Returns all attendance rows for a batch by joining attendance ↔ session_mappings on session_id
  // (preferred — more accurate than batch_name, which is denormalized and inconsistent), UNIONed
  // with a batch_name-text fallback for attendance rows that have no session mapping at all (e.g.
  // legacy/manually-marked attendance never taken through the session-based flow — the common case
  // in practice). The plain INNER JOIN this replaced silently dropped every such row, which is why
  // filtering by a batch could make almost everyone in it disappear.
  allAttendanceByBatch: (filters: { centreId: string; batchId: number }): AEAttendanceRow[] => {
    const db = getAttendeaseDb();
    const mapped = db
      .prepare(
        `SELECT a.id, a.mobile, a.date, a.batch_name, a.session_id, a.timestamp,
                a.remark, a.rounds, a.cheating, a.name, a.address, a.occupation, a.centre_id
         FROM attendance a
         JOIN session_mappings sm ON sm.session_id = a.session_id
         WHERE a.centre_id = @centreId AND sm.batch_id = @batchId`,
      )
      .all({ centreId: filters.centreId, batchId: filters.batchId }) as AEAttendanceRow[];

    const batchRow = db
      .prepare(`SELECT name FROM batches WHERE id = @batchId`)
      .get({ batchId: filters.batchId }) as { name: string } | undefined;

    const unmapped = batchRow
      ? (db
          .prepare(
            `SELECT a.id, a.mobile, a.date, a.batch_name, a.session_id, a.timestamp,
                    a.remark, a.rounds, a.cheating, a.name, a.address, a.occupation, a.centre_id
             FROM attendance a
             WHERE a.centre_id = @centreId
               AND a.batch_name = @batchName
               AND NOT EXISTS (SELECT 1 FROM session_mappings sm2 WHERE sm2.session_id = a.session_id)`,
          )
          .all({ centreId: filters.centreId, batchName: batchRow.name }) as AEAttendanceRow[])
      : [];

    const seen = new Set(mapped.map((r) => r.id));
    const merged = [...mapped, ...unmapped.filter((r) => !seen.has(r.id))];
    merged.sort((a, b) => `${b.date}${b.timestamp}`.localeCompare(`${a.date}${a.timestamp}`));
    return merged;
  },

  // Returns map of batchId → unique-attendee-count for all batches in a centre. Same mapped +
  // unmapped-fallback merge as allAttendanceByBatch above, for the same reason.
  uniqueAttendeeCountsByBatch: (centreId: string): Map<number, number> => {
    const db = getAttendeaseDb();
    const mappedRows = db
      .prepare(
        `SELECT sm.batch_id AS batch_id, a.mobile AS mobile
         FROM session_mappings sm
         JOIN attendance a ON a.session_id = sm.session_id AND a.centre_id = @centreId`,
      )
      .all({ centreId }) as Array<{ batch_id: number; mobile: string }>;

    const unmappedRows = db
      .prepare(
        `SELECT b.id AS batch_id, a.mobile AS mobile
         FROM attendance a
         JOIN batches b ON b.name = a.batch_name
         WHERE a.centre_id = @centreId
           AND NOT EXISTS (SELECT 1 FROM session_mappings sm2 WHERE sm2.session_id = a.session_id)`,
      )
      .all({ centreId }) as Array<{ batch_id: number; mobile: string }>;

    const byBatch = new Map<number, Set<string>>();
    for (const row of [...mappedRows, ...unmappedRows]) {
      const set = byBatch.get(row.batch_id) ?? new Set<string>();
      set.add(row.mobile);
      byBatch.set(row.batch_id, set);
    }
    return new Map([...byBatch.entries()].map(([batchId, mobiles]) => [batchId, mobiles.size]));
  },
};