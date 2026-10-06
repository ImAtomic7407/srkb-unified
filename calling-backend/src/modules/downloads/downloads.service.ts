import {
  ActiveQueue,
  AssignmentStatus,

  Category,
  CallType,
  DropdownAction,
  DropdownCategoryScope,
  DropdownContext,
  FollowupStatus,
  RegistrationStage,
  UserRole,
} from "@prisma/client";
import type { Request } from "express";
import { prisma, rawPrisma } from "../../config/prisma.js";

type CsvRow = Record<string, string | number | boolean | null | undefined>;
type RequestUser = NonNullable<Request["user"]>;

type CsvDownload = {
  filename: string;
  csv: string;
};

const excludedActions = new Set<DropdownAction>([
  DropdownAction.remove_from_running,
  DropdownAction.mark_invalid,
  DropdownAction.archive,
  DropdownAction.close_from_my_attendance,
]);

const includedFollowupActions = new Set<DropdownAction>([
  DropdownAction.move_to_followup,
  DropdownAction.continue_calling,
  DropdownAction.stay_in_followup,
  DropdownAction.reassign_calling,
]);

const escapeCsvValue = (value: CsvRow[string]) => {
  if (value === null || value === undefined) {
    return "";
  }
  const text = String(value).replace(/\r?\n/g, " ").replace(/"/g, '""');
  if (/[",\n]/.test(text)) {
    return `"${text}"`;
  }
  return text;
};

const toCsv = (headers: string[], rows: CsvRow[]) => {
  const lines = [headers.join(",")];
  for (const row of rows) {
    lines.push(headers.map((header) => escapeCsvValue(row[header])).join(","));
  }
  return `${lines.join("\n")}\n`;
};

const withCsvFile = (filename: string, headers: string[], rows: CsvRow[]): CsvDownload => ({
  filename,
  csv: toCsv(headers, rows),
});

const latestCall = (attempts: Array<{ dropdownValue: string; remark: string | null; callType: CallType }>) =>
  attempts[0] ?? null;

const resolveLatestAction = async (registration: {
  category: Category;
  callAttempts: Array<{ dropdownValue: string; callType: CallType }>;
}) => {
  const latestAttempt = registration.callAttempts[0];
  if (!latestAttempt) {
    return null;
  }

  const context =
    latestAttempt.callType === CallType.followup ? DropdownContext.followup : DropdownContext.calling;

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
    return option.mapping?.action ?? null;
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

  return fallback?.mapping?.action ?? null;
};

const toPeopleRow = (registration: {
  id: number;
  name: string;
  mobile: string;
  address: string | null;
  occupation: string | null;
  category: Category;
  currentStage: RegistrationStage;
  activeQueue: ActiveQueue;
  createdAt: Date;
  originalCaller?: { name: string } | null;
  currentAssignedCaller?: { name: string } | null;
  callAttempts?: Array<{ dropdownValue: string; remark: string | null; createdAt: Date }>;
}) => {
  const attempt = latestCall(
    (registration.callAttempts ?? []).map((item) => ({
      dropdownValue: item.dropdownValue,
      remark: item.remark,
      callType: CallType.calling,
    })),
  );

  return {
    id: registration.id,
    name: registration.name,
    mobile: registration.mobile,
    address: registration.address ?? "",
    occupation: registration.occupation ?? "",
    category: registration.category,
    current_stage: registration.currentStage,
    active_queue: registration.activeQueue,
    last_response: attempt?.dropdownValue ?? "",
    last_remark: attempt?.remark ?? "",
    assigned_caller: registration.currentAssignedCaller?.name ?? "",
    original_caller: registration.originalCaller?.name ?? "",
    created_at: registration.createdAt.toISOString(),
  };
};

const peopleHeaders = [
  "id",
  "name",
  "mobile",
  "address",
  "occupation",
  "category",
  "current_stage",
  "active_queue",
  "last_response",
  "last_remark",
  "assigned_caller",
  "original_caller",
  "created_at",
];

const myAttendanceHeaders = [
  "id",
  "name",
  "mobile",
  "category",
  "attendance_count",
  "owner_caller",
  "status",
  "created_at",
];

const batchHeaders = [
  "id",
  "name",
  "mobile",
  "batch_name",
  "earlier_managed_by",
  "current_batch_caller",
  "status",
  "created_at",
];

const dropdownHeaders = [
  "id",
  "label",
  "context",
  "category",
  "action",
  "is_active",
  "sort_order",
];

const getBasePeopleRows = (registrations: Array<any>) => registrations.map((registration) => toPeopleRow(registration));

const resolveScopeCentreId = (requester: RequestUser) => {
  if (requester.role === UserRole.SUPER_ADMIN) {
    return requester.centreId ?? null;
  }

  return requester.centreId ?? null;
};

const filterByCentre = <T extends { centreId?: number | null }>(items: T[], centreId: number | null) => {
  if (centreId === null) {
    return items;
  }
  return items.filter((item) => Number(item.centreId ?? -1) === Number(centreId));
};

export const getNewRegistrationsDownload = async (category: Category, requester: RequestUser): Promise<CsvDownload> => {
  const scopeCentreId = resolveScopeCentreId(requester);
  const registrations = await prisma.registration.findMany({
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
      originalCaller: true,
      currentAssignedCaller: true,
      callAttempts: {
        orderBy: {
          createdAt: "desc",
        },
        take: 1,
        select: {
          dropdownValue: true,
          remark: true,
          createdAt: true,
          callType: true,
        },
      },
    },
    orderBy: {
      createdAt: "asc",
    },
  });

  const rows = getBasePeopleRows(filterByCentre(registrations, scopeCentreId));
  return withCsvFile(`new_registrations_${category}.csv`, peopleHeaders, rows);
};

export const getRemainingFollowupsDownload = async (category: Category, requester: RequestUser): Promise<CsvDownload> => {
  const scopeCentreId = resolveScopeCentreId(requester);
  const registrations = await prisma.registration.findMany({
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
      originalCaller: true,
      currentAssignedCaller: true,
      callAttempts: {
        orderBy: {
          createdAt: "desc",
        },
        take: 1,
        select: {
          dropdownValue: true,
          remark: true,
          createdAt: true,
          callType: true,
        },
      },
    },
    orderBy: {
      createdAt: "asc",
    },
  });

  const filtered = [];
  for (const registration of registrations) {
    const latestAction = await resolveLatestAction(registration);
    if (latestAction && !includedFollowupActions.has(latestAction) && !excludedActions.has(latestAction)) {
      continue;
    }
    if (latestAction && excludedActions.has(latestAction)) {
      continue;
    }
    filtered.push(registration);
  }

  return withCsvFile(`remaining_followups_${category}.csv`, peopleHeaders, getBasePeopleRows(filterByCentre(filtered, scopeCentreId)));
};

