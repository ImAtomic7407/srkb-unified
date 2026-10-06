import { ApprovalStatus, UserRole, type User } from "@prisma/client";
import { enqueueOutbox } from "../../lib/outbox.js";
import type { Request } from "express";
import { prisma } from "../../config/prisma.js";
import { ApiError } from "../../lib/api-error.js";
import { DEFAULT_PERMISSION_KEYS, OPTIONAL_PERMISSION_KEYS } from "../../utils/permissions.js";
import { getRequestContext } from "../../lib/request-context.js";
import { env } from "../../config/env.js";
import { attendeaseRepo } from "../../lib/attendease-db.js";

// Grant a freshly-approved user their default permissions. Without this, resolvePermissions
// returns [] (it only reads isEnabled rows) and the user is locked out of every gated endpoint —
// which is exactly why approved CALLERs had no calling_access and couldn't reach the caller screens.
// CALLER → operational defaults; ADMIN/SUPER_ADMIN → defaults + optional (assign/report/etc.).
export const grantDefaultPermissions = async (userId: number, role: UserRole) => {
  const keys =
    role === UserRole.CALLER
      ? [...DEFAULT_PERMISSION_KEYS]
      : [...DEFAULT_PERMISSION_KEYS, ...OPTIONAL_PERMISSION_KEYS];
  for (const permissionKey of keys) {
    await prisma.userPermission.upsert({
      where: { userId_permissionKey: { userId, permissionKey } },
      update: { isEnabled: true },
      create: { userId, permissionKey, isEnabled: true },
    });
  }
};

type RequestUser = NonNullable<Request["user"]>;

type UserSummary = {
  id: number;
  name: string;
  username: string;
  mobile: string;
  role: UserRole;
  centre_id: number | null;
  centre_name: string | null;
  centre_code: string | null;
  approval_status: ApprovalStatus;
  is_active: boolean;
  attendance_system_access: boolean;
  role_mode: RoleMode;
  created_at: Date;
};

// The Manage-Staff role picker collapses the permission bundle into one of three
// workspace roles. registerer = attendance/registration only (AE on, no calling);
// caller = calling/follow-up only (AE off); both = everything (AE on + calling).
export type RoleMode = "registerer" | "caller" | "both";
const CALLER_BUNDLE = ["calling_access", "followup_access", "batch_calling_access", "my_attendance_access"] as const;
const REGISTERER_BUNDLE = ["registration_access", "my_attendance_access"] as const;

const deriveRoleMode = (callingAccess: boolean, aeAccess: boolean): RoleMode => {
  if (callingAccess && aeAccess) return "both";
  if (callingAccess) return "caller";
  return "registerer";
};

// Batch-load the calling_access flag for a set of users so a list can be mapped
// without an N+1 permission query per row.
const getCallingAccessMap = async (userIds: number[]): Promise<Set<number>> => {
  if (!userIds.length) return new Set();
  const rows = await prisma.userPermission.findMany({
    where: { userId: { in: userIds }, permissionKey: "calling_access", isEnabled: true },
    select: { userId: true },
  });
  return new Set(rows.map((r) => r.userId));
};

const MANAGE_ROLES = new Set<UserRole>([UserRole.SUPER_ADMIN, UserRole.ADMIN]);

const assertManageUsersRole = (requester: RequestUser) => {
  if (!MANAGE_ROLES.has(requester.role)) {
    throw new ApiError(403, "Missing role: ADMIN or SUPER_ADMIN");
  }
};

