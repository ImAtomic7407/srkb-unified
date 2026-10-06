/**
 * Retire the legacy calling responses the twelve new defaults replace.
 *
 * Seeding the new set did not remove the old one, so a caller sees BOTH — 21 options, with "Will
 * attend" next to "Yes" and "No answer / Switched off" next to "Switch off". That is worse than
 * what the client complained about, not better, so the redesign is not actually delivered until
 * the duplicates are gone.
 *
 * Only responses with a clear one-for-one replacement are retired. Three legacy options survive
 * BECAUSE NOTHING IN THE NEW SET DOES THEIR JOB, and silently dropping them would remove
 * behaviour callers rely on:
 *
 *   - "Call back later / Busy"  the only followup_callback; the twelve have no ring-them-back
 *   - "Call back tomorrow"      the only 1-day deferral; "Next week" is 7
 *   - "Call next month"         the only 30-day deferral
 *
 * Their labels are still conflated ("/ Busy"), but a centre admin can now rename them from
 * Directory - Responses, which is the whole point of the redesign. Renaming them here would push
 * one opinion onto every centre.
 *
 * Retiring is isActive = false, NOT a delete: call history stores the label verbatim and links by
 * dropdown_id, so every past attempt keeps its meaning, and any centre that wants one back can
 * re-enable it from the same screen.
 *
 * Run:  npx tsx scripts/retire-superseded-responses.ts          (dry run — prints, changes nothing)
 *       npx tsx scripts/retire-superseded-responses.ts --apply
 *       npx tsx scripts/retire-superseded-responses.ts --undo   (re-enables exactly these)
 */
import { PrismaClient, DropdownContext } from "@prisma/client";

const prisma = new PrismaClient();

/** legacy label -> the new response that does its job. */
const SUPERSEDED: Record<string, string> = {
  "Will attend": "Yes",
  "No answer / Switched off": "Not response / Switch off",
  "Won't come this time": "Will try next time",
  "Not interested / Won't ever come": "Not interested",
  "Wrong number / Invalid": "Wrong number",
  "Call next week": "Next week",
};

const KEPT = [
  ["Call back later / Busy", "nothing in the new set rings someone back"],
  ["Call back tomorrow", "the only 1-day deferral; \"Next week\" is 7"],
  ["Call next month", "the only 30-day deferral"],
];

/**
 * The three survivors were backfilled to `both`, because the backfill had only the action to go on
 * and `both` is the safe default when you cannot tell. Now that the caller's list actually splits
 * on it, `both` puts them under "they didn't pick up" — where none of them can be true. Somebody
 * who never answered did not ask to be rung back tomorrow.
 *
 * "Next week" (new) is already `answered` for exactly this reason; this makes its three siblings
 * agree. A centre that disagrees can change it in Directory - Responses.
 */
const SCOPE_FIX: Record<string, "answered"> = {
  "Call back later / Busy": "answered",
  "Call back tomorrow": "answered",
  "Call next month": "answered",
};

async function main() {
  const apply = process.argv.includes("--apply");
  const undo = process.argv.includes("--undo");

  // Global rows only. A centre that made its own copy of one of these owns that copy, and this
  // must not reach across and disable something a centre chose for itself.
  const rows = await prisma.dropdownOption.findMany({
    where: {
      context: DropdownContext.calling,
      centreId: null,
      label: { in: Object.keys(SUPERSEDED) },
    },
    orderBy: { id: "asc" },
  });

  console.log(undo ? "Re-enabling:" : "Superseded by the new response set:");
  for (const row of rows) {
    console.log(
      `  ${row.isActive ? "[active]  " : "[hidden]  "}${row.label.padEnd(34)} -> ${SUPERSEDED[row.label]}`,
    );
  }
  const missing = Object.keys(SUPERSEDED).filter(
    (l) => !rows.some((r) => r.label === l),
  );
  if (missing.length) console.log("  (not present, nothing to do:", missing.join(", "), ")");

  console.log("\nKept deliberately — nothing in the new set replaces them:");
  for (const [label, why] of KEPT) console.log(`  ${label.padEnd(34)} ${why}`);

  const target = undo ? true : false;
  const toChange = rows.filter((r) => r.isActive !== target);

  if (!apply && !undo) {
    console.log(`\nDRY RUN — ${toChange.length} would be hidden. Re-run with --apply.`);
    return;
  }
  if (toChange.length === 0) {
    console.log("\nNo response needs hiding or re-enabling.");
  } else {
    const result = await prisma.dropdownOption.updateMany({
      where: { id: { in: toChange.map((r) => r.id) } },
      data: { isActive: target },
    });
    console.log(`\n${result.count} response(s) ${target ? "re-enabled" : "hidden from callers"}.`);
  }

  // Runs even when nothing needed hiding — the scope fix is a separate correction, and skipping it
  // on a re-run is how it got missed the first time.
  // Undo restores the labels; it deliberately does not restore `both`, because `both` was never a
  // decision — it was the backfill having nothing to go on.
  if (!undo) {
    for (const [label, scope] of Object.entries(SCOPE_FIX)) {
      const fixed = await prisma.dropdownOption.updateMany({
        where: {
          context: DropdownContext.calling,
          centreId: null,
          label,
          answeredScope: { not: scope },
        },
        data: { answeredScope: scope },
      });
      if (fixed.count) console.log(`  "${label}" now shows only when they picked up.`);
    }
  }

  const remaining = await prisma.dropdownOption.count({
    where: { context: DropdownContext.calling, centreId: null, isActive: true },
  });
  console.log(`Callers at a centre with no customisation now see ${remaining} responses.`);
}

main().finally(() => prisma.$disconnect());