export const getCallingListDownload = async (category: Category, requester: RequestUser): Promise<CsvDownload> => {
  const scopeCentreId = resolveScopeCentreId(requester);
  const assignments = await prisma.callingAssignment.findMany({
    where: {
      status: AssignmentStatus.active,
      registration: {
        category,
        ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
        currentStage: RegistrationStage.calling_active,
        activeQueue: ActiveQueue.calling,
      },
    },
    include: {
      registration: {
        include: {
          originalCaller: true,
          currentAssignedCaller: true,
          callAttempts: {
            orderBy: {
              createdAt: "desc",
            },
            take: 1,
            select: {
              dropdownValue: true,
              remark: true,
              createdAt: true,
              callType: true,
            },
          },
        },
      },
      assignedToUser: true,
    },
    orderBy: {
      startDate: "asc",
    },
  });

  const rows = filterByCentre(
    assignments.map((assignment) => ({
      ...assignment,
      centreId: assignment.registration?.centreId ?? assignment.centreId ?? null,
    })),
    scopeCentreId,
  ).map((assignment) => ({
    ...toPeopleRow(assignment.registration),
    assigned_caller: assignment.assignedToUser.name,
  }));

  return withCsvFile(`calling_list_${category}.csv`, peopleHeaders, rows);
};

export const getFollowupListDownload = async (category: Category, requester: RequestUser): Promise<CsvDownload> => {
  const scopeCentreId = resolveScopeCentreId(requester);
  const followups = await prisma.followup.findMany({
    where: {
      status: FollowupStatus.active,
      registration: {
        category,
        ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
        currentStage: RegistrationStage.followup_active,
        activeQueue: ActiveQueue.followup,
      },
    },
    include: {
      registration: {
        include: {
          originalCaller: true,
          currentAssignedCaller: true,
          callAttempts: {
            orderBy: {
              createdAt: "desc",
            },
            take: 1,
            select: {
              dropdownValue: true,
              remark: true,
              createdAt: true,
              callType: true,
            },
          },
        },
      },
      ownerCaller: true,
    },
    orderBy: {
      createdAt: "asc",
    },
  });

  const rows = filterByCentre(
    followups.map((followup) => ({
      ...followup,
      centreId: followup.registration?.centreId ?? followup.centreId ?? null,
    })),
    scopeCentreId,
  ).map((followup) => ({
    ...toPeopleRow(followup.registration),
    assigned_caller: followup.ownerCaller.name,
  }));

  return withCsvFile(`followup_list_${category}.csv`, peopleHeaders, rows);
};

export const getMyAttendanceDownload = async (category: Category, requester: RequestUser): Promise<CsvDownload> => {
  const scopeCentreId = resolveScopeCentreId(requester);
  const items = await prisma.myAttendance.findMany({
    where: {
      registration: {
        category,
        ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
      },
    },
    include: {
      registration: {
        include: {
          originalCaller: true,
        },
      },
      ownerCaller: true,
    },
    orderBy: {
      createdAt: "desc",
    },
  });

  const rows = filterByCentre(
    items.map((item) => ({
      ...item,
      centreId: item.registration?.centreId ?? null,
    })),
    scopeCentreId,
  ).map((item) => ({
    id: item.id,
    name: item.registration.name,
    mobile: item.registration.mobile,
    category: item.registration.category,
    attendance_count: 0,
    owner_caller: item.ownerCaller.name,
    status: item.status,
    created_at: item.createdAt.toISOString(),
  }));

  return withCsvFile(`my_attendance_${category}.csv`, myAttendanceHeaders, rows);
};

