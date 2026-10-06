/*
  Warnings:

  - Added the required column `centre_id` to the `registrations` table without a default value. This is not possible if the table is not empty.

*/
-- CreateTable
CREATE TABLE "centres" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "name" TEXT NOT NULL,
    "code" TEXT NOT NULL,
    "allowed_categories" TEXT NOT NULL,
    "is_active" BOOLEAN NOT NULL DEFAULT true,
    "enabled_youth" BOOLEAN NOT NULL DEFAULT true,
    "enabled_congregation" BOOLEAN NOT NULL DEFAULT true,
    "attendease_status" TEXT,
    "inactive_since" DATETIME,
    "last_synced_at" DATETIME,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" DATETIME NOT NULL
);

-- CreateTable
CREATE TABLE "user_centre_access" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "user_id" INTEGER NOT NULL,
    "centre_id" INTEGER NOT NULL,
    "is_admin" BOOLEAN NOT NULL DEFAULT false,
    "display_name" TEXT,
    "last_seen_at" DATETIME,
    "last_synced_at" DATETIME,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "user_centre_access_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "user_centre_access_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "attendance_batches" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "centre_id" INTEGER,
    "name" TEXT NOT NULL,
    "category" TEXT NOT NULL,
    "source_sheet_name" TEXT NOT NULL,
    "is_active" BOOLEAN NOT NULL DEFAULT true,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" DATETIME NOT NULL,
    CONSTRAINT "attendance_batches_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE SET NULL ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "attendance_sessions" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "centre_id" INTEGER,
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
    CONSTRAINT "attendance_sessions_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT "attendance_sessions_attendance_batch_id_fkey" FOREIGN KEY ("attendance_batch_id") REFERENCES "attendance_batches" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "attendance_sessions_imported_by_user_id_fkey" FOREIGN KEY ("imported_by_user_id") REFERENCES "users" ("id") ON DELETE SET NULL ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "attendance_import_logs" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "centre_id" INTEGER,
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
    CONSTRAINT "attendance_import_logs_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT "attendance_import_logs_attendance_session_id_fkey" FOREIGN KEY ("attendance_session_id") REFERENCES "attendance_sessions" ("id") ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT "attendance_import_logs_started_by_user_id_fkey" FOREIGN KEY ("started_by_user_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "centre_stats_snapshots" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "centre_id" INTEGER NOT NULL,
    "snapshot_at" DATETIME NOT NULL,
    "stat_key" TEXT NOT NULL,
    "stat_value" INTEGER NOT NULL,
    CONSTRAINT "centre_stats_snapshots_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);

