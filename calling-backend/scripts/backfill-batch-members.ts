/**
 * Phase 9: Batch-membership backfill
 *
 * Reads ALL historical attendance rows from the AttendEase SQLite DB and
 * creates BatchMember + BatchMemberAudit rows in CB for every
 * (centreId, category, mobile, batchId) tuple that has attendance but no
 * active membership yet.
 *
 * Safe to re-run: skips tuples that already have an active (removedAt=null)
 * BatchMember row.  Existing Batch / Session / Centre mirror rows are
 * never touched.
 *
 * Usage:
 *   npx tsx scripts/backfill-batch-members.ts
 *   DRY_RUN=1 npx tsx scripts/backfill-batch-members.ts
 */

import Database from "better-sqlite3";
import path from "path";
import { fileURLToPath } from "url";
import { PrismaClient } from "@prisma/client";

// ── config ────────────────────────────────────────────────────────────────────

const DRY_RUN = process.env.DRY_RUN === "1";

// Path to AttendEase SQLite DB (adjust if different)
const AE_DB_PATH =
  process.env.AE_DB_PATH ??
  path.resolve(
    fileURLToPath(import.meta.url),
    "../../../replica excel managment/database/attendance.db",
  );

const CHANGED_BY = "system:backfill";
const SOURCE = "backfill";

// ── setup ─────────────────────────────────────────────────────────────────────

const prisma = new PrismaClient();
const log = (...args: unknown[]) => console.log("[backfill]", ...args);
const warn = (...args: unknown[]) => console.warn("[backfill][WARN]", ...args);

// ── types ─────────────────────────────────────────────────────────────────────

interface AEAttendanceRow {
  mobile: string;
  batch_name: string;
  session_id: string;
  centre_id: string; // AE string slug e.g. "SRKB-MAIN"
  timestamp: string | null;
}

interface AEBatchRow {
  id: number;
  name: string;
  centre_id: string;
  category: string;
  is_default: number; // SQLite boolean
  status: string;
}

interface AECentreRow {
  centre_id: string;
  name: string;
}

// ── helpers ───────────────────────────────────────────────────────────────────

const normalizeMobile = (mobile: string) =>
  mobile.replace(/\D/g, "").slice(-10);

// ── main ──────────────────────────────────────────────────────────────────────

