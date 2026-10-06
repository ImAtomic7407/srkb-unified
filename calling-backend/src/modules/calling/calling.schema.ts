import { z } from "zod";
import { Category } from "@prisma/client";

export const poolTypeSchema = z
  .enum(["new_registration_pool", "remaining_followup_pool"])
  .optional()
  .default("new_registration_pool");

export const assignCallingSchema = z.object({
  registration_ids: z
    .array(z.coerce.number().int().positive())
    .min(1, "At least one registration_id is required")
    .refine((ids) => new Set(ids).size === ids.length, "registration_ids must be unique"),
  caller_id: z.coerce.number().int().positive(),
  active_days: z.coerce.number().int().positive().max(3650),
  pool_type: poolTypeSchema,
});

export const callingDoneSchema = z.object({
  registration_id: z.coerce.number().int().positive(),
  dropdown_value: z.string().min(1, "dropdown_value is required"),
  // Which response was tapped. Optional — older builds send only dropdown_value, and the server
  // still resolves those by name (current or former). Sending it survives any rename.
  dropdown_id: z.coerce.number().int().positive().optional(),
  remark: z.string().trim().optional(),
});

export const categoryQuerySchema = z.object({
  category: z.nativeEnum(Category),
  caller_id: z.coerce.number().int().positive().optional(),
  admin_override: z.string().optional(),
});
