import { z } from "zod";

export const notificationQuerySchema = z.object({
  unread_only: z
    .enum(["true", "false", "1", "0"])
    .optional()
    .default("false")
    .transform((value) => value === "true" || value === "1"),
});

export const notificationIdParamSchema = z.object({
  id: z.coerce.number().int().positive(),
});
