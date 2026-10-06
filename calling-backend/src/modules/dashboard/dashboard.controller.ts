import type { Request, Response } from "express";
import { Category, UserRole } from "@prisma/client";
import { getDashboardSummary } from "./dashboard.service.js";
import { sendSuccess } from "../../lib/response.js";
import { requestContextStorage } from "../../lib/request-context.js";

export const dashboardSummaryHandler = async (req: Request, res: Response) => {
  const category = req.query.category as Category;
  const requester = req.user!;
  // Android sends x-centre-id header (not ?centre_id), so prefer the context
  // centreId resolved by resolveCentre middleware; fall back to query param for
  // any web callers that pass it explicitly.
  const ctx = requestContextStorage.getStore();
  const requestedCentreId = req.query.centre_id
    ? Number(req.query.centre_id)
    : (ctx?.centreId ?? undefined);
  const effectiveRequester =
    requester.role === UserRole.SUPER_ADMIN
      ? { ...requester, centreId: requestedCentreId ?? null }
      : requester;
  const result = await getDashboardSummary({
    category,
    requester: effectiveRequester,
  });

  sendSuccess(res, result, "Dashboard summary loaded");
};
