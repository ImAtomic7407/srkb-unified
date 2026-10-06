-- Add session campaign kind: called (admin calling campaign) | direct_attendance (attendance-only, flagged)
ALTER TABLE "session_campaigns" ADD COLUMN "kind" TEXT NOT NULL DEFAULT 'called';
