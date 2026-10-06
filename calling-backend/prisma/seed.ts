import {
  CentreType,
  ApprovalStatus,
  DropdownAction,
  DropdownCategoryScope,
  DropdownContext,
  PrismaClient,
  UserRole,
} from "@prisma/client";
import { randomBytes, scryptSync } from "crypto";
import {
  DEFAULT_PERMISSION_KEYS,
  OPTIONAL_PERMISSION_KEYS,
} from "../src/utils/permissions.js";
import { buildCentreConfigCreateData } from "../src/modules/centres/centre-config.defaults.js";

const prisma = new PrismaClient();

const hashPassword = (password: string) => {
  const salt = randomBytes(16).toString("hex");
  const derivedKey = scryptSync(password, salt, 64).toString("hex");
  return `${salt}:${derivedKey}`;
};

// Set SUPERADMIN_PASSWORD in .env to create the super-admin account. There is no built-in default.
const SUPER_ADMIN_PASSWORD = process.env.SUPERADMIN_PASSWORD ? hashPassword(process.env.SUPERADMIN_PASSWORD) : "";

// ─────────────────────────────────────────────────────────────────────────
// OUTCOME DROPDOWNS — redesigned 2026-07-06 (see plan: tolerance/strike model)
//
// Goals:
//  - One clear, minimal outcome list per context. No legacy mapping-actions
//    (reassign_calling, assign_to_online_team, request_move_to_batch,
//    move_to_batch_calling, pause_personal_followup) — they confused callers.
//  - Labels are explicit about intent so the strike engine can classify by the
//    mapped ACTION (not fragile keyword guessing):
//      * continue_calling   = tolerated contact-attempt (non-contact OR a soft
//        "not this time"). Counts toward the 5-strike tolerance window.
//      * defer_calling      = person answered and named a later date. Hides them from the live
//        queue until that date, then returns them to the batch roster. Never a strike — this is
//        a positive signal, and treating it like a missed call used to auto-archive interested
//        people after five polite postponements.
//      * move_to_followup   = positive / converting. Never a strike.
//      * remove_from_running = firm refusal ("won't ever come" / not interested).
//        Immediate action, no tolerance.
//      * mark_invalid       = bad contact info.
//  - my_attendance is DELIBERATELY not seeded — the app's "My Call Notebook" is a
//    read-only history feed and needs no outcome dropdown.
//
// IMPORTANT: seedDropdowns() below deactivates any global (centreId=null) option
// whose (context,label) is NOT in this list, so removing a row here retires it
// from every caller on the next seed run.
// ─────────────────────────────────────────────────────────────────────────
// Explicitly typed: without it TypeScript infers a union over the entries and `seed.deferDays`
// (present on only the three deferral rows) becomes inaccessible.
const dropdownSeeds: {
  label: string;
  context: DropdownContext;
  action: DropdownAction;
  sortOrder: number;
  deferDays?: number;
}[] = [
  // ── Set A: first-time / session-campaign calling (context = calling) ──
  { label: "Will attend", context: DropdownContext.calling, action: DropdownAction.move_to_followup, sortOrder: 1 },
  // NOT-PICKED-UP outcome. Deliberately kept alongside the timed deferrals below and deliberately
  // still a strike: it means the phone was not answered, so the caller may retry in a few minutes
  // and the next caller needs to see it. It is NOT "they asked for a later date" — that is what
  // defer_calling is for. Removing this would lose the distinction between "no contact" and
  // "contact made, come back later".
  { label: "Call back later / Busy", context: DropdownContext.calling, action: DropdownAction.continue_calling, sortOrder: 2 },
  { label: "No answer / Switched off", context: DropdownContext.calling, action: DropdownAction.continue_calling, sortOrder: 3 },
  { label: "Won't come this time", context: DropdownContext.calling, action: DropdownAction.continue_calling, sortOrder: 4 },
  // TIMED DEFERRALS — the person ANSWERED and asked to be called on a later date. Fixed intervals
  // so the caller taps once with no date picker; deferDays is what actually drives the date, so a
  // centre can retitle these without changing behaviour. Not strikes (see STRIKE_EXCLUDED).
  { label: "Call back tomorrow", context: DropdownContext.calling, action: DropdownAction.defer_calling, sortOrder: 5, deferDays: 1 },
  { label: "Call next week", context: DropdownContext.calling, action: DropdownAction.defer_calling, sortOrder: 6, deferDays: 7 },
  { label: "Call next month", context: DropdownContext.calling, action: DropdownAction.defer_calling, sortOrder: 7, deferDays: 30 },
  { label: "Not interested / Won't ever come", context: DropdownContext.calling, action: DropdownAction.remove_from_running, sortOrder: 8 },
  { label: "Wrong number / Invalid", context: DropdownContext.calling, action: DropdownAction.mark_invalid, sortOrder: 9 },

  // ── Follow-ups (context = followup) — simple, no complex state changes ──
  { label: "Still coming", context: DropdownContext.followup, action: DropdownAction.stay_in_followup, sortOrder: 1 },
  { label: "Call again before class", context: DropdownContext.followup, action: DropdownAction.stay_in_followup, sortOrder: 2 },
  { label: "Won't come anymore", context: DropdownContext.followup, action: DropdownAction.remove_from_running, sortOrder: 3 },
  { label: "Send back to original caller", context: DropdownContext.followup, action: DropdownAction.send_back_to_original_caller, sortOrder: 4 },
  { label: "Wrong number / Invalid", context: DropdownContext.followup, action: DropdownAction.mark_invalid, sortOrder: 5 },

  // NOTE: There is DELIBERATELY no separate batch_calling / pool outcome list. A calling session IS
  // batch calling — it's created for a batch and its people are called via the ONE `calling` list
  // above. Ad-hoc calling is just a pool assignment that also uses the `calling` list. Keeping a
  // second list here only re-introduces the confusion this redesign removed.

  // ── Removed-record reuse (un-archive) ──
  { label: "Restore", context: DropdownContext.removed_reuse, action: DropdownAction.restore, sortOrder: 1 },
];

