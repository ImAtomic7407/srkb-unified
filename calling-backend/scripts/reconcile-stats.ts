// One-off backfill / repair for the live read-model counters (centre_stats).
//
//   npx tsx scripts/reconcile-stats.ts            → reconcile all active centres
//   npx tsx scripts/reconcile-stats.ts <centreId> → reconcile a single centre
//
// Safe to run anytime; recomputes every counter from source and overwrites the cache.

import { reconcileAllCentreStats, reconcileCentre } from "../src/lib/projections.js";

const main = async () => {
  const arg = process.argv[2];
  if (arg) {
    const centreId = Number(arg);
    if (!Number.isInteger(centreId)) {
      console.error(`Invalid centreId: ${arg}`);
      process.exit(1);
    }
    const written = await reconcileCentre(centreId);
    console.log(`Reconciled centre ${centreId}:`);
    console.table(written);
  } else {
    const count = await reconcileAllCentreStats();
    console.log(`Reconciled ${count} active centres.`);
  }
};

main()
  .then(() => process.exit(0))
  .catch((err) => {
    console.error("reconcile-stats failed:", err);
    process.exit(1);
  });
