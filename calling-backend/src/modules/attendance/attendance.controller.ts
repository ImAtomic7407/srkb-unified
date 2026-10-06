import { Category } from "@prisma/client";
import type { Request, Response } from "express";
import { sendSuccess } from "../../lib/response.js";
import { rawPrisma } from "../../config/prisma.js";
import { getSessionsForRegistration } from "../sessionCampaign/sessionCampaign.service.js";

const gone = (_req: Request, res: Response) => {
  res.status(410).json({ success: false, message: "Google Sheets attendance sync has been removed." });
};

export const attendanceConfigHandler = gone;
export const attendanceConfigTestHandler = gone;
export const attendanceConfigSaveHandler = gone;
export const attendanceConfigUnlockHandler = gone;
export const attendanceSystemLinkHandler = gone;
export const attendanceSyncHandler = gone;
export const attendanceSessionPreviewHandler = gone;
export const attendanceSessionImportHandler = gone;

export const attendanceSessionsHandler = async (req: Request, res: Response) => {
  const batchId = req.query.batch_id ? Number(req.query.batch_id) : undefined;
  const category = req.query.category as Category | undefined;
  const effectiveCentreId = req.user!.centreId;
  const sessions = await rawPrisma.session.findMany({
    where: {
      ...(effectiveCentreId ? { centreId: effectiveCentreId } : {}),
      ...(category ? { category } : {}),
      ...(batchId ? { batchId } : {}),
      status: { not: "deleted" },
    },
    orderBy: [{ date: "desc" }, { displayName: "asc" }],
    select: {
      sessionId: true,
      displayName: true,
      batchId: true,
      centreId: true,
      category: true,
      date: true,
      status: true,
    },
  });
  sendSuccess(res, { count: sessions.length, items: sessions }, "Sessions loaded");
};

// Registerer's session dropdown — sessions split into "Called Session" (last 4 with a calling
// campaign) and "Direct Attendance" (last 4 attendance-only). Available to registerers.
export const sessionsForRegistrationHandler = async (req: Request, res: Response) => {
  const category = req.query.category as Category | undefined;
  const centreId = req.user!.centreId;
  if (!centreId) {
    sendSuccess(res, { called: [], direct_attendance: [] }, "No centre context");
    return;
  }
  const grouped = await getSessionsForRegistration(centreId, category);
  sendSuccess(res, grouped, "Sessions for registration loaded");
};