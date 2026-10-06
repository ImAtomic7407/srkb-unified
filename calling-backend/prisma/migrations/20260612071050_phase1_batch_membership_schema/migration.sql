/*
  Warnings:

  - You are about to drop the column `current_batch_caller_id` on the `batch_members` table. All the data in the column will be lost.
  - You are about to drop the column `entry_source` on the `batch_members` table. All the data in the column will be lost.
  - You are about to drop the column `original_caller_id` on the `batch_members` table. All the data in the column will be lost.
  - You are about to drop the column `registration_id` on the `batch_members` table. All the data in the column will be lost.
  - You are about to drop the column `status` on the `batch_members` table. All the data in the column will be lost.
  - You are about to drop the column `updated_at` on the `batch_members` table. All the data in the column will be lost.
  - You are about to drop the column `created_at` on the `batches` table. All the data in the column will be lost.
  - You are about to drop the column `is_active` on the `batches` table. All the data in the column will be lost.
  - You are about to drop the column `updated_at` on the `batches` table. All the data in the column will be lost.
  - Made the column `centre_id` on table `attendance_batches` required. This step will fail if there are existing NULL values in that column.
  - Made the column `centre_id` on table `attendance_import_logs` required. This step will fail if there are existing NULL values in that column.
  - Made the column `centre_id` on table `attendance_matches` required. This step will fail if there are existing NULL values in that column.
  - Made the column `centre_id` on table `attendance_sessions` required. This step will fail if there are existing NULL values in that column.
  - Made the column `centre_id` on table `attendance_sync_logs` required. This step will fail if there are existing NULL values in that column.
  - Made the column `centre_id` on table `batch_calling_assignments` required. This step will fail if there are existing NULL values in that column.
  - Added the required column `category` to the `batch_members` table without a default value. This is not possible if the table is not empty.
  - Added the required column `changed_at` to the `batch_members` table without a default value. This is not possible if the table is not empty.
  - Added the required column `changed_by` to the `batch_members` table without a default value. This is not possible if the table is not empty.
  - Added the required column `mobile` to the `batch_members` table without a default value. This is not possible if the table is not empty.
  - Added the required column `source` to the `batch_members` table without a default value. This is not possible if the table is not empty.
  - Made the column `centre_id` on table `batch_members` required. This step will fail if there are existing NULL values in that column.
  - Made the column `centre_id` on table `batch_move_requests` required. This step will fail if there are existing NULL values in that column.
  - Added the required column `centre_id` to the `batches` table without a default value. This is not possible if the table is not empty.
  - Added the required column `last_synced_at` to the `batches` table without a default value. This is not possible if the table is not empty.
  - Made the column `centre_id` on table `call_attempts` required. This step will fail if there are existing NULL values in that column.
  - Made the column `centre_id` on table `calling_assignments` required. This step will fail if there are existing NULL values in that column.
  - Made the column `centre_id` on table `followups` required. This step will fail if there are existing NULL values in that column.
  - Made the column `centre_id` on table `my_attendance` required. This step will fail if there are existing NULL values in that column.

*/
-- CreateTable
CREATE TABLE "sessions" (
    "session_id" TEXT NOT NULL PRIMARY KEY,
    "display_name" TEXT NOT NULL,
    "batch_id" INTEGER NOT NULL,
    "centre_id" INTEGER NOT NULL,
    "category" TEXT NOT NULL,
    "date" DATETIME,
    "status" TEXT NOT NULL DEFAULT 'active',
    "last_synced_at" DATETIME NOT NULL,
    CONSTRAINT "sessions_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "sessions_batch_id_fkey" FOREIGN KEY ("batch_id") REFERENCES "batches" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "batch_member_audit" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "batch_member_id" INTEGER NOT NULL,
    "action" TEXT NOT NULL,
    "by_user" TEXT NOT NULL,
    "at_time" DATETIME NOT NULL,
    "reason" TEXT,
    "prev_state" TEXT,
    "new_state" TEXT,
    CONSTRAINT "batch_member_audit_batch_member_id_fkey" FOREIGN KEY ("batch_member_id") REFERENCES "batch_members" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "outbox" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "kind" TEXT NOT NULL,
    "payload" TEXT NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'pending',
    "attempts" INTEGER NOT NULL DEFAULT 0,
    "next_attempt" DATETIME NOT NULL,
    "last_error" TEXT,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" DATETIME NOT NULL
);

