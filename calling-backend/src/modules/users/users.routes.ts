import { Router, type NextFunction, type Request, type Response } from "express";
import { ApprovalStatus, UserRole } from "@prisma/client";
import { asyncHandler } from "../../lib/async-handler.js";
import { ApiError } from "../../lib/api-error.js";
import { validateBody, validateParams, validateQuery } from "../../middlewares/validate.js";
import { userCentreQuerySchema, userIdParamSchema } from "./users.schema.js";
import {
  activateUserHandler,
  approveUserRequestHandler,
  deactivateUserHandler,
  listActiveUsersHandler,
  listInactiveUsersHandler,
  listPendingRequestsHandler,
  rejectUserRequestHandler,
  setUserRoleHandler,
  toggleAttendanceSystemAccessHandler,
} from "./users.controller.js";
import { adminResetPassword, hashPassword } from "../auth/auth.service.js";
import { grantDefaultPermissions } from "./users.service.js";
import { enqueueOutbox } from "../../lib/outbox.js";
import { rawPrisma } from "../../config/prisma.js";
import { z } from "zod";

const requireManageUsersRole = (req: Request, _res: Response, next: NextFunction) => {
  if (!req.user) {
    return next(new ApiError(401, "Unauthenticated request"));
  }

  if (req.user.role !== UserRole.SUPER_ADMIN && req.user.role !== UserRole.ADMIN) {
    return next(new ApiError(403, "Missing role: ADMIN or SUPER_ADMIN"));
  }

  return next();
};

export const usersRouter = Router();

usersRouter.get(
  "/users/requests",
  requireManageUsersRole,
  validateQuery(userCentreQuerySchema),
  asyncHandler(listPendingRequestsHandler),
);
usersRouter.get(
  "/users/active",
  requireManageUsersRole,
  validateQuery(userCentreQuerySchema),
  asyncHandler(listActiveUsersHandler),
);
usersRouter.get(
  "/users/inactive",
  requireManageUsersRole,
  validateQuery(userCentreQuerySchema),
  asyncHandler(listInactiveUsersHandler),
);
usersRouter.patch(
  "/users/:id/approve",
  requireManageUsersRole,
  validateParams(userIdParamSchema),
  asyncHandler(approveUserRequestHandler),
);
usersRouter.patch(
  "/users/:id/reject",
  requireManageUsersRole,
  validateParams(userIdParamSchema),
  asyncHandler(rejectUserRequestHandler),
);
usersRouter.patch(
  "/users/:id/activate",
  requireManageUsersRole,
  validateParams(userIdParamSchema),
  asyncHandler(activateUserHandler),
);
usersRouter.patch(
  "/users/:id/deactivate",
  requireManageUsersRole,
  validateParams(userIdParamSchema),
  asyncHandler(deactivateUserHandler),
);
usersRouter.patch(
  "/users/:id/toggle-attendance-access",
  requireManageUsersRole,
  validateParams(userIdParamSchema),
  asyncHandler(toggleAttendanceSystemAccessHandler),
);
usersRouter.patch(
  "/users/:id/role",
  requireManageUsersRole,
  validateParams(userIdParamSchema),
  asyncHandler(setUserRoleHandler),
);

// POST /admin/users/:username/reset-password
// Permission: SUPER_ADMIN, or ADMIN who is a centre admin of at least one centre
// the target user belongs to.
const resetPasswordSchema = z.object({ new_password: z.string().min(8) });

usersRouter.post(
  "/admin/users/:username/reset-password",
  requireManageUsersRole,
  validateBody(resetPasswordSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const targetUsername = String(req.params.username ?? "");
    const requester = req.user!;

    if (requester.role !== UserRole.SUPER_ADMIN) {
      // ADMIN: must be a centre admin for at least one centre the target user belongs to
      const target = await rawPrisma.user.findFirst({ where: { mobile: targetUsername }, select: { id: true } });
      if (!target) throw new ApiError(404, "User not found");

      const targetCentres = await rawPrisma.userCentreAccess.findMany({
        where: { userId: target.id },
        select: { centreId: true },
      });
      const targetCentreIds = new Set(targetCentres.map((r) => r.centreId));

      const requesterAdminCentres = await rawPrisma.userCentreAccess.findMany({
        where: { userId: requester.id, isAdmin: true },
        select: { centreId: true },
      });
      const hasOverlap = requesterAdminCentres.some((r) => targetCentreIds.has(r.centreId));
      if (!hasOverlap) throw new ApiError(403, "You are not an admin for any centre this user belongs to");
    }

    await adminResetPassword(targetUsername, req.body.new_password, requester.mobile);
    res.json({ ok: true, message: "Password reset — user must change on next login" });
  }),
);

// POST /admin/users — create a caller/registerer directly under the ADMIN's centre
const createUserSchema = z.object({
  name: z.string().min(1),
  username: z.string().min(1),
  password: z.string().min(6),
  role: z.enum(["CALLER", "ADMIN"]).default("CALLER"),
  ae_access: z.boolean().default(false),
});

