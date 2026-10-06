import { ApprovalStatus, NotificationType, UserRole } from "@prisma/client";
import { randomBytes, scryptSync, timingSafeEqual } from "crypto";
import { prisma, rawPrisma } from "../../config/prisma.js";
import { ApiError } from "../../lib/api-error.js";
import { enqueueOutbox } from "../../lib/outbox.js";
import { createNotificationsForRole } from "../notifications/notifications.service.js";
import type { PermissionKey } from "../../utils/permissions.js";
import { ALL_PERMISSION_KEYS } from "../../utils/permissions.js";

export type CentreAccessItem = {
  centre_id: number;
  centre_name: string;
  centre_code: string;
  is_admin: boolean;
};

export type SessionUser = {
  id: number;
  name: string;
  username: string;
  mobile: string;
  role: UserRole;
  permissions: PermissionKey[];
  centreId: number | null;
  centreCode: string | null;
  centre_access: CentreAccessItem[];
  approvalStatus: ApprovalStatus;
  isActive: boolean;
  attendanceSystemAccess: boolean;
  mustChangePassword: boolean;
};

export type PublicCentreItem = {
  id: number;
  name: string;
  code: string;
  allowed_categories: string;
};

export type RegisterRequestInput = {
  name: string;
  identifier: string;
  password: string;
  requested_role: "ADMIN" | "CALLER";
  centre_id: number;
  ae_access?: boolean;
};

export const hashPassword = (password: string) => {
  const salt = randomBytes(16).toString("hex");
  const derivedKey = scryptSync(password, salt, 64).toString("hex");
  return `${salt}:${derivedKey}`;
};

const verifyPassword = (password: string, storedHash: string | null | undefined) => {
  if (!storedHash) {
    return false;
  }

  const [salt, derivedKey] = storedHash.split(":");
  if (!salt || !derivedKey) {
    return false;
  }

  const actualDerivedKey = scryptSync(password, salt, 64);
  const expectedDerivedKey = Buffer.from(derivedKey, "hex");
  return actualDerivedKey.length === expectedDerivedKey.length && timingSafeEqual(actualDerivedKey, expectedDerivedKey);
};

const resolvePermissions = async (userId: number): Promise<PermissionKey[]> => {
  const permissions = await prisma.userPermission.findMany({
    where: {
      userId,
      isEnabled: true,
    },
    orderBy: [
      { permissionKey: "asc" },
    ],
  });

  return permissions
    .map((permission) => permission.permissionKey)
    .filter((permissionKey): permissionKey is PermissionKey =>
      ALL_PERMISSION_KEYS.includes(permissionKey as PermissionKey),
    );
};

const findUserByIdentifier = async (identifier: string) => {
  const trimmed = identifier.trim();
  if (!trimmed) {
    return null;
  }

  const numericId = Number(trimmed);
  return prisma.user.findFirst({
    where: {
      OR: [
        { mobile: trimmed },
        { name: trimmed },
        ...(Number.isInteger(numericId) && numericId > 0 ? [{ id: numericId }] : []),
      ],
    },
  });
};

const requireActiveApprovedUser = (user: {
  approvalStatus?: ApprovalStatus | null;
  isActive?: boolean | null;
}) => {
  const approvalStatus = user.approvalStatus ?? ApprovalStatus.approved;
  const isActive = user.isActive ?? true;

  if (approvalStatus === ApprovalStatus.pending) {
    throw new ApiError(403, "Your request is pending approval");
  }

  if (approvalStatus === ApprovalStatus.rejected) {
    throw new ApiError(403, "Your request was rejected");
  }

  if (!isActive) {
    throw new ApiError(403, "Your account is inactive");
  }
};

