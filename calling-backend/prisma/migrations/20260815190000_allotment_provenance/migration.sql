-- Allotment provenance on session call assignments.
--
-- Records WHICH LIST an allotment came from and which press of "Assign" created it, so the admin
-- screen can group a caller's allotments separately, show the row range each one covered, and put
-- the pool filter back the way it was when the heading is tapped.
--
-- All three columns are nullable and every existing row keeps NULL: rows written before this
-- feature simply group under their caller with no heading, which is the behaviour they already had.
-- Purely additive — no data is read, rewritten or removed.

ALTER TABLE "session_call_assignments" ADD COLUMN "allotment_key" TEXT;
ALTER TABLE "session_call_assignments" ADD COLUMN "allotment_label" TEXT;
ALTER TABLE "session_call_assignments" ADD COLUMN "allotment_filter" TEXT;

CREATE INDEX "session_call_assignments_campaign_id_allotment_key_idx"
  ON "session_call_assignments"("campaign_id", "allotment_key");
