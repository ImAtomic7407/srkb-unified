// Cross-test for session-scoped calling + Called/Direct-Attendance classification.
//
//   npx tsx scripts/crosstest-sessions.ts
//
// Verifies, on centre 1, using throwaway sessions/members (all cleaned up):
//   1. A session with an admin campaign classifies as "called"; one with attendance-only as
//      "direct_attendance" — and the registerer grouping puts each under the right heading.
//   2. ensureDirectAttendanceCampaign is idempotent (no duplicate campaign).
//   3. Per-session caller assignments are isolated: assigning a caller in campaign A does not
//      leak into campaign B's queue.
//   4. listSessionCampaigns surfaces the session name + kind (for the pool-page heading).

import { SessionCampaignKind, SessionCampaignStatus, type Category } from "@prisma/client";
import { rawPrisma } from "../src/config/prisma.js";
import {
  createSessionCampaign,
  ensureDirectAttendanceCampaign,
  getSessionsForRegistration,
  listSessionCampaigns,
  assignSessionCallers,
  getSessionCallQueue,
} from "../src/modules/sessionCampaign/sessionCampaign.service.js";

const CENTRE = 1;
const BATCH = 3;
const ADMIN = 258;
const CALLER = 259;
const CAT: Category = "youth";
const SESS_A = "__xtest_called__";
const SESS_B = "__xtest_direct__";
const MOB_A = "9990111111";
const MOB_B = "9990222222";

let fails = 0;
const check = (label: string, cond: boolean, extra = "") => {
  if (cond) console.log(`  ✓ ${label}`);
  else { fails++; console.log(`  ✗ ${label} ${extra}`); }
};

const future = (days: number) => new Date(Date.now() + days * 86400000);

const cleanup = async () => {
  const camps = await rawPrisma.sessionCampaign.findMany({ where: { aeSessionId: { in: [SESS_A, SESS_B] } }, select: { id: true } });
  const campIds = camps.map((c) => c.id);
  if (campIds.length) await rawPrisma.sessionCallAssignment.deleteMany({ where: { campaignId: { in: campIds } } });
  await rawPrisma.sessionCampaign.deleteMany({ where: { aeSessionId: { in: [SESS_A, SESS_B] } } });
  await rawPrisma.batchMemberAudit.deleteMany({ where: { batchMember: { mobile: { in: [MOB_A, MOB_B] }, centreId: CENTRE } } });
  await rawPrisma.batchMember.deleteMany({ where: { mobile: { in: [MOB_A, MOB_B] }, centreId: CENTRE } });
  await rawPrisma.session.deleteMany({ where: { sessionId: { in: [SESS_A, SESS_B] } } });
};

const run = async () => {
  console.log("=== cross-test: session classification + assignment isolation (centre 1) ===");
  await cleanup();

  // Throwaway sessions in the mirror, dated far in the future so they sort to the top.
  await rawPrisma.session.create({ data: { sessionId: SESS_A, displayName: "XTest Called Session", batchId: BATCH, centreId: CENTRE, category: CAT, date: future(10), status: "active", lastSyncedAt: new Date() } });
  await rawPrisma.session.create({ data: { sessionId: SESS_B, displayName: "XTest Direct Session", batchId: BATCH, centreId: CENTRE, category: CAT, date: future(9), status: "active", lastSyncedAt: new Date() } });
  // Throwaway batch members for assignment.
  const mA = await rawPrisma.batchMember.create({ data: { centreId: CENTRE, category: CAT, batchId: BATCH, mobile: MOB_A, joinedAt: new Date(), changedBy: "xtest", changedAt: new Date(), source: "manual_add" } });
  const mB = await rawPrisma.batchMember.create({ data: { centreId: CENTRE, category: CAT, batchId: BATCH, mobile: MOB_B, joinedAt: new Date(), changedBy: "xtest", changedAt: new Date(), source: "manual_add" } });

  try {
    // 1) Admin creates a CALLED campaign for session A.
    const campA = await createSessionCampaign({ aeSessionId: SESS_A, centreId: CENTRE, batchId: BATCH, scheduledAt: future(10), createdById: ADMIN });
    check("campaign A is kind=called", campA.kind === SessionCampaignKind.called);

    // 2) Session B gets attendance with no campaign → direct_attendance, idempotent.
    const dir1 = await ensureDirectAttendanceCampaign(SESS_B, CENTRE);
    const dir2 = await ensureDirectAttendanceCampaign(SESS_B, CENTRE);
    check("session B direct campaign created + flagged", !!dir1 && dir1.kind === SessionCampaignKind.direct_attendance);
    check("ensureDirectAttendanceCampaign idempotent", !!dir1 && !!dir2 && dir1.id === dir2.id);

    // 3) Registerer grouping: A under called, B under direct_attendance.
    const grouped = await getSessionsForRegistration(CENTRE, CAT);
    const inCalled = grouped.called.some((s: any) => s.sessionId === SESS_A && s.kind === "called");
    const inDirect = grouped.direct_attendance.some((s: any) => s.sessionId === SESS_B && s.kind === "direct_attendance");
    const noCross = !grouped.called.some((s: any) => s.sessionId === SESS_B) && !grouped.direct_attendance.some((s: any) => s.sessionId === SESS_A);
    check("session A listed under Called Session", inCalled);
    check("session B listed under Direct Attendance", inDirect);
    check("no cross-contamination between headings", noCross);

    // 4) Assignment isolation: assign member A → caller in campaign A only.
    await assignSessionCallers({ campaignId: campA.id, centreId: CENTRE, assignments: [{ batch_member_id: mA.id, caller_id: CALLER }], now: new Date() });
    const queue = await getSessionCallQueue({ callerId: CALLER, centreId: CENTRE });
    const hasA = queue.some((q: any) => q.campaignId === campA.id && q.batchMemberId === mA.id);
    const leakB = queue.some((q: any) => q.campaign?.aeSessionId === SESS_B);
    check("caller queue contains campaign A assignment", hasA);
    check("campaign B did NOT leak into the caller's queue", !leakB);

    // 5) listSessionCampaigns surfaces session name + kind (pool-page heading).
    const camps = await listSessionCampaigns({ centreId: CENTRE, status: SessionCampaignStatus.active });
    const rowA = camps.find((c: any) => c.aeSessionId === SESS_A) as any;
    check("campaign list exposes session name for heading", !!rowA && rowA.sessionName === "XTest Called Session");
    check("campaign list exposes kind", !!rowA && rowA.kind === "called");
  } finally {
    await cleanup();
  }

  console.log(fails === 0 ? "\nRESULT: PASS — sessions classified + isolated correctly." : `\nRESULT: FAIL — ${fails} check(s) failed.`);
  process.exit(fails === 0 ? 0 : 1);
};

run().catch((e) => { console.error("session test error:", e); process.exit(1); });
