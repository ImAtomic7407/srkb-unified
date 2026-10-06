package com.example.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "AttendEase"
private fun logReq(backend: String, method: String, path: String, status: Int, ms: Long) {
    val level = if (status >= 400) "WARN" else "OK"
    Log.d(TAG, "[$level] $backend $method $path → $status (${ms}ms)")
}
private fun logErr(backend: String, method: String, path: String, err: String) {
    Log.e(TAG, "[ERR] $backend $method $path → $err)}")
}

// Data Models
data class Centre(
    val id: String,
    val name: String,
    val code: String,
    val googleSheetId: String = "",
    val isSheetEnabled: Boolean = false,
    val enabledYouth: Boolean = true,
    val enabledCongregation: Boolean = true,
    val status: String = "active" // active, inactive
)

data class Batch(
    val id: String,
    val name: String,
    val centreId: String,
    val category: String, // Youth, Congregation
    val isDefault: Boolean = false,
    val status: String = "active", // active, disabled
    val memberCount: Int = 0
)

data class Session(
    val id: String,
    val name: String,
    val batchId: String,
    val date: String,
    // 'planned' = a calling session (has a calling campaign); 'ad_hoc' = a direct, attendance-only
    // session. Drives the Calling/Direct grouping in the registerer session picker.
    val sessionType: String = "ad_hoc"
)

// The centre's active session EXACTLY as the server reports it (/api/cache/state).
// This is the authority for which session an attendance mark belongs to. The app used to
// guess with `sessions.firstOrNull { it.batchId == ... }`, which picks an arbitrary session
// of that batch — so a mark could land on a months-old session even with a perfect
// connection. Mirrored to disk, it also gives an OFFLINE mark the correct session id and
// date, instead of the fabricated "s1" / today's-date pair the replay used to invent.
/**
 * The server's verdict on this build. [updateRequired] is the mandatory lever — the
 * server sets it only when the installed versionCode is below the configured minimum
 * AND it actually has an APK to serve, so a user can never be blocked by a prompt
 * whose download link would 404.
 */
data class AppUpdateStatus(
    val currentVersionCode: Long,
    val latestVersionCode: Long,
    val latestVersionName: String,
    val updateAvailable: Boolean,
    val updateRequired: Boolean,
    val downloadPageUrl: String,
    val apkUrl: String,
    val notes: List<String>,
)

data class ServerActiveSession(
    val sessionId: String,
    val date: String,
    val batchId: String,
    val batchName: String,
    val category: String
)


data class PlannedSession(
    val id: String,
    val name: String,
    val batchId: String,
    val centreId: String,
    val scheduledAt: String
)

data class SessionCampaign(
    val id: String,
    val aeSessionId: String,
    val batchId: String,
    val batchName: String,
    val sessionName: String = "",
    val scheduledAt: String,
    val status: String,
    val assignmentCount: Int = 0,
    // Existing caller assignments for this campaign: batchMemberId -> callerId. Lets the assign
    // dialog pre-select callers already assigned (so previously-assigned rows load).
    val assignments: Map<String, String> = emptyMap(),
    // Full per-assignment detail (id, who's assigned to whom, call status) — needed for the live
    // per-caller expand/note/remove view while the campaign is still "defined" (active).
    val assignmentDetails: List<SessionCallAssignmentDetail> = emptyList(),
    // The admin's WhatsApp message for this calling session, RAW (placeholders unexpanded) — this
    // is what Directory Setup edits. Callers never see this form; the server renders it per person.
    val whatsappMessage: String = ""
)

// One assignment row inside a session campaign — richer than the flat `assignments` map above,
// carries the real assignment id (needed to soft-remove it) and display fields.
data class SessionCallAssignmentDetail(
    val id: String,
    val batchMemberId: String,
    val batchMemberName: String?,
    val batchMemberMobile: String,
    val callerId: String,
    val callerName: String,
    val callStatus: String,
    // Which allotment this row belongs to — see [Allotment]. Null on every row written before
    // allotment provenance existed; those group under their caller alone, with no heading.
    val allotmentKey: String? = null,
    val allotmentLabel: String? = null,
    val allotmentFilter: String? = null
)

// One centre session for the pool-assignment lower "Filter: session" dropdown. Covers BOTH
// calling sessions (kind="called") and direct-attendance sessions (kind="direct_attendance").
// Selecting one filters the pool to people who ATTENDED that session (by aeSessionId).
data class CentreSession(
    val aeSessionId: String,
    val displayName: String,
    val kind: String,          // "called" | "direct_attendance"
    val batchId: String? = null,
    val date: String? = null
)

// Reconciliation result returned after completing a calling session.
data class SessionSummary(
    val campaignId: String,
    val total: Int,
    val came: Int,
    val absent: Int,
    // Legacy name. This is NOT "sent to follow-up". Completion CLOSES follow-ups; this
    // counts within-tolerance absentees returned to the registered pool who stay in the
    // batch roster for the next round. Rendered as "In roster" in the UI.
    val toFollowup: Int,
    val toRemove: Int,
    val skippedNoReg: Int,
    val status: String,
    val byResponse: Map<String, Int> = emptyMap()
)

// One previous logged call response — surfaced inline on the calling card so the caller sees the
// person's history without opening a detail screen.
data class CallResponse(
    val date: String,
    val outcome: String,
    val remark: String? = null,
    val caller: String = "—"
)

// Per-person calling context (previous responses + attendance + details) attached to every calling
// card, whether it's a session call or a pool call — they are the same thing.
data class PersonContext(
    val recentCalls: List<CallResponse> = emptyList(),
    val attendanceCount: Int = 0,
    val lastAttended: String? = null,
    val recentAttendance: List<String> = emptyList(),
    val occupation: String? = null,
    val address: String? = null,
    val isNew: Boolean = false,
    // Active batch names this person belongs to (shown as chips on the caller's card).
    val currentBatches: List<String> = emptyList()
)

// "My Attendance" by-calling scoreboard: drill down batch → session → people, showing how many the
// caller CALLED vs how many CAME. Fed by /my-attendance/calling/{batches,sessions,attended}.
data class CallingBatchStat(
    val batchId: Int, val batchName: String, val calledCount: Int, val attendedCount: Int
)
data class CallingSessionStat(
    val campaignId: Int, val aeSessionId: String, val scheduledAtMillis: Long,
    val batchName: String, val calledCount: Int, val attendedCount: Int
)
data class CalledPerson(
    val name: String, val mobile: String, val came: Boolean,
    val outcomeLabel: String, val calledAtMillis: Long
)

// Caller "Today" progress numbers for the Calling tab's progress strip.
data class SessionCallSummary(
    val total: Int = 0,
    val done: Int = 0,
    val left: Int = 0,
    // People deferred to a later date ("call me next week"). Deliberately NOT part of total/left —
    // they are not today's work, and counting them would leave the caller a number they cannot
    // drive to zero.
    val scheduled: Int = 0
)

data class SessionCallAssignment(
    val id: String,
    val campaignId: String,
    val batchMemberId: String,
    val mobile: String,
    val name: String? = null,
    val callStatus: String,
    val sessionName: String,
    val batchName: String,
    val scheduledAt: String,
    val context: PersonContext = PersonContext(),
    // Admin's note for THIS caller on this campaign — shown beside the session name in My Callings.
    val callerNote: String? = null,
    // The calling session's WhatsApp message, ALREADY RENDERED for this person by the server
    // ({name} etc. substituted). Null when the admin set none — the button then opens WhatsApp
    // with no text, exactly as it did before this existed.
    val whatsappMessage: String? = null,
    // Set when the person answered and asked to be called on a later date. They sit in the
    // Scheduled section instead of the working list until `snoozeUntil` passes, at which point the
    // server returns them in `items` again and they rejoin the batch roster on their own.
    val isScheduled: Boolean = false,
    val snoozeUntil: String? = null
)

data class BatchMemberInfo(
    val id: String,
    val mobile: String,
    val name: String? = null,
    val batchId: Int,
    val joinedAt: String
)

// One person as the mirrored server roster reports them, for the registration screen's
// live batch roll. Deliberately minimal: this list is read at a glance while typing, and the
// wider Registration model carries a dozen fields none of which belong on that row.
data class BatchRosterRow(
    val mobile: String,
    val name: String,
    // Both of these are read if the server sends them and ignored if it does not, so the
    // backend half of "who registered this row, and when" can land later WITHOUT another app
    // release. Today roster.json emits neither: `centre_roster` has no column naming the
    // operator at all, and regenerate_cache_files does not emit the created_at it already has.
    val registeredBy: String? = null,
    val createdAt: String? = null
)

/**
 * One person as the mirrored roster reports them, for the registration screen's number lookup.
 *
 * Unlike [BatchRosterRow] this keeps CENTRE-LEVEL rows (batch_id null) — 1,601 of Centre B's
 * 1,804 people are exactly that, and a number lookup that skipped them would miss most of the
 * centre. It is "the whole roster", which is the point.
 */
data class RosterPerson(
    val mobile: String,
    val name: String,
    val address: String,
    val occupation: String,
    val batchId: String?,
    val batchName: String?,
    /** [mobile] with non-digits stripped, computed ONCE at index time. The number field scans
     *  every person on every keystroke; stripping 1,800 strings per character is the input-lag
     *  failure this file has already been bitten by twice. */
    val digits: String = mobile.filter { it.isDigit() },
    /** The centre this person belongs to, set ONLY for a cross-centre hit — null means they are
     *  on the centre currently selected, which is the ordinary case and needs no label. Trailing
     *  and defaulted so every existing positional construction keeps compiling. */
    val centreName: String? = null,
)

// Named batch member (from the calling backend's /pool/batch-members — enriched from the
// AttendEase roster + attendance history). Used by the Batch Management membership view.
data class NamedMember(
    val name: String,
    val mobile: String,
    val attendanceCount: Int = 0,
    val lastAttended: String? = null,
    val isRegistered: Boolean = false
)

// One person from a session filter on the pool page. `stage` is the registration's current_stage,
// null when they have no registration in this centre+category. `busy` is where they are held right
// now, if anywhere (null = free to assign) — worked out by the server from the records that hold a
// person, never from `stage`. See the calling backend's lib/person-busy.ts.
data class SessionPerson(val mobile: String, val stage: String?, val row: Registration, val busy: PersonBusy? = null)

// "One number, one place." kind: followup | session_call | pool_call | batch_call.
data class PersonBusy(val kind: String, val label: String, val caller: String?)

data class Registration(
    val id: String,
    val name: String,
    val mobile: String,
    val address: String,
    val occupation: String,
    val remark: String,
    val centreId: String,
    val category: String, // Youth, Congregation
    val status: String = "active", // active, archived
    val explicitBatchId: String? = null,
    val batchIds: List<String> = emptyList(),
    val callerAssignedId: String? = null,
    val context: PersonContext = PersonContext(),
    // Follow-up specific (only populated by fetchFollowups): locked once the originating calling
    // session is retrieved — still visible/callable, just can't be closed out until unlocked.
    val isLocked: Boolean = false,
    val lockedReason: String? = null,
    val createdAtMillis: Long = 0L,
    // Follow-up grouping: the calling session this person gave a positive response in (if any).
    val sessionName: String? = null,
    // Why this follow-up exists: "will_attend" (said they are coming) or "call_back_later"
    // (answered and asked to be rung back — positive, but NOT yet a yes). Drives the separate
    // section header so a call-back is never mistaken for a confirmed attendee.
    val followupKind: String = "will_attend",
    // Rendered WhatsApp message inherited from the calling session this follow-up came from.
    // Null for a follow-up with no campaign link, and for one whose session has no message set.
    val whatsappMessage: String? = null,
    // "New Registered" tab: disposition tags from /pool/registered-by-date
    // (any of: new, assigned, in_batch, removed_archived). Empty for other pools.
    val regTypes: List<String> = emptyList()
)

data class CallAttempt(
    val id: String,
    val registrationId: String,
    val dateStr: String,
    val callerName: String,
    val outcome: String,
    val notes: String,
    val status: String = "done" // done, pending
)

data class BatchMoveRequest(
    val id: String,
    val registrationId: String,
    val studentName: String,
    val sourceBatchId: String,
    val targetBatchId: String,
    val reason: String,
    var status: String = "pending" // pending, approved, rejected
)

data class Notification(
    val id: String,
    val title: String,
    val body: String,
    val createdAt: String,
    var isRead: Boolean = false,
    val type: String = "alert",
    val referenceId: String? = null,      // user ID for access-request notifications
    val referenceUsername: String? = null  // username for password-reset
)

data class DropdownOption(
    val id: String,
    val label: String,
    val context: String, // address, remark, calling_outcome, followup_outcome
    val active: Boolean = true,
    val isGlobal: Boolean = false,
    // What the admin declared this response MEANS. The app never infers any of it from the label —
    // see ResponseMeaning for why that mattered. The defaults keep an older server working: it
    // sends none of these, and `not_reached` + `both` is the combination that neither creates a
    // follow-up nor strikes anyone, and hides no option from the caller.
    val sentiment: String = ResponseMeaning.NOT_REACHED,
    val answeredScope: String = ResponseMeaning.SCOPE_BOTH,
    val action: String? = null,
    val deferDays: Int? = null,
    val countsAsStrike: Boolean = false
)

/**
 * One row of the admin dictionary, as returned by the calling backend's /admin/dropdowns.
 *
 * Deliberately separate from [DropdownOption]: that one feeds the caller's outcome picker and holds
 * only the active rows for one context, whereas this holds every row (inactive included) across every
 * context and carries the fields management needs — the action it maps to, its deferral interval, and
 * whether it is a host-owned global. Merging the two would put disabled options into the picker.
 *
 * `context` here is the DB-exact value (calling, followup, batch_calling, my_attendance,
 * removed_reuse), NOT the calling_outcome/followup_outcome aliases the caller-facing GET accepts.
 */
data class AdminDropdownOption(
    val id: Int,
    val label: String,
    val context: String,
    val category: String,       // youth, congregation, both
    val action: String?,        // DropdownAction, e.g. continue_calling, defer_calling
    val deferDays: Int?,        // set only when action == defer_calling
    val sortOrder: Int,
    val isActive: Boolean,
    val isGlobal: Boolean,      // centre_id IS NULL — the shared option every centre starts from
    val centreId: Int?,
    // Set when this row is THIS centre's customised replacement for a shared option. Editing or
    // hiding a shared option produces one of these server-side; the shared row is never modified.
    val overridesId: Int? = null,
    val isOverride: Boolean = false,
    // The declared meaning the directory editor configures. Same defaults as DropdownOption, for
    // the same reason: an older server sends none of these and must keep working.
    val sentiment: String = ResponseMeaning.NOT_REACHED,
    val countsAsStrike: Boolean = false,
    val removeAfterConsecutive: Int? = null,
    val answeredScope: String = ResponseMeaning.SCOPE_BOTH,
    // Protects the responses the system relies on (Wrong number, Not interested) from deletion.
    val isSystem: Boolean = false
)

/**
 * One entry of a centre's remark or address list, from the ATTENDANCE backend (Flask), not the
 * calling one. `centreId` is null for a row shared by every centre — only the host may change
 * those, and the directory screen shows them read-only with the reason.
 */
data class FlaskOption(
    val id: Int,
    val text: String,
    val centreId: String?,
    val isShared: Boolean,
)

data class User(
    val id: String,
    val username: String,
    val name: String,
    val role: String, // SUPER_ADMIN, ADMIN, CALLER
    var status: String = "active", // pending, active, inactive
    var isAeAccessEnabled: Boolean = true,
    val centreId: String,
    val permissions: List<String> = emptyList(),
    var roleMode: String = "both" // registerer, caller, both — drives the Manage-Staff role picker
)

data class OfflineQueueItem(
    val id: String,
    val type: String,
    val label: String,
    val payload: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val lastAttemptAt: Long? = null,
    // "pending" (will auto-retry) | "failed_needs_attention" (gave up after MAX_QUEUE_ATTEMPTS,
    // needs a manual retry so a flaky connection can't silently lose the write forever).
    val status: String = "pending"
)

// An offline/failed call-outcome that hasn't been confirmed by the server yet. Kept so the caller's
// queue and Follow-ups tab look EXACTLY the same offline as online: the marked row shows as done
// (not pending), and a positive ("will attend") response surfaces in Follow-ups immediately. Cleared
// the moment its matching queue item syncs successfully (the live server response then takes over).
data class PendingCallOverride(
    val assignmentId: String,
    val outcome: String,           // the human label the caller chose
    val derivedStatus: String,     // coarse status: will_attend / wont_attend / no_answer / called
    val remark: String? = null,
    val positive: Boolean = false, // will_attend OR call_back_later → belongs in Follow-ups
    // Which Follow-ups heading this belongs under. A call-back is positive but is NOT a confirmed
    // yes, so it must never be rendered beside "Said they will attend" — offline or online.
    val followupKind: String = "will_attend",
    // Person info captured at mark time so an optimistic Follow-up card can render with no server call.
    val mobile: String = "",
    val name: String? = null,
    val sessionName: String? = null,
    val callerName: String = "You",
    val ts: Long = System.currentTimeMillis()
)

data class AuditLog(
    val id: String,
    val registrationId: String,
    val action: String,
    val actor: String,
    val timestamp: String
)

/** One finding of the server's stuck-data check (GET /integrity, lib/integrity.ts on the server). */
data class IntegrityIssue(
    val code: String,
    val severity: String, // "error" | "warning"
    val count: Int,
    val title: String,
    val action: String,
    val examples: List<String>,
)

data class DashboardStats(
    val assignedCalling: Int = 0,
    val pendingFollowups: Int = 0,
    val myAttendanceCount: Int = 0,
    val batchCallingCount: Int = 0,
    val registrationsCount: Int = 0,
    // ADMIN fields
    val newRegistrations: Int = 0,
    val activeCalling: Int = 0,
    val archivedCount: Int = 0,
    val removedCount: Int = 0,
    val totalRegistrations: Int = 0
)

data class CentreStatSummary(
    val centreId: String,      // numeric string matching Centre.id
    val centreCode: String,
    val registrations: Int,
    val activeCalling: Int,
    val followups: Int
)

data class HostStats(
    val centreCount: Int,
    val totalRegistrations: Int,
    val byCentre: List<CentreStatSummary> = emptyList()
)

data class AttendanceTrendPoint(val date: String, val count: Int)

data class AttendanceTrendBatch(
    val batchName: String,
    val centreId: String,
    val points: List<AttendanceTrendPoint>
)

data class CentreUserCount(
    val centreId: String,
    val centreName: String,
    val centreCode: String,
    val callerCount: Int,
    val adminCount: Int
)

// In-Memory Database representing full backend capability
object AppDatabase {
    // Persistent scope — outlives any individual Composable lifecycle.
    // loadDataFromBackend must use this, not the caller's scope, so navigation
    // away from the login screen doesn't cancel the in-flight data fetch.
    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var isOnline by mutableStateOf(true)

    var dataRevision by mutableStateOf(0L)

    fun bumpDataRevision() {
        dataRevision++
        Log.d("AppDatabase", "dataRevision bumped to $dataRevision")
    }

    /**
     * Bumped only when the SHAPE of the centre changes — a calling session or batch created,
     * renamed or deleted, a response list edited — never for the flow of attendance marks.
     *
     * Admin screens list things other people create, so they must react to somebody else’s work or
     * they show yesterday’s world until the operator switches tabs and back. They are also the most
     * expensive screens to reload (three calls behind the session pickers, a whole centre roster
     * behind those), and dataRevision moves on EVERY attendance mark — 115 of them in one log.
     * Keying the admin screens off that would refetch all of it once per person marked at the door.
     * Two signals is what lets a new calling session appear the moment it is made without paying
     * for it on every mark.
     */
    var structureRevision by mutableStateOf(0L)

    fun bumpStructureRevision() {
        structureRevision++
        Log.d("AppDatabase", "structureRevision bumped to $structureRevision")
    }

    // Auth State
    var currentUser by mutableStateOf<User?>(null)
    var mustChangePassword by mutableStateOf(false)
    var selectedCategory by mutableStateOf("Youth") // Youth or Congregation
    var selectedCentreId by mutableStateOf("")       // Node.js numeric ID
    var selectedFlaskCentreId by mutableStateOf("")  // Flask string code (e.g. "centre_b_Base")
    var currentMode by mutableStateOf("registerer") // registerer, caller

    // Node.js calling backend (auth, users, calling operations).
    // Served by the replica's Cloudflare tunnel (config-onlineattendanceapp.yml → :3000).
    var callingBackendUrl by mutableStateOf("https://calling.example.com")
    // Flask attendance backend (registrations, sessions, attendance marking)
    var backendUrl by mutableStateOf("https://attendance.example.com")

    var jwtToken by mutableStateOf<String?>(null)
    var attendanceJwtToken by mutableStateOf<String?>(null)
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()

    // ── Durable session persistence ───────────────────────────────────────────
    // jwtToken/attendanceJwtToken/currentUser used to be in-memory only, so every process
    // restart forced a live login round-trip — the app couldn't "open" at all without internet,
    // even though the offline-resume UI logic (LoginScreen's C6 LaunchedEffect) already existed
    // and just needed currentUser to be non-null when it ran. Reuses the same "attendease_prefs"
    // SharedPreferences the rest of the app already uses (no new persistence architecture).
    private var appContext: Context? = null

    fun initAppContext(context: Context) {
        ensureRestored(context)
        startConnectivityMonitor()
        // Phones that update into a build with background sync are already logged in and will not
        // pass through persistSession(), so arm the heartbeat here too. Idempotent.
        if (hasSavedLogin()) BackgroundSync.ensurePeriodic(context)
    }

    @Volatile private var stateRestored = false

    /**
     * Load the saved session, the offline queue and the pending overrides — once per process, and
     * nothing more. The background worker calls this on a cold process, where it must NOT start the
     * 1-second reachability loop that initAppContext starts. Idempotent on purpose: opening the app
     * while a worker is mid-drain must not re-read the queue from disk, which would bring back an
     * item the worker has just removed.
     */
    @Synchronized
    fun ensureRestored(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        if (stateRestored) return
        stateRestored = true
        restoreSession()
        restoreOfflineQueue()
        restoreOverrides()
        persistedQueueSize = offlineQueue.size
    }

    /** True when a login token is saved — the only credential background sync can act with. */
    fun hasSavedLogin(): Boolean = !jwtToken.isNullOrEmpty()

    fun pendingQueueCount(): Int = offlineQueue.count { it.status == "pending" }

    // Size of the queue the last time it was written to disk. A persist that finds the queue
    // LARGER means a write just joined it, which is the cue to ask for a background upload. A
    // persist that only shrinks it (a drain) or re-stamps attempts must not re-arm the job.
    private var persistedQueueSize = 0

    // ── Automatic connectivity monitor ─────────────────────────────────────────
    // `isOnline` used to be a manual toggle. It's now driven automatically by two signals:
    //   1. A ConnectivityManager callback — fires the instant the device gains/loses a network.
    //   2. A reachability poll — a real HTTP probe to the backend, because the failure mode here is
    //      usually the tunnel/server being down while WiFi is perfectly fine (the "530" case). A
    //      network callback alone would wrongly report "online" then.
    // Whenever we transition unreachable→reachable we flush the offline write queue automatically —
    // the "auto-sync when the server returns" the caller asked for, with no button to press.
    private var connectivityMonitorStarted = false
    private var reachabilityJob: Job? = null

