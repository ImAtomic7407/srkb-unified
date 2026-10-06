-- Per-centre dictionary overrides.
--
-- HAND-WRITTEN, like 20260729230000_add_call_deferral, for the same reason: `prisma migrate dev`
-- cannot replay this project's history into a shadow database because 20260622000000_session_campaigns
-- contains Postgres `CREATE TYPE ... AS ENUM`, which SQLite cannot execute. Apply with
-- `prisma migrate deploy`, never `migrate dev`.
--
-- `overrides_id` points at the GLOBAL dropdown_options row (centre_id IS NULL) that this
-- centre-specific row replaces for its own centre. SQLite permits adding a column with a REFERENCES
-- clause only when its default is NULL, which is the case here.
ALTER TABLE "dropdown_options" ADD COLUMN "overrides_id" INTEGER REFERENCES "dropdown_options"("id") ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS "dropdown_options_overrides_id_idx" ON "dropdown_options"("overrides_id");

-- At most one override per centre per shared option. SQLite treats NULLs as distinct in a unique
-- index, so the many rows with overrides_id IS NULL are unaffected.
CREATE UNIQUE INDEX IF NOT EXISTS "dropdown_options_centre_id_overrides_id_key"
  ON "dropdown_options"("centre_id", "overrides_id");
