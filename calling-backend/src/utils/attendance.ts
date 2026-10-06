type AttendanceOwnershipSource = {
  relationshipOwnerId: number | null;
  firstConnectedBy: number | null;
  lastHandledBy: number | null;
  currentAssignedCallerId: number | null;
};

export const resolveAttendanceOwnerId = (registration: AttendanceOwnershipSource) => {
  return (
    registration.relationshipOwnerId ??
    registration.firstConnectedBy ??
    registration.lastHandledBy ??
    registration.currentAssignedCallerId ??
    null
  );
};
