import { Router } from "express";
import { asyncHandler } from "../../lib/async-handler.js";
import { requirePermission } from "../../middlewares/permissions.js";
import { validateBody, validateQuery } from "../../middlewares/validate.js";
import {
  assignCallingSchema,
  callingDoneSchema,
  categoryQuerySchema,
} from "./calling.schema.js";
import {
  assignCallingHandler,
  callingDoneHandler,
  callingListHandler,
} from "./calling.controller.js";

export const callingRouter = Router();

callingRouter.post(
  "/assign-calling",
  requirePermission("assign_calling_access"),
  validateBody(assignCallingSchema),
  asyncHandler(assignCallingHandler),
);
callingRouter.get(
  "/calling-list",
  requirePermission("calling_access"),
  validateQuery(categoryQuerySchema),
  asyncHandler(callingListHandler),
);
callingRouter.post(
  "/calling-done",
  requirePermission("calling_access"),
  validateBody(callingDoneSchema),
  asyncHandler(callingDoneHandler),
);
