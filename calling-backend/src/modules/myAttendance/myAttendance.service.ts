// "My Attendance" is now a READ-ONLY scoreboard, not a stage in the funnel.
// Students are never "graduated" out of batch calling - they stay in the loop
// permanently. This module exposes two independent views for a caller:
//
//   View 1 "By Calling"        - session conversion scoreboard. Pick a batch, then a
//                                session; see the people who attended that session after
//                                being called by this caller. Source: SessionCallAssignment
//                                where callerId = me and callStatus = attended.
//   View 2 "By Proprietorship" - the caller's permanent roster: every active student whose
//                                relationshipOwnerId = me, regardless of session. Source:
//                                Registration.relationshipOwnerId.
import {
  Category,
  RegistrationStage,
  SessionCallStatus,
  UserRole,
} from "@prisma/client";
import type { Request } from "express";
import { prisma } from "../../config/prisma.js";
import { ApiError } from "../../lib/api-error.js";
import { normalizeMobile } from "../../utils/mobile.js";

type RequestUser = NonNullable<Request["user"]>;

const requireCentreId = (centreId: number | null | undefined) => {
  if (!centreId) {
    throw new ApiError(400, "Centre is not configured for this user");
  }

  return centreId;
};

const resolveMyAttendanceScopeCentreId = (requester: RequestUser) => {
  if (requester.role === UserRole.SUPER_ADMIN) {
    return requester.centreId ?? null;
  }

  return requireCentreId(requester.centreId);
};

const matchesSearch = (value: string | null, search?: string) => {
  if (!search) {
    return true;
  }

  if (!value) {
    return false;
  }

  return value.toLowerCase().includes(search.toLowerCase());
};

// View 2: "By Proprietorship" - the caller's permanent roster

export const getProprietorshipRoster = async ({
  userId,
  category,
  search,
  requester,
}: {
  userId: number;
  category: Category;
  search?: string;
  requester: RequestUser;
}) => {
  const scopeCentreId = resolveMyAttendanceScopeCentreId(requester);

  const registrations = await prisma.registration.findMany({
    where: {
      relationshipOwnerId: userId,
      category,
      ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
      NOT: [
        { currentStage: RegistrationStage.removed },
        { currentStage: RegistrationStage.archived },
      ],
    },
    include: {
      callAttempts: {
        orderBy: { createdAt: "desc" },
        take: 2,
        include: { caller: true },
      },
      originalCaller: true,
      relationshipOwner: true,
      currentAssignedCaller: true,
    },
    orderBy: { createdAt: "desc" },
  });

  return registrations
    .filter(
      (reg) =>
        matchesSearch(reg.name, search) ||
        matchesSearch(reg.mobile, search) ||
        matchesSearch(reg.address ?? null, search) ||
        matchesSearch(reg.occupation ?? null, search),
    )
    .map((reg) => ({
      registration_id: reg.id,
      name: reg.name,
      mobile: reg.mobile,
      address: reg.address,
      occupation: reg.occupation,
      category: reg.category,
      current_stage: reg.currentStage,
      active_queue: reg.activeQueue,
      original_caller: reg.originalCaller,
      relationship_owner: reg.relationshipOwner,
      current_assigned_caller: reg.currentAssignedCaller,
      last_2_call_attempts: reg.callAttempts,
    }));
};

// View 1: "By Calling" - session conversion scoreboard

// Step 1: the batches in which this caller made ANY calls (not just conversions), with a
// called-vs-came scoreboard per batch. "called" = assignments this caller actually acted on
// (status left pending), "came" = those who then attended.
export const getCallingBatches = async ({
  userId,
  category,
  requester,
}: {
  userId: number;
  category: Category;
  requester: RequestUser;
}) => {
  const scopeCentreId = resolveMyAttendanceScopeCentreId(requester);

  const rows = await prisma.sessionCallAssignment.findMany({
    where: {
      callerId: userId,
      removedAt: null,
      campaign: {
        ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
        batch: { category },
      },
    },
    include: {
      campaign: { include: { batch: { select: { id: true, name: true } } } },
    },
  });

  const byBatch = new Map<number, { batch_id: number; batch_name: string; called_count: number; attended_count: number }>();
  for (const row of rows) {
    const batch = row.campaign.batch;
    const entry = byBatch.get(batch.id) ?? { batch_id: batch.id, batch_name: batch.name, called_count: 0, attended_count: 0 };
    if (row.callStatus !== SessionCallStatus.pending) entry.called_count += 1;
    if (row.callStatus === SessionCallStatus.attended) entry.attended_count += 1;
    byBatch.set(batch.id, entry);
  }

  return Array.from(byBatch.values())
    .filter((b) => b.called_count > 0)
    .sort((a, b) => a.batch_name.localeCompare(b.batch_name));
};