usersRouter.post(
  "/admin/users",
  requireManageUsersRole,
  validateBody(createUserSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const requester = req.user!;
    const centreId = requester.role === UserRole.SUPER_ADMIN
      ? (req.body.centre_id ? Number(req.body.centre_id) : null)
      : requester.centreId;

    if (!centreId) throw new ApiError(400, "centreId is required for SUPER_ADMIN");

    const mobile = String(req.body.username).trim();
    const existing = await rawPrisma.user.findFirst({ where: { OR: [{ mobile }, { name: mobile }] } });
    if (existing) throw new ApiError(409, "A user with this username already exists");

    const aeAccess = req.body.ae_access ?? false;
    const plainPassword = String(req.body.password);

    const user = await rawPrisma.user.create({
      data: {
        name: String(req.body.name).trim(),
        mobile,
        role: req.body.role as UserRole,
        centreId,
        approvalStatus: ApprovalStatus.approved,
        isActive: true,
        attendanceSystemAccess: aeAccess,
        passwordHash: hashPassword(plainPassword),
        mustChangePassword: false,
      },
      include: { centre: true },
    });

    // approveUserRequest also seeds the user's default permissions — this
    // direct-create path used to skip it, so admin-created CALLERs were born
    // with zero user_permissions rows (no calling_access), which locked them
    // out of caller mode: no "Calling" tab, no registerer/caller toggle. Seed
    // them here exactly as the approval path does.
    await grantDefaultPermissions(user.id, user.role as UserRole);

    // approveUserRequest also creates a matching userCentreAccess row — this
    // direct-create path used to skip it too, so admin-scoped checks elsewhere
    // (e.g. reset-password's "requester is admin of this user's centre" test)
    // found no row and rejected valid admins for users they'd just created.
    await rawPrisma.userCentreAccess.upsert({
      where: { userId_centreId: { userId: user.id, centreId } },
      update: { isAdmin: user.role === UserRole.ADMIN },
      create: { userId: user.id, centreId, isAdmin: user.role === UserRole.ADMIN },
    });

    // Every other path that grants AE access (approveUserRequest, activateUser,
    // toggleAttendanceSystemAccess) enqueues grant_ae_access so AE provisions a
    // matching registerer_account — this direct-create path used to skip it,
    // silently leaving ae_access=true users with no way to log into AE.
    if (aeAccess) {
      await enqueueOutbox("grant_ae_access", {
        username: user.mobile,
        name: user.name,
        centre_code: user.centre?.code ?? null,
        plain_password: plainPassword,
        occurred_at: new Date().toISOString(),
      });
    }

    res.json({ ok: true, id: user.id, username: user.mobile, name: user.name });
  }),
);

// PATCH /admin/users/:id/username — rename a user's login username
const changeUsernameSchema = z.object({ new_username: z.string().min(1) });

usersRouter.patch(
  "/admin/users/:id/username",
  requireManageUsersRole,
  validateParams(userIdParamSchema),
  validateBody(changeUsernameSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const id = Number(req.params.id);
    const newMobile = String(req.body.new_username).trim();

    const existing = await rawPrisma.user.findFirst({ where: { OR: [{ mobile: newMobile }, { name: newMobile }] } });
    if (existing && existing.id !== id) throw new ApiError(409, "Username already taken");

    const old = await rawPrisma.user.findUnique({ where: { id }, select: { mobile: true } });
    await rawPrisma.user.update({ where: { id }, data: { mobile: newMobile } });
    // Sync to AE: rename the user in registerer_accounts + centre_access
    if (old) {
      await enqueueOutbox("rename_user_in_ae", {
        old_username: old.mobile,
        new_username: newMobile,
        occurred_at: new Date().toISOString(),
      });
    }
    res.json({ ok: true });
  }),
);

// DELETE /admin/users/:id — permanently remove a user
usersRouter.delete(
  "/admin/users/:id",
  requireManageUsersRole,
  validateParams(userIdParamSchema),
  asyncHandler(async (req: Request, res: Response) => {
    const requester = req.user!;
    const id = Number(req.params.id);

    if (id === requester.id) throw new ApiError(400, "Cannot delete yourself");

    const target = await rawPrisma.user.findUnique({ where: { id } });
    if (!target) throw new ApiError(404, "User not found");
    if (target.role === UserRole.SUPER_ADMIN) throw new ApiError(403, "Cannot delete a SUPER_ADMIN");

    // ADMIN can only delete users in their own centre
    if (requester.role === UserRole.ADMIN && target.centreId !== requester.centreId) {
      throw new ApiError(403, "User is not in your centre");
    }

    await rawPrisma.user.delete({ where: { id } });
    // Sync to AE: remove the user's centre_access + registerer_accounts entry
    await enqueueOutbox("delete_user_from_ae", {
      username: target.mobile,
      occurred_at: new Date().toISOString(),
    });
    res.json({ ok: true });
  }),
);

