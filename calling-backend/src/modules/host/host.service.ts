// Host stats service — aggregates across ALL centres.
// All queries run under bypassCentreScope.
// Row-level cross-centre data requires cross_centre_data_access permission.

import {
  ActiveQueue,
  AssignmentStatus,
  BatchMoveRequestStatus,
  FollowupStatus,
  MyAttendanceStatus,
  RegistrationStage,
} from "@prisma/client";
import { prisma, rawPrisma } from "../../config/prisma.js";
import { bypassCentreScope } from "../../lib/prisma-extension.js";
import { ApiError } from "../../lib/api-error.js";

const GRACE_PERIOD_DAYS = 7;

const daysBetween = (a: Date, b: Date) =>
  Math.floor((b.getTime() - a.getTime()) / (1000 * 60 * 60 * 24));

// ── Per-centre counts helper ──────────────────────────────────────────────

const getCountsForCentre = async (centreId: number) => {
  const [registrations, activeCalling, followups, myAttendance, batchCalling, removed, archived, batchMembers] =
    await Promise.all([
      prisma.registration.count({
        where: { centreId, currentStage: RegistrationStage.registered, activeQueue: ActiveQueue.none },
      }),
      prisma.callingAssignment.count({ where: { centreId, status: AssignmentStatus.active } }),
      prisma.followup.count({ where: { centreId, status: FollowupStatus.active } }),
      prisma.myAttendance.count({ where: { centreId, status: MyAttendanceStatus.active } }),
      Promise.all([
        prisma.batchMoveRequest.count({ where: { centreId, status: BatchMoveRequestStatus.pending } }),
        prisma.batchMember.count({ where: { centreId, removedAt: null } }),
      ]).then(([a, b]) => a + b),
      prisma.registration.count({ where: { centreId, currentStage: RegistrationStage.removed } }),
      prisma.registration.count({ where: { centreId, currentStage: RegistrationStage.archived } }),
      // Batch members broken down by category
      rawPrisma.batchMember.groupBy({
        by: ["category"],
        where: { centreId, removedAt: null },
        _count: { id: true },
      }),
    ]);

  const batchMembersByCategory = Object.fromEntries(
    batchMembers.map((r) => [r.category, r._count.id]),
  );

  return { registrations, activeCalling, followups, myAttendance, batchCalling, removed, archived, batchMembersByCategory };
};

// ── Snapshot writer ────────────────────────────────────────────────────────

export const writeCentreStatsSnapshot = async (centreId: number, counts: Record<string, number>) => {
  const now = new Date();
  await rawPrisma.centreStatsSnapshot.createMany({
    data: Object.entries(counts).map(([statKey, statValue]) => ({
      centreId,
      snapshotAt: now,
      statKey,
      statValue,
    })),
  });
};

// ── Summary across all centres ────────────────────────────────────────────

export const getHostStatsSummary = async () => {
  const now = new Date();

  const fiveMinutesAgo = new Date(now.getTime() - 5 * 60 * 1000);
  const [activeCentres, inactiveCentres, stuckOutboxCount] = await Promise.all([
    bypassCentreScope(() =>
      prisma.centre.findMany({
        where: { isActive: true },
        select: { id: true, name: true, code: true, status: true },
        orderBy: { name: "asc" },
      }),
    ),
    bypassCentreScope(() =>
      prisma.centre.findMany({
        where: { isActive: false, inactiveSince: { not: null }, status: { not: "archived" } },
        select: { id: true, name: true, code: true, status: true, inactiveSince: true },
        orderBy: { inactiveSince: "asc" },
      }),
    ),
    // Outbox jobs stuck waiting more than 5 minutes — should always be 0
    rawPrisma.outbox.count({
      where: { status: "pending", nextAttempt: { lt: fiveMinutesAgo } },
    }),
  ]);

  const byCentre = await bypassCentreScope(async () => {
    return Promise.all(
      activeCentres.map(async (c) => {
        const counts = await getCountsForCentre(c.id);
        return { centreId: c.id, centreName: c.name, centreCode: c.code, status: c.status, ...counts };
      }),
    );
  });

  // Aggregate totals
  const totals = byCentre.reduce(
    (acc, c) => ({
      registrations: acc.registrations + c.registrations,
      activeCalling: acc.activeCalling + c.activeCalling,
      followups: acc.followups + c.followups,
      myAttendance: acc.myAttendance + c.myAttendance,
      batchCalling: acc.batchCalling + c.batchCalling,
      removed: acc.removed + c.removed,
      archived: acc.archived + c.archived,
    }),
    { registrations: 0, activeCalling: 0, followups: 0, myAttendance: 0, batchCalling: 0, removed: 0, archived: 0 },
  );

  // Grace-period countdown for inactive centres
  const gracePeriod = inactiveCentres.map((c) => ({
    centreId: c.id,
    centreName: c.name,
    centreCode: c.code,
    inactiveSince: c.inactiveSince,
    daysInactive: c.inactiveSince ? daysBetween(c.inactiveSince, now) : 0,
    daysUntilArchival: c.inactiveSince
      ? Math.max(0, GRACE_PERIOD_DAYS - daysBetween(c.inactiveSince, now))
      : GRACE_PERIOD_DAYS,
  }));

  return {
    totals,
    centres: byCentre,
    centreCount: activeCentres.length,
    gracePeriod,
    // Health signals — should both be 0 in normal operation
    stuckOutboxJobs: stuckOutboxCount,
    outboxWarning: stuckOutboxCount > 0,
  };
};

