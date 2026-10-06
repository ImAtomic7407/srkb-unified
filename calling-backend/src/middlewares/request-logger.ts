import type { NextFunction, Request, Response } from "express";
import { logger } from "../lib/logger.js";

const SKIP = new Set(["/health"]);

export const requestLogger = (req: Request, res: Response, next: NextFunction) => {
  if (SKIP.has(req.path)) return next();
  const start = Date.now();

  res.on("finish", () => {
    const ms = Date.now() - start;
    const status = res.statusCode;
    const user = req.user ? req.user.mobile : "anon";
    const centre = req.headers["x-centre-id"] as string | undefined;

    const line = [
      `${req.method} ${req.path}`,
      `status=${status}`,
      `${ms}ms`,
      `user=${user}`,
      centre ? `centre=${centre}` : null,
    ].filter(Boolean).join(" | ");

    if (status >= 500) logger.error(line);
    else if (status >= 400) logger.warn(line);
    else logger.info(line);
  });

  next();
};
