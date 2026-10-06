import { Router } from "express";
import { asyncHandler } from "../../lib/async-handler.js";
import { validateQuery } from "../../middlewares/validate.js";
import { dashboardSummaryHandler } from "./dashboard.controller.js";
import { dashboardSummaryQuerySchema } from "./dashboard.schema.js";
import { requireAdminOrPermission } from "../../middlewares/permissions.js";
import { requestContextStorage } from "../../lib/request-context.js";
import { checkIntegrity } from "../../lib/integrity.js";
import { sendSuccess } from "../../lib/response.js";

export const dashboardRouter = Router();

dashboardRouter.get(
  "/dashboard/summary",
  validateQuery(dashboardSummaryQuerySchema),
  asyncHandler(dashboardSummaryHandler),
);

// Stuck-data check for the admin's own centre, run live (read-only). The app shows the result on
// Pool Assignment and Planned Sessions, so a stuck state is seen the day it happens instead of
// being found weeks later from a wrong count. See lib/integrity.ts.
dashboardRouter.get(
  "/integrity",
  requireAdminOrPermission("assign_calling_access"),
  asyncHandler(async (_req, res) => {
    const centreId = requestContextStorage.getStore()?.centreId ?? undefined;
    if (!centreId) { res.status(400).json({ success: false, message: "No active centre" }); return; }
    sendSuccess(res, await checkIntegrity({ centreId }), "Integrity checked");
  }),
);

