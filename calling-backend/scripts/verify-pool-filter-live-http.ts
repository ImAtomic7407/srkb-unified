/**
 * Verifies the pool "Filter people" dropdowns AND together — OVER HTTP, against the process that
 * is actually serving the app.
 *
 * WHY THIS EXISTS SEPARATELY FROM verify-pool-filter-intersects.ts
 * That script imports from ../src and runs under tsx, so it exercises the SOURCE TREE. pm2 runs
 * `dist/src/server.js` (see pm2-srkb.config.cjs). On 2026-08-15 the source-level script reported
 * ALL PASS while the app still returned the buggy 62, because the fix had been written to src/ and
 * never compiled into dist/. A green tsx script is NOT evidence that the running server is fixed.
 *
 * So this one goes over the wire. It proves three things at once: the code is correct, it was
 * built, and the built code is the code that is running.
 *
 *   npm run build && pm2 restart srkb-backend
 *   npx tsx scripts/verify-pool-filter-live-http.ts
 *
 * Read-only: every request is a GET. The JWT is minted locally with the server's own shared secret
 * for a real centre admin, so no password is needed and none is stored here.
 */
import jwt from "jsonwebtoken";
import { rawPrisma } from "../src/config/prisma.js";
import { env } from "../src/config/env.js";

const BASE_URL = process.env.BASE_URL ?? "http://127.0.0.1:3000";
const CENTRE_ID = 2; // Centre A
const BATCH_ID = 18; // "BG"
const SESSION_ID = "01-08-26";

let failed = 0;
const check = (name: string, cond: boolean, extra?: unknown) => {
  if (cond) console.log("ok:", name);
  else {
    console.error("FAIL:", name, extra ?? "");
    failed++;
  }
};

type Person = { mobile: string; matched_via: string[]; response_label: string | null; name: string };

