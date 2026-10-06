-- CreateTable
CREATE TABLE "centre_config" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "centre_name" TEXT NOT NULL,
    "centre_type" TEXT NOT NULL,
    "calling_active_days" INTEGER NOT NULL,
    "attendance_sheet_id" TEXT NOT NULL,
    "attendance_sheet_locked" BOOLEAN NOT NULL DEFAULT false,
    "summary_sheet_name" TEXT NOT NULL DEFAULT 'Summary',
    "attendance_webapp_url" TEXT NOT NULL,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" DATETIME NOT NULL
);

-- CreateTable
CREATE TABLE "users" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "name" TEXT NOT NULL,
    "mobile" TEXT NOT NULL,
    "role" TEXT NOT NULL,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- CreateTable
CREATE TABLE "user_permissions" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "user_id" INTEGER NOT NULL,
    "permission_key" TEXT NOT NULL,
    "is_enabled" BOOLEAN NOT NULL DEFAULT true,
    CONSTRAINT "user_permissions_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "registrations" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "name" TEXT NOT NULL,
    "mobile" TEXT NOT NULL,
    "normalized_mobile" TEXT NOT NULL,
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
    CONSTRAINT "registrations_earlier_managed_by_fkey" FOREIGN KEY ("earlier_managed_by") REFERENCES "users" ("id") ON DELETE SET NULL ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "calling_assignments" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "registration_id" INTEGER NOT NULL,
    "assigned_to_user_id" INTEGER NOT NULL,
    "assigned_by" TEXT NOT NULL,
    "start_date" DATETIME NOT NULL,
    "end_date" DATETIME NOT NULL,
    "status" TEXT NOT NULL,
    "assignment_source" TEXT NOT NULL DEFAULT 'normal_calling',
    CONSTRAINT "calling_assignments_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "calling_assignments_assigned_to_user_id_fkey" FOREIGN KEY ("assigned_to_user_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "call_attempts" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "registration_id" INTEGER NOT NULL,
    "caller_id" INTEGER NOT NULL,
    "call_type" TEXT NOT NULL,
    "dropdown_value" TEXT NOT NULL,
    "remark" TEXT,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "call_attempts_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "call_attempts_caller_id_fkey" FOREIGN KEY ("caller_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "followups" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "registration_id" INTEGER NOT NULL,
    "owner_caller_id" INTEGER NOT NULL,
    "status" TEXT NOT NULL,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "followups_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "followups_owner_caller_id_fkey" FOREIGN KEY ("owner_caller_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "dropdown_options" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "label" TEXT NOT NULL,
    "context" TEXT NOT NULL,
    "category" TEXT NOT NULL DEFAULT 'both',
    "sort_order" INTEGER NOT NULL DEFAULT 0,
    "is_active" BOOLEAN NOT NULL DEFAULT true
);

-- CreateTable
CREATE TABLE "dropdown_mappings" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "dropdown_id" INTEGER NOT NULL,
    "action" TEXT NOT NULL,
    CONSTRAINT "dropdown_mappings_dropdown_id_fkey" FOREIGN KEY ("dropdown_id") REFERENCES "dropdown_options" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "removed_records" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "registration_id" INTEGER NOT NULL,
    "reason" TEXT NOT NULL,
    "removed_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "removed_records_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "attendance_summary" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "normalized_mobile" TEXT NOT NULL,
    "mobile" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "address" TEXT,
    "occupation" TEXT,
    "total_count" INTEGER NOT NULL DEFAULT 0,
    "last_synced_at" DATETIME
);

