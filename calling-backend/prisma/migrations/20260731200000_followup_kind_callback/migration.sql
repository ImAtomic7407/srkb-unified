-- "Call back later" becomes a POSITIVE response.
--
-- Until now it mapped to continue_calling — identical to "No answer / Switched off" — so a person
-- who actually picked up and asked to be rung back was struck toward removal and produced no
-- follow-up. It is now its own action, and the follow-up it creates is tagged so the caller's
-- Follow-ups tab can list it under a separate header instead of blending it in with people who
-- confirmed they are coming.
--
-- Both `action` and `call_status` are plain TEXT columns (no CHECK constraint), so the new enum
-- values need no table rewrite — only Prisma's schema knows about them.

ALTER TABLE "followups" ADD COLUMN "kind" TEXT NOT NULL DEFAULT 'will_attend';

-- Re-point the outcome itself. Matched by LABEL rather than by a hardcoded id because the
-- dictionary is centre-customisable: a centre may hold its own override row of the same option,
-- and every copy must move together or one centre keeps the old striking behaviour.
UPDATE "dropdown_mappings"
   SET "action" = 'followup_callback'
 WHERE "dropdown_id" IN (
   SELECT "id" FROM "dropdown_options"
    WHERE "context" = 'calling'
      AND lower("label") LIKE '%call back later%'
 );
