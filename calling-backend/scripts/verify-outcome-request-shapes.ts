/**
 * Every shape of "save this call outcome" request the server must accept, and the ones it must not.
 *
 * `dropdown_id` was added to a route that installed app builds already call. Those builds do not
 * send it, and a caller cannot be forced to upgrade before their next shift — an app that suddenly
 * started getting 400s would lose every outcome in its outbox. So the old shapes are asserted here
 * as explicitly as the new one.
 *
 * Run: npx tsx scripts/verify-outcome-request-shapes.ts
 */
import { updateCallStatusSchema } from "../src/modules/sessionCampaign/sessionCampaign.schema.js";
import { callingDoneSchema } from "../src/modules/calling/calling.schema.js";

let failed = 0;
const check = (name: string, ok: boolean, detail = "") => {
  if (ok) console.log(`  PASS  ${name}`);
  else {
    failed++;
    console.log(`  FAIL  ${name}${detail ? ` — ${detail}` : ""}`);
  }
};

const accepts = (
  schema: { safeParse: (v: unknown) => { success: boolean } },
  name: string,
  input: unknown,
  shouldPass: boolean,
) => {
  const got = schema.safeParse(input).success;
  check(name, got === shouldPass, `accepted=${got}, wanted=${shouldPass}`);
};

console.log("session call queue (PATCH /session-call-queue/:id)");
accepts(updateCallStatusSchema, "build before the two-step: label only", { outcome: "Yes" }, true);
accepts(
  updateCallStatusSchema,
  "build before response ids: label + answered",
  { outcome: "Yes", answered: true },
  true,
);
accepts(
  updateCallStatusSchema,
  "current build: label + answered + id",
  { outcome: "Yes", answered: true, dropdown_id: 12 },
  true,
);
accepts(
  updateCallStatusSchema,
  "an id with no label is still refused",
  { dropdown_id: 12 },
  false,
);
accepts(
  updateCallStatusSchema,
  "a non-numeric id is refused rather than coerced",
  { outcome: "Yes", dropdown_id: "12" },
  false,
);
accepts(updateCallStatusSchema, "a zero id is refused", { outcome: "Yes", dropdown_id: 0 }, false);
accepts(
  updateCallStatusSchema,
  "the legacy quick-status path still works",
  { call_status: "will_attend" },
  true,
);

console.log("\npool call (POST /calling/done)");
accepts(
  callingDoneSchema,
  "build before response ids: value only",
  { registration_id: 1, dropdown_value: "Yes" },
  true,
);
accepts(
  callingDoneSchema,
  "current build: value + id",
  { registration_id: 1, dropdown_value: "Yes", dropdown_id: 12 },
  true,
);
accepts(
  callingDoneSchema,
  "an id with no value is refused",
  { registration_id: 1, dropdown_id: 12 },
  false,
);

console.log(failed === 0 ? "\nALL PASS" : `\n${failed} FAILED`);
process.exit(failed === 0 ? 0 : 1);
