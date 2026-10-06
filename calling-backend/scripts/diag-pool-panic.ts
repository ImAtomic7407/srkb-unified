/**
 * DIAGNOSTIC ONLY — read-only. Narrows down which clause of `getNewRegistrationsPool` makes the
 * Prisma engine panic ("no entry found for key") for centre 1 / youth, by running the query in
 * widening slices and reporting the first that fails.
 *
 *   npx tsx scripts/diag-pool-panic.ts
 */
import { rawPrisma } from "../src/config/prisma.js";

const CENTRE = Number(process.env.CENTRE_ID ?? 1);
const CATEGORY = process.env.CATEGORY ?? "youth";

const main = async () => {
  const base = {
    category: CATEGORY,
    centreId: CENTRE,
    currentStage: "registered",
    activeQueue: "none",
  } as never;

  const tries: [string, () => Promise<number>][] = [
    ["count only", async () => rawPrisma.registration.count({ where: base })],
    ["findMany bare", async () => (await rawPrisma.registration.findMany({ where: base })).length],
    [
      "where + assignments-none",
      async () =>
        (await rawPrisma.registration.findMany({
          where: { ...(base as object), assignments: { none: { status: "active" } } } as never,
        })).length,
    ],
    [
      "include assignments",
      async () =>
        (await rawPrisma.registration.findMany({
          where: base,
          include: { assignments: { select: { id: true, status: true } } },
        })).length,
    ],
    [
      "include callAttempts take:1",
      async () =>
        (await rawPrisma.registration.findMany({
          where: base,
          include: {
            callAttempts: {
              orderBy: { createdAt: "desc" },
              take: 1,
              select: { dropdownValue: true, callType: true },
            },
          },
        })).length,
    ],
    [
      "include callAttempts NO take",
      async () =>
        (await rawPrisma.registration.findMany({
          where: base,
          include: { callAttempts: { select: { dropdownValue: true, callType: true } } },
        })).length,
    ],
    [
      "full pool shape",
      async () =>
        (await rawPrisma.registration.findMany({
          where: { ...(base as object), assignments: { none: { status: "active" } } } as never,
          include: {
            assignments: { select: { id: true, status: true } },
            callAttempts: {
              orderBy: { createdAt: "desc" },
              take: 1,
              select: { dropdownValue: true, callType: true },
            },
          },
          orderBy: { createdAt: "asc" },
        })).length,
    ],
  ];

  console.log(`centre ${CENTRE} / ${CATEGORY}`);
  for (const [name, fn] of tries) {
    try {
      console.log(`OK    ${name} -> ${await fn()}`);
    } catch (e) {
      const msg = String((e as Error).message).split("\n").map((s) => s.trim()).filter(Boolean);
      console.log(`FAIL  ${name} -> ${msg.slice(0, 3).join(" | ")}`);
    }
  }
};

main()
  .catch((e) => { console.error(e); process.exit(1); })
  .finally(() => rawPrisma.$disconnect());
