// Cross-test for the "send back to original caller closes the follow-up row" fix.
//
// Proves the previously-orphaned transitions now close the Followup row:
//   1. "Send back to original caller" → registration leaves followup_active AND the Followup row
//      becomes `closed` (was left `active` = the visible bug).
//   2. "Still coming" → stays `active` (unchanged, regression guard).
//
//   npx tsx scripts/crosstest-followup-sendback-close.ts
//
// Uses centre 1 / batch 3 with throwaway rows; cleans everything up.

import { FollowupStatus, RegistrationStage, UserRole, ApprovalStatus, type Category } from "@prisma/client";
import { rawPrisma } from "../src/config/prisma.js";
import {
  createSessionCampaign,
  assignSessionCallers,
  updateSessionCallStatus,
} from "../src/modules/sessionCampaign/sessionCampaign.service.js";
import { markFollowupDone } from "../src/modules/followup/followup.service.js";

const CENTRE = 1;
const BATCH = 3;
const CALLER = 259;
const CAT: Category = "youth";
const SESS = "__xtest_sendback__";
const MOBILE_SB = "9990557001";  // send-back
const MOBILE_ST = "9990557002";  // still-coming (regression guard)

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
  for (const mobile of [MOBILE_SB, MOBILE_ST]) {
    await rawPrisma.followup.deleteMany({ where: { registration: { normalizedMobile: mobile, centreId: CENTRE } } });
    await rawPrisma.callAttempt.deleteMany({ where: { registration: { normalizedMobile: mobile, centreId: CENTRE } } });
    await rawPrisma.batchMemberAudit.deleteMany({ where: { batchMember: { mobile, centreId: CENTRE } } });
    await rawPrisma.batchMember.deleteMany({ where: { mobile, centreId: CENTRE } });
    await rawPrisma.registration.deleteMany({ where: { normalizedMobile: mobile, centreId: CENTRE } });
  }
};

const requester = {
  id: CALLER, name: "XTest Caller", mobile: "0000000000", role: UserRole.CALLER, permissions: [],
  centreId: CENTRE, approvalStatus: ApprovalStatus.approved, isActive: true,
  attendanceSystemAccess: false, mustChangePassword: false,
} as any;

const run = async () => {
  console.log("=== cross-test: send-back closes the follow-up row (centre 1) ===");
  await cleanup();

  try {
    const regs: Record<string, number> = {};
    const members: Record<string, number> = {};
    for (const [key, mobile] of [["SB", MOBILE_SB], ["ST", MOBILE_ST]] as const) {
      const reg = await rawPrisma.registration.create({
        data: { name: `Sendback Xtest ${key}`, mobile, normalizedMobile: mobile, centreId: CENTRE, category: CAT,
          originalCallerId: CALLER },
      });
      const member = await rawPrisma.batchMember.create({
        data: { centreId: CENTRE, category: CAT, batchId: BATCH, mobile, joinedAt: new Date(), changedBy: "xtest", changedAt: new Date(), source: "manual_add" },
      });
      regs[key] = reg.id; members[key] = member.id;
    }

    await rawPrisma.session.create({
      data: { sessionId: SESS, displayName: "XTest Sendback", batchId: BATCH, centreId: CENTRE, category: CAT, date: new Date(), status: "active", lastSyncedAt: new Date() },
    });
    const camp = await createSessionCampaign({ aeSessionId: SESS, centreId: CENTRE, batchId: BATCH, scheduledAt: new Date(), createdById: CALLER });
    await assignSessionCallers({ campaignId: camp.id, centreId: CENTRE, assignments: [
      { batch_member_id: members.SB, caller_id: CALLER },
      { batch_member_id: members.ST, caller_id: CALLER },
    ], now: new Date() });

    const aSB = await rawPrisma.sessionCallAssignment.findUnique({ where: { campaignId_batchMemberId: { campaignId: camp.id, batchMemberId: members.SB } } });
    const aST = await rawPrisma.sessionCallAssignment.findUnique({ where: { campaignId_batchMemberId: { campaignId: camp.id, batchMemberId: members.ST } } });

    // Both give a positive response → both get an active follow-up.
    await updateSessionCallStatus({ assignmentId: aSB!.id, callerId: CALLER, centreId: CENTRE, outcome: "Will attend", now: new Date() });
    await updateSessionCallStatus({ assignmentId: aST!.id, callerId: CALLER, centreId: CENTRE, outcome: "Will attend", now: new Date() });
    check("SB got an active follow-up", (await rawPrisma.followup.findUnique({ where: { registrationId: regs.SB } }))?.status === FollowupStatus.active);
    check("ST got an active follow-up", (await rawPrisma.followup.findUnique({ where: { registrationId: regs.ST } }))?.status === FollowupStatus.active);

    // 1) Send back to original caller — must close the follow-up row (the fix).
    await markFollowupDone({ registrationId: regs.SB, dropdownValue: "Send back to original caller", requester });
    const fSB = await rawPrisma.followup.findUnique({ where: { registrationId: regs.SB } });
    const rSB = await rawPrisma.registration.findUnique({ where: { id: regs.SB }, select: { currentStage: true } });
    check("send-back CLOSES the follow-up row (was the bug: left active)", fSB?.status === FollowupStatus.closed, `— got ${fSB?.status}`);
    check("send-back moves registration to sent_back_to_owner", rSB?.currentStage === RegistrationStage.sent_back_to_owner, `— got ${rSB?.currentStage}`);

    // 2) Regression guard: "Still coming" keeps the follow-up active.
    await markFollowupDone({ registrationId: regs.ST, dropdownValue: "Still coming", requester });
    const fST = await rawPrisma.followup.findUnique({ where: { registrationId: regs.ST } });
    check("'Still coming' keeps the follow-up active (regression guard)", fST?.status === FollowupStatus.active, `— got ${fST?.status}`);
  } finally {
    await cleanup();
  }

  console.log(fails === 0
    ? "\nRESULT: PASS — send-back closes the follow-up; stay-in-followup unaffected."
    : `\nRESULT: FAIL — ${fails} check(s) failed.`);
  process.exit(fails === 0 ? 0 : 1);
};

run().catch((e) => { console.error("sendback-close test error:", e); process.exit(1); });
