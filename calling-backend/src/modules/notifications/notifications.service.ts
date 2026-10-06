import { ApprovalStatus, NotificationType, Prisma, UserRole } from "@prisma/client";
import { prisma, rawPrisma } from "../../config/prisma.js";

export const createNotification = async ({
  userId,
  title,
  message,
  type,
  registrationId,
}: {
  userId: number;
  title: string;
  message: string;
  type: NotificationType;
  registrationId?: number | null;
}) => {
  return prisma.notification.create({
    data: {
      userId,
      registrationId: registrationId ?? null,
      title,
      message,
      type,
    },
  });
};

export const createNotificationsForPermission = async ({
  permissionKey,
  title,
  message,
  type,
  registrationId,
}: {
  permissionKey: string;
  title: string;
  message: string;
  type: NotificationType;
  registrationId?: number | null;
}) => {
  const users = await prisma.userPermission.findMany({
    where: {
      permissionKey,
      isEnabled: true,
    },
    select: {
      userId: true,
    },
  });

  if (users.length === 0) {
    return [];
  }

  return prisma.notification.createMany({
    data: users.map((entry) => ({
      userId: entry.userId,
      registrationId: registrationId ?? null,
      title,
      message,
      type,
    })),
  });
};

export const createNotificationsForRole = async ({
  role,
  centreId,
  title,
  message,
  type,
  registrationId,
  referenceUserId,
}: {
  role: UserRole;
  centreId?: number | null;
  title: string;
  message: string;
  type: NotificationType;
  registrationId?: number | null;
  referenceUserId?: number | null;
}) => {
  const users = await prisma.user.findMany({
    where: {
      role,
      approvalStatus: ApprovalStatus.approved,
      isActive: true,
      ...(centreId ? { centreId } : {}),
    },
    select: {
      id: true,
    },
  });

  if (users.length === 0) {
    return [];
  }

  const encodedMessage = referenceUserId ? `${message} [uid:${referenceUserId}]` : message;

  return prisma.notification.createMany({
    data: users.map((entry) => ({
      userId: entry.id,
      registrationId: registrationId ?? null,
      title,
      message: encodedMessage,
      type,
    })),
  });
};

export const listNotifications = async (userId: number, unreadOnly: boolean) => {
  return prisma.notification.findMany({
    where: {
      userId,
      ...(unreadOnly ? { isRead: false } : {}),
    },
    include: {
      registration: true,
    },
    orderBy: {
      createdAt: "desc",
    },
  });
};

export const markNotificationRead = async (notificationId: number, userId: number) => {
  const notification = await prisma.notification.findUnique({
    where: { id: notificationId },
  });

  if (!notification || notification.userId !== userId) {
    return null;
  }

  return prisma.notification.update({
    where: { id: notificationId },
    data: {
      isRead: true,
    },
  });
};

/**
 * Resolve all recipients for a password-sync failure notification:
 * - All centre admins (isAdmin=true in UserCentreAccess) at every centre the affected user has access to
 * - All hosts (role=SUPER_ADMIN)
 * Returns deduplicated user IDs.
 */
export const resolvePasswordSyncFailedRecipients = async (affectedUsername: string): Promise<number[]> => {
  // Find the affected CB user
  const affectedUser = await rawPrisma.user.findFirst({ where: { mobile: affectedUsername }, select: { id: true } });

  const recipientSet = new Set<number>();

  if (affectedUser) {
    // Centre admins at every centre the affected user has access to
    const centreAccess = await rawPrisma.userCentreAccess.findMany({
      where: { userId: affectedUser.id },
      select: { centreId: true },
    });
    const centreIds = centreAccess.map((r) => r.centreId);
    if (centreIds.length > 0) {
      const admins = await rawPrisma.userCentreAccess.findMany({
        where: { centreId: { in: centreIds }, isAdmin: true },
        select: { userId: true },
      });
      admins.forEach((a) => recipientSet.add(a.userId));
    }
  }

  // All hosts (SUPER_ADMIN)
  const hosts = await rawPrisma.user.findMany({
    where: { role: UserRole.SUPER_ADMIN, isActive: true, approvalStatus: ApprovalStatus.approved },
    select: { id: true },
  });
  hosts.forEach((h) => recipientSet.add(h.id));

  return Array.from(recipientSet);
};

export const createPasswordSyncFailedNotifications = async ({
  affectedUsername,
  direction,
  lastError,
}: {
  affectedUsername: string;
  direction: "cb_to_ae" | "ae_to_cb";
  lastError: string;
}): Promise<void> => {
  const recipients = await resolvePasswordSyncFailedRecipients(affectedUsername);
  if (recipients.length === 0) {
    console.warn(`WARN password_sync_failed: username=${affectedUsername} direction=${direction} recipients=0 — no one to notify`);
    return;
  }
  await rawPrisma.notification.createMany({
    data: recipients.map((userId) => ({
      userId,
      title: `Password sync failed for ${affectedUsername}`,
      message: `Password sync (${direction}) failed after max retries. Error: ${lastError.slice(0, 200)}. Use Admin → Reset Password to recover.`,
      type: NotificationType.password_sync_failed,
      registrationId: null,
    })),
  });
  console.warn(`WARN password_sync_failed: username=${affectedUsername} direction=${direction} recipients=${recipients.length}`);
};