export const loginWithCredentials = async (identifier: string, password: string): Promise<SessionUser> => {
  const user = await findUserByIdentifier(identifier);

  if (!user) {
    console.log(`INFO login: username=${identifier} result=fail reason=not_found`);
    throw new ApiError(401, "Invalid username/mobile or password");
  }

  if (!user.passwordHash) {
    console.log(`INFO login: username=${identifier} result=fail reason=no_password_set`);
    throw new ApiError(403, "Password not set — contact your centre admin");
  }

  if (!verifyPassword(password, user.passwordHash)) {
    console.log(`INFO login: username=${user.mobile} result=fail reason=wrong_password`);
    throw new ApiError(401, "Invalid username/mobile or password");
  }

  requireActiveApprovedUser(user);
  requireActiveApprovedUser(user);
  const [permissions, centre, centreAccessRows] = await Promise.all([
    resolvePermissions(user.id),
    user.centreId
      ? rawPrisma.centre.findUnique({ where: { id: user.centreId }, select: { code: true } })
      : Promise.resolve(null),
    rawPrisma.userCentreAccess.findMany({
      where: { userId: user.id },
      include: { centre: { select: { id: true, name: true, code: true } } },
    }),
  ]);

  // For SUPER_ADMIN with no UserCentreAccess rows, fall back to all active centres
  const centreAccessList = centreAccessRows.length > 0
    ? centreAccessRows.map((a) => ({
        centre_id: a.centreId,
        centre_name: a.centre.name,
        centre_code: a.centre.code,
        is_admin: a.isAdmin,
      }))
    : await rawPrisma.centre
        .findMany({ where: { isActive: true }, select: { id: true, name: true, code: true } })
        .then((cs) => cs.map((c) => ({ centre_id: c.id, centre_name: c.name, centre_code: c.code, is_admin: true })));

  console.log(`INFO login: username=${user.mobile} result=success`);
  return {
    id: user.id,
    name: user.name,
    username: user.mobile,
    mobile: user.mobile,
    role: user.role,
    permissions,
    centreId: user.centreId,
    centreCode: centre?.code ?? null,
    centre_access: centreAccessList,
    approvalStatus: user.approvalStatus ?? ApprovalStatus.approved,
    isActive: user.isActive ?? true,
    attendanceSystemAccess: user.attendanceSystemAccess ?? true,
    mustChangePassword: user.mustChangePassword ?? true,
  };
};

export const changePassword = async (
  userId: number,
  newPassword: string,
  oldPassword?: string,
): Promise<void> => {
  const user = await rawPrisma.user.findUnique({ where: { id: userId } });
  if (!user) throw new ApiError(404, "User not found");

  // If mustChangePassword is false, old_password is required to prove possession
  if (!user.mustChangePassword) {
    if (!oldPassword) throw new ApiError(422, "old_password is required");
    if (!verifyPassword(oldPassword, user.passwordHash)) {
      throw new ApiError(401, "Current password is incorrect");
    }
  }

  const newHash = hashPassword(newPassword);
  const now = new Date();
  await rawPrisma.user.update({
    where: { id: userId },
    data: { passwordHash: newHash, mustChangePassword: false, passwordUpdatedAt: now },
  });

  console.log(`INFO password_change: username=${user.mobile} initiated_via=cb mustChange=${user.mustChangePassword}`);

  await enqueueOutbox("sync_password_to_attendease", {
    username: user.mobile,
    password_hash: newHash,
    password_updated_at: now.toISOString(),
    must_change: false,
  });
};

/**
 * Admin-initiated password reset. Caller must be SUPER_ADMIN or a centre admin
 * of at least one centre the target user belongs to (checked in the route layer).
 * Writes the new hash and enqueues sync to AE.
 */
export const adminResetPassword = async (
  targetUsername: string,
  newPassword: string,
  resetBy: string,
): Promise<void> => {
  const user = await rawPrisma.user.findFirst({ where: { mobile: targetUsername } });
  if (!user) throw new ApiError(404, "User not found");

  const newHash = hashPassword(newPassword);
  const now = new Date();
  await rawPrisma.user.update({
    where: { id: user.id },
    data: { passwordHash: newHash, mustChangePassword: true, passwordUpdatedAt: now },
  });

  console.log(`INFO password_change: username=${targetUsername} initiated_via=admin_reset by=${resetBy} mustChange=true`);

  await enqueueOutbox("sync_password_to_attendease", {
    username: targetUsername,
    password_hash: newHash,
    password_updated_at: now.toISOString(),
    must_change: true,
  });
};

