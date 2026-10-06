// Batch service — implements batch calling operations.
// Batches are owned by AttendEase and mirrored here — CB cannot create them.

import { BatchCallingAssignmentMode, BatchCallingAssignmentStatus, Category, UserRole } from "@prisma/client";
import { prisma, rawPrisma } from "../../config/prisma.js";
import { getRequestContext } from "../../lib/request-context.js";
import { ApiError } from "../../lib/api-error.js";
import { attendeaseRepo } from "../../lib/attendease-db.js";
import { recomputeCentreCategory } from "../../lib/projections.js";
import type { Request } from "express";

type RequestUser = NonNullable<Request["user"]>;

// ── Read / write ───────────────────────────────────────────────────────────

// Batches are synced from AttendEase — CB cannot create them independently.
export const createBatch = async (_args: unknown): Promise<void> => {
  throw new ApiError(
    501,
    "Batches are created in AttendEase — use the AttendEase batch UI. CB mirrors them via webhook/reconcile.",
  );
};

export const listBatches = async (category: Category, centreId?: number) => {
  const batches = await prisma.batch.findMany({
    // Archived batches must not reach the pickers this feeds (Pool Assignment's batch strip,
    // Planned Sessions). Filtering only on category let a retired batch keep showing up for
    // selection - e.g. Centre A's archived "SUNDAY CONGREGATION", which is additionally
    // mis-categorised as youth, so it surfaced on the youth toggle where it plainly does not
    // belong. Only one endpoint consumes this (GET /batches), so nothing loses history here.
    where: { category, status: "active" },
    // Newest batch first, with the default batch still pinned to the top.
    //
    // Was [isDefault desc, name asc] — alphabetical, so a batch created today sat wherever its
    // name happened to fall and the newest one was rarely near the top. Batch has no createdAt;
    // `id` is AttendEase's autoincrement primary key, so id-desc IS creation order and is the
    // correct proxy. The isDefault pin is deliberately KEPT: the default batch is where walk-ins
    // are filed, registerers reach for it constantly, and un-pinning it mid-marking would invite
    // picking the wrong batch. Recency orders everything below the pin.
    orderBy: [{ isDefault: "desc" }, { id: "desc" }],
    select: { id: true, name: true, category: true, isDefault: true, status: true, lastSyncedAt: true },
  });

  // member_count MUST match the batch roster the user actually sees when they open the batch, i.e.
  // the count of active BatchMember rows — NOT unique AttendEase attendees. Those two diverge for any
  // batch whose members came from an import/calling flow rather than attendance (e.g. "sunday online"
  // has 20 roster members but 0 attendees, so the old attendee-based count showed 0 while the roster
  // showed 20). Roster membership by batch is the single source of truth for the badge.
  let memberCounts = new Map<number, number>();
  if (centreId && batches.length) {
    const grouped = await rawPrisma.batchMember.groupBy({
      by: ["batchId"],
      where: { centreId, removedAt: null, batchId: { in: batches.map((b) => b.id) } },
      _count: { _all: true },
    });
    memberCounts = new Map(grouped.map((g) => [g.batchId, g._count._all]));
  }

  return batches.map((b) => ({ ...b, member_count: memberCounts.get(b.id) ?? 0 }));
};

// Mark a batch archived in the CB mirror. Does not propagate back to AttendEase.
export const disableBatch = async (batchId: number) => {
  const ctx = getRequestContext();
  const batch = await rawPrisma.batch.findFirst({
    where: { id: batchId, centreId: ctx.centreId ?? undefined },
  });
  if (!batch) throw new ApiError(404, "Batch not found");
  if (batch.isDefault) throw new ApiError(400, "Cannot archive the default batch");

  const updated = await rawPrisma.batch.update({
    where: { id: batchId },
    data: { status: "archived", lastSyncedAt: new Date() },
  });
  await recomputeCentreCategory(rawPrisma, batch.centreId, batch.category as Category, ["batch_calling"]).catch(() => undefined);
  return updated;
};

// ── Batch-move requests (stubs — Phase 8) ─────────────────────────────────

const notImplemented = (name: string) => {
  throw new ApiError(501, `${name} not yet implemented — pending Phase 8`);
};

export const listBatchMoveRequests = async (_args: unknown): Promise<never[]> => {
  notImplemented("listBatchMoveRequests");
  return [];
};

export const approveBatchMoveRequest = async (_args: unknown): Promise<void> => {
  notImplemented("approveBatchMoveRequest");
};

export const rejectBatchMoveRequest = async (_args: unknown): Promise<void> => {
  notImplemented("rejectBatchMoveRequest");
};

// ── Batch calling ──────────────────────────────────────────────────────────

