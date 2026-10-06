import {
  SessionCampaignStatus, SessionCampaignKind, SessionCallStatus, CallType,
  RegistrationStage, ActiveQueue, FollowupStatus, FollowupKind,
  DropdownAction, DropdownContext, ResponseSentiment,
  type Category,
} from "@prisma/client";
import { rawPrisma } from "../../config/prisma.js";
import { ApiError } from "../../lib/api-error.js";
import { buildPeopleContext, emptyPersonContext } from "../caller/caller-context.service.js";
import { normalizeMobile } from "../../utils/mobile.js";
import { attendeaseRepo } from "../../lib/attendease-db.js";
import { attendeasePersonNames, mobileKey } from "../../lib/person-directory.js";
import { recomputeCentreCategory } from "../../lib/projections.js";
import { releaseFromClosedFollowups } from "../../lib/followup-release.js";
import { busyByMobile } from "../../lib/person-busy.js";
import { BATCH_ABSENCE_EVICTION_THRESHOLD, evictFromBatchForAbsence } from "../batch/batch-member.service.js";
import { resolveCallingOption } from "../../lib/resolve-calling-option.js";
import { renderWhatsappMessage } from "../../lib/whatsapp-template.js";
import {
  enforceConsecutiveRemoval,
  UNREACHABLE_REASON_PREFIX,
  type ThresholdOption,
} from "../../lib/unreachable-threshold.js";
import { applyDeclaredRemoval, revertDeclaredRemoval, isRemovalAction, heldOutPeople, isCallLogAttendance } from "../../lib/declared-removal.js";
import { logger } from "../../lib/logger.js";

// Resolve a user to attribute an auto-created direct-attendance campaign to. Prefers a host;
// falls back to a centre admin. Returns null if none found (caller then skips creation).
const resolveSystemCreatorId = async (centreId: number): Promise<number | null> => {
  const host = await rawPrisma.user.findFirst({
    where: { role: "SUPER_ADMIN", isActive: true },
    select: { id: true },
  });
  if (host) return host.id;
  const admin = await rawPrisma.userCentreAccess.findFirst({
    where: { centreId, isAdmin: true },
    select: { userId: true },
  });
  return admin?.userId ?? null;
};

/**
 * Ensure a session that received attendance is represented in the calling system. If the
 * session already has a campaign (called OR direct), this is a no-op. Otherwise it creates a
 * campaign flagged `direct_attendance` so the session is "in the system but clearly marked".
 * Idempotent (aeSessionId is unique); best-effort — returns null if it can't resolve a creator
 * or the session isn't mirrored yet (the read endpoints still classify it correctly by derivation).
 */
export const ensureDirectAttendanceCampaign = async (aeSessionId: string, centreId: number) => {
  const existing = await rawPrisma.sessionCampaign.findUnique({ where: { aeSessionId } });
  if (existing) return existing;

  const session = await rawPrisma.session.findUnique({ where: { sessionId: aeSessionId } });
  if (!session || session.centreId !== centreId) return null;

  const createdById = await resolveSystemCreatorId(centreId);
  if (createdById === null) return null;

  try {
    return await rawPrisma.sessionCampaign.create({
      data: {
        aeSessionId,
        centreId,
        batchId: session.batchId,
        scheduledAt: session.date ?? new Date(),
        kind: SessionCampaignKind.direct_attendance,
        createdById,
      },
    });
  } catch {
    // Race: another attendance event created it first — fine.
    return rawPrisma.sessionCampaign.findUnique({ where: { aeSessionId } });
  }
};

/**
 * Sessions for the registerer's session dropdown, split into the two headings:
 *   called            — last 4 sessions the centre admin created a calling campaign for
 *   direct_attendance — last 4 sessions with attendance but no calling campaign
 * Kind is DERIVED from campaign presence so it is correct even for sessions whose direct
 * campaign hasn't been auto-created yet. Each list is newest-first, capped at 4.
 */
export const getSessionsForRegistration = async (centreId: number, category?: Category) => {
  const sessions = await rawPrisma.session.findMany({
    where: {
      centreId,
      status: { not: "deleted" },
      ...(category ? { category } : {}),
    },
    orderBy: [{ date: "desc" }, { displayName: "asc" }],
    select: { sessionId: true, displayName: true, batchId: true, centreId: true, category: true, date: true },
  });

  const campaigns = await rawPrisma.sessionCampaign.findMany({
    where: { centreId, aeSessionId: { in: sessions.map((s) => s.sessionId) } },
    select: { aeSessionId: true, kind: true },
  });
  const kindBy = new Map(campaigns.map((c) => [c.aeSessionId, c.kind]));

  const called: Array<Record<string, unknown>> = [];
  const direct: Array<Record<string, unknown>> = [];
  for (const s of sessions) {
    const isCalled = kindBy.get(s.sessionId) === SessionCampaignKind.called;
    const item = { ...s, kind: isCalled ? SessionCampaignKind.called : SessionCampaignKind.direct_attendance };
    (isCalled ? called : direct).push(item);
  }

  return { called: called.slice(0, 4), direct_attendance: direct.slice(0, 4) };
};

/**
 * ALL sessions for a centre (both calling sessions and direct-attendance sessions), for the
 * pool-assignment page's lower "Filter: session" dropdown. Unlike getSessionsForRegistration
 * (capped at last-4 per heading), this returns the FULL list, newest-first, as one flat array
 * with a derived `kind` on each row. Selecting one filters the pool by who ATTENDED that session
 * (via listPeopleByFilter session_id), so every attended session must be selectable — not just
 * the few that have an explicit calling campaign.
 */
export const listSessionsForFilter = async (centreId: number, category?: Category) => {
  const sessions = await rawPrisma.session.findMany({
    where: {
      centreId,
      status: { not: "deleted" },
      ...(category ? { category } : {}),
    },
    orderBy: [{ date: "desc" }, { displayName: "asc" }],
    select: { sessionId: true, displayName: true, batchId: true, date: true },
  });

  const campaigns = await rawPrisma.sessionCampaign.findMany({
    where: { centreId, aeSessionId: { in: sessions.map((s) => s.sessionId) } },
    select: { aeSessionId: true, kind: true },
  });
  const kindBy = new Map(campaigns.map((c) => [c.aeSessionId, c.kind]));

  return sessions.map((s) => ({
    session_id: s.sessionId,
    display_name: s.displayName,
    batch_id: s.batchId,
    date: s.date,
    kind:
      kindBy.get(s.sessionId) === SessionCampaignKind.called
        ? SessionCampaignKind.called
        : SessionCampaignKind.direct_attendance,
  }));
};

export const createSessionCampaign = async ({
  aeSessionId,
  centreId,
  batchId,
  scheduledAt,
  createdById,
}: {
  aeSessionId: string;
  centreId: number;
  batchId: number;
  scheduledAt: Date;
  createdById: number;
}) => {
  const existing = await rawPrisma.sessionCampaign.findUnique({ where: { aeSessionId } });
  if (existing) {
    throw new ApiError(409, `Session campaign for session '${aeSessionId}' already exists`);
  }
  const campaign = await rawPrisma.sessionCampaign.create({
    data: { aeSessionId, centreId, batchId, scheduledAt, createdById },
    include: { batch: true, createdBy: { select: { id: true, name: true } } },
  });

  // Reverse sync: notify AttendEase to create/register this calling session so it appears
  // in the registerer's Mark Attendance dropdown. NOT the batch name — labelling it with
  // the batch name made it show up as a session literally called "OTP" inside the OTP batch.
  // AttendEase keys centres by their slug (`centres.centre_id`, e.g. "centre_a_Base") and has no
  // numeric id column at all, so sending only our integer PK made its lookup raise
  // `Entity namespace for "centres" has no property "id"` — a 500 the outbox then retried forever
  // (job 1389, and 1283 before it). Send the code as well; AE prefers it and needs no lookup.
  // Kept ALONGSIDE centre_id rather than replacing it: jobs enqueued by an older build are still
  // sitting in the outbox with only the numeric field, and AE must keep understanding those.
  const centre = await rawPrisma.centre.findUnique({ where: { id: centreId }, select: { code: true } });

  // NEVER send the generated session id as the display name.
  //
  // This used to be `display_name: aeSessionId`, justified by a comment claiming "aeSessionId is
  // the name the admin typed". That is true only of the iHost / CB-first path, where the typed
  // text becomes the id. From the APP the order is reversed: AttendEase creates the session first
  // and GENERATES the id ("YYYY-MM-DD <CENTRE> <BATCH>"), keeping what the registerer typed as a
  // separate display name. Sending the id back then destroyed it — AE's create-calling-session did
  // `existing.display_name = display_name or existing.display_name`, so "testing01" became
  // "2026-08-17 CTRB CELIBACY". Two live sessions were renamed this way (outbox 1408 and 1392)
  // before this was found on 2026-08-17, and it is why creating a calling session from the app
  // "didn't work" while creating one from iHost did.
  //
  // Prefer the mirrored display name. Fall back to the id ONLY when the session isn't mirrored
  // yet — which is exactly the CB-first case, where the id genuinely is the typed name. AttendEase
  // now also refuses to overwrite a real name with its own id, so neither side can reintroduce
  // this on its own.
  // Deliberately fail-safe. This read sits between "campaign created" and "AE told about it", so a
  // throw here would leave a campaign with no outbox job — the session would never be registered in
  // AttendEase and would never reach the registerer's dropdown. That is the 2026-08-13 failure
  // wearing a new costume. A lookup failure degrades to the old fallback (the id) rather than
  // skipping the enqueue: a slightly wrong label is recoverable, a missing session is not.
  const mirrored = await rawPrisma.session
    .findUnique({ where: { sessionId: aeSessionId }, select: { displayName: true } })
    .catch(() => null);
  const displayName = mirrored?.displayName?.trim() || aeSessionId;

  const { enqueueOutbox } = await import("../../lib/outbox.js");
  await enqueueOutbox("create_calling_session_in_ae", {
    ae_session_id: aeSessionId,
    centre_id: centreId,
    centre_code: centre?.code ?? null,
    batch_id: batchId,
    display_name: displayName,
    created_at: new Date().toISOString(),
  });

  return campaign;
};

