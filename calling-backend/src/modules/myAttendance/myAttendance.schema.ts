import { Category } from "@prisma/client";
import { z } from "zod";

export const proprietorshipQuerySchema = z.object({
  category: z.nativeEnum(Category),
  search: z.string().trim().optional(),
});

export const callingBatchesQuerySchema = z.object({
  category: z.nativeEnum(Category),
});

export const callingSessionsQuerySchema = z.object({
  batch_id: z.coerce.number().int().positive(),
});

export const callingAttendedQuerySchema = z.object({
  campaign_id: z.coerce.number().int().positive(),
});