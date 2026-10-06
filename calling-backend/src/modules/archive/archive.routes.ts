import { Router } from "express";
import { asyncHandler } from "../../lib/async-handler.js";
import { requireAnyPermission } from "../../middlewares/permissions.js";
import { validateQuery } from "../../middlewares/validate.js";
import { archiveListHandler } from "./archive.controller.js";
import { archiveQuerySchema } from "./archive.schema.js";

export const archiveRouter = Router();

archiveRouter.get(
  "/archive",
  requireAnyPermission(["assign_calling_access", "report_access"]),
  validateQuery(archiveQuerySchema),
  asyncHandler(archiveListHandler),
);
