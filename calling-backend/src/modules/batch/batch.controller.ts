import type { Request, Response } from "express";
import { BatchCallingAssignmentMode, BatchMoveRequestStatus, Category } from "@prisma/client";
import { ApiError } from "../../lib/api-error.js";
import { sendSuccess } from "../../lib/response.js";
import { getRequestContext } from "../../lib/request-context.js";
import { getBatchMembers } from "./batch-member.service.js";
import {
  approveBatchMoveRequest,
  batchCallingDone,
  createBatch,
  disableBatch,
  getBatchCallingPool,
  getBatchCallingView,
  listBatchMoveRequests,
  listBatches,
  parseAdminOverride,
  rejectBatchMoveRequest,
  sendBackToOriginalCaller,
} from "./batch.service.js";

const requireAdminOverridePermission = (req: Request) => {
  if (
    !req.user?.permissions.some(
      (permission) => permission === "assign_calling_access" || permission === "report_access",
    )
  ) {
    throw new ApiError(403, "Admin override requires assign_calling_access or report_access");
  }
};

export const createBatchHandler = async (req: Request, res: Response) => {
  const batch = await createBatch({
    name: req.body.name,
    category: req.body.category,
  });

  sendSuccess(res, batch, "Batch created", 201);
};

export const listBatchesHandler = async (req: Request, res: Response) => {
  const batches = await listBatches(req.query.category as Category, getRequestContext().centreId ?? undefined);
  sendSuccess(res, { count: batches.length, items: batches }, "Batches loaded");
};

export const disableBatchHandler = async (req: Request, res: Response) => {
  const batch = await disableBatch(Number(req.params.id));
  sendSuccess(res, batch, "Batch disabled");
};

export const batchMoveRequestsListHandler = async (req: Request, res: Response) => {
  const result = await listBatchMoveRequests(
    {
      category: req.query.category as Category,
      status: req.query.status as BatchMoveRequestStatus,
      requester: req.user!,
    },
  );
  sendSuccess(res, { count: result.length, items: result }, "Batch move requests loaded");
};

export const batchMoveRequestApproveHandler = async (req: Request, res: Response) => {
  const result = await approveBatchMoveRequest({
    requestId: Number(req.params.id),
    batchId: req.body.batch_id,
    assignmentMode: req.body.assignment_mode as BatchCallingAssignmentMode,
    assignedToUserId: req.body.assigned_to_user_id,
    activeDays: req.body.active_days,
    adminUserId: req.user!.id,
    requester: req.user!,
  });

  sendSuccess(res, result, "Batch move request approved");
};

export const batchMoveRequestRejectHandler = async (req: Request, res: Response) => {
  const result = await rejectBatchMoveRequest({
    requestId: Number(req.params.id),
    reason: req.body.reason,
    adminUserId: req.user!.id,
    requester: req.user!,
  });

  sendSuccess(res, result, "Batch move request rejected");
};

export const batchCallingViewHandler = async (req: Request, res: Response) => {
  const adminOverride = parseAdminOverride(req.query.admin_override as string | undefined);
  if (adminOverride) {
    requireAdminOverridePermission(req);
  }

  const result = await getBatchCallingView({
    userId: req.user!.id,
    category: req.query.category as Category,
    batchId: req.query.batch_id ? Number(req.query.batch_id) : undefined,
    search: req.query.search as string | undefined,
    adminOverride,
    requester: req.user!,
  });

  sendSuccess(res, result, "Batch calling loaded");
};

export const batchCallingDoneHandler = async (req: Request, res: Response) => {
  const adminOverride = parseAdminOverride(req.query.admin_override as string | undefined);
  if (adminOverride) {
    requireAdminOverridePermission(req);
  }

  const result = await batchCallingDone({
    batchMemberId: Number(req.params.batch_member_id),
    userId: req.user!.id,
    dropdownId: req.body.dropdown_id,
    remark: req.body.remark,
    adminOverride,
    requester: req.user!,
  });

  sendSuccess(res, result, "Batch calling attempt saved");
};

export const batchSendBackHandler = async (req: Request, res: Response) => {
  const adminOverride = parseAdminOverride(req.query.admin_override as string | undefined);
  if (adminOverride) {
    requireAdminOverridePermission(req);
  }

  const result = await sendBackToOriginalCaller({
    batchMemberId: Number(req.params.batch_member_id),
    userId: req.user!.id,
    reason: req.body.reason,
    adminOverride,
    requester: req.user!,
  });

  sendSuccess(res, result, "Student sent back to original caller");
};

export const batchCallingPoolHandler = async (req: Request, res: Response) => {
  const result = await getBatchCallingPool({
    category: req.query.category as Category,
    batchId: req.query.batch_id ? Number(req.query.batch_id) : undefined,
    assignedStatus: req.query.assigned_status as "assigned" | "unassigned" | "all",
    search: req.query.search as string | undefined,
    requester: req.user!,
  });

  sendSuccess(res, result, "Batch calling pool loaded");
};

export const batchMembersHandler = async (req: Request, res: Response) => {
  const ctx = getRequestContext();
  const centreId = ctx.centreId;
  if (!centreId) throw new ApiError(400, "Centre is required");
  const category = req.query.category as string;
  if (!category) throw new ApiError(400, "category is required");
  const batchId = req.query.batch_id ? Number(req.query.batch_id) : undefined;
  const members = await getBatchMembers(centreId, category, batchId);
  sendSuccess(res, { items: members.map(m => ({ id: m.id ?? 0, mobile: m.mobile, name: m.name, batch_id: m.batchId, joined_at: m.joinedAt })) });
};

// Whole-centre attendance-candidate roster: every person on the centre's calling roster
// (all batches, given category) with a resolved name. Feeds the Mark-Attendance autocomplete so a
// caller can mark attendance for anyone they called — not just people who already have an
// attendance record. Attendance IS registration here, so an unknown person is created on mark.
// Lightly gated (any attendance-adjacent permission) because it is just names+mobiles, no PII beyond
// what these users already see; a full-batch list (batch_id omitted) is returned.
export const attendanceRosterHandler = async (req: Request, res: Response) => {
  const ctx = getRequestContext();
  const centreId = ctx.centreId;
  if (!centreId) throw new ApiError(400, "Centre is required");
  const category = req.query.category as string;
  if (!category) throw new ApiError(400, "category is required");
  const members = await getBatchMembers(centreId, category);
  sendSuccess(res, { items: members.map((m) => ({ mobile: m.mobile, name: m.name })) });
};
