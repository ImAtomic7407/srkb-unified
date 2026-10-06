import { BatchCallingAssignmentMode, BatchCallingAssignmentStatus } from "@prisma/client";
import {
  AssignmentStatus,
  CallType,
  Category,
  DropdownAction,
  DropdownCategoryScope,
  DropdownContext,
  FollowupStatus,
  RegistrationStage,
  ActiveQueue,
  UserRole,
} from "@prisma/client";
import type { Request } from "express";
import { prisma, rawPrisma } from "../../config/prisma.js";
import { ApiError } from "../../lib/api-error.js";
import { getBatchCallingPool } from "../batch/batch.service.js";
import { requestContextStorage } from "../../lib/request-context.js";

type RequestUser = NonNullable<Request["user"]>;

const includedFollowupActions = new Set<DropdownAction>([
  DropdownAction.move_to_followup,
  DropdownAction.continue_calling,
  DropdownAction.stay_in_followup,
  DropdownAction.reassign_calling,
]);

const excludedActions = new Set<DropdownAction>([
  DropdownAction.remove_from_running,
  DropdownAction.mark_invalid,
  DropdownAction.archive,
  DropdownAction.close_from_my_attendance,
]);

const parseDate = (value: string | undefined) => {
  if (!value) {
    return null;
  }
  // A bare YYYY-MM-DD from the app is a CALENDAR DAY at the centre, not a UTC instant.
  // `new Date("2026-08-13")` is specified to mean UTC midnight, so in IST (UTC+5:30) the window
  // silently ran 05:30 that morning -> 05:29 the NEXT morning. Two consequences, both of which
  // look to an admin like data that failed to arrive:
  //   * someone registered between 00:00 and 05:30 IST is missing from a same-day `date_from`
  //   * someone registered up to 05:29 IST the following day is wrongly counted as "today"
  // The client builds these strings with a device-local formatter (isoDate in AdminScreens.kt),
  // so the day has to be anchored to the centre's own clock for the two ends to agree.
  //
  // The offset is PINNED to +05:30 rather than left to the server's local time. Anchoring to
  // server-local would be correct on this box (it runs IST) but would silently revert to the
  // exact bug above the moment this backend runs anywhere on UTC — and the repo already carries
  // DEPLOY_TO_VPS.md / VPS_HANDOFF.md, so that is a real destination, not a hypothetical. Every
  // centre this system serves is in India, which has a single timezone and no DST, so there is
  // no ambiguous or non-existent midnight to handle.
  const IST_OFFSET = "+05:30";
  const isBareCalendarDay = /^\d{4}-\d{2}-\d{2}$/.test(value);
  const date = isBareCalendarDay ? new Date(`${value}T00:00:00${IST_OFFSET}`) : new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
};

const matchesSearch = (value: string | null, search?: string) => {
  if (!search) {
    return true;
  }

  if (!value) {
    return false;
  }

  return value.toLowerCase().includes(search.toLowerCase());
};

const requireCentreId = (centreId: number | null | undefined) => {
  if (!centreId) {
    throw new ApiError(400, "Centre is not configured for this user");
  }

  return centreId;
};

const resolvePoolScopeCentreId = (requester: RequestUser) => {
  if (requester.role === UserRole.SUPER_ADMIN) {
    const ctx = requestContextStorage.getStore();
    return ctx?.centreId ?? requester.centreId ?? null;
  }

  return requireCentreId(requester.centreId);
};

type LatestAttempt = { dropdownValue: string; callType: CallType };

/**
 * Attaches each registration's most recent call attempt as a 0-or-1 element `callAttempts` array —
 * the same shape a Prisma `include` with `take: 1` produced, so callers are unchanged.
 *
 * WHY NOT `include: { callAttempts: { orderBy, take: 1 } }`, WHICH IS WHAT THIS REPLACES.
 * A relation `take` makes the query engine emit one correlated sub-select per parent row. Past
 * roughly 999 parents that exceeds SQLite's host-parameter ceiling and the engine does not fail
 * gracefully — it PANICS ("no entry found for key") and the whole request 500s.
 *
 * That is not theoretical. Centre B has 1,672 unassigned youth registrations, and on
 * 2026-08-15 its Pool Assignment table was returning 500 on every load while Centre A (450) and
 * Rani Ghat (823) were fine. It is a threshold every growing centre crosses, silently, by
 * registering one more person — Rani Ghat added 19 that same day.
 *
 * Fetching the attempts separately keeps the parent query a plain findMany, and the id list is
 * chunked so the lookup itself can never reach the same ceiling.
 */
