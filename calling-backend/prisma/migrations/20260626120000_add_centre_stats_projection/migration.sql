-- CreateTable: live event-driven read-model counters (see src/lib/projections.ts)
CREATE TABLE IF NOT EXISTS "centre_stats" (
    "centre_id" INTEGER NOT NULL,
    "stat_key" TEXT NOT NULL,
    "value" INTEGER NOT NULL DEFAULT 0,
    "version" INTEGER NOT NULL DEFAULT 0,
    "updated_at" DATETIME NOT NULL,
    "reconciled_at" DATETIME,
    CONSTRAINT "centre_stats_centre_id_fkey" FOREIGN KEY ("centre_id") REFERENCES "centres" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    PRIMARY KEY ("centre_id", "stat_key")
);

-- CreateIndex
CREATE INDEX IF NOT EXISTS "centre_stats_stat_key_idx" ON "centre_stats"("stat_key");
