import { z } from "zod";
import { Category, DropdownCategoryScope, DropdownContext } from "@prisma/client";

export const downloadPeopleQuerySchema = z.object({
  category: z.nativeEnum(Category),
  centre_id: z.coerce.number().int().positive().optional(),
});

export const downloadDropdownQuerySchema = z.object({
  context: z.nativeEnum(DropdownContext).optional(),
  category: z.nativeEnum(DropdownCategoryScope).optional(),
});
