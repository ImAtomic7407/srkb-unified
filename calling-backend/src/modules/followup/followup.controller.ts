import type { Request, Response } from "express";
import { Category } from "@prisma/client";
import { ApiError } from "../../lib/api-error.js";
import { getFollowupList, markFollowupDone } from "./followup.service.js";
import { sendSuccess } from "../../lib/response.js";

export const followupListHandler = async (req: Request, res: Response) => {
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

  const result = await getFollowupList({
    category,
    callerId: req.user?.id,
    adminOverride,
    requester: req.user!,
  });
  sendSuccess(res, result, "Follow-up list loaded");
};

export const followupDoneHandler = async (req: Request, res: Response) => {
  const result = await markFollowupDone({
    registrationId: req.body.registration_id,
    dropdownValue: req.body.dropdown_value,
    remark: req.body.remark,
    requester: req.user!,
  });

  sendSuccess(res, result, "Follow-up attempt saved");
};
