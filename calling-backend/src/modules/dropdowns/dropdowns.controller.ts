import type { Request, Response } from "express";
import { DropdownContext } from "@prisma/client";
import { ApiError } from "../../lib/api-error.js";
import { sendSuccess } from "../../lib/response.js";
import { getRequestContext } from "../../lib/request-context.js";
import {
  createDropdown,
  disableDropdown,
  listDropdowns,
  listDropdownsAdmin,
  revertDropdownOverride,
  updateDropdown,
} from "./dropdowns.service.js";

// Aliases for the app's outcome-picker contexts, which aren't real DropdownContext DB values.
const CONTEXT_ALIASES: Partial<Record<string, DropdownContext>> = {
  calling_outcome: DropdownContext.calling,
  followup_outcome: DropdownContext.followup,
};

export const listDropdownsHandler = async (req: Request, res: Response) => {
  const rawContext = req.query.context as string;
  const context = CONTEXT_ALIASES[rawContext] ?? (rawContext as DropdownContext);
  const items = await listDropdowns(
    context,
    req.query.category as "youth" | "congregation" | "both",
  );
  sendSuccess(res, { items, count: items.length }, "Dropdowns loaded");
};

export const listDropdownsAdminHandler = async (req: Request, res: Response) => {
  const ctx = getRequestContext();
  const centreId = req.query.centre_id ? Number(req.query.centre_id) : (ctx.centreId ?? undefined);
  const includeGlobal = req.query.include_global === "true" || req.query.include_global === "1";

  const items = await listDropdownsAdmin({
    centreId,
    includeGlobal,
    context: req.query.context as DropdownContext | undefined,
  });
  sendSuccess(res, { items, count: items.length }, "Admin dropdown list loaded");
};

export const createDropdownHandler = async (req: Request, res: Response) => {
  const ctx = getRequestContext();

  // centreId=null means global — host only
  const requestedCentreId = req.body.centre_id ?? ctx.centreId;
  if (requestedCentreId === null && !ctx.isHost) {
    throw new ApiError(403, "Only the host can create global dropdown options");
  }

  const created = await createDropdown({
    label: req.body.label,
    context: req.body.context,
    category: req.body.category,
    action: req.body.action,
    sortOrder: req.body.sort_order,
    centreId: requestedCentreId ?? null,
    deferDays: req.body.defer_days ?? null,
    sentiment: req.body.sentiment ?? null,
    countsAsStrike: req.body.counts_as_strike ?? null,
    removeAfterConsecutive: req.body.remove_after_consecutive ?? null,
    answeredScope: req.body.answered_scope ?? null,
  });

  sendSuccess(res, created, "Dropdown created", 201);
};

export const updateDropdownHandler = async (req: Request, res: Response) => {
  const id = Number(req.params.id);
  if (!Number.isInteger(id) || id <= 0) throw new ApiError(422, "Invalid dropdown id");

  const updated = await updateDropdown(id, {
    label: req.body.label,
    context: req.body.context,
    category: req.body.category,
    action: req.body.action,
    isActive: req.body.is_active,
    sortOrder: req.body.sort_order,
    // Passing undefined (key absent) preserves the stored interval; the service decides whether an
    // interval is allowed at all based on the action the option ends up with.
    deferDays: "defer_days" in req.body ? req.body.defer_days : undefined,
    // Same rule as defer_days: an absent key means "leave it alone", which is not the same as
    // sending null. The service inherits the stored value when these are undefined.
    sentiment: "sentiment" in req.body ? req.body.sentiment : undefined,
    countsAsStrike: "counts_as_strike" in req.body ? req.body.counts_as_strike : undefined,
    removeAfterConsecutive:
      "remove_after_consecutive" in req.body ? req.body.remove_after_consecutive : undefined,
    answeredScope: "answered_scope" in req.body ? req.body.answered_scope : undefined,
  });

  sendSuccess(res, updated, "Dropdown updated");
};

export const disableDropdownHandler = async (req: Request, res: Response) => {
  const id = Number(req.params.id);
  if (!Number.isInteger(id) || id <= 0) throw new ApiError(422, "Invalid dropdown id");

  const updated = await disableDropdown(id);
  sendSuccess(res, updated, "Dropdown disabled");
};

// Discard this centre's customisation of a shared option, so the shared one applies again.
export const revertDropdownHandler = async (req: Request, res: Response) => {
  const id = Number(req.params.id);
  if (!Number.isInteger(id) || id <= 0) throw new ApiError(422, "Invalid dropdown id");

  const result = await revertDropdownOverride(id);
  sendSuccess(res, result, "Reverted to the shared option");
};
