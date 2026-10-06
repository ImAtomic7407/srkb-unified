// Outbox worker — polls for pending jobs and processes them with exponential backoff.
// Runs in-process on a setInterval. Safe to run concurrently (rows are claimed
// by status + nextAttempt check; SQLite serialises writes natively).
//
// Adding a new job kind: add a handler to HANDLERS below.

import { rawPrisma } from "../config/prisma.js";
import { MAX_ATTEMPTS, nextAttemptDate } from "./outbox.js";
import { env } from "../config/env.js";
import { createPasswordSyncFailedNotifications } from "../modules/notifications/notifications.service.js";
import { existsSync } from "fs";
import { journalWrite } from "./journal.js";

const jobEntity = (payload: OutboxPayload): string | null => {
  const p = payload as Record<string, unknown>;
  const val = p.username ?? p.ae_session_id ?? p.centre_code ?? p.centre_id;
  return val === undefined || val === null ? null : String(val);
};

const log = (msg: string) => console.log(`[outbox] ${msg}`);
const warn = (msg: string) => console.warn(`[outbox] ${msg}`);

// ── Handler registry ──────────────────────────────────────────────────────

type OutboxPayload = Record<string, unknown>;

// sync_membership_to_attendease — tell AttendEase to refresh its batch-member cache.
// AttendEase pulls from CB on demand, so this is a lightweight hint POST.
const handleSyncMembershipToAttendease = async (payload: OutboxPayload): Promise<void> => {
  const base = process.env.ATTENDEASE_BASE_URL ?? "http://127.0.0.1:6061";
  const res = await fetch(`${base}/api/internal/batch_members/invalidate_cache`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "x-internal-token": env.internalToken,
    },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(5000),
  });
  if (!res.ok) {
    throw new Error(`AttendEase responded ${res.status}`);
  }
};

// create_failure_notification — surface a failed outbox job as an in-app notification
// to host users so operators know something needs attention.
const handleCreateFailureNotification = async (payload: OutboxPayload): Promise<void> => {
  const hostUsers = await rawPrisma.user.findMany({
    where: { role: "SUPER_ADMIN", isActive: true },
    select: { id: true },
  });
  for (const u of hostUsers) {
    await rawPrisma.notification.create({
      data: {
        userId: u.id,
        title: "Outbox job failed",
        message: `Job "${payload.original_kind ?? "unknown"}" failed after ${MAX_ATTEMPTS} attempts. Payload: ${JSON.stringify(payload).slice(0, 200)}`,
        type: "batch_assigned", // closest available type; Phase 8 can add a dedicated outbox_failed type
      },
    });
  }
};

// sync_password_to_attendease — push a new password hash to AE after a CB-side change.
const handleSyncPasswordToAttendease = async (payload: OutboxPayload): Promise<void> => {
  const base = process.env.ATTENDEASE_BASE_URL ?? "http://127.0.0.1:6061";
  const res = await fetch(`${base}/api/internal/sync-password`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "x-internal-token": env.internalToken,
    },
    body: JSON.stringify({
      username: payload.username,
      password_hash: payload.password_hash,
      password_updated_at: payload.password_updated_at,
      must_change: payload.must_change ?? false,
    }),
    signal: AbortSignal.timeout(5000),
  });
  if (!res.ok) {
    throw new Error(`AttendEase responded ${res.status}`);
  }
  log(`INFO sync_password: direction=cb_to_ae username=${payload.username} result=success`);
};


// delete_user_from_ae — remove user from AE when deleted in SRKB
const handleDeleteUserFromAE = async (payload: OutboxPayload): Promise<void> => {
  const base = process.env.ATTENDEASE_BASE_URL ?? "http://127.0.0.1:6061";
  const res = await fetch(`${base}/api/internal/delete-user`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-internal-token": env.internalToken },
    body: JSON.stringify({ username: payload.username, occurred_at: payload.occurred_at }),
    signal: AbortSignal.timeout(5000),
  });
  if (!res.ok) throw new Error(`AttendEase responded ${res.status}`);
  log(`INFO sync: delete_user username=${payload.username} result=ok`);
};

// grant_ae_access — create AE account when attendanceSystemAccess is toggled ON
const handleGrantAEAccess = async (payload: OutboxPayload): Promise<void> => {
  const base = process.env.ATTENDEASE_BASE_URL ?? "http://127.0.0.1:6061";
  const res = await fetch(`${base}/api/internal/create-user`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-internal-token": env.internalToken },
    body: JSON.stringify({
      username: payload.username,
      name: payload.name,
      centre_code: payload.centre_code,
      role: "registerer",
      plain_password: payload.plain_password,
      occurred_at: payload.occurred_at,
    }),
    signal: AbortSignal.timeout(5000),
  });
  if (!res.ok) throw new Error(`AttendEase responded ${res.status}`);
  log(`INFO sync: grant_ae_access username=${payload.username} result=ok`);
};

