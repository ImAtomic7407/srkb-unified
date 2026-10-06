/**
 * Proves handleSessionDeleted settles BOTH halves of a deleted AttendEase session.
 *
 * Before the 2026-08-14 fix it marked only the `sessions` mirror row `deleted`, which hides the
 * session from the registerer/pool dropdowns but leaves its calling campaign `active` — and
 * listSessionCampaigns never joins to `sessions`, so the campaign kept being offered in
 * "Manage calling session" for a session that no longer existed.
 *
 * MUST be run against a throwaway copy of the database, never the live one:
 *
 *   cp prisma/dev.db /tmp/t.db
 *   DATABASE_URL="file:/tmp/t.db" npx tsx scripts/verify-session-deleted-closes-campaign.ts
 *
 * It refuses to run if DATABASE_URL still points at dev.db.
 */
import { rawPrisma } from "../src/config/prisma.js";
import { handleSessionDeleted } from "../src/modules/sync/handlers/batch.handler.js";

const url = process.env.DATABASE_URL ?? "";
if (!url || /dev\.db(\?|$)/.test(url)) {
  console.error(`REFUSING TO RUN against '${url || "(unset)"}' — point DATABASE_URL at a COPY.`);
  process.exit(2);
}

let failed = 0;
const check = (name: string, cond: boolean, extra?: unknown) => {
  if (cond) console.log("ok:", name);
  else { console.error("FAIL:", name, extra ?? ""); failed++; }
};

const SID = "__verify_session_deleted__";

const main = async () => {
  const centre = await rawPrisma.centre.findFirst({ select: { id: true } });
  const batch = await rawPrisma.batch.findFirst({ where: { centreId: centre!.id }, select: { id: true } });
  const user = await rawPrisma.user.findFirst({ select: { id: true } });
  if (!centre || !batch || !user) throw new Error("copy has no centre/batch/user to build a fixture on");

  await rawPrisma.sessionCampaign.deleteMany({ where: { aeSessionId: SID } });
  await rawPrisma.session.deleteMany({ where: { sessionId: SID } });

  await rawPrisma.session.create({
    data: { sessionId: SID, displayName: "verify fixture", batchId: batch.id,
            centreId: centre.id, category: "youth", status: "active", lastSyncedAt: new Date() },
  });
  const campaign = await rawPrisma.sessionCampaign.create({
    data: { aeSessionId: SID, centreId: centre.id, batchId: batch.id,
            scheduledAt: new Date(), kind: "called", status: "active", createdById: user.id },
  });

  // Precondition: the campaign is offered by the very query the app uses.
  const visibleBefore = await rawPrisma.sessionCampaign.count({
    where: { centreId: centre.id, kind: "called", status: "active", aeSessionId: SID },
  });
  check("fixture starts visible in the calling-session list", visibleBefore === 1);

  await handleSessionDeleted({ payload: { session_id: SID } } as never);

  const session = await rawPrisma.session.findUnique({ where: { sessionId: SID } });
  const after = await rawPrisma.sessionCampaign.findUnique({ where: { id: campaign.id } });
  check("session mirror row is marked deleted", session?.status === "deleted", session?.status);
  check("its calling campaign is closed, not left active", after?.status === "closed", after?.status);
  check("the campaign row still EXISTS (closed, never deleted)", after !== null);

  const visibleAfter = await rawPrisma.sessionCampaign.count({
    where: { centreId: centre.id, kind: "called", status: "active", aeSessionId: SID },
  });
  check("no longer offered in the calling-session list", visibleAfter === 0, visibleAfter);

  // Idempotent: a replayed webhook must not mutate anything a second time.
  await handleSessionDeleted({ payload: { session_id: SID } } as never);
  const replayed = await rawPrisma.sessionCampaign.findUnique({ where: { id: campaign.id } });
  check("a replayed session.deleted is a no-op", replayed?.status === "closed", replayed?.status);

  // A campaign that was already completed must NOT be dragged back to closed.
  await rawPrisma.sessionCampaign.update({ where: { id: campaign.id }, data: { status: "completed" } });
  await handleSessionDeleted({ payload: { session_id: SID } } as never);
  const completed = await rawPrisma.sessionCampaign.findUnique({ where: { id: campaign.id } });
  check("a completed campaign is left alone", completed?.status === "completed", completed?.status);

  await rawPrisma.sessionCampaign.deleteMany({ where: { aeSessionId: SID } });
  await rawPrisma.session.deleteMany({ where: { sessionId: SID } });

  console.log(failed ? `\n${failed} FAILED` : "\nALL PASS");
  process.exitCode = failed ? 1 : 0;
};

main().catch((e) => { console.error("ERROR:", e); process.exitCode = 1; })
      .finally(() => rawPrisma.$disconnect());
