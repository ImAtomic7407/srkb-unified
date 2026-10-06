// A response that DECLARES removal ("Not interested", "Wrong number") takes the person out the
// moment it is saved.
//
// ── Why this file exists ───────────────────────────────────────────────────────────────────────
//
// Every response carries an admin-declared action (dropdown_mappings.action). Two of them say "this
// person is done": remove_from_running (Not interested / Won't ever come) and mark_invalid (Wrong
// number). Until 2026-09-27 nothing acted on that declaration when a caller saved it:
//
//   * session calls only recorded `wont_attend`; removal waited for an admin to COMPLETE the
//     session, and even then was decided by matching the label against a keyword pattern that
//     "Wrong number" did not match — so wrong numbers were never removed at all;
//   * when a refusal was archived, the person's batch_members row stayed live, and the Pool and the
//     next session's allotment kept handing them straight back to a caller;
//   * registration and follow-up calls set the stage to `removed` but also left the batch row.
//
// Live data on the day: 30 archived refusers and 20 wrong numbers still sitting in Centre A
// batches, and people answering "Not interested" in one session being rung again two weeks later.
//
// ── What "removed" means here ──────────────────────────────────────────────────────────────────
//
// One place, used by all three save paths, so a removal is the same thing wherever it happens:
//   1. every live batch membership for the mobile is soft-removed (audited, never deleted);
//   2. any call still WAITING for them in another active session is withdrawn from that caller;
//   3. an open follow-up is closed;
//   4. the registration is archived (refusal) or removed (wrong number) with a RemovedRecord whose
//      removedAt is the date the Archive page groups by.
//
// ── Undo ───────────────────────────────────────────────────────────────────────────────────────
//
// A caller can scroll back and correct an answer. If the SAME session call is re-answered with a
// response that does not remove, exactly what this file did for that call is put back — and
// nothing else. The undo is keyed on the audit rows written here (by_user = DECLARED_ACTOR and the
// assignment id), so it can never reverse a removal made by an admin, the absence tolerance or the
// unreachable rule.

import { ActiveQueue, Category, DropdownAction, FollowupStatus, RegistrationStage, SessionCallStatus, SessionCampaignStatus } from "@prisma/client";
import { rawPrisma } from "../config/prisma.js";
import { normalizeMobile } from "../utils/mobile.js";
import { recomputeCentreCategory } from "./projections.js";
import { logger } from "./logger.js";

export const DECLARED_ACTOR = "system:declared_response";
const AUDIT_REASON = "declared_response";

/** The two declared meanings that take a person out. `archive` is deliberately NOT here: the
 *  follow-up list uses it for "close from my attendance", which is not a refusal. */
export const isRemovalAction = (action: DropdownAction | null | undefined): action is DropdownAction =>
  action === DropdownAction.remove_from_running || action === DropdownAction.mark_invalid;

const mobileVariants = (mobile: string): string[] => {
  const out = new Set<string>([mobile]);
  try {
    out.add(normalizeMobile(mobile));
  } catch {
    /* unparseable — the raw string is all there is */
  }
  const digits = mobile.replace(/\D/g, "");
  if (digits) out.add(digits);
  return [...out];
};

type AuditState = {
  batchId: number;
  active: boolean;
  registrationId?: number | null;
  assignmentId?: number | null;
  campaignId?: number | null;
  withdrawnAssignmentIds?: number[];
};

export const removedReasonFor = (label: string, where: string | null): string =>
  where ? `Said "${label}" (${where})` : `Said "${label}"`;

/**
 * Take a person out on a declared removal. Safe to call more than once: memberships already
 * removed are skipped, and the registration update is idempotent.
 *
 * `batchMemberId` is the membership the call was made against. It is stripped even when no
 * registration can be matched to the mobile — a roster spelling that does not normalise the same
 * way as the registration must not be the reason a refuser stays in the batch.
 */