// NOTE: dummy-data generators (seedCentres / seedUsers / seedRegistrations /
// seedPerCentreDropdowns — fake "SRKB-MAIN/BRANCH" centres, 9000000000-3 users, and
// "Shared Person"/"Centre A Only" 9111111111-3 registrations) were REMOVED 2026-06-27.
// Real centres, users, and batches come from the AttendEase reconcile job after server start;
// the seed must NEVER inject test rows into the live DB. Do not re-add fake-data seeders here.

async function seedCentreConfig() {
  const existing = await prisma.centreConfig.findFirst();
  const data = buildCentreConfigCreateData({
    centreId: null,
    centreName: "Main Centre",
    centreType: CentreType.youth_and_congregation,
  });

  if (existing) {
    const {
      centreId: _centreId,
      ...updateData
    } = data;
    await prisma.centreConfig.update({
      where: { id: existing.id },
      data: updateData,
    });
    return;
  }

  await prisma.centreConfig.create({ data });
}

async function seedCentreConfigForCentre(centreId: number, centreName: string) {
  const template = await prisma.centreConfig.findFirst({
    where: {
      centreId: null,
    },
    orderBy: {
      id: "asc",
    },
  });

  if (!template) {
    return;
  }

  const existing = await prisma.centreConfig.findUnique({
    where: {
      centreId,
    },
  });

  const data = buildCentreConfigCreateData({
    centreId,
    centreName,
    source: template,
  });

  if (existing) {
    const {
      centreId: _centreId,
      ...updateData
    } = data;
    await prisma.centreConfig.update({
      where: {
        centreId,
      },
      data: updateData,
    });
    return;
  }

  await prisma.centreConfig.create({
    data,
  });
}

async function seedPermissions() {
  const users = await prisma.user.findMany();

  for (const user of users) {
    const enabledKeys: string[] =
      user.role === UserRole.CALLER
        ? [...DEFAULT_PERMISSION_KEYS]
        : [...DEFAULT_PERMISSION_KEYS, ...OPTIONAL_PERMISSION_KEYS];

    await prisma.userPermission.updateMany({
      where: { userId: user.id },
      data: { isEnabled: false },
    });

    for (const permissionKey of enabledKeys) {
      await prisma.userPermission.upsert({
        where: {
          userId_permissionKey: {
            userId: user.id,
            permissionKey,
          },
        },
        update: {
          isEnabled: true,
        },
        create: {
          userId: user.id,
          permissionKey,
          isEnabled: true,
        },
      });
    }
  }
}

