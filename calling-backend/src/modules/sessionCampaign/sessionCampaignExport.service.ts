// Exportable report for one calling session.
//
// Four questions the centre asked to be able to answer off a finished session, each becoming one
// sort/filter mode of the same sheet rather than four different sheets:
//
//   by_caller        — who called which numbers
//   classes_attended — how many classes each number has attended
//   new_old          — whose first ever class this was vs who had already attended before
//   direct_called    — who turned up on their own vs who was called
//
// Every mode emits the SAME columns. Only the row order (and, for new_old, which rows) changes.
// That is deliberate: the client compares these sheets side by side, and a column that appears in
// one export and not another makes them non-comparable.

import { SessionCallStatus } from "@prisma/client";
import { rawPrisma } from "../../config/prisma.js";
import { ApiError } from "../../lib/api-error.js";
import { attendeaseRepo } from "../../lib/attendease-db.js";
import { attendeasePersonNames, mobileKey } from "../../lib/person-directory.js";

export type ExportField = "by_caller" | "classes_attended" | "new_old" | "direct_called" | "batch";
export type ExportOrder = "asc" | "desc";
export type ExportCohort = "new" | "old";

// Three values, not two. A person who has never attended anything cannot have had a FIRST class,
// so calling them "New" is a category error - and it was swamping the column: of 217 "New" rows on
// campaign 60, 201 were people who had been called and had never once turned up, leaving only 16
// actual first-timers. Splitting them out is what makes New mean what the client says it means.
export type Cohort = "New" | "Old" | "Never attended";
export const NEVER_ATTENDED: Cohort = "Never attended";

// Shown wherever a value genuinely does not exist for this row (a walk-in has no caller, no
// response and no call status). An empty cell reads as "we lost it"; a dash reads as "not
// applicable", which is what it is.
const NA = "—";

