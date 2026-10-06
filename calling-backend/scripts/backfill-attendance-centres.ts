import { PrismaClient } from "@prisma/client";

const prisma = new PrismaClient();

async function main() {
  let updatedAttendanceSyncLogs = 0;
  let updatedAttendanceMatches = 0;

  const syncLogs = await prisma.attendanceSyncLog.findMany({
    where: {
      centreId: null,
    },
    include: {
      startedByUser: true,
    },
  });

  for (const log of syncLogs) {
    const centreId = log.startedByUser.centreId ?? null;
    if (!centreId) {
      continue;
    }

    await prisma.attendanceSyncLog.update({
      where: { id: log.id },
      data: { centreId },
    });
    updatedAttendanceSyncLogs += 1;
  }

  const matches = await prisma.attendanceMatch.findMany({
    where: {
      centreId: null,
    },
    include: {
      registration: true,
    },
  });

  for (const match of matches) {
    const centreId = match.registration.centreId ?? null;
    if (!centreId) {
      continue;
    }

    await prisma.attendanceMatch.update({
      where: { id: match.id },
      data: { centreId },
    });
    updatedAttendanceMatches += 1;
  }

  console.log(
    JSON.stringify(
      {
        updatedAttendanceSyncLogs,
        updatedAttendanceMatches,
      },
      null,
      2,
    ),
  );
}

main()
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  })
  .finally(async () => {
    await prisma.$disconnect();
  });
