import { Prisma } from "@prisma/client";
import { requestContextStorage } from "./request-context.js";

// All Prisma model names (lowercase) whose queries should be auto-scoped
// to the current request's centreId. Add new domain tables here.
const CENTRE_SCOPED_MODELS = new Set([
  "registration",
  "callingassignment",
  "callattempt",
  "followup",
  "myattendance",
  "batchmoverequest",
  "batchmember",
  "batchcallingassignment",
  "attendancesynclog",
  "attendancematch",
  "attendancebatch",
  "attendancesession",
  "attendanceimportlog",
  // Mirror tables — reads within a request context are auto-scoped to the current centre.
  // Sync writes use rawPrisma (no ALS context) so injection is skipped automatically.
  "batch",
  "session",
  // dropdownoption intentionally omitted — service always queries with OR-NULL hybrid pattern via rawPrisma
  // Notification intentionally omitted — model has no centreId column
]);

// Read queries that accept a `where` clause
const READ_OPS = new Set([
  "findMany",
  "findFirst",
  "findUnique",
  "findFirstOrThrow",
  "findUniqueOrThrow",
  "count",
  "aggregate",
  "groupBy",
]);

// Write queries where we inject into `data`
const CREATE_OPS = new Set(["create", "createMany"]);

// Mutation queries where we also scope the `where`
const SCOPED_WRITE_OPS = new Set(["update", "updateMany", "delete", "deleteMany", "upsert"]);

const getCentreId = (): number | null => {
  const ctx = requestContextStorage.getStore();
  return ctx?.centreId ?? null;
};

export const centreIsolationExtension = Prisma.defineExtension((client) => {
  return client.$extends({
    name: "centre-isolation",
    query: {
      $allModels: {
        async $allOperations({ model, operation, args, query }) {
          const modelKey = model?.toLowerCase();
          if (!modelKey || !CENTRE_SCOPED_MODELS.has(modelKey)) {
            return query(args);
          }

          const centreId = getCentreId();
          // No centreId in context = bypass (sync workers, host stats, seed)
          if (centreId === null) {
            return query(args);
          }

          const mutatedArgs = { ...args } as Record<string, unknown>;

          if (READ_OPS.has(operation)) {
            mutatedArgs.where = { ...(mutatedArgs.where as object ?? {}), centreId };
          }

          if (CREATE_OPS.has(operation)) {
            if (operation === "create") {
              mutatedArgs.data = { ...(mutatedArgs.data as object ?? {}), centreId };
            } else {
              // createMany: data is an array
              const data = mutatedArgs.data as Record<string, unknown>[];
              mutatedArgs.data = data.map((row) => ({ ...row, centreId }));
            }
          }

          if (SCOPED_WRITE_OPS.has(operation)) {
            mutatedArgs.where = { ...(mutatedArgs.where as object ?? {}), centreId };
            if (operation === "upsert") {
              mutatedArgs.create = { ...(mutatedArgs.create as object ?? {}), centreId };
              mutatedArgs.update = mutatedArgs.update ?? {};
            }
          }

          return query(mutatedArgs);
        },
      },
    },
  });
});

// Helper: run a callback with centreId forced to null (bypasses extension scoping).
// Use ONLY in: sync workers, reconcile jobs, host stats, seeding.
// Every call site should have a comment explaining why bypass is needed.
export const bypassCentreScope = <T>(fn: () => Promise<T>): Promise<T> => {
  const ctx = requestContextStorage.getStore();
  if (!ctx) {
    // Outside a request context — no ALS to modify, already unscoped
    return fn();
  }
  return requestContextStorage.run({ ...ctx, centreId: null }, fn);
};