export const getBatchCallingDownload = async (category: Category, requester: RequestUser): Promise<CsvDownload> => {
  const scopeCentreId = resolveScopeCentreId(requester);

  const members = await rawPrisma.batchMember.findMany({
    where: {
      category,
      removedAt: null,
      ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
    },
    include: { batch: { select: { name: true } } },
    orderBy: [{ centreId: "asc" }, { mobile: "asc" }],
  });

  const mobiles = [...new Set(members.map((m) => m.mobile))];
  const registrations = mobiles.length
    ? await prisma.registration.findMany({
        where: {
          category,
          mobile: { in: mobiles },
          ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
        },
        select: { id: true, name: true, mobile: true, normalizedMobile: true, originalCallerId: true, currentAssignedCallerId: true },
      })
    : [];

  const regByMobile = new Map<string, (typeof registrations)[0]>();
  for (const r of registrations) {
    const key = (r.normalizedMobile ?? r.mobile).replace(/\D/g, "").slice(-10);
    if (!regByMobile.has(key)) regByMobile.set(key, r);
  }

  const callerIds = [...new Set([
    ...registrations.map((r) => r.originalCallerId).filter((id): id is number => id !== null),
    ...registrations.map((r) => r.currentAssignedCallerId).filter((id): id is number => id !== null),
  ])];
  const callers = callerIds.length
    ? await prisma.user.findMany({ where: { id: { in: callerIds } }, select: { id: true, name: true } })
    : [];
  const callerMap = new Map(callers.map((c) => [c.id, c.name]));

  const rows: CsvRow[] = members.map((m) => {
    const key = m.mobile.replace(/\D/g, "").slice(-10);
    const reg = regByMobile.get(key);
    return {
      id: reg?.id ?? "",
      name: reg?.name ?? "",
      mobile: m.mobile,
      batch_name: m.batch?.name ?? "",
      earlier_managed_by: reg?.originalCallerId ? (callerMap.get(reg.originalCallerId) ?? "") : "",
      current_batch_caller: reg?.currentAssignedCallerId ? (callerMap.get(reg.currentAssignedCallerId) ?? "") : "",
      status: "active",
      created_at: m.joinedAt.toISOString(),
    };
  });

  return withCsvFile(`batch_calling_${category}.csv`, batchHeaders, rows);
};

export const getRemovedDownload = async (category: Category, requester: RequestUser): Promise<CsvDownload> => {
  const scopeCentreId = resolveScopeCentreId(requester);
  const registrations = await prisma.registration.findMany({
    where: {
      category,
      ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
      currentStage: RegistrationStage.removed,
    },
    include: {
      originalCaller: true,
      currentAssignedCaller: true,
      callAttempts: {
        orderBy: {
          createdAt: "desc",
        },
        take: 1,
        select: {
          dropdownValue: true,
          remark: true,
          createdAt: true,
          callType: true,
        },
      },
    },
    orderBy: {
      createdAt: "desc",
    },
  });

  return withCsvFile(`removed_${category}.csv`, peopleHeaders, getBasePeopleRows(filterByCentre(registrations, scopeCentreId)));
};

export const getArchivedDownload = async (category: Category, requester: RequestUser): Promise<CsvDownload> => {
  const scopeCentreId = resolveScopeCentreId(requester);
  const registrations = await prisma.registration.findMany({
    where: {
      category,
      ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
      currentStage: RegistrationStage.archived,
    },
    include: {
      originalCaller: true,
      currentAssignedCaller: true,
      callAttempts: {
        orderBy: {
          createdAt: "desc",
        },
        take: 1,
        select: {
          dropdownValue: true,
          remark: true,
          createdAt: true,
          callType: true,
        },
      },
    },
    orderBy: {
      createdAt: "desc",
    },
  });

  return withCsvFile(`archived_${category}.csv`, peopleHeaders, getBasePeopleRows(filterByCentre(registrations, scopeCentreId)));
};

export const getDropdownsDownload = async ({
  context,
  category,
}: {
  context?: DropdownContext;
  category?: DropdownCategoryScope;
}): Promise<CsvDownload> => {
  const dropdowns = await prisma.dropdownOption.findMany({
    where: {
      ...(context ? { context } : {}),
      ...(category
        ? {
            OR: [{ category }, { category: DropdownCategoryScope.both }],
          }
        : {}),
    },
    include: {
      mapping: true,
    },
    orderBy: [
      { sortOrder: "asc" },
      { id: "asc" },
    ],
  });

  const rows = dropdowns.map((dropdown) => ({
    id: dropdown.id,
    label: dropdown.label,
    context: dropdown.context,
    category: dropdown.category,
    action: dropdown.mapping?.action ?? "",
    is_active: dropdown.isActive,
    sort_order: dropdown.sortOrder,
  }));

  const fileSuffix = category ?? "all";
  return withCsvFile(`dropdowns_${fileSuffix}.csv`, dropdownHeaders, rows);
};
