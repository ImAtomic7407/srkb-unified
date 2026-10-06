// Follow-up expiry worker.
//
// A follow-up means "ring them again BEFORE this class to confirm they're still coming". That is a
// promise with a deadline, but nothing used to enforce it: follow-ups were only ever closed when an
// admin manually COMPLETED the campaign. Closing ("retrieving") a session merely locks them, which
// blocks markFollowupDone — so a session that was closed and never completed left its follow-ups
// active AND uneditable, permanently.
//
// The damage was not just clutter. An active follow-up holds the registration in
// followup_active/followup, and getNewRegistrationsPool only returns registered/none — so a stale
// follow-up silently removed the person from the assignable pool for every FUTURE session. The
// people it hid were the keenest ones: they had said "I'll attend", then not come. Exactly who you
// most want to ring for the next class.
//
// Expiry is computed from ABSOLUTE dates and is idempotent, so downtime costs nothing: whenever the
// process next runs, everything whose class has passed is swept in one go. Nothing is scheduled per
// follow-up, so there is nothing to miss or replay.

import { prisma } from "../../config/prisma.js";
import { bypassCentreScope } from "../../lib/prisma-extension.js";
import { recomputeCentreCategory } from "../../lib/projections.js";
import { ActiveQueue, FollowupStatus, RegistrationStage, SessionCampaignStatus } from "@prisma/client";

// How long after the class starts a confirmation call still makes sense. It stays visible through
// the class itself (someone may ring during it), and dies the same night.
export const FOLLOWUP_GRACE_HOURS = 12;

// Follow-ups created before the class link existed carry no campaign, so they expire on age alone.
// A confirmation call is a days-long errand, never a week-long one.
export const FOLLOWUP_LEGACY_MAX_AGE_DAYS = 7;

const log = (msg: string) => console.log(`[followup-expiry] ${msg}`);

/**
 * Close every active follow-up whose class has already happened, and hand the person back to the
 * general pool so they can be called for the next one.
 *
 * Returns the number closed. Safe to call repeatedly.
 */
export const expireStaleFollowups = async (now: Date = new Date()): Promise<number> => {
  const graceMs = FOLLOWUP_GRACE_HOURS * 60 * 60 * 1000;
  const legacyCutoff = new Date(now.getTime() - FOLLOWUP_LEGACY_MAX_AGE_DAYS * 24 * 60 * 60 * 1000);

  const active = await bypassCentreScope(() =>
    prisma.followup.findMany({
      where: { status: FollowupStatus.active },
      select: {
        id: true,
        registrationId: true,
        centreId: true,
        createdAt: true,
        campaignId: true,
        campaign: { select: { scheduledAt: true, status: true } },
      },
    }),
  );

  const stale = active.filter((f) => {
    if (f.campaign) {
      // A follow-up attached to a STILL-ACTIVE session is that caller's live work list. Never
      // sweep it, whatever the clock says.
      //
      // Expiring on scheduledAt alone was destroying work as it was created. Sessions get
      // timestamped when the admin sets them up, not when the calling actually happens: session
      // "01-08-26" carried a scheduledAt of 31 Jul 17:45, so its 12h grace ran out at 05:45 on the
      // 1st — before anyone started ringing. Observed live on 2026-08-01: all 78 follow-ups for
      // that session closed, 29 of them created after their own deadline had already passed, one
      // of them seven minutes old. Callers logged "will attend" and watched the person disappear
      // from Follow-ups before they could act.
      //
      // Age is NOT a usable guard here: `followups` holds one reusable row per registration, so a
      // row re-linked to today's session can carry a created_at from weeks ago. Session lifecycle
      // is the only honest signal — once the admin closes or completes the session, the class
      // really is over and the grace window then applies as intended.
      if (f.campaign.status === SessionCampaignStatus.active) return false;
      return f.campaign.scheduledAt.getTime() + graceMs < now.getTime();
    }
    // No class link (pre-migration row, or its session was deleted): fall back to age.
    return f.createdAt < legacyCutoff;
  });

  if (stale.length === 0) return 0;

  const affected = new Map<number, Set<string>>(); // centreId -> categories touched

  for (const f of stale) {
    await bypassCentreScope(() =>
      prisma.followup.update({
        where: { id: f.id },
        data: { status: FollowupStatus.closed, isLocked: false, lockedReason: null },
      }),
    );

    // Release the person ONLY if they are still parked in follow-up. If they have since moved on —
    // attended, archived, removed, pulled into batch calling — that later state is the truth and
    // must not be overwritten by a sweep cleaning up an old class.
    const registration = await bypassCentreScope(() =>
      prisma.registration.findUnique({
        where: { id: f.registrationId },
        select: { id: true, category: true, currentStage: true, activeQueue: true },
      }),
    );

    if (
      registration &&
      registration.currentStage === RegistrationStage.followup_active &&
      registration.activeQueue === ActiveQueue.followup
    ) {
      await bypassCentreScope(() =>
        prisma.registration.update({
          where: { id: registration.id },
          data: { currentStage: RegistrationStage.registered, activeQueue: ActiveQueue.none },
        }),
      );
      const set = affected.get(f.centreId) ?? new Set<string>();
      set.add(registration.category);
      affected.set(f.centreId, set);
    }
  }

  // Keep the dashboard counters honest about what just moved.
  for (const [centreId, categories] of affected) {
    for (const category of categories) {
      try {
        await recomputeCentreCategory(prisma, centreId, category as never);
      } catch {
        /* counter refresh is non-critical */
      }
    }
  }

  log(`closed ${stale.length} follow-up(s) whose class has passed`);
  return stale.length;
};

export const startFollowupExpiryScheduler = () => {
  const INTERVAL_MS = 60 * 60 * 1000; // hourly — a class ending should free someone up promptly
  setTimeout(() => {
    void expireStaleFollowups();
    setInterval(() => void expireStaleFollowups(), INTERVAL_MS);
  }, 45_000); // after reconcile (0s) and archival (30s)
  log("scheduler registered (first run in 45s)");
};
