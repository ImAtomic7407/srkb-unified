import type { Request, Response } from "express";
import { Category } from "@prisma/client";
import { assignCalling, getCallingList, markCallingDone } from "./calling.service.js";
import { sendSuccess } from "../../lib/response.js";
import { ApiError } from "../../lib/api-error.js";

export const assignCallingHandler = async (req: Request, res: Response) => {
  const result = await assignCalling({
    registrationIds: req.body.registration_ids,
    callerId: req.body.caller_id,
    activeDays: req.body.active_days,
    poolType: req.body.pool_type,
    assignedByName: req.user!.name,
    requester: req.user!,
  });

  sendSuccess(res, result, "Calling assignments created", 201);
};

export const callingListHandler = async (req: Request, res: Response) => {
  const category = req.query.category as Category;
  const adminOverride =
    typeof req.query.admin_override === "string"
      ? ["1", "true", "yes"].includes(req.query.admin_override.toLowerCase())
      : false;

  if (
    adminOverride &&
    !req.user?.permissions.some(
      (permission) => permission === "assign_calling_access" || permission === "report_access",
    )
  ) {
    throw new ApiError(403, "Admin override requires assign_calling_access or report_access");
  }

  const result = await getCallingList({
    category,
    adminOverride,
    requester: req.user!,
  });
  sendSuccess(res, result, "Calling list loaded");
};

export const callingDoneHandler = async (req: Request, res: Response) => {
  const result = await markCallingDone({
    registrationId: req.body.registration_id,
    dropdownValue: req.body.dropdown_value,
    dropdownId: req.body.dropdown_id,
    remark: req.body.remark,
    requester: req.user!,
  });

  sendSuccess(res, result, "Calling attempt saved");
};
