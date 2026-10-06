// Batch membership rule engine — Phase 5.
// Rules R1–R4 as specified in MULTI_CENTRE_DESIGN.md (BM3/BM4).
// All writes use rawPrisma so they are never accidentally centre-scoped.

import {
  BatchCallingAssignmentStatus,
  BatchCallingAssignmentMode,
  RegistrationStage,
  ActiveQueue,
} from "@prisma/client";
import { Category } from "@prisma/client";
import { rawPrisma } from "../../config/prisma.js";
import { ApiError } from "../../lib/api-error.js";
import { enqueueOutbox } from "../../lib/outbox.js";
import { normalizeMobile } from "../../utils/mobile.js";
import { recomputeCentreCategory } from "../../lib/projections.js";
import {
  attendeasePersonNames, freshPersonName, freshPersonDetails, mobileKey as digits,
} from "../../lib/person-directory.js";

// Refresh the live batch_calling counter for a centre+category after a membership change.
// Runs on rawPrisma post-write (memberships are written outside a domain transaction).
// Non-fatal: a stale counter self-heals on the next reconcile.
const refreshBatchCalling = (centreId: number, category: string) =>
  recomputeCentreCategory(rawPrisma, centreId, category as Category, ["batch_calling"] as const).catch(
    () => undefined,
  );

// ── Types ─────────────────────────────────────────────────────────────────

export type MembershipSource =
  | "registration_auto"   // R1 — default batch on registration
  | "registration_manual" // R2 — explicit batch override at registration
  | "attendance_evict"    // R3 — evicted by attendance in non-default batch
  | "manual_add"          // explicit host/caller add
  | "manual_remove"       // explicit host/caller remove
  | "backfill";           // bulk historical import

type ActiveMembership = {
  id: number;
  batchId: number;
  mobile: string;
  isDefault: boolean;
  source: string;
};

// ── Helpers ───────────────────────────────────────────────────────────────

const now = () => new Date();

const log = (msg: string) => console.log(`[batch_member] ${msg}`);

// How many times a batch member may be absent from that batch's calling sessions before they are
// removed from the batch ("kicked out"). Single named constant so the threshold is trivial to
// change. Distinct from the no-answer 3-strike registration removal — this is CLASS absence →
// BATCH membership removal, a soft-remove (recoverable), not a registration deletion.
export const BATCH_ABSENCE_EVICTION_THRESHOLD = 3;

// Soft-remove a person's active membership in a specific batch because they hit the absence
// threshold. Idempotent: no-op if they have no active membership in that batch. Returns whether a
// removal happened. Reuses the same audited soft-remove path every other membership change uses.
export const evictFromBatchForAbsence = async (
  centreId: number,
  category: string,
  mobile: string,
  batchId: number,
  changedBy = "system:absence",
): Promise<boolean> => {
  const membership = await rawPrisma.batchMember.findFirst({
    where: { centreId, category, mobile, batchId, removedAt: null },
  });
  if (!membership) return false;
  await removeMembership(membership.id, changedBy, "absent_threshold", "attendance_evict");
  return true;
};

// Fetch all active (removedAt IS NULL) memberships for a mobile in a centre+category.
const getActiveMemberships = async (
  centreId: number,
  category: string,
  mobile: string,
): Promise<ActiveMembership[]> => {
  const rows = await rawPrisma.batchMember.findMany({
    where: { centreId, category, mobile, removedAt: null },
    include: { batch: { select: { isDefault: true } } },
  });
  return rows.map((r) => ({
    id: r.id,
    batchId: r.batchId,
    mobile: r.mobile,
    isDefault: r.batch.isDefault,
    source: r.source,
  }));
};

// Get default batch for a centre+category. Returns null if none configured.
const getDefaultBatch = async (centreId: number, category: string) => {
  return rawPrisma.batch.findFirst({
    where: { centreId, category, isDefault: true, status: "active" },
  });
};

