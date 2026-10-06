-- CreateEnum
CREATE TYPE "SessionCampaignStatus" AS ENUM ('active', 'done');
CREATE TYPE "SessionCallStatus" AS ENUM ('pending', 'called', 'will_attend', 'wont_attend', 'no_answer', 'attended');

-- CreateTable
CREATE TABLE IF NOT EXISTS "session_campaigns" (
    "id"             INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "ae_session_id"  TEXT    NOT NULL,
    "centre_id"      INTEGER NOT NULL,
    "batch_id"       INTEGER NOT NULL,
    "scheduled_at"   DATETIME NOT NULL,
    "status"         TEXT NOT NULL DEFAULT 'active',
    "created_by_id"  INTEGER NOT NULL,
    "created_at"     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at"     DATETIME NOT NULL,
    CONSTRAINT "session_campaigns_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "session_campaigns_batch_id_fkey" FOREIGN KEY ("batch_id") REFERENCES "batches" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "session_campaigns_created_by_id_fkey" FOREIGN KEY ("created_by_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE IF NOT EXISTS "session_call_assignments" (
    "id"              INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "campaign_id"     INTEGER NOT NULL,
    "batch_member_id" INTEGER NOT NULL,
    "caller_id"       INTEGER NOT NULL,
    "call_status"     TEXT NOT NULL DEFAULT 'pending',
    "called_at"       DATETIME,
    "created_at"      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at"      DATETIME NOT NULL,
    CONSTRAINT "session_call_assignments_campaign_id_fkey" FOREIGN KEY ("campaign_id") REFERENCES "session_campaigns" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "session_call_assignments_batch_member_id_fkey" FOREIGN KEY ("batch_member_id") REFERENCES "batch_members" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "session_call_assignments_caller_id_fkey" FOREIGN KEY ("caller_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE
);

-- CreateIndex
CREATE UNIQUE INDEX IF NOT EXISTS "session_campaigns_ae_session_id_key" ON "session_campaigns"("ae_session_id");
CREATE INDEX IF NOT EXISTS "session_campaigns_centre_id_status_idx" ON "session_campaigns"("centre_id", "status");
CREATE INDEX IF NOT EXISTS "session_campaigns_scheduled_at_idx" ON "session_campaigns"("scheduled_at");
CREATE UNIQUE INDEX IF NOT EXISTS "session_call_assignments_campaign_id_batch_member_id_key" ON "session_call_assignments"("campaign_id", "batch_member_id");
CREATE INDEX IF NOT EXISTS "session_call_assignments_caller_id_call_status_idx" ON "session_call_assignments"("caller_id", "call_status");
CREATE INDEX IF NOT EXISTS "session_call_assignments_campaign_id_call_status_idx" ON "session_call_assignments"("campaign_id", "call_status");