export const listSessionCampaigns = async ({
  centreId,
  status,
  batchId,
  category,
}: {
  centreId: number;
  status?: SessionCampaignStatus;
  batchId?: number;
  category?: Category;
}) => {
  const campaigns = await rawPrisma.sessionCampaign.findMany({
    where: {
      centreId,
      // Upper "Manage calling session" scope dropdown must list ONLY real calling sessions
      // (admin-created via createSessionCampaign). direct_attendance campaigns are auto-created
      // by ensureDirectAttendanceCampaign when attendance syncs in — those belong ONLY in the
      // lower "Filter people" dropdown (listSessionsForFilter, which shows all kinds), never here.
      kind: SessionCampaignKind.called,
      ...(status ? { status } : {}),
      ...(batchId ? { batchId } : {}),
      // A campaign carries no category of its own — it inherits its batch's. Without this the
      // list returned both categories, so a Youth<->Congregation switch left the OTHER category's
      // calling session selected on Pool Assignment while the roster reloaded for the new one.
      // Every row then failed to resolve and the screen said "aren't in any batch yet" about
      // people who each had an active batch_members row (2026-08-23).
      ...(category ? { batch: { is: { category } } } : {}),
    },
    include: {
      batch: true,
      createdBy: { select: { id: true, name: true } },
      assignments: {
        where: { removedAt: null },
        include: {
          caller: { select: { id: true, name: true } },
          batchMember: { select: { id: true, mobile: true } },
        },
      },
    },
    // Newest first. Was "asc", which put the oldest calling session at the top of every dropdown
    // built from this list — so the one the admin almost always wants was at the bottom.
    orderBy: { scheduledAt: "desc" },
  });

  // Attach the human-readable session name (from the Session mirror) so the pool-assignment
  // page can show "<session name>" as the heading above that session's callers. `kind` already
  // rides along on each campaign row (called | direct_attendance).
  const sessions = await rawPrisma.session.findMany({
    where: { sessionId: { in: campaigns.map((c) => c.aeSessionId) } },
    select: { sessionId: true, displayName: true, date: true, status: true },
  });
  const sessionBy = new Map(sessions.map((s) => [s.sessionId, s]));

  return campaigns.map((c) => {
    const s = sessionBy.get(c.aeSessionId);
    return {
      ...c,
      sessionName: s?.displayName ?? c.aeSessionId,
      sessionDate: s?.date ?? c.scheduledAt,
      // The AttendEase session behind this campaign is gone or deleted.
      //
      // Deleting a session in AttendEase is a HARD delete. This list deliberately filters on
      // kind/status and never joined `sessions`, so a campaign whose session had been destroyed
      // kept being offered here as though it were live — while every registerer-facing list hid
      // it, because those filter `status: { not: "deleted" }`. On 2026-08-17 that left campaign
      // #57 visible to the admin, assignable, and impossible for any registerer to mark against.
      //
      // Reported rather than filtered out: the campaign still owns real assignments and recorded
      // calls, so silently hiding it would strand that history where nobody could reach it. The
      // client can label it and refuse new allotments.
      sessionMissing: !s || s.status === "deleted",
    };
  });
};

export const assignSessionCallers = async ({
  campaignId,
  centreId,
  assignments,
  allotments,
  actorId,
  now,
}: {
  campaignId: number;
  centreId: number;
  assignments: { batch_member_id: number; caller_id: number }[];
  allotments?: { caller_id: number; key: string; label?: string; filter?: string; replace?: boolean }[];
  actorId?: number;
  now: Date;
}) => {
  const campaign = await rawPrisma.sessionCampaign.findUnique({ where: { id: campaignId } });
  if (!campaign || campaign.centreId !== centreId) {
    throw new ApiError(404, "Session campaign not found");
  }
  if (campaign.status !== SessionCampaignStatus.active) {
    throw new ApiError(409, "Campaign is not active — cannot assign callers");
  }

  // One number, one place. Anyone held somewhere else (an open follow-up, or waiting for a call in
  // another active session) cannot be pulled into this session until they come back. Enforced HERE,
  // not only on the Pool page, so Planned Sessions and older app builds obey it too.
  //
  // The whole save is refused rather than silently trimmed: older builds report "Assigned N" from
  // what they SENT, so skipping people quietly would tell the admin they were assigned when they
  // were not. A refusal that names them is the honest answer on every build.
  // Someone who already holds a live row in THIS session is not being pulled in — a re-save or a
  // hand-over between callers — so they are never refused.
  const alreadyHere = new Set(
    (
      await rawPrisma.sessionCallAssignment.findMany({
        where: { campaignId, removedAt: null, batchMemberId: { in: assignments.map((a) => a.batch_member_id) } },
        select: { batchMemberId: true },
      })
    ).map((r) => r.batchMemberId),
  );
  const members = await rawPrisma.batchMember.findMany({
    where: { id: { in: assignments.map((a) => a.batch_member_id).filter((id) => !alreadyHere.has(id)) } },
    select: { id: true, mobile: true },
  });
  const busy = await busyByMobile(centreId, members.map((m) => m.mobile), campaignId);
  if (busy.size > 0) {
    const names = await rawPrisma.registration.findMany({
      where: { centreId, normalizedMobile: { in: [...busy.keys()] } },
      select: { normalizedMobile: true, name: true },
    });
    const nameOf = new Map(names.map((r) => [r.normalizedMobile, r.name]));
    const lines = [...busy.entries()].map(([m, b]) => `${nameOf.get(m) || m} — ${b.label}${b.caller ? ` (${b.caller})` : ""}`);
    throw new ApiError(
      409,
      `${busy.size} ${busy.size === 1 ? "person is" : "people are"} already somewhere else and can't be assigned until they come back: ${lines.join("; ")}`,
    );
  }

  // Said "Not interested" / "Wrong number" (or archived for absence) and not seen since: not
  // allottable, from any list or any app build. Refused by name for the same reason as `busy`.
  {
    const byNorm = new Map<string, string>();
    for (const m of members) {
      try {
        byNorm.set(normalizeMobile(m.mobile), m.mobile);
      } catch {
        /* unparseable roster mobile */
      }
    }
    const lastAttended = (n: string): string | null => {
      try {
        const real = (m: string) => attendeaseRepo.allAttendance({ mobile: m }).find((r) => !isCallLogAttendance(r.remark))?.date;
        return real(byNorm.get(n) ?? n) ?? real(n) ?? null;
      } catch {
        return null;
      }
    };
    const out = await heldOutPeople(centreId, [...byNorm.keys()], lastAttended);
    if (out.size > 0) {
      const names = await rawPrisma.registration.findMany({
        where: { centreId, normalizedMobile: { in: [...out.keys()] } },
        select: { normalizedMobile: true, name: true },
      });
      const nameOf = new Map(names.map((r) => [r.normalizedMobile, r.name]));
      const lines = [...out.entries()].map(([m, o]) => `${nameOf.get(m) || m} — ${o.reason}`);
      throw new ApiError(
        409,
        `${out.size} ${out.size === 1 ? "person has" : "people have"} been removed and can't be assigned: ${lines.join("; ")}`,
      );
    }
  }

  const allotmentByCaller = new Map((allotments ?? []).map((a) => [a.caller_id, a]));

  // "Replace this caller's allotment": clear what they hold in this campaign before writing the
  // new list, so the caller ends up with exactly the rows just allotted rather than the union.
  // SOFT-removed (removedAt), never deleted — the people already rung keep their outcome and
  // history, and re-allotting any of them below revives the same row via the upsert's removedAt
  // reset. Done before the upserts so a person present in both the old and new list survives.
  const replacingCallerIds = (allotments ?? []).filter((a) => a.replace).map((a) => a.caller_id);
  if (replacingCallerIds.length > 0) {
    await rawPrisma.sessionCallAssignment.updateMany({
      where: { campaignId, callerId: { in: replacingCallerIds }, removedAt: null },
      data: { removedAt: now, removedById: actorId ?? null },
    });
  }

  // Who held each of these people BEFORE this save — needed to spot genuine hand-overs below.
  const previous = await rawPrisma.sessionCallAssignment.findMany({
    where: { campaignId, batchMemberId: { in: assignments.map((a) => a.batch_member_id) } },
    select: { batchMemberId: true, callerId: true },
  });
  const previousCallerId = new Map(previous.map((p) => [p.batchMemberId, p.callerId]));

  await rawPrisma.$transaction(
    assignments.map(({ batch_member_id, caller_id }) => {
      const allotment = allotmentByCaller.get(caller_id);
      // Provenance always reflects the allotment being made NOW: re-allotting someone moves them
      // to the list they were just picked from, which is the list the admin will look for them in.
      const provenance = allotment
        ? {
            allotmentKey: allotment.key,
            allotmentLabel: allotment.label ?? null,
            allotmentFilter: allotment.filter ?? null,
          }
        : // An app build from before provenance existed sends none. Leave whatever is already on
          // the row alone rather than blanking a heading the admin can see, and write nothing new.
          {};
      return rawPrisma.sessionCallAssignment.upsert({
        where: { campaignId_batchMemberId: { campaignId, batchMemberId: batch_member_id } },
        create: {
          campaignId,
          batchMemberId: batch_member_id,
          callerId: caller_id,
          callStatus: SessionCallStatus.pending,
          ...provenance,
        },
        // Re-assigning a previously soft-removed person brings them back into the live view —
        // otherwise they'd stay invisible to both the caller's queue and the admin's expand list.
        // callStatus/calledAt/outcomeLabel are deliberately NOT reset: when a person is handed to a
        // new caller, that caller must SEE what the previous one already got out of them rather than
        // ringing a number that was answered yesterday.
        update: { callerId: caller_id, removedAt: null, removedById: null, ...provenance },
      });
    })
  );

  // A hand-over moves the PERSON, not just the queue row. The outcome above stays visible to the new
  // caller, but ownership has to follow too — otherwise a positive response left by the old caller
  // keeps its follow-up parked under them (getFollowupList scopes by ownerCallerId), so the new
  // caller inherits the call but never sees the pre-class confirmation it created.
  const handovers = assignments.filter(
    (a) => previousCallerId.has(a.batch_member_id) && previousCallerId.get(a.batch_member_id) !== a.caller_id,
  );

  for (const { batch_member_id, caller_id } of handovers) {
    try {
      const member = await rawPrisma.batchMember.findUnique({
        where: { id: batch_member_id },
        select: { mobile: true },
      });
      if (!member?.mobile) continue;
      const registration = await rawPrisma.registration.findFirst({
        where: { centreId, normalizedMobile: normalizeMobile(member.mobile) },
        select: { id: true },
      });
      if (!registration) continue;

      // The new caller is now this number's caller of record.
      await rawPrisma.registration.update({
        where: { id: registration.id },
        data: { currentAssignedCallerId: caller_id },
      });

      // Move any live follow-up across so it surfaces in the new caller's Follow-ups tab.
      // Scoped to THIS session's follow-up (or a legacy one with no session link) so a follow-up
      // belonging to a different class is not silently reassigned along with it.
      await rawPrisma.followup.updateMany({
        where: {
          registrationId: registration.id,
          status: FollowupStatus.active,
          OR: [{ campaignId }, { campaignId: null }],
        },
        data: { ownerCallerId: caller_id },
      });
    } catch {
      // One unparseable mobile or missing registration must not fail the whole assignment save.
    }
  }

  return assignments.length;
};

