import { Router } from "express";
import { authenticateRequest } from "../middlewares/auth.js";
import { resolveCentre } from "../middlewares/resolve-centre.js";
import { bypassCentreScope } from "../lib/prisma-extension.js";
import { requestContextStorage } from "../lib/request-context.js";
import { prisma } from "../config/prisma.js";
import { asyncHandler } from "../lib/async-handler.js";
import { authRouter } from "../modules/auth/auth.routes.js";
import { registrationRouter } from "../modules/registration/registration.routes.js";
import { callingRouter } from "../modules/calling/calling.routes.js";
import { followupRouter } from "../modules/followup/followup.routes.js";
import { dropdownRouter } from "../modules/dropdowns/dropdowns.routes.js";
import { poolRouter } from "../modules/pool/pool.routes.js";
import { attendanceRouter } from "../modules/attendance/attendance.routes.js";
import { myAttendanceRouter } from "../modules/myAttendance/myAttendance.routes.js";
import { batchRouter } from "../modules/batch/batch.routes.js";
import { archiveRouter } from "../modules/archive/archive.routes.js";
import { hostRouter } from "../modules/host/host.routes.js";
import { dashboardRouter } from "../modules/dashboard/dashboard.routes.js";
import { notificationsRouter } from "../modules/notifications/notifications.routes.js";
import { downloadsRouter } from "../modules/downloads/downloads.routes.js";
import { centresRouter } from "../modules/centres/centres.routes.js";
import { usersRouter } from "../modules/users/users.routes.js";
import { sessionCampaignRouter } from "../modules/sessionCampaign/sessionCampaign.routes.js";
import { clientDumpRouter } from "../modules/clientDump/clientDump.router.js";

export const router = Router();

router.use("/auth", authRouter);
router.use(authenticateRequest);

// ── /me endpoints (no centre context needed) ──────────────────────────────

router.get("/me", (req, res) => {
  if (!req.user) { res.status(401).json({ message: "Unauthorized" }); return; }
  res.json({
    id: req.user.id,
    name: req.user.name,
    username: req.user.mobile,
    mobile: req.user.mobile,
    role: req.user.role,
    permissions: req.user.permissions,
    centreId: req.user.centreId,
    approvalStatus: req.user.approvalStatus ?? "approved",
    isActive: req.user.isActive ?? true,
    attendanceSystemAccess: req.user.attendanceSystemAccess ?? true,
  });
});

// Client data-dump collector (no centre context needed) — app uploads its stuck/
// failed queue here; admins read it back. Mounted in the no-centre zone alongside /me.
router.use("/client", clientDumpRouter);

// List centres this user has access to (used by the centre switcher UI)
router.get("/me/centres", asyncHandler(async (req, res) => {
  if (!req.user) { res.status(401).json({ message: "Unauthorized" }); return; }
  const rows = await bypassCentreScope(() =>
    prisma.userCentreAccess.findMany({
      where: { userId: req.user!.id },
      include: { centre: { select: { id: true, name: true, code: true, isActive: true, enabledYouth: true, enabledCongregation: true } } },
      orderBy: { centre: { name: "asc" } },
    }),
  );
  res.json({
    success: true,
    data: rows.map((r) => ({
      centreId: r.centreId,
      centreName: r.centre.name,
      centreCode: r.centre.code,
      isActive: r.centre.isActive,
      isAdmin: r.isAdmin,
      enabledYouth: r.centre.enabledYouth,
      enabledCongregation: r.centre.enabledCongregation,
      lastSeenAt: r.lastSeenAt,
    })),
  });
}));

// Effective permissions at the current centre (requires x-centre-id)
router.get("/me/effective-permissions", resolveCentre, (req, res) => {
  if (!req.user) { res.status(401).json({ message: "Unauthorized" }); return; }
  const ctx = requestContextStorage.getStore();
  res.json({
    success: true,
    data: {
      permissions: req.user.permissions,
      isAdminAtCentre: ctx?.isAdminAtCentre ?? false,
      isHost: ctx?.isHost ?? false,
    },
  });
});

// ── Host routes — auth only, no centre resolution (bypass) ───────────────
// resolveCentre skips /host/* for SUPER_ADMIN, so these run with centreId=null
router.use(hostRouter);

// ── All other routes — require both auth AND centre resolution ────────────
router.use(resolveCentre);

router.use(centresRouter);
router.use(usersRouter);
router.use(registrationRouter);
router.use(callingRouter);
router.use(followupRouter);
router.use(dropdownRouter);
router.use(poolRouter);
router.use(attendanceRouter);
router.use(myAttendanceRouter);
router.use(batchRouter);
router.use(archiveRouter);
router.use(dashboardRouter);
router.use(notificationsRouter);
router.use(downloadsRouter);
router.use(sessionCampaignRouter);
