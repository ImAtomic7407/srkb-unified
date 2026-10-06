import { Category } from "@prisma/client";
import { z } from "zod";

export const archiveQuerySchema = z.object({
  category: z.nativeEnum(Category),
  search: z.string().trim().optional(),
  status: z.enum(["all", "removed", "archived", "invalid"]).optional(),
  centre_id: z.coerce.number().int().positive().optional(),
});

export type ArchiveStatusFilter = z.infer<typeof archiveQuerySchema>["status"];
