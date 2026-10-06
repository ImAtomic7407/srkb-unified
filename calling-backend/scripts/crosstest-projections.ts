// Cross-test for the event-driven read model.
//
// Drives the REAL domain services through a registration's full lifecycle inside a simulated
// request context, and after EVERY transition asserts the invariant that makes the whole
// design correct:  every cached centre_stats counter == a fresh from-source recount (zero drift).
// If any event is mis-wired, an audit will show cached != fresh and the test fails loudly.
//
//   npx tsx scripts/crosstest-projections.ts
//
// Uses centre 1 with a throwaway mobile, then deletes everything it created and reconciles,
// so live data is left exactly as found.

import { Category, UserRole } from "@prisma/client";
import { rawPrisma } from "../src/config/prisma.js";
import { requestContextStorage, type RequestContext } from "../src/lib/request-context.js";
import { auditCentre, reconcileCentre } from "../src/lib/projections.js";
import { createRegistration } from "../src/modules/registration/registration.service.js";
import { assignCalling, markCallingDone } from "../src/modules/calling/calling.service.js";
import { markFollowupDone } from "../src/modules/followup/followup.service.js";

const CENTRE_ID = 1;
const ADMIN_ID = 258;
const CALLER_ID = 259;
const TEST_MOBILE = "9990001234"; // throwaway; cleaned up at the end
const CALLING_MOVE_LABEL = "Move to follow-up"; // → move_to_followup
const FOLLOWUP_STAY_LABEL = "Stay in follow-up"; // → stay_in_followup

let failures = 0;

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

const audit = async (label: string) => {
  const rows = await auditCentre(CENTRE_ID);
  const bad = rows.filter((r) => !r.ok);
  if (bad.length === 0) {
    console.log(`  ✓ [${label}] zero drift across ${rows.length} counters`);
  } else {
    failures += bad.length;
    console.log(`  ✗ [${label}] DRIFT in ${bad.length} counter(s):`);
    for (const b of bad) console.log(`      ${b.key}: cached=${b.cached} fresh=${b.fresh}`);
  }
};

const cleanup = async (regId?: number) => {
  // Remove anything the test created so live data is untouched.
  await rawPrisma.batchMemberAudit.deleteMany({
    where: { batchMember: { centreId: CENTRE_ID, mobile: TEST_MOBILE } },
  });
  await rawPrisma.batchMember.deleteMany({ where: { centreId: CENTRE_ID, mobile: TEST_MOBILE } });
  if (regId) {
    // Registration cascade removes callingAssignment / callAttempt / followup / etc.
    await rawPrisma.registration.deleteMany({ where: { id: regId } });
  } else {
    await rawPrisma.registration.deleteMany({
      where: { centreId: CENTRE_ID, normalizedMobile: TEST_MOBILE },
    });
  }
};

const run = async () => {
  console.log("=== cross-test: event-driven read model (centre 1) ===");

  // Pre-clean in case a prior aborted run left rows.
  await cleanup();

  // Baseline: reconcile so the cache is known-exact, then confirm audit is clean.
  await reconcileCentre(CENTRE_ID);
  await audit("baseline (post-reconcile)");

  let regId: number | undefined;
  try {
    // 1) Register a new person → registrations_total + new_registrations should rise.
    const reg = await createRegistration(
      { name: "Crosstest Person", mobile: TEST_MOBILE, category: Category.youth },
      requester,
    );
    regId = reg.id;
    await audit("after register");

    // 2) Assign to calling (new pool) → new_registrations ↓, active_calling ↑.
    await assignCalling({
      registrationIds: [reg.id],
      callerId: CALLER_ID,
      activeDays: 7,
      poolType: "new_registration_pool",
      assignedByName: "crosstest",
      requester,
    });
    await audit("after assign-calling");

    // 3) Calling done → move to follow-up → active_calling ↓, pending_followups ↑.
    await markCallingDone({
      registrationId: reg.id,
      dropdownValue: CALLING_MOVE_LABEL,
      requester,
    });
    await audit("after calling-done (move_to_followup)");

    // 4) Follow-up done → stay in follow-up → counters unchanged but recompute must hold.
    await markFollowupDone({
      registrationId: reg.id,
      dropdownValue: FOLLOWUP_STAY_LABEL,
      requester,
    });
    await audit("after followup-done (stay_in_followup)");
  } finally {
    await cleanup(regId);
    // Restore the cache to exact truth after the test mutations are removed.
    await reconcileCentre(CENTRE_ID);
    await audit("after cleanup + reconcile");
  }

  console.log(
    failures === 0
      ? "\nRESULT: PASS — read model stayed consistent with source through every transition."
      : `\nRESULT: FAIL — ${failures} drifted counter check(s).`,
  );
};

requestContextStorage.run(ctx, run)
  .then(() => process.exit(failures === 0 ? 0 : 1))
  .catch((e) => {
    console.error("cross-test error:", e);
    process.exit(1);
  });
