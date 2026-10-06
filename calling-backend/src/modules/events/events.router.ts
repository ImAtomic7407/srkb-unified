// Real-time event stream (SSE). Clients open ONE long-lived connection here and receive
// push events like "dropdowns.changed" the instant another device makes a change.
//
// Auth note: the browser/WebView EventSource API cannot set an Authorization header, so this
// endpoint accepts the JWT either as a Bearer header (for header-capable SSE clients) OR as a
// `?token=` / `?access_token=` query param (for native EventSource). Verification is identical
// to the normal auth middleware (same shared secret, same user lookup + active/approval checks).

import { Router, type Request, type Response } from "express";
import { ApprovalStatus } from "@prisma/client";
import { prisma } from "../../config/prisma.js";
import { bypassCentreScope } from "../../lib/prisma-extension.js";
import { verifyToken } from "../../middlewares/auth.js";
import { registerSseClient } from "../../lib/sse.js";
import { logger } from "../../lib/logger.js";

export const eventsRouter = Router();

const extractToken = (req: Request): string | null => {
  const header = req.headers.authorization;
  if (header && header.startsWith("Bearer ")) {
    const t = header.slice(7).trim();
    if (t) return t;
  }
  const q = (req.query.token ?? req.query.access_token) as string | undefined;
  return q && q.trim() ? q.trim() : null;
};

// GET /events — open the SSE stream. Stays open until the client disconnects.
eventsRouter.get("/events", async (req: Request, res: Response) => {
  const token = extractToken(req);
  if (!token) {
    res.status(401).json({ success: false, message: "Missing token (Bearer header or ?token=)" });
    return;
  }

  let username: string;
  try {
    username = verifyToken(token);
  } catch {
    res.status(401).json({ success: false, message: "Invalid or expired token" });
    return;
  }

  const user = await bypassCentreScope(() =>
    prisma.user.findFirst({ where: { mobile: username }, select: { id: true, role: true, centreId: true, approvalStatus: true, isActive: true } }),
  );

  if (!user || (user.approvalStatus ?? ApprovalStatus.approved) !== ApprovalStatus.approved || user.isActive === false) {
    res.status(403).json({ success: false, message: "Account not active" });
    return;
  }

  logger.info(`sse_connect: user=${user.id} centre=${user.centreId ?? "none"}`);
  registerSseClient(res, {
    userId: user.id,
    centreId: user.centreId,
    isHost: user.role === "SUPER_ADMIN",
  });
});
