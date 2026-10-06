/**
 * The server's half of "meaning is declared, never guessed".
 *
 * Plan 2 stored a sentiment on every option. Until this passes, the server still decides what a
 * `continue_calling` response MEANS by matching the label against
 * /(no answer|switch|unreachable|voice|ringing)/ — which is the same class of bug as the app's
 * regex classifier. "Out of patna" and "No incoming" match nothing, so a caller who could not
 * reach anybody was recorded as having reached them (`called`).
 *
 * Run: npx tsx scripts/verify-outcome-resolution.ts
 */
import { PrismaClient, DropdownAction, DropdownContext, SessionCallStatus } from "@prisma/client";
import { resolveSessionStatusFrom } from "../src/modules/sessionCampaign/sessionCampaign.service";

const prisma = new PrismaClient();

let failed = 0;
const check = (name: string, cond: boolean) => {
  if (!cond) {
    console.error("FAIL:", name);
    failed++;
  } else {
    console.log("ok:", name);
  }
};

// What each seeded default must resolve to. Deferrals are handled before this function is reached
// (they read deferDays), so "Next week" is not listed here.
const EXPECTED: Record<string, SessionCallStatus> = {
  Yes: SessionCallStatus.will_attend,
  "Try to come": SessionCallStatus.will_attend,
  "Will try next time": SessionCallStatus.called,
  "Not interested": SessionCallStatus.wont_attend,
  "Wrong number": SessionCallStatus.wont_attend,
  "Out of patna": SessionCallStatus.no_answer,
  Busy: SessionCallStatus.no_answer,
  "Not response": SessionCallStatus.no_answer,
  "Switch off": SessionCallStatus.no_answer,
  "No incoming": SessionCallStatus.no_answer,
  Others: SessionCallStatus.no_answer,
};

async function main() {
  const options = await prisma.dropdownOption.findMany({
    where: { context: DropdownContext.calling, centreId: null },
    include: { mapping: true },
  });

  for (const [label, expected] of Object.entries(EXPECTED)) {
    const option = options.find((o) => o.label.toLowerCase() === label.toLowerCase());
    if (!option) {
      check(`"${label}" exists`, false);
      continue;
    }
    const actual = resolveSessionStatusFrom(option.mapping?.action, option.sentiment, option.label);
    check(`"${label}" -> ${expected} (got ${actual})`, actual === expected);
  }

  // The regression that started all of this. Stated separately from the table above so it cannot be
  // deleted by someone tidying the table.
  const noIncoming = options.find((o) => o.label.toLowerCase() === "no incoming");
  check(
    "No incoming is never will_attend",
    !!noIncoming &&
      resolveSessionStatusFrom(noIncoming.mapping?.action, noIncoming.sentiment, noIncoming.label) !==
        SessionCallStatus.will_attend,
  );

  // A label the database has never seen (an old client, or a centre's own wording that predates the
  // migration) must still resolve — meaning falls back to the keyword classifier, not to a crash.
  check(
    "unmapped label still resolves",
    resolveSessionStatusFrom(undefined, "not_reached", "no answer") === SessionCallStatus.no_answer,
  );

  // Sentiment is what decides, NOT the words. A centre that names its unreachable response
  // something the old regex never matched must still record a no-answer.
  check(
    "sentiment beats label wording",
    resolveSessionStatusFrom(DropdownAction.continue_calling, "not_reached", "gaon gaya hai") ===
      SessionCallStatus.no_answer,
  );
  check(
    "a reached-but-refused response is not a no-answer",
    resolveSessionStatusFrom(DropdownAction.continue_calling, "negative", "Busy this week") ===
      SessionCallStatus.called,
  );

  console.log(failed === 0 ? "\nALL PASS" : `\n${failed} FAILED`);
  process.exit(failed === 0 ? 0 : 1);
}

main().finally(() => prisma.$disconnect());
