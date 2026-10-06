package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import android.widget.Toast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.data.*
import com.example.ui.components.*

// ─────────────────────────────────────────────────────────────────────────────
// REGISTERER DASHBOARD
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun RegistererDashboard(
    onNavigateToMarkAttendance: () -> Unit,
    onNavigateToRegisterStudent: () -> Unit,
    onNavigateToLists: () -> Unit
) {
    // Fallback counts from the raw Flask attendance feed (one row per check-in — inflated,
    // not deduplicated by person). Used only if the backend call below fails.
    val fallbackRegistered = AppDatabase.registrations.count {
        it.centreId == AppDatabase.selectedFlaskCentreId && it.category == AppDatabase.selectedCategory && it.status == "active"
    }
    val fallbackArchived = AppDatabase.registrations.count {
        it.centreId == AppDatabase.selectedFlaskCentreId && it.category == AppDatabase.selectedCategory && it.status == "archived"
    }
    var stats by remember { mutableStateOf(com.example.data.DashboardStats()) }
    LaunchedEffect(AppDatabase.selectedCentreId, AppDatabase.selectedCategory) {
        stats = AppDatabase.fetchDashboardStats()
    }
    val totalRegistered = if (stats.totalRegistrations > 0) stats.totalRegistrations else fallbackRegistered
    val totalArchived = if (stats.archivedCount > 0) stats.archivedCount else fallbackArchived
    val pendingSync = AppDatabase.offlineQueue.size
    val failedCount = AppDatabase.offlineQueue.count { it.status == "failed_needs_attention" }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val dumpScope = rememberCoroutineScope()
    val centreName = AppDatabase.centres.find { it.id == AppDatabase.selectedCentreId }?.name ?: "Centre"
    var syncExpanded by remember { mutableStateOf(false) }
    val syncRotation by animateFloatAsState(if (syncExpanded) 180f else 0f, label = "sync_rotate")

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        // Header
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Registerer Dashboard", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("$centreName · ${AppDatabase.selectedCategory}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (pendingSync > 0) StatusBadge(text = "$pendingSync pending sync", status = "pending")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Stat cards — tappable, navigate to relevant screen
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(
                value = "$totalRegistered",
                label = "Registered",
                icon = Icons.Default.PeopleAlt,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                onClick = onNavigateToLists
            )
            StatCard(
                value = "$totalArchived",
                label = "Archived",
                icon = Icons.Default.Archive,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
                onClick = onNavigateToLists
            )
            StatCard(
                value = "${AppDatabase.sessions.filter { s -> AppDatabase.batches.any { b -> b.id == s.batchId && b.centreId == AppDatabase.selectedFlaskCentreId } }.size}",
                label = "Sessions",
                icon = Icons.Default.Event,
                color = Color(0xFF6200EA),
                modifier = Modifier.weight(1f),
                onClick = onNavigateToMarkAttendance
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text("Quick Access", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        HorizontalActionCard(
            title = "Enter Attendance Mode",
            description = "Track session rounds and log feedback remarks for students.",
            icon = Icons.Default.EventAvailable,
            onClick = onNavigateToMarkAttendance
        )
        HorizontalActionCard(
            title = "New Student Enrollment",
            description = "Register incoming students; batch rules R1-R2 applied automatically.",
            icon = Icons.Default.PersonAdd,
            onClick = onNavigateToRegisterStudent
        )
        HorizontalActionCard(
            title = "Browse Collections",
            description = "View registrations, calling logs, and Excel resources.",
            icon = Icons.Default.FolderZip,
            onClick = onNavigateToLists
        )

        // Offline sync card — expandable to show queue items
        if (pendingSync > 0) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { syncExpanded = !syncExpanded },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CloudSync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("$pendingSync Offline Actions Queued", fontWeight = FontWeight.Bold)
                            Text("Tap to expand. Will auto-sync when network is restored.", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp).rotate(syncRotation), tint = MaterialTheme.colorScheme.primary)
                    }

                    // Expandable queue item list
                    AnimatedVisibility(visible = syncExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                            AppDatabase.offlineQueue.take(5).forEach { item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(item.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    StatusBadge(text = item.type, status = "pending")
                                }
                            }
                            if (pendingSync > 5) {
                                Text("…and ${pendingSync - 5} more", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { AppDatabase.recheckConnectivity(); AppDatabase.syncOfflineQueue() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Force Sync Now")
                            }
                            if (failedCount > 0) {
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedButton(
                                    onClick = { AppDatabase.retryAllFailedItems() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Retry $failedCount Failed")
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            // Recover stuck data: copy the raw queue JSON to the clipboard AND
                            // write it to a file so nothing entered on this device is ever lost.
                            OutlinedButton(
                                onClick = {
                                    val raw = AppDatabase.offlineQueueRawJson()
                                    clipboard.setText(AnnotatedString(raw))
                                    val path = AppDatabase.exportOfflineQueueToFile()
                                    // Also push to the server under this user's username+role so an
                                    // admin can recover it centrally (no adb, no device needed).
                                    dumpScope.launch {
                                        // Clears the parked rows once the server confirms it stored
                                        // them, so recovered data stops piling up on the phone. Rows
                                        // still retrying are left alone, and nothing is removed
                                        // unless the server echoes back the path it wrote.
                                        val cleared = AppDatabase.uploadAndClearParked("manual_export")
                                        Toast.makeText(
                                            context,
                                            (if (path != null) "Saved to file + clipboard" else "Copied to clipboard") +
                                                (if (cleared > 0) " · $cleared stuck row(s) uploaded + cleared ✓"
                                                 else " · server upload will retry"),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Export Raw Data (recover stuck)")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ATTENDANCE MARKING SCREEN  (combines attendance + new registration, matching web UI)
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttendanceMarkingScreen(
    onNavigateBack: () -> Unit,
    onViewStudentDetails: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }

    // ── Keyboard-aware layout ────────────────────────────────────────────────
    // A phone keyboard eats 40-50% of the screen. This form is the one place in the app where
    // someone types continuously, at speed, with a queue of people in front of them - and with
    // the keyboard up, the header and the big present-count card were pushing the actual fields
    // and the Submit button into a sliver at the bottom, so every entry meant scrolling.
    //
    // While the keyboard is open the chrome collapses: the title shrinks to a single line and
    // the count card becomes a one-line strip. Nothing is removed - the count stays visible the
    // whole time, because it is what the registerer is checking against - it just stops taking
    // a fifth of the screen. Everything springs back when the keyboard closes.
    val keyboardUp = WindowInsets.isImeVisible
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val mobileFocus = remember { FocusRequester() }
    val nameFocus = remember { FocusRequester() }
    val addressFocus = remember { FocusRequester() }
    val occupationFocus = remember { FocusRequester() }
    val roundsFocus = remember { FocusRequester() }

    // Load the whole-centre calling roster so Mark Attendance can suggest called-but-unregistered
    // people; refreshes when the centre or youth/congregation category changes.
    LaunchedEffect(AppDatabase.selectedCentreId, AppDatabase.selectedCategory) {
        AppDatabase.fetchAttendanceCandidates()
    }

    // Mode toggle: attendance (mark existing member) or register (add new + mark)
    var mode by remember { mutableStateOf(AppDatabase.uiSelection("mark.mode") ?: "attendance") }

    // Batch + Session selectors (D4 & A1)
    //
    // Both picks are STICKY, but only for the day that made them. Leaving this screen for another
    // drawer tab disposes the whole composable branch, so an in-memory pick was lost every time the
    // operator glanced at another page - hence the sticky store rather than a plain remember{}.
    // The day bound is the safety half: a phone opened the next morning must not inherit
    // yesterday's class, because serverActiveSession() is the authority for which session a mark
    // belongs to (see its comment in AppData.kt) and marking into a stale session is the exact
    // failure it was introduced to stop.
    val stickyPicks = remember { AppDatabase.uiSelectionIsFromToday("mark.day") }
    fun rememberPicks(batchId: String?, sessionId: String?) {
        AppDatabase.setUiSelection("mark.day", AppDatabase.uiToday())
        AppDatabase.setUiSelection("mark.batchId", batchId)
        AppDatabase.setUiSelection("mark.sessionId", sessionId)
    }
    var selectedBatch by remember {
        mutableStateOf<Batch?>(
            if (!stickyPicks) null else AppDatabase.uiSelection("mark.batchId")?.let { id ->
                AppDatabase.batches.firstOrNull {
                    it.id == id && it.centreId == AppDatabase.selectedFlaskCentreId && it.category == AppDatabase.selectedCategory
                }
            }
        )
    }
    var selectedSession by remember {
        mutableStateOf<Session?>(null)
    }

    // Dynamic resolution of Batch + Session based on centre, category, serverActiveSession(), and dataRevision
    LaunchedEffect(AppDatabase.selectedFlaskCentreId, AppDatabase.selectedCategory, AppDatabase.dataRevision, AppDatabase.batches, AppDatabase.sessions) {
        val availableBatches = AppDatabase.batches.filter {
            it.centreId == AppDatabase.selectedFlaskCentreId && it.category == AppDatabase.selectedCategory
        }
        val serverActive = AppDatabase.serverActiveSession(AppDatabase.selectedFlaskCentreId, AppDatabase.selectedCategory)
        
        // Read the day stamp FRESH on every run, never through the memoized `stickyPicks`. That
        // val is captured once per composition, so on the first visit of the day it is false - and
        // it stays false even after a pick writes today's stamp. An SSE bump arriving later in the
        // same composition would then skip the restore below and fall through to firstOrNull(),
        // which is the very reset this change exists to remove.
        val stickyNow = AppDatabase.uiSelectionIsFromToday("mark.day")

        // 1. Resolve batch.
        // Matched by ID, never by object. Batch carries memberCount, so the same batch refetched
        // after anyone registers is a different VALUE - an object comparison would read a perfectly
        // live pick as stale and drop the operator back on the default batch. Rebind to the fresh
        // instance instead, so member counts stay current without losing the pick.
        val stillAvailable = selectedBatch?.let { sel -> availableBatches.find { it.id == sel.id } }
        if (stillAvailable != null) {
            if (stillAvailable != selectedBatch) selectedBatch = stillAvailable
        } else {
            val batchForServerActive = serverActive?.batchId?.let { bId -> availableBatches.find { it.id == bId } }
            val rememberedBatch = if (!stickyNow) null else
                AppDatabase.uiSelection("mark.batchId")?.let { id -> availableBatches.find { it.id == id } }
            selectedBatch = rememberedBatch ?: batchForServerActive ?: availableBatches.firstOrNull()
        }

        // 2. Resolve session per A1: prefer serverActiveSession(), never fall back to arbitrary firstOrNull
        val availableSessions = selectedBatch?.id?.let { bId -> AppDatabase.sessions.filter { it.batchId == bId } } ?: emptyList()
        val serverActiveMatch = serverActive?.sessionId?.let { sId -> AppDatabase.sessions.find { it.id == sId } }

        if (serverActiveMatch != null && (selectedBatch == null || serverActiveMatch.batchId == selectedBatch?.id)) {
            selectedSession = serverActiveMatch
            if (selectedBatch == null) {
                selectedBatch = availableBatches.find { it.id == serverActiveMatch.batchId }
            }
        } else if (selectedSession != null && availableSessions.none { it.id == selectedSession?.id }) {
            // Selected session is no longer valid for track/batch — require explicit choice
            selectedSession = null
        } else if (selectedSession != null) {
            // Still valid: rebind by id to the fresh row, so a session renamed or re-dated on the
            // server is never marked against the stale copy this screen happens to be holding.
            availableSessions.find { it.id == selectedSession?.id }
                ?.let { fresh -> if (fresh != selectedSession) selectedSession = fresh }
        } else {
            // Nothing is server-active for the selected batch, so fall back to the operator's own
            // last pick - but only one that still exists, still belongs to THIS batch, and is dated
            // today. Those three guards are what stop a restored pick from reintroducing the
            // months-old-session mark that serverActiveSession() was added to prevent.
            val rememberedSession = if (!stickyNow) null else AppDatabase.uiSelection("mark.sessionId")
            if (rememberedSession != null) {
                selectedSession = availableSessions.find { it.id == rememberedSession && it.date == today }
            }
        }
    }

    // Form fields
    var mobileQuery  by remember { mutableStateOf("") }
    var nameValue    by remember { mutableStateOf("") }
    var addressValue by remember { mutableStateOf("") }
    var occupationValue by remember { mutableStateOf("") }
    var roundsValue  by remember { mutableStateOf("1") }
    var remarkValue  by remember { mutableStateOf("") }
    var remarkMenuExpanded by remember { mutableStateOf(false) }
    var addressExpanded    by remember { mutableStateOf(false) }

    // State
    var matchedReg    by remember { mutableStateOf<Registration?>(null) }
    var detailsEdited by remember { mutableStateOf(false) } // D5: explicit flag when profile edit unlocked
    var isSubmitting  by remember { mutableStateOf(false) }
    var submitError   by remember { mutableStateOf<String?>(null) }
    // Live list: (name, mobile) submitted by THIS app instance since the current session was selected.
    var submittedItems by remember { mutableStateOf<List<Pair<String,String>>>(emptyList()) }
    // Server-truth attendance already recorded for the selected session (Flask history). Merged with
    // submittedItems so the big number + list reflect the session's REAL present-count, and persist
    // across tab switches. Cleared+reloaded whenever the selected session or dataRevision changes (D3).
    var sessionAttendance by remember { mutableStateOf<List<Pair<String,String>>>(emptyList()) }

    // Create Batch / Session dialog state
    var showCreateBatch    by remember { mutableStateOf(false) }
    var newBatchNameInput  by remember { mutableStateOf("") }
    var createBatchError   by remember { mutableStateOf<String?>(null) }
    var showCreateSession  by remember { mutableStateOf(false) }
    var newSessionNameInput by remember { mutableStateOf("") }
    var createSessionError by remember { mutableStateOf<String?>(null) }
    var showCreatePlannedSession by remember { mutableStateOf(false) }
    var plannedSessionName by remember { mutableStateOf("") }
    var plannedSessionDate by remember { mutableStateOf("") }
    var plannedSessions by remember { mutableStateOf<List<PlannedSession>>(emptyList()) }

    // Whenever the selected session or dataRevision changes, reload the session's real attendance (D3)
    LaunchedEffect(selectedSession?.id, AppDatabase.selectedFlaskCentreId, AppDatabase.selectedCategory, AppDatabase.dataRevision) {
        val sid = selectedSession?.id
        sessionAttendance = if (!sid.isNullOrBlank()) AppDatabase.fetchSessionAttendance(sid) else emptyList()
    }

    // Present-in-this-session list: server history ∪ this-instance marks, deduped by last-10 mobile
    // (newest name wins). In registration mode there's no attendance history, so it's just the
    // students added this session. Drives BOTH the big number and the list below.
    val presentList: List<Pair<String,String>> = run {
        val out = LinkedHashMap<String, Pair<String,String>>()
        val source = if (mode == "attendance") sessionAttendance + submittedItems else submittedItems
        source.forEach { pair ->
            val k = pair.second.filter { it.isDigit() }.takeLast(10)
            if (k.isNotEmpty()) out[k] = pair
        }
        out.values.toList()
    }

    val currentRegistrations = AppDatabase.registrations.filter {
        it.centreId == AppDatabase.selectedFlaskCentreId &&
        it.category == AppDatabase.selectedCategory && it.status == "active"
    }
    // Attendance mode also suggests the whole-centre calling roster (people who were called but may
    // have no Flask attendance/registration record yet). Registration mode keeps the Flask roster
    // only. Merge deduped by last-10-digit mobile; a real Flask registration wins over a bare
    // calling-roster candidate (richer details, auto-fill).
    val suggestionPool = if (mode == "attendance") {
        val regPhones = currentRegistrations.map { it.mobile.filter { c -> c.isDigit() }.takeLast(10) }.toSet()
        currentRegistrations + AppDatabase.attendanceCandidates.filter {
            it.mobile.filter { c -> c.isDigit() }.takeLast(10) !in regPhones
        }
    } else currentRegistrations
    val suggestions = if (mobileQuery.length >= 2 && matchedReg == null) {
        suggestionPool.filter {
            it.mobile.contains(mobileQuery) || it.name.contains(mobileQuery, ignoreCase = true)
        }.take(6)
    } else emptyList()

    val addressOptions = AppDatabase.dropdownOptions.filter {
        it.context == "address" && it.active &&
        (addressValue.isEmpty() || it.label.contains(addressValue, ignoreCase = true))
    }
    val remarkOptions = AppDatabase.dropdownOptions.filter { it.context == "remark" && it.active }

    fun clearForm() {
        mobileQuery = ""; nameValue = ""; addressValue = ""; occupationValue = ""
        roundsValue = "1"; remarkValue = ""; matchedReg = null; detailsEdited = false; submitError = null
    }

    val isMobileValid = mobileQuery.length == 10 && mobileQuery.all { it.isDigit() }

    // A walk-in: a valid number typed on the Attendance tab that matches nobody on the roster.
    // The person is standing at the door, so the tab must be able to take them — previously the
    // button simply stayed disabled and the only way through was to leave for the Registration
    // tab, register them there, and come back. That round trip is what left five people on
    // 2026-08-01 registered but never marked present.
    val isWalkIn = mode == "attendance" && matchedReg == null && isMobileValid && nameValue.isNotBlank()

    val formReady = selectedSession != null && when (mode) {
        "attendance" -> matchedReg != null || isWalkIn
        else         -> isMobileValid && nameValue.isNotBlank()
    }

    val canSubmit = !isSubmitting && formReady

    // Both the full-width button below and the inline tick beside the mobile field call this.
    // Extracted rather than duplicated on purpose: the branches here (walk-in, known-vs-edited,
    // attendance-vs-register) are the whole behaviour of the screen, and two copies would diverge.
    fun submitCurrent() {
        if (!canSubmit) return
        scope.launch {
            isSubmitting = true; submitError = null
            // A walk-in has no matchedReg to read from — take what was typed.
            val known   = mode == "attendance" && matchedReg != null && !detailsEdited
            val mobile  = if (known) matchedReg!!.mobile else mobileQuery
            val name    = if (known) matchedReg!!.name   else nameValue
            val address = if (known) (matchedReg!!.address.ifEmpty { addressValue }) else addressValue
            val occ     = if (known) (matchedReg!!.occupation.ifEmpty { occupationValue }) else occupationValue
            val sid     = selectedSession?.id ?: ""
            val date    = selectedSession?.date ?: today
            val bid     = selectedBatch?.id ?: ""
            val ok = if (isWalkIn) {
                // One action, both effects: add them to the selected batch AND
                // record them present for this session. Registering first and
                // marking second would leave them absent if the second call
                // never happened, which is the exact failure this replaces.
                AppDatabase.addRegistration(
                    name, mobile, address, occ, remarkValue, bid,
                    markPresent = true, sessionId = sid, date = date
                )
                true
            } else if (mode == "attendance") {
                AppDatabase.submitAttendance(
                    mobile = mobile, name = name, address = address, occupation = occ,
                    sessionId = sid, date = date, rounds = roundsValue, remark = remarkValue, batchId = bid
                )
            } else {
                // Registration mode = add the student to the batch ROSTER only.
                // Do NOT call submitAttendance here — that posts to mark_attendance
                // and would wrongly record the new person as present for the session.
                // addRegistration hits /api/register_student (roster, no attendance).
                AppDatabase.addRegistration(name, mobile, address, occ, remarkValue, bid)
                true
            }
            isSubmitting = false
            if (ok) {
                submittedItems = submittedItems + (name to mobile)
                // Reconcile with server truth for this session (picks up marks
                // made by other devices too); dedup in presentList handles overlap.
                if (mode == "attendance" && sid.isNotBlank()) {
                    scope.launch { sessionAttendance = AppDatabase.fetchSessionAttendance(sid) }
                }
                clearForm()
                // Straight back to the search box for the next person in the
                // queue. Marking is done in a continuous run at the door, and
                // without this the operator had to re-tap the field and wait
                // for the keyboard once per person.
                mobileFocus.requestFocus()
            } else {
                submitError = "Submission failed. Check connection and try again."
            }
        }
    }

    // ── Create Batch dialog ──────────────────────────────────────────────
    if (showCreateBatch) {
        AlertDialog(
            onDismissRequest = { showCreateBatch = false; newBatchNameInput = ""; createBatchError = null },
            icon = { Icon(Icons.Default.Group, contentDescription = null) },
            title = { Text("New Batch") },
            text = {
                Column {
                    Text("Add a batch for ${AppDatabase.selectedCategory} · ${AppDatabase.centres.find { it.id == AppDatabase.selectedCentreId }?.name ?: "Centre"}")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newBatchNameInput,
                        onValueChange = { newBatchNameInput = it; createBatchError = null },
                        label = { Text("Batch Name") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )
                    createBatchError?.let { err ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val created = AppDatabase.createBatch(newBatchNameInput.trim())
                            if (created != null) {
                                selectedBatch = created; selectedSession = null
                                scope.launch { plannedSessions = AppDatabase.fetchPlannedSessions(created.id) }
                                showCreateBatch = false; newBatchNameInput = ""; createBatchError = null
                            } else {
                                createBatchError = "Failed — batch may already exist or connection error."
                            }
                        }
                    },
                    enabled = newBatchNameInput.isNotBlank()
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateBatch = false; newBatchNameInput = ""; createBatchError = null }) { Text("Cancel") } }
        )
    }

    // ── Create Session dialog ─────────────────────────────────────────────────
    if (showCreateSession) {
        AlertDialog(
            onDismissRequest = { showCreateSession = false; newSessionNameInput = ""; createSessionError = null },
            icon = { Icon(Icons.Default.Event, contentDescription = null) },
            title = { Text("New Session") },
            text = {
                Column {
                    Text("Add session to batch: ${selectedBatch?.name ?: ""}")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newSessionNameInput,
                        onValueChange = { newSessionNameInput = it; createSessionError = null },
                        label = { Text("Session Name (e.g. Sunday 15 June)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )
                    createSessionError?.let { err ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val batchId = selectedBatch?.id ?: return@launch
                            val created = AppDatabase.createSession(newSessionNameInput.trim(), batchId)
                            if (created != null) {
                                selectedSession = created
                                showCreateSession = false; newSessionNameInput = ""; createSessionError = null
                            } else {
                                createSessionError = "Failed — check connection and try again."
                            }
                        }
                    },
                    enabled = newSessionNameInput.isNotBlank() && selectedBatch != null
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateSession = false; newSessionNameInput = ""; createSessionError = null }) { Text("Cancel") } }
        )
    }
    LazyColumn(
        // Inset by the keyboard so the focused field is never drawn underneath it — but by the
        // part of the keyboard that is NOT already accounted for.
        //
        // A plain imePadding() here left a white strip between the form and the keyboard. This
        // screen is hosted inside MainActivity's Scaffold, whose innerPadding has ALREADY inset
        // the content by the navigation bar; the ime inset is measured from the bottom of the
        // window and so includes that same navigation bar. Applying it whole counts the
        // navigation bar twice. Excluding it adds only the genuinely new space.
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars)),
        // The trailing breathing room is dead space once the keyboard is the thing below the
        // list, and it was stacking on top of the gap above.
        contentPadding = PaddingValues(bottom = if (keyboardUp) 0.dp else 24.dp)
    ) {
        // ── Header ──────────────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(
                    start = 4.dp,
                    top = if (keyboardUp) 0.dp else 8.dp,
                    end = 16.dp,
                    bottom = if (keyboardUp) 0.dp else 4.dp
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Column(modifier = Modifier.weight(1f)) {
                    if (!keyboardUp) {
                        Text("Session Attendance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    // The session name stays on screen even collapsed — marking into the wrong
                    // session is the expensive mistake here, so it is never the thing hidden.
                    Text(
                        selectedSession?.name?.ifEmpty { "No session selected" } ?: "No session selected",
                        style = if (keyboardUp) MaterialTheme.typography.labelMedium
                                else MaterialTheme.typography.bodySmall,
                        fontWeight = if (keyboardUp) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // ── Batch + Session selectors ────────────────────────────────────
        // Hidden while typing, but ONLY once a session is actually chosen — the header already
        // names it, and nobody switches batch mid-entry. With no session picked yet these stay
        // put, because then they are the whole point of the screen.
        item {
            if (keyboardUp && selectedSession != null) return@item
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Batch dropdown
                    Column(modifier = Modifier.weight(1f)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Batch", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { showCreateBatch = true }, modifier = Modifier.size(18.dp)) {
                                Icon(Icons.Default.Add, contentDescription = "New Batch", modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        var batchExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(expanded = batchExpanded, onExpandedChange = { batchExpanded = it }) {
                            OutlinedTextField(
                                value = selectedBatch?.name ?: "Select",
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = batchExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            ExposedDropdownMenu(expanded = batchExpanded, onDismissRequest = { batchExpanded = false }, modifier = Modifier.heightIn(max = 240.dp)) {
                                // Newest batch first, DEFAULT BATCH PINNED ABOVE EVERYTHING.
                                //
                                // Batch carries no createdAt on the client model, so `id` is the ordering
                                // key: it is AttendEase's autoincrement primary key, therefore insertion
                                // order by construction and never null. `toIntOrNull() ?: 0` keeps a
                                // non-numeric id (there are none today) at the bottom rather than crashing.
                                //
                                // The `isDefault` term is NOT decoration — it must come first in the
                                // comparator. /api/batches was changed on 2026-08-17 to return
                                // `is_default DESC, id DESC` precisely because the default batch is where
                                // walk-ins are filed and registerers reach for it constantly. This list
                                // re-sorts client-side, so without the pin here the client would quietly
                                // override that and the two ends would disagree about the first row.
                                AppDatabase.batches.filter {
                                    it.centreId == AppDatabase.selectedFlaskCentreId && it.category == AppDatabase.selectedCategory
                                }.sortedWith(
                                    compareByDescending<Batch> { it.isDefault }
                                        .thenByDescending { it.id.toIntOrNull() ?: 0 }
                                ).forEach { b ->
                                    DropdownMenuItem(
                                        text = { Text(b.name) },
                                        onClick = {
                                            selectedBatch = b
                                            val serverActive = AppDatabase.serverActiveSession(AppDatabase.selectedFlaskCentreId, AppDatabase.selectedCategory)
                                            val serverActiveMatch = serverActive?.sessionId?.let { sId -> AppDatabase.sessions.find { it.id == sId && it.batchId == b.id } }
                                            selectedSession = serverActiveMatch
                                            rememberPicks(b.id, serverActiveMatch?.id)
                                            scope.launch { plannedSessions = AppDatabase.fetchPlannedSessions(b.id) }
                                            batchExpanded = false
                                        },
                                        trailingIcon = if (b.isDefault) ({ StatusBadge("Default", "active") }) else null
                                    )
                                }
                            }
                        }
                    }
                    // Session dropdown
                    Column(modifier = Modifier.weight(1f)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Session", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { if (selectedBatch != null) showCreateSession = true }, modifier = Modifier.size(18.dp), enabled = selectedBatch != null) {
                                Icon(Icons.Default.Add, contentDescription = "New Session", modifier = Modifier.size(12.dp), tint = if (selectedBatch != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        var sessionExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(expanded = sessionExpanded, onExpandedChange = { if (selectedBatch != null) sessionExpanded = it }) {
                            OutlinedTextField(
                                value = selectedSession?.name ?: "Select",
                                onValueChange = {},
                                readOnly = true,
                                enabled = selectedBatch != null,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sessionExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            ExposedDropdownMenu(expanded = sessionExpanded, onDismissRequest = { sessionExpanded = false }, modifier = Modifier.heightIn(max = 240.dp)) {
                                // Grouped under two headings so the registerer can tell the kinds
                                // apart: Calling sessions (session_type 'planned' — calling + attendance)
                                // vs Direct sessions (attendance-only). Up to 4 most-recent of each.
                                val batchSessions = AppDatabase.sessions.filter { it.batchId == selectedBatch?.id }
                                val callingSessions = batchSessions
                                    .filter { it.sessionType == "planned" }
                                    .sortedByDescending { it.date }.take(4)
                                val directSessions = batchSessions
                                    .filter { it.sessionType != "planned" }
                                    .sortedByDescending { it.date }.take(4)
                                if (callingSessions.isNotEmpty()) {
                                    DropdownMenuItem(
                                        enabled = false,
                                        text = { Text("Calling sessions", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                                        onClick = {}
                                    )
                                    callingSessions.forEach { s ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Column(Modifier.weight(1f)) { Text(s.name); Text(s.date, style = MaterialTheme.typography.labelSmall) }
                                                    StatusBadge("Calling", "active")
                                                }
                                            },
                                            onClick = {
                                                selectedSession = s
                                                rememberPicks(selectedBatch?.id, s.id)
                                                sessionExpanded = false
                                            }
                                        )
                                    }
                                }
                                if (directSessions.isNotEmpty()) {
                                    DropdownMenuItem(
                                        enabled = false,
                                        text = { Text("Direct sessions", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                        onClick = {}
                                    )
                                    directSessions.forEach { s ->
                                        DropdownMenuItem(
                                            text = { Column { Text(s.name); Text(s.date, style = MaterialTheme.typography.labelSmall) } },
                                            onClick = {
                                                selectedSession = s
                                                rememberPicks(selectedBatch?.id, s.id)
                                                sessionExpanded = false
                                            }
                                        )
                                    }
                                }
                                if (callingSessions.isEmpty() && directSessions.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No sessions yet", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                        onClick = {}
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Counter card ─────────────────────────────────────────────────
        item {
            Spacer(modifier = Modifier.height(if (keyboardUp) 4.dp else 8.dp))
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                if (keyboardUp) {
                    // Same information, one line instead of a block: the number the registerer
                    // is tracking stays in front of them while they type, at a fraction of the
                    // height. This card alone was ~120dp of a ~350dp usable area.
                    Row(
                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "${presentList.size}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            if (mode == "attendance") "present" else "registered",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.padding(vertical = 20.dp, horizontal = 24.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "${presentList.size}",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            if (mode == "attendance") "PRESENT IN THIS SESSION" else "REGISTERED THIS SESSION",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        if (mode == "attendance" && selectedSession == null) {
                            Text(
                                "Select a session to see its attendance",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }

        // ── Form card ────────────────────────────────────────────────────
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {

                    // Mode toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("attendance" to "Attendance", "register" to "Register New").forEach { (m, label) ->
                            if (mode == m) {
                                Button(
                                    onClick = {},
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) { Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                            } else {
                                TextButton(
                                    onClick = { mode = m; AppDatabase.setUiSelection("mark.mode", m); clearForm() },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                    }

                    if (mode == "register") {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                "Registration saves the student into the selected centre/batch roster for future sessions.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp),
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Mobile / name search with inline submit tick (Task 1)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = mobileQuery,
                            onValueChange = { raw ->
                                // In "Register New" this field is a phone number, so a pasted
                                // "+91 98350 00001" has to become 9835000001 or isMobileValid never
                                // turns true, the inline tick never appears, and the paste looks
                                // ignored. null = refuse the edit, which keeps the 10-digit cap.
                                // In attendance mode the SAME field is "Mobile or Name" free text,
                                // so stripping non-digits there would break searching by name.
                                val v = if (mode == "register")
                                    PhoneNumbers.normalizeMobileInput(raw) ?: mobileQuery
                                else raw
                                mobileQuery = v
                                submitError = null
                                if (mode == "attendance") {
                                    // Compare on last-10 like the suggestion pool above does, so a
                                    // pasted number with a +91 or spacing still finds its person.
                                    // Guarded on 10 digits because a name-only query filters to ""
                                    // and would otherwise match any record with a blank mobile.
                                    val typed = v.filter { c -> c.isDigit() }.takeLast(10)
                                    val exact = if (typed.length == 10)
                                        currentRegistrations.find {
                                            it.mobile.filter { c -> c.isDigit() }.takeLast(10) == typed
                                        }
                                    else null
                                    matchedReg = exact
                                    if (exact != null) {
                                        nameValue = exact.name; addressValue = exact.address; occupationValue = exact.occupation
                                    } else {
                                        nameValue = ""; addressValue = ""; occupationValue = ""
                                    }
                                }
                            },
                            label = { Text(if (mode == "attendance") "Mobile or Name" else "Mobile (10 digits)") },
                            modifier = Modifier.weight(1f).focusRequester(mobileFocus),
                            shape = RoundedCornerShape(10.dp),
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = if (mode == "register") KeyboardType.Phone else KeyboardType.Text,
                                imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { nameFocus.requestFocus() }),
                            trailingIcon = if (mobileQuery.isNotEmpty()) ({
                                IconButton(onClick = { clearForm() }) { Icon(Icons.Default.Clear, contentDescription = "Clear") }
                            }) else null,
                            singleLine = true
                        )

                        val inlineTickDesc = when {
                            isWalkIn -> "Register and mark present"
                            mode == "attendance" -> "Mark attendance"
                            else -> "Register student"
                        }

                        AnimatedVisibility(
                            visible = formReady,
                            enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
                            exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start)
                        ) {
                            Surface(
                                onClick = { submitCurrent() },
                                enabled = canSubmit,
                                modifier = Modifier
                                    .height(54.dp)
                                    .width(52.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                shadowElevation = 2.dp
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    if (isSubmitting) {
                                        CircularProgressIndicator(
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = inlineTickDesc,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Autocomplete suggestions
                    AnimatedVisibility(visible = suggestions.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Column {
                                suggestions.forEach { s ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable {
                                                matchedReg = s; mobileQuery = s.mobile
                                                nameValue = s.name; addressValue = s.address; occupationValue = s.occupation
                                            }
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(s.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                            Text(s.mobile, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                    if (s != suggestions.last()) HorizontalDivider()
                                }
                            }
                        }
                    }

                    // Auto-fill indicator (D5)
                    AnimatedVisibility(visible = matchedReg != null) {
                        Surface(
                            modifier = Modifier.padding(top = 6.dp),
                            color = if (detailsEdited) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (detailsEdited) Icons.Default.Edit else Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (detailsEdited) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    if (detailsEdited) "Editing profile for matched person" else "User Found — details auto-filled",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (detailsEdited) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                if (!detailsEdited) {
                                    TextButton(
                                        onClick = { detailsEdited = true },
                                        contentPadding = PaddingValues(0.dp)
                                    ) { Text("Edit details", style = MaterialTheme.typography.labelSmall) }
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                TextButton(
                                    onClick = { matchedReg = null; detailsEdited = false },
                                    contentPadding = PaddingValues(0.dp)
                                ) { Text("Change person", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Name field
                    OutlinedTextField(
                        value = nameValue,
                        onValueChange = { if (mode == "register" || matchedReg == null || detailsEdited) nameValue = it },
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
                        shape = RoundedCornerShape(10.dp),
                        readOnly = mode == "attendance" && matchedReg != null && !detailsEdited,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(
                            onNext = { addressFocus.requestFocus() })
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Address with dropdown suggestions
                    ExposedDropdownMenuBox(
                        expanded = addressExpanded && addressOptions.isNotEmpty() && (mode == "register" || matchedReg == null || detailsEdited),
                        onExpandedChange = { if (mode == "register" || matchedReg == null || detailsEdited) addressExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = addressValue,
                            onValueChange = { if (mode == "register" || matchedReg == null || detailsEdited) { addressValue = it; addressExpanded = true } },
                            label = { Text("Address") },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryEditable)
                                .fillMaxWidth()
                                .focusRequester(addressFocus),
                            shape = RoundedCornerShape(10.dp),
                            readOnly = mode == "attendance" && matchedReg != null && !detailsEdited,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = {
                                addressExpanded = false
                                occupationFocus.requestFocus()
                            })
                        )
                        if (mode == "register" || matchedReg == null || detailsEdited) {
                            ExposedDropdownMenu(
                                expanded = addressExpanded,
                                onDismissRequest = { addressExpanded = false },
                                modifier = Modifier.heightIn(max = 240.dp)
                            ) {
                                addressOptions.take(40).forEach { opt ->
                                    DropdownMenuItem(text = { Text(opt.label) }, onClick = { addressValue = opt.label; addressExpanded = false })
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Occupation
                    OutlinedTextField(
                        value = occupationValue,
                        onValueChange = { if (mode == "register" || matchedReg == null || detailsEdited) occupationValue = it },
                        label = { Text("Occupation") },
                        modifier = Modifier.fillMaxWidth().focusRequester(occupationFocus),
                        shape = RoundedCornerShape(10.dp),
                        readOnly = mode == "attendance" && matchedReg != null && !detailsEdited,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(
                            onNext = { roundsFocus.requestFocus() })
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Rounds + Remark
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = roundsValue,
                            onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 2) roundsValue = it },
                            label = { Text("Rounds") },
                            // Last typed field, so Done closes the keyboard and hands the whole
                            // screen back — the Submit button is then in plain view rather than
                            // behind the keyboard.
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                focusManager.clearFocus()
                                keyboard?.hide()
                            }),
                            modifier = Modifier.weight(1f).focusRequester(roundsFocus),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )
                        ExposedDropdownMenuBox(
                            expanded = remarkMenuExpanded,
                            onExpandedChange = { remarkMenuExpanded = it },
                            modifier = Modifier.weight(2f)
                        ) {
                            // Editable too, so it needs PrimaryEditable for the same reason as
                            // Address above. It only misbehaved once the menu had been opened
                            // from the trailing icon — typing alone does not expand this one —
                            // but from that point typing a free-text remark hit the identical
                            // stolen-focus wall.
                            OutlinedTextField(
                                value = remarkValue,
                                onValueChange = { remarkValue = it },
                                label = { Text("Remark") },
                                modifier = Modifier
                                    .menuAnchor(MenuAnchorType.PrimaryEditable)
                                    .fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = remarkMenuExpanded) },
                                singleLine = true
                            )
                            ExposedDropdownMenu(
                                expanded = remarkMenuExpanded,
                                onDismissRequest = { remarkMenuExpanded = false },
                                modifier = Modifier.heightIn(max = 240.dp)
                            ) {
                                remarkOptions.forEach { opt ->
                                    DropdownMenuItem(text = { Text(opt.label) }, onClick = { remarkValue = opt.label; remarkMenuExpanded = false })
                                }
                            }
                        }
                    }

                    if (submitError != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(submitError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (selectedSession == null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Pick a session first to enable marking", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    } else {
                        val sessName = selectedSession?.name.orEmpty()
                        val sessDate = selectedSession?.date.orEmpty()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Target Session:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                if (sessDate.isNotBlank()) "$sessName ($sessDate)" else sessName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Button(
                        onClick = { submitCurrent() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        enabled = canSubmit,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                when {
                                    // Says exactly what the tap will do, so nobody has to guess
                                    // whether an unknown number was taken.
                                    isWalkIn -> "REGISTER & MARK PRESENT"
                                    mode == "attendance" -> "MARK ATTENDANCE"
                                    else -> "ADD STUDENT"
                                },
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }
        }

        // ── This session's attendance list (server history + this-instance marks) ─────────
        if (presentList.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (mode == "attendance") "Attendance — ${selectedSession?.name ?: "this session"} (${presentList.size})" else "Registered Students (${presentList.size})",
                                fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)
                            )
                            Surface(color = Color(0xFF2E7D32).copy(alpha = 0.12f), shape = CircleShape) {
                                Text(
                                    "● LIVE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF2E7D32),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                        HorizontalDivider()
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text("Name",   modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Mobile", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        presentList.asReversed().forEach { (n, m) ->
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(n, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                Text(m, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        } else if (mode == "attendance" && selectedSession != null) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        "No attendance marked yet for ${selectedSession?.name ?: "this session"}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}
// ─────────────────────────────────────────────────────────────────────────────
// STUDENT REGISTRATION SCREEN
// ─────────────────────────────────────────────────────────────────────────────
/**
 * One registration filed from THIS device during THIS visit to the screen.
 *
 * It exists because the server records neither of these facts. roster.json emits no created_at,
 * and `centre_roster` has no column naming the operator who wrote a row at all — so the time an
 * entry was filed, and who filed it, live nowhere but here until that changes server-side.
 */
private data class FiledRow(
    val name: String,
    val mobile: String,
    val at: String,
    val by: String,
    val batchId: String?,
    /** True when this mobile was already on the centre's roster before it was filed. The write
     *  still happens either way — AttendEase treats a duplicate as a profile/batch update, not
     *  an error — but the roll lists them apart, because "I just added this person" and "this
     *  person was already here" are different facts to an operator at a desk. */
    val alreadyMember: Boolean = false
)

// Which field's suggestion list is open. ONE slot, not a boolean per field: the three fields
// each held their own flag, so typing in Address and then tapping Occupation left both lists
// open and the form grew by two lists at once. Address and Occupation reuse FieldSuggestions'
// own field names so the MRU key and the open-slot key cannot drift apart.
private const val SUGGEST_MOBILE = "mobile"
private const val SUGGEST_REMARK = "remark"

@Composable
fun StudentRegistrationScreen(onNavigateBack: () -> Unit) {
    var mobile by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var occupation by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    var showSuggestionCard by remember { mutableStateOf(false) }
    var openSuggest by remember { mutableStateOf<String?>(null) }
    var expandedBatch by remember { mutableStateOf(false) }
    // Name of the last person filed, shown as a one-line confirmation for a couple of seconds.
    // This REPLACES the old full-screen "Student Enrolled!" page, which cost two taps and a
    // screen transition per person — unnoticeable once, and the dominant cost across a
    // thousand entries at a festival.
    var justAdded by remember { mutableStateOf<String?>(null) }
    // Monotonic, and it — not the name — keys the auto-hide effect. Two people with the same
    // name in a row produce the same `justAdded` string, so keying on the name left the timer
    // un-restarted and the banner could still be showing an earlier entry while the operator
    // files the next one. A confirmation naming the WRONG person is worse than none: it is the
    // double-entry signal this was added to prevent.
    var justAddedSeq by remember { mutableStateOf(0) }

    // What this device has filed since the screen opened, newest first. Pinned above the batch
    // roll so an entry is visible the INSTANT it is submitted, rather than after the ~5s mirror
    // refresh carries it back from the server.
    var sessionFiled by remember { mutableStateOf<List<FiledRow>>(emptyList()) }

    val nameFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // ── Batch: hold the ID, resolve the NAME lazily ──────────────────────────────────────────
    // The pick has always been persisted (prefs, scoped per centre+category). What made it look
    // like it reset was resolving it to a Batch object at init: on a cold or offline start
    // `AppDatabase.batches` is still empty, the lookup returned null, and the picker fell back to
    // "Auto" — silently sending the next person to the DEFAULT batch instead of the chosen one.
    // Keeping the raw id in state and resolving the label as batches arrive fixes both the
    // display and, more importantly, where the person actually lands.
    var selectedBatchId by remember { mutableStateOf(AppDatabase.uiSelection("reg.batchId")) }
    // The stored pick is namespaced per centre AND per category (see uiPrefKey). This state was
    // read ONCE at composition, so switching Youth<->Congregation left the OLD category's batch
    // id sitting in it and never restored the pick made for the new one.
    //
    // That was not only a display fault. `selectedBatch` resolves against the NEW category's
    // batch list, so it went null, `effectiveBatchId` went null with it, and the next person was
    // filed into the DEFAULT batch instead of the chosen one — silently, behind an HTTP 200.
    // Re-reading on the switch restores the right pick and stops the misfile.
    LaunchedEffect(AppDatabase.selectedFlaskCentreId, AppDatabase.selectedCategory) {
        selectedBatchId = AppDatabase.uiSelection("reg.batchId")
    }
    val batchesForPicker = AppDatabase.batches.filter {
        it.centreId == AppDatabase.selectedFlaskCentreId && it.category == AppDatabase.selectedCategory
    }
    val selectedBatch = batchesForPicker.firstOrNull { it.id == selectedBatchId }
    // Trust a stored id while the list is still loading; drop it only once the list HAS loaded
    // and the batch is genuinely gone (deleted, or renamed into another category). Sending a
    // batch id that no longer exists parks the registration server-side behind an HTTP 200 —
    // it looks like success and is invisible until someone goes looking.
    val effectiveBatchId = if (batchesForPicker.isNotEmpty() && selectedBatch == null) null else selectedBatchId

    // ── The batch whose roll is shown below the form ─────────────────────────────────────────
    // "Auto" is not a batch, but neither is it unknown: the server files an auto-registration
    // into this centre+category's DEFAULT batch (register_student in app.py — "no batch_id and
    // no active session — defaulted to batch"). Resolving it the same way here means the roll
    // shows the batch the next entry will actually land in, instead of nothing.
    val defaultBatch = batchesForPicker.firstOrNull { it.isDefault }
    val listedBatch = selectedBatch ?: defaultBatch
    val listedBatchId = listedBatch?.id

    // Real per-batch counts, replacing the "0 members" the picker showed against every batch.
    // Reading dataRevision here is also what SUBSCRIBES this composable to the 5s mirror
    // refresh: batchMemberCounts() is a plain function call and Compose cannot see through it.
    val rosterKnown = AppDatabase.hasRosterMirror()
    val memberCounts = remember(AppDatabase.dataRevision, rosterKnown) { AppDatabase.batchMemberCounts() }

    // This device's entries for the listed batch, and the rest of that batch from the shared
    // mirror with those removed — so a person filed here does not appear twice the moment the
    // mirror catches up with them. Computed HERE, not inside the LazyColumn: its content lambda
    // is a LazyListScope, not a composable scope, and remember{} is unavailable in it.
    // Keyed with AppDatabase.lastTenDigits, NOT a bare takeLast(10) on the raw string. That is
    // the rule mergeServerRosterInto already dedupes with, and it exists because stored mobiles
    // carry prefixes — a second, slightly different rule fails to match those and the person then
    // appears twice in the roll forever.
    val filedHere = sessionFiled.filter { listedBatchId != null && it.batchId == listedBatchId }
    // Split for display ONLY. Both halves are still in `filedHere`, so the header total and the
    // picker count are untouched by this: a returning member re-filed into the batch they are
    // already in is dropped from `batchRoll` by `filedKeys` and counted once here, and one moved
    // in from another batch is counted here and absent from this batch's mirror — +1, correctly.
    val filedNew = filedHere.filterNot { it.alreadyMember }
    val filedExisting = filedHere.filter { it.alreadyMember }
    val filedKeys = filedHere.map { AppDatabase.lastTenDigits(it.mobile) }.toSet()
    val batchRoll = remember(AppDatabase.dataRevision, listedBatchId, filedKeys) {
        if (listedBatchId == null) emptyList()
        else AppDatabase.batchRosterRows(listedBatchId)
            .filterNot { AppDatabase.lastTenDigits(it.mobile) in filedKeys }
    }

    // Entries this device has filed that the shared mirror has not carried back yet, per batch.
    // The picker and the roll header MUST both account for these, or the same batch shows two
    // different totals seconds apart: the roll counts a person the instant they are submitted,
    // the mirror only catches up on the next 5s tick. With this added to the picker the two agree
    // by construction — |M| + pending is exactly |M \ F| + |F|, which is the number of rows the
    // roll actually renders.
    val pendingByBatch = remember(AppDatabase.dataRevision, sessionFiled) {
        sessionFiled.filter { it.batchId != null }
            .groupBy { it.batchId!! }
            .mapValues { (bid, rows) ->
                val mirrored = AppDatabase.batchRosterRows(bid)
                    .mapTo(HashSet()) { AppDatabase.lastTenDigits(it.mobile) }
                rows.count { AppDatabase.lastTenDigits(it.mobile) !in mirrored }
            }
    }
    fun countFor(batchId: String) = (memberCounts[batchId] ?: 0) + (pendingByBatch[batchId] ?: 0)

    // ── Suggestion corpora ───────────────────────────────────────────────────────────────────
    // Built from data the app already holds: roster.json carries every member's address and
    // occupation, so "everything already in the system" needs no new endpoint and works offline.
    // Keyed on list size so this walks the roster only when the roster changes — NEVER per
    // keystroke, which at 1800+ rows on a cheap phone is felt as input lag.
    val allRegistrations = AppDatabase.registrations
    val curatedAddresses = AppDatabase.dropdownOptions
    // Keyed on the server-data revision and a COARSE size bucket, deliberately not on
    // `registrations.size`. Registering someone online appends a row locally, so keying on the
    // exact size rebuilt the whole corpus on every single submit — a full walk of 1800+ rows on
    // the main thread at the exact moment focus returns to Name, which is the input lag this
    // comment exists to prevent. The just-typed value is not lost by skipping the rebuild: the
    // MRU already carries it, ranked above everything else.
    val corpusKey = AppDatabase.dataRevision to (allRegistrations.size / 50)
    val addressCorpus = remember(corpusKey, curatedAddresses.size) {
        FieldSuggestions.buildCorpus(
            allRegistrations.map { it.address } +
                curatedAddresses.filter { it.context == "address" && it.active }.map { it.label }
        )
    }
    val occupationCorpus = remember(corpusKey) {
        FieldSuggestions.buildCorpus(allRegistrations.map { it.occupation })
    }
    // Bumped on submit so the just-typed value is re-read to the front of the recent list.
    var mruBump by remember { mutableStateOf(0) }
    val addressRecents = remember(mruBump) { FieldSuggestions.recents(FieldSuggestions.ADDRESS) }
    val occupationRecents = remember(mruBump) { FieldSuggestions.recents(FieldSuggestions.OCCUPATION) }

    // Capped at 4, down from 8. These lists are rendered INLINE beneath their field now rather
    // than floating over the form, so every extra row pushes the Submit button further down a
    // screen that already has the keyboard taking half of it.
    val addressSuggestions = FieldSuggestions.rank(address, addressCorpus, addressRecents, limit = 4)
    val occupationSuggestions =
        FieldSuggestions.rank(occupation, occupationCorpus, occupationRecents, limit = 4)
    // Remarks keep the admin-curated list they have always used — not part of this change.
    val remarkSuggestions = AppDatabase.dropdownOptions.filter {
        it.context == "remark" && it.active && (remark.isEmpty() || it.label.contains(remark, ignoreCase = true))
    }.take(4)

    val isMobileValid = mobile.length == 10 && mobile.all { it.isDigit() }

    // ── The number field's lookup ────────────────────────────────────────────────────────────
    // Over the WHOLE centre roster, not just the batch being registered into and not just the
    // people this device happens to have merged: rosterPeople() reads the mirrored roster.json,
    // which keeps the centre-level rows too (1,601 of Centre B's 1,804 people have no batch
    // at all, so a batch-scoped lookup would miss most of the centre).
    //
    // Keyed on dataRevision, which is also what SUBSCRIBES this to the mirror refresh — the
    // index itself is memoised on the mirror's content, so this costs one map read per change
    // and nothing per keystroke.
    val rosterPeople = remember(AppDatabase.dataRevision) { AppDatabase.rosterPeople() }
    // Below MOBILE_MIN_QUERY digits the list is hundreds long and worth nothing; at a full 10 the
    // "already registered" notice below says it better than a one-row list repeating what was
    // just typed.
    // ── The cross-centre lookup ──────────────────────────────────────────────────────────────
    // People arrive at a festival from every region, and the mirror above holds ONE centre. A
    // visitor already registered at Centre A is a total stranger to a Centre B device: no
    // suggestion, and no "already a member" notice, because they genuinely are not on this
    // centre's roster. The operator then re-types a name, address and occupation the system
    // already holds, and the centre gains a second profile for somebody it has on file.
    //
    // Online-only and uncached on purpose — see lookupRosterAcrossCentres. Offline this stays
    // empty and the screen behaves exactly as it did before the feature existed.
    var remoteHits by remember { mutableStateOf<List<RosterPerson>>(emptyList()) }
    LaunchedEffect(mobile, AppDatabase.selectedFlaskCentreId) {
        val q = mobile.filter { it.isDigit() }
        if (q.length < FieldSuggestions.CROSS_CENTRE_MIN_QUERY) {
            remoteHits = emptyList()
        } else {
            // Debounced. Typing the back half of a number would otherwise fire a scan of every
            // roster in the system per keystroke; an operator pauses far longer than this between
            // one person and the next, so in practice one query per number actually goes out.
            // Re-keying on `mobile` cancels the in-flight wait on the very next character, which
            // is what makes the debounce a debounce rather than a delay.
            delay(250)
            remoteHits = AppDatabase.lookupRosterAcrossCentres(q)
        }
    }

    // Local first, always. This centre's own people are the overwhelmingly likely answer and a
    // visitor must never push a member down the list. The two can't overlap: the server excludes
    // the centre we pass it, so nobody is offered twice.
    val mobileSuggestions =
        if (mobile.length in FieldSuggestions.MOBILE_MIN_QUERY..9)
            FieldSuggestions.rankPeople(mobile, rosterPeople) + remoteHits
        else emptyList()
    // The person this exact number belongs to, once all ten digits are in.
    val existingMember =
        if (isMobileValid) AppDatabase.rosterPersonByMobile(mobile) else null

    // Pull a known person's details into the form. Only ever FILLS — a blank field on the roster
    // must not wipe something the operator has already typed, which is the one way this could
    // destroy work rather than save it.
    val prefillFrom: (RosterPerson) -> Unit = { p ->
        val ten = p.digits.takeLast(10)
        if (ten.length == 10) mobile = ten
        if (p.name.isNotBlank() && p.name != "Unknown") name = p.name
        if (p.address.isNotBlank()) address = p.address
        if (p.occupation.isNotBlank()) occupation = p.occupation
        openSuggest = null
    }
    val isNameValid = name.isNotBlank()

    val formReady = isNameValid && isMobileValid
    // No separate `isSubmitting` flag any more. The write is fire-and-forget (addRegistration
    // returns immediately and parks its own failures in the offline queue), so a spinner would
    // never be visible. Clearing the fields FIRST is what actually debounces a double tap:
    // `formReady` goes false in the same frame, which the old boolean — set and cleared
    // synchronously around a non-blocking call — never did.
    val canSubmit = formReady

    // ONE submit path, not two. The inline tick and the button must do exactly the same thing —
    // duplicating the body is how the two drift and one of them quietly stops resetting.
    val submitRegistration: () -> Unit = {
        if (canSubmit) {
            // Snapshot before clearing; the async write reads these values.
            val n = name; val m = mobile; val a = address; val o = occupation; val r = remark
            // Whether this person was already on the roster is read BEFORE the fields are
            // cleared and before the write goes out, so it describes the state the operator was
            // actually looking at.
            val wasMember = AppDatabase.rosterPersonByMobile(m) != null
            name = ""; mobile = ""; address = ""; occupation = ""; remark = ""
            openSuggest = null
            showSuggestionCard = false
            AppDatabase.addRegistration(n, m, a, o, r, effectiveBatchId)
            // Learn from what was actually submitted, so the second person from a locality is
            // one tap instead of a retyped address.
            FieldSuggestions.recordUse(FieldSuggestions.ADDRESS, a)
            FieldSuggestions.recordUse(FieldSuggestions.OCCUPATION, o)
            mruBump++
            justAdded = n
            justAddedSeq++
            sessionFiled = listOf(
                FiledRow(
                    name = n,
                    mobile = m,
                    at = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                    by = AppDatabase.currentUser?.name?.trim()?.ifBlank { null } ?: "You",
                    batchId = listedBatchId,
                    alreadyMember = wasMember,
                )
            ) + sessionFiled
            // Straight back to Name with the keyboard still up — the next person can be typed
            // without touching the screen at all.
            // If Name has been scrolled out of the LazyColumn it is not composed and cannot
            // take focus. Swallowing that silently is not safe — focus would stay on whatever
            // field the operator submitted from, and the next person's name would be typed into
            // Remarks with nothing to show for it. Drop focus entirely instead: worst case is
            // one deliberate tap on Name.
            if (runCatching { nameFocus.requestFocus() }.isFailure) focusManager.clearFocus()
        }
    }

    LaunchedEffect(justAddedSeq) {
        if (justAddedSeq > 0) {
            delay(2200)
            justAdded = null
        }
    }

    // C7 cross-centre suggestion — trigger on any 10-digit mobile
    LaunchedEffect(mobile) {
        showSuggestionCard = if (isMobileValid) {
            AppDatabase.registrations.any { it.mobile == mobile && it.centreId != AppDatabase.selectedFlaskCentreId }
        } else false
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {

        // ── Slim control row ─────────────────────────────────────────────────────────────────
        // What used to be here was a tall primaryContainer card carrying a back button, the
        // title "New Student Enrollment" and two badges repeating the centre and category — all
        // of which the app's universal top bar already shows. It cost roughly a fifth of the
        // screen on every entry.
        //
        // The back arrow SURVIVES the deletion on purpose: the universal header's left icon
        // opens the navigation drawer, it is not a back affordance, and `onNavigateBack` is this
        // screen's only direct exit.
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp))
            }

            // The confirmation lives HERE, in the row's dead space, rather than in a banner of
            // its own above the form. A banner adds height when it appears and removes it when
            // it goes, so every entry nudged the whole form up and down — precisely when focus
            // has just returned to Name and the operator is already typing the next person.
            // Sharing this row costs no layout at all.
            Spacer(modifier = Modifier.width(4.dp))
            Box(modifier = Modifier.weight(1f)) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = justAdded != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            "${justAdded ?: ""} added",
                            fontSize = 12.sp,
                            color = Color(0xFF2E7D32),
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Batch pill. The cap is on the PILL, not on the label inside it, and the label
            // takes whatever is left after the two icons via weight(1f, fill = false). The old
            // `widthIn(max = 104.dp)` on the Text alone chopped "Festival Registration 2026" to
            // "Festival Re…" while the row it sits in still had room to spare. A weight and a
            // fixed cap on the same Text fight each other, so the cap moved out here rather than
            // being kept alongside.
            Box {
                val pillSelected = selectedBatchId != null
                Surface(
                    onClick = { expandedBatch = true },
                    shape = RoundedCornerShape(50),
                    color = if (pillSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (pillSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.height(32.dp).widthIn(max = 176.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = selectedBatch?.name
                                ?: if (pillSelected) "Batch" else "Auto",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            // fill = false so a SHORT name still gives a short pill; the two
                            // icons are laid out first either way and can never be pushed out.
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
                DropdownMenu(
                    expanded = expandedBatch,
                    onDismissRequest = { expandedBatch = false },
                    // Bound the menu. Its rows use fillMaxWidth(), which inside a popup measures
                    // against the WINDOW — so one long batch name stretched the whole menu across
                    // the screen and shoved the "N members" label around with it. A floor as well
                    // as a ceiling, so short names still leave the count somewhere to sit.
                    modifier = Modifier.widthIn(min = 232.dp, max = 320.dp)
                ) {
                    DropdownMenuItem(
                        // Auto names the batch it resolves to and carries its count, like every
                        // other row. It is not an abstract rule to the operator — it is the
                        // default batch, and they should be able to see what they are joining.
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (defaultBatch != null) "Auto — ${defaultBatch.name}"
                                    else "Auto — apply default batch rules",
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (rosterKnown && defaultBatch != null) {
                                    Text(
                                        "${countFor(defaultBatch.id)} members",
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        modifier = Modifier.padding(start = 10.dp)
                                    )
                                }
                            }
                        },
                        leadingIcon = { Icon(Icons.Default.AutoMode, contentDescription = null) },
                        onClick = {
                            selectedBatchId = null
                            AppDatabase.setUiSelection("reg.batchId", null)
                            expandedBatch = false
                        }
                    )
                    HorizontalDivider()
                    batchesForPicker.forEach { b ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // weight + ellipsis, so a long batch name takes the room it
                                    // has and stops — it used to be an unbounded Text that pushed
                                    // the count off the row.
                                    Text(
                                        b.name,
                                        maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    // Was "${b.memberCount} members", which was ALWAYS "0 members"
                                    // for every batch in every centre: Flask's Batch.to_dict()
                                    // has never emitted the member_count field the app parses, so
                                    // optInt("member_count", 0) could only ever read zero — while
                                    // the batches behind it hold 698 and 434 people. Counted from
                                    // the mirrored roster now, and omitted entirely when no mirror
                                    // has arrived: an absent number is honest, a wrong one is not.
                                    if (rosterKnown) {
                                        Text(
                                            "${countFor(b.id)} members",
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            modifier = Modifier.padding(start = 10.dp)
                                        )
                                    }
                                }
                            },
                            trailingIcon = if (b.isDefault) ({ StatusBadge(text = "Default", status = "active") }) else null,
                            onClick = {
                                selectedBatchId = b.id
                                AppDatabase.setUiSelection("reg.batchId", b.id)
                                expandedBatch = false
                            }
                        )
                    }
                }
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {

            // C7 Cross-centre profile suggestion
            item {
                AnimatedVisibility(visible = showSuggestionCard) {
                    val crossReg = AppDatabase.registrations.find { it.mobile == mobile && it.centreId != AppDatabase.selectedFlaskCentreId }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                        shape = RoundedCornerShape(12.dp),
                        border = CardDefaults.outlinedCardBorder()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CloudQueue, contentDescription = null, tint = Color(0xFF2E7D32))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Cross-Centre Profile Found", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            val fromCentre = AppDatabase.centres.find { it.id == crossReg?.centreId }?.name ?: "another centre"
                            Text(
                                "${crossReg?.name ?: "Existing student"} is registered at $fromCentre. Prefill from their profile?",
                                fontSize = 13.sp, color = Color(0xFF1B5E20)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        crossReg?.let {
                                            name = it.name
                                            address = it.address
                                            occupation = it.occupation
                                            remark = "Transferred from $fromCentre."
                                        }
                                        showSuggestionCard = false
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                                ) { Text("Prefill Profile") }
                                OutlinedButton(
                                    onClick = { showSuggestionCard = false },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF2E7D32))
                                ) { Text("Ignore") }
                            }
                        }
                    }
                }
            }

            // ── 1. Name ──────────────────────────────────────────────────────────────────────
            // Name leads the form now. It is what the operator asks for first, and it is where
            // focus returns after each submit.
            item {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Full Name",
                    placeholder = "Student's full name",
                    leadingIcon = Icons.Default.Person,
                    isError = name.isNotEmpty() && !isNameValid,
                    errorMessage = if (name.isNotEmpty() && !isNameValid) "Name cannot be empty" else null,
                    textFieldModifier = Modifier.focusRequester(nameFocus)
                )
            }

            // ── 2. Mobile + the inline submit tick ───────────────────────────────────────────
            // The tick stays on the mobile field: with Name above it, the form is complete
            // exactly when this field is, so the tick appears right where the operator is
            // already looking and one tap files the entry.
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                AppTextField(
                    modifier = Modifier.weight(1f),
                    value = mobile,
                    // Normalise, don't measure-then-strip. The old guard was
                    // `if (it.length <= 10) mobile = it.filter { isDigit }`, which counted the RAW
                    // characters, so "+91 98350 00001" and even "9835000001 " (one trailing space
                    // from WhatsApp) were discarded whole and the field stayed empty. null means
                    // "refuse this edit" — that is what preserves the 10-digit cap while typing.
                    onValueChange = {
                        PhoneNumbers.normalizeMobileInput(it)?.let { n ->
                            mobile = n
                            openSuggest =
                                if (n.length >= FieldSuggestions.MOBILE_MIN_QUERY) SUGGEST_MOBILE
                                else null
                        }
                    },
                    label = "Mobile Number (10 digits)",
                    placeholder = "Enter 10-digit mobile",
                    leadingIcon = Icons.Default.Phone,
                    keyboardType = KeyboardType.Phone,
                    isError = mobile.isNotEmpty() && !isMobileValid,
                    errorMessage = if (mobile.isNotEmpty() && !isMobileValid) "Must be exactly 10 digits" else null,
                    trailingIcon = if (isMobileValid) ({ Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32)) }) else null
                )

                AnimatedVisibility(
                    visible = formReady,
                    enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
                    exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start)
                ) {
                    Surface(
                        onClick = submitRegistration,
                        enabled = canSubmit,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .height(54.dp)
                            .width(52.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shadowElevation = 2.dp
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Register student",
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }
                }

                // Number lookup across the whole centre roster. The operator types three or more
                // digits and picks the person; tapping fills the rest of the form from what the
                // roster already knows about them, which is the difference between re-typing a
                // returning member's details and confirming them.
                InlineSuggestions(visible = openSuggest == SUGGEST_MOBILE && mobileSuggestions.isNotEmpty()) {
                    mobileSuggestions.forEach { p ->
                        SuggestionRow(
                            text = p.name,
                            // A cross-centre hit says WHERE, because that is the one thing the
                            // operator cannot infer and the one thing that changes what this
                            // registration means — a visitor, not a returning member.
                            secondary = p.centreName
                                ?.let { "${p.digits.takeLast(10)} · $it" }
                                ?: p.digits.takeLast(10),
                            onClick = { prefillFrom(p) }
                        )
                    }
                }

                // All ten digits are in and this number is already on the roster. Said here, at
                // the field, rather than left for the operator to discover after submitting.
                // The Register button stays enabled on purpose: AttendEase treats a repeat
                // registration as a profile and batch update, not an error, and moving a member
                // into tonight's batch is a real thing an operator needs to do.
                if (existingMember != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            "Already a member — ${existingMember.name}" +
                                (existingMember.batchName?.let { " · $it" } ?: ""),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { prefillFrom(existingMember) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) { Text("Fill details", fontSize = 11.sp) }
                    }
                }

                // Ten digits in, NOT on this centre's roster, but known elsewhere in the system.
                // This is the visitor case, and it is the whole reason the cross-centre lookup
                // exists: without this line the operator sees nothing at exactly the moment they
                // finish typing, because the suggestion list stops at nine digits and
                // existingMember is null for somebody who belongs to another centre.
                //
                // Only when existingMember is null. A person on both rosters is a member here
                // first, and saying "registered at Centre A" over their own membership would
                // be true but misleading.
                val visitor = if (existingMember == null) remoteHits.firstOrNull() else null
                if (isMobileValid && visitor != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            "Registered at ${visitor.centreName ?: "another centre"} — ${visitor.name}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { prefillFrom(visitor) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) { Text("Fill details", fontSize = 11.sp) }
                    }
                }
                }
            }

            // ── 3. Address ───────────────────────────────────────────────────────────────────
            // Suggestions are the union of what this operator typed recently, what the whole
            // system already holds, and the admin's curated list — see FieldSuggestions. The
            // arrow opens the hot list with nothing typed, which is the "pick, don't type" path.
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    AppTextField(
                        value = address,
                        onValueChange = {
                            address = it
                            openSuggest = if (it.isNotEmpty()) FieldSuggestions.ADDRESS else null
                        },
                        label = "Permanent Address",
                        placeholder = "Type or pick from suggestions",
                        leadingIcon = Icons.Default.Home,
                        trailingIcon = {
                            IconButton(onClick = {
                                openSuggest =
                                    if (openSuggest == FieldSuggestions.ADDRESS) null
                                    else FieldSuggestions.ADDRESS
                            }) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                        }
                    )
                    InlineSuggestions(
                        visible = openSuggest == FieldSuggestions.ADDRESS && addressSuggestions.isNotEmpty()
                    ) {
                        addressSuggestions.forEach { label ->
                            SuggestionRow(text = label, onClick = { address = label; openSuggest = null })
                        }
                    }
                }
            }

            // ── 4. Occupation ────────────────────────────────────────────────────────────────
            // New: this field had no suggestions at all and no curated list behind it, so every
            // "Student" was typed from scratch. Same union ranking as Address, sourced purely
            // from occupations already recorded across the system.
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    AppTextField(
                        value = occupation,
                        onValueChange = {
                            occupation = it
                            openSuggest = if (it.isNotEmpty()) FieldSuggestions.OCCUPATION else null
                        },
                        label = "Occupation / Study Level",
                        placeholder = "Type or pick from suggestions",
                        leadingIcon = Icons.Default.Work,
                        trailingIcon = {
                            IconButton(onClick = {
                                openSuggest =
                                    if (openSuggest == FieldSuggestions.OCCUPATION) null
                                    else FieldSuggestions.OCCUPATION
                            }) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                        }
                    )
                    InlineSuggestions(
                        visible = openSuggest == FieldSuggestions.OCCUPATION && occupationSuggestions.isNotEmpty()
                    ) {
                        occupationSuggestions.forEach { label ->
                            SuggestionRow(text = label, onClick = { occupation = label; openSuggest = null })
                        }
                    }
                }
            }

            // ── 5. Remarks ───────────────────────────────────────────────────────────────────
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    AppTextField(
                        value = remark,
                        onValueChange = {
                            remark = it
                            openSuggest = if (it.isNotEmpty()) SUGGEST_REMARK else null
                        },
                        label = "Initial Remarks",
                        placeholder = "Type or pick from suggestions",
                        leadingIcon = Icons.Default.Edit,
                        trailingIcon = {
                            IconButton(onClick = {
                                openSuggest = if (openSuggest == SUGGEST_REMARK) null else SUGGEST_REMARK
                            }) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                        }
                    )
                    InlineSuggestions(
                        visible = openSuggest == SUGGEST_REMARK && remarkSuggestions.isNotEmpty()
                    ) {
                        remarkSuggestions.forEach { opt ->
                            SuggestionRow(text = opt.label, onClick = { remark = opt.label; openSuggest = null })
                        }
                    }
                }
            }

            // Submit button — kept alongside the inline tick so nobody's existing habit breaks.
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = submitRegistration,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = canSubmit
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Register Student", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── Live roll of the batch being registered into ──────────────────────────────────
            // The total is the SHARED one. It is counted from the mirrored roster.json, which the
            // 5s connectivity tick re-pulls whenever the centre's checksum moves — and the
            // checksum fingerprints the roster's row count and newest updated_at, so any operator
            // on any phone filing anyone moves it. A person registered on another device shows up
            // here within a few seconds without this screen doing anything special.
            //
            // Not derived from AppDatabase.registrations, deliberately: the tick refreshes the
            // mirror but registrations is only re-merged by a full loadDataFromBackend pass, so a
            // total taken from it would sit still all evening while the shared truth moved.
            //
            // This device's own entries are pinned on top with the operator's name and the time.
            // Neither is available for anybody else's rows TODAY — roster.json emits no
            // created_at, and centre_roster has no column recording who wrote a row at all.
            // Showing the half that genuinely exists is better than showing nothing, and far
            // better than inventing a name for the other half. The parser already reads both
            // fields if the server starts sending them, so that half needs no app change.
            if (listedBatch != null) {
                item {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Groups,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            listedBatch.name,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (rosterKnown) "${batchRoll.size + filedHere.size} registered"
                            else "${filedHere.size} filed here",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (!rosterKnown) {
                        // Says why the list is short rather than letting it read as an empty
                        // batch — the same distinction the picker's absent count preserves.
                        Text(
                            "Full list appears once the roster reaches this device.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                }

                items(filedNew) { row ->
                    BatchRollRow(name = row.name, mobile = row.mobile, trailing = "${row.by} · ${row.at}")
                }
                // People filed from this device who were ALREADY on the centre's roster. They
                // are still registered — AttendEase updates the existing person rather than
                // refusing — but an operator needs to see at a glance which of tonight's entries
                // were new faces and which were members coming back, and a single undifferentiated
                // list hides exactly that.
                if (filedExisting.isNotEmpty()) {
                    item {
                        Text(
                            "Already a member · ${filedExisting.size}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                        )
                    }
                    items(filedExisting) { row ->
                        BatchRollRow(
                            name = row.name,
                            mobile = row.mobile,
                            trailing = "${row.by} · ${row.at}"
                        )
                    }
                }
                items(batchRoll) { row ->
                    // registeredBy is null against every row today and the slot simply stays
                    // empty. The parser reads the field if roster.json ever carries it, so the
                    // server half of "who filed this row" lands WITHOUT another app release.
                    BatchRollRow(name = row.name, mobile = row.mobile, trailing = row.registeredBy)
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

/**
 * Suggestions rendered INLINE, directly beneath the field they belong to.
 *
 * NOT a DropdownMenu, and that is not a style preference — it is the only fix available.
 * DropdownMenu offers no placement control: its position provider flips the popup ABOVE the
 * anchor whenever there is too little room below it, and on this form the keyboard is up the
 * entire time, so "too little room below" is the normal state. The list then covered the field
 * being typed into.
 *
 * The flicker was separate and also structural. `expanded` was `someFlag && suggestions.isNotEmpty()`,
 * so every time the list crossed empty <-> non-empty mid-word the popup ran a full exit and a
 * full enter animation. Inline has neither problem: it is always below, and it grows and shrinks
 * in place with no animation at all.
 *
 * Deliberately NOT scrollable. A nested vertical scroller inside the form's LazyColumn would
 * fight it for drags; the lists are capped at four entries instead, which is what a type-ahead
 * is worth reading anyway.
 */
@Composable
private fun InlineSuggestions(
    visible: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/** One suggestion. [secondary] carries the mobile number on the number field's rows, so the
 *  operator can tell two people with the same name apart before tapping one of them. */
@Composable
private fun SuggestionRow(text: String, secondary: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (secondary != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                secondary,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/**
 * One line of the registration screen's batch roll.
 *
 * Deliberately a plain Row rather than a Card: a batch holds up to 698 people, and this list sits
 * under a form the operator is typing into a thousand times an evening. Every dp of chrome per
 * row is paid for on every scroll.
 */
@Composable
private fun BatchRollRow(name: String, mobile: String, trailing: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            fontSize = 13.sp,
            fontWeight = if (trailing != null) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(mobile, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                trailing,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 116.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// STUDENT DETAIL SCREEN — collapsible sections
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun StudentDetailScreen(studentId: String, onNavigateBack: () -> Unit) {
    val student = AppDatabase.registrations.find { it.id == studentId } ?: return
    val callAttempts = AppDatabase.callAttempts.filter { it.registrationId == studentId }
    val auditHistory = AppDatabase.auditLogs.filter { it.registrationId == studentId }
    var showBatchDialog by remember { mutableStateOf(false) }
    var profileExpanded by remember { mutableStateOf(true) }
    var batchExpanded by remember { mutableStateOf(true) }
    var callsExpanded by remember { mutableStateOf(true) }
    var auditExpanded by remember { mutableStateOf(false) }

    @Composable
    fun SectionHeader(title: String, expanded: Boolean, onToggle: () -> Unit, badge: String? = null, action: @Composable (() -> Unit)? = null) {
        val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "section_rotate")
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                if (badge != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusBadge(text = badge, status = "active")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                action?.invoke()
                Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp).rotate(rotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, contentDescription = null) }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(student.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                StatusBadge(text = student.status, status = student.status)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Profile card
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SectionHeader("Profile Demographics", profileExpanded, { profileExpanded = !profileExpanded })
                        AnimatedVisibility(visible = profileExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                                HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
                                listOf(
                                    Icons.Default.Phone to "Mobile" to student.mobile,
                                    Icons.Default.Home to "Address" to student.address,
                                    Icons.Default.Work to "Occupation" to student.occupation,
                                    Icons.Default.Edit to "Initial Remarks" to student.remark,
                                    Icons.Default.Category to "Category" to student.category,
                                    Icons.Default.LocationOn to "Centre" to (AppDatabase.centres.find { it.id == student.centreId }?.name ?: student.centreId)
                                ).forEach { (iconLabel, value) ->
                                    val (icon, label) = iconLabel
                                    Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                                        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Batch card
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SectionHeader(
                            title = "Assigned Batches",
                            expanded = batchExpanded,
                            onToggle = { batchExpanded = !batchExpanded },
                            badge = "${student.batchIds.size}",
                            action = { TextButton(onClick = { showBatchDialog = true }) { Text("Edit") } }
                        )
                        AnimatedVisibility(visible = batchExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                                HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
                                if (student.batchIds.isEmpty()) {
                                    Text("No batch assigned.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    student.batchIds.forEach { bId ->
                                        val batch = AppDatabase.batches.find { it.id == bId }
                                        Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(batch?.name ?: bId, fontWeight = FontWeight.SemiBold)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            if (batch?.isDefault == true) StatusBadge(text = "Default", status = "active")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Calls card
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SectionHeader("Caller Outreach Log", callsExpanded, { callsExpanded = !callsExpanded }, badge = "${callAttempts.size}")
                        AnimatedVisibility(visible = callsExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                                HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
                                if (callAttempts.isEmpty()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.PhoneDisabled, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("No calling outreach yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                } else {
                                    callAttempts.reversed().forEach { ca ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                        ) {
                                            Column(modifier = Modifier.padding(10.dp)) {
                                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                                    Text(ca.dateStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    StatusBadge(text = ca.outcome.take(16), status = "active")
                                                }
                                                Text("By ${ca.callerName}", style = MaterialTheme.typography.labelSmall)
                                                if (ca.notes.isNotBlank()) Text(ca.notes, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Audit trail card (C9) — collapsed by default (rarely needed)
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SectionHeader("Batch Audit Trail (C9)", auditExpanded, { auditExpanded = !auditExpanded }, badge = "${auditHistory.size}")
                        AnimatedVisibility(visible = auditExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                                HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
                                if (auditHistory.isEmpty()) {
                                    Text("No batch mutations logged.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    auditHistory.reversed().forEach { log ->
                                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                            Text("${log.timestamp} — ${log.actor}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            Text(log.action, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showBatchDialog) {
        BatchMembershipDialog(student = student, onDismiss = { showBatchDialog = false })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BATCH MEMBERSHIP DIALOG — with member counts, R3 explanations
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun BatchMembershipDialog(student: Registration, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var selectedIds by remember { mutableStateOf(student.batchIds) }
    val availableBatches = AppDatabase.batches.filter { it.centreId == AppDatabase.selectedFlaskCentreId && it.category == AppDatabase.selectedCategory }
    val hasDefault = selectedIds.any { id -> availableBatches.find { it.id == id }?.isDefault == true }
    val hasNonDefault = selectedIds.any { id -> availableBatches.find { it.id == id }?.isDefault == false }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Edit Batch Memberships", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(4.dp))

                // R3 rule hint card
                if (hasDefault && hasNonDefault) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFEF6C00), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("R3: Cannot mix default and non-default batches.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFEF6C00))
                        }
                    }
                } else {
                    Text("R3: Default and non-default batches are mutually exclusive.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(modifier = Modifier.height(12.dp))

                availableBatches.forEach { b ->
                    val isSelected = selectedIds.contains(b.id)
                    val wouldConflict = isSelected.not() && (
                        (b.isDefault && hasNonDefault) || (!b.isDefault && hasDefault)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !wouldConflict) {
                                selectedIds = if (isSelected) {
                                    selectedIds - b.id
                                } else {
                                    if (b.isDefault) {
                                        // Selecting a default clears all non-default (R3)
                                        (selectedIds.filter { id -> availableBatches.find { it.id == id }?.isDefault == true }) + b.id
                                    } else {
                                        // Selecting non-default clears all default (R3)
                                        (selectedIds.filter { id -> availableBatches.find { it.id == id }?.isDefault == false }) + b.id
                                    }
                                }
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = null,
                            enabled = !wouldConflict
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                b.name,
                                color = if (wouldConflict) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (b.isDefault) FontWeight.Bold else FontWeight.Normal
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (b.isDefault) Text("Default (R3 exclusive)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                if (wouldConflict) Text("• Conflicts with selection", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                            }
                        }
                        Text("${b.memberCount}m", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        scope.launch {
                            AppDatabase.setRegistrationBatches(student.id, selectedIds)
                            onDismiss()
                        }
                    }) {
                        Text("Save Changes")
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CENTRE CHANGE REQUEST SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun CentreChangeRequestScreen(onNavigateBack: () -> Unit) {
    var selectedTarget by remember { mutableStateOf(AppDatabase.centres.firstOrNull { it.id != AppDatabase.selectedCentreId } ?: AppDatabase.centres.first()) }
    var reasonText by remember { mutableStateOf("") }
    var activeProposalStatus by remember { mutableStateOf("none") }
    val currentCentreName = AppDatabase.centres.find { it.id == AppDatabase.selectedCentreId }?.name ?: "Current"

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, contentDescription = null) }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text("Centre Transfer Request", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Currently: $currentCentreName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        AnimatedVisibility(visible = activeProposalStatus == "requested", enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.HourglassTop, contentDescription = null, tint = Color(0xFFEF6C00))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Transfer Pending Review", fontWeight = FontWeight.Bold, color = Color(0xFFEF6C00))
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Request to move from $currentCentreName → ${selectedTarget.name} is under review. An admin will process it shortly.",
                        fontSize = 14.sp, color = Color(0xFF7F3B00)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    TextButton(onClick = { activeProposalStatus = "none"; reasonText = "" }) {
                        Text("Withdraw Request", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        AnimatedVisibility(visible = activeProposalStatus == "none", enter = fadeIn() + expandVertically()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Submit Transfer Request", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Requests are reviewed by a coordinator. Only submit if your study centre has changed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Target Centre", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    var expandedC by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { expandedC = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(selectedTarget.name)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                        }
                        DropdownMenu(expanded = expandedC, onDismissRequest = { expandedC = false }) {
                            AppDatabase.centres.filter { it.id != AppDatabase.selectedCentreId }.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text(c.name) },
                                    trailingIcon = if (c.status == "inactive") ({ StatusBadge(text = "Inactive", status = "inactive") }) else null,
                                    onClick = { selectedTarget = c; expandedC = false },
                                    enabled = c.status != "inactive"
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    AppTextField(
                        value = reasonText,
                        onValueChange = { reasonText = it },
                        label = "Reason for Transfer",
                        placeholder = "Explain why you need to switch centres…",
                        maxLines = 4,
                        singleLine = false
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { activeProposalStatus = "requested" },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = reasonText.length >= 10
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Submit Transfer Request")
                    }
                    if (reasonText.isNotEmpty() && reasonText.length < 10) {
                        Text("Please provide at least 10 characters.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ARCHIVED DATA SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ArchivedDataScreen(onViewStudentDetails: (String) -> Unit) {
    // From the calling server: everyone removed, archived or marked invalid, with the day they
    // left and why. The old version filtered a local list by a status nothing ever set, so it
    // was always empty.
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("all") }
    var items by remember { mutableStateOf<List<AppDatabase.ArchiveEntry>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var expandedId by remember { mutableStateOf<Int?>(null) }
    val category = AppDatabase.selectedCategory
    val centreId = AppDatabase.selectedCentreId

    LaunchedEffect(category, centreId, statusFilter, searchQuery, reloadKey) {
        delay(if (searchQuery.isBlank()) 0 else 350) // debounce typing
        loading = true
        val r = AppDatabase.fetchArchive(category, searchQuery, statusFilter)
        failed = r == null
        if (r != null) items = r
        loading = false
    }

    val isoParser = remember {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    }
    val dayKey = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val dayLabel = remember { SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()) }
    val timeLabel = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    fun parse(iso: String): Date? = try { isoParser.parse(iso.take(19)) } catch (e: Exception) { null }

    val today = dayKey.format(Date())
    val yesterday = dayKey.format(Date(System.currentTimeMillis() - 86_400_000L))
    val groups = remember(items) {
        (items ?: emptyList()).groupBy { e -> parse(e.removedAt)?.let { dayKey.format(it) } ?: "" }
            .toList()
            .sortedByDescending { it.first }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Archive", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${items?.size ?: 0} people out of calling · $category · ${AppDatabase.centres.find { it.id == centreId }?.name ?: ""}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { reloadKey++ }, enabled = !loading) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
        }
        Spacer(modifier = Modifier.height(12.dp))

        AppTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = "Search name, mobile or reason",
            leadingIcon = Icons.Default.Search,
            trailingIcon = if (searchQuery.isNotEmpty()) ({
                IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear") }
            }) else null
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf("all" to "All", "archived" to "Not interested", "invalid" to "Wrong number", "removed" to "Removed").forEach { (key, label) ->
                FilterChip(selected = statusFilter == key, onClick = { statusFilter = key }, label = { Text(label, fontSize = 12.sp) })
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) else Spacer(modifier = Modifier.height(4.dp))

        when {
            failed && items == null -> Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't load the archive.", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { reloadKey++ }) { Text("Try again") }
                }
            }
            !loading && groups.isEmpty() -> Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Nobody here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (searchQuery.isNotEmpty()) TextButton(onClick = { searchQuery = "" }) { Text("Clear search") }
                }
            }
            else -> LazyColumn(modifier = Modifier.weight(1f)) {
                groups.forEach { (day, entries) ->
                    item(key = "h_$day") {
                        val title = when (day) {
                            today -> "Today"
                            yesterday -> "Yesterday"
                            "" -> "Date unknown"
                            else -> try { dayLabel.format(dayKey.parse(day)!!) } catch (e: Exception) { day }
                        }
                        Text("$title · ${entries.size}", fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    }
                    items(entries, key = { "e_${it.id}" }) { e ->
                        val expanded = expandedId == e.id
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { expandedId = if (expanded) null else e.id },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(e.name.ifBlank { "(no name)" }, fontWeight = FontWeight.Bold)
                                        Text(e.mobile, style = MaterialTheme.typography.bodySmall)
                                    }
                                    val badge = when (e.status) {
                                        "invalid" -> "Wrong number"
                                        "removed" -> "Removed"
                                        else -> "Archived"
                                    }
                                    StatusBadge(text = badge, status = "archived")
                                }
                                if (e.reason.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(e.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                AnimatedVisibility(visible = expanded) {
                                    Column(modifier = Modifier.padding(top = 6.dp)) {
                                        parse(e.removedAt)?.let { Text("Out since ${timeLabel.format(it)}", style = MaterialTheme.typography.labelSmall) }
                                        if (e.lastResponse.isNotBlank()) Text("Last response: ${e.lastResponse}", style = MaterialTheme.typography.labelSmall)
                                        if (e.lastCallDate.isNotBlank()) Text("Last call: ${e.lastCallDate.take(10)}", style = MaterialTheme.typography.labelSmall)
                                        if (e.address.isNotBlank()) Text("Address: ${e.address}", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
