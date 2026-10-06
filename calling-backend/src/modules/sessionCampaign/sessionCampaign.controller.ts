import type { Request, Response } from "express";
import { SessionCampaignStatus, SessionCallStatus, Category } from "@prisma/client";
import { sendSuccess } from "../../lib/response.js";
import { getRequestContext } from "../../lib/request-context.js";
import { sendCsv } from "../downloads/downloads.responses.js";
import {
  buildSessionReport,
  renderReportCsv,
  reportFilename,
  shapeReportRows,
  type ExportCohort,
  type ExportField,
  type ExportOrder,
} from "./sessionCampaignExport.service.js";
import {
  createSessionCampaign,
  listSessionCampaigns,
  assignSessionCallers,
  getSessionCallQueue,
  getSessionCallSummary,
  updateSessionCallStatus,
  closeSessionCampaign,
  reopenSessionCampaign,
  completeSessionCampaign,
  getSessionReconciliationSummary,
  removeSessionCallAssignment,
  setSessionCallerNote,
  setCampaignWhatsappMessage,
} from "./sessionCampaign.service.js";

export const createCampaignHandler = async (req: Request, res: Response) => {
  const { centreId, userId } = getRequestContext();
  if (!centreId) { res.status(400).json({ success: false, message: "No active centre" }); return; }

  const { ae_session_id, batch_id, scheduled_at } = req.body as {
    ae_session_id: string;
    batch_id: number;
    scheduled_at: string;
  };

  const campaign = await createSessionCampaign({
    aeSessionId: ae_session_id,
    centreId,
    batchId: batch_id,
    scheduledAt: new Date(scheduled_at),
    createdById: userId,
  });

  sendSuccess(res, campaign, "Session campaign created");
};

export const listCampaignsHandler = async (req: Request, res: Response) => {
  const { centreId } = getRequestContext();
  if (!centreId) { res.status(400).json({ success: false, message: "No active centre" }); return; }

  const statusQuery = req.query.status as string | undefined;
  const campaigns = await listSessionCampaigns({
    centreId,
    status: statusQuery === "all" ? undefined : ((statusQuery as SessionCampaignStatus) ?? SessionCampaignStatus.active),
    batchId: req.query.batch_id ? Number(req.query.batch_id) : undefined,
    // Undefined for app builds <= v38, which do not send it — those keep the old
    // both-categories list rather than losing every session mid-rollout.
    category: req.query.category as Category | undefined,
  });

  sendSuccess(res, { count: campaigns.length, items: campaigns }, "Session campaigns loaded");
};

export const assignSessionCallersHandler = async (req: Request, res: Response) => {
  const { centreId } = getRequestContext();
  if (!centreId) { res.status(400).json({ success: false, message: "No active centre" }); return; }

  const campaignId = Number(req.params.id);
  const { assignments, allotments } = req.body as {
    assignments: { batch_member_id: number; caller_id: number }[];
    allotments?: { caller_id: number; key: string; label?: string; filter?: string; replace?: boolean }[];
  };

  const count = await assignSessionCallers({
    campaignId,
    centreId,
    assignments,
    allotments,
    // Whoever is doing the allotting owns any soft-removal a `replace` causes.
    actorId: getRequestContext().userId,
    now: new Date(),
  });

  sendSuccess(res, { assigned_count: count }, `${count} session call(s) assigned`);
};

export const sessionCallQueueHandler = async (req: Request, res: Response) => {
  const { centreId, userId } = getRequestContext();
  if (!centreId) { res.status(400).json({ success: false, message: "No active centre" }); return; }

  const now = new Date();
  const [queue, summary] = await Promise.all([
    getSessionCallQueue({ callerId: userId, centreId, now }),
    getSessionCallSummary({ callerId: userId, centreId, now }),
  ]);
  // `items` stays the list to work through NOW. Deferred people go in `scheduled` instead of being
  // dropped, so an app build that doesn't know about the Scheduled section simply stops showing
  // them (correct) rather than showing them as callable (the old, wrong behaviour).
  sendSuccess(
    res,
    {
      count: queue.active.length,
      items: queue.active,
      scheduled_count: queue.scheduled.length,
      scheduled: queue.scheduled,
      summary,
    },
    "Session call queue loaded"
  );
};

export const closeSessionCampaignHandler = async (req: Request, res: Response) => {
  const { centreId } = getRequestContext();
  if (!centreId) { res.status(400).json({ success: false, message: "No active centre" }); return; }
  const campaignId = Number(req.params.id);
  const updated = await closeSessionCampaign(campaignId, centreId);
  sendSuccess(res, updated, "Session campaign closed — callers will no longer see these assignments");
};

