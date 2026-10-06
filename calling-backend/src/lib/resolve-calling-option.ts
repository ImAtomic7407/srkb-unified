import { DropdownContext, Prisma } from "@prisma/client";

/**
 * Find the response an incoming call outcome refers to.
 *
 * ── Why this exists ────────────────────────────────────────────────────────────────────────────
 *
 * Saving an outcome is instant and durable: the caller taps, it lands in an on-device outbox, and
 * it syncs whenever signal returns — which may be hours later. The old lookup matched the option
 * by its LABEL and only among ACTIVE rows:
 *
 *     findFirst({ where: { label, context: calling, isActive: true } })
 *
 * So a centre admin renaming "Not interested" (or simply switching it off) while a caller was out
 * of signal orphaned every outcome sitting in that caller's outbox. The session path then fell
 * through to `deriveStatusFromOutcome`, the keyword guesser the response-semantics work exists to
 * delete, and the pool path rejected the write outright with 400 "Invalid calling dropdown_value".
 *
 * ── The rule this file enforces ────────────────────────────────────────────────────────────────
 *
 *   THE NAME IS HISTORICAL. THE BEHAVIOUR IS ALWAYS LIVE.
 *
 * An old name is allowed to LOCATE an option. It is never allowed to replay that option's old
 * behaviour. Once a row is found, the caller reads its CURRENT action, sentiment and interval
 * straight off the row — which is why this function returns the option itself and not a snapshot.
 *
 * Storing and replaying old behaviour would be actively harmful: an admin who corrects a
 * misconfigured response expects the correction to apply, and a rule that replayed yesterday's
 * meaning would silently exempt every caller whose phone happened to be offline when they fixed
 * it. Corrections would land for some people and not others, with no way to tell which. Only the
 * name is a historical fact; the meaning is a current decision.
 */

const include = { mapping: true } as const;
export type ResolvedOption = Prisma.DropdownOptionGetPayload<{ include: typeof include }>;

/**
 * The reads this function needs, declared structurally.
 *
 * Not `Prisma.TransactionClient`: the pool path calls this with the `tx` of a transaction opened on
 * the CENTRE-SCOPE-EXTENDED client, whose generic delegate signatures are not assignable to the
 * plain ones. Both clients satisfy the two queries below, which is all this file uses.
 */
type Db = {
  dropdownOption: {
    findUnique(args: {
      where: { id: number };
      include: typeof include;
    }): Promise<ResolvedOption | null>;
    findFirst(args: {
      where: { context: DropdownContext; label: string; isActive?: boolean };
      include: typeof include;
      orderBy: { centreId: "desc" }[];
    }): Promise<ResolvedOption | null>;
  };
  dropdownOptionAlias: {
    findMany(args: {
      where: { label: string; context: DropdownContext };
      orderBy: ({ changedAt: "desc" } | { id: "desc" })[];
      select: { dropdownId: true; centreId: true };
    }): Promise<{ dropdownId: number; centreId: number | null }[]>;
  };
};

export type OptionMatch = "id" | "label" | "inactive_label" | "alias" | null;

export interface ResolveInput {
  /** Sent by app builds that know their option ids. Authoritative when present. */
  dropdownId?: number | null;
  /** The text the caller's device recorded. May be a name the option no longer carries. */
  label?: string | null;
  /**
   * The centre the outcome belongs to. Used to prefer that centre's own copy of a response over
   * the shared one, exactly as the caller-facing list does.
   */
  centreId?: number | null;
  context?: DropdownContext;
}

/**
 * Resolution order, most trustworthy first. Every step returns the LIVE row.
 *
 *   1. id            — unambiguous; survives any number of renames
 *   2. active label  — how every client before this change identified a response
 *   3. inactive label — the response was switched off after the caller queued it. Its meaning is
 *                      still perfectly well defined, and refusing to read it would throw away a
 *                      real call over an admin's later housekeeping.
 *   4. alias         — a name the option used to carry
 *
 * Steps 2–4 prefer a centre-specific row over the shared one (`centreId: "desc"` puts non-null
 * first in SQLite), matching what the caller was actually shown.
 */
export const resolveCallingOption = async (
  db: Db,
  input: ResolveInput,
): Promise<{ option: ResolvedOption | null; matchedBy: OptionMatch }> => {
  const context = input.context ?? DropdownContext.calling;
  const label = input.label?.trim();

  // 1. By id. Deliberately NOT filtered on isActive — an id is an exact statement about which
  // response was tapped, and hiding it afterwards does not make that statement less true.
  if (input.dropdownId != null) {
    const byId = await db.dropdownOption.findUnique({
      where: { id: input.dropdownId },
      include,
    });
    // A row belonging to a DIFFERENT centre is not ours to read: ids come from a client and a
    // stale or tampered one must not reach across centres. Shared rows (centreId null) are fine.
    if (byId && byId.context === context) {
      const sameScope =
        byId.centreId === null || input.centreId == null || byId.centreId === input.centreId;
      if (sameScope) return { option: byId, matchedBy: "id" };
    }
  }

  if (!label) return { option: null, matchedBy: null };

  const scope = { context, label } as const;
  const preferCentre = [{ centreId: "desc" as const }];

  // 2. Active label — the ordinary path, unchanged in behaviour from before.
  const active = await db.dropdownOption.findFirst({
    where: { ...scope, isActive: true },
    include,
    orderBy: preferCentre,
  });
  if (active) return { option: active, matchedBy: "label" };

  // 3. Same name, switched off since.
  const inactive = await db.dropdownOption.findFirst({
    where: scope,
    include,
    orderBy: preferCentre,
  });
  if (inactive) return { option: inactive, matchedBy: "inactive_label" };

  // 4. A name it used to have. Newest alias first: if a label was recycled — "Busy" freed up by
  // one response and later adopted by another — the most recent claim is the one a caller's
  // device was most plausibly showing.
  const aliases = await db.dropdownOptionAlias.findMany({
    where: { label, context },
    orderBy: [{ changedAt: "desc" }, { id: "desc" }],
    select: { dropdownId: true, centreId: true },
  });
  const mine = input.centreId != null ? aliases.find((a) => a.centreId === input.centreId) : undefined;
  const chosen = mine ?? aliases.find((a) => a.centreId === null) ?? aliases[0];
  if (chosen) {
    const viaAlias = await db.dropdownOption.findUnique({
      where: { id: chosen.dropdownId },
      include,
    });
    if (viaAlias) return { option: viaAlias, matchedBy: "alias" };
  }

  return { option: null, matchedBy: null };
};
