import type { Request, Response } from "express";
import jwt from "jsonwebtoken";
import { sendSuccess } from "../../lib/response.js";
import { changePassword, createRegisterRequest, listPublicCentres, loginWithCredentials, syncPasswordFromAE } from "./auth.service.js";
import { env } from "../../config/env.js";

export const loginHandler = async (req: Request, res: Response) => {
  const user = await loginWithCredentials(req.body.identifier, req.body.password);

  const token = jwt.sign(
    { sub: user.mobile, role: user.role },
    env.jwtSharedSecret,
    { expiresIn: env.jwtExpiresIn as jwt.SignOptions["expiresIn"] },
  );

  sendSuccess(res, { token, user, must_change_password: user.mustChangePassword }, "Login successful");
};

export const changePasswordHandler = async (req: Request, res: Response) => {
  const userId = req.user!.id;
  await changePassword(userId, req.body.new_password, req.body.old_password);
  sendSuccess(res, {}, "Password changed successfully");
};

export const syncPasswordFromAEHandler = async (req: Request, res: Response) => {
  const { username, password_hash, password_updated_at, plain_password, must_change } = req.body as {
    username: string;
    password_hash?: string;
    password_updated_at: string;
    plain_password?: string;
    must_change?: boolean;
  };
  // Require either password_hash or plain_password; plain_password is preferred
  // because AE and SRKB use incompatible scrypt key lengths.
  if (!username || (!password_hash && !plain_password) || !password_updated_at) {
    res.status(400).json({ message: "username, (password_hash or plain_password), password_updated_at are required" });
    return;
  }
  await syncPasswordFromAE(username, password_hash ?? null, password_updated_at, plain_password, must_change);
  res.json({ ok: true });
};

export const publicCentresHandler = async (_req: Request, res: Response) => {
  const data = await listPublicCentres();
  sendSuccess(res, data, "Centres loaded");
};

export const registerRequestHandler = async (req: Request, res: Response) => {
  const data = await createRegisterRequest({
    name: req.body.name,
    identifier: req.body.identifier,
    password: req.body.password,
    requested_role: req.body.requested_role,
    centre_id: req.body.centre_id,
    ae_access: req.body.ae_access ?? false,
  });

  sendSuccess(res, data, "Request submitted", 201);
};
