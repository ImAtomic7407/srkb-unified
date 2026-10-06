import { Router } from "express";
import { asyncHandler } from "../../lib/async-handler.js";
import { requireAnyPermission, requirePermission } from "../../middlewares/permissions.js";
import { validateBody, validateParams, validateQuery } from "../../middlewares/validate.js";
import {
  batchCallingDoneSchema,
  batchCallingMemberParamSchema,
  batchCallingQuerySchema,
  batchCreateSchema,
  batchDisableParamSchema,
  batchIdParamSchema,
  batchMoveRequestApproveSchema,
  batchMoveRequestIdParamSchema,
  batchMoveRequestQuerySchema,
  batchMoveRequestRejectSchema,
  batchQuerySchema,
  batchSendBackSchema,
} from "./batch.schema.js";
import {
  attendanceRosterHandler,
  batchCallingDoneHandler,
  batchCallingPoolHandler,
  batchCallingViewHandler,
  batchMembersHandler,
  batchMoveRequestApproveHandler,
  batchMoveRequestRejectHandler,
  batchMoveRequestsListHandler,
  batchSendBackHandler,
  createBatchHandler,
  disableBatchHandler,
  listBatchesHandler,
} from "./batch.controller.js";

export const batchRouter = Router();

batchRouter.post(
  "/batches",
  requirePermission("assign_calling_access"),
  validateBody(batchCreateSchema),
  asyncHandler(createBatchHandler),
);
batchRouter.get(
  "/batches",
  requirePermission("assign_calling_access"),
  validateQuery(batchQuerySchema),
  asyncHandler(listBatchesHandler),
);
batchRouter.patch(
  "/batches/:id/disable",
  requirePermission("assign_calling_access"),
  validateParams(batchDisableParamSchema),
  asyncHandler(disableBatchHandler),
);
batchRouter.get(
  "/batch-move-requests",
  requirePermission("assign_calling_access"),
  validateQuery(batchMoveRequestQuerySchema),
  asyncHandler(batchMoveRequestsListHandler),
);
batchRouter.post(
  "/batch-move-requests/:id/approve",
  requirePermission("assign_calling_access"),
  validateParams(batchMoveRequestIdParamSchema),
  validateBody(batchMoveRequestApproveSchema),
  asyncHandler(batchMoveRequestApproveHandler),
);
batchRouter.post(
  "/batch-move-requests/:id/reject",
  requirePermission("assign_calling_access"),
  validateParams(batchMoveRequestIdParamSchema),
  validateBody(batchMoveRequestRejectSchema),
  asyncHandler(batchMoveRequestRejectHandler),
);
batchRouter.get(
  "/batch-calling",
  requireAnyPermission(["batch_calling_access", "calling_access", "assign_calling_access"]),
  validateQuery(batchCallingQuerySchema),
  asyncHandler(batchCallingViewHandler),
);
batchRouter.post(
  "/batch-calling/:batch_member_id/done",
  requireAnyPermission(["batch_calling_access", "calling_access", "assign_calling_access"]),
  validateParams(batchCallingMemberParamSchema),
  validateBody(batchCallingDoneSchema),
  asyncHandler(batchCallingDoneHandler),
);
batchRouter.post(
  "/batch-calling/:batch_member_id/send-back",
  requireAnyPermission(["batch_calling_access", "calling_access", "assign_calling_access"]),
  validateParams(batchCallingMemberParamSchema),
  validateBody(batchSendBackSchema),
  asyncHandler(batchSendBackHandler),
);
batchRouter.get(
  "/batch-members",
  requirePermission("assign_calling_access"),
  asyncHandler(batchMembersHandler),
);
// Attendance-candidate roster for the whole centre — usable by anyone who marks attendance
// (callers/registerers), not just calling-assigners.
batchRouter.get(
  "/attendance-roster",
  requireAnyPermission([
    "my_attendance_access",
    "registration_access",
    "calling_access",
    "assign_calling_access",
  ]),
  asyncHandler(attendanceRosterHandler),
);