const main = async () => {
  const admin = await rawPrisma.user.findFirst({
    where: { centreId: CENTRE_ID, role: "ADMIN" },
    select: { mobile: true, role: true, name: true },
  });
  if (!admin) throw new Error(`no ADMIN at centre ${CENTRE_ID} to authenticate as`);
  const token = jwt.sign({ sub: admin.mobile, role: admin.role }, env.jwtSharedSecret, { expiresIn: "10m" });
  console.log(`  auth: ${admin.name} (${admin.role}) at centre ${CENTRE_ID} via ${BASE_URL}\n`);

  const get = async (params: Record<string, string>): Promise<Person[]> => {
    const qs = new URLSearchParams(params).toString();
    const res = await fetch(`${BASE_URL}/pool/batch-members?${qs}`, {
      headers: { Authorization: `Bearer ${token}`, "x-centre-id": String(CENTRE_ID) },
    });
    const body = (await res.json()) as { data?: { items?: Person[] }; message?: string };
    if (!res.ok) throw new Error(`${res.status} ${body.message ?? ""} for ?${qs}`);
    return body.data?.items ?? [];
  };

  const byBatch = await get({ category: "youth", batch_id: String(BATCH_ID) });
  const bySession = await get({ category: "youth", session_id: SESSION_ID });
  const both = await get({ category: "youth", batch_id: String(BATCH_ID), session_id: SESSION_ID });

  console.log(`  batch only   : ${byBatch.length}`);
  console.log(`  session only : ${bySession.length}`);
  console.log(`  batch+session: ${both.length}\n`);

  check("the live server answers the pool filter at all", byBatch.length > 0 && bySession.length > 0);

  // The exact symptom the user reported: picking a session changed nothing.
  check(
    "picking a session NARROWS the batch (the reported bug)",
    both.length < byBatch.length,
    { both: both.length, batch: byBatch.length },
  );

  const batchM = new Set(byBatch.map((p) => p.mobile));
  const sessM = new Set(bySession.map((p) => p.mobile));
  const expected = [...sessM].filter((m) => batchM.has(m));
  check(
    "batch+session is exactly the set intersection",
    both.length === expected.length && both.every((p) => batchM.has(p.mobile) && sessM.has(p.mobile)),
    { got: both.length, expected: expected.length },
  );

  check(
    "every person returned matched both filters",
    both.every((p) => p.matched_via.includes("batch") && p.matched_via.includes("session")),
  );

  const none = await get({ category: "youth", batch_id: String(BATCH_ID), session_id: "__no_such_session__" });
  check("an empty session yields an empty list, not the whole batch", none.length === 0, none.length);

  // ── include=attended|responded|both ───────────────────────────────────────────────────────────
  console.log("\n  --- positive-response filter ---");

  // REGRESSION GUARD on the bug fixed above: adding the feature must not re-widen the default.
  const attendedExplicit = await get({
    category: "youth",
    batch_id: String(BATCH_ID),
    session_id: SESSION_ID,
    include: "attended",
  });
  check(
    "include=attended is identical to sending no include at all",
    attendedExplicit.length === both.length &&
      attendedExplicit.every((p) => both.some((q) => q.mobile === p.mobile)),
    { attended: attendedExplicit.length, default: both.length },
  );

  const responded = await get({ category: "youth", batch_id: String(BATCH_ID), session_id: SESSION_ID, include: "responded" });
  const union = await get({ category: "youth", batch_id: String(BATCH_ID), session_id: SESSION_ID, include: "both" });
  console.log(`  attended : ${both.length}`);
  console.log(`  responded: ${responded.length}`);
  console.log(`  both     : ${union.length}`);

  check("positive responders are found for a called session", responded.length > 0);
  check(
    "every responder is tagged 'response' and carries the label that classified them",
    responded.every((p) => p.matched_via.includes("response") && !!p.response_label),
    responded.find((p) => !p.response_label)?.mobile,
  );

  // The trap: 'response' must NOT be treated as a fourth required tag, or `both` collapses back
  // into `attended` and the feature silently does nothing.
  check(
    "include=both does NOT collapse back to include=attended",
    union.length > both.length,
    { both: union.length, attended: both.length },
  );

  const unionMobiles = union.map((p) => p.mobile);
  check("include=both contains no duplicate people", new Set(unionMobiles).size === unionMobiles.length, {
    rows: unionMobiles.length,
    distinct: new Set(unionMobiles).size,
  });

  // both === attended ∪ responded, exactly.
  const expectedUnion = new Set([...both.map((p) => p.mobile), ...responded.map((p) => p.mobile)]);
  check(
    "include=both is exactly attended ∪ responded",
    union.length === expectedUnion.size && union.every((p) => expectedUnion.has(p.mobile)),
    { got: union.length, expected: expectedUnion.size },
  );

  // Someone who both attended AND answered positively must appear once, tagged with both.
  const dual = union.filter((p) => p.matched_via.includes("response") && p.matched_via.includes("session"));
  console.log(`  attended AND responded positively: ${dual.length}`);
  check("people who both attended and responded are merged, not duplicated", dual.every((p) => !!p.response_label));

  // A positive response is meaningless without a calling session — the server must say so.
  const qs = new URLSearchParams({ category: "youth", batch_id: String(BATCH_ID), include: "responded" }).toString();
  const badRes = await fetch(`${BASE_URL}/pool/batch-members?${qs}`, {
    headers: { Authorization: `Bearer ${token}`, "x-centre-id": String(CENTRE_ID) },
  });
  check("include=responded without a session_id is rejected, not silently ignored", badRes.status === 400, badRes.status);

  console.log(`\n  responses that classified the responders:`);
  const labelCount = new Map<string, number>();
  for (const p of responded) labelCount.set(p.response_label ?? "?", (labelCount.get(p.response_label ?? "?") ?? 0) + 1);
  for (const [l, n] of [...labelCount.entries()].sort((a, b) => b[1] - a[1])) console.log(`    ${String(n).padStart(3)}  ${l}`);

  console.log(failed ? `\n${failed} FAILED` : "\nALL PASS — the RUNNING server intersects the filters");
  process.exitCode = failed ? 1 : 0;
};

main()
  .catch((e) => {
    console.error("ERROR:", e);
    process.exitCode = 1;
  })
  .finally(() => rawPrisma.$disconnect());