// Progress numbers for the caller's "Today" strip. Counted over all of this caller's live
// assignments in active campaigns (not just the not-yet-attended ones the queue displays), so
// done + left always add up to the caller's real workload.
export const getSessionCallSummary = async ({
  callerId,
  centreId,
  now = new Date(),
}: {
  callerId: number;
  centreId: number;
  now?: Date;
}): Promise<{ total: number; done: number; left: number; scheduled: number }> => {
  // Deferred people are not part of today's workload — counting them would leave the caller with a
  // "left" number they cannot drive to zero. They are reported separately as `scheduled` instead.
  const notSnoozed = { OR: [{ snoozeUntil: null }, { snoozeUntil: { lte: now } }] };
  const [total, left, scheduled] = await Promise.all([
    rawPrisma.sessionCallAssignment.count({
      where: { callerId, campaign: { centreId, status: SessionCampaignStatus.active }, removedAt: null, batchMember: notSnoozed },
    }),
    rawPrisma.sessionCallAssignment.count({
      where: {
        callerId,
        campaign: { centreId, status: SessionCampaignStatus.active },
        removedAt: null,
        callStatus: SessionCallStatus.pending,
        batchMember: notSnoozed,
      },
    }),
    rawPrisma.sessionCallAssignment.count({
      where: {
        callerId,
        campaign: { centreId, status: SessionCampaignStatus.active },
        removedAt: null,
        batchMember: { snoozeUntil: { gt: now } },
      },
    }),
  ]);
  return { total, done: total - left, left, scheduled };
};

// ── Call deferral ("call me next week / next month") ─────────────────────────────────────────
// A deferral is stored as a start-of-day date on the BATCH MEMBER, not on the call assignment:
// assignments belong to one campaign and the caller queue only reads active campaigns, so a
// month-long deferral would disappear when its session completed. Held on the roster, the person
// simply returns once the date passes.
export const MAX_CONSECUTIVE_DEFERRALS = 3; // free postponements before deferrals start striking again

// Midnight of the target day, so "call back tomorrow" is available first thing tomorrow rather than
// at whatever time today's call happened to be made.
export const startOfDayAfter = (from: Date, days: number): Date => {
  const d = new Date(from);
  d.setDate(d.getDate() + days);
  d.setHours(0, 0, 0, 0);
  return d;
};

export const isStillSnoozed = (snoozeUntil: Date | null | undefined, now: Date): boolean =>
  snoozeUntil != null && snoozeUntil.getTime() > now.getTime();

export const getSessionCallQueue = async ({
  callerId,
  centreId,
  now = new Date(),
}: {
  callerId: number;
  centreId: number;
  now?: Date;
}) => {
  const rows = await rawPrisma.sessionCallAssignment.findMany({
    where: {
      callerId,
      campaign: { centreId, status: SessionCampaignStatus.active },
      callStatus: { not: SessionCallStatus.attended },
      removedAt: null,
    },
    include: {
      campaign: { include: { batch: true } },
      batchMember: { select: { id: true, mobile: true, snoozeUntil: true } },
    },
    orderBy: { campaign: { scheduledAt: "asc" } },
  });

  // Enrich so the caller's screen shows a NAME (not a bare mobile) and the session's display name
  // ("test") rather than its internal id ("test-calling-session"). First attendance IS the
  // registration here, so a name comes from a CB registration OR AttendEase's person directory.
  const mobiles = rows.map((r) => r.batchMember.mobile.replace(/\D/g, ""));
  const regs = mobiles.length
    ? await rawPrisma.registration.findMany({
        where: { centreId, normalizedMobile: { in: mobiles } },
        select: { normalizedMobile: true, name: true },
      })
    : [];
  const nameByMobile = new Map(regs.map((r) => [mobileKey(r.normalizedMobile), r.name]));
  const directoryName = attendeasePersonNames();

  const sessionIds = [...new Set(rows.map((r) => r.campaign.aeSessionId))];
  const sessions = sessionIds.length
    ? await rawPrisma.session.findMany({
        where: { sessionId: { in: sessionIds } },
        select: { sessionId: true, displayName: true },
      })
    : [];
  const nameBySession = new Map(sessions.map((s) => [s.sessionId, s.displayName]));

  // The admin's per-session note for THIS caller (if any) — shown beside the session name.
  const campaignIds = [...new Set(rows.map((r) => r.campaign.id))];
  const notes = campaignIds.length
    ? await rawPrisma.sessionCallerNote.findMany({
        where: { callerId, campaignId: { in: campaignIds } },
        select: { campaignId: true, note: true },
      })
    : [];
  const noteByCampaign = new Map(notes.map((n) => [n.campaignId, n.note]));

  // Per-person calling context (previous responses + attendance + details) so the caller's card is
  // self-contained — session calls get the SAME context as pool calls.
  const context = await buildPeopleContext(centreId, rows.map((r) => r.batchMember.mobile));
  const ctxFor = (mobile: string) => {
    try {
      return context.get(normalizeMobile(mobile)) ?? emptyPersonContext();
    } catch {
      return emptyPersonContext();
    }
  };

  const enriched = rows.map((r) => ({
    ...r,
    batchMember: { ...r.batchMember, name: nameByMobile.get(mobileKey(r.batchMember.mobile)) ?? directoryName.get(mobileKey(r.batchMember.mobile)) ?? null },
    campaign: {
      ...r.campaign,
      sessionName: nameBySession.get(r.campaign.aeSessionId) ?? r.campaign.aeSessionId,
      callerNote: noteByCampaign.get(r.campaign.id) ?? null,
      // Rendered per person, not per campaign: {name} differs on every row. Null when the admin
      // set no message, which is what makes the app fall back to opening WhatsApp with no text.
      whatsappMessage: renderWhatsappMessage(r.campaign.whatsappMessage, {
        name: nameByMobile.get(mobileKey(r.batchMember.mobile)) ?? directoryName.get(mobileKey(r.batchMember.mobile)),
        mobile: r.batchMember.mobile,
        session: nameBySession.get(r.campaign.aeSessionId) ?? r.campaign.aeSessionId,
        batch: r.campaign.batch?.name,
        date: r.campaign.scheduledAt,
      }),
    },
    context: ctxFor(r.batchMember.mobile),
  }));

  // Split the deferred people ("call me next week") out of the list the caller works through today.
  // Evaluated here at READ time rather than by a scheduled job, so a person reappears the moment
  // their date passes with nothing to run, miss, or retry. A due-today deferral compares as NOT
  // still-snoozed and lands back in `active`, which is exactly "reappears in the batch roster".
  const active: typeof enriched = [];
  const scheduled: typeof enriched = [];
  for (const row of enriched) {
    if (isStillSnoozed(row.batchMember.snoozeUntil, now)) scheduled.push(row);
    else active.push(row);
  }
  // Soonest-due first, so the caller sees who is coming back next.
  scheduled.sort((a, b) => (a.batchMember.snoozeUntil?.getTime() ?? 0) - (b.batchMember.snoozeUntil?.getTime() ?? 0));

  return { active, scheduled };
};

// Human-readable label stored as the call-history outcome for a session call, so it shows up in the
// person's "previous responses" the same way a pool-call outcome does.
const sessionStatusLabel: Partial<Record<SessionCallStatus, string>> = {
  [SessionCallStatus.will_attend]: "Will attend",
  [SessionCallStatus.wont_attend]: "Won't attend",
  [SessionCallStatus.no_answer]: "No answer",
  [SessionCallStatus.called]: "Called",
  [SessionCallStatus.deferred]: "Call back later (scheduled)",
  [SessionCallStatus.attended]: "Attended",
};

