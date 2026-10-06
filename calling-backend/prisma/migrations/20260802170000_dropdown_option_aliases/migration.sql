-- Rename history for calling responses.
--
-- Hand-written because `prisma migrate dev` is permanently broken in this repo (see AGENT_MEMORY).
-- Purely additive: a new table and two indexes, no ALTER on an existing table, so it is safe to
-- apply while the backend is serving.
--
-- Nothing is backfilled. Every option currently carries its original name, so there is no prior
-- name to record; the table starts empty and fills as admins rename things from Directory.

CREATE TABLE "dropdown_option_aliases" (
    "id"          INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "dropdown_id" INTEGER NOT NULL,
    "label"       TEXT    NOT NULL,
    "context"     TEXT    NOT NULL,
    "centre_id"   INTEGER,
    "changed_at"  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "changed_by"  TEXT,
    CONSTRAINT "dropdown_option_aliases_dropdown_id_fkey"
        FOREIGN KEY ("dropdown_id") REFERENCES "dropdown_options" ("id")
        ON DELETE CASCADE ON UPDATE CASCADE
);

-- The lookup path: an outcome arrives holding a name, and this resolves it to an option in one hit.
CREATE INDEX "dropdown_option_aliases_label_context_idx"
    ON "dropdown_option_aliases" ("label", "context");

-- The audit path: "what has this response been called, and when did it change?"
CREATE INDEX "dropdown_option_aliases_dropdown_id_changed_at_idx"
    ON "dropdown_option_aliases" ("dropdown_id", "changed_at");