// Soft-remove a membership row and write an audit entry.
const removeMembership = async (
  memberId: number,
  changedBy: string,
  reason: string,
  source: MembershipSource,
): Promise<void> => {
  const t = now();
  const prev = await rawPrisma.batchMember.findUnique({ where: { id: memberId } });
  await rawPrisma.batchMember.update({
    where: { id: memberId },
    data: { removedAt: t, changedBy, changedAt: t },
  });
  await rawPrisma.batchMemberAudit.create({
    data: {
      batchMemberId: memberId,
      action: source === "manual_remove" ? "remove" : "remove",
      byUser: changedBy,
      atTime: t,
      reason,
      prevState: prev ? JSON.stringify({ batchId: prev.batchId, removedAt: prev.removedAt }) : null,
      newState: JSON.stringify({ removedAt: t }),
    },
  });
  log(`remove mobile=${prev?.mobile} batchId=${prev?.batchId} by=${changedBy} reason=${reason}`);
  if (prev) {
    await refreshBatchCalling(prev.centreId, prev.category);
    enqueueOutbox("sync_membership_to_attendease", {
      action: "remove", mobile: prev.mobile, batchId: prev.batchId, centreId: prev.centreId, category: prev.category,
    }).catch(() => undefined);
  }
};

// Add a new membership row and write an audit entry. Idempotent — if an
// active membership for the same (centreId, category, mobile, batchId) already
// exists, returns that row without creating a duplicate.
const addMembership = async (
  centreId: number,
  category: string,
  mobile: string,
  batchId: number,
  changedBy: string,
  source: MembershipSource,
): Promise<void> => {
  const existing = await rawPrisma.batchMember.findFirst({
    where: { centreId, category, mobile, batchId, removedAt: null },
  });
  if (existing) return;

  const t = now();
  const row = await rawPrisma.batchMember.create({
    data: { centreId, category, mobile, batchId, joinedAt: t, changedBy, changedAt: t, source },
  });
  await rawPrisma.batchMemberAudit.create({
    data: {
      batchMemberId: row.id,
      action: source === "backfill" ? "backfill" : "create",
      byUser: changedBy,
      atTime: t,
      reason: source,
      newState: JSON.stringify({ batchId, joinedAt: t }),
    },
  });
  log(`add mobile=${mobile} batchId=${batchId} by=${changedBy} source=${source}`);
  await refreshBatchCalling(centreId, category);
  enqueueOutbox("sync_membership_to_attendease", {
    action: "add", mobile, batchId, centreId, category,
  }).catch(() => undefined);
};

// ── Rule engine entry points ──────────────────────────────────────────────

/**
 * R1 / R2: Called when a student is registered.
 * - If explicitBatchId provided → R2: add to that batch only (never default).
 * - If no explicitBatchId and prior attendance → throw ExplicitBatchRequiredError (Q4).
 * - Otherwise → R1: add to default batch.
 */
export const onRegistration = async (
  centreId: number,
  category: string,
  mobile: string,
  changedBy: string,
  explicitBatchId?: number,
): Promise<void> => {
  if (explicitBatchId !== undefined) {
    // R2 — explicit override
    const batch = await rawPrisma.batch.findUnique({ where: { id: explicitBatchId } });
    if (!batch || batch.status === "archived") {
      throw new ApiError(422, "Target batch not found or archived");
    }
    if (batch.isDefault) {
      throw new ApiError(422, "Use the automatic default-batch flow — do not pass the default batch as an explicit override");
    }
    await addMembership(centreId, category, mobile, explicitBatchId, changedBy, "registration_manual");
    return;
  }

  // Q4: if the mobile already has non-default batch memberships (from prior R3/R2 flows or
  // manual adds), we don't know which batch to default to — caller must specify explicitly.
  const existingNonDefault = await rawPrisma.batchMember.findFirst({
    where: {
      centreId,
      category,
      mobile,
      removedAt: null,
      batch: { isDefault: false },
    },
  });
  if (existingNonDefault) {
    throw new ApiError(
      409,
      "Student already has a non-default batch placement — explicit_batch_id required",
      "ExplicitBatchRequiredError",
    );
  }

  // R1 — no prior attendance, add to default batch
  const defaultBatch = await getDefaultBatch(centreId, category);
  if (!defaultBatch) {
    log(`R1: no default batch found for centre=${centreId} category=${category} — skipping`);
    return;
  }
  await addMembership(centreId, category, mobile, defaultBatch.id, changedBy, "registration_auto");
};

