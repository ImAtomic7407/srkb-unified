import { Router } from "express";
import { asyncHandler } from "../../lib/async-handler.js";
import { validateParams, validateQuery } from "../../middlewares/validate.js";
import {
  notificationIdParamSchema,
  notificationQuerySchema,
} from "./notifications.schema.js";
import {
  notificationReadHandler,
  notificationsListHandler,
} from "./notifications.controller.js";

export const notificationsRouter = Router();

notificationsRouter.get(
  "/notifications",
  validateQuery(notificationQuerySchema),
  asyncHandler(notificationsListHandler),
);
notificationsRouter.patch(
  "/notifications/:id/read",
  validateParams(notificationIdParamSchema),
  asyncHandler(notificationReadHandler),
);
