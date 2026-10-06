import type { Request, Response } from "express";
import { Category } from "@prisma/client";
import { sendSuccess } from "../../lib/response.js";
import { getRequestContext } from "../../lib/request-context.js";
import {
  getNewRegistrationsPool,
  getRegisteredByDate,
  getPoolSummary,
  getRemainingFollowupsPool,
  assignBatchCallingMembers,
} from "./pool.service.js";
import { getBatchCallingPool } from "../batch/batch.service.js";
import {
  listPeopleByFilter,
  listSessionPeopleByOutcome,
  listSessionResponseOptions,
  type PeopleFilterInclude,
} from "../attendance/attendease-attendance.service.js";
import { sessionPeoplePoolSchema, sessionResponsesPoolSchema } from "./pool.schema.js";
import { listSessionsForFilter } from "../sessionCampaign/sessionCampaign.service.js";

export const newRegistrationsPoolHandler = async (req: Request, res: Response) => {
  const result = await getNewRegistrationsPool({
    category: req.query.category as Category,
    dateFrom: req.query.date_from as string | undefined,
    dateTo: req.query.date_to as string | undefined,
    assignedStatus: req.query.assigned_status as "assigned" | "unassigned" | "all",
    search: req.query.search as string | undefined,
    requester: req.user!,
  });

  sendSuccess(res, { count: result.length, items: result }, "New registration pool loaded");
};

export const registeredByDateHandler = async (req: Request, res: Response) => {
  const result = await getRegisteredByDate({
    category: req.query.category as Category,
    dateFrom: req.query.date_from as string | undefined,
    dateTo: req.query.date_to as string | undefined,
    search: req.query.search as string | undefined,
    requester: req.user!,
  });

  sendSuccess(res, { count: result.length, items: result }, "Registered-by-date pool loaded");
};

export const remainingFollowupsPoolHandler = async (req: Request, res: Response) => {
  const result = await getRemainingFollowupsPool({
    category: req.query.category as Category,
    lastResponse: req.query.last_response as string | undefined,
    callerId: req.query.caller_id ? Number(req.query.caller_id) : undefined,
    search: req.query.search as string | undefined,
    requester: req.user!,
  });

  sendSuccess(res, { count: result.length, items: result }, "Remaining follow-up pool loaded");
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

export const poolSummaryHandler = async (req: Request, res: Response) => {
  const result = await getPoolSummary(req.query.category as Category, req.user!);
  sendSuccess(res, result, "Pool summary loaded");
};
export const batchMembersPoolHandler = async (req: Request, res: Response) => {
  const centreId = getRequestContext().centreId;
  if (!centreId) {
    res.status(400).json({ success: false, message: 'No active centre — send x-centre-id header' });
    return;
  }
  const result = await listPeopleByFilter({
    centreId,
    category: req.query.category as Category,
    batchId: req.query.batch_id ? Number(req.query.batch_id) : undefined,
    sessionId: req.query.session_id as string | undefined,
    date: req.query.date as string | undefined,
    include: req.query.include as PeopleFilterInclude | undefined,
    exceptCampaignId: req.query.exclude_campaign_id ? Number(req.query.exclude_campaign_id) : undefined,
  });
  sendSuccess(res, { count: result.length, items: result }, 'Batch members pool loaded');
};

// The list/number transforms in these two schemas are re-applied here rather than read off
// req.query: validateQuery merges into req.query, which is not guaranteed to keep the parsed arrays.
export const sessionResponsesPoolHandler = async (req: Request, res: Response) => {
  const centreId = getRequestContext().centreId;
  if (!centreId) {
    res.status(400).json({ success: false, message: "No active centre — send x-centre-id header" });
    return;
  }
  const q = sessionResponsesPoolSchema.parse(req.query);
  const result = await listSessionResponseOptions({ centreId, category: q.category, sessionIds: q.session_ids });
  sendSuccess(res, result, "Session responses loaded");
};

export const sessionPeoplePoolHandler = async (req: Request, res: Response) => {
  const centreId = getRequestContext().centreId;
  if (!centreId) {
    res.status(400).json({ success: false, message: "No active centre — send x-centre-id header" });
    return;
  }
  const q = sessionPeoplePoolSchema.parse(req.query);
  const result = await listSessionPeopleByOutcome({
    centreId,
    category: q.category,
    sessionIds: q.session_ids,
    sentiments: q.sentiments,
    responseIds: q.response_ids,
    neverCalled: q.never_called,
    attendance: q.attendance,
    exceptCampaignId: q.exclude_campaign_id,
  });
  sendSuccess(res, { count: result.length, items: result }, "Session people loaded");
};

export const poolSessionsHandler = async (req: Request, res: Response) => {
  const centreId = getRequestContext().centreId;
  if (!centreId) {
    res.status(400).json({ success: false, message: "No active centre — send x-centre-id header" });
    return;
  }
  const result = await listSessionsForFilter(centreId, req.query.category as Category | undefined);
  sendSuccess(res, { count: result.length, items: result }, "Centre sessions loaded");
};

export const batchCallingAssignHandler = async (req: Request, res: Response) => {
  const ctx = getRequestContext();
  if (!ctx.centreId) {
    res.status(400).json({ success: false, message: "No active centre — send x-centre-id header" });
    return;
  }
  const { batch_member_ids, caller_id, active_days } = req.body as {
    batch_member_ids: number[];
    caller_id: number;
    active_days: number;
  };
  const count = await assignBatchCallingMembers({
    batchMemberIds: batch_member_ids,
    callerId: caller_id,
    activeDays: active_days,
    assignedByUserId: ctx.userId,
    centreId: ctx.centreId,
  });
  sendSuccess(res, { assigned_count: count }, `${count} batch member(s) assigned to caller`);
};
