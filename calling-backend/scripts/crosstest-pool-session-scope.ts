// Cross-test: the session-scoped Pool-Assignment page data path (centre 1, live "test" session).
// Proves exactly what the rewired BatchPoolsScreen renders when you pick Batch → Calling session:
//
//   1. listSessionCampaigns exposes the "test" campaign with its batchId (so the batch dropdown can
//      filter sessions to a batch) and sessionName (heading).
//   2. The campaign's assignments map every batch member to a caller (per-session, isolated).
//   3. getBatchMembers(batch) returns the SAME members (named), keyed by the batch_member id used in
//      the assignment map — so the page's roster ↔ assignment join is consistent.
//
//   npx tsx scripts/crosstest-pool-session-scope.ts
//
// Read-only — does not mutate the seeded "test" session.

import { SessionCampaignStatus } from "@prisma/client";
import { listSessionCampaigns } from "../src/modules/sessionCampaign/sessionCampaign.service.js";
import { getBatchMembers } from "../src/modules/batch/batch-member.service.js";

const CENTRE = 1;
const HARERDAS = 259;
const SESSION_NAME = "test";

let fails = 0;
const check = (label: string, cond: boolean, extra = "") => {
  if (cond) console.log(`  ✓ ${label}`);
  else { fails++; console.log(`  ✗ ${label} ${extra}`); }
};

const run = async () => {
  console.log('=== cross-test: session-scoped pool page (live "test" session) ===');

  const camps = await listSessionCampaigns({ centreId: CENTRE, status: SessionCampaignStatus.active });
  const test = camps.find((c: any) => c.sessionName === SESSION_NAME) as any;
  check(`"test" calling session is listed`, !!test);
  if (!test) { console.log("\nRESULT: FAIL — seed it: npx tsx scripts/seed-test-calling-session.ts --apply"); process.exit(1); }

  check("campaign carries its batchId (batch dropdown can filter to it)", typeof test.batchId === "number" && test.batchId > 0,
    `— batchId=${test.batchId}`);
  check('campaign kind is "called"', test.kind === "called", `— kind=${test.kind}`);

  const assignments = test.assignments ?? [];
  check("session has 8 assignments", assignments.length === 8, `— got ${assignments.length}`);
  check("every assignment is to Harerdas (259), isolated to this session",
    assignments.length > 0 && assignments.every((a: any) => a.caller?.id === HARERDAS));

  // Roster ↔ assignment join: every assigned batch_member id must exist in getBatchMembers(batch),
  // and those members must resolve to real names (what the page row shows).
  const members = await getBatchMembers(CENTRE, "youth", test.batchId);
  const memberIds = new Set(members.map((m) => m.id));
  check("getBatchMembers returns the session's batch roster", members.length >= assignments.length,
    `— members=${members.length}, assignments=${assignments.length}`);
  check("every assigned member id is present in the batch roster",
    assignments.every((a: any) => memberIds.has(a.batchMember?.id)));
  const named = members.filter((m) => !!m.name).length;
  check("batch members resolve to real names (page shows names, not bare mobiles)",
    named === members.length, `— named ${named}/${members.length}`);

  console.log(fails === 0
    ? '\nRESULT: PASS — picking Batch → "test" shows 8 named members, all assigned to Harerdas.'
    : `\nRESULT: FAIL — ${fails} check(s) failed.`);
  process.exit(fails === 0 ? 0 : 1);
};

run().catch((e) => { console.error("pool-session-scope test error:", e); process.exit(1); });
