// Outbox helper — enqueue a cross-system write job.
// Worker is in outbox-worker.ts. All writes use rawPrisma (no ALS context needed).

import { rawPrisma } from "../config/prisma.js";

export type OutboxKind =
  | "sync_membership_to_attendease"  // notify AttendEase that CB memberships changed
  | "sync_password_to_attendease"    // propagate a password hash change to AttendEase
  | "create_failure_notification"    // create in-app notification when a job gives up
  | "delete_user_from_ae"            // remove user from AE when deleted in SRKB
  | "grant_ae_access"                // create AE account when attendanceSystemAccess toggled ON
  | "revoke_ae_access"               // remove AE centre_access when attendanceSystemAccess toggled OFF
  | "rename_user_in_ae"              // sync username change to AE
  | "create_centre_in_ae"            // immediately sync new centre to AE
  | "grant_centre_admin_in_ae"       // sync a centre-admin assignment made in SRKB to AE
  | "create_calling_session_in_ae"   // create a calling session in AE so registerer can mark attendance
  | "delete_calling_session_in_ae";  // delete a calling session from AE when removed from CB

// Backoff schedule (seconds): index = attempt number (0-based).
// After the last entry the cap is reused forever (until max attempts reached).
const BACKOFF_SECONDS = [1, 5, 30, 300, 1800]; // 1s, 5s, 30s, 5m, 30m
const BACKOFF_CAP_S = 1800; // 30m
export const MAX_ATTEMPTS = 10;

export const nextAttemptDate = (attempts: number): Date => {
  const delaySec = BACKOFF_SECONDS[attempts] ?? BACKOFF_CAP_S;
  return new Date(Date.now() + delaySec * 1000);
};

export const enqueueOutbox = async (
  kind: OutboxKind,
  payload: Record<string, unknown>,
): Promise<void> => {
  await rawPrisma.outbox.create({
    data: {
      kind,
      payload: JSON.stringify(payload),
      status: "pending",
      attempts: 0,
      nextAttempt: nextAttemptDate(0),
    },
  });
};