// Session calls now use the SAME configurable calling-outcome list as pool calls. The chosen label is
// stored verbatim in history; for the session's own attendance-prediction we derive a coarse status
// from the label by keyword. Anything that isn't clearly will/won't/no-answer falls back to `called`
// (logged-but-undecided) — which keeps the person in the session queue until they actually attend.
const deriveStatusFromOutcome = (label: string): SessionCallStatus => {
  const l = label.toLowerCase();
  if (/(no answer|no response|voice ?mail|voice message|unreachable|switched off|not reachable|did ?n'?t (pick|answer)|ringing|busy)/.test(l)) {
    return SessionCallStatus.no_answer;
  }
  if (/(won'?t|will not|not attend|not coming|not interested|declin|refus|cancel)/.test(l)) {
    return SessionCallStatus.wont_attend;
  }
  if (/(will attend|attending|will come|coming|yes|interested|confirm|agreed|spoke)/.test(l)) {
    return SessionCallStatus.will_attend;
  }
  return SessionCallStatus.called;
};

// Prefer the EXPLICIT dropdown→action mapping (what the seed/admin configured) over keyword
// guessing, so a label like "Won't come this time" (action continue_calling → tolerated) and
// "Not interested / Won't ever come" (action remove_from_running → firm refusal) behave exactly
// as mapped rather than both collapsing to wont_attend on the word "won't". Falls back to
// deriveStatusFromOutcome when no mapping exists (custom/legacy labels).
// Also reports the deferral interval, which is read from the OPTION (deferDays) rather than parsed
// out of the label — same principle as the action mapping: a centre that retitles "Call next week"
// in its own words must not silently change how far the call is pushed.
const resolveSessionOutcome = async (
  label: string,
  dropdownId: number | null,
  centreId: number | null
): Promise<{
  status: SessionCallStatus;
  deferDays: number | null;
  dropdownId: number | null;
  // The option's "remove after N of these in a row" rule, carried out with the resolution so the
  // enforcement below does not have to re-read the row it was just resolved from.
  threshold: ThresholdOption | null;
  // The declared action, so the save can act on "remove" the moment it is recorded.
  action: DropdownAction | null;
  label: string;
}> => {
  // Matches by id first, then by current name, then by any name the option used to have. The old
  // lookup was name-only and active-only, so renaming or hiding a response while a caller was
  // offline orphaned every outcome in their outbox and dropped it onto the keyword guesser.
  //
  // Whatever it finds, the meaning below is read off the LIVE row — an old name locates the
  // option, it never resurrects the option's old behaviour.
  const { option } = await resolveCallingOption(rawPrisma, {
    dropdownId,
    label,
    centreId,
    context: DropdownContext.calling,
  });
  const action = option?.mapping?.action;
  const resolvedId = option?.id ?? null;
  const threshold: ThresholdOption | null = option
    ? { id: option.id, label: option.label, removeAfterConsecutive: option.removeAfterConsecutive }
    : null;
  if (action === DropdownAction.defer_calling) {
    // A deferral option with no/garbage interval would otherwise snooze the person to "now" and
    // reappear instantly. Fall back to a week rather than silently doing nothing.
    const days = option?.deferDays && option.deferDays > 0 ? option.deferDays : 7;
    return { status: SessionCallStatus.deferred, deferDays: days, dropdownId: resolvedId, threshold, action: action ?? null, label: option?.label ?? label };
  }
  return {
    status: resolveSessionStatusFrom(action, option?.sentiment, label),
    deferDays: null,
    dropdownId: resolvedId,
    threshold,
    action: action ?? null,
    label: option?.label ?? label,
  };
};

/**
 * Coarse session status from the option's DECLARED meaning.
 *
 * `sentiment` is what separates the two things `continue_calling` covers — "I could not reach
 * them" from "I reached them and they said no this time". This used to be decided by matching the
 * label against /(no answer|switch|unreachable|voice|ringing)/, which is why "Out of patna" and
 * "No incoming" — both plainly unreachable — were recorded as `called`, i.e. as contact made. A
 * centre naming its own response in Hindi, or in any words the pattern did not anticipate, hit the
 * same wall. The label is no longer consulted except as a last resort.
 *
 * Exported for scripts/verify-outcome-resolution.ts, which asserts the seeded twelve.
 */
export const resolveSessionStatusFrom = (
  action: DropdownAction | undefined,
  sentiment: ResponseSentiment | undefined,
  label: string
): SessionCallStatus => {
  switch (action) {
    case DropdownAction.move_to_followup:
      return SessionCallStatus.will_attend;
    case DropdownAction.followup_callback:
      // Positive, but NOT a promise to attend — keeps session forecasts honest.
      return SessionCallStatus.call_back_later;
    case DropdownAction.remove_from_running:
    case DropdownAction.mark_invalid:
      return SessionCallStatus.wont_attend; // leaves the attendance-prediction queue
    case DropdownAction.continue_calling:
      // Tolerated attempt — keep predicting "undecided", but a response that means "never reached
      // them" is a non-contact, and non-contact is not the person's fault.
      return sentiment === ResponseSentiment.not_reached
        ? SessionCallStatus.no_answer
        : SessionCallStatus.called;
    default:
      // No mapping at all: a legacy row, or an old client sending a label this database has never
      // seen. Keyword guessing is wrong, but it is the only thing left, and refusing the write
      // would lose a caller's work.
      return deriveStatusFromOutcome(label);
  }
};

export const updateSessionCallStatus = async ({
  assignmentId,
  callerId,
  centreId,
  callStatus,
  outcome,
  remark,
  answered,
  dropdownId,
  now,
}: {
  assignmentId: number;
  callerId: number;
  centreId: number;
  callStatus?: SessionCallStatus;
  outcome?: string;
  remark?: string;
  // Step 1 of the caller's two-step capture: did the person pick up at all? Optional, because an
  // app build from before the two-step shipped does not send it — those rows keep NULL, which is
  // exactly what every pre-existing row carries.
  answered?: boolean;
  // Which response was tapped, by id. Optional for the same reason `answered` is: builds from
  // before this shipped send only the label. When present it survives any rename, including one
  // made while this very outcome sat in the caller's outbox.
  dropdownId?: number;
  now: Date;
}) => {
  const assignment = await rawPrisma.sessionCallAssignment.findUnique({
    where: { id: assignmentId },
    include: { campaign: true, batchMember: { select: { id: true, mobile: true, snoozeUntil: true, snoozeCount: true } } },
  });
  if (!assignment || assignment.callerId !== callerId || assignment.campaign.centreId !== centreId || assignment.removedAt) {
    throw new ApiError(404, "Session call assignment not found");
  }
  // Late responses (session closed/completed) are NO LONGER rejected — an offline caller who returns
  // after the session ended would otherwise lose all their queued outcomes forever. We accept and
  // record them, and for a COMPLETED session re-run the removal/keeping decision (a late "will
  // attend" can restore someone wrongly archived; a late firm refusal can remove). We do NOT create
  // a follow-up CALL for a late response: that session's class is over and its follow-ups are
  // already closed. The window (measured from completion ≈ campaign.updatedAt) gates only extra
  // side-effects — removal/keeping applies regardless of age.
  const campaignStatus = assignment.campaign.status;
  const isLate = campaignStatus !== SessionCampaignStatus.active;
  const daysSinceCompletion =
    campaignStatus === SessionCampaignStatus.completed
      ? (now.getTime() - assignment.campaign.updatedAt.getTime()) / (24 * 60 * 60 * 1000)
      : 0;
  const withinFullWindow = !isLate || daysSinceCompletion <= LATE_RESPONSE_WINDOW_DAYS;

  // Prefer a configurable outcome label (same list as pool calls); fall back to the legacy enum path.
  const trimmedOutcome = outcome?.trim();
  const resolved = trimmedOutcome
    ? await resolveSessionOutcome(trimmedOutcome, dropdownId ?? null, centreId)
    : { status: callStatus ?? SessionCallStatus.called, deferDays: null, dropdownId: null, threshold: null, action: null, label: "" };
  let resolvedStatus: SessionCallStatus = resolved.status;

  // ── Deferral ("call me next week") ──────────────────────────────────────────────────────────
  // Distinct from "Call back later / Busy", which means the phone was NOT picked up and remains a
  // strike. Here the person answered and named a date, so we park them until then instead.
  const member = assignment.batchMember;
  if (resolved.deferDays != null) {
    const consecutive = member.snoozeCount + 1;
    // The date is always honoured. But after MAX_CONSECUTIVE_DEFERRALS free postponements the
    // absence clock resumes (recorded as `called`, which strikes), so nobody can defer forever and
    // never reach the removal threshold.
    if (consecutive > MAX_CONSECUTIVE_DEFERRALS) resolvedStatus = SessionCallStatus.called;
    await rawPrisma.batchMember.update({
      where: { id: member.id },
      data: { snoozeUntil: startOfDayAfter(now, resolved.deferDays), snoozeCount: consecutive },
    });
  } else if (member.snoozeUntil !== null || member.snoozeCount > 0) {
    // Any non-deferral outcome ends the run — both the parked date and the consecutive count, so a
    // person who postpones twice then actually answers starts clean next time.
    await rawPrisma.batchMember.update({
      where: { id: member.id },
      data: { snoozeUntil: null, snoozeCount: 0 },
    });
  }

  const historyLabel = trimmedOutcome || sessionStatusLabel[resolvedStatus] || resolvedStatus;

  const updated = await rawPrisma.sessionCallAssignment.update({
    where: { id: assignmentId },
    data: {
      callStatus: resolvedStatus,
      calledAt: assignment.calledAt ?? now,
      outcomeLabel: trimmedOutcome ?? null,
    },
  });

  // The registration this call is about. Looked up once, outside the history mirror's try, because
  // the removal below must run even when the mirror fails.
  let personRegId: number | null = null;
  try {
    const reg = await rawPrisma.registration.findFirst({
      where: { centreId, normalizedMobile: normalizeMobile(assignment.batchMember.mobile) },
      select: { id: true },
    });
    personRegId = reg?.id ?? null;
  } catch {
    /* unparseable roster mobile — the batch membership is still removed by id below */
  }
  const removes = isRemovalAction(resolved.action);

  // A caller correcting "Not interested" to anything that does not remove gets the person back —
  // exactly what that answer took away, nothing more. Runs BEFORE the mirror so a corrected "Will
  // attend" still creates its follow-up on top of the restore.
  if (!removes && trimmedOutcome) {
    try {
      await revertDeclaredRemoval({
        centreId,
        mobile: assignment.batchMember.mobile,
        batchMemberId: assignment.batchMember.id,
        registrationId: personRegId,
        assignmentId,
        now,
      });
    } catch (e) {
      logger.error(`declared-removal undo failed assignment=${assignmentId}: ${(e as Error).message}`);
    }
  }

  // Mirror the outcome into call history (unified with pool calls) and, on a clear will-attend
  // response, surface the person in the caller's follow-up list INSTANTLY. "Follow-up" here means
  // "call again before class to confirm they're still coming" — not "call next time" — so a
  // positive response must not wait for the session to be closed/completed to become visible.
  // Best-effort: match the member's mobile to a registration; if none, skip — the status update
  // above already succeeded.
  try {
    const normalized = normalizeMobile(assignment.batchMember.mobile);
    const registration = await rawPrisma.registration.findFirst({
      where: { centreId, normalizedMobile: normalized },
      select: { id: true, category: true, currentAssignedCallerId: true, originalCallerId: true, firstConnectedBy: true, currentStage: true },
    });
    if (registration) {
      // A calling session holds exactly ONE response per person. The assignment row above is
      // already updated in place; this mirror must behave the same way, or a caller who scrolls
      // back and corrects an answer stacks a second history row and the person then shows two
      // conflicting "last responses" for the same day. Keyed on (registration, campaign) so a
      // re-answer overwrites, while a genuinely different session still gets its own row.
      await rawPrisma.callAttempt.upsert({
        where: {
          registrationId_campaignId: {
            registrationId: registration.id,
            campaignId: assignment.campaignId,
          },
        },
        update: {
          callerId,
          dropdownValue: historyLabel,
          remark: remark?.trim() ? remark.trim() : null,
          // Recorded by ID as well as by label. The label is what a human reads back; the id is
          // what keeps this row's MEANING intact when a centre later renames the option. Without
          // it, renaming "Out of patna" would orphan every attempt that used the old wording.
          dropdownId: resolved.dropdownId,
          answered: answered ?? null,
        },
        create: {
          registrationId: registration.id,
          callerId,
          centreId,
          campaignId: assignment.campaignId,
          callType: CallType.attendance_conversion_call,
          dropdownValue: historyLabel,
          remark: remark?.trim() ? remark.trim() : null,
          dropdownId: resolved.dropdownId,
          answered: answered ?? null,
        },
      });

      // "Remove after N of these in a row" (e.g. a number that is switched off every time). Runs on
      // the history row just written, so the response being recorded is counted. Independent of the
      // strike system — see lib/unreachable-threshold.ts for why the two are kept apart.
      try {
        await enforceConsecutiveRemoval({
          registrationId: registration.id,
          centreId,
          mobile: assignment.batchMember.mobile,
          option: resolved.threshold,
          now,
        });
      } catch {
        /* removal is best-effort; the caller's response is already saved */
      }

      // Follow-up CALL (pre-class "confirm they're still coming") — ONLY while the session is still
      // active. A late will-attend must NOT create one: the class is over and this session's
      // follow-ups are already closed.
      const followupKind =
        resolvedStatus === SessionCallStatus.call_back_later
          ? FollowupKind.call_back_later
          : FollowupKind.will_attend;
      if (
        (resolvedStatus === SessionCallStatus.will_attend ||
          resolvedStatus === SessionCallStatus.call_back_later) &&
        !isLate
      ) {
        // The caller who logged this will-attend is the one responsible for the pre-class
        // confirmation call, so THEY must own (and therefore SEE) the follow-up. Previously this
        // fell back to the registration's stale owner (currentAssignedCallerId/originalCallerId/
        // firstConnectedBy) — which for a called-but-registered-elsewhere person resolved to the
        // original registerer, hiding the follow-up from the caller who actually made contact.
        // getFollowupList scopes by ownerCallerId, so a mismatched owner => empty list for the
        // caller (exactly the "follow-up vanishes when I go online" bug).
        const ownerCallerId = callerId;
        // campaignId records WHICH class this confirmation call is for. There is one follow-up row
        // per person (registrationId is unique), so re-pointing it here is what stops a stale
        // follow-up from an earlier class masquerading as a live one for this session.
        await rawPrisma.followup.upsert({
          where: { registrationId: registration.id },
          update: {
            ownerCallerId,
            status: FollowupStatus.active,
            centreId,
            isLocked: false,
            lockedReason: null,
            campaignId: assignment.campaignId,
            kind: followupKind,
          },
          create: {
            registrationId: registration.id,
            ownerCallerId,
            centreId,
            status: FollowupStatus.active,
            campaignId: assignment.campaignId,
            kind: followupKind,
          },
        });
        // A positive answer lifts the person into follow-up EVEN IF they were previously struck off
        // or archived. Someone saying "yes, I'm coming" is the strongest possible signal to bring
        // them back — refusing to move them left the follow-up row active but invisible, because
        // getFollowupList requires currentStage=followup_active. The caller logged a yes and got
        // nothing in their Follow-ups tab. Seen live on 2026-08-01: 9 people struck off in the July
        // purge answered positively and every one of those answers was swallowed.
        await rawPrisma.registration.update({
          where: { id: registration.id },
          data: { currentStage: RegistrationStage.followup_active, activeQueue: ActiveQueue.followup },
        });
        try { await recomputeCentreCategory(rawPrisma, centreId, registration.category); } catch { /* non-critical */ }
      }
    }
  } catch {
    // mobile unparseable or registration lookup failed — history mirror is non-critical.
  }

  // "Not interested" / "Wrong number": out NOW, not when an admin eventually completes the session.
  // Not inside the mirror's try — a failure there must not leave a refuser in the batch.
  if (removes) {
    try {
      const session = await rawPrisma.session.findFirst({
        where: { sessionId: assignment.campaign.aeSessionId },
        select: { displayName: true },
      });
      await applyDeclaredRemoval({
        centreId,
        action: resolved.action!,
        label: resolved.label || historyLabel,
        mobile: assignment.batchMember.mobile,
        batchMemberId: assignment.batchMember.id,
        registrationId: personRegId,
        assignmentId,
        campaignId: assignment.campaignId,
        where: session?.displayName ?? assignment.campaign.aeSessionId,
        now,
      });
    } catch (e) {
      logger.error(`declared-removal failed assignment=${assignmentId}: ${(e as Error).message}`);
    }
  }

  // For a late response on an already-COMPLETED session, re-run the removal/keeping decision so the
  // updated outcome takes effect (restore a wrongly-archived person, or remove on a late refusal).
  // `withinFullWindow` (≤ LATE_RESPONSE_WINDOW_DAYS since completion) is reserved for any extra
  // side-effects; removal/keeping itself applies at any age per the agreed rule.
  if (isLate && campaignStatus === SessionCampaignStatus.completed) {
    void withinFullWindow; // documents the window boundary; removal/keeping runs regardless
    try {
      await reevaluatePersonForBatch(centreId, assignment.campaign.batchId, assignment.batchMember.mobile, now);
    } catch {
      // re-evaluation is best-effort; the outcome write above already succeeded.
    }
  }

  return updated;
};

// Admin: instantly unassign a person from a caller while the campaign is still active (not yet
// retrieved/closed). Soft-remove only — the row and any outcome/history it already has stays in
// the database for audit, it just stops appearing in any live view (caller queue, admin expand
// list, reconciliation summary) because every one of those queries filters removedAt: null.
export const removeSessionCallAssignment = async ({
  campaignId,
  assignmentId,
  centreId,
  removedById,
  now,
}: {
  campaignId: number;
  assignmentId: number;
  centreId: number;
  removedById: number;
  now: Date;
}) => {
  const assignment = await rawPrisma.sessionCallAssignment.findUnique({
    where: { id: assignmentId },
    include: { campaign: true },
  });
  if (!assignment || assignment.campaignId !== campaignId || assignment.campaign.centreId !== centreId) {
    throw new ApiError(404, "Session call assignment not found");
  }
  if (assignment.campaign.status !== SessionCampaignStatus.active) {
    throw new ApiError(409, "Can only remove an assignment while the campaign is active");
  }
  if (assignment.removedAt) {
    return assignment; // already removed — idempotent
  }

  return rawPrisma.sessionCallAssignment.update({
    where: { id: assignmentId },
    data: { removedAt: now, removedById },
  });
};

// Admin: set (or clear, with an empty string) the note shown beside this session's name in a
// specific caller's "My Callings" list. One note per (campaign, caller) pair.
export const setSessionCallerNote = async ({
  campaignId,
  callerId,
  centreId,
  note,
  updatedById,
}: {
  campaignId: number;
  callerId: number;
  centreId: number;
  note: string;
  updatedById: number;
}) => {
  const campaign = await rawPrisma.sessionCampaign.findUnique({ where: { id: campaignId } });
  if (!campaign || campaign.centreId !== centreId) {
    throw new ApiError(404, "Session campaign not found");
  }

  const trimmed = note.trim();
  if (!trimmed) {
    await rawPrisma.sessionCallerNote.deleteMany({ where: { campaignId, callerId } });
    return null;
  }

  return rawPrisma.sessionCallerNote.upsert({
    where: { campaignId_callerId: { campaignId, callerId } },
    create: { campaignId, callerId, note: trimmed, updatedById },
    update: { note: trimmed, updatedById },
  });
};

// Admin: set (or clear, with an empty string) the WhatsApp message this calling session prefills
// for every caller working it. One message per campaign — unlike the caller note, which is per
// (campaign, caller), because the message goes to the PERSON being called, not to the caller.
export const setCampaignWhatsappMessage = async ({
  campaignId,
  centreId,
  message,
}: {
  campaignId: number;
  centreId: number;
  message: string;
}) => {
  const campaign = await rawPrisma.sessionCampaign.findUnique({ where: { id: campaignId } });
  if (!campaign || campaign.centreId !== centreId) {
    throw new ApiError(404, "Session campaign not found");
  }

  // Empty clears to NULL rather than storing "". Both would mean "no prefill", but only one of
  // them does so everywhere the column is read, including the `whatsappMessage ?? null` paths.
  const trimmed = message.trim();
  return rawPrisma.sessionCampaign.update({
    where: { id: campaignId },
    data: { whatsappMessage: trimmed || null },
  });
};

// ─── Session lifecycle ────────────────────────────────────────────────────────

export const closeSessionCampaign = async (campaignId: number, centreId: number) => {
  const campaign = await rawPrisma.sessionCampaign.findUnique({ where: { id: campaignId } });
  if (!campaign || campaign.centreId !== centreId) throw new ApiError(404, "Session campaign not found");
  if (campaign.status === SessionCampaignStatus.closed) return campaign;
  if (campaign.status !== SessionCampaignStatus.active) {
    throw new ApiError(409, `Cannot close a campaign with status '${campaign.status}'`);
  }
  const updated = await rawPrisma.sessionCampaign.update({
    where: { id: campaignId },
    data: { status: SessionCampaignStatus.closed },
  });

  // "Retrieval" locks the responses this campaign produced (no more edits to a closed campaign's
  // follow-ups) but must NEVER hide/remove them — a follow-up may still need a call minutes or
  // hours before class. Visibility (getFollowupList) is untouched; only markFollowupDone checks
  // isLocked. Best-effort: closing the campaign has already succeeded regardless of this.
  try {
    const assignments = await rawPrisma.sessionCallAssignment.findMany({
      where: { campaignId, removedAt: null },
      select: { batchMember: { select: { mobile: true } } },
    });
    const mobiles = assignments
      .map((a) => { try { return normalizeMobile(a.batchMember.mobile); } catch { return null; } })
      .filter((m): m is string => !!m);
    if (mobiles.length) {
      const regs = await rawPrisma.registration.findMany({
        where: { centreId, normalizedMobile: { in: mobiles } },
        select: { id: true },
      });
      await rawPrisma.followup.updateMany({
        where: { registrationId: { in: regs.map((r) => r.id) }, status: FollowupStatus.active },
        data: { isLocked: true, lockedReason: "Session retrieved — responses locked" },
      });
    }
  } catch { /* best-effort; closing the campaign already succeeded */ }

  return updated;
};

export const reopenSessionCampaign = async (campaignId: number, centreId: number) => {
  const campaign = await rawPrisma.sessionCampaign.findUnique({ where: { id: campaignId } });
  if (!campaign || campaign.centreId !== centreId) throw new ApiError(404, "Session campaign not found");
  if (campaign.status !== SessionCampaignStatus.closed) {
    throw new ApiError(409, "Only closed campaigns can be reopened");
  }

  // Refuse to reopen onto a session that no longer exists.
  //
  // Reopen exists to undo a Retrieve. But deleting the session in AttendEase ALSO closes the
  // campaign (handleSessionDeleted), and those two closed states are indistinguishable from here.
  // Reopening the second kind produced an ACTIVE campaign pointing at a session that had been hard
  // deleted: the admin could see it and assign callers to it, while no registerer could ever mark
  // attendance against it, because every registerer-facing list filters `status: { not: "deleted" }`.
  // That is the exact state campaign #57 was found in on 2026-08-17 — reopened four minutes after
  // its session was destroyed, and permanently stuck as a result.
  //
  // Checked here rather than only in the UI: this is the one transition that can create that state.
  const session = await rawPrisma.session.findUnique({
    where: { sessionId: campaign.aeSessionId },
    select: { status: true },
  });
  if (!session || session.status === "deleted") {
    throw new ApiError(
      409,
      `The session "${campaign.aeSessionId}" no longer exists in AttendEase, so this calling ` +
        `session cannot be reopened — no registerer would be able to mark attendance against it. ` +
        `Create the session again and start a new calling session for it.`,
    );
  }

  return rawPrisma.sessionCampaign.update({
    where: { id: campaignId },
    data: { status: SessionCampaignStatus.active },
  });
};

// Keyword-based action for reconciliation — used when the outcomeLabel doesn't match a DropdownMapping.
// ── Tolerance / strike model (redesigned 2026-07-06) ────────────────────────
// A person absent from a session is TOLERATED for a limited number of absences as long as they
// aren't firmly refusing. On the Nth absence (any response — positive-but-absent, non-contact, or
// soft "not this time" all count) they are auto-archived. A FIRM refusal ("Not interested / Won't
// ever come") is immediate — no tolerance. Attending resets the count for free, because attendees
// are excluded from the absence tally. Archived people are purged after ARCHIVED_PURGE_DAYS.
export const ABSENCE_ARCHIVE_THRESHOLD = 5; // absences tolerated before auto-archive

// Outcomes that do NOT count as an absence. Single shared definition — this used to be declared
// separately in reevaluatePersonForBatch and in the campaign-completion reconciler, and the two
// have to agree or a person is archived by one path and restored by the other.
//   attended / will_attend — the person is coming.
//   deferred              — the person ANSWERED and named a later date. A postponement is a
//                           positive signal; counting it used to auto-archive interested people
//                           after five polite "call me next month"s. Note this is NOT the same as
//                           "Call back later / Busy" (phone not picked up), which resolves to
//                           `called` and still strikes, by design.
const STRIKE_EXCLUDED: SessionCallStatus[] = [
  SessionCallStatus.attended,
  SessionCallStatus.will_attend,
  SessionCallStatus.deferred,
  // They answered the phone and asked to be rung back — the opposite of an absence.
  SessionCallStatus.call_back_later,
  // NO RESPONSE WAS EVER RECORDED. Either nobody rang them, or a caller rang them offline and
  // the response has not synced. Neither is evidence that the person stayed away, so it must not
  // count against them. This matters because a session can now be completed while responses are
  // still outstanding: without this, every un-called person would take a strike for their
  // caller's un-synced work, and five such sessions would auto-archive someone who was never
  // actually contacted. A late response is still accepted after completion and re-runs the
  // keep/remove decision, so the real outcome lands whenever the caller finally syncs.
  SessionCallStatus.pending,
];
export const ARCHIVED_PURGE_DAYS = 30;      // days an auto-archived registration is kept before deletion
// Late queued responses for a COMPLETED session are accepted at any age (they update the outcome
// and re-drive removal/keeping). Within this many days of completion they get "full" treatment;
// beyond it, only removal/keeping applies (there are no other post-completion side-effects today,
// so this is currently a documented boundary rather than a behavioural fork).
export const LATE_RESPONSE_WINDOW_DAYS = 4;

// Auto-archive a registration and stamp a RemovedRecord as the purge anchor (Registration has no
// updatedAt column, so RemovedRecord.removedAt is what the 30-day purge job measures against).
// Idempotent via upsert. Used for both firm-refusal and tolerance-exhausted paths.
const archiveRegistration = async (registrationId: number, reason: string, now: Date): Promise<void> => {
  await rawPrisma.removedRecord.upsert({
    where: { registrationId },
    update: { reason, removedAt: now },
    create: { registrationId, reason, removedAt: now },
  });
  await rawPrisma.registration.update({
    where: { id: registrationId },
    data: { currentStage: RegistrationStage.archived, activeQueue: ActiveQueue.archived },
  });
};

// Firm refusal / bad number → remove; everything else → follow-up, with the absence tolerance
// deciding later. Decided by the response's DECLARED action (remove_from_running, mark_invalid),
// never by its wording. The wording rule this replaces matched "not interested" and "won't ever"
// but not "Wrong number", so wrong numbers were never removed at completion either.
//
// The label pattern is kept only for labels no option row can be found for (history from before
// responses were configurable), where it is the only information there is.
const LEGACY_REFUSAL = /(leave|never (attend|come)|won'?t ever|not interested|won'?t come (any ?more|again)|stopped|kicked out|removing|refus|declin)/;

type Reconciled = { decision: "followup" | "remove"; action: DropdownAction };
type Reconcile = (callStatus: SessionCallStatus, outcomeLabel: string | null) => Promise<Reconciled>;

/** One resolver per centre; each distinct label is looked up once, not once per person. */
const makeReconciler = (centreId: number): Reconcile => {
  const cache = new Map<string, Promise<Reconciled>>();
  const keep: Reconciled = { decision: "followup", action: DropdownAction.continue_calling };
  return (_callStatus, outcomeLabel) => {
    const label = (outcomeLabel ?? "").trim();
    if (!label) return Promise.resolve(keep);
    let hit = cache.get(label);
    if (!hit) {
      hit = resolveCallingOption(rawPrisma, { label, centreId, context: DropdownContext.calling }).then(({ option }) => {
        if (option?.mapping) {
          return isRemovalAction(option.mapping.action)
            ? { decision: "remove" as const, action: option.mapping.action }
            : { decision: "followup" as const, action: option.mapping.action };
        }
        return LEGACY_REFUSAL.test(label.toLowerCase())
          ? { decision: "remove" as const, action: DropdownAction.remove_from_running }
          : keep;
      });
      cache.set(label, hit);
    }
    return hit;
  };
};

// Re-evaluate a person's auto-removal state for a batch after a LATE response changed one of their
// outcomes on an already-completed session. Recomputes the strike tally under the current rule
// (attended + will_attend are NOT strikes) across this batch's completed sessions, then either
// archives them (now over tolerance / firm refusal) or RESTORES a previously tolerance-archived
// registrant whose tally dropped back under the threshold. Restore is deliberately limited to this
// system's own "Absent N times" archives so it never reverses a manual/other removal.
const reevaluatePersonForBatch = async (
  centreId: number,
  batchId: number,
  mobile: string,
  now: Date,
): Promise<void> => {
  const assignments = await rawPrisma.sessionCallAssignment.findMany({
    where: {
      campaign: { batchId, status: SessionCampaignStatus.completed },
      batchMember: { mobile },
      removedAt: null,
    },
    select: { callStatus: true, outcomeLabel: true },
  });
  const strikeCount = assignments.filter((x) => !STRIKE_EXCLUDED.includes(x.callStatus)).length;
  const reconcile = makeReconciler(centreId);
  let anyFirmRefusal = false;
  for (const x of assignments) {
    if ((await reconcile(x.callStatus, x.outcomeLabel)).decision === "remove") anyFirmRefusal = true;
  }
  const shouldRemove = anyFirmRefusal || strikeCount >= ABSENCE_ARCHIVE_THRESHOLD;

  let normalized: string;
  try {
    normalized = normalizeMobile(mobile);
  } catch {
    return;
  }
  const reg = await rawPrisma.registration.findFirst({
    where: { centreId, normalizedMobile: normalized },
    select: { id: true, currentStage: true, category: true },
  });
  if (!reg) return;
  // `removed` is out too (a wrong number) — re-archiving it would overwrite its reason.
  const isArchived = reg.currentStage === RegistrationStage.archived || reg.currentStage === RegistrationStage.removed;

  if (shouldRemove && !isArchived) {
    await archiveRegistration(
      reg.id,
      anyFirmRefusal
        ? "Refused — won't attend (late response)"
        : `Absent ${strikeCount} times — tolerance exhausted (late response)`,
      now,
    );
  } else if (!shouldRemove && isArchived) {
    // Only reverse archives THIS SYSTEM made — tolerance ("Absent N times …") or the consecutive
    // unreachable rule ("Unreachable — …") — never a manual/other removal. The unreachable case
    // belongs here for the same reason as tolerance: it was an inference drawn from silence, and a
    // later response that breaks the run is direct evidence the inference was wrong.
    const removed = await rawPrisma.removedRecord.findUnique({ where: { registrationId: reg.id } });
    const isOwnArchive =
      /^Absent \d+ times/.test(removed?.reason ?? "") ||
      (removed?.reason ?? "").startsWith(UNREACHABLE_REASON_PREFIX);
    if (removed && isOwnArchive) {
      await rawPrisma.removedRecord.delete({ where: { registrationId: reg.id } });
      await rawPrisma.registration.update({
        where: { id: reg.id },
        data: { currentStage: RegistrationStage.registered, activeQueue: ActiveQueue.none },
      });
    }
  }
  try {
    await recomputeCentreCategory(rawPrisma, centreId, reg.category);
  } catch {
    /* counter refresh is non-critical */
  }
};

export const getSessionReconciliationSummary = async (campaignId: number, centreId: number) => {
  const campaign = await rawPrisma.sessionCampaign.findUnique({ where: { id: campaignId } });
  if (!campaign || campaign.centreId !== centreId) throw new ApiError(404, "Session campaign not found");

  const assignments = await rawPrisma.sessionCallAssignment.findMany({
    where: { campaignId, removedAt: null },
    include: { batchMember: { select: { mobile: true } } },
  });

  // Get the centre code for AttendEase lookup
  const centre = await rawPrisma.centre.findUnique({ where: { id: centreId }, select: { code: true } });
  const attendedMobiles = new Set<string>();
  if (centre?.code) {
    try {
      const rows = attendeaseRepo.allAttendance({ centreId: centre.code, sessionId: campaign.aeSessionId });
      for (const row of rows) {
        try { attendedMobiles.add(normalizeMobile(row.mobile)); } catch { /* skip */ }
      }
    } catch { /* AttendEase unreachable */ }
  }

  const total = assignments.length;
  const reconcile = makeReconciler(centreId);
  let came = 0, absent = 0, toFollowup = 0, toRemove = 0, skippedNoReg = 0;
  const byResponse: Record<string, number> = { will_attend: 0, wont_attend: 0, no_answer: 0, called: 0, pending: 0 };

  for (const a of assignments) {
    const norm = (() => { try { return normalizeMobile(a.batchMember.mobile); } catch { return null; } })();
    if (a.callStatus === SessionCallStatus.attended || (norm && attendedMobiles.has(norm))) {
      came++;
      continue;
    }
    absent++;
    byResponse[a.callStatus] = (byResponse[a.callStatus] ?? 0) + 1;

    // Projected action — mirrors completeSessionCampaign so the preview matches the real outcome.
    // Firm refusal → archive now; otherwise archive only once the 5-absence tolerance is exhausted.
    // (toRemove is the "will be archived/removed" bucket in this summary.)
    const { decision: action } = await reconcile(a.callStatus, a.outcomeLabel);
    if (action === "remove") { toRemove++; continue; }

    const reg = norm ? await rawPrisma.registration.findFirst({
      where: { centreId, normalizedMobile: norm },
      select: { id: true },
    }) : null;

    if (!reg) { skippedNoReg++; continue; }

    const priorAbsences = await rawPrisma.sessionCallAssignment.count({
      where: {
        campaign: { batchId: campaign.batchId, status: SessionCampaignStatus.completed },
        batchMember: { mobile: a.batchMember.mobile },
        // Must use the SAME exclusion list as completeSessionCampaign. This counted everything
        // except `attended`, so the preview projected archives for people the real reconciler
        // keeps — will_attend, deferred and call_back_later are not absences, and now neither is
        // an unanswered `pending`. The comment above already promised the preview mirrors the
        // real outcome; this makes it true.
        callStatus: { notIn: STRIKE_EXCLUDED },
        removedAt: null,
      },
    });
    if (priorAbsences + 1 >= ABSENCE_ARCHIVE_THRESHOLD) {
      toRemove++;
    } else {
      toFollowup++;
    }
  }

  // Walk-ins: people who ATTENDED this session but were never assigned to a caller for it.
  //
  // The loop above iterates over assignments, so anyone without an assignment row was simply
  // absent from this summary — not counted as present, and not counted at all. That is exactly
  // what happens to someone registered at the door: register_student adds them to the roster and
  // marks them present in AttendEase, but nothing creates a session_call_assignment for them,
  // because no caller was ever given their number. Reported live on 2026-08-01: "the newly
  // registered people are not taken in consideration as present" when completing the session.
  //
  // They are counted here rather than given an assignment row: session_call_assignments.caller_id
  // is NOT nullable, so a walk-in cannot be represented there without either inventing a caller
  // or migrating the schema. Counting them keeps the completion figures honest today; `walkIns`
  // is reported separately so "came" can still be read as assigned-and-came if needed.
  const assignedMobiles = new Set<string>();
  for (const a of assignments) {
    try { assignedMobiles.add(normalizeMobile(a.batchMember.mobile)); } catch { /* skip */ }
  }
  const walkIns = [...attendedMobiles].filter((m) => !assignedMobiles.has(m)).length;

  // Assignments with no response recorded. A session can be completed with these outstanding, so
  // the admin needs to see how many there are — they cost nobody a strike, but they do mean this
  // many callers' work is still un-collected and may yet arrive.
  const stillPending = assignments.filter((a) => a.callStatus === SessionCallStatus.pending).length;

  return {
    campaignId,
    // total/came INCLUDE walk-ins so the completion screen reports everyone who was actually
    // there, without needing the app to know about the new field.
    total: total + walkIns,
    came: came + walkIns,
    absent,
    toFollowup,
    toRemove,
    skippedNoReg,
    walkIns,
    stillPending,
    byResponse,
    status: campaign.status,
  };
};

export const completeSessionCampaign = async (campaignId: number, centreId: number, now: Date) => {
  const campaign = await rawPrisma.sessionCampaign.findUnique({ where: { id: campaignId } });
  if (!campaign || campaign.centreId !== centreId) throw new ApiError(404, "Session campaign not found");
  if (campaign.status === SessionCampaignStatus.completed) {
    // Idempotent — return summary
    return getSessionReconciliationSummary(campaignId, centreId);
  }
  if (campaign.status !== SessionCampaignStatus.closed) {
    throw new ApiError(409, "Campaign must be closed (retrieved) before it can be completed");
  }

  const assignments = await rawPrisma.sessionCallAssignment.findMany({
    where: { campaignId, removedAt: null },
    include: { batchMember: { select: { mobile: true } } },
  });

  // Outstanding responses NO LONGER block completion.
  //
  // This used to throw 409 until every assigned caller had submitted, on the reasoning that
  // completing early would reconcile those people as absent and lose the caller's offline work.
  // Neither still holds: `pending` is excluded from the strike tally (see STRIKE_EXCLUDED), so an
  // un-answered assignment costs the person nothing, and a late response is accepted after
  // completion and re-runs the keep/remove decision. Nothing is lost by finishing the session.
  //
  // What the block did cost was real: the class is over, the admin wants to close the books, and
  // one caller who never opened the app — or who is simply offline — held the whole session open
  // indefinitely. The count is still reported in the summary so the admin can see exactly how
  // many are outstanding and chase them if they want to.
  const reconcile = makeReconciler(centreId);
  const pendingCount = assignments.filter((a) => a.callStatus === SessionCallStatus.pending).length;
  if (pendingCount > 0) {
    console.log(
      `[sessionCampaign] completing campaign ${campaignId} with ${pendingCount} response(s) ` +
      `still outstanding — excluded from the strike tally; they reconcile if they sync later`,
    );
  }

  const centre = await rawPrisma.centre.findUnique({ where: { id: centreId }, select: { code: true } });
  const attendedMobiles = new Set<string>();
  if (centre?.code) {
    try {
      const rows = attendeaseRepo.allAttendance({ centreId: centre.code, sessionId: campaign.aeSessionId });
      for (const row of rows) {
        try { attendedMobiles.add(normalizeMobile(row.mobile)); } catch { /* skip */ }
      }
    } catch { /* AttendEase unreachable */ }
  }

  // Batch category (for the absence-eviction rule below) — this session's batch.
  const batchRow = await rawPrisma.batch.findUnique({ where: { id: campaign.batchId }, select: { category: true } });

  const affectedCategories = new Set<Category>();

  for (const a of assignments) {
    const norm = (() => { try { return normalizeMobile(a.batchMember.mobile); } catch { return null; } })();
    // Skip attendees
    if (a.callStatus === SessionCallStatus.attended || (norm && attendedMobiles.has(norm))) {
      if (norm && attendedMobiles.has(norm) && a.callStatus !== SessionCallStatus.attended) {
        await rawPrisma.sessionCallAssignment.update({ where: { id: a.id }, data: { callStatus: SessionCallStatus.attended } });
      }
      // Safety net: close any follow-up left open for a confirmed attendee. Normally
      // markSessionAttendance already closes it in real time; this covers attendance that
      // synced in after the fact (e.g. between close and complete).
      if (norm) {
        const attendee = await rawPrisma.registration.findFirst({
          where: { centreId, normalizedMobile: norm },
          select: { id: true, category: true },
        });
        if (attendee) {
          const closed = await rawPrisma.followup.updateMany({
            where: { registrationId: attendee.id, status: FollowupStatus.active },
            data: { status: FollowupStatus.closed, isLocked: false, lockedReason: null },
          });
          if (closed.count > 0) affectedCategories.add(attendee.category);
          // Closing the row is not enough — see releaseFromClosedFollowups.
          for (const c of await releaseFromClosedFollowups([attendee.id])) affectedCategories.add(c);
        }
      }
      continue;
    }

    if (!norm) continue;
    const reg = await rawPrisma.registration.findFirst({
      where: { centreId, normalizedMobile: norm },
      select: { id: true, category: true, currentStage: true, currentAssignedCallerId: true, originalCallerId: true, firstConnectedBy: true },
    });
    if (!reg) continue;

    const reconciled = await reconcile(a.callStatus, a.outcomeLabel);
    const action = reconciled.decision;
    const alreadyOut = reg.currentStage === RegistrationStage.archived || reg.currentStage === RegistrationStage.removed;
    affectedCategories.add(reg.category);

    // Absence (strike) tally across this batch's completed calling sessions, +1 for THIS session if
    // it's a strike. Strikes are non-contact / soft "not this time" — i.e. everything EXCEPT actual
    // attendance AND a positive "will attend". A "will attend" now SPARES the strike (the caller
    // showed intent to come), so it no longer counts toward auto-removal — only genuine
    // absence/no-contact does. Actual attendance still excludes/resets as before.
    const priorAbsences = await rawPrisma.sessionCallAssignment.count({
      where: {
        campaign: { batchId: campaign.batchId, status: SessionCampaignStatus.completed },
        batchMember: { mobile: a.batchMember.mobile },
        callStatus: { notIn: STRIKE_EXCLUDED },
        removedAt: null,
      },
    });
    const thisIsStrike = !STRIKE_EXCLUDED.includes(a.callStatus);
    const absenceCount = priorAbsences + (thisIsStrike ? 1 : 0);
    const firmRefusal = action === "remove";
    const toleranceExhausted = absenceCount >= ABSENCE_ARCHIVE_THRESHOLD;

    if (firmRefusal) {
      // Normally already done the moment the caller saved it (applyDeclaredRemoval). This catches
      // answers saved by an older server, and legacy labels. Already out = leave the reason alone.
      if (!alreadyOut) {
        await applyDeclaredRemoval({
          centreId,
          action: reconciled.action,
          label: a.outcomeLabel ?? "Refused",
          mobile: a.batchMember.mobile,
          batchMemberId: a.batchMemberId,
          registrationId: reg.id,
          campaignId,
          where: null,
          now,
        });
      }
    } else if (alreadyOut) {
      // Archived or removed for another reason (a refusal in a different session, the unreachable
      // rule, an admin). Completing THIS session is not evidence they are back, so they stay out.
      // This used to reset them to `registered`, which quietly undid those removals.
    } else if (toleranceExhausted) {
      // Auto-archive once the 5-absence grace is exhausted.
      await archiveRegistration(reg.id, `Absent ${absenceCount} times — tolerance exhausted`, now);
    } else {
      // Still within tolerance. Completion ends this session's lifecycle, so the person returns to
      // the general registered pool to be re-engaged by a future calling session. (The final sweep
      // below closes any follow-up still open for them.)
      await rawPrisma.registration.update({
        where: { id: reg.id },
        data: { currentStage: RegistrationStage.registered, activeQueue: ActiveQueue.none },
      });

      // Soft, recoverable batch-eviction once they've missed BATCH_ABSENCE_EVICTION_THRESHOLD
      // classes: they leave the non-default batch but stay a registrant (still callable).
      if (batchRow && absenceCount >= BATCH_ABSENCE_EVICTION_THRESHOLD) {
        await evictFromBatchForAbsence(centreId, batchRow.category, a.batchMember.mobile, campaign.batchId);
      }
    }
  }

  // Follow-ups belong to a calling session and DISAPPEAR when it is completed. Close every active
  // follow-up tied to this session's people in one sweep (covers attendees, no-shows, and anyone
  // whose positive response created a follow-up during the active phase).
  {
    // Attendees are included alongside the assigned people: someone can attend without ever
    // having been assigned for this session (a door registration, or a person who came on their
    // own). If such a person was carrying an open follow-up from an earlier session, sweeping
    // only `assignments` would leave it open forever, since the session it belonged to is now
    // finished and will never be completed again.
    const mobiles = Array.from(new Set([
      ...assignments
        .map((a) => { try { return normalizeMobile(a.batchMember.mobile); } catch { return null; } })
        .filter((m): m is string => !!m),
      ...attendedMobiles,
    ]));
    if (mobiles.length) {
      const regs = await rawPrisma.registration.findMany({
        where: { centreId, normalizedMobile: { in: mobiles } },
        select: { id: true, category: true },
      });
      const closed = await rawPrisma.followup.updateMany({
        where: { registrationId: { in: regs.map((r) => r.id) }, status: FollowupStatus.active },
        data: { status: FollowupStatus.closed, isLocked: false, lockedReason: null },
      });
      if (closed.count > 0) regs.forEach((r) => affectedCategories.add(r.category));
      // Non-attendees were already released above; this is what releases the ATTENDEES.
      for (const c of await releaseFromClosedFollowups(regs.map((r) => r.id))) affectedCategories.add(c);
    }
  }

  // Mark campaign completed
  await rawPrisma.sessionCampaign.update({
    where: { id: campaignId },
    data: { status: SessionCampaignStatus.completed },
  });

  // Refresh the read-model counters for every affected category (best-effort).
  for (const category of affectedCategories) {
    try { await recomputeCentreCategory(rawPrisma, centreId, category); } catch { /* non-critical */ }
  }

  return getSessionReconciliationSummary(campaignId, centreId);
};

// ─── Session attendance mark ─────────────────────────────────────────────────

export const markSessionAttendance = async ({
  aeSessionId,
  mobile,
  centreId,
}: {
  aeSessionId: string;
  mobile: string;
  centreId: number;
}) => {
  const campaign = await rawPrisma.sessionCampaign.findUnique({ where: { aeSessionId } });
  if (!campaign || campaign.centreId !== centreId) return; // no campaign for this session

  const batchMember = await rawPrisma.batchMember.findFirst({
    where: { mobile, centreId, removedAt: null },
  });
  if (!batchMember) return;

  const assignment = await rawPrisma.sessionCallAssignment.findUnique({
    where: { campaignId_batchMemberId: { campaignId: campaign.id, batchMemberId: batchMember.id } },
  });
  if (!assignment) return;

  await rawPrisma.sessionCallAssignment.update({
    where: { id: assignment.id },
    data: { callStatus: SessionCallStatus.attended },
  });

  // Attendance confirmed → the follow-up's whole purpose ("get them to attend") is fulfilled.
  // Close it outright (not merely unlock it) so it drops off the caller's list. Retrieval/close
  // alone must never do this — only actual confirmed attendance does.
  try {
    const normalized = normalizeMobile(batchMember.mobile);
    const registration = await rawPrisma.registration.findFirst({
      where: { centreId, normalizedMobile: normalized },
      select: { id: true, category: true },
    });
    if (registration) {
      const closed = await rawPrisma.followup.updateMany({
        where: { registrationId: registration.id, status: FollowupStatus.active },
        data: { status: FollowupStatus.closed, isLocked: false, lockedReason: null },
      });
      // They came, so they are back in the general pool for the NEXT class. Without this the
      // registration stayed followup_active forever and vanished from the Pool page.
      await releaseFromClosedFollowups([registration.id]);
      if (closed.count > 0) {
        try { await recomputeCentreCategory(rawPrisma, centreId, registration.category); } catch { /* non-critical */ }
      }
    }
  } catch { /* mobile unparseable / lookup failed — attendance mark above already succeeded */ }
};