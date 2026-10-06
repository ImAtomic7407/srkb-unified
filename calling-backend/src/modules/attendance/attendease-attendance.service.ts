// Attendance history reads live from AttendEase's SQLite DB -- never duplicated locally.
// Used by the pool ('who came / who is in this batch') and by per-person history lookups.

import { Category } from '@prisma/client';
import { busyByMobile, busyToJson } from '../../lib/person-busy.js';
import { heldOutPeople, isCallLogAttendance } from '../../lib/declared-removal.js';
import { rawPrisma } from '../../config/prisma.js';
import { ApiError } from '../../lib/api-error.js';
import { attendeaseRepo, type AEAttendanceRow } from '../../lib/attendease-db.js';
import { normalizeMobile } from '../../utils/mobile.js';

const resolveCentreCode = async (centreId: number): Promise<string> => {
  const centre = await rawPrisma.centre.findUnique({ where: { id: centreId }, select: { code: true } });
  if (!centre) {
    throw new ApiError(404, 'Centre not found');
  }
  return centre.code;
};

export const getAttendanceForMobile = async (mobile: string, centreId: number): Promise<AEAttendanceRow[]> => {
  const centreCode = await resolveCentreCode(centreId);
  const normalized = normalizeMobile(mobile);
  return attendeaseRepo.allAttendance({ centreId: centreCode, mobile: normalized });
};

export const getAttendanceCountForMobile = async (mobile: string, centreId: number): Promise<number> => {
  const rows = await getAttendanceForMobile(mobile, centreId);
  return rows.length;
};

type MatchVia = 'batch' | 'session' | 'date' | 'response';

/** Shown instead of a response label for someone allotted to a caller who never rang them. Not a
 *  response and never stored as one — see the read-only note in the need_check block below. */
export const NEVER_CALLED_LABEL = 'Not called yet';

type PersonMatch = {
  mobile: string;
  name: string;
  address: string | null;
  occupation: string | null;
  matchedVia: MatchVia[];
  batchName: string | null;
  /** The calling response that put this person in the list, when they came in via `response`. */
  responseLabel: string | null;
};

/**
 * Which people a pool filter is asking for.
 *
 *   attended  — people with an ATTENDANCE record matching the filters. The original behaviour and
 *               still the default, so an app build that does not know about this parameter keeps
 *               getting exactly what it got before.
 *   responded — people who gave a POSITIVE response when called for this session, whether or not
 *               they turned up. Inherently per-calling-session, so session_id is required.
 *   both      — the union: came, or said something positive, or both. Kept for OLDER APP BUILDS,
 *               which still send it; updates are offered rather than forced, so deleting the value
 *               would 422 the pool screen on every phone that has not updated.
 *   need_check— people this session did NOT get through to: their latest response in the session
 *               was `not_reached` (no answer / switched off / busy / …), OR they were assigned to a
 *               caller and never rung at all. The two halves are one question — "who never got
 *               their chance?" — and separating them would leave the never-rung half invisible,
 *               which is how 19 people on 23 Aug and 70 on 29 Aug went unnoticed.
 */
export type PeopleFilterInclude = 'attended' | 'responded' | 'both' | 'need_check';

