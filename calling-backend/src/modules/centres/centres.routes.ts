import { Router, type NextFunction, type Request, type Response } from "express";
import { UserRole } from "@prisma/client";
import { asyncHandler } from "../../lib/async-handler.js";
import { ApiError } from "../../lib/api-error.js";
import { validateBody, validateParams } from "../../middlewares/validate.js";
import {
  centreCreateSchema,
  centreIdSchema,
  centreUpdateSchema,
} from "./centres.schema.js";
import {
  activateCentreHandler,
  createCentreHandler,
  deactivateCentreHandler,
  getCentreHandler,
  listCentresHandler,
  updateCentreHandler,
} from "./centres.controller.js";

const requireSuperAdmin = (req: Request, _res: Response, next: NextFunction) => {
  if (!req.user) {
    return next(new ApiError(401, "Unauthenticated request"));
  }

  if (req.user.role !== UserRole.SUPER_ADMIN) {
    return next(new ApiError(403, "Missing role: SUPER_ADMIN"));
  }

  return next();
};

export const centresRouter = Router();

centresRouter.get("/centres", requireSuperAdmin, asyncHandler(listCentresHandler));
centresRouter.post(
  "/centres",
  requireSuperAdmin,
  validateBody(centreCreateSchema),
  asyncHandler(createCentreHandler),
);
centresRouter.get(
  "/centres/:id",
  requireSuperAdmin,
  validateParams(centreIdSchema),
  asyncHandler(getCentreHandler),
);
centresRouter.patch(
  "/centres/:id",
  requireSuperAdmin,
  validateParams(centreIdSchema),
  validateBody(centreUpdateSchema),
  asyncHandler(updateCentreHandler),
);
centresRouter.patch(
  "/centres/:id/activate",
  requireSuperAdmin,
  validateParams(centreIdSchema),
  asyncHandler(activateCentreHandler),
);
centresRouter.patch(
  "/centres/:id/deactivate",
  requireSuperAdmin,
  validateParams(centreIdSchema),
  asyncHandler(deactivateCentreHandler),
);
