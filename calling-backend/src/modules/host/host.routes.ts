import { Router } from "express";
import { asyncHandler } from "../../lib/async-handler.js";
import { UserRole } from "@prisma/client";
import { ApiError } from "../../lib/api-error.js";
import { getRequestContext } from "../../lib/request-context.js";
import { sendSuccess } from "../../lib/response.js";
import { getCentreUserCounts, getCrosscentreRegistrations, getHostStatsCentre, getHostStatsSummary, getInactiveCentres } from "./host.service.js";

export const hostRouter = Router();

// All /host/* routes require SUPER_ADMIN role.
// resolveCentre middleware skips centre validation for /host/* (see resolve-centre.ts),
// so these run with centreId=null in context (full bypass).
hostRouter.use((req, _res, next) => {
  if (!req.path.startsWith("/host/")) return next();
  if (!req.user || req.user.role !== UserRole.SUPER_ADMIN) {
    return next(new ApiError(403, "Host role required"));
  }
  next();
});

// Aggregate counts across all active centres
hostRouter.get("/host/stats/summary", asyncHandler(async (_req, res) => {
  const data = await getHostStatsSummary();
  sendSuccess(res, data, "Host stats summary loaded");
}));

// Per-centre breakdown
hostRouter.get("/host/stats/centres/:centreId", asyncHandler(async (req, res) => {
  const centreId = Number(req.params.centreId);
  if (!Number.isInteger(centreId) || centreId <= 0) throw new ApiError(400, "Invalid centreId");
  const data = await getHostStatsCentre(centreId);
  sendSuccess(res, data, "Centre stats loaded");
}));

// Inactive centres — grace period countdown
hostRouter.get("/host/stats/inactive-centres", asyncHandler(async (_req, res) => {
  const data = await getInactiveCentres();
  sendSuccess(res, data, "Inactive centres loaded");
}));

// Per-centre caller + admin counts (for analytics dashboard)
hostRouter.get("/host/centre-users", asyncHandler(async (_req, res) => {
  const data = await getCentreUserCounts();
  sendSuccess(res, data, "Centre user counts loaded");
}));

// Cross-centre registration list — requires explicit permission on top of host role
hostRouter.get("/host/registrations", asyncHandler(async (req, res) => {
  const ctx = getRequestContext();
  if (!ctx.permissions.has("report_access") && !ctx.permissions.has("assign_calling_access")) {
    throw new ApiError(403, "report_access or assign_calling_access required for cross-centre data");
  }

  const centreId = req.query.centre_id ? Number(req.query.centre_id) : undefined;
  const page = req.query.page ? Number(req.query.page) : 1;
  const pageSize = Math.min(Number(req.query.page_size ?? 50), 200);

  const data = await getCrosscentreRegistrations({ centreId, page, pageSize });
  sendSuccess(res, data, "Cross-centre registrations loaded");
}));
