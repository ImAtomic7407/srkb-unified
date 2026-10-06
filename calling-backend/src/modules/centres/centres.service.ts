import {
  CentreAllowedCategory,
  CentreType,
  Prisma,
  type Centre as PrismaCentre,
  type CentreConfig as PrismaCentreConfig,
} from "@prisma/client";
import { prisma } from "../../config/prisma.js";
import { enqueueOutbox } from "../../lib/outbox.js";
import { ApiError } from "../../lib/api-error.js";
import {
  buildCentreConfigCreateData,
} from "./centre-config.defaults.js";

type CentreCorePayload = {
  id: number;
  name: string;
  code: string;
  allowed_categories: CentreAllowedCategory;
  is_active: boolean;
  created_at: Date;
  updated_at: Date;
};

type CentreItemPayload = CentreCorePayload & {
  user_count: number;
  config_exists: boolean;
  config: CentreConfigPayload | null;
};

type CentreConfigPayload = {
  id: number;
  centre_id: number | null;
  centre_name: string;
  centre_type: string;
  calling_active_days: number;
  attendance_sheet_id: string;
  attendance_sheet_locked: boolean;
  summary_sheet_name: string;
  attendance_webapp_url: string;
  enabled_tabs_modules: Prisma.JsonValue | null;
  dropdown_settings: Prisma.JsonValue | null;
  attendance_settings: Prisma.JsonValue | null;
  pool_settings: Prisma.JsonValue | null;
  permission_settings: Prisma.JsonValue | null;
  ui_layout_settings: Prisma.JsonValue | null;
  custom_tabs_modules: Prisma.JsonValue | null;
  created_at: Date;
  updated_at: Date;
};

type CentreInput = {
  name: string;
  code: string;
  allowed_categories: CentreAllowedCategory;
  is_active?: boolean;
};

type CentreUpdateInput = Partial<CentreInput>;

const toCentrePayload = (centre: PrismaCentre): CentreCorePayload => ({
  id: centre.id,
  name: centre.name,
  code: centre.code,
  allowed_categories: centre.allowedCategories,
  is_active: centre.isActive,
  created_at: centre.createdAt,
  updated_at: centre.updatedAt,
});

const toCentreConfigPayload = (config: PrismaCentreConfig): CentreConfigPayload => ({
  id: config.id,
  centre_id: config.centreId,
  centre_name: config.centreName,
  centre_type: config.centreType,
  calling_active_days: config.callingActiveDays,
  attendance_sheet_id: config.attendanceSheetId,
  attendance_sheet_locked: config.attendanceSheetLocked,
  summary_sheet_name: config.summarySheetName,
  attendance_webapp_url: config.attendanceWebappUrl,
  enabled_tabs_modules: config.enabledTabsModules ?? null,
  dropdown_settings: config.dropdownSettings ?? null,
  attendance_settings: config.attendanceSettings ?? null,
  pool_settings: config.poolSettings ?? null,
  permission_settings: config.permissionSettings ?? null,
  ui_layout_settings: config.uiLayoutSettings ?? null,
  custom_tabs_modules: config.customTabsModules ?? null,
  created_at: config.createdAt,
  updated_at: config.updatedAt,
});

const buildCentrePayload = (
  centre: PrismaCentre & {
    config: PrismaCentreConfig | null;
    _count?: { users: number };
  },
): CentreItemPayload => ({
  ...toCentrePayload(centre),
  user_count: centre._count?.users ?? 0,
  config_exists: Boolean(centre.config),
  config: centre.config ? toCentreConfigPayload(centre.config) : null,
});

const ensureCodeAvailable = async (code: string, centreId?: number) => {
  const existing = await prisma.centre.findUnique({ where: { code } });
  if (existing && existing.id !== centreId) {
    throw new ApiError(409, "Centre code already exists");
  }
};

const getTemplateCentreConfig = async () => {
  const existing = await prisma.centreConfig.findFirst({
    where: {
      centreId: null,
    },
    orderBy: {
      id: "asc",
    },
  });

  if (existing) {
    return existing;
  }

  return prisma.centreConfig.create({
    data: buildCentreConfigCreateData({
      centreId: null,
      centreName: "Main Centre",
      centreType: CentreType.youth_and_congregation,
    }),
  });
};

const cloneTemplateConfigForCentre = async (centreId: number) => {
  const template = await getTemplateCentreConfig();
  const existing = await prisma.centreConfig.findUnique({
    where: {
      centreId,
    },
  });

  const data = buildCentreConfigCreateData({
    centreId,
    centreName: template.centreName,
    source: template,
  });

  const config = existing
    ? await prisma.centreConfig.update({
        where: { centreId },
        data,
      })
    : await prisma.centreConfig.create({
        data,
      });

  return toCentreConfigPayload(config);
};

