/**
 * Prove that renaming or hiding a response cannot break an outcome that was already queued.
 *
 * The scenario this guards is not hypothetical: saving an outcome is instant and parks it in an
 * on-device outbox, so a caller can be out of signal for hours holding a response that an admin
 * renames in the meantime. Before this work the server matched by CURRENT name among ACTIVE rows
 * only, so that outcome matched nothing and fell through to the keyword guesser (session path) or
 * was rejected with a 400 the app could never retry past (pool path).
 *
 * The load-bearing assertion is #6: after a rename, the OLD name still finds the option, and what
 * gets applied is the option's NEW behaviour. An old name may locate a response; it must never
 * resurrect that response's old meaning, or an admin's correction would silently skip everyone
 * whose phone happened to be offline when they made it.
 *
 * Everything is created inside a transaction that is ALWAYS rolled back — this runs against the
 * live database and must leave no trace.
 *
 * Run: npx tsx scripts/verify-rename-safe-resolution.ts
 */
import { PrismaClient, DropdownContext, DropdownAction, ResponseSentiment } from "@prisma/client";
import { resolveCallingOption } from "../src/lib/resolve-calling-option.js";

const prisma = new PrismaClient();

let pass = 0;
let fail = 0;
const check = (name: string, ok: boolean, detail = "") => {
  if (ok) {
    pass++;
    console.log(`  PASS  ${name}`);
  } else {
    fail++;
    console.log(`  FAIL  ${name}${detail ? ` — ${detail}` : ""}`);
  }
};

const ROLLBACK = "verify-rollback";

