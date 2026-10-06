import {
  ActiveQueue,
  AssignmentSource,
  AssignmentStatus,
  CallType,
  Category,
  DropdownAction,
  DropdownCategoryScope,
  DropdownContext,
  FollowupStatus,
  RegistrationStage,
  NotificationType,
  UserRole,
} from "@prisma/client";
import type { Request } from "express";
import { prisma } from "../../config/prisma.js";
import { ApiError } from "../../lib/api-error.js";
import { buildOwnershipPatch } from "../../utils/ownership.js";
import { resolveCallingOption } from "../../lib/resolve-calling-option.js";
import { enforceConsecutiveRemoval, type ThresholdOption } from "../../lib/unreachable-threshold.js";
import { applyDeclaredRemoval, isRemovalAction } from "../../lib/declared-removal.js";
import { logger } from "../../lib/logger.js";
import { createNotification } from "../notifications/notifications.service.js";
import { applyDomainEvent } from "../../lib/events.js";
import { buildPeopleContext, emptyPersonContext } from "../caller/caller-context.service.js";
import { normalizeMobile } from "../../utils/mobile.js";
import { getRequestContext } from "../../lib/request-context.js";

type RequestUser = NonNullable<Request["user"]>;

const isActiveCallingAssignment = (registration: {
  currentStage: RegistrationStage;
  activeQueue: ActiveQueue;
}) => registration.currentStage === RegistrationStage.calling_active && registration.activeQueue === ActiveQueue.calling;

const requireCentreId = (centreId: number | null | undefined) => {
  if (!centreId) {
    throw new ApiError(400, "Centre is not configured for this user");
  }

  return centreId;
};

// Uses the per-request active centre (x-centre-id header, validated against UserCentreAccess by
// the resolveCentre middleware) rather than requester.centreId (the user's static "home centre" —
// same bug class already fixed in users.service.ts's resolveCentreScope: a caller/admin who
// switches centres in the app must be scoped to THAT centre, not wherever they originally logged
// in under).
const resolveCallingScopeCentreId = (requester: RequestUser) => {
  if (requester.role === UserRole.SUPER_ADMIN) {
    return getRequestContext().centreId ?? requester.centreId ?? null;
  }

  return requireCentreId(getRequestContext().centreId ?? requester.centreId);
};

