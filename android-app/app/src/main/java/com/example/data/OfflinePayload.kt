package com.example.data

/**
 * Encoding/decoding for the durable offline queue's payload strings.
 *
 * Pulled out of AppData deliberately: this is the part of the offline path where a mistake
 * silently strands a user's work, and keeping it free of Android and Compose dependencies
 * means it can be covered by a plain JVM unit test (see OfflinePayloadTest).
 *
 * The cardinal rule is that the field order is APPEND-ONLY. Queue items live on people's
 * phones across app updates, so positions 0-2 must keep meaning exactly what they meant to
 * the build that wrote them; anything new goes on the end and is read defensively by size.
 */
object OfflinePayload {

    const val SEP = "<--SEP-->"

    /** Number of fields written by the current mark_attendance encoder. */
    const val MARK_ATTENDANCE_FIELDS = 8

    /**
     * What a mark_attendance queue item carries.
     *
     * [mobile], [sessionId], [date] and [centreId] are blank for a legacy three-field item
     * written before the payload captured them — [isLegacy] says so explicitly, so the
     * caller can apply its own recovery instead of guessing from empty strings.
     */
    data class MarkAttendanceFields(
        val studentId: String,
        val outcome: String,
        val note: String,
        val mobile: String,
        val sessionId: String,
        val date: String,
        val centreId: String,
        val category: String,
        val isLegacy: Boolean,
    )

    fun encodeMarkAttendance(
        studentId: String,
        outcome: String,
        note: String,
        mobile: String,
        sessionId: String,
        date: String,
        centreId: String,
        category: String,
    ): String = listOf(
        studentId, outcome, note, mobile, sessionId, date, centreId, category
    ).joinToString(SEP)

    /** Returns null only when the payload is too short to be a mark at all. */
    fun decodeMarkAttendance(payload: String): MarkAttendanceFields? {
        val parts = payload.split(SEP)
        if (parts.size < 3) return null
        // A payload from an older build carries only studentId/outcome/note. Treat anything
        // with the captured block present (7+) as current, so a future field appended after
        // `category` cannot turn every existing item into a legacy one.
        val full = parts.size >= 7
        return MarkAttendanceFields(
            studentId = parts[0],
            outcome = parts[1],
            note = parts[2],
            mobile = if (full) parts[3] else "",
            sessionId = if (full) parts[4] else "",
            date = if (full) parts[5] else "",
            centreId = if (full) parts[6] else "",
            category = parts.getOrNull(7).orEmpty(),
            isLegacy = !full,
        )
    }

    // ── register_student ──────────────────────────────────────────────────────
    // Was built and parsed inline, in two places ~2700 lines apart, by raw index. That is the
    // hazard mark_attendance was pulled out of this file to escape: nothing failed loudly if the
    // two drifted, the queued item just replayed into the wrong batch, centre or class. These
    // carry the scoping that decides WHERE someone lands, so they get the same treatment.

    /** Number of fields written by the current register_student encoder. */
    const val REGISTER_STUDENT_FIELDS = 11

    data class RegisterStudentFields(
        val name: String,
        val mobile: String,
        val address: String,
        val occupation: String,
        val remark: String,
        /** Blank only for an item queued before the field existed. */
        val batchId: String,
        /** Blank = not stated; the server then falls back to the caller's own primary centre. */
        val centreId: String,
        /** A walk-in queued from the Attendance tab replays as a registration AND an attendance. */
        val markPresent: Boolean,
        val sessionId: String,
        val date: String,
        /**
         * The class as it was at CAPTURE time. Null — not blank — when the item predates the field,
         * so the caller can tell "never recorded" from "recorded as empty" and fall back to the
         * live selection only in the first case.
         */
        val category: String?,
    )

    fun encodeRegisterStudent(
        name: String,
        mobile: String,
        address: String,
        occupation: String,
        remark: String,
        batchId: String,
        centreId: String,
        markPresent: Boolean,
        sessionId: String,
        date: String,
        category: String,
    ): String = listOf(
        name, mobile, address, occupation, remark, batchId, centreId,
        if (markPresent) "1" else "0", sessionId, date, category,
    ).joinToString(SEP)

    /** Returns null when the payload is too short to be a registration at all. */
    fun decodeRegisterStudent(payload: String): RegisterStudentFields? {
        val p = payload.split(SEP)
        if (p.size < 5) return null
        return RegisterStudentFields(
            name = p[0], mobile = p[1], address = p[2], occupation = p[3], remark = p[4],
            batchId = if (p.size >= 6) p[5] else "",
            centreId = if (p.size >= 7) p[6] else "",
            // The walk-in block is only meaningful in full: an item that stops short of the date
            // meant "register only" when it was written, and must keep meaning that.
            markPresent = p.size >= 10 && p[7] == "1",
            sessionId = if (p.size >= 10) p[8] else "",
            date = if (p.size >= 10) p[9] else "",
            category = p.getOrNull(10)?.takeIf { it.isNotEmpty() },
        )
    }

    // ── submit_attendance ─────────────────────────────────────────────────────

    /** Number of fields written by the current submit_attendance encoder. */
    const val SUBMIT_ATTENDANCE_FIELDS = 12

    data class SubmitAttendanceFields(
        val mobile: String,
        val name: String,
        val address: String,
        val occupation: String,
        val batchId: String,
        val sessionId: String,
        val date: String,
        /** Blank in the payload means one round — the server has always been sent "1". */
        val rounds: String,
        val remark: String,
        /** Blank = not stated. */
        val centreId: String,
        /** Null when the item predates the field: send NO category rather than guessing one. */
        val category: String?,
        /** When the operator actually tapped save. Null for items written before it was captured. */
        val capturedAtMillis: Long?,
    )

    fun encodeSubmitAttendance(
        mobile: String,
        name: String,
        address: String,
        occupation: String,
        batchId: String,
        sessionId: String,
        date: String,
        rounds: String,
        remark: String,
        centreId: String,
        category: String,
        capturedAtMillis: Long,
    ): String = listOf(
        mobile, name, address, occupation, batchId, sessionId, date, rounds, remark,
        centreId, category, capturedAtMillis.toString(),
    ).joinToString(SEP)

    /** Returns null when the payload is too short to be a faithful re-POST. */
    fun decodeSubmitAttendance(payload: String): SubmitAttendanceFields? {
        val p = payload.split(SEP)
        if (p.size < 9) return null
        return SubmitAttendanceFields(
            mobile = p[0], name = p[1], address = p[2], occupation = p[3], batchId = p[4],
            sessionId = p[5], date = p[6], rounds = p[7].ifEmpty { "1" }, remark = p[8],
            centreId = if (p.size >= 10) p[9] else "",
            category = p.getOrNull(10)?.takeIf { it.isNotEmpty() },
            capturedAtMillis = p.getOrNull(11)?.takeIf { it.isNotEmpty() }?.toLongOrNull(),
        )
    }

    /**
     * Best-effort recovery of a mobile number from a Registration id.
     *
     * Both member-list sources mint ids as "r_<mobile>" / "cand_<mobile>", so an item queued
     * by an older build — whose payload never carried the mobile — can still be replayed
     * rather than parked. Guarded on exactly ten digits, which is what the server accepts:
     * that also rejects the "r_<epochMillis>" ids minted for freshly-registered people,
     * whose 13 digits would otherwise be sent as a nonsense phone number.
     */
    fun mobileFromRegistrationId(studentId: String): String {
        val tail = studentId.substringAfter('_', "")
        return if (tail.length == 10 && tail.all { it.isDigit() }) tail else ""
    }
}
