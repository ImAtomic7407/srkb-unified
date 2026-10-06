// Domain events — the spine of the event-driven read model.
//
// A domain event names a state change that just happened, scoped to one (centreId,
// category). `applyDomainEvent(tx, event)` is called INSIDE the transaction that performed
// the write and recomputes the affected centre_stats counters from source on that same `tx`.
// Because the recount runs on the write's transaction client, it sees the write's own
// (still-uncommitted) changes, so the cached numbers are exact the instant the transaction
// commits — real-time and consistent, with no eventual-consistency window internally.
//
// Why recompute-from-source instead of +1/-1 deltas?
//   The dashboard counters have compound predicates (e.g. new_registrations = stage=registered
//   AND queue=none AND no active assignment). Hand-derived deltas across ~10 transitions are
//   easy to get subtly wrong, and a wrong delta drifts silently until the daily reconcile.
//   Recomputing the affected (centre, category) slice is correct-by-construction and cheap
//   (a handful of indexed COUNTs, on the WRITE path — reads stay O(1), which was the goal).
//
// Cross-system effects (notifying AttendEase, etc.) are NOT done here — those go through the
// async outbox (outbox.ts) so a slow/offline downstream never blocks the write.
//
// Adding a new transition: import applyDomainEvent and call it inside that transition's
// `prisma.$transaction`, passing the affected registration's centreId + category. Pick the
// closest event `type` (purely a label for logging) — every type recomputes the full
// per-category metric set, so you can never under-specify which counter changed.

import { Category } from "@prisma/client";
import { recomputeCentreCategory, type ProjectionClient } from "./projections.js";

// ── Event catalogue ──────────────────────────────────────────────────────────
// `type` is a human/log label; `centreId` + `category` select the read-model slice to
// refresh. Every event refreshes ALL per-category metrics for that slice.

export type DomainEventType =
  | "registration.created" // new person registered
  | "registration.assigned_calling" // moved from new pool into calling
  | "calling.done" // calling result recorded → followup / removed / continue
  | "calling.reassigned" // calling assignment reassigned to another caller
  | "followup.done" // followup result recorded → my_attendance / stay / removed
  | "attendance.matched" // attendance sync matched a person → my_attendance
  | "batch.changed" // batch-calling membership / move-request change
  | "registration.archived" // archived from any queue
  | "registration.restored" // restored from archive
  | "registration.removed"; // removed from running

export type DomainEvent = {
  type: DomainEventType;
  centreId: number;
  category: Category;
};

// ── Dispatch ─────────────────────────────────────────────────────────────────

/**
 * Refresh the read-model counters affected by a domain event, within the given transaction
 * client. Call this in the SAME `prisma.$transaction` as the domain write so the cache
 * commits atomically with the source change.
 *
 * Never throws: a projection failure is logged and swallowed so it cannot roll back a valid
 * domain write. Any counter left stale this way self-heals on the next reconcile.
 */
export const applyDomainEvent = async (
  tx: ProjectionClient,
  event: DomainEvent,
): Promise<void> => {
  try {
    await recomputeCentreCategory(tx, event.centreId, event.category);
  } catch (err) {
    console.warn(
      `[events] read-model refresh failed for "${event.type}" (centre ${event.centreId}, ${
        event.category
      }): ${err instanceof Error ? err.message : String(err)} — will self-heal on next reconcile`,
    );
  }
};