    // Short-timeout client dedicated to the health probe so a dead server fails fast (≈4s) instead of
    // hanging on the main client's longer timeouts.
    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private fun startConnectivityMonitor() {
        if (connectivityMonitorStarted) return
        connectivityMonitorStarted = true
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.let {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { appScope.launch { refreshReachability() } }
                override fun onLost(network: Network) { updateOnlineState(false) }
            }
            try { it.registerDefaultNetworkCallback(cb) } catch (e: Exception) { /* older OEM quirk — poll still covers it */ }
        }
        // Poll loop: re-probe periodically so a server that comes back while the network never dropped
        // (tunnel restart) still flips us online without any user action.
        reachabilityJob = appScope.launch {
            // Ticks since the loop started. The loop now runs at 1s, but only the CHEAP call
            // belongs at that rate — everything else stays on the cadence it already had, keyed
            // off this counter. Speeding a loop up must not silently multiply every request in
            // it by five.
            var tick = 0L
            while (isActive) {
                // /health answers "can we reach the server", which is not a question that moves
                // second to second, and the answer drives no on-screen number. Kept at ~5s while
                // online. While OFFLINE every tick probes, because this is the only path back
                // online and delaying it delays recovery.
                if (!isOnline || tick % REACHABILITY_EVERY_N_TICKS == 0L) refreshReachability()
                if (isOnline) {
                    // One-shot drain at startup. `isOnline` starts out true, so an app opened
                    // with a working connection never sees the offline→online transition that
                    // used to be the only trigger — a queue built up the night before simply
                    // sat there until the network happened to blip or someone tapped the
                    // status chip. Deliberately one-shot: draining every tick would spend a
                    // genuinely failing item's four attempts inside two minutes and park it.
                    if (!didInitialDrain) {
                        didInitialDrain = true
                        syncOfflineQueue()
                    }
                    // Keep the mirrored centre cache current while we're connected. The
                    // checksum call is tiny and the full state is only re-pulled when it
                    // actually moved, so this is what keeps two devices agreeing — and what
                    // leaves the phone holding the server's exact state if it drops offline.
                    runCatching {
                        if (syncServerCacheIfChanged()) {
                            bumpDataRevision()
                        }
                    }
                    // A roster pull that its own rate floor deferred still has to happen once
                    // the floor elapses, even if the checksum has stopped moving since. Without
                    // this the LAST registration of a burst would never reach this device's roll:
                    // its checksum change was consumed by a tick that skipped the pull.
                    runCatching { if (drainOwedRosterPull()) bumpDataRevision() }
                    // Both of these keep their pre-1s cadence deliberately. The SSE state machine
                    // reconnects on a 5s heartbeat and gains nothing from being asked five times
                    // as often, and re-asking the version endpoint every second would be pure
                    // chatter for an answer that cannot change without a server-side edit.
                    if (tick % REACHABILITY_EVERY_N_TICKS == 0L) {
                        runCatching { RealtimeSseClient.updateState() }
                        if (updateStatus == null) runCatching { checkForUpdate() }
                    }
                }
                // 1s while connected. Two changes in AttendEase emit no event at all —
                // activating a session, and a mark being superseded by a later capture — so this
                // loop is the ONLY thing that ever sees them, and everyone marking or registering
                // needs to be looking at the same numbers within a second of each other.
                //
                // WHAT ACTUALLY RIDES THE 1s TICK, AND WHY ONLY THAT. The per-tick call is the
                // checksum read: checksum.json is ~70 BYTES. When it moves we pull state.json,
                // which is ~1.6 KB — still nothing. What must NOT ride it is the roster:
                // roster.json is 468 KB at Centre B and 303 KB at Centre A, and the
                // checksum fingerprints the roster's row count and newest updated_at, so during
                // a festival every registration by anyone moves it. Pulled once per tick that
                // would be ~7 MB/s across fifteen phones through the tunnel. It is floored at
                // ROSTER_PULL_MIN_INTERVAL_MS instead, which is the rate it already ran at, so
                // this change makes session state five times more live and costs the roster
                // nothing.
                delay(if (isOnline) 1_000L else 12_000L)
            }
        }
    }

    private var didInitialDrain = false

    /** Run the reachability probe, the SSE state machine and the version check on every Nth
     *  tick, so moving the loop to 1s did not multiply their request counts. 5 ticks = the 5s
     *  cadence they all had before. */
    private const val REACHABILITY_EVERY_N_TICKS = 5L

    /**
     * Floor on how often the full roster may be re-pulled, regardless of how often the checksum
     * moves. roster.json is 300-470 KB; the checksum that guards it is 70 bytes and moves on
     * every registration anywhere in the centre. Without this floor the 1s tick would pull the
     * whole roster once a second per device during a festival.
     */
    private const val ROSTER_PULL_MIN_INTERVAL_MS = 5_000L

    private var lastRosterPullMs = 0L

    /**
     * The centre a checksum change asked for a roster pull FOR, or null when nothing is owed.
     *
     * Carries the centre id rather than being a bare boolean, and that is the whole point. The
     * floor can span a centre switch: a flag alone would let the pull for the centre just
     * switched TO clear the debt owed to the centre just switched FROM, so the one left behind
     * would keep serving a roster it had already been told was out of date until its own
     * checksum happened to move again. The debt is honoured against the centre that incurred it.
     *
     * Cleared only on a pull that actually SUCCEEDS, so a deferred-then-failed pull is retried
     * rather than dropped. One residual: two centres' changes inside the same floor window means
     * the second overwrites the first, and the first heals on its next checksum move. A queue
     * would close that, and is not worth its own state for a case that needs a centre switch
     * inside five seconds.
     */
    private var rosterPullOwedFor: String? = null

    /**
     * Pull the roster if one is owed and the floor has elapsed. Returns true only when the
     * mirror was actually replaced, so the caller can bump dataRevision on that and nothing else.
     */
    private suspend fun drainOwedRosterPull(): Boolean {
        val owed = rosterPullOwedFor ?: return false
        if (owed.isEmpty() || !isOnline) return false
        val now = System.currentTimeMillis()
        if (now - lastRosterPullMs < ROSTER_PULL_MIN_INTERVAL_MS) return false
        // Stamped BEFORE the call, not after: a pull that hangs or fails must not be retried on
        // the very next tick, which is how a slow tunnel turns a floor into a flood.
        lastRosterPullMs = now
        // `owed`, never the currently selected centre — see rosterPullOwedFor.
        val ok = runCatching { refreshServerRoster(owed) }.getOrDefault(false)
        if (ok) rosterPullOwedFor = null
        return ok
    }

    // ── Forced update check ───────────────────────────────────────────────────
    // The app reports the build it is running to /api/app/version and the SERVER
    // decides whether it may carry on, by comparing against Server file/app_release.json.
    // Keeping the policy on the server means forcing an update is a one-file edit
    // rather than another app release — which would be circular.
    var updateStatus by mutableStateOf<AppUpdateStatus?>(null)
        private set

    /** This build's versionCode, read from the installed package rather than
     *  BuildConfig so it works regardless of buildFeatures configuration. */
    fun currentVersionCode(): Long {
        val ctx = appContext ?: return 0L
        return try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
        } catch (e: Exception) { 0L }
    }

    /**
     * Ask the server whether this build is still allowed. Silent on any failure:
     * an unreachable server, a timeout or a malformed reply must never gate the app,
     * because that would lock out exactly the offline users the rest of this file
     * exists to support. Only an explicit "you are too old" from the server blocks.
     */
    suspend fun checkForUpdate() = withContext(Dispatchers.IO) {
        val code = currentVersionCode()
        val raw = apiGet("/api/app/version?version_code=$code") ?: return@withContext
        try {
            val o = JSONObject(raw)
            if (!o.optBoolean("ok", false)) return@withContext
            val notesArr = o.optJSONArray("notes")
            val notes = if (notesArr == null) emptyList()
            else (0 until notesArr.length()).map { notesArr.optString(it, "") }
                .filter { it.isNotBlank() }
            val status = AppUpdateStatus(
                currentVersionCode = code,
                latestVersionCode = o.optLong("latest_version_code", code),
                latestVersionName = o.optString("latest_version_name", ""),
                updateAvailable = o.optBoolean("update_available", false),
                updateRequired = o.optBoolean("update_required", false),
                downloadPageUrl = backendUrl.trimEnd('/') + o.optString("download_page", "/app"),
                apkUrl = o.optString("apk_url", "").let {
                    if (it.isBlank()) "" else backendUrl.trimEnd('/') + it
                },
                notes = notes,
            )
            withContext(Dispatchers.Main) { updateStatus = status }
        } catch (e: Exception) { /* malformed reply — never block on it */ }
    }

    private fun updateOnlineState(value: Boolean) {
        if (isOnline == value) return
        appScope.launch(Dispatchers.Main) {
            val wasOffline = !isOnline
            isOnline = value
            if (value && wasOffline) syncOfflineQueue()  // reachable again → drain the queue
        }
    }

    /** Probe the backend. Any HTTP status (even 401/404) means "reachable"; only a transport failure
     *  (timeout/connection refused/DNS) counts as offline. Updates isOnline and auto-syncs on recovery. */
    suspend fun refreshReachability() = withContext(Dispatchers.IO) {
        updateOnlineState(probeBackendHealth())
    }

    /** One short health probe. Shared by the foreground monitor and the background worker. */
    suspend fun probeBackendHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$callingBackendUrl/health").get().build()
            // status < 500 means the origin answered (200/401/404 all fine). A 5xx — including the
            // Cloudflare 530 "origin down" — means the tunnel is up but the server can't serve, so we
            // treat it as offline and keep working on cache.
            probeClient.newCall(req).execute().use { it.code < 500 }
        } catch (e: Exception) { false }
    }

    /** Fire-and-forget connectivity re-check for UI buttons ("try to reconnect now"). Cooperates
     *  with the automatic monitor instead of blindly forcing isOnline. */
    fun recheckConnectivity() { appScope.launch { refreshReachability() } }

    /** Prime the in-memory datasets from the durable read-cache. Used on an offline resume so the
     *  batch/session pickers and rosters show last-known data immediately, before any (failing) fetch. */
    fun loadCachedData() {
        val cachedCentres = loadCentresCache()
        if (cachedCentres.isNotEmpty()) centres = cachedCentres
        if (selectedFlaskCentreId.isNotEmpty()) {
            val cb = loadBatchesCache(selectedFlaskCentreId); if (cb.isNotEmpty()) batches = cb
            val cs = loadSessionsCache(selectedFlaskCentreId); if (cs.isNotEmpty()) sessions = cs
        }
        val cachedDropdowns = loadDropdownCache()
        if (cachedDropdowns.isNotEmpty()) dropdownOptions = cachedDropdowns
        // The member list — the dataset that used to come back empty on every offline start,
        // taking the registerer's counts, lists and duplicate warning down with it.
        val cachedRegs = loadRegistrationsCache()
        if (cachedRegs.isNotEmpty()) registrations = cachedRegs
    }

    private fun serializeUser(u: User): String = JSONObject().apply {
        put("id", u.id); put("username", u.username); put("name", u.name); put("role", u.role)
        put("status", u.status); put("isAeAccessEnabled", u.isAeAccessEnabled); put("centreId", u.centreId)
        put("permissions", JSONArray(u.permissions))
    }.toString()

    private fun deserializeUser(json: String): User {
        val o = JSONObject(json)
        val permsArr = o.optJSONArray("permissions")
        val perms = mutableListOf<String>()
        if (permsArr != null) for (i in 0 until permsArr.length()) perms.add(permsArr.getString(i))
        return User(
            id = o.optString("id"), username = o.optString("username"), name = o.optString("name"),
            role = o.optString("role"), status = o.optString("status", "active"),
            isAeAccessEnabled = o.optBoolean("isAeAccessEnabled", true), centreId = o.optString("centreId"),
            permissions = perms
        )
    }

    private fun persistSession() {
        val prefs = appContext?.getSharedPreferences("attendease_prefs", Context.MODE_PRIVATE) ?: return
        prefs.edit()
            .putString("jwt_token", jwtToken)
            .putString("attendance_jwt_token", attendanceJwtToken)
            .putString("current_user_json", currentUser?.let { serializeUser(it) })
            // Centre context must survive an offline cold start, or the centre-keyed caches
            // (batches/sessions/roster) can't be found and every offline screen goes blank.
            .putString("sel_centre_id", selectedCentreId)
            .putString("sel_flask_centre_id", selectedFlaskCentreId)
            .putString("sel_category", selectedCategory)
            .putString("current_mode", currentMode)
            // Having logged in successfully once while online IS the offline-login authorization:
            // from now on the app may auto-resume this identity with the server unreachable.
            .putBoolean("offline_authorized", currentUser != null)
            .apply()
        if (hasSavedLogin()) appContext?.let { BackgroundSync.ensurePeriodic(it) }
    }

    private fun restoreSession() {
        val prefs = appContext?.getSharedPreferences("attendease_prefs", Context.MODE_PRIVATE) ?: return
        jwtToken = prefs.getString("jwt_token", null)
        attendanceJwtToken = prefs.getString("attendance_jwt_token", null)
        prefs.getString("current_user_json", null)?.let { json ->
            try { currentUser = deserializeUser(json) } catch (e: Exception) { /* corrupt/old shape — ignore */ }
        }
        prefs.getString("sel_centre_id", "")?.let { if (it.isNotEmpty()) selectedCentreId = it }
        prefs.getString("sel_flask_centre_id", "")?.let { if (it.isNotEmpty()) selectedFlaskCentreId = it }
        prefs.getString("sel_category", "")?.let { if (it.isNotEmpty()) selectedCategory = it }
        prefs.getString("current_mode", "")?.let { if (it.isNotEmpty()) currentMode = it }
    }

    // ── Sticky UI selections ─────────────────────────────────────────────────────────────────
    // A dropdown pick is a statement about WHERE the operator is working, not about the row in
    // front of them, so it must outlive the things that were wiping it: a form submit, a drawer
    // tab switch, and an app restart.
    //
    // It lives here rather than in `rememberSaveable` because `MainDrawerShell` swaps screens
    // through a `when` on `activeScreen` — leaving a screen DISPOSES its whole composable branch,
    // and saved-state entries go with it (there is no SaveableStateHolder around those branches).
    // Riding the same SharedPreferences file as sel_centre_id / sel_category also means a pick
    // survives an OFFLINE cold start, which is the state a phone at the door is often in.
    //
    // Scoped keys are namespaced by centre + category. The same logical key under
    // Centre A/Youth and Centre A/Congregation are different selections; restoring one into
    // the other would quietly put the operator on a batch from the wrong track — the same class
    // of bug as the roster category leak documented on mergeServerRosterInto.
    private fun uiPrefKey(key: String, scoped: Boolean): String =
        if (scoped) "ui.$key.$selectedFlaskCentreId.${selectedCategory.lowercase()}" else "ui.$key"

    /** Last sticky value for [key], or null when never set / cleared. */
    fun uiSelection(key: String, scoped: Boolean = true): String? {
        val prefs = appContext?.getSharedPreferences("attendease_prefs", Context.MODE_PRIVATE) ?: return null
        return prefs.getString(uiPrefKey(key, scoped), null)?.ifEmpty { null }
    }

    /** Remember [value] for [key]; null or empty CLEARS it (an explicit "Reset" must not be
     *  restored on the next visit). */
    fun setUiSelection(key: String, value: String?, scoped: Boolean = true) {
        val prefs = appContext?.getSharedPreferences("attendease_prefs", Context.MODE_PRIVATE) ?: return
        val k = uiPrefKey(key, scoped)
        if (value.isNullOrEmpty()) prefs.edit().remove(k).apply() else prefs.edit().putString(k, value).apply()
    }

    /**
     * Today, as the sticky keys stamp it. The attendance-marking selection is deliberately
     * DAY-BOUNDED: within a working day a pick sticks across tabs and restarts, but the next day
     * starts fresh on whatever session the server says is active. Restoring yesterday's batch
     * into today's class is the failure `serverActiveSession()` exists to prevent (see its
     * comment above), so the stickiness is not allowed to outlive the day that produced it.
     */
    fun uiToday(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    /** True when the sticky selections under [dayKey] were stored today. */
    fun uiSelectionIsFromToday(dayKey: String): Boolean = uiSelection(dayKey) == uiToday()

    /** Drop every sticky selection. Used on logout: a new operator must not inherit the previous
     *  one's working position, and a restored drawer screen especially would drop a registerer
     *  onto an admin page they have no permission for. */
    fun clearUiSelections() {
        val prefs = appContext?.getSharedPreferences("attendease_prefs", Context.MODE_PRIVATE) ?: return
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("ui.") }.forEach { editor.remove(it) }
        editor.apply()
    }

    /**
     * A stable id for THIS installation, minted once and kept for the life of the install.
     *
     * Several operators mark attendance at the same centre, at the same time, signed in to the
     * SAME shared account. The username therefore cannot identify who produced a row, which is
     * why the 2026-08-03 duplicate could not be attributed to a device without correlating
     * server logs against device logs by hand. Sending this on every mark makes that a query.
     *
     * Deliberately not tied to any hardware identifier: it identifies an install, not a person
     * or a handset, needs no permission, and disappears when the app is uninstalled.
     */
    fun deviceId(): String {
        val prefs = appContext?.getSharedPreferences("attendease_prefs", Context.MODE_PRIVATE)
            ?: return "unknown-device"
        prefs.getString("device_id", null)?.let { if (it.isNotEmpty()) return it }
        val fresh = "dev_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().putString("device_id", fresh).apply()
        return fresh
    }

    /** True once the user has completed at least one online login on this device. Lets the login
     *  screen admit a returning user with the server down, working entirely on cached data. */
    fun isOfflineLoginAuthorized(): Boolean {
        val prefs = appContext?.getSharedPreferences("attendease_prefs", Context.MODE_PRIVATE) ?: return false
        return prefs.getBoolean("offline_authorized", false) && currentUser != null
    }

    private fun clearPersistedSession() {
        val prefs = appContext?.getSharedPreferences("attendease_prefs", Context.MODE_PRIVATE) ?: return
        prefs.edit()
            .remove("jwt_token").remove("attendance_jwt_token").remove("current_user_json")
            .remove("sel_centre_id").remove("sel_flask_centre_id").remove("sel_category")
            .remove("current_mode").remove("offline_authorized")
            .apply()
    }

    // ── Durable offline write queue ────────────────────────────────────────────
    // The queue used to be in-memory only, and syncOfflineQueue() cleared it BEFORE confirming
    // any replay succeeded — a failed replay (or a process kill mid-sync) silently lost the write
    // forever, with no attempt count and nothing surviving restart. Persisted the same way as the
    // session (attendease_prefs, JSON), only removing an item once its write is CONFIRMED successful.
    const val MAX_QUEUE_ATTEMPTS = 4

    /** Serialize a specific queue snapshot. Split out from persistOfflineQueue so the
     *  data-dump upload can send byte-for-byte the same list it later deletes from — the
     *  ids and the uploaded bytes must describe one single moment, or we could remove a row
     *  that never made it into the file the server wrote. */
    private fun serializeQueue(items: List<OfflineQueueItem>): String {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id); put("type", item.type); put("label", item.label)
                put("payload", item.payload); put("timestamp", item.timestamp)
                put("attempts", item.attempts)
                put("lastAttemptAt", item.lastAttemptAt ?: JSONObject.NULL)
                put("status", item.status)
            })
        }
        return arr.toString()
    }

    private fun persistOfflineQueue() {
        val prefs = appContext?.getSharedPreferences("attendease_prefs", Context.MODE_PRIVATE) ?: return
        prefs.edit().putString("offline_queue_json", serializeQueue(offlineQueue)).apply()
        val grew = offlineQueue.size > persistedQueueSize
        persistedQueueSize = offlineQueue.size
        // Leaving the app straight after a save is the normal case at a door. Hand the new write to
        // the background job so it goes up when a network is there, app open or not.
        if (grew && pendingQueueCount() > 0) appContext?.let { BackgroundSync.requestUpload(it) }
    }

    private fun restoreOfflineQueue() {
        val prefs = appContext?.getSharedPreferences("attendease_prefs", Context.MODE_PRIVATE) ?: return
        val raw = prefs.getString("offline_queue_json", null) ?: return
        try {
            val arr = JSONArray(raw)
            val restored = mutableListOf<OfflineQueueItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                restored += OfflineQueueItem(
                    id = o.getString("id"), type = o.getString("type"), label = o.getString("label"),
                    payload = o.getString("payload"), timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                    attempts = o.optInt("attempts", 0),
                    lastAttemptAt = if (o.isNull("lastAttemptAt")) null else o.optLong("lastAttemptAt"),
                    status = o.optString("status", "pending")
                )
            }
            offlineQueue = restored
        } catch (e: Exception) { /* corrupt/old shape — start with an empty queue */ }
    }

    /**
     * Replays one queued write and reports whether it actually succeeded (not fire-and-forget).
     * On success, mirrors the same local-state update the online path performs (registrations /
     * callAttempts), so the item reappears in on-screen lists exactly as if it had gone through
     * the online branch the first time.
     */
    private suspend fun replayQueueItem(item: OfflineQueueItem): Boolean = withContext(Dispatchers.IO) {
        try {
            when (item.type) {
                "register_student" -> {
                    val parts = OfflinePayload.decodeRegisterStudent(item.payload)
                        ?: return@withContext false
                    val json = JSONObject().apply {
                        put("name", parts.name); put("mobile", parts.mobile); put("address", parts.address)
                        put("occupation", parts.occupation); put("remark", parts.remark)
                        put("batch_id", parts.batchId)
                        // Not stated by an older queued item; the server then falls back to the
                        // caller's own primary centre.
                        if (parts.centreId.isNotEmpty()) put("centre_id", parts.centreId)
                        // The category chosen when this was CAPTURED. It is the only trustworthy
                        // source here: selectedCategory is whatever the toggle happens to say NOW,
                        // which for an item queued yesterday is a coin flip. Older items never
                        // recorded one, so they fall back to it as before.
                        put("category", parts.category ?: selectedCategory)
                        put("device_id", deviceId())
                        // A walk-in queued from the Attendance tab must replay as a registration
                        // AND an attendance. Older items stop short and simply register, which is
                        // what they meant when they were queued.
                        if (parts.markPresent) {
                            put("mark_present", true)
                            put("session_id", parts.sessionId)
                            put("date", parts.date)
                        }
                    }.toString()
                    val ok = apiPost("/api/register_student", json) != null
                    if (ok) {
                        withContext(Dispatchers.Main) {
                            registrations = registrations + Registration(
                                id = "r_${System.currentTimeMillis()}", name = parts.name, mobile = parts.mobile,
                                address = parts.address, occupation = parts.occupation, remark = parts.remark,
                                centreId = parts.centreId.ifEmpty { selectedFlaskCentreId },
                                category = parts.category ?: selectedCategory,
                                batchIds = listOfNotNull(parts.batchId.ifEmpty { null })
                            )
                        }
                    }
                    ok
                }
                "mark_attendance" -> {
                    val f = OfflinePayload.decodeMarkAttendance(item.payload)
                        ?: return@withContext false
                    val ctx = if (!f.isLegacy) {
                        // Every field was captured when the user tapped save, so the replay
                        // sends exactly what they meant — no lookups, no re-dating.
                        AttendanceMarkContext(
                            mobile = f.mobile, sessionId = f.sessionId,
                            date = f.date, centreId = f.centreId
                        )
                    } else {
                        // A three-field item queued by an older build and still on the device.
                        // Recover it rather than drop it: the mobile is embedded in the id, and
                        // leaving session/date empty makes the server resolve its own active
                        // session — a far better answer than the "s1" this used to fabricate.
                        AttendanceMarkContext(
                            mobile = registrations.find { it.id == f.studentId }?.mobile
                                ?.takeIf { it.isNotBlank() }
                                ?: OfflinePayload.mobileFromRegistrationId(f.studentId),
                            sessionId = "", date = "", centreId = selectedFlaskCentreId
                        )
                    }
                    // Without a valid mobile the server returns 400 every time, so retrying
                    // only burns the attempt budget. Fail straight away and let the item park
                    // for central recovery instead of pretending it might succeed.
                    if (ctx.mobile.isBlank()) return@withContext false
                    val ok = postMarkAttendanceIdempotent(
                        buildMarkAttendanceJson(
                            ctx, f.outcome, f.note,
                            f.category.ifBlank { selectedCategory },
                            // The queue item's OWN stamp — when the operator actually tapped,
                            // which may be hours before this replay runs. Passing "now" here
                            // would let a late sync outrank a correction made in between, and
                            // would quietly undo it. This value has always been recorded on the
                            // item; it just never left the device.
                            capturedAtMillis = item.timestamp,
                        )
                    )
                    if (ok) {
                        withContext(Dispatchers.Main) {
                            callAttempts = callAttempts + CallAttempt(
                                id = "ca_${System.currentTimeMillis()}", registrationId = f.studentId,
                                dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()),
                                callerName = currentUser?.name ?: "Staff", outcome = f.outcome, notes = f.note
                            )
                        }
                    }
                    ok
                }
                "update_call_status" -> {
                    // Items parked by an older build carry three or four fields, not five; decode
                    // returns null for the meaning-carrying keys it cannot find, and an omitted key
                    // is what the server has always treated as "not stated".
                    val parked = CallOutcomeQueue.decode(item.payload) ?: return@withContext false
                    val assignmentId = parked.assignmentId
                    val json = JSONObject().apply {
                        put("outcome", parked.outcome)
                        if (!parked.remark.isNullOrBlank()) put("remark", parked.remark.trim())
                        if (parked.answered != null) put("answered", parked.answered)
                        // The field that makes a parked outcome survive a rename made while it was
                        // sitting here.
                        if (parked.dropdownId != null) put("dropdown_id", parked.dropdownId)
                    }.toString()
                    val ok = callingApiPatch("/session-call-queue/$assignmentId", json) != null
                    // NOTE: a 2xx here does NOT clear the optimistic override. Only a fetch that
                    // comes back from the SERVER already showing this row answered does that — see
                    // applyOverridesToQueue. Clearing on the write acknowledgement is what made a
                    // just-saved answer vanish: a GET issued moments earlier is still in flight,
                    // was computed before this PATCH committed, and so returns callStatus
                    // "pending"; with the paint already dropped there was nothing left to cover
                    // the stale row and the caller saw their response disappear.
                    //
                    // A late outcome is NOT rejected any more: updateSessionCallStatus deliberately
                    // accepts responses for a closed or completed session, because an offline caller
                    // who gets signal back after the class ended would otherwise lose every answer
                    // they recorded. (This comment used to say the opposite, describing the older
                    // "only syncs while the session is open" rule.) apiPatch still returns null on
                    // any non-2xx, so anything the server genuinely refuses stays queued and retries
                    // rather than being dropped.
                    ok
                }
                "submit_attendance" -> {
                    // Durable form of submitAttendance (the registerer's Mark-Attendance / Add-Student
                    // screen). Payload carries every field so the replay is a faithful re-POST.
                    val p = OfflinePayload.decodeSubmitAttendance(item.payload)
                        ?: return@withContext false
                    val json = JSONObject().apply {
                        put("mobile", p.mobile); put("name", p.name); put("session_id", p.sessionId)
                        put("date", p.date); put("rounds", p.rounds); put("remark", p.remark)
                        put("address", p.address); put("occupation", p.occupation); put("batch_id", p.batchId)
                        // Older queued items won't carry a centre.
                        if (p.centreId.isNotEmpty()) put("centre_id", p.centreId)
                        // Category and capture time are both optional because items queued by a
                        // build before they existed are still sitting in phones. A replay with no
                        // stored category is sent WITHOUT one rather than being stamped with
                        // whatever the toggle happens to show now — the server then applies its
                        // long-standing youth default, which is the honest answer for a payload
                        // that genuinely never recorded which class it was taken in.
                        p.category?.let { put("category", it) }
                        p.capturedAtMillis?.let { put("client_marked_at", it) }
                        put("device_id", deviceId())
                    }.toString()
                    postMarkAttendanceIdempotent(json)
                }
                else -> false
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { false }
    }

    /** Re-arms a parked failed_needs_attention item so the next sync retries it. */
    fun retryFailedItem(id: String) {
        offlineQueue = offlineQueue.map { if (it.id == id) it.copy(status = "pending") else it }
        persistOfflineQueue()
        syncOfflineQueue()
    }

    /** Re-arms EVERY parked failed item so the next sync retries them all at once. */
    fun retryAllFailedItems() {
        offlineQueue = offlineQueue.map {
            if (it.status == "failed_needs_attention") it.copy(status = "pending") else it
        }
        persistOfflineQueue()
        syncOfflineQueue()
    }

    /** Raw JSON of the entire offline queue (pending + failed), straight from the
     *  persisted cache — nothing lost. Used to recover data stuck on the device. */
    fun offlineQueueRawJson(): String {
        val ctx = appContext ?: return "[]"
        return ctx.getSharedPreferences("attendease_prefs", Context.MODE_PRIVATE)
            .getString("offline_queue_json", "[]") ?: "[]"
    }

    /** Dump the raw offline-queue JSON to a file in the app's external files dir so
     *  stuck data can be recovered directly (pull via adb, or open in a file
     *  manager at Android/data/com.example/files/). Returns the absolute path, or
     *  null on failure. */
    fun exportOfflineQueueToFile(): String? {
        val ctx = appContext ?: return null
        return try {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val file = java.io.File(dir, "failed_data_export.json")
            file.writeText(offlineQueueRawJson())
            file.absolutePath
        } catch (e: Exception) { null }
    }

    /** Push the ENTIRE offline queue (pending + failed + corrupted) to the CB server
     *  tagged with this user's username + role, so stuck data is centrally recoverable
     *  by an admin without adb/the device. Best-effort — returns true if accepted. */
    suspend fun uploadDataDump(reason: String): Boolean {
        val raw = offlineQueueRawJson()
        val pending = offlineQueue.count { it.status == "pending" }
        val failed = offlineQueue.count { it.status == "failed_needs_attention" }
        // Identify the phone, not just the login. A dump used to say only "unified", so an
        // emergency months later could tell you WHAT was stuck but never WHICH device it was
        // stuck on — and with a shared login, the username does not narrow it down either.
        val body = JSONObject().apply {
            put("raw_json", raw)
            put("reason", reason)
            put("pending", pending)
            put("failed", failed)
            put("app_version", "unified")
            put("device_id", deviceId())
            put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android_release", Build.VERSION.RELEASE)
            put("android_sdk", Build.VERSION.SDK_INT)
            put("version_code", currentVersionCode())
            put("centre_id", selectedFlaskCentreId)
            put("category", selectedCategory)
        }.toString()
        val result = callingApiPost("/client/data-dump", body) ?: return false
        return try { JSONObject(result).optBoolean("ok", false) } catch (e: Exception) { false }
    }

    /**
     * Upload the queue and, once the server confirms it is stored, delete from the device
     * exactly the parked rows contained in the copy we sent.
     *
     * Parked rows used to accumulate on the phone forever, even after an admin already had
     * them. The server keeps every upload as its own timestamped file under
     * client-dumps/<user>/, preserving the raw payload even when it is unparseable, so once
     * it answers with the path it wrote, that file — not the phone — is the record of
     * record and the device copy is safe to drop.
     *
     * Three deliberate guards, because this is the one place that deletes user data:
     *   - only rows already parked as failed_needs_attention; anything still `pending` has
     *     retries left and is never touched,
     *   - only ids present in the snapshot that was actually uploaded, so a row added while
     *     the upload was in flight survives,
     *   - only on ok:true WITH the stored path echoed back; any error, timeout or
     *     ambiguous reply leaves the queue exactly as it was.
     *
     * Returns how many rows were cleared.
     */
    suspend fun uploadAndClearParked(reason: String): Int {
        val snapshot = offlineQueue
        val parkedIds = snapshot.filter { it.status == "failed_needs_attention" }.map { it.id }.toSet()
        if (parkedIds.isEmpty()) return 0
        val body = JSONObject().apply {
            put("raw_json", serializeQueue(snapshot))
            put("reason", reason)
            put("pending", snapshot.count { it.status == "pending" })
            put("failed", parkedIds.size)
            put("app_version", "unified")
        }.toString()
        val result = callingApiPost("/client/data-dump", body) ?: return 0
        val stored = try {
            val o = JSONObject(result)
            if (o.optBoolean("ok", false)) o.optString("stored", "") else ""
        } catch (e: Exception) { "" }
        if (stored.isBlank()) return 0
        var cleared = 0
        withContext(Dispatchers.Main) {
            val before = offlineQueue.size
            // Re-check the status here as well as the id: if the user hit "retry" on a row
            // while the upload was in flight it is `pending` again, and clearing it would
            // throw away a write that is still trying to land.
            offlineQueue = offlineQueue.filterNot {
                it.id in parkedIds && it.status == "failed_needs_attention"
            }
            cleared = before - offlineQueue.size
            persistOfflineQueue()
        }
        return cleared
    }

    // ── Optimistic call-outcome overrides ─────────────────────────────────────
    /**
     * The status to paint a just-marked row with, before the server has answered.
     *
     * Looks the response up in the cached option list and reads the meaning the admin declared;
     * it does NOT read the words. An option this build has not cached (first run, or a response
     * added while offline) resolves to the neutral "called" rather than to a guess — see
     * [ResponseMeaning.derive].
     */
    fun deriveCallStatusLocal(outcome: String): String {
        val option = dropdownOptions.firstOrNull {
            it.context == "calling_outcome" && it.label.equals(outcome, ignoreCase = true)
        } ?: dropdownOptions.firstOrNull { it.label.equals(outcome, ignoreCase = true) }
        return ResponseMeaning.derive(option?.action, option?.sentiment)
    }

    private fun persistOverrides() {
        val prefs = cachePrefs() ?: return
        val arr = JSONArray()
        pendingCallOverrides.values.forEach { o ->
            arr.put(JSONObject().apply {
                put("assignmentId", o.assignmentId); put("outcome", o.outcome)
                put("derivedStatus", o.derivedStatus); put("remark", o.remark ?: JSONObject.NULL)
                put("positive", o.positive); put("followupKind", o.followupKind)
                put("mobile", o.mobile); put("name", o.name ?: JSONObject.NULL)
                put("sessionName", o.sessionName ?: JSONObject.NULL); put("callerName", o.callerName); put("ts", o.ts)
            })
        }
        prefs.edit().putString("pending_call_overrides", arr.toString()).apply()
    }

    private fun restoreOverrides() {
        val prefs = cachePrefs() ?: return
        val raw = prefs.getString("pending_call_overrides", null) ?: return
        try {
            val arr = JSONArray(raw)
            val restored = mutableMapOf<String, PendingCallOverride>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("assignmentId")
                restored[id] = PendingCallOverride(
                    assignmentId = id,
                    outcome = o.optString("outcome"),
                    derivedStatus = o.optString("derivedStatus", "called"),
                    remark = if (o.isNull("remark")) null else o.optString("remark").ifBlank { null },
                    positive = o.optBoolean("positive", false),
                    followupKind = o.optString("followupKind", "will_attend").ifEmpty { "will_attend" },
                    mobile = o.optString("mobile", ""),
                    name = if (o.isNull("name")) null else o.optString("name").ifBlank { null },
                    sessionName = if (o.isNull("sessionName")) null else o.optString("sessionName").ifBlank { null },
                    callerName = o.optString("callerName", "You"),
                    ts = o.optLong("ts", System.currentTimeMillis())
                )
            }
            pendingCallOverrides = restored
        } catch (e: Exception) { /* corrupt/old shape — start empty */ }
    }

    /**
     * Drop the optimistic paint for every row the SERVER now reports as answered.
     *
     * "Answered" is any status other than `pending`: pending is only ever the initial state of an
     * assignment, and no outcome the caller can pick resolves back to it, so a non-pending row in a
     * live reply proves this caller's response reached the server and is being read back.
     *
     * A row that has left the caller's queue entirely (session completed, person removed) is also
     * retired — there is no longer anything on screen for the paint to cover, and holding it would
     * keep a stale optimistic follow-up card alive forever.
     *
     * Deliberately driven by what the server SHOWS rather than by our write succeeding. The two look
     * equivalent and are not: the write's 2xx says the server accepted it, while this says the server
     * is now serving it back — and only the second one makes a concurrent refetch safe to paint.
     */
    private suspend fun retireConfirmedOverrides(serverList: List<SessionCallAssignment>) {
        val confirmed = CallOutcomeQueue.confirmedOverrideIds(
            overrideIds = pendingCallOverrides.keys,
            serverStatusById = serverList.associate { it.id to it.callStatus },
        )
        if (confirmed.isEmpty()) return
        withContext(Dispatchers.Main) {
            pendingCallOverrides = pendingCallOverrides - confirmed
            persistOverrides()
        }
    }

    private fun putOverride(o: PendingCallOverride) {
        pendingCallOverrides = pendingCallOverrides + (o.assignmentId to o)
        persistOverrides()
    }

    // internal, not private: the registration screen dedupes its own just-filed entries against
    // the mirrored roster and must use the SAME key this file already uses for that comparison.
    // A second, slightly different rule (`mobile.takeLast(10)` on the raw string) silently fails
    // to match anything stored with a prefix, and the person then shows twice forever.
    internal fun lastTenDigits(m: String): String = m.filter { it.isDigit() }.takeLast(10)

    // ── Durable read-cache for batch/session/roster lists ─────────────────────
    // Populated on every successful fetch; read back only when the live fetch fails (offline / cold
    // start with no network) so the batch picker, session dropdown, and roster show the last-known
    // data instead of going blank. Same "attendease_prefs" SharedPreferences as session/queue persistence.
    private fun cachePrefs() = appContext?.getSharedPreferences("attendease_prefs", Context.MODE_PRIVATE)

    // ── Server-side centre cache mirror ───────────────────────────────────────
    // The Flask backend already maintains a per-centre cache on disk — Server file/cache/
    // <centre_id>/{state,roster,checksum}.json — rewritten atomically by
    // regenerate_cache_files() on every mutation that touches the centre. That file IS the
    // one copy every device sees, so mirroring it here is what makes two phones agree, and
    // what leaves a phone that loses signal holding precisely what the server held at the
    // moment contact was lost.
    //
    // The Unified app never called these endpoints at all: it derived everything from
    // /api/show_attendance, which is why nothing survived a restart and why people who are
    // registered but have never attended were invisible to it. We store each response body
    // verbatim, so re-reading the cache offline is byte-for-byte what the server last said.
    private fun serverStateKey(centreId: String, category: String) =
        "cached_server_state_${centreId}_${category.lowercase()}"

    private fun serverRosterKey(centreId: String) = "cached_server_roster_$centreId"

    private fun serverChecksumKey(centreId: String) = "cached_server_checksum_$centreId"

    /**
     * Mirror /api/cache/state for this centre+category. Returns true when refreshed.
     *
     * Sends this device's OWN session id when it has one. Without it the server answers for
     * the centre's single shared active-session slot, so with two classes running every device
     * receives the same marked_today_count — and the second class's operator watches the first
     * class's number climb, which is exactly what happened on 2026-08-03.
     */
    suspend fun refreshServerState(
        centreId: String = selectedFlaskCentreId,
        category: String = selectedCategory,
    ): Boolean = withContext(Dispatchers.IO) {
        if (centreId.isEmpty()) return@withContext false
        val cat = category.lowercase().ifBlank { "youth" }
        val ownSession = serverActiveSession()?.sessionId.orEmpty()
        val sessionParam =
            if (ownSession.isNotEmpty()) "&session_id=" + java.net.URLEncoder.encode(ownSession, "UTF-8")
            else ""
        val raw = apiGet("/api/cache/state?centre_id=$centreId&category=$cat$sessionParam")
            ?: return@withContext false
        cachePrefs()?.edit()?.putString(serverStateKey(centreId, cat), raw)?.apply()
        true
    }

    /** Mirror /api/cache/roster (the centre's registered roster) for this centre. */
    suspend fun refreshServerRoster(centreId: String = selectedFlaskCentreId): Boolean =
        withContext(Dispatchers.IO) {
            if (centreId.isEmpty()) return@withContext false
            val raw = apiGet("/api/cache/roster?centre_id=$centreId") ?: return@withContext false
            // Only overwrite a good mirror with something that actually parses as a list —
            // an error body must never be allowed to blank out the offline roster.
            try { JSONArray(raw) } catch (e: Exception) { return@withContext false }
            cachePrefs()?.edit()?.putString(serverRosterKey(centreId), raw)?.apply()
            true
        }

    // ── Live per-batch roster index ─────────────────────────────────────────────────────────
    //
    // Built from the SAME mirrored roster.json that the 5s connectivity tick already refreshes,
    // so it reflects what EVERY operator on every device has registered — not just this phone.
    // That is what makes the count on the registration screen a real, shared number during a
    // festival, where a dozen people are filing into one batch at once.
    //
    // It reads the mirror directly rather than `registrations`, deliberately. The tick refreshes
    // the mirror and bumps dataRevision, but `registrations` is only re-merged by a full
    // loadDataFromBackend pass (mergeServerRosterInto is called from exactly one place), so a
    // count derived from `registrations` would sit still all evening while the mirror underneath
    // it moved.
    //
    // Memoised on the mirror's own content because the registration screen recomposes on every
    // keystroke: re-parsing 1,800 JSON rows per character is the input-lag failure this codebase
    // has already been bitten by twice. The parse runs only when the mirror actually changes.
    private var rosterIndexKey: String? = null
    private var rosterIndexValid = false
    private var rosterIndexCounts: Map<String, Int> = emptyMap()
    private var rosterIndexRows: Map<String, List<BatchRosterRow>> = emptyMap()

    private fun rosterMirrorRaw(centreId: String): String =
        if (centreId.isEmpty()) "" else (cachePrefs()?.getString(serverRosterKey(centreId), null) ?: "")

    /**
     * Group a roster.json body by batch id.
     *
     * Returns null when the body is present but does not parse — which the caller must NOT
     * flatten into "no members". A mirror that will not parse has to leave the screen showing
     * nothing rather than zero: "0 members" against a batch of 698 people is exactly the defect
     * this whole index replaces, and re-inventing it from a parse failure would be worse.
     *
     * Pure and internal so the counting rule can be tested against real roster shapes without a
     * device, a context, or a SharedPreferences mirror.
     */
    internal fun indexRosterJson(raw: String): Map<String, List<BatchRosterRow>>? {
        if (raw.isBlank()) return emptyMap()
        val rows = HashMap<String, MutableList<BatchRosterRow>>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // A centre-level row (batch_id IS NULL) belongs to no batch — 1,601 of Bazar
                // Samati's 1,804 rows are exactly that. Counting them against a batch would
                // inflate every number on this screen.
                if (o.isNull("batch_id")) continue
                val bid = o.optInt("batch_id", -1)
                if (bid < 0) continue
                fun optText(key: String): String? =
                    if (o.isNull(key)) null else o.optString(key, "").trim().ifBlank { null }
                rows.getOrPut(bid.toString()) { mutableListOf() }.add(
                    BatchRosterRow(
                        mobile = o.optString("mobile", ""),
                        name = o.optString("name", "").trim().ifBlank { "Unknown" },
                        registeredBy = optText("registered_by"),
                        createdAt = optText("created_at"),
                    )
                )
            }
        } catch (e: Exception) {
            return null
        }
        // Newest first WHEN the server tells us when a row was written, otherwise left exactly
        // as roster.json ordered it (by name). The sort is stable and puts rows without a
        // timestamp last, so a partially-stamped roster degrades to "the dated ones on top,
        // everyone else in name order" rather than to an arbitrary shuffle.
        return if (rows.values.any { list -> list.any { it.createdAt != null } }) {
            rows.mapValues { (_, list) ->
                list.sortedWith(compareByDescending { it.createdAt ?: "" })
            }
        } else {
            rows
        }
    }

    private fun ensureRosterIndex(centreId: String) {
        val raw = rosterMirrorRaw(centreId)
        val key = "$centreId|${raw.length}|${raw.hashCode()}"
        if (key == rosterIndexKey) return
        rosterIndexKey = key
        val rows = indexRosterJson(raw)
        rosterIndexValid = rows != null && raw.isNotBlank()
        rosterIndexRows = rows ?: emptyMap()
        // Derived from the rows rather than counted alongside them, so the number on the picker
        // and the length of the list under the form can never disagree.
        rosterIndexCounts = rosterIndexRows.mapValues { it.value.size }
    }

    /**
     * True when this centre's roster has been mirrored at least once AND parses.
     *
     * Callers MUST check this before showing a count. Without it every batch counts zero, and a
     * zero meaning "not known yet" is indistinguishable on screen from a zero meaning "nobody is
     * in this batch" — which is the whole bug being fixed here.
     */
    fun hasRosterMirror(centreId: String = selectedFlaskCentreId): Boolean {
        ensureRosterIndex(centreId)
        return rosterIndexValid
    }

    /**
     * How many people are filed under each batch of this centre, keyed by batch id.
     *
     * THE BUG THIS REPLACES. The batch picker read `Batch.memberCount`, parsed from a
     * `member_count` field on /api/batches — and Flask's `Batch.to_dict()` has never emitted
     * that field at all. `optInt("member_count", 0)` therefore returned 0 for every batch in
     * every centre, so the picker confidently reported "0 members" against batches holding 698
     * and 434 people. Counting the mirrored roster gives the real number and, unlike a new
     * server field, needs no backend change and works offline.
     */
    fun batchMemberCounts(centreId: String = selectedFlaskCentreId): Map<String, Int> {
        ensureRosterIndex(centreId)
        return rosterIndexCounts
    }

    /**
     * The people filed under one batch, as the server last reported them.
     *
     * Ordered as roster.json is ordered, which is by name — the server emits no created_at on
     * these rows, so newest-first is not derivable on the client.
     */
    fun batchRosterRows(batchId: String, centreId: String = selectedFlaskCentreId): List<BatchRosterRow> {
        ensureRosterIndex(centreId)
        return rosterIndexRows[batchId] ?: emptyList()
    }

    // ── Centre-wide people index (the number field's lookup) ────────────────────────────────
    //
    // Its OWN memo key and state fields, deliberately not shared with the per-batch index above.
    // The two answer different questions over the same body — one drops centre-level rows, the
    // other must keep them — and a shared `rosterIndexKey` would make each call invalidate the
    // other's cache and re-parse 1,800 rows per keystroke.
    private var peopleIndexKey: String? = null
    private var peopleIndexRows: List<RosterPerson> = emptyList()
    private var peopleIndexByMobile: Map<String, RosterPerson> = emptyMap()

    /**
     * Every person in the centre's mirrored roster, one row each.
     *
     * Returns null only when the body is present and does not parse — the caller must not treat
     * that as "nobody is registered", for the same reason [indexRosterJson] documents.
     */
    internal fun indexRosterPeople(raw: String): List<RosterPerson>? {
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<RosterPerson>(arr.length())
            val seen = HashSet<String>(arr.length() * 2)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val mobile = o.optString("mobile", "").trim()
                if (mobile.isEmpty()) continue
                // ONE row per person. Somebody in three batches is three rows in roster.json,
                // and the number field offers people, not memberships — three identical
                // suggestions for one person is worse than none. Keyed with lastTenDigits, the
                // same rule mergeServerRosterInto dedupes with, because stored mobiles carry
                // prefixes and a second slightly different rule fails to match exactly those.
                if (!seen.add(lastTenDigits(mobile))) continue
                fun text(key: String): String =
                    if (o.isNull(key)) "" else o.optString(key, "").trim()
                out.add(
                    RosterPerson(
                        mobile = mobile,
                        name = text("name").ifBlank { "Unknown" },
                        address = text("address"),
                        occupation = text("occupation"),
                        batchId = if (o.isNull("batch_id")) null
                                  else o.optInt("batch_id", -1).takeIf { it >= 0 }?.toString(),
                        batchName = text("batch_name").ifBlank { null },
                    )
                )
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    private fun ensurePeopleIndex(centreId: String) {
        val raw = rosterMirrorRaw(centreId)
        val key = "$centreId|${raw.length}|${raw.hashCode()}"
        if (key == peopleIndexKey) return
        peopleIndexKey = key
        val rows = indexRosterPeople(raw) ?: emptyList()
        peopleIndexRows = rows
        peopleIndexByMobile = rows.associateBy { lastTenDigits(it.mobile) }
    }

    /** Everyone in this centre's roster, for the registration screen's number suggestions. */
    fun rosterPeople(centreId: String = selectedFlaskCentreId): List<RosterPerson> {
        ensurePeopleIndex(centreId)
        return peopleIndexRows
    }

    /**
     * The roster person holding [mobile], or null. Matched on the last ten digits so a stored
     * "+91 98350 00001" is found by a typed "9835000001".
     */
    fun rosterPersonByMobile(
        mobile: String,
        centreId: String = selectedFlaskCentreId,
    ): RosterPerson? {
        if (mobile.isBlank()) return null
        ensurePeopleIndex(centreId)
        return peopleIndexByMobile[lastTenDigits(mobile)]
    }

    /**
     * Find a person by number across EVERY centre in the system, not just the selected one.
     *
     * The mirrored roster this file indexes above is one centre's, because CentreRoster is scoped
     * by centre. That is right for the roster and wrong for a festival: somebody already
     * registered at Centre A arrives at Centre B as a complete stranger — no suggestion and
     * no "already a member" notice, since they genuinely are not on this centre's roster — and
     * the operator re-types details the system already holds.
     *
     * Deliberately a live call rather than a second mirror. Mirroring every centre would put
     * ~1 MB on each device and triple the roster pull, for a lookup that fires a handful of times
     * an evening; this asks a question and gets a few hundred bytes back. It is therefore
     * ONLINE-ONLY by design, and returns an empty list rather than failing when there is no
     * network — the local index still answers everything about this centre offline, exactly as
     * before this existed.
     */
    suspend fun lookupRosterAcrossCentres(
        query: String,
        excludeCentreId: String = selectedFlaskCentreId,
    ): List<RosterPerson> = withContext(Dispatchers.IO) {
        val q = query.filter { it.isDigit() }
        if (q.length < FieldSuggestions.CROSS_CENTRE_MIN_QUERY || !isOnline) {
            return@withContext emptyList()
        }
        val raw = apiGet("/api/roster/lookup?q=$q&exclude_centre_id=$excludeCentreId")
            ?: return@withContext emptyList()
        try {
            val arr = JSONArray(raw)
            val out = ArrayList<RosterPerson>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val m = o.optString("mobile", "").trim()
                if (m.isEmpty()) continue
                fun text(key: String): String =
                    if (o.isNull(key)) "" else o.optString(key, "").trim()
                out.add(
                    RosterPerson(
                        mobile = m,
                        name = text("name").ifBlank { "Unknown" },
                        address = text("address"),
                        occupation = text("occupation"),
                        // No batch: this person's batch belongs to THEIR centre and would be
                        // meaningless — worse, misleading — against the batch list on this screen.
                        batchId = null,
                        batchName = null,
                        centreName = text("centre_name").ifBlank { null },
                    )
                )
            }
            out
        } catch (e: Exception) {
            // An error body or a 502 from the tunnel is "we could not ask", not "nobody exists".
            // Both come out as no suggestions, which is the same thing the operator sees offline.
            emptyList()
        }
    }

    /**
     * Cheap change detector. /api/cache/checksum fingerprints the centre's session state, so
     * we can poll it on the existing connectivity tick and only pull the full state payload
     * when it actually moved. Returns true when the checksum changed (state was re-pulled).
     */
    suspend fun syncServerCacheIfChanged(): Boolean = withContext(Dispatchers.IO) {
        val centreId = selectedFlaskCentreId
        if (centreId.isEmpty() || !isOnline) return@withContext false
        val raw = apiGet("/api/cache/checksum?centre_id=$centreId") ?: return@withContext false
        val checksum = try { JSONObject(raw).optString("checksum", "") } catch (e: Exception) { "" }
        if (checksum.isEmpty()) return@withContext false
        val prefs = cachePrefs() ?: return@withContext false
        if (prefs.getString(serverChecksumKey(centreId), null) == checksum) return@withContext false
        val refreshed = refreshServerState(centreId)
        // Pull the ROSTER too, not just the session state. This tick was the only thing running
        // on a device that stays open all evening, and it refreshed state.json alone — so people
        // added to the centre were invisible to attendance autocomplete until the app was fully
        // restarted. On 2026-08-08 that meant 125 people added mid-class could not be suggested on
        // an already-open phone.
        //
        // ASK for that pull rather than performing one. At the 1s tick a burst of
        // registrations moves the checksum nearly every tick, and roster.json is 300-470 KB;
        // drainOwedRosterPull coalesces the burst into one pull per ROSTER_PULL_MIN_INTERVAL_MS
        // and the connectivity loop drains what is still owed once the floor elapses. Still
        // deliberately NOT part of the `refreshed` result: the checksum must only be marked seen
        // when the STATE pull succeeded, exactly as before, so a roster hiccup can never make the
        // app skip a state change.
        rosterPullOwedFor = centreId
        drainOwedRosterPull()
        // Only remember the checksum once the state behind it is safely mirrored, so a failed
        // state pull is retried on the next tick instead of being marked as already-seen.
        if (refreshed) prefs.edit().putString(serverChecksumKey(centreId), checksum).apply()
        refreshed
    }

    /**
     * The centre's active session as the server last reported it — read from the on-disk
     * mirror, so it answers identically online and offline. Null when we have never
     * successfully reached the server for this centre+category, or the server has no active
     * session set (session_id is null in that case).
     */
    fun serverActiveSession(
        centreId: String = selectedFlaskCentreId,
        category: String = selectedCategory,
    ): ServerActiveSession? {
        val cat = category.lowercase().ifBlank { "youth" }
        val raw = cachePrefs()?.getString(serverStateKey(centreId, cat), null) ?: return null
        return try {
            val state = JSONObject(raw).optJSONObject("session_state") ?: return null
            // optString yields the literal "null" for a JSON null, which is exactly the
            // truthy-placeholder trap that put 21 junk dates in the attendance table. Read
            // through isNull() so an unset slot comes back as genuinely absent.
            fun field(key: String): String =
                if (state.isNull(key)) "" else state.optString(key, "").trim()
            val sessionId = field("session_id")
            if (sessionId.isEmpty()) return null
            ServerActiveSession(
                sessionId = sessionId,
                date = field("date"),
                batchId = field("batch_id"),
                batchName = field("batch_name"),
                category = cat
            )
        } catch (e: Exception) { null }
    }

    /**
     * Union the mirrored server roster into an existing member list, deduped on the last ten
     * mobile digits. Nothing is ever dropped: a person already known from attendance keeps
     * that (richer) record, and roster-only people are appended from the server cache.
     * roster.json carries one row per (person, batch) because CentreRoster is scoped by
     * batch, so repeated mobiles collapse into a single person holding several batch ids.
     *
     * Each row now takes the category the SERVER says it has. This used to stamp every row
     * with `selectedCategory` instead — the reasoning being that CentreRoster had no category
     * column and guessing from the batch risked hiding people. The cost of that choice only
     * became visible once a centre ran both tracks: because every screen filters with
     * `it.category == selectedCategory`, stamping the selected category made that filter
     * always true, so all 365 Centre A congregation members appeared in youth lookups and
     * the entire BG roster appeared in congregation ones. The server resolves the category
     * from the row's own batch (`roster.json`, added 2026-08-09), so it is no longer a guess.
     *
     * A row WITHOUT a category still falls back to `selectedCategory`, which keeps the old
     * lenient behaviour against a server or cache file predating that field. Hiding everyone
     * would be a worse failure than showing too many.
     */
    /**
     * The server speaks lower case ("youth"); every screen compares against the display form
     * ("Youth") — see `RegistererScreens.kt`'s `it.category == AppDatabase.selectedCategory`.
     * Passing a raw server value through matches nothing and silently hides those people.
     *
     * This lived as a local function inside [mergeServerRosterInto] and so protected the ROSTER
     * rows only. The show_attendance parser built its Registrations with the raw lower-case value,
     * which meant every attendance-ONLY person — 769 of them at Centre B, people in no roster
     * at all — was dropped by the door filter on a casing mismatch. That is a second, independent
     * cause of the same disappearance `scope=all` was added to fix, and fixing only one of the two
     * would have left the symptom exactly as the client reported it.
     *
     * Hoisted here so both parsers share it and neither can drift again. An absent or unrecognised
     * value keeps the old lenient behaviour of assuming the track currently being worked in.
     */
    internal fun displayCategory(raw: String?): String =
        when ((raw ?: "").trim().lowercase()) {
            "youth" -> "Youth"
            "congregation" -> "Congregation"
            else -> selectedCategory
        }

    private fun mergeServerRosterInto(
        existing: List<Registration>,
        centreId: String,
        knownBatches: List<Batch>,
    ): List<Registration> {
        val raw = cachePrefs()?.getString(serverRosterKey(centreId), null) ?: return existing
        return try {
            val arr = JSONArray(raw)
            val validBatchIds = knownBatches.map { it.id }.toSet()
            val added = LinkedHashMap<String, Registration>()
            // People already known from attendance are ENRICHED from the roster now, not
            // skipped. They used to be dropped outright on the grounds that the attendance
            // record was "richer" — but attendance rows mostly carry no profile at all (3,133
            // of 3,278 have no name of their own), so skipping meant a person who had attended
            // could never pick up their address or occupation from the roster. Only blanks are
            // filled, so the attendance record still wins wherever it actually holds a value.
            // Held as a LIST, not a map keyed by mobile: if `existing` ever contains two entries
            // sharing the last ten digits, a map would silently drop one of them. The old code
            // returned `existing` untouched and kept both, and that must not regress.
            val enriched = existing.toMutableList()
            val existingIndex = HashMap<String, Int>()
            existing.forEachIndexed { idx, r ->
                val k = lastTenDigits(r.mobile)
                if (k.isNotEmpty()) existingIndex.putIfAbsent(k, idx)
            }
            fun rowCategoryOf(row: JSONObject): String =
                displayCategory(if (row.isNull("category")) "" else row.optString("category", ""))
            // Fold ONE roster row into whatever is already known about that person. Every field
            // is fill-the-blank: a value already held is never overwritten, a value still
            // missing is taken. This is what was wrong before — a person on two batches has
            // their details split across two rows, and only the first row was read. Gaurav
            // Goswami is row 255 (BG, no address) and row 256 (Sunday 4pm, "Mithpur Bus
            // Stand"); the first won outright, so his address was dropped even though the
            // server had sent it.
            fun fold(prior: Registration, row: JSONObject, batchId: String): Registration {
                fun text(field: String) =
                    if (row.isNull(field)) "" else row.optString(field, "").trim()
                var out = prior
                if (batchId.isNotEmpty() && batchId !in out.batchIds) {
                    out = out.copy(batchIds = out.batchIds + batchId)
                }
                if (out.name.isBlank() || out.name == "Unknown") {
                    text("name").ifBlank { null }?.let { out = out.copy(name = it) }
                }
                if (out.address.isBlank()) {
                    text("address").ifBlank { null }?.let { out = out.copy(address = it) }
                }
                if (out.occupation.isBlank()) {
                    text("occupation").ifBlank { null }?.let { out = out.copy(occupation = it) }
                }
                if (out.remark.isBlank()) {
                    text("remark").ifBlank { null }?.let { out = out.copy(remark = it) }
                }
                // Someone can hold a batch in BOTH tracks — at Centre A three people attend
                // BG and the Sunday congregation class. `category` is a single field, so
                // whichever row was read first would decide and the person would vanish from
                // the other track. When rows disagree, the track being worked in wins, so they
                // stay visible either way.
                val rowCategory = rowCategoryOf(row)
                if (rowCategory != out.category && rowCategory == selectedCategory) {
                    out = out.copy(category = selectedCategory)
                }
                return out
            }

            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val mobile = o.optString("mobile", "").trim()
                val key = lastTenDigits(mobile)
                if (key.isEmpty()) continue
                // Keep a batch id only when it names a batch this client actually knows, so
                // the roster can never point someone at a batch the pickers can't resolve.
                val batchId = (if (o.isNull("batch_id")) "" else o.optInt("batch_id", -1)
                    .let { if (it >= 0) it.toString() else "" })
                    .takeIf { it.isNotEmpty() && it in validBatchIds } ?: ""

                val knownAt = existingIndex[key]
                if (knownAt != null) {
                    enriched[knownAt] = fold(enriched[knownAt], o, batchId)
                    continue
                }

                val prior = added[key]
                if (prior != null) {
                    added[key] = fold(prior, o, batchId)
                    continue
                }
                fun text(field: String) =
                    if (o.isNull(field)) "" else o.optString(field, "").trim()
                added[key] = Registration(
                    // Same "r_<mobile>" id shape the attendance-derived list uses, so every
                    // screen and the offline-queue recovery path treat both sources alike.
                    id = "r_$key",
                    name = text("name").ifBlank { "Unknown" },
                    mobile = mobile,
                    address = text("address"),
                    occupation = text("occupation"),
                    remark = text("remark"),
                    centreId = centreId,
                    category = rowCategoryOf(o),
                    status = "active",
                    batchIds = if (batchId.isEmpty()) emptyList() else listOf(batchId)
                )
            }
            enriched + added.values
        } catch (e: Exception) { existing }
    }

    // Durable copy of the merged member list. The registerer screens read `registrations`
    // directly in several places — dashboard counts, the registered/archived lists, and the
    // cross-centre duplicate warning — and it was the one dataset with no cache at all, so
    // every one of them went blank (and the duplicate warning silently stopped firing) as
    // soon as the app was reopened without a connection. Keyed by centre+category for the
    // same reason the follow-ups cache is: neither may bleed into the other.
    private fun registrationsCacheKey() =
        "cached_registrations_${selectedFlaskCentreId}_${selectedCategory.lowercase()}"

    private fun saveRegistrationsCache(list: List<Registration>) {
        val prefs = cachePrefs() ?: return
        if (list.isEmpty() || selectedFlaskCentreId.isEmpty()) return
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id); put("name", r.name); put("mobile", r.mobile)
                put("address", r.address); put("occupation", r.occupation); put("remark", r.remark)
                put("centreId", r.centreId); put("category", r.category); put("status", r.status)
                put("batchIds", JSONArray(r.batchIds))
                r.explicitBatchId?.let { put("explicitBatchId", it) }
            })
        }
        // PersonContext and the follow-up fields are deliberately left out: they are only
        // ever populated by live follow-up fetches, so persisting them would inflate the
        // blob and make stale values look like fresh ones.
        prefs.edit().putString(registrationsCacheKey(), arr.toString()).apply()
    }

    private fun loadRegistrationsCache(): List<Registration> {
        val prefs = cachePrefs() ?: return emptyList()
        val raw = prefs.getString(registrationsCacheKey(), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val batchArr = o.optJSONArray("batchIds")
                Registration(
                    id = o.optString("id"), name = o.optString("name"), mobile = o.optString("mobile"),
                    address = o.optString("address", ""), occupation = o.optString("occupation", ""),
                    remark = o.optString("remark", ""), centreId = o.optString("centreId", ""),
                    // Normalised on the way out of the cache too: a device upgrading from a build
                    // that stored the server's raw "youth" would otherwise keep serving the door
                    // a list those people are filtered out of until the next live fetch lands.
                    category = displayCategory(o.optString("category", "")),
                    status = o.optString("status", "active"),
                    explicitBatchId = if (o.isNull("explicitBatchId")) null else o.optString("explicitBatchId", null),
                    batchIds = if (batchArr == null) emptyList()
                    else (0 until batchArr.length()).map { j -> batchArr.optString(j, "") }.filter { it.isNotEmpty() }
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun saveCentresCache(list: List<Centre>) {
        val prefs = cachePrefs() ?: return
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id); put("name", c.name); put("code", c.code)
                put("googleSheetId", c.googleSheetId); put("isSheetEnabled", c.isSheetEnabled)
                put("enabledYouth", c.enabledYouth); put("enabledCongregation", c.enabledCongregation)
                put("status", c.status)
            })
        }
        prefs.edit().putString("cached_centres", arr.toString()).apply()
    }

    private fun loadCentresCache(): List<Centre> {
        val prefs = cachePrefs() ?: return emptyList()
        val raw = prefs.getString("cached_centres", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Centre(
                    id = o.optString("id"), name = o.optString("name"), code = o.optString("code"),
                    googleSheetId = o.optString("googleSheetId", ""), isSheetEnabled = o.optBoolean("isSheetEnabled", false),
                    enabledYouth = o.optBoolean("enabledYouth", true), enabledCongregation = o.optBoolean("enabledCongregation", true),
                    status = o.optString("status", "active")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun saveBatchesCache(centreId: String, list: List<Batch>) {
        val prefs = cachePrefs() ?: return
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(JSONObject().apply {
                put("id", b.id); put("name", b.name); put("centreId", b.centreId); put("category", b.category)
                put("isDefault", b.isDefault); put("status", b.status); put("memberCount", b.memberCount)
            })
        }
        prefs.edit().putString("cached_batches_$centreId", arr.toString()).apply()
    }

    private fun loadBatchesCache(centreId: String): List<Batch> {
        val prefs = cachePrefs() ?: return emptyList()
        val raw = prefs.getString("cached_batches_$centreId", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Batch(
                    id = o.optString("id"), name = o.optString("name"), centreId = o.optString("centreId"),
                    category = o.optString("category"), isDefault = o.optBoolean("isDefault", false),
                    status = o.optString("status", "active"), memberCount = o.optInt("memberCount", 0)
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun saveSessionsCache(centreId: String, list: List<Session>) {
        val prefs = cachePrefs() ?: return
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("name", s.name); put("batchId", s.batchId); put("date", s.date)
                put("sessionType", s.sessionType)
            })
        }
        prefs.edit().putString("cached_sessions_$centreId", arr.toString()).apply()
    }

    private fun loadSessionsCache(centreId: String): List<Session> {
        val prefs = cachePrefs() ?: return emptyList()
        val raw = prefs.getString("cached_sessions_$centreId", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Session(
                    id = o.optString("id"), name = o.optString("name"), batchId = o.optString("batchId"),
                    date = o.optString("date"), sessionType = o.optString("sessionType", "ad_hoc")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun saveRosterCache(batchId: String, list: List<BatchMemberInfo>) {
        val prefs = cachePrefs() ?: return
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id); put("mobile", m.mobile); put("name", m.name ?: JSONObject.NULL)
                put("batchId", m.batchId); put("joinedAt", m.joinedAt)
            })
        }
        prefs.edit().putString("cached_roster_$batchId", arr.toString()).apply()
    }

    private fun loadRosterCache(batchId: String): List<BatchMemberInfo> {
        val prefs = cachePrefs() ?: return emptyList()
        val raw = prefs.getString("cached_roster_$batchId", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BatchMemberInfo(
                    id = o.optString("id"), mobile = o.optString("mobile"),
                    name = if (o.isNull("name")) null else o.optString("name", "").ifBlank { null },
                    batchId = o.optInt("batchId"), joinedAt = o.optString("joinedAt")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    // The cache must carry the meaning too. Without it an offline caller gets a picker that cannot
    // split into "picked / didn't pick", and every response paints as the neutral fallback — which
    // is exactly when the app is most on its own.
    private fun saveDropdownCache(list: List<DropdownOption>) {
        val prefs = cachePrefs() ?: return
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(JSONObject().apply {
                put("id", d.id); put("label", d.label); put("context", d.context); put("active", d.active)
                put("is_global", d.isGlobal)
                put("sentiment", d.sentiment); put("answered_scope", d.answeredScope)
                put("action", d.action ?: JSONObject.NULL)
                put("defer_days", d.deferDays ?: JSONObject.NULL)
                put("counts_as_strike", d.countsAsStrike)
            })
        }
        prefs.edit().putString("cached_dropdowns", arr.toString()).apply()
    }

    private fun loadDropdownCache(): List<DropdownOption> {
        val prefs = cachePrefs() ?: return emptyList()
        val raw = prefs.getString("cached_dropdowns", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DropdownOption(
                    id = o.optString("id"), label = o.optString("label"),
                    context = o.optString("context"), active = o.optBoolean("active", true),
                    isGlobal = o.optBoolean("is_global", false),
                    // A cache written by the previous build has none of these keys; the defaults
                    // then apply and the picker degrades to one flat list rather than breaking.
                    sentiment = o.optString("sentiment", ResponseMeaning.NOT_REACHED),
                    answeredScope = o.optString("answered_scope", ResponseMeaning.SCOPE_BOTH),
                    action = if (o.isNull("action")) null else o.optString("action", "").ifBlank { null },
                    deferDays = if (o.isNull("defer_days")) null else o.optInt("defer_days"),
                    countsAsStrike = o.optBoolean("counts_as_strike", false)
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    // The caller's session-call queue is their whole work list, so it must survive the server going
    // down. We cache the raw HTTP response body verbatim and re-run the exact same parser on it, so a
    // cached queue is indistinguishable from a live one (names, person context, summary all intact).
    private fun saveQueueCache(raw: String) {
        cachePrefs()?.edit()?.putString("cached_session_queue", raw)?.apply()
    }

    private fun loadQueueCacheRaw(): String? = cachePrefs()?.getString("cached_session_queue", null)

    // After an ONLINE outcome succeeds, fold it into the on-disk queue cache so a caller who loses
    // signal immediately after still sees who they've called + the response (Thing 1). Mirrors what
    // the next server fetch would return: sets the item's callStatus, prepends the response to its
    // recentCalls, and recomputes the summary counts. Best-effort — never blocks the write.
    private fun patchQueueCacheEntry(assignmentId: String, derivedStatus: String, outcome: String, remark: String?, callerName: String) {
        val raw = loadQueueCacheRaw() ?: return
        try {
            val root = JSONObject(raw)
            val data = root.optJSONObject("data")
            val items = data?.optJSONArray("items") ?: root.optJSONArray("items") ?: return
            val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            for (i in 0 until items.length()) {
                val obj = items.getJSONObject(i)
                if (obj.optInt("id").toString() == assignmentId) {
                    obj.put("callStatus", derivedStatus)
                    val ctx = obj.optJSONObject("context") ?: JSONObject().also { obj.put("context", it) }
                    val synthetic = JSONObject().apply {
                        put("date", nowStr); put("outcome", outcome)
                        put("remark", remark ?: JSONObject.NULL); put("caller", callerName)
                    }
                    // One caller may hold only ONE response per person per day — the same rule the
                    // backend now enforces with a unique (registration, session) key. Prepending
                    // unconditionally is what made a changed answer show up TWICE for the same date;
                    // fixing it only on the server would have left the local cache reproducing the
                    // bug until the next successful fetch.
                    val newCalls = JSONArray().put(synthetic)
                    ctx.optJSONArray("recentCalls")?.let {
                        for (j in 0 until it.length()) {
                            val prev = it.optJSONObject(j)
                            val supersedes = prev != null &&
                                prev.optString("caller") == callerName &&
                                prev.optString("date").take(10) == nowStr.take(10)
                            if (!supersedes) newCalls.put(it.get(j))
                        }
                    }
                    ctx.put("recentCalls", newCalls)
                    break
                }
            }
            var done = 0
            for (i in 0 until items.length()) if (items.getJSONObject(i).optString("callStatus") != "pending") done++
            val summary = data?.optJSONObject("summary") ?: data?.let { JSONObject().also { s -> it.put("summary", s) } }
            summary?.put("total", items.length()); summary?.put("done", done); summary?.put("left", items.length() - done)
            saveQueueCache(root.toString())
        } catch (e: Exception) { /* cache patch is best-effort */ }
    }

    // Follow-up list cache — same verbatim-raw-body approach as the queue cache, keyed by
    // centre+category so switching either doesn't cross-contaminate. Lets the Follow-ups tab render
    // the last-known list offline instead of going blank.
    private fun followupsCacheKey(): String = "cached_followups_${selectedCentreId}_${selectedCategory.lowercase()}"
    private fun saveFollowupsCache(raw: String) { cachePrefs()?.edit()?.putString(followupsCacheKey(), raw)?.apply() }
    private fun loadFollowupsCacheRaw(): String? = cachePrefs()?.getString(followupsCacheKey(), null)

    // All datasets start empty — populated entirely from the backend after login
    var centres        by mutableStateOf(listOf<Centre>())
    var batches        by mutableStateOf(listOf<Batch>())
    var sessions       by mutableStateOf(listOf<Session>())
    var registrations  by mutableStateOf(listOf<Registration>())
    // Whole-centre calling roster (batch_members with names) from the CB backend, used ONLY to widen
    // Mark-Attendance autocomplete: a caller can mark attendance for anyone they called, even if that
    // person has no prior Flask attendance/registration record (attendance IS registration, so the
    // Flask mark_attendance auto-creates them). Kept separate from `registrations` so it never
    // pollutes the Flask-derived roster views.
    var attendanceCandidates by mutableStateOf(listOf<Registration>())
    var callAttempts   by mutableStateOf(listOf<CallAttempt>())
    var batchMoveRequests by mutableStateOf(listOf<BatchMoveRequest>())
    var notifications  by mutableStateOf(listOf<Notification>())
    var users          by mutableStateOf(listOf<User>())
    var offlineQueue   by mutableStateOf(listOf<OfflineQueueItem>())
    // Optimistic call-outcome overrides keyed by assignmentId — see PendingCallOverride. Drives the
    // "offline looks identical to online" behaviour in the caller's Calling + Follow-ups tabs.
    var pendingCallOverrides by mutableStateOf(mapOf<String, PendingCallOverride>())
    var auditLogs      by mutableStateOf(listOf<AuditLog>())
    var dropdownOptions by mutableStateOf(listOf<DropdownOption>())

    init {
        currentUser = null
    }

    // ── Flask (attendance) API helpers ────────────────────────────────────────
    // Flask now accepts the Node.js JWT directly (shared secret, no RegistererAccount lookup).
    private suspend fun apiGet(path: String): String? = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url("$backendUrl$path").get()
        jwtToken?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
        val t0 = System.currentTimeMillis()
        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val ms = System.currentTimeMillis() - t0
                logReq("AE", "GET", path, response.code, ms)
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) { logErr("AE", "GET", path, e.message ?: "io"); null }
    }

    private suspend fun apiPost(path: String, jsonBody: String): String? = withContext(Dispatchers.IO) {
        val body = jsonBody.toRequestBody(jsonMediaType)
        val requestBuilder = Request.Builder().url("$backendUrl$path").post(body)
        jwtToken?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
        val t0 = System.currentTimeMillis()
        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val ms = System.currentTimeMillis() - t0
                logReq("AE", "POST", path, response.code, ms)
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) { logErr("AE", "POST", path, e.message ?: "io"); null }
    }

    /**
     * Flask write that returns the server's own message on failure.
     *
     * apiPost swallows the body on a non-2xx, which is unusable for the directory screen: refusing
     * to edit a shared remark comes back as a 403 whose whole value IS the explanation ("only the
     * host can change it — add your own instead"). Mirrors callingApiSend, reading Flask's `error`
     * key rather than the calling backend's `message`.
     */
    private suspend fun apiSendWithError(
        method: String,
        path: String,
        jsonBody: String = "{}"
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val body = jsonBody.toRequestBody(jsonMediaType)
        val requestBuilder = Request.Builder().url("$backendUrl$path")
        when (method) {
            "POST" -> requestBuilder.post(body)
            "PUT" -> requestBuilder.put(body)
            "PATCH" -> requestBuilder.patch(body)
            // Flask reads centre_id from the body on DELETE too, so the body must go with it.
            "DELETE" -> requestBuilder.delete(body)
            else -> requestBuilder.get()
        }
        jwtToken?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
        val t0 = System.currentTimeMillis()
        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                logReq("AE", method, path, response.code, System.currentTimeMillis() - t0)
                if (response.isSuccessful) return@withContext Pair(true, null)
                val raw = response.body?.string()
                val msg = try {
                    JSONObject(raw ?: "{}").optString("error", "").ifEmpty { null }
                } catch (e: Exception) { null }
                Pair(false, msg ?: "Request failed (HTTP ${response.code})")
            }
        } catch (e: Exception) {
            logErr("AE", method, path, e.message ?: "io")
            Pair(false, "No connection to the attendance server.")
        }
    }

    /**
     * POST to /api/mark_attendance treating the write as SUCCESS on 2xx OR 409.
     *
     * mark_attendance is idempotent server-side: a duplicate (mobile, session, date, centre)
     * returns 409 "Already Marked". For our durable offline queue a 409 means the record IS
     * present on the server, so the queued write's intent is satisfied. Without this, the
     * classic flaky-signal case — server commits (201) but the network drops before the
     * response arrives — retries forever and falsely parks a genuinely-recorded attendance as
     * failed_needs_attention. Treating 409 as success also makes an accidental double-sync
     * (two overlapping syncOfflineQueue runs) fully benign: the second replay just sees 409.
     */
    private suspend fun postMarkAttendanceIdempotent(jsonBody: String): Boolean = withContext(Dispatchers.IO) {
        val body = jsonBody.toRequestBody(jsonMediaType)
        val requestBuilder = Request.Builder().url("$backendUrl/api/mark_attendance").post(body)
        jwtToken?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
        val t0 = System.currentTimeMillis()
        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                logReq("AE", "POST", "/api/mark_attendance", response.code, System.currentTimeMillis() - t0)
                response.isSuccessful || response.code == 409
            }
        } catch (e: Exception) { logErr("AE", "POST", "/api/mark_attendance", e.message ?: "io"); false }
    }

    // ── Node.js (calling) API helpers ─────────────────────────────────────────
    private suspend fun callingApiGet(path: String): String? = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url("$callingBackendUrl$path").get()
        jwtToken?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
        selectedCentreId.toIntOrNull()?.let { requestBuilder.addHeader("x-centre-id", it.toString()) }
        val t0 = System.currentTimeMillis()
        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val ms = System.currentTimeMillis() - t0
                logReq("SRKB", "GET", path, response.code, ms)
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) { logErr("SRKB", "GET", path, e.message ?: "io"); null }
    }

    private suspend fun callingApiPost(path: String, jsonBody: String): String? = withContext(Dispatchers.IO) {
        val body = jsonBody.toRequestBody(jsonMediaType)
        val requestBuilder = Request.Builder().url("$callingBackendUrl$path").post(body)
        jwtToken?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
        selectedCentreId.toIntOrNull()?.let { requestBuilder.addHeader("x-centre-id", it.toString()) }
        val t0 = System.currentTimeMillis()
        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val ms = System.currentTimeMillis() - t0
                logReq("SRKB", "POST", path, response.code, ms)
                if (response.isSuccessful) response.body?.string()
                else {
                    // Keep the server's explanation for callers that want to show it.
                    lastCallingPostError = try {
                        JSONObject(response.body?.string() ?: "").optString("message", "").ifBlank { null }
                    } catch (e: Exception) { null }
                    null
                }
            }
        } catch (e: Exception) { logErr("SRKB", "POST", path, e.message ?: "io"); null }
    }

    // The message of the last refused calling-backend POST (see callingApiPost). Read right after
    // the call by assignSessionCallersOrError; not a general-purpose error channel.
    @Volatile private var lastCallingPostError: String? = null

    private suspend fun callingApiPatch(path: String, jsonBody: String = "{}"): String? = withContext(Dispatchers.IO) {
        val body = jsonBody.toRequestBody(jsonMediaType)
        val requestBuilder = Request.Builder().url("$callingBackendUrl$path").patch(body)
        jwtToken?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
        selectedCentreId.toIntOrNull()?.let { requestBuilder.addHeader("x-centre-id", it.toString()) }
        val t0 = System.currentTimeMillis()
        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val ms = System.currentTimeMillis() - t0
                logReq("SRKB", "PATCH", path, response.code, ms)
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) { logErr("SRKB", "PATCH", path, e.message ?: "io"); null }
    }

    private suspend fun callingApiPut(path: String, jsonBody: String): String? = withContext(Dispatchers.IO) {
        val body = jsonBody.toRequestBody(jsonMediaType)
        val requestBuilder = Request.Builder().url("$callingBackendUrl$path").put(body)
        jwtToken?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
        selectedCentreId.toIntOrNull()?.let { requestBuilder.addHeader("x-centre-id", it.toString()) }
        val t0 = System.currentTimeMillis()
        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val ms = System.currentTimeMillis() - t0
                logReq("SRKB", "PUT", path, response.code, ms)
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) { logErr("SRKB", "PUT", path, e.message ?: "io"); null }
    }

    /**
     * Like the other calling-API helpers but reports WHY a write failed instead of collapsing every
     * failure to null. Management screens need this: editing a host-owned global option returns a
     * 403 with a specific reason, and a validation slip returns a 422 — both are things the admin
     * must actually read, not silently-nothing-happened.
     */
    private suspend fun callingApiSend(
        method: String,
        path: String,
        jsonBody: String = "{}"
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val body = jsonBody.toRequestBody(jsonMediaType)
        val requestBuilder = Request.Builder().url("$callingBackendUrl$path")
        when (method) {
            "POST" -> requestBuilder.post(body)
            "PUT" -> requestBuilder.put(body)
            "PATCH" -> requestBuilder.patch(body)
            "DELETE" -> requestBuilder.delete()
            else -> requestBuilder.get()
        }
        jwtToken?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
        selectedCentreId.toIntOrNull()?.let { requestBuilder.addHeader("x-centre-id", it.toString()) }
        val t0 = System.currentTimeMillis()
        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                logReq("SRKB", method, path, response.code, System.currentTimeMillis() - t0)
                if (response.isSuccessful) return@withContext Pair(true, null)
                val raw = response.body?.string()
                val msg = try {
                    JSONObject(raw ?: "{}").optString("message", "").ifEmpty { null }
                } catch (e: Exception) { null }
                Pair(false, msg ?: "Request failed (HTTP ${response.code})")
            }
        } catch (e: Exception) {
            logErr("SRKB", method, path, e.message ?: "io")
            Pair(false, "No connection to the calling server.")
        }
    }

    private suspend fun callingApiDelete(path: String): String? = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url("$callingBackendUrl$path").delete()
        jwtToken?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
        selectedCentreId.toIntOrNull()?.let { requestBuilder.addHeader("x-centre-id", it.toString()) }
        val t0 = System.currentTimeMillis()
        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val ms = System.currentTimeMillis() - t0
                logReq("SRKB", "DELETE", path, response.code, ms)
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) { logErr("SRKB", "DELETE", path, e.message ?: "io"); null }
    }

    // Login to Flask attendance backend — called when attendanceSystemAccess = true
    private suspend fun loginToFlask(username: String, password: String): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
        }.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$backendUrl/api/auth/login")
            .post(body)
            .build()
        val result = try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) { null } ?: return@withContext false
        try {
            val jwt = JSONObject(result).optString("jwt").ifEmpty { null }
            if (jwt != null) {
                withContext(Dispatchers.Main) { attendanceJwtToken = jwt }
                true
            } else false
        } catch (e: Exception) { false }
    }

    // Clear all session state on logout
    fun logout() {
        currentUser = null
        jwtToken = null
        // Drop the stream now rather than waiting for the poll to notice the blank token, and
        // clear the auth latch so a logout->login cycle inside one process reconnects cleanly.
        runCatching { RealtimeSseClient.disconnect(); RealtimeSseClient.resetAuthStop() }
        attendanceJwtToken = null
        centres = listOf()
        batches = listOf()
        sessions = listOf()
        registrations = listOf()
        callAttempts = listOf()
        users = listOf()
        dropdownOptions = listOf()
        selectedCentreId = ""
        selectedFlaskCentreId = ""
        currentMode = "registerer"
        clearUiSelections()
        clearPersistedSession()
        clearReadCaches()
        // The login the background jobs run as is gone; stop them. Unsent writes stay in the queue
        // and go up after the next sign-in.
        appContext?.let { BackgroundSync.cancelAll(it) }
    }

    /**
     * Drop every cached_* entry on logout. These caches hold one user's working data — their
     * call queue, their centre's roster and member list — and these phones get shared, so
     * without this the next person to sign in opens the previous user's work list.
     *
     * Scoped to the "cached_" prefix on purpose. The offline write queue and its optimistic
     * overrides are NOT cached_* keys and must survive: they are un-sent work, and clearing
     * them here would destroy the very data the durable queue exists to protect.
     */
    private fun clearReadCaches() {
        val prefs = cachePrefs() ?: return
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("cached_") }.forEach { editor.remove(it) }
        editor.apply()
    }

    // Load data from both backends after login.
    // Uses appScope (not the caller's scope) so navigation away from login screen
    // doesn't cancel the in-flight batch/registration fetch.
    @Suppress("UNUSED_PARAMETER")
    fun loadDataFromBackend(scope: CoroutineScope) {
        appScope.launch {
            val role = currentUser?.role ?: return@launch

            // Load calling dropdown options from Node.js
            launch {
                val newOptions = mutableListOf<DropdownOption>()
                // The backend's dropdownListSchema requires BOTH context and category — omitting
                // category always 422'd, silently leaving the outcome picker empty.
                val cat = selectedCategory.lowercase()
                val callingJson = callingApiGet("/dropdowns?context=calling_outcome&category=$cat")
                if (callingJson != null) {
                    try {
                        val obj = JSONObject(callingJson)
                        val arr = obj.optJSONObject("data")?.optJSONArray("items") ?: obj.optJSONArray("data") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val d = arr.getJSONObject(i)
                            newOptions.add(DropdownOption(
                                id = d.optInt("id", i).toString(),
                                label = d.optString("label", d.optString("value", "")),
                                context = "calling_outcome",
                                // The API serialises snake_case (is_active / is_global). Reading
                                // camelCase here meant active always fell back to true and the
                                // Global badge never rendered for any option.
                                active = d.optBoolean("is_active", true),
                                isGlobal = d.optBoolean("is_global", false),
                                sentiment = d.optString("sentiment", ResponseMeaning.NOT_REACHED),
                                answeredScope = d.optString("answered_scope", ResponseMeaning.SCOPE_BOTH),
                                action = if (d.isNull("action")) null else d.optString("action", "").ifBlank { null },
                                deferDays = if (d.isNull("defer_days")) null else d.optInt("defer_days"),
                                countsAsStrike = d.optBoolean("counts_as_strike", false)
                            ))
                        }
                    } catch (e: Exception) {}
                }
                val followupJson = callingApiGet("/dropdowns?context=followup_outcome&category=$cat")
                if (followupJson != null) {
                    try {
                        val obj = JSONObject(followupJson)
                        val arr = obj.optJSONObject("data")?.optJSONArray("items") ?: obj.optJSONArray("data") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val d = arr.getJSONObject(i)
                            newOptions.add(DropdownOption(
                                id = d.optInt("id", i).toString(),
                                label = d.optString("label", d.optString("value", "")),
                                context = "followup_outcome",
                                active = d.optBoolean("is_active", true),
                                isGlobal = d.optBoolean("is_global", false),
                                sentiment = d.optString("sentiment", ResponseMeaning.NOT_REACHED),
                                answeredScope = d.optString("answered_scope", ResponseMeaning.SCOPE_BOTH),
                                action = if (d.isNull("action")) null else d.optString("action", "").ifBlank { null },
                                deferDays = if (d.isNull("defer_days")) null else d.optInt("defer_days"),
                                countsAsStrike = d.optBoolean("counts_as_strike", false)
                            ))
                        }
                    } catch (e: Exception) {}
                }
                if (newOptions.isNotEmpty()) {
                    withContext(Dispatchers.Main) { dropdownOptions = newOptions }
                    saveDropdownCache(newOptions)
                } else {
                    // Both dropdown fetches failed (offline / server down) — fall back to the last
                    // cached outcome list so the caller can still log call results offline.
                    val cached = loadDropdownCache()
                    if (cached.isNotEmpty()) withContext(Dispatchers.Main) { dropdownOptions = cached }
                }
            }

            // Load notifications from Node.js (all roles)
            launch { fetchNotificationsFromBackend() }

            // Load users from Node.js (for ADMIN and SUPER_ADMIN)
            if (role == "ADMIN" || role == "SUPER_ADMIN") {
                launch { fetchUsers() }
            }

            // For SUPER_ADMIN: load centres from Node.js (authenticated — includes is_active flag)
            if (role == "SUPER_ADMIN") {
                launch {
                    val centresJson = callingApiGet("/centres")
                    if (centresJson != null) {
                        try {
                            val obj = JSONObject(centresJson)
                            val items = obj.optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
                            val newCentres = mutableListOf<Centre>()
                            for (i in 0 until items.length()) {
                                val c = items.getJSONObject(i)
                                val cats = c.optString("allowed_categories", "both")
                                newCentres.add(Centre(
                                    id = c.optInt("id", i).toString(),
                                    name = c.optString("name", "Centre ${i + 1}"),
                                    code = c.optString("code", "C${i + 1}"),
                                    enabledYouth = cats == "youth" || cats == "both",
                                    enabledCongregation = cats == "congregation" || cats == "both",
                                    status = if (c.optBoolean("is_active", true)) "active" else "inactive"
                                ))
                            }
                            if (newCentres.isNotEmpty()) withContext(Dispatchers.Main) { centres = newCentres; saveCentresCache(newCentres) }
                        } catch (e: Exception) {}
                    } else {
                        val cachedCentres = loadCentresCache()
                        if (cachedCentres.isNotEmpty()) withContext(Dispatchers.Main) { centres = cachedCentres }
                    }
                }
                return@launch // SUPER_ADMIN doesn't use Flask attendance data
            }

            // For ADMIN / CALLER: load attendance data from Flask

            // 1. Fetch Batches
            val flaskCentreId = selectedFlaskCentreId.ifEmpty { selectedCentreId }
            val batchesJson = apiGet("/api/batches?centre_id=$flaskCentreId")
            val fetchedBatches = mutableListOf<Batch>()
            if (batchesJson != null) {
                try {
                    val arr = JSONArray(batchesJson)
                    for (i in 0 until arr.length()) {
                        val bObj = arr.getJSONObject(i)
                        fetchedBatches.add(Batch(
                            id = bObj.optString("id").ifEmpty { bObj.optString("batch_id", "b_$i") },
                            name = bObj.optString("name", "Batch ${i + 1}"),
                            centreId = selectedFlaskCentreId,
                            category = bObj.optString("category", "Youth").replaceFirstChar { it.uppercase() },
                            isDefault = bObj.optBoolean("is_default", false),
                            memberCount = bObj.optInt("member_count", 0)
                        ))
                    }
                    if (fetchedBatches.isNotEmpty()) {
                        batches = fetchedBatches
                        saveBatchesCache(flaskCentreId, fetchedBatches)
                    }
                } catch (e: Exception) {}
            } else {
                // Offline / server unreachable — fall back to the last-known-good batch list
                // instead of leaving the picker empty. fetchedBatches feeds the centre-derivation
                // logic just below, so keep it in sync too.
                val cachedBatches = loadBatchesCache(flaskCentreId)
                if (cachedBatches.isNotEmpty()) {
                    batches = cachedBatches
                    fetchedBatches.addAll(cachedBatches)
                }
            }

            // 2. Fetch Centres from Flask (for non-SUPER_ADMIN)
            val centresJson = apiGet("/api/centres")
            val serverCats = fetchMyCentreCategories()
            if (centresJson != null) {
                try {
                    val arr = JSONArray(centresJson)
                    val newCentres = mutableListOf<Centre>()
                    for (i in 0 until arr.length()) {
                        val cObj = arr.getJSONObject(i)
                        val flaskCode = cObj.optString("centre_id").ifEmpty { cObj.optString("id", "c_$i") }
                        // Map the Flask string code to the Node.js numeric ID for the user's own centre
                        val nodeId = if (flaskCode == selectedFlaskCentreId) selectedCentreId else flaskCode
                        val centreBatches = fetchedBatches.filter { it.centreId == flaskCode }
                        val hasYouth = centreBatches.any { it.category.equals("Youth", ignoreCase = true) }
                        val hasCong  = centreBatches.any { it.category.equals("Congregation", ignoreCase = true) }
                        newCentres.add(Centre(
                            id = nodeId,
                            name = cObj.optString("name").ifEmpty { cObj.optString("centre_name", "Centre ${i+1}") },
                            code = flaskCode,
                            googleSheetId = cObj.optString("google_sheet_id", ""),
                            isSheetEnabled = cObj.optBoolean("google_sheet_enabled", false),
                            // The calling server's setting wins (what the super-admin chose);
                            // guessing from existing batches only when it can't be reached.
                            enabledYouth = (serverCats["code:" + flaskCode.lowercase()] ?: serverCats["id:$nodeId"])?.first
                                ?: if (centreBatches.isEmpty()) true else hasYouth,
                            enabledCongregation = (serverCats["code:" + flaskCode.lowercase()] ?: serverCats["id:$nodeId"])?.second
                                ?: if (centreBatches.isEmpty()) true else hasCong,
                            status = cObj.optString("status", "active")
                        ))
                    }
                    if (newCentres.isNotEmpty()) {
                        centres = newCentres
                        saveCentresCache(newCentres)
                    }
                } catch (e: Exception) {}
            } else {
                val cachedCentres = loadCentresCache()
                if (cachedCentres.isNotEmpty()) centres = cachedCentres
            }

            // 3. Fetch address/remark dictionary options from Flask.
            // MUST pass centre_id, for the same reason list_sessions had to (see step 4): without
            // it Flask falls back to the JWT user's PRIMARY centre, so anyone working in a centre
            // other than their primary one got the wrong centre's lists. Each list is the shared
            // rows plus that centre's own.
            val centreParam = selectedFlaskCentreId.ifBlank { "" }
            val q = if (centreParam.isNotBlank()) "?centre_id=$centreParam" else ""
            val addrJson = apiGet("/api/addresses$q")
            val remJson  = apiGet("/api/remarks$q")
            val dictOptions = mutableListOf<DropdownOption>()
            if (addrJson != null) {
                try {
                    val arr = JSONArray(addrJson)
                    for (i in 0 until arr.length()) {
                        val aObj = arr.getJSONObject(i)
                        dictOptions.add(DropdownOption(
                            id = aObj.optString("id", "addr_$i"),
                            // Flask's AddressOption.to_dict() emits {id, text}. Reading label/value
                            // produced a blank label for every row, so the Address Suggestions list
                            // rendered as a column of empty entries.
                            label = aObj.optString("text", aObj.optString("label", aObj.optString("value", ""))),
                            context = "address"
                        ))
                    }
                } catch (e: Exception) {}
            }
            if (remJson != null) {
                try {
                    val arr = JSONArray(remJson)
                    for (i in 0 until arr.length()) {
                        val rObj = arr.getJSONObject(i)
                        dictOptions.add(DropdownOption(
                            id = rObj.optString("id", "rem_$i"),
                            // Same as addresses — RemarkOption.to_dict() emits {id, text}.
                            label = rObj.optString("text", rObj.optString("label", rObj.optString("value", ""))),
                            context = "remark"
                        ))
                    }
                } catch (e: Exception) {}
            }
            if (dictOptions.isNotEmpty()) {
                dropdownOptions = dropdownOptions + dictOptions
            }

            // 4. Fetch Sessions from Flask. MUST pass centre_id — without it Flask
            // resolves sessions for the caller's PRIMARY centre (not the selected one),
            // so a registerer whose primary centre differs got the wrong centre's
            // sessions, no session matched the selected batch, and the "Add Student"
            // button stayed disabled (selectedSession never resolved). batches/
            // show_attendance already pass centre_id; list_sessions was the holdout.
            val sessionsJson = apiGet("/api/list_sessions?centre_id=$flaskCentreId")
            if (sessionsJson != null) {
                try {
                    val arr = JSONArray(sessionsJson)
                    val newSessions = mutableListOf<Session>()
                    for (i in 0 until arr.length()) {
                        val sObj = arr.getJSONObject(i)
                        newSessions.add(Session(
                            id = sObj.optString("session_id").ifEmpty { sObj.optString("id", "s_$i") },
                            name = sObj.optString("display_name").ifEmpty { sObj.optString("session_name").ifEmpty { sObj.optString("name", "Session ${i+1}") } },
                            batchId = sObj.optString("batch_id", fetchedBatches.firstOrNull()?.id ?: ""),
                            date = sObj.optString("date", ""),
                            sessionType = sObj.optString("session_type", "ad_hoc")
                        ))
                    }
                    if (newSessions.isNotEmpty()) {
                        sessions = newSessions
                        saveSessionsCache(flaskCentreId, newSessions)
                    }
                } catch (e: Exception) {}
            } else {
                val cachedSessions = loadSessionsCache(flaskCentreId)
                if (cachedSessions.isNotEmpty()) sessions = cachedSessions
            }

            // 5. Fetch attendance/registration records from Flask
            val cat = selectedCategory.lowercase().ifBlank { "youth" }
            // scope=all is load-bearing, not an optimisation. Without it show_attendance falls back
            // to the centre's ACTIVE session, so this list — which is what the registerer's
            // autocomplete searches at the door — collapses to just the people already marked in
            // the class currently running. Everyone else vanishes from lookup at exactly the moment
            // someone is being looked up. The roster does not rescue it: roster.json only holds
            // registered people, and Centre B alone has 769 people who exist only in attendance
            // and are in no roster. scope=all asks for the whole centre+category history instead.
            //
            // Cost, measured 2026-08-17: Centre B youth is 3103 rows that dedup to 855 people
            // (the dedup below keeps one Registration per mobile). That is the largest centre by a
            // wide margin. This runs on user actions — category toggle, centre switch, login — and
            // on the host dashboard's revision key; it is NOT on the registerer's marking path.
            val attendanceJson = apiGet("/api/show_attendance?centre_id=$flaskCentreId&category=$cat&scope=all")
            if (attendanceJson != null) {
                try {
                    val arr = JSONArray(attendanceJson)
                    // show_attendance returns one row PER attendance record, so a person who
                    // attended several sessions appears multiple times. Dedup by mobile (the id
                    // is "r_<mobile>" anyway) so each person is a single suggestion. Keyed map
                    // keeps the last row seen for that mobile.
                    val regByMobile = LinkedHashMap<String, Registration>()
                    val defaultBatchId = fetchedBatches.firstOrNull { it.isDefault }?.id
                        ?: fetchedBatches.firstOrNull()?.id ?: ""
                    for (i in 0 until arr.length()) {
                        val rObj = arr.getJSONObject(i)
                        val mobile = rObj.optString("mobile", "")
                        val batchName = rObj.optString("batch_name", "")
                        val matchedBatch = fetchedBatches.find { it.name.equals(batchName, ignoreCase = true) }
                        val key = mobile.ifEmpty { "$i" }
                        // Normalise to the display form. Storing the server's raw "youth" here is
                        // what hid every attendance-only person from the door filter — see
                        // displayCategory(). A blank falls back to the track being worked in.
                        val rowCategory = displayCategory(rObj.optString("category", ""))
                        regByMobile[key] = Registration(
                            id = "r_${mobile.ifEmpty { "$i" }}",
                            name = rObj.optString("name", "Unknown"),
                            mobile = mobile,
                            address = rObj.optString("address", ""),
                            occupation = rObj.optString("occupation", ""),
                            remark = rObj.optString("remark", ""),
                            centreId = selectedFlaskCentreId,
                            category = rowCategory,
                            batchIds = listOf(matchedBatch?.id ?: defaultBatchId)
                        )
                    }
                    val newRegs = regByMobile.values.toList()
                    if (newRegs.isNotEmpty()) registrations = newRegs
                } catch (e: Exception) {}
            }

            // 5b. Mirror the server's per-centre cache, then FOLD its roster into the member
            // list. These are two different populations and neither contains the other:
            // show_attendance lists people who have ATTENDED, roster.json lists people who
            // were REGISTERED (upsert_centre_roster_entry runs only in register_student).
            // Measured on live data, Centre B has 769 attendance-only people, 557
            // roster-only and just 51 in both — so replacing one with the other would have
            // hidden hundreds of real members. We take the union instead, keyed on the last
            // ten mobile digits, and let the richer show_attendance record win on a clash.
            try {
                refreshServerState(flaskCentreId)
                if (refreshServerRoster(flaskCentreId)) {
                    val merged = mergeServerRosterInto(registrations, flaskCentreId, fetchedBatches)
                    if (merged.isNotEmpty()) registrations = merged
                }
                // Persist only after a successful live merge, so a failed fetch can never
                // overwrite a good cache with a thinner list.
                saveRegistrationsCache(registrations)
            } catch (e: Exception) { /* mirror is best-effort; live data already applied */ }

            // 6. Fetch the whole-centre calling roster from CB (batch_members + names) so Mark
            // Attendance can suggest people who were called but never yet registered/attended.
            try { fetchAttendanceCandidates() } catch (e: Exception) {}
        }
    }

    // Whole-centre calling roster → attendance autocomplete candidates. CB endpoint (needs
    // calling/registration/my_attendance access). Best-effort: empty on failure, so attendance still
    // works from the Flask roster alone.
    suspend fun fetchAttendanceCandidates() = withContext(Dispatchers.IO) {
        val cat = selectedCategory.lowercase()
        val json = callingApiGet("/attendance-roster?category=$cat") ?: return@withContext
        try {
            val obj = JSONObject(json)
            val arr = obj.optJSONObject("data")?.optJSONArray("items") ?: obj.optJSONArray("data") ?: JSONArray()
            val list = (0 until arr.length()).mapNotNull { i ->
                val it = arr.optJSONObject(i) ?: return@mapNotNull null
                val mobile = it.optString("mobile", "").filter { c -> c.isDigit() }.takeLast(10)
                if (mobile.length < 10) return@mapNotNull null
                val nm = it.optString("name", "").ifBlank { mobile }
                Registration(
                    id = "cand_$mobile", name = nm, mobile = mobile,
                    address = "", occupation = "", remark = "",
                    centreId = selectedFlaskCentreId, category = selectedCategory,
                    batchIds = emptyList()
                )
            }
            attendanceCandidates = list
        } catch (e: Exception) {}
    }

    // Server-truth attendance already recorded for ONE session (from Flask). Powers the Mark-
    // Attendance "present in this session" count + list so selecting a session shows its real
    // history — not just what THIS app instance marked. Deduped by last-10 mobile. Empty on
    // failure/offline (the screen then falls back to this-session optimistic marks only).
    suspend fun fetchSessionAttendance(sessionId: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) return@withContext emptyList()
        val cat = selectedCategory.lowercase()
        val json = apiGet("/api/show_attendance?centre_id=$selectedFlaskCentreId&category=$cat&session_id=$sessionId")
            ?: return@withContext emptyList()
        try {
            val arr = JSONArray(json)
            val byMobile = LinkedHashMap<String, Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val mob = o.optString("mobile", "")
                val key = mob.filter { it.isDigit() }.takeLast(10)
                if (key.isEmpty()) continue
                val nm = o.optString("name", "").ifBlank { mob }
                byMobile[key] = nm to mob
            }
            byMobile.values.toList()
        } catch (e: Exception) { emptyList() }
    }

    // Fetch all users (active, pending, inactive) from Node.js — for ADMIN and SUPER_ADMIN
    suspend fun fetchUsers() = withContext(Dispatchers.IO) {
        val allUsers = mutableListOf<User>()

        listOf("/users/active", "/users/requests", "/users/inactive").forEach { path ->
            val result = callingApiGet(path) ?: return@forEach
            try {
                val obj = JSONObject(result)
                val items = obj.optJSONObject("data")?.optJSONArray("items") ?: return@forEach
                for (i in 0 until items.length()) {
                    val u = items.getJSONObject(i)
                    val approvalStatus = u.optString("approval_status", "approved")
                    val isActive = u.optBoolean("is_active", true)
                    val status = when {
                        approvalStatus == "pending" -> "pending"
                        !isActive -> "inactive"
                        else -> "active"
                    }
                    allUsers.add(User(
                        id = u.optInt("id", 0).toString(),
                        username = u.optString("username", u.optString("mobile", "")),
                        name = u.optString("name", ""),
                        role = u.optString("role", "CALLER"),
                        status = status,
                        isAeAccessEnabled = u.optBoolean("attendance_system_access", false),
                        centreId = u.optInt("centre_id", 0).toString(),
                        roleMode = u.optString("role_mode", "both")
                    ))
                }
            } catch (e: Exception) {}
        }

        if (allUsers.isNotEmpty()) withContext(Dispatchers.Main) { users = allUsers }
    }

    // User management API calls — all use Node.js backend
    suspend fun approveUserApi(userId: String): Boolean {
        val result = callingApiPatch("/users/$userId/approve") ?: return false
        return try { JSONObject(result).optBoolean("success", false) } catch (e: Exception) { false }
    }

    suspend fun rejectUserApi(userId: String): Boolean {
        val result = callingApiPatch("/users/$userId/reject") ?: return false
        return try { JSONObject(result).optBoolean("success", false) } catch (e: Exception) { false }
    }

    suspend fun activateUserApi(userId: String): Boolean {
        val result = callingApiPatch("/users/$userId/activate") ?: return false
        return try { JSONObject(result).optBoolean("success", false) } catch (e: Exception) { false }
    }

    suspend fun deactivateUserApi(userId: String): Boolean {
        val result = callingApiPatch("/users/$userId/deactivate") ?: return false
        return try { JSONObject(result).optBoolean("success", false) } catch (e: Exception) { false }
    }

    suspend fun toggleAeAccessApi(userId: String): Boolean {
        val result = callingApiPatch("/users/$userId/toggle-attendance-access") ?: return false
        return try { JSONObject(result).optBoolean("success", false) } catch (e: Exception) { false }
    }

    // Set a staff member's workspace role: "registerer", "caller", or "both".
    // The backend rewrites the calling-permission bundle + AE access to match, then
    // reads BOTH sides back and returns a confirmation block. `verified` = the change
    // is fully applied (CB permissions + AE account both confirmed); success-but-not-
    // verified = saved, AE provisioning still syncing (reconciler will complete it).
    data class RoleUpdateResult(val success: Boolean, val verified: Boolean, val detail: String)

    suspend fun setUserRoleApi(userId: String, roleMode: String): RoleUpdateResult {
        val body = JSONObject().apply { put("role", roleMode) }.toString()
        val result = callingApiPatch("/users/$userId/role", body)
            ?: return RoleUpdateResult(false, false, "No response from server")
        return try {
            val j = JSONObject(result)
            val ok = j.optBoolean("success", false)
            val conf = j.optJSONObject("data")?.optJSONObject("confirmation")
            RoleUpdateResult(ok, conf?.optBoolean("verified", false) ?: false, conf?.optString("detail", "") ?: "")
        } catch (e: Exception) { RoleUpdateResult(false, false, "Unexpected response") }
    }

    suspend fun adminResetPasswordApi(username: String, newPassword: String): Boolean {
        val body = JSONObject().apply { put("new_password", newPassword) }.toString()
        val result = callingApiPost("/admin/users/$username/reset-password", body) ?: return false
        return try { JSONObject(result).optBoolean("ok", false) } catch (e: Exception) { false }
    }

    suspend fun createAdminUserApi(name: String, username: String, password: String, role: String, aeAccess: Boolean): Boolean {
        val body = JSONObject().apply {
            put("name", name)
            put("username", username)
            put("password", password)
            put("role", role)
            put("ae_access", aeAccess)
        }.toString()
        val result = callingApiPost("/admin/users", body) ?: return false
        return try { JSONObject(result).optBoolean("ok", false) } catch (e: Exception) { false }
    }

    suspend fun changeUsernameApi(userId: String, newUsername: String): Boolean {
        val body = JSONObject().apply { put("new_username", newUsername) }.toString()
        val result = callingApiPatch("/admin/users/$userId/username", body) ?: return false
        return try { JSONObject(result).optBoolean("ok", false) } catch (e: Exception) { false }
    }

    suspend fun deleteUserApi(userId: String): Boolean {
        val result = callingApiDelete("/admin/users/$userId") ?: return false
        return try { JSONObject(result).optBoolean("ok", false) } catch (e: Exception) { false }
    }

    // Built-in sheet opener helper (Flask)
    suspend fun fetchSheetData(sheetName: String): Pair<List<String>, List<Map<String, Any>>> = withContext(Dispatchers.IO) {
        // centre_id for the same reason fetchServerFiles sends it — see the note there.
        // Also send category (2026-08-18). AttendEase has a per-sheet guard, _sheet_category_allowed,
        // that refuses to serve a congregation sheet to a youth request and vice versa — but it is
        // additive: with no category claimed it filters nothing, so it sat inert as long as the app
        // never sent the param. Now that the Downloads list is itself track-filtered, a user can only
        // ever tap a sheet of their own track, so claiming it here costs nothing and turns the guard
        // on. Defence in depth: the list decides what is offered, this decides what is served.
        val centreQ = buildString {
            val parts = mutableListOf<String>()
            selectedFlaskCentreId.ifBlank { "" }.let { if (it.isNotBlank()) parts.add("centre_id=$it") }
            selectedCategory.trim().lowercase().let {
                if (it == "youth" || it == "congregation") parts.add("category=$it")
            }
            if (parts.isNotEmpty()) append("?").append(parts.joinToString("&"))
        }
        val result = apiGet("/api/get_sheet_content/$sheetName$centreQ")
            ?: return@withContext Pair(emptyList(), emptyList())
        try {
            val obj = JSONObject(result)
            val hArr = obj.getJSONArray("headers")
            val headers = mutableListOf<String>()
            for (i in 0 until hArr.length()) headers.add(hArr.getString(i))
            val dArr = obj.getJSONArray("data")
            val rows = mutableListOf<Map<String, Any>>()
            for (i in 0 until dArr.length()) {
                val rObj = dArr.getJSONObject(i)
                val rowMap = mutableMapOf<String, Any>()
                val keys = rObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    rowMap[k] = rObj.get(k)
                }
                rows.add(rowMap)
            }
            Pair(headers, rows)
        } catch (e: Exception) {
            Pair(emptyList(), emptyList())
        }
    }

    /**
     * Actually contact an attendance-backend URL and report what came back.
     *
     * The Sync Config "Test" button used to check only that the string started with "http" and then
     * claim "backend reachable" — so a typo'd but well-formed URL passed, which is precisely the case
     * the button exists to catch. Takes the URL as an argument so an unsaved edit can be tested
     * before it is committed.
     */
    suspend fun probeAttendanceBackend(url: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val base = url.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return@withContext Pair(false, "URL must start with http:// or https://")
        }
        val t0 = System.currentTimeMillis()
        try {
            client.newCall(Request.Builder().url("$base/health").get().build()).execute().use { response ->
                val ms = System.currentTimeMillis() - t0
                logReq("AE", "GET", "/health", response.code, ms)
                if (!response.isSuccessful) {
                    return@withContext Pair(false, "Reached the host but it answered HTTP ${response.code}.")
                }
                val body = response.body?.string().orEmpty()
                val obj = runCatching { JSONObject(body) }.getOrNull()
                val service = obj?.optString("service", "").orEmpty()
                val depth = obj?.optInt("webhook_queue_depth", 0) ?: 0
                if (obj?.optBoolean("ok", false) != true) {
                    return@withContext Pair(false, "That host responded but is not an AttendEase backend.")
                }
                val queueNote = if (depth > 50) " Warning: $depth events are backed up." else ""
                Pair(true, "Connected to ${service.ifEmpty { "AttendEase" }} in ${ms}ms.$queueNote")
            }
        } catch (e: Exception) {
            logErr("AE", "GET", "/health", e.message ?: "io")
            Pair(false, "Could not reach that address (${e.message ?: "no response"}).")
        }
    }

    /** Epoch millis of the master workbook's last write, or null if unavailable. */
    suspend fun fetchMasterLastUpdate(): Long? = withContext(Dispatchers.IO) {
        val result = apiGet("/api/get_last_update") ?: return@withContext null
        runCatching {
            val mtime = JSONObject(result).optDouble("mtime", 0.0)
            if (mtime > 0) (mtime * 1000).toLong() else null
        }.getOrNull()
    }

    /**
     * Download one sheet as a real .xlsx and save it where the user can find it.
     *
     * The button behind this used to show "saving to Android shared downloads folder" and then do
     * nothing at all — no file was ever written, despite the server endpoint being complete. Returns
     * the human-readable destination on success so the message can name where the file actually is.
     *
     * API 29+ writes to the shared Downloads collection via MediaStore, which needs no permission.
     * Below that, writing to shared storage would require WRITE_EXTERNAL_STORAGE and a runtime
     * prompt, so we use the app's own external files directory instead and say so.
     */
    suspend fun downloadSheetFile(sheetName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext Pair(false, "App is not initialised yet.")
        val encoded = java.net.URLEncoder.encode(sheetName, "UTF-8").replace("+", "%20")
        val fileName = sheetName.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".xlsx"
        val xlsxMime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

        // centre_id for the same reason fetchServerFiles sends it — see the note there. The server
        // validates it against the caller's own access before honouring it, so it cannot be used to
        // reach a centre the registerer does not belong to.
        // Also send category (2026-08-18). AttendEase has a per-sheet guard, _sheet_category_allowed,
        // that refuses to serve a congregation sheet to a youth request and vice versa — but it is
        // additive: with no category claimed it filters nothing, so it sat inert as long as the app
        // never sent the param. Now that the Downloads list is itself track-filtered, a user can only
        // ever tap a sheet of their own track, so claiming it here costs nothing and turns the guard
        // on. Defence in depth: the list decides what is offered, this decides what is served.
        val centreQ = buildString {
            val parts = mutableListOf<String>()
            selectedFlaskCentreId.ifBlank { "" }.let { if (it.isNotBlank()) parts.add("centre_id=$it") }
            selectedCategory.trim().lowercase().let {
                if (it == "youth" || it == "congregation") parts.add("category=$it")
            }
            if (parts.isNotEmpty()) append("?").append(parts.joinToString("&"))
        }
        val requestBuilder = Request.Builder().url("$backendUrl/api/download_file/$encoded$centreQ").get()
        jwtToken?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
        val t0 = System.currentTimeMillis()
        try {
            val bytes = client.newCall(requestBuilder.build()).execute().use { response ->
                logReq("AE", "GET", "/api/download_file", response.code, System.currentTimeMillis() - t0)
                when {
                    // 403 now has two causes, not one: wrong centre (_sheet_belongs_to_caller) or wrong
                    // track (_sheet_category_allowed, live since the app started sending ?category=).
                    // Naming only the centre would send someone hunting the wrong problem.
                    response.code == 403 -> return@withContext Pair(
                        false, "This sheet belongs to another centre or another track."
                    )
                    !response.isSuccessful -> return@withContext Pair(false, "Server could not build the file (HTTP ${response.code}).")
                    else -> response.body?.bytes()
                }
            } ?: return@withContext Pair(false, "The server returned an empty file.")

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, xlsxMime)
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext Pair(false, "Could not create the file in Downloads.")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: return@withContext Pair(false, "Could not write to Downloads.")
                // Clearing IS_PENDING is what makes the file visible to other apps; skipping it
                // leaves an invisible zero-byte entry behind.
                resolver.update(uri, android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
                Pair(true, "Downloads/$fileName")
            } else {
                val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                    ?: return@withContext Pair(false, "No storage available on this device.")
                if (!dir.exists()) dir.mkdirs()
                val out = java.io.File(dir, fileName)
                out.writeBytes(bytes)
                Pair(true, out.absolutePath)
            }
        } catch (e: Exception) {
            logErr("AE", "GET", "/api/download_file", e.message ?: "io")
            Pair(false, e.message ?: "Download failed.")
        }
    }

    /**
     * Downloads a CSV export report for a completed session campaign from the Node calling backend.
     * The response bytes carry a UTF-8 BOM so Excel opens it with correct encoding.
     * Raw bytes are written to storage directly — never decoded to String and re-encoded.
     */
    suspend fun downloadSessionReport(
        campaignId: String,
        field: String,
        order: String? = null,
        cohort: String? = null,
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext Pair(false, "App is not initialised yet.")
        val queryParts = mutableListOf("field=$field")
        if (!order.isNullOrBlank()) queryParts.add("order=$order")
        if (!cohort.isNullOrBlank()) queryParts.add("cohort=$cohort")
        val queryStr = queryParts.joinToString("&")
        val path = "/session-campaigns/$campaignId/export?$queryStr"

        val requestBuilder = Request.Builder().url("$callingBackendUrl$path").get()
        jwtToken?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
        selectedCentreId.toIntOrNull()?.let { requestBuilder.addHeader("x-centre-id", it.toString()) }

        val t0 = System.currentTimeMillis()
        try {
            var serverFilename: String? = null
            val bytes = client.newCall(requestBuilder.build()).execute().use { response ->
                val ms = System.currentTimeMillis() - t0
                logReq("SRKB", "GET", path, response.code, ms)
                when (response.code) {
                    401 -> return@withContext Pair(false, "Session expired. Please log in again.")
                    403 -> return@withContext Pair(false, "Permission denied (requires Report or Assign Calling access).")
                    404 -> return@withContext Pair(false, "Session campaign not found.")
                    422 -> return@withContext Pair(false, "Invalid export options.")
                    else -> if (!response.isSuccessful) {
                        return@withContext Pair(false, "Server could not build export (HTTP ${response.code}).")
                    }
                }

                val disposition = response.header("Content-Disposition")
                if (disposition != null) {
                    val match = Regex("""filename=["']?([^"';]+)["']?""").find(disposition)
                    if (match != null) {
                        serverFilename = match.groupValues[1]
                    }
                }
                response.body?.bytes()
            } ?: return@withContext Pair(false, "Server returned an empty file.")

            if (bytes.isEmpty()) {
                return@withContext Pair(false, "Server returned an empty file.")
            }

            // Empty group check: UTF-8 BOM + header line only
            val csvText = String(bytes, Charsets.UTF_8).trim()
            val nonBlankLines = csvText.lines().filter { it.isNotBlank() }
            if (nonBlankLines.size <= 1) {
                return@withContext Pair(false, "No people in this group for this session")
            }

            val rawName = serverFilename ?: buildString {
                append("session_report_${campaignId}_${field}")
                if (!order.isNullOrBlank()) append("_$order")
                if (!cohort.isNullOrBlank()) append("_$cohort")
                append(".csv")
            }
            val fileName = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_").let {
                if (it.endsWith(".csv", ignoreCase = true)) it else "$it.csv"
            }
            val mimeType = "text/csv"

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext Pair(false, "Could not create the file in Downloads.")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: return@withContext Pair(false, "Could not write to Downloads.")
                resolver.update(uri, android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
                Pair(true, "Saved to Downloads/$fileName")
            } else {
                val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                    ?: return@withContext Pair(false, "No storage available on this device.")
                if (!dir.exists()) dir.mkdirs()
                val out = java.io.File(dir, fileName)
                out.writeBytes(bytes)
                Pair(true, "Saved to ${out.name}")
            }
        } catch (e: Exception) {
            logErr("SRKB", "GET", path, e.message ?: "io")
            Pair(false, e.message ?: "Download failed.")
        }
    }

    suspend fun fetchServerFiles(): List<Map<String, String>> = withContext(Dispatchers.IO) {
        // DO pass ?category= (2026-08-18). This used to be deliberately omitted, because a sheet's
        // track is resolved from SessionMapping.category and 32 of 36 mapping rows had that column
        // NULL — so asking for a track filtered out almost everything. The backfill on 2026-08-17
        // resolved every one of those from its batch (31 rows changed, 0 unresolved, 0 nulls left),
        // which is what makes this safe to turn on.
        //
        // Measured against the live server before enabling, rather than reasoned about:
        //
        //     centre                no param   youth   congregation
        //     centre_b_Base          155     155              0
        //     centre_a_Base              8       6              2
        //     rani_ghat_bace               5       5              0
        //
        // youth == no-param at EVERY centre, so switching this on cannot empty a youth screen —
        // the 122 unmapped legacy tabs (session_1..session_98 and some test junk) all fall back to
        // youth exactly as they did before. The only list that narrows is congregation at
        // Centre A, 8 -> 2, and that is the whole point: those 6 were youth sheets being offered
        // on the congregation track.
        //
        // The zeros are correct, not a regression. Centre A is the only centre with a
        // congregation batch at all; bazar and rani ghat have none, so there is genuinely nothing
        // for a congregation user to download there. DownloadsScreen says so in words rather than
        // showing the generic "No server files found", which would read as a fault.
        //
        // Sent lowercase. The server does .lower() itself so either would work, but selectedCategory
        // holds "Youth"/"Congregation" capitalised and an unrecognised value is silently treated as
        // "no filter claimed" — a Youth/youth mismatch has already cost this system a day once.
        //
        // DO pass centre_id (2026-08-17). The old comment here claimed "centre scoping is unaffected:
        // the server enforces that separately via _sheet_belongs_to_caller" — that was wrong. That
        // guard identified the caller from the FLASK SESSION, which this app never has (Bearer only,
        // no CookieJar), so every user of every centre was silently treated as the default centre and
        // served its 156 sheets while being shown none of their own. The server fix no longer needs
        // this param to be safe — it falls back to the user's own CentreAccess — but a registerer with
        // access to two centres would otherwise see both centres' sheets merged into one list. Sending
        // the centre actually being worked in is what makes the tab show exactly that centre.
        val centreParam = selectedFlaskCentreId.ifBlank { "" }
        val catParam = selectedCategory.trim().lowercase()
        val parts = mutableListOf<String>()
        if (centreParam.isNotBlank()) parts.add("centre_id=$centreParam")
        if (catParam == "youth" || catParam == "congregation") parts.add("category=$catParam")
        val q = if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
        val result = apiGet("/api/list_server_files$q") ?: return@withContext emptyList()
        try {
            val arr = JSONArray(result)
            val files = mutableListOf<Map<String, String>>()
            for (i in 0 until arr.length()) {
                val fObj = arr.getJSONObject(i)
                files.add(mapOf(
                    "name" to fObj.getString("name"),
                    "size" to fObj.optLong("size", 0L).toString(),
                    "time" to fObj.optString("time", "")
                ))
            }
            files
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Fetch aggregate host stats from Node.js — SUPER_ADMIN only
    suspend fun fetchHostStats(): HostStats? = withContext(Dispatchers.IO) {
        val result = callingApiGet("/host/stats/summary") ?: return@withContext null
        try {
            val d = JSONObject(result).optJSONObject("data") ?: return@withContext null
            val totals = d.optJSONObject("totals") ?: JSONObject()
            val centresArr = d.optJSONArray("centres")
            val byCentre = if (centresArr != null) {
                (0 until centresArr.length()).map { i ->
                    val c = centresArr.getJSONObject(i)
                    CentreStatSummary(
                        centreId = c.optInt("centreId", 0).toString(),
                        centreCode = c.optString("centreCode", ""),
                        registrations = c.optInt("registrations", 0),
                        activeCalling = c.optInt("activeCalling", 0),
                        followups = c.optInt("followups", 0)
                    )
                }
            } else emptyList()
            HostStats(
                centreCount = d.optInt("centreCount", 0),
                totalRegistrations = totals.optInt("registrations", 0),
                byCentre = byCentre
            )
        } catch (e: Exception) { null }
    }

    // Fetch per-user dashboard stats from Node.js calling backend
    suspend fun fetchDashboardStats(): DashboardStats = withContext(Dispatchers.IO) {
        val category = selectedCategory.lowercase()
        val result = callingApiGet("/dashboard/summary?category=$category") ?: return@withContext DashboardStats()
        try {
            val d = JSONObject(result).optJSONObject("data") ?: return@withContext DashboardStats()
            DashboardStats(
                assignedCalling   = d.optInt("assigned_calling", 0),
                pendingFollowups  = d.optInt("pending_followups", 0),
                myAttendanceCount = d.optInt("my_attendance_count", 0),
                batchCallingCount = d.optInt("batch_calling_count", 0),
                registrationsCount= d.optInt("registrations_count", 0),
                newRegistrations  = d.optInt("new_registrations", 0),
                activeCalling     = d.optInt("active_calling", 0),
                archivedCount     = d.optInt("archived_count", 0),
                removedCount      = d.optInt("removed_count", 0),
                totalRegistrations= d.optInt("total_registrations", 0)
            )
        } catch (e: Exception) { DashboardStats() }
    }

    /**
     * The server's stuck-data check for this centre: people whose state isn't backed by anything,
     * sessions left open that are still holding people, a number held in two places. Null when the
     * check couldn't be reached — the banner then shows nothing rather than a false all-clear.
     */
    suspend fun fetchIntegrityIssues(): List<IntegrityIssue>? = withContext(Dispatchers.IO) {
        val result = callingApiGet("/integrity") ?: return@withContext null
        try {
            val arr = JSONObject(result).optJSONObject("data")?.optJSONArray("issues") ?: return@withContext null
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val ex = o.optJSONArray("examples")
                IntegrityIssue(
                    code = o.optString("code"),
                    severity = o.optString("severity", "warning"),
                    count = o.optInt("count", 0),
                    title = o.optString("title"),
                    action = o.optString("action"),
                    examples = if (ex == null) emptyList() else (0 until ex.length()).map { ex.optString(it) },
                )
            }
        } catch (e: Exception) { null }
    }

    // ── Admin dictionary (dropdown) management ────────────────────────────────
    // Deliberately NOT written into `dropdownOptions`: that list is the caller's outcome picker and
    // must stay active-only and single-context. These rows include inactive ones and every context.

    /** Full dictionary for the management screen — this centre's rows plus the host-owned globals. */
    suspend fun fetchAdminDropdowns(): List<AdminDropdownOption> = withContext(Dispatchers.IO) {
        val result = callingApiGet("/admin/dropdowns?include_global=true") ?: return@withContext emptyList()
        try {
            val arr = JSONObject(result).optJSONObject("data")?.optJSONArray("items")
                ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val d = arr.optJSONObject(i) ?: return@mapNotNull null
                AdminDropdownOption(
                    id = d.optInt("id", -1),
                    label = d.optString("label", ""),
                    context = d.optString("context", ""),
                    category = d.optString("category", "both"),
                    action = d.optString("action", "").ifEmpty { null },
                    deferDays = if (d.isNull("defer_days")) null else d.optInt("defer_days").takeIf { it > 0 },
                    sortOrder = d.optInt("sort_order", 0),
                    isActive = d.optBoolean("is_active", true),
                    isGlobal = d.optBoolean("is_global", false),
                    centreId = if (d.isNull("centreId")) null else d.optInt("centreId").takeIf { it > 0 },
                    overridesId = if (d.isNull("overrides_id")) null else d.optInt("overrides_id").takeIf { it > 0 },
                    isOverride = d.optBoolean("is_override", false),
                    sentiment = d.optString("sentiment", ResponseMeaning.NOT_REACHED),
                    countsAsStrike = d.optBoolean("counts_as_strike", false),
                    removeAfterConsecutive = if (d.isNull("remove_after_consecutive")) null
                        else d.optInt("remove_after_consecutive").takeIf { it > 0 },
                    answeredScope = d.optString("answered_scope", ResponseMeaning.SCOPE_BOTH),
                    isSystem = d.optBoolean("is_system", false)
                ).takeIf { it.id > 0 }
            }
        } catch (e: Exception) { emptyList() }
    }

    /**
     * Create a dictionary option for THIS centre. `action` is mandatory server-side — omitting it
     * 422s — and `deferDays` is only honoured when the action is defer_calling.
     */
    suspend fun createDropdownOption(
        label: String,
        context: String,
        category: String,
        action: String,
        sortOrder: Int,
        deferDays: Int? = null,
        sentiment: String? = null,
        countsAsStrike: Boolean? = null,
        removeAfterConsecutive: Int? = null,
        answeredScope: String? = null
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("label", label)
            put("context", context)
            put("category", category)
            put("action", action)
            put("sort_order", sortOrder)
            if (deferDays != null) put("defer_days", deferDays)
            sentiment?.let { put("sentiment", it) }
            countsAsStrike?.let { put("counts_as_strike", it) }
            // Explicit null is meaningful here — it is how "never remove them" is expressed.
            put("remove_after_consecutive", removeAfterConsecutive ?: JSONObject.NULL)
            answeredScope?.let { put("answered_scope", it) }
        }.toString()
        callingApiSend("POST", "/dropdowns", body)
    }

    suspend fun updateDropdownOption(
        id: Int,
        label: String? = null,
        action: String? = null,
        sortOrder: Int? = null,
        isActive: Boolean? = null,
        deferDays: Int? = null,
        clearDeferDays: Boolean = false,
        sentiment: String? = null,
        countsAsStrike: Boolean? = null,
        removeAfterConsecutive: Int? = null,
        clearRemoveAfterConsecutive: Boolean = false,
        answeredScope: String? = null
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            label?.let { put("label", it) }
            action?.let { put("action", it) }
            sortOrder?.let { put("sort_order", it) }
            isActive?.let { put("is_active", it) }
            // Sending an explicit null clears the interval; omitting the key preserves it.
            if (clearDeferDays) put("defer_days", JSONObject.NULL)
            else deferDays?.let { put("defer_days", it) }
            sentiment?.let { put("sentiment", it) }
            countsAsStrike?.let { put("counts_as_strike", it) }
            // Same absent-vs-null distinction as defer_days: the server inherits the stored value
            // when the key is missing, and clears it when the key is present and null.
            if (clearRemoveAfterConsecutive) put("remove_after_consecutive", JSONObject.NULL)
            else removeAfterConsecutive?.let { put("remove_after_consecutive", it) }
            answeredScope?.let { put("answered_scope", it) }
        }.toString()
        callingApiSend("PUT", "/dropdowns/$id", body)
    }

    // ── Remark and address lists (Flask) ──────────────────────────────────────
    //
    // These live in the ATTENDANCE backend, not the calling one, because the registration screen
    // reads them. Every call carries centre_id: without it Flask falls back to the JWT user's
    // primary centre, which is the wrong list for anyone working in a second centre.

    private fun parseFlaskOptions(raw: String?): List<FlaskOption> {
        if (raw == null) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                FlaskOption(
                    id = o.optInt("id", -1),
                    text = o.optString("text", ""),
                    centreId = if (o.isNull("centre_id")) null else o.optString("centre_id").ifBlank { null },
                    isShared = o.optBoolean("is_shared", o.isNull("centre_id"))
                ).takeIf { it.id > 0 && it.text.isNotBlank() }
            }
        } catch (e: Exception) { emptyList() }
    }

    /** kind is "remarks" or "addresses" — the two Flask endpoints share one shape. */
    suspend fun fetchFlaskOptions(kind: String): List<FlaskOption> = withContext(Dispatchers.IO) {
        parseFlaskOptions(apiGet("/api/$kind?centre_id=$selectedFlaskCentreId"))
    }

    suspend fun createFlaskOption(kind: String, text: String): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            apiSendWithError("POST", "/api/$kind",
                JSONObject().apply { put("text", text); put("centre_id", selectedFlaskCentreId) }.toString())
        }

    suspend fun renameFlaskOption(kind: String, id: Int, text: String): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            apiSendWithError("PUT", "/api/$kind/$id",
                JSONObject().apply { put("text", text); put("centre_id", selectedFlaskCentreId) }.toString())
        }

    suspend fun deleteFlaskOption(kind: String, id: Int): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            apiSendWithError("DELETE", "/api/$kind/$id",
                JSONObject().apply { put("centre_id", selectedFlaskCentreId) }.toString())
        }

    /** Disable uses the dedicated PATCH route; re-enable goes back through PUT. */
    suspend fun setDropdownOptionActive(id: Int, active: Boolean): Pair<Boolean, String?> =
        if (active) updateDropdownOption(id = id, isActive = true)
        else callingApiSend("PATCH", "/dropdowns/$id/disable")

    /**
     * Discard this centre's customisation of a shared option so the shared one applies again.
     * Only valid on an override row — a option the centre created outright has nothing to revert to.
     */
    suspend fun revertDropdownOverride(id: Int): Pair<Boolean, String?> =
        callingApiSend("DELETE", "/dropdowns/$id/override")

    // Fetch notifications from Node.js and update AppDatabase.notifications
    suspend fun fetchNotificationsFromBackend() = withContext(Dispatchers.IO) {
        val result = callingApiGet("/notifications") ?: return@withContext
        try {
            val items = JSONObject(result).optJSONObject("data")?.optJSONArray("items")
                ?: return@withContext
            val uidRegex = Regex("\\s*\\[uid:(\\d+)\\]")
            val list = (0 until items.length()).map { i ->
                val n = items.getJSONObject(i)
                val rawBody = n.optString("message", "")
                val uidMatch = uidRegex.find(rawBody)
                val referenceId = uidMatch?.groupValues?.getOrNull(1)
                val cleanBody = if (uidMatch != null) rawBody.replace(uidMatch.value, "") else rawBody
                Notification(
                    id               = n.optInt("id", i).toString(),
                    title            = n.optString("title", "Notification"),
                    body             = cleanBody,
                    createdAt        = n.optString("createdAt", ""),
                    isRead           = n.optBoolean("isRead", false),
                    type             = n.optString("type", "alert"),
                    referenceId      = referenceId,
                    referenceUsername = n.optString("referenceUsername", "").ifEmpty { null }
                )
            }
            withContext(Dispatchers.Main) { notifications = list }
        } catch (e: Exception) {}
    }

    // ── Analytics: attendance trend per batch (Flask) ──────────────────────
    suspend fun fetchAttendanceTrend(centreId: String? = null, days: Int = 30): List<AttendanceTrendBatch> = withContext(Dispatchers.IO) {
        val param = if (centreId != null) "?centre_id=$centreId&days=$days" else "?days=$days"
        val result = apiGet("/api/analytics/attendance-trend$param") ?: return@withContext emptyList()
        try {
            val arr = JSONObject(result).optJSONArray("data") ?: return@withContext emptyList()
            (0 until arr.length()).map { i ->
                val b = arr.getJSONObject(i)
                val pts = b.optJSONArray("points") ?: org.json.JSONArray()
                AttendanceTrendBatch(
                    batchName = b.optString("batch_name", "Unknown"),
                    centreId  = b.optString("centre_id", ""),
                    points    = (0 until pts.length()).map { j ->
                        val p = pts.getJSONObject(j)
                        AttendanceTrendPoint(date = p.optString("date", ""), count = p.optInt("count", 0))
                    }
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    // ── Analytics: per-centre caller + admin counts (Node.js) ──────────────
    suspend fun fetchCentreUserCounts(): List<CentreUserCount> = withContext(Dispatchers.IO) {
        val result = callingApiGet("/host/centre-users") ?: return@withContext emptyList()
        try {
            val arr = JSONObject(result).optJSONArray("data") ?: return@withContext emptyList()
            (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                CentreUserCount(
                    centreId    = c.optInt("centreId", 0).toString(),
                    centreName  = c.optString("centreName", ""),
                    centreCode  = c.optString("centreCode", ""),
                    callerCount = c.optInt("callerCount", 0),
                    adminCount  = c.optInt("adminCount", 0)
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    // Fetch public centre list before login (for register dialog)
    suspend fun fetchPublicCentres(): List<Centre> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$callingBackendUrl/auth/centres").get().build()
        val result = try {
            client.newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        } catch (e: Exception) { null } ?: return@withContext emptyList()
        try {
            val arr = JSONObject(result).optJSONObject("data")?.optJSONArray("items")
                ?: return@withContext emptyList()
            (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                Centre(id = c.optInt("id").toString(), name = c.optString("name"), code = c.optString("code"))
            }
        } catch (e: Exception) { emptyList() }
    }

    // Log in via Node.js calling backend — returns null on success, error string on failure
    suspend fun logIn(username: String, password: String): String? = withContext(Dispatchers.IO) {
        val loginBody = JSONObject().apply {
            put("identifier", username)
            put("password", password)
        }.toString()

        val body = loginBody.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$callingBackendUrl/auth/login")
            .post(body)
            .build()

        val httpResponse = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            val errType = e.javaClass.simpleName
            val errMsg = e.message ?: "no message"
            Log.e(TAG, "[ERR] SRKB POST /auth/login → $errType: $errMsg")
            return@withContext "Connection failed ($errType): $errMsg"
        }

        val result = httpResponse.use { it.body?.string() }
        val statusCode = httpResponse.code
        logReq("SRKB", "POST", "/auth/login", statusCode, 0)

        if (statusCode == 401 || statusCode == 403) {
            val msg = try { JSONObject(result ?: "").optString("message") } catch (e: Exception) { "" }
            return@withContext msg.ifEmpty { "Incorrect username or password." }
        }
        if (!httpResponse.isSuccessful || result == null) {
            return@withContext "Server error ($statusCode). Try again."
        }

        try {
            val obj = JSONObject(result)
            val success = obj.optBoolean("success", false)
            if (!success) return@withContext "Login failed. Check your credentials."

            val data = obj.optJSONObject("data") ?: return@withContext "Invalid response from server."
            val token = data.optString("token").ifEmpty { null } ?: return@withContext "No token in response."
            val userObj = data.optJSONObject("user") ?: return@withContext "No user data in response."

            val role = userObj.optString("role", "CALLER")
            val centreIdRaw = if (userObj.isNull("centreId")) null else userObj.optInt("centreId", 0)
            val centreIdStr = centreIdRaw?.toString() ?: ""

            val permsList = mutableListOf<String>()
            val permsArr = userObj.optJSONArray("permissions")
            if (permsArr != null) {
                for (i in 0 until permsArr.length()) permsList.add(permsArr.getString(i))
            }

            val aeAccess = userObj.optBoolean("attendanceSystemAccess", false)
            val mustChange = userObj.optBoolean("mustChangePassword", false)
            val mobile = userObj.optString("mobile", username)

            withContext(Dispatchers.Main) {
                jwtToken = token
                // A 401 on /events latches RealtimeSseClient permanently off, and nothing else in
                // the app ever clears that latch — resetAuthStop() had zero callers. A fresh token
                // is precisely the event that makes retrying worthwhile, so clear it here.
                runCatching { RealtimeSseClient.resetAuthStop() }
                currentUser = User(
                    id = userObj.optInt("id", 0).toString(),
                    username = mobile,
                    name = userObj.optString("name", username),
                    role = role,
                    status = "active",
                    isAeAccessEnabled = aeAccess,
                    centreId = centreIdStr,
                    permissions = permsList
                )
                if (centreIdStr.isNotEmpty()) selectedCentreId = centreIdStr
                val centreCode = userObj.optString("centreCode", "")
                if (centreCode.isNotEmpty()) selectedFlaskCentreId = centreCode
                currentMode = when {
                    role == "SUPER_ADMIN" -> "registerer"
                    role == "ADMIN" -> "registerer"
                    // A caller's primary workspace is caller mode — land there whenever they can call,
                    // regardless of whether they also have attendance (AE) access.
                    role == "CALLER" && permsList.contains("calling_access") -> "caller"
                    else -> "registerer"
                }
                mustChangePassword = mustChange
                persistSession()
            }

            null  // null = success
        } catch (e: Exception) {
            "Unexpected response from server."
        }
    }

    // Save new password to CB backend. When mustChangePassword=true, old_password is not required.
    // Sets mustChangePassword=false in the DB so the next login doesn't re-trigger the force screen.
    suspend fun changePasswordOnBackend(newPassword: String): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().apply { put("new_password", newPassword) }.toString()
        val result = callingApiPost("/auth/change-password", body) ?: return@withContext false
        try { JSONObject(result).optBoolean("success", false) } catch (e: Exception) { false }
    }

    // Create a new centre on the Node.js backend — called by CreateCentreDialog (SUPER_ADMIN)
    // Returns the server's centre (numeric id + code) or null. The categories ticked in the dialog
    // are sent; this used to send "both" whatever was ticked.
    suspend fun createCentreOnBackend(
        centreId: String, name: String, googleSheetId: String, sheetEnabled: Boolean,
        enabledYouth: Boolean = true, enabledCongregation: Boolean = true
    ): Centre? = withContext(Dispatchers.IO) {
        val code = name.trim().take(4).uppercase().replace(Regex("[^A-Z0-9]"), "").ifEmpty { "CEN" }
        val body = JSONObject().apply {
            put("name", name.trim())
            put("code", code)
            put("allowed_categories", categoriesValue(enabledYouth, enabledCongregation))
            put("is_active", true)
        }.toString()
        val result = callingApiPost("/centres", body) ?: return@withContext null
        try {
            val d = JSONObject(result).optJSONObject("data") ?: return@withContext null
            val cats = d.optString("allowed_categories", "both")
            Centre(
                id = d.optInt("id").toString(),
                name = d.optString("name", name),
                code = d.optString("code", code),
                googleSheetId = googleSheetId,
                isSheetEnabled = sheetEnabled,
                enabledYouth = cats != "congregation",
                enabledCongregation = cats != "youth",
                status = if (d.optBoolean("is_active", true)) "active" else "inactive"
            )
        } catch (e: Exception) { null }
    }

    private fun categoriesValue(youth: Boolean, congregation: Boolean) = when {
        youth && congregation -> "both"
        congregation -> "congregation"
        else -> "youth"
    }

    // Super-admin Edit Centre. Saves on the server (which also flips the attendance server's
    // congregation switch). Returns null on success, else the reason to show. Before this, the
    // dialog only changed the list on this phone.
    suspend fun updateCentreOnBackend(centre: Centre): String? = withContext(Dispatchers.IO) {
        val id = centre.id.toIntOrNull() ?: return@withContext "This centre has no server id — refresh and try again."
        val body = JSONObject().apply {
            put("name", centre.name.trim())
            put("allowed_categories", categoriesValue(centre.enabledYouth, centre.enabledCongregation))
        }.toString()
        if (callingApiPatch("/centres/$id", body) == null) "Couldn't save on the server. Check the connection and try again." else null
    }

    suspend fun setCentreActiveOnBackend(centreId: String, active: Boolean): Boolean = withContext(Dispatchers.IO) {
        val id = centreId.toIntOrNull() ?: return@withContext false
        callingApiPatch("/centres/$id/${if (active) "activate" else "deactivate"}") != null
    }

    // Archive page: people removed/archived/marked invalid, newest first, each with the date they
    // left and why. null = couldn't load.
    data class ArchiveEntry(
        val id: Int,
        val name: String,
        val mobile: String,
        val address: String,
        val status: String,      // removed | archived | invalid
        val reason: String,
        val removedAt: String,   // ISO instant
        val lastResponse: String,
        val lastCallDate: String
    )

    suspend fun fetchArchive(category: String, search: String = "", status: String = "all"): List<ArchiveEntry>? = withContext(Dispatchers.IO) {
        val cat = if (category.equals("Congregation", ignoreCase = true)) "congregation" else "youth"
        val q = StringBuilder("/archive?category=$cat&status=$status")
        if (search.isNotBlank()) q.append("&search=").append(java.net.URLEncoder.encode(search.trim(), "UTF-8"))
        val json = callingApiGet(q.toString()) ?: return@withContext null
        try {
            val items = JSONObject(json).optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
            (0 until items.length()).map { i ->
                val o = items.getJSONObject(i)
                ArchiveEntry(
                    id = o.optInt("id"),
                    name = o.optString("name"),
                    mobile = o.optString("mobile"),
                    address = o.optString("address"),
                    status = o.optString("status", "archived"),
                    reason = o.optString("reason"),
                    removedAt = o.optString("removed_at"),
                    lastResponse = o.optString("last_response"),
                    lastCallDate = o.optString("last_call_date")
                )
            }
        } catch (e: Exception) { null }
    }

    // What the calling server says each of this user's centres allows. The admin's Youth /
    // Congregation switch used to be guessed from which batches exist, so a centre whose only
    // batches were congregation showed no Youth option at all.
    private suspend fun fetchMyCentreCategories(): Map<String, Pair<Boolean, Boolean>> {
        val json = callingApiGet("/me/centres") ?: return emptyMap()
        return try {
            val arr = JSONObject(json).optJSONArray("data") ?: JSONArray()
            val out = mutableMapOf<String, Pair<Boolean, Boolean>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val flags = o.optBoolean("enabledYouth", true) to o.optBoolean("enabledCongregation", true)
                out["id:" + o.optInt("centreId")] = flags
                o.optString("centreCode").takeIf { it.isNotBlank() }?.let { out["code:" + it.lowercase()] = flags }
            }
            out
        } catch (e: Exception) { emptyMap() }
    }

    // Check approval status for pending user — polls Node.js via stored JWT attempt
    suspend fun checkApprovalStatus(): String = withContext(Dispatchers.IO) {
        val username = currentUser?.username ?: return@withContext "pending"
        val request = Request.Builder()
            .url("$backendUrl/api/auth/status/check?username=${username}")
            .get().build()
        try {
            val result = client.newCall(request).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            } ?: return@withContext "pending"
            JSONObject(result).optString("status", "pending")
        } catch (e: Exception) { "pending" }
    }

    // Submit a new account request via Node.js — called by RegisterRequestDialog
    suspend fun submitRegisterRequest(name: String, username: String, password: String, centreId: String, role: String, aeAccess: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val requestedRole = when (role.uppercase()) {
            "ADMIN" -> "ADMIN"
            else -> "CALLER"
        }
        val body = JSONObject().apply {
            put("name", name)
            put("identifier", username)
            put("password", password)
            put("requested_role", requestedRole)
            put("centre_id", centreId.toIntOrNull() ?: centreId)
            put("ae_access", aeAccess)
        }.toString()

        // Register is public — no auth header needed
        val reqBody = body.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$callingBackendUrl/auth/register-request")
            .post(reqBody)
            .build()
        val result = try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) { null } ?: return@withContext false
        try {
            val obj = JSONObject(result)
            obj.optBoolean("success", false) || obj.optJSONObject("data")?.has("id") == true
        } catch (e: Exception) { false }
    }

    // Fetch caller's assigned queue from Node.js — called by CallingQueueScreen
    suspend fun fetchCallerQueue(callerId: String): List<Registration> = withContext(Dispatchers.IO) {
        val result = callingApiGet("/calling-list?category=$selectedCategory")
            ?: return@withContext emptyList()
        try {
            val obj = JSONObject(result)
            val arr = obj.optJSONObject("data")?.optJSONArray("items")
                ?: obj.optJSONArray("data")
                ?: JSONArray(result)
            (0 until arr.length()).map { i ->
                val item = arr.getJSONObject(i)
                Registration(
                    id = item.optInt("id", i).toString(),
                    name = item.optString("name"),
                    mobile = item.optString("mobile"),
                    address = item.optString("address", ""),
                    occupation = item.optString("occupation", ""),
                    remark = item.optString("remark", ""),
                    centreId = selectedCentreId,
                    category = selectedCategory,
                    callerAssignedId = callerId,
                    batchIds = listOfNotNull(item.optString("batchId", "").ifEmpty { null }),
                    context = parsePersonContext(item.optJSONObject("context"))
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    // Fetch followups from Node.js — called by FollowUpScreen
    // /followup-list returns raw Prisma Followup rows (id/registrationId/status/isLocked/
    // lockedReason/createdAt) with a nested `registration` object (name/mobile/etc — camelCase,
    // no global snake_case transform). Read both the Followup's own fields and its nested
    // registration so the app shows real names while also surfacing lock state.
    private fun parseIsoToMillis(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
        for (p in patterns) {
            try {
                val fmt = SimpleDateFormat(p, Locale.US)
                fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                return fmt.parse(iso)?.time ?: 0L
            } catch (e: Exception) { /* try next pattern */ }
        }
        return 0L
    }

    suspend fun fetchFollowups(): List<Registration> = withContext(Dispatchers.IO) {
        // Backend requires category (nativeEnum youth|congregation) — omitting it returns 422 and
        // the follow-up list silently comes back empty. Send the current category, lowercased.
        // Server reachable → parse live + refresh cache. Server down → replay the cached list so the
        // Follow-ups tab keeps working offline (same pattern as the session-call queue cache).
        val live = callingApiGet("/followup-list?category=${selectedCategory.lowercase()}")
        val raw = if (live != null) { saveFollowupsCache(live); live } else loadFollowupsCacheRaw()
        val server = if (raw == null) emptyList() else parseFollowups(raw)
        // Merge in locally-marked positive responses that haven't synced yet, so a "will attend"
        // logged offline appears in Follow-ups immediately. Dedupe by phone (last 10 digits): if the
        // server already lists this person, its richer row wins and the optimistic one is dropped.
        val serverPhones = server.map { lastTenDigits(it.mobile) }.filter { it.isNotBlank() }.toSet()
        val locals = localOptimisticFollowups().filter { lastTenDigits(it.mobile) !in serverPhones }
        server + locals
    }

    private fun parseFollowups(result: String): List<Registration> = try {
        val obj = JSONObject(result)
        val arr = obj.optJSONObject("data")?.optJSONArray("items")
            ?: obj.optJSONArray("data")
            ?: JSONArray(result)
        (0 until arr.length()).map { i ->
            val item = arr.getJSONObject(i)
            val reg = item.optJSONObject("registration")
            Registration(
                id = (reg?.optInt("id") ?: item.optInt("registrationId", item.optInt("id", i))).toString(),
                name = reg?.optString("name") ?: item.optString("name"),
                mobile = reg?.optString("mobile") ?: item.optString("mobile"),
                address = reg?.optString("address", "") ?: item.optString("address", ""),
                occupation = reg?.optString("occupation", "") ?: item.optString("occupation", ""),
                // The answer that created this follow-up. A follow-up has no `remark` field, so the
                // old default put the literal words "Needs followup" under EVERY name — identical
                // on every card, which reads as a screen stuck on stale data. Fall back to the
                // kind, never to a sentence that pretends to be a logged reason.
                remark = item.optString("last_outcome", "").ifBlank {
                    if (item.optString("kind") == "call_back_later") "Asked to call back" else "Said they will attend"
                },
                centreId = selectedCentreId,
                category = selectedCategory,
                batchIds = listOfNotNull(item.optString("batchId", "").ifEmpty { null }),
                isLocked = item.optBoolean("isLocked", false),
                lockedReason = item.optString("lockedReason", "").ifEmpty { null },
                createdAtMillis = parseIsoToMillis(item.optString("createdAt")),
                sessionName = item.optString("session_name", "").ifEmpty { null },
                followupKind = item.optString("kind", "will_attend").ifEmpty { "will_attend" },
                // snake_case here, unlike the queue: /followup-list spreads raw Prisma rows and
                // adds its derived fields in snake_case (session_name, last_outcome) — matched.
                whatsappMessage = item.optString("whatsapp_message", "").ifBlank { null },
                // Same context blob the calling queue carries (responses + remarks, attendance,
                // batches) — lets the Follow-ups tab show the full calling card. Absent on an
                // older server, which parses to an empty context exactly as before.
                context = parsePersonContext(item.optJSONObject("context"))
            )
        }
    } catch (e: Exception) { emptyList() }

    // Positive outcomes marked but not yet synced → shown as follow-ups optimistically. Positive now
    // means "will attend" OR "asked to call back", matching the backend; each keeps its own kind so
    // the optimistic card lands under the same heading the server would file it under. Once the write
    // syncs, the backend creates the real follow-up and clears the override, so these disappear
    // naturally (and are deduped against the server list by phone in the meantime).
    fun localOptimisticFollowups(): List<Registration> =
        pendingCallOverrides.values.filter { it.positive && it.mobile.isNotBlank() }.map { o ->
            Registration(
                id = "local_fu_${lastTenDigits(o.mobile)}",
                name = o.name?.ifBlank { null } ?: o.mobile,
                mobile = o.mobile,
                address = "", occupation = "",
                remark = if (o.followupKind == "call_back_later") "Asked to call back — pending sync"
                         else "Will attend — pending sync",
                centreId = selectedFlaskCentreId,
                category = selectedCategory,
                createdAtMillis = o.ts,
                sessionName = o.sessionName?.ifBlank { null },
                followupKind = o.followupKind
            )
        }

    // ── "My Attendance" by-calling scoreboard (batch → session → people) ──────
    private fun itemsArray(result: String): JSONArray {
        val obj = JSONObject(result)
        return obj.optJSONObject("data")?.optJSONArray("items") ?: obj.optJSONArray("data") ?: JSONArray()
    }

    suspend fun fetchCallingBatchesMA(): List<CallingBatchStat> = withContext(Dispatchers.IO) {
        val result = callingApiGet("/my-attendance/calling/batches?category=${selectedCategory.lowercase()}")
            ?: return@withContext emptyList()
        try {
            val arr = itemsArray(result)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CallingBatchStat(
                    batchId = o.optInt("batch_id"),
                    batchName = o.optString("batch_name", "Batch"),
                    calledCount = o.optInt("called_count", 0),
                    attendedCount = o.optInt("attended_count", 0)
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    suspend fun fetchCallingSessionsMA(batchId: Int): List<CallingSessionStat> = withContext(Dispatchers.IO) {
        val result = callingApiGet("/my-attendance/calling/sessions?batch_id=$batchId")
            ?: return@withContext emptyList()
        try {
            val arr = itemsArray(result)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CallingSessionStat(
                    campaignId = o.optInt("campaign_id"),
                    aeSessionId = o.optString("ae_session_id", ""),
                    scheduledAtMillis = parseIsoToMillis(o.optString("scheduled_at")),
                    batchName = o.optString("batch_name", "Batch"),
                    calledCount = o.optInt("called_count", 0),
                    attendedCount = o.optInt("attended_count", 0)
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    suspend fun fetchCallingPeopleMA(campaignId: Int): List<CalledPerson> = withContext(Dispatchers.IO) {
        val result = callingApiGet("/my-attendance/calling/attended?campaign_id=$campaignId")
            ?: return@withContext emptyList()
        try {
            val arr = itemsArray(result)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CalledPerson(
                    name = o.optString("name", o.optString("mobile", "Unknown")),
                    mobile = o.optString("mobile", ""),
                    came = o.optBoolean("came", false),
                    outcomeLabel = o.optString("outcome_label", "").ifEmpty { "—" },
                    calledAtMillis = parseIsoToMillis(o.optString("called_at"))
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    // ── Centre-admin calling pool (SRKB calling backend) ──────────────────────
    // Named, deduplicated registrations for the Pool Assignment screen. This REPLACES the
    // Flask /api/show_attendance feed, which returned raw attendance rows (mostly nameless →
    // "Unknown") and inflated counts. The calling backend returns curated, named registrations
    // with their current assignment so the admin sees correct names + who is already assigned.
    //   poolType: "new" (registration pool) | "followup" (remaining follow-ups)
    //   assignedStatus: "assigned" | "unassigned" | "all"  (only applies to the new pool)
    // Returns null when the REQUEST failed (unreachable, non-2xx, unparseable) and an empty list
    // only when the backend genuinely has nobody. Collapsing those two into emptyList() meant a
    // dropped connection, an expired token and "no such people" all rendered as the same blank
    // table, so a transport failure read to the admin as data loss. The caller must tell them apart.
    suspend fun fetchCallingPool(
        poolType: String = "new",
        assignedStatus: String = "all"
    ): List<Registration>? = withContext(Dispatchers.IO) {
        val cat = selectedCategory.lowercase()
        val path = if (poolType == "followup")
            "/pool/remaining-followups?category=$cat"
        else
            "/pool/new-registrations?category=$cat&assigned_status=$assignedStatus"
        val result = callingApiGet(path) ?: return@withContext null
        try {
            val arr = JSONObject(result).optJSONObject("data")?.optJSONArray("items")
                ?: return@withContext null
            (0 until arr.length()).map { i ->
                val item = arr.getJSONObject(i)
                // Backend emits raw Prisma camelCase. currentAssignedCallerId may be null.
                val caller = if (item.isNull("currentAssignedCallerId")) null
                    else item.optInt("currentAssignedCallerId").takeIf { it > 0 }?.toString()
                Registration(
                    id = item.optInt("id", i).toString(),
                    name = item.optString("name", "").ifBlank { "(name pending)" },
                    mobile = item.optString("mobile", ""),
                    address = item.optString("address", ""),
                    occupation = item.optString("occupation", ""),
                    remark = if (item.isNull("latest_action")) "" else item.optString("latest_action", ""),
                    centreId = selectedCentreId,
                    category = selectedCategory,
                    callerAssignedId = caller,
                    // Registration date — the backend returns the full Prisma row, so createdAt is present.
                    createdAtMillis = parseIsoToMillis(item.optString("createdAt"))
                )
            }
        } catch (e: Exception) { null }
    }

    // "New Registered" tab — people registered in the centre + selected category whose registration
    // date falls in [dateFrom, dateTo] (YYYY-MM-DD). Each row carries a `regTypes` list
    // (new / assigned / in_batch / removed_archived) so the tab can multi-select which types to show.
    // Null means the request failed; empty means nobody registered in that window. See fetchCallingPool.
    suspend fun fetchRegisteredByDate(dateFrom: String, dateTo: String): List<Registration>? = withContext(Dispatchers.IO) {
        val cat = selectedCategory.lowercase()
        val path = "/pool/registered-by-date?category=$cat&date_from=$dateFrom&date_to=$dateTo"
        val result = callingApiGet(path) ?: return@withContext null
        try {
            val arr = JSONObject(result).optJSONObject("data")?.optJSONArray("items")
                ?: return@withContext null
            (0 until arr.length()).map { i ->
                val item = arr.getJSONObject(i)
                val caller = if (item.isNull("currentAssignedCallerId")) null
                    else item.optInt("currentAssignedCallerId").takeIf { it > 0 }?.toString()
                val typesArr = item.optJSONArray("types")
                val types = if (typesArr == null) emptyList()
                    else (0 until typesArr.length()).map { typesArr.getString(it) }
                Registration(
                    id = item.optInt("id", i).toString(),
                    name = item.optString("name", "").ifBlank { "(name pending)" },
                    mobile = item.optString("mobile", ""),
                    address = item.optString("address", ""),
                    occupation = item.optString("occupation", ""),
                    remark = "",
                    centreId = selectedCentreId,
                    category = selectedCategory,
                    callerAssignedId = caller,
                    createdAtMillis = parseIsoToMillis(item.optString("createdAt")),
                    regTypes = types
                )
            }
        } catch (e: Exception) { null }
    }

    // Persist a calling assignment for a set of registrations → one caller via /assign-calling.
    // Returns the number assigned (0 on failure). poolType selects the backend pool semantics.
    suspend fun assignCallingToCaller(
        registrationIds: List<String>,
        callerId: String,
        activeDays: Int = 30,
        poolType: String = "new"
    ): Int = withContext(Dispatchers.IO) {
        val ids = registrationIds.mapNotNull { it.toIntOrNull() }
        val callerInt = callerId.toIntOrNull()
        if (ids.isEmpty() || callerInt == null) return@withContext 0
        val body = JSONObject().apply {
            put("registration_ids", JSONArray(ids))
            put("caller_id", callerInt)
            put("active_days", activeDays)
            put("pool_type", if (poolType == "followup") "remaining_followup_pool" else "new_registration_pool")
        }.toString()
        val result = callingApiPost("/assign-calling", body) ?: return@withContext 0
        try {
            if (JSONObject(result).optBoolean("success", false)) ids.size else 0
        } catch (e: Exception) { 0 }
    }

    // ── Batch-by-membership (SRKB calling backend) ────────────────────────────
    // Batches with ACCURATE member_count (unique AttendEase attendees) — replaces the Flask
    // /api/batches feed whose member counts were unreliable. Also excludes deleted/junk batches.
    suspend fun fetchCallingBatches(): List<Batch> = withContext(Dispatchers.IO) {
        val cat = selectedCategory.lowercase()
        val result = callingApiGet("/batches?category=$cat") ?: return@withContext emptyList()
        try {
            val arr = JSONObject(result).optJSONObject("data")?.optJSONArray("items") ?: return@withContext emptyList()
            (0 until arr.length()).map { i ->
                val b = arr.getJSONObject(i)
                Batch(
                    id = b.optInt("id", i).toString(),
                    name = b.optString("name", "Batch ${i + 1}"),
                    centreId = selectedCentreId,
                    category = selectedCategory,
                    isDefault = b.optBoolean("isDefault", false),
                    status = b.optString("status", "active"),
                    memberCount = b.optInt("member_count", 0)
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    // Named members of one batch (with attendance count) from /pool/batch-members.
    suspend fun fetchNamedBatchMembers(batchId: String): List<NamedMember> = withContext(Dispatchers.IO) {
        val cat = selectedCategory.lowercase()
        val result = callingApiGet("/pool/batch-members?batch_id=$batchId&category=$cat") ?: return@withContext emptyList()
        try {
            val arr = JSONObject(result).optJSONObject("data")?.optJSONArray("items") ?: return@withContext emptyList()
            (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                NamedMember(
                    name = m.optString("name", "").ifBlank { m.optString("mobile", "") },
                    mobile = m.optString("mobile", ""),
                    attendanceCount = m.optInt("total_attendance_count", 0),
                    lastAttended = if (m.isNull("last_attended_date")) null else m.optString("last_attended_date", "").ifEmpty { null },
                    isRegistered = m.optJSONObject("registration") != null
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    // All sessions for this centre/category (calling + direct-attendance), for the Pool Assignment
    // lower "Filter: session" dropdown. Backend derives `kind` per session; list is newest-first.
    suspend fun fetchCentreSessions(): List<CentreSession> = withContext(Dispatchers.IO) {
        val cat = selectedCategory.lowercase()
        val result = callingApiGet("/pool/sessions?category=$cat") ?: return@withContext emptyList()
        try {
            val arr = JSONObject(result).optJSONObject("data")?.optJSONArray("items") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val sid = o.optString("session_id", "")
                if (sid.isBlank()) return@mapNotNull null
                CentreSession(
                    aeSessionId = sid,
                    displayName = o.optString("display_name", "").ifBlank { sid },
                    kind = o.optString("kind", "direct_attendance"),
                    batchId = if (o.isNull("batch_id")) null else o.optInt("batch_id").toString(),
                    date = if (o.isNull("date")) null else o.optString("date", "").ifEmpty { null }
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    // Who the lower "Filter: session" dropdown should show, for one session.
    // (Replaces fetchSessionAttendeeMobiles, which could only ever answer "who attended".)
    //
    //   SESSION_PEOPLE_ATTENDED  — turned up (an attendance record exists)
    //   SESSION_PEOPLE_RESPONDED — said something POSITIVE when called for this session, whether or
    //                              not they turned up. Sentiment is decided by the backend from the
    //                              response's dropdown_options.sentiment, never guessed from the
    //                              label, so a centre re-classifying a response changes this list
    //                              with no app change.
    //   SESSION_PEOPLE_BOTH      — the union of the two.
    //
    // Returns null ONLY when the request itself failed. An empty SET means "this session genuinely
    // has nobody" and must stay distinct from null, because the caller treats null as "no filter" —
    // conflating them would silently show the whole pool instead of an empty list.
    //
    // Returns the whole row, not just the mobile. The pool table is /pool/new-registrations, which
    // holds only stage=registered people, so a session filter used to be "session ∩ new pool" and
    // silently hid everyone already in follow-up. 2026-09-12 CTRA BG: 13 attended, 8 of them
    // followup_active, the pool page showed 5. The screen now adds these rows back itself.
    //
    // excludeCampaignId: the calling session being assigned into. Being in THAT session is not
    // "busy elsewhere", so its own people are not flagged.
    suspend fun fetchSessionPeople(
        aeSessionId: String,
        include: String,
        excludeCampaignId: String? = null,
    ): List<SessionPerson>? = withContext(Dispatchers.IO) {
        val cat = selectedCategory.lowercase()
        val encoded = java.net.URLEncoder.encode(aeSessionId, "UTF-8")
        val exclude = excludeCampaignId?.toIntOrNull()?.let { "&exclude_campaign_id=$it" } ?: ""
        val result = callingApiGet("/pool/batch-members?session_id=$encoded&category=$cat&include=$include$exclude")
            ?: return@withContext null
        parseSessionPeople(result)
    }

    // People from several sessions at once, filtered by the responses they gave and whether they
    // attended — see PoolFilter. ONE request for all the chosen sessions: fetching them one after
    // another was what made the Pool page's list feel slow with several sessions ticked.
    // Null only when the request failed, exactly like fetchSessionPeople.
    suspend fun fetchSessionPeopleFiltered(
        aeSessionIds: Collection<String>,
        filter: PoolFilter,
        excludeCampaignId: String? = null,
    ): List<SessionPerson>? = withContext(Dispatchers.IO) {
        if (aeSessionIds.isEmpty()) return@withContext emptyList()
        val q = buildString {
            append("/pool/session-people?category=").append(selectedCategory.lowercase())
            append(sessionIdsQuery(aeSessionIds))
            if (filter.sentiments.isNotEmpty()) append("&sentiments=").append(filter.sentiments.sorted().joinToString(","))
            if (filter.responseIds.isNotEmpty()) append("&response_ids=").append(filter.responseIds.sorted().joinToString(","))
            if (filter.neverCalled) append("&never_called=1")
            append("&attendance=").append(filter.attendance)
            excludeCampaignId?.toIntOrNull()?.let { append("&exclude_campaign_id=").append(it) }
        }
        val result = callingApiGet(q) ?: return@withContext null
        parseSessionPeople(result)
    }

    // The responses given in these sessions, grouped by meaning, with how many people gave each —
    // the contents of the Pool filter sheet. Null when the request failed.
    suspend fun fetchSessionResponses(aeSessionIds: Collection<String>): SessionResponses? = withContext(Dispatchers.IO) {
        if (aeSessionIds.isEmpty()) return@withContext SessionResponses(emptyList(), 0, anyCalling = false)
        val cat = selectedCategory.lowercase()
        val result = callingApiGet("/pool/session-responses?category=$cat${sessionIdsQuery(aeSessionIds)}") ?: return@withContext null
        try {
            val data = JSONObject(result).optJSONObject("data") ?: return@withContext null
            val arr = data.optJSONArray("responses") ?: JSONArray()
            val sessions = data.optJSONArray("sessions") ?: JSONArray()
            SessionResponses(
                responses = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val idArr = o.optJSONArray("option_ids") ?: JSONArray()
                    SessionResponseOption(
                        label = o.optString("label", ""),
                        sentiment = if (o.isNull("sentiment")) null else o.optString("sentiment", "").ifBlank { null },
                        optionIds = (0 until idArr.length()).map { idArr.getInt(it) },
                        count = o.optInt("count", 0),
                    )
                },
                neverCalledCount = data.optInt("never_called_count", 0),
                anyCalling = (0 until sessions.length()).any { sessions.getJSONObject(it).optBoolean("has_calling", false) },
            )
        } catch (e: Exception) { null }
    }

    // One session_ids= per session, each encoded on its own. Session ids are admin-typed and may
    // contain a comma, so a joined list would split one session into two that match nobody.
    private fun sessionIdsQuery(aeSessionIds: Collection<String>): String =
        aeSessionIds.joinToString("") { "&session_ids=" + java.net.URLEncoder.encode(it, "UTF-8") }

    private fun parseSessionPeople(result: String): List<SessionPerson>? {
        return try {
            val arr = JSONObject(result).optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val item = arr.getJSONObject(i)
                // Digit-only, matching every other mobile comparison on the pool screen. Verified
                // against live data: both AE attendance and registrations store bare 10-digit
                // numbers at these centres, so no country-code stripping is needed.
                val digits = item.optString("mobile", "").filter(Char::isDigit).ifBlank { null }
                    ?: return@mapNotNull null
                val reg = item.optJSONObject("registration")
                // Absent on a server from before 2026-09-24: treated as free, and the server's own
                // assign guard still refuses anyone busy.
                val busy = item.optJSONObject("busy")?.let { b ->
                    PersonBusy(
                        kind = b.optString("kind", ""),
                        label = b.optString("label", "Busy elsewhere").ifBlank { "Busy elsewhere" },
                        caller = if (b.isNull("caller")) null else b.optString("caller", "").ifBlank { null },
                    )
                }
                SessionPerson(
                    busy = busy,
                    mobile = digits,
                    stage = reg?.optString("current_stage")?.ifBlank { null },
                    row = Registration(
                        // A person with no registration still needs a stable, unique row id.
                        id = reg?.optInt("id")?.takeIf { it > 0 }?.toString() ?: "m:$digits",
                        name = item.optString("name", "").ifBlank { digits },
                        mobile = digits,
                        address = if (item.isNull("address")) "" else item.optString("address", ""),
                        occupation = if (item.isNull("occupation")) "" else item.optString("occupation", ""),
                        remark = "",
                        centreId = selectedCentreId,
                        category = selectedCategory,
                    ),
                )
            }
        } catch (e: Exception) { null }
    }

    const val SESSION_PEOPLE_ATTENDED  = "attended"
    const val SESSION_PEOPLE_RESPONDED = "responded"
    // Kept so an allotment saved by an older build still renders its heading. No longer offered as
    // a chip; the server still accepts it for phones that have not taken the update.
    const val SESSION_PEOPLE_BOTH      = "both"
    // "Who never got their chance?" - latest response was couldn't-reach, OR allotted to a caller
    // and never rung at all. The second half is invisible to any sentiment filter, because a person
    // who was never called has no response to have a sentiment.
    const val SESSION_PEOPLE_NEED_CHECK = "need_check"

    // Persist a batch disable to the calling backend.
    suspend fun disableBatchOnBackend(batchId: String): Boolean = withContext(Dispatchers.IO) {
        val result = callingApiPatch("/batches/$batchId/disable") ?: return@withContext false
        try { JSONObject(result).optBoolean("success", false) } catch (e: Exception) { false }
    }

    // Hard-delete a batch via the Flask (AttendEase) system, which OWNS batches. This is the real
    // delete: it removes the Batch row + its attendance + session mappings + Excel sheets and
    // propagates the deletion to the calling-backend mirror (unlike disableBatchOnBackend, which only
    // archives). Requires host role on Flask. Returns (ok, message).
    suspend fun deleteBatchOnFlask(batchId: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val result = apiPost("/api/batches/$batchId/delete", "{}")
            ?: return@withContext Pair(false, "Delete failed — server refused (host permission required)")
        try {
            val o = JSONObject(result)
            val ok = o.optBoolean("ok", false)
            Pair(ok, o.optString("message", if (ok) "Batch deleted" else "Delete failed"))
        } catch (e: Exception) { Pair(false, "Delete failed: ${e.message}") }
    }

    // Create admin account in AE's Flask DB (so HOST ADMIN MANAGEMENT shows it)
    private suspend fun createManagedAccountApi(name: String, username: String, password: String, centreCode: String): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("name", name)
            put("username", username)
            put("password", password)
            put("centre_id", centreCode)
        }.toString()
        val result = apiPost("/api/auth/create-managed-account", body) ?: return@withContext false
        try { JSONObject(result).optBoolean("ok", false) } catch (e: Exception) { false }
    }

    // SUPER_ADMIN creates centre admin: register-request then immediately approve.
    // centreId = SRKB numeric ID string (e.g. "2"); centreCode = AE centre_id (e.g. "centre_a_Base").
    // When centreCode is provided, also mirrors to AE so HOST ADMIN MANAGEMENT stays in sync.
    suspend fun createAdminUser(name: String, username: String, password: String, centreId: String, centreCode: String = ""): Boolean = withContext(Dispatchers.IO) {
        val reqBody = JSONObject().apply {
            put("name", name)
            put("identifier", username)
            put("password", password)
            put("requested_role", "ADMIN")
            put("centre_id", centreId.toIntOrNull() ?: centreId)
        }.toString()

        val regResult = callingApiPost("/auth/register-request", reqBody) ?: return@withContext false
        val srkbOk = try {
            val obj = JSONObject(regResult)
            val userId = obj.optJSONObject("data")?.optInt("id", 0) ?: 0
            if (userId <= 0) return@withContext false
            val approveResult = callingApiPatch("/users/$userId/approve") ?: return@withContext false
            JSONObject(approveResult).optBoolean("success", false)
        } catch (e: Exception) { return@withContext false }

        if (srkbOk && centreCode.isNotEmpty()) {
            createManagedAccountApi(name, username, password, centreCode)
        }
        srkbOk
    }

    fun approveRequest(userId: String) {
        users = users.map {
            if (it.id == userId) it.copy(status = "active") else it
        }
        CoroutineScope(Dispatchers.IO).launch { approveUserApi(userId) }
    }

    fun rejectRequest(userId: String) {
        users = users.toMutableList().apply { removeIf { it.id == userId } }
        CoroutineScope(Dispatchers.IO).launch { rejectUserApi(userId) }
    }

    /**
     * Add someone to a batch roster.
     *
     * [markPresent] additionally records them present for [sessionId] — the "Register & mark
     * present" action on the Attendance tab, for a walk-in whose number isn't on the roster yet.
     * It is OFF by default and the Registration tab never sets it: registering is used outside
     * class hours too, where an attendance row would be a lie. The server makes the same
     * distinction — /api/register_student only marks when mark_present is explicitly sent, and
     * refuses to mark at all if no session is genuinely selected.
     */
    fun addRegistration(
        name: String, mobile: String, address: String, occupation: String, remark: String,
        explicitBatchId: String? = null,
        markPresent: Boolean = false, sessionId: String = "", date: String = "",
    ) {
        val newId = "r_${System.currentTimeMillis()}"
        val finalBatchIds = mutableListOf<String>()

        if (explicitBatchId != null) {
            finalBatchIds.add(explicitBatchId)
            auditLogs = auditLogs + AuditLog("al_${System.currentTimeMillis()}", newId, "Explicitly assigned to batch $explicitBatchId (R2 Rule)", currentUser?.name ?: "User", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
        } else {
            val defaultBatch = batches.find { it.centreId == selectedFlaskCentreId && it.category == selectedCategory && it.isDefault }
            if (defaultBatch != null) {
                finalBatchIds.add(defaultBatch.id)
                auditLogs = auditLogs + AuditLog("al_${System.currentTimeMillis()}", newId, "Auto-assigned default batch ${defaultBatch.name} (R1 rules)", "System Engine", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
            } else {
                auditLogs = auditLogs + AuditLog("al_${System.currentTimeMillis()}", newId, "No default batch existed, registration skipped default assignment (R2 Exception)", "System Engine", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
            }
        }

        val newReg = Registration(
            id = newId,
            name = name,
            mobile = mobile,
            address = address,
            occupation = occupation,
            remark = remark,
            centreId = selectedFlaskCentreId,
            category = selectedCategory,
            batchIds = finalBatchIds
        )

        // Resolve the batch ONCE (explicit pick, else the centre+category default) so the
        // offline payload never stores an empty batch_id — a blank batch replays as
        // "Select a batch first". Carry centre_id as a 7th field too, so an offline write
        // lands in the SELECTED centre, not the caller's Flask primary centre, when the
        // queue drains. Online and offline now send identical data.
        val resolvedBatchId = explicitBatchId ?: (batches.find { it.centreId == selectedFlaskCentreId && it.category == selectedCategory && it.isDefault }?.id ?: "")
        // Fields 8-10 carry the "register & mark present" intent so an offline walk-in replays as
        // BOTH a registration and an attendance. Appended rather than inserted: replayQueueItem
        // reads positionally with size guards, so older queued items keep working untouched.
        // Field 11 is the category as it was at CAPTURE time. Without it the server had to
        // infer one, and "no category" has always had to mean youth — so a congregation
        // walk-in registered from the Registration tab could only ever land in a youth
        // batch. That is not something the server can fix: the information was never in the
        // request. Replay reads this field rather than the live selectedCategory, which by
        // then may have been toggled to something else entirely.
        val payload = OfflinePayload.encodeRegisterStudent(
            name = name, mobile = mobile, address = address, occupation = occupation,
            remark = remark, batchId = resolvedBatchId, centreId = selectedFlaskCentreId,
            markPresent = markPresent, sessionId = sessionId, date = date,
            category = selectedCategory,
        )
        if (isOnline) {
            registrations = registrations + newReg
            CoroutineScope(Dispatchers.IO).launch {
                val json = JSONObject().apply {
                    put("name", name)
                    put("mobile", mobile)
                    put("address", address)
                    put("occupation", occupation)
                    put("remark", remark)
                    // Tell Flask which centre to write into. Without this the write
                    // resolves to the Flask session's centre (DEFAULT_CENTRE_ID for a
                    // Bearer-only call) instead of the centre the registerer selected,
                    // so a valid batch comes back "not found". mark_attendance already
                    // sends centre_id — registration was the one write that didn't.
                    put("centre_id", selectedFlaskCentreId)
                    put("batch_id", resolvedBatchId)
                    // ALWAYS sent, not just alongside a mark. This used to sit inside the
                    // markPresent block, so a plain registration reached the server with no
                    // category at all and could only be filed as youth.
                    put("category", selectedCategory)
                    // Identifies the device on anything the server has to hold rather than
                    // apply — a parked registration is worth nothing later if you cannot tell
                    // which phone it came from.
                    put("device_id", deviceId())
                    // Only sent when the Attendance tab asked for it. Absent = register only.
                    if (markPresent) {
                        put("mark_present", true)
                        put("session_id", sessionId)
                        put("date", date)
                    }
                }.toString()
                val ok = apiPost("/api/register_student", json) != null
                if (!ok) {
                    // Online but the write itself failed (server error, mid-flight drop, etc.) —
                    // don't silently lose it; park it in the same durable retry queue offline uses.
                    withContext(Dispatchers.Main) {
                        offlineQueue = offlineQueue + OfflineQueueItem(
                            id = "off_${System.currentTimeMillis()}", type = "register_student",
                            label = "Register student: $name", payload = payload
                        )
                        persistOfflineQueue()
                    }
                }
            }
        } else {
            val queueItem = OfflineQueueItem(
                id = "off_${System.currentTimeMillis()}",
                type = "register_student",
                label = "Register student: $name",
                payload = payload
            )
            offlineQueue = offlineQueue + queueItem
            persistOfflineQueue()
        }
    }

    /**
     * Replays every "pending" queued write and only removes an item once its write is CONFIRMED
     * successful — a failed replay increments its attempt count and stays queued (retried on the
     * next sync) until MAX_QUEUE_ATTEMPTS, at which point it's parked as failed_needs_attention
     * instead of looping forever or silently vanishing. Safe to call repeatedly / concurrently
     * with itself (each item is only replayed once per call, off the snapshot taken at the start).
     */
    // Guards against two drains running at once. There are four trigger points (the
    // connectivity transition, the launch drain, the manual chip, and retryAll), so a user
    // tapping "sync" just as the watchdog flips online could replay every item twice.
    // Registration and attendance shrug that off (upsert / 409), but the calling backend
    // creates a CallAttempt row unconditionally, so a double replay duplicates call history.
    private val syncInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun syncOfflineQueue() {
        if (!isOnline) return
        if (offlineQueue.none { it.status == "pending" }) return
        CoroutineScope(Dispatchers.IO).launch { drainOfflineQueue() }
    }

    /**
     * The drain itself, as a suspend function so the background worker can AWAIT it (a worker that
     * launched and returned would report success before anything had uploaded). Returns how many
     * writes were confirmed. Guarded by [syncInFlight], which is shared with the foreground because
     * WorkManager runs in this same process.
     *
     * [background] = true is the worker: failures are not counted against an item's attempt budget
     * (see BackgroundSyncPolicy), it gives up after a few failures in a row, it checks [shouldStop]
     * between items, and it skips the parked-data upload — all to keep the background cost small.
     */
    suspend fun drainOfflineQueue(background: Boolean = false, shouldStop: () -> Boolean = { false }): Int {
        val pending = offlineQueue.filter { it.status == "pending" }
        if (pending.isEmpty()) return 0
        if (!syncInFlight.compareAndSet(false, true)) return 0
        var drained = 0
        try {
            run {
                var consecutiveFailures = 0
                for (item in pending) {
                    if (shouldStop()) break
                    // Send AND dequeue as one uninterruptible step. WorkManager cancels the worker
                    // when the network drops mid-drain (routine at a door); if that landed between
                    // the server committing a write and the item being removed, the item would be
                    // replayed later — and the calling backend adds a CallAttempt row every time, so
                    // a replayed call response duplicates call history. The only exit is
                    // shouldStop(), checked between items.
                    val success = withContext(NonCancellable) {
                        val ok = replayQueueItem(item)
                        withContext(Dispatchers.Main) {
                            offlineQueue = if (ok) {
                                offlineQueue.filter { it.id != item.id }
                            } else {
                                val now = System.currentTimeMillis()
                                offlineQueue.map {
                                    if (it.id == item.id)
                                        BackgroundSyncPolicy.afterFailedReplay(it, background, now, MAX_QUEUE_ATTEMPTS)
                                    else it
                                }
                            }
                            persistOfflineQueue()
                        }
                        ok
                    }
                    if (success) { drained++; consecutiveFailures = 0 } else consecutiveFailures++
                    if (background && BackgroundSyncPolicy.shouldAbortRun(consecutiveFailures)) break
                }
                // Something the operator recorded has just reached the server for the first time.
                // Nothing on screen knows that yet: the queue item that WAS the local evidence is
                // gone, and the visible lists were fetched before the write landed — so a mark made
                // offline is briefly in neither. That is the "it disappears, then comes back on the
                // next refresh" report. Ask every screen to re-read now, rather than leaving the
                // operator to go and find a refresh themselves.
                if (drained > 0) withContext(Dispatchers.Main) {
                    bumpDataRevision()
                    bumpStructureRevision()
                }
                // After draining, if anything is parked as failed, push the whole queue to
                // the server so an admin can recover the stuck data centrally (no adb) — and
                // then clear those rows from the phone, now that the server holds them. Only
                // fires when there's genuinely stuck data, so this isn't periodic spam.
                // Foreground only: the background job stays minimal and never creates parked items.
                if (!background && offlineQueue.any { it.status == "failed_needs_attention" }) {
                    runCatching { uploadAndClearParked("post_sync_failed") }
                }
            }
        } finally {
            syncInFlight.set(false)
        }
        return drained
    }

    /**
     * Background DOWNLOAD half. Both calls are the same ones the foreground already makes, and both
     * are cheap when nothing changed: the session/roster pull is gated on a ~70 byte checksum, and
     * the calling list is fetched only for someone working in caller mode. Results land in the
     * on-disk caches the screens read, so a screen opened later paints the fresh data at once.
     */
    suspend fun backgroundPull() {
        runCatching { syncServerCacheIfChanged() }
        val calling = currentMode == "caller" && currentUser?.permissions?.contains("calling_access") == true
        if (calling) runCatching { fetchSessionCallQueue() }
    }

    /**
     * Everything an attendance mark needs, captured at the moment the user taps save.
     *
     * All of this used to be re-derived at REPLAY time from whatever happened to be in
     * memory, which is why an offline mark could not survive a restart: `registrations` is
     * rebuilt from the network, so a cold start left the mobile blank and the server
     * rejected the row outright (it requires exactly ten digits). The same code invented
     * `session_id = "s1"` — written through verbatim, since the server does not validate it
     * — and re-stamped the date as "today", so a Friday mark synced on Monday was recorded
     * as Monday against a session that never existed. It also sent no centre at all, unlike
     * every other write path, so the row could land in the wrong centre.
     */
    private data class AttendanceMarkContext(
        val mobile: String,
        val sessionId: String,
        val date: String,
        val centreId: String,
    )

    private fun resolveAttendanceMarkContext(studentId: String): AttendanceMarkContext {
        val matchedReg = registrations.find { it.id == studentId }
        // The server is the authority on which session is live, so ask its mirrored state
        // first. The old fallback — `sessions.firstOrNull { it.batchId == ... }` — picks an
        // ARBITRARY session belonging to that batch, which is how a mark could attach to a
        // months-old session even on a perfect connection.
        val serverSession = serverActiveSession()
        val batchSession = sessions.firstOrNull {
            it.batchId == (matchedReg?.batchIds?.firstOrNull() ?: "")
        }
        return AttendanceMarkContext(
            mobile = matchedReg?.mobile?.takeIf { it.isNotBlank() }
                ?: OfflinePayload.mobileFromRegistrationId(studentId),
            sessionId = serverSession?.sessionId?.ifBlank { null } ?: batchSession?.id ?: "",
            date = serverSession?.date?.ifBlank { null } ?: batchSession?.date
                ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
            centreId = selectedFlaskCentreId,
        )
    }

    /** The one place a mark_attendance body is built — used by BOTH the live write and the
     *  replay, so the two arms cannot drift apart again. They already did once, and that
     *  divergence is the entire bug this replaces. */
    private fun buildMarkAttendanceJson(
        ctx: AttendanceMarkContext,
        outcome: String,
        note: String,
        category: String = selectedCategory,
        capturedAtMillis: Long = System.currentTimeMillis(),
    ): String = JSONObject().apply {
        put("mobile", ctx.mobile)
        // Still omitted when genuinely absent — inventing an id ("s1") created junk rows once
        // and must not come back. But the comment that used to sit here claimed the server
        // would then "resolve the centre's own active session, which is the right answer".
        // That is only true when ONE class is running. The server holds a single active-session
        // slot per centre, so with two classes it holds whichever device selected last, and an
        // omitted id meant the mark landed in the other class — silently, on 2026-08-03.
        //
        // The server no longer guesses in that situation: it parks the mark for the host to
        // assign rather than filing it against the wrong class. Sending the id whenever the
        // device has one is what keeps marks out of that queue in the first place.
        if (ctx.sessionId.isNotEmpty()) put("session_id", ctx.sessionId)
        if (ctx.date.isNotEmpty()) put("date", ctx.date)
        if (ctx.centreId.isNotEmpty()) put("centre_id", ctx.centreId)
        put("category", category.lowercase().ifBlank { "youth" })
        put("rounds", "1")
        put("remark", "$outcome. Note: $note")
        // Which install produced this mark. Several operators share one login, so without it
        // no row can be traced back to a device.
        put("device_id", deviceId())
        // WHEN THE OPERATOR TAPPED — not when this reaches the server. The server resolves
        // competing marks by this value, so a phone that was offline during class and syncs
        // hours later cannot overwrite a correction made in the meantime. The queue already
        // stamped this; it was simply never sent.
        put("client_marked_at", capturedAtMillis)
    }.toString()

    private fun markAttendancePayload(
        studentId: String, outcome: String, note: String, ctx: AttendanceMarkContext,
    ): String = OfflinePayload.encodeMarkAttendance(
        studentId = studentId, outcome = outcome, note = note, mobile = ctx.mobile,
        sessionId = ctx.sessionId, date = ctx.date, centreId = ctx.centreId,
        category = selectedCategory,
    )

    fun markAttendance(studentId: String, outcome: String, note: String) {
        val matchedReg = registrations.find { it.id == studentId }
        val sName = matchedReg?.name ?: "Unknown"
        val ctx = resolveAttendanceMarkContext(studentId)
        val payload = markAttendancePayload(studentId, outcome, note, ctx)
        fun enqueue() {
            offlineQueue = offlineQueue + OfflineQueueItem(
                id = "off_${System.currentTimeMillis()}",
                type = "mark_attendance",
                label = "Mark attendance: $sName",
                payload = payload
            )
            persistOfflineQueue()
        }
        if (!isOnline) {
            enqueue()
            return
        }
        val attempt = CallAttempt(
            id = "ca_${System.currentTimeMillis()}",
            registrationId = studentId,
            dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()),
            callerName = currentUser?.name ?: "Staff",
            outcome = outcome,
            notes = note
        )
        callAttempts = callAttempts + attempt
        CoroutineScope(Dispatchers.IO).launch {
            val ok = postMarkAttendanceIdempotent(buildMarkAttendanceJson(ctx, outcome, note))
            if (!ok) withContext(Dispatchers.Main) { enqueue() }
        }
    }

    suspend fun submitAttendance(
        mobile: String, name: String, address: String, occupation: String,
        sessionId: String, date: String, rounds: String, remark: String, batchId: String
    ): Boolean = withContext(Dispatchers.IO) {
        // WHEN the operator tapped, not when this reaches the server. Stamped once here and used
        // by both arms below, so a mark queued at a venue with no signal still resolves by the
        // moment it was taken after it finally syncs.
        val capturedAt = System.currentTimeMillis()
        // Durable-offline like addRegistration/markAttendance: this is the registerer's primary
        // Mark-Attendance / Add-Student screen, the one most likely to be used at a venue with no
        // signal. Field order MUST match the "submit_attendance" replay parser above. centre_id is
        // the 10th field so an offline attendance mark replays into the SELECTED centre.
        //
        // 11th = category, 12th = capture time. Appended, never reordered: the replay parser reads
        // by index and items queued by an older build are still sitting in people's phones, so a
        // new field can only ever go on the end behind a size guard.
        val payload = OfflinePayload.encodeSubmitAttendance(
            mobile = mobile, name = name, address = address, occupation = occupation,
            batchId = batchId, sessionId = sessionId, date = date, rounds = rounds,
            remark = remark, centreId = selectedFlaskCentreId, category = selectedCategory,
            capturedAtMillis = capturedAt,
        )
        suspend fun enqueue() = withContext(Dispatchers.Main) {
            offlineQueue = offlineQueue + OfflineQueueItem(
                id = "off_${System.currentTimeMillis()}", type = "submit_attendance",
                label = "Attendance: ${name.ifBlank { mobile }}", payload = payload
            )
            persistOfflineQueue()
        }
        if (!isOnline) {
            enqueue()
            // Accepted — durably queued and will auto-sync on reconnect. Same contract as the other
            // offline writes (return true so the UI confirms + clears the form instead of erroring).
            return@withContext true
        }
        val json = JSONObject().apply {
            put("mobile", mobile)
            put("name", name)
            put("session_id", sessionId)
            put("date", date)
            put("rounds", rounds.ifEmpty { "1" })
            put("remark", remark)
            put("address", address)
            put("occupation", occupation)
            put("batch_id", batchId)
            put("centre_id", selectedFlaskCentreId)
            // THE CATEGORY. Its absence is the whole reason 14 congregation attendees were filed
            // as youth on 2026-08-09: this body carried ten keys and category was not among them,
            // so the registerer choosing Congregation on screen changed nothing about what was
            // sent, and the server's `data.get('category') or 'youth'` defaulted every one of them.
            // The caller path has always sent it; this screen never did.
            put("category", selectedCategory)
            // Which install produced this mark. Several operators share one login, so without it
            // no row can be traced back to a device — every row from this screen had a null one.
            put("device_id", deviceId())
            put("client_marked_at", capturedAt)
        }.toString()
        // 2xx OR 409 "Already Marked" both mean the record is present server-side (mark_attendance
        // is idempotent) — either way the submit is done. Any other outcome (server error, mid-flight
        // drop) parks it in the durable queue for automatic retry. Reported as accepted so the UI
        // confirms + clears the form instead of erroring, matching the offline contract.
        val ok = postMarkAttendanceIdempotent(json)
        if (!ok) enqueue()
        return@withContext true
    }

    suspend fun createBatch(name: String): Batch? = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("name", name)
            put("centre_id", selectedFlaskCentreId)
        }.toString()
        val result = apiPost("/api/batches", body) ?: return@withContext null
        try {
            val obj = JSONObject(result)
            if (obj.has("error")) return@withContext null
            val newBatch = Batch(
                id = obj.optInt("id", 0).toString(),
                name = obj.optString("name", name),
                centreId = obj.optString("centre_id", selectedFlaskCentreId),
                category = obj.optString("category", selectedCategory),
                isDefault = obj.optBoolean("is_default", false)
            )
            withContext(Dispatchers.Main) { batches = batches + newBatch }
            newBatch
        } catch (e: Exception) { null }
    }

    suspend fun createSession(sessionName: String, batchId: String): Session? = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("session_name", sessionName)
            put("batch_id", batchId)
            put("centre_id", selectedFlaskCentreId)
        }.toString()
        val result = apiPost("/api/create_session", body) ?: return@withContext null
        try {
            val obj = JSONObject(result)
            if (obj.has("error")) return@withContext null
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
            val newSession = Session(
                id = obj.optString("session_id", sessionName),
                name = obj.optString("display_name", sessionName),
                batchId = batchId,
                date = today
            )
            // REPLACE by session_id, never blind-append. /api/create_session is idempotent —
            // creating the same name twice returns the SAME session_id rather than a second
            // session — so appending meant every extra tap on Create (or every retry of a
            // request that had actually succeeded) added another identical-looking row to the
            // picker. Four taps produced four "9 Aug 2026" entries that were all the one real
            // session. Keying on the id the server returned makes the list idempotent too.
            withContext(Dispatchers.Main) {
                sessions = sessions.filterNot { it.id == newSession.id } + newSession
            }
            newSession
        } catch (e: Exception) { null }
    }

    fun submitMoveToBatch(reqId: String, sourceB: String, targetB: String, reason: String) {
        val sName = registrations.find { it.id == reqId }?.name ?: "Unknown"
        batchMoveRequests = batchMoveRequests + BatchMoveRequest(
            id = "bm_${System.currentTimeMillis()}",
            registrationId = reqId,
            studentName = sName,
            sourceBatchId = sourceB,
            targetBatchId = targetB,
            reason = reason
        )
    }

    fun updateBatchMembershipsLocal(registrationId: String, newBatchIds: List<String>) {
        registrations = registrations.map {
            if (it.id == registrationId) {
                auditLogs = auditLogs + AuditLog(
                    id = "al_${System.currentTimeMillis()}",
                    registrationId = registrationId,
                    action = "Batch modified: ${it.batchIds.joinToString()} -> ${newBatchIds.joinToString()}",
                    actor = currentUser?.name ?: "Admin",
                    timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                )
                it.copy(batchIds = newBatchIds)
            } else {
                it
            }
        }
    }

    // Persist batch membership to SRKB backend, then update local state.
    suspend fun setRegistrationBatches(registrationId: String, batchIds: List<String>): Boolean = withContext(Dispatchers.IO) {
        val numericIds = batchIds.mapNotNull { it.toIntOrNull() }
        val body = """{"batch_ids":$numericIds}"""
        val result = callingApiPost("/registrations/$registrationId/batch-membership", body)
            ?: return@withContext false
        updateBatchMembershipsLocal(registrationId, batchIds)
        true
    }

    // ── Session Campaigns (planned session calling) ───────────────────────────

    suspend fun fetchPlannedSessions(batchId: String? = null): List<PlannedSession> = withContext(Dispatchers.IO) {
        val q = if (batchId != null) "?batch_id=$batchId" else ""
        val result = apiGet("/api/planned_sessions$q") ?: return@withContext emptyList()
        try {
            val arr = org.json.JSONArray(result)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                PlannedSession(
                    id = obj.optString("session_id"),
                    name = obj.optString("display_name"),
                    batchId = obj.optString("batch_id"),
                    centreId = obj.optString("centre_id"),
                    scheduledAt = obj.optString("scheduled_at")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    // The calling backend requires scheduled_at as ISO-8601 WITH offset (e.g. 2026-06-27T10:00:00+05:30).
    // The create dialog collects "YYYY-MM-DD HH:MM" (or just a date), so normalize before sending —
    // otherwise validation rejects every create and "nothing creates".
    // Was silently falling back to "now" whenever scheduledAt didn't match one of the 3 narrow
    // formats tried — so a bad/unexpected input never errored, it just created the session for the
    // current moment with no indication anything was wrong. Now a genuine parse failure returns
    // null and surfaces as a real error instead of a silent wrong-time schedule.
    private fun toIsoWithOffset(input: String): String? {
        val out = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
        for (p in listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd")) {
            try {
                val fmt = java.text.SimpleDateFormat(p, java.util.Locale.US)
                fmt.isLenient = false
                val parsed = fmt.parse(input.trim())
                if (parsed != null) return out.format(parsed)
            } catch (_: Exception) {}
        }
        return null
    }

    suspend fun createPlannedSession(sessionName: String, batchId: String, scheduledAt: String): PlannedSession? = withContext(Dispatchers.IO) {
        val isoScheduled = toIsoWithOffset(scheduledAt) ?: return@withContext null
        val aeBody = JSONObject().apply {
            put("session_name", sessionName)
            put("batch_id", batchId)
            put("centre_id", selectedFlaskCentreId)
            put("session_type", "planned")
            put("scheduled_at", isoScheduled)
        }.toString()
        val aeResult = apiPost("/api/create_session", aeBody) ?: return@withContext null
        try {
            val aeObj = JSONObject(aeResult)
            if (aeObj.has("error")) return@withContext null
            val sessionId = aeObj.optString("session_id")
            val displayName = aeObj.optString("display_name", sessionName)
            val srkbBody = JSONObject().apply {
                put("ae_session_id", sessionId)
                put("batch_id", batchId.toIntOrNull() ?: 0)
                put("scheduled_at", isoScheduled)
            }.toString()
            val campRes = callingApiPost("/session-campaigns", srkbBody) ?: return@withContext null
            val campObj = try { JSONObject(campRes) } catch (_: Exception) { null }
            if (campObj != null && (campObj.has("error") || campObj.optBoolean("success", true) == false)) {
                return@withContext null
            }
            PlannedSession(id = sessionId, name = displayName, batchId = batchId, centreId = selectedFlaskCentreId ?: "", scheduledAt = isoScheduled)
        } catch (e: Exception) { null }
    }

    suspend fun fetchSessionCampaigns(status: String = "all"): List<SessionCampaign> = withContext(Dispatchers.IO) {
        // Lowercase for the same reason fetchBatchMembers does it: the backend Category enum is
        // youth/congregation and "Youth" matches ZERO rows.
        //
        // Sending the category at all is the server half of the 2026-08-23 fix. A campaign has no
        // category column — it inherits its batch's — so this list used to return BOTH categories,
        // which let the previous category's calling session stay selected across a workspace
        // switch while the roster reloaded for the new one. Every row then failed to resolve and
        // the assign block claimed they "aren't in any batch yet".
        val cat = selectedCategory.lowercase()
        val result = callingApiGet("/session-campaigns?status=$status&category=$cat") ?: return@withContext emptyList()
        try {
            val root = JSONObject(result)
            val arr = root.optJSONObject("data")?.optJSONArray("items") ?: root.optJSONArray("items") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                // Backend emits raw Prisma camelCase (no snake_case transform).
                val batch = obj.optJSONObject("batch")
                val assignmentsArr = obj.optJSONArray("assignments")
                val assignMap = mutableMapOf<String, String>()
                val assignDetails = mutableListOf<SessionCallAssignmentDetail>()
                if (assignmentsArr != null) {
                    for (j in 0 until assignmentsArr.length()) {
                        val a = assignmentsArr.getJSONObject(j)
                        val batchMember = a.optJSONObject("batchMember")
                        val caller = a.optJSONObject("caller")
                        val bmId = batchMember?.optInt("id") ?: a.optInt("batchMemberId", 0)
                        val callerId = caller?.optInt("id") ?: 0
                        if (bmId > 0 && callerId > 0) assignMap[bmId.toString()] = callerId.toString()
                        assignDetails += SessionCallAssignmentDetail(
                            id = a.optInt("id").toString(),
                            batchMemberId = bmId.toString(),
                            batchMemberName = batchMember?.let { if (it.isNull("name")) null else it.optString("name", "").ifBlank { null } },
                            batchMemberMobile = batchMember?.optString("mobile") ?: "",
                            callerId = callerId.toString(),
                            callerName = caller?.optString("name") ?: "Caller #$callerId",
                            callStatus = a.optString("callStatus", "pending"),
                            // optString returns "" for JSON null, which would read as a real
                            // (blank) heading and split legacy rows off into their own group.
                            allotmentKey = a.optString("allotmentKey").ifBlank { null },
                            allotmentLabel = a.optString("allotmentLabel").ifBlank { null },
                            allotmentFilter = a.optString("allotmentFilter").ifBlank { null }
                        )
                    }
                }
                SessionCampaign(
                    id = obj.optInt("id").toString(),
                    aeSessionId = obj.optString("aeSessionId"),
                    batchId = obj.optInt("batchId").toString(),
                    batchName = batch?.optString("name") ?: "",
                    sessionName = obj.optString("sessionName", batch?.optString("name") ?: ""),
                    scheduledAt = obj.optString("scheduledAt"),
                    status = obj.optString("status"),
                    assignmentCount = assignmentsArr?.length() ?: 0,
                    assignments = assignMap,
                    assignmentDetails = assignDetails,
                    whatsappMessage = obj.optString("whatsappMessage", "")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    /**
     * @param allotments one entry per caller named in [assignments], recording which list the
     *        people were picked from so the admin screen can group and re-open it later. Omit for
     *        an allotment with no provenance — the server then leaves those columns untouched.
     */
    suspend fun assignSessionCallers(
        campaignId: String,
        assignments: List<Pair<String, String>>,
        allotments: List<AllotmentWrite> = emptyList(),
    ): Boolean = withContext(Dispatchers.IO) {
        val arr = org.json.JSONArray()
        assignments.forEach { (bmId, cId) ->
            arr.put(JSONObject().apply {
                put("batch_member_id", bmId.toIntOrNull() ?: 0)
                put("caller_id", cId.toIntOrNull() ?: 0)
            })
        }
        val body = JSONObject().apply {
            put("assignments", arr)
            if (allotments.isNotEmpty()) {
                put("allotments", org.json.JSONArray().apply {
                    allotments.forEach { al ->
                        put(JSONObject().apply {
                            put("caller_id", al.callerId.toIntOrNull() ?: 0)
                            put("key", al.key.take(80))
                            // Hard-clamped to stay inside the server's limits. Provenance travels
                            // in the SAME request as the assignment, so an over-long heading must
                            // never be able to cost the admin the allotment itself.
                            put("label", al.label.take(300))
                            if (al.filter.length <= 3500) put("filter", al.filter)
                            // Only sent when true: `replace` soft-removes the caller's other rows,
                            // so it must never ride along by accident.
                            if (al.replace) put("replace", true)
                        })
                    }
                })
            }
        }.toString()
        callingApiPost("/session-campaigns/$campaignId/assign", body) != null
    }

    // Same request as assignSessionCallers, but says WHY it failed. null = saved. Otherwise the
    // server's own message when it gave one — above all its refusal to pull in someone who is
    // already in a follow-up or waiting for a call in another session, which names each of them —
    // and a connection message only when there was no answer at all.
    suspend fun assignSessionCallersOrError(
        campaignId: String,
        assignments: List<Pair<String, String>>,
        allotments: List<AllotmentWrite> = emptyList(),
    ): String? = withContext(Dispatchers.IO) {
        lastCallingPostError = null
        if (assignSessionCallers(campaignId, assignments, allotments)) null
        else lastCallingPostError ?: "Assignment failed — check connection."
    }

    // Soft-remove (detach/archive, not delete) one assigned person from a campaign — instant, works
    // even while the campaign is still active and more people are still being assigned.
    suspend fun removeSessionCallAssignment(campaignId: String, assignmentId: String): Boolean =
        withContext(Dispatchers.IO) {
            callingApiDelete("/session-campaigns/$campaignId/assignments/$assignmentId") != null
        }

    // Note attached to a caller for this campaign — shown beside the session name in their My
    // Callings queue (see fetchSessionCallQueue's callerNote parsing above).
    // Admin: set or clear this calling session's WhatsApp message. Empty string CLEARS it — the
    // backend stores blank as NULL, so there is no separate delete call.
    suspend fun setCampaignWhatsappMessage(campaignId: String, message: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply { put("message", message) }.toString()
            callingApiPut("/session-campaigns/$campaignId/whatsapp-message", body) != null
        }

    suspend fun setSessionCallerNote(campaignId: String, callerId: String, note: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply { put("note", note) }.toString()
            callingApiPut("/session-campaigns/$campaignId/callers/$callerId/note", body) != null
        }

    // Caller "Today" progress numbers, refreshed by fetchSessionCallQueue.
    var sessionCallSummary by mutableStateOf(SessionCallSummary())

    suspend fun fetchSessionCallQueue(): List<SessionCallAssignment> = withContext(Dispatchers.IO) {
        // Server reachable → parse live and refresh the cache. Server down → replay the last cached
        // response so the caller keeps their full work list (names, context, summary) offline.
        val live = callingApiGet("/session-call-queue")
        val parsed = if (live != null) {
            saveQueueCache(live)
            parseSessionQueue(live)
        } else {
            val cached = loadQueueCacheRaw() ?: return@withContext emptyList()
            parseSessionQueue(cached)
        }
        // Only a live reply is evidence about what the server holds. A cache read is our OWN
        // last paint read back, so retiring an override on it would be the app confirming itself.
        applyOverridesToQueue(parsed, fromServer = live != null)
    }

    /**
     * Repaint a list the screen is ALREADY holding with the optimistic overrides — no network call.
     *
     * This is what lets a save show up instantly without a refetch. Before, the caller screen had to
     * re-run fetchSessionCallQueue() to see its own answer, because the overlay only happened inside
     * that fetch — which is precisely what put a GET on the wire at the same moment as the PATCH, and
     * made every single online save race its own write. The refetch now waits for the write to settle
     * (see updateSessionCallStatus); this keeps the paint instant in the meantime, online or off.
     *
     * `fromServer = false`: a list we are re-painting is not evidence about what the server holds, so
     * it must never retire an override.
     */
    suspend fun overlayPendingOverrides(
        list: List<SessionCallAssignment>,
    ): List<SessionCallAssignment> = applyOverridesToQueue(list, fromServer = false)

    // Overlay any un-synced offline outcomes onto the freshly-parsed queue so the row shows the marked
    // status (not "pending") and the "Today" strip counts it as done — identical to the online view.
    //
    // [fromServer] says whether [list] came from a live /session-call-queue reply. When it did, it is
    // also the ONLY moment we may retire an override: the server has now told us, in its own words,
    // what it holds for this row. Retiring it any earlier — on the write's own 2xx — raced every
    // refetch that was already in flight and blanked freshly-saved answers.
    private suspend fun applyOverridesToQueue(
        list: List<SessionCallAssignment>,
        fromServer: Boolean = false,
    ): List<SessionCallAssignment> {
        if (fromServer && pendingCallOverrides.isNotEmpty()) retireConfirmedOverrides(list)
        if (pendingCallOverrides.isEmpty()) return list
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val overridden = list.map { call ->
            val o = pendingCallOverrides[call.id] ?: return@map call
            val synthetic = CallResponse(date = nowStr, outcome = o.outcome, remark = o.remark, caller = o.callerName)
            // Same one-response-per-caller-per-day rule as patchQueueCacheEntry: an earlier answer by
            // this caller today is SUPERSEDED, not stacked. Without this, re-answering someone whose
            // first response had already synced showed both entries side by side.
            val kept = call.context.recentCalls.filterNot {
                it.caller == o.callerName && it.date.take(10) == nowStr.take(10)
            }
            call.copy(
                callStatus = o.derivedStatus,
                context = call.context.copy(recentCalls = listOf(synthetic) + kept)
            )
        }
        // Recompute the progress strip from the overlaid list (pending = not-yet-responded).
        // Deferred people are excluded from total/done — they are not today's work, so counting them
        // would leave a "left" figure the caller can never clear.
        val working = overridden.filter { !it.isScheduled }
        val total = working.size
        val done = working.count { it.callStatus != "pending" }
        withContext(Dispatchers.Main) {
            sessionCallSummary = SessionCallSummary(
                total = total, done = done, left = total - done,
                scheduled = overridden.count { it.isScheduled },
            )
        }
        return overridden
    }

    private suspend fun parseSessionQueue(result: String): List<SessionCallAssignment> {
        return try {
            val root = JSONObject(result)
            val data = root.optJSONObject("data")
            data?.optJSONObject("summary")?.let { s ->
                val summary = SessionCallSummary(
                    total = s.optInt("total", 0), done = s.optInt("done", 0), left = s.optInt("left", 0),
                    scheduled = s.optInt("scheduled", 0),
                )
                withContext(Dispatchers.Main) { sessionCallSummary = summary }
            }
            val arr = data?.optJSONArray("items") ?: root.optJSONArray("items") ?: return emptyList()
            // `scheduled` holds people deferred to a later date. Absent on older backends, in which
            // case this is simply empty and the screen behaves exactly as before.
            val scheduledArr = data?.optJSONArray("scheduled") ?: root.optJSONArray("scheduled")
            parseQueueRows(arr, isScheduled = false) + parseQueueRows(scheduledArr, isScheduled = true)
        } catch (e: Exception) { emptyList() }
    }

    private fun parseQueueRows(arr: org.json.JSONArray?, isScheduled: Boolean): List<SessionCallAssignment> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            // Backend emits raw Prisma camelCase (no snake_case transform).
            val campaign = obj.optJSONObject("campaign")
            val batchMember = obj.optJSONObject("batchMember")
            val batch = campaign?.optJSONObject("batch")
            SessionCallAssignment(
                id = obj.optInt("id").toString(),
                campaignId = obj.optInt("campaignId").toString(),
                batchMemberId = obj.optInt("batchMemberId").toString(),
                mobile = batchMember?.optString("mobile") ?: "",
                name = batchMember?.let { if (it.isNull("name")) null else it.optString("name", "").ifBlank { null } },
                callStatus = obj.optString("callStatus"),
                sessionName = (campaign?.optString("sessionName") ?: "").ifBlank { campaign?.optString("aeSessionId") ?: "" },
                batchName = batch?.optString("name") ?: "",
                scheduledAt = campaign?.optString("scheduledAt") ?: "",
                context = parsePersonContext(obj.optJSONObject("context")),
                callerNote = campaign?.let { if (it.isNull("callerNote")) null else it.optString("callerNote", "").ifBlank { null } },
                whatsappMessage = campaign?.let { if (it.isNull("whatsappMessage")) null else it.optString("whatsappMessage", "").ifBlank { null } },
                isScheduled = isScheduled,
                snoozeUntil = batchMember?.let { if (it.isNull("snoozeUntil")) null else it.optString("snoozeUntil", "").ifBlank { null } }
            )
        }
    }

    // Parse the backend `context` blob (previous responses + attendance + details) shared by the
    // session-call and pool-call queue endpoints.
    private fun parsePersonContext(obj: JSONObject?): PersonContext {
        if (obj == null) return PersonContext()
        val callsArr = obj.optJSONArray("recentCalls")
        val calls = if (callsArr == null) emptyList() else (0 until callsArr.length()).map { i ->
            val c = callsArr.getJSONObject(i)
            CallResponse(
                date = c.optString("date", ""),
                outcome = c.optString("outcome", ""),
                remark = if (c.isNull("remark")) null else c.optString("remark", "").ifBlank { null },
                caller = c.optString("caller", "—")
            )
        }
        val attArr = obj.optJSONArray("recentAttendance")
        val att = if (attArr == null) emptyList() else (0 until attArr.length()).map { attArr.optString(it, "") }.filter { it.isNotBlank() }
        val batchArr = obj.optJSONArray("currentBatches")
        val batches = if (batchArr == null) emptyList() else (0 until batchArr.length()).map { batchArr.optString(it, "") }.filter { it.isNotBlank() }
        return PersonContext(
            recentCalls = calls,
            attendanceCount = obj.optInt("attendanceCount", 0),
            lastAttended = if (obj.isNull("lastAttended")) null else obj.optString("lastAttended", "").ifBlank { null },
            recentAttendance = att,
            occupation = if (obj.isNull("occupation")) null else obj.optString("occupation", "").ifBlank { null },
            address = if (obj.isNull("address")) null else obj.optString("address", "").ifBlank { null },
            isNew = obj.optBoolean("isNew", false),
            currentBatches = batches
        )
    }

    suspend fun fetchBatchMembers(batchId: String): List<BatchMemberInfo> = withContext(Dispatchers.IO) {
        // category MUST be lowercase — the backend Category enum is youth/congregation, but the app's
        // selectedCategory is "Youth"/"Congregation". Sending "Youth" matched ZERO batch_members rows,
        // which is why session-mode roster + the lower batch/session filter came back empty.
        val cat = selectedCategory.lowercase()
        val result = callingApiGet("/batch-members?batch_id=$batchId&category=$cat")
            ?: return@withContext loadRosterCache(batchId)
        try {
            val root = JSONObject(result)
            val arr = root.optJSONObject("data")?.optJSONArray("items") ?: root.optJSONArray("items") ?: return@withContext loadRosterCache(batchId)
            val members = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                BatchMemberInfo(
                    id = obj.optInt("id").toString(),
                    mobile = obj.optString("mobile"),
                    name = if (obj.isNull("name")) null else obj.optString("name", "").ifBlank { null },
                    batchId = obj.optInt("batch_id"),
                    joinedAt = obj.optString("joined_at")
                )
            }
            if (members.isNotEmpty()) saveRosterCache(batchId, members)
            members
        } catch (e: Exception) { loadRosterCache(batchId) }
    }

    // The WHOLE centre+category roster (every batch), not one batch's.
    //
    // Pool Assignment needs this to allot someone to a calling session whose batch they are not in.
    // The unified model has always allowed that — `assignSessionCallers` takes ANY batch_member_id
    // and deliberately does not check it against the campaign's batch — but the screen could only
    // resolve a visible row to an id through the campaign's OWN roster, so every cross-batch row
    // came back "not in this batch" and could not be assigned. Assembling people from several
    // batches into one calling session is the entire point of that screen.
    //
    // Omitting batch_id is the whole trick: getBatchMembers treats it as optional and then returns
    // the centre+category roster. No backend change was needed.
    //
    // Returns NULL when the request failed AND no cache could stand in for it. Empty means the
    // centre genuinely has no members. The caller MUST keep these apart — reporting "they aren't in
    // any batch" off a request that never landed is a confident lie about the data, which is the
    // same failure-vs-empty conflation `fetchSessionPeopleMobiles` carries a tri-state to avoid.
    suspend fun fetchCentreRosterMembers(): List<BatchMemberInfo>? = withContext(Dispatchers.IO) {
        // Lowercase for the same reason fetchBatchMembers does it: the backend Category enum is
        // youth/congregation and "Youth" matches ZERO rows.
        val cat = selectedCategory.lowercase()
        // Deliberately NOT a batch id, so it can never collide with cached_roster_<batchId>.
        // Centre- and category-scoped because one device switches between both.
        val cacheKey = "__centre_${selectedCentreId}_$cat"
        val result = callingApiGet("/batch-members?category=$cat")
            ?: return@withContext loadRosterCache(cacheKey).ifEmpty { null }
        try {
            val root = JSONObject(result)
            val arr = root.optJSONObject("data")?.optJSONArray("items") ?: root.optJSONArray("items")
                ?: return@withContext loadRosterCache(cacheKey).ifEmpty { null }
            val members = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                BatchMemberInfo(
                    id = obj.optInt("id").toString(),
                    mobile = obj.optString("mobile"),
                    name = if (obj.isNull("name")) null else obj.optString("name", "").ifBlank { null },
                    batchId = obj.optInt("batch_id"),
                    joinedAt = obj.optString("joined_at")
                )
            }
            if (members.isNotEmpty()) saveRosterCache(cacheKey, members)
            members
        } catch (e: Exception) { loadRosterCache(cacheKey).ifEmpty { null } }
    }

    // Session calls log the SAME configurable calling-outcome label as pool calls. The backend derives
    // the coarse session status (will/won't/no-answer/called) from the label and records it in history.
    // Same durable-outbox pattern as addRegistration/markAttendance: offline or a failed write parks
    // the outcome for automatic retry instead of losing it or blocking the caller.
    suspend fun updateSessionCallStatus(
        assignmentId: String, outcome: String, remark: String? = null,
        mobile: String = "", name: String? = null, sessionName: String? = null,
        // Step 1 of the caller's two-step ("Did they pick the call?"). Null when the screen that
        // logged this outcome does not ask — the server stores NULL, which is what every attempt
        // recorded before the two-step shipped already carries.
        answered: Boolean? = null,
        // WHICH response was tapped. Sent alongside the label so a rename cannot orphan this
        // outcome — including a rename made while it is still sitting in the outbox, which is the
        // whole reason it exists. Null from screens that do not pick from the calling list.
        dropdownId: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        // Encoded by the same object that decodes it on drain, so the two can't drift apart — the
        // fields are positional and a mismatch would silently move `answered` or the response id.
        val payload = CallOutcomeQueue.encode(assignmentId, outcome, remark, answered, dropdownId)
        val derived = deriveCallStatusLocal(outcome)
        val override = PendingCallOverride(
            assignmentId = assignmentId, outcome = outcome, derivedStatus = derived,
            remark = remark, positive = ResponseMeaning.isPositive(derived),
            followupKind = ResponseMeaning.followupKind(derived),
            mobile = mobile, name = name, sessionName = sessionName,
            callerName = currentUser?.name ?: "You"
        )
        // Park it for retry AND optimistically override the queue/follow-up view, so an offline mark
        // looks exactly like an online one until it syncs.
        suspend fun queueAndOverride() = withContext(Dispatchers.Main) {
            // Changing an answer before the queue drains must REPLACE the parked one, not stack a
            // second write for the same person. The backend would upsert to the same row either way,
            // but only if the two arrived in order — dropping the superseded item makes the outcome
            // independent of drain order, and keeps "one session, one response" true end to end.
            offlineQueue = CallOutcomeQueue.supersede(
                offlineQueue,
                OfflineQueueItem(
                    id = "off_${System.currentTimeMillis()}", type = CallOutcomeQueue.TYPE,
                    label = "Call outcome: $outcome", payload = payload
                )
            )
            persistOfflineQueue()
            putOverride(override)
        }
        // Park it and paint it FIRST, always — online and offline are now the same fast path.
        // The old code awaited the PATCH (4s connect + 4s read) before the caller could move on,
        // which made saving online SLOWER than saving offline and is what callers were feeling
        // as "saving takes longer than the call itself".
        queueAndOverride()
        if (!isOnline) return@withContext true

        appScope.launch {
            val body = JSONObject().apply {
                put("outcome", outcome)
                if (!remark.isNullOrBlank()) put("remark", remark.trim())
                if (answered != null) put("answered", answered)
                // Omitted rather than sent as 0/null when unknown: the server treats an absent key
                // as "resolve by name", which is exactly the old behaviour and is still correct.
                dropdownId?.toIntOrNull()?.let { put("dropdown_id", it) }
            }.toString()
            if (callingApiPatch("/session-call-queue/$assignmentId", body) != null) {
                // Fold the outcome into the on-disk queue cache so a caller who loses signal right
                // after still sees who they've called + the response. Only now is it safe to drop
                // the parked copy and the optimistic paint.
                patchQueueCacheEntry(assignmentId, derived, outcome, remark, currentUser?.name ?: "You")
                // The item no longer needs SENDING, so it leaves the outbox here. The PAINT
                // stays until the server's own view of this row catches up (applyOverridesToQueue):
                // these are two different questions and answering them together is what let a
                // concurrent refetch blank out an answer the server had already accepted.
                withContext(Dispatchers.Main) {
                    offlineQueue = CallOutcomeQueue.remove(offlineQueue, assignmentId)
                    persistOfflineQueue()
                }
            }
            // On failure leave the queued item AND the override in place: the existing drain
            // retries it, and the caller keeps seeing their own answer until it lands.

            // Ask the screens to re-read ONLY now, success or failure — never before. The caller
            // screen used to refetch the queue the instant this function returned, which is before
            // the PATCH above has even been sent: the GET and the write went out together and the
            // reply was routinely computed from pre-write state, coming back "pending" for someone
            // just answered. The optimistic override covers that, but the honest fix is not to
            // create the race — a refetch issued after the write has settled reads the truth.
            // Bumped on failure too, so a caller whose write did not land still catches up with
            // everyone else's work rather than sitting on a list that never refreshes.
            withContext(Dispatchers.Main) { bumpDataRevision() }
        }
        // Durably queued and painted — "accepted" from the caller's perspective, matching
        // addRegistration/markAttendance semantics.
        true
    }

    // Close a session campaign (Retrieve) — stops callers from logging new responses.
    suspend fun closeSessionCampaign(campaignId: String): Boolean = withContext(Dispatchers.IO) {
        callingApiPost("/session-campaigns/$campaignId/close", "{}") != null
    }

    // Reopen a closed campaign (undo Retrieve before completing).
    suspend fun reopenSessionCampaign(campaignId: String): Boolean = withContext(Dispatchers.IO) {
        callingApiPost("/session-campaigns/$campaignId/reopen", "{}") != null
    }

    // Complete a session campaign — runs reconciliation and returns the summary.
    suspend fun completeSessionCampaign(campaignId: String): SessionSummary? = withContext(Dispatchers.IO) {
        val result = callingApiPost("/session-campaigns/$campaignId/complete", "{}") ?: return@withContext null
        try { parseSessionSummary(JSONObject(result)) } catch (e: Exception) { null }
    }

    // Fetch the reconciliation summary preview for a session (can be called before completing).
    suspend fun fetchSessionSummary(campaignId: String): SessionSummary? = withContext(Dispatchers.IO) {
        val result = callingApiGet("/session-campaigns/$campaignId/summary") ?: return@withContext null
        try { parseSessionSummary(JSONObject(result)) } catch (e: Exception) { null }
    }

    private fun parseSessionSummary(root: JSONObject): SessionSummary {
        val data = root.optJSONObject("data") ?: root
        val byResponseObj = data.optJSONObject("byResponse")
        val byResponse = mutableMapOf<String, Int>()
        byResponseObj?.keys()?.forEach { k -> byResponse[k] = byResponseObj.optInt(k, 0) }
        return SessionSummary(
            campaignId = data.optInt("campaignId").toString(),
            total = data.optInt("total"),
            came = data.optInt("came"),
            absent = data.optInt("absent"),
            toFollowup = data.optInt("toFollowup"),
            toRemove = data.optInt("toRemove"),
            skippedNoReg = data.optInt("skippedNoReg"),
            status = data.optString("status", ""),
            byResponse = byResponse
        )
    }

}