// Step 2: the sessions (campaigns) within a batch where this caller converted attendees.
export const getCallingSessions = async ({
  userId,
  batchId,
  requester,
}: {
  userId: number;
  batchId: number;
  requester: RequestUser;
}) => {
  const scopeCentreId = resolveMyAttendanceScopeCentreId(requester);

  const rows = await prisma.sessionCallAssignment.findMany({
    where: {
      callerId: userId,
      removedAt: null,
      campaign: {
        batchId,
        ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
      },
    },
    include: {
      campaign: { include: { batch: { select: { id: true, name: true } } } },
    },
  });

  const bySession = new Map<
    number,
    { campaign_id: number; ae_session_id: string; scheduled_at: Date; batch_id: number; batch_name: string; called_count: number; attended_count: number }
  >();
  for (const row of rows) {
    const c = row.campaign;
    const entry =
      bySession.get(c.id) ?? {
        campaign_id: c.id,
        ae_session_id: c.aeSessionId,
        scheduled_at: c.scheduledAt,
        batch_id: c.batch.id,
        batch_name: c.batch.name,
        called_count: 0,
        attended_count: 0,
      };
    if (row.callStatus !== SessionCallStatus.pending) entry.called_count += 1;
    if (row.callStatus === SessionCallStatus.attended) entry.attended_count += 1;
    bySession.set(c.id, entry);
  }

  return Array.from(bySession.values())
    .filter((s) => s.called_count > 0)
    .sort((a, b) => b.scheduled_at.getTime() - a.scheduled_at.getTime());
};

// Step 3: everyone this caller CALLED in a specific session, each flagged with whether they
// came (attended) — so the detail list shows the full called-vs-came picture, not just converts.
export const getCallingAttended = async ({
  userId,
  campaignId,
  requester,
}: {
  userId: number;
  campaignId: number;
  requester: RequestUser;
}) => {
  const scopeCentreId = resolveMyAttendanceScopeCentreId(requester);

  const rows = await prisma.sessionCallAssignment.findMany({
    where: {
      callerId: userId,
      campaignId,
      callStatus: { not: SessionCallStatus.pending }, // everyone actually called
      removedAt: null,
      campaign: {
        ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
      },
    },
    include: {
      batchMember: { select: { id: true, mobile: true, centreId: true } },
      campaign: { select: { aeSessionId: true, scheduledAt: true } },
    },
    orderBy: { calledAt: "desc" },
  });

  if (rows.length === 0) {
    return [];
  }

  // BatchMember carries only the mobile; join Registration to surface name/details.
  const centreId = rows[0]!.batchMember.centreId;
  const normalized = Array.from(new Set(rows.map((r) => normalizeMobile(r.batchMember.mobile))));
  const regs = await prisma.registration.findMany({
    where: { normalizedMobile: { in: normalized }, centreId },
    select: {
      id: true,
      name: true,
      mobile: true,
      normalizedMobile: true,
      address: true,
      occupation: true,
      category: true,
    },
  });
  const regByMobile = new Map(regs.map((r) => [r.normalizedMobile, r]));

  return rows.map((row) => {
    const reg = regByMobile.get(normalizeMobile(row.batchMember.mobile)) ?? null;
    return {
      assignment_id: row.id,
      registration_id: reg?.id ?? null,
      name: reg?.name ?? row.batchMember.mobile,
      mobile: reg?.mobile ?? row.batchMember.mobile,
      address: reg?.address ?? null,
      occupation: reg?.occupation ?? null,
      category: reg?.category ?? null,
      called_at: row.calledAt,
      came: row.callStatus === SessionCallStatus.attended, // did they actually attend?
      call_status: row.callStatus,
      outcome_label: row.outcomeLabel,
      ae_session_id: row.campaign.aeSessionId,
      scheduled_at: row.campaign.scheduledAt,
    };
  });
};