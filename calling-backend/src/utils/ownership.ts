type OwnershipSource = {
  originalCallerId: number | null;
  firstConnectedBy: number | null;
  relationshipOwnerId: number | null;
  lastHandledBy: number | null;
  currentAssignedCallerId: number | null;
  currentBatchCallerId?: number | null;
  earlierManagedBy: number | null;
};

export const buildOwnershipPatch = (registration: OwnershipSource, handlerId: number) => {
  const previousManager = registration.currentAssignedCallerId ?? registration.lastHandledBy ?? registration.earlierManagedBy ?? null;

  return {
    firstConnectedBy: registration.firstConnectedBy ?? handlerId,
    relationshipOwnerId: registration.relationshipOwnerId ?? handlerId,
    earlierManagedBy: previousManager,
    lastHandledBy: handlerId,
    currentAssignedCallerId: handlerId,
    originalCallerId: registration.originalCallerId ?? handlerId,
  } as const;
};

export const clearActiveQueuePatch = {
  activeQueue: "none" as const,
};