const buildCallingTransition = ({
  action,
  actorId,
  registration,
}: {
  action: DropdownAction;
  actorId: number;
  registration: {
    currentStage: RegistrationStage;
    activeQueue: ActiveQueue;
    currentBatchCallerId: number | null;
    originalCallerId: number | null;
    firstConnectedBy: number | null;
    relationshipOwnerId: number | null;
    lastHandledBy: number | null;
    currentAssignedCallerId: number | null;
    earlierManagedBy: number | null;
  };
}) => {
  const ownershipPatch = buildOwnershipPatch(registration, actorId);

  switch (action) {
    case DropdownAction.move_to_followup:
      return {
        stage: RegistrationStage.followup_active,
        queue: ActiveQueue.followup,
        ownershipPatch,
        closeCallingAssignment: true,
        createFollowup: true,
        createRemovedRecord: false,
      } as const;
    case DropdownAction.continue_calling:
    // A deferral leaves the person exactly where they are — still a calling-stage registrant, still
    // owned by the same caller. The only difference is the snoozeUntil date stamped on their batch
    // membership, which hides them from the queue until it passes. Grouped here so it can never
    // fall through to the default and silently change someone's stage.
    case DropdownAction.defer_calling:
    case DropdownAction.reassign_calling:
    case DropdownAction.assign_to_online_team:
    case DropdownAction.keep_in_batch_calling:
      return {
        stage: RegistrationStage.calling_active,
        queue: ActiveQueue.calling,
        ownershipPatch,
        closeCallingAssignment: false,
        createFollowup: false,
        createRemovedRecord: false,
      } as const;
    case DropdownAction.stay_in_followup:
    case DropdownAction.pause_personal_followup:
      return {
        stage: RegistrationStage.followup_active,
        queue: ActiveQueue.followup,
        ownershipPatch,
        closeCallingAssignment: false,
        createFollowup: false,
        createRemovedRecord: false,
      } as const;
    case DropdownAction.remove_from_running:
    case DropdownAction.mark_invalid:
      return {
        stage: RegistrationStage.removed,
        queue: ActiveQueue.removed,
        ownershipPatch,
        closeCallingAssignment: true,
        createFollowup: false,
        createRemovedRecord: true,
      } as const;
    case DropdownAction.archive:
    case DropdownAction.close_from_my_attendance:
      return {
        stage: RegistrationStage.archived,
        queue: ActiveQueue.archived,
        ownershipPatch,
        closeCallingAssignment: true,
        createFollowup: false,
        createRemovedRecord: false,
      } as const;
    case DropdownAction.restore:
      return {
        stage: RegistrationStage.registered,
        queue: ActiveQueue.none,
        ownershipPatch,
        closeCallingAssignment: true,
        createFollowup: false,
        createRemovedRecord: false,
      } as const;
    case DropdownAction.send_back_to_original_caller:
      return {
        stage: RegistrationStage.sent_back_to_owner,
        queue: ActiveQueue.none,
        ownershipPatch: {
          ...ownershipPatch,
          currentAssignedCallerId: registration.originalCallerId ?? actorId,
        },
        closeCallingAssignment: true,
        createFollowup: false,
        createRemovedRecord: false,
      } as const;
    case DropdownAction.request_move_to_batch:
    case DropdownAction.move_to_batch_calling:
    case DropdownAction.move_to_another_batch:
      return {
        stage: RegistrationStage.batch_calling,
        queue: ActiveQueue.batch_calling,
        ownershipPatch: {
          ...ownershipPatch,
          currentBatchCallerId: actorId,
        },
        closeCallingAssignment: true,
        createFollowup: false,
        createRemovedRecord: false,
      } as const;
    default:
      return {
        stage: registration.currentStage,
        queue: registration.activeQueue,
        ownershipPatch,
        closeCallingAssignment: false,
        createFollowup: false,
        createRemovedRecord: false,
      } as const;
  }
};

const buildFollowupTransition = ({
  action,
  actorId,
  registration,
}: {
  action: DropdownAction;
  actorId: number;
  registration: {
    currentStage: RegistrationStage;
    activeQueue: ActiveQueue;
    originalCallerId: number | null;
    firstConnectedBy: number | null;
    relationshipOwnerId: number | null;
    lastHandledBy: number | null;
    currentAssignedCallerId: number | null;
    currentBatchCallerId: number | null;
    earlierManagedBy: number | null;
  };
}) => {
  const ownershipPatch = buildOwnershipPatch(registration, actorId);

  switch (action) {
    case DropdownAction.stay_in_followup:
    case DropdownAction.continue_calling:
    case DropdownAction.pause_personal_followup:
      return {
        stage: RegistrationStage.followup_active,
        queue: ActiveQueue.followup,
        ownershipPatch,
      } as const;
    case DropdownAction.send_back_to_original_caller:
      return {
        stage: RegistrationStage.sent_back_to_owner,
        queue: ActiveQueue.none,
        ownershipPatch: {
          ...ownershipPatch,
          currentAssignedCallerId: registration.originalCallerId ?? actorId,
        },
      } as const;
    case DropdownAction.archive:
    case DropdownAction.close_from_my_attendance:
      return {
        stage: RegistrationStage.archived,
        queue: ActiveQueue.archived,
        ownershipPatch,
      } as const;
    case DropdownAction.restore:
      return {
        stage: RegistrationStage.registered,
        queue: ActiveQueue.none,
        ownershipPatch,
      } as const;
    default:
      return {
        stage: registration.currentStage,
        queue: registration.activeQueue,
        ownershipPatch,
      } as const;
  }
};

