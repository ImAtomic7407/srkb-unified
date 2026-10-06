import { ApprovalStatus, UserRole } from "@prisma/client";
import { prisma } from "../../../config/prisma.js";
import { bypassCentreScope } from "../../../lib/prisma-extension.js";
import { DEFAULT_PERMISSION_KEYS, OPTIONAL_PERMISSION_KEYS } from "../../../utils/permissions.js";
import type { WebhookEvent } from "../sync.types.js";

const mapRole = (role: string): UserRole => {
  if (role === "host") return UserRole.SUPER_ADMIN;
  if (role === "centre_admin" || role === "admin") return UserRole.ADMIN;
  return UserRole.CALLER;
};

const mapStatus = (status: string): ApprovalStatus =>
  status === "approved" ? ApprovalStatus.approved
  : status === "rejected" ? ApprovalStatus.rejected
  : ApprovalStatus.pending;

const seedDefaultPermissions = async (userId: number, role: UserRole) => {
  const keys = role === UserRole.CALLER
    ? [...DEFAULT_PERMISSION_KEYS]
    : [...DEFAULT_PERMISSION_KEYS, ...OPTIONAL_PERMISSION_KEYS];

  for (const permissionKey of keys) {
    await bypassCentreScope(() =>
      prisma.userPermission.upsert({
        where: { userId_permissionKey: { userId, permissionKey } },
        update: {},
        create: { userId, permissionKey, isEnabled: true },
      }),
    );
  }
};

export const handleAccountCreated = async (event: WebhookEvent) => {
  const p = event.payload as { username: string; role: string; status: string; display_name?: string };
  const role = mapRole(p.role);

  const existing = await bypassCentreScope(() =>
    prisma.user.findUnique({ where: { mobile: p.username }, select: { id: true } }),
  );

  const user = await bypassCentreScope(() =>
    prisma.user.upsert({
      where: { mobile: p.username },
      update: { role, approvalStatus: mapStatus(p.status) },
      create: {
        name: p.display_name ?? p.username,
        mobile: p.username,
        role,
        approvalStatus: mapStatus(p.status),
        isActive: p.status === "approved",
      },
    }),
  );

  if (!existing) {
    await seedDefaultPermissions(user.id, role);
  }
};

export const handleAccountApproved = async (event: WebhookEvent) => {
  const p = event.payload as { username: string };
  await bypassCentreScope(() =>
    prisma.user.updateMany({
      where: { mobile: p.username },
      data: { approvalStatus: ApprovalStatus.approved, isActive: true },
    }),
  );
};

export const handleAccessGranted = async (event: WebhookEvent) => {
  const p = event.payload as { username: string; centre_id: string; role?: string; display_name?: string };
  await bypassCentreScope(async () => {
    const user = await prisma.user.findUnique({ where: { mobile: p.username } });
    const centre = await prisma.centre.findUnique({ where: { code: p.centre_id } });
    if (!user || !centre) return;

    // AttendEase labels centre admins as "admin" (not "centre_admin"); accept both.
    const isCentreAdmin = p.role === "centre_admin" || p.role === "admin" || p.role === "host";

    await prisma.userCentreAccess.upsert({
      where: { userId_centreId: { userId: user.id, centreId: centre.id } },
      update: { displayName: p.display_name ?? null, isAdmin: isCentreAdmin, lastSyncedAt: new Date() },
      create: { userId: user.id, centreId: centre.id, displayName: p.display_name ?? null, isAdmin: isCentreAdmin, lastSyncedAt: new Date() },
    });

    // Promote CALLER to ADMIN if they are a centre admin in AE (never demote SUPER_ADMIN)
    if (isCentreAdmin && user.role === UserRole.CALLER) {
      await prisma.user.update({
        where: { id: user.id },
        data: { role: UserRole.ADMIN },
      });
      // User was first created as CALLER (default perms only); grant the optional
      // admin permission set (incl. assign_calling_access) now that they are ADMIN.
      await seedDefaultPermissions(user.id, UserRole.ADMIN);
    }
  });
};

export const handleAccessRevoked = async (event: WebhookEvent) => {
  const p = event.payload as { username: string; centre_id: string };
  await bypassCentreScope(async () => {
    const user = await prisma.user.findUnique({ where: { mobile: p.username } });
    const centre = await prisma.centre.findUnique({ where: { code: p.centre_id } });
    if (!user || !centre) return;
    await prisma.userCentreAccess.deleteMany({ where: { userId: user.id, centreId: centre.id } });
  });
};
