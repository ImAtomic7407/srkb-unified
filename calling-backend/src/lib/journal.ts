// Cross-system sync journal — CB's write side of the SAME shared file AE
// writes to (see journal_write() in app.py for the full rationale). One
// append-only JSONL file both processes write to, so every cross-system
// connection point — webhook fires, outbox job attempts in both directions,
// reconcile runs/findings, internal endpoint calls — lands in one place with
// a full timestamp and the original payload, regardless of which side or
// which specific job kind was involved. That payload is what makes a failed
// entry replayable later instead of just a log line nobody revisits.
import { appendFile } from "fs/promises";
import { join } from "path";
import { homedir } from "os";

const JOURNAL_PATH = join(homedir(), "cross_system_sync_journal.jsonl");

export type JournalDirection = "AE->CB" | "CB->AE" | "internal";
export type JournalConnection = "webhook" | "outbox" | "reconcile" | "endpoint";
export type JournalStatus = "attempted" | "success" | "failed" | "self_healed" | "skipped" | "needs_attention";

export const journalWrite = async (entry: {
  direction: JournalDirection;
  connection: JournalConnection;
  kind: string;
  status: JournalStatus;
  entity?: string | number | null;
  detail?: string | null;
  payload?: unknown;
  error?: unknown;
  attempt?: number | null;
  source?: string | null;
}): Promise<void> => {
  const record = {
    ts: new Date().toISOString(),
    system: "CB",
    direction: entry.direction,
    connection: entry.connection,
    kind: entry.kind,
    status: entry.status,
    entity: entry.entity ?? null,
    detail: entry.detail ?? null,
    payload: entry.payload ?? null,
    error: entry.error ? String(entry.error instanceof Error ? entry.error.message : entry.error).slice(0, 1000) : null,
    attempt: entry.attempt ?? null,
    source: entry.source ?? null,
  };
  try {
    await appendFile(JOURNAL_PATH, JSON.stringify(record) + "\n", "utf-8");
  } catch (err) {
    console.error(`[journal] write failed: ${err instanceof Error ? err.message : err}`);
  }
};