export const listPeopleByFilter = async ({
  centreId,
  category,
  batchId,
  sessionId,
  date,
  include = 'attended',
  exceptCampaignId,
}: {
  centreId: number;
  category: Category;
  batchId?: number;
  sessionId?: string;
  date?: string;
  include?: PeopleFilterInclude;
  /** The session being assigned — being in it is not "busy elsewhere". See lib/person-busy. */
  exceptCampaignId?: number;
}) => {
  if (!batchId && !sessionId && !date) {
    throw new ApiError(400, 'Specify at least one of batch_id, session_id, or date');
  }
  // A "positive response" is a response given IN a calling session. There is no such thing as a
  // positive response to a batch or to a date, so refuse rather than silently ignoring the mode.
  if (include !== 'attended' && !sessionId) {
    throw new ApiError(400, `include=${include} needs session_id — a calling response belongs to a session`);
  }

  const centreCode = await resolveCentreCode(centreId);
  const matches = new Map<string, PersonMatch>();

  const mergeMatch = (
    mobile: string,
    name: string,
    address: string | null,
    occupation: string | null,
    via: MatchVia,
    batchName: string | null,
  ) => {
    const existing = matches.get(mobile);
    if (existing) {
      if (!existing.matchedVia.includes(via)) {
        existing.matchedVia.push(via);
      }
      existing.batchName = existing.batchName ?? batchName;
      return;
    }
    matches.set(mobile, { mobile, name, address, occupation, matchedVia: [via], batchName, responseLabel: null });
  };

  // include=responded / need_check ask ONLY about the calling side, so the attendance lookups below
  // are skipped entirely — otherwise the union at the end would hand back attendees too.
  const wantsAttendance = include === 'attended' || include === 'both';

  if (batchId && wantsAttendance) {
    // Use session_mappings join to find all real attendees of this batch.
    // More accurate than centre_roster (sparse) or batch_name filter (denormalized).
    const attRows = attendeaseRepo.allAttendanceByBatch({ centreId: centreCode, batchId });
    for (const row of attRows) {
      mergeMatch(row.mobile, row.name ?? row.mobile, row.address, row.occupation, 'batch', row.batch_name);
    }
  }

  if (sessionId && wantsAttendance) {
    const sessionRows = attendeaseRepo.allAttendance({ centreId: centreCode, sessionId });
    for (const row of sessionRows) {
      mergeMatch(row.mobile, row.name ?? row.mobile, row.address, row.occupation, 'session', row.batch_name);
    }
  }

  if (date && wantsAttendance) {
    const dateRows = attendeaseRepo.allAttendance({ centreId: centreCode, date });
    for (const row of dateRows) {
      mergeMatch(row.mobile, row.name ?? row.mobile, row.address, row.occupation, 'date', row.batch_name);
    }
  }

  // The three blocks above each ADD their matches to the same map, which made the filters a
  // union: batch 18 alone returned 62 attendees at Centre A, and batch 18 + ANY of its sessions
  // returned the same 62, because every session's attendees are a subset of its batch's. Picking a
  // session "inside" the chosen batch therefore narrowed nothing at all — the whole point of the
  // lower pair of dropdowns.
  //
  // Filters now AND together, which is what a filter row means: each one supplied must match.
  // matchedVia already records which filters a person satisfied, so requiring it to contain every
  // supplied filter is the intersection. With a single filter this is a no-op, so the one-dropdown
  // behaviour is byte-for-byte unchanged.
  //
  // Note this intersection covers the ATTENDANCE filters only. 'response' is deliberately not a
  // fourth required tag: a positive responder who never turned up has no attendance row and no
  // 'batch' tag, so requiring one would delete every one of them and collapse include=both back
  // into include=attended. The shape is (attended ∩ filters) ∪ (positive responders), applied
  // below — not a four-way AND.
  const required: Array<'batch' | 'session' | 'date'> = [];
  if (batchId) required.push('batch');
  if (sessionId) required.push('session');
  if (date) required.push('date');
  if (required.length > 1 && wantsAttendance) {
    for (const [mobile, person] of matches) {
      if (!required.every((r) => person.matchedVia.includes(r))) matches.delete(mobile);
    }
  }

  // ── Positive calling responses ────────────────────────────────────────────────────────────────
  // "Who said yes when we rang them for this session?" — asked for separately from who attended,
  // because the two answer different questions and the pool page needs both.
  //
  // Sentiment is READ OFF THE OPTION ROW (dropdown_options.sentiment), never guessed from the
  // label. That is the rule src/lib/response-rules.ts exists to enforce: the old keyword derivation
  // scored "No incoming" as will_attend. It also means a centre that renames or re-classifies one
  // of its responses changes this list immediately and correctly, with no code change here.
  //
  // Verified against live centre-2 data before writing: all 366 session-scoped call_attempts carry
  // a dropdown_id, so every response resolves; nothing falls through to a fallback.
  if (include !== 'attended' && sessionId) {
    // findMany, not findFirst: nothing in the schema stops one AE session carrying more than one
    // campaign (a called campaign plus a direct_attendance one, or a re-created session). Today no
    // centre has a duplicate, but picking an arbitrary single campaign would fail silently and
    // invisibly the first time one appears.
    const campaigns = await rawPrisma.sessionCampaign.findMany({
      where: { centreId, aeSessionId: sessionId },
      select: { id: true },
    });

    if (campaigns.length > 0) {
      const attempts = await rawPrisma.callAttempt.findMany({
        where: { centreId, campaignId: { in: campaigns.map((c) => c.id) } },
        select: { registrationId: true, dropdownId: true, dropdownValue: true, createdAt: true },
        orderBy: { createdAt: 'asc' },
      });

      const optionIds = [...new Set(attempts.map((a) => a.dropdownId).filter((id): id is number => id !== null))];
      const options = optionIds.length
        ? await rawPrisma.dropdownOption.findMany({
            where: { id: { in: optionIds } },
            select: { id: true, label: true, sentiment: true },
          })
        : [];
      const optionById = new Map(options.map((o) => [o.id, o]));

      // Which meaning this mode is asking about. Read off dropdown_options.sentiment as always —
      // `not_reached` is the EXISTING neutral bucket ("could not reach them", never a refusal), not a
      // new category: see the header of src/lib/response-rules.ts for why re-classifying it would
      // move every dashboard, forecast and the absence tolerance with it.
      const wantedSentiment = include === 'need_check' ? 'not_reached' : 'positive';

      // Latest response wins: a person who is rung back and changes their answer must be judged on
      // what they last said, not on whichever row happens to come out of the query first.
      const matchedByRegistrationId = new Map<number, string>();
      for (const attempt of attempts) {
        const option = attempt.dropdownId ? optionById.get(attempt.dropdownId) : undefined;
        if (!option) continue;
        if (option.sentiment === wantedSentiment) matchedByRegistrationId.set(attempt.registrationId, option.label);
        else matchedByRegistrationId.delete(attempt.registrationId);
      }

      // ── Assigned but never rung ────────────────────────────────────────────────────────────────
      // Someone allotted to a caller who has no call_attempt row at all for this campaign got no
      // chance whatsoever, which is a stronger version of "we could not reach them". They carry no
      // sentiment (there is no response), so a sentiment filter alone can never surface them.
      //
      // READ-ONLY, DELIBERATELY. This must never write a CallAttempt to "record" the miss. Every
      // consecutive-removal run in lib/unreachable-threshold.ts is counted off call_attempts, so a
      // synthetic row here would archive people for the CALLERS' failure to dial — the precise
      // opposite of what this list is for. Surface them; never stamp them.
      if (include === 'need_check') {
        const attemptedRegistrationIds = new Set(attempts.map((a) => a.registrationId));
        const assignments = await rawPrisma.sessionCallAssignment.findMany({
          where: { campaignId: { in: campaigns.map((c) => c.id) }, removedAt: null },
          select: { batchMember: { select: { mobile: true } } },
        });
        // BatchMember.mobile is a third key space again (AE raw strings and registrations'
        // normalizedMobile being the other two). Normalise before matching, or the same person is
        // looked up under two spellings and silently missed.
        const assignedMobiles = new Set<string>();
        for (const a of assignments) {
          try {
            assignedMobiles.add(normalizeMobile(a.batchMember.mobile));
          } catch {
            // unparseable roster mobile — cannot be tied to a registration either way
          }
        }
        if (assignedMobiles.size > 0) {
          const assignedRegs = await rawPrisma.registration.findMany({
            where: { centreId, category, normalizedMobile: { in: [...assignedMobiles] } },
            select: { id: true },
          });
          for (const r of assignedRegs) {
            if (!attemptedRegistrationIds.has(r.id)) matchedByRegistrationId.set(r.id, NEVER_CALLED_LABEL);
          }
        }
      }

      if (matchedByRegistrationId.size > 0) {
        const responders = await rawPrisma.registration.findMany({
          where: { id: { in: [...matchedByRegistrationId.keys()] }, centreId, category },
          select: { id: true, name: true, mobile: true, normalizedMobile: true, address: true, occupation: true },
        });

        // Dedup across two different key spaces. `matches` is keyed by the RAW mobile string AE
        // stores; registrations are keyed by normalizedMobile. Comparing them directly would list
        // the same person twice in include=both — once as an attendee, once as a responder.
        const rawByNormalized = new Map<string, string>();
        for (const rawMobile of matches.keys()) {
          try {
            rawByNormalized.set(normalizeMobile(rawMobile), rawMobile);
          } catch {
            // malformed upstream mobile — cannot be matched to a registration either way
          }
        }

        for (const responder of responders) {
          const label = matchedByRegistrationId.get(responder.id)!;
          const existingRaw = rawByNormalized.get(responder.normalizedMobile);
          if (existingRaw) {
            // Already listed as an attendee: tag them rather than adding a duplicate row.
            const person = matches.get(existingRaw)!;
            if (!person.matchedVia.includes('response')) person.matchedVia.push('response');
            person.responseLabel = label;
            continue;
          }
          if (matches.has(responder.mobile)) continue;
          matches.set(responder.mobile, {
            mobile: responder.mobile,
            name: responder.name,
            address: responder.address,
            occupation: responder.occupation,
            matchedVia: ['response'],
            batchName: null,
            responseLabel: label,
          });
        }
      }
    }
  }

  return buildPeopleRows(matches, { centreId, centreCode, category, exceptCampaignId });
};

