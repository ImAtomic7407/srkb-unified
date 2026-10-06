import { z } from "zod";
import { Category } from "@prisma/client";

export const registerSchema = z.object({
  name: z.string().min(2, "Name is required"),
  mobile: z.string().min(7, "Mobile is required"),
  address: z.string().trim().optional(),
  occupation: z.string().trim().optional(),
  category: z.nativeEnum(Category),
  explicit_batch_id: z.number().int().positive().optional(),
});

export const registrationIdSchema = z.object({
  registrationId: z.coerce.number().int().positive(),
});

export const registrationListQuerySchema = z.object({
  centre_id: z.coerce.number().int().positive().optional(),
  category: z.nativeEnum(Category).optional(),
});

export const batchMembershipBodySchema = z.object({
  batch_ids: z.array(z.number().int().positive()).min(1, "At least one batch_id is required"),
});

export const profileSuggestionQuerySchema = z.object({
  mobile: z.string().min(7, "Mobile is required"),
});
