import { z } from "zod";

export const loginSchema = z.object({
  identifier: z.string().trim().min(1, "username/mobile is required"),
  password: z.string().min(1, "password is required"),
});

export const changePasswordSchema = z.object({
  old_password: z.string().optional(),
  new_password: z.string().min(8, "new_password must be at least 8 characters"),
});

export const registerRequestSchema = z.object({
  name: z.string().trim().min(2, "Name is required"),
  identifier: z.string().trim().min(1, "username/mobile is required"),
  password: z.string().min(1, "password is required"),
  requested_role: z.enum(["ADMIN", "CALLER"]),
  centre_id: z.coerce.number().int().positive("Centre is required"),
});
