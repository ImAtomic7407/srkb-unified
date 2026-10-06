import { ActiveQueue, Category, RegistrationStage, UserRole } from "@prisma/client";
import { prisma, rawPrisma } from "../../config/prisma.js";
import { bypassCentreScope } from "../../lib/prisma-extension.js";
import { ApiError } from "../../lib/api-error.js";
import { normalizeMobile } from "../../utils/mobile.js";
import { onRegistration } from "../batch/batch-member.service.js";
import { applyDomainEvent } from "../../lib/events.js";
import type { Request } from "express";

type RegisterInput = {
  name: string;
  mobile: string;
  address?: string;
  occupation?: string;
  category: Category;
  explicitBatchId?: number;
};

type RequestUser = NonNullable<Request["user"]>;

const resolveCentreId = (requester?: RequestUser | null, requestedCentreId?: number) => {
  if (!requester) {
    throw new ApiError(401, "Unauthenticated request");
  }

  if (requester.role === UserRole.SUPER_ADMIN) {
    return requestedCentreId ?? requester.centreId ?? null;
  }

  return requester.centreId ?? null;
};

const requireCentreId = (centreId: number | null) => {
  if (!centreId) {
    throw new ApiError(400, "Centre is not configured for this user");
  }

  return centreId;
};

export const createRegistration = async (input: RegisterInput, requester?: RequestUser | null) => {
  const mobile = normalizeMobile(input.mobile);
  const centreId = requireCentreId(resolveCentreId(requester));

  // Validate that the requested category is enabled at this centre
  const centre = await bypassCentreScope(() =>
    prisma.centre.findUnique({ where: { id: centreId }, select: { enabledYouth: true, enabledCongregation: true } }),
  );
  if (!centre) throw new ApiError(404, "Centre not found");
  if (input.category === Category.youth && !centre.enabledYouth) {
    throw new ApiError(400, "Youth registrations are not enabled at this centre");
  }
  if (input.category === Category.congregation && !centre.enabledCongregation) {
    throw new ApiError(400, "Congregation registrations are not enabled at this centre");
  }

  const existing = await prisma.registration.findUnique({
    where: { normalizedMobile_centreId: { normalizedMobile: mobile, centreId } },
  });

  if (existing) {
    throw new ApiError(409, "A registration with this mobile already exists at this centre");
  }

  // Support both camelCase (internal) and snake_case (API body) for explicit_batch_id
  const explicitBatchId = input.explicitBatchId ?? (input as Record<string, unknown>)["explicit_batch_id"] as number | undefined;

  // Create the registration AND update the live read-model counters atomically.
  // The event handler bumps centre_stats (registrations_total, new_registrations) in the
  // same transaction, so the dashboard's cached numbers stay exact without a recount.
  const registration = await prisma.$transaction(async (tx) => {
    const created = await tx.registration.create({
      data: {
        name: input.name.trim(),
        mobile,
        normalizedMobile: mobile,
        centreId,
        address: input.address?.trim() || null,
        occupation: input.occupation?.trim() || null,
        category: input.category,
        currentStage: RegistrationStage.registered,
        activeQueue: ActiveQueue.none,
      },
    });

    await applyDomainEvent(tx, {
      type: "registration.created",
      centreId,
      category: input.category,
    });

    return created;
  });

  // Apply R1/R2 batch membership rule. Non-fatal: a missing default batch is logged but
  // does not block the registration. Q4 conflicts bubble up as 409.
  const callerName = requester ? String(requester.id) : "system";
  try {
    await onRegistration(centreId, input.category, mobile, callerName, explicitBatchId);
  } catch (err) {
    if (err instanceof ApiError && err.details === "ExplicitBatchRequiredError") throw err;
    // All other batch errors (missing default batch, etc.) are non-fatal
    console.warn("[registration] batch membership assignment skipped:", (err as Error).message);
  }

  return registration;
};

