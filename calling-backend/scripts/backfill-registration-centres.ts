import { PrismaClient } from "@prisma/client";

const prisma = new PrismaClient();

async function main() {
  const defaultCentre = await prisma.centre.findFirst({
    orderBy: { id: "asc" },
  });

  if (!defaultCentre) {
    throw new Error("No centre found to backfill registrations");
  }

  const result = await prisma.registration.updateMany({
    where: {
      centreId: null,
    },
    data: {
      centreId: defaultCentre.id,
    },
  });

  console.log(
    JSON.stringify(
      {
        defaultCentreId: defaultCentre.id,
        updatedRegistrations: result.count,
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

