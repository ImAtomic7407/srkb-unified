-- Per-calling-session WhatsApp message, authored by an admin in Directory Setup.
--
-- Hand-written on purpose: `prisma migrate dev` cannot run in this project (an older migration
-- carries a Postgres `CREATE TYPE ... AS ENUM`, which SQLite cannot replay into a shadow database
-- and fails P3006). Apply with `prisma migrate deploy`.
--
-- Nullable with no default: existing campaigns keep NULL, which means "no prefill" and is exactly
-- the behaviour every caller has today.
ALTER TABLE "session_campaigns" ADD COLUMN "whatsapp_message" TEXT;
