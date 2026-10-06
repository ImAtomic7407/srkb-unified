/**
 * reset-calling-data.ts
 *
 * Wipes the calling pipeline data while keeping configuration intact.
 *
 * KEEPS: User, Centre, CentreConfig, Batch, Session, DropdownOption, DropdownMapping
 * WIPES: Registration, CallAttempt, CallingAssignment, Followup, RemovedRecord,
 *        BatchMoveRequest, MyAttendance, BatchMember, BatchMemberAudit,
 *        BatchCallingAssignment, and the 6 Google-Sheets-era tables
 *        (AttendanceSummary, AttendanceMatch, AttendanceBatch, AttendanceSession,
 *         AttendanceImportLog, AttendanceSyncLog) — only if they exist in the schema.
 *
 * TAKE A DB BACKUP BEFORE RUNNING. This is irreversible.
 *
 * Usage:
 *   npx tsx scripts/reset-calling-data.ts
 *   DRY_RUN=1 npx tsx scripts/reset-calling-data.ts   # prints counts only
 */

import { PrismaClient } from '@prisma/client';

const DRY_RUN = process.env.DRY_RUN === '1';
const prisma = new PrismaClient();
const log = (...a: unknown[]) => console.log('[reset]', ...a);

const safeDrop = async (label: string, fn: () => Promise<{ count: number } | number>) => {
  try {
    const result = await fn();
    const count = typeof result === 'number' ? result : result.count;
    log(DRY_RUN ? `[DRY] ${label}: ${count} rows` : `[LIVE] deleted ${label}: ${count} rows`);
    return count;
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : String(err);
    if (msg.toLowerCase().includes('does not exist') || msg.toLowerCase().includes('no such table')) {
      log(`[SKIP] ${label}: table not in schema`);
      return 0;
    }
    throw err;
  }
};

const run = async () => {
  log(DRY_RUN ? 'DRY RUN — no data will be deleted' : 'LIVE RUN — wiping calling pipeline data');

  if (!DRY_RUN) {
    // Delete in dependency order (children before parents)
    await prisma.$executeRawUnsafe('DELETE FROM batch_member_audit');
    await prisma.$executeRawUnsafe('DELETE FROM batch_calling_assignments');
    await safeDrop('BatchMember', () => prisma.$executeRawUnsafe('DELETE FROM batch_members').then((n) => ({ count: n })));
    await safeDrop('BatchMoveRequest', () => prisma.$executeRawUnsafe('DELETE FROM batch_move_requests').then((n) => ({ count: n })));

    // Google-Sheets-era tables (may not exist if migration hasn't run yet)
    await safeDrop('AttendanceSummary', () => prisma.$executeRawUnsafe('DELETE FROM attendance_summaries').then((n) => ({ count: n })));
    await safeDrop('AttendanceImportLog', () => prisma.$executeRawUnsafe('DELETE FROM attendance_import_logs').then((n) => ({ count: n })));
    await safeDrop('AttendanceMatch', () => prisma.$executeRawUnsafe('DELETE FROM attendance_matches').then((n) => ({ count: n })));
    await safeDrop('MyAttendance', () => prisma.$executeRawUnsafe('DELETE FROM my_attendances').then((n) => ({ count: n })));
    await safeDrop('AttendanceSession', () => prisma.$executeRawUnsafe('DELETE FROM attendance_sessions').then((n) => ({ count: n })));
    await safeDrop('AttendanceBatch', () => prisma.$executeRawUnsafe('DELETE FROM attendance_batches').then((n) => ({ count: n })));
    await safeDrop('AttendanceSyncLog', () => prisma.$executeRawUnsafe('DELETE FROM attendance_sync_logs').then((n) => ({ count: n })));

    await prisma.$executeRawUnsafe('DELETE FROM call_attempts');
    await prisma.$executeRawUnsafe('DELETE FROM calling_assignments');
    await prisma.$executeRawUnsafe('DELETE FROM followups');
    await prisma.$executeRawUnsafe('DELETE FROM removed_records');
    await prisma.$executeRawUnsafe('DELETE FROM registrations');
  } else {
    // DRY_RUN: just count
    const counts = await Promise.allSettled([
      prisma.batchMember.count(),
      prisma.batchMoveRequest.count(),
      prisma.registration.count(),
    ]);
    log('BatchMember:', counts[0].status === 'fulfilled' ? counts[0].value : '?');
    log('BatchMoveRequest:', counts[1].status === 'fulfilled' ? counts[1].value : '?');
    log('Registration:', counts[2].status === 'fulfilled' ? counts[2].value : '?');
  }

  log('done');
};

run()
  .catch((e) => {
    console.error(e);
    process.exit(1);
  })
  .finally(() => prisma.$disconnect());