// ── Calling assignment helper ────────────────────────────────────────────────

// Create or re-activate a BatchCallingAssignment for a batch member.
// callerId must be provided explicitly (no auto-picking — admin assigns via the pool).
export const ensureBatchCallingAssignment = async (
  batchMemberId: number,
  centreId: number,
  callerId: number,
  activeDays?: number,
): Promise<void> => {
  const existing = await rawPrisma.batchCallingAssignment.findUnique({ where: { batchMemberId } });
  if (existing && existing.status === BatchCallingAssignmentStatus.active) return;

  if (activeDays === undefined) {
    const config = await rawPrisma.centreConfig.findUnique({ where: { centreId } });
    activeDays = config?.callingActiveDays ?? 7;
  }
  const startDate = new Date();
  const endDate = new Date(startDate.getTime() + activeDays * 24 * 60 * 60 * 1000);

  await rawPrisma.batchCallingAssignment.upsert({
    where: { batchMemberId },
    create: {
      batchMemberId,
      assignedToUserId: callerId,
      assignedByUserId: callerId,
      centreId,
      assignmentMode: BatchCallingAssignmentMode.manual,
      status: BatchCallingAssignmentStatus.active,
      startDate,
      endDate,
    },
    update: {
      assignedToUserId: callerId,
      assignedByUserId: callerId,
      status: BatchCallingAssignmentStatus.active,
      startDate,
      endDate,
    },
  });
};


/**
 * R3: Called when an attendance row is persisted for a non-default batch.
 * If the student is currently ONLY in the default batch -> evict default, add non-default.
 * If the student is already in the target non-default batch -> second+ attendance:
 *   check if they should graduate to personal My Attendance.
 * If attendance is for a default batch -> no-op (R3 only fires for non-default).
 * Student in non-default A, attendance for non-default B -> additive (add B, keep A).
 */
/**
 * Attendance IS registration. Per system design (see person-directory.ts: "a person's FIRST
 * attendance IS their registration") there is no separate registration gate. This materializes the
 * CB `registrations` row when someone attends so attendance-only people appear in the pool and the
 * lower session/batch filter — which read the `registrations` table — not just in the batch roster.
 * Idempotent: no-op if a registration for (mobile, centre) already exists, so it NEVER clobbers an
 * existing calling stage/queue. Returns true when a new registration was created.
 */
export const ensureRegistrationForAttendance = async (
  centreId: number,
  category: string,
  mobile: string,
  // Name supplied by the caller that already knows it (a roster inject carries the name in its
  // event payload; the reconcile pass has it on the roster row). Preferred over the directory
  // lookup below because that directory is a 60s-TTL cache: a person written to AttendEase
  // seconds ago is NOT in a cache built before they existed, so a second import inside that
  // window used to store the bare phone number as their permanent display name — callers saw a
  // number instead of a person, and it was never corrected because this function early-returns
  // once a registration exists. Optional, so every existing caller keeps its old behaviour.
  preferredName?: string | null,
): Promise<boolean> => {
  let normalized: string;
  try {
    normalized = normalizeMobile(mobile);
  } catch {
    return false; // malformed mobile — nothing to register
  }

  const existing = await rawPrisma.registration.findUnique({
    where: { normalizedMobile_centreId: { normalizedMobile: normalized, centreId } },
    select: { id: true },
  });
  if (existing) return false;

  // Name comes from the caller if it has one, else a FRESH (uncached) directory lookup; fall back
  // to the bare number so the row is valid even before a name is known. freshPersonName is used
  // rather than the 60s cache because whatever is chosen here is permanent for this person — this
  // branch only runs once, when the registration is created. See freshPersonName's comment.
  // A name that is still unresolved at this point is repaired later by reconcileRegistrationNames.
  const name = preferredName?.trim() || freshPersonName(mobile) || normalized;
  // Occupation/address are shown on the caller's context card. They were never carried over from
  // AttendEase, so imported people reached callers with a blank card. Same upgrade-only rule as
  // the name: take what AttendEase has, leave null when it has nothing.
  const details = freshPersonDetails(mobile);

  try {
    await rawPrisma.registration.create({
      data: {
        name,
        mobile: normalized,
        normalizedMobile: normalized,
        centreId,
        category: category as Category,
        currentStage: RegistrationStage.registered,
        activeQueue: ActiveQueue.none,
        occupation: details?.occupation ?? null,
        address: details?.address ?? null,
      },
    });
  } catch (err) {
    // Unique-race (a concurrent attendance created it first) or FK issue — non-fatal.
    log(`ensureRegistrationForAttendance: skipped ${normalized} @${centreId}: ${(err as Error).message}`);
    return false;
  }

  // Keep the dashboard counters exact (registrations_total / new_registrations rise). Best-effort.
  await recomputeCentreCategory(rawPrisma, centreId, category as Category).catch(() => undefined);
  log(`ensureRegistrationForAttendance: created registration for ${normalized} @centre ${centreId} (${name})`);
  return true;
};

