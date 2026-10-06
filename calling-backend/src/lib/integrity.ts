import {
  ActiveQueue,
  AssignmentStatus,
  BatchCallingAssignmentStatus,
  FollowupStatus,
  RegistrationStage,
  SessionCallStatus,
  SessionCampaignStatus,
} from "@prisma/client";
import { rawPrisma } from "../config/prisma.js";
import { recomputeCentreCategory } from "./projections.js";
import { releaseFromClosedFollowups } from "./followup-release.js";
import { normalizeMobile } from "../utils/mobile.js";

/**
 * Stuck-data checker: does every person's state agree with the records that are supposed to hold
 * them there, and is anything holding people that should have let go?
 *
 * Why this exists. On 2026-09-24 the Pool page showed 5 of 13 attendees. 25 people had sat in
 * followup_active for weeks with their follow-up closed, and on top of that two sessions nobody had
 * closed (13 Sep SUNDAY4, 30 Aug CTRA BG) were still holding 21 follow-ups and 20 pending calls.
 * Both scanners that existed missed all of it: the reconcile compares AttendEase with the mirror
 * tables and recounts the dashboard FROM currentStage, and the Flask audit compares Excel with its
 * DB. Neither ever asked "is this stage backed by anything?" — and what they did find only went to
 * pm2 logs and the journal, which nobody reads. So this is run live by GET /integrity and shown to
 * the admin on the Pool and Planned Sessions screens, and also by the reconcile and hourly.
 *
 * Only the one fix whose answer is certain is ever applied (`heal`): a person parked in follow-up
 * with no active follow-up is released, exactly as the expiry worker would. Everything else is
 * REPORTED with what to do, because the right fix is a person's call (close vs complete a session
 * decides absence strikes) or the cause is not yet known.
 */
export type IntegrityIssue = {
  code: string;
  severity: "error" | "warning";
  count: number;
  title: string;
  action: string;
  examples: string[];
};
export type IntegrityReport = { checkedAt: string; healed: number; issues: IntegrityIssue[] };

// A session is expected to be closed or completed within this many days of its class.
export const OPEN_SESSION_GRACE_DAYS = 2;
const EXAMPLES = 5;

const tryNormalize = (m: string): string | null => {
  try {
    return normalizeMobile(m);
  } catch {
    return null;
  }
};

