package com.example

import com.example.data.OfflinePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the offline queue's payload contract.
 *
 * The failure this protects against is not a crash — it is silent data loss. Queue items
 * sit on people's phones across app updates, so if a decoder stops understanding what an
 * older build wrote, that person's un-synced attendance marks are stranded with no error
 * anywhere. Every test here is about that: the current shape round-trips, and the legacy
 * shape still parses.
 */
class OfflinePayloadTest {

    @Test
    fun `current payload round-trips every captured field`() {
        val encoded = OfflinePayload.encodeMarkAttendance(
            studentId = "r_9876543210",
            outcome = "Spoke with student",
            note = "Confirmed for Sunday",
            mobile = "9876543210",
            sessionId = "11 july",
            date = "2026-07-24",
            centreId = "centre_a_Base",
            category = "Youth",
        )
        val f = OfflinePayload.decodeMarkAttendance(encoded)!!
        assertFalse("a freshly encoded payload must never read as legacy", f.isLegacy)
        assertEquals("r_9876543210", f.studentId)
        assertEquals("Spoke with student", f.outcome)
        assertEquals("Confirmed for Sunday", f.note)
        assertEquals("9876543210", f.mobile)
        assertEquals("11 july", f.sessionId)
        // The date must survive verbatim. Re-deriving it at replay time is what dated a
        // Friday mark as the following Monday.
        assertEquals("2026-07-24", f.date)
        assertEquals("centre_a_Base", f.centreId)
        assertEquals("Youth", f.category)
    }

    @Test
    fun `legacy three-field payload still parses and is flagged`() {
        val legacy = listOf("r_9876543210", "Spoke with student", "Bulk batch call log.")
            .joinToString(OfflinePayload.SEP)
        val f = OfflinePayload.decodeMarkAttendance(legacy)!!
        assertTrue(f.isLegacy)
        assertEquals("r_9876543210", f.studentId)
        assertEquals("Spoke with student", f.outcome)
        assertEquals("Bulk batch call log.", f.note)
        // Nothing may be invented for the fields a legacy item never carried.
        assertEquals("", f.mobile)
        assertEquals("", f.sessionId)
        assertEquals("", f.date)
    }

    @Test
    fun `positions zero through two never move`() {
        // The append-only rule, asserted directly: the first three fields of the current
        // encoder must sit exactly where the legacy decoder expects to find them.
        val encoded = OfflinePayload.encodeMarkAttendance(
            "r_1112223334", "Left message", "note text",
            "1112223334", "sess", "2026-07-29", "centre", "Youth",
        )
        val raw = encoded.split(OfflinePayload.SEP)
        assertEquals(OfflinePayload.MARK_ATTENDANCE_FIELDS, raw.size)
        assertEquals("r_1112223334", raw[0])
        assertEquals("Left message", raw[1])
        assertEquals("note text", raw[2])
    }

    // ── register_student / submit_attendance ─────────────────────────────────
    //
    // These two decide WHERE a queued item lands: batch, centre, session and class. They were
    // built and parsed inline by raw index, ~2700 lines apart, with no test between them.
    //
    // The tests below are deliberately written against FROZEN LITERAL payloads — the exact
    // strings the shipped encoder produces — not round-trips. A round-trip proves nothing here:
    // shift every index consistently and encode->decode still agrees, while every item already
    // sitting on a phone silently replays into the wrong batch or the wrong class. Only a frozen
    // literal catches that, because the phones are holding literals too.

    private val REGISTER_STUDENT_V1 =
        "Ravi Kumar<--SEP-->9876543210<--SEP-->Centre A<--SEP-->Student<--SEP-->met at class" +
            "<--SEP-->18<--SEP-->centre_a_Base<--SEP-->1<--SEP-->2026-08-14 CTRA BG" +
            "<--SEP-->2026-08-14<--SEP-->Congregation"

    private val SUBMIT_ATTENDANCE_V1 =
        "9876543210<--SEP-->Ravi Kumar<--SEP-->Centre A<--SEP-->Student<--SEP-->18" +
            "<--SEP-->2026-08-14 CTRA BG<--SEP-->2026-08-14<--SEP-->4<--SEP-->came late" +
            "<--SEP-->centre_a_Base<--SEP-->Congregation<--SEP-->1786700000000"

    @Test
    fun `a register_student payload already on a phone decodes into the right fields`() {
        val f = OfflinePayload.decodeRegisterStudent(REGISTER_STUDENT_V1)!!
        assertEquals("Ravi Kumar", f.name)
        assertEquals("9876543210", f.mobile)
        assertEquals("Centre A", f.address)
        assertEquals("Student", f.occupation)
        assertEquals("met at class", f.remark)
        // The four that decide where this person lands.
        assertEquals("18", f.batchId)
        assertEquals("centre_a_Base", f.centreId)
        assertEquals("2026-08-14 CTRA BG", f.sessionId)
        assertEquals("Congregation", f.category)
        assertTrue("a walk-in must replay as an attendance too", f.markPresent)
        assertEquals("2026-08-14", f.date)
    }

