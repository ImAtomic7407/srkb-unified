import { createApp } from "./app.js";
import { env } from "./config/env.js";
import { startIntegrityScheduler, startReconcileScheduler } from "./modules/sync/reconcile.js";
import { startArchivalScheduler } from "./modules/sync/archival.js";
import { startOutboxWorker } from "./lib/outbox-worker.js";
import { startAeAccessReconcileScheduler } from "./modules/sync/ae-access-reconcile.js";
import { startFollowupExpiryScheduler } from "./modules/sync/followup-expiry.js";

const app = createApp();

app.listen(env.port, () => {
  console.log(`Calling management backend running on port ${env.port}`);
  startReconcileScheduler();
  startArchivalScheduler();
  startOutboxWorker();
  startAeAccessReconcileScheduler();
  startFollowupExpiryScheduler();
  startIntegrityScheduler();
});
