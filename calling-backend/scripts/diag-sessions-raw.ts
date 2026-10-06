/**
 * DIAGNOSTIC ONLY — read-only. Reads the sessions mirror with the raw SQLite driver, bypassing
 * Prisma's type conversion, because `rawPrisma.session.findMany()` throws
 * `P2023 Conversion failed: input contains invalid characters` — and `listSessionsForFilter`
 * (which backs the pool-assignment session dropdowns) issues that exact query.
 *
 *   npx tsx scripts/diag-sessions-raw.ts
 */
import Database from "better-sqlite3";
import path from "node:path";

const DB = path.resolve(process.cwd(), "prisma/dev.db");
const db = new Database(DB, { readonly: true });

const head = (s: string) => console.log(`\n===== ${s} =====`);
const show = (v: unknown) =>
  v === null ? "NULL" : `${typeof v}:${JSON.stringify(String(v))}`;

type Row = {
  session_id: string;
  display_name: string;
  batch_id: number | null;
  centre_id: number;
  category: string | null;
  date: unknown;
  status: string | null;
  last_synced_at: unknown;
};

const rows = db.prepare(`SELECT * FROM sessions ORDER BY centre_id, category, date`).all() as Row[];
console.log(`${rows.length} rows in sessions (db: ${DB})`);

head("distinct shapes of the `date` column");
const shapes = new Map<string, number>();
for (const r of rows) {
  const v = r.date;
  const shape =
    v === null ? "NULL"
      : typeof v === "number" ? "number (epoch ms)"
        : typeof v === "string" ? `string len=${v.length} sample=${JSON.stringify(v)}`
          : typeof v;
  shapes.set(shape, (shapes.get(shape) ?? 0) + 1);
}
for (const [k, n] of [...shapes].sort()) console.log(`  ${String(n).padStart(4)}  ${k}`);

head("distinct shapes of `last_synced_at`");
const shapes2 = new Map<string, number>();
for (const r of rows) {
  const v = r.last_synced_at;
  const shape =
    v === null ? "NULL"
      : typeof v === "number" ? "number (epoch ms)"
        : typeof v === "string" ? `string len=${v.length} sample=${JSON.stringify(v)}`
          : typeof v;
  shapes2.set(shape, (shapes2.get(shape) ?? 0) + 1);
}
for (const [k, n] of [...shapes2].sort()) console.log(`  ${String(n).padStart(4)}  ${k}`);

head("EVERY row Prisma cannot convert (date/last_synced_at not a clean epoch-ms integer)");
const bad = rows.filter((r) => {
  for (const v of [r.date, r.last_synced_at]) {
    if (v === null) continue;
    if (typeof v !== "number" || !Number.isInteger(v)) return true;
  }
  return false;
});
console.log(`  ${bad.length} bad rows`);
for (const r of bad) {
  console.log(
    `  centre ${r.centre_id} cat=${r.category} status=${r.status} batch=${r.batch_id}\n` +
    `      session_id   ${show(r.session_id)}\n` +
    `      display_name ${show(r.display_name)}\n` +
    `      date         ${show(r.date)}\n` +
    `      last_synced  ${show(r.last_synced_at)}`
  );
}

head("all CONGREGATION rows, verbatim");
for (const r of rows.filter((r) => r.category === "congregation")) {
  console.log(
    `  centre ${r.centre_id} status=${r.status} batch=${r.batch_id}  ${r.display_name}\n` +
    `      session_id ${show(r.session_id)}  date ${show(r.date)}  last_synced ${show(r.last_synced_at)}`
  );
}

head("count by centre + category + status");
const cc = new Map<string, number>();
for (const r of rows) {
  const k = `centre ${String(r.centre_id).padEnd(5)} cat=${String(r.category).padEnd(14)} status=${r.status}`;
  cc.set(k, (cc.get(k) ?? 0) + 1);
}
for (const [k, n] of [...cc].sort()) console.log(`  ${k}  n=${n}`);

db.close();