const resolveCentreScope = (requester: RequestUser, requestedCentreId?: number | null) => {
  assertManageUsersRole(requester);

  if (requester.role === UserRole.SUPER_ADMIN) {
    return requestedCentreId ?? null;
  }

  // Use the per-request active centre (x-centre-id header, already validated against
  // UserCentreAccess by the resolveCentre middleware) — not requester.centreId, the user's
  // static "home centre" set at login. An admin who switches centres in the app must see
  // THAT centre's callers, not always the centre they originally logged in under. Every other
  // centre-scoped module (pool, batch) already reads getRequestContext().centreId; this was
  // the one inconsistent holdout, silently returning zero callers after a centre switch.
  const activeCentreId = getRequestContext().centreId;
  if (!activeCentreId) {
    throw new ApiError(400, "No active centre — send x-centre-id header");
  }

  if (requestedCentreId && requestedCentreId !== activeCentreId) {
    throw new ApiError(403, "You can only manage users inside your active centre");
  }

  return activeCentreId;
};

const getManagedRoleFilter = (requester: RequestUser) => {
  if (requester.role === UserRole.SUPER_ADMIN) {
    return UserRole.ADMIN;
  }

  return UserRole.CALLER;
};

const mapUserSummary = (
  user: User & { centre?: { id: number; name: string; code: string } | null },
  callingAccess = false,
): UserSummary => ({
  id: user.id,
  name: user.name,
  username: user.mobile,
  mobile: user.mobile,
  role: user.role,
  centre_id: user.centreId,
  centre_name: user.centre?.name ?? null,
  centre_code: user.centre?.code ?? null,
  approval_status: user.approvalStatus ?? ApprovalStatus.approved,
  is_active: user.isActive ?? true,
  attendance_system_access: user.attendanceSystemAccess ?? true,
  role_mode: deriveRoleMode(callingAccess, user.attendanceSystemAccess ?? true),
  created_at: user.createdAt,
});

// Map a list of users, resolving each one's role_mode from a single batched
// calling_access lookup.
const mapUserList = async (
  users: (User & { centre?: { id: number; name: string; code: string } | null })[],
): Promise<UserSummary[]> => {
  const callingSet = await getCallingAccessMap(users.map((u) => u.id));
  return users.map((u) => mapUserSummary(u, callingSet.has(u.id)));
};

const loadTargetUser = async (requester: RequestUser, userId: number) => {
  const user = await prisma.user.findUnique({
    where: { id: userId },
    include: {
      centre: true,
    },
  });

  if (!user) {
    throw new ApiError(404, "User not found");
  }

  const allowedCentreId = resolveCentreScope(requester);
  if (requester.role !== UserRole.SUPER_ADMIN && user.centreId !== allowedCentreId) {
    throw new ApiError(403, "You can only manage users inside your centre");
  }

  if (requester.role === UserRole.SUPER_ADMIN && user.role !== UserRole.ADMIN) {
    throw new ApiError(403, "Super Admin can only manage Centre Admin users");
  }

  if (requester.role === UserRole.ADMIN && user.role !== UserRole.CALLER) {
    throw new ApiError(403, "Centre ADMIN can only manage CALLER users");
  }

  return user;
};

const ensurePendingRequestAccess = (requester: RequestUser, target: User) => {
  assertManageUsersRole(requester);

  if (target.approvalStatus !== ApprovalStatus.pending) {
    throw new ApiError(409, "Only pending requests can be approved or rejected");
  }

  if (requester.role === UserRole.SUPER_ADMIN) {
    if (target.role !== UserRole.ADMIN) {
      throw new ApiError(403, "Super Admin can only approve Centre Admin requests");
    }

    return;
  }

  if (target.centreId !== requester.centreId) {
    throw new ApiError(403, "You can only manage users inside your centre");
  }

  if (target.role !== UserRole.CALLER) {
    throw new ApiError(403, "Centre ADMIN can only approve CALLER requests");
  }
};

const ensureMutableAccess = (requester: RequestUser, target: User) => {
  assertManageUsersRole(requester);

  if (requester.role === UserRole.SUPER_ADMIN) {
    if (target.role !== UserRole.ADMIN) {
      throw new ApiError(403, "Super Admin can only manage Centre Admin users");
    }

    return;
  }

  if (target.role !== UserRole.CALLER) {
    throw new ApiError(403, "Centre ADMIN can only manage CALLER users");
  }

  if (!requester.centreId || target.centreId !== requester.centreId) {
    throw new ApiError(403, "You can only manage users inside your centre");
  }
};