export const syncPasswordFromAE = async (
  username: string,
  passwordHash: string | null,
  passwordUpdatedAt: string,
  plainPassword?: string,
  mustChange?: boolean,
): Promise<void> => {
  const user = await rawPrisma.user.findFirst({ where: { mobile: username } });
  if (!user) {
    console.warn(`WARN sync_password: direction=ae_to_cb username=${username} result=not_found`);
    throw new ApiError(404, "No CB user found for this username");
  }
  // AE and CB use the SAME scrypt format (N=16384, r=8, p=1, dklen=64, "salt:hexkey" with the salt
  // as its hex-string bytes), and each side's verify accepts the other's hash — so we store AE's
  // hash directly. No plaintext is transmitted or persisted. (plainPassword is only a defensive
  // fallback for any in-flight legacy job that arrived without a hash.)
  const hashToStore = passwordHash ?? (plainPassword ? hashPassword(plainPassword) : null);
  if (!hashToStore) {
    throw new ApiError(400, "sync-password requires password_hash");
  }
  await rawPrisma.user.update({
    where: { id: user.id },
    data: {
      passwordHash: hashToStore,
      passwordUpdatedAt: new Date(passwordUpdatedAt),
      mustChangePassword: mustChange ?? false,
    },
  });
  console.log(`INFO sync_password: direction=ae_to_cb username=${username} result=success mustChange=${mustChange ?? false}`);
};

export const listPublicCentres = async () => {
  const centres = await prisma.centre.findMany({
    where: { isActive: true },
    orderBy: [{ name: "asc" }],
  });

  return {
    count: centres.length,
    items: centres.map((centre) => ({
      id: centre.id,
      name: centre.name,
      code: centre.code,
      allowed_categories: centre.allowedCategories,
    })),
  };
};

export const createRegisterRequest = async (input: RegisterRequestInput) => {
  const centre = await prisma.centre.findFirst({
    where: {
      id: input.centre_id,
      isActive: true,
    },
  });

  if (!centre) {
    throw new ApiError(404, "Centre not found");
  }

  const mobile = input.identifier.trim();
  if (!mobile) {
    throw new ApiError(422, "username/mobile is required");
  }

  const existing = await prisma.user.findFirst({
    where: {
      OR: [
        { mobile },
        { name: mobile },
      ],
    },
  });

  if (existing) {
    throw new ApiError(409, "A user with this username/mobile already exists");
  }

  const user = await prisma.user.create({
    data: {
      name: input.name.trim(),
      mobile,
      role: input.requested_role as UserRole,
      centreId: centre.id,
      approvalStatus: ApprovalStatus.pending,
      isActive: false,
      attendanceSystemAccess: input.ae_access ?? false,
      passwordHash: hashPassword(input.password),
    },
  });

  if (user.role === UserRole.ADMIN) {
    await createNotificationsForRole({
      role: UserRole.SUPER_ADMIN,
      title: "Centre admin request submitted",
      message: `${user.name} requested Centre Admin access for ${centre.name}.`,
      type: NotificationType.user_request_created,
      registrationId: null,
      referenceUserId: user.id,
    });
  } else {
    await createNotificationsForRole({
      role: UserRole.ADMIN,
      centreId: centre.id,
      title: "Caller request submitted",
      message: `${user.name} requested Caller access in ${centre.name}.`,
      type: NotificationType.user_request_created,
      registrationId: null,
      referenceUserId: user.id,
    });
  }

  return {
    id: user.id,
    name: user.name,
    username: user.mobile,
    mobile: user.mobile,
    role: user.role,
    centre_id: user.centreId,
    approval_status: user.approvalStatus,
    is_active: user.isActive,
    attendance_system_access: user.attendanceSystemAccess,
  };
};
