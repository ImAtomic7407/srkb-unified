// Projections — the live read-model layer.
//
// Two-speed consistency model in this codebase:
//   • outbox.ts      → ASYNC, retried, CROSS-SYSTEM sync (AttendEase). Eventually consistent.
//   • projections.ts → SYNC, in-transaction, INTERNAL counters. Strongly consistent.
//
// Every counter the UI shows lives as a row in `centre_stats`. Domain writes bump
// these counters by a delta (+1 / -1) inside the SAME transaction as the write, so
// the read model can never disagree with the source data after a committed write.
// The UI then reads ONE indexed row instead of COUNT(*)-ing over thousands of records.
//
// Drift backstop: `reconcileCentre()` recomputes every key from source. It is the
// authoritative self-heal — run it on a schedule and on-demand when a key is missing.
//
// statKey convention: "<metric>" or "<metric>:<category>", e.g. "new_registrations:youth".

import {
  ActiveQueue,
  AssignmentStatus,
  Category,
  FollowupStatus,
  MyAttendanceStatus,
  RegistrationStage,
  type Prisma,
} from "@prisma/client";
import { rawPrisma, type ExtendedPrismaClient } from "../config/prisma.js";

// The interactive-transaction client of our EXTENDED Prisma client — i.e. the value
// passed to the `prisma.$transaction(async (tx) => …)` callback. Projection writes take
// this so they run inside the domain write's transaction (strong consistency). This is
// the extended client minus the methods Prisma forbids inside a transaction.
export type ProjectionClient = Omit<
  ExtendedPrismaClient,
  "$connect" | "$disconnect" | "$on" | "$transaction" | "$use" | "$extends"
>;

// ── Stat key registry ───────────────────────────────────────────────────────
// The canonical list of per-(centre,category) counters. Adding a counter = add a key
// here + a delta in events.ts + a recompute in reconcileCentre(). No migration needed.

export const CATEGORIES: Category[] = [Category.youth, Category.congregation];

// Metrics tracked per category (suffixed ":youth" / ":congregation").
export const PER_CATEGORY_METRICS = [
  "new_registrations", // stage=registered, queue=none, no active assignment
  "active_calling", // active calling assignments
  "pending_followups", // active followups
  "batch_calling", // pending batch-move requests + active batch members
  "removed", // stage=removed
  "archived", // stage=archived
  "my_attendance", // active my-attendance rows
  "registrations_total", // all registrations (any stage) in the category
] as const;

export type PerCategoryMetric = (typeof PER_CATEGORY_METRICS)[number];

export const statKey = (metric: PerCategoryMetric, category: Category): string =>
  `${metric}:${category}`;

// The minimal slice of a Prisma client that recompute needs: the source-table `count`
// methods (typed by their WhereInput so the predicates below stay checked) plus the
// `centre_stats` upsert. Defined structurally — NOT via Pick<> on a concrete client —
// because the EXTENDED transaction client (`tx`) and the base `rawPrisma` have
// mutually-incompatible delegate types under $extends. Both satisfy these simple shapes,
// so metric definitions live in ONE place and run either in-transaction (real-time, on the
// domain write's `tx`) or standalone on rawPrisma (reconcile).
type CountWhere<W> = { count: (args: { where: W }) => Promise<number> };
type CountClient = {
  registration: CountWhere<Prisma.RegistrationWhereInput>;
  callingAssignment: CountWhere<Prisma.CallingAssignmentWhereInput>;
  followup: CountWhere<Prisma.FollowupWhereInput>;
  batchMoveRequest: CountWhere<Prisma.BatchMoveRequestWhereInput>;
  batchMember: CountWhere<Prisma.BatchMemberWhereInput>;
  myAttendance: CountWhere<Prisma.MyAttendanceWhereInput>;
  centreStat: { upsert: (args: Prisma.CentreStatUpsertArgs) => Promise<unknown> };
};

// ── Read path ──────────────────────────────────────────────────────────────

/**
 * Read cached counters for a centre as a { statKey: value } map.
 * Uses rawPrisma — centre_stats is not centre-scoped by the extension, so we filter
 * explicitly. Missing keys are simply absent; callers default them to 0.
 */
export const getCentreStats = async (
  centreId: number,
  keys?: string[],
): Promise<Record<string, number>> => {
  const rows = await rawPrisma.centreStat.findMany({
    where: { centreId, ...(keys && keys.length ? { statKey: { in: keys } } : {}) },
    select: { statKey: true, value: true },
  });
  const out: Record<string, number> = {};
  for (const r of rows) out[r.statKey] = r.value;
  return out;
};

// ── Metric definitions (single source of truth) ──────────────────────────────
// Each metric is defined ONCE here and counted via the passed client, so the exact same
// query runs whether recomputing in-transaction (tx) or during standalone reconcile
// (rawPrisma). Counts are centre-scoped explicitly (centre_stats isn't auto-scoped).

