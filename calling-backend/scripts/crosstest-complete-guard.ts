// Cross-test for the "can't complete a session while responses are pending" guard.
//
// Proves:
//   1. completeSessionCampaign THROWS 409 while any assignment is still `pending`
//      (covers both "never called" and "called offline, not yet synced").
//   2. The intended remedy works: Reopen (→ active) lets the pending response land,
//      then Close + Complete succeeds once nothing is pending.
//
//   npx tsx scripts/crosstest-complete-guard.ts
//
// Uses centre 1 / batch 3 with throwaway rows; cleans everything up.

import { SessionCampaignStatus, UserRole, ApprovalStatus, type Category } from "@prisma/client";
import { rawPrisma } from "../src/config/prisma.js";
import {
  createSessionCampaign,
  assignSessionCallers,
  updateSessionCallStatus,
  closeSessionCampaign,
  reopenSessionCampaign,
  completeSessionCampaign,
} from "../src/modules/sessionCampaign/sessionCampaign.service.js";

const CENTRE = 1;
const BATCH = 3;
const CALLER = 259;
const CAT: Category = "youth";
const SESS = "__xtest_complete_guard__";
const MOBILE_DONE = "9990556001";    // responds (will_attend)
const MOBILE_PENDING = "9990556002"; // stays pending until the remedy flow

let fails = 0;
const check = (label: string, cond: boolean, extra = "") => {
  if (cond) console.log(`  ✓ ${label}`);
  else { fails++; console.log(`  ✗ ${label} ${extra}`); }
};

const cleanup = async () => {
  const camps = await rawPrisma.sessionCampaign.findMany({ where: { aeSessionId: SESS }, select: { id: true } });
  const ids = camps.map((c) => c.id);
  if (ids.length) await rawPrisma.sessionCallAssignment.deleteMany({ where: { campaignId: { in: ids } } });
  await rawPrisma.sessionCampaign.deleteMany({ where: { aeSessionId: SESS } });
  await rawPrisma.session.deleteMany({ where: { sessionId: SESS } });
  for (const mobile of [MOBILE_DONE, MOBILE_PENDING]) {
    await rawPrisma.followup.deleteMany({ where: { registration: { normalizedMobile: mobile, centreId: CENTRE } } });
    await rawPrisma.callAttempt.deleteMany({ where: { registration: { normalizedMobile: mobile, centreId: CENTRE } } });
    await rawPrisma.batchMemberAudit.deleteMany({ where: { batchMember: { mobile, centreId: CENTRE } } });
    await rawPrisma.batchMember.deleteMany({ where: { mobile, centreId: CENTRE } });
    await rawPrisma.registration.deleteMany({ where: { normalizedMobile: mobile, centreId: CENTRE } });
  }
};

const run = async () => {
  console.log("=== cross-test: completion guard blocks while responses pending (centre 1) ===");
  await cleanup();

  try {
    const members: Record<string, number> = {};
    for (const [key, mobile] of [["DONE", MOBILE_DONE], ["PENDING", MOBILE_PENDING]] as const) {
      await rawPrisma.registration.create({
        data: { name: `Guard Xtest ${key}`, mobile, normalizedMobile: mobile, centreId: CENTRE, category: CAT },
      });
      const member = await rawPrisma.batchMember.create({
        data: { centreId: CENTRE, category: CAT, batchId: BATCH, mobile, joinedAt: new Date(), changedBy: "xtest", changedAt: new Date(), source: "manual_add" },
      });
      members[key] = member.id;
    }

    await rawPrisma.session.create({
      data: { sessionId: SESS, displayName: "XTest Complete Guard", batchId: BATCH, centreId: CENTRE, category: CAT, date: new Date(), status: "active", lastSyncedAt: new Date() },
    });

    const camp = await createSessionCampaign({ aeSessionId: SESS, centreId: CENTRE, batchId: BATCH, scheduledAt: new Date(), createdById: CALLER });
    await assignSessionCallers({ campaignId: camp.id, centreId: CENTRE, assignments: [
      { batch_member_id: members.DONE, caller_id: CALLER },
      { batch_member_id: members.PENDING, caller_id: CALLER },
    ], now: new Date() });

    const aDone = await rawPrisma.sessionCallAssignment.findUnique({
      where: { campaignId_batchMemberId: { campaignId: camp.id, batchMemberId: members.DONE } },
    });
    const aPending = await rawPrisma.sessionCallAssignment.findUnique({
      where: { campaignId_batchMemberId: { campaignId: camp.id, batchMemberId: members.PENDING } },
    });

    // Only ONE of the two responds. The other stays pending (simulates a caller who hasn't
    // synced their offline response yet).
    await updateSessionCallStatus({ assignmentId: aDone!.id, callerId: CALLER, centreId: CENTRE, outcome: "Yes, will attend", now: new Date() });

    // Close (retrieve) so completion is even attemptable.
    await closeSessionCampaign(camp.id, CENTRE);

    // 1) Completion must be BLOCKED while aPending is still pending.
    let blocked = false;
    let blockMsg = "";
    try {
      await completeSessionCampaign(camp.id, CENTRE, new Date());
    } catch (e: any) {
      blocked = e?.statusCode === 409;
      blockMsg = e?.message ?? "";
    }
    check("completion is blocked (409) while a response is pending", blocked, `— got msg="${blockMsg}"`);
    check("block message mentions pending responses", /pending/i.test(blockMsg), `— got "${blockMsg}"`);

    const stillClosed = await rawPrisma.sessionCampaign.findUnique({ where: { id: camp.id }, select: { status: true } });
    check("campaign was NOT completed (still closed)", stillClosed?.status === SessionCampaignStatus.closed, `— got ${stillClosed?.status}`);

    // 2) Remedy flow: Reopen → the pending response finally lands → Close → Complete succeeds.
    await reopenSessionCampaign(camp.id, CENTRE);
    await updateSessionCallStatus({ assignmentId: aPending!.id, callerId: CALLER, centreId: CENTRE, outcome: "No answer, switched off", now: new Date() });
    await closeSessionCampaign(camp.id, CENTRE);

    let completed = false;
    let completeErr = "";
    try {
      const summary = await completeSessionCampaign(camp.id, CENTRE, new Date());
      completed = !!summary && summary.status === SessionCampaignStatus.completed;
    } catch (e: any) {
      completeErr = e?.message ?? String(e);
    }
    check("completion SUCCEEDS once no response is pending", completed, `— err="${completeErr}"`);
  } finally {
    await cleanup();
  }

  console.log(fails === 0
    ? "\nRESULT: PASS — completion guard + reopen-remedy verified."
    : `\nRESULT: FAIL — ${fails} check(s) failed.`);
  process.exit(fails === 0 ? 0 : 1);
};

run().catch((e) => { console.error("complete-guard test error:", e); process.exit(1); });
