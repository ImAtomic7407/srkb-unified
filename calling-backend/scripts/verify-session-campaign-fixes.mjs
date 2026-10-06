// Verify the 2026-08-17 session-campaign fixes against the real compiled service.
//
// Exercises the service functions directly rather than going through HTTP, so no credentials are
// involved. The reopen guard needs a CLOSED campaign whose AttendEase session is gone; no campaign
// is currently in that exact state, so this temporarily flips campaign #33 (a July test row,
// "Testing calling session 1", whose session really is deleted) from completed -> closed, asserts
// the guard refuses it, and puts it straight back. The restore runs in a finally block.
import { rawPrisma } from "../dist/src/config/prisma.js";
import { listSessionCampaigns, reopenSessionCampaign } from "../dist/src/modules/sessionCampaign/sessionCampaign.service.js";

const CENTRE = 1;
let pass = 0, fail = 0;
const ok = (c, m) => { c ? (pass++, console.log("   PASS " + m)) : (fail++, console.log("   FAIL " + m)); };

console.log("=".repeat(70));
console.log("A. listSessionCampaigns — newest-first + sessionMissing flag");
const list = await listSessionCampaigns({ centreId: CENTRE, status: undefined });
for (const c of list) {
  console.log(`   #${c.id} ${String(c.status).padEnd(10)} missing=${String(c.sessionMissing).padEnd(5)} ` +
              `${new Date(c.scheduledAt).toISOString().slice(0, 10)}  ${JSON.stringify(c.sessionName)}`);
}
const times = list.map((c) => new Date(c.scheduledAt).getTime());
ok(times.every((t, i) => i === 0 || times[i - 1] >= t), "ordered newest-first");
ok(list.every((c) => "sessionMissing" in c), "every row carries sessionMissing");
const orphans = list.filter((c) => c.sessionMissing);
console.log(`   orphaned campaigns flagged: ${orphans.length} -> ${orphans.map((c) => "#" + c.id).join(", ") || "(none)"}`);
// #57 ("2026-08-17 CTRB CELIBACY") was DELETED on 2026-08-17 — it was an orphan with 0 outcomes,
// 0 call attempts, 0 followups and no AttendEase session behind it. It must now be absent entirely.
// #33 stays as the live example of the sessionMissing flag: it is also orphaned, but it holds real
// history and so was deliberately kept.
ok(!list.some((c) => c.id === 57), "campaign #57 is gone (deleted as an empty orphan)");
ok(orphans.some((c) => c.id === 33), "campaign #33 still flagged as orphaned (kept: it has history)");

console.log();
console.log("=".repeat(70));
console.log("B. reopenSessionCampaign must refuse a campaign whose session is gone");
const before = await rawPrisma.sessionCampaign.findUnique({ where: { id: 33 } });
console.log(`   campaign #33 status before: ${before.status}  session=${JSON.stringify(before.aeSessionId)}`);
const sess = await rawPrisma.session.findUnique({ where: { sessionId: before.aeSessionId } });
console.log(`   its session mirror status : ${sess ? sess.status : "(no row)"}`);

try {
  await rawPrisma.sessionCampaign.update({ where: { id: 33 }, data: { status: "closed" } });
  console.log("   temporarily set to closed");
  let threw = null;
  try {
    await reopenSessionCampaign(33, CENTRE);
  } catch (e) {
    threw = e;
  }
  ok(threw !== null, "reopen threw instead of succeeding");
  if (threw) {
    console.log(`   status=${threw.statusCode ?? threw.status ?? "?"}  message=${threw.message}`);
    ok(/no longer exists in AttendEase/.test(threw.message), "refused for the right reason");
  }
  const after = await rawPrisma.sessionCampaign.findUnique({ where: { id: 33 } });
  ok(after.status === "closed", "campaign was NOT flipped to active by the refused reopen");
} finally {
  await rawPrisma.sessionCampaign.update({ where: { id: 33 }, data: { status: before.status } });
  const restored = await rawPrisma.sessionCampaign.findUnique({ where: { id: 33 } });
  ok(restored.status === before.status, `restored #33 to ${before.status}`);
}

console.log();
console.log("=".repeat(70));
console.log(`RESULT: ${pass} passed, ${fail} failed`);
await rawPrisma.$disconnect();
process.exit(fail ? 1 : 0);