const expireStaleAssignments = async () => {
  const now = new Date();
  const expiredAssignments = await prisma.callingAssignment.findMany({
    where: {
      status: AssignmentStatus.active,
      endDate: { lt: now },
    },
    select: {
      id: true,
      registrationId: true,
      // Pull centre + category so we can refresh exactly the affected read-model slices.
      registration: { select: { centreId: true, category: true } },
    },
  });

  if (expiredAssignments.length === 0) {
    return;
  }

  // Distinct (centreId, category) slices touched by this sweep — these may span centres.
  const affected = new Map<string, { centreId: number; category: Category }>();
  for (const a of expiredAssignments) {
    affected.set(`${a.registration.centreId}:${a.registration.category}`, {
      centreId: a.registration.centreId,
      category: a.registration.category,
    });
  }

  await prisma.$transaction(async (tx) => {
    await tx.callingAssignment.updateMany({
      where: {
        id: { in: expiredAssignments.map((assignment) => assignment.id) },
      },
      data: {
        status: AssignmentStatus.expired,
      },
    });

    await tx.registration.updateMany({
      where: {
        id: { in: expiredAssignments.map((assignment) => assignment.registrationId) },
        currentStage: RegistrationStage.calling_active,
        activeQueue: ActiveQueue.calling,
      },
      data: {
        currentStage: RegistrationStage.registered,
        activeQueue: ActiveQueue.none,
        currentAssignedCallerId: null,
      },
    });

    // Real-time refresh: expired assignments revert people to the "new" pool
    // (active_calling ↓, new_registrations ↑) across every affected centre/category.
    for (const slice of affected.values()) {
      await applyDomainEvent(tx, {
        type: "calling.reassigned",
        centreId: slice.centreId,
        category: slice.category,
      });
    }
  });
};

