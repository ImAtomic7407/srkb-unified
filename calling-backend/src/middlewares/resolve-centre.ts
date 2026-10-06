import type { NextFunction, Request, Response } from "express";
import { prisma } from "../config/prisma.js";
import { ApiError } from "../lib/api-error.js";
import { requestContextStorage } from "../lib/request-context.js";
import { bypassCentreScope } from "../lib/prisma-extension.js";

const parseCentreId = (value: unknown): number | null => {
  if (typeof value !== "string" || !value.trim()) return null;
  const n = Number(value);
  return Number.isInteger(n) && n > 0 ? n : null;
};

export const resolveCentre = async (req: Request, _res: Response, next: NextFunction) => {
  const user = req.user;
  if (!user) return next(new ApiError(401, "Not authenticated"));

  if (user.role === "SUPER_ADMIN") {
    const centreId =
      parseCentreId(req.header("x-centre-id")) ??
      parseCentreId(req.header("x-session-centre-id")) ??
      null;
    return requestContextStorage.run(
      {
        userId: user.id,
        centreId,
        permissions: new Set(user.permissions),
        isAdminAtCentre: false,
        isHost: true,
      },
      next,
    );
  }

  // Resolve centreId: prefer x-centre-id header, fall back to x-session-centre-id
  const centreId =
    parseCentreId(req.header("x-centre-id")) ??
    parseCentreId(req.header("x-session-centre-id"));

  if (!centreId) {
    return next(new ApiError(400, "Missing centre — send x-centre-id header"));
  }

  // Validate: user must have a UserCentreAccess row for this centre
  // Use bypassCentreScope since this lookup is about access control, not domain data
  const access = await bypassCentreScope(() =>
    prisma.userCentreAccess.findUnique({
      where: { userId_centreId: { userId: user.id, centreId } },
    }),
  );

  if (!access) {
    return next(new ApiError(403, "You do not have access to this centre"));
  }

  requestContextStorage.run(
    {
      userId: user.id,
      centreId,
      permissions: new Set(user.permissions),
      isAdminAtCentre: access.isAdmin,
      isHost: false,
    },
    next,
  );
};
