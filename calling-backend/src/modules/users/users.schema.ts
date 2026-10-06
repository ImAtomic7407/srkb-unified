import { UserRole } from "@prisma/client";
import { z } from "zod";

export const userIdParamSchema = z.object({
  id: z.coerce.number().int().positive(),
});

export const userCentreQuerySchema = z.object({
  centre_id: z.coerce.number().int().positive().optional(),
});

export const userManagementQuerySchema = userCentreQuerySchema;

export const userUserRoleSchema = z.nativeEnum(UserRole);