// Cross-centre profile lookup for the registration prefill UI (D6 / Phase 5.1).
// Searches ALL centres for any registration matching this mobile number.
// Returns profile data to help the caller prefill name/address/occupation.
// Never links the registrations — each centre's record stays independent.
export const getProfileSuggestions = async (rawMobile: string) => {
  let normalized: string;
  try {
    normalized = normalizeMobile(rawMobile);
  } catch {
    return { suggestions: [] };
  }

  // Intentional cross-centre read (D6) — use rawPrisma to bypass the centre-isolation extension.
  // bypassCentreScope + extended client does not reliably propagate through Prisma's async pipeline.
  const matches = await rawPrisma.registration.findMany({
    where: { normalizedMobile: normalized },
    include: { centre: { select: { id: true, name: true, code: true } } },
    orderBy: { createdAt: "desc" },
  });

  return {
    suggestions: matches.map((r) => ({
      centreId: r.centreId,
      centreName: r.centre.name,
      centreCode: r.centre.code,
      name: r.name,
      address: r.address,
      occupation: r.occupation,
      category: r.category,
      lastSeenStage: r.currentStage,
      registeredAt: r.createdAt,
    })),
  };
};

export const listRegistrations = async ({
  requester,
  centreId,
  category,
}: {
  requester?: RequestUser | null;
  centreId?: number;
  category?: Category;
}) => {
  const isSuperAdmin = requester?.role === UserRole.SUPER_ADMIN;
  const resolvedCentreId = isSuperAdmin ? centreId ?? null : requireCentreId(resolveCentreId(requester, centreId));

  const registrations = await prisma.registration.findMany({
    where: {
      ...(resolvedCentreId ? { centreId: resolvedCentreId } : {}),
      ...(category ? { category } : {}),
    },
    orderBy: { createdAt: "desc" },
    take: 50,
  });

  return {
    count: registrations.length,
    items: registrations.map((registration) => ({
      id: registration.id,
      name: registration.name,
      mobile: registration.mobile,
      address: registration.address,
      occupation: registration.occupation,
      category: registration.category,
      current_stage: registration.currentStage,
      active_queue: registration.activeQueue,
      centre_id: registration.centreId,
      created_at: registration.createdAt,
    })),
  };
};

export const getRegistrationDetails = async (registrationId: number, requester?: RequestUser | null) => {
  const registration = await prisma.registration.findUnique({
    where: { id: registrationId },
    include: {
      callAttempts: {
        orderBy: {
          createdAt: "desc",
        },
        include: {
          caller: true,
        },
      },
      followup: {
        include: {
          ownerCaller: true,
        },
      },
      myAttendance: {
        include: {
          ownerCaller: true,

        },
      },
      batchMoveRequests: {
        orderBy: {
          requestedAt: "desc",
        },
        take: 1,
        include: {
          requestedByUser: true,
          originalCaller: true,
        },
      },
      originalCaller: true,
      firstConnectedByUser: true,
      relationshipOwner: true,
      currentAssignedCaller: true,
      currentBatchCaller: true,
      earlierManager: true,
      lastHandler: true,
      notifications: {
        orderBy: {
          createdAt: "desc",
        },
        take: 5,
      },
    },
  });

  if (!registration) {
    throw new ApiError(404, "Registration not found");
  }

  if (requester?.role !== UserRole.SUPER_ADMIN) {
    const requesterCentreId = requireCentreId(requester?.centreId ?? null);
    if (registration.centreId !== requesterCentreId) {
      throw new ApiError(403, "Registration is outside your centre");
    }
  }

  return {
    profile: {
      id: registration.id,
      name: registration.name,
      mobile: registration.mobile,
      normalized_mobile: registration.normalizedMobile,
      centre_id: registration.centreId,
      address: registration.address,
      occupation: registration.occupation,
      category: registration.category,
      current_stage: registration.currentStage,
      active_queue: registration.activeQueue,
    },
    ownership: {
      original_caller_id: registration.originalCallerId,
      first_connected_by: registration.firstConnectedBy,
      relationship_owner_id: registration.relationshipOwnerId,
      last_handled_by: registration.lastHandledBy,
      current_assigned_caller_id: registration.currentAssignedCallerId,
      current_batch_caller_id: registration.currentBatchCallerId,
      earlier_managed_by: registration.earlierManagedBy,
    },
    call_attempts: registration.callAttempts,
    last_3_call_attempts: registration.callAttempts.slice(0, 3),
    attendance_summary: null,

    my_attendance: registration.myAttendance,
    batch_member: null,
    batch_name: null,
    batch_calling_assignment: null,
    batch_member_status: null,
    sent_back_status:
      registration.currentStage === RegistrationStage.sent_back_to_owner
        ? registration.currentStage
        : null,
    current_batch_caller: registration.currentBatchCaller,
    batch_move_request: registration.batchMoveRequests[0] ?? null,
    followup: registration.followup,
    notifications: registration.notifications,
  };
};