async function main() {
  const centre = await prisma.centre.findFirst({ orderBy: { id: "asc" } });
  if (!centre) throw new Error("no centre to test against");
  const otherCentre = await prisma.centre.findFirst({
    where: { id: { not: centre.id } },
    orderBy: { id: "asc" },
  });

  try {
    await prisma.$transaction(async (tx) => {
      // A shared response, exactly as the seeded twelve are shaped.
      const opt = await tx.dropdownOption.create({
        data: {
          centreId: null,
          label: "__verify_original__",
          context: DropdownContext.calling,
          sentiment: ResponseSentiment.not_reached,
          deferDays: null,
        },
      });
      await tx.dropdownMapping.create({
        data: { dropdownId: opt.id, action: DropdownAction.continue_calling },
      });

      const R = (input: Parameters<typeof resolveCallingOption>[1]) =>
        resolveCallingOption(tx, { context: DropdownContext.calling, ...input });

      // ── 1-3. Baseline, before anything is renamed ──────────────────────────────────────────
      const byName = await R({ label: "__verify_original__", centreId: centre.id });
      check("current name resolves", byName.option?.id === opt.id && byName.matchedBy === "label");

      const byId = await R({ dropdownId: opt.id, label: "anything at all", centreId: centre.id });
      check(
        "id wins over a label that matches nothing",
        byId.option?.id === opt.id && byId.matchedBy === "id",
      );

      const unknown = await R({ label: "__never_existed__", centreId: centre.id });
      check("an unknown name resolves to nothing", unknown.option === null && unknown.matchedBy === null);

      // ── 4. Hidden after the caller queued it ──────────────────────────────────────────────
      await tx.dropdownOption.update({ where: { id: opt.id }, data: { isActive: false } });
      const hiddenByName = await R({ label: "__verify_original__", centreId: centre.id });
      check(
        "a response switched off after the tap still resolves by name",
        hiddenByName.option?.id === opt.id && hiddenByName.matchedBy === "inactive_label",
      );
      const hiddenById = await R({ dropdownId: opt.id, centreId: centre.id });
      check(
        "a response switched off after the tap still resolves by id",
        hiddenById.option?.id === opt.id && hiddenById.matchedBy === "id",
      );
      await tx.dropdownOption.update({ where: { id: opt.id }, data: { isActive: true } });

      // ── 5-6. The rename. THIS IS THE POINT OF THE WHOLE CHANGE. ───────────────────────────
      // Rename AND change the behaviour at the same time, which is what an admin fixing a
      // miscofigured response actually does.
      await tx.dropdownOptionAlias.create({
        data: {
          dropdownId: opt.id,
          label: "__verify_original__",
          context: DropdownContext.calling,
          centreId: null,
        },
      });
      await tx.dropdownOption.update({
        where: { id: opt.id },
        data: { label: "__verify_renamed__", sentiment: ResponseSentiment.negative },
      });
      await tx.dropdownMapping.update({
        where: { dropdownId: opt.id },
        data: { action: DropdownAction.remove_from_running },
      });

      const viaOldName = await R({ label: "__verify_original__", centreId: centre.id });
      check(
        "the OLD name still finds the response after a rename",
        viaOldName.option?.id === opt.id && viaOldName.matchedBy === "alias",
        `matchedBy=${viaOldName.matchedBy}`,
      );

      // The one that matters. Found by yesterday's name, judged by today's rules.
      check(
        "an outcome found by its old name gets the NEW behaviour, not the old one",
        viaOldName.option?.mapping?.action === DropdownAction.remove_from_running &&
          viaOldName.option?.sentiment === ResponseSentiment.negative,
        `action=${viaOldName.option?.mapping?.action} sentiment=${viaOldName.option?.sentiment}`,
      );

      // ── 7. Two renames deep ───────────────────────────────────────────────────────────────
      await tx.dropdownOptionAlias.create({
        data: {
          dropdownId: opt.id,
          label: "__verify_renamed__",
          context: DropdownContext.calling,
          centreId: null,
        },
      });
      await tx.dropdownOption.update({
        where: { id: opt.id },
        data: { label: "__verify_renamed_twice__" },
      });
      const firstName = await R({ label: "__verify_original__", centreId: centre.id });
      const secondName = await R({ label: "__verify_renamed__", centreId: centre.id });
      check(
        "every past name keeps working, not just the most recent",
        firstName.option?.id === opt.id && secondName.option?.id === opt.id,
      );

      // ── 8. The current name still wins over someone else's alias ──────────────────────────
      // A label freed up by one response and adopted by another must resolve to the LIVE owner.
      const reuser = await tx.dropdownOption.create({
        data: {
          centreId: null,
          label: "__verify_original__", // the name opt used to have
          context: DropdownContext.calling,
          sentiment: ResponseSentiment.positive,
        },
      });
      await tx.dropdownMapping.create({
        data: { dropdownId: reuser.id, action: DropdownAction.move_to_followup },
      });
      const recycled = await R({ label: "__verify_original__", centreId: centre.id });
      check(
        "a recycled name resolves to whoever holds it NOW, not the one that let it go",
        recycled.option?.id === reuser.id && recycled.matchedBy === "label",
        `got id=${recycled.option?.id} (live=${reuser.id}, old owner=${opt.id})`,
      );

      // ── 9. An id from another centre is not honoured ──────────────────────────────────────
      if (otherCentre) {
        const foreign = await tx.dropdownOption.create({
          data: {
            centreId: otherCentre.id,
            label: "__verify_foreign__",
            context: DropdownContext.calling,
          },
        });
        const cross = await R({ dropdownId: foreign.id, centreId: centre.id });
        check(
          "an id belonging to another centre is refused",
          cross.option === null,
          `got ${cross.option?.id ?? "null"}`,
        );
      } else {
        console.log("  SKIP  cross-centre id guard (only one centre in this database)");
      }

      // ── 10. A centre's own alias beats the shared one ─────────────────────────────────────
      const centreCopy = await tx.dropdownOption.create({
        data: {
          centreId: centre.id,
          label: "__verify_centre_wording__",
          context: DropdownContext.calling,
          overridesId: reuser.id,
        },
      });
      await tx.dropdownMapping.create({
        data: { dropdownId: centreCopy.id, action: DropdownAction.mark_invalid },
      });
      await tx.dropdownOptionAlias.create({
        data: {
          dropdownId: centreCopy.id,
          label: "__verify_shared_name__",
          context: DropdownContext.calling,
          centreId: centre.id,
        },
      });
      await tx.dropdownOptionAlias.create({
        data: {
          dropdownId: opt.id,
          label: "__verify_shared_name__",
          context: DropdownContext.calling,
          centreId: null,
        },
      });
      const preferred = await R({ label: "__verify_shared_name__", centreId: centre.id });
      check(
        "a centre's own former name wins over a shared one",
        preferred.option?.id === centreCopy.id,
        `got ${preferred.option?.id} (centre copy=${centreCopy.id}, shared=${opt.id})`,
      );

      throw new Error(ROLLBACK);
    });
  } catch (error) {
    if (!(error instanceof Error) || error.message !== ROLLBACK) throw error;
  }

  // Nothing above may survive.
  const leaked = await prisma.dropdownOption.count({ where: { label: { startsWith: "__verify_" } } });
  check("the test left nothing behind", leaked === 0, `${leaked} rows remain`);

  console.log(`\n${pass} passed, ${fail} failed`);
  if (fail > 0) process.exitCode = 1;
  else console.log("ALL PASS");
}

main().finally(() => prisma.$disconnect());
