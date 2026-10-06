// Backfill default permissions for active, approved users who have ZERO enabled permissions.
// These users were approved before approveUserRequest seeded defaults, so resolvePermissions
// returned [] and they were locked out of every gated endpoint (callers had no calling_access →
// couldn't reach the caller screens at all).
//
//   npx tsx scripts/backfill-user-permissions.ts          (dry run — lists who would be granted)
//   npx tsx scripts/backfill-user-permissions.ts --apply  (grant defaults)
//
// Idempotent — only touches users with no enabled permission rows; safe to re-run.

import { UserRole } from "@prisma/client";
import { rawPrisma } from "../src/config/prisma.js";
import { grantDefaultPermissions } from "../src/modules/users/users.service.js";

const APPLY = process.argv.includes("--apply");

const run = async () => {
  console.log(`=== backfill-user-permissions (${APPLY ? "APPLY" : "DRY RUN"}) ===`);

  const users = await rawPrisma.user.findMany({
    where: { isActive: true, approvalStatus: "approved" },
    select: { id: true, name: true, mobile: true, role: true },
  });

  const targets: typeof users = [];
  for (const u of users) {
    const enabled = await rawPrisma.userPermission.count({ where: { userId: u.id, isEnabled: true } });
    if (enabled === 0) targets.push(u);
  }

  if (targets.length === 0) {
    console.log("No permission-less active users — nothing to backfill.");
    return;
  }

  console.log(`${targets.length} user(s) have no enabled permissions:`);
  for (const u of targets) {
    console.log(`  id=${u.id}  ${u.role.padEnd(11)}  ${u.name} (${u.mobile})`);
  }

  if (!APPLY) {
    console.log("\nDRY RUN — re-run with --apply to grant role-appropriate defaults.");
    return;
  }

  for (const u of targets) {
    await grantDefaultPermissions(u.id, u.role as UserRole);
  }
  console.log(`\nDONE — granted default permissions to ${targets.length} user(s).`);
};

run()
  .then(() => process.exit(0))
  .catch((e) => { console.error("backfill error:", e); process.exit(1); });
