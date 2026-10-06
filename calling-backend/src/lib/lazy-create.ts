// Lazy-create safety net for mirror rows.
// When a webhook or domain operation references a Batch/Session that isn't in our mirror yet
// (race: webhook arrived before batch.created, or reconcile hasn't run yet), this helper
// fetches the row from AttendEase DB (or HTTP fallback) and upserts it.
// Returns true if the row was found and upserted, false if not found in AttendEase.

import { rawPrisma } from "../config/prisma.js";
import { attendeaseRepo } from "./attendease-db.js";
import { attendeaseHttp } from "./attendease-http.js";
import { env } from "../config/env.js";

const log = (msg: string) => console.log(`[lazy-create] ${msg}`);

// Resolve centre PK from AttendEase centre_id slug
const resolveCentrePk = async (centreCode: string): Promise<number | null> => {
  const centre = await rawPrisma.centre.findUnique({ where: { code: centreCode } });
  return centre?.id ?? null;
};

export const lazyCreateBatch = async (batchId: number): Promise<boolean> => {
  // Already exists?
  const existing = await rawPrisma.batch.findUnique({ where: { id: batchId } });
  if (existing) return true;

  log(`batch ${batchId} not in mirror — fetching from AttendEase`);

  let row: { name: string; centre_id: string; category: string; is_default: boolean; status: string } | null = null;

  if (env.attendeaseDbPath) {
    const db = attendeaseRepo.batchById(batchId);
    if (db) {
      row = { name: db.name, centre_id: db.centre_id, category: db.category, is_default: !!db.is_default, status: db.status };
    }
  }

  if (!row) {
    // HTTP fallback — we don't know the centre_id upfront, so we can't use the HTTP endpoint
    // which requires centre_id. Log and return false.
    log(`batch ${batchId} not found in AttendEase DB; HTTP fallback requires centre_id — skipping`);
    return false;
  }

  const centreId = await resolveCentrePk(row.centre_id);
  if (!centreId) {
    log(`batch ${batchId}: centre ${row.centre_id} not in mirror — skipping`);
    return false;
  }

  await rawPrisma.batch.upsert({
    where: { id: batchId },
    update: { name: row.name, category: row.category, isDefault: row.is_default, status: row.status, lastSyncedAt: new Date() },
    create: { id: batchId, centreId, name: row.name, category: row.category, isDefault: row.is_default, status: row.status, lastSyncedAt: new Date() },
  });

  log(`batch ${batchId} lazily created`);
  return true;
};

export const lazyCreateSession = async (sessionId: string): Promise<boolean> => {
  const existing = await rawPrisma.session.findUnique({ where: { sessionId } });
  if (existing) return true;

  log(`session ${sessionId} not in mirror — fetching from AttendEase`);

  let row: { display_name: string; batch_id: number; centre_id: string; category: string; date: string | null; status: string } | null = null;

  if (env.attendeaseDbPath) {
    const db = attendeaseRepo.sessionById(sessionId);
    if (db) {
      row = { display_name: db.display_name, batch_id: db.batch_id, centre_id: db.centre_id, category: db.category, date: db.date, status: db.status };
    }
  }

  if (!row) {
    log(`session ${sessionId} not found in AttendEase DB — skipping`);
    return false;
  }

  const centreId = await resolveCentrePk(row.centre_id);
  if (!centreId) {
    log(`session ${sessionId}: centre ${row.centre_id} not in mirror — skipping`);
    return false;
  }

  // Ensure the parent batch exists
  await lazyCreateBatch(row.batch_id);

  await rawPrisma.session.upsert({
    where: { sessionId },
    update: { displayName: row.display_name, category: row.category, date: row.date ? new Date(row.date) : null, status: row.status, lastSyncedAt: new Date() },
    create: {
      sessionId,
      displayName: row.display_name,
      batchId: row.batch_id,
      centreId,
      category: row.category,
      date: row.date ? new Date(row.date) : null,
      status: row.status,
      lastSyncedAt: new Date(),
    },
  });

  log(`session ${sessionId} lazily created`);
  return true;
};
