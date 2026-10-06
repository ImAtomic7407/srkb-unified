import { DropdownAction, DropdownCategoryScope, DropdownContext } from "@prisma/client";
import { prisma, rawPrisma } from "../../config/prisma.js";
import { bypassCentreScope } from "../../lib/prisma-extension.js";
import { getRequestContext } from "../../lib/request-context.js";
import { ApiError } from "../../lib/api-error.js";
import { broadcastSse } from "../../lib/sse.js";
import {
  normaliseResponseRules,
  type Sentiment,
  type AnsweredScope,
} from "../../lib/response-rules.js";

// Push a real-time "refetch your dropdowns" signal to connected devices. A global option
// (centreId=null) reaches everyone; a centre-specific option reaches that centre (+ hosts).
// The payload tells clients which list changed so they can refetch precisely.
const announceDropdownChange = (
  change: "created" | "updated" | "disabled",
  centreId: number | null,
  context: DropdownContext,
  category: DropdownCategoryScope,
): void => {
  broadcastSse(
    "dropdowns.changed",
    { change, centreId, context, category, ts: Date.now() },
    { centreId },
  );
};

/**
 * Remember a name a response used to carry.
 *
 * Outcomes save instantly and sync later, so a caller's outbox can hold a response under a name
 * that no longer exists by the time it reaches the server. Recording the old name lets
 * resolveCallingOption still find the row — see src/lib/resolve-calling-option.ts for the rule that
 * an old name may LOCATE an option but never restores its old behaviour.
 *
 * Best-effort by design: a rename must not fail because its audit line could not be written. The
 * cost of losing one alias row is a late outcome falling back to keyword guessing, which is what
 * happened for every rename before this existed.
 */
const recordRename = async (
  dropdownId: number,
  previousLabel: string,
  context: DropdownContext,
  centreId: number | null,
): Promise<void> => {
  let changedBy: string | null = null;
  try {
    changedBy = String(getRequestContext().userId);
  } catch {
    // A seed or maintenance script has no request. The rename is still worth recording.
  }
  try {
    await rawPrisma.dropdownOptionAlias.create({
      data: { dropdownId, label: previousLabel, context, centreId, changedBy },
    });
  } catch {
    // Swallowed deliberately — see above.
  }
};

// ── Shared shape ──────────────────────────────────────────────────────────

type DropdownRow = {
  id: number;
  centreId: number | null;
  label: string;
  context: DropdownContext;
  category: DropdownCategoryScope;
  sort_order: number;
  is_active: boolean;
  is_global: boolean;
  action: DropdownAction | null;
  defer_days: number | null;
  overrides_id: number | null;
  is_override: boolean;
  // Declared meaning — the caller UI reads answered_scope to decide which branch of the
  // "Did they pick the call?" step this option belongs under, and sentiment to colour it.
  sentiment: Sentiment;
  counts_as_strike: boolean;
  remove_after_consecutive: number | null;
  answered_scope: AnsweredScope;
  is_system: boolean;
};

const toRow = (d: {
  id: number;
  centreId: number | null;
  label: string;
  context: DropdownContext;
  category: DropdownCategoryScope;
  sortOrder: number;
  isActive: boolean;
  deferDays: number | null;
  overridesId?: number | null;
  sentiment: Sentiment;
  countsAsStrike: boolean;
  removeAfterConsecutive: number | null;
  answeredScope: AnsweredScope;
  isSystem: boolean;
  mapping: { action: DropdownAction } | null;
}): DropdownRow => ({
  id: d.id,
  centreId: d.centreId,
  label: d.label,
  context: d.context,
  category: d.category,
  sort_order: d.sortOrder,
  is_active: d.isActive,
  is_global: d.centreId === null,
  action: d.mapping?.action ?? null,
  defer_days: d.deferDays ?? null,
  overrides_id: d.overridesId ?? null,
  is_override: (d.overridesId ?? null) !== null,
  sentiment: d.sentiment,
  counts_as_strike: d.countsAsStrike,
  remove_after_consecutive: d.removeAfterConsecutive ?? null,
  answered_scope: d.answeredScope,
  is_system: d.isSystem,
});

