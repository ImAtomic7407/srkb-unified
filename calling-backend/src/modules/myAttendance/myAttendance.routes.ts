import { Router } from "express";
import { asyncHandler } from "../../lib/async-handler.js";
import { requirePermission } from "../../middlewares/permissions.js";
import { validateQuery } from "../../middlewares/validate.js";
import {
  proprietorshipQuerySchema,
  callingBatchesQuerySchema,
  callingSessionsQuerySchema,
  callingAttendedQuerySchema,
} from "./myAttendance.schema.js";
import {
  proprietorshipRosterHandler,
  callingBatchesHandler,
  callingSessionsHandler,
  callingAttendedHandler,
} from "./myAttendance.controller.js";

export const myAttendanceRouter = Router();

// View 2 - permanent roster
myAttendanceRouter.get(
  "/my-attendance/proprietorship",
  requirePermission("my_attendance_access"),
  validateQuery(proprietorshipQuerySchema),
  asyncHandler(proprietorshipRosterHandler),
);

// View 1 - by-calling scoreboard (batch -> session -> attended people)
myAttendanceRouter.get(
  "/my-attendance/calling/batches",
  requirePermission("my_attendance_access"),
  validateQuery(callingBatchesQuerySchema),
  asyncHandler(callingBatchesHandler),
);
myAttendanceRouter.get(
  "/my-attendance/calling/sessions",
  requirePermission("my_attendance_access"),
  validateQuery(callingSessionsQuerySchema),
  asyncHandler(callingSessionsHandler),
);
myAttendanceRouter.get(
  "/my-attendance/calling/attended",
  requirePermission("my_attendance_access"),
  validateQuery(callingAttendedQuerySchema),
  asyncHandler(callingAttendedHandler),
);