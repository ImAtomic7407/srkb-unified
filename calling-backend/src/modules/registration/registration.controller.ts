import type { Request, Response } from "express";
import { Category } from "@prisma/client";
import {
  createRegistration,
  getProfileSuggestions,
  getRegistrationDetails,
  listRegistrations,
} from "./registration.service.js";
import { getMembershipsByMobile, manualReplace } from "../batch/batch-member.service.js";
import { rawPrisma } from "../../config/prisma.js";
import { ApiError } from "../../lib/api-error.js";
import { sendSuccess } from "../../lib/response.js";

export const register = async (req: Request, res: Response) => {
  const registration = await createRegistration(req.body, req.user);
  sendSuccess(res, registration, "Registration created", 201);
};

export const registrationDetailsHandler = async (req: Request, res: Response) => {
  const result = await getRegistrationDetails(Number(req.params.registrationId), req.user);
  sendSuccess(res, result, "Registration details loaded");
};

export const profileSuggestionHandler = async (req: Request, res: Response) => {
  const result = await getProfileSuggestions(req.query.mobile as string);
  sendSuccess(res, result, "Profile suggestions loaded");
};

export const getBatchMembershipHandler = async (req: Request, res: Response) => {
  const registration = await rawPrisma.registration.findUnique({
    where: { id: Number(req.params.registrationId) },
    select: { mobile: true, centreId: true, category: true },
  });
  if (!registration) throw new ApiError(404, "Registration not found");

  const memberships = await getMembershipsByMobile(
    registration.centreId,
    registration.category,
    registration.mobile,
  );
  sendSuccess(res, { mobile: registration.mobile, memberships }, "Batch memberships loaded");
};

export const setBatchMembershipHandler = async (req: Request, res: Response) => {
  const registration = await rawPrisma.registration.findUnique({
    where: { id: Number(req.params.registrationId) },
    select: { mobile: true, centreId: true, category: true },
  });
  if (!registration) throw new ApiError(404, "Registration not found");

  const batchIds: number[] = req.body.batch_ids;
  const changedBy = String(req.user!.id);

  await manualReplace(registration.centreId, registration.category, registration.mobile, batchIds, changedBy);
  const memberships = await getMembershipsByMobile(
    registration.centreId,
    registration.category,
    registration.mobile,
  );
  sendSuccess(res, { mobile: registration.mobile, memberships }, "Batch memberships updated");
};

export const listRegistrationsHandler = async (req: Request, res: Response) => {
  const result = await listRegistrations({
    requester: req.user,
    centreId: req.query.centre_id ? Number(req.query.centre_id) : undefined,
    category:
      typeof req.query.category === "string" && Object.values(Category).includes(req.query.category as Category)
        ? (req.query.category as Category)
        : undefined,
  });
  sendSuccess(res, result, "Registrations loaded");
};