/**
 * Ids of the global options this centre has replaced with its own version.
 *
 * Deliberately ignores isActive: an INACTIVE override is how a centre hides a shared option for
 * itself, so it must still suppress the global — otherwise hiding would do nothing.
 */
const overriddenGlobalIds = async (centreId: number | null): Promise<Set<number>> => {
  if (!centreId) return new Set();
  const rows = await bypassCentreScope(() =>
    prisma.dropdownOption.findMany({
      where: { centreId, overridesId: { not: null } },
      select: { overridesId: true },
    }),
  );
  return new Set(rows.map((r) => r.overridesId as number));
};

// The deferral interval is only meaningful for defer_calling. Normalising here — rather than at each
// call site — means a non-deferral option can never keep a stale interval that would park someone,
// and a deferral option can never be saved without one (7 days matches the read-side fallback in
// sessionCampaign.resolveSessionOutcome, so the two agree instead of drifting).
const DEFAULT_DEFER_DAYS = 7;

const normaliseDeferDays = (
  action: DropdownAction | null | undefined,
  deferDays: number | null | undefined,
): number | null => {
  if (action !== DropdownAction.defer_calling) return null;
  return deferDays && deferDays > 0 ? deferDays : DEFAULT_DEFER_DAYS;
};

// ── List (caller-facing) — global + centre-specific, deduped ─────────────

export const listDropdowns = async (context: DropdownContext, category: DropdownCategoryScope) => {
  const ctx = getRequestContext();
  const centreId = ctx.centreId;

  // Bypass extension — we need the OR-NULL pattern, not the strict centreId filter
  const rows = await bypassCentreScope(() =>
    prisma.dropdownOption.findMany({
      where: {
        context,
        isActive: true,
        OR: [{ category }, { category: DropdownCategoryScope.both }],
        AND: [
          {
            OR: centreId
              ? [{ centreId }, { centreId: null }]
              : [{ centreId: null }],
          },
        ],
      },
      include: { mapping: true },
      orderBy: [{ sortOrder: "asc" }, { id: "asc" }],
    }),
  );

  // Drop any global this centre has explicitly overridden. This is the authoritative rule and is
  // keyed on id, so it keeps working after the centre renames its version.
  const suppressed = await overriddenGlobalIds(centreId);
  const visible = rows.filter((row) => !(row.centreId === null && suppressed.has(row.id)));

  // Retained belt-and-braces for the pre-override convention where a centre row shadowed a global by
  // sharing its exact label. Harmless alongside the id rule: when both apply they pick the same row.
  const seen = new Map<string, typeof visible[0]>();
  for (const row of visible) {
    const key = `${row.label}::${row.context}::${row.category}`;
    const existing = seen.get(key);
    if (!existing || (row.centreId !== null && existing.centreId === null)) {
      seen.set(key, row);
    }
  }

  return [...seen.values()].map(toRow);
};

// ── Admin list — for dropdown management UI (includes centreId info) ──────

export const listDropdownsAdmin = async ({
  centreId,
  includeGlobal,
  context,
}: {
  centreId?: number;
  includeGlobal?: boolean;
  context?: DropdownContext;
}) => {
  const rows = await bypassCentreScope(() =>
    prisma.dropdownOption.findMany({
      where: {
        ...(context ? { context } : {}),
        OR: [
          ...(centreId ? [{ centreId }] : []),
          ...(includeGlobal ? [{ centreId: null }] : []),
          ...(!centreId && !includeGlobal ? [{ centreId: null }] : []),
        ],
      },
      include: { mapping: true },
      orderBy: [{ context: "asc" }, { sortOrder: "asc" }, { id: "asc" }],
    }),
  );

  // Show the EFFECTIVE list, matching what this centre's callers actually see: a customised option
  // appears once, as the centre's version, not twice alongside the shared row it replaced. Inactive
  // rows are still returned — that is how the admin sees and can undo something they hid.
  const suppressed = await overriddenGlobalIds(centreId ?? null);
  return rows
    .filter((row) => !(row.centreId === null && suppressed.has(row.id)))
    .map(toRow);
};

// ── Create ────────────────────────────────────────────────────────────────