// AttendEase stores every attendance date as a strict YYYY-MM-DD string (verified: 3,497 of 3,497
// rows, none null), so plain string comparison is a correct date comparison. This formats a JS Date
// the same way using LOCAL parts — toISOString() is UTC and would render an evening IST class as
// the previous day, which can flip a person's New/Old classification.
const isoDate = (value: Date): string => {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}`;
};

export type SessionReportRow = {
  name: string;
  mobile: string;
  caller: string;
  /** "Called" | "Direct" — Direct means they attended with no assignment to any caller. */
  participation: "Called" | "Direct";
  attendedThisClass: boolean;
  callResponse: string;
  callStatus: string;
  /** DISTINCT classes at this centre, lifetime. Not raw attendance rows — see buildSessionReport. */
  classesAttended: number;
  classesInBatch: number;
  lastAttended: string;
  /** Batch(es) this number belongs to on the calling side; the campaign's own batch first. */
  batch: string;
  /**
   * "New" | "Old" | "Never attended" — New means THIS class was their first ever class at this
   * centre. Decided from ATTENDANCE, not from calling history; see the cohort block in
   * buildSessionReport.
   */
  cohort: Cohort;
};

export type SessionReportMeta = {
  campaignId: number;
  sessionName: string;
  batchName: string;
  centreName: string;
  status: string;
  scheduledAt: Date;
  totalRows: number;
  calledCount: number;
  directCount: number;
};

// Header text is what the client reads in Excel, so it is spelled for them, not for the schema.
export const REPORT_HEADERS = [
  "Name",
  "Mobile",
  "Caller",
  "Direct or Called",
  "Attended this class",
  "Call response",
  "Call status",
  "Total class",
  "Class in this batch",
  "Last class attended",
  "New or Old",
  "Batch",
] as const;

export const reportRowToCsvRecord = (row: SessionReportRow): Record<string, string | number> => ({
  Name: row.name,
  Mobile: row.mobile,
  Caller: row.caller,
  "Direct or Called": row.participation,
  "Attended this class": row.attendedThisClass ? "Yes" : "No",
  "Call response": row.callResponse,
  "Call status": row.callStatus,
  "Total class": row.classesAttended,
  "Class in this batch": row.classesInBatch,
  "Last class attended": row.lastAttended,
  "New or Old": row.cohort,
  Batch: row.batch,
});

/**
 * Build every row of the report for one campaign, unsorted and unfiltered.
 *
 * Reads are batched deliberately: the whole centre's attendance is pulled ONCE and aggregated in
 * memory rather than asking per person. getAttendanceCountForMobile would issue a centre lookup
 * plus a query for each of 259 people on campaign 60 — the same shape the pool bridge service
 * already avoids, for the same reason.
 */
export const buildSessionReport = async (
  campaignId: number,
  centreId: number,
): Promise<{ meta: SessionReportMeta; rows: SessionReportRow[] }> => {
  const campaign = await rawPrisma.sessionCampaign.findUnique({
    where: { id: campaignId },
    include: { batch: { select: { id: true, name: true } } },
  });
  // Same 404-on-foreign-centre shape as every sibling handler. The centre is taken from the
  // request context, never from the client.
  if (!campaign || campaign.centreId !== centreId) throw new ApiError(404, "Session campaign not found");

  const centre = await rawPrisma.centre.findUnique({
    where: { id: centreId },
    select: { code: true, name: true },
  });
  const centreCode = centre?.code ?? null;

  const assignments = await rawPrisma.sessionCallAssignment.findMany({
    where: { campaignId, removedAt: null },
    include: {
      batchMember: { select: { mobile: true } },
      caller: { select: { id: true, name: true } },
    },
  });

  // ── Attendance aggregation ──────────────────────────────────────────────────────────────────
  // COUNT DISTINCT CLASSES, NOT ATTENDANCE ROWS. `attendance` is a raw check-in log — one row per
  // scan — and the same person can be scanned twice for one class. Measured on live data before
  // choosing: Centre B has 240 (mobile, session) pairs with more than one row, and its most
  // active number holds 149 rows across 100 actual classes. Counting rows would have told the
  // client that person attended 149 classes, a 49% overcount, on a sheet whose entire purpose is
  // that number. Keyed on session_id, falling back to the date for any row without one.
  const classesByKey = new Map<string, Set<string>>();
  const lastDateByKey = new Map<string, string>();
  const firstDateByKey = new Map<string, string>();
  const addClass = (target: Map<string, Set<string>>, key: string, classId: string) => {
    let set = target.get(key);
    if (!set) {
      set = new Set<string>();
      target.set(key, set);
    }
    set.add(classId);
  };
  const classIdOf = (row: { session_id: string | null; date: string | null }): string | null => {
    const sid = row.session_id?.trim();
    if (sid) return sid;
    return row.date ? `date:${row.date}` : null;
  };

  // AttendEase is a separate database on a separate system; if it is unreachable the report must
  // still come out with the calling-side facts rather than 500ing. Attendance columns then read 0.
  let attendeaseReadable = Boolean(centreCode);

  // -- THIS class first, because its date bounds everything else -------------------------------
  // Who was physically present at THIS class, and what AttendEase knows about them. Walk-ins are
  // discovered here - they exist nowhere on the calling side.
  const attendedKeys = new Set<string>();
  const attendeeInfo = new Map<string, { mobile: string; name: string | null; batchName: string | null }>();
  // The date of THIS class, read from its own attendance rows rather than from scheduledAt - that
  // is when the calling session was scheduled, which can differ from the day people were scanned in.
  let observedSessionStart: string | null = null;
  let observedSessionEnd: string | null = null;
  if (centreCode) {
    try {
      for (const row of attendeaseRepo.allAttendance({ centreId: centreCode, sessionId: campaign.aeSessionId })) {
        const key = mobileKey(row.mobile);
        attendedKeys.add(key);
        if (row.date && (!observedSessionStart || row.date < observedSessionStart)) {
          observedSessionStart = row.date;
        }
        if (row.date && (!observedSessionEnd || row.date > observedSessionEnd)) {
          observedSessionEnd = row.date;
        }
        if (!attendeeInfo.has(key)) {
          attendeeInfo.set(key, { mobile: row.mobile, name: row.name, batchName: row.batch_name });
        }
      }
    } catch {
      attendeaseReadable = false;
    }
  }

  // -- AS OF THIS CLASS, NOT AS OF TODAY -------------------------------------------------------
  // This sheet describes one class, so every figure on it describes the world as it stood at that
  // class. Without the bound below, `Total class` and `Last class attended` were read at EXPORT
  // time while New/Old was read as of the session, and the two clocks contradicted each other on
  // the same row: on the 01-08-26 sheet one attendee printed "Direct, New, Total class 4, Last
  // class attended 2026-08-23" - New because that class WAS their first, 4 because three more had
  // happened by the time the button was pressed. 33 of that sheet's 312 rows named a class later
  // than the class the sheet is about.
  //
  // Bounding here also makes a re-export reproducible: the same finished session exports the same
  // numbers next month as it did on the day, which is what makes two sheets comparable at all.
  // `sessionDate` anchors New/Old: it is when this class STARTED, so someone whose own first
  // attendance is a later date within the same series is still New at its start.
  //
  // `snapshotDate` bounds every aggregation: it is the LAST date of this session. For an ordinary
  // one-day class the two are the same value and nothing below changes. They diverge only for a
  // multi-date session, and there the difference is load-bearing: bounding the aggregations at the
  // START date would drop every row of a person who attended only on a later date of this very
  // session, printing "Attended this class = Yes" beside "Total class = 0" and "Last class
  // attended = —". That is the same self-contradicting row this section exists to prevent.
  const sessionDate = observedSessionStart ?? isoDate(campaign.scheduledAt);
  const snapshotDate = observedSessionEnd ?? isoDate(campaign.scheduledAt);
  const onOrBefore = (date: string | null): boolean => Boolean(date && date <= snapshotDate);

  if (centreCode && attendeaseReadable) {
    try {
      for (const row of attendeaseRepo.allAttendance({ centreId: centreCode })) {
        if (!onOrBefore(row.date)) continue;
        const key = mobileKey(row.mobile);
        const classId = classIdOf(row);
        if (classId) addClass(classesByKey, key, classId);
        if (row.date && (!lastDateByKey.has(key) || row.date > lastDateByKey.get(key)!)) {
          lastDateByKey.set(key, row.date);
        }
        if (row.date && (!firstDateByKey.has(key) || row.date < firstDateByKey.get(key)!)) {
          firstDateByKey.set(key, row.date);
        }
      }
    } catch {
      attendeaseReadable = false;
    }
  }

  const batchClassesByKey = new Map<string, Set<string>>();
  if (centreCode && attendeaseReadable) {
    try {
      for (const row of attendeaseRepo.allAttendanceByBatch({ centreId: centreCode, batchId: campaign.batchId })) {
        if (!onOrBefore(row.date)) continue;
        const classId = classIdOf(row);
        if (classId) addClass(batchClassesByKey, mobileKey(row.mobile), classId);
      }
    } catch {
      // batch-scoped column degrades to 0; the lifetime column above still stands
    }
  }

  // -- Batch -----------------------------------------------------------------------------------
  // Asked for back so the client can see, on a Sunday sheet, that a "Direct" attendee is really a
  // BG member who walked in - which is the whole explanation for a walk-in carrying six classes.
  // Seven people at this centre belong to two batches at once, so the campaign's own batch is
  // listed first and any others follow. One deterministic string, because this is also a sort key.
  // Read AS OF THE CLASS like everything else. Rosters churn — `source` includes attendance_evict
  // and manual_remove — so filtering on `removedAt: null` would mean re-exporting an old sheet
  // after an eviction silently changed that person's Batch cell. Filtered in memory rather than in
  // SQL because the bound is a local YYYY-MM-DD day, not an instant.
  const memberships = await rawPrisma.batchMember.findMany({
    where: { centreId },
    select: { mobile: true, joinedAt: true, removedAt: true, batch: { select: { name: true } } },
  });
  const batchSetByKey = new Map<string, Set<string>>();
  for (const member of memberships) {
    if (isoDate(member.joinedAt) > snapshotDate) continue;
    if (member.removedAt && isoDate(member.removedAt) <= snapshotDate) continue;
    const key = mobileKey(member.mobile);
    let set = batchSetByKey.get(key);
    if (!set) {
      set = new Set<string>();
      batchSetByKey.set(key, set);
    }
    set.add(member.batch.name);
  }
  const ownBatch = campaign.batch.name;
  const batchNameByKey = new Map<string, string>();
  for (const [key, set] of batchSetByKey) {
    const rest = [...set].filter((n) => n !== ownBatch).sort();
    batchNameByKey.set(key, (set.has(ownBatch) ? [ownBatch, ...rest] : rest).join(" / "));
  }
  // Someone on no roster at all is a true walk-in; fall back to the batch AttendEase scanned them
  // under, which is the only batch fact that exists for them.
  const batchOf = (key: string): string =>
    (batchNameByKey.get(key) ?? attendeeInfo.get(key)?.batchName?.trim()) || NA;

  // ── New vs Old ──────────────────────────────────────────────────────────────────────
  // New means THIS class was the person's first ever class at this centre — the rule is literally
  // "was it their 1st class or not". It is therefore decided from ATTENDANCE, not from calling
  // history: someone can have been called in five earlier sessions and still be sitting in their
  // first class, and that person is New. Walk-ins are classified by the same rule, so a person who
  // turned up without being called can be New or Old just like anyone else.
  //
  // The comparison is STRICTLY BEFORE this class's own date, which matters twice:
  //   • a centre runs two classes at the same time, so someone scanned into the parallel class on
  //     the same day has not attended "earlier" — they are still New;
  //   • a completed session can be exported weeks later, by which point the person has attended
  //     more classes. Counting those would relabel someone Old for a session at which they were
  //     New, so the same session would export differently depending on when the button was pressed.
  //
  // Centre-scoped like every other figure here, and keyed on mobileKey (last 10 digits) on both
  // sides, since batch_members and AttendEase agree on the number but on no shared row id.
  // ── Names ───────────────────────────────────────────────────────────────────────────────────
  // A person's name may live in a CB registration OR only in AttendEase's person directory —
  // first attendance IS registration here, so attendance-only people have no registration row.
  // Consulting registrations alone renders them as a bare phone number.
  const registrations = await rawPrisma.registration.findMany({
    where: { centreId },
    select: { name: true, normalizedMobile: true },
  });
  const regNameByKey = new Map<string, string>();
  for (const reg of registrations) {
    const name = reg.name?.trim();
    if (name) regNameByKey.set(mobileKey(reg.normalizedMobile), name);
  }
  const aeNameByKey = attendeaseReadable ? attendeasePersonNames() : new Map<string, string>();

  const resolveName = (key: string, fallbackName: string | null, mobile: string): string => {
    const ae = fallbackName?.trim();
    if (ae && ae !== mobile) return ae;
    return regNameByKey.get(key) ?? aeNameByKey.get(key) ?? mobile;
  };

  // No first date at all means this person has never attended a single class as of this session -
  // they were called and never came. They are neither New nor Old: there is no first class to be
  // new to. Anyone who actually attended THIS class necessarily has a first date (their own row),
  // so an attendee can never land in this third bucket.
  const cohortOf = (key: string): Cohort => {
    const first = firstDateByKey.get(key);
    if (!first) return NEVER_ATTENDED;
    return first < sessionDate ? "Old" : "New";
  };

  const rows: SessionReportRow[] = [];
  const assignedKeys = new Set<string>();

  for (const assignment of assignments) {
    const key = mobileKey(assignment.batchMember.mobile);
    assignedKeys.add(key);
    const cohort = cohortOf(key);
    // Mirrors the reconciliation summary's definition of "came" exactly: an explicit `attended`
    // status OR an attendance record. If these two disagreed, the sheet would contradict the
    // came/total figure printed on the report card the export button sits under.
    const attended = assignment.callStatus === SessionCallStatus.attended || attendedKeys.has(key);
    rows.push({
      name: resolveName(key, null, assignment.batchMember.mobile),
      mobile: assignment.batchMember.mobile,
      caller: assignment.caller.name,
      participation: "Called",
      attendedThisClass: attended,
      callResponse: assignment.outcomeLabel?.trim() || NA,
      callStatus: assignment.callStatus,
      classesAttended: classesByKey.get(key)?.size ?? 0,
      classesInBatch: batchClassesByKey.get(key)?.size ?? 0,
      lastAttended: lastDateByKey.get(key) ?? NA,
      cohort,
      batch: batchOf(key),
    });
  }

  // Walk-ins: attended, but nobody was ever given their number for this session. They are the
  // whole point of the direct_called sheet, and they carry no caller, response or call status.
  for (const key of attendedKeys) {
    if (assignedKeys.has(key)) continue;
    const info = attendeeInfo.get(key);
    if (!info) continue;
    const cohort = cohortOf(key);
    rows.push({
      name: resolveName(key, info.name, info.mobile),
      mobile: info.mobile,
      caller: NA,
      participation: "Direct",
      attendedThisClass: true,
      callResponse: NA,
      callStatus: NA,
      classesAttended: classesByKey.get(key)?.size ?? 0,
      classesInBatch: batchClassesByKey.get(key)?.size ?? 0,
      lastAttended: lastDateByKey.get(key) ?? NA,
      cohort,
      batch: batchOf(key),
    });
  }

  const meta: SessionReportMeta = {
    campaignId,
    sessionName: campaign.aeSessionId,
    batchName: campaign.batch.name,
    centreName: centre?.name ?? String(centreId),
    status: campaign.status,
    scheduledAt: campaign.scheduledAt,
    totalRows: rows.length,
    calledCount: rows.filter((r) => r.participation === "Called").length,
    directCount: rows.filter((r) => r.participation === "Direct").length,
  };

  return { meta, rows };
};

const byName = (a: SessionReportRow, b: SessionReportRow) => a.name.localeCompare(b.name);

/**
 * Apply one field's ordering (and, for new_old, its filter) to the built rows.
 *
 * `order` is optional for new_old: picking New or Old already implies the useful direction —
 * fewest classes first for the people who are new, most classes first for the people who are not.
 * An explicit order always wins over that default.
 */
export const shapeReportRows = (
  rows: SessionReportRow[],
  field: ExportField,
  order: ExportOrder | undefined,
  cohort: ExportCohort | undefined,
): SessionReportRow[] => {
  const out = [...rows];
  const dir = order === "desc" ? -1 : 1;

  switch (field) {
    case "by_caller": {
      // Walk-ins have no caller, so they are pinned to the bottom in BOTH directions rather than
      // being sorted into the middle of the caller list as a run of dashes.
      const rank = (r: SessionReportRow) => (r.participation === "Direct" ? 1 : 0);
      out.sort(
        (a, b) => rank(a) - rank(b) || dir * a.caller.localeCompare(b.caller) || byName(a, b),
      );
      return out;
    }
    case "classes_attended": {
      // The useful split on this sheet is who actually turned up, not who has the bigger lifetime
      // number - so this groups PRESENT first (or absent first with order=desc) and only then
      // ranks within each group, most classes first. Sorting purely by the count interleaved the
      // people who came with the people who did not, which is what made the sheet unreadable.
      const presentFirst = order !== "desc";
      const rank = (r: SessionReportRow) => (r.attendedThisClass === presentFirst ? 0 : 1);
      out.sort(
        (a, b) => rank(a) - rank(b) || b.classesAttended - a.classesAttended || byName(a, b),
      );
      return out;
    }
    case "new_old": {
      // GROUPS, it does not filter. Picking "New" used to return ONLY the New people, so half the
      // session vanished from the sheet and the two halves could not be compared without exporting
      // twice. Now the whole session comes out every time, with the chosen cohort's block first,
      // the other block after it, and the people who have never attended anything last - they
      // belong to neither group and would otherwise pad out whichever block they were dumped in.
      const lead = cohort === "old" ? "Old" : "New";
      const rank = (r: SessionReportRow) =>
        r.cohort === NEVER_ATTENDED ? 2 : r.cohort === lead ? 0 : 1;
      out.sort(
        (a, b) => rank(a) - rank(b) || dir * (b.classesAttended - a.classesAttended) || byName(a, b),
      );
      return out;
    }
    case "batch": {
      // Every member of a batch must land in ONE contiguous block. People in two batches carry a
      // combined cell ("sunday 4 pm / BG"), so sorting on the raw cell would split them off from
      // their own batch-mates; group on the PRIMARY batch (the part before the slash, which is the
      // campaign's own batch whenever they belong to it) and use the full cell only as a tiebreak.
      const groupOf = (r: SessionReportRow) => r.batch.split(" / ")[0] ?? r.batch;
      out.sort(
        (a, b) =>
          dir * groupOf(a).localeCompare(groupOf(b)) ||
          a.batch.localeCompare(b.batch) ||
          byName(a, b),
      );
      return out;
    }
    case "direct_called": {
      // No sub-menu on this field, so the order is fixed: the people who came on their own first
      // (they are the shorter and more interesting list), then everyone who was called.
      const rank = (r: SessionReportRow) => (r.participation === "Direct" ? 0 : 1);
      out.sort((a, b) => rank(a) - rank(b) || byName(a, b));
      return out;
    }
  }
};

const slugify = (value: string) =>
  value
    .replace(/[^A-Za-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "")
    .slice(0, 60) || "session";

export const reportFilename = (
  meta: SessionReportMeta,
  field: ExportField,
  order: ExportOrder | undefined,
  cohort: ExportCohort | undefined,
): string => {
  // The filename is the only part of this the client sees before opening the file, so it must not
  // describe the old behaviour. `new_old` no longer filters - both files hold the WHOLE session and
  // differ only in which block leads - so "_new" would read as "the New people" and re-create the
  // exact confusion the grouping change was made to fix. Likewise asc/desc on classes_attended now
  // means present-first/absent-first, not smallest/largest.
  const parts = [slugify(meta.sessionName), field];
  if (field === "new_old") {
    parts.push(cohort === "old" ? "old_first" : "new_first");
  } else if (field === "classes_attended") {
    parts.push(order === "desc" ? "absent_first" : "present_first");
  } else if (order && field !== "direct_called") {
    parts.push(order);
  }
  return `${parts.join("_")}.csv`;
};

// Local CSV serialisation rather than importing the downloads module's private helper. The
// escaping rules are identical; keeping a copy here means adding this endpoint cannot change the
// behaviour of the nine live /downloads/* endpoints.
const escapeCsvValue = (value: string | number | null | undefined): string => {
  if (value === null || value === undefined) return "";
  const text = String(value).replace(/\r?\n/g, " ").replace(/"/g, '""');
  return /[",\n]/.test(text) ? `"${text}"` : text;
};

/**
 * Render the shaped rows as CSV, prefixed with a UTF-8 BOM.
 *
 * The BOM is not decoration. Excel on Windows opens a .csv in the system codepage unless it sees
 * one, so without it any non-ASCII name renders as mojibake — and this database holds names that
 * are not even representable in cp1252. The export is opened in Excel by definition, so the BOM
 * goes on this endpoint. It is NOT added to the existing /downloads/* endpoints, whose output is
 * already consumed elsewhere and must stay byte-identical.
 */
export const renderReportCsv = (rows: SessionReportRow[]): string => {
  const headers = [...REPORT_HEADERS];
  const lines = [headers.join(",")];
  for (const row of rows) {
    const record = reportRowToCsvRecord(row);
    lines.push(headers.map((header) => escapeCsvValue(record[header])).join(","));
  }
  return `\uFEFF${lines.join("\n")}\n`;
};
