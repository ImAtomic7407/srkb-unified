import type { Request, Response } from "express";
import { Category, UserRole } from "@prisma/client";
import { sendSuccess } from "../../lib/response.js";
import { getArchiveData } from "./archive.service.js";
import type { ArchiveStatusFilter } from "./archive.schema.js";

export const archiveListHandler = async (req: Request, res: Response) => {
  const category = req.query.category as Category;
  const search = typeof req.query.search === "string" ? req.query.search.trim() : undefined;
  const status = req.query.status as ArchiveStatusFilter | undefined;
  const requester = req.user!;
  const requestedCentreId = typeof req.query.centre_id === "number" ? req.query.centre_id : undefined;
  const effectiveRequester =
    requester.role === UserRole.SUPER_ADMIN && requestedCentreId
      ? { ...requester, centreId: requestedCentreId }
      : requester;
  const result = await getArchiveData({ category, search, status, requester: effectiveRequester });
  sendSuccess(res, result, "Archived data loaded");
};
