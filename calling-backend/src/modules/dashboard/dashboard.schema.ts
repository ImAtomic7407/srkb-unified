import { Category } from "@prisma/client";
import { z } from "zod";

export const dashboardSummaryQuerySchema = z.object({
  category: z.nativeEnum(Category),
  centre_id: z.coerce.number().int().positive().optional(),
});
