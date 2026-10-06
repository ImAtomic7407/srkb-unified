// Per-person CONTEXT for the caller's calling card — so a caller sees, inline, who they're about
// to call without opening a detail screen. Three signals, all batched by mobile:
//   1. recentCalls       — the last few logged call outcomes + notes (from CallAttempt history)
//   2. attendance        — how many sessions attended + last date + a few recent dates (AttendEase)
//   3. person details    — occupation / address + a "new vs existing" flag (attended < 3 = new)
//
// Session calls and pool calls are the SAME thing here — both resolve a person by mobile and get
// the identical context. One AE attendance scan + one CallAttempt query covers the whole queue.

import { rawPrisma } from "../../config/prisma.js";
import { attendeaseRepo } from "../../lib/attendease-db.js";
import { normalizeMobile } from "../../utils/mobile.js";

export type CallResponseLite = {
  date: string;        // ISO
  outcome: string;     // dropdown label logged
  remark: string | null;
  caller: string;      // who logged it
};

export type PersonContext = {
  recentCalls: CallResponseLite[];
  attendanceCount: number;
  lastAttended: string | null;     // ISO date string from AttendEase
  recentAttendance: string[];      // up to 6 recent attendance dates, newest first
  occupation: string | null;
  address: string | null;
  isNew: boolean;                  // registered but attended fewer than 3 sessions (attendanceCount < 3)
  currentBatches: string[];        // active batch names this person belongs to
};

// A person is "New" until they've attended 3 sessions total — matches the product rule that
// someone is still considered newly registered while they're getting settled into the batch.
// The card shows the most recent 4 inline and expands to the rest, so send a fuller slice (not all
// history, but enough that "see full history" is meaningful without an extra round-trip).
const RECENT_CALLS_INLINE = 12;
const EMPTY: PersonContext = {
  recentCalls: [],
  attendanceCount: 0,
  lastAttended: null,
  recentAttendance: [],
  occupation: null,
  address: null,
  isNew: true,
  currentBatches: [],
};

/**
 * Build a normalizedMobile -> PersonContext map for every mobile in `rawMobiles`.
 * Best-effort: a missing registration or unreachable AttendEase DB degrades to empty fields rather
 * than throwing, so the queue still renders. Keyed by normalized mobile (digits only via normalizeMobile).
 */
export const buildPeopleContext = async (
  centreId: number,
  rawMobiles: string[]
): Promise<Map<string, PersonContext>> => {
  const out = new Map<string, PersonContext>();
  const normalized: string[] = [];
  for (const m of rawMobiles) {
    try {
      const n = normalizeMobile(m);
      if (!normalized.includes(n)) normalized.push(n);
    } catch {
      // skip malformed mobiles
    }
  }
  if (normalized.length === 0) return out;

  // ── Attendance (AttendEase SQLite) — one scan of the centre, aggregated per normalized mobile ──
  const countByNorm = new Map<string, number>();
  const lastByNorm = new Map<string, string>();
  const datesByNorm = new Map<string, string[]>();
  try {
    const centre = await rawPrisma.centre.findUnique({ where: { id: centreId }, select: { code: true } });
    if (centre?.code) {
      const wanted = new Set(normalized);
      for (const row of attendeaseRepo.allAttendance({ centreId: centre.code })) {
        let n: string;
        try {
          n = normalizeMobile(row.mobile);
        } catch {
          continue;
        }
        if (!wanted.has(n)) continue;
        countByNorm.set(n, (countByNorm.get(n) ?? 0) + 1);
        if (row.date) {
          const arr = datesByNorm.get(n) ?? [];
          arr.push(row.date);
          datesByNorm.set(n, arr);
          if (!lastByNorm.has(n) || row.date > lastByNorm.get(n)!) lastByNorm.set(n, row.date);
        }
      }
    }
  } catch {
    // AttendEase unreachable — leave attendance empty.
  }

  // ── Registrations (occupation/address) + recent call attempts (previous responses) ──
  const regs = await rawPrisma.registration.findMany({
    where: { centreId, normalizedMobile: { in: normalized } },
    select: { id: true, normalizedMobile: true, occupation: true, address: true },
  });
  const regByNorm = new Map(regs.map((r) => [r.normalizedMobile, r]));
  const normByRegId = new Map(regs.map((r) => [r.id, r.normalizedMobile]));

  const attempts = regs.length
    ? await rawPrisma.callAttempt.findMany({
        where: { registrationId: { in: regs.map((r) => r.id) } },
        orderBy: { createdAt: "desc" },
        select: {
          registrationId: true,
          dropdownValue: true,
          remark: true,
          createdAt: true,
          caller: { select: { name: true } },
        },
      })
    : [];

  const callsByNorm = new Map<string, CallResponseLite[]>();
  for (const a of attempts) {
    const n = normByRegId.get(a.registrationId);
    if (!n) continue;
    const arr = callsByNorm.get(n) ?? [];
    if (arr.length >= RECENT_CALLS_INLINE) continue; // keep the most recent few (already ordered desc)
    arr.push({
      date: a.createdAt.toISOString(),
      outcome: a.dropdownValue,
      remark: a.remark ?? null,
      caller: a.caller?.name ?? "—",
    });
    callsByNorm.set(n, arr);
  }

  // ── Current batch memberships (active) per person ──────────────────────────
  // batch_members.mobile is the raw AE mobile; normalize to match our keys. Centre-scoped set is
  // small enough to load and filter in memory rather than a per-person query.
  const batchesByNorm = new Map<string, string[]>();
  try {
    const wantedNorm = new Set(normalized);
    const memberships = await rawPrisma.batchMember.findMany({
      where: { centreId, removedAt: null },
      select: { mobile: true, batch: { select: { name: true } } },
    });
    for (const m of memberships) {
      let n: string;
      try {
        n = normalizeMobile(m.mobile);
      } catch {
        continue;
      }
      if (!wantedNorm.has(n)) continue;
      const arr = batchesByNorm.get(n) ?? [];
      if (m.batch?.name && !arr.includes(m.batch.name)) arr.push(m.batch.name);
      batchesByNorm.set(n, arr);
    }
  } catch {
    // leave batches empty on any failure
  }

  for (const n of normalized) {
    const reg = regByNorm.get(n);
    const count = countByNorm.get(n) ?? 0;
    const recentAttendance = (datesByNorm.get(n) ?? [])
      .slice()
      .sort()
      .reverse()
      .slice(0, 6);
    out.set(n, {
      recentCalls: callsByNorm.get(n) ?? [],
      attendanceCount: count,
      lastAttended: lastByNorm.get(n) ?? null,
      recentAttendance,
      occupation: reg?.occupation ?? null,
      address: reg?.address ?? null,
      isNew: count < 3,
      currentBatches: batchesByNorm.get(n) ?? [],
    });
  }
  return out;
};

export const emptyPersonContext = (): PersonContext => ({ ...EMPTY });