export const checkIntegrity = async (
  opts: { centreId?: number; heal?: boolean; now?: Date } = {},
): Promise<IntegrityReport> => {
  const now = opts.now ?? new Date();
  const centre = opts.centreId ? { centreId: opts.centreId } : {};
  const issues: IntegrityIssue[] = [];
  const add = (i: IntegrityIssue) => {
    if (i.count > 0) issues.push(i);
  };
  const who = (r: { name: string; mobile: string }) => `${r.name || "?"} (${r.mobile})`;

  // 1. Parked in follow-up with no follow-up behind it — invisible to every Pool page.
  const parked = await rawPrisma.registration.findMany({
    where: {
      ...centre,
      currentStage: RegistrationStage.followup_active,
      activeQueue: ActiveQueue.followup,
      NOT: { followup: { is: { status: FollowupStatus.active } } },
    },
    select: { id: true, centreId: true, name: true, mobile: true },
  });
  let healed = 0;
  if (opts.heal && parked.length) {
    const touched = await releaseFromClosedFollowups(parked.map((r) => r.id));
    for (const c of new Set(parked.map((r) => r.centreId))) {
      for (const cat of touched) {
        try { await recomputeCentreCategory(rawPrisma, c, cat); } catch { /* counters are non-critical */ }
      }
    }
    healed = parked.length;
  } else {
    add({
      code: "stuck_in_followup",
      severity: "error",
      count: parked.length,
      title: "Stuck in follow-up with no follow-up open — hidden from the Pool page",
      action: "Released automatically within the hour.",
      examples: parked.slice(0, EXAMPLES).map(who),
    });
  }

  // 2. In calling with no calling assignment — also hidden from the pool, and nobody is ringing them.
  const callingOrphans = await rawPrisma.registration.findMany({
    where: { ...centre, currentStage: RegistrationStage.calling_active, assignments: { none: { status: AssignmentStatus.active } } },
    select: { name: true, mobile: true },
  });
  add({
    code: "calling_without_assignment",
    severity: "error",
    count: callingOrphans.length,
    title: "Marked 'in calling' but no caller has them",
    action: "Report to the developer — no known path causes this.",
    examples: callingOrphans.slice(0, EXAMPLES).map(who),
  });

  // 3. An open follow-up the callers cannot see (their list filters on the stage).
  const hidden = await rawPrisma.followup.findMany({
    where: { ...centre, status: FollowupStatus.active, registration: { NOT: { currentStage: RegistrationStage.followup_active } } },
    select: { registration: { select: { name: true, mobile: true } } },
  });
  add({
    code: "followup_hidden",
    severity: "error",
    count: hidden.length,
    title: "Open follow-up that no caller can see",
    action: "Report to the developer — no known path causes this.",
    examples: hidden.slice(0, EXAMPLES).map((f) => who(f.registration)),
  });

  // 4. Sessions left open after their class while still holding people. Open sessions are what
  //    keep people "busy elsewhere" — the expiry worker never touches an active session's work.
  const cutoff = new Date(now.getTime() - OPEN_SESSION_GRACE_DAYS * 24 * 60 * 60 * 1000);
  const stale = await rawPrisma.sessionCampaign.findMany({
    where: { ...centre, status: SessionCampaignStatus.active, scheduledAt: { lt: cutoff } },
    select: {
      id: true,
      aeSessionId: true,
      scheduledAt: true,
      _count: {
        select: {
          assignments: { where: { removedAt: null, callStatus: SessionCallStatus.pending } },
          followups: { where: { status: FollowupStatus.active } },
        },
      },
    },
    orderBy: { scheduledAt: "asc" },
  });
  const holding = stale.filter((s) => s._count.assignments + s._count.followups > 0);
  if (holding.length) {
    const names = new Map(
      (await rawPrisma.session.findMany({
        where: { sessionId: { in: holding.map((s) => s.aeSessionId) } },
        select: { sessionId: true, displayName: true },
      })).map((s) => [s.sessionId, s.displayName]),
    );
    add({
      code: "stale_open_session",
      severity: "warning",
      count: holding.length,
      title: `Session${holding.length > 1 ? "s" : ""} still open ${OPEN_SESSION_GRACE_DAYS}+ days after class, holding people`,
      action: "Close or complete them in Planned Sessions — until then these people can't be assigned anywhere else.",
      examples: holding.slice(0, EXAMPLES).map(
        (s) => `${names.get(s.aeSessionId) || s.aeSessionId}: ${s._count.followups} in follow-up, ${s._count.assignments} waiting for a call`,
      ),
    });
  }

  // 5. One number, one place. The assign guard should make this impossible; if it ever shows up,
  //    some path got around the guard. A follow-up and a call row of the SAME session are one place.
  const places = new Map<string, Set<string>>(); // centre:mobile -> places
  const hold = (centreId: number, mobile: string | null, place: string) => {
    if (!mobile) return;
    const k = `${centreId}:${mobile}`;
    const s = places.get(k) ?? new Set<string>();
    s.add(place);
    places.set(k, s);
  };
  for (const f of await rawPrisma.followup.findMany({
    where: { ...centre, status: FollowupStatus.active },
    select: { centreId: true, campaignId: true, registration: { select: { normalizedMobile: true } } },
  })) hold(f.centreId, f.registration.normalizedMobile, f.campaignId ? `session#${f.campaignId}` : "followup");
  for (const a of await rawPrisma.sessionCallAssignment.findMany({
    where: { removedAt: null, callStatus: SessionCallStatus.pending, campaign: { ...centre, status: SessionCampaignStatus.active } },
    select: { campaignId: true, campaign: { select: { centreId: true } }, batchMember: { select: { mobile: true } } },
  })) hold(a.campaign.centreId, tryNormalize(a.batchMember.mobile), `session#${a.campaignId}`);
  for (const a of await rawPrisma.callingAssignment.findMany({
    where: { ...centre, status: AssignmentStatus.active },
    select: { centreId: true, registration: { select: { normalizedMobile: true } } },
  })) hold(a.centreId, a.registration.normalizedMobile, "pool_calling");
  for (const a of await rawPrisma.batchCallingAssignment.findMany({
    where: { ...centre, status: BatchCallingAssignmentStatus.active },
    select: { centreId: true, batchMember: { select: { mobile: true } } },
  })) hold(a.centreId, tryNormalize(a.batchMember.mobile), "batch_calling");
  const doubled = [...places.entries()].filter(([, s]) => s.size > 1);
  add({
    code: "two_places",
    severity: "error",
    count: doubled.length,
    title: "Same number held in two places at once",
    action: "Report to the developer — the one-number-one-place guard was bypassed.",
    examples: doubled.slice(0, EXAMPLES).map(([k, s]) => `${k.split(":")[1]}: ${[...s].join(" + ")}`),
  });

  return { checkedAt: now.toISOString(), healed, issues };
};
