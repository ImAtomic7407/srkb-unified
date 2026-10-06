-- Retrieval must LOCK a follow-up's editability, not hide/remove it from the caller's list.
ALTER TABLE "followups" ADD COLUMN "is_locked" BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE "followups" ADD COLUMN "locked_reason" TEXT;
