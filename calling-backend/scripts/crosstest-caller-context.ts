// Cross-test: the caller's session-call queue is now enriched with per-person CONTEXT
// (previous responses + attendance + details). Proves what the redesigned calling card renders.
//
//   npx tsx scripts/crosstest-caller-context.ts
//
// Read-only.

import { getSessionCallQueue } from "../src/modules/sessionCampaign/sessionCampaign.service.js";

const CENTRE = 1;
const HARERDAS = 259;

let fails = 0;
const check = (label: string, cond: boolean, extra = "") => {
  if (cond) console.log(`  ✓ ${label}`);
  else { fails++; console.log(`  ✗ ${label} ${extra}`); }
};

const run = async () => {
  console.log("=== cross-test: caller session-call queue context enrichment ===");
  const rows = (await getSessionCallQueue({ callerId: HARERDAS, centreId: CENTRE })) as any[];
  check("queue returns rows for Harerdas", rows.length > 0, `— got ${rows.length}`);
  if (rows.length === 0) { console.log("\nRESULT: FAIL — no rows (seed: npx tsx scripts/seed-test-calling-session.ts --apply)"); process.exit(1); }

  check("every row carries a context object", rows.every((r) => r.context && typeof r.context === "object"));
  check("context.recentCalls is an array", rows.every((r) => Array.isArray(r.context.recentCalls)));
  check("context.recentAttendance is an array", rows.every((r) => Array.isArray(r.context.recentAttendance)));
  check("context.attendanceCount is a number", rows.every((r) => typeof r.context.attendanceCount === "number"));
  check("context.isNew is a boolean", rows.every((r) => typeof r.context.isNew === "boolean"));

  const sample = rows[0];
  console.log("\n  sample row:");
  console.log("    name:", sample.batchMember?.name, "| mobile:", sample.batchMember?.mobile);
  console.log("    session:", sample.campaign?.sessionName, "| status:", sample.callStatus);
  console.log("    attendanceCount:", sample.context.attendanceCount, "| lastAttended:", sample.context.lastAttended, "| isNew:", sample.context.isNew);
  console.log("    recentCalls:", JSON.stringify(sample.context.recentCalls));

  console.log(fails === 0 ? "\nRESULT: PASS — queue rows are context-enriched." : `\nRESULT: FAIL — ${fails} check(s) failed.`);
  process.exit(fails === 0 ? 0 : 1);
};

run().catch((e) => { console.error("caller-context test error:", e); process.exit(1); });
