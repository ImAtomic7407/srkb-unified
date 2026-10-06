// Cross-test for instant follow-up creation + lock-not-hide retrieval semantics.
//
// Proves the corrected model:
//   1. A "will attend" outcome creates a Followup INSTANTLY (no waiting for close/complete).
//   2. Closing the campaign ("retrieval") LOCKS the follow-up but keeps it visible in getFollowupList.
//   3. A locked follow-up cannot be closed out via markFollowupDone (409).
//   4. Confirmed attendance (markSessionAttendance) CLOSES the follow-up outright (drops from the list).
//   5. A clean negative/no-answer outcome does NOT create a follow-up.
//
//   npx tsx scripts/crosstest-followup-instant.ts
//
// Uses centre 1 / batch 3 with throwaway rows; cleans everything up.

import { FollowupStatus, SessionCallStatus, UserRole, ApprovalStatus, type Category } from "@prisma/client";
import { rawPrisma } from "../src/config/prisma.js";
import {
  createSessionCampaign,
  assignSessionCallers,
  updateSessionCallStatus,
  closeSessionCampaign,
  completeSessionCampaign,
  markSessionAttendance,
} from "../src/modules/sessionCampaign/sessionCampaign.service.js";
import { getFollowupList, markFollowupDone } from "../src/modules/followup/followup.service.js";

const CENTRE = 1;
const BATCH = 3;
const CALLER = 259;
const CAT: Category = "youth";
const SESS_A = "__xtest_followup_a__";
const SESS_B = "__xtest_followup_b__";
const MOBILE_A = "9990555001"; // will_attend, then retrieved before attending
const MOBILE_B = "9990555002"; // will_attend, then actually attends
const MOBILE_C = "9990555003"; // no_answer — should never get a followup

const requester = {
  id: CALLER,
  name: "XTest Caller",
  mobile: "0000000000",
  role: UserRole.CALLER,
  permissions: [],
  centreId: CENTRE,
  approvalStatus: ApprovalStatus.approved,
  isActive: true,
  attendanceSystemAccess: false,
  mustChangePassword: false,
} as any;

let fails = 0;
const check = (label: string, cond: boolean, extra = "") => {
  if (cond) console.log(`  ✓ ${label}`);
  else { fails++; console.log(`  ✗ ${label} ${extra}`); }
};

const cleanup = async () => {
  for (const sess of [SESS_A, SESS_B]) {
    const camps = await rawPrisma.sessionCampaign.findMany({ where: { aeSessionId: sess }, select: { id: true } });
    const ids = camps.map((c) => c.id);
    if (ids.length) await rawPrisma.sessionCallAssignment.deleteMany({ where: { campaignId: { in: ids } } });
    await rawPrisma.sessionCampaign.deleteMany({ where: { aeSessionId: sess } });
    await rawPrisma.session.deleteMany({ where: { sessionId: sess } });
  }
  for (const mobile of [MOBILE_A, MOBILE_B, MOBILE_C]) {
    await rawPrisma.followup.deleteMany({ where: { registration: { normalizedMobile: mobile, centreId: CENTRE } } });
    await rawPrisma.callAttempt.deleteMany({ where: { registration: { normalizedMobile: mobile, centreId: CENTRE } } });
    await rawPrisma.batchMemberAudit.deleteMany({ where: { batchMember: { mobile, centreId: CENTRE } } });
    await rawPrisma.batchMember.deleteMany({ where: { mobile, centreId: CENTRE } });
    await rawPrisma.registration.deleteMany({ where: { normalizedMobile: mobile, centreId: CENTRE } });
  }
};

