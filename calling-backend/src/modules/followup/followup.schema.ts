import { z } from "zod";
import { Category } from "@prisma/client";

export const followupDoneSchema = z.object({
  registration_id: z.coerce.number().int().positive(),
  dropdown_value: z.string().min(1, "dropdown_value is required"),
  remark: z.string().trim().optional(),
});

export const followupListSchema = z.object({
  category: z.nativeEnum(Category),
  admin_override: z.string().optional(),
});
