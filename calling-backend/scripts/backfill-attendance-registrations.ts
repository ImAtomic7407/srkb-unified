/**
 * Attendance = registration backfill.
 *
 * The system's rule (person-directory.ts): a person's FIRST attendance IS their registration.
 * The live path now materializes a CB `registrations` row on attendance
 * (ensureRegistrationForAttendance). This backfills the same row for everyone who ALREADY attended
 * before that change, so attendance-only people appear in the pool / lower filter (which read the
 * `registrations` table), not just in the batch roster.
 *
 * Idempotent: creates a registration only where none exists for (normalizedMobile, centreId). Never
 * touches an existing registration (no stage/queue clobber). Batch membership is untouched.
 *
 * Usage:
 *   DRY_RUN=1 npx tsx scripts/backfill-attendance-registrations.ts   # report only
 *   npx tsx scripts/backfill-attendance-registrations.ts             # apply
 */

import Database from "better-sqlite3";
import path from "path";
import { fileURLToPath } from "url";
import { PrismaClient } from "@prisma/client";

const DRY_RUN = process.env.DRY_RUN === "1";
const AE_DB_PATH =
  process.env.AE_DB_PATH ??
  path.resolve(fileURLToPath(import.meta.url), "../../../replica excel managment/database/attendance.db");

const prisma = new PrismaClient();
const log = (...a: unknown[]) => console.log("[attreg]", ...a);
const warn = (...a: unknown[]) => console.warn("[attreg][WARN]", ...a);

const normalizeMobile = (m: string) => m.replace(/\D/g, "").slice(-10);
const isValidCategory = (c: string | null | undefined): c is "youth" | "congregation" =>
  c === "youth" || c === "congregation";

interface AEAtt { mobile: string; centre_id: string; category: string | null; batch_name: string | null; name: string | null }
interface AEBatch { name: string; centre_id: string; category: string }
interface AEUser { mobile: string; name: string | null }

async function main() {
  log(`Starting${DRY_RUN ? " (DRY RUN — no writes)" : ""}`);
  const aeDb = new Database(AE_DB_PATH, { readonly: true });

  // centre slug -> CB PK
  const cbCentres = await prisma.centre.findMany({ select: { id: true, code: true } });
  const centreCodeToId = new Map(cbCentres.map((c) => [c.code, c.id]));

  // AE batch (centre_id, name) -> category  (fallback when attendance.category is null)
  const aeBatches = aeDb.prepare("SELECT name, centre_id, category FROM batches").all() as AEBatch[];
  const batchCat = new Map(aeBatches.map((b) => [`${b.centre_id}::${b.name}`, b.category]));

  // AE person directory: mobileKey -> name
  const aeUsers = aeDb.prepare("SELECT mobile, name FROM users").all() as AEUser[];
  const nameByMobile = new Map<string, string>();
  for (const u of aeUsers) {
    const n = (u.name ?? "").trim();
    if (n) nameByMobile.set(normalizeMobile(u.mobile), n);
  }

  // Existing CB registrations: set of `centreId:normalizedMobile`
  const existingRegs = await prisma.registration.findMany({ select: { centreId: true, normalizedMobile: true } });
  const existingSet = new Set(existingRegs.map((r) => `${r.centreId}:${r.normalizedMobile}`));
  log(`${existingSet.size} existing registrations loaded`);

  const attRows = aeDb
    .prepare("SELECT mobile, centre_id, category, batch_name, name FROM attendance ORDER BY timestamp ASC")
    .all() as AEAtt[];
  log(`${attRows.length} attendance rows to scan`);

  // Dedup to unique (cbCentreId, normalizedMobile) -> {category, name}
  const toCreate = new Map<string, { centreId: number; category: "youth" | "congregation"; mobile: string; name: string }>();
  let skipNoCentre = 0, skipBadMobile = 0, skipExisting = 0, skipNoCategory = 0;

  for (const r of attRows) {
    if (!r.mobile || !r.centre_id) { skipBadMobile++; continue; }
    const centreId = centreCodeToId.get(r.centre_id);
    if (!centreId) { skipNoCentre++; continue; }
    const nm = normalizeMobile(r.mobile);
    if (nm.length < 10) { skipBadMobile++; continue; }
    const key = `${centreId}:${nm}`;
    if (existingSet.has(key) || toCreate.has(key)) { if (existingSet.has(key)) skipExisting++; continue; }

    const cat = isValidCategory(r.category)
      ? r.category
      : (r.batch_name && isValidCategory(batchCat.get(`${r.centre_id}::${r.batch_name}`)) ? (batchCat.get(`${r.centre_id}::${r.batch_name}`) as "youth" | "congregation") : "youth");
    if (!isValidCategory(cat)) { skipNoCategory++; continue; }

    const name = nameByMobile.get(nm) || (r.name?.trim() || nm);
    toCreate.set(key, { centreId, category: cat, mobile: nm, name });
  }

  // Per-centre summary
  const byCentre = new Map<number, number>();
  for (const v of toCreate.values()) byCentre.set(v.centreId, (byCentre.get(v.centreId) ?? 0) + 1);
  log(`To create: ${toCreate.size} registrations`);
  for (const [cid, n] of [...byCentre.entries()].sort((a, b) => b[1] - a[1])) {
    const code = cbCentres.find((c) => c.id === cid)?.code ?? cid;
    log(`   centre ${cid} (${code}): ${n}`);
  }
  log(`Skipped — centre not in CB: ${skipNoCentre}, bad mobile: ${skipBadMobile}, already registered: ${skipExisting}, no category: ${skipNoCategory}`);

  if (DRY_RUN) { log("DRY RUN — no writes"); await prisma.$disconnect(); aeDb.close(); return; }

  const entries = [...toCreate.values()];
  let created = 0, errors = 0;
  const SIZE = 200;
  for (let i = 0; i < entries.length; i += SIZE) {
    const chunk = entries.slice(i, i + SIZE);
    await prisma.$transaction(async (tx) => {
      for (const e of chunk) {
        try {
          await tx.registration.create({
            data: {
              name: e.name,
              mobile: e.mobile,
              normalizedMobile: e.mobile,
              centreId: e.centreId,
              category: e.category,
              currentStage: "registered",
              activeQueue: "none",
            },
          });
          created++;
        } catch (err: unknown) {
          const msg = err instanceof Error ? err.message : String(err);
          if (msg.includes("Unique constraint")) skipExisting++;
          else { errors++; warn(`create failed ${e.centreId}:${e.mobile}:`, msg); }
        }
      }
    });
    log(`Progress: ${Math.min(i + SIZE, entries.length)}/${entries.length}, created ${created}`);
  }
  log(`Done. Created: ${created}, Errors: ${errors}. Run reconcile-stats next to refresh counters.`);
  await prisma.$disconnect();
  aeDb.close();
}

main().catch((err) => { console.error("[attreg] Fatal:", err); process.exit(1); });