-- CreateTable
CREATE TABLE "attendance_sync_logs" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "started_by_user_id" INTEGER NOT NULL,
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
    CONSTRAINT "attendance_sync_logs_started_by_user_id_fkey" FOREIGN KEY ("started_by_user_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "attendance_matches" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "registration_id" INTEGER NOT NULL,
    "normalized_mobile" TEXT NOT NULL,
    "matched_caller_id" INTEGER,
    "attendance_total_count" INTEGER NOT NULL DEFAULT 0,
    "first_matched_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "last_matched_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "status" TEXT NOT NULL DEFAULT 'new_match',
    CONSTRAINT "attendance_matches_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "attendance_matches_matched_caller_id_fkey" FOREIGN KEY ("matched_caller_id") REFERENCES "users" ("id") ON DELETE SET NULL ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "my_attendance" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "registration_id" INTEGER NOT NULL,
    "owner_caller_id" INTEGER NOT NULL,
    "attendance_match_id" INTEGER NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'active',
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" DATETIME NOT NULL,
    CONSTRAINT "my_attendance_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "my_attendance_owner_caller_id_fkey" FOREIGN KEY ("owner_caller_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "my_attendance_attendance_match_id_fkey" FOREIGN KEY ("attendance_match_id") REFERENCES "attendance_matches" ("id") ON DELETE RESTRICT ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "batch_move_requests" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "registration_id" INTEGER NOT NULL,
    "requested_by_user_id" INTEGER NOT NULL,
    "original_caller_id" INTEGER NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'pending',
    "requested_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "resolved_at" DATETIME,
    CONSTRAINT "batch_move_requests_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "batch_move_requests_requested_by_user_id_fkey" FOREIGN KEY ("requested_by_user_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "batch_move_requests_original_caller_id_fkey" FOREIGN KEY ("original_caller_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "batches" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "name" TEXT NOT NULL,
    "category" TEXT NOT NULL,
    "is_active" BOOLEAN NOT NULL DEFAULT true,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" DATETIME NOT NULL
);

-- CreateTable
CREATE TABLE "batch_members" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "registration_id" INTEGER NOT NULL,
    "batch_id" INTEGER NOT NULL,
    "entry_source" TEXT NOT NULL,
    "original_caller_id" INTEGER,
    "current_batch_caller_id" INTEGER,
    "status" TEXT NOT NULL DEFAULT 'active',
    "joined_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" DATETIME NOT NULL,
    CONSTRAINT "batch_members_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "batch_members_batch_id_fkey" FOREIGN KEY ("batch_id") REFERENCES "batches" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "batch_members_original_caller_id_fkey" FOREIGN KEY ("original_caller_id") REFERENCES "users" ("id") ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT "batch_members_current_batch_caller_id_fkey" FOREIGN KEY ("current_batch_caller_id") REFERENCES "users" ("id") ON DELETE SET NULL ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "batch_calling_assignments" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "batch_member_id" INTEGER NOT NULL,
    "assigned_to_user_id" INTEGER NOT NULL,
    "assigned_by_user_id" INTEGER NOT NULL,
    "assignment_mode" TEXT NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'active',
    "start_date" DATETIME NOT NULL,
    "end_date" DATETIME NOT NULL,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" DATETIME NOT NULL,
    CONSTRAINT "batch_calling_assignments_batch_member_id_fkey" FOREIGN KEY ("batch_member_id") REFERENCES "batch_members" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "batch_calling_assignments_assigned_to_user_id_fkey" FOREIGN KEY ("assigned_to_user_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "batch_calling_assignments_assigned_by_user_id_fkey" FOREIGN KEY ("assigned_by_user_id") REFERENCES "users" ("id") ON DELETE RESTRICT ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "notifications" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "user_id" INTEGER NOT NULL,
    "registration_id" INTEGER,
    "title" TEXT NOT NULL,
    "message" TEXT NOT NULL,
    "type" TEXT NOT NULL,
    "is_read" BOOLEAN NOT NULL DEFAULT false,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "notifications_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "notifications_registration_id_fkey" FOREIGN KEY ("registration_id") REFERENCES "registrations" ("id") ON DELETE SET NULL ON UPDATE CASCADE
);

-- CreateIndex
CREATE UNIQUE INDEX "users_mobile_key" ON "users"("mobile");

-- CreateIndex
CREATE INDEX "user_permissions_permission_key_is_enabled_idx" ON "user_permissions"("permission_key", "is_enabled");

-- CreateIndex
CREATE UNIQUE INDEX "user_permissions_user_id_permission_key_key" ON "user_permissions"("user_id", "permission_key");

-- CreateIndex
CREATE UNIQUE INDEX "registrations_normalized_mobile_key" ON "registrations"("normalized_mobile");

-- CreateIndex
CREATE INDEX "registrations_category_current_stage_active_queue_idx" ON "registrations"("category", "current_stage", "active_queue");

-- CreateIndex
CREATE INDEX "registrations_normalized_mobile_idx" ON "registrations"("normalized_mobile");

-- CreateIndex
CREATE INDEX "calling_assignments_status_end_date_idx" ON "calling_assignments"("status", "end_date");

-- CreateIndex
CREATE INDEX "calling_assignments_registration_id_status_idx" ON "calling_assignments"("registration_id", "status");

-- CreateIndex
CREATE INDEX "call_attempts_registration_id_call_type_idx" ON "call_attempts"("registration_id", "call_type");

-- CreateIndex
CREATE UNIQUE INDEX "followups_registration_id_key" ON "followups"("registration_id");

-- CreateIndex
CREATE INDEX "followups_status_idx" ON "followups"("status");

-- CreateIndex
CREATE INDEX "dropdown_options_context_category_is_active_idx" ON "dropdown_options"("context", "category", "is_active");

-- CreateIndex
CREATE UNIQUE INDEX "dropdown_options_label_context_category_key" ON "dropdown_options"("label", "context", "category");

-- CreateIndex
CREATE UNIQUE INDEX "dropdown_mappings_dropdown_id_key" ON "dropdown_mappings"("dropdown_id");

-- CreateIndex
CREATE UNIQUE INDEX "removed_records_registration_id_key" ON "removed_records"("registration_id");

-- CreateIndex
CREATE UNIQUE INDEX "attendance_summary_normalized_mobile_key" ON "attendance_summary"("normalized_mobile");

-- CreateIndex
CREATE INDEX "attendance_sync_logs_status_started_at_idx" ON "attendance_sync_logs"("status", "started_at");

-- CreateIndex
CREATE UNIQUE INDEX "attendance_matches_registration_id_key" ON "attendance_matches"("registration_id");

-- CreateIndex
CREATE INDEX "attendance_matches_normalized_mobile_idx" ON "attendance_matches"("normalized_mobile");

-- CreateIndex
CREATE INDEX "attendance_matches_status_idx" ON "attendance_matches"("status");

-- CreateIndex
CREATE UNIQUE INDEX "my_attendance_registration_id_key" ON "my_attendance"("registration_id");

-- CreateIndex
CREATE UNIQUE INDEX "my_attendance_attendance_match_id_key" ON "my_attendance"("attendance_match_id");

-- CreateIndex
CREATE INDEX "my_attendance_owner_caller_id_status_idx" ON "my_attendance"("owner_caller_id", "status");

-- CreateIndex
CREATE INDEX "batch_move_requests_status_requested_at_idx" ON "batch_move_requests"("status", "requested_at");

-- CreateIndex
CREATE INDEX "batch_move_requests_registration_id_idx" ON "batch_move_requests"("registration_id");

-- CreateIndex
CREATE INDEX "batches_category_is_active_idx" ON "batches"("category", "is_active");

-- CreateIndex
CREATE UNIQUE INDEX "batch_members_registration_id_key" ON "batch_members"("registration_id");

-- CreateIndex
CREATE INDEX "batch_members_batch_id_status_idx" ON "batch_members"("batch_id", "status");

-- CreateIndex
CREATE INDEX "batch_members_current_batch_caller_id_status_idx" ON "batch_members"("current_batch_caller_id", "status");

-- CreateIndex
CREATE UNIQUE INDEX "batch_calling_assignments_batch_member_id_key" ON "batch_calling_assignments"("batch_member_id");

-- CreateIndex
CREATE INDEX "batch_calling_assignments_assigned_to_user_id_status_idx" ON "batch_calling_assignments"("assigned_to_user_id", "status");

-- CreateIndex
CREATE INDEX "batch_calling_assignments_status_end_date_idx" ON "batch_calling_assignments"("status", "end_date");

-- CreateIndex
CREATE INDEX "notifications_user_id_is_read_created_at_idx" ON "notifications"("user_id", "is_read", "created_at");

-- CreateIndex
CREATE INDEX "notifications_registration_id_idx" ON "notifications"("registration_id");

