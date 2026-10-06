// Cross-test for the admin "Planned Sessions" assign-callers data path.
// Proves what the rewired SessionCampaignScreen now relies on:
//
//   1. getBatchMembers (GET /batch-members) returns a `name` for registered members (was mobile-only).
//   2. listSessionCampaigns (GET /session-campaigns) returns each campaign's existing assignments
//      with batchMember.id + caller.id — so the assign dialog can PRE-SELECT already-assigned callers
//      (the "doesn't load rows assigned previously" bug).
//
//   npx tsx scripts/crosstest-session-assign.ts
//
// Uses centre 1 / batch 3 with throwaway rows; cleans everything up.

import { SessionCampaignStatus, type Category } from "@prisma/client";
import { rawPrisma } from "../src/config/prisma.js";
import {
  createSessionCampaign,
  assignSessionCallers,
  listSessionCampaigns,
} from "../src/modules/sessionCampaign/sessionCampaign.service.js";
import { getBatchMembers } from "../src/modules/batch/batch-member.service.js";

const CENTRE = 1;
const BATCH = 3;
const ADMIN = 258;
const CALLER = 259;
const CAT: Category = "youth";
const SESS = "__xtest_assign__";
const MOBILE = "9990333444";

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
  await rawPrisma.batchMemberAudit.deleteMany({ where: { batchMember: { mobile: MOBILE, centreId: CENTRE } } });
  await rawPrisma.batchMember.deleteMany({ where: { mobile: MOBILE, centreId: CENTRE } });
  await rawPrisma.registration.deleteMany({ where: { normalizedMobile: MOBILE, centreId: CENTRE } });
  await rawPrisma.session.deleteMany({ where: { sessionId: SESS } });
};

const run = async () => {
  console.log("=== cross-test: session assign data path (centre 1) ===");
  await cleanup();

  // A registration + a batch member sharing the mobile → getBatchMembers should resolve the name.
  await rawPrisma.registration.create({
    data: { name: "Assign Crosstest", mobile: MOBILE, normalizedMobile: MOBILE, centreId: CENTRE, category: CAT },
  });
  const member = await rawPrisma.batchMember.create({
    data: { centreId: CENTRE, category: CAT, batchId: BATCH, mobile: MOBILE, joinedAt: new Date(), changedBy: "xtest", changedAt: new Date(), source: "manual_add" },
  });
  await rawPrisma.session.create({
    data: { sessionId: SESS, displayName: "XTest Assign Session", batchId: BATCH, centreId: CENTRE, category: CAT, date: new Date(), status: "active", lastSyncedAt: new Date() },
  });

  try {
    // 1) Named batch members.
    const members = await getBatchMembers(CENTRE, CAT, BATCH);
    const mine = members.find((m) => m.id === member.id);
    check("getBatchMembers returns the member with a resolved name", !!mine && mine.name === "Assign Crosstest",
      `— got name=${mine?.name}`);

    // 2) Create a campaign and assign the member to a caller.
    const camp = await createSessionCampaign({ aeSessionId: SESS, centreId: CENTRE, batchId: BATCH, scheduledAt: new Date(), createdById: ADMIN });
    await assignSessionCallers({ campaignId: camp.id, centreId: CENTRE, assignments: [{ batch_member_id: member.id, caller_id: CALLER }], now: new Date() });

    // 3) listSessionCampaigns must expose the existing assignment with batchMember.id + caller.id.
    const camps = await listSessionCampaigns({ centreId: CENTRE, status: SessionCampaignStatus.active });
    const row = camps.find((c) => c.aeSessionId === SESS) as any;
    check("campaign present in list", !!row);
    const a = row?.assignments?.find((x: any) => x.batchMember?.id === member.id);
    check("existing assignment exposes batchMember.id", !!a && a.batchMember?.id === member.id);
    check("existing assignment exposes caller.id (for pre-select)", !!a && a.caller?.id === CALLER,
      `— got caller=${a?.caller?.id}`);
    check("campaign exposes sessionName for the heading", !!row && row.sessionName === "XTest Assign Session");
  } finally {
    await cleanup();
  }

  console.log(fails === 0
    ? "\nRESULT: PASS — assign dialog gets named members + loads existing assignments."
    : `\nRESULT: FAIL — ${fails} check(s) failed.`);
  process.exit(fails === 0 ? 0 : 1);
};

run().catch((e) => { console.error("session-assign test error:", e); process.exit(1); });
