import type { NextFunction, Request, Response } from "express";
import { ApiError } from "../lib/api-error.js";
import type { PermissionKey } from "../utils/permissions.js";

export const requirePermission = (permissionKey: PermissionKey) => {
  return (req: Request, _res: Response, next: NextFunction) => {
    if (!req.user) {
      return next(new ApiError(401, "Unauthenticated request"));
    }

    if (!req.user.permissions.includes(permissionKey)) {
      return next(new ApiError(403, `Missing permission: ${permissionKey}`));
    }

    next();
  };
};

export const requireAnyPermission = (permissionKeys: PermissionKey[]) => {
  return (req: Request, _res: Response, next: NextFunction) => {
    if (!req.user) {
      return next(new ApiError(401, "Unauthenticated request"));
    }

    const hasPermission = permissionKeys.some((permissionKey) =>
      req.user?.permissions.includes(permissionKey),
    );

    if (!hasPermission) {
      return next(new ApiError(403, `Missing one of: ${permissionKeys.join(", ")}`));
    }

    next();
  };
};

export const requireAdminOrPermission = (permissionKey: PermissionKey) => {
  return (req: Request, _res: Response, next: NextFunction) => {
    if (!req.user) {
      return next(new ApiError(401, "Unauthenticated request"));
    }

    const isAdmin = req.user.role === "SUPER_ADMIN" || req.user.role === "ADMIN";
    if (!isAdmin && !req.user.permissions.includes(permissionKey)) {
      return next(new ApiError(403, `Missing permission: ${permissionKey}`));
    }

    next();
  };
};