-- RedefineTables
PRAGMA defer_foreign_keys=ON;
PRAGMA foreign_keys=OFF;
CREATE TABLE "new_attendance_matches" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "registration_id" INTEGER NOT NULL,
    "centre_id" INTEGER,
    "normalized_mobile" TEXT NOT NULL,
    "matched_caller_id" INTEGER,
    "attendance_total_count" INTEGER NOT NULL DEFAULT 0,
    "first_matched_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "last_matched_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "status" TEXT NOT NULL DEFAULT 'new_match',
    CONSTRAINT "attendance_matches_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "attendance_matches_matched_caller_id_fkey" FOREIGN KEY ("matched_caller_id") REFERENCES "users" ("id") ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT "attendance_matches_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE SET NULL ON UPDATE CASCADE
);
INSERT INTO "new_attendance_matches" ("attendance_total_count", "first_matched_at", "id", "last_matched_at", "matched_caller_id", "normalized_mobile", "registration_id", "status") SELECT "attendance_total_count", "first_matched_at", "id", "last_matched_at", "matched_caller_id", "normalized_mobile", "registration_id", "status" FROM "attendance_matches";
DROP TABLE "attendance_matches";
ALTER TABLE "new_attendance_matches" RENAME TO "attendance_matches";
CREATE UNIQUE INDEX "attendance_matches_registration_id_key" ON "attendance_matches"("registration_id");
CREATE INDEX "attendance_matches_centre_id_status_idx" ON "attendance_matches"("centre_id", "status");
CREATE INDEX "attendance_matches_normalized_mobile_idx" ON "attendance_matches"("normalized_mobile");
CREATE INDEX "attendance_matches_status_idx" ON "attendance_matches"("status");
CREATE TABLE "new_attendance_sync_logs" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "started_by_user_id" INTEGER NOT NULL,
    "centre_id" INTEGER,
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
    CONSTRAINT "attendance_sync_logs_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE SET NULL ON UPDATE CASCADE
);
INSERT INTO "new_attendance_sync_logs" ("already_processed_count", "completed_at", "error_count", "error_message", "id", "matched_count", "newly_matched_count", "skipped_count", "started_at", "started_by_user_id", "status", "total_checked") SELECT "already_processed_count", "completed_at", "error_count", "error_message", "id", "matched_count", "newly_matched_count", "skipped_count", "started_at", "started_by_user_id", "status", "total_checked" FROM "attendance_sync_logs";
DROP TABLE "attendance_sync_logs";
ALTER TABLE "new_attendance_sync_logs" RENAME TO "attendance_sync_logs";
CREATE INDEX "attendance_sync_logs_centre_id_started_at_idx" ON "attendance_sync_logs"("centre_id", "started_at");
CREATE INDEX "attendance_sync_logs_status_started_at_idx" ON "attendance_sync_logs"("status", "started_at");
CREATE TABLE "new_batch_calling_assignments" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "batch_member_id" INTEGER NOT NULL,
    "assigned_to_user_id" INTEGER NOT NULL,
    "assigned_by_user_id" INTEGER NOT NULL,
    "centre_id" INTEGER,
    "assignment_mode" TEXT NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'active',
    "start_date" DATETIME NOT NULL,
    "end_date" DATETIME NOT NULL,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" DATETIME NOT NULL,
    CONSTRAINT "batch_calling_assignments_batch_member_id_fkey" FOREIGN KEY ("batch_member_id") REFERENCES "batch_members" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "batch_calling_assignments_assigned_to_user_id_fkey" FOREIGN KEY ("assigned_to_user_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "batch_calling_assignments_assigned_by_user_id_fkey" FOREIGN KEY ("assigned_by_user_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "batch_calling_assignments_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE SET NULL ON UPDATE CASCADE
);
INSERT INTO "new_batch_calling_assignments" ("assigned_by_user_id", "assigned_to_user_id", "assignment_mode", "batch_member_id", "created_at", "end_date", "id", "start_date", "status", "updated_at") SELECT "assigned_by_user_id", "assigned_to_user_id", "assignment_mode", "batch_member_id", "created_at", "end_date", "id", "start_date", "status", "updated_at" FROM "batch_calling_assignments";
DROP TABLE "batch_calling_assignments";
ALTER TABLE "new_batch_calling_assignments" RENAME TO "batch_calling_assignments";
CREATE UNIQUE INDEX "batch_calling_assignments_batch_member_id_key" ON "batch_calling_assignments"("batch_member_id");
CREATE INDEX "batch_calling_assignments_assigned_to_user_id_status_idx" ON "batch_calling_assignments"("assigned_to_user_id", "status");
CREATE INDEX "batch_calling_assignments_status_end_date_idx" ON "batch_calling_assignments"("status", "end_date");
CREATE INDEX "batch_calling_assignments_centre_id_status_idx" ON "batch_calling_assignments"("centre_id", "status");
CREATE TABLE "new_batch_members" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "registration_id" INTEGER NOT NULL,
    "batch_id" INTEGER NOT NULL,
    "entry_source" TEXT NOT NULL,
    "original_caller_id" INTEGER,
    "current_batch_caller_id" INTEGER,
    "centre_id" INTEGER,
    "status" TEXT NOT NULL DEFAULT 'active',
    "joined_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" DATETIME NOT NULL,
    CONSTRAINT "batch_members_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "batch_members_batch_id_fkey" FOREIGN KEY ("batch_id") REFERENCES "batches" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "batch_members_original_caller_id_fkey" FOREIGN KEY ("original_caller_id") REFERENCES "users" ("id") ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT "batch_members_current_batch_caller_id_fkey" FOREIGN KEY ("current_batch_caller_id") REFERENCES "users" ("id") ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT "batch_members_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE SET NULL ON UPDATE CASCADE
);
INSERT INTO "new_batch_members" ("batch_id", "current_batch_caller_id", "entry_source", "id", "joined_at", "original_caller_id", "registration_id", "status", "updated_at") SELECT "batch_id", "current_batch_caller_id", "entry_source", "id", "joined_at", "original_caller_id", "registration_id", "status", "updated_at" FROM "batch_members";
DROP TABLE "batch_members";
ALTER TABLE "new_batch_members" RENAME TO "batch_members";
CREATE UNIQUE INDEX "batch_members_registration_id_key" ON "batch_members"("registration_id");
CREATE INDEX "batch_members_batch_id_status_idx" ON "batch_members"("batch_id", "status");
CREATE INDEX "batch_members_current_batch_caller_id_status_idx" ON "batch_members"("current_batch_caller_id", "status");
CREATE INDEX "batch_members_centre_id_status_idx" ON "batch_members"("centre_id", "status");
CREATE TABLE "new_batch_move_requests" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "registration_id" INTEGER NOT NULL,
    "requested_by_user_id" INTEGER NOT NULL,
    "original_caller_id" INTEGER NOT NULL,
    "centre_id" INTEGER,
    "status" TEXT NOT NULL DEFAULT 'pending',
    "requested_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "resolved_at" DATETIME,
    CONSTRAINT "batch_move_requests_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "batch_move_requests_requested_by_user_id_fkey" FOREIGN KEY ("requested_by_user_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "batch_move_requests_original_caller_id_fkey" FOREIGN KEY ("original_caller_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "batch_move_requests_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE SET NULL ON UPDATE CASCADE
);
INSERT INTO "new_batch_move_requests" ("id", "original_caller_id", "registration_id", "requested_at", "requested_by_user_id", "resolved_at", "status") SELECT "id", "original_caller_id", "registration_id", "requested_at", "requested_by_user_id", "resolved_at", "status" FROM "batch_move_requests";
DROP TABLE "batch_move_requests";
ALTER TABLE "new_batch_move_requests" RENAME TO "batch_move_requests";
CREATE INDEX "batch_move_requests_status_requested_at_idx" ON "batch_move_requests"("status", "requested_at");
CREATE INDEX "batch_move_requests_registration_id_idx" ON "batch_move_requests"("registration_id");
CREATE INDEX "batch_move_requests_centre_id_status_idx" ON "batch_move_requests"("centre_id", "status");
CREATE TABLE "new_call_attempts" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "registration_id" INTEGER NOT NULL,
    "caller_id" INTEGER NOT NULL,
    "centre_id" INTEGER,
    "call_type" TEXT NOT NULL,
    "dropdown_value" TEXT NOT NULL,
    "remark" TEXT,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "call_attempts_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "call_attempts_caller_id_fkey" FOREIGN KEY ("caller_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "call_attempts_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE SET NULL ON UPDATE CASCADE
);
INSERT INTO "new_call_attempts" ("call_type", "caller_id", "created_at", "dropdown_value", "id", "registration_id", "remark") SELECT "call_type", "caller_id", "created_at", "dropdown_value", "id", "registration_id", "remark" FROM "call_attempts";
DROP TABLE "call_attempts";
ALTER TABLE "new_call_attempts" RENAME TO "call_attempts";
CREATE INDEX "call_attempts_registration_id_call_type_idx" ON "call_attempts"("registration_id", "call_type");
CREATE INDEX "call_attempts_centre_id_idx" ON "call_attempts"("centre_id");
CREATE TABLE "new_calling_assignments" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "registration_id" INTEGER NOT NULL,
    "assigned_to_user_id" INTEGER NOT NULL,
    "centre_id" INTEGER,
    "assigned_by" TEXT NOT NULL,
    "start_date" DATETIME NOT NULL,
    "end_date" DATETIME NOT NULL,
    "status" TEXT NOT NULL,
    "assignment_source" TEXT NOT NULL DEFAULT 'normal_calling',
    CONSTRAINT "calling_assignments_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "calling_assignments_assigned_to_user_id_fkey" FOREIGN KEY ("assigned_to_user_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "calling_assignments_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE SET NULL ON UPDATE CASCADE
);
INSERT INTO "new_calling_assignments" ("assigned_by", "assigned_to_user_id", "assignment_source", "end_date", "id", "registration_id", "start_date", "status") SELECT "assigned_by", "assigned_to_user_id", "assignment_source", "end_date", "id", "registration_id", "start_date", "status" FROM "calling_assignments";
DROP TABLE "calling_assignments";
ALTER TABLE "new_calling_assignments" RENAME TO "calling_assignments";
CREATE INDEX "calling_assignments_status_end_date_idx" ON "calling_assignments"("status", "end_date");
CREATE INDEX "calling_assignments_registration_id_status_idx" ON "calling_assignments"("registration_id", "status");
CREATE INDEX "calling_assignments_centre_id_idx" ON "calling_assignments"("centre_id");
CREATE TABLE "new_centre_config" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "centre_id" INTEGER,
    "centre_name" TEXT NOT NULL,
    "centre_type" TEXT NOT NULL,
    "calling_active_days" INTEGER NOT NULL,
    "attendance_sheet_id" TEXT NOT NULL,
    "attendance_sheet_locked" BOOLEAN NOT NULL DEFAULT false,
    "summary_sheet_name" TEXT NOT NULL DEFAULT 'Summary',
    "attendance_webapp_url" TEXT NOT NULL,
    "enabled_tabs_modules" JSONB,
    "dropdown_settings" JSONB,
    "attendance_settings" JSONB,
    "pool_settings" JSONB,
    "permission_settings" JSONB,
    "ui_layout_settings" JSONB,
    "custom_tabs_modules" JSONB,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" DATETIME NOT NULL,
    CONSTRAINT "centre_config_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);
