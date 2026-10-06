import { z } from "zod";

// A year is the practical ceiling for "call me later" — anything longer is a data-entry slip, not an
// intention, and would silently park someone past the point where the roster still means anything.
export const DEFER_DAYS_MAX = 365;

export const dropdownContextSchema = z.enum([
  "calling",
  "followup",
  "my_attendance",
  "batch_calling",
  "removed_reuse",
]);

export const dropdownCategorySchema = z.enum(["youth", "congregation", "both"]);

// Declared meaning. The service normalises these through src/lib/response-rules.ts, so an
// illegal combination (e.g. positive + keeps calling) cannot be persisted even if a stale or
// hand-rolled client sends one.
export const sentimentSchema = z.enum(["positive", "negative", "not_reached"]);
export const answeredScopeSchema = z.enum(["answered", "not_answered", "both"]);

export const dropdownActionSchema = z.enum([
  "move_to_followup",
  "continue_calling",
  // Timed deferral ("call me next week"). Pairs with defer_days below — the interval lives on the
  // option, never parsed from the label. Was missing here while present in the Prisma enum, so the
  // admin API rejected every attempt to create or edit a deferral outcome with a 422.
  "defer_calling",
  "stay_in_followup",
  "remove_from_running",
  "mark_invalid",
  "archive",
  "restore",
  "reassign_calling",
  "request_move_to_batch",
  "move_to_batch_calling",
  "send_back_to_original_caller",
  "assign_to_online_team",
  "pause_personal_followup",
  "close_from_my_attendance",
  "move_to_another_batch",
  "keep_in_batch_calling",
]);

// The Android app's outcome pickers query context=calling_outcome/followup_outcome, which aren't
// real DropdownContext DB values — they alias to calling/followup (see listDropdownsHandler).
// Accepted only on the GET query, not on create/update (dropdownContextSchema stays DB-exact).
export const dropdownQueryContextSchema = z.enum([
  "calling",
  "followup",
  "my_attendance",
  "batch_calling",
  "removed_reuse",
  "calling_outcome",
  "followup_outcome",
]);

export const dropdownListSchema = z.object({
  context: dropdownQueryContextSchema,
  category: dropdownCategorySchema,
});

export const dropdownAdminListSchema = z.object({
  centre_id: z.coerce.number().int().positive().optional(),
  include_global: z.coerce.boolean().optional(),
  context: dropdownContextSchema.optional(),
});

export const dropdownCreateSchema = z.object({
  label: z.string().min(1, "label is required"),
  context: dropdownContextSchema,
  category: dropdownCategorySchema,
  action: dropdownActionSchema,
  sort_order: z.coerce.number().int().min(0),
  // null = global default (requires host role, enforced in service)
  centre_id: z.coerce.number().int().positive().nullable().optional(),
  // Deferral interval in days. Only meaningful when action = defer_calling; the service forces it to
  // null for every other action so a stale value can never make a non-deferral option park someone.
  defer_days: z.coerce.number().int().min(1).max(DEFER_DAYS_MAX).nullable().optional(),
  sentiment: sentimentSchema.optional(),
  counts_as_strike: z.boolean().optional(),
  // Minimum 2 — "remove after 1 in a row" is just an ordinary removal, and allowing 1 here
  // would let a misconfiguration wipe someone out on a single response.
  remove_after_consecutive: z.coerce.number().int().min(2).max(20).nullable().optional(),
  answered_scope: answeredScopeSchema.optional(),
});

export const dropdownUpdateSchema = z.object({
  label: z.string().min(1).optional(),
  context: dropdownContextSchema.optional(),
  category: dropdownCategorySchema.optional(),
  action: dropdownActionSchema.optional(),
  is_active: z.boolean().optional(),
  sort_order: z.coerce.number().int().min(0).optional(),
  defer_days: z.coerce.number().int().min(1).max(DEFER_DAYS_MAX).nullable().optional(),
  sentiment: sentimentSchema.optional(),
  counts_as_strike: z.boolean().optional(),
  remove_after_consecutive: z.coerce.number().int().min(2).max(20).nullable().optional(),
  answered_scope: answeredScopeSchema.optional(),
});

export const dropdownIdParamSchema = z.object({
  id: z.coerce.number().int().positive(),
});
