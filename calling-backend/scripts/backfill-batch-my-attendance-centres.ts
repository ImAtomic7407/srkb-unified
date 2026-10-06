import { PrismaClient } from "@prisma/client";

const prisma = new PrismaClient();

async function main() {
  const myAttendances = await prisma.myAttendance.findMany({
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

  const batchMoveRequests = await prisma.batchMoveRequest.findMany({
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

  const batchMembers = await prisma.batchMember.findMany({
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

  const batchCallingAssignments = await prisma.batchCallingAssignment.findMany({
    where: {
      centreId: null,
    },
    select: {
      id: true,
      batchMemberId: true,
      batchMember: {
        select: {
          registration: {
            select: {
              centreId: true,
            },
          },
        },
      },
    },
  });

  let updatedMyAttendance = 0;
  let updatedBatchMoveRequests = 0;
  let updatedBatchMembers = 0;
  let updatedBatchCallingAssignments = 0;
  let skipped = 0;

  await prisma.$transaction(async (tx) => {
    for (const item of myAttendances) {
      const centreId = item.registration.centreId;
      if (!centreId) {
        skipped += 1;
        continue;
      }
      await tx.myAttendance.update({
        where: { id: item.id },
        data: { centreId },
      });
      updatedMyAttendance += 1;
    }

    for (const item of batchMoveRequests) {
      const centreId = item.registration.centreId;
      if (!centreId) {
        skipped += 1;
        continue;
      }
      await tx.batchMoveRequest.update({
        where: { id: item.id },
        data: { centreId },
      });
      updatedBatchMoveRequests += 1;
    }

    for (const item of batchMembers) {
      const centreId = item.registration.centreId;
      if (!centreId) {
        skipped += 1;
        continue;
      }
      await tx.batchMember.update({
        where: { id: item.id },
        data: { centreId },
      });
      updatedBatchMembers += 1;
    }

    for (const item of batchCallingAssignments) {
      const centreId = item.batchMember.registration.centreId;
      if (!centreId) {
        skipped += 1;
        continue;
      }
      await tx.batchCallingAssignment.update({
        where: { id: item.id },
        data: { centreId },
      });
      updatedBatchCallingAssignments += 1;
    }
  });

  console.log(
    JSON.stringify(
      {
        updatedMyAttendance,
        updatedBatchMoveRequests,
        updatedBatchMembers,
        updatedBatchCallingAssignments,
        skipped,
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
