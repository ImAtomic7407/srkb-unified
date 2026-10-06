// "Remove after N of these in a row" — enforcement.
//
// ── Why this file exists ───────────────────────────────────────────────────────────────────────
//
// `DropdownOption.removeAfterConsecutive` has been in the schema, the API, the validator and the
// centre-admin editor since the response-semantics work. Nothing ever READ it. A centre could
// configure "remove after 2 switched-off calls", the value would round-trip through the editor
// perfectly, and absolutely nothing would happen. This is the consumer.
//
// ── Why it is not the strike system ────────────────────────────────────────────────────────────
//
// The obvious implementation is to make "Switch off" a negative response that strikes, and let the
// existing tolerance archiving remove them. That is wrong, and expensively so:
//
//   * sentiment `negative` makes resolveSessionStatusFrom return `called` instead of `no_answer`,
//     which changes every dashboard, forecast and reconciliation summary;
//   * `called` is not in STRIKE_EXCLUDED, so an unreachable phone would start counting toward the
//     GENERAL absence tolerance too — removing people faster than any configured threshold, by a
//     path nobody asked for.
//
// So the rule lives here instead: the response stays `not_reached` (not their fault, no strike, no
// effect on any tally), and removal is driven solely by an explicit per-option count. The two
// mechanisms never touch.
//
// ── Counting rule ──────────────────────────────────────────────────────────────────────────────
//
// The run is counted PER OPTION, newest-first, and stops at the first response that is anything
// else. "N of these in a row" is exactly what the schema comment promises. A legacy row (dropdownId
// NULL) matches only on its stored label, which means a run recorded under a retired label does not
// chain into a run under the new one — deliberately conservative: "No answer / Switched off" was a
// conflation of two distinct outcomes, and the granular options that replaced it are not the same
// response, so they do not accumulate together.

import { rawPrisma } from "../config/prisma.js";
import { attendeaseRepo } from "./attendease-db.js";
import { normalizeMobile } from "../utils/mobile.js";
import { recomputeCentreCategory } from "./projections.js";
import { RegistrationStage, ActiveQueue } from "@prisma/client";

/** How far back to look. A run longer than this is already far past any sane threshold (max 20). */
const HISTORY_WINDOW = 40;

export type ThresholdOption = {
  id: number;
  label: string;
  removeAfterConsecutive: number | null;
};

/** Prefix for the RemovedRecord reason. Distinct from "Absent N times …" so the tolerance system's
 *  restore path cannot mistake one for the other — they undo different decisions. */
export const UNREACHABLE_REASON_PREFIX = "Unreachable";

export const unreachableReason = (label: string, run: number): string =>
  `${UNREACHABLE_REASON_PREFIX} — "${label}" ${run} times in a row`;

/**
 * Apply an option's consecutive-removal rule after a response has been recorded.
 *
 * Returns true if the person was removed. Best-effort by contract: the response itself is already
 * saved by the time this runs, so every failure path here is swallowed by the caller rather than
 * failing the caller's write.
 */
export const enforceConsecutiveRemoval = async ({
  registrationId,
  centreId,
  mobile,
  option,
  now,
}: {
  registrationId: number;
  centreId: number;
  mobile: string;
  option: ThresholdOption | null | undefined;
  now: Date;
}): Promise<boolean> => {
  const threshold = option?.removeAfterConsecutive ?? null;
  if (!option || threshold === null || threshold < 2) return false;

  const history = await rawPrisma.callAttempt.findMany({
    where: { registrationId },
    orderBy: { createdAt: "desc" },
    take: HISTORY_WINDOW,
    select: { dropdownId: true, dropdownValue: true, createdAt: true },
  });

  // Newest-first tail run of THIS option. A legacy row carries no id, so it matches by label only.
  let run = 0;
  for (const h of history) {
    const isSame = h.dropdownId !== null ? h.dropdownId === option.id : h.dropdownValue === option.label;
    if (!isSame) break;
    run += 1;
  }
  if (run < threshold) return false;

  // The adit Raj guard. Someone can be permanently unreachable by phone and still walk into class —
  // it happened on 2026-08-01, where a person whose recorded response was a flat refusal had
  // physically attended that same day. Turning up is a stronger signal than any phone call, so if
  // they have attended at any point since this run began, the run means nothing.
  const runStart = history[run - 1]?.createdAt;
  if (runStart) {
    try {
      const since = runStart.toISOString().slice(0, 10);
      const attended = attendeaseRepo.allAttendance({ mobile, since });
      if (attended.length > 0) return false;
    } catch {
      // AttendEase unreachable. Refuse to remove rather than remove blind — a missing attendance
      // database must never be the reason somebody is dropped from the programme.
      return false;
    }
  }

  const registration = await rawPrisma.registration.findUnique({
    where: { id: registrationId },
    select: { id: true, category: true, currentStage: true, normalizedMobile: true },
  });
  if (!registration) return false;
  if (registration.currentStage === RegistrationStage.archived) return false;

  const reason = unreachableReason(option.label, run);

  // Strip the batch membership FIRST — being archived while still tagged onto a batch is exactly
  // the state that left 24 refusers sitting in a live roster for a week. Soft-remove + audit, so
  // it is reversible and attributable, never a DELETE.
  let normalized: string;
  try {
    normalized = normalizeMobile(mobile);
  } catch {
    normalized = registration.normalizedMobile;
  }
  const memberships = await rawPrisma.batchMember.findMany({
    where: { centreId, mobile: { in: [mobile, normalized] }, removedAt: null },
    select: { id: true, batchId: true },
  });
  for (const m of memberships) {
    await rawPrisma.batchMember.update({
      where: { id: m.id },
      data: { removedAt: now, changedBy: "system:unreachable_threshold", changedAt: now },
    });
    await rawPrisma.batchMemberAudit.create({
      data: {
        batchMemberId: m.id,
        action: "remove",
        byUser: "system:unreachable_threshold",
        atTime: now,
        reason: "unreachable_threshold",
        prevState: JSON.stringify({ batchId: m.batchId, active: true }),
        newState: JSON.stringify({ batchId: m.batchId, active: false, registrationId, run }),
      },
    });
  }

  await rawPrisma.removedRecord.upsert({
    where: { registrationId },
    update: { reason, removedAt: now },
    create: { registrationId, reason, removedAt: now },
  });
  await rawPrisma.registration.update({
    where: { id: registrationId },
    data: { currentStage: RegistrationStage.archived, activeQueue: ActiveQueue.archived },
  });

  try {
    await recomputeCentreCategory(rawPrisma, centreId, registration.category);
  } catch {
    /* counter refresh is non-critical */
  }
  return true;
};
