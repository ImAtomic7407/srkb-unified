// Internal endpoints consumed by AttendEase (batch attendance screen + multi-select UI).
// Auth: x-internal-token header + IP allowlist (same guard as sync router).

import { Router, type Request, type Response, type NextFunction } from "express";
import { env } from "../../config/env.js";
import { rawPrisma } from "../../config/prisma.js";
import { asyncHandler } from "../../lib/async-handler.js";
import {
  getBatchMembers,
  getMembershipsByMobile,
  manualReplace,
  onAttendancePersisted,
} from "../batch/batch-member.service.js";
import { syncPasswordFromAE } from "../auth/auth.service.js";
import { createPasswordSyncFailedNotifications } from "../notifications/notifications.service.js";

const authInternal = (req: Request, res: Response, next: NextFunction) => {
  const token = req.header("x-internal-token");
  if (token !== env.internalToken) {
    res.status(401).json({ message: "Invalid internal token" });
    return;
  }
  next();
};

export const internalRouter = Router();

// GET /internal/resolve-centre?code=<centre_code>  — slug  -> CB integer PK
// GET /internal/resolve-centre?id=<centre_pk>      — CB PK -> slug
//
// The `id` direction exists because AttendEase's `centres` table is keyed by the slug and has no
// numeric column, so it cannot translate a CB PK on its own. Without it, an outbox job carrying
// only the integer centre id (the shape older CB builds enqueued) has no way to name the centre.
internalRouter.get(
  "/internal/resolve-centre",
  authInternal,
  asyncHandler(async (req: Request, res: Response) => {
    const code = String(req.query.code ?? "");
    const idRaw = String(req.query.id ?? "");
    if (!code && !idRaw) {
      res.status(400).json({ message: "code or id is required" });
      return;
    }
    const select = { id: true, name: true, code: true } as const;
    let centre = null;
    if (code) {
      centre = await rawPrisma.centre.findUnique({ where: { code }, select });
    } else {
      const id = Number(idRaw);
      if (!Number.isInteger(id) || id <= 0) {
        res.status(400).json({ message: "id must be a positive integer" });
        return;
      }
      centre = await rawPrisma.centre.findUnique({ where: { id }, select });
    }
    if (!centre) {
      res.status(404).json({ message: "Centre not found" });
      return;
    }
    res.json({ centre });
  }),
);

// GET /internal/batch-members?centre_id=&category=&batch_id=
internalRouter.get(
  "/internal/batch-members",
  authInternal,
  asyncHandler(async (req: Request, res: Response) => {
    const centreId = Number(req.query.centre_id);
    const category = String(req.query.category ?? "youth");
    const batchId = req.query.batch_id ? Number(req.query.batch_id) : undefined;

    if (!centreId || isNaN(centreId)) {
      res.status(400).json({ message: "centre_id is required and must be an integer" });
      return;
    }

    const members = await getBatchMembers(centreId, category, batchId);
    res.json({ members });
  }),
);

// GET /internal/batch-members/by-mobile?centre_id=&category=&mobile=
internalRouter.get(
  "/internal/batch-members/by-mobile",
  authInternal,
  asyncHandler(async (req: Request, res: Response) => {
    const centreId = Number(req.query.centre_id);
    const category = String(req.query.category ?? "youth");
    const mobile = String(req.query.mobile ?? "");

    if (!centreId || isNaN(centreId)) {
      res.status(400).json({ message: "centre_id is required and must be an integer" });
      return;
    }
    if (!mobile) {
      res.status(400).json({ message: "mobile is required" });
      return;
    }

    const members = await getMembershipsByMobile(centreId, category, mobile);
    res.json({ members });
  }),
);

// POST /internal/batch-members/manual-replace
// Body: { centre_id, category, mobile, batch_ids: number[], changed_by }
internalRouter.post(
  "/internal/batch-members/manual-replace",
  authInternal,
  asyncHandler(async (req: Request, res: Response) => {
    const { centre_id, category, mobile, batch_ids, changed_by } = req.body as {
      centre_id: number;
      category: string;
      mobile: string;
      batch_ids: number[];
      changed_by: string;
    };

    if (!centre_id || !category || !mobile || !Array.isArray(batch_ids) || !changed_by) {
      res.status(400).json({ message: "centre_id, category, mobile, batch_ids[], changed_by are required" });
      return;
    }

    await manualReplace(Number(centre_id), category, mobile, batch_ids, changed_by);
    res.json({ ok: true });
  }),
);

// POST /internal/batch-members/on-attendance
// Body: { centre_id, category, mobile, batch_id }
internalRouter.post(
  "/internal/batch-members/on-attendance",
  authInternal,
  asyncHandler(async (req: Request, res: Response) => {
    const { centre_id, category, mobile, batch_id } = req.body as {
      centre_id: number;
      category: string;
      mobile: string;
      batch_id: number;
    };

    if (!centre_id || !category || !mobile || !batch_id) {
      res.status(400).json({ message: "centre_id, category, mobile, batch_id are required" });
      return;
    }

    await onAttendancePersisted(Number(centre_id), category, mobile, Number(batch_id));
    res.json({ ok: true });
  }),
);

// POST /internal/auth/sync-password
// Receives a password hash from AttendEase after an AE-side password change.
// Body: { username, password_hash, password_updated_at, plain_password, must_change }
internalRouter.post(
  "/internal/auth/sync-password",
  authInternal,
  asyncHandler(async (req: Request, res: Response) => {
    const { username, password_hash, password_updated_at, plain_password, must_change } = req.body as {
      username: string;
      password_hash?: string | null;
      password_updated_at: string;
      plain_password?: string;
      must_change?: boolean;
    };
    if (!username || !password_updated_at || (!password_hash && !plain_password)) {
      res.status(400).json({ message: "username, password_updated_at, and either password_hash or plain_password are required" });
      return;
    }
    await syncPasswordFromAE(username, password_hash ?? null, password_updated_at, plain_password, must_change);
    res.json({ ok: true });
  }),
);

// POST /internal/notifications/password-sync-failed
// Called by AE when its AE→CB outbox job hits max retries.
// Body: { affected_username, direction, last_error }
internalRouter.post(
  "/internal/notifications/password-sync-failed",
  authInternal,
  asyncHandler(async (req: Request, res: Response) => {
    const { affected_username, direction, last_error } = req.body as {
      affected_username: string;
      direction: "ae_to_cb" | "cb_to_ae";
      last_error: string;
    };
    if (!affected_username || !direction) {
      res.status(400).json({ message: "affected_username and direction are required" });
      return;
    }
    await createPasswordSyncFailedNotifications({
      affectedUsername: affected_username,
      direction: direction === "ae_to_cb" ? "ae_to_cb" : "cb_to_ae",
      lastError: last_error ?? "unknown error",
    });
    res.json({ ok: true });
  }),
);