export const assignCalling = async ({
  registrationIds,
  callerId,
  activeDays,
  poolType,
  assignedByName,
  requester,
}: {
  registrationIds: number[];
  callerId: number;
  activeDays: number;
  poolType: "new_registration_pool" | "remaining_followup_pool";
  assignedByName: string;
  requester: RequestUser;
}) => {
  const scopeCentreId = resolveCallingScopeCentreId(requester);

  const caller = await prisma.user.findUnique({ where: { id: callerId } });
  if (!caller) {
    throw new ApiError(404, "Caller user not found");
  }

  if (scopeCentreId !== null && caller.centreId !== scopeCentreId) {
    throw new ApiError(403, "Caller must belong to your centre");
  }

  const registrations = await prisma.registration.findMany({
    where: { id: { in: registrationIds } },
    include: {
      assignments: {
        where: {
          status: AssignmentStatus.active,
        },
        select: {
          id: true,
        },
      },
      followup: true,
      callAttempts: {
        orderBy: {
          createdAt: "desc",
        },
        take: 1,
        select: {
          dropdownValue: true,
          callType: true,
        },
      },
    },
  });

  if (registrations.length !== registrationIds.length) {
    throw new ApiError(404, "One or more registrations were not found");
  }

  const category = registrations[0]?.category;
  if (!category) {
    throw new ApiError(400, "No registrations supplied");
  }

  const mixedCategory = registrations.some((registration) => registration.category !== category);
  if (mixedCategory) {
    throw new ApiError(400, "Selected registrations must belong to the same category");
  }

  const registrationCentreId = registrations[0]?.centreId ?? null;
  if (!registrationCentreId) {
    throw new ApiError(400, "Selected registrations are missing centre assignment");
  }

  const mixedCentre = registrations.some((registration) => registration.centreId !== registrationCentreId);
  if (mixedCentre) {
    throw new ApiError(400, "Selected registrations must belong to the same centre");
  }

  if (scopeCentreId !== null && registrationCentreId !== scopeCentreId) {
    throw new ApiError(403, "Selected registrations are outside your centre");
  }

  if (caller.centreId !== registrationCentreId) {
    throw new ApiError(403, "Assigned caller must belong to the same centre");
  }

  if (poolType === "new_registration_pool") {
    const blocked = registrations.find((registration) => registration.activeQueue !== ActiveQueue.none);
    if (blocked) {
      throw new ApiError(409, `Registration ${blocked.id} already has an active queue`);
    }

    const notRegistered = registrations.find(
      (registration) => registration.currentStage !== RegistrationStage.registered,
    );
    if (notRegistered) {
      throw new ApiError(
        409,
        `Registration ${notRegistered.id} must be in registered stage before calling assignment`,
      );
    }
  } else {
    const blocked = registrations.find(
      (registration) =>
        registration.currentStage !== RegistrationStage.followup_active ||
        registration.activeQueue !== ActiveQueue.followup ||
        registration.followup?.status !== FollowupStatus.active,
    );
    if (blocked) {
      throw new ApiError(
        409,
        `Registration ${blocked.id} must be in active follow-up before follow-up pool assignment`,
      );
    }
  }

  const alreadyAssigned = registrations.find((registration) => registration.assignments.length > 0);
  if (alreadyAssigned) {
    throw new ApiError(409, `Registration ${alreadyAssigned.id} already has an active assignment`);
  }

  const now = new Date();
  const endDate = new Date(now.getTime() + activeDays * 24 * 60 * 60 * 1000);

  const result = await prisma.$transaction(async (tx) => {
    const items = [];

    for (const registration of registrations) {
      const followupPool = poolType === "remaining_followup_pool";
      const stage = followupPool ? RegistrationStage.followup_active : RegistrationStage.calling_active;
      const queue = followupPool ? ActiveQueue.followup : ActiveQueue.calling;
      const assignmentSource = followupPool
        ? AssignmentSource.followup_calling
        : AssignmentSource.normal_calling;

      const updated = await tx.registration.update({
        where: { id: registration.id },
        data: {
          currentStage: stage,
          activeQueue: queue,
          ...buildOwnershipPatch(registration, callerId),
        },
      });

      const assignment = await tx.callingAssignment.create({
        data: {
          registrationId: updated.id,
          assignedToUserId: callerId,
          centreId: registrationCentreId,
          assignedBy: assignedByName,
          startDate: now,
          endDate,
          status: AssignmentStatus.active,
          assignmentSource,
        },
      });

      if (followupPool) {
        await tx.followup.update({
          where: { registrationId: updated.id },
          data: {
            ownerCallerId: callerId,
            status: FollowupStatus.active,
            centreId: registrationCentreId,
          },
        });
      }

      items.push({
        registration: updated,
        assignment,
      });
    }

    // Real-time read-model refresh: these registrations left the "new" pool and entered
    // calling (or follow-up). All share one centre + category (validated above).
    await applyDomainEvent(tx, {
      type: "registration.assigned_calling",
      centreId: registrationCentreId,
      category,
    });

    return {
      category,
      count: items.length,
      items,
    };
  });

  const notificationType =
    poolType === "remaining_followup_pool"
      ? NotificationType.followup_assigned
      : NotificationType.calling_assigned;
  const notificationTitle =
    poolType === "remaining_followup_pool" ? "Follow-up Assigned" : "New Calling Assigned";
  const notificationMessage =
    poolType === "remaining_followup_pool"
      ? `${result.count} follow-up record(s) assigned to you`
      : `${result.count} new calling record(s) assigned to you`;

  await createNotification({
    userId: callerId,
    title: notificationTitle,
    message: notificationMessage,
    type: notificationType,
  });

  return result;
};

