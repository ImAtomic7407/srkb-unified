// Reconcile job — runs daily to correct any drift between AttendEase DB and mirror tables.
// Compares full entity lists from AttendEase's SQLite (read-only) against our mirror rows,
// upserts differences, and soft-marks deletions. Safe to run multiple times.

import { ApprovalStatus, CentreAllowedCategory, CentreType, UserRole } from "@prisma/client";
import { DEFAULT_PERMISSION_KEYS, OPTIONAL_PERMISSION_KEYS } from "../../utils/permissions.js";
import { prisma } from "../../config/prisma.js";
import { bypassCentreScope } from "../../lib/prisma-extension.js";
import { attendeaseRepo } from "../../lib/attendease-db.js";
import { env } from "../../config/env.js";
import { reconcileAllCentreStats, recomputeCentreCategory } from "../../lib/projections.js";
import {
  onAttendancePersisted,
  onRosterMember,
  ensureRegistrationForAttendance,
} from "../batch/batch-member.service.js";
import { journalWrite } from "../../lib/journal.js";
import { rawPrisma } from "../../config/prisma.js";
import { checkIntegrity } from "../../lib/integrity.js";

const log = (msg: string) => console.log(`[reconcile] ${msg}`);

const reconcileCentres = async () => {
  if (!env.attendeaseDbPath) return;
  const aeCentres = attendeaseRepo.allCentres();
  log(`centres: ${aeCentres.length} from AttendEase`);

  for (const c of aeCentres) {
    const isActive = c.status === "active";
    await bypassCentreScope(() =>
      prisma.centre.upsert({
        where: { code: c.centre_id },
        update: { name: c.name, attendeaseStatus: c.status, isActive, lastSyncedAt: new Date() },
        create: { code: c.centre_id, name: c.name, allowedCategories: CentreAllowedCategory.both, attendeaseStatus: c.status, isActive, lastSyncedAt: new Date() },
      }),
    );

    // Ensure every reconciled centre has a CentreConfig so Sheets sync can pick it up.
    // On first sync, attendanceSheetId is empty — an admin fills it in the management UI later.
    const upserted = await bypassCentreScope(() =>
      prisma.centre.findUnique({ where: { code: c.centre_id } }),
    );
    if (upserted) {
      await bypassCentreScope(() =>
        prisma.centreConfig.upsert({
          where: { centreId: upserted.id },
          update: {},
          create: {
            centreId: upserted.id,
            centreName: c.name,
            centreType: CentreType.youth_and_congregation,
            callingActiveDays: 7,
            attendanceSheetId: "",
            attendanceWebappUrl: "",
          },
        }),
      );
    }
  }
};

const seedDefaultPermissions = async (userId: number, role: UserRole) => {
  const keys = role === UserRole.CALLER
    ? [...DEFAULT_PERMISSION_KEYS]
    : [...DEFAULT_PERMISSION_KEYS, ...OPTIONAL_PERMISSION_KEYS];

  for (const permissionKey of keys) {
    await bypassCentreScope(() =>
      prisma.userPermission.upsert({
        where: { userId_permissionKey: { userId, permissionKey } },
        update: {},
        create: { userId, permissionKey, isEnabled: true },
      }),
    );
  }
};

const reconcileUsers = async () => {
  if (!env.attendeaseDbPath) return;
  const aeUsers = attendeaseRepo.allUsers();
  log(`users: ${aeUsers.length} from AttendEase`);

  for (const u of aeUsers) {
    const role = u.role === "host" ? UserRole.SUPER_ADMIN : UserRole.CALLER;
    const approvalStatus =
      u.status === "approved" ? ApprovalStatus.approved
      : u.status === "rejected" ? ApprovalStatus.rejected
      : ApprovalStatus.pending;

    const existing = await bypassCentreScope(() =>
      prisma.user.findUnique({ where: { mobile: u.username }, select: { id: true } }),
    );

    const user = await bypassCentreScope(() =>
      prisma.user.upsert({
        where: { mobile: u.username },
        update: { role, approvalStatus, isActive: u.status === "approved", attendanceSystemAccess: true },
        create: {
          name: u.username,
          mobile: u.username,
          role,
          approvalStatus,
          isActive: u.status === "approved",
          attendanceSystemAccess: true,
        },
      }),
    );

    if (!existing) {
      await seedDefaultPermissions(user.id, role);
    }
  }
};