// ── Single centre detail ───────────────────────────────────────────────────

export const getHostStatsCentre = async (centreId: number) => {
  const centre = await bypassCentreScope(() =>
    prisma.centre.findUnique({
      where: { id: centreId },
      select: { id: true, name: true, code: true, isActive: true, status: true, attendeaseStatus: true, inactiveSince: true },
    }),
  );

  if (!centre) throw new ApiError(404, "Centre not found");

  const counts = await bypassCentreScope(() => getCountsForCentre(centreId));

  // Snapshot for history (non-blocking)
  writeCentreStatsSnapshot(centreId, {
    registrations: counts.registrations,
    activeCalling: counts.activeCalling,
    followups: counts.followups,
    myAttendance: counts.myAttendance,
    batchCalling: counts.batchCalling,
    removed: counts.removed,
    archived: counts.archived,
  }).catch(() => undefined);

  return { centre, ...counts };
};

// ── Per-centre user counts (callers + admins) ─────────────────────────────

export const getCentreUserCounts = async () => {
  const [centres, roleCounts] = await Promise.all([
    bypassCentreScope(() =>
      prisma.centre.findMany({
        where: { isActive: true },
        select: { id: true, name: true, code: true },
        orderBy: { name: "asc" },
      }),
    ),
    rawPrisma.$queryRaw<{ centre_id: number; role: string; cnt: bigint }[]>`
      SELECT uca.centre_id, u.role, COUNT(DISTINCT uca.user_id) AS cnt
      FROM user_centre_access uca
      JOIN users u ON u.id = uca.user_id
      WHERE u.role IN ('CALLER', 'ADMIN')
      GROUP BY uca.centre_id, u.role
    `,
  ]);

  return centres.map((c) => {
    const callerRow = roleCounts.find((r) => r.centre_id === c.id && r.role === "CALLER");
    const adminRow = roleCounts.find((r) => r.centre_id === c.id && r.role === "ADMIN");
    return {
      centreId: c.id,
      centreName: c.name,
      centreCode: c.code,
      callerCount: Number(callerRow?.cnt ?? 0),
      adminCount: Number(adminRow?.cnt ?? 0),
    };
  });
};

// ── Inactive-centres grace period list ────────────────────────────────────

export const getInactiveCentres = async () => {
  const now = new Date();
  const centres = await bypassCentreScope(() =>
    prisma.centre.findMany({
      where: { isActive: false, inactiveSince: { not: null } },
      select: { id: true, name: true, code: true, status: true, inactiveSince: true, attendeaseStatus: true },
      orderBy: { inactiveSince: "asc" },
    }),
  );

  return centres.map((c) => {
    const daysInactive = c.inactiveSince ? daysBetween(c.inactiveSince, now) : 0;
    return {
      centreId: c.id,
      centreName: c.name,
      centreCode: c.code,
      status: c.status,
      attendeaseStatus: c.attendeaseStatus,
      inactiveSince: c.inactiveSince,
      daysInactive,
      daysUntilArchival: Math.max(0, GRACE_PERIOD_DAYS - daysInactive),
      willBeArchivedAt: c.inactiveSince
        ? new Date(c.inactiveSince.getTime() + GRACE_PERIOD_DAYS * 24 * 60 * 60 * 1000)
        : null,
    };
  });
};

// ── Cross-centre registration list (requires cross_centre_data_access) ────

export const getCrosscentreRegistrations = async ({
  page = 1,
  pageSize = 50,
  centreId,
}: {
  page?: number;
  pageSize?: number;
  centreId?: number;
}) => {
  const skip = (page - 1) * pageSize;
  const where = centreId ? { centreId } : {};

  const [items, total] = await bypassCentreScope(() =>
    Promise.all([
      prisma.registration.findMany({
        where,
        include: { centre: { select: { name: true, code: true } } },
        orderBy: { createdAt: "desc" },
        skip,
        take: pageSize,
      }),
      prisma.registration.count({ where }),
    ]),
  );

  return { items, total, page, pageSize, pageCount: Math.ceil(total / pageSize) };
};
