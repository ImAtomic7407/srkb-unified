import {
  ActiveQueue,
  AssignmentStatus,

  BatchMoveRequestStatus,
  FollowupStatus,
  MyAttendanceStatus,
  UserRole,
  Category,
  RegistrationStage,
  SessionCallStatus,
  SessionCampaignStatus,
} from "@prisma/client";
import type { Request } from "express";
import { prisma } from "../../config/prisma.js";
import { ApiError } from "../../lib/api-error.js";
import {
  getCentreStatsWithReconcile,
  statKey,
  type PerCategoryMetric,
} from "../../lib/projections.js";

type RequestUser = NonNullable<Request["user"]>;

const requireCentreId = (centreId: number | null | undefined) => {
  if (!centreId) {
    throw new ApiError(400, "Centre is not configured for this user");
  }

  return centreId;
};

const resolveDashboardScopeCentreId = (requester: RequestUser) => {
  if (requester.role === UserRole.SUPER_ADMIN) {
    return requester.centreId ?? null;
  }

  return requireCentreId(requester.centreId);
};

const countCallerRegistrations = async (userId: number, category: Category, centreId: number | null) => {
  return prisma.registration.count({
    where: {
      category,
      ...(centreId !== null ? { centreId } : {}),
      NOT: [
        { currentStage: RegistrationStage.removed },
        { currentStage: RegistrationStage.archived },
      ],
      OR: [
        { originalCallerId: userId },
        { firstConnectedBy: userId },
        { relationshipOwnerId: userId },
        { lastHandledBy: userId },
        { currentAssignedCallerId: userId },
      ],
    },
  });
};

export const getDashboardSummary = async ({
  category,
  requester,
}: {
  category: Category;
  requester: RequestUser;
}) => {
  const isAdmin = requester.role !== UserRole.CALLER;
  const scopeCentreId = resolveDashboardScopeCentreId(requester);

  // Fast path: a centre admin (or super-admin scoped to one centre) reads the live
  // read-model counters from centre_stats — one indexed lookup instead of 7 joined
  // COUNT(*)s. Cross-centre (scopeCentreId === null) and caller views fall through to
  // the source-of-truth queries below.
  if (isAdmin && scopeCentreId !== null) {
    const k = (m: PerCategoryMetric) => statKey(m, category);
    const s = await getCentreStatsWithReconcile(scopeCentreId, [
      k("new_registrations"),
      k("active_calling"),
      k("pending_followups"),
      k("batch_calling"),
      k("removed"),
      k("archived"),
      k("my_attendance"),
      k("registrations_total"),
    ]);
    const removedCount = s[k("removed")] ?? 0;
    const archivedCount = s[k("archived")] ?? 0;
    return {
      new_registrations: s[k("new_registrations")] ?? 0,
      active_calling: s[k("active_calling")] ?? 0,
      pending_followups: s[k("pending_followups")] ?? 0,
      batch_calling: s[k("batch_calling")] ?? 0,
      removed_count: removedCount,
      archived_count: archivedCount,
      my_attendance_count: s[k("my_attendance")] ?? 0,
      total_registrations: (s[k("registrations_total")] ?? 0) - removedCount - archivedCount,
    };
  }

  const [newRegistrations, activeCalling, pendingFollowups, batchCalling, removedCount, archivedCount, myAttendanceCount, registrationsCount, registrationsTotal] =
    await Promise.all([
      prisma.registration.count({
        where: {
          category,
          ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
          currentStage: RegistrationStage.registered,
          activeQueue: ActiveQueue.none,
          assignments: {
            none: {
              status: AssignmentStatus.active,
            },
          },
          NOT: [
            { currentStage: RegistrationStage.removed },
            { currentStage: RegistrationStage.archived },
          ],
        },
      }),
      prisma.callingAssignment.count({
        where: {
          status: AssignmentStatus.active,
          ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
          registration: {
            category,
            currentStage: RegistrationStage.calling_active,
            activeQueue: ActiveQueue.calling,
            ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
          },
        },
      }),
      prisma.followup.count({
        where: {
          status: FollowupStatus.active,
          ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
          registration: {
            category,
            currentStage: RegistrationStage.followup_active,
            activeQueue: ActiveQueue.followup,
            ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
          },
        },
      }),
      Promise.all([
        prisma.batchMoveRequest.count({
          where: {
            status: BatchMoveRequestStatus.pending,
            ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
            registration: {
              category,
              ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
            },
          },
        }),
        prisma.batchMember.count({
          where: {
            removedAt: null,
            ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
            batch: {
              category,
              status: "active",
            },
          },
        }),
      ]).then(([pendingRequests, activeBatchMembers]) => pendingRequests + activeBatchMembers),
      prisma.registration.count({
        where: {
          category,
          ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
          currentStage: RegistrationStage.removed,
        },
      }),
      prisma.registration.count({
        where: {
          category,
          ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
          currentStage: RegistrationStage.archived,
        },
      }),
      prisma.myAttendance.count({
        where: {
          registration: {
            category,
            ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
          },
          NOT: [
            { registration: { currentStage: RegistrationStage.removed } },
            { registration: { currentStage: RegistrationStage.archived } },
          ],
          ...(isAdmin ? {} : { ownerCallerId: requester.id }),
          status: MyAttendanceStatus.active,
        },
      }),
      isAdmin ? Promise.resolve(0) : countCallerRegistrations(requester.id, category, scopeCentreId),
      prisma.registration.count({
        where: {
          category,
          ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
        },
      }),
    ]);

  if (isAdmin) {
    return {
      new_registrations: newRegistrations,
      active_calling: activeCalling,
      pending_followups: pendingFollowups,
      batch_calling: batchCalling,
      removed_count: removedCount,
      archived_count: archivedCount,
      my_attendance_count: myAttendanceCount,
      total_registrations: registrationsTotal - removedCount - archivedCount,
    };
  }

  // "In My Queue" for a caller = their own pending session-call assignments in
  // still-active campaigns. The legacy `callingAssignment` table (activeCalling
  // above) is empty in the session-based model, so it always read 0 despite the
  // caller having real assigned calls — this counts the table that actually holds
  // the work (session_call_assignments), scoped to THIS caller.
  const assignedCallingSession = await prisma.sessionCallAssignment.count({
    where: {
      callerId: requester.id,
      removedAt: null,
      callStatus: SessionCallStatus.pending,
      batchMember: { category },
      campaign: {
        status: SessionCampaignStatus.active,
        ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
      },
    },
  });

  return {
    assigned_calling: assignedCallingSession,
    pending_followups: pendingFollowups,
    my_attendance_count: myAttendanceCount,
    batch_calling_count: batchCalling,
    registrations_count: registrationsCount,
  };
};
