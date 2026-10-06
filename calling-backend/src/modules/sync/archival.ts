// Grace-period archival worker (Phase 8).
// Runs daily. For each centre that has been inactive for >7 days,
// closes all in-flight work and marks the centre as archived.

import { prisma } from "../../config/prisma.js";
import { bypassCentreScope } from "../../lib/prisma-extension.js";
import { reconcileCentre } from "../../lib/projections.js";
import { ActiveQueue, AssignmentStatus, BatchCallingAssignmentStatus, FollowupStatus, MyAttendanceStatus, RegistrationStage } from "@prisma/client";

const GRACE_PERIOD_DAYS = 7;
const WARN_BEFORE_DAYS = 1; // notify this many days before archival
const ARCHIVED_PURGE_DAYS = 30; // auto-archived registrations are deleted after this many days

const log = (msg: string) => console.log(`[archival] ${msg}`);

const daysBetween = (a: Date, b: Date) =>
  Math.floor((b.getTime() - a.getTime()) / (1000 * 60 * 60 * 24));

// An auto-archived registration is NEVER hard-deleted. This function used to delete every
// registration whose RemovedRecord.removedAt was more than ARCHIVED_PURGE_DAYS old; it now only
// counts them, and the rows are retained permanently.
//
// Why, observed live on 2026-09-08: 84 Centre A people were auto-archived on 2026-08-08 by the
// unreachable threshold. For a month the daily reconcile correctly skipped them — onRosterMember
// refuses to re-import anyone at stage `removed` or `archived`. Then the 30-day window expired,
// this purge deleted all 84 ("purge: deleted 84/84"), and the next reconcile found AttendEase's
// centre_roster still listing them as active, found no registration to consult, and recreated all
// 84 as brand-new never-called work — with their 182 call attempts gone in the delete cascade.
//
// The archived registration row IS the tombstone. AttendEase's roster is a STANDING list that
// keeps naming people long after the calling side strikes them off, so the only thing stopping a
// re-import is the evidence that we already decided about this person. Deleting that evidence on a
// timer does not retire them, it launders them into new work. Retention is cheap (a struck-off row
// costs one row and is excluded from every pool query); resurrection is not.
//
// If disk ever genuinely becomes the problem, strip CHILD rows and keep the parent — never the
// reverse, and not call_attempts, which is the audit trail that makes incidents like this legible.
const reportExpiredArchived = async (now: Date): Promise<void> => {
  const cutoff = new Date(now.getTime() - ARCHIVED_PURGE_DAYS * 24 * 60 * 60 * 1000);
  const expired = await bypassCentreScope(() =>
    prisma.registration.count({
      where: {
        currentStage: RegistrationStage.archived,
        removedRecord: { is: { removedAt: { lt: cutoff } } },
      },
    }),
  );
  log(
    expired === 0
      ? "retention: no archived registrations past the 30-day window"
      : `retention: ${expired} archived registrations are past the ${ARCHIVED_PURGE_DAYS}-day window and are being RETAINED as strike-off tombstones (not deleted — see comment)`,
  );
};

const archiveCentre = async (centreId: number, centreName: string) => {
  log(`archiving centre ${centreName} (id=${centreId})`);

  // Close all active calling assignments
  await prisma.callingAssignment.updateMany({
    where: { centreId, status: AssignmentStatus.active },
    data: { status: AssignmentStatus.done },
  });

  // Close all active followups
  await prisma.followup.updateMany({
    where: { centreId, status: FollowupStatus.active },
    data: { status: FollowupStatus.closed },
  });

  // Close all my_attendance items
  await prisma.myAttendance.updateMany({
    where: { centreId, status: MyAttendanceStatus.active },
    data: { status: MyAttendanceStatus.closed },
  });

  // Close active batch calling assignments
  await prisma.batchCallingAssignment.updateMany({
    where: { centreId, status: BatchCallingAssignmentStatus.active },
    data: { status: BatchCallingAssignmentStatus.cancelled },
  });

  // Evict all active batch members (set removedAt)
  await prisma.batchMember.updateMany({
    where: { centreId, removedAt: null },
    data: { removedAt: new Date(), changedBy: "archival", changedAt: new Date() },
  });

  // Move all non-archived registrations to archived queue
  await prisma.registration.updateMany({
    where: {
      centreId,
      currentStage: { not: RegistrationStage.archived },
    },
    data: {
      currentStage: RegistrationStage.archived,
      activeQueue: ActiveQueue.archived,
    },
  });

  // Refresh the live read-model counters BEFORE the centre goes inactive: once isActive=false
  // the daily reconcileAllCentreStats skips this centre, so this is its last chance to be made
  // exact (everything now sits in `archived`). Done outside a transaction — archival is an
  // eventually-consistent maintenance job.
  await reconcileCentre(centreId);

  // Mark centre as archived
  await prisma.centre.update({
    where: { id: centreId },
    data: { isActive: false, status: "archived", attendeaseStatus: "archived" },
  });

  log(`centre ${centreName} archived — all in-flight work closed`);
};

