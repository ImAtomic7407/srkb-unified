import "./env.js";
import { PrismaClient } from "@prisma/client";
import { centreIsolationExtension } from "../lib/prisma-extension.js";

const baseClient = new PrismaClient();
export const prisma = baseClient.$extends(centreIsolationExtension);
export type ExtendedPrismaClient = typeof prisma;

// Raw (unextended) client — use ONLY for cross-centre queries that bypass isolation by design:
// profile suggestions, host-level reconcile helpers, etc.
// Never use rawPrisma for regular domain writes inside a request context.
export const rawPrisma = baseClient;