export const applyDeclaredRemoval = async ({
  centreId,
  action,
  label,
  mobile,
  batchMemberId,
  registrationId,
  assignmentId,
  campaignId,
  where,
  now,
}: {
  centreId: number;
  action: DropdownAction;
  label: string;
  mobile: string;
  batchMemberId?: number | null;
  registrationId: number | null;
  assignmentId?: number | null;
  campaignId?: number | null;
  /** Human context for the Archive page, e.g. the session name. */
  where?: string | null;
  now: Date;
}): Promise<{ membershipsRemoved: number; callsWithdrawn: number }> => {
  const variants = mobileVariants(mobile);

  const memberships = await rawPrisma.batchMember.findMany({
    where: {
      centreId,
      removedAt: null,
      OR: [{ mobile: { in: variants } }, ...(batchMemberId ? [{ id: batchMemberId }] : [])],
    },
    select: { id: true, batchId: true },
  });
  // Every membership of this person, live or not, so a call waiting under an older roster row is
  // found too.
  const allMemberIds = (
    await rawPrisma.batchMember.findMany({
      where: { centreId, OR: [{ mobile: { in: variants } }, ...(batchMemberId ? [{ id: batchMemberId }] : [])] },
      select: { id: true },
    })
  ).map((m) => m.id);

  // Calls still WAITING for them in other active sessions. An answered call is history and stays.
  const waiting = await rawPrisma.sessionCallAssignment.findMany({
    where: {
      batchMemberId: { in: allMemberIds },
      removedAt: null,
      callStatus: SessionCallStatus.pending,
      campaign: { status: SessionCampaignStatus.active },
      ...(assignmentId ? { NOT: { id: assignmentId } } : {}),
    },
    select: { id: true },
  });
  const withdrawnAssignmentIds = waiting.map((w) => w.id);
  if (withdrawnAssignmentIds.length) {
    await rawPrisma.sessionCallAssignment.updateMany({
      where: { id: { in: withdrawnAssignmentIds } },
      data: { removedAt: now },
    });
  }

  let first = true;
  for (const m of memberships) {
    await rawPrisma.batchMember.update({
      where: { id: m.id },
      data: { removedAt: now, changedBy: DECLARED_ACTOR, changedAt: now },
    });
    const state: AuditState = {
      batchId: m.batchId,
      active: false,
      registrationId,
      assignmentId: assignmentId ?? null,
      campaignId: campaignId ?? null,
      // Carried once, on the first row, so the undo can hand the withdrawn calls back.
      withdrawnAssignmentIds: first ? withdrawnAssignmentIds : [],
    };
    first = false;
    await rawPrisma.batchMemberAudit.create({
      data: {
        batchMemberId: m.id,
        action: "remove",
        byUser: DECLARED_ACTOR,
        atTime: now,
        reason: AUDIT_REASON,
        prevState: JSON.stringify({ batchId: m.batchId, active: true }),
        newState: JSON.stringify(state),
      },
    });
  }

  if (registrationId) {
    const reg = await rawPrisma.registration.findUnique({
      where: { id: registrationId },
      select: { id: true, category: true },
    });
    if (reg) {
      await rawPrisma.followup.updateMany({
        where: { registrationId, status: FollowupStatus.active },
        data: { status: FollowupStatus.closed, isLocked: false, lockedReason: null },
      });
      const reason = removedReasonFor(label, where ?? null);
      // Re-saving the same removal (a caller editing the remark) must not move the date: the undo
      // below matches the record to its audit rows by that exact moment.
      const existing = await rawPrisma.removedRecord.findUnique({ where: { registrationId } });
      const keepDate = existing && existing.reason.startsWith('Said "') && memberships.length === 0;
      await rawPrisma.removedRecord.upsert({
        where: { registrationId },
        update: { reason, removedAt: keepDate ? existing.removedAt : now },
        create: { registrationId, reason, removedAt: now },
      });
      const invalid = action === DropdownAction.mark_invalid;
      await rawPrisma.registration.update({
        where: { id: registrationId },
        data: invalid
          ? { currentStage: RegistrationStage.removed, activeQueue: ActiveQueue.removed }
          : { currentStage: RegistrationStage.archived, activeQueue: ActiveQueue.archived },
      });
      try {
        await recomputeCentreCategory(rawPrisma, centreId, reg.category);
      } catch {
        /* counter refresh is non-critical */
      }
    }
  }

  logger.info(
    `declared-removal centre=${centreId} mobile=${mobile} action=${action} label="${label}" ` +
      `reg=${registrationId ?? "-"} memberships=${memberships.length} withdrawn=${withdrawnAssignmentIds.length}`,
  );
  return { membershipsRemoved: memberships.length, callsWithdrawn: withdrawnAssignmentIds.length };
};

/**
 * Put back what applyDeclaredRemoval did for THIS session call, if anything. Called when the same
 * call is re-answered with a response that does not remove. Returns true if something was undone.
 */