export const getCallingList = async ({
  category,
  adminOverride,
  requester,
}: {
  category: Category;
  adminOverride?: boolean;
  requester: RequestUser;
}) => {
  await expireStaleAssignments();

  const scopeCentreId =
    adminOverride && requester.role === UserRole.SUPER_ADMIN ? null : resolveCallingScopeCentreId(requester);

  const assignments = await prisma.callingAssignment.findMany({
    where: {
      status: AssignmentStatus.active,
      registration: {
        category,
        currentStage: RegistrationStage.calling_active,
        activeQueue: ActiveQueue.calling,
        ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
      },
      ...(requester.role === UserRole.CALLER ? { assignedToUserId: requester.id } : {}),
      ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
    },
    include: {
      registration: true,
      assignedToUser: true,
    },
    orderBy: {
      startDate: "asc",
    },
  });

  // Per-person context so the caller's card is self-contained (same data as session calls).
  const ctxCentreId = scopeCentreId ?? requester.centreId ?? null;
  const context = ctxCentreId
    ? await buildPeopleContext(ctxCentreId, assignments.map((a) => a.registration.mobile))
    : new Map();
  const itemsWithContext = assignments.map((a) => {
    let ctx = emptyPersonContext();
    try {
      ctx = context.get(normalizeMobile(a.registration.mobile)) ?? emptyPersonContext();
    } catch {
      /* malformed mobile — empty context */
    }
    return { ...a, context: ctx };
  });

  return {
    count: itemsWithContext.length,
    items: itemsWithContext,
  };
};

