import { Category } from "@prisma/client";
import { recomputeCentreCategory } from "../../../lib/projections.js";
import { rawPrisma } from "../../../config/prisma.js";
import type { WebhookEvent } from "../sync.types.js";
import { onAttendancePersisted, repairRegistrationName } from "../../batch/batch-member.service.js";
import { ensureDirectAttendanceCampaign } from "../../sessionCampaign/sessionCampaign.service.js";
import { normalizeMobile } from "../../../utils/mobile.js";

const log = (msg: string) => console.log(`[sync:batch] ${msg}`);

// Resolve centre PK from AttendEase centre_id slug (code). Returns null if not found.
const resolveCentrePk = async (centreCode: string): Promise<number | null> => {
  const centre = await rawPrisma.centre.findUnique({ where: { code: centreCode } });
  return centre?.id ?? null;
};

export const handleBatchCreated = async (event: WebhookEvent): Promise<void> => {
  const p = event.payload as { id: number; centre_id: string; name: string; category: string; is_default: boolean; status: string };
  log(`batch.created id=${p.id} centre=${p.centre_id}`);

  const centreId = await resolveCentrePk(p.centre_id);
  if (!centreId) {
    log(`batch.created: centre ${p.centre_id} not found — skipping`);
    return;
  }

  await rawPrisma.batch.upsert({
    where: { id: p.id },
    update: { name: p.name, category: p.category, isDefault: p.is_default, status: p.status, lastSyncedAt: new Date() },
    create: { id: p.id, centreId, name: p.name, category: p.category, isDefault: p.is_default, status: p.status, lastSyncedAt: new Date() },
  });
};

export const handleBatchRenamed = async (event: WebhookEvent): Promise<void> => {
  const p = event.payload as { id: number; new_name: string };
  log(`batch.renamed id=${p.id} new_name=${p.new_name}`);

  await rawPrisma.batch.updateMany({
    where: { id: p.id },
    data: { name: p.new_name, lastSyncedAt: new Date() },
  });
};

export const handleBatchArchived = async (event: WebhookEvent): Promise<void> => {
  const p = event.payload as { id: number };
  log(`batch.archived id=${p.id}`);

  const batch = await rawPrisma.batch.findUnique({ where: { id: p.id } });
  if (batch) {
    await rawPrisma.batch.update({
      where: { id: p.id },
      data: { status: "archived", lastSyncedAt: new Date() },
    });
    await recomputeCentreCategory(rawPrisma, batch.centreId, batch.category as Category, ["batch_calling"]).catch(() => undefined);
  }
};

export const handleBatchCategorySet = async (event: WebhookEvent): Promise<void> => {
  const p = event.payload as { id: number; category: string };
  log(`batch.category_set id=${p.id} category=${p.category}`);

  const batch = await rawPrisma.batch.findUnique({ where: { id: p.id } });
  if (batch) {
    const oldCategory = batch.category;
    await rawPrisma.batch.update({
      where: { id: p.id },
      data: { category: p.category, lastSyncedAt: new Date() },
    });
    await recomputeCentreCategory(rawPrisma, batch.centreId, oldCategory as Category, ["batch_calling"]).catch(() => undefined);
    await recomputeCentreCategory(rawPrisma, batch.centreId, p.category as Category, ["batch_calling"]).catch(() => undefined);
  }
};

export const handleBatchDefaultSet = async (event: WebhookEvent): Promise<void> => {
  const p = event.payload as { id: number; is_default: boolean };
  log(`batch.default_set id=${p.id} is_default=${p.is_default}`);

  await rawPrisma.batch.updateMany({
    where: { id: p.id },
    data: { isDefault: p.is_default, lastSyncedAt: new Date() },
  });
};

export const handleSessionCreated = async (event: WebhookEvent): Promise<void> => {
  const p = event.payload as { session_id: string; display_name: string; batch_id: number; centre_id: string; category: string; date: string | null; status: string };
  log(`session.created id=${p.session_id} batch=${p.batch_id}`);

  const centreId = await resolveCentrePk(p.centre_id);
  if (!centreId) {
    log(`session.created: centre ${p.centre_id} not found — skipping`);
    return;
  }

  // Ensure parent batch is mirrored (lazy-create if needed)
  const batchExists = await rawPrisma.batch.findUnique({ where: { id: p.batch_id } });
  if (!batchExists) {
    log(`session.created: batch ${p.batch_id} not in mirror — deferring to lazy-create on next access`);
    return;
  }

  await rawPrisma.session.upsert({
    where: { sessionId: p.session_id },
    update: { displayName: p.display_name, category: p.category, date: p.date ? new Date(p.date) : null, status: p.status, lastSyncedAt: new Date() },
    create: {
      sessionId: p.session_id,
      displayName: p.display_name,
      batchId: p.batch_id,
      centreId,
      category: p.category,
      date: p.date ? new Date(p.date) : null,
      status: p.status,
      lastSyncedAt: new Date(),
    },
  });
};

