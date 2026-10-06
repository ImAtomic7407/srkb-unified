import express from "express";
import cors from "cors";
import { router } from "./routes/index.js";
import { syncRouter } from "./modules/sync/sync.router.js";
import { internalRouter } from "./modules/internal/internal.router.js";
import { logsRouter } from "./modules/logs/logs.router.js";
import { eventsRouter } from "./modules/events/events.router.js";
import { errorHandler } from "./middlewares/error-handler.js";
import { requestLogger } from "./middlewares/request-logger.js";

export const createApp = () => {
  const app = express();

  app.use(cors());
  app.use(express.json({ limit: "1mb" }));
  app.use(requestLogger);

  app.get("/health", (_req, res) => {
    res.json({ success: true, message: "OK" });
  });

  // Internal endpoints — no auth middleware, have their own IP+token guard
  app.use(syncRouter);
  app.use(internalRouter);

  // Real-time SSE stream — self-authenticating (Bearer header OR ?token=), mounted before
  // the header-only auth router so native EventSource (no custom headers) can connect.
  app.use(eventsRouter);

  app.use(logsRouter);
  app.use(router);
  app.use(errorHandler);

  return app;
};