const reconcileUserAccess = async () => {
  if (!env.attendeaseDbPath) return;
  const aeAccess = attendeaseRepo.allUserAccess();
  log(`user_centre_access: ${aeAccess.length} from AttendEase`);

  const runStart = new Date();

  for (const a of aeAccess) {
    await bypassCentreScope(async () => {
      const user = await prisma.user.findUnique({ where: { mobile: a.username } });
      const centre = await prisma.centre.findUnique({ where: { code: a.centre_id } });
      if (!user || !centre) return;

      // AttendEase labels centre admins as "admin" (not "centre_admin"); accept both.
      const isCentreAdmin = a.role === "centre_admin" || a.role === "admin" || a.role === "host";

      await prisma.userCentreAccess.upsert({
        where: { userId_centreId: { userId: user.id, centreId: centre.id } },
        update: { displayName: a.display_name ?? null, isAdmin: isCentreAdmin, lastSyncedAt: new Date() },
        create: { userId: user.id, centreId: centre.id, displayName: a.display_name ?? null, isAdmin: isCentreAdmin, lastSyncedAt: new Date() },
      });

      // Promote CALLER to ADMIN if they are a centre admin in AE (but never demote SUPER_ADMIN)
      if (isCentreAdmin && user.role === UserRole.CALLER) {
        await prisma.user.update({
          where: { id: user.id },
          data: { role: UserRole.ADMIN },
        });
        // User was first created as CALLER (default perms only); grant the optional
        // admin permission set (incl. assign_calling_access) now that they are ADMIN.
        await seedDefaultPermissions(user.id, UserRole.ADMIN);
      }
    });
  }

  // Remove access rows not touched this run — means access was revoked in AttendEase
  const deleted = await bypassCentreScope(() =>
    prisma.userCentreAccess.deleteMany({ where: { lastSyncedAt: { lt: runStart } } }),
  );
  if (deleted.count > 0) log(`user_centre_access: removed ${deleted.count} revoked rows`);
};

const reconcileBatches = async () => {
  if (!env.attendeaseDbPath) return;
  const aeBatches = attendeaseRepo.allBatches();
  log(`batches: ${aeBatches.length} from AttendEase`);
  const skipped: string[] = [];

  for (const b of aeBatches) {
    const centre = await bypassCentreScope(() =>
      prisma.centre.findUnique({ where: { code: b.centre_id } }),
    );
    if (!centre) continue;

    // Per-row isolation, same as the roster pass. `batch` has a unique key on
    // (centre_id, category, name) while this upsert is keyed on id, so the two can disagree:
    // if AE batch N carries a name that some OTHER CB batch already holds, the update raises
    // P2002. That is a real data divergence a human has to resolve (which batch is the right
    // one?), but it must NOT take the rest of the reconcile down with it — before this guard,
    // one such row stopped every later step from running at all, every single run.
    try {
      await bypassCentreScope(() =>
        prisma.batch.upsert({
          where: { id: b.id },
          update: { name: b.name, category: b.category, isDefault: !!b.is_default, status: b.status, lastSyncedAt: new Date() },
          create: { id: b.id, centreId: centre.id, name: b.name, category: b.category, isDefault: !!b.is_default, status: b.status, lastSyncedAt: new Date() },
        }),
      );
    } catch (err) {
      skipped.push(`${b.id}:"${b.name}"`);
      console.error(
        `[reconcile] batches: SKIPPED AE batch id=${b.id} "${b.name}" ` +
          `(centre ${b.centre_id}, ${b.category}): ${(err as Error).message}`,
      );
    }
  }
  if (skipped.length) {
    log(`batches: ${skipped.length} could not be mirrored and NEED A HUMAN: ${skipped.join(", ")}`);
    void journalWrite({
      direction: "AE->CB", connection: "reconcile", kind: "batch_mirror_conflict", status: "needs_attention",
      detail: `these AttendEase batches collide with an existing calling-backend batch on (centre, category, name) and were skipped: ${skipped.join(", ")}`,
      source: "reconcile.reconcileBatches",
    });
  }
};

