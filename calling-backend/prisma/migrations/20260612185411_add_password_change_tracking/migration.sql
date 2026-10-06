-- RedefineTables
PRAGMA defer_foreign_keys=ON;
PRAGMA foreign_keys=OFF;
CREATE TABLE "new_users" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "name" TEXT NOT NULL,
    "mobile" TEXT NOT NULL,
    "role" TEXT NOT NULL,
    "approval_status" TEXT NOT NULL DEFAULT 'pending',
    "is_active" BOOLEAN NOT NULL DEFAULT false,
    "attendance_system_access" BOOLEAN NOT NULL DEFAULT false,
    "password_hash" TEXT,
    "must_change_password" BOOLEAN NOT NULL DEFAULT true,
    "password_updated_at" DATETIME,
    "centre_id" INTEGER,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "users_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE SET NULL ON UPDATE CASCADE
);
INSERT INTO "new_users" ("approval_status", "attendance_system_access", "centre_id", "created_at", "id", "is_active", "mobile", "name", "password_hash", "role") SELECT "approval_status", "attendance_system_access", "centre_id", "created_at", "id", "is_active", "mobile", "name", "password_hash", "role" FROM "users";
DROP TABLE "users";
ALTER TABLE "new_users" RENAME TO "users";
CREATE UNIQUE INDEX "users_mobile_key" ON "users"("mobile");
CREATE INDEX "users_centre_id_idx" ON "users"("centre_id");
PRAGMA foreign_keys=ON;
PRAGMA defer_foreign_keys=OFF;