export const handleSessionDeleted = async (event: WebhookEvent): Promise<void> => {
  const p = event.payload as { session_id: string };
  log(`session.deleted id=${p.session_id}`);

  await rawPrisma.session.updateMany({
    where: { sessionId: p.session_id },
    data: { status: "deleted", lastSyncedAt: new Date() },
  });

  // Marking the mirror row `deleted` only hides the session from the registerer and pool
  // dropdowns (both filter `status: { not: "deleted" }`). It does NOT touch the calling campaign,
  // and listSessionCampaigns filters on kind/status WITHOUT ever joining to `sessions` — so a
  // deleted session's calling campaign kept being offered in "Manage calling session" as though
  // it still existed. On 2026-08-14 that left the host staring at a calling session they had just
  // deleted, beside the replacement they had created, with no way to tell them apart.
  //
  // Closing is the right verb, not deleting: it preserves every assignment and recorded call, it
  // is reversible via reopenSessionCampaign, and — unlike `completed` — it triggers no absence
  // eviction. Outcomes still in a caller's offline outbox are unaffected either way, because
  // updateSessionCallStatus resolves by assignment id and deliberately accepts late responses.
  //
  // Scoped to `active` so a replayed or duplicated webhook is a no-op rather than a second,
  // surprising state change, and best-effort so a failure here cannot make the sync handler throw
  // and strand the session mirror update above.
  try {
    const closed = await rawPrisma.sessionCampaign.updateMany({
      where: { aeSessionId: p.session_id, status: "active" },
      data: { status: "closed" },
    });
    if (closed.count) {
      log(`session.deleted id=${p.session_id}: closed ${closed.count} active calling campaign(s)`);
    }
  } catch (e) {
    log(`session.deleted id=${p.session_id}: could not close campaign — ${String(e)}`);
  }
};

export const handleAttendancePersisted = async (event: WebhookEvent): Promise<void> => {
  const p = event.payload as { mobile: string; centre_id: string; batch_id: number | null; session_id?: string; date?: string };
  log(`attendance.persisted mobile=${p.mobile} centre=${p.centre_id} batch=${p.batch_id}`);

  const centre = await rawPrisma.centre.findUnique({ where: { code: p.centre_id } });
  if (!centre) {
    log(`attendance.persisted: centre ${p.centre_id} not in mirror — skipping`);
    return;
  }

  // Attendance is the moment AttendEase learns someone's name — the operator types it while
  // marking them present. Anyone who entered as a number alone (bulk import) still carries a
  // placeholder name here, and the code that chose it never runs again, so repair it now.
  // Deliberately BEFORE the batch checks below: those return early for ad-hoc sessions, and a
  // name repair must not depend on a batch being resolvable. Upgrade-only and best-effort.
  await repairRegistrationName(centre.id, p.mobile).catch(() => undefined);

  // The webhook payload doesn't always carry batch_id directly (e.g. ad-hoc/manual sessions) —
  // this used to silently skip R3 (no membership row created) whenever that happened, which is
  // why some attendance never showed up in batch membership even though the event fired
  // correctly. Resolve it via the session mirror when it's missing, same as the daily reconcile
  // job does for historical rows, so the live path doesn't depend on the payload always including it.
  const resolvedBatchId =
    p.batch_id ?? (p.session_id ? (await rawPrisma.session.findUnique({ where: { sessionId: p.session_id } }))?.batchId ?? null : null);

  if (!resolvedBatchId) {
    log(`attendance.persisted: no batch_id in payload and none resolvable from session ${p.session_id ?? "(none)"} — skipping R3`);
    return;
  }

  const batch = await rawPrisma.batch.findUnique({ where: { id: resolvedBatchId } });
  if (!batch) {
    log(`attendance.persisted: batch ${resolvedBatchId} not in mirror — skipping`);
    return;
  }

  // R3: auto-assign to non-default batch
  await onAttendancePersisted(centre.id, batch.category, p.mobile, resolvedBatchId);

  // If this attendance is for a session with no calling campaign, register it in the calling
  // system flagged as direct_attendance (idempotent, best-effort).
  if (p.session_id) {
    await ensureDirectAttendanceCampaign(p.session_id, centre.id).catch(() => undefined);
  }

  // Attendance history itself lives in AttendEase's DB (read live via attendeaseRepo) —
  // no local copy is kept. Just confirm mobile is well-formed for logging purposes.
  try {
    const normalizedMobileVal = normalizeMobile(p.mobile);
    log(`attendance.persisted: recorded mobile ${normalizedMobileVal} in centre ${centre.id}, batch ${p.batch_id}`);
  } catch {
    log(`attendance.persisted: invalid mobile "${p.mobile}"`);
  }
};