const reconcileSessions = async () => {
  if (!env.attendeaseDbPath) return;
  const aeSessions = attendeaseRepo.allSessions();
  log(`sessions: ${aeSessions.length} from AttendEase`);

  for (const s of aeSessions) {
    const centre = await bypassCentreScope(() =>
      prisma.centre.findUnique({ where: { code: s.centre_id } }),
    );
    if (!centre) continue;

    const batch = await bypassCentreScope(() =>
      prisma.batch.findUnique({ where: { id: s.batch_id } }),
    );
    if (!batch) continue;

    // batchId/centreId belong in `update` as much as in `create`. Leaving them out pinned a
    // session to whatever batch it was FIRST mirrored under, forever: AttendEase moved
    // "9 Aug 2026" to batch 20 (congregation, "sunday 4 pm") and the mirror kept it on batch 18
    // (BG, youth). Every session dropdown is scoped to the batch in play, so a session parked on
    // a batch of the wrong category is unreachable from either category — invisible under
    // congregation because batch 18 is not a congregation batch, and invisible under youth
    // because the session's own category is congregation. The `if (!batch) continue` guard above
    // has already proved the batch row exists, so writing the id here cannot dangle.
    await bypassCentreScope(() =>
      prisma.session.upsert({
        where: { sessionId: s.session_id },
        update: { displayName: s.display_name ?? s.session_id, batchId: s.batch_id, centreId: centre.id, category: s.category, date: s.date ? new Date(s.date) : null, status: s.status, lastSyncedAt: new Date() },
        create: {
          sessionId: s.session_id,
          displayName: s.display_name ?? s.session_id,
          batchId: s.batch_id,
          centreId: centre.id,
          category: s.category,
          date: s.date ? new Date(s.date) : null,
          status: s.status,
          lastSyncedAt: new Date(),
        },
      }),
    );
  }
};

// Keeps batch_members (the CB's own domain table — the one actually read for the Pool
// Assignment batch filter and for session/pool call assignment, since it's the only source with
// real, assignable IDs) converging on AttendEase's CURRENT-SEASON attendance only.
// Only processes students who have attended active sessions in the current season — excludes
// historical attendance from archived/inactive sessions. This ensures consistency with
// AttendEase's current-season-only roster display and search.
const reconcileBatchMembers = async () => {
  if (!env.attendeaseDbPath) return;

  const centres = await bypassCentreScope(() => prisma.centre.findMany({ select: { id: true, code: true } }));
  const allBatches = attendeaseRepo.allBatches();
  const allSessions = attendeaseRepo.allSessions();
  let checked = 0;

  for (const centre of centres) {
    const centreBatches = allBatches.filter((b) => b.centre_id === centre.code && !b.is_default);
    if (centreBatches.length === 0) continue;
    const batchIdByName = new Map(centreBatches.map((b) => [b.name, b.id]));
    const batchById = new Map(centreBatches.map((b) => [b.id, b]));

    // ONLY consider active sessions (current season)
    const activeSessions = allSessions.filter((s) => s.centre_id === centre.code && s.status === "active");
    const activeSessionIds = new Set(activeSessions.map((s) => s.session_id));
    const sessionToBatch = new Map(activeSessions.map((s) => [s.session_id, s.batch_id]));

    const attendanceRows = attendeaseRepo.allAttendance({ centreId: centre.code });
    const seen = new Set<string>();

    for (const row of attendanceRows) {
      // Only include attendance from active sessions; skip historical attendance from archived sessions
      if (row.session_id && !activeSessionIds.has(row.session_id)) continue;

      const batchId =
        (row.session_id ? sessionToBatch.get(row.session_id) : undefined) ??
        (row.batch_name ? batchIdByName.get(row.batch_name) : undefined);
      if (batchId === undefined) continue;
      const batch = batchById.get(batchId);
      if (!batch) continue; // R3 only applies to non-default batches (already filtered above)

      const key = `${batch.category}:${row.mobile}:${batchId}`;
      if (seen.has(key)) continue;
      seen.add(key);
      checked++;
      await onAttendancePersisted(centre.id, batch.category, row.mobile, batchId, "system:reconcile");
    }
  }
  log(`batch_members: reconciled current-season only (${checked} membership checks across ${centres.length} centres)`);
};

