import { Router } from "express";
import { asyncHandler } from "../../lib/async-handler.js";
import { validateBody } from "../../middlewares/validate.js";
import { changePasswordSchema, loginSchema, registerRequestSchema } from "./auth.schema.js";
import { changePasswordHandler, loginHandler, publicCentresHandler, registerRequestHandler } from "./auth.controller.js";
import { authenticateRequest } from "../../middlewares/auth.js";

export const authRouter = Router();

authRouter.post("/login", validateBody(loginSchema), asyncHandler(loginHandler));
authRouter.get("/centres", asyncHandler(publicCentresHandler));
authRouter.post("/register-request", validateBody(registerRequestSchema), asyncHandler(registerRequestHandler));
authRouter.post("/change-password", authenticateRequest, validateBody(changePasswordSchema), asyncHandler(changePasswordHandler));