/**
 * Turns matched people into the rows the Pool page reads: names filled from the roster, lifetime
 * attendance, registration stage and "busy elsewhere". Shared by listPeopleByFilter and
 * listSessionPeopleByOutcome so both endpoints return exactly the same row shape.
 */
const buildPeopleRows = async (
  matches: Map<string, PersonMatch>,
  {
    centreId,
    centreCode,
    category,
    exceptCampaignId,
  }: { centreId: number; centreCode: string; category: Category; exceptCampaignId?: number },
) => {
  if (matches.size === 0) {
    return [];
  }

  // Enrich null/missing names from centre_roster for any filter type.
  {
    const allRoster = attendeaseRepo.allRosterMembers({ centreId: centreCode });
    const rosterByMobile = new Map(allRoster.map((r) => [r.mobile, r]));
    for (const person of matches.values()) {
      if (!person.name || person.name === person.mobile) {
        const rosterEntry = rosterByMobile.get(person.mobile);
        if (rosterEntry) {
          person.name = rosterEntry.name;
          person.address = person.address ?? rosterEntry.address;
          person.occupation = person.occupation ?? rosterEntry.occupation;
          person.batchName = person.batchName ?? rosterEntry.batch_name;
        }
      }
    }
  }

  // Pull full attendance history for this centre once and aggregate per mobile in memory
  // rather than issuing one query per matched person (~7k rows is fine in-process).
  const allAttendanceRows = attendeaseRepo.allAttendance({ centreId: centreCode });
  const countByMobile = new Map<string, number>();
  const lastDateByMobile = new Map<string, string>();
  // Parallel maps keyed by NORMALIZED mobile, consulted only when the raw-key lookup misses.
  // A person pulled in by their calling response is keyed by the registration's mobile, which need
  // not be byte-identical to the string AE stores — without this they would report 0 lifetime
  // attendances even when they have attended for years. The raw map is still consulted first so
  // every previously-returned row keeps the exact number it had before.
  const countByNormalized = new Map<string, number>();
  const lastDateByNormalized = new Map<string, string>();
  // Real attendance only (no bulk call logs) — what "has turned up since being removed" reads.
  const lastRealByNormalized = new Map<string, string>();
  for (const row of allAttendanceRows) {
    countByMobile.set(row.mobile, (countByMobile.get(row.mobile) ?? 0) + 1);
    if (row.date && (!lastDateByMobile.has(row.mobile) || row.date > lastDateByMobile.get(row.mobile)!)) {
      lastDateByMobile.set(row.mobile, row.date);
    }
    try {
      const norm = normalizeMobile(row.mobile);
      countByNormalized.set(norm, (countByNormalized.get(norm) ?? 0) + 1);
      if (row.date && (!lastDateByNormalized.has(norm) || row.date > lastDateByNormalized.get(norm)!)) {
        lastDateByNormalized.set(norm, row.date);
      }
      if (row.date && !isCallLogAttendance(row.remark) && (!lastRealByNormalized.has(norm) || row.date > lastRealByNormalized.get(norm)!)) {
        lastRealByNormalized.set(norm, row.date);
      }
    } catch {
      // malformed upstream mobile — already counted under its raw key above
    }
  }

  const normalizedMobiles: string[] = [];
  const normalizedToRaw = new Map<string, string>();
  for (const mobile of matches.keys()) {
    try {
      const normalized = normalizeMobile(mobile);
      normalizedMobiles.push(normalized);
      normalizedToRaw.set(normalized, mobile);
    } catch {
      // skip malformed mobiles from upstream data
    }
  }

  // Archived / removed people ("Not interested", "Wrong number", absent too often) are not offered
  // for allotment again unless they have attended since — see lib/declared-removal heldOutPeople.
  const heldOut = await heldOutPeople(centreId, normalizedMobiles, (n) => lastRealByNormalized.get(n) ?? null, category);
  if (heldOut.size > 0) {
    for (const [normalized, raw] of normalizedToRaw) {
      if (heldOut.has(normalized)) matches.delete(raw);
    }
  }

  const registrations = await rawPrisma.registration.findMany({
    where: {
      centreId,
      category,
      normalizedMobile: { in: normalizedMobiles },
    },
    select: {
      id: true,
      name: true,
      normalizedMobile: true,
      currentStage: true,
      activeQueue: true,
      currentAssignedCallerId: true,
      relationshipOwnerId: true,
    },
  });
  const registrationByNormalizedMobile = new Map(registrations.map((r) => [r.normalizedMobile, r]));
  // Where each person is held right now, so the Pool page can show it and refuse to assign them.
  const busyMap = await busyByMobile(centreId, normalizedMobiles, exceptCampaignId);

  return Array.from(matches.values()).map((person) => {
    let normalized: string | null = null;
    try {
      normalized = normalizeMobile(person.mobile);
    } catch {
      normalized = null;
    }
    const registration = normalized ? (registrationByNormalizedMobile.get(normalized) ?? null) : null;

    // Prefer a real name from AttendEase; if it's missing (name falls back to the mobile) but the
    // person IS registered, use the registration's name — registered people always have a name.
    const hasRealName = person.name && person.name.trim().length > 0 && person.name.trim() !== person.mobile;
    const resolvedName = hasRealName ? person.name : (registration?.name?.trim() || person.name);

    return {
      mobile: person.mobile,
      name: resolvedName,
      address: person.address,
      occupation: person.occupation,
      batch_name: person.batchName,
      matched_via: person.matchedVia,
      // The response that put them in the list, so the admin can see WHY a non-attendee is here
      // ("Will attend" vs "Call next month" are both positive but mean very different things).
      response_label: person.responseLabel,
      total_attendance_count:
        countByMobile.get(person.mobile) ?? (normalized ? (countByNormalized.get(normalized) ?? 0) : 0),
      last_attended_date:
        lastDateByMobile.get(person.mobile) ?? (normalized ? (lastDateByNormalized.get(normalized) ?? null) : null),
      // null = free to assign. Otherwise { kind, label, caller } — see lib/person-busy.
      busy: normalized ? busyToJson(busyMap.get(normalized)) : null,
      registration: registration
        ? {
            id: registration.id,
            current_stage: registration.currentStage,
            active_queue: registration.activeQueue,
            current_assigned_caller_id: registration.currentAssignedCallerId,
            relationship_owner_id: registration.relationshipOwnerId,
          }
        : null,
    };
  });
};