const CHUNK = 500;

const attachLatestCallAttempts = async <T extends { id: number }>(
  registrations: T[],
): Promise<Array<T & { callAttempts: LatestAttempt[] }>> => {
  const latestById = new Map<number, LatestAttempt>();

  for (let i = 0; i < registrations.length; i += CHUNK) {
    const ids = registrations.slice(i, i + CHUNK).map((r) => r.id);
    if (ids.length === 0) continue;
    // Tie-break on id, because createdAt alone does NOT uniquely order these rows. Bulk history
    // imports stamp a whole day's attempts with one identical timestamp: 7 registrations here have
    // two attempts sharing a createdAt, and for 3 of them the two rows carry DIFFERENT outcomes
    // (e.g. "Won't come this time" vs "No answer / Switched off"). All 3 sit in a live pool.
    // Since the resolved action feeds `excludedActions`, an unstable winner would quietly include
    // or exclude a real person from a caller's work depending on row order. Higher id = inserted
    // later = the genuinely latest attempt.
    const attempts = await prisma.callAttempt.findMany({
      where: { registrationId: { in: ids } },
      orderBy: [{ createdAt: "desc" }, { id: "desc" }],
      select: { registrationId: true, dropdownValue: true, callType: true },
    });
    // Ordered newest-first, so the first row seen for a registration is its latest attempt.
    for (const a of attempts) {
      if (a.registrationId == null || latestById.has(a.registrationId)) continue;
      latestById.set(a.registrationId, { dropdownValue: a.dropdownValue, callType: a.callType });
    }
  }

  return registrations.map((r) => {
    const latest = latestById.get(r.id);
    return { ...r, callAttempts: latest ? [latest] : [] };
  });
};

/**
 * Resolve one attempt's configured action. Memoised per call site because the pool loops over
 * every candidate: at Centre B's 1,672 rows the un-memoised version issued up to two
 * sequential dropdown lookups EACH, and the distinct (label, context, category) keys behind them
 * number in the dozens.
 */
type ActionMemo = Map<string, DropdownAction | null>;

const resolveLatestAction = async (
  registration: {
    category: Category;
    callAttempts: Array<{ dropdownValue: string; callType: CallType }>;
  },
  memo?: ActionMemo,
) => {
  const latestAttempt = registration.callAttempts[0];
  if (!latestAttempt) {
    return null;
  }

  const context =
    latestAttempt.callType === CallType.followup ? DropdownContext.followup : DropdownContext.calling;

  const key = JSON.stringify([latestAttempt.dropdownValue, context, registration.category]);
  if (memo?.has(key)) {
    return memo.get(key) ?? null;
  }

  const option = await prisma.dropdownOption.findFirst({
    where: {
      label: latestAttempt.dropdownValue,
      context,
      category: registration.category,
    },
    include: {
      mapping: true,
    },
  });

  if (option) {
    const action = option.mapping?.action ?? null;
    memo?.set(key, action);
    return action;
  }

  const fallback = await prisma.dropdownOption.findFirst({
    where: {
      label: latestAttempt.dropdownValue,
      context,
      category: DropdownCategoryScope.both,
    },
    include: {
      mapping: true,
    },
  });

  const action = fallback?.mapping?.action ?? null;
  memo?.set(key, action);
  return action;
};

