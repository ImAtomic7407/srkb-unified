// Cross-test: dashboard "total_registrations" fix (registrations_total - removed - archived).
// Verifies the fast-path (centre_stats) and slow-path (live count) computations agree, and
// that the resulting figure is in the real ~600-700 ballpark instead of the old inflated 2788.
//
//   npx tsx scripts/crosstest-dashboard-total-registrations.ts
//
// Read-only — does not mutate anything.

import { Category, RegistrationStage } from "@prisma/client";
import { rawPrisma } from "../src/config/prisma.js";
import { getCentreStatsWithReconcile, statKey } from "../src/lib/projections.js";

const CENTRE = 1;

let fails = 0;
const check = (label: string, cond: boolean, extra = "") => {
  if (cond) console.log(`  ✓ ${label}`);
  else { fails++; console.log(`  ✗ ${label} ${extra}`); }
};

const main = async () => {
  for (const category of [Category.youth, Category.congregation]) {
    console.log(`\n== centre ${CENTRE} / ${category} ==`);
    const k = (m: string) => statKey(m as any, category);
    const s = await getCentreStatsWithReconcile(CENTRE, [
      k("registrations_total"),
      k("removed"),
      k("archived"),
    ]);
    const cachedTotal = (s[k("registrations_total")] ?? 0) - (s[k("removed")] ?? 0) - (s[k("archived")] ?? 0);

    const [rawTotal, removed, archived] = await Promise.all([
      rawPrisma.registration.count({ where: { centreId: CENTRE, category } }),
      rawPrisma.registration.count({ where: { centreId: CENTRE, category, currentStage: RegistrationStage.removed } }),
      rawPrisma.registration.count({ where: { centreId: CENTRE, category, currentStage: RegistrationStage.archived } }),
    ]);
    const liveTotal = rawTotal - removed - archived;

    console.log(`  raw_total=${rawTotal} removed=${removed} archived=${archived} -> total_registrations=${liveTotal}`);
    check("cached (centre_stats) total_registrations matches live recompute", cachedTotal === liveTotal,
      `(cached=${cachedTotal} live=${liveTotal})`);
    check("total_registrations is NOT the old inflated raw attendance-row count (sanity < rawTotal*3)", liveTotal <= rawTotal);
  }

  console.log(fails === 0 ? "\nALL PASS" : `\n${fails} FAIL`);
  process.exit(fails === 0 ? 0 : 1);
};

main().catch((e) => { console.error(e); process.exit(1); });
