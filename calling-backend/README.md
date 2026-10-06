# Calling Management Backend

Backend foundation for the single-centre calling management flow:

`Registration -> Calling -> Follow-up`

This project now supports attendance sync, My Attendance, batch move approval, batch calling foundation, and downloadable CSV exports while keeping real batch assignment future-safe.

## Stack

- Node.js
- Express
- TypeScript
- Prisma
- Zod

## Folder Structure

```text
prisma/
  schema.prisma
  seed.ts
src/
  app.ts
  server.ts
  config/
    env.ts
    prisma.ts
  lib/
    api-error.ts
    async-handler.ts
    response.ts
  middlewares/
    error-handler.ts
    validate.ts
  modules/
    registration/
      registration.controller.ts
      registration.routes.ts
      registration.schema.ts
      registration.service.ts
    calling/
      calling.controller.ts
      calling.routes.ts
      calling.schema.ts
      calling.service.ts
    followup/
      followup.controller.ts
      followup.routes.ts
      followup.schema.ts
      followup.service.ts
    attendance/
      attendance.controller.ts
      attendance.routes.ts
      attendance.schema.ts
      attendance.service.ts
    dropdowns/
      dropdowns.controller.ts
      dropdowns.routes.ts
      dropdowns.schema.ts
      dropdowns.service.ts
    myAttendance/
      myAttendance.controller.ts
      myAttendance.routes.ts
      myAttendance.schema.ts
      myAttendance.service.ts
    pool/
      pool.controller.ts
      pool.routes.ts
      pool.schema.ts
      pool.service.ts
  utils/
    mobile.ts
    ownership.ts
    permissions.ts
  routes/
    index.ts
```

## Database Tables

The Prisma schema creates:

- `centre_config`
- `users`
- `user_permissions`
- `registrations`
- `calling_assignments`
- `call_attempts`
- `followups`
- `dropdown_options`
- `dropdown_mappings`
- `removed_records`
- `attendance_summary`
- `attendance_sync_logs`
- `attendance_matches`
- `my_attendance`
- `batch_move_requests`
- `batches`
- `batch_members`
- `batch_calling_assignments`
- `notifications`
- `downloads`

## Important Rules Implemented

- Mobile numbers are normalized and unique through `normalized_mobile`
- A registration can have only one active queue at a time
- Category separation is enforced through validation and query filtering
- Dropdown labels are resolved by context and category scope
- `call_attempts.dropdown_value` stores the dropdown snapshot text
- Ownership fields are preserved across stage transitions
- `centre_config` and `user_permissions` are seeded for production-style setup
- Future stage values are reserved in the schema
- Dropdown management is supported with create, update, disable, and list APIs
- Pool APIs are separated into New Registration, Remaining Follow-up, and Batch-wise Calling pools
- Attendance setup, Google Sheet sync, My Attendance, batch-request approval, batch calling foundation, and notification flows are supported
- Download endpoints are supported for CSV exports of pools, live lists, attendance, batch, removed/archived records, and dropdowns

## Environment

Create a `.env` file from `.env.example`:

```env
DATABASE_URL="file:./dev.db"
PORT=3000
```

## Install and Run

```bash
npm install
npx prisma generate
npm run db:init
npm run prisma:seed
npm run dev
```

To run the end-to-end CLI smoke test against the local backend:

```bash
npm run smoke:full
```

Typical local workflow:

```bash
npm run dev
npm run smoke:full
```

## Authentication

This backend uses simple header-based auth for development:

- Send `x-user-id: <user id>` with every protected request
- The server loads the user and enabled permissions from the database
- `req.user` is populated with:
  - `id`
  - `name`
  - `permissions[]`

Example:

```bash
curl -H "x-user-id: 1" http://localhost:3000/calling-list?category=youth
```

Permission checks:

- `POST /register` -> `registration_access`
- `GET /calling-list` -> `calling_access`
- `POST /assign-calling` -> `assign_calling_access`
- `POST /calling-done` -> `calling_access`
- `GET /followup-list` -> `followup_access`
- `POST /followup-done` -> `followup_access`
- `GET /pool/*` -> `assign_calling_access`
- `POST /dropdowns` -> `dropdown_manage_access`
- `PUT /dropdowns/:id` -> `dropdown_manage_access`
- `PATCH /dropdowns/:id/disable` -> `dropdown_manage_access`
- `GET /attendance/config` -> `attendance_sync_access`
- `POST /attendance/config/test` -> `attendance_sync_access`
- `POST /attendance/config/save-lock` -> `attendance_sync_access`
- `PATCH /attendance/config/unlock` -> `attendance_sync_access`
- `POST /attendance/sync` -> `attendance_sync_access`
- `GET /attendance/system-link` -> `attendance_system_access`
- `GET /my-attendance` -> `my_attendance_access`
- `POST /my-attendance/:id/note` -> `my_attendance_access`
- `POST /my-attendance/:id/request-move-to-batch` -> `my_attendance_access`
- `GET /batch-move-requests` -> `assign_calling_access`
- `POST /batch-move-requests/:id/approve` -> `assign_calling_access`
- `POST /batch-move-requests/:id/reject` -> `assign_calling_access`
- `POST /batches` -> `assign_calling_access`
- `GET /batches` -> `assign_calling_access`
- `PATCH /batches/:id/disable` -> `assign_calling_access`
- `GET /batch-calling` -> `batch_calling_access`
- `POST /batch-calling/:batch_member_id/done` -> `batch_calling_access`
- `POST /batch-calling/:batch_member_id/send-back` -> `batch_calling_access`
- `GET /notifications` -> authenticated user
- `PATCH /notifications/:id/read` -> authenticated user

Admin override for `GET /calling-list` only works when the user also has:

- `assign_calling_access`, or
- `report_access`

## Migration Steps

For this local SQLite setup, the bootstrap path is:

```bash
npm run db:init
npm run prisma:seed
```

If you later move to a managed database, generate a real Prisma migration from the updated schema instead of editing the SQL by hand.

## Endpoints

### Attendance Setup and Sync

`attendance_system_access` and `attendance_sync_access` are intentionally separate:

- `attendance_system_access` is only for opening the existing attendance web app link
- `attendance_sync_access` is for testing, saving, unlocking, and syncing the Google Sheet into the calling system

### `GET /attendance/config`

Returns the current attendance config, including:

- `attendanceSheetId`
- `attendanceSheetLocked`
- `summarySheetName`
- `attendanceWebappUrl`

### `POST /attendance/config/test`

Tests a Summary sheet connection without saving anything.

Required Summary headers:

- `Number`
- `Name`
- `Address`
- `Occupation`
- `Total Count`

### `POST /attendance/config/save-lock`

Saves the sheet config and locks it.

If the config is already locked, it must be unlocked first.

### `PATCH /attendance/config/unlock`

Unlocks the attendance config so it can be edited again.

### `POST /attendance/sync`

Reads the locked Summary sheet, matches by `normalized_mobile`, and moves matched students to:

- `current_stage = attended_unassigned`
- `active_queue = my_attendance`

Important:

- It does not move anyone directly to batch calling
- It removes students from calling and follow-up queues
- It creates `attendance_summary`, `attendance_matches`, and `my_attendance` rows as needed

### `GET /attendance/system-link`

Returns the configured attendance web app URL for opening the existing attendance app under the same roof.

### `GET /my-attendance?category=youth`

Returns the authenticated caller’s own attendance list.

Query options:

- `category` is required
- `status` is optional and defaults to `active`
- `search` is optional

### `POST /my-attendance/:id/note`

Creates a call attempt with `call_type = attendance_conversion_call`.

It stores the dropdown label snapshot if `dropdown_id` is provided, but it does not move the queue automatically.

### `POST /my-attendance/:id/request-move-to-batch`

Creates a pending batch move request and changes the my-attendance row to `batch_requested`.

It does not move the student to `batch_calling`.

### `GET /batch-move-requests?category=youth`

Admin view for pending batch move requests.

### `POST /batch-move-requests/:id/approve`

Approves a pending batch move request and creates:

- a batch member
- a batch calling assignment
- batch calling notifications

The student moves to:

- `current_stage = batch_calling`
- `active_queue = batch_calling`

### `POST /batch-move-requests/:id/reject`