// ── Pool filter by response + attendance ─────────────────────────────────────────────────────────
// Replaces the three fixed "Show" chips (Attended / Positive response / Need check) on the Pool
// page, which could not answer the questions admins actually ask: "who said Will attend and then
// did not come?", "who gave a negative answer but turned up anyway?". The admin now picks any mix
// of responses (whole sentiment groups or single responses) and any mix of attended / not attended.
//
// The old /pool/batch-members?include= path is left exactly as it was: phones on older builds
// still call it.

export type OutcomeSentiment = 'positive' | 'negative' | 'not_reached';
export type OutcomeAttendance = 'present' | 'absent' | 'any';

type SessionOutcome = { optionId: number | null; label: string; sentiment: OutcomeSentiment | null };

/** One session's facts, keyed by NORMALIZED mobile. */
type SessionFacts = {
  sessionId: string;
  hasCampaign: boolean;
  attendees: Map<string, AEAttendanceRow>;
  /** Latest response in this session, or the "Not called yet" marker for allotted-but-unrung. */
  outcomes: Map<string, SessionOutcome>;
  /** Registration details for people reached through the calling side. */
  regs: Map<string, { name: string; mobile: string; address: string | null; occupation: string | null }>;
};

const loadSessionFacts = async (
  centreId: number,
  centreCode: string,
  category: Category,
  sessionIds: string[],
): Promise<SessionFacts[]> => {
  const campaigns = await rawPrisma.sessionCampaign.findMany({
    where: { centreId, aeSessionId: { in: sessionIds } },
    select: { id: true, aeSessionId: true },
  });
  const campaignIdsBySession = new Map<string, number[]>();
  for (const c of campaigns) {
    const list = campaignIdsBySession.get(c.aeSessionId) ?? [];
    list.push(c.id);
    campaignIdsBySession.set(c.aeSessionId, list);
  }

  const out: SessionFacts[] = [];
  for (const sessionId of sessionIds) {
    const attendees = new Map<string, AEAttendanceRow>();
    for (const row of attendeaseRepo.allAttendance({ centreId: centreCode, sessionId })) {
      try {
        attendees.set(normalizeMobile(row.mobile), row);
      } catch {
        // malformed upstream mobile — cannot be matched to a response either way
      }
    }

    const outcomes: SessionFacts['outcomes'] = new Map();
    const regs: SessionFacts['regs'] = new Map();
    const campaignIds = campaignIdsBySession.get(sessionId) ?? [];

    if (campaignIds.length > 0) {
      const attempts = await rawPrisma.callAttempt.findMany({
        where: { centreId, campaignId: { in: campaignIds } },
        select: { registrationId: true, dropdownId: true, dropdownValue: true },
        orderBy: { createdAt: 'asc' },
      });
      const optionIds = [...new Set(attempts.map((a) => a.dropdownId).filter((id): id is number => id !== null))];
      const options = optionIds.length
        ? await rawPrisma.dropdownOption.findMany({
            where: { id: { in: optionIds } },
            select: { id: true, label: true, sentiment: true },
          })
        : [];
      const optionById = new Map(options.map((o) => [o.id, o]));

      // Latest response wins, the same rule as the include= path.
      const latestByRegistration = new Map<number, SessionOutcome>();
      for (const a of attempts) {
        const option = a.dropdownId !== null ? optionById.get(a.dropdownId) : undefined;
        latestByRegistration.set(a.registrationId, {
          optionId: option?.id ?? null,
          label: option?.label ?? a.dropdownValue ?? 'Unknown response',
          // No option row = no declared meaning. Listed, never guessed into a group.
          sentiment: (option?.sentiment as OutcomeSentiment | undefined) ?? null,
        });
      }

      // Allotted to a caller in this session. READ-ONLY — see the need_check note above.
      const assignments = await rawPrisma.sessionCallAssignment.findMany({
        where: { campaignId: { in: campaignIds }, removedAt: null },
        select: { batchMember: { select: { mobile: true } } },
      });
      const assignedMobiles = new Set<string>();
      for (const a of assignments) {
        try {
          assignedMobiles.add(normalizeMobile(a.batchMember.mobile));
        } catch {
          // unparseable roster mobile
        }
      }

      const regRows = await rawPrisma.registration.findMany({
        where: {
          centreId,
          category,
          OR: [
            { id: { in: [...latestByRegistration.keys()] } },
            { normalizedMobile: { in: [...assignedMobiles] } },
          ],
        },
        select: { id: true, name: true, mobile: true, normalizedMobile: true, address: true, occupation: true },
      });
      for (const r of regRows) {
        const outcome = latestByRegistration.get(r.id);
        if (outcome) {
          outcomes.set(r.normalizedMobile, outcome);
        } else if (assignedMobiles.has(r.normalizedMobile)) {
          outcomes.set(r.normalizedMobile, { optionId: null, label: NEVER_CALLED_LABEL, sentiment: null });
        } else {
          continue;
        }
        regs.set(r.normalizedMobile, { name: r.name, mobile: r.mobile, address: r.address, occupation: r.occupation });
      }
    }

    out.push({ sessionId, hasCampaign: campaignIds.length > 0, attendees, outcomes, regs });
  }
  return out;
};

