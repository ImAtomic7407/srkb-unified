import type { Request, Response } from "express";
import { sendSuccess } from "../../lib/response.js";
import {
  listNotifications,
  markNotificationRead,
} from "./notifications.service.js";

export const notificationsListHandler = async (req: Request, res: Response) => {
  const unreadOnlyValue = req.query.unread_only as unknown;
  const unreadOnly =
    unreadOnlyValue === true ||
    unreadOnlyValue === "true" ||
    unreadOnlyValue === "1";

  const items = await listNotifications(req.user!.id, unreadOnly);
  sendSuccess(res, { count: items.length, items }, "Notifications loaded");
};

export const notificationReadHandler = async (req: Request, res: Response) => {
  const updated = await markNotificationRead(Number(req.params.id), req.user!.id);

  if (!updated) {
    sendSuccess(res, { updated: false }, "Notification not found", 404);
    return;
  }

  sendSuccess(res, { updated: true }, "Notification marked as read");
};
