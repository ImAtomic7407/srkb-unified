import { Router } from "express";
import { asyncHandler } from "../../lib/async-handler.js";
import { requirePermission } from "../../middlewares/permissions.js";
import { validateBody, validateQuery } from "../../middlewares/validate.js";
import { followupDoneSchema, followupListSchema } from "./followup.schema.js";
import { followupDoneHandler, followupListHandler } from "./followup.controller.js";

export const followupRouter = Router();

followupRouter.get(
  "/followup-list",
  requirePermission("followup_access"),
  validateQuery(followupListSchema),
  asyncHandler(followupListHandler),
);
followupRouter.post(
  "/followup-done",
  requirePermission("followup_access"),
  validateBody(followupDoneSchema),
  asyncHandler(followupDoneHandler),
);