const computeMetric = async (
  client: CountClient,
  centreId: number,
  category: Category,
  metric: PerCategoryMetric,
): Promise<number> => {
  switch (metric) {
    case "registrations_total":
      return client.registration.count({ where: { centreId, category } });

    case "new_registrations":
      return client.registration.count({
        where: {
          centreId,
          category,
          currentStage: RegistrationStage.registered,
          activeQueue: ActiveQueue.none,
          assignments: { none: { status: AssignmentStatus.active } },
        },
      });

    case "active_calling":
      return client.callingAssignment.count({
        where: {
          centreId,
          status: AssignmentStatus.active,
          registration: {
            category,
            currentStage: RegistrationStage.calling_active,
            activeQueue: ActiveQueue.calling,
          },
        },
      });

    case "pending_followups":
      return client.followup.count({
        where: {
          centreId,
          status: FollowupStatus.active,
          registration: {
            category,
            currentStage: RegistrationStage.followup_active,
            activeQueue: ActiveQueue.followup,
          },
        },
      });

    case "batch_calling": {
      const [pendingRequests, activeBatchMembers] = await Promise.all([
        client.batchMoveRequest.count({
          where: { centreId, status: "pending", registration: { category } },
        }),
        client.batchMember.count({
          where: { centreId, removedAt: null, batch: { category, status: "active" } },
        }),
      ]);
      return pendingRequests + activeBatchMembers;
    }

    case "removed":
      return client.registration.count({
        where: { centreId, category, currentStage: RegistrationStage.removed },
      });

    case "archived":
      return client.registration.count({
        where: { centreId, category, currentStage: RegistrationStage.archived },
      });

    case "my_attendance":
      return client.myAttendance.count({
        where: {
          centreId,
          status: MyAttendanceStatus.active,
          registration: {
            category,
            NOT: [
              { currentStage: RegistrationStage.removed },
              { currentStage: RegistrationStage.archived },
            ],
          },
        },
      });

    default: {
      const _exhaustive: never = metric;
      return _exhaustive;
    }
  }
};

// ── Recompute primitive (the heart of the event-driven update) ───────────────

/**
 * Recompute the given metrics (default: all) for one centre+category using `client`,
 * and write them to centre_stats. Pass a transaction client (`tx`) to update the read
 * model atomically with a domain write — the counts then see the write's own uncommitted
 * changes, so the cache is exact the instant the transaction commits. Pass `rawPrisma`
 * for standalone reconcile. This is correct-by-construction: it never does delta math.
 */
export const recomputeCentreCategory = async (
  client: CountClient,
  centreId: number,
  category: Category,
  metrics: readonly PerCategoryMetric[] = PER_CATEGORY_METRICS,
): Promise<Record<string, number>> => {
  const now = new Date();
  const written: Record<string, number> = {};
  for (const metric of metrics) {
    const value = await computeMetric(client, centreId, category, metric);
    const key = statKey(metric, category);
    written[key] = value;
    await client.centreStat.upsert({
      where: { centreId_statKey: { centreId, statKey: key } },
      create: { centreId, statKey: key, value, version: 1, reconciledAt: now },
      update: { value, version: { increment: 1 }, reconciledAt: now },
    });
  }
  return written;
};

/**
 * Recompute and overwrite ALL cached counters for a centre (both categories) from source.
 * The authoritative drift backstop — run on a schedule and on cold-read miss. Uses
 * rawPrisma (post-commit, separate connection); never on the user's read path.
 */
export const reconcileCentre = async (centreId: number): Promise<Record<string, number>> => {
  const written: Record<string, number> = {};
  for (const category of CATEGORIES) {
    Object.assign(written, await recomputeCentreCategory(rawPrisma, centreId, category));
  }
  return written;
};

/**
 * Audit a centre's cached counters against a fresh from-source recompute WITHOUT writing.
 * Returns one row per (metric, category) with cached vs fresh and whether they agree.
 * Use for drift detection / monitoring and in tests after exercising domain transitions.
 */
export const auditCentre = async (
  centreId: number,
): Promise<Array<{ key: string; cached: number; fresh: number; ok: boolean }>> => {
  const cached = await getCentreStats(centreId);
  const out: Array<{ key: string; cached: number; fresh: number; ok: boolean }> = [];
  for (const category of CATEGORIES) {
    for (const metric of PER_CATEGORY_METRICS) {
      const key = statKey(metric, category);
      const fresh = await computeMetric(rawPrisma, centreId, category, metric);
      const cachedVal = cached[key] ?? 0;
      out.push({ key, cached: cachedVal, fresh, ok: cachedVal === fresh });
    }
  }
  return out;
};

/**
 * Reconcile every active centre. The daily drift-backstop entry point — called from the
 * reconcile scheduler after mirror tables are refreshed. Returns count of centres healed.
 */
export const reconcileAllCentreStats = async (): Promise<number> => {
  const centres = await rawPrisma.centre.findMany({
    where: { isActive: true },
    select: { id: true },
  });
  for (const c of centres) {
    await reconcileCentre(c.id);
  }
  return centres.length;
};

/**
 * Read cached stats, reconciling first if any requested key is missing (cold cache).
 * This makes the read path correct even before every transition emits events.
 */
export const getCentreStatsWithReconcile = async (
  centreId: number,
  keys: string[],
): Promise<Record<string, number>> => {
  let stats = await getCentreStats(centreId, keys);
  const missing = keys.some((k) => !(k in stats));
  if (missing) {
    await reconcileCentre(centreId);
    stats = await getCentreStats(centreId, keys);
  }
  // Default any still-absent key to 0
  for (const k of keys) if (!(k in stats)) stats[k] = 0;
  return stats;
};