const updateUser = async (userId: number, data: Partial<Pick<User, "approvalStatus" | "isActive" | "attendanceSystemAccess">>) => {
  const user = await prisma.user.update({
    where: { id: userId },
    data,
    include: {
      centre: true,
    },
  });

  return mapUserSummary(user);
};

export const listPendingRequests = async (requester: RequestUser, centreId?: number | null) => {
  const resolvedCentreId = resolveCentreScope(requester, centreId);
  const where: Record<string, unknown> = {
    approvalStatus: ApprovalStatus.pending,
    role: getManagedRoleFilter(requester),
    ...(resolvedCentreId ? { centreId: resolvedCentreId } : {}),
  };

  const users = await prisma.user.findMany({
    where,
    include: {
      centre: true,
    },
    orderBy: [
      { createdAt: "desc" },
      { id: "desc" },
    ],
  });

  return {
    count: users.length,
    items: await mapUserList(users),
  };
};

export const listActiveUsers = async (requester: RequestUser, centreId?: number | null) => {
  const resolvedCentreId = resolveCentreScope(requester, centreId);
  const users = await prisma.user.findMany({
    where: {
      approvalStatus: ApprovalStatus.approved,
      isActive: true,
      role: getManagedRoleFilter(requester),
      ...(resolvedCentreId ? { centreId: resolvedCentreId } : {}),
    },
    include: {
      centre: true,
    },
    orderBy: [
      { createdAt: "desc" },
      { id: "desc" },
    ],
  });

  return {
    count: users.length,
    items: await mapUserList(users),
  };
};

export const listInactiveUsers = async (requester: RequestUser, centreId?: number | null) => {
  const resolvedCentreId = resolveCentreScope(requester, centreId);
  const users = await prisma.user.findMany({
    where: {
      approvalStatus: {
        in: [ApprovalStatus.approved, ApprovalStatus.rejected],
      },
      isActive: false,
      role: getManagedRoleFilter(requester),
      ...(resolvedCentreId ? { centreId: resolvedCentreId } : {}),
    },
    include: {
      centre: true,
    },
    orderBy: [
      { createdAt: "desc" },
      { id: "desc" },
    ],
  });

  return {
    count: users.length,
    items: await mapUserList(users),
  };
};

export const approveUserRequest = async (requester: RequestUser, userId: number) => {
  const target = await loadTargetUser(requester, userId);
  ensurePendingRequestAccess(requester, target);

  const updated = await updateUser(userId, {
    approvalStatus: ApprovalStatus.approved,
    isActive: true,
  });

  // Seed the user's default permissions so they can actually use the app after approval.
  await grantDefaultPermissions(target.id, target.role);

  const isCentreAdmin = target.role === UserRole.ADMIN;
  if (target.centreId) {
    await prisma.userCentreAccess.upsert({
      where: { userId_centreId: { userId: target.id, centreId: target.centreId } },
      update: { isAdmin: isCentreAdmin },
      create: { userId: target.id, centreId: target.centreId, isAdmin: isCentreAdmin },
    });
  }

  if (target.attendanceSystemAccess) {
    await enqueueOutbox("grant_ae_access", {
      username: target.mobile,
      name: target.name,
      centre_code: target.centre?.code ?? null,
      occurred_at: new Date().toISOString(),
    });

    // Sync the centre-admin role itself to AE. Without this, AE's own
    // centre_access table never learns about the assignment — a direct AE
    // login keeps showing the user's old/default centre indefinitely.
    if (isCentreAdmin && target.centreId) {
      await enqueueOutbox("grant_centre_admin_in_ae", {
        username: target.mobile,
        display_name: target.name,
        centre_code: target.centre?.code ?? null,
        occurred_at: new Date().toISOString(),
      });
    }
  }

  return updated;
};