/** A display name that isn't really a name: never set, the "Unknown" sentinel, or the bare number. */
const isPlaceholderName = (name: string | null | undefined, normalizedMobile: string): boolean => {
  const n = (name ?? "").trim().toLowerCase();
  return n === "" || n === "unknown" || n === normalizedMobile.trim().toLowerCase();
};

/**
 * Upgrade one registration's placeholder name from AttendEase, if a real one now exists.
 *
 * Called on attendance because that is the moment AttendEase actually learns a name: someone can
 * legitimately enter this system as a number alone (bulk import), and the operator types their
 * name when marking them present. Without this the registration keeps the placeholder forever —
 * the create path that chose it only ever runs once.
 *
 * Strictly an upgrade, so it is safe to call on every attendance event: it does nothing unless
 * the stored name is a placeholder AND AttendEase offers something better. Never overwrites a
 * name a human set. The daily reconcile runs the same repair in bulk as a backstop.
 */
export const repairRegistrationName = async (centreId: number, mobile: string): Promise<boolean> => {
  let normalized: string;
  try {
    normalized = normalizeMobile(mobile);
  } catch {
    return false;
  }
  const reg = await rawPrisma.registration.findUnique({
    where: { normalizedMobile_centreId: { normalizedMobile: normalized, centreId } },
    select: { id: true, name: true, occupation: true, address: true },
  });
  if (!reg) return false;

  const data: { name?: string; occupation?: string; address?: string } = {};

  if (isPlaceholderName(reg.name, normalized)) {
    const better = freshPersonName(mobile);
    if (better && !isPlaceholderName(better, normalized)) data.name = better;
  }

  // Occupation/address are filled only when currently blank — never overwrite something a
  // caller or admin has already put there.
  const details = freshPersonDetails(mobile);
  if (!(reg.occupation ?? "").trim() && details?.occupation) data.occupation = details.occupation;
  if (!(reg.address ?? "").trim() && details?.address) data.address = details.address;

  if (!Object.keys(data).length) return false;

  await rawPrisma.registration.update({ where: { id: reg.id }, data });
  log(`repairRegistrationName: ${normalized} @centre ${centreId} <- ${JSON.stringify(data)}`);
  return true;
};

export const onAttendancePersisted = async (
  centreId: number,
  category: string,
  mobile: string,
  batchId: number,
  changedBy = "system:r3",
): Promise<void> => {
  // Attendance IS registration: ensure the CB registration row exists first, for EVERY attendance
  // (including default-batch), so attendees always surface in the registration-based pool/filter.
  await ensureRegistrationForAttendance(centreId, category, mobile);

  const batch = await rawPrisma.batch.findUnique({ where: { id: batchId } });
  if (!batch || batch.isDefault) return; // R3 only fires for non-default batches

  const active = await getActiveMemberships(centreId, category, mobile);

  // Already a member of the target batch: second+ attendance.
  // Graduation removed — students stay in the batch calling loop permanently so they
  // keep getting weekly reminder calls. "My Attendance" is now a read-only scoreboard,
  // not a stage, so there is nothing to move them into here.
  const existingMembership = active.find((m) => m.batchId === batchId);
  if (existingMembership) {
    return;
  }

  // First attendance for this batch: evict default, add non-default
  const defaultMemberships = active.filter((m) => m.isDefault);
  for (const dm of defaultMemberships) {
    await removeMembership(dm.id, changedBy, "r3_eviction", "attendance_evict");
  }
  await addMembership(centreId, category, mobile, batchId, changedBy, "attendance_evict");

};