export const listCentres = async () => {
  const centres = await prisma.centre.findMany({
    orderBy: [
      { isActive: "desc" },
      { createdAt: "asc" },
    ],
    include: {
      config: true,
      _count: {
        select: {
          users: true,
        },
      },
    },
  });

  return {
    count: centres.length,
    items: centres.map((centre) => buildCentrePayload(centre)),
  };
};

export const createCentre = async (input: CentreInput) => {
  await ensureCodeAvailable(input.code);

  const result = await prisma.$transaction(async (tx) => {
    const centre = await tx.centre.create({
      data: {
        name: input.name.trim(),
        code: input.code.trim(),
        allowedCategories: input.allowed_categories,
        isActive: input.is_active ?? true,
      },
    });

    const template = await tx.centreConfig.findFirst({
      where: {
        centreId: null,
      },
      orderBy: {
        id: "asc",
      },
    });

    const clonedConfig = await tx.centreConfig.create({
      data: template
        ? buildCentreConfigCreateData({
            centreId: centre.id,
            centreName: centre.name,
            source: template,
          })
        : buildCentreConfigCreateData({
            centreId: centre.id,
            centreName: centre.name,
            centreType: CentreType.youth_and_congregation,
          }),
    });

    return {
      centre: {
        ...centre,
        config: clonedConfig,
        _count: { users: 0 },
      },
      config: clonedConfig,
    };
  });

  // Immediately sync new centre to AE — no 24h wait for reconcile
  await enqueueOutbox("create_centre_in_ae", {
    centre_code: result.centre.code,
    centre_name: result.centre.name,
    occurred_at: new Date().toISOString(),
  });

  return buildCentrePayload({
    ...result.centre,
    config: result.config,
    _count: { users: 0 },
  });
};

export const updateCentre = async (centreId: number, input: CentreUpdateInput) => {
  const existing = await prisma.centre.findUnique({ where: { id: centreId } });
  if (!existing) {
    throw new ApiError(404, "Centre not found");
  }

  if (input.code) {
    await ensureCodeAvailable(input.code, centreId);
  }

  const centre = await prisma.centre.update({
    where: { id: centreId },
    data: {
      ...(input.name !== undefined ? { name: input.name.trim() } : {}),
      ...(input.code !== undefined ? { code: input.code.trim() } : {}),
      ...(input.allowed_categories !== undefined
        ? {
            allowedCategories: input.allowed_categories,
            // Kept in step with allowed_categories. They were two separate switches: the app's
            // super-admin read one, registration checked the other, and nothing kept them equal.
            enabledYouth: input.allowed_categories !== CentreAllowedCategory.congregation,
            enabledCongregation: input.allowed_categories !== CentreAllowedCategory.youth,
          }
        : {}),
      ...(input.is_active !== undefined ? { isActive: input.is_active } : {}),
    },
  });

  // And the attendance server's own switch (which decides whether a congregation batch can be
  // created there). Same machine, so its host-only endpoint accepts us. Best-effort: the calling
  // side is already saved, and a failure is logged rather than undoing it.
  if (input.allowed_categories !== undefined) {
    try {
      // Local address on purpose: ATTENDEASE_BASE_URL is the public tunnel, and the endpoint
      // refuses anything arriving through it.
      const base = process.env.ATTENDEASE_LOCAL_URL ?? "http://127.0.0.1:6061";
      const res = await fetch(`${base}/api/centres/${encodeURIComponent(centre.code)}/categories`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ congregation_enabled: centre.enabledCongregation }),
        signal: AbortSignal.timeout(5000),
      });
      if (!res.ok) console.warn(`WARN centre categories not mirrored to AttendEase: ${centre.code} -> ${res.status}`);
    } catch (e) {
      console.warn(`WARN centre categories not mirrored to AttendEase: ${centre.code}: ${(e as Error).message}`);
    }
  }

  return toCentrePayload(centre);
};

export const getCentre = async (centreId: number) => {
  const centre = await prisma.centre.findUnique({
    where: { id: centreId },
    include: {
      config: true,
      _count: {
        select: {
          users: true,
        },
      },
    },
  });

  if (!centre) {
    throw new ApiError(404, "Centre not found");
  }

  return buildCentrePayload(centre);
};

export const activateCentre = async (centreId: number) => {
  const existing = await prisma.centre.findUnique({ where: { id: centreId } });
  if (!existing) {
    throw new ApiError(404, "Centre not found");
  }

  const centre = await prisma.centre.update({
    where: { id: centreId },
    data: { isActive: true },
  });

  return toCentrePayload(centre);
};

export const deactivateCentre = async (centreId: number) => {
  const existing = await prisma.centre.findUnique({ where: { id: centreId } });
  if (!existing) {
    throw new ApiError(404, "Centre not found");
  }

  const centre = await prisma.centre.update({
    where: { id: centreId },
    data: { isActive: false },
  });

  return toCentrePayload(centre);
};