export const rejectUserRequest = async (requester: RequestUser, userId: number) => {
  const target = await loadTargetUser(requester, userId);
  ensurePendingRequestAccess(requester, target);

  return updateUser(userId, {
    approvalStatus: ApprovalStatus.rejected,
    isActive: false,
    attendanceSystemAccess: false,
  });
};

export const activateUser = async (requester: RequestUser, userId: number) => {
  const target = await loadTargetUser(requester, userId);
  ensureMutableAccess(requester, target);

  if (target.approvalStatus !== ApprovalStatus.approved) {
    throw new ApiError(409, "Only approved users can be activated");
  }

  const activateResult = await updateUser(userId, { isActive: true });
  if (target.attendanceSystemAccess) {
    await enqueueOutbox("grant_ae_access", {
      username: target.mobile,
      name: target.name,
      centre_code: target.centre?.code ?? null,
      occurred_at: new Date().toISOString(),
    });
  }
  return activateResult;
};

export const deactivateUser = async (requester: RequestUser, userId: number) => {
  const target = await loadTargetUser(requester, userId);
  ensureMutableAccess(requester, target);

  if (target.approvalStatus !== ApprovalStatus.approved) {
    throw new ApiError(409, "Only approved users can be deactivated");
  }

  const deactivateResult = await updateUser(userId, { isActive: false });
  if (target.attendanceSystemAccess) {
    await enqueueOutbox("revoke_ae_access", {
      username: target.mobile,
      centre_code: target.centre?.code ?? null,
      occurred_at: new Date().toISOString(),
    });
  }
  return deactivateResult;
};

export const toggleAttendanceSystemAccess = async (requester: RequestUser, userId: number) => {
  const target = await loadTargetUser(requester, userId);
  ensureMutableAccess(requester, target);

  const newAccess = !(target.attendanceSystemAccess ?? false);
  const result = await updateUser(userId, { attendanceSystemAccess: newAccess });

  const centreCode = target.centre?.code ?? null;
  const occurredAt = new Date().toISOString();
  if (newAccess) {
    // AE access granted — create AE registerer account so the user can log into AE
    await enqueueOutbox("grant_ae_access", {
      username: target.mobile,
      name: target.name,
      centre_code: centreCode,
      occurred_at: occurredAt,
    });
  } else {
    // AE access revoked — remove centre_access entry so the user can no longer log into AE
    await enqueueOutbox("revoke_ae_access", {
      username: target.mobile,
      centre_code: centreCode,
      occurred_at: occurredAt,
    });
  }

  return result;
};

const aeBaseUrl = () => process.env.ATTENDEASE_BASE_URL ?? "http://127.0.0.1:6061";

