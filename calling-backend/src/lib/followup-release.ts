import { ActiveQueue, Category, FollowupStatus, RegistrationStage } from "@prisma/client";
import { rawPrisma } from "../config/prisma.js";

/**
 * Hand people back to the general pool once their follow-up has been closed.
 *
 * Closing the `followups` row is only half of leaving follow-up: the registration's own
 * currentStage/activeQueue must move too, or the person stays parked in `followup_active/followup`
 * with nothing behind it. getNewRegistrationsPool only returns `registered/none`, so a parked person
 * is invisible to every future session on the Pool page, and nothing ever un-parks them — the
 * expiry worker only looks at ACTIVE follow-up rows.
 *
 * That is exactly what markSessionAttendance and the attendee paths of completeSessionCampaign did:
 * they closed the follow-up of someone who CAME to class and left the stage behind. On 2026-09-24,
 * 25 registrations were stuck like this (8 of the 13 present at "2026-09-12 CTRA BG"), so the Pool
 * page's session filter showed 5 of 13.
 *
 * Same guard as the expiry worker: release only someone STILL parked in follow-up, and only when
 * their follow-up row is no longer active. A later state (archived, removed, batch calling, a fresh
 * follow-up from a newer session) is the truth and is never overwritten.
 *
 * Returns the categories touched, so the caller can refresh the read-model counters.
 */
export const releaseFromClosedFollowups = async (registrationIds: number[]): Promise<Set<Category>> => {
  const touched = new Set<Category>();
  if (registrationIds.length === 0) return touched;

  const parked = await rawPrisma.registration.findMany({
    where: {
      id: { in: registrationIds },
      currentStage: RegistrationStage.followup_active,
      activeQueue: ActiveQueue.followup,
      NOT: { followup: { is: { status: FollowupStatus.active } } },
    },
    select: { id: true, category: true },
  });
  if (parked.length === 0) return touched;

  await rawPrisma.registration.updateMany({
    where: { id: { in: parked.map((r) => r.id) } },
    data: { currentStage: RegistrationStage.registered, activeQueue: ActiveQueue.none },
  });
  for (const r of parked) touched.add(r.category);
  return touched;
};