export const createDropdown = async ({
  label,
  context,
  category,
  action,
  sortOrder,
  centreId,
  deferDays,
  sentiment,
  countsAsStrike,
  removeAfterConsecutive,
  answeredScope,
}: {
  label: string;
  context: DropdownContext;
  category: DropdownCategoryScope;
  action: DropdownAction;
  sortOrder: number;
  centreId: number | null; // null = global default (host only)
  deferDays?: number | null;
  sentiment?: Sentiment | null;
  countsAsStrike?: boolean | null;
  removeAfterConsecutive?: number | null;
  answeredScope?: AnsweredScope | null;
}) => {
  try {
    const resolvedDeferDays = normaliseDeferDays(action, deferDays);
    // Coerce to the nearest legal combination. The editor greys out illegal choices, but a
    // stale or hand-rolled client could still ask for e.g. "positive + keeps calling".
    const rules = normaliseResponseRules({
      action,
      sentiment,
      countsAsStrike,
      removeAfterConsecutive,
      answeredScope,
    });

    // For global options (centreId=null), bypass extension so it doesn't inject centre context
    const dropdown = await bypassCentreScope(() =>
      prisma.dropdownOption.create({
        data: {
          label,
          context,
          category,
          sortOrder,
          isActive: true,
          centreId,
          deferDays: resolvedDeferDays,
          ...rules,
        },
      }),
    );

    await bypassCentreScope(() =>
      prisma.dropdownMapping.create({ data: { dropdownId: dropdown.id, action } }),
    );

    announceDropdownChange("created", centreId, context, category);
    return toRow({ ...dropdown, mapping: { action } });
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError(409, "Unable to create dropdown option — label may already exist for this context/category", error);
  }
};

// ── Per-centre override (copy-on-write) ──────────────────────────────────
//
// A centre admin owns what their own callers see, but must never mutate a global row — that row is
// read by every other centre. So editing or hiding a shared option transparently creates (or updates)
// a centre-scoped copy that replaces it for this centre only. The admin never has to know this
// happened; from their side the option simply became editable.

type OverridePatch = {
  label?: string;
  action?: DropdownAction;
  isActive?: boolean;
  sortOrder?: number;
  deferDays?: number | null;
  sentiment?: Sentiment | null;
  countsAsStrike?: boolean | null;
  removeAfterConsecutive?: number | null;
  answeredScope?: AnsweredScope | null;
};

const applyCentreOverride = async (
  global: {
    id: number;
    label: string;
    context: DropdownContext;
    category: DropdownCategoryScope;
    sortOrder: number;
    deferDays: number | null;
    sentiment: Sentiment;
    countsAsStrike: boolean;
    removeAfterConsecutive: number | null;
    answeredScope: AnsweredScope;
    mapping: { action: DropdownAction } | null;
  },
  centreId: number,
  patch: OverridePatch,
) => {
  const existing = await bypassCentreScope(() =>
    prisma.dropdownOption.findFirst({
      where: { centreId, overridesId: global.id },
      include: { mapping: true },
    }),
  );

  const action = patch.action ?? existing?.mapping?.action ?? global.mapping?.action ?? null;
  const deferDays = normaliseDeferDays(
    action,
    patch.deferDays !== undefined ? patch.deferDays : (existing?.deferDays ?? global.deferDays),
  );

  // Anything the patch does not set is inherited — from this centre's own copy if it already has
  // one, otherwise from the shared option — then coerced to a legal combination for `action`.
  const inheritFrom = existing ?? global;
  const rules = action
    ? normaliseResponseRules({
        action,
        sentiment: patch.sentiment ?? inheritFrom.sentiment,
        countsAsStrike: patch.countsAsStrike ?? inheritFrom.countsAsStrike,
        // Same absent-vs-null rule as updateDropdown: an explicit null clears, an absent key
        // inherits.
        removeAfterConsecutive:
          patch.removeAfterConsecutive !== undefined
            ? patch.removeAfterConsecutive
            : inheritFrom.removeAfterConsecutive,
        answeredScope: patch.answeredScope ?? inheritFrom.answeredScope,
      })
    : {};

  if (existing) {
    if (patch.label && patch.label !== existing.label) {
      await recordRename(existing.id, existing.label, existing.context, centreId);
    }
    await bypassCentreScope(() =>
      prisma.dropdownOption.update({
        where: { id: existing.id },
        data: {
          label: patch.label ?? existing.label,
          isActive: patch.isActive ?? existing.isActive,
          sortOrder: patch.sortOrder ?? existing.sortOrder,
          deferDays,
          ...rules,
        },
      }),
    );
    if (action) await upsertMapping(existing.id, action);
    return readRow(existing.id);
  }

  try {
    const created = await bypassCentreScope(() =>
      prisma.dropdownOption.create({
        data: {
          centreId,
          overridesId: global.id,
          // Anything the patch does not set is inherited from the shared option, so a centre that
          // only renames it keeps the same behaviour and position.
          label: patch.label ?? global.label,
          context: global.context,
          category: global.category,
          sortOrder: patch.sortOrder ?? global.sortOrder,
          isActive: patch.isActive ?? true,
          deferDays,
          ...rules,
        },
      }),
    );
    if (action) await upsertMapping(created.id, action);
    // Renaming a SHARED response is, mechanically, creating this centre's own copy under a new
    // name. To a caller at this centre it is still a rename, and their outbox may hold the shared
    // name — so the shared name becomes an alias of the copy, scoped to this centre. Without this,
    // that queued outcome would resolve to the shared row and be judged by the shared behaviour
    // rather than by what this centre configured.
    if (patch.label && patch.label !== global.label) {
      await recordRename(created.id, global.label, global.context, centreId);
    }
    announceDropdownChange("updated", centreId, global.context, global.category);
    return readRow(created.id);
  } catch (error) {
    // @@unique([centreId, label, context, category]) — the centre already has a different option
    // using this label in the same place.
    throw new ApiError(
      409,
      `This centre already has an option called "${patch.label ?? global.label}" in that list. Pick a different name.`,
      error,
    );
  }
};

