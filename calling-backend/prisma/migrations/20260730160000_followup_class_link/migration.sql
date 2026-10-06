-- Link a follow-up to the class it is for, so it can expire once that class has passed.
--
-- HAND-WRITTEN (see 20260729230000_add_call_deferral): `prisma migrate dev` cannot replay this
-- project's history into a shadow DB because 20260622000000_session_campaigns contains Postgres
-- `CREATE TYPE ... AS ENUM`. Apply with `prisma migrate deploy`.
--
-- A follow-up means "ring them again BEFORE this class to confirm they're still coming". Without a
-- class reference there was nothing to measure "before" against, so a follow-up survived its own
-- class indefinitely and left the person parked in followup_active — where the new-registrations
-- pool cannot see them, silently blocking them from being assigned to any later session.
--
-- Nullable and ON DELETE SET NULL: rows that predate this column keep working and expire on age
-- instead, and deleting a session must not delete the record of a real conversation.
ALTER TABLE "followups" ADD COLUMN "campaign_id" INTEGER REFERENCES "session_campaigns"("id") ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS "followups_campaign_id_idx" ON "followups"("campaign_id");
