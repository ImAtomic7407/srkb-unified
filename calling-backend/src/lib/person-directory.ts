// Display-name resolution by mobile.
//
// This system has NO separate "formal registration" gate — a person's FIRST attendance IS their
// registration. So a display name may live in a CB `registrations` row OR, for anyone whose record
// originates purely from attending, in AttendEase's person directory (the `users` table, keyed by
// mobile, written on first attendance). Resolving names from CB registrations alone is why
// attendance-only people rendered as a bare mobile number ("Unknown").

import { attendeaseRepo } from "./attendease-db.js";
import { env } from "../config/env.js";

// Last-10-digits key so "+91 98…", "098…", and "98…" all collide to the same person.
export const mobileKey = (mobile: string): string => mobile.replace(/\D/g, "").slice(-10);

let cache: { at: number; byMobile: Map<string, string> } | null = null;

// AttendEase person directory (users table) → { mobileKey → name }. Whole-table read, so cached
// with a short TTL since it's reused across many per-request roster/queue lookups.
export const attendeasePersonNames = (): Map<string, string> => {
  const nowMs = Date.now();
  if (cache && nowMs - cache.at < 60_000) return cache.byMobile;
  const map = new Map<string, string>();
  if (env.attendeaseDbPath) {
    for (const p of attendeaseRepo.allPersons()) {
      const name = p.name?.trim();
      if (name) map.set(mobileKey(p.mobile), name);
    }
  }
  cache = { at: nowMs, byMobile: map };
  return map;
};

/**
 * Uncached, single-row name lookup — for the one case the cache above must never serve:
 * deciding the name that gets FROZEN into a permanent record.
 *
 * `attendeasePersonNames()` is a 60-second whole-table cache. That is correct for rendering a
 * queue, where a slightly stale name self-corrects on the next read. It is WRONG for a write:
 * a person created in AttendEase seconds ago is absent from a cache built before they existed,
 * so the write stored the bare phone number as their name — permanently, because the creating
 * code path only runs once per person. Observed live: an import running within 60s of a previous
 * one put every person in the second file into callers' queues as a number.
 *
 * One indexed lookup on an already-open read-only handle, and only on the create path, so the
 * cost is negligible. Falls back to the cached map for the handful of rows whose stored mobile
 * is not the plain 10-digit form (AE holds ~90 such rows out of ~6.5k).
 */
export const freshPersonName = (mobile: string): string | undefined => {
  if (!env.attendeaseDbPath) return undefined;
  const key = mobileKey(mobile);
  try {
    const direct = attendeaseRepo.personByMobile(key)?.name?.trim();
    if (direct) return direct;
  } catch {
    // fall through to the cached map rather than failing the write
  }
  return attendeasePersonNames().get(key)?.trim() || undefined;
};

/**
 * Occupation/address for a person, straight from AttendEase. Uncached for the same reason as
 * freshPersonName, and used for the same job: these two fields are shown on the caller's context
 * card, are only ever set when a registration is created, and were never populated from
 * AttendEase at all — so an imported person reached the caller with a blank card even though
 * AttendEase held their occupation. There is no cache to fall back on for these, so a person
 * whose stored mobile is not the plain 10-digit form simply returns undefined.
 */
export const freshPersonDetails = (
  mobile: string,
): { occupation?: string; address?: string } | undefined => {
  if (!env.attendeaseDbPath) return undefined;
  try {
    const p = attendeaseRepo.personByMobile(mobileKey(mobile));
    if (!p) return undefined;
    return { occupation: p.occupation?.trim() || undefined, address: p.address?.trim() || undefined };
  } catch {
    return undefined;
  }
};