/**
 * Roster-imported people. AttendEase's centre_roster is the list of who belongs to a batch RIGHT
 * NOW, including prospects imported from a sheet who have never attended anything. batch_members
 * was only ever materialised from attendance, so those people existed in AttendEase and nowhere
 * else — invisible to Pool Assignment's counts and impossible to assign to a caller, which is
 * exactly backwards: someone who has not come yet is precisely who needs ringing.
 *
 * Deliberately NOT a copy of onAttendancePersisted: no default-batch eviction. Eviction there
 * encodes "they turned up to a real class, so the placeholder batch is wrong". An import asserts
 * no such thing, and silently moving someone out of a batch they were attending would lose data.
 */
export const onRosterMember = async (
  centreId: number,
  category: string,
  mobile: string,
  batchId: number,
  changedBy = "system:roster",
  // Forwarded to ensureRegistrationForAttendance — see the note there on the 60s name cache.
  preferredName?: string | null,
): Promise<void> => {
  const batch = await rawPrisma.batch.findUnique({ where: { id: batchId } });
  if (!batch) return;

  // Never re-import someone the calling system has already FINISHED with. The roster is a standing
  // list and keeps naming people long after they were struck off, so an unguarded import silently
  // resurrected them as brand-new, never-called work.
  //
  // Observed live on 2026-08-01: a 158-person roster import put 49 people at stage `removed` — who
  // had already been rung and had said no — back into the batch as fresh assignments. Callers were
  // handed numbers they had personally closed weeks earlier, saw them listed as never called, and
  // reasonably reported their answers as lost. Nothing was lost; the import had simply un-done the
  // decision. Bringing a struck-off person back is a deliberate act, and there is already an
  // explicit Restore action for it — it must not happen as a side effect of a roster sync.
  //
  // This check runs BEFORE ensureRegistrationForAttendance. It used to run after, which meant that
  // for anyone whose registration was missing the guard inspected the row this very function had
  // just created — always stage `registered` — and waved them through. The row is the evidence;
  // creating it first destroys the thing being checked. (The 2026-09-08 incident needed the purge
  // in archival.ts to delete the row first, which no longer happens, but a guard that reads state
  // it just wrote is wrong regardless of who else is deleting rows.)
  const existing = await rawPrisma.registration.findFirst({
    where: { centreId, normalizedMobile: normalizeMobile(mobile) },
    select: { currentStage: true },
  });
  if (
    existing?.currentStage === RegistrationStage.removed ||
    existing?.currentStage === RegistrationStage.archived
  ) {
    return;
  }

  // Unlike the attendance rules, default batches are NOT skipped here. R3 skips them because a
  // default membership is implied by registration; but a centre's main batch is very often the
  // default one (Centre A's "BG" is), and that is precisely the batch imports target. Skipping
  // it meant an import of 165 people moved no counter at all.
  // The pool is registration-based, so a membership without a registration row still would not show.
  await ensureRegistrationForAttendance(centreId, category, mobile, preferredName);

  const active = await getActiveMemberships(centreId, category, mobile);
  if (active.some((m) => m.batchId === batchId)) return; // idempotent — safe to re-run

  // Mutual exclusivity (same invariant manualReplace enforces): a real non-default membership
  // outranks the default batch, so never demote someone who has one.
  if (batch.isDefault && active.some((m) => !m.isDefault)) return;

  // Purely additive: no eviction. An import is not evidence that an existing membership is wrong,
  // and silently removing someone from a batch they are being called for would lose real work.
  await addMembership(centreId, category, mobile, batchId, changedBy, "backfill");
};

/**
 * R4: Replace all batch memberships for a mobile (multi-select save from AttendEase UI).
 * Enforces mutual exclusivity: if newBatchIds includes a default batch, no non-default may be
 * present, and vice-versa. Adding default when any non-default exists → rejects.
 */
