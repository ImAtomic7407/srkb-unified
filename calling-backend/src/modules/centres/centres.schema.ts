import { z } from "zod";

const allowedCategoryValues = ["youth", "congregation", "both"] as const;

export const centreIdSchema = z.object({
  id: z.coerce.number().int().positive(),
});

export const centreCreateSchema = z.object({
  name: z.string().trim().min(1, "name is required"),
  code: z.string().trim().min(1, "code is required"),
  allowed_categories: z.enum(allowedCategoryValues),
  is_active: z.boolean().optional(),
});

export const centreUpdateSchema = z
  .object({
    name: z.string().trim().min(1).optional(),
    code: z.string().trim().min(1).optional(),
    allowed_categories: z.enum(allowedCategoryValues).optional(),
    is_active: z.boolean().optional(),
  })
  .refine((value) => Object.values(value).some((entry) => entry !== undefined), {
    message: "At least one field must be provided",
  });