-- RedefineTables
PRAGMA defer_foreign_keys=ON;
PRAGMA foreign_keys=OFF;
CREATE TABLE "new_attendance_batches" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "centre_id" INTEGER NOT NULL,
    "name" TEXT NOT NULL,
    "category" TEXT NOT NULL,
    "source_sheet_name" TEXT NOT NULL,
    "is_active" BOOLEAN NOT NULL DEFAULT true,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" DATETIME NOT NULL,
    CONSTRAINT "attendance_batches_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);
INSERT INTO "new_attendance_batches" ("category", "centre_id", "created_at", "id", "is_active", "name", "source_sheet_name", "updated_at") SELECT "category", "centre_id", "created_at", "id", "is_active", "name", "source_sheet_name", "updated_at" FROM "attendance_batches";
DROP TABLE "attendance_batches";
ALTER TABLE "new_attendance_batches" RENAME TO "attendance_batches";
CREATE INDEX "attendance_batches_centre_id_category_is_active_idx" ON "attendance_batches"("centre_id", "category", "is_active");
CREATE UNIQUE INDEX "attendance_batches_centre_id_name_category_key" ON "attendance_batches"("centre_id", "name", "category");
CREATE TABLE "new_attendance_import_logs" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "centre_id" INTEGER NOT NULL,
    "attendance_session_id" INTEGER,
    "started_by_user_id" INTEGER NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'previewed',
    "total_rows" INTEGER NOT NULL DEFAULT 0,
    "matched_existing_count" INTEGER NOT NULL DEFAULT 0,
    "new_direct_count" INTEGER NOT NULL DEFAULT 0,
    "invalid_count" INTEGER NOT NULL DEFAULT 0,
    "duplicate_count" INTEGER NOT NULL DEFAULT 0,
    "already_imported_count" INTEGER NOT NULL DEFAULT 0,
    "error_count" INTEGER NOT NULL DEFAULT 0,
    "preview_json" JSONB,
    "started_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "completed_at" DATETIME,
    CONSTRAINT "attendance_import_logs_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "attendance_import_logs_attendance_session_id_fkey" FOREIGN KEY ("attendance_session_id") REFERENCES "attendance_sessions" ("id") ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT "attendance_import_logs_started_by_user_id_fkey" FOREIGN KEY ("started_by_user_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE
);
INSERT INTO "new_attendance_import_logs" ("already_imported_count", "attendance_session_id", "centre_id", "completed_at", "duplicate_count", "error_count", "id", "invalid_count", "matched_existing_count", "new_direct_count", "preview_json", "started_at", "started_by_user_id", "status", "total_rows") SELECT "already_imported_count", "attendance_session_id", "centre_id", "completed_at", "duplicate_count", "error_count", "id", "invalid_count", "matched_existing_count", "new_direct_count", "preview_json", "started_at", "started_by_user_id", "status", "total_rows" FROM "attendance_import_logs";
DROP TABLE "attendance_import_logs";
ALTER TABLE "new_attendance_import_logs" RENAME TO "attendance_import_logs";
CREATE INDEX "attendance_import_logs_centre_id_started_at_idx" ON "attendance_import_logs"("centre_id", "started_at");
CREATE INDEX "attendance_import_logs_attendance_session_id_started_at_idx" ON "attendance_import_logs"("attendance_session_id", "started_at");
CREATE TABLE "new_attendance_matches" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "registration_id" INTEGER NOT NULL,
    "centre_id" INTEGER NOT NULL,
    "normalized_mobile" TEXT NOT NULL,
    "matched_caller_id" INTEGER,
    "attendance_total_count" INTEGER NOT NULL DEFAULT 0,
    "first_matched_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "last_matched_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "status" TEXT NOT NULL DEFAULT 'new_match',
    CONSTRAINT "attendance_matches_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "attendance_matches_matched_caller_id_fkey" FOREIGN KEY ("matched_caller_id") REFERENCES "users" ("id") ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT "attendance_matches_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);
