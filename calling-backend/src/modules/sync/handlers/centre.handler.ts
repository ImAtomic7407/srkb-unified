import { CentreAllowedCategory } from "@prisma/client";
import { prisma } from "../../../config/prisma.js";
import { bypassCentreScope } from "../../../lib/prisma-extension.js";
import type { WebhookEvent } from "../sync.types.js";

const statusToIsActive = (status: string) => status === "active";

export const handleCentreCreated = async (event: WebhookEvent) => {
  const p = event.payload as { centre_id: string; name: string; status: string };
  await bypassCentreScope(() =>
    prisma.centre.upsert({
      where: { code: p.centre_id },
      update: { name: p.name, attendeaseStatus: p.status, isActive: statusToIsActive(p.status), lastSyncedAt: new Date() },
      create: { code: p.centre_id, name: p.name, allowedCategories: CentreAllowedCategory.both, attendeaseStatus: p.status, isActive: statusToIsActive(p.status), lastSyncedAt: new Date() },
    }),
  );
};

export const handleCentreRenamed = async (event: WebhookEvent) => {
  const p = event.payload as { centre_id: string; new_name: string };
  await bypassCentreScope(() =>
    prisma.centre.updateMany({ where: { code: p.centre_id }, data: { name: p.new_name, lastSyncedAt: new Date() } }),
  );
};

export const handleCentreStatusChanged = async (event: WebhookEvent) => {
  const p = event.payload as { centre_id: string; new_status: string };
  const isActive = statusToIsActive(p.new_status);
  const inactiveSince = !isActive ? new Date() : null;

  await bypassCentreScope(async () => {
    const existing = await prisma.centre.findUnique({ where: { code: p.centre_id } });
    if (!existing) return;
    await prisma.centre.update({
      where: { code: p.centre_id },
      data: {
        status: isActive ? "active" : "inactive",
        attendeaseStatus: p.new_status,
        isActive,
        // Only set inactiveSince on first transition to inactive; clear on re-activation
        inactiveSince: isActive ? null : (existing.inactiveSince ?? inactiveSince),
        lastSyncedAt: new Date(),
      },
    });
  });
};

export const handleCentreDeleted = async (event: WebhookEvent) => {
  const p = event.payload as { centre_id: string };
  // Mark inactive + set attendeaseStatus to deleted-pending; archival worker handles grace period
  await bypassCentreScope(() =>
    prisma.centre.updateMany({
      where: { code: p.centre_id },
      data: { attendeaseStatus: "deleted-pending", isActive: false, inactiveSince: new Date(), lastSyncedAt: new Date() },
    }),
  );
};
