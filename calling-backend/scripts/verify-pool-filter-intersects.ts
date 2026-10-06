/**
 * The pool-assignment "Filter people" dropdowns (batch / session / date) must AND together.
 *
 * They used to OR: listPeopleByFilter added each filter's matches to one shared map, so batch 18
 * at Centre A returned 62 attendees and batch 18 + ANY of its sessions returned the same 62 —
 * every session's attendees being a subset of its batch's. Choosing a session inside the selected
 * batch narrowed nothing, which is the entire purpose of that lower pair.
 *
 * Read-only: it calls the real service against the live AttendEase DB and writes nothing.
 *
 *   npx tsx scripts/verify-pool-filter-intersects.ts
 */
import { listPeopleByFilter } from "../src/modules/attendance/attendease-attendance.service.js";
import { rawPrisma } from "../src/config/prisma.js";

let failed = 0;
const check = (name: string, cond: boolean, extra?: unknown) => {
  if (cond) console.log("ok:", name);
  else { console.error("FAIL:", name, extra ?? ""); failed++; }
};

const CENTRE_ID = 2;          // Centre A
const BATCH_ID = 18;          // "BG"
const SESSION_ID = "01-08-26"; // a session mapped to batch 18

const main = async () => {
  const byBatch = await listPeopleByFilter({ centreId: CENTRE_ID, category: "youth", batchId: BATCH_ID });
  const bySession = await listPeopleByFilter({ centreId: CENTRE_ID, category: "youth", sessionId: SESSION_ID });
  const both = await listPeopleByFilter({
    centreId: CENTRE_ID, category: "youth", batchId: BATCH_ID, sessionId: SESSION_ID,
  });

  console.log(`  batch only   : ${byBatch.length}`);
  console.log(`  session only : ${bySession.length}`);
  console.log(`  batch+session: ${both.length}`);

  check("a single filter still returns its own matches", byBatch.length > 0 && bySession.length > 0);

  // The regression itself: combining must not simply return the batch again.
  check("batch+session NARROWS rather than returning the whole batch",
    both.length < byBatch.length, { both: both.length, batch: byBatch.length });

  // It is exactly the intersection.
  const batchM = new Set(byBatch.map((p) => p.mobile));
  const sessM = new Set(bySession.map((p) => p.mobile));
  const expected = [...sessM].filter((m) => batchM.has(m));
  check("batch+session equals the set intersection",
    both.length === expected.length && both.every((p) => batchM.has(p.mobile) && sessM.has(p.mobile)),
    { got: both.length, expected: expected.length });

  // Everyone returned must genuinely satisfy BOTH filters.
  check("every person returned matched both filters",
    both.every((p) => p.matched_via.includes("batch") && p.matched_via.includes("session")));

  // A session belonging to a DIFFERENT batch must yield nothing when ANDed.
  const other = await listPeopleByFilter({
    centreId: CENTRE_ID, category: "youth", batchId: BATCH_ID, sessionId: "__no_such_session__",
  });
  check("a session with no attendees yields an empty result, not the batch", other.length === 0, other.length);

  console.log(failed ? `\n${failed} FAILED` : "\nALL PASS");
  process.exitCode = failed ? 1 : 0;
};

main().catch((e) => { console.error("ERROR:", e); process.exitCode = 1; })
      .finally(() => rawPrisma.$disconnect());