async function main() {
  log(`Starting${DRY_RUN ? " (DRY RUN — no writes)" : ""}`);
  log(`AE DB: ${AE_DB_PATH}`);

  const aeDb = new Database(AE_DB_PATH, { readonly: true });

  // ── 1. Load AE reference data ────────────────────────────────────────────

  const aeBatches = aeDb
    .prepare("SELECT id, name, centre_id, category, is_default, status FROM batches")
    .all() as AEBatchRow[];

  const aeCentres = aeDb
    .prepare("SELECT centre_id, name FROM centres")
    .all() as AECentreRow[];

  log(`AE has ${aeBatches.length} batches across ${aeCentres.length} centres`);

  // ── 2. Build lookup: AE centre_id → CB integer PK ───────────────────────

  const cbCentres = await prisma.centre.findMany({
    select: { id: true, code: true },
  });
  const centreCodeToId = new Map(cbCentres.map((c) => [c.code, c.id]));

  // ── 3. Build lookup: (AE centre_id, batch_name) → Batch row ──────────────

  const batchKey = (centreId: string, name: string) => `${centreId}::${name}`;
  const aeBatchMap = new Map<string, AEBatchRow>();
  for (const b of aeBatches) {
    aeBatchMap.set(batchKey(b.centre_id, b.name), b);
  }

  // ── 4. Load all CB Batch mirror rows: id → CB Batch ─────────────────────

  const cbBatches = await prisma.batch.findMany({
    select: { id: true, name: true, centreId: true, category: true, isDefault: true, status: true },
  });
  const cbBatchById = new Map(cbBatches.map((b) => [b.id, b]));

  // ── 5. Load all existing active BatchMember rows into a Set for O(1) dedup

  log("Loading existing active batch members…");
  const existingMembers = await prisma.batchMember.findMany({
    where: { removedAt: null },
    select: { centreId: true, category: true, mobile: true, batchId: true },
  });
  // key: `centreId:category:normalizedMobile:batchId`
  const existingSet = new Set(
    existingMembers.map(
      (m) => `${m.centreId}:${m.category}:${normalizeMobile(m.mobile)}:${m.batchId}`,
    ),
  );
  log(`${existingSet.size} existing active memberships loaded`);

  // ── 6. Stream attendance rows ────────────────────────────────────────────

  const attendanceRows = aeDb
    .prepare(
      `SELECT mobile, batch_name, session_id, centre_id, timestamp
       FROM attendance
       ORDER BY centre_id, mobile, timestamp ASC`,
    )
    .all() as AEAttendanceRow[];

  log(`${attendanceRows.length} total attendance rows to process`);

  // ── 7. Collect unique (centreId, category, mobile, batchId) tuples ────────

  // Track what we'll create: key → { centreId, category, mobile, batchId, joinedAt }
  const toCreate = new Map<
    string,
    { centreId: number; category: string; mobile: string; batchId: number; joinedAt: Date }
  >();

  let skippedNoCentre = 0;
  let skippedNoBatch = 0;
  let skippedNoCbBatch = 0;
  let skippedArchivedBatch = 0;
  let skippedAlreadyExists = 0;
  let skippedDefaultBatch = 0;

  for (const row of attendanceRows) {
    if (!row.mobile || !row.batch_name || !row.centre_id) continue;

    const cbCentreId = centreCodeToId.get(row.centre_id);
    if (!cbCentreId) {
      skippedNoCentre++;
      continue;
    }

    const aeBatch = aeBatchMap.get(batchKey(row.centre_id, row.batch_name));
    if (!aeBatch) {
      skippedNoBatch++;
      continue;
    }

    const cbBatch = cbBatchById.get(aeBatch.id);
    if (!cbBatch) {
      skippedNoCbBatch++;
      continue;
    }

    if (cbBatch.status === "archived") {
      skippedArchivedBatch++;
      continue;
    }

    // Skip default-batch attendance — R1 (registration) handles those
    if (cbBatch.isDefault || aeBatch.is_default) {
      skippedDefaultBatch++;
      continue;
    }

    const category = cbBatch.category ?? aeBatch.category ?? "youth";
    const normalizedMobile = normalizeMobile(row.mobile);
    const key = `${cbCentreId}:${category}:${normalizedMobile}:${aeBatch.id}`;

    if (existingSet.has(key)) {
      skippedAlreadyExists++;
      continue;
    }

    if (!toCreate.has(key)) {
      const joinedAt = row.timestamp ? new Date(row.timestamp) : new Date(0);
      toCreate.set(key, {
        centreId: cbCentreId,
        category,
        mobile: row.mobile,
        batchId: aeBatch.id,
        joinedAt,
      });
    }
  }

  log(`Tuples to create: ${toCreate.size}`);
  log(`Skipped — centre not in CB:     ${skippedNoCentre}`);
  log(`Skipped — batch not in AE map:  ${skippedNoBatch}`);
  log(`Skipped — batch not in CB:      ${skippedNoCbBatch}`);
  log(`Skipped — archived batch:       ${skippedArchivedBatch}`);
  log(`Skipped — default batch:        ${skippedDefaultBatch}`);
  log(`Skipped — already active member:${skippedAlreadyExists}`);

  if (DRY_RUN) {
    log("DRY RUN — exiting without writing");
    await prisma.$disconnect();
    aeDb.close();
    return;
  }

  // ── 8. Write in batches of 200 ────────────────────────────────────────────

  const BATCH_SIZE = 200;
  const entries = [...toCreate.values()];
  let created = 0;
  let errors = 0;

  for (let i = 0; i < entries.length; i += BATCH_SIZE) {
    const chunk = entries.slice(i, i + BATCH_SIZE);

    await prisma.$transaction(async (tx) => {
      for (const m of chunk) {
        try {
          const now = new Date();
          const member = await tx.batchMember.create({
            data: {
              centreId: m.centreId,
              category: m.category,
              batchId: m.batchId,
              mobile: m.mobile,
              joinedAt: m.joinedAt,
              removedAt: null,
              changedBy: CHANGED_BY,
              changedAt: now,
              source: SOURCE,
            },
          });
          await tx.batchMemberAudit.create({
            data: {
              batchMemberId: member.id,
              action: "backfill",
              byUser: CHANGED_BY,
              atTime: now,
              reason: "historical backfill from AttendEase attendance data",
              newState: JSON.stringify({
                centreId: m.centreId,
                category: m.category,
                batchId: m.batchId,
                mobile: m.mobile,
                source: SOURCE,
              }),
            },
          });
          created++;
        } catch (err: unknown) {
          // Unique constraint violation means a concurrent insert happened — safe to skip
          const msg = err instanceof Error ? err.message : String(err);
          if (msg.includes("Unique constraint")) {
            skippedAlreadyExists++;
          } else {
            errors++;
            warn(`Failed to create member for mobile=${m.mobile} batchId=${m.batchId}:`, msg);
          }
        }
      }
    });

    if ((i / BATCH_SIZE) % 10 === 0) {
      log(`Progress: ${Math.min(i + BATCH_SIZE, entries.length)}/${entries.length} processed, ${created} created`);
    }
  }

  log(`Done. Created: ${created}, Errors: ${errors}`);
  await prisma.$disconnect();
  aeDb.close();
}

main().catch((err) => {
  console.error("[backfill] Fatal:", err);
  process.exit(1);
});