export const manualReplace = async (
  centreId: number,
  category: string,
  mobile: string,
  newBatchIds: number[],
  changedBy: string,
): Promise<void> => {
  if (newBatchIds.length === 0) {
    throw new ApiError(422, "newBatchIds must not be empty");
  }

  const batches = await rawPrisma.batch.findMany({
    where: { id: { in: newBatchIds } },
  });

  if (batches.length !== newBatchIds.length) {
    throw new ApiError(422, "One or more target batches not found");
  }
  if (batches.some((b) => b.status === "archived")) {
    throw new ApiError(422, "Cannot add to an archived batch", "ArchivedBatchError");
  }

  const hasDefault = batches.some((b) => b.isDefault);
  const hasNonDefault = batches.some((b) => !b.isDefault);
  if (hasDefault && hasNonDefault) {
    throw new ApiError(
      409,
      "Default and non-default batches are mutually exclusive",
      "DefaultMutuallyExclusiveError",
    );
  }

  const active = await getActiveMemberships(centreId, category, mobile);
  const activeIds = new Set(active.map((m) => m.batchId));
  const targetIds = new Set(newBatchIds);

  // R4 guard: adding default when any non-default is already present
  if (hasDefault) {
    const hasActiveNonDefault = active.some((m) => !m.isDefault);
    if (hasActiveNonDefault) {
      throw new ApiError(
        409,
        "Cannot add to default batch while non-default memberships exist",
        "DefaultMutuallyExclusiveError",
      );
    }
  }

  // Remove memberships no longer in the target set
  const toRemove = active.filter((m) => !targetIds.has(m.batchId));
  for (const m of toRemove) {
    await removeMembership(m.id, changedBy, "manual_replace", "manual_remove");
  }

  // Add memberships not yet active
  for (const batchId of newBatchIds) {
    if (!activeIds.has(batchId)) {
      await addMembership(centreId, category, mobile, batchId, changedBy, "manual_add");
    }
  }
};

// ── Read queries (used by internal endpoints) ─────────────────────────────

export const getBatchMembers = async (
  centreId: number,
  category: string,
  batchId?: number,
): Promise<{ id: number; mobile: string; name: string | null; batchId: number; joinedAt: Date; source: string }[]> => {
  const rows = await rawPrisma.batchMember.findMany({
    where: {
      centreId,
      category,
      removedAt: null,
      ...(batchId !== undefined ? { batchId } : {}),
    },
    orderBy: { joinedAt: "asc" },
  });

  // batch_members has no name column — resolve a display name by mobile. First attendance IS the
  // registration here, so the name is either on a CB registration OR in AttendEase's person
  // directory (attendance-origin people). Prefer the CB registration, fall back to the directory.
  const normalized = rows.map((r) => digits(r.mobile));
  const registrations = normalized.length
    ? await rawPrisma.registration.findMany({
        where: { centreId, normalizedMobile: { in: rows.map((r) => r.mobile.replace(/\D/g, "")) } },
        select: { normalizedMobile: true, name: true },
      })
    : [];
  const regNameByMobile = new Map(registrations.map((r) => [digits(r.normalizedMobile), r.name]));
  const directoryName = attendeasePersonNames();

  return rows.map((r) => {
    const key = digits(r.mobile);
    return {
      id: r.id,
      mobile: r.mobile,
      name: regNameByMobile.get(key) ?? directoryName.get(key) ?? null,
      batchId: r.batchId,
      joinedAt: r.joinedAt,
      source: r.source,
    };
  });
};

export const getMembershipsByMobile = async (
  centreId: number,
  category: string,
  mobile: string,
): Promise<{ batchId: number; isDefault: boolean; joinedAt: Date; source: string }[]> => {
  const rows = await rawPrisma.batchMember.findMany({
    where: { centreId, category, mobile, removedAt: null },
    include: { batch: { select: { isDefault: true } } },
  });
  return rows.map((r) => ({
    batchId: r.batchId,
    isDefault: r.batch.isDefault,
    joinedAt: r.joinedAt,
    source: r.source,
  }));
};