const sendArchivalWarnings = async (centreId: number, centreName: string, daysLeft: number) => {
  // Idempotency: skip if we already sent a warning for this centre in the last 22 hours
  const recentWarning = await prisma.notification.findFirst({
    where: {
      title: { contains: `"${centreName}" archiving` },
      createdAt: { gte: new Date(Date.now() - 22 * 60 * 60 * 1000) },
    },
  });
  if (recentWarning) {
    log(`warning already sent for centre ${centreName} — skipping`);
    return;
  }

  // Find users with active work at this centre and notify them
  const activeUsers = await prisma.callingAssignment.findMany({
    where: { centreId, status: AssignmentStatus.active },
    select: { assignedToUserId: true },
    distinct: ["assignedToUserId"],
  });

  const followupUsers = await prisma.followup.findMany({
    where: { centreId, status: FollowupStatus.active },
    select: { ownerCallerId: true },
    distinct: ["ownerCallerId"],
  });

  const userIds = [
    ...activeUsers.map((a) => a.assignedToUserId),
    ...followupUsers.map((f) => f.ownerCallerId),
  ];
  const uniqueUserIds = [...new Set(userIds)];

  for (const userId of uniqueUserIds) {
    await prisma.notification.create({
      data: {
        userId,
        title: `Centre "${centreName}" archiving in ${daysLeft} day${daysLeft !== 1 ? "s" : ""}`,
        message: `The centre "${centreName}" has been inactive and will be archived in ${daysLeft} day${daysLeft !== 1 ? "s" : ""}. Please close out any open work.`,
        type: "attendance_matched", // reusing closest available type — extend enum if needed
        isRead: false,
      },
    });
  }

  log(`sent archival warnings to ${uniqueUserIds.length} users for centre ${centreName}`);
};

export const runArchivalCheck = async () => {
  const now = new Date();
  log("starting archival check");

  const candidates = await bypassCentreScope(() =>
    prisma.centre.findMany({
      where: {
        isActive: false,
        inactiveSince: { not: null },
        attendeaseStatus: { not: "archived" },
      },
    }),
  );

  for (const centre of candidates) {
    if (!centre.inactiveSince) continue;
    const daysSinceInactive = daysBetween(centre.inactiveSince, now);

    if (daysSinceInactive >= GRACE_PERIOD_DAYS) {
      await bypassCentreScope(() => archiveCentre(centre.id, centre.name));
    } else if (daysSinceInactive >= GRACE_PERIOD_DAYS - WARN_BEFORE_DAYS) {
      const daysLeft = GRACE_PERIOD_DAYS - daysSinceInactive;
      await bypassCentreScope(() => sendArchivalWarnings(centre.id, centre.name, daysLeft));
    }
  }

  // Report (do NOT delete) auto-archived registrations past the 30-day window — see the comment
  // on reportExpiredArchived for the 2026-09-08 resurrection this deletion caused.
  await reportExpiredArchived(now);

  log(`archival check done — checked ${candidates.length} inactive centres`);
};

export const startArchivalScheduler = () => {
  const INTERVAL_MS = 24 * 60 * 60 * 1000;
  setTimeout(() => {
    void runArchivalCheck();
    setInterval(() => void runArchivalCheck(), INTERVAL_MS);
  }, 30_000); // 30s after startup so reconcile runs first
  log("scheduler registered (first run in 30s)");
};
