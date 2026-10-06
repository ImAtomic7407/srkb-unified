import { PrismaClient } from "@prisma/client";

const prisma = new PrismaClient();

async function main() {
  const assignments = await prisma.callingAssignment.findMany({
    where: {
      centreId: null,
    },
    select: {
      id: true,
      registrationId: true,
      registration: {
        select: {
          centreId: true,
        },
      },
    },
  });

  const attempts = await prisma.callAttempt.findMany({
    where: {
      centreId: null,
    },
    select: {
      id: true,
      registrationId: true,
      registration: {
        select: {
          centreId: true,
        },
      },
    },
  });

  let updatedAssignments = 0;
  let skippedAssignments = 0;
  let updatedAttempts = 0;
  let skippedAttempts = 0;

  await prisma.$transaction(async (tx) => {
    for (const assignment of assignments) {
      const centreId = assignment.registration.centreId;
      if (!centreId) {
        skippedAssignments += 1;
        continue;
      }

      await tx.callingAssignment.update({
        where: { id: assignment.id },
        data: { centreId },
      });
      updatedAssignments += 1;
    }

    for (const attempt of attempts) {
      const centreId = attempt.registration.centreId;
      if (!centreId) {
        skippedAttempts += 1;
        continue;
      }

      await tx.callAttempt.update({
        where: { id: attempt.id },
        data: { centreId },
      });
      updatedAttempts += 1;
    }
  });

  console.log(
    JSON.stringify(
      {
        updatedAssignments,
        skippedAssignments,
        updatedAttempts,
        skippedAttempts,
      },
      null,
      2,
    ),
  );
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