export const revertDeclaredRemoval = async ({
  centreId,
  mobile,
  batchMemberId,
  registrationId,
  assignmentId,
  now,
}: {
  centreId: number;
  mobile: string;
  batchMemberId: number;
  registrationId: number | null;
  assignmentId: number;
  now: Date;
}): Promise<boolean> => {
  const variants = mobileVariants(mobile);
  const members = await rawPrisma.batchMember.findMany({
    where: { centreId, OR: [{ mobile: { in: variants } }, { id: batchMemberId }] },
    select: { id: true, removedAt: true, changedBy: true },
  });
  const audits = await rawPrisma.batchMemberAudit.findMany({
    where: { batchMemberId: { in: members.map((m) => m.id) }, byUser: DECLARED_ACTOR, action: "remove" },
    orderBy: { atTime: "desc" },
  });
  const mine = audits.filter((a) => {
    try {
      return (JSON.parse(a.newState ?? "{}") as AuditState).assignmentId === assignmentId;
    } catch {
      return false;
    }
  });
  if (mine.length === 0) return false;

  // Only the newest removal made for this call. Rows since re-removed by anything else are left.
  const at = mine[0]!.atTime.getTime();
  const batch = mine.filter((a) => a.atTime.getTime() === at);
  const memberById = new Map(members.map((m) => [m.id, m]));
  let restored = 0;
  const withdrawn: number[] = [];
  for (const a of batch) {
    const state = JSON.parse(a.newState ?? "{}") as AuditState;
    withdrawn.push(...(state.withdrawnAssignmentIds ?? []));
    const m = memberById.get(a.batchMemberId);
    if (!m || m.removedAt === null || m.changedBy !== DECLARED_ACTOR || m.removedAt.getTime() !== at) continue;
    await rawPrisma.batchMember.update({
      where: { id: m.id },
      data: { removedAt: null, changedBy: DECLARED_ACTOR, changedAt: now },
    });
    await rawPrisma.batchMemberAudit.create({
      data: {
        batchMemberId: m.id,
        action: "re_add",
        byUser: DECLARED_ACTOR,
        atTime: now,
        reason: "declared_response_corrected",
        prevState: JSON.stringify({ batchId: state.batchId, active: false }),
        newState: JSON.stringify({ batchId: state.batchId, active: true, assignmentId }),
      },
    });
    restored += 1;
  }
  if (withdrawn.length) {
    await rawPrisma.sessionCallAssignment.updateMany({
      where: { id: { in: withdrawn }, removedAt: new Date(at), campaign: { status: SessionCampaignStatus.active } },
      data: { removedAt: null },
    });
  }

  if (registrationId) {
    const removed = await rawPrisma.removedRecord.findUnique({ where: { registrationId } });
    // Only a record this file wrote, at this same moment. Anything newer belongs to someone else.
    if (removed && removed.reason.startsWith('Said "') && removed.removedAt.getTime() === at) {
      await rawPrisma.removedRecord.delete({ where: { registrationId } });
      const reg = await rawPrisma.registration.update({
        where: { id: registrationId },
        data: { currentStage: RegistrationStage.registered, activeQueue: ActiveQueue.none },
        select: { category: true },
      });
      try {
        await recomputeCentreCategory(rawPrisma, centreId, reg.category);
      } catch {
        /* non-critical */
      }
    }
  }
  logger.info(`declared-removal undone centre=${centreId} mobile=${mobile} assignment=${assignmentId} memberships=${restored}`);
  return true;
};

/**
 * People in this centre who are archived or removed and have NOT attended since. Keyed by
 * normalized mobile, value = the removal date. Used to keep them out of the Pool and to refuse
 * allotting them.
 *
 * Turning up is a stronger signal than any phone call (the adit Raj guard in
 * unreachable-threshold.ts), so someone who has attended since being removed is not held out.
 * `lastAttended` returns YYYY-MM-DD or null for a normalized mobile.
 */
/**
 * An attendance row that is really a CALL LOG. The caller app's "Mark All as Contacted" button
 * posts every student in the batch through mark_attendance, which writes a real attendance row
 * ("Spoke with student. Note: Bulk batch call log."). On 2026-09-25 one tap wrote 707 of them for
 * Centre A. Counting those as "turned up" would bring back every refuser in that batch, so the
 * removal checks skip them.
 */
export const isCallLogAttendance = (remark: string | null | undefined): boolean =>
  (remark ?? "").startsWith("Spoke with student. Note: Bulk batch call log");

const localDay = (d: Date): string =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

export const heldOutPeople = async (
  centreId: number,
  normalizedMobiles: string[],
  lastAttended: (normalized: string) => string | null,
  category?: Category,
): Promise<Map<string, { since: Date; reason: string }>> => {
  const out = new Map<string, { since: Date; reason: string }>();
  if (normalizedMobiles.length === 0) return out;
  const regs = await rawPrisma.registration.findMany({
    where: {
      centreId,
      ...(category ? { category } : {}),
      normalizedMobile: { in: normalizedMobiles },
      OR: [
        { currentStage: { in: [RegistrationStage.archived, RegistrationStage.removed] } },
        { activeQueue: { in: [ActiveQueue.archived, ActiveQueue.removed] } },
      ],
    },
    select: { normalizedMobile: true, createdAt: true, removedRecord: { select: { removedAt: true, reason: true } } },
  });
  for (const r of regs) {
    const since = r.removedRecord?.removedAt ?? r.createdAt;
    const last = lastAttended(r.normalizedMobile);
    // AttendEase dates are local calendar days, so compare against the local day of removal.
    if (last && last >= localDay(since)) continue;
    out.set(r.normalizedMobile, { since, reason: r.removedRecord?.reason ?? "Removed" });
  }
  return out;
};
