-- Response semantics: meaning is DECLARED, never inferred from the label.
--
-- Context: meaning was previously guessed from label text by regex in the app
-- (CallStatusRules.kt). Against the labels this centre actually wants, 5 of 11 were
-- misclassified — most severely "No incoming", which matched the WILL regex on the substring
-- "coming" and was scored as will_attend, so a person who had blocked incoming calls was
-- recorded as having confirmed attendance.
--
-- Defaults are chosen so this migration changes NO behaviour on its own: every existing row
-- lands on not_reached / no strike / both, and scripts/seed-response-semantics.ts then
-- backfills each one from its actual mapped action.
ALTER TABLE "dropdown_options" ADD COLUMN "sentiment" TEXT NOT NULL DEFAULT 'not_reached';
ALTER TABLE "dropdown_options" ADD COLUMN "counts_as_strike" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "dropdown_options" ADD COLUMN "remove_after_consecutive" INTEGER;
ALTER TABLE "dropdown_options" ADD COLUMN "answered_scope" TEXT NOT NULL DEFAULT 'both';
ALTER TABLE "dropdown_options" ADD COLUMN "is_system" BOOLEAN NOT NULL DEFAULT false;

-- Link call history to the option by id so a rename cannot orphan its meaning.
-- No FOREIGN KEY: SQLite cannot add one via ALTER TABLE.
ALTER TABLE "call_attempts" ADD COLUMN "dropdown_id" INTEGER;
ALTER TABLE "call_attempts" ADD COLUMN "answered" BOOLEAN;
CREATE INDEX "call_attempts_dropdown_id_idx" ON "call_attempts"("dropdown_id");
