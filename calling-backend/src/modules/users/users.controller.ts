import type { Request, Response } from "express";
import { sendSuccess } from "../../lib/response.js";
import {
  activateUser,
  approveUserRequest,
  deactivateUser,
  listActiveUsers,
  listInactiveUsers,
  listPendingRequests,
  rejectUserRequest,
  setUserRole,
  toggleAttendanceSystemAccess,
  type RoleMode,
} from "./users.service.js";

const ROLE_MODES = new Set<RoleMode>(["registerer", "caller", "both"]);

export const listPendingRequestsHandler = async (req: Request, res: Response) => {
  const result = await listPendingRequests(req.user!, req.query.centre_id ? Number(req.query.centre_id) : undefined);
  sendSuccess(res, result, "Pending requests loaded");
};

export const listActiveUsersHandler = async (req: Request, res: Response) => {
  const result = await listActiveUsers(req.user!, req.query.centre_id ? Number(req.query.centre_id) : undefined);
  sendSuccess(res, result, "Active users loaded");
};

export const listInactiveUsersHandler = async (req: Request, res: Response) => {
  const result = await listInactiveUsers(req.user!, req.query.centre_id ? Number(req.query.centre_id) : undefined);
  sendSuccess(res, result, "Inactive users loaded");
};

export const approveUserRequestHandler = async (req: Request, res: Response) => {
  const result = await approveUserRequest(req.user!, Number(req.params.id));
  sendSuccess(res, result, "User approved");
};

export const rejectUserRequestHandler = async (req: Request, res: Response) => {
  const result = await rejectUserRequest(req.user!, Number(req.params.id));
  sendSuccess(res, result, "User rejected");
};

export const activateUserHandler = async (req: Request, res: Response) => {
  const result = await activateUser(req.user!, Number(req.params.id));
  sendSuccess(res, result, "User activated");
};

export const deactivateUserHandler = async (req: Request, res: Response) => {
  const result = await deactivateUser(req.user!, Number(req.params.id));
  sendSuccess(res, result, "User deactivated");
};

export const toggleAttendanceSystemAccessHandler = async (req: Request, res: Response) => {
  const result = await toggleAttendanceSystemAccess(req.user!, Number(req.params.id));
  sendSuccess(res, result, "Attendance system access updated");
};

export const setUserRoleHandler = async (req: Request, res: Response) => {
  const role = String(req.body?.role ?? "") as RoleMode;
  if (!ROLE_MODES.has(role)) {
    res.status(400).json({ ok: false, message: "role must be one of: registerer, caller, both" });
    return;
  }
  const result = await setUserRole(req.user!, Number(req.params.id), role);
  sendSuccess(res, result, "User role updated");
};

