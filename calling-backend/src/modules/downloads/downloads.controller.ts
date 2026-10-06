import type { Request, Response } from "express";
import { Category, DropdownCategoryScope, DropdownContext, UserRole } from "@prisma/client";
import { sendCsv } from "./downloads.responses.js";
import {
  getArchivedDownload,
  getBatchCallingDownload,
  getCallingListDownload,
  getDropdownsDownload,
  getFollowupListDownload,
  getMyAttendanceDownload,
  getNewRegistrationsDownload,
  getRemainingFollowupsDownload,
  getRemovedDownload,
} from "./downloads.service.js";

export const newRegistrationsDownloadHandler = async (req: Request, res: Response) => {
  const category = req.query.category as Category;
  const requester = req.user!;
  const requestedCentreId = req.query.centre_id ? Number(req.query.centre_id) : undefined;
  const effectiveRequester =
    requester.role === UserRole.SUPER_ADMIN && requestedCentreId
      ? { ...requester, centreId: requestedCentreId }
      : requester;
  const download = await getNewRegistrationsDownload(category, effectiveRequester);
  sendCsv(res, download.filename, download.csv);
};

export const remainingFollowupsDownloadHandler = async (req: Request, res: Response) => {
  const category = req.query.category as Category;
  const requester = req.user!;
  const requestedCentreId = req.query.centre_id ? Number(req.query.centre_id) : undefined;
  const effectiveRequester =
    requester.role === UserRole.SUPER_ADMIN && requestedCentreId
      ? { ...requester, centreId: requestedCentreId }
      : requester;
  const download = await getRemainingFollowupsDownload(category, effectiveRequester);
  sendCsv(res, download.filename, download.csv);
};

export const callingListDownloadHandler = async (req: Request, res: Response) => {
  const category = req.query.category as Category;
  const requester = req.user!;
  const requestedCentreId = req.query.centre_id ? Number(req.query.centre_id) : undefined;
  const effectiveRequester =
    requester.role === UserRole.SUPER_ADMIN && requestedCentreId
      ? { ...requester, centreId: requestedCentreId }
      : requester;
  const download = await getCallingListDownload(category, effectiveRequester);
  sendCsv(res, download.filename, download.csv);
};

export const followupListDownloadHandler = async (req: Request, res: Response) => {
  const category = req.query.category as Category;
  const requester = req.user!;
  const requestedCentreId = req.query.centre_id ? Number(req.query.centre_id) : undefined;
  const effectiveRequester =
    requester.role === UserRole.SUPER_ADMIN && requestedCentreId
      ? { ...requester, centreId: requestedCentreId }
      : requester;
  const download = await getFollowupListDownload(category, effectiveRequester);
  sendCsv(res, download.filename, download.csv);
};

export const myAttendanceDownloadHandler = async (req: Request, res: Response) => {
  const category = req.query.category as Category;
  const requester = req.user!;
  const requestedCentreId = req.query.centre_id ? Number(req.query.centre_id) : undefined;
  const effectiveRequester =
    requester.role === UserRole.SUPER_ADMIN && requestedCentreId
      ? { ...requester, centreId: requestedCentreId }
      : requester;
  const download = await getMyAttendanceDownload(category, effectiveRequester);
  sendCsv(res, download.filename, download.csv);
};

export const batchCallingDownloadHandler = async (req: Request, res: Response) => {
  const category = req.query.category as Category;
  const requester = req.user!;
  const requestedCentreId = req.query.centre_id ? Number(req.query.centre_id) : undefined;
  const effectiveRequester =
    requester.role === UserRole.SUPER_ADMIN && requestedCentreId
      ? { ...requester, centreId: requestedCentreId }
      : requester;
  const download = await getBatchCallingDownload(category, effectiveRequester);
  sendCsv(res, download.filename, download.csv);
};

export const removedDownloadHandler = async (req: Request, res: Response) => {
  const category = req.query.category as Category;
  const requester = req.user!;
  const requestedCentreId = req.query.centre_id ? Number(req.query.centre_id) : undefined;
  const effectiveRequester =
    requester.role === UserRole.SUPER_ADMIN && requestedCentreId
      ? { ...requester, centreId: requestedCentreId }
      : requester;
  const download = await getRemovedDownload(category, effectiveRequester);
  sendCsv(res, download.filename, download.csv);
};

export const archivedDownloadHandler = async (req: Request, res: Response) => {
  const category = req.query.category as Category;
  const requester = req.user!;
  const requestedCentreId = req.query.centre_id ? Number(req.query.centre_id) : undefined;
  const effectiveRequester =
    requester.role === UserRole.SUPER_ADMIN && requestedCentreId
      ? { ...requester, centreId: requestedCentreId }
      : requester;
  const download = await getArchivedDownload(category, effectiveRequester);
  sendCsv(res, download.filename, download.csv);
};

export const dropdownsDownloadHandler = async (req: Request, res: Response) => {
  const context = req.query.context as DropdownContext | undefined;
  const category = req.query.category as DropdownCategoryScope | undefined;
  const download = await getDropdownsDownload({
    context,
    category,
  });
  sendCsv(res, download.filename, download.csv);
};
