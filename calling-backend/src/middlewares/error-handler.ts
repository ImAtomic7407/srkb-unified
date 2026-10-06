import type { NextFunction, Request, Response } from "express";
import { ApiError } from "../lib/api-error.js";
import { logger } from "../lib/logger.js";

export const errorHandler = (
  error: unknown,
  req: Request,
  res: Response,
  _next: NextFunction,
) => {
  if (error instanceof ApiError) {
    return res.status(error.statusCode).json({
      success: false,
      message: error.message,
      details: error.details ?? null,
    });
  }

  const msg = error instanceof Error ? error.message : String(error);
  logger.error(`UNHANDLED ${req.method} ${req.path} | ${msg}`);
  console.error(error);
  return res.status(500).json({
    success: false,
    message: "Internal server error",
  });
};
