import type { NextFunction, Request, Response } from "express";
import { ApprovalStatus } from "@prisma/client";
import jwt from "jsonwebtoken";
import { prisma } from "../config/prisma.js";
import { env } from "../config/env.js";
import { ApiError } from "../lib/api-error.js";
import { logger } from "../lib/logger.js";
import { ALL_PERMISSION_KEYS, type PermissionKey } from "../utils/permissions.js";
import { requestContextStorage } from "../lib/request-context.js";
import { bypassCentreScope } from "../lib/prisma-extension.js";

const extractBearerToken = (req: Request): string | null => {
  const header = req.headers.authorization;
  if (!header || !header.startsWith("Bearer ")) return null;
  const token = header.slice(7).trim();
  return token || null;
};

export const verifyToken = (token: string): string => {
  let payload: jwt.JwtPayload;
  try {
    payload = jwt.verify(token, env.jwtSharedSecret) as jwt.JwtPayload;
  } catch (err) {
    if (err instanceof jwt.TokenExpiredError) {
      throw new ApiError(401, "Token expired — please log in again");
    }
    logger.warn(`jwt_invalid: reason=${err instanceof Error ? err.message : String(err)}`);
    throw new ApiError(401, "Invalid token");
  }

  const username = payload.sub;
  if (typeof username !== "string" || !username) {
    logger.warn(`jwt_invalid: reason=missing_or_invalid_sub`);
    throw new ApiError(401, "Invalid token payload");
  }
  return username;
};

export const authenticateRequest = async (req: Request, _res: Response, next: NextFunction) => {
  const token = extractBearerToken(req);
  if (!token) {
    return next(new ApiError(401, "Missing Authorization header — Bearer token required"));
  }

  let username: string;
  try {
    username = verifyToken(token);
  } catch (err) {
    return next(err);
  }

  // Bypass centre scoping for the auth lookup — no centre context yet
  const user = await bypassCentreScope(() =>
    prisma.user.findFirst({
      where: { mobile: username },
      include: { permissions: { where: { isEnabled: true } } },
    }),
  );

  if (!user) {
    return next(new ApiError(401, "User not found"));
  }

  const approvalStatus = user.approvalStatus ?? ApprovalStatus.approved;
  const isActive = user.isActive ?? true;

  if (approvalStatus === ApprovalStatus.pending) {
    return next(new ApiError(403, "Your request is pending approval"));
  }
  if (approvalStatus === ApprovalStatus.rejected) {
    return next(new ApiError(403, "Your request was rejected"));
  }
  if (!isActive) {
    return next(new ApiError(403, "Your account is inactive"));
  }

  const permissions = user.permissions
    .map((p) => p.permissionKey)
    .filter((k): k is PermissionKey => ALL_PERMISSION_KEYS.includes(k as PermissionKey));

  req.user = {
    id: user.id,
    name: user.name,
    mobile: user.mobile,
    role: user.role,
    permissions,
    centreId: user.centreId,
    approvalStatus,
    isActive,
    attendanceSystemAccess: user.attendanceSystemAccess ?? true,
    mustChangePassword: user.mustChangePassword ?? true,
  };

  // Seed ALS with a partial context (no centreId yet — resolveCentre fills that).
  // This lets downstream code that only needs userId/permissions work before
  // resolveCentre runs (e.g. /me/centres which lists accessible centres).
  requestContextStorage.run(
    {
      userId: user.id,
      centreId: null,
      permissions: new Set(permissions),
      isAdminAtCentre: false,
      isHost: user.role === "SUPER_ADMIN",
    },
    next,
  );
};