// Synchronously drive AE (Flask) provisioning to the intended state AND read AE's
// actual DB back to confirm it landed — so the caller gets an immediate yes/no
// instead of trusting the async outbox job + the 3-min reconciler. Idempotent:
// safe to run alongside the outbox (Flask's create/revoke both guard on
// occurred_at and converge to the same end state). Verification reads the real
// centre_access table, so a "stale-skipped" endpoint response can't fake success.
const forceAeAccessAndVerify = async (
  mobile: string,
  name: string,
  centreCode: string | null,
  shouldHave: boolean,
): Promise<{ ae_ok: boolean; detail: string }> => {
  if (!env.attendeaseDbPath) return { ae_ok: false, detail: "AE DB path not configured — cannot verify" };
  if (!centreCode) return { ae_ok: !shouldHave, detail: "no centre code on user" };
  const occurred_at = new Date().toISOString();
  try {
    const path = shouldHave ? "/api/internal/create-user" : "/api/internal/revoke-ae-access";
    const body = shouldHave
      ? { username: mobile, name, centre_code: centreCode, role: "registerer", occurred_at }
      : { username: mobile, centre_code: centreCode, occurred_at };
    const res = await fetch(`${aeBaseUrl()}${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-internal-token": env.internalToken },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(5000),
    });
    if (!res.ok) return { ae_ok: false, detail: `AE ${shouldHave ? "create-user" : "revoke"} responded ${res.status}` };
  } catch (err) {
    return { ae_ok: false, detail: err instanceof Error ? err.message : String(err) };
  }
  // Source of truth: read AE's actual centre_access state. An access row that
  // exists but is 'revoked'/'inactive' does NOT count as granted.
  const hasAccess = attendeaseRepo
    .allUserAccess()
    .some((a) => a.username === mobile && a.centre_id === centreCode && (a.status ?? "active") === "active");
  const ae_ok = shouldHave ? hasAccess : !hasAccess;
  return {
    ae_ok,
    detail: ae_ok
      ? "verified against AE"
      : shouldHave
        ? "AE still missing centre_access after provisioning"
        : "AE still has centre_access after revoke",
  };
};

// Set a user's workspace role from the Manage-Staff picker. This is the single
// source of truth for the registerer/caller/both split: it rewrites the calling
// permission bundle AND flips AE (attendance) access + provisioning to match, so
// the app's mode gating (calling_access → caller mode; AE → registerer mode;
// both → the mode toggle) lands the user exactly where the admin intends.
//
// It does NOT fire-and-forget: after writing, it reads BOTH sides back (CB
// user_permissions + AE centre_access) and returns a `confirmation` block so the
// admin gets an immediate verified/half-done answer. The async outbox job stays
// as the durability fallback if this process dies mid-request; the 3-min AE
// reconciler is the final backstop. `verified:false` means the write is saved but
// AE provisioning didn't confirm yet — the app should say "saved, syncing…".
export const setUserRole = async (requester: RequestUser, userId: number, roleMode: RoleMode) => {
  const target = await loadTargetUser(requester, userId);
  ensureMutableAccess(requester, target);

  const enabled = new Set<string>(
    roleMode === "caller"
      ? CALLER_BUNDLE
      : roleMode === "registerer"
        ? REGISTERER_BUNDLE
        : [...CALLER_BUNDLE, ...REGISTERER_BUNDLE],
  );

  // Rewrite every default permission key so downgrading a role actually REVOKES
  // the keys it no longer includes (not just grants new ones).
  for (const permissionKey of DEFAULT_PERMISSION_KEYS) {
    const isEnabled = enabled.has(permissionKey);
    await prisma.userPermission.upsert({
      where: { userId_permissionKey: { userId, permissionKey } },
      update: { isEnabled },
      create: { userId, permissionKey, isEnabled },
    });
  }

  // registerer + both need AE access; caller does not.
  const aeAccess = roleMode !== "caller";
  const result = await updateUser(userId, { attendanceSystemAccess: aeAccess });

  const centreCode = target.centre?.code ?? null;
  const occurredAt = new Date().toISOString();
  // Durable fallback (survives a mid-request crash); the sync force below is what
  // actually confirms for the admin right now.
  await enqueueOutbox(aeAccess ? "grant_ae_access" : "revoke_ae_access", {
    username: target.mobile,
    ...(aeAccess ? { name: target.name } : {}),
    centre_code: centreCode,
    occurred_at: occurredAt,
  });

  // ── Confirmation: verify BOTH sides actually reached the intended state ──
  const perms = await prisma.userPermission.findMany({
    where: { userId },
    select: { permissionKey: true, isEnabled: true },
  });
  const permMap = new Map(perms.map((p) => [p.permissionKey, p.isEnabled]));
  const permissions_ok = DEFAULT_PERMISSION_KEYS.every((k) => permMap.get(k) === enabled.has(k));

  const ae = await forceAeAccessAndVerify(target.mobile, target.name, centreCode, aeAccess);

  return {
    ...result,
    role_mode: roleMode,
    confirmation: {
      permissions_ok,
      ae_ok: ae.ae_ok,
      verified: permissions_ok && ae.ae_ok,
      detail: ae.detail,
    },
  };
};
