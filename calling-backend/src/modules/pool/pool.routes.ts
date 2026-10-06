import { Router } from "express";
import { asyncHandler } from "../../lib/async-handler.js";
import { requireAdminOrPermission } from "../../middlewares/permissions.js";
import { validateBody, validateQuery } from "../../middlewares/validate.js";
import {
  batchCallingAssignSchema,
  batchCallingPoolSchema,
  batchMembersPoolSchema,
  newRegistrationsPoolSchema,
  registeredByDateSchema,
  poolSessionsSchema,
  poolSummarySchema,
  sessionPeoplePoolSchema,
  sessionResponsesPoolSchema,
  remainingFollowupsPoolSchema,
} from "./pool.schema.js";
import {
  batchCallingAssignHandler,
  batchCallingPoolHandler,
  batchMembersPoolHandler,
  newRegistrationsPoolHandler,
  registeredByDateHandler,
  poolSessionsHandler,
  poolSummaryHandler,
  remainingFollowupsPoolHandler,
  sessionPeoplePoolHandler,
  sessionResponsesPoolHandler,
} from "./pool.controller.js";

export const poolRouter = Router();

poolRouter.get(
  "/pool/new-registrations",
  requireAdminOrPermission("assign_calling_access"),
  validateQuery(newRegistrationsPoolSchema),
  asyncHandler(newRegistrationsPoolHandler),
);
poolRouter.get(
  "/pool/registered-by-date",
  requireAdminOrPermission("assign_calling_access"),
  validateQuery(registeredByDateSchema),
  asyncHandler(registeredByDateHandler),
);
poolRouter.get(
  "/pool/remaining-followups",
  requireAdminOrPermission("assign_calling_access"),
  validateQuery(remainingFollowupsPoolSchema),
  asyncHandler(remainingFollowupsPoolHandler),
);
poolRouter.get(
  "/pool/batch-calling",
  requireAdminOrPermission("assign_calling_access"),
  validateQuery(batchCallingPoolSchema),
  asyncHandler(batchCallingPoolHandler),
);
poolRouter.get(
  "/pool/summary",
  requireAdminOrPermission("assign_calling_access"),
  validateQuery(poolSummarySchema),
  asyncHandler(poolSummaryHandler),
);
poolRouter.get(
  "/pool/batch-members",
  requireAdminOrPermission("assign_calling_access"),
  validateQuery(batchMembersPoolSchema),
  asyncHandler(batchMembersPoolHandler),
);
poolRouter.get(
  "/pool/session-responses",
  requireAdminOrPermission("assign_calling_access"),
  validateQuery(sessionResponsesPoolSchema),
  asyncHandler(sessionResponsesPoolHandler),
);
poolRouter.get(
  "/pool/session-people",
  requireAdminOrPermission("assign_calling_access"),
  validateQuery(sessionPeoplePoolSchema),
  asyncHandler(sessionPeoplePoolHandler),
);
poolRouter.get(
  "/pool/sessions",
  requireAdminOrPermission("assign_calling_access"),
  validateQuery(poolSessionsSchema),
  asyncHandler(poolSessionsHandler),
);
poolRouter.post(
  "/pool/batch-calling/assign",
  requireAdminOrPermission("assign_calling_access"),
  validateBody(batchCallingAssignSchema),
  asyncHandler(batchCallingAssignHandler),
);
