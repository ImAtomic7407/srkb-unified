import { ActiveQueue, CallType, Category, DropdownAction, DropdownCategoryScope, DropdownContext, RegistrationStage } from "@prisma/client";
import type { Request } from "express";
import { prisma } from "../../config/prisma.js";
import type { ArchiveStatusFilter } from "./archive.schema.js";

type RequestUser = NonNullable<Request["user"]>;

type ArchiveRow = {
  id: number;
  name: string;
  mobile: string;
  address: string;
  occupation: string;
  category: Category;
  current_stage: RegistrationStage;
  active_queue: ActiveQueue;
  last_response: string;
  last_remark: string;
  last_call_date: string;
  removed_reason: string;
  archived_reason: string;
  created_at: string;
  // When they left — the date the Archive page groups by. The RemovedRecord's own date where there
  // is one (every removal since 2026-09-27 writes one), else the last call, else registration.
  removed_at: string;
  // The one reason to show, whichever kind of removal this is.
  reason: string;
  status: "removed" | "archived" | "invalid";
};

const matchesSearch = (value: string | null | undefined, search?: string) => {
  if (!search) {
    return true;
  }

  if (!value) {
    return false;
  }

  return value.toLowerCase().includes(search.toLowerCase());
};

const resolveLatestAction = async (registration: {
  category: Category;
  callAttempts: Array<{ dropdownValue: string; callType: CallType; dropdownId: number | null }>;
}) => {
  const latestAttempt = registration.callAttempts[0];
  if (!latestAttempt) {
    return null;
  }

  // By id first: labels are shared across centres and renamed, an id is what was actually tapped.
  if (latestAttempt.dropdownId !== null) {
    const byId = await prisma.dropdownOption.findUnique({
      where: { id: latestAttempt.dropdownId },
      include: { mapping: true },
    });
    if (byId?.mapping) return byId.mapping.action;
  }

  const context = latestAttempt.callType === CallType.followup ? DropdownContext.followup : DropdownContext.calling;

  const option = await prisma.dropdownOption.findFirst({
    where: {
      label: latestAttempt.dropdownValue,
      context,
      category: registration.category,
    },
    include: { mapping: true },
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
    include: { mapping: true },
  });

  return fallback?.mapping?.action ?? null;
};

const toArchiveRow = async (registration: {
  id: number;
  name: string;
  mobile: string;
  address: string | null;
  occupation: string | null;
  category: Category;
  currentStage: RegistrationStage;
  activeQueue: ActiveQueue;
  createdAt: Date;
  callAttempts: Array<{
    dropdownValue: string;
    remark: string | null;
    createdAt: Date;
    callType: CallType;
    dropdownId: number | null;
  }>;
  removedRecord: { reason: string; removedAt: Date } | null;
}) => {
  const latestAttempt = registration.callAttempts[0] ?? null;
  const latestAction = await resolveLatestAction({
    category: registration.category,
    callAttempts: registration.callAttempts.map((attempt) => ({
      dropdownValue: attempt.dropdownValue,
      callType: attempt.callType,
      dropdownId: attempt.dropdownId,
    })),
  });

  const status: "removed" | "archived" | "invalid" =
    latestAction === DropdownAction.mark_invalid
      ? "invalid"
      : registration.currentStage === RegistrationStage.archived || registration.activeQueue === ActiveQueue.archived || latestAction === DropdownAction.archive
        ? "archived"
        : "removed";

  const lastCallDate = latestAttempt?.createdAt ?? registration.removedRecord?.removedAt ?? registration.createdAt;
  const removedAt = registration.removedRecord?.removedAt ?? latestAttempt?.createdAt ?? registration.createdAt;
  const reason =
    registration.removedRecord?.reason ?? latestAttempt?.remark ?? latestAttempt?.dropdownValue ?? "";

  return {
    id: registration.id,
    name: registration.name,
    mobile: registration.mobile,
    address: registration.address ?? "",
    occupation: registration.occupation ?? "",
    category: registration.category,
    current_stage: registration.currentStage,
    active_queue: registration.activeQueue,
    last_response: latestAttempt?.dropdownValue ?? "",
    last_remark: latestAttempt?.remark ?? "",
    last_call_date: lastCallDate.toISOString(),
    removed_reason:
      status === "invalid"
        ? registration.removedRecord?.reason ?? latestAttempt?.remark ?? latestAttempt?.dropdownValue ?? ""
        : registration.currentStage === RegistrationStage.removed || registration.activeQueue === ActiveQueue.removed
          ? registration.removedRecord?.reason ?? latestAttempt?.remark ?? latestAttempt?.dropdownValue ?? ""
          : "",
    archived_reason:
      status === "archived"
        ? registration.removedRecord?.reason ?? latestAttempt?.remark ?? latestAttempt?.dropdownValue ?? ""
        : "",
    created_at: registration.createdAt.toISOString(),
    removed_at: removedAt.toISOString(),
    reason,
    status,
  } satisfies ArchiveRow;
};

export const getArchiveData = async ({
  category,
  search,
  status,
  requester,
}: {
  category: Category;
  search?: string;
  status?: ArchiveStatusFilter;
  requester: RequestUser;
}) => {
  const scopeCentreId = requester.centreId ?? null;
  const registrations = await prisma.registration.findMany({
    where: {
      category,
      ...(scopeCentreId !== null ? { centreId: scopeCentreId } : {}),
      OR: [
        { currentStage: RegistrationStage.removed },
        { activeQueue: ActiveQueue.removed },
        { currentStage: RegistrationStage.archived },
        { activeQueue: ActiveQueue.archived },
        { removedRecord: { isNot: null } },
      ],
    },
    include: {
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
          dropdownId: true,
        },
      },
      removedRecord: true,
    },
    orderBy: {
      createdAt: "desc",
    },
  });

  const rows: ArchiveRow[] = [];
  for (const registration of registrations) {
    const row = await toArchiveRow(registration);

    if (status && status !== "all" && row.status !== status) {
      continue;
    }

    if (
      !matchesSearch(row.name, search) &&
      !matchesSearch(row.mobile, search) &&
      !matchesSearch(row.address, search) &&
      !matchesSearch(row.occupation, search) &&
      !matchesSearch(row.last_response, search) &&
      !matchesSearch(row.last_remark, search) &&
      !matchesSearch(row.removed_reason, search) &&
      !matchesSearch(row.archived_reason, search)
    ) {
      continue;
    }

    rows.push(row);
  }

  // Newest removal first — the page groups these by day.
  rows.sort((a, b) => b.removed_at.localeCompare(a.removed_at));
  return {
    count: rows.length,
    items: rows,
  };
};
