import {
  BatchCallingAssignmentMode,
  BatchCallingAssignmentStatus,
  BatchMoveRequestStatus,
  Category,
} from "@prisma/client";
import { z } from "zod";

export const batchCreateSchema = z.object({
  name: z.string().min(1, "name is required"),
  category: z.nativeEnum(Category),
});

export const batchQuerySchema = z.object({
  category: z.nativeEnum(Category),
});

export const batchIdParamSchema = z.object({
  id: z.coerce.number().int().positive(),
});

export const batchDisableParamSchema = batchIdParamSchema;

export const batchMoveRequestQuerySchema = z.object({
  category: z.nativeEnum(Category),
  status: z
    .nativeEnum(BatchMoveRequestStatus)
    .optional()
    .default(BatchMoveRequestStatus.pending),
});

export const batchMoveRequestIdParamSchema = z.object({
  id: z.coerce.number().int().positive(),
});

export const batchMoveRequestApproveSchema = z
  .object({
    batch_id: z.coerce.number().int().positive(),
    assignment_mode: z.nativeEnum(BatchCallingAssignmentMode),
    assigned_to_user_id: z.coerce.number().int().positive().optional(),
    active_days: z.coerce.number().int().positive().max(3650).optional(),
  })
  .superRefine((value, ctx) => {
    if (value.assignment_mode === BatchCallingAssignmentMode.manual && !value.assigned_to_user_id) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: "assigned_to_user_id is required for manual assignment",
        path: ["assigned_to_user_id"],
      });
    }
  });

export const batchMoveRequestRejectSchema = z.object({
  reason: z.string().trim().optional(),
});

export const batchCallingQuerySchema = z.object({
  category: z.nativeEnum(Category),
  batch_id: z.coerce.number().int().positive().optional(),
  status: z.nativeEnum(BatchCallingAssignmentStatus).optional().default(BatchCallingAssignmentStatus.active),
  search: z.string().trim().optional(),
  admin_override: z.string().optional(),
});

export const batchCallingDoneSchema = z.object({
  dropdown_id: z.coerce.number().int().positive(),
  remark: z.string().trim().min(1, "remark is required"),
});

export const batchCallingMemberParamSchema = z.object({
  batch_member_id: z.coerce.number().int().positive(),
});

export const batchSendBackSchema = z.object({
  reason: z.string().trim().optional(),
});
