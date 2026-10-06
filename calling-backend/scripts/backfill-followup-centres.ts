import { PrismaClient } from "@prisma/client";

const prisma = new PrismaClient();

async function main() {
  const followups = await prisma.followup.findMany({
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

  let updatedFollowups = 0;
  let skippedFollowups = 0;

  await prisma.$transaction(async (tx) => {
    for (const followup of followups) {
      const centreId = followup.registration.centreId;
      if (!centreId) {
        skippedFollowups += 1;
        continue;
      }

      await tx.followup.update({
        where: { id: followup.id },
        data: {
          centreId,
        },
      });
      updatedFollowups += 1;
    }
  });

  console.log(
    JSON.stringify(
      {
        updatedFollowups,
        skippedFollowups,
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
