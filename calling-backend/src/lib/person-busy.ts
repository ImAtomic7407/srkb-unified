import {
  AssignmentStatus,
  BatchCallingAssignmentStatus,
  FollowupStatus,
  SessionCallStatus,
  SessionCampaignStatus,
} from "@prisma/client";
import { rawPrisma } from "../config/prisma.js";
import { normalizeMobile } from "../utils/mobile.js";

/**
 * "One number can be in only one place at a time." Where a person is held right now, if anywhere,
 * so the Pool page can show it and a session assignment can refuse to pull them in twice.
 *
 * Read off the records that HOLD a person, never off registrations.currentStage: that field is a
 * summary, and on 2026-09-24 it was wrong for 25 people (followup_active with the follow-up long
 * closed). A place is:
 *   followup       an active follow-up row (someone is ringing them before a class)
 *   session_call   a live assignment in ANOTHER active session that is still waiting for its call
 *                  (callStatus pending — the same "left" getSessionCallSummary counts). Not every
 *                  live row: a session nobody completed (2026-08-30 CTRA BG kept 22 rows, 1 pending)
 *                  would otherwise lock its people out of every later class.
 *   pool_call      an active new-registration calling assignment
 *   batch_call     an active batch-calling assignment
 *
 * `exceptCampaignId` is the session being assigned: being in THAT session is not "elsewhere", so a
 * hand-over between callers of the same session stays allowed. A follow-up created BY that session
 * counts as the same place for the same reason.
 */
export type BusyKind = "followup" | "session_call" | "pool_call" | "batch_call";
export type Busy = { kind: BusyKind; label: string; caller: string | null };

const tryNormalize = (m: string): string | null => {
  try {
    return normalizeMobile(m);
  } catch {
    return null;
  }
};

export const busyByMobile = async (
  centreId: number,
  mobiles: string[],
  exceptCampaignId?: number,
): Promise<Map<string, Busy>> => {
  const out = new Map<string, Busy>();
  const wanted = new Set(mobiles.map(tryNormalize).filter((m): m is string => !!m));
  if (wanted.size === 0) return out;

  // First place found wins; the order is the order a person would naturally leave them in.
  const put = (mobile: string | null, busy: Busy) => {
    if (mobile && wanted.has(mobile) && !out.has(mobile)) out.set(mobile, busy);
  };

  const sessionNames = new Map<string, string>();
  const sessionName = async (aeSessionId: string) => {
    if (!sessionNames.has(aeSessionId)) {
      const s = await rawPrisma.session.findFirst({ where: { sessionId: aeSessionId }, select: { displayName: true } });
      sessionNames.set(aeSessionId, s?.displayName || aeSessionId);
    }
    return sessionNames.get(aeSessionId)!;
  };

  const followups = await rawPrisma.followup.findMany({
    where: {
      centreId,
      status: FollowupStatus.active,
      registration: { normalizedMobile: { in: [...wanted] } },
      ...(exceptCampaignId ? { NOT: { campaignId: exceptCampaignId } } : {}),
    },
    select: {
      registration: { select: { normalizedMobile: true } },
      ownerCaller: { select: { name: true } },
      campaign: { select: { aeSessionId: true } },
    },
  });
  for (const f of followups) {
    const where = f.campaign ? ` (${await sessionName(f.campaign.aeSessionId)})` : "";
    put(f.registration.normalizedMobile, { kind: "followup", label: `In follow-up${where}`, caller: f.ownerCaller?.name ?? null });
  }

  // BatchMember.mobile is a raw string, so candidates are narrowed in SQL by centre and matched
  // after normalising — the same key-space care as listPeopleByFilter.
  const sessionCalls = await rawPrisma.sessionCallAssignment.findMany({
    where: {
      removedAt: null,
      callStatus: SessionCallStatus.pending,
      campaign: { centreId, status: SessionCampaignStatus.active },
      ...(exceptCampaignId ? { NOT: { campaignId: exceptCampaignId } } : {}),
    },
    select: {
      batchMember: { select: { mobile: true } },
      caller: { select: { name: true } },
      campaign: { select: { aeSessionId: true } },
    },
  });
  for (const a of sessionCalls) {
    const m = tryNormalize(a.batchMember.mobile);
    if (!m || !wanted.has(m) || out.has(m)) continue;
    put(m, { kind: "session_call", label: `In calling: ${await sessionName(a.campaign.aeSessionId)}`, caller: a.caller?.name ?? null });
  }

  const poolCalls = await rawPrisma.callingAssignment.findMany({
    where: { centreId, status: AssignmentStatus.active, registration: { normalizedMobile: { in: [...wanted] } } },
    select: { registration: { select: { normalizedMobile: true } }, assignedToUser: { select: { name: true } } },
  });
  for (const a of poolCalls) {
    put(a.registration.normalizedMobile, { kind: "pool_call", label: "In calling (new registrations)", caller: a.assignedToUser?.name ?? null });
  }

  const batchCalls = await rawPrisma.batchCallingAssignment.findMany({
    where: { centreId, status: BatchCallingAssignmentStatus.active },
    select: { batchMember: { select: { mobile: true } }, assignedToUser: { select: { name: true } } },
  });
  for (const a of batchCalls) {
    put(tryNormalize(a.batchMember.mobile), { kind: "batch_call", label: "In batch calling", caller: a.assignedToUser?.name ?? null });
  }

  return out;
};

export const busyToJson = (b: Busy | undefined) =>
  b ? { kind: b.kind, label: b.label, caller: b.caller } : null;
