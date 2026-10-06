import { Router } from "express";
import { asyncHandler } from "../../lib/async-handler.js";
import { requirePermission } from "../../middlewares/permissions.js";
import { validateBody, validateParams, validateQuery } from "../../middlewares/validate.js";
import {
  dropdownAdminListSchema,
  dropdownCreateSchema,
  dropdownIdParamSchema,
  dropdownListSchema,
  dropdownUpdateSchema,
} from "./dropdowns.schema.js";
import {
  createDropdownHandler,
  disableDropdownHandler,
  listDropdownsAdminHandler,
  listDropdownsHandler,
  revertDropdownHandler,
  updateDropdownHandler,
} from "./dropdowns.controller.js";

export const dropdownRouter = Router();

// Caller-facing list — global + centre-specific merged, active only
dropdownRouter.get("/dropdowns", validateQuery(dropdownListSchema), asyncHandler(listDropdownsHandler));

// Admin list — full visibility for management UI (includes centreId, inactive rows)
dropdownRouter.get(
  "/admin/dropdowns",
  requirePermission("dropdown_manage_access"),
  validateQuery(dropdownAdminListSchema),
  asyncHandler(listDropdownsAdminHandler),
);

dropdownRouter.post(
  "/dropdowns",
  requirePermission("dropdown_manage_access"),
  validateBody(dropdownCreateSchema),
  asyncHandler(createDropdownHandler),
);
dropdownRouter.put(
  "/dropdowns/:id",
  requirePermission("dropdown_manage_access"),
  validateParams(dropdownIdParamSchema),
  validateBody(dropdownUpdateSchema),
  asyncHandler(updateDropdownHandler),
);
dropdownRouter.patch(
  "/dropdowns/:id/disable",
  requirePermission("dropdown_manage_access"),
  validateParams(dropdownIdParamSchema),
  asyncHandler(disableDropdownHandler),
);

// Discard a centre's customisation of a shared option. DELETE, because it removes the centre's own
// override row — the shared option it pointed at is untouched.
dropdownRouter.delete(
  "/dropdowns/:id/override",
  requirePermission("dropdown_manage_access"),
  validateParams(dropdownIdParamSchema),
  asyncHandler(revertDropdownHandler),
);
