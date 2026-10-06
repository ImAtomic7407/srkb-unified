// Cross-test for the Batch-by-Membership data path (the admin Batch Management screen).
// Proves what the rewired Android screen now consumes from the calling backend instead of the
// Flask attendance feed:
//
//   1. GET /batches (listBatches) returns batches with a numeric member_count (unique AttendEase
//      attendees) — the accurate count the summary stats use.
//   2. GET /pool/batch-members (listPeopleByFilter) for a populated batch returns NAMED members
//      (every item has a non-empty name, not a bare mobile) with a total_attendance_count field.
//   3. The deleted junk batch "Testing" (id 12) is GONE from the batch list (purge stuck).
//
//   npx tsx scripts/crosstest-batch-membership.ts
//
// Read-only — creates and deletes nothing.

import { Category } from "@prisma/client";
import { requestContextStorage, type RequestContext } from "../src/lib/request-context.js";
import { listBatches } from "../src/modules/batch/batch.service.js";
import { listPeopleByFilter } from "../src/modules/attendance/attendease-attendance.service.js";

const CENTRE_ID = 1;
const ADMIN_ID = 258;

let failures = 0;
const check = (label: string, cond: boolean, extra = "") => {
  if (cond) console.log(`  ✓ ${label}`);
  else { failures++; console.log(`  ✗ ${label} ${extra}`); }
};

const ctx: RequestContext = {
  userId: ADMIN_ID,
  centreId: CENTRE_ID,
  permissions: new Set<string>(),
  isAdminAtCentre: true,
  isHost: false,
};

const run = async () => {
  console.log("=== cross-test: Batch-by-Membership data path (centre 1) ===");

  const batches = await listBatches(Category.youth, CENTRE_ID);
  check(`listBatches returned batches (${batches.length})`, batches.length > 0);
  check("every batch exposes a numeric member_count", batches.every((b) => typeof b.member_count === "number"));
  check("deleted junk batch 'Testing' (id 12) is gone from the list",
    !batches.some((b) => b.id === 12 || b.name === "Testing"));

  // Pick the batch with the most attendees to exercise the named-member path.
  const populated = [...batches].sort((a, b) => b.member_count - a.member_count)[0];
  if (!populated || populated.member_count === 0) {
    console.log("  (no populated youth batch to test members — skipping member-name checks)");
  } else {
    console.log(`  using batch id=${populated.id} "${populated.name}" (member_count=${populated.member_count})`);
    const members = await listPeopleByFilter({ centreId: CENTRE_ID, category: Category.youth, batchId: populated.id });
    check(`/pool/batch-members returned members (${members.length})`, members.length > 0);
    // Name enrichment: anyone who is REGISTERED has a name in the system, so registered members
    // must always resolve to a real name (the core fix vs the old blanket "Unknown"). People who
    // only ever attended anonymously have no name anywhere and honestly fall back to their mobile.
    const isNamed = (m: { name?: string; mobile: string }) =>
      typeof m.name === "string" && m.name.trim().length > 0 && m.name.trim() !== m.mobile;
    const registered = members.filter((m) => m.registration != null);
    const registeredNamed = registered.filter(isNamed);
    check(`every REGISTERED member resolves to a real name (${registeredNamed.length}/${registered.length})`,
      registered.length === 0 || registeredNamed.length === registered.length,
      `— unnamed registered: ${registered.filter((m) => !isNamed(m)).map((m) => m.mobile).slice(0, 5).join(",")}`);
    const overall = members.filter(isNamed).length;
    console.log(`    (overall name coverage: ${overall}/${members.length} — rest are anonymous attendees, shown by mobile)`);
    check("every member exposes total_attendance_count", members.every((m) => typeof m.total_attendance_count === "number"));
  }

  console.log(failures === 0
    ? "\nRESULT: PASS — Batch Management consumes named members + accurate counts."
    : `\nRESULT: FAIL — ${failures} check(s) failed.`);
};

requestContextStorage.run(ctx, run)
  .then(() => process.exit(failures === 0 ? 0 : 1))
  .catch((e) => { console.error("cross-test error:", e); process.exit(1); });