// revoke_ae_access — remove AE centre_access when attendanceSystemAccess is toggled OFF
const handleRevokeAEAccess = async (payload: OutboxPayload): Promise<void> => {
  const base = process.env.ATTENDEASE_BASE_URL ?? "http://127.0.0.1:6061";
  const res = await fetch(`${base}/api/internal/revoke-ae-access`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-internal-token": env.internalToken },
    body: JSON.stringify({ username: payload.username, centre_code: payload.centre_code, occurred_at: payload.occurred_at }),
    signal: AbortSignal.timeout(5000),
  });
  if (!res.ok) throw new Error(`AttendEase responded ${res.status}`);
  log(`INFO sync: revoke_ae_access username=${payload.username} result=ok`);
};

// rename_user_in_ae — sync username change to AE
const handleRenameUserInAE = async (payload: OutboxPayload): Promise<void> => {
  const base = process.env.ATTENDEASE_BASE_URL ?? "http://127.0.0.1:6061";
  const res = await fetch(`${base}/api/internal/update-username`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-internal-token": env.internalToken },
    body: JSON.stringify({ old_username: payload.old_username, new_username: payload.new_username, occurred_at: payload.occurred_at }),
    signal: AbortSignal.timeout(5000),
  });
  if (!res.ok) throw new Error(`AttendEase responded ${res.status}`);
  log(`INFO sync: rename_user ${payload.old_username}→${payload.new_username} result=ok`);
};

// create_centre_in_ae — immediately sync new centre to AE (no 24h wait)
const handleCreateCentreInAE = async (payload: OutboxPayload): Promise<void> => {
  const base = process.env.ATTENDEASE_BASE_URL ?? "http://127.0.0.1:6061";
  const res = await fetch(`${base}/api/internal/create-centre`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-internal-token": env.internalToken },
    body: JSON.stringify({ centre_code: payload.centre_code, centre_name: payload.centre_name, occurred_at: payload.occurred_at }),
    signal: AbortSignal.timeout(5000),
  });
  if (!res.ok) throw new Error(`AttendEase responded ${res.status}`);
  log(`INFO sync: create_centre code=${payload.centre_code} result=ok`);
};

// grant_centre_admin_in_ae — sync a centre-admin assignment made in SRKB to AE.
// Previously nothing pushed this: assigning a centre admin in SRKB only ever
// updated SRKB's own UserCentreAccess row, so AE's centre_access table never
// learned about it and a direct AE login kept showing the user's old centre.
const handleGrantCentreAdminInAE = async (payload: OutboxPayload): Promise<void> => {
  const base = process.env.ATTENDEASE_BASE_URL ?? "http://127.0.0.1:6061";
  const res = await fetch(`${base}/api/internal/grant-centre-admin`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-internal-token": env.internalToken },
    body: JSON.stringify({
      username: payload.username,
      centre_code: payload.centre_code,
      display_name: payload.display_name ?? null,
      occurred_at: payload.occurred_at,
    }),
    signal: AbortSignal.timeout(5000),
  });
  if (!res.ok) throw new Error(`AttendEase responded ${res.status}`);
  log(`INFO sync: grant_centre_admin username=${payload.username} centre=${payload.centre_code} result=ok`);
};

// create_calling_session_in_ae — register a calling session in AttendEase so it appears
// in the registerer's Mark Attendance dropdown. This enables registrers to mark attendance
// for people in calling sessions.
const handleCreateCallingSessionInAE = async (payload: OutboxPayload): Promise<void> => {
  const base = process.env.ATTENDEASE_BASE_URL ?? "http://127.0.0.1:6061";
  const res = await fetch(`${base}/api/internal/create-calling-session`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-internal-token": env.internalToken },
    body: JSON.stringify({
      ae_session_id: payload.ae_session_id,
      centre_id: payload.centre_id,
      batch_id: payload.batch_id,
      display_name: payload.display_name,
      created_at: payload.created_at,
    }),
    signal: AbortSignal.timeout(5000),
  });
  if (!res.ok) throw new Error(`AttendEase responded ${res.status}`);
  log(`INFO sync: create_calling_session ae_session_id=${payload.ae_session_id} result=ok`);
};

// delete_calling_session_in_ae — remove a calling session from AttendEase when it's deleted in CB
const handleDeleteCallingSessionInAE = async (payload: OutboxPayload): Promise<void> => {
  const base = process.env.ATTENDEASE_BASE_URL ?? "http://127.0.0.1:6061";
  const res = await fetch(`${base}/api/internal/delete-calling-session`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-internal-token": env.internalToken },
    body: JSON.stringify({
      ae_session_id: payload.ae_session_id,
      centre_id: payload.centre_id,
    }),
    signal: AbortSignal.timeout(5000),
  });
  if (!res.ok) throw new Error(`AttendEase responded ${res.status}`);
  log(`INFO sync: delete_calling_session ae_session_id=${payload.ae_session_id} result=ok`);
};

