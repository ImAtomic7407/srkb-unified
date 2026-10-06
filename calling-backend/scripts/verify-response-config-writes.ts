/**
 * What the centre-admin directory editor writes must be what the row ends up holding.
 *
 * The editor greys out contradictions, but the API is the authority and has to coerce anything a
 * stale or hand-rolled client sends. Two properties are checked here:
 *
 *   1. an illegal combination is coerced to the nearest LEGAL one, never stored as asked
 *   2. an explicit null CLEARS a field, while an absent key INHERITS it
 *
 * (2) is not a hypothetical. `removeAfterConsecutive ?? existing.removeAfterConsecutive` restored
 * the value an admin had just cleared, so "never remove them on a run" could not be expressed at
 * all once it had been set.
 *
 * Run: npx tsx scripts/verify-response-config-writes.ts
 */
import { PrismaClient, DropdownAction, DropdownContext, DropdownCategoryScope } from "@prisma/client";
import { requestContextStorage } from "../src/lib/request-context";
import { createDropdown, updateDropdown } from "../src/modules/dropdowns/dropdowns.service";
import { resolveCallingOption } from "../src/lib/resolve-calling-option";
import { normaliseResponseRules } from "../src/lib/response-rules";

const prisma = new PrismaClient();

let failed = 0;
const check = (name: string, cond: boolean, detail?: unknown) => {
  if (!cond) {
    console.error("FAIL:", name, detail ?? "");
    failed++;
  } else {
    console.log("ok:", name);
  }
};

const LABEL = "__cfg_probe__";

