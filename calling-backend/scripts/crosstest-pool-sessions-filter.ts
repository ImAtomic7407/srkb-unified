// Cross-test: the Pool-Assignment LOWER "Filter: session" data path (centre 1).
// Proves the two halves of the fix:
//   1. listSessionsForFilter returns ALL centre sessions (not just the few with a calling campaign),
//      each classified kind = "called" | "direct_attendance". This is what the lower session dropdown
//      lists — so both calling AND direct sessions are selectable.
//   2. listPeopleByFilter({ sessionId }) returns the actual ATTENDEES of a picked session — the set of
//      mobiles the app intersects the pool against. This is presence-based (NOT the batch roster).
//
//   npx tsx scripts/crosstest-pool-sessions-filter.ts
//
// Read-only — does not mutate anything.

import { SessionCampaignKind } from "@prisma/client";
import { listSessionsForFilter } from "../src/modules/sessionCampaign/sessionCampaign.service.js";
import { listPeopleByFilter } from "../src/modules/attendance/attendease-attendance.service.js";

const CENTRE = 1;
const CATEGORY = "youth" as const;

let fails = 0;
const check = (label: string, cond: boolean, extra = "") => {
  if (cond) console.log(`  ✓ ${label}`);
  else { fails++; console.log(`  ✗ ${label} ${extra}`); }
};

const main = async () => {
  console.log("== listSessionsForFilter (lower session dropdown source) ==");
  const sessions = await listSessionsForFilter(CENTRE, CATEGORY);
  const called = sessions.filter((s) => s.kind === SessionCampaignKind.called);
  const direct = sessions.filter((s) => s.kind === SessionCampaignKind.direct_attendance);
  console.log(`  total=${sessions.length}  called=${called.length}  direct=${direct.length}`);

  check("returns more than just the calling sessions (direct sessions present too)", direct.length > 0,
    `(direct=${direct.length})`);
  check("every session has a non-blank aeSessionId", sessions.every((s) => !!s.session_id));
  check("every session has a display_name", sessions.every((s) => !!s.display_name));
  check("every session's kind is called|direct_attendance",
    sessions.every((s) => s.kind === SessionCampaignKind.called || s.kind === SessionCampaignKind.direct_attendance));

  // Sample a couple of sessions and prove presence-based attendee lookup works.
  console.log("\n== listPeopleByFilter({ sessionId }) (presence-based pool filter) ==");
  let anyAttendees = false;
  for (const s of sessions.slice(0, 8)) {
    const people = await listPeopleByFilter({ centreId: CENTRE, category: CATEGORY, sessionId: s.session_id });
    const mobiles = new Set(people.map((p) => (p.mobile || "").replace(/\D/g, "")).filter(Boolean));
    if (mobiles.size > 0) anyAttendees = true;
    console.log(`  [${s.kind}] "${s.display_name}" (${s.session_id}) -> ${mobiles.size} attendee mobiles`);
    check(`  every returned person for "${s.display_name}" matched via session`,
      people.every((p) => p.matched_via.includes("session")));
  }
  check("at least one sampled session has real attendees (presence filter is non-empty)", anyAttendees);

  console.log(fails === 0 ? "\nALL PASS" : `\n${fails} FAIL`);
  process.exit(fails === 0 ? 0 : 1);
};

main().catch((e) => { console.error(e); process.exit(1); });
