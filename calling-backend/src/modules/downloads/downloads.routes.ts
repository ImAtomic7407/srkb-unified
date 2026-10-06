import { Router } from "express";
import { asyncHandler } from "../../lib/async-handler.js";
import { requireAnyPermission } from "../../middlewares/permissions.js";
import { validateQuery } from "../../middlewares/validate.js";
import {
  downloadDropdownQuerySchema,
  downloadPeopleQuerySchema,
} from "./downloads.schema.js";
import {
  archivedDownloadHandler,
  batchCallingDownloadHandler,
  callingListDownloadHandler,
  dropdownsDownloadHandler,
  followupListDownloadHandler,
  myAttendanceDownloadHandler,
  newRegistrationsDownloadHandler,
  remainingFollowupsDownloadHandler,
  removedDownloadHandler,
} from "./downloads.controller.js";

export const downloadsRouter = Router();
const downloadPermissions = requireAnyPermission(["report_access", "assign_calling_access"]);

downloadsRouter.get(
  "/downloads/new-registrations",
  downloadPermissions,
  validateQuery(downloadPeopleQuerySchema),
  asyncHandler(newRegistrationsDownloadHandler),
);
downloadsRouter.get(
  "/downloads/remaining-followups",
  downloadPermissions,
  validateQuery(downloadPeopleQuerySchema),
  asyncHandler(remainingFollowupsDownloadHandler),
);
downloadsRouter.get(
  "/downloads/calling-list",
  downloadPermissions,
  validateQuery(downloadPeopleQuerySchema),
  asyncHandler(callingListDownloadHandler),
);
downloadsRouter.get(
  "/downloads/followup-list",
  downloadPermissions,
  validateQuery(downloadPeopleQuerySchema),
  asyncHandler(followupListDownloadHandler),
);
downloadsRouter.get(
  "/downloads/my-attendance",
  downloadPermissions,
  validateQuery(downloadPeopleQuerySchema),
  asyncHandler(myAttendanceDownloadHandler),
);
downloadsRouter.get(
  "/downloads/batch-calling",
  downloadPermissions,
  validateQuery(downloadPeopleQuerySchema),
  asyncHandler(batchCallingDownloadHandler),
);
downloadsRouter.get(
  "/downloads/removed",
  downloadPermissions,
  validateQuery(downloadPeopleQuerySchema),
  asyncHandler(removedDownloadHandler),
);
downloadsRouter.get(
  "/downloads/archived",
  downloadPermissions,
  validateQuery(downloadPeopleQuerySchema),
  asyncHandler(archivedDownloadHandler),
);
downloadsRouter.get(
  "/downloads/dropdowns",
  downloadPermissions,
  validateQuery(downloadDropdownQuerySchema),
  asyncHandler(dropdownsDownloadHandler),
);