async function seedDropdowns() {
  // Retire any legacy GLOBAL option not in the current seed list. Callers only ever
  // see active options, so this cleanly removes the old confusing entries (Reassign
  // calling, Assign to online team, Move to batch calling, Pause personal follow-up,
  // the whole my_attendance list, etc.) without deleting history. Centre-specific
  // options (centreId != null) are left untouched — those are admin customisations.
  const keep = new Set(dropdownSeeds.map((s) => `${s.context}::${s.label}`));
  const globals = await prisma.dropdownOption.findMany({ where: { centreId: null } });
  for (const opt of globals) {
    if (!keep.has(`${opt.context}::${opt.label}`) && opt.isActive) {
      await prisma.dropdownOption.update({ where: { id: opt.id }, data: { isActive: false } });
    }
  }

  for (const seed of dropdownSeeds) {
    // centreId=null (global default) can't be used in Prisma's compound unique lookup
    // because Prisma types the unique field as non-nullable. Use findFirst+create instead.
    let option = await prisma.dropdownOption.findFirst({
      where: { centreId: null, label: seed.label, context: seed.context, category: DropdownCategoryScope.both },
    });
    // deferDays is re-asserted on every run (including back to null for non-deferral options), so
    // changing an interval here is enough to change it everywhere on the next seed.
    const deferDays = seed.deferDays ?? null;
    if (!option) {
      option = await prisma.dropdownOption.create({
        data: { label: seed.label, context: seed.context, category: DropdownCategoryScope.both, sortOrder: seed.sortOrder, isActive: true, deferDays },
      });
    } else {
      await prisma.dropdownOption.update({ where: { id: option.id }, data: { isActive: true, sortOrder: seed.sortOrder, deferDays } });
    }

    await prisma.dropdownMapping.upsert({
      where: { dropdownId: option.id },
      update: { action: seed.action },
      create: { dropdownId: option.id, action: seed.action },
    });
  }
}

async function seedSuperAdmin() {
  if (!SUPER_ADMIN_PASSWORD) {
    console.warn("SUPERADMIN_PASSWORD is not set: skipping the super-admin account.");
    return;
  }
  const sa = await prisma.user.upsert({
    where: { mobile: "superadmin" },
    update: {
      name: "superadmin",
      role: UserRole.SUPER_ADMIN,
      approvalStatus: ApprovalStatus.approved,
      isActive: true,
      mustChangePassword: false,
      attendanceSystemAccess: true,
      passwordHash: SUPER_ADMIN_PASSWORD,
    },
    create: {
      name: "superadmin",
      mobile: "superadmin",
      role: UserRole.SUPER_ADMIN,
      approvalStatus: ApprovalStatus.approved,
      isActive: true,
      mustChangePassword: false,
      attendanceSystemAccess: true,
      passwordHash: SUPER_ADMIN_PASSWORD,
    },
  });

  // Grant all permissions to superadmin
  const allKeys = [...DEFAULT_PERMISSION_KEYS, ...OPTIONAL_PERMISSION_KEYS];
  for (const permissionKey of allKeys) {
    await prisma.userPermission.upsert({
      where: { userId_permissionKey: { userId: sa.id, permissionKey } },
      update: { isEnabled: true },
      create: { userId: sa.id, permissionKey, isEnabled: true },
    });
  }
}

async function main() {
  // `--dropdowns-only` refreshes just the outcome lists. Needed because seedSuperAdmin() rewrites
  // the superadmin password back to SUPERADMIN_PASSWORD on every run, so a full seed is NOT safe
  // to fire at a live system merely to publish a new dropdown option.
  if (process.argv.includes("--dropdowns-only")) {
    await seedDropdowns();
    console.log("Dropdown outcome options refreshed. Nothing else was touched.");
    return;
  }

  // Seed only essentials — centres, users, and batches come from the AttendEase
  // reconcile job that runs 10s after server start.
  await seedCentreConfig();   // global template used when new centres are created
  await seedDropdowns();      // calling/followup/batch outcome options
  await seedSuperAdmin();     // superadmin account (login: superadmin, password: SUPERADMIN_PASSWORD from .env)

  console.log("Seed complete. Real data will be pulled from AttendEase when the server starts.");
}

main()
  .then(async () => {
    await prisma.$disconnect();
  })
  .catch(async (error) => {
    console.error(error);
    await prisma.$disconnect();
    process.exit(1);
  });
