import { Router } from "express";
import { asyncHandler } from "../../lib/async-handler.js";
import { requireAnyPermission, requirePermission } from "../../middlewares/permissions.js";
import { validateBody, validateParams, validateQuery } from "../../middlewares/validate.js";
import {
  registerSchema,
  registrationIdSchema,
  registrationListQuerySchema,
  profileSuggestionQuerySchema,
} from "./registration.schema.js";
import {
  listRegistrationsHandler,
  profileSuggestionHandler,
  register,
  registrationDetailsHandler,
  getBatchMembershipHandler,
  setBatchMembershipHandler,
} from "./registration.controller.js";
import { batchMembershipBodySchema } from "./registration.schema.js";

export const registrationRouter = Router();

// Cross-centre profile lookup — no permission gate (available to all authenticated users)
registrationRouter.get(
  "/registrations/profile-suggestion",
  validateQuery(profileSuggestionQuerySchema),
  asyncHandler(profileSuggestionHandler),
);

registrationRouter.post(
  "/register",
  requirePermission("registration_access"),
  validateBody(registerSchema),
  asyncHandler(register),
);
registrationRouter.get(
  "/registrations",
  requirePermission("registration_access"),
  validateQuery(registrationListQuerySchema),
  asyncHandler(listRegistrationsHandler),
);
registrationRouter.get(
  "/registrations/:registrationId/batch-membership",
  requireAnyPermission(["calling_access", "batch_calling_access", "assign_calling_access"]),
  validateParams(registrationIdSchema),
  asyncHandler(getBatchMembershipHandler),
);
registrationRouter.post(
  "/registrations/:registrationId/batch-membership",
  requireAnyPermission(["calling_access", "batch_calling_access", "assign_calling_access"]),
  validateParams(registrationIdSchema),
  validateBody(batchMembershipBodySchema),
  asyncHandler(setBatchMembershipHandler),
);
registrationRouter.get(
  "/registrations/:registrationId/details",
  requireAnyPermission([
    "calling_access",
    "followup_access",
    "my_attendance_access",
    "batch_calling_access",
    "assign_calling_access",
  ]),
  validateParams(registrationIdSchema),
  asyncHandler(registrationDetailsHandler),
);
