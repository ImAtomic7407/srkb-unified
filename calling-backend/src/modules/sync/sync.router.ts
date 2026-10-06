import { Router, type Request, type Response } from "express";
import { env } from "../../config/env.js";
import { asyncHandler } from "../../lib/async-handler.js";
import type { WebhookEvent } from "./sync.types.js";
import { existsSync } from "fs";
import {
  handleCentreCreated,
  handleCentreRenamed,
  handleCentreStatusChanged,
  handleCentreDeleted,
} from "./handlers/centre.handler.js";
import {
  handleAccountCreated,
  handleAccountApproved,
  handleAccessGranted,
  handleAccessRevoked,
} from "./handlers/user.handler.js";
import { runReconcile } from "./reconcile.js";
import { runAeAccessReconcile, getLastAeAccessReconcileSummary } from "./ae-access-reconcile.js";
import { journalWrite } from "../../lib/journal.js";
import { broadcastAeEvent } from "../../lib/ae-broadcast.js";
import {
  handleBatchCreated,
  handleBatchRenamed,
  handleBatchArchived,
  handleBatchCategorySet,
  handleBatchDefaultSet,
  handleSessionCreated,
  handleSessionDeleted,
  handleAttendancePersisted,
} from "./handlers/batch.handler.js";
import { markSessionAttendance } from "../sessionCampaign/sessionCampaign.service.js";
import { onRosterMember, ensureRegistrationForAttendance } from "../batch/batch-member.service.js";
import { rawPrisma } from "../../config/prisma.js";

export const syncRouter = Router();

const authInternal = (req: Request, res: Response, next: () => void) => {
  const token = req.header("x-internal-token");
  if (token !== env.internalToken) {
    res.status(401).json({ message: "Invalid internal token" });
    return;
  }
  next();
};

const eventHandlers: Partial<Record<string, (e: WebhookEvent) => Promise<void>>> = {
  "centre.created": handleCentreCreated,
  "centre.renamed": handleCentreRenamed,
  "centre.status_changed": handleCentreStatusChanged,
  "centre.deleted": handleCentreDeleted,
  "batch.created": handleBatchCreated,
  "batch.renamed": handleBatchRenamed,
  "batch.archived": handleBatchArchived,
  "batch.category_set": handleBatchCategorySet,
  "batch.default_set": handleBatchDefaultSet,
  "session.created": handleSessionCreated,
  "session.deleted": handleSessionDeleted,
  "attendance.persisted": handleAttendancePersisted,
  "session_attendance_recorded": async (e) => {
    const p = e.payload as { mobile: string; centre_id: number; ae_session_id: string };
    await markSessionAttendance({ aeSessionId: p.ae_session_id, mobile: p.mobile, centreId: p.centre_id });
  },
  // Roster Inject (AttendEase host page). Deliberately routed through onRosterMember,
  // NOT onAttendancePersisted: an import is not attendance. onRosterMember is purely
  // additive (no default-batch eviction), does NOT skip default batches — which is the
  // whole point, since a centre's main batch is usually the default one — and refuses
  // to re-add anyone at stage removed/archived.
  //
  // A centre-level inject has no batch to be a member of, so it can only ensure the
  // registration. That is enough: the pool is registration-based. Safe against
  // resurrecting struck-off people because ensureRegistrationForAttendance returns
  // early when ANY registration already exists, including a removed one.
  // `name` rides along in the payload because the person-directory lookup is a 60s-TTL cache:
  // someone written to AttendEase moments ago is absent from a cache built before they existed,
  // so a second import inside that window stored the bare phone number as their permanent
  // display name. AE knows the name at enqueue time — send it rather than re-derive it.
  "roster.injected": async (e) => {
    const p = e.payload as {
      mobile: string; centre_id: string; batch_id: number | null; category: string;
      name?: string | null;
    };
    const centre = await rawPrisma.centre.findUnique({ where: { code: p.centre_id } });
    if (!centre) return;
    if (p.batch_id) {
      await onRosterMember(centre.id, p.category, p.mobile, p.batch_id, "system:inject", p.name);
    } else {
      await ensureRegistrationForAttendance(centre.id, p.category, p.mobile, p.name);
    }
  },
  "registerer.account_created": handleAccountCreated,
  "registerer.account_approved": handleAccountApproved,
  "registerer.access_granted": handleAccessGranted,
  "registerer.access_revoked": handleAccessRevoked,
};

syncRouter.post(
  "/internal/attendease-event",
  authInternal,
  asyncHandler(async (req, res) => {
    if (existsSync("webhook-failed.lock")) {
      res.status(500).json({ error: "Simulated webhook receiver outage" });
      return;
    }
    const event = req.body as WebhookEvent;

    const handler = eventHandlers[event.event_type];
    if (handler) {
      // Fire-and-forget with error catch — AttendEase shouldn't retry (it
      // already has its own durable webhook outbox with retry on its side).
      handler(event)
        .then(() => {
          // Tell connected devices something moved, now that the mirror actually reflects it.
          // Deliberately AFTER the handler resolves: a phone that refetches before the write
          // landed would just re-read the old state and believe it was current. Fire-and-forget
          // and internally guarded — a fan-out failure must never affect ingestion, and the
          // client's 30-second poll remains the floor either way.
          void broadcastAeEvent(event);
          void journalWrite({
            direction: "AE->CB", connection: "webhook", kind: event.event_type, status: "success",
            entity: event.entity_id, payload: event, source: "sync.router./internal/attendease-event",
          });
        })
        .catch((err) => {
          console.error(`[sync] handler error for ${event.event_type}:`, err);
          void journalWrite({
            direction: "AE->CB", connection: "webhook", kind: event.event_type, status: "needs_attention",
            entity: event.entity_id,
            detail: "handler threw — AE considers this delivered (200 already returned) so it will NOT retry; replay this payload manually against the handler",
            payload: event, error: err, source: "sync.router./internal/attendease-event",
          });
        });
    } else {
      console.warn(`[sync] unknown event_type: ${event.event_type}`);
      void journalWrite({
        direction: "AE->CB", connection: "webhook", kind: event.event_type, status: "needs_attention",
        entity: event.entity_id, detail: "no handler registered for this event_type",
        payload: event, source: "sync.router./internal/attendease-event",
      });
    }

    // Always 200 immediately
    res.json({ received: true });
  }),
);

// POST /internal/trigger-reconcile — for simulation harness use only
syncRouter.post(
  "/internal/trigger-reconcile",
  authInternal,
  asyncHandler(async (_req, res) => {
    void runReconcile();
    res.json({ triggered: true });
  }),
);

// POST /internal/trigger-ae-access-reconcile — runs synchronously and returns the
// summary immediately (unlike the fire-and-forget trigger above) so it's usable
// both for testing and for an admin "check now" button.
syncRouter.post(
  "/internal/trigger-ae-access-reconcile",
  authInternal,
  asyncHandler(async (_req, res) => {
    const summary = await runAeAccessReconcile();
    res.json({ triggered: true, summary });
  }),
);

// GET /internal/ae-access-status — last reconcile run's result, for visibility
// into cross-system sync health without waiting for the next scheduled pass.
syncRouter.get(
  "/internal/ae-access-status",
  authInternal,
  asyncHandler(async (_req, res) => {
    res.json({ last_run: getLastAeAccessReconcileSummary() });
  }),
);
