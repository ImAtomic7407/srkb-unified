// AE-access reconciler — independent safety net for the CB→AE user-provisioning
// bridge (grant_ae_access / revoke_ae_access outbox jobs).
//
// Why this exists: on 2026-07-19 a direct-create endpoint (POST /admin/users)
// was found to silently skip enqueueing grant_ae_access entirely — four real
// callers were created with attendanceSystemAccess=true but never got an AE
// login, and nothing surfaced the gap because there was no failed job to see
// (no job was ever created). Outbox retries only handle delivery failures for
// jobs that exist; they can't catch a job that a code path forgot to enqueue.
// This reconciler closes that blind spot by periodically comparing CB's
// intended state against AE's actual state (read directly from AE's SQLite,
// same as reconcile.ts) and self-healing what it safely can.
//
// What it can and can't self-heal:
//   - Missing AE login entirely      -> creates a skeleton account (no password
//                                        available here) and flags it as still
//                                        needing a password reset to log in.
//   - AE login exists, centre_access missing/wrong -> fixed directly (idempotent,
//                                        doesn't touch the existing password).
//   - AE still has active access CB says should be gone -> revoked directly.
//   - A permanently-failed outbox job (retries exhausted) -> NOT re-attempted
//                                        automatically; surfaced in the summary
//                                        for a human to look at.
import { ApprovalStatus } from "@prisma/client";
import { rawPrisma } from "../../config/prisma.js";
import { env } from "../../config/env.js";
import { attendeaseRepo, type AEUserAccessRow } from "../../lib/attendease-db.js";
import { journalWrite } from "../../lib/journal.js";

const log = (msg: string) => console.log(`[ae-access-reconcile] ${msg}`);
const warn = (msg: string) => console.warn(`[ae-access-reconcile] WARN: ${msg}`);

export type AeAccessReconcileSummary = {
  ranAt: string;
  durationMs: number;
  checked: number;
  fixedCentreAccess: number;
  fixedSkeleton: number;
  needsPassword: string[];
  revoked: number;
  errors: { username: string; error: string }[];
  staleFailedJobs: { id: number; kind: string; lastError: string | null }[];
};

let lastSummary: AeAccessReconcileSummary | null = null;
export const getLastAeAccessReconcileSummary = () => lastSummary;

const aeBaseUrl = () => process.env.ATTENDEASE_BASE_URL ?? "http://127.0.0.1:6061";

const callAeCreateUser = async (payload: {
  username: string;
  name: string;
  centre_code: string;
  role: string;
}) => {
  const res = await fetch(`${aeBaseUrl()}/api/internal/create-user`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-internal-token": env.internalToken },
    body: JSON.stringify({ ...payload, occurred_at: new Date().toISOString() }),
    signal: AbortSignal.timeout(5000),
  });
  if (!res.ok) throw new Error(`create-user responded ${res.status}`);
};

const callAeRevoke = async (username: string, centreCode: string) => {
  const res = await fetch(`${aeBaseUrl()}/api/internal/revoke-ae-access`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-internal-token": env.internalToken },
    body: JSON.stringify({ username, centre_code: centreCode, occurred_at: new Date().toISOString() }),
    signal: AbortSignal.timeout(5000),
  });
  if (!res.ok) throw new Error(`revoke-ae-access responded ${res.status}`);
};

