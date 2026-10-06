// Fan AttendEase's webhook events out to connected devices over the existing SSE hub.
//
// AttendEase already pushes every change we care about here (batch/session lifecycle, attendance
// marks, roster imports) via /internal/attendease-event, and lib/sse.ts already holds every
// connected device. Until now the two were never joined: an event arrived, updated the mirror, and
// nothing told the phones. They found out on their own 30-second poll, or when the operator changed
// tab. This closes that gap.
//
// Design rule, and the reason this file is so small: a broadcast is a NOTIFICATION, NOT DATA.
// It says "something in this scope moved" and the client refetches through the paths it already
// has. Nothing here is authoritative — the payload must never be rendered. That keeps the fan-out
// free of business logic and means a bug here can, at worst, cause a redundant refetch.
//
// This is deliberately additive: it runs AFTER a handler has already succeeded, it never throws,
// and if it fails entirely the system behaves exactly as it did before (the poll still carries
// everything). It must never be able to break event ingestion.

import { rawPrisma } from "../config/prisma.js";
import { broadcastSse } from "./sse.js";
import { logger } from "./logger.js";

// Event kinds worth waking a device for. Anything not listed is ignored — account/permission
// events change nothing a screen is showing, and broadcasting them would be pure noise.
const BROADCAST_KINDS = new Set([
  "attendance.persisted",
  "session_attendance_recorded",
  "roster.injected",
  "batch.created",
  "batch.renamed",
  "batch.deleted",
  "batch.archived",
  "batch.category_set",
  "batch.default_set",
  "session.created",
  "session.renamed",
  "session.deleted",
  "centre.renamed",
  "centre.status_changed",
]);

// AttendEase identifies a centre by its string code ("centre_a_Base"); SSE targeting uses the
// calling backend's numeric id. Centre codes never change identity, so this mapping is stable and
// worth caching — it turns a DB round-trip into a map lookup on the hot path of every mark.
const centreIdByCode = new Map<string, number | null>();

const resolveCentreId = async (raw: unknown): Promise<number | null> => {
  if (typeof raw === "number" && Number.isFinite(raw)) return raw;
  if (typeof raw !== "string" || raw.trim() === "") return null;
  const code = raw.trim();

  const cached = centreIdByCode.get(code);
  if (cached !== undefined) return cached;

  try {
    const centre = await rawPrisma.centre.findUnique({ where: { code }, select: { id: true } });
    const id = centre?.id ?? null;
    // Cache misses too: an unknown code is usually a centre this backend does not mirror, and
    // re-querying it on every mark would be a pointless round-trip per attendance tap.
    centreIdByCode.set(code, id);
    return id;
  } catch {
    return null;
  }
};

const asText = (v: unknown): string | null =>
  typeof v === "string" && v.trim() !== "" ? v.trim() : null;

/**
 * Broadcast one AttendEase event to the devices that could be looking at it.
 *
 * Targeting is intentionally generous. When the centre cannot be resolved the event goes to
 * everyone rather than nobody: an extra refetch on an unrelated device costs one cheap request,
 * whereas a dropped notification leaves an operator staring at a stale count — which is the exact
 * complaint this whole change exists to fix. Clients filter on centre and category anyway.
 *
 * Never throws. Never awaits anything the caller depends on.
 */
export const broadcastAeEvent = async (event: {
  event_type?: string;
  payload?: Record<string, unknown>;
}): Promise<void> => {
  try {
    const kind = event?.event_type;
    if (!kind || !BROADCAST_KINDS.has(kind)) return;

    const payload = event.payload ?? {};
    const centreCode = asText(payload.centre_id);
    const centreId = await resolveCentreId(payload.centre_id);

    // Lowercase on the wire; the app normalises to its own title-case form. Absent means
    // "not category-specific" — the client then treats it as relevant to whichever track is open.
    const category = asText(payload.category)?.toLowerCase() ?? null;

    const scope: Record<string, unknown> = {};
    const sessionId = asText(payload.session_id) ?? asText(payload.ae_session_id);
    if (sessionId) scope.sessionId = sessionId;
    if (payload.batch_id !== undefined && payload.batch_id !== null) scope.batchId = payload.batch_id;

    const sent = broadcastSse(
      "data.changed",
      { kind, centreCode, centreId, category, scope, ts: Date.now() },
      { centreId },
    );

    if (sent > 0) logger.info(`sse_fanout: ${kind} centre=${centreCode ?? "?"} -> ${sent} client(s)`);
  } catch (err) {
    // A failed notification must never affect ingestion. The client's 30-second checksum poll
    // remains the floor and will pick the change up regardless.
    logger.warn(`sse_fanout failed for ${event?.event_type ?? "unknown"}: ${String(err)}`);
  }
};