Rejects a pending batch move request and returns the student to `attended_unassigned` with `active_queue = my_attendance`.

### `POST /batches`

Creates a batch for a category.

### `GET /batches?category=youth`

Returns active batches for the requested category.

### `PATCH /batches/:id/disable`

Marks a batch inactive.

### `GET /batch-calling?category=youth`

Returns the batch caller's active batch calling view.

Admins with `assign_calling_access` or `report_access` can use `admin_override=true` to view all active batch calling assignments.

Each item includes:

- `batch_member_id`
- `registration_id`
- `earlier_managed_by`
- `original_caller_id`
- `relationship_owner_id`
- `current_batch_caller`
- last two call attempts
- assignment end date

### `POST /batch-calling/:batch_member_id/done`

Creates a batch calling attempt and applies the dropdown mapping for `batch_calling`.

Supported actions include:

- `keep_in_batch_calling`
- `continue_calling`
- `send_back_to_original_caller`
- `remove_from_running`
- `mark_invalid`
- `archive`

### `POST /batch-calling/:batch_member_id/send-back`

Sends the student back to the original caller for personal follow-up.

This does not move the student to normal calling or follow-up.

### `GET /notifications`

Returns authenticated user notifications.

### `PATCH /notifications/:id/read`

Marks a notification as read only if it belongs to the authenticated user.

### `GET /registrations/:registrationId/details`

Returns a detail view with:

- profile
- current stage and active queue
- ownership fields
- all call attempts
- last 2 call attempts
- attendance summary
- attendance match
- my-attendance status
- latest batch move request

### `GET /dropdowns?context=calling&category=youth`

Returns dropdown options for the requested context and category, including the mapped action.

Response:

```json
{
  "success": true,
  "message": "Dropdowns loaded",
  "data": {
    "count": 2,
    "items": [
      {
        "id": 1,
        "label": "Move to follow-up",
        "context": "calling",
        "category": "youth",
        "sort_order": 1,
        "is_active": true,
        "action": "move_to_followup"
      }
    ]
  }
}
```

### `POST /dropdowns`

Creates a dropdown option and its mapping.

Request:

```json
{
  "label": "Move to follow-up",
  "context": "calling",
  "category": "youth",
  "action": "move_to_followup",
  "sort_order": 1
}
```

### `PUT /dropdowns/:id`

Updates dropdown metadata and mapping action.

### `PATCH /dropdowns/:id/disable`

Marks a dropdown option as inactive.

### `GET /pool/new-registrations?category=youth`

Returns the New Registration Pool only.

Query options:

- `category` is required
- `date_from` is optional
- `date_to` is optional
- `assigned_status` may be `assigned`, `unassigned`, or `all`
- `search` is optional

Rules:

- `current_stage = registered`
- `active_queue = none`
- category must match exactly
- removed and archived records are excluded
- active assignments are excluded
- latest dropdown actions that remove/archive the record are excluded

### `GET /pool/remaining-followups?category=youth`

Returns the Remaining Follow-up Pool only.

Query options:

- `category` is required
- `last_response` is optional
- `caller_id` is optional
- `search` is optional

Rules:

- `current_stage = followup_active`
- `active_queue = followup`
- follow-up status must be active
- category must match exactly
- removed and archived records are excluded
- latest dropdown actions that remove/archive/close the record are excluded

### `GET /pool/batch-calling?category=youth`

Returns the batch-calling admin pool as two separate sections:

- `pending_requests`
- `active_batch_members`

This pool is category-aware and remains split by youth and congregation.

### `GET /pool/summary?category=youth`

Returns admin counts for the three pool areas plus removed and archived totals.

### `POST /assign-calling`

Supports `pool_type`:

- `new_registration_pool`
- `remaining_followup_pool`

For `new_registration_pool`, records move to `calling_active` and `active_queue = calling`.

For `remaining_followup_pool`, records stay in `followup_active` and `active_queue = followup` so they remain visible in the follow-up flow.

### `GET /pool/summary?category=youth`

Returns counts for:

- new registration pool
- remaining follow-up pool
- batch calling pool
- removed records
- archived records

### `POST /register`

Registers a person and sets `current_stage = registered`.

Request:

