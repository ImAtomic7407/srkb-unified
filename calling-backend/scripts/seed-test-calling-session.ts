// One-off: create a "test" CALLING session at centre 1 (batch 7 "H.M and B.G Batch", 8 members)
// and assign its members to Harerdas (caller 259), so the admin can SEE a populated session in the
// pool-assignment session dropdown and Harerdas sees his queue. Explicitly requested by the user.
//
//   npx tsx scripts/seed-test-calling-session.ts          (dry run — shows what it will do)
//   npx tsx scripts/seed-test-calling-session.ts --apply  (create it)
//
// Idempotent: re-running re-uses the same session id and only fills missing assignments.

import { rawPrisma } from "../src/config/prisma.js";
import {
  createSessionCampaign,
  assignSessionCallers,
} from "../src/modules/sessionCampaign/sessionCampaign.service.js";

const APPLY = process.argv.includes("--apply");
const CENTRE = 1;
const BATCH = 7;            // "H.M and B.G Batch" (youth) — 8 real members
const CALLER = 259;        // Harerdas
const ADMIN = 258;         // centre admin (createdBy)
const CAT = "youth";
const SESSION_ID = "test-calling-session";
const SESSION_NAME = "test";

const run = async () => {
  console.log(`=== seed test calling session (${APPLY ? "APPLY" : "DRY RUN"}) ===`);

  const members = await rawPrisma.batchMember.findMany({
    where: { centreId: CENTRE, category: CAT, batchId: BATCH, removedAt: null },
    select: { id: true, mobile: true },
    orderBy: { joinedAt: "asc" },
  });
  console.log(`batch ${BATCH} has ${members.length} member(s) → all will be assigned to caller ${CALLER} (Harerdas)`);

  if (!APPLY) {
    console.log("\nDRY RUN — re-run with --apply to create the session + assignments.");
    return;
  }

  // 1) AE session mirror row (display name drives the dropdown label).
  await rawPrisma.session.upsert({
    where: { sessionId: SESSION_ID },
    update: { displayName: SESSION_NAME, batchId: BATCH, status: "active" },
    create: {
      sessionId: SESSION_ID, displayName: SESSION_NAME, batchId: BATCH,
      centreId: CENTRE, category: CAT, date: new Date(), status: "active", lastSyncedAt: new Date(),
    },
  });

  // 2) The calling-side campaign (kind defaults to "called" — a normal calling session).
  let campaign = await rawPrisma.sessionCampaign.findUnique({ where: { aeSessionId: SESSION_ID } });
  if (!campaign) {
    campaign = await createSessionCampaign({
      aeSessionId: SESSION_ID, centreId: CENTRE, batchId: BATCH, scheduledAt: new Date(), createdById: ADMIN,
    });
  }
  console.log(`campaign id=${campaign.id} kind=${(campaign as any).kind}`);

  // 3) Assign every batch member to Harerdas (skips ones already assigned — idempotent).
  const existing = await rawPrisma.sessionCallAssignment.findMany({
    where: { campaignId: campaign.id },
    select: { batchMemberId: true },
  });
  const already = new Set(existing.map((e) => e.batchMemberId));
  const toAssign = members.filter((m) => !already.has(m.id)).map((m) => ({ batch_member_id: m.id, caller_id: CALLER }));

  if (toAssign.length) {
    await assignSessionCallers({ campaignId: campaign.id, centreId: CENTRE, assignments: toAssign, now: new Date() });
  }

  const total = await rawPrisma.sessionCallAssignment.count({ where: { campaignId: campaign.id, callerId: CALLER } });
  console.log(`\nDONE — session "${SESSION_NAME}" now has ${total} call(s) assigned to Harerdas (${toAssign.length} new this run).`);
};

run().then(() => process.exit(0)).catch((e) => { console.error("seed error:", e); process.exit(1); });