const upsertMapping = async (dropdownId: number, action: DropdownAction) => {
  await bypassCentreScope(() =>
    prisma.dropdownMapping.upsert({
      where: { dropdownId },
      update: { action },
      create: { dropdownId, action },
    }),
  );
};

const readRow = async (id: number): Promise<DropdownRow> => {
  const row = await bypassCentreScope(() =>
    prisma.dropdownOption.findUnique({ where: { id }, include: { mapping: true } }),
  );
  if (!row) throw new ApiError(404, "Dropdown option not found after write");
  announceDropdownChange("updated", row.centreId, row.context, row.category);
  return toRow(row);
};

/**
 * Drop a centre's customisation so the shared option applies again.
 * Only an override row can be reverted — an option the centre created outright is deleted, not
 * reverted, and that is a different operation.
 */
export const revertDropdownOverride = async (id: number) => {
  const ctx = getRequestContext();
  const row = await bypassCentreScope(() =>
    prisma.dropdownOption.findUnique({ where: { id }, include: { overrides: true } }),
  );

  if (!row) throw new ApiError(404, "Dropdown option not found");
  if (row.overridesId === null) {
    throw new ApiError(422, "This option is not a customisation of a shared one, so there is nothing to revert to.");
  }
  if (!ctx.isHost && row.centreId !== ctx.centreId) {
    throw new ApiError(403, "You can only revert your own centre's customisations");
  }

  await bypassCentreScope(() => prisma.dropdownOption.delete({ where: { id } }));
  announceDropdownChange("updated", row.centreId, row.context, row.category);
  return { reverted_to: row.overridesId };
};

// ── Update ────────────────────────────────────────────────────────────────