export const getNewRegistrationsPool = async ({
  category,
  dateFrom,
  dateTo,
  assignedStatus,
  search,
  requester,
}: {
  category: Category;
  dateFrom?: string;
  dateTo?: string;
  assignedStatus: "assigned" | "unassigned" | "all";
  search?: string;
  requester: RequestUser;
}) => {
  const from = parseDate(dateFrom);
  const to = parseDate(dateTo);
  const toInclusive = to ? new Date(to.getTime() + 24 * 60 * 60 * 1000 - 1) : null;
  const scopeCentreId = resolvePoolScopeCentreId(requester);

  const rows = await prisma.registration.findMany({
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
    include: {
      assignments: {
        select: {
          id: true,
          status: true,
        },
      },
    },
    orderBy: {
      createdAt: "asc",
    },
  });

  const candidates = await attachLatestCallAttempts(rows);
  const actionMemo: ActionMemo = new Map();

  const filtered = [];
  for (const registration of candidates) {
    const latestAction = await resolveLatestAction(registration, actionMemo);
    if (latestAction && excludedActions.has(latestAction)) {
      continue;
    }

    const createdAt = registration.createdAt;
    if (from && createdAt < from) {
      continue;
    }
    if (toInclusive && createdAt > toInclusive) {
      continue;
    }

    const hasHistory = registration.assignments.length > 0;
    if (assignedStatus === "assigned" && !hasHistory) {
      continue;
    }
    if (assignedStatus === "unassigned" && hasHistory) {
      continue;
    }

    if (
      !matchesSearch(registration.name, search) &&
      !matchesSearch(registration.mobile, search) &&
      !matchesSearch(registration.normalizedMobile, search) &&
      !matchesSearch(registration.address ?? null, search) &&
      !matchesSearch(registration.occupation ?? null, search)
    ) {
      continue;
    }

    filtered.push({
      ...registration,
      latest_action: latestAction,
    });
  }

  return filtered;
};

