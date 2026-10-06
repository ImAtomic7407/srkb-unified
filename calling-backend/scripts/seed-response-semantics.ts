import { PrismaClient, DropdownAction, DropdownContext, DropdownCategoryScope } from "@prisma/client";
import { normaliseResponseRules, type Sentiment, type AnsweredScope } from "../src/lib/response-rules";

const prisma = new PrismaClient();

type Seed = {
  label: string; action: DropdownAction; sentiment: Sentiment;
  strike: boolean; scope: AnsweredScope; deferDays?: number; system?: boolean;
};

// The twelve the client asked for, each with exactly one meaning.
const DEFAULTS: Seed[] = [
  { label: "Yes", action: DropdownAction.move_to_followup, sentiment: "positive", strike: false, scope: "answered" },
  { label: "Try to come", action: DropdownAction.move_to_followup, sentiment: "positive", strike: false, scope: "answered" },
  { label: "Next week", action: DropdownAction.defer_calling, sentiment: "positive", strike: false, scope: "answered", deferDays: 7 },
  { label: "Will try next time", action: DropdownAction.continue_calling, sentiment: "negative", strike: true, scope: "answered" },
  { label: "Not interested", action: DropdownAction.remove_from_running, sentiment: "negative", strike: true, scope: "answered", system: true },
  { label: "Wrong number", action: DropdownAction.mark_invalid, sentiment: "negative", strike: false, scope: "both", system: true },
  { label: "Out of patna", action: DropdownAction.continue_calling, sentiment: "not_reached", strike: false, scope: "both" },
  { label: "Busy", action: DropdownAction.continue_calling, sentiment: "not_reached", strike: false, scope: "both" },
  { label: "Not response", action: DropdownAction.continue_calling, sentiment: "not_reached", strike: false, scope: "not_answered" },
  { label: "Switch off", action: DropdownAction.continue_calling, sentiment: "not_reached", strike: false, scope: "not_answered" },
  { label: "No incoming", action: DropdownAction.continue_calling, sentiment: "not_reached", strike: false, scope: "not_answered" },
  { label: "Others", action: DropdownAction.continue_calling, sentiment: "not_reached", strike: false, scope: "both" },
];

/**
 * One-time, human-checked overrides for existing labels the ACTION alone cannot classify.
 *
 * `continue_calling` covers two genuinely different things — "I could not reach them" and
 * "I reached them and they said no this time" — and that ambiguity is precisely what this
 * redesign removes. Falling back to not_reached for all of them would be safe but would CHANGE
 * behaviour for the refusals: "Won't come this time" currently matches the WONT regex, is scored
 * wont_attend, and wont_attend is NOT in STRIKE_EXCLUDED, so it strikes today. It must keep
 * striking after the migration.
 *
 * This is a fixed list checked by hand against the live rows, applied once. It is not runtime
 * text matching, and nothing reads it after this script has run.
 */
const SENTIMENT_BY_LABEL: Record<string, { sentiment: Sentiment; strike: boolean }> = {
  "won't come this time": { sentiment: "negative", strike: true },
  "wont come this time": { sentiment: "negative", strike: true },
  "won’t come this time": { sentiment: "negative", strike: true },
};

// What each EXISTING option's action implies, so nothing changes behaviour at deploy time.
const SENTIMENT_BY_ACTION: Partial<Record<DropdownAction, Sentiment>> = {
  [DropdownAction.move_to_followup]: "positive",
  [DropdownAction.followup_callback]: "positive",
  [DropdownAction.defer_calling]: "positive",
  [DropdownAction.continue_calling]: "not_reached",
  [DropdownAction.remove_from_running]: "negative",
  [DropdownAction.mark_invalid]: "negative",
};

async function main() {
  const apply = process.argv.includes("--apply");

  const existing = await prisma.dropdownOption.findMany({
    where: { context: DropdownContext.calling },
    include: { mapping: true },
  });

  console.log(`backfilling ${existing.length} existing calling options`);
  for (const o of existing) {
    const action = o.mapping?.action;
    if (!action) { console.log(`  skip ${o.id} "${o.label}" — no mapping`); continue; }
    const byLabel = SENTIMENT_BY_LABEL[o.label.trim().toLowerCase()];
    const rules = normaliseResponseRules({
      action,
      sentiment: byLabel?.sentiment ?? SENTIMENT_BY_ACTION[action] ?? null,
      countsAsStrike: byLabel?.strike ?? action === DropdownAction.remove_from_running,
      removeAfterConsecutive: null,
      answeredScope: null,
    });
    const note = byLabel ? "  [label override]" : "";
    console.log(`  ${o.id} "${o.label}" ${action} -> ${rules.sentiment} strike=${rules.countsAsStrike}${note}`);
    if (apply) {
      await prisma.dropdownOption.update({ where: { id: o.id }, data: rules });
    }
  }

  console.log(`\nseeding ${DEFAULTS.length} global defaults`);
  let order = 100;
  for (const d of DEFAULTS) {
    const already = existing.find(
      (o) => o.centreId === null && o.label.toLowerCase() === d.label.toLowerCase(),
    );
    if (already) { console.log(`  exists: "${d.label}"`); continue; }
    const rules = normaliseResponseRules({
      action: d.action, sentiment: d.sentiment, countsAsStrike: d.strike,
      removeAfterConsecutive: null, answeredScope: d.scope,
    });
    console.log(`  create "${d.label}" -> ${rules.sentiment} strike=${rules.countsAsStrike} scope=${rules.answeredScope}`);
    if (apply) {
      const created = await prisma.dropdownOption.create({
        data: {
          centreId: null, label: d.label, context: DropdownContext.calling,
          category: DropdownCategoryScope.both, sortOrder: order,
          isActive: true, deferDays: d.deferDays ?? null, isSystem: d.system ?? false,
          ...rules,
        },
      });
      await prisma.dropdownMapping.create({
        data: { dropdownId: created.id, action: d.action },
      });
    }
    order += 1;
  }

  console.log(apply ? "\nAPPLIED" : "\nDRY RUN — pass --apply to write");
}

main().finally(() => prisma.$disconnect());
