/**
 * Phase 6 — Bootstrap host account
 *
 * Sets the SUPER_ADMIN user's mobile (login identifier) and password from env vars
 * BOOTSTRAP_HOST_USERNAME and BOOTSTRAP_HOST_PASSWORD, and clears mustChangePassword.
 *
 * Run once, manually, after all preceding phases pass acceptance criteria:
 *   npx tsx scripts/bootstrap-host.ts
 *
 * Idempotent: safe to re-run.
 */

import { randomBytes, scryptSync } from "crypto";
import { PrismaClient, UserRole } from "@prisma/client";
import * as dotenv from "dotenv";

dotenv.config();

const prisma = new PrismaClient();

function hashPassword(password: string): string {
  const salt = randomBytes(16).toString("hex");
  const derivedKey = scryptSync(password, salt, 64, { N: 16384, r: 8, p: 1 }).toString("hex");
  return `${salt}:${derivedKey}`;
}

async function main() {
  const username = process.env.BOOTSTRAP_HOST_USERNAME?.trim();
  const password = process.env.BOOTSTRAP_HOST_PASSWORD?.trim();

  if (!username || !password) {
    console.error("ERROR: BOOTSTRAP_HOST_USERNAME and BOOTSTRAP_HOST_PASSWORD must be set in .env");
    process.exitCode = 1;
    return;
  }

  const host = await prisma.user.findFirst({ where: { role: UserRole.SUPER_ADMIN } });
  if (!host) {
    console.error("ERROR: No SUPER_ADMIN user found. Run prisma:seed first.");
    process.exitCode = 1;
    return;
  }

  // Check if the target mobile is already taken by a different user
  const conflict = await prisma.user.findFirst({
    where: { mobile: username, NOT: { id: host.id } },
  });
  if (conflict) {
    console.error(`ERROR: mobile/username "${username}" is already taken by user id=${conflict.id}`);
    process.exitCode = 1;
    return;
  }

  const passwordHash = hashPassword(password);

  await prisma.user.update({
    where: { id: host.id },
    data: {
      mobile: username,
      passwordHash,
      mustChangePassword: false,
    },
  });

  console.log(`Host account bootstrapped: id=${host.id}, username=${username}`);
  console.log("mustChangePassword set to false — host can log in immediately.");
}

main()
  .catch((err) => {
    console.error("bootstrap-host crashed:", err);
    process.exitCode = 1;
  })
  .finally(() => prisma.$disconnect());
