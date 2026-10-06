package com.example

import com.example.data.RealtimeSseClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which realtime events are allowed to reload an admin screen.
 *
 * Reported 2026-08-30: one admin creates a calling session and the other cannot see it until they
 * switch to another tab, wait, and switch back — that round trip disposes and rebuilds the screen,
 * which was the ONLY thing that ever reloaded it. AdminScreens.kt referenced `dataRevision` zero
 * times, so no realtime event reached it at all.
 *
 * The obvious fix — key the admin screens off `dataRevision` — trades that bug for a worse one:
 * `dataRevision` moves on every attendance mark (115 `attendance.persisted` events in a single
 * server log), and those screens cost three fetches plus a centre roster to reload. An admin with
 * Pool Assignment open during a class would refetch all of it once per person marked at the door.
 *
 * Hence the split these tests guard: the flow of attendance through a class is NOT structural;
 * anything that changes what the pickers and lists contain is.
 */
class RealtimeKindTest {

    @Test
    fun `the attendance flow does not reload the admin screens`() {
        // The three high-frequency kinds. These fire continuously while a class is being marked.
        assertFalse(RealtimeSseClient.isStructuralKind("attendance.persisted"))
        assertFalse(RealtimeSseClient.isStructuralKind("session_attendance_recorded"))
        assertFalse(RealtimeSseClient.isStructuralKind("roster.injected"))
    }

    @Test
    fun `a session another admin creates reloads the screens listing it`() {
        // The reported bug, in one line.
        assertTrue(RealtimeSseClient.isStructuralKind("session.created"))
        assertTrue(RealtimeSseClient.isStructuralKind("session.renamed"))
        assertTrue(RealtimeSseClient.isStructuralKind("session.deleted"))
    }

    @Test
    fun `batch and centre changes are structural too`() {
        assertTrue(RealtimeSseClient.isStructuralKind("batch.created"))
        assertTrue(RealtimeSseClient.isStructuralKind("batch.renamed"))
        assertTrue(RealtimeSseClient.isStructuralKind("batch.deleted"))
        assertTrue(RealtimeSseClient.isStructuralKind("batch.archived"))
        assertTrue(RealtimeSseClient.isStructuralKind("batch.category_set"))
        assertTrue(RealtimeSseClient.isStructuralKind("batch.default_set"))
        assertTrue(RealtimeSseClient.isStructuralKind("centre.renamed"))
        assertTrue(RealtimeSseClient.isStructuralKind("centre.status_changed"))
    }

    @Test
    fun `a kind this build has never heard of counts as structural`() {
        // Deliberately an exclusion list, not an allowlist. The server owns BROADCAST_KINDS
        // (lib/ae-broadcast.ts) and can add to it without the app being rebuilt; a phone that then
        // ignored the new kind would reproduce the exact bug this fixes, and would do it silently.
        // A screen refreshing when it need not have is invisible. One that never refreshes is not.
        assertTrue(RealtimeSseClient.isStructuralKind("session_campaign.created"))
        assertTrue(RealtimeSseClient.isStructuralKind("something.invented.next.year"))
    }

    @Test
    fun `a missing kind still reloads rather than being silently dropped`() {
        // handleDataChanged passes optString("kind", "") — an older server, or a payload shape
        // change, must not leave the screens frozen.
        assertTrue(RealtimeSseClient.isStructuralKind(""))
    }
}
