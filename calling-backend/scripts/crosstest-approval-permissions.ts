// Cross-test: approving a user now seeds their default permissions, so approved CALLERs actually
// have calling_access (the bug that left the whole caller workforce locked out of the caller screens).
//
//   npx tsx scripts/crosstest-approval-permissions.ts
//
// Creates a throwaway pending caller at centre 1, approves it as the centre admin, asserts the
// default permissions were granted, then deletes everything it created.

import { ApprovalStatus, UserRole } from "@prisma/client";
import { rawPrisma } from "../src/config/prisma.js";
import { approveUserRequest } from "../src/modules/users/users.service.js";
import { DEFAULT_PERMISSION_KEYS } from "../src/utils/permissions.js";

const CENTRE = 1;
const ADMIN_ID = 258;
const TEST_MOBILE = "xtest_caller_perm";

let fails = 0;
const check = (label: string, cond: boolean, extra = "") => {
  if (cond) console.log(`  ✓ ${label}`);
  else { fails++; console.log(`  ✗ ${label} ${extra}`); }
};

const adminRequester = {
  id: ADMIN_ID,
  name: "crosstest-admin",
  mobile: "crosstest",
  role: UserRole.ADMIN,
  centreId: CENTRE,
  permissions: ["manage_users_access"],
} as unknown as Parameters<typeof approveUserRequest>[0];

const cleanup = async () => {
  const u = await rawPrisma.user.findFirst({ where: { mobile: TEST_MOBILE } });
  if (u) {
    await rawPrisma.userPermission.deleteMany({ where: { userId: u.id } });
    await rawPrisma.userCentreAccess.deleteMany({ where: { userId: u.id } });
    await rawPrisma.user.delete({ where: { id: u.id } });
  }
};

const run = async () => {
  console.log("=== cross-test: approval grants default permissions (centre 1) ===");
  await cleanup();

  const caller = await rawPrisma.user.create({
    data: {
      name: "Crosstest Caller",
      mobile: TEST_MOBILE,
      role: UserRole.CALLER,
      centreId: CENTRE,
      approvalStatus: ApprovalStatus.pending,
      isActive: false,
    },
  });

  try {
    const before = await rawPrisma.userPermission.count({ where: { userId: caller.id, isEnabled: true } });
    check("pending caller starts with zero permissions", before === 0);

    await approveUserRequest(adminRequester, caller.id);

    const granted = await rawPrisma.userPermission.findMany({
      where: { userId: caller.id, isEnabled: true },
      select: { permissionKey: true },
    });
    const keys = new Set(granted.map((g) => g.permissionKey));
    check("approval granted calling_access", keys.has("calling_access"));
    check("approval granted ALL caller defaults",
      DEFAULT_PERMISSION_KEYS.every((k) => keys.has(k)),
      `— missing: ${DEFAULT_PERMISSION_KEYS.filter((k) => !keys.has(k)).join(",")}`);
  } finally {
    await cleanup();
  }

  console.log(fails === 0
    ? "\nRESULT: PASS — approved users get their default permissions (callers can call)."
    : `\nRESULT: FAIL — ${fails} check(s) failed.`);
  process.exit(fails === 0 ? 0 : 1);
};

run().catch((e) => { console.error("approval-perms test error:", e); process.exit(1); });