// The attendance pass above can only ever see people who already turned up. AttendEase's
// centre_roster additionally holds imported prospects — sheet/WhatsApp imports of people who have
// NOT attended yet. They are the callers' actual work list, but until now nothing carried them
// into batch_members, so Pool Assignment's counts never moved after an import and none of them
// could be assigned. This pass closes that gap; onRosterMember is idempotent, so re-running is
// safe and existing members are untouched.
const reconcileRosterMembers = async () => {
  if (!env.attendeaseDbPath) return;

  const centres = await bypassCentreScope(() => prisma.centre.findMany({ select: { id: true, code: true } }));
  const allBatches = attendeaseRepo.allBatches();
  let added = 0;

  for (const centre of centres) {
    // Default batches included on purpose — see onRosterMember. The main batch a centre imports
    // into is usually the default one, so excluding it made this pass a no-op where it mattered most.
    const centreBatches = allBatches.filter(
      (b) => b.centre_id === centre.code && b.status === "active",
    );
    for (const batch of centreBatches) {
      const roster = attendeaseRepo.allRosterMembers({ centreId: centre.code, batchId: batch.id });
      for (const row of roster) {
        if (!row.mobile) continue;
        try {
          await onRosterMember(
            centre.id, batch.category, row.mobile, batch.id, "system:reconcile-roster", row.name,
          );
          added++;
        } catch {
          // one bad roster row must not abort the whole reconcile pass
        }
      }
    }

    // Centre-level roster rows (batch_id IS NULL) are invisible to the per-batch loop
    // above, so before this pass a centre-level import reached the calling backend
    // NEVER — not in 24h, not ever. There is no batch to be a member of, so all we can
    // do (and all that is needed, the pool being registration-based) is ensure the
    // registration exists.
    //
    // allRosterMembers({batchless}) additionally requires category IS NOT NULL, which
    // confines this to rows the Inject page wrote. The ~1,525 legacy centre-level rows
    // carry no category and are deliberately left alone: materialising them in one pass
    // would hand callers ~1,525 people as fresh never-called work.
    const centreLevel = attendeaseRepo.allRosterMembers({ centreId: centre.code, batchless: true });
    for (const row of centreLevel) {
      if (!row.mobile || !row.category) continue;
      try {
        await ensureRegistrationForAttendance(centre.id, row.category, row.mobile, row.name);
        added++;
      } catch {
        // one bad roster row must not abort the whole reconcile pass
      }
    }
  }
  log(`batch_members: roster pass complete (${added} roster rows checked)`);
};

/**
 * Repair registrations whose display name was never resolved — the self-healing half of the
 * name problem.
 *
 * A registration's name is chosen once, when the row is created, from whatever AttendEase knew
 * at that instant. That is often nothing: this system has no separate registration gate, so
 * people legitimately enter it before anyone has typed their name (a bulk roster import with the
 * number only, or an import that raced the 60s directory cache). The creating code path never
 * runs again for that person, so without this pass the placeholder is permanent and callers keep
 * seeing a bare phone number or "Unknown" forever, even long after AttendEase learns the name at
 * their first attendance.
 *
 * Strictly an UPGRADE: it only touches rows whose current name is a placeholder, and only when
 * AttendEase offers a real one. A name a human set is never overwritten, and a placeholder is
 * never written over a real name — so this is safe to run every day, forever, unattended.
 */
