/**
 * Guards the Pool Assignment screen against the two outages found on 2026-08-15, for EVERY centre
 * and BOTH categories — over HTTP, against the process actually serving the app.
 *
 * Both failures were invisible from one centre. The screen was fine at Centre A and returning 500
 * at Centre B, so a check that tests "a centre" rather than "every centre" would have passed
 * while a centre's whole workflow was down. Both categories for the same reason: congregation and
 * youth take different code paths through the category filter.
 *
 *   1. GET /pool/sessions — one `sessions` row whose DateTime columns were TEXT rather than
 *      epoch-ms made Prisma fail the WHOLE query (P2023), so the session dropdowns were empty.
 *      Repaired by scripts/repair-session-date-types.ts; this asserts it stays repaired.
 *   2. GET /pool/new-registrations — a Prisma relation `take: 1` panics the query engine
 *      ("no entry found for key") past ~999 parent rows. Centre B had 1,672 and was dead.
 *      Fixed in pool.service.ts (attachLatestCallAttempts); this asserts it stays fixed AT SCALE,
 *      which is the only way it ever shows up.
 *
 * Over HTTP on purpose: pm2 runs dist/, and a tsx script importing ../src exercises the source
 * tree instead — that difference hid a fix here for a day.
 *
 *   npx tsx scripts/verify-pool-screen-live-http.ts
 */
import jwt from "jsonwebtoken";
import { rawPrisma } from "../src/config/prisma.js";
import { env } from "../src/config/env.js";

const BASE_URL = process.env.BASE_URL ?? "http://127.0.0.1:3000";

let failed = 0;
const check = (name: string, cond: boolean, extra?: unknown) => {
  if (cond) console.log("ok:", name);
  else {
    console.error("FAIL:", name, extra ?? "");
    failed++;
  }
};

const main = async () => {
  const centres = await rawPrisma.centre.findMany({ select: { id: true, code: true, name: true } });

  // The relation-take panic only appears past ~999 rows, so a run where no centre is anywhere near
  // that ceiling proves much less than it looks. Say so rather than reporting a hollow pass.
  let sawLargePool = false;

  for (const centre of centres) {
    const admin = await rawPrisma.user.findFirst({
      where: { centreId: centre.id, role: "ADMIN" },
      select: { mobile: true, role: true },
    });
    if (!admin) {
      console.log(`  (skipping centre ${centre.id} ${centre.code} — no ADMIN to authenticate as)`);
      continue;
    }
    const token = jwt.sign({ sub: admin.mobile, role: admin.role }, env.jwtSharedSecret, { expiresIn: "10m" });
    const headers = { Authorization: `Bearer ${token}`, "x-centre-id": String(centre.id) };

    for (const category of ["youth", "congregation"] as const) {
      const tag = `centre ${centre.id} (${centre.code}) / ${category}`;

      const sessions = await fetch(`${BASE_URL}/pool/sessions?category=${category}`, { headers });
      check(`${tag}: session dropdown loads`, sessions.ok, sessions.status);

      const pool = await fetch(
        `${BASE_URL}/pool/new-registrations?category=${category}&assigned_status=all`,
        { headers },
      );
      const body = (await pool.json()) as { data?: { count?: number; items?: unknown[] } };
      check(`${tag}: pool table loads`, pool.ok, pool.status);

      const count = body.data?.count ?? 0;
      if (pool.ok && count > 999) {
        sawLargePool = true;
        check(`${tag}: pool of ${count} is past the ~999 relation-take ceiling and still loads`, true);
      }
      // A count the server reports must match what it actually sent, or the screen silently
      // truncates — the same class of "looks right, is short" bug as under-filtering.
      if (pool.ok) {
        check(`${tag}: reported count matches items returned`, count === (body.data?.items?.length ?? 0), {
          count,
          items: body.data?.items?.length,
        });
      }
    }
  }

  console.log("");
  if (!sawLargePool) {
    console.log(
      "NOTE: no centre currently has >999 unassigned registrations, so the relation-take ceiling " +
      "was not actually exercised this run. This suite only proves that regression is gone while " +
      "some centre is above the threshold.",
    );
  }
  if (failed > 0) {
    console.error(`${failed} check(s) FAILED`);
    process.exit(1);
  }
  console.log("ALL PASS — every centre's Pool Assignment screen loads in both categories");
};

main()
  .catch((e) => { console.error(e); process.exit(1); })
  .finally(() => rawPrisma.$disconnect());