```json
{
  "name": "John Doe",
  "mobile": "9876543210",
  "address": "Main Street",
  "occupation": "Student",
  "category": "youth"
}
```

Response:

```json
{
  "success": true,
  "message": "Registration created",
  "data": {
    "id": 1,
    "name": "John Doe",
    "mobile": "9876543210",
    "category": "youth",
    "currentStage": "registered"
  }
}
```

### `POST /assign-calling`

Assigns one or more registrations to a caller and moves them to `calling_active`.

Request:

```json
{
  "pool_type": "new_registration_pool",
  "registration_ids": [1, 2],
  "caller_id": 1,
  "active_days": 7
}
```

Response:

```json
{
  "success": true,
  "message": "Calling assignments created",
  "data": {
    "category": "youth",
    "count": 2,
    "items": [
      {
        "registration": { "id": 1, "currentStage": "calling_active" },
        "assignment": { "status": "active" }
      }
    ]
  }
}
```

### `GET /calling-list?category=youth`

Returns active calling assignments for one category only.

Query options:

- `category` is required
- `caller_id` is ignored in favor of the authenticated user
- `admin_override=true` returns all active assignments for that category
- admin override only works if the authenticated user has `assign_calling_access` or `report_access`

Response:

```json
{
  "success": true,
  "message": "Calling list loaded",
  "data": {
    "count": 1,
    "items": []
  }
}
```

### `POST /calling-done`

Creates a call attempt and moves the record according to the dropdown mapping.

Request:

```json
{
  "registration_id": 1,
  "dropdown_value": "Move to follow-up",
  "remark": "Requested callback tomorrow"
}
```

Possible outcomes:

- `move_to_followup` -> create/update follow-up, `current_stage = followup_active`
- `remove_from_running` -> `current_stage = removed`, insert/update removed record
- `continue_calling` -> remain in `calling_active`

### `GET /followup-list?category=youth`

Returns active follow-ups for one category only.

`category` is required so youth and congregation records stay strictly separated.

### `POST /followup-done`

Creates a follow-up call attempt and keeps the record in follow-up for now.

Request:

```json
{
  "registration_id": 1,
  "dropdown_value": "Follow-up attempted",
  "remark": "No answer"
}
```

## Notes

- Seed data creates sample users and dropdown mappings
- Seed data also creates one centre config row and default user permissions
- Youth and congregation records stay strictly separated in all pool and dropdown queries
- Dropdown mappings control state transitions, including future-ready states such as attendance and batch calling
- Attendance sync uses the Summary sheet only for this step
- Attendance sync does not auto-move users to batch calling
- Batch approvals, batch calling view, send-back, and notifications are now supported
- Current APIs remain:
  - `POST /register`
  - `POST /assign-calling`
  - `GET /calling-list`
  - `POST /calling-done`
  - `GET /followup-list`
  - `POST /followup-done`
- New APIs added in this step:
  - `GET /attendance/config`
  - `POST /attendance/config/test`
  - `POST /attendance/config/save-lock`
  - `PATCH /attendance/config/unlock`
  - `POST /attendance/sync`
  - `GET /attendance/system-link`
  - `GET /my-attendance`
  - `POST /my-attendance/:id/note`
  - `POST /my-attendance/:id/request-move-to-batch`
  - `GET /batch-move-requests`
  - `POST /batch-move-requests/:id/approve`
  - `POST /batch-move-requests/:id/reject`
  - `POST /batches`
  - `GET /batches`
  - `PATCH /batches/:id/disable`
  - `GET /batch-calling`
  - `POST /batch-calling/:batch_member_id/done`
  - `POST /batch-calling/:batch_member_id/send-back`
- `GET /notifications`
- `PATCH /notifications/:id/read`
- `GET /downloads/new-registrations`
- `GET /downloads/remaining-followups`
- `GET /downloads/calling-list`
- `GET /downloads/followup-list`
- `GET /downloads/my-attendance`
- `GET /downloads/batch-calling`
- `GET /downloads/removed`
- `GET /downloads/archived`
- `GET /downloads/dropdowns`
- `GET /registrations/:registrationId/details`
- Real batch move assignment is still future work, but the foundation is now in place
- No JWT yet; `x-user-id` is the only auth input for now