export const reconcileRegistrationNames = async () => {
  // Read the directory straight from AttendEase rather than through attendeasePersonNames(),
  // so a daily repair job can never be served a stale cached map.
  const directory = new Map<string, { name?: string; occupation?: string; address?: string }>();
  for (const p of attendeaseRepo.allPersons()) {
    const key = String(p.mobile ?? "").replace(/\D/g, "").slice(-10);
    if (!key) continue;
    directory.set(key, {
      name: p.name?.trim() || undefined,
      occupation: p.occupation?.trim() || undefined,
      address: p.address?.trim() || undefined,
    });
  }

  const isPlaceholder = (name: string | null | undefined, normalizedMobile: string): boolean => {
    const n = (name ?? "").trim().toLowerCase();
    return n === "" || n === "unknown" || n === normalizedMobile.trim().toLowerCase();
  };

  const regs = await bypassCentreScope(() =>
    prisma.registration.findMany({
      select: { id: true, name: true, normalizedMobile: true, occupation: true, address: true },
    }),
  );

  let repaired = 0;
  let detailsFilled = 0;
  let stillUnknown = 0;
  for (const r of regs) {
    const ae = directory.get(r.normalizedMobile.replace(/\D/g, "").slice(-10));
    const data: { name?: string; occupation?: string; address?: string } = {};

    if (isPlaceholder(r.name, r.normalizedMobile)) {
      if (ae?.name && !isPlaceholder(ae.name, r.normalizedMobile)) data.name = ae.name;
      else stillUnknown++;
    }
    // Blank-fill only — never overwrite details someone has already set.
    if (!(r.occupation ?? "").trim() && ae?.occupation) data.occupation = ae.occupation;
    if (!(r.address ?? "").trim() && ae?.address) data.address = ae.address;

    if (!Object.keys(data).length) continue;
    await bypassCentreScope(() =>
      prisma.registration.update({ where: { id: r.id }, data }),
    );
    if (data.name) repaired++;
    if (data.occupation || data.address) detailsFilled++;
  }
  log(
    `registration names: ${repaired} repaired from AttendEase, ${detailsFilled} had ` +
      `occupation/address filled, ${stillUnknown} still unnamed in both systems ` +
      `(will heal once AttendEase learns them)`,
  );
  return repaired;
};

/**
 * Run one reconcile step in isolation.
 *
 * THIS IS THE WHOLE POINT OF THE STRUCTURE BELOW — do not "tidy" the steps back into one big
 * try/catch. Until 2026-08-12 every step ran inside a single try, so the FIRST failure aborted
 * the entire run. `reconcileBatches` had been throwing P2002 on one colliding batch name on every
 * single run, which meant sessions, batch_members, roster_members, name repair and the
 * centre_stats drift backstop had NEVER executed — the daily self-healing job looked wired up and
 * scheduled while silently doing nothing past step 4 of 8. A self-healing mechanism that one bad
 * row can switch off is not self-healing.
 */
const STEP_FAILED = Symbol("reconcile step failed");

const step = async (name: string, fn: () => Promise<unknown>): Promise<unknown> => {
  try {
    return await fn();
  } catch (err) {
    console.error(`[reconcile] step "${name}" FAILED (continuing with the rest):`, err);
    void journalWrite({
      direction: "AE->CB", connection: "reconcile", kind: `reconcile_step:${name}`,
      status: "failed", error: err, source: "reconcile.step",
    });
    return STEP_FAILED;
  }
};