export const updateDropdown = async (
  id: number,
  input: {
    label?: string;
    context?: DropdownContext;
    category?: DropdownCategoryScope;
    action?: DropdownAction;
    isActive?: boolean;
    sortOrder?: number;
    deferDays?: number | null;
    sentiment?: Sentiment | null;
    countsAsStrike?: boolean | null;
    removeAfterConsecutive?: number | null;
    answeredScope?: AnsweredScope | null;
  },
) => {
  const existing = await bypassCentreScope(() =>
    prisma.dropdownOption.findUnique({ where: { id }, include: { mapping: true } }),
  );

  if (!existing) throw new ApiError(404, "Dropdown option not found");

  // A global row belongs to every centre, so a centre admin editing one gets a copy of their own
  // instead of a 403. The host edits the shared row itself.
  if (existing.centreId === null) {
    const ctx = getRequestContext();
    if (!ctx.isHost) {
      if (!ctx.centreId) {
        throw new ApiError(403, "Only the host can edit shared dropdown options");
      }
      return applyCentreOverride(existing, ctx.centreId, {
        label: input.label,
        action: input.action,
        isActive: input.isActive,
        sortOrder: input.sortOrder,
        deferDays: input.deferDays,
        sentiment: input.sentiment,
        countsAsStrike: input.countsAsStrike,
        removeAfterConsecutive: input.removeAfterConsecutive,
        answeredScope: input.answeredScope,
      });
    }
  }

  // The action after this update decides whether an interval is allowed at all: switching an option
  // away from defer_calling must drop its interval, and switching one to it must gain a usable one.
  const effectiveAction = input.action ?? existing.mapping?.action ?? null;
  const resolvedDeferDays = normaliseDeferDays(
    effectiveAction,
    input.deferDays ?? existing.deferDays,
  );

  // Re-normalise against the action this option will have AFTER the update. Switching a
  // response from "move to follow-up" to "keep calling" must drop its positive meaning in the
  // same write, or the row would be left asserting something its behaviour contradicts.
  const rules = effectiveAction
    ? normaliseResponseRules({
        action: effectiveAction,
        sentiment: input.sentiment ?? existing.sentiment,
        countsAsStrike: input.countsAsStrike ?? existing.countsAsStrike,
        // `??` would be wrong here: an explicit null is how "never remove them on a run" is
        // expressed, and `null ?? existing` restores the value the admin just cleared. Only an
        // ABSENT key inherits — the same absent-vs-null rule defer_days follows.
        removeAfterConsecutive:
          input.removeAfterConsecutive !== undefined
            ? input.removeAfterConsecutive
            : existing.removeAfterConsecutive,
        answeredScope: input.answeredScope ?? existing.answeredScope,
      })
    : {};

  // Recorded BEFORE the update, while `existing.label` is still the name devices are holding. An
  // outcome queued under the old name stays resolvable — see recordRename.
  if (input.label && input.label !== existing.label) {
    await recordRename(id, existing.label, existing.context, existing.centreId);
  }

  await bypassCentreScope(() =>
    prisma.dropdownOption.update({
      where: { id },
      data: {
        label: input.label,
        context: input.context,
        category: input.category,
        isActive: input.isActive,
        sortOrder: input.sortOrder,
        deferDays: resolvedDeferDays,
        ...rules,
      },
    }),
  );

  if (input.action) {
    if (existing.mapping) {
      await bypassCentreScope(() =>
        prisma.dropdownMapping.update({ where: { dropdownId: id }, data: { action: input.action! } }),
      );
    } else {
      await bypassCentreScope(() =>
        prisma.dropdownMapping.create({ data: { dropdownId: id, action: input.action! } }),
      );
    }
  }

  const updated = await bypassCentreScope(() =>
    prisma.dropdownOption.findUnique({ where: { id }, include: { mapping: true } }),
  );

  if (!updated) throw new ApiError(404, "Dropdown option not found after update");
  announceDropdownChange("updated", updated.centreId, updated.context, updated.category);
  return toRow(updated);
};

// ── Disable ───────────────────────────────────────────────────────────────

export const disableDropdown = async (id: number) => {
  const existing = await bypassCentreScope(() =>
    prisma.dropdownOption.findUnique({ where: { id }, include: { mapping: true } }),
  );

  if (!existing) throw new ApiError(404, "Dropdown option not found");

  // Hiding a shared option must hide it for THIS centre only — recorded as an inactive override, not
  // by deactivating the row every other centre reads.
  if (existing.centreId === null) {
    const ctx = getRequestContext();
    if (!ctx.isHost) {
      if (!ctx.centreId) {
        throw new ApiError(403, "Only the host can disable shared dropdown options");
      }
      return applyCentreOverride(existing, ctx.centreId, { isActive: false });
    }
  }

  const updated = await bypassCentreScope(() =>
    prisma.dropdownOption.update({ where: { id }, data: { isActive: false } }),
  );

  announceDropdownChange("disabled", updated.centreId, updated.context, updated.category);
  return toRow({ ...updated, mapping: null });
};