INSERT INTO "new_attendance_matches" ("attendance_total_count", "centre_id", "first_matched_at", "id", "last_matched_at", "matched_caller_id", "normalized_mobile", "registration_id", "status") SELECT "attendance_total_count", "centre_id", "first_matched_at", "id", "last_matched_at", "matched_caller_id", "normalized_mobile", "registration_id", "status" FROM "attendance_matches";
DROP TABLE "attendance_matches";
ALTER TABLE "new_attendance_matches" RENAME TO "attendance_matches";
CREATE UNIQUE INDEX "attendance_matches_registration_id_key" ON "attendance_matches"("registration_id");
CREATE INDEX "attendance_matches_centre_id_status_idx" ON "attendance_matches"("centre_id", "status");
CREATE INDEX "attendance_matches_normalized_mobile_idx" ON "attendance_matches"("normalized_mobile");
CREATE INDEX "attendance_matches_status_idx" ON "attendance_matches"("status");
CREATE TABLE "new_attendance_sessions" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "centre_id" INTEGER NOT NULL,
    "attendance_batch_id" INTEGER NOT NULL,
    "batch_name" TEXT NOT NULL,
    "session_name" TEXT NOT NULL,
    "session_date" DATETIME,
    "session_key" TEXT NOT NULL,
    "source_sheet_name" TEXT NOT NULL,
    "row_count" INTEGER NOT NULL DEFAULT 0,
    "matched_existing_count" INTEGER NOT NULL DEFAULT 0,
    "new_direct_count" INTEGER NOT NULL DEFAULT 0,
    "invalid_count" INTEGER NOT NULL DEFAULT 0,
    "duplicate_count" INTEGER NOT NULL DEFAULT 0,
    "already_imported_count" INTEGER NOT NULL DEFAULT 0,
    "import_status" TEXT NOT NULL DEFAULT 'detected',
    "imported_at" DATETIME,
    "imported_by_user_id" INTEGER,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" DATETIME NOT NULL,
    CONSTRAINT "attendance_sessions_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "attendance_sessions_attendance_batch_id_fkey" FOREIGN KEY ("attendance_batch_id") REFERENCES "attendance_batches" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "attendance_sessions_imported_by_user_id_fkey" FOREIGN KEY ("imported_by_user_id") REFERENCES "users" ("id") ON DELETE SET NULL ON UPDATE CASCADE
);
INSERT INTO "new_attendance_sessions" ("already_imported_count", "attendance_batch_id", "batch_name", "centre_id", "created_at", "duplicate_count", "id", "import_status", "imported_at", "imported_by_user_id", "invalid_count", "matched_existing_count", "new_direct_count", "row_count", "session_date", "session_key", "session_name", "source_sheet_name", "updated_at") SELECT "already_imported_count", "attendance_batch_id", "batch_name", "centre_id", "created_at", "duplicate_count", "id", "import_status", "imported_at", "imported_by_user_id", "invalid_count", "matched_existing_count", "new_direct_count", "row_count", "session_date", "session_key", "session_name", "source_sheet_name", "updated_at" FROM "attendance_sessions";
DROP TABLE "attendance_sessions";
ALTER TABLE "new_attendance_sessions" RENAME TO "attendance_sessions";
CREATE INDEX "attendance_sessions_centre_id_import_status_idx" ON "attendance_sessions"("centre_id", "import_status");
CREATE UNIQUE INDEX "attendance_sessions_centre_id_session_key_key" ON "attendance_sessions"("centre_id", "session_key");
CREATE TABLE "new_attendance_sync_logs" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "started_by_user_id" INTEGER NOT NULL,
    "centre_id" INTEGER NOT NULL,
    "started_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "completed_at" DATETIME,
    "status" TEXT NOT NULL,
    "total_checked" INTEGER NOT NULL DEFAULT 0,
    "matched_count" INTEGER NOT NULL DEFAULT 0,
    "newly_matched_count" INTEGER NOT NULL DEFAULT 0,
    "already_processed_count" INTEGER NOT NULL DEFAULT 0,
    "skipped_count" INTEGER NOT NULL DEFAULT 0,
    "error_count" INTEGER NOT NULL DEFAULT 0,
    "error_message" TEXT,
    CONSTRAINT "attendance_sync_logs_started_by_user_id_fkey" FOREIGN KEY ("started_by_user_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "attendance_sync_logs_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);
INSERT INTO "new_attendance_sync_logs" ("already_processed_count", "centre_id", "completed_at", "error_count", "error_message", "id", "matched_count", "newly_matched_count", "skipped_count", "started_at", "started_by_user_id", "status", "total_checked") SELECT "already_processed_count", "centre_id", "completed_at", "error_count", "error_message", "id", "matched_count", "newly_matched_count", "skipped_count", "started_at", "started_by_user_id", "status", "total_checked" FROM "attendance_sync_logs";
DROP TABLE "attendance_sync_logs";
ALTER TABLE "new_attendance_sync_logs" RENAME TO "attendance_sync_logs";
CREATE INDEX "attendance_sync_logs_centre_id_started_at_idx" ON "attendance_sync_logs"("centre_id", "started_at");
CREATE INDEX "attendance_sync_logs_status_started_at_idx" ON "attendance_sync_logs"("status", "started_at");
CREATE TABLE "new_batch_calling_assignments" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "batch_member_id" INTEGER NOT NULL,
    "assigned_to_user_id" INTEGER NOT NULL,
    "assigned_by_user_id" INTEGER NOT NULL,
    "centre_id" INTEGER NOT NULL,
    "assignment_mode" TEXT NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'active',
    "start_date" DATETIME NOT NULL,
    "end_date" DATETIME NOT NULL,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" DATETIME NOT NULL,
    CONSTRAINT "batch_calling_assignments_batch_member_id_fkey" FOREIGN KEY ("batch_member_id") REFERENCES "batch_members" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "batch_calling_assignments_assigned_to_user_id_fkey" FOREIGN KEY ("assigned_to_user_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "batch_calling_assignments_assigned_by_user_id_fkey" FOREIGN KEY ("assigned_by_user_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "batch_calling_assignments_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);
INSERT INTO "new_batch_calling_assignments" ("assigned_by_user_id", "assigned_to_user_id", "assignment_mode", "batch_member_id", "centre_id", "created_at", "end_date", "id", "start_date", "status", "updated_at") SELECT "assigned_by_user_id", "assigned_to_user_id", "assignment_mode", "batch_member_id", "centre_id", "created_at", "end_date", "id", "start_date", "status", "updated_at" FROM "batch_calling_assignments";
DROP TABLE "batch_calling_assignments";
ALTER TABLE "new_batch_calling_assignments" RENAME TO "batch_calling_assignments";
CREATE UNIQUE INDEX "batch_calling_assignments_batch_member_id_key" ON "batch_calling_assignments"("batch_member_id");
CREATE INDEX "batch_calling_assignments_assigned_to_user_id_status_idx" ON "batch_calling_assignments"("assigned_to_user_id", "status");
CREATE INDEX "batch_calling_assignments_status_end_date_idx" ON "batch_calling_assignments"("status", "end_date");
CREATE INDEX "batch_calling_assignments_centre_id_status_idx" ON "batch_calling_assignments"("centre_id", "status");
CREATE TABLE "new_batch_members" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "centre_id" INTEGER NOT NULL,
    "category" TEXT NOT NULL,
    "batch_id" INTEGER NOT NULL,
    "mobile" TEXT NOT NULL,
    "joined_at" DATETIME NOT NULL,
    "removed_at" DATETIME,
    "changed_by" TEXT NOT NULL,
    "changed_at" DATETIME NOT NULL,
    "source" TEXT NOT NULL,
    CONSTRAINT "batch_members_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "batch_members_batch_id_fkey" FOREIGN KEY ("batch_id") REFERENCES "batches" ("id") ON DELETE RESTRICT ON UPDATE CASCADE
);
INSERT INTO "new_batch_members" ("batch_id", "centre_id", "id", "joined_at") SELECT "batch_id", "centre_id", "id", "joined_at" FROM "batch_members";
DROP TABLE "batch_members";
ALTER TABLE "new_batch_members" RENAME TO "batch_members";
CREATE INDEX "batch_members_centre_id_category_batch_id_removed_at_idx" ON "batch_members"("centre_id", "category", "batch_id", "removed_at");
CREATE INDEX "batch_members_centre_id_mobile_removed_at_idx" ON "batch_members"("centre_id", "mobile", "removed_at");
CREATE TABLE "new_batch_move_requests" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "registration_id" INTEGER NOT NULL,
    "requested_by_user_id" INTEGER NOT NULL,
    "original_caller_id" INTEGER NOT NULL,
    "centre_id" INTEGER NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'pending',
    "requested_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "resolved_at" DATETIME,
    CONSTRAINT "batch_move_requests_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "batch_move_requests_requested_by_user_id_fkey" FOREIGN KEY ("requested_by_user_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "batch_move_requests_original_caller_id_fkey" FOREIGN KEY ("original_caller_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "batch_move_requests_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);
INSERT INTO "new_batch_move_requests" ("centre_id", "id", "original_caller_id", "registration_id", "requested_at", "requested_by_user_id", "resolved_at", "status") SELECT "centre_id", "id", "original_caller_id", "registration_id", "requested_at", "requested_by_user_id", "resolved_at", "status" FROM "batch_move_requests";
DROP TABLE "batch_move_requests";
ALTER TABLE "new_batch_move_requests" RENAME TO "batch_move_requests";
CREATE INDEX "batch_move_requests_status_requested_at_idx" ON "batch_move_requests"("status", "requested_at");
CREATE INDEX "batch_move_requests_registration_id_idx" ON "batch_move_requests"("registration_id");
CREATE INDEX "batch_move_requests_centre_id_status_idx" ON "batch_move_requests"("centre_id", "status");
CREATE TABLE "new_batches" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "centre_id" INTEGER NOT NULL,
    "category" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "is_default" BOOLEAN NOT NULL DEFAULT false,
    "status" TEXT NOT NULL DEFAULT 'active',
    "last_synced_at" DATETIME NOT NULL,
    CONSTRAINT "batches_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);
INSERT INTO "new_batches" ("category", "id", "name") SELECT "category", "id", "name" FROM "batches";
DROP TABLE "batches";
ALTER TABLE "new_batches" RENAME TO "batches";
CREATE INDEX "batches_centre_id_category_status_idx" ON "batches"("centre_id", "category", "status");
CREATE UNIQUE INDEX "batches_centre_id_category_name_key" ON "batches"("centre_id", "category", "name");
CREATE TABLE "new_call_attempts" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "registration_id" INTEGER NOT NULL,
    "caller_id" INTEGER NOT NULL,
    "centre_id" INTEGER NOT NULL,
    "call_type" TEXT NOT NULL,
    "dropdown_value" TEXT NOT NULL,
    "remark" TEXT,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "call_attempts_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "call_attempts_caller_id_fkey" FOREIGN KEY ("caller_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "call_attempts_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);
INSERT INTO "new_call_attempts" ("call_type", "caller_id", "centre_id", "created_at", "dropdown_value", "id", "registration_id", "remark") SELECT "call_type", "caller_id", "centre_id", "created_at", "dropdown_value", "id", "registration_id", "remark" FROM "call_attempts";
DROP TABLE "call_attempts";
ALTER TABLE "new_call_attempts" RENAME TO "call_attempts";
CREATE INDEX "call_attempts_registration_id_call_type_idx" ON "call_attempts"("registration_id", "call_type");
CREATE INDEX "call_attempts_centre_id_idx" ON "call_attempts"("centre_id");
CREATE TABLE "new_calling_assignments" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "registration_id" INTEGER NOT NULL,
    "assigned_to_user_id" INTEGER NOT NULL,
    "centre_id" INTEGER NOT NULL,
    "assigned_by" TEXT NOT NULL,
    "start_date" DATETIME NOT NULL,
    "end_date" DATETIME NOT NULL,
    "status" TEXT NOT NULL,
    "assignment_source" TEXT NOT NULL DEFAULT 'normal_calling',
    CONSTRAINT "calling_assignments_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "calling_assignments_assigned_to_user_id_fkey" FOREIGN KEY ("assigned_to_user_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "calling_assignments_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);
INSERT INTO "new_calling_assignments" ("assigned_by", "assigned_to_user_id", "assignment_source", "centre_id", "end_date", "id", "registration_id", "start_date", "status") SELECT "assigned_by", "assigned_to_user_id", "assignment_source", "centre_id", "end_date", "id", "registration_id", "start_date", "status" FROM "calling_assignments";
DROP TABLE "calling_assignments";
ALTER TABLE "new_calling_assignments" RENAME TO "calling_assignments";
CREATE INDEX "calling_assignments_status_end_date_idx" ON "calling_assignments"("status", "end_date");
CREATE INDEX "calling_assignments_registration_id_status_idx" ON "calling_assignments"("registration_id", "status");
CREATE INDEX "calling_assignments_centre_id_idx" ON "calling_assignments"("centre_id");
CREATE TABLE "new_centres" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "name" TEXT NOT NULL,
    "code" TEXT NOT NULL,
    "allowed_categories" TEXT NOT NULL,
    "is_active" BOOLEAN NOT NULL DEFAULT true,
    "status" TEXT NOT NULL DEFAULT 'active',
    "enabled_youth" BOOLEAN NOT NULL DEFAULT true,
    "enabled_congregation" BOOLEAN NOT NULL DEFAULT true,
    "attendease_status" TEXT,
    "inactive_since" DATETIME,
    "last_synced_at" DATETIME,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" DATETIME NOT NULL
);
INSERT INTO "new_centres" ("allowed_categories", "attendease_status", "code", "created_at", "enabled_congregation", "enabled_youth", "id", "inactive_since", "is_active", "last_synced_at", "name", "updated_at") SELECT "allowed_categories", "attendease_status", "code", "created_at", "enabled_congregation", "enabled_youth", "id", "inactive_since", "is_active", "last_synced_at", "name", "updated_at" FROM "centres";
DROP TABLE "centres";
ALTER TABLE "new_centres" RENAME TO "centres";
CREATE UNIQUE INDEX "centres_code_key" ON "centres"("code");
CREATE INDEX "centres_is_active_idx" ON "centres"("is_active");
CREATE INDEX "centres_status_idx" ON "centres"("status");
CREATE TABLE "new_followups" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "registration_id" INTEGER NOT NULL,
    "owner_caller_id" INTEGER NOT NULL,
    "centre_id" INTEGER NOT NULL,
    "status" TEXT NOT NULL,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "followups_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "followups_owner_caller_id_fkey" FOREIGN KEY ("owner_caller_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "followups_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);
INSERT INTO "new_followups" ("centre_id", "created_at", "id", "owner_caller_id", "registration_id", "status") SELECT "centre_id", "created_at", "id", "owner_caller_id", "registration_id", "status" FROM "followups";
DROP TABLE "followups";
ALTER TABLE "new_followups" RENAME TO "followups";
CREATE UNIQUE INDEX "followups_registration_id_key" ON "followups"("registration_id");
CREATE INDEX "followups_centre_id_status_idx" ON "followups"("centre_id", "status");
CREATE INDEX "followups_status_idx" ON "followups"("status");
CREATE TABLE "new_my_attendance" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "registration_id" INTEGER NOT NULL,
    "owner_caller_id" INTEGER NOT NULL,
    "attendance_match_id" INTEGER NOT NULL,
    "centre_id" INTEGER NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'active',
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" DATETIME NOT NULL,
    CONSTRAINT "my_attendance_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "my_attendance_owner_caller_id_fkey" FOREIGN KEY ("owner_caller_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "my_attendance_attendance_match_id_fkey" FOREIGN KEY ("attendance_match_id") REFERENCES "attendance_matches" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "my_attendance_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);
INSERT INTO "new_my_attendance" ("attendance_match_id", "centre_id", "created_at", "id", "owner_caller_id", "registration_id", "status", "updated_at") SELECT "attendance_match_id", "centre_id", "created_at", "id", "owner_caller_id", "registration_id", "status", "updated_at" FROM "my_attendance";
DROP TABLE "my_attendance";
ALTER TABLE "new_my_attendance" RENAME TO "my_attendance";
CREATE UNIQUE INDEX "my_attendance_registration_id_key" ON "my_attendance"("registration_id");
CREATE UNIQUE INDEX "my_attendance_attendance_match_id_key" ON "my_attendance"("attendance_match_id");
CREATE INDEX "my_attendance_owner_caller_id_status_idx" ON "my_attendance"("owner_caller_id", "status");
CREATE INDEX "my_attendance_centre_id_status_idx" ON "my_attendance"("centre_id", "status");
PRAGMA foreign_keys=ON;
PRAGMA defer_foreign_keys=OFF;

-- CreateIndex
CREATE INDEX "sessions_batch_id_idx" ON "sessions"("batch_id");

-- CreateIndex
CREATE INDEX "sessions_centre_id_idx" ON "sessions"("centre_id");

-- CreateIndex
CREATE INDEX "batch_member_audit_batch_member_id_idx" ON "batch_member_audit"("batch_member_id");

-- CreateIndex
CREATE INDEX "outbox_status_next_attempt_idx" ON "outbox"("status", "next_attempt");