// ── Stage consistency ───────────────────────────────────────────────────────────────────────────
// Every other step here compares AttendEase against the mirror, and centre_stats recounts the
// dashboard FROM registrations.currentStage — so a wrong stage was faithfully counted, never
// questioned. Nothing checked that a person's stage agrees with the records that are supposed to
// justify it. On 2026-09-24 that let 25 people sit in followup_active for weeks with their
// follow-up closed, invisible to every Pool page (8 of 13 attendees at "2026-09-12 CTRA BG").
//
// The checks themselves live in lib/integrity.ts, shared with GET /integrity (shown to the admin on
// the Pool and Planned Sessions screens) and the hourly run below. Here: heal what is certain,
// journal the rest so it is on record even if nobody opens the app.
export const reconcileStageConsistency = async () => {
  const report = await checkIntegrity({ heal: true });
  const detail =
    `released ${report.healed} stuck in follow-up` +
    (report.issues.length
      ? "; " + report.issues.map((i) => `${i.code}=${i.count} [${i.examples.join("; ")}]`).join("; ")
      : "; no other issues");
  log(`stage_consistency: ${detail}`);
  if (report.healed || report.issues.length) {
    void journalWrite({
      direction: "AE->CB", connection: "reconcile", kind: "stage_consistency",
      status: report.issues.some((i) => i.severity === "error") ? "failed" : "success",
      detail, source: "reconcile.stageConsistency",
    });
  }
  return report.healed;
};

// Hourly, not only in the daily reconcile: a stuck person costs a session's worth of calling, so a
// day is too long to wait. Cheap — a handful of indexed counts on one SQLite file.
export const startIntegrityScheduler = () => {
  setInterval(() => void reconcileStageConsistency().catch((e) => console.error("[reconcile] integrity run failed", e)), 60 * 60 * 1000);
};

export const runReconcile = async () => {
  const start = Date.now();
  log("starting reconcile run");

  // Ordered, but independently fault-isolated: a step that throws is logged and skipped, and
  // every later step still runs.
  const STEPS: [string, () => Promise<unknown>][] = [
    ["centres", reconcileCentres],
    ["users", reconcileUsers],
    ["user_access", reconcileUserAccess],
    ["batches", reconcileBatches],
    ["sessions", reconcileSessions],
    ["batch_members", reconcileBatchMembers],
    ["roster_members", reconcileRosterMembers],
    // Heal display names that were never resolved when their registration was created.
    // After the roster passes, so anyone added moments ago is repaired in the same run.
    ["registration_names", reconcileRegistrationNames],
    // Before centre_stats, so the counters are recomputed from corrected stages.
    ["stage_consistency", reconcileStageConsistency],
    // Drift backstop for the live read-model counters: recompute centre_stats from source now
    // that the mirror tables are fresh.
    ["centre_stats", reconcileAllCentreStats],
  ];

  const results: Record<string, unknown> = {};
  const failed: string[] = [];
  for (const [name, fn] of STEPS) {
    const outcome = await step(name, fn);
    if (outcome === STEP_FAILED) failed.push(name);
    else results[name] = outcome;
  }
  log(`centre_stats recomputed for ${results.centre_stats ?? 0} active centres`);

  const durationMs = Date.now() - start;
  const summary =
    `${results.registration_names ?? 0} registration names repaired, ` +
    `${results.centre_stats ?? 0} centres' stats recomputed, ${durationMs}ms` +
    (failed.length ? ` — FAILED STEPS: ${failed.join(", ")}` : "");
  log(`reconcile done in ${durationMs}ms${failed.length ? ` (failed: ${failed.join(", ")})` : ""}`);
  void journalWrite({
    direction: "AE->CB", connection: "reconcile", kind: "full_mirror_reconcile",
    status: failed.length ? "failed" : "success",
    detail: summary, source: "reconcile.runReconcile",
  });
};

// Schedule to run once per day (24h interval). Kicks off once on startup after a short delay
// so the server finishes initialising before the first reconcile hits the DB.
export const startReconcileScheduler = () => {
  if (!env.attendeaseDbPath) {
    console.warn("[reconcile] ATTENDEASE_DB_PATH not set — reconcile job disabled");
    return;
  }
  const INTERVAL_MS = 24 * 60 * 60 * 1000;
  setTimeout(() => {
    void runReconcile();
    setInterval(() => void runReconcile(), INTERVAL_MS);
  }, 10_000);
  log("scheduler registered (first run in 10s)");
};