export const runAeAccessReconcile = async (): Promise<AeAccessReconcileSummary> => {
  const start = Date.now();
  const summary: AeAccessReconcileSummary = {
    ranAt: new Date().toISOString(),
    durationMs: 0,
    checked: 0,
    fixedCentreAccess: 0,
    fixedSkeleton: 0,
    needsPassword: [],
    revoked: 0,
    errors: [],
    staleFailedJobs: [],
  };

  if (!env.attendeaseDbPath) {
    warn("ATTENDEASE_DB_PATH not set — skipped");
    lastSummary = summary;
    return summary;
  }

  const aeUsers = new Map(attendeaseRepo.allUsers().map((u) => [u.username, u]));
  const aeAccessByUser = new Map<string, AEUserAccessRow[]>();
  for (const a of attendeaseRepo.allUserAccess()) {
    const list = aeAccessByUser.get(a.username) ?? [];
    list.push(a);
    aeAccessByUser.set(a.username, list);
  }

  // Direction 1: CB says this user should have AE access — verify AE agrees.
  const shouldHaveAccess = await rawPrisma.user.findMany({
    where: { attendanceSystemAccess: true, approvalStatus: ApprovalStatus.approved, isActive: true },
    include: { centre: true },
  });

  for (const u of shouldHaveAccess) {
    summary.checked++;
    if (!u.centre) continue;
    try {
      const aeUser = aeUsers.get(u.mobile);
      const accessRows = aeAccessByUser.get(u.mobile) ?? [];
      const hasCorrectAccess = accessRows.some((a) => a.centre_id === u.centre!.code);

      if (!aeUser) {
        await callAeCreateUser({ username: u.mobile, name: u.name, centre_code: u.centre.code, role: "registerer" });
        summary.fixedSkeleton++;
        summary.needsPassword.push(u.mobile);
        warn(`${u.mobile}: no AE login existed — created a skeleton account. Still needs a password reset before they can log in.`);
        void journalWrite({
          direction: "CB->AE", connection: "reconcile", kind: "ae_access_create_skeleton", status: "self_healed",
          entity: u.mobile, detail: "AE had no login at all — created skeleton, still needs a password reset to actually log in",
          payload: { username: u.mobile, name: u.name, centre_code: u.centre.code }, source: "ae-access-reconcile.runAeAccessReconcile",
        });
      } else if (!hasCorrectAccess) {
        await callAeCreateUser({ username: u.mobile, name: u.name, centre_code: u.centre.code, role: "registerer" });
        summary.fixedCentreAccess++;
        log(`${u.mobile}: fixed missing/incorrect centre_access for ${u.centre.code}`);
        void journalWrite({
          direction: "CB->AE", connection: "reconcile", kind: "ae_access_fix_centre_access", status: "self_healed",
          entity: u.mobile, detail: `fixed missing/incorrect centre_access for ${u.centre.code}`,
          payload: { username: u.mobile, name: u.name, centre_code: u.centre.code }, source: "ae-access-reconcile.runAeAccessReconcile",
        });
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      summary.errors.push({ username: u.mobile, error: msg });
      warn(`${u.mobile}: reconcile failed — ${msg}`);
      void journalWrite({
        direction: "CB->AE", connection: "reconcile", kind: "ae_access_check", status: "failed",
        entity: u.mobile, payload: { username: u.mobile, centre_code: u.centre?.code }, error: msg,
        source: "ae-access-reconcile.runAeAccessReconcile",
      });
    }
  }

  // Direction 2: AE has active access for a CB-known user who should no longer have it.
  const allCbUsers = await rawPrisma.user.findMany();
  const cbUsersByMobile = new Map(allCbUsers.map((u) => [u.mobile, u]));
  for (const [username, accessRows] of aeAccessByUser) {
    const cbUser = cbUsersByMobile.get(username);
    if (!cbUser) continue; // not a CB-managed account (e.g. a legacy AE-native registerer) — not our concern
    const shouldHave = cbUser.attendanceSystemAccess && cbUser.approvalStatus === ApprovalStatus.approved && cbUser.isActive;
    if (shouldHave) continue;
    for (const row of accessRows) {
      try {
        await callAeRevoke(username, row.centre_id);
        summary.revoked++;
        log(`${username}: revoked stale AE access for ${row.centre_id} (CB no longer grants it)`);
        void journalWrite({
          direction: "CB->AE", connection: "reconcile", kind: "ae_access_revoke", status: "self_healed",
          entity: username, detail: `revoked AE access for ${row.centre_id} — CB no longer grants it`,
          payload: { username, centre_id: row.centre_id }, source: "ae-access-reconcile.runAeAccessReconcile",
        });
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        summary.errors.push({ username, error: msg });
        warn(`${username}: revoke failed — ${msg}`);
        void journalWrite({
          direction: "CB->AE", connection: "reconcile", kind: "ae_access_revoke", status: "failed",
          entity: username, payload: { username, centre_id: row.centre_id }, error: msg,
          source: "ae-access-reconcile.runAeAccessReconcile",
        });
      }
    }
  }

  // Direction 3: visibility only — jobs that already exhausted retries need a human.
  const stuckJobs = await rawPrisma.outbox.findMany({
    where: { status: "failed", kind: { in: ["grant_ae_access", "revoke_ae_access", "delete_user_from_ae", "rename_user_in_ae"] } },
    orderBy: { id: "desc" },
    take: 20,
  });
  summary.staleFailedJobs = stuckJobs.map((j) => ({ id: j.id, kind: j.kind, lastError: j.lastError }));
  if (stuckJobs.length > 0) {
    warn(`${stuckJobs.length} permanently-failed AE-sync job(s) — manual attention needed:`);
    for (const j of stuckJobs) warn(`  job ${j.id} (${j.kind}): ${j.lastError}`);
  }

  summary.durationMs = Date.now() - start;
  lastSummary = summary;
  log(
    `done in ${summary.durationMs}ms — checked=${summary.checked} fixedCentreAccess=${summary.fixedCentreAccess} ` +
      `fixedSkeleton=${summary.fixedSkeleton} revoked=${summary.revoked} errors=${summary.errors.length} staleFailedJobs=${summary.staleFailedJobs.length}`,
  );
  if (summary.needsPassword.length) {
    warn(`accounts needing a password reset to actually log in: ${summary.needsPassword.join(", ")}`);
  }
  void journalWrite({
    direction: "internal", connection: "reconcile", kind: "ae_access_reconcile_run",
    status: summary.errors.length || summary.staleFailedJobs.length ? "needs_attention" : "success",
    detail: `checked=${summary.checked} fixedCentreAccess=${summary.fixedCentreAccess} fixedSkeleton=${summary.fixedSkeleton} revoked=${summary.revoked} errors=${summary.errors.length} staleFailedJobs=${summary.staleFailedJobs.length}`,
    payload: summary, source: "ae-access-reconcile.runAeAccessReconcile",
  });
  return summary;
};

// Runs every 3 minutes — this exists to catch a code path silently omitting a
// sync step (as happened 2026-07-19), so it needs to be frequent enough that a
// freshly-created gap doesn't sit invisible for long, while staying cheap since
// it only ever iterates over currently-AE-access-eligible users.
export const startAeAccessReconcileScheduler = () => {
  if (!env.attendeaseDbPath) {
    warn("ATTENDEASE_DB_PATH not set — reconcile scheduler disabled");
    return;
  }
  const INTERVAL_MS = 3 * 60 * 1000;
  setTimeout(() => {
    void runAeAccessReconcile();
    setInterval(() => void runAeAccessReconcile(), INTERVAL_MS);
  }, 15_000);
  log("scheduler registered (first run in 15s, then every 3min)");
};