/** Held-out people among everyone these sessions mention. Keyed by normalized mobile. */
const heldOutForFacts = async (centreId: number, centreCode: string, category: Category, facts: SessionFacts[]) => {
  const mobiles = new Set<string>();
  for (const f of facts) for (const m of f.outcomes.keys()) mobiles.add(m);
  if (mobiles.size === 0) return new Map<string, unknown>();
  const last = new Map<string, string>();
  for (const row of attendeaseRepo.allAttendance({ centreId: centreCode })) {
    if (isCallLogAttendance(row.remark)) continue;
    try {
      const n = normalizeMobile(row.mobile);
      if (row.date && (!last.has(n) || row.date > last.get(n)!)) last.set(n, row.date);
    } catch {
      /* malformed upstream mobile */
    }
  }
  return heldOutPeople(centreId, [...mobiles], (n) => last.get(n) ?? null, category);
};

const isNeverCalled = (o: SessionOutcome) => o.optionId === null && o.sentiment === null && o.label === NEVER_CALLED_LABEL;

/**
 * The responses given in the chosen sessions, grouped by meaning, with how many people gave each
 * (latest answer per person per session). Feeds the filter sheet so the admin sees "Will attend 42"
 * before ticking it. The same response under two ids (a centre's override of a shared option) is
 * merged by meaning + label, so the sheet never lists "Will attend" twice.
 */
