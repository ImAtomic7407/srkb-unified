import type { PermissionKey } from "../utils/permissions.js";
import type { ApprovalStatus, UserRole } from "@prisma/client";

declare global {
  namespace Express {
    interface Request {
      user?: {
        id: number;
        name: string;
        mobile: string;
        role: UserRole;
        permissions: PermissionKey[];
        centreId: number | null;
        approvalStatus: ApprovalStatus;
        isActive: boolean;
        attendanceSystemAccess: boolean;
        mustChangePassword: boolean;
      };
    }
  }
}

export {};
