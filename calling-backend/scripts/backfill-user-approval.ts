import { ApprovalStatus, PrismaClient, UserRole } from "@prisma/client";
import { randomBytes, scryptSync } from "crypto";

const prisma = new PrismaClient();

const hashPassword = (password: string) => {
  const salt = randomBytes(16).toString("hex");
  const derivedKey = scryptSync(password, salt, 64).toString("hex");
  return `${salt}:${derivedKey}`;
};

const SUPER_ADMIN_PASSWORD = hashPassword("admin1234");

async function main() {
  const users = await prisma.user.findMany();

  for (const user of users) {
    await prisma.user.update({
      where: { id: user.id },
      data: {
        approvalStatus: ApprovalStatus.approved,
        isActive: true,
        attendanceSystemAccess: true,
        passwordHash: user.role === UserRole.SUPER_ADMIN ? SUPER_ADMIN_PASSWORD : null,
      },
    });
  }

  console.log(`Backfilled ${users.length} user(s).`);
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