export const getRemainingFollowupsPool = async ({
  category,
  lastResponse,
  callerId,
  search,
  requester,
}: {
  category: Category;
  lastResponse?: string;
  callerId?: number;
  search?: string;
  requester: RequestUser;
}) => {
  const scopeCentreId = resolvePoolScopeCentreId(requester);
  const rows = await prisma.registration.findMany({
    where: {
      category,
      ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
      currentStage: RegistrationStage.followup_active,
      activeQueue: ActiveQueue.followup,
      followup: {
        is: {
          status: FollowupStatus.active,
        },
      },
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
    include: {
      assignments: {
        select: {
          id: true,
          status: true,
        },
      },
      followup: true,
    },
    orderBy: {
      createdAt: "asc",
    },
  });

  const candidates = await attachLatestCallAttempts(rows);
  const actionMemo: ActionMemo = new Map();

  const filtered = [];
  for (const registration of candidates) {
    const latestAction = await resolveLatestAction(registration, actionMemo);
    if (latestAction && !includedFollowupActions.has(latestAction) && !excludedActions.has(latestAction)) {
      continue;
    }
    if (latestAction && excludedActions.has(latestAction)) {
      continue;
    }

    if (callerId && registration.currentAssignedCallerId !== callerId) {
      continue;
    }

    if (lastResponse) {
      const latestValue = registration.callAttempts[0]?.dropdownValue?.toLowerCase() ?? "";
      const response = lastResponse.toLowerCase();
      const actionText = latestAction?.toLowerCase() ?? "";
      if (!latestValue.includes(response) && !actionText.includes(response)) {
        continue;
      }
    }

    if (
      !matchesSearch(registration.name, search) &&
      !matchesSearch(registration.mobile, search) &&
      !matchesSearch(registration.normalizedMobile, search) &&
      !matchesSearch(registration.address ?? null, search) &&
      !matchesSearch(registration.occupation ?? null, search)
    ) {
      continue;
    }

    filtered.push({
      ...registration,
      latest_action: latestAction,
    });
  }

  return filtered;
};

export const getBatchCallingPoolPlaceholder = async () => {
  return [];
};

export const getPoolSummary = async (category: Category, requester: RequestUser) => {
  const scopeCentreId = resolvePoolScopeCentreId(requester);

  const [newRegistrations, followups, batchCallingPool, removedCount, archivedCount] = await Promise.all([
    getNewRegistrationsPool({
      category,
      assignedStatus: "all",
      requester,
    }),
    getRemainingFollowupsPool({
      category,
      requester,
    }),
    getBatchCallingPool({
      category,
      assignedStatus: "all",
      requester,
    }),
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
  ]);

  return {
    new_registration_pool: newRegistrations.length,
    remaining_followup_pool: followups.length,
    batch_calling_pool: batchCallingPool.pending_requests.length + batchCallingPool.active_batch_members.length,
    removed_count: removedCount,
    archived_count: archivedCount,
  };
};

export const assignBatchCallingMembers = async ({
  batchMemberIds,
  callerId,
  activeDays,
  assignedByUserId,
  centreId,
}: {
  batchMemberIds: number[];
  callerId: number;
  activeDays: number;
  assignedByUserId: number;
  centreId: number;
}): Promise<number> => {
  const startDate = new Date();
  const endDate = new Date(startDate.getTime() + activeDays * 24 * 60 * 60 * 1000);

  await Promise.all(
    batchMemberIds.map((batchMemberId) =>
      rawPrisma.batchCallingAssignment.upsert({
        where: { batchMemberId },
        create: {
          batchMemberId,
          assignedToUserId: callerId,
          assignedByUserId,
          centreId,
          assignmentMode: BatchCallingAssignmentMode.manual,
          status: BatchCallingAssignmentStatus.active,
          startDate,
          endDate,
        },
        update: {
          assignedToUserId: callerId,
          assignedByUserId,
          status: BatchCallingAssignmentStatus.active,
          startDate,
          endDate,
        },
      })
    )
  );

  return batchMemberIds.length;
};

// Registered-by-date pool — powers the app's "New Registered" tab. Unlike getNewRegistrationsPool
// (which only returns the callable pool subset), this returns EVERY registration in the centre +
// selected category whose createdAt falls in [from, toInclusive], each tagged with a `types[]` array
// so the app can multi-select which dispositions to show. A person can carry more than one type
// (e.g. both "assigned" and "in_batch"); "new" means neither assigned nor in a batch.
export const getRegisteredByDate = async ({
  category,
  dateFrom,
  dateTo,
  search,
  requester,
}: {
  category: Category;
  dateFrom?: string;
  dateTo?: string;
  search?: string;
  requester: RequestUser;
}) => {
  const from = parseDate(dateFrom);
  const to = parseDate(dateTo);
  const toInclusive = to ? new Date(to.getTime() + 24 * 60 * 60 * 1000 - 1) : null;
  const scopeCentreId = resolvePoolScopeCentreId(requester);

  const digits10 = (value: string | null | undefined) => (value ?? "").replace(/\D/g, "").slice(-10);

  const registrations = await prisma.registration.findMany({
    where: {
      category,
      ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
      ...(from || toInclusive
        ? {
            createdAt: {
              ...(from ? { gte: from } : {}),
              ...(toInclusive ? { lte: toInclusive } : {}),
            },
          }
        : {}),
    },
    include: {
      assignments: {
        where: { status: AssignmentStatus.active },
        select: { id: true },
      },
    },
    orderBy: { createdAt: "desc" },
  });

  // Active batch membership for this centre + category, matched by last-10-digit mobile.
  const batchMembers = await prisma.batchMember.findMany({
    where: {
      ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
      category,
      removedAt: null,
    },
    select: { mobile: true },
  });
  const batchMobiles = new Set(batchMembers.map((b) => digits10(b.mobile)));

  const result = [];
  for (const reg of registrations) {
    if (
      search &&
      !matchesSearch(reg.name, search) &&
      !matchesSearch(reg.mobile, search) &&
      !matchesSearch(reg.normalizedMobile, search)
    ) {
      continue;
    }

    const types: string[] = [];
    if (
      reg.currentStage === RegistrationStage.removed ||
      reg.currentStage === RegistrationStage.archived
    ) {
      types.push("removed_archived");
    } else {
      const assigned = reg.assignments.length > 0;
      const inBatch = batchMobiles.has(digits10(reg.normalizedMobile ?? reg.mobile));
      if (assigned) types.push("assigned");
      if (inBatch) types.push("in_batch");
      if (!assigned && !inBatch) types.push("new");
    }

    result.push({ ...reg, types });
  }

  return result;
};