export const listSessionResponseOptions = async ({
  centreId,
  category,
  sessionIds,
}: {
  centreId: number;
  category: Category;
  sessionIds: string[];
}) => {
  const centreCode = await resolveCentreCode(centreId);
  const facts = await loadSessionFacts(centreId, centreCode, category, sessionIds);
  // The same people the list leaves out, so a count in the sheet matches what ticking it returns.
  const heldOut = await heldOutForFacts(centreId, centreCode, category, facts);

  type Row = { sentiment: OutcomeSentiment | null; label: string; ids: Set<number>; people: Set<string> };
  const rows = new Map<string, Row>();
  const neverCalled = new Set<string>();

  for (const f of facts) {
    for (const [mobile, o] of f.outcomes) {
      if (heldOut.has(mobile)) continue;
      if (isNeverCalled(o)) {
        neverCalled.add(mobile);
        continue;
      }
      const key = `${o.sentiment ?? 'none'}|${o.label.trim().toLowerCase()}`;
      const row = rows.get(key) ?? { sentiment: o.sentiment, label: o.label.trim(), ids: new Set<number>(), people: new Set<string>() };
      if (o.optionId !== null) row.ids.add(o.optionId);
      row.people.add(mobile);
      rows.set(key, row);
    }
  }

  return {
    sessions: facts.map((f) => ({ session_id: f.sessionId, has_calling: f.hasCampaign })),
    responses: [...rows.values()]
      .sort((a, b) => b.people.size - a.people.size || a.label.localeCompare(b.label))
      .map((r) => ({
        label: r.label,
        sentiment: r.sentiment,
        option_ids: [...r.ids].sort((x, y) => x - y),
        count: r.people.size,
      })),
    never_called_count: neverCalled.size,
  };
};

