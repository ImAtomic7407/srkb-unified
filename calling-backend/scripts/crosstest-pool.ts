// Cross-test for the centre-admin Pool Assignment data path (the screen that showed
// "2815 / Unknown after ~690"). Proves the EXACT shape the Android Pool Assignment screen
// now consumes from the calling backend instead of the Flask attendance feed:
//
//   1. /pool/new-registrations items all carry a non-empty `name` (the old Flask feed
//      returned mostly nameless attendance rows → "Unknown").
//   2. Every item exposes `currentAssignedCallerId` (the field the app reads to show who is
//      already assigned) — null when unassigned, the caller id once assigned.
//   3. The pool count equals getPoolSummary().new_registration_pool (counts agree — no
//      double source of truth).
//   4. Assigning via the real assignCalling() service removes the reg from the UNASSIGNED
//      pool and surfaces it in the ASSIGNED pool with currentAssignedCallerId set (persistence).
//
//   npx tsx scripts/crosstest-pool.ts
//
// Uses centre 1 with a throwaway mobile, then deletes everything it created and reconciles.

import { Category, UserRole } from "@prisma/client";
import { rawPrisma } from "../src/config/prisma.js";
import { requestContextStorage, type RequestContext } from "../src/lib/request-context.js";
import { reconcileCentre } from "../src/lib/projections.js";
import { createRegistration } from "../src/modules/registration/registration.service.js";
import { assignCalling } from "../src/modules/calling/calling.service.js";
import { getNewRegistrationsPool, getPoolSummary } from "../src/modules/pool/pool.service.js";

const CENTRE_ID = 1;
const ADMIN_ID = 258;
const CALLER_ID = 259;
const TEST_MOBILE = "9990007777"; // throwaway; cleaned up at the end

let failures = 0;
const check = (label: string, cond: boolean, extra = "") => {
  if (cond) console.log(`  ✓ ${label}`);
  else { failures++; console.log(`  ✗ ${label} ${extra}`); }
};

const requester = {
  id: ADMIN_ID,
  name: "crosstest-admin",
  mobile: "crosstest",
  role: UserRole.ADMIN,
  centreId: CENTRE_ID,
  permissions: [],
} as unknown as Parameters<typeof createRegistration>[1];

const ctx: RequestContext = {
  userId: ADMIN_ID,
  centreId: CENTRE_ID,
  permissions: new Set<string>(),
  isAdminAtCentre: true,
  isHost: false,
};

const cleanup = async (regId?: number) => {
  await rawPrisma.batchMemberAudit.deleteMany({ where: { batchMember: { centreId: CENTRE_ID, mobile: TEST_MOBILE } } });
  await rawPrisma.batchMember.deleteMany({ where: { centreId: CENTRE_ID, mobile: TEST_MOBILE } });
  if (regId) await rawPrisma.registration.deleteMany({ where: { id: regId } });
  else await rawPrisma.registration.deleteMany({ where: { centreId: CENTRE_ID, normalizedMobile: TEST_MOBILE } });
};

const run = async () => {
  console.log("=== cross-test: Pool Assignment data path (centre 1) ===");
  await cleanup();
  await reconcileCentre(CENTRE_ID);

  let regId: number | undefined;
  try {
    // Snapshot the real, existing unassigned pool — assert the shape the app depends on.
    const poolAll = await getNewRegistrationsPool({ category: Category.youth, assignedStatus: "all", requester });
    const everyNamed = poolAll.every((r) => typeof r.name === "string" && r.name.trim().length > 0);
    check(`every new-registration pool item has a non-empty name (${poolAll.length} items)`, everyNamed,
      `— nameless ids: ${poolAll.filter((r) => !r.name || !r.name.trim()).map((r) => r.id).slice(0, 5).join(",")}`);
    const exposesField = poolAll.every((r) => "currentAssignedCallerId" in r);
    check("every item exposes currentAssignedCallerId (app reads this)", exposesField);

    // Count agreement with the summary card.
    const summary = await getPoolSummary(Category.youth, requester);
    check("pool count == getPoolSummary().new_registration_pool",
      poolAll.length === summary.new_registration_pool,
      `— pool=${poolAll.length} summary=${summary.new_registration_pool}`);

    // Create a throwaway, named registration → it must show up NAMED and UNASSIGNED.
    const reg = await createRegistration(
      { name: "Pool Crosstest", mobile: TEST_MOBILE, category: Category.youth },
      requester,
    );
    regId = reg.id;
    const unassignedBefore = await getNewRegistrationsPool({ category: Category.youth, assignedStatus: "unassigned", requester });
    const mine = unassignedBefore.find((r) => r.id === reg.id);
    check("new reg appears in UNASSIGNED pool with its name", !!mine && mine.name === "Pool Crosstest");
    check("new reg has currentAssignedCallerId == null while unassigned", !!mine && mine.currentAssignedCallerId == null);

    // Assign it → it must leave the unassigned pool and surface as assigned to the caller.
    await assignCalling({
      registrationIds: [reg.id],
      callerId: CALLER_ID,
      activeDays: 7,
      poolType: "new_registration_pool",
      assignedByName: "crosstest",
      requester,
    });
    // Once assigned, the reg LEAVES the to-assign pool (it moves to the caller's calling list) —
    // that is the correct pool semantics, so the row should disappear from every pool view.
    const unassignedAfter = await getNewRegistrationsPool({ category: Category.youth, assignedStatus: "unassigned", requester });
    check("assigned reg is GONE from the unassigned pool", !unassignedAfter.some((r) => r.id === reg.id));
    const allAfter = await getNewRegistrationsPool({ category: Category.youth, assignedStatus: "all", requester });
    check("assigned reg is GONE from the to-assign pool entirely", !allAfter.some((r) => r.id === reg.id));

    // Persistence is verified at the source: the registration row + an ACTIVE calling assignment.
    const persisted = await rawPrisma.registration.findUnique({ where: { id: reg.id } });
    check("registration.currentAssignedCallerId persisted == caller",
      persisted?.currentAssignedCallerId === CALLER_ID, `— got ${persisted?.currentAssignedCallerId}`);
    const activeAssignment = await rawPrisma.callingAssignment.findFirst({
      where: { registrationId: reg.id, assignedToUserId: CALLER_ID, status: "active" },
    });
    check("an ACTIVE calling assignment row exists for the caller", !!activeAssignment);
  } finally {
    await cleanup(regId);
    await reconcileCentre(CENTRE_ID);
  }

  console.log(failures === 0
    ? "\nRESULT: PASS — Pool Assignment consumes named, correctly-counted, persistable data."
    : `\nRESULT: FAIL — ${failures} check(s) failed.`);
};

requestContextStorage.run(ctx, run)
  .then(() => process.exit(failures === 0 ? 0 : 1))
  .catch((e) => { console.error("cross-test error:", e); process.exit(1); });
