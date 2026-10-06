-- One response per person per calling session.
--
-- Until now `call_attempts` was append-only: sessionCallAssignment was correctly UPDATEd in
-- place, but its mirror into call history was an unconditional INSERT. A caller who scrolled
-- back and corrected an answer therefore produced a second history row, and the app's "last
-- responses" reads history — so the same person showed two different outcomes for one day.
--
-- campaign_id records WHICH calling session a response belongs to, and the unique index makes
-- "one response per person per session" a database guarantee rather than a convention.
-- General-pool calls have no session and stay NULL; SQLite treats NULLs as distinct in a
-- unique index, so those remain append-only and are untouched by this change.

ALTER TABLE "call_attempts" ADD COLUMN "campaign_id" INTEGER
    REFERENCES "session_campaigns" ("id") ON DELETE SET NULL;

-- SQLite only permits a CONSTANT default in ALTER TABLE ADD COLUMN, so seed 0 and backfill
-- from created_at immediately after (Prisma stores DateTime as epoch-milliseconds INTEGER).
ALTER TABLE "call_attempts" ADD COLUMN "updated_at" DATETIME NOT NULL DEFAULT 0;
UPDATE "call_attempts" SET "updated_at" = "created_at";

CREATE UNIQUE INDEX "call_attempts_registration_id_campaign_id_key"
    ON "call_attempts" ("registration_id", "campaign_id");
CREATE INDEX "call_attempts_campaign_id_idx" ON "call_attempts" ("campaign_id");