    @Test
    fun `a submit_attendance payload already on a phone decodes into the right fields`() {
        val f = OfflinePayload.decodeSubmitAttendance(SUBMIT_ATTENDANCE_V1)!!
        assertEquals("9876543210", f.mobile)
        assertEquals("Ravi Kumar", f.name)
        assertEquals("Centre A", f.address)
        assertEquals("Student", f.occupation)
        assertEquals("came late", f.remark)
        assertEquals("4", f.rounds)
        assertEquals("18", f.batchId)
        assertEquals("centre_a_Base", f.centreId)
        assertEquals("2026-08-14 CTRA BG", f.sessionId)
        assertEquals("2026-08-14", f.date)
        assertEquals("Congregation", f.category)
        // The moment the operator tapped save — a late sync must not outrank a correction made
        // in between, so this has to survive verbatim.
        assertEquals(1786700000000L, f.capturedAtMillis)
    }

    @Test
    fun `todays encoders still produce exactly those strings`() {
        // Pairs the two tests above with the write side: if an encoder changes, this fails here
        // rather than in the field, on a queue that has already been written.
        assertEquals(REGISTER_STUDENT_V1, OfflinePayload.encodeRegisterStudent(
            name = "Ravi Kumar", mobile = "9876543210", address = "Centre A",
            occupation = "Student", remark = "met at class", batchId = "18",
            centreId = "centre_a_Base", markPresent = true,
            sessionId = "2026-08-14 CTRA BG", date = "2026-08-14", category = "Congregation",
        ))
        assertEquals(SUBMIT_ATTENDANCE_V1, OfflinePayload.encodeSubmitAttendance(
            mobile = "9876543210", name = "Ravi Kumar", address = "Centre A",
            occupation = "Student", batchId = "18", sessionId = "2026-08-14 CTRA BG",
            date = "2026-08-14", rounds = "4", remark = "came late",
            centreId = "centre_a_Base", category = "Congregation",
            capturedAtMillis = 1786700000000L,
        ))
        assertEquals(OfflinePayload.REGISTER_STUDENT_FIELDS, REGISTER_STUDENT_V1.split(OfflinePayload.SEP).size)
        assertEquals(OfflinePayload.SUBMIT_ATTENDANCE_FIELDS, SUBMIT_ATTENDANCE_V1.split(OfflinePayload.SEP).size)
    }

    @Test
    fun `a class that was never recorded is never invented`() {
        // An item queued before the category field existed must replay WITHOUT a category, so the
        // server applies its own default — rather than being stamped with whatever the toggle
        // happens to show at sync time, which is how a congregation mark became a youth one.
        val olderRegister = REGISTER_STUDENT_V1.substringBeforeLast("<--SEP-->")
        assertEquals(null, OfflinePayload.decodeRegisterStudent(olderRegister)!!.category)
        val olderSubmit = SUBMIT_ATTENDANCE_V1.split(OfflinePayload.SEP).take(10)
            .joinToString(OfflinePayload.SEP)
        val f = OfflinePayload.decodeSubmitAttendance(olderSubmit)!!
        assertEquals(null, f.category)
        assertEquals(null, f.capturedAtMillis)
        // ...but everything the older item DID record still lands correctly.
        assertEquals("18", f.batchId)
        assertEquals("centre_a_Base", f.centreId)
    }

    @Test
    fun `a register-only item is never replayed as an attendance`() {
        // Stopping short of the walk-in block meant "register only" when it was written.
        val registerOnly = REGISTER_STUDENT_V1.split(OfflinePayload.SEP).take(7)
            .joinToString(OfflinePayload.SEP)
        val f = OfflinePayload.decodeRegisterStudent(registerOnly)!!
        assertFalse(f.markPresent)
        assertEquals("", f.sessionId)
        assertEquals("18", f.batchId)
        assertEquals("centre_a_Base", f.centreId)
    }

    @Test
    fun `payloads too short to act on are rejected rather than half-applied`() {
        assertEquals(null, OfflinePayload.decodeRegisterStudent("a${OfflinePayload.SEP}b"))
        assertEquals(null, OfflinePayload.decodeSubmitAttendance(
            SUBMIT_ATTENDANCE_V1.split(OfflinePayload.SEP).take(8).joinToString(OfflinePayload.SEP)))
    }

    @Test
    fun `mobile is recovered from both id shapes`() {
        assertEquals("9876543210", OfflinePayload.mobileFromRegistrationId("r_9876543210"))
        assertEquals("9876543210", OfflinePayload.mobileFromRegistrationId("cand_9876543210"))
    }

    @Test
    fun `epoch-millis ids are rejected rather than sent as a phone number`() {
        // "r_<System.currentTimeMillis()>" is minted for a freshly-registered person. Its 13
        // digits are not a mobile number, and the server rejects anything that is not exactly
        // ten — so this must come back blank and let the item park, not send junk.
        assertEquals("", OfflinePayload.mobileFromRegistrationId("r_1785000000000"))
        assertEquals("", OfflinePayload.mobileFromRegistrationId("r_"))
        assertEquals("", OfflinePayload.mobileFromRegistrationId("12345"))
        assertEquals("", OfflinePayload.mobileFromRegistrationId("r_98765abcde"))
    }

    @Test
    fun `payloads too short to be a mark are rejected outright`() {
        assertEquals(null, OfflinePayload.decodeMarkAttendance(""))
        assertEquals(null, OfflinePayload.decodeMarkAttendance("only${OfflinePayload.SEP}two"))
    }
}
