import { CentreType, type CentreConfig as PrismaCentreConfig, type Prisma } from "@prisma/client";

export type CentreConfigSnapshot = {
  enabledTabsModules: Prisma.JsonValue;
  dropdownSettings: Prisma.JsonValue;
  attendanceSettings: Prisma.JsonValue;
  poolSettings: Prisma.JsonValue;
  permissionSettings: Prisma.JsonValue;
  uiLayoutSettings: Prisma.JsonValue;
  customTabsModules: Prisma.JsonValue;
};

export const DEFAULT_CENTRE_CONFIG_SNAPSHOT: CentreConfigSnapshot = {
  enabledTabsModules: {
    dashboard: true,
    registration: true,
    calling: true,
    followup: true,
    myAttendance: true,
    batch: true,
    pool: true,
    attendance: true,
    downloads: true,
    archivedData: true,
    manageUsers: true,
    centres: true,
  },
  dropdownSettings: {
    defaultContext: "calling",
    defaultCategoryScope: "both",
    dynamicDropdowns: true,
  },
  attendanceSettings: {
    sheetLocked: false,
    syncEnabled: true,
    webviewEnabled: true,
    masterSheetName: "MASTER_ALL",
    sessionImportEnabled: true,
  },
  poolSettings: {
    assignCallingEnabled: true,
    batchCallingEnabled: true,
    batchPoolsEnabled: true,
  },
  permissionSettings: {
    SUPER_ADMIN: ["all"],
    ADMIN: ["management", "caller_work"],
    CALLER: ["caller_work"],
  },
  uiLayoutSettings: {
    drawerStyle: "compact",
    topHeaderStyle: "standard",
    cardDensity: "operational",
  },
  customTabsModules: [],
};

const cloneJsonValue = <T extends Prisma.JsonValue>(value: T): T => {
  return JSON.parse(JSON.stringify(value)) as T;
};

export const resolveCentreConfigSnapshot = (
  source?: PrismaCentreConfig | null,
): CentreConfigSnapshot => ({
  enabledTabsModules: cloneJsonValue(source?.enabledTabsModules ?? DEFAULT_CENTRE_CONFIG_SNAPSHOT.enabledTabsModules),
  dropdownSettings: cloneJsonValue(source?.dropdownSettings ?? DEFAULT_CENTRE_CONFIG_SNAPSHOT.dropdownSettings),
  attendanceSettings: cloneJsonValue(source?.attendanceSettings ?? DEFAULT_CENTRE_CONFIG_SNAPSHOT.attendanceSettings),
  poolSettings: cloneJsonValue(source?.poolSettings ?? DEFAULT_CENTRE_CONFIG_SNAPSHOT.poolSettings),
  permissionSettings: cloneJsonValue(source?.permissionSettings ?? DEFAULT_CENTRE_CONFIG_SNAPSHOT.permissionSettings),
  uiLayoutSettings: cloneJsonValue(source?.uiLayoutSettings ?? DEFAULT_CENTRE_CONFIG_SNAPSHOT.uiLayoutSettings),
  customTabsModules: cloneJsonValue(source?.customTabsModules ?? DEFAULT_CENTRE_CONFIG_SNAPSHOT.customTabsModules),
});

export const buildCentreConfigCreateData = (input: {
  centreId: number | null;
  centreName: string;
  centreType?: CentreType;
  source?: PrismaCentreConfig | null;
}): Prisma.CentreConfigUncheckedCreateInput => {
  const snapshot = resolveCentreConfigSnapshot(input.source);

  return {
    centreId: input.centreId,
    centreName: input.centreName,
    centreType: input.source?.centreType ?? input.centreType ?? CentreType.youth_and_congregation,
    callingActiveDays: input.source?.callingActiveDays ?? 7,
    attendanceSheetId: input.source?.attendanceSheetId ?? "",
    attendanceSheetLocked: input.source?.attendanceSheetLocked ?? false,
    summarySheetName: input.source?.summarySheetName ?? "Summary",
    attendanceWebappUrl: input.source?.attendanceWebappUrl ?? "",
    enabledTabsModules: snapshot.enabledTabsModules,
    dropdownSettings: snapshot.dropdownSettings,
    attendanceSettings: snapshot.attendanceSettings,
    poolSettings: snapshot.poolSettings,
    permissionSettings: snapshot.permissionSettings,
    uiLayoutSettings: snapshot.uiLayoutSettings,
    customTabsModules: snapshot.customTabsModules,
  } as Prisma.CentreConfigUncheckedCreateInput;
};