export const markCallingDone = async ({
  registrationId,
  dropdownValue,
  dropdownId,
  remark,
  requester,
}: {
  registrationId: number;
  dropdownValue: string;
  // Which response was tapped. Optional — older builds send the label alone.
  dropdownId?: number;
  remark?: string;
  requester: RequestUser;
}) => {
  const scopeCentreId =
    requester.role === UserRole.SUPER_ADMIN ? requester.centreId ?? null : requireCentreId(requester.centreId);

  // The consecutive-removal rule is applied AFTER this transaction commits, never inside it: it
  // writes to batch_members / registrations through the raw client, and SQLite would deadlock on a
  // second writer opened while this one is still open.
  let threshold: ThresholdOption | null = null;
  let thresholdCentreId: number | null = null;
  let thresholdMobile: string | null = null;

  const result = await prisma.$transaction(async (tx) => {
    const registration = await tx.registration.findUnique({
      where: { id: registrationId },
      include: {
        centre: true,
      },
    });

    if (!registration) {
      throw new ApiError(404, "Registration not found");
    }

    if (scopeCentreId !== null && registration.centreId !== scopeCentreId) {
      throw new ApiError(403, "Registration is outside your centre");
    }

    if (!isActiveCallingAssignment(registration)) {
      throw new ApiError(409, "Registration is not in the calling queue");
    }

    const assignment = await tx.callingAssignment.findFirst({
      where: {
        registrationId,
        status: AssignmentStatus.active,
      },
      orderBy: {
        startDate: "desc",
      },
    });

    if (!assignment) {
      throw new ApiError(409, "No active calling assignment exists for this registration");
    }

    if (assignment.centreId !== registration.centreId) {
      throw new ApiError(409, "Active assignment centre does not match registration centre");
    }

    // By id, then by current name, then by any name it used to have. Before this, a pool call that
    // had been queued offline was REJECTED outright (400) if an admin renamed the response in the
    // meantime — the caller's work was simply refused, and the app's retry could never succeed
    // because the name it kept resending no longer existed.
    const { option } = await resolveCallingOption(tx, {
      dropdownId,
      label: dropdownValue,
      centreId: registration.centreId,
      context: DropdownContext.calling,
    });

    if (!option || !option.mapping) {
      throw new ApiError(400, "Invalid calling dropdown_value");
    }

    // The category filter is applied AFTER resolution, not as part of it: a response that no
    // longer applies to this person's category still describes what the caller actually chose, and
    // rejecting it would lose the call rather than record it slightly out of scope.
    if (
      option.category !== DropdownCategoryScope.both &&
      option.category !== registration.category
    ) {
      throw new ApiError(400, "That response does not apply to this person");
    }

    threshold = { id: option.id, label: option.label, removeAfterConsecutive: option.removeAfterConsecutive };
    thresholdCentreId = registration.centreId;
    thresholdMobile = registration.normalizedMobile || registration.mobile;

    const attempt = await tx.callAttempt.create({
      data: {
        registrationId,
        callerId: assignment.assignedToUserId,
        centreId: registration.centreId,
        callType: CallType.calling,
        dropdownValue: option.label,
        // Recorded by id as well as by label, exactly as the session path does. Without it every
        // pool call was label-only history: renaming a response orphaned its meaning, and any
        // "N of these in a row" count had nothing but text to match on.
        dropdownId: option.id,
        remark: remark?.trim() || null,
      },
    });

    const transition = buildCallingTransition({
      action: option.mapping.action,
      actorId: assignment.assignedToUserId,
      registration,
    });

    const updatedRegistration = await tx.registration.update({
      where: { id: registrationId },
      data: {
        currentStage: transition.stage,
        activeQueue: transition.queue,
        ...transition.ownershipPatch,
      },
    });

    if (transition.createFollowup) {
      const followup = await tx.followup.upsert({
        where: { registrationId },
        update: {
          ownerCallerId: assignment.assignedToUserId,
          status: FollowupStatus.active,
          centreId: registration.centreId,
        },
        create: {
          registrationId,
          ownerCallerId: assignment.assignedToUserId,
          centreId: registration.centreId,
          status: FollowupStatus.active,
        },
      });

      if (transition.closeCallingAssignment) {
        await tx.callingAssignment.updateMany({
          where: {
            registrationId,
            status: AssignmentStatus.active,
          },
          data: {
            status: AssignmentStatus.done,
          },
        });
      }

      // Real-time read-model refresh: calling result moved this person into follow-up.
      await applyDomainEvent(tx, {
        type: "calling.done",
        centreId: registration.centreId,
        category: registration.category,
      });

      return {
        action: option.mapping.action,
        attempt,
        registration: updatedRegistration,
        followup,
      };
    }

    if (transition.createRemovedRecord) {
      await tx.removedRecord.upsert({
        where: { registrationId },
        update: {
          reason: remark?.trim() || option.label,
        },
        create: {
          registrationId,
          reason: remark?.trim() || option.label,
        },
      });
    }

    if (transition.closeCallingAssignment) {
      await tx.callingAssignment.updateMany({
        where: {
          registrationId,
          status: AssignmentStatus.active,
        },
        data: {
          status: AssignmentStatus.done,
        },
      });
    }

    // Real-time read-model refresh: calling result removed/continued this person
    // (active_calling, removed, new_registrations may all shift).
    await applyDomainEvent(tx, {
      type: "calling.done",
      centreId: registration.centreId,
      category: registration.category,
    });

    return {
      action: option.mapping.action,
      attempt,
      registration: updatedRegistration,
    };
  });

  // "Remove after N of these in a row." Best-effort: the call is already recorded, so a failure
  // here must not turn a saved response into an error the caller's app will retry forever.
  if (threshold && thresholdCentreId !== null && thresholdMobile) {
    try {
      await enforceConsecutiveRemoval({
        registrationId,
        centreId: thresholdCentreId,
        mobile: thresholdMobile,
        option: threshold,
        now: new Date(),
      });
    } catch {
      /* non-critical */
    }
  }

  // "Not interested" / "Wrong number" already moved the registration to `removed` above, but left
  // its batch membership live — so the Pool and the next session handed them straight back.
  if (isRemovalAction(result.action) && thresholdCentreId !== null && thresholdMobile) {
    try {
      await applyDeclaredRemoval({
        centreId: thresholdCentreId,
        action: result.action,
        label: result.attempt.dropdownValue,
        mobile: thresholdMobile,
        // The registration itself was handled inside the transaction, reason and all.
        registrationId: null,
        now: new Date(),
      });
    } catch (e) {
      logger.error(`declared-removal (registration call) failed reg=${registrationId}: ${(e as Error).message}`);
    }
  }

  return result;
};
