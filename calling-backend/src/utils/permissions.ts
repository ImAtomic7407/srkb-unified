export const DEFAULT_PERMISSION_KEYS = [
  "registration_access",
  "calling_access",
  "followup_access",
  "my_attendance_access",
  "batch_calling_access",
] as const;

export const OPTIONAL_PERMISSION_KEYS = [
  "attendance_system_access",
  "attendance_sync_access",
  "dropdown_manage_access",
  "assign_calling_access",
  "report_access",
] as const;

export const ALL_PERMISSION_KEYS = [
  ...DEFAULT_PERMISSION_KEYS,
  ...OPTIONAL_PERMISSION_KEYS,
] as const;

export type PermissionKey = (typeof ALL_PERMISSION_KEYS)[number];
