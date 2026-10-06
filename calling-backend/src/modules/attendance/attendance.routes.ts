import { Router } from "express";
import { asyncHandler } from "../../lib/async-handler.js";
import { requirePermission } from "../../middlewares/permissions.js";
import { validateBody, validateParams, validateQuery } from "../../middlewares/validate.js";
import {
  attendanceConfigSaveSchema,
  attendanceConfigTestSchema,
  attendanceSessionParamsSchema,
  attendanceSessionImportSchema,
  attendanceSessionPreviewQuerySchema,
  attendanceSessionsQuerySchema,
  attendanceSyncSchema,
} from "./attendance.schema.js";
import {
  attendanceConfigHandler,
  attendanceConfigSaveHandler,
  attendanceConfigTestHandler,
  attendanceConfigUnlockHandler,
  attendanceSessionImportHandler,
  attendanceSessionPreviewHandler,
  attendanceSessionsHandler,
  attendanceSyncHandler,
  attendanceSystemLinkHandler,
  sessionsForRegistrationHandler,
} from "./attendance.controller.js";

export const attendanceRouter = Router();

// Registerer session dropdown — grouped Called Session / Direct Attendance (last 4 each).
attendanceRouter.get(
  "/attendance/sessions/for-registration",
  requirePermission("registration_access"),
  validateQuery(attendanceSessionsQuerySchema),
  asyncHandler(sessionsForRegistrationHandler),
);

attendanceRouter.get(
  "/attendance/config",
  requirePermission("attendance_sync_access"),
  asyncHandler(attendanceConfigHandler),
);
attendanceRouter.post(
  "/attendance/config/test",
  requirePermission("attendance_sync_access"),
  validateBody(attendanceConfigTestSchema),
  asyncHandler(attendanceConfigTestHandler),
);
attendanceRouter.post(
  "/attendance/config/save-lock",
  requirePermission("attendance_sync_access"),
  validateBody(attendanceConfigSaveSchema),
  asyncHandler(attendanceConfigSaveHandler),
);
attendanceRouter.patch(
  "/attendance/config/unlock",
  requirePermission("attendance_sync_access"),
  asyncHandler(attendanceConfigUnlockHandler),
);
attendanceRouter.get(
  "/attendance/system-link",
  requirePermission("attendance_system_access"),
  asyncHandler(attendanceSystemLinkHandler),
);
attendanceRouter.get(
  "/attendance/sessions",
  requirePermission("attendance_sync_access"),
  validateQuery(attendanceSessionsQuerySchema),
  asyncHandler(attendanceSessionsHandler),
);
attendanceRouter.get(
  "/attendance/sessions/:id/preview",
  requirePermission("attendance_sync_access"),
  validateParams(attendanceSessionParamsSchema),
  validateQuery(attendanceSessionPreviewQuerySchema),
  asyncHandler(attendanceSessionPreviewHandler),
);
attendanceRouter.post(
  "/attendance/sessions/:id/import",
  requirePermission("attendance_sync_access"),
  validateParams(attendanceSessionParamsSchema),
  validateQuery(attendanceSessionPreviewQuerySchema),
  validateBody(attendanceSessionImportSchema),
  asyncHandler(attendanceSessionImportHandler),
);
attendanceRouter.post(
  "/attendance/sync",
  requirePermission("attendance_sync_access"),
  validateBody(attendanceSyncSchema),
  asyncHandler(attendanceSyncHandler),
);