async function main() {
  const centre = await prisma.centre.findFirst({ orderBy: { id: "asc" } });
  if (!centre) throw new Error("No centre to test against");

  const asAdmin = <T>(fn: () => Promise<T>) =>
    requestContextStorage.run(
      {
        userId: 0,
        centreId: centre.id,
        permissions: new Set<string>(),
        isAdminAtCentre: true,
        isHost: false,
      },
      fn,
    );

  const cleanup = async () => {
    const rows = await prisma.dropdownOption.findMany({ where: { label: { startsWith: LABEL } } });
    await prisma.dropdownMapping.deleteMany({ where: { dropdownId: { in: rows.map((r) => r.id) } } });
    await prisma.dropdownOption.deleteMany({ where: { id: { in: rows.map((r) => r.id) } } });
  };
  await cleanup();

  // ── 1. the compatibility table, at the pure-function level ────────────────
  check(
    "keep-calling cannot be positive",
    normaliseResponseRules({ action: DropdownAction.continue_calling, sentiment: "positive" }).sentiment !==
      "positive",
  );
  check(
    "an unreachable response can never strike",
    normaliseResponseRules({
      action: DropdownAction.continue_calling,
      sentiment: "not_reached",
      countsAsStrike: true,
    }).countsAsStrike === false,
  );
  // A consecutive rule is gated on MEANING, not on striking. This assertion used to demand the
  // opposite ("needs a strike to hang off"), which made "remove after N switched-off calls"
  // impossible to express: unreachable responses are not_reached by design and can never strike.
  check(
    "an unreachable response keeps its consecutive rule WITHOUT striking",
    (() => {
      const r = normaliseResponseRules({
        action: DropdownAction.continue_calling,
        sentiment: "not_reached",
        countsAsStrike: true,
        removeAfterConsecutive: 2,
      });
      return r.removeAfterConsecutive === 2 && r.countsAsStrike === false;
    })(),
  );
  check(
    "a wrong number always shows under both",
    normaliseResponseRules({
      action: DropdownAction.mark_invalid,
      answeredScope: "answered",
    }).answeredScope === "both",
  );
  check(
    "an illegal sentiment falls back to the LEAST destructive option",
    normaliseResponseRules({ action: DropdownAction.continue_calling, sentiment: "positive" }).sentiment ===
      "not_reached",
  );

  // ── 2. the same rules, through the write path ─────────────────────────────
  const created = await asAdmin(() =>
    createDropdown({
      label: `${LABEL}1`,
      context: DropdownContext.calling,
      category: DropdownCategoryScope.both,
      action: DropdownAction.continue_calling,
      sortOrder: 999,
      centreId: centre.id,
      // Everything below is illegal for continue_calling and must be coerced.
      sentiment: "positive",
      countsAsStrike: true,
      removeAfterConsecutive: 3,
      answeredScope: "answered",
    }),
  );
  check("created response is not stored as positive", created.sentiment !== "positive", created);
  check("created response does not strike", created.counts_as_strike === false, created);
  // The illegal `positive` above is coerced to not_reached, which MAY carry a consecutive rule — so
  // the rule survives the write even though the strike does not. Both halves are asserted together
  // because it is the combination (rule kept, strike dropped) that is the intended behaviour.
  check("created response keeps its consecutive rule", created.remove_after_consecutive === 3, created);
  check("scope the caller asked for survives when legal", created.answered_scope === "answered", created);

  // ── 3. absent key inherits, explicit null clears ──────────────────────────
  const legal = await asAdmin(() =>
    createDropdown({
      label: `${LABEL}2`,
      context: DropdownContext.calling,
      category: DropdownCategoryScope.both,
      action: DropdownAction.remove_from_running,
      sortOrder: 998,
      centreId: centre.id,
      sentiment: "negative",
      countsAsStrike: true,
      removeAfterConsecutive: 2,
      answeredScope: "answered",
    }),
  );
  check("a striking response keeps its consecutive rule", legal.remove_after_consecutive === 2, legal);

  const renamedOnly = await asAdmin(() => updateDropdown(legal.id, { label: `${LABEL}2b` }));
  check(
    "renaming does not disturb the consecutive rule",
    renamedOnly.remove_after_consecutive === 2,
    renamedOnly,
  );
  check("renaming does not disturb the meaning", renamedOnly.sentiment === "negative", renamedOnly);

  // ── 3b. the rename is remembered, end to end through the real service ─────
  // Not just "the table can hold an alias" (verify-rename-safe-resolution proves that) but "the
  // write path actually records one" — the two are different failures and only this catches the
  // second. Without it, a caller offline across the rename above loses their outcome's meaning.
  const alias = await prisma.dropdownOptionAlias.findFirst({
    where: { dropdownId: legal.id, label: `${LABEL}2` },
  });
  check("renaming records the old name", alias !== null, alias);

  const viaOldName = await resolveCallingOption(prisma, {
    label: `${LABEL}2`,
    centreId: centre.id,
  });
  check(
    "an outcome arriving under the old name still finds the response",
    viaOldName.option?.id === legal.id && viaOldName.matchedBy === "alias",
    viaOldName.matchedBy,
  );
  // Compared against the row as it stands NOW, not a hardcoded action: the point is that
  // resolving by a former name yields today's configuration, whatever today's configuration is.
  const live = await prisma.dropdownOption.findUnique({
    where: { id: legal.id },
    include: { mapping: true },
  });
  check(
    "...and is judged by its CURRENT behaviour",
    viaOldName.option?.mapping?.action === live?.mapping?.action &&
      viaOldName.option?.sentiment === live?.sentiment,
    `${viaOldName.option?.mapping?.action} vs live ${live?.mapping?.action}`,
  );

  const cleared = await asAdmin(() => updateDropdown(legal.id, { removeAfterConsecutive: null }));
  check(
    "an explicit null clears the consecutive rule",
    cleared.remove_after_consecutive === null,
    cleared,
  );
  check("...without switching the strike off too", cleared.counts_as_strike === true, cleared);

  // ── 4. changing the behaviour re-settles the meaning in the same write ────
  const reBehaved = await asAdmin(() =>
    updateDropdown(legal.id, { action: DropdownAction.move_to_followup }),
  );
  check(
    "switching to move-to-follow-up forces a positive meaning",
    reBehaved.sentiment === "positive",
    reBehaved,
  );
  check("...and a positive response cannot still strike", reBehaved.counts_as_strike === false, reBehaved);

  await cleanup();
  console.log(failed === 0 ? "\nALL PASS" : `\n${failed} FAILED`);
  process.exit(failed === 0 ? 0 : 1);
}

main().finally(() => prisma.$disconnect());