export const reopenSessionCampaignHandler = async (req: Request, res: Response) => {
  const { centreId } = getRequestContext();
  if (!centreId) { res.status(400).json({ success: false, message: "No active centre" }); return; }
  const campaignId = Number(req.params.id);
  const updated = await reopenSessionCampaign(campaignId, centreId);
  sendSuccess(res, updated, "Session campaign reopened");
};

export const completeSessionCampaignHandler = async (req: Request, res: Response) => {
  const { centreId } = getRequestContext();
  if (!centreId) { res.status(400).json({ success: false, message: "No active centre" }); return; }
  const campaignId = Number(req.params.id);
  const summary = await completeSessionCampaign(campaignId, centreId, new Date());
  sendSuccess(res, summary, "Session campaign completed — reconciliation applied");
};

export const getSessionSummaryHandler = async (req: Request, res: Response) => {
  const { centreId } = getRequestContext();
  if (!centreId) { res.status(400).json({ success: false, message: "No active centre" }); return; }
  const campaignId = Number(req.params.id);
  const summary = await getSessionReconciliationSummary(campaignId, centreId);
  sendSuccess(res, summary, "Session reconciliation summary loaded");
};

export const removeSessionCallAssignmentHandler = async (req: Request, res: Response) => {
  const { centreId, userId } = getRequestContext();
  if (!centreId) { res.status(400).json({ success: false, message: "No active centre" }); return; }

  const campaignId = Number(req.params.id);
  const assignmentId = Number(req.params.assignmentId);

  await removeSessionCallAssignment({
    campaignId,
    assignmentId,
    centreId,
    removedById: userId,
    now: new Date(),
  });

  sendSuccess(res, { assignment_id: assignmentId }, "Person unassigned from this calling session");
};

export const setSessionCallerNoteHandler = async (req: Request, res: Response) => {
  const { centreId, userId } = getRequestContext();
  if (!centreId) { res.status(400).json({ success: false, message: "No active centre" }); return; }

  const campaignId = Number(req.params.id);
  const callerId = Number(req.params.callerId);
  const { note } = req.body as { note: string };

  const result = await setSessionCallerNote({ campaignId, callerId, centreId, note, updatedById: userId });
  sendSuccess(res, result, result ? "Note saved" : "Note cleared");
};

// Admin: set or clear (empty string) the WhatsApp message this calling session prefills for its
// callers. Centre scoping is enforced in the service, which 404s on a foreign campaign.
export const setCampaignWhatsappMessageHandler = async (req: Request, res: Response) => {
  const { centreId } = getRequestContext();
  if (!centreId) { res.status(400).json({ success: false, message: "No active centre" }); return; }

  const campaignId = Number(req.params.id);
  const { message } = req.body as { message: string };

  const result = await setCampaignWhatsappMessage({ campaignId, centreId, message });
  sendSuccess(res, result, result.whatsappMessage ? "WhatsApp message saved" : "WhatsApp message cleared");
};

export const updateCallStatusHandler = async (req: Request, res: Response) => {
  const { centreId, userId } = getRequestContext();
  if (!centreId) { res.status(400).json({ success: false, message: "No active centre" }); return; }

  const assignmentId = Number(req.params.id);
  const { call_status, outcome, remark, answered, dropdown_id } = req.body as {
    call_status?: string;
    outcome?: string;
    remark?: string;
    answered?: boolean;
    dropdown_id?: number;
  };

  const updated = await updateSessionCallStatus({
    assignmentId,
    callerId: userId,
    centreId,
    callStatus: call_status as SessionCallStatus | undefined,
    outcome,
    remark,
    answered,
    dropdownId: dropdown_id,
    now: new Date(),
  });

  sendSuccess(res, updated, "Call status updated");
};

// Export one calling session as a sheet the centre opens in Excel. `field` picks which of the
// client's four criteria orders the sheet; `order`/`cohort` are that field's sub-choices.
//
// Not restricted to completed campaigns. The button lives under the completed-session report, so
// that is where it is used from, but the same rows are meaningful mid-session and refusing would
// only produce an error the operator cannot act on.
export const exportSessionReportHandler = async (req: Request, res: Response) => {
  const { centreId } = getRequestContext();
  if (!centreId) { res.status(400).json({ success: false, message: "No active centre" }); return; }

  const campaignId = Number(req.params.id);
  const field = req.query.field as ExportField;
  const order = req.query.order as ExportOrder | undefined;
  const cohort = req.query.cohort as ExportCohort | undefined;

  const { meta, rows } = await buildSessionReport(campaignId, centreId);
  const shaped = shapeReportRows(rows, field, order, cohort);

  sendCsv(res, reportFilename(meta, field, order, cohort), renderReportCsv(shaped));
};
