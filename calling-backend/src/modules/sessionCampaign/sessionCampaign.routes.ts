import { Router } from "express";
import { asyncHandler } from "../../lib/async-handler.js";
import { requirePermission, requireAnyPermission } from "../../middlewares/permissions.js";
import { validateBody, validateParams, validateQuery } from "../../middlewares/validate.js";
import { z } from "zod";
import {
  createCampaignSchema,
  assignSessionCallersSchema,
  listCampaignsSchema,
  exportSessionReportSchema,
  updateCallStatusSchema,
  setCallerNoteSchema,
  setCampaignWhatsappMessageSchema,
} from "./sessionCampaign.schema.js";
import {
  createCampaignHandler,
  listCampaignsHandler,
  exportSessionReportHandler,
  assignSessionCallersHandler,
  sessionCallQueueHandler,
  updateCallStatusHandler,
  closeSessionCampaignHandler,
  reopenSessionCampaignHandler,
  completeSessionCampaignHandler,
  getSessionSummaryHandler,
  removeSessionCallAssignmentHandler,
  setSessionCallerNoteHandler,
  setCampaignWhatsappMessageHandler,
} from "./sessionCampaign.controller.js";

export const sessionCampaignRouter = Router();

// Admin: create a planned session calling campaign
sessionCampaignRouter.post(
  "/session-campaigns",
  requirePermission("assign_calling_access"),
  validateBody(createCampaignSchema),
  asyncHandler(createCampaignHandler),
);

// Admin: list campaigns
sessionCampaignRouter.get(
  "/session-campaigns",
  requirePermission("assign_calling_access"),
  validateQuery(listCampaignsSchema),
  asyncHandler(listCampaignsHandler),
);

// Admin: assign batch members to callers for a campaign
sessionCampaignRouter.post(
  "/session-campaigns/:id/assign",
  requirePermission("assign_calling_access"),
  validateParams(z.object({ id: z.coerce.number().int().positive() })),
  validateBody(assignSessionCallersSchema),
  asyncHandler(assignSessionCallersHandler),
);

// Caller: see their session call queue
sessionCampaignRouter.get(
  "/session-call-queue",
  requireAnyPermission(["calling_access", "batch_calling_access"]),
  asyncHandler(sessionCallQueueHandler),
);

// Caller: record outcome for a session call
sessionCampaignRouter.patch(
  "/session-call-queue/:id",
  requireAnyPermission(["calling_access", "batch_calling_access"]),
  validateParams(z.object({ id: z.coerce.number().int().positive() })),
  validateBody(updateCallStatusSchema),
  asyncHandler(updateCallStatusHandler),
);

// Admin: close calling (Retrieve) — no new responses, calls vanish from caller queue
sessionCampaignRouter.post(
  "/session-campaigns/:id/close",
  requirePermission("assign_calling_access"),
  validateParams(z.object({ id: z.coerce.number().int().positive() })),
  asyncHandler(closeSessionCampaignHandler),
);

// Admin: reopen a closed campaign (undo Retrieve before completing)
sessionCampaignRouter.post(
  "/session-campaigns/:id/reopen",
  requirePermission("assign_calling_access"),
  validateParams(z.object({ id: z.coerce.number().int().positive() })),
  asyncHandler(reopenSessionCampaignHandler),
);

// Admin: complete — run attendance reconciliation (attendance must be fully synced first)
sessionCampaignRouter.post(
  "/session-campaigns/:id/complete",
  requirePermission("assign_calling_access"),
  validateParams(z.object({ id: z.coerce.number().int().positive() })),
  asyncHandler(completeSessionCampaignHandler),
);

// Admin/SUPER_ADMIN: get reconciliation summary (preview before completing, or view after)
sessionCampaignRouter.get(
  "/session-campaigns/:id/summary",
  requirePermission("assign_calling_access"),
  validateParams(z.object({ id: z.coerce.number().int().positive() })),
  asyncHandler(getSessionSummaryHandler),
);

// Admin: instantly unassign a person from a caller (only while the campaign is active). Soft-remove —
// the row and its history stay in the DB, they just vanish from every live view.
sessionCampaignRouter.delete(
  "/session-campaigns/:id/assignments/:assignmentId",
  requirePermission("assign_calling_access"),
  validateParams(z.object({ id: z.coerce.number().int().positive(), assignmentId: z.coerce.number().int().positive() })),
  asyncHandler(removeSessionCallAssignmentHandler),
);

// Admin: set or clear (empty string) the note shown beside this session's name in a caller's My Callings.
sessionCampaignRouter.put(
  "/session-campaigns/:id/callers/:callerId/note",
  requirePermission("assign_calling_access"),
  validateParams(z.object({ id: z.coerce.number().int().positive(), callerId: z.coerce.number().int().positive() })),
  validateBody(setCallerNoteSchema),
  asyncHandler(setSessionCallerNoteHandler),
);

// Admin: set or clear (empty string) the WhatsApp message this calling session prefills.
//
// Guarded with assign_calling_access like its neighbours: composing the message the whole calling
// team sends is the same authority as deciding who calls whom. The campaign is scoped to the
// requester's own centre inside the service (404 on a foreign campaign) — no centre from the client.
sessionCampaignRouter.put(
  "/session-campaigns/:id/whatsapp-message",
  requirePermission("assign_calling_access"),
  validateParams(z.object({ id: z.coerce.number().int().positive() })),
  validateBody(setCampaignWhatsappMessageSchema),
  asyncHandler(setCampaignWhatsappMessageHandler),
);


// Admin/report: export this calling session as a CSV the centre opens in Excel.
//
// Guarded with the SAME pair the /downloads/* endpoints use, not with assign_calling_access alone:
// this is a report, and an admin who may read reports but not allot callers must still be able to
// export one. The campaign is scoped to the requester's own centre inside the service (404 on a
// foreign campaign) — no centre ever comes from the client.
sessionCampaignRouter.get(
  "/session-campaigns/:id/export",
  requireAnyPermission(["report_access", "assign_calling_access"]),
  validateParams(z.object({ id: z.coerce.number().int().positive() })),
  validateQuery(exportSessionReportSchema),
  asyncHandler(exportSessionReportHandler),
);
