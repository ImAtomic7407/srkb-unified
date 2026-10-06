/**
 * The caller's picker cannot be built from `label` alone.
 *
 * `GET /dropdowns` is what the app reads to draw the outcome list. For the two-step
 * ("Did they pick the call?") the app needs `answered_scope` on every option, and to paint the
 * row optimistically before the server answers it needs `sentiment`. This asserts the caller-facing
 * projection carries them — the controller passes the service's rows through untouched, so
 * checking the service is checking the endpoint.
 *
 * Run: npx tsx scripts/verify-dropdown-projection.ts
 */
import { PrismaClient, DropdownContext, DropdownCategoryScope } from "@prisma/client";
import { requestContextStorage } from "../src/lib/request-context";
import { listDropdowns } from "../src/modules/dropdowns/dropdowns.service";

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

const REQUIRED = [
  "sentiment",
  "answered_scope",
  "counts_as_strike",
  "defer_days",
  "action",
  "remove_after_consecutive",
] as const;

async function main() {
  const centre = await prisma.centre.findFirst({ orderBy: { id: "asc" } });
  if (!centre) throw new Error("No centre in the database to test against");

  const run = <T>(centreId: number | null, fn: () => Promise<T>) =>
    requestContextStorage.run(
      {
        userId: 0,
        centreId,
        permissions: new Set<string>(),
        isAdminAtCentre: false,
        isHost: false,
      },
      fn,
    );

  const items = await run(centre.id, () =>
    listDropdowns(DropdownContext.calling, DropdownCategoryScope.both),
  );

  check("centre sees a calling list", items.length > 0);

  for (const field of REQUIRED) {
    check(
      `every option carries ${field}`,
      items.every((i) => field in i && (i as Record<string, unknown>)[field] !== undefined),
    );
  }

  // The two fields the two-step picker cannot work without must be populated, not merely present.
  check(
    "answered_scope is always one of answered/not_answered/both",
    items.every((i) => ["answered", "not_answered", "both"].includes(i.answered_scope)),
  );
  check(
    "sentiment is never null",
    items.every((i) => i.sentiment !== null),
  );

  // Every option must land under at least one branch of the two-step, or a caller could pick a
  // "Did they pick up?" answer and be shown nothing.
  const underYes = items.filter((i) => i.answered_scope !== "not_answered");
  const underNo = items.filter((i) => i.answered_scope !== "answered");
  check(`at least one response under YES (${underYes.length})`, underYes.length > 0);
  check(`at least one response under NO (${underNo.length})`, underNo.length > 0);

  console.log("\nwhat a caller at this centre would actually see:");
  for (const i of items) {
    console.log(
      `  ${i.label.padEnd(28)} ${String(i.action).padEnd(18)} ${i.sentiment.padEnd(12)} ${i.answered_scope}`,
    );
  }
  console.log(failed === 0 ? "\nALL PASS" : `\n${failed} FAILED`);
  process.exit(failed === 0 ? 0 : 1);
}

main().finally(() => prisma.$disconnect());