const HANDLERS: Record<string, (p: OutboxPayload) => Promise<void>> = {
  sync_membership_to_attendease: handleSyncMembershipToAttendease,
  sync_password_to_attendease: handleSyncPasswordToAttendease,
  create_failure_notification: handleCreateFailureNotification,
  delete_user_from_ae: handleDeleteUserFromAE,
  grant_ae_access: handleGrantAEAccess,
  revoke_ae_access: handleRevokeAEAccess,
  rename_user_in_ae: handleRenameUserInAE,
  create_centre_in_ae: handleCreateCentreInAE,
  grant_centre_admin_in_ae: handleGrantCentreAdminInAE,
  create_calling_session_in_ae: handleCreateCallingSessionInAE,
  delete_calling_session_in_ae: handleDeleteCallingSessionInAE,
};

// ── Poll + process ─────────────────────────────────────────────────────────

const processOne = async (): Promise<void> => {
  const job = await rawPrisma.outbox.findFirst({
    where: { status: "pending", nextAttempt: { lte: new Date() } },
    orderBy: { nextAttempt: "asc" },
  });
  if (!job) return;

  const handler = HANDLERS[job.kind];
  const payload = JSON.parse(job.payload) as OutboxPayload;

  try {
    if (!handler) {
      // Unknown kind — mark failed immediately, don't retry
      warn(`unknown kind "${job.kind}" — marking failed`);
      await rawPrisma.outbox.update({
        where: { id: job.id },
        data: { status: "failed", lastError: `No handler for kind "${job.kind}"` },
      });
      void journalWrite({
        direction: "CB->AE", connection: "outbox", kind: job.kind, status: "needs_attention",
        entity: jobEntity(payload), detail: `job ${job.id}: no handler registered for this kind`,
        payload, source: "outbox-worker.processOne",
      });
      return;
    }

    await handler(payload);

    await rawPrisma.outbox.update({
      where: { id: job.id },
      data: { status: "sent", lastError: null },
    });
    log(`job ${job.id} (${job.kind}) sent — attempts=${job.attempts + 1}`);
    void journalWrite({
      direction: "CB->AE", connection: "outbox", kind: job.kind, status: "success",
      entity: jobEntity(payload), detail: `job ${job.id}`, payload,
      attempt: job.attempts + 1, source: "outbox-worker.processOne",
    });
  } catch (err) {
    const newAttempts = job.attempts + 1;
    const errMsg = err instanceof Error ? err.message : String(err);

    if (newAttempts >= MAX_ATTEMPTS) {
      warn(`job ${job.id} (${job.kind}) gave up after ${newAttempts} attempts: ${errMsg}`);
      await rawPrisma.outbox.update({
        where: { id: job.id },
        data: { status: "failed", attempts: newAttempts, lastError: errMsg },
      });
      void journalWrite({
        direction: "CB->AE", connection: "outbox", kind: job.kind, status: "needs_attention",
        entity: jobEntity(payload), detail: `job ${job.id}: gave up after ${newAttempts} attempts — replay payload against the same sync path to recover`,
        payload, error: errMsg, attempt: newAttempts, source: "outbox-worker.processOne",
      });
      // For password sync failures: targeted notification to centre admins + hosts.
      // For everything else: generic failure notification.
      try {
        if (job.kind === "sync_password_to_attendease") {
          const p = JSON.parse(job.payload) as OutboxPayload;
          await createPasswordSyncFailedNotifications({
            affectedUsername: String(p.username ?? "unknown"),
            direction: "cb_to_ae",
            lastError: errMsg,
          });
        } else {
          await rawPrisma.outbox.create({
            data: {
              kind: "create_failure_notification",
              payload: JSON.stringify({ original_kind: job.kind, original_id: job.id, error: errMsg }),
              status: "pending",
              attempts: 0,
              nextAttempt: new Date(),
            },
          });
        }
      } catch {
        // Non-fatal
      }
    } else {
      const next = nextAttemptDate(newAttempts);
      warn(`job ${job.id} (${job.kind}) attempt ${newAttempts} failed: ${errMsg} — retry at ${next.toISOString()}`);
      await rawPrisma.outbox.update({
        where: { id: job.id },
        data: { attempts: newAttempts, nextAttempt: next, lastError: errMsg },
      });
      void journalWrite({
        direction: "CB->AE", connection: "outbox", kind: job.kind, status: "failed",
        entity: jobEntity(payload), detail: `job ${job.id}: retrying at ${next.toISOString()}`,
        payload, error: errMsg, attempt: newAttempts, source: "outbox-worker.processOne",
      });
    }
  }
};

const tick = async (): Promise<void> => {
  try {
    if (existsSync("outbox-paused.lock")) {
      return;
    }
    // Drain all due jobs in one tick (up to 20 to avoid starvation)
    for (let i = 0; i < 20; i++) {
      const job = await rawPrisma.outbox.findFirst({
        where: { status: "pending", nextAttempt: { lte: new Date() } },
      });
      if (!job) break;
      await processOne();
    }
  } catch (err) {
    warn(`tick error: ${err instanceof Error ? err.message : String(err)}`);
  }
};

// ── Scheduler ─────────────────────────────────────────────────────────────

const POLL_INTERVAL_MS = 5_000;

export const startOutboxWorker = (): void => {
  log("outbox worker started (poll interval 5s)");
  setInterval(() => void tick(), POLL_INTERVAL_MS);
};
