import type { NextFunction, Request, Response } from "express";
import type { ZodTypeAny } from "zod";
import { ApiError } from "../lib/api-error.js";

export const validateBody = (schema: ZodTypeAny) => {
  return (req: Request, _res: Response, next: NextFunction) => {
    const result = schema.safeParse(req.body);
    if (!result.success) {
      return next(new ApiError(422, "Validation failed", result.error.flatten()));
    }
    req.body = result.data;
    next();
  };
};

export const validateQuery = (schema: ZodTypeAny) => {
  return (req: Request, _res: Response, next: NextFunction) => {
    const result = schema.safeParse(req.query);
    if (!result.success) {
      return next(new ApiError(422, "Validation failed", result.error.flatten()));
    }
    Object.assign(req.query, result.data);
    next();
  };
};

export const validateParams = (schema: ZodTypeAny) => {
  return (req: Request, _res: Response, next: NextFunction) => {
    const result = schema.safeParse(req.params);
    if (!result.success) {
      return next(new ApiError(422, "Validation failed", result.error.flatten()));
    }
    Object.assign(req.params, result.data);
    next();
  };
};
