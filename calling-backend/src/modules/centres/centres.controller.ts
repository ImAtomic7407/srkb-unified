import type { Request, Response } from "express";
import {
  activateCentre,
  createCentre,
  deactivateCentre,
  getCentre,
  listCentres,
  updateCentre,
} from "./centres.service.js";
import { sendSuccess } from "../../lib/response.js";

export const listCentresHandler = async (_req: Request, res: Response) => {
  const result = await listCentres();
  sendSuccess(res, result, "Centres loaded");
};

export const createCentreHandler = async (req: Request, res: Response) => {
  const result = await createCentre(req.body);
  sendSuccess(res, result, "Centre created", 201);
};

export const getCentreHandler = async (req: Request, res: Response) => {
  const result = await getCentre(Number(req.params.id));
  sendSuccess(res, result, "Centre loaded");
};

export const updateCentreHandler = async (req: Request, res: Response) => {
  const result = await updateCentre(Number(req.params.id), req.body);
  sendSuccess(res, result, "Centre updated");
};

export const activateCentreHandler = async (req: Request, res: Response) => {
  const result = await activateCentre(Number(req.params.id));
  sendSuccess(res, result, "Centre activated");
};

export const deactivateCentreHandler = async (req: Request, res: Response) => {
  const result = await deactivateCentre(Number(req.params.id));
  sendSuccess(res, result, "Centre deactivated");
};