/**
 * People from the chosen sessions who match the response filter AND the attendance filter.
 *
 * Judged PER SESSION, then unioned: "said Will attend for 8 Aug and was absent from 8 Aug"
 * qualifies; "said Will attend for 8 Aug and was absent from 15 Aug" does not. Checking responses
 * across all sessions and attendance across all sessions separately would list people who match
 * neither question.
 *
 * Response filter: sentiments (whole groups, so a response the centre adds later is included
 * automatically) OR response_ids (single picks) OR never_called. None of the three = no response
 * filter: everyone who attended or was allotted/called in the session is considered.
 *
 * Attendance filter: present = has an attendance record for THAT session; absent = does not.
 * "Absent" needs a list to be absent FROM, which is the session's calling list — so a session with
 * no calling (direct attendance) can only ever produce attendees.
 */
export const listSessionPeopleByOutcome = async ({
  centreId,
  category,
  sessionIds,
  sentiments,
  responseIds,
  neverCalled,
  attendance,
  exceptCampaignId,
}: {
  centreId: number;
  category: Category;
  sessionIds: string[];
  sentiments: OutcomeSentiment[];
  responseIds: number[];
  neverCalled: boolean;
  attendance: OutcomeAttendance;
  exceptCampaignId?: number;
}) => {
  const centreCode = await resolveCentreCode(centreId);
  const facts = await loadSessionFacts(centreId, centreCode, category, sessionIds);

  const sentimentSet = new Set(sentiments);
  const responseIdSet = new Set(responseIds);
  const filterByResponse = sentimentSet.size > 0 || responseIdSet.size > 0 || neverCalled;

  const matches = new Map<string, PersonMatch>();
  // Key already used for a normalized mobile, so someone who qualifies in two sessions is one row.
  const keyByNormalized = new Map<string, string>();

  for (const f of facts) {
    const population = new Set<string>([...f.attendees.keys(), ...f.outcomes.keys()]);
    for (const mobile of population) {
      const outcome = f.outcomes.get(mobile);
      if (filterByResponse) {
        if (!outcome) continue;
        const ok = isNeverCalled(outcome)
          ? neverCalled
          : (outcome.sentiment !== null && sentimentSet.has(outcome.sentiment)) ||
            (outcome.optionId !== null && responseIdSet.has(outcome.optionId));
        if (!ok) continue;
      }
      const attended = f.attendees.has(mobile);
      if (attendance === 'present' && !attended) continue;
      if (attendance === 'absent' && attended) continue;

      const existingKey = keyByNormalized.get(mobile);
      if (existingKey) {
        const person = matches.get(existingKey)!;
        if (attended && !person.matchedVia.includes('session')) person.matchedVia.push('session');
        if (outcome && !person.matchedVia.includes('response')) person.matchedVia.push('response');
        person.responseLabel = person.responseLabel ?? outcome?.label ?? null;
        continue;
      }

      const att = f.attendees.get(mobile);
      const reg = f.regs.get(mobile);
      // AE's raw mobile first: buildPeopleRows looks lifetime attendance up by it.
      const key = att?.mobile ?? reg?.mobile ?? mobile;
      const via: MatchVia[] = [];
      if (attended) via.push('session');
      if (outcome) via.push('response');
      matches.set(key, {
        mobile: key,
        name: att?.name ?? reg?.name ?? key,
        address: att?.address ?? reg?.address ?? null,
        occupation: att?.occupation ?? reg?.occupation ?? null,
        matchedVia: via,
        batchName: att?.batch_name ?? null,
        responseLabel: outcome?.label ?? null,
      });
      keyByNormalized.set(mobile, key);
    }
  }

  return buildPeopleRows(matches, { centreId, centreCode, category, exceptCampaignId });
};
