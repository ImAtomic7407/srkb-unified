-- Drop Google Sheets-era attendance tables.
-- AttendEase is now the single source of truth; these tables are no longer populated.
-- Use IF EXISTS so the migration is safe whether or not the tables were ever created.

-- 1. Leaf tables first (FK children)
DROP TABLE IF EXISTS "attendance_import_logs";
DROP TABLE IF EXISTS "attendance_sessions";
DROP TABLE IF EXISTS "attendance_batches";
DROP TABLE IF EXISTS "attendance_matches";
DROP TABLE IF EXISTS "attendance_sync_logs";
DROP TABLE IF EXISTS "attendance_summary";

-- 2. Remove the attendance_match_id column from my_attendance (was FK to attendance_matches)
-- SQLite does not support DROP COLUMN; recreate the table without that column.
PRAGMA foreign_keys=OFF;

CREATE TABLE "my_attendance_new" (
  "id"               INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  "registration_id"  INTEGER NOT NULL UNIQUE,
  "owner_caller_id"  INTEGER NOT NULL,
  "centre_id"        INTEGER NOT NULL,
  "status"           TEXT    NOT NULL DEFAULT 'active',
  "created_at"       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updated_at"       DATETIME NOT NULL,
  CONSTRAINT "my_attendance_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT "my_attendance_owner_caller_id_fkey" FOREIGN KEY ("owner_caller_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT "my_attendance_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);

INSERT INTO "my_attendance_new"
  SELECT "id", "registration_id", "owner_caller_id", "centre_id", "status", "created_at", "updated_at"
  FROM "my_attendance";

DROP TABLE "my_attendance";
ALTER TABLE "my_attendance_new" RENAME TO "my_attendance";

CREATE INDEX "my_attendance_owner_caller_id_status_idx" ON "my_attendance"("owner_caller_id", "status");
CREATE INDEX "my_attendance_centre_id_status_idx" ON "my_attendance"("centre_id", "status");

PRAGMA foreign_keys=ON;