export const getBatchCallingView = async ({
  userId,
  category,
  batchId,
  search,
  adminOverride,
  requester,
}: {
  userId: number;
  category: Category;
  batchId?: number;
  search?: string;
  adminOverride?: string;
  requester: RequestUser;
}) => {
  const ctx = getRequestContext();

  // Expire stale active assignments before serving the view.
  await rawPrisma.batchCallingAssignment.updateMany({
    where: {
      status: BatchCallingAssignmentStatus.active,
      endDate: { lt: new Date() },
    },
    data: { status: BatchCallingAssignmentStatus.done },
  });

  const assignments = await rawPrisma.batchCallingAssignment.findMany({
    where: {
      ...(adminOverride ? {} : { assignedToUserId: userId }),
      status: BatchCallingAssignmentStatus.active,
      ...(ctx.centreId ? { centreId: ctx.centreId } : {}),
      batchMember: {
        removedAt: null,
        category,
        ...(batchId ? { batchId } : {}),
      },
    },
    include: {
      batchMember: {
        include: { batch: { select: { id: true, name: true, isDefault: true } } },
      },
    },
    orderBy: { startDate: "asc" },
  });

  const filtered = search
    ? assignments.filter((a) => a.batchMember.mobile.includes(search))
    : assignments;

  return filtered.map((a) => ({
    id: a.id,
    batch_member_id: a.batchMemberId,
    mobile: a.batchMember.mobile,
    batch_id: a.batchMember.batchId,
    batch_name: a.batchMember.batch.name,
    category: a.batchMember.category,
    joined_at: a.batchMember.joinedAt,
    assignment_mode: a.assignmentMode,
    start_date: a.startDate,
    end_date: a.endDate,
  }));
};

export const getBatchCallingPool = async ({
  category,
  batchId,
  assignedStatus,
  search,
  requester,
}: {
  category: Category;
  batchId?: number;
  assignedStatus: "assigned" | "unassigned" | "all";
  search?: string;
  requester: RequestUser;
}): Promise<{ pending_requests: never[]; active_batch_members: object[] }> => {
  const ctx = getRequestContext();
  const centreId =
    ctx.centreId ??
    (requester.role === UserRole.SUPER_ADMIN ? (requester.centreId ?? null) : requester.centreId);

  const members = await rawPrisma.batchMember.findMany({
    where: {
      category,
      removedAt: null,
      ...(centreId ? { centreId } : {}),
      ...(batchId ? { batchId } : {}),
    },
    include: {
      batch: { select: { id: true, name: true } },
      batchCallingAssignment: {
        select: {
          status: true,
          assignedToUserId: true,
          assignedToUser: { select: { id: true, name: true } },
        },
      },
    },
    orderBy: { joinedAt: "asc" },
  });

  const filtered = members.filter((m) => {
    const isActivelyAssigned =
      m.batchCallingAssignment?.status === BatchCallingAssignmentStatus.active;
    if (assignedStatus === "assigned" && !isActivelyAssigned) return false;
    if (assignedStatus === "unassigned" && isActivelyAssigned) return false;
    if (search && !m.mobile.includes(search)) return false;
    return true;
  });

  return {
    pending_requests: [],
    active_batch_members: filtered.map((m) => ({
      id: m.id,
      mobile: m.mobile,
      batch_id: m.batchId,
      batch_name: m.batch.name,
      category: m.category,
      joined_at: m.joinedAt,
      has_active_assignment:
        m.batchCallingAssignment?.status === BatchCallingAssignmentStatus.active,
      assigned_to: m.batchCallingAssignment?.assignedToUser
        ? {
            id: m.batchCallingAssignment.assignedToUser.id,
            name: m.batchCallingAssignment.assignedToUser.name,
          }
        : null,
    })),
  };
};

export const batchCallingDone = async ({
  batchMemberId,
  userId,
  adminOverride,
}: {
  batchMemberId: number;
  userId: number;
  dropdownId?: number;
  remark?: string;
  adminOverride?: string;
  requester: RequestUser;
}) => {
  const assignment = await rawPrisma.batchCallingAssignment.findUnique({
    where: { batchMemberId },
  });

  if (!assignment) throw new ApiError(404, "Batch calling assignment not found");
  if (!adminOverride && assignment.assignedToUserId !== userId) {
    throw new ApiError(403, "This assignment belongs to a different caller");
  }
  if (assignment.status !== BatchCallingAssignmentStatus.active) {
    throw new ApiError(409, "Assignment is not active");
  }

  await rawPrisma.batchCallingAssignment.update({
    where: { batchMemberId },
    data: { status: BatchCallingAssignmentStatus.done },
  });

  return { batch_member_id: batchMemberId, status: "done" };
};

export const sendBackToOriginalCaller = async ({
  batchMemberId,
  userId,
  adminOverride,
}: {
  batchMemberId: number;
  userId: number;
  reason?: string;
  adminOverride?: string;
  requester: RequestUser;
}) => {
  const assignment = await rawPrisma.batchCallingAssignment.findUnique({
    where: { batchMemberId },
  });

  if (!assignment) throw new ApiError(404, "Batch calling assignment not found");
  if (!adminOverride && assignment.assignedToUserId !== userId) {
    throw new ApiError(403, "Not authorized to send back this assignment");
  }
  if (assignment.status !== BatchCallingAssignmentStatus.active) {
    throw new ApiError(409, "Assignment is not active");
  }

  // Deactivate the assignment so admin can reassign from the pool.
  await rawPrisma.batchCallingAssignment.update({
    where: { batchMemberId },
    data: { status: BatchCallingAssignmentStatus.done },
  });

  return { batch_member_id: batchMemberId, sent_back: true };
};

export const parseAdminOverride = (_value?: string): string | undefined => {
  return _value;
};