const run = async () => {
  console.log("=== cross-test: instant follow-up + lock-not-hide retrieval (centre 1) ===");
  await cleanup();

  try {
    // Seed registrations + batch members for all three mobiles.
    const regs: Record<string, number> = {};
    const members: Record<string, number> = {};
    for (const [key, mobile] of [["A", MOBILE_A], ["B", MOBILE_B], ["C", MOBILE_C]] as const) {
      const reg = await rawPrisma.registration.create({
        data: { name: `Followup Xtest ${key}`, mobile, normalizedMobile: mobile, centreId: CENTRE, category: CAT },
      });
      const member = await rawPrisma.batchMember.create({
        data: { centreId: CENTRE, category: CAT, batchId: BATCH, mobile, joinedAt: new Date(), changedBy: "xtest", changedAt: new Date(), source: "manual_add" },
      });
      regs[key] = reg.id;
      members[key] = member.id;
    }

    await rawPrisma.session.create({
      data: { sessionId: SESS_A, displayName: "XTest Followup Session A", batchId: BATCH, centreId: CENTRE, category: CAT, date: new Date(), status: "active", lastSyncedAt: new Date() },
    });
    await rawPrisma.session.create({
      data: { sessionId: SESS_B, displayName: "XTest Followup Session B", batchId: BATCH, centreId: CENTRE, category: CAT, date: new Date(), status: "active", lastSyncedAt: new Date() },
    });

    const campA = await createSessionCampaign({ aeSessionId: SESS_A, centreId: CENTRE, batchId: BATCH, scheduledAt: new Date(), createdById: CALLER });
    const campB = await createSessionCampaign({ aeSessionId: SESS_B, centreId: CENTRE, batchId: BATCH, scheduledAt: new Date(), createdById: CALLER });

    await assignSessionCallers({ campaignId: campA.id, centreId: CENTRE, assignments: [
      { batch_member_id: members.A, caller_id: CALLER },
      { batch_member_id: members.C, caller_id: CALLER },
    ], now: new Date() });
    await assignSessionCallers({ campaignId: campB.id, centreId: CENTRE, assignments: [
      { batch_member_id: members.B, caller_id: CALLER },
    ], now: new Date() });

    const assignmentA = await rawPrisma.sessionCallAssignment.findUnique({
      where: { campaignId_batchMemberId: { campaignId: campA.id, batchMemberId: members.A } },
    });
    const assignmentC = await rawPrisma.sessionCallAssignment.findUnique({
      where: { campaignId_batchMemberId: { campaignId: campA.id, batchMemberId: members.C } },
    });
    const assignmentB = await rawPrisma.sessionCallAssignment.findUnique({
      where: { campaignId_batchMemberId: { campaignId: campB.id, batchMemberId: members.B } },
    });

    // 1) A "will attend" outcome must create a Followup INSTANTLY, before any close/complete.
    await updateSessionCallStatus({ assignmentId: assignmentA!.id, callerId: CALLER, centreId: CENTRE, outcome: "Yes, will attend", now: new Date() });
    let followupA = await rawPrisma.followup.findUnique({ where: { registrationId: regs.A } });
    check("instant followup created on will_attend (before close)", !!followupA && followupA.status === FollowupStatus.active && !followupA.isLocked,
      `— got ${JSON.stringify(followupA)}`);

    let list = await getFollowupList({ category: CAT, callerId: CALLER, requester });
    check("instant followup appears in getFollowupList", list.items.some((f) => f.registrationId === regs.A));

    // A clean no-answer must NOT create a followup.
    await updateSessionCallStatus({ assignmentId: assignmentC!.id, callerId: CALLER, centreId: CENTRE, outcome: "No answer, unreachable", now: new Date() });
    const followupC = await rawPrisma.followup.findUnique({ where: { registrationId: regs.C } });
    check("no_answer outcome does NOT create a followup", followupC === null, `— got ${JSON.stringify(followupC)}`);

    // 2) Closing the campaign (retrieval) LOCKS the followup but does not remove it.
    await closeSessionCampaign(campA.id, CENTRE);
    followupA = await rawPrisma.followup.findUnique({ where: { registrationId: regs.A } });
    check("retrieval (close) locks the followup", !!followupA?.isLocked, `— got isLocked=${followupA?.isLocked}`);
    check("retrieval (close) does NOT change status away from active", followupA?.status === FollowupStatus.active);

    list = await getFollowupList({ category: CAT, callerId: CALLER, requester });
    check("locked followup STILL appears in getFollowupList (visible, not hidden)", list.items.some((f) => f.registrationId === regs.A));

    // 3) A locked followup cannot be closed out via markFollowupDone.
    let rejected = false;
    try {
      await markFollowupDone({ registrationId: regs.A, dropdownValue: "Stay in follow-up", requester });
    } catch (e: any) {
      rejected = e?.statusCode === 409 || /locked/i.test(e?.message ?? "");
    }
    check("markFollowupDone rejects a locked followup", rejected);

    // 3b) Completing the session CLEARS its follow-ups (they disappear on completion).
    await completeSessionCampaign(campA.id, CENTRE, new Date());
    followupA = await rawPrisma.followup.findUnique({ where: { registrationId: regs.A } });
    check("completion closes the session's follow-up", followupA?.status === FollowupStatus.closed, `— got status=${followupA?.status}`);
    list = await getFollowupList({ category: CAT, callerId: CALLER, requester });
    check("completed session's follow-up no longer in getFollowupList", !list.items.some((f) => f.registrationId === regs.A));

    // 4) Confirmed attendance CLOSES the followup outright (person B, separate session).
    await updateSessionCallStatus({ assignmentId: assignmentB!.id, callerId: CALLER, centreId: CENTRE, outcome: "Will attend for sure", now: new Date() });
    let followupB = await rawPrisma.followup.findUnique({ where: { registrationId: regs.B } });
    check("person B: instant followup created", !!followupB && followupB.status === FollowupStatus.active);

    await markSessionAttendance({ aeSessionId: SESS_B, mobile: MOBILE_B, centreId: CENTRE });
    followupB = await rawPrisma.followup.findUnique({ where: { registrationId: regs.B } });
    check("confirmed attendance closes the followup outright", followupB?.status === FollowupStatus.closed,
      `— got status=${followupB?.status}`);

    list = await getFollowupList({ category: CAT, callerId: CALLER, requester });
    check("closed followup no longer appears in getFollowupList", !list.items.some((f) => f.registrationId === regs.B));
  } finally {
    await cleanup();
  }

  console.log(fails === 0
    ? "\nRESULT: PASS — instant follow-up + lock-not-hide retrieval semantics verified."
    : `\nRESULT: FAIL — ${fails} check(s) failed.`);
  process.exit(fails === 0 ? 0 : 1);
};

run().catch((e) => { console.error("followup-instant test error:", e); process.exit(1); });
