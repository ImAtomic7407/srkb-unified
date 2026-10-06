import type { Request, Response } from "express";
import { Category } from "@prisma/client";
import { sendSuccess } from "../../lib/response.js";
import {
  getProprietorshipRoster,
  getCallingBatches,
  getCallingSessions,
  getCallingAttended,
} from "./myAttendance.service.js";

// View 2: permanent roster (students whose relationshipOwner = this caller)
export const proprietorshipRosterHandler = async (req: Request, res: Response) => {
  const result = await getProprietorshipRoster({
    userId: req.user!.id,
    category: req.query.category as Category,
    search: req.query.search as string | undefined,
    requester: req.user!,
  });

  sendSuccess(res, { count: result.length, items: result }, "Proprietorship roster loaded");
};

// View 1, step 1: batches where this caller has attended session calls
export const callingBatchesHandler = async (req: Request, res: Response) => {
  const result = await getCallingBatches({
    userId: req.user!.id,
    category: req.query.category as Category,
    requester: req.user!,
  });

  sendSuccess(res, { count: result.length, items: result }, "Calling batches loaded");
};

// View 1, step 2: sessions within a batch where this caller converted attendees
export const callingSessionsHandler = async (req: Request, res: Response) => {
  const result = await getCallingSessions({
    userId: req.user!.id,
    batchId: Number(req.query.batch_id),
    requester: req.user!,
  });

  sendSuccess(res, { count: result.length, items: result }, "Calling sessions loaded");
};

// View 1, step 3: people this caller called who attended a specific session
export const callingAttendedHandler = async (req: Request, res: Response) => {
  const result = await getCallingAttended({
    userId: req.user!.id,
    campaignId: Number(req.query.campaign_id),
    requester: req.user!,
  });

  sendSuccess(res, { count: result.length, items: result }, "Attended people loaded");
};