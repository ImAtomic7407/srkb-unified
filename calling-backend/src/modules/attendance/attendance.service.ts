// Google Sheets attendance sync has been removed — AttendEase is the source of truth.
// This file is kept as a stub to satisfy any remaining imports during the transition.
// All functions below throw 410 Gone.

import { ApiError } from "../../lib/api-error.js";

const gone = (name: string): never => {
  throw new ApiError(410, `${name} is no longer available — use the AttendEase system directly`);
};

export const getAttendanceConfig = async () => gone("getAttendanceConfig");
export const testAttendanceConfig = async () => gone("testAttendanceConfig");
export const saveLockAttendanceConfig = async () => gone("saveLockAttendanceConfig");
export const unlockAttendanceConfig = async () => gone("unlockAttendanceConfig");
export const getAttendanceSystemLink = async () => gone("getAttendanceSystemLink");
export const listAttendanceSessions = async () => gone("listAttendanceSessions");
export const previewAttendanceSession = async () => gone("previewAttendanceSession");
export const importAttendanceSession = async () => gone("importAttendanceSession");
export const syncAttendance = async () => gone("syncAttendance");