INSERT INTO "new_centre_config" ("attendance_sheet_id", "attendance_sheet_locked", "attendance_webapp_url", "calling_active_days", "centre_name", "centre_type", "created_at", "id", "summary_sheet_name", "updated_at") SELECT "attendance_sheet_id", "attendance_sheet_locked", "attendance_webapp_url", "calling_active_days", "centre_name", "centre_type", "created_at", "id", "summary_sheet_name", "updated_at" FROM "centre_config";
DROP TABLE "centre_config";
ALTER TABLE "new_centre_config" RENAME TO "centre_config";
CREATE UNIQUE INDEX "centre_config_centre_id_key" ON "centre_config"("centre_id");
CREATE TABLE "new_dropdown_options" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "centre_id" INTEGER,
    "label" TEXT NOT NULL,
    "context" TEXT NOT NULL,
    "category" TEXT NOT NULL DEFAULT 'both',
    "sort_order" INTEGER NOT NULL DEFAULT 0,
    "is_active" BOOLEAN NOT NULL DEFAULT true,
    CONSTRAINT "dropdown_options_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);
INSERT INTO "new_dropdown_options" ("category", "context", "id", "is_active", "label", "sort_order") SELECT "category", "context", "id", "is_active", "label", "sort_order" FROM "dropdown_options";
DROP TABLE "dropdown_options";
ALTER TABLE "new_dropdown_options" RENAME TO "dropdown_options";
CREATE INDEX "dropdown_options_centre_id_context_category_is_active_idx" ON "dropdown_options"("centre_id", "context", "category", "is_active");
CREATE UNIQUE INDEX "dropdown_options_centre_id_label_context_category_key" ON "dropdown_options"("centre_id", "label", "context", "category");
CREATE TABLE "new_followups" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "registration_id" INTEGER NOT NULL,
    "owner_caller_id" INTEGER NOT NULL,
    "centre_id" INTEGER,
    "status" TEXT NOT NULL,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "followups_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "followups_owner_caller_id_fkey" FOREIGN KEY ("owner_caller_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "followups_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE SET NULL ON UPDATE CASCADE
);
INSERT INTO "new_followups" ("created_at", "id", "owner_caller_id", "registration_id", "status") SELECT "created_at", "id", "owner_caller_id", "registration_id", "status" FROM "followups";
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
    "centre_id" INTEGER,
    "status" TEXT NOT NULL DEFAULT 'active',
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" DATETIME NOT NULL,
    CONSTRAINT "my_attendance_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "my_attendance_owner_caller_id_fkey" FOREIGN KEY ("owner_caller_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "my_attendance_attendance_match_id_fkey" FOREIGN KEY ("attendance_match_id") REFERENCES "attendance_matches" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "my_attendance_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE SET NULL ON UPDATE CASCADE
);
INSERT INTO "new_my_attendance" ("attendance_match_id", "created_at", "id", "owner_caller_id", "registration_id", "status", "updated_at") SELECT "attendance_match_id", "created_at", "id", "owner_caller_id", "registration_id", "status", "updated_at" FROM "my_attendance";
DROP TABLE "my_attendance";
ALTER TABLE "new_my_attendance" RENAME TO "my_attendance";
CREATE UNIQUE INDEX "my_attendance_registration_id_key" ON "my_attendance"("registration_id");
CREATE UNIQUE INDEX "my_attendance_attendance_match_id_key" ON "my_attendance"("attendance_match_id");
CREATE INDEX "my_attendance_owner_caller_id_status_idx" ON "my_attendance"("owner_caller_id", "status");
CREATE INDEX "my_attendance_centre_id_status_idx" ON "my_attendance"("centre_id", "status");
CREATE TABLE "new_registrations" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "name" TEXT NOT NULL,
    "mobile" TEXT NOT NULL,
    "normalized_mobile" TEXT NOT NULL,
    "centre_id" INTEGER NOT NULL,
    "address" TEXT,
    "occupation" TEXT,
    "category" TEXT NOT NULL,
    "current_stage" TEXT NOT NULL DEFAULT 'registered',
    "active_queue" TEXT NOT NULL DEFAULT 'none',
    "original_caller_id" INTEGER,
    "first_connected_by" INTEGER,
    "relationship_owner_id" INTEGER,
    "last_handled_by" INTEGER,
    "current_assigned_caller_id" INTEGER,
    "current_batch_caller_id" INTEGER,
    "earlier_managed_by" INTEGER,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "registrations_original_caller_id_fkey" FOREIGN KEY ("original_caller_id") REFERENCES "users" ("id") ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT "registrations_first_connected_by_fkey" FOREIGN KEY ("first_connected_by") REFERENCES "users" ("id") ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT "registrations_relationship_owner_id_fkey" FOREIGN KEY ("relationship_owner_id") REFERENCES "users" ("id") ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT "registrations_last_handled_by_fkey" FOREIGN KEY ("last_handled_by") REFERENCES "users" ("id") ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT "registrations_current_assigned_caller_id_fkey" FOREIGN KEY ("current_assigned_caller_id") REFERENCES "users" ("id") ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT "registrations_current_batch_caller_id_fkey" FOREIGN KEY ("current_batch_caller_id") REFERENCES "users" ("id") ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT "registrations_earlier_managed_by_fkey" FOREIGN KEY ("earlier_managed_by") REFERENCES "users" ("id") ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT "registrations_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);
INSERT INTO "new_registrations" ("active_queue", "address", "category", "created_at", "current_assigned_caller_id", "current_batch_caller_id", "current_stage", "earlier_managed_by", "first_connected_by", "id", "last_handled_by", "mobile", "name", "normalized_mobile", "occupation", "original_caller_id", "relationship_owner_id") SELECT "active_queue", "address", "category", "created_at", "current_assigned_caller_id", "current_batch_caller_id", "current_stage", "earlier_managed_by", "first_connected_by", "id", "last_handled_by", "mobile", "name", "normalized_mobile", "occupation", "original_caller_id", "relationship_owner_id" FROM "registrations";
DROP TABLE "registrations";
ALTER TABLE "new_registrations" RENAME TO "registrations";
CREATE INDEX "registrations_centre_id_category_current_stage_active_queue_idx" ON "registrations"("centre_id", "category", "current_stage", "active_queue");
CREATE UNIQUE INDEX "registrations_normalized_mobile_centre_id_key" ON "registrations"("normalized_mobile", "centre_id");
CREATE TABLE "new_users" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "name" TEXT NOT NULL,
    "mobile" TEXT NOT NULL,
    "role" TEXT NOT NULL,
    "approval_status" TEXT NOT NULL DEFAULT 'pending',
    "is_active" BOOLEAN NOT NULL DEFAULT false,
    "attendance_system_access" BOOLEAN NOT NULL DEFAULT false,
    "password_hash" TEXT,
    "centre_id" INTEGER,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "users_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE SET NULL ON UPDATE CASCADE
);
INSERT INTO "new_users" ("created_at", "id", "mobile", "name", "role") SELECT "created_at", "id", "mobile", "name", "role" FROM "users";
DROP TABLE "users";
ALTER TABLE "new_users" RENAME TO "users";
CREATE UNIQUE INDEX "users_mobile_key" ON "users"("mobile");
CREATE INDEX "users_centre_id_idx" ON "users"("centre_id");
PRAGMA foreign_keys=ON;
PRAGMA defer_foreign_keys=OFF;

-- CreateIndex
CREATE UNIQUE INDEX "centres_code_key" ON "centres"("code");

-- CreateIndex
CREATE INDEX "centres_is_active_idx" ON "centres"("is_active");

-- CreateIndex
CREATE INDEX "user_centre_access_centre_id_idx" ON "user_centre_access"("centre_id");

-- CreateIndex
CREATE UNIQUE INDEX "user_centre_access_user_id_centre_id_key" ON "user_centre_access"("user_id", "centre_id");

-- CreateIndex
CREATE INDEX "attendance_batches_centre_id_category_is_active_idx" ON "attendance_batches"("centre_id", "category", "is_active");

-- CreateIndex
CREATE UNIQUE INDEX "attendance_batches_centre_id_name_category_key" ON "attendance_batches"("centre_id", "name", "category");

-- CreateIndex
CREATE INDEX "attendance_sessions_centre_id_import_status_idx" ON "attendance_sessions"("centre_id", "import_status");

-- CreateIndex
CREATE UNIQUE INDEX "attendance_sessions_centre_id_session_key_key" ON "attendance_sessions"("centre_id", "session_key");

-- CreateIndex
CREATE INDEX "attendance_import_logs_centre_id_started_at_idx" ON "attendance_import_logs"("centre_id", "started_at");

-- CreateIndex
CREATE INDEX "attendance_import_logs_attendance_session_id_started_at_idx" ON "attendance_import_logs"("attendance_session_id", "started_at");

-- CreateIndex
CREATE INDEX "centre_stats_snapshots_centre_id_stat_key_snapshot_at_idx" ON "centre_stats_snapshots"("centre_id", "stat_key", "snapshot_at");
