import { Category } from "@prisma/client";
import { z } from "zod";

export const attendanceSessionsQuerySchema = z.object({
  category: z.nativeEnum(Category),
  batch_id: z.coerce.number().int().positive().optional(),
});

// Stubs retained for import compatibility during transition
export const attendanceConfigTestSchema = z.object({}).strip();
export const attendanceConfigSaveSchema = z.object({}).strip();
export const attendanceSyncSchema = z.object({}).strict();
export const attendanceSessionPreviewQuerySchema = z.object({}).strip();
export const attendanceSessionParamsSchema = z.object({ id: z.coerce.number().int().positive() });
export const attendanceSessionImportSchema = z.object({}).strict();