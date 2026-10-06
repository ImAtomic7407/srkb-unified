package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.*
import com.example.ui.components.*
import kotlinx.coroutines.launch

/**
 * Open a WhatsApp chat with this person.
 *
 * Every WhatsApp button on the caller screens goes through here. They each used to build the link
 * inline as "https://wa.me/${mobile}", which hands wa.me a bare 10-digit number with no country
 * code — so WhatsApp read the start of the subscriber number as one and reported either a wrong
 * country or an invalid one. PhoneNumbers.forWhatsApp supplies the country code the link format
 * requires; see that file for the full account.
 *
 * A number too short to be real gets an explicit message instead of a WhatsApp error blaming the
 * country code, so the caller can see it is the record that is wrong and report it.
 */
private fun openWhatsApp(context: Context, mobile: String?, message: String? = null) {
    val number = PhoneNumbers.forWhatsApp(mobile)
    if (number == null) {
        Toast.makeText(
            context,
            "Can't message ${mobile.orEmpty().ifBlank { "this person" }} — the number on record is incomplete",
            Toast.LENGTH_LONG,
        ).show()
        return
    }
    // The admin's message for this calling session, already rendered per person by the server —
    // the app never substitutes placeholders itself, so there is one implementation of the
    // template language and it works identically offline (the queue cache stores the raw JSON).
    //
    // Uri.encode, not string interpolation: a message can carry &, #, newlines and Devanagari, and
    // an unencoded # would truncate the whole text at the URL fragment.
    val url = if (message.isNullOrBlank()) {
        "https://wa.me/$number"
    } else {
        "https://wa.me/$number?text=${Uri.encode(message)}"
    }
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

// ─────────────────────────────────────────────────────────────────────────────
// OUTCOME TWO-STEP — shared by CallingQueue and StudentCallDetail
//
// Replaces OutcomeAccordion, which asked for a response first and then expanded a free-text
// "Notes (optional)" field under it. Two things were wrong with that. Free text is what breaks the
// system — nothing downstream can interpret it, so callers typed "nr" / "so" / "oop" into notes
// where those facts died. And it made the caller read a list mixing "they answered and said no"
// with "the phone was off", which is why the client reported responses carrying two meanings.
//
// Now: "Did they pick the call?" first, then only the responses that can follow from that answer.
// Which responses appear on each branch is the admin's `answered_scope`, not anything inferred here.
//
// TAP BUDGET — the client's actual complaint was that saving took longer than the call. Tapping a
// response SAVES IT; there is no separate Save button, so the common case is exactly two taps.
// That is also why the optional remark sits ABOVE the responses: it has to be chosen before the tap
// that commits, and a caller who does not want one never looks at it.
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun OutcomeTwoStep(
    // `dropdownId` identifies WHICH response was tapped, independently of what it is called. It
    // travels with the outcome so that renaming the response — including while this very outcome is
    // still parked in the outbox — cannot orphan it. The label is still sent, for old servers and
    // for the human reading history back.
    onSave: (outcome: String, remark: String?, answered: Boolean, dropdownId: String?) -> Unit,
    saveEnabled: Boolean,
    // Changing this resets the picker — a fresh person must not inherit the previous one's answer.
    resetKey: Any? = Unit,
) {
    var answered by remember(resetKey) { mutableStateOf<Boolean?>(null) }
    var remark by remember(resetKey) { mutableStateOf("") }

    val allOutcomes = AppDatabase.dropdownOptions.filter { it.context == "calling_outcome" && it.active }
    val shown = allOutcomes.filter { ResponseMeaning.showsUnder(it.answeredScope, answered) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

        // ── Step 1 ────────────────────────────────────────────────────────────
        Text("Did they pick the call?", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            PickUpButton(
                text = "YES",
                selected = answered == true,
                selectedColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            ) { answered = true }
            PickUpButton(
                text = "NO",
                selected = answered == false,
                selectedColor = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            ) { answered = false }
        }

        // ── Step 2 ────────────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = answered != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

                // Remark — free text, the caller's own words. Purely a supplementary note: the
                // structured outcome below is still what the system reads, so this can never lose
                // meaning downstream the way pre-redesign free-text notes did.
                OutlinedTextField(
                    value = remark,
                    onValueChange = { remark = it },
                    label = { Text("Remark (optional)", fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Notes, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (shown.isEmpty()) {
                    // Only reachable if an admin has scoped every response to the other branch.
                    // Say so plainly rather than showing an empty area the caller cannot act on.
                    Text(
                        "No response is set up for this answer. Ask your centre admin to add one in " +
                            "Directory → Responses.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        if (answered == true) "What did they say?" else "What happened?",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        shown.forEach { opt ->
                            ResponseChip(
                                label = opt.label,
                                sentiment = opt.sentiment,
                                enabled = saveEnabled,
                            ) { onSave(opt.label, remark.trim().ifBlank { null }, answered == true, opt.id) }
                        }
                    }
                }
            }
        }
    }
}

/** One half of the pick-up question. Deliberately large — it is tapped on every single call. */
@Composable
private fun PickUpButton(
    text: String,
    selected: Boolean,
    selectedColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val container = if (selected) selectedColor else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val content = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

/**
 * One response. Tapping it saves.
 *
 * The tint comes from the declared sentiment, so a centre that renames "Not interested" to
 * something in Hindi keeps the same colour — the words are never read.
 */
@Composable
private fun ResponseChip(
    label: String,
    sentiment: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = when (sentiment) {
        ResponseMeaning.POSITIVE -> Color(0xFF2E7D32)
        ResponseMeaning.NEGATIVE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = tint.copy(alpha = 0.10f),
        contentColor = tint,
        border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CALLER DASHBOARD
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun CallerDashboard(
    onNavigateToQueue: () -> Unit,
    onNavigateToFollowups: () -> Unit,
    onNavigateToMyAttendance: () -> Unit,
    onNavigateToBatchCalling: () -> Unit
) {
    val user = AppDatabase.currentUser ?: return
    var stats by remember { mutableStateOf(com.example.data.DashboardStats()) }
    val scope = rememberCoroutineScope()
    // Refresh the counters every time the dashboard becomes visible (ON_RESUME), not just once on
    // first mount — otherwise "In My Queue"/"Followups"/"Calls Logged" stay frozen at their initial
    // values after the caller logs outcomes and navigates back here.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { stats = AppDatabase.fetchDashboardStats() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // No local fallback. This used to drop back to counting registrations whose
    // `callerAssignedId` matched — but that field is a stale leftover: it is written when someone
    // is assigned and NEVER cleared when the assignment ends or the calling session finishes.
    // Measured 2026-08-13 at Centre A: `calling_assignments` held 0 rows of any status while 147
    // registrations still carried a caller id from a session completed weeks earlier. The fallback
    // fires precisely when the authoritative count is 0 — i.e. exactly when the stale field lies —
    // so it showed callers a queue of work that does not exist. A truthful 0 beats a confident
    // wrong number. Same root cause as the phantom names on the Pool Assignment screen; see
    // data/PoolCallerResolution.kt.
    val assignedCount = stats.assignedCalling
    val followupsCount = if (stats.pendingFollowups > 0) stats.pendingFollowups
        else AppDatabase.registrations.count {
            it.centreId == AppDatabase.selectedFlaskCentreId && it.category == AppDatabase.selectedCategory && it.remark.contains("followup", ignoreCase = true)
        }
    val todayCallsCount = stats.myAttendanceCount
    val centreName = AppDatabase.centres.find { it.id == AppDatabase.selectedCentreId }?.name ?: "Centre"

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.Start) {
        // Header
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Caller Dashboard", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("$centreName · ${AppDatabase.selectedCategory} · ${user.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusBadge(text = if (AppDatabase.isOnline) "Online" else "Offline", status = if (AppDatabase.isOnline) "online" else "offline")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Stats — tappable
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(
                value = "$assignedCount",
                label = "In My Queue",
                icon = Icons.Default.ContactPhone,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                onClick = onNavigateToQueue
            )
            StatCard(
                value = "$followupsCount",
                label = "Followups",
                icon = Icons.Default.Feedback,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
                onClick = onNavigateToFollowups
            )
            StatCard(
                value = "$todayCallsCount",
                label = "Calls Logged",
                icon = Icons.Default.Phone,
                color = Color(0xFF6200EA),
                modifier = Modifier.weight(1f),
                onClick = onNavigateToMyAttendance
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text("Active Channels", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        HorizontalActionCard("Personal Queue", "Your assigned students — call, WhatsApp, log outcomes.", Icons.Default.ListAlt, onNavigateToQueue)
        HorizontalActionCard("Special Followups", "Students flagged for secondary engagement.", Icons.Default.WifiProtectedSetup, onNavigateToFollowups)
        HorizontalActionCard("Batch Calling Pools", "Community bulk calling across an entire batch.", Icons.Default.PhoneForwarded, onNavigateToBatchCalling)
        HorizontalActionCard("Attendance & Transfers", "Your call logs and batch transfer requests.", Icons.Default.AccountBox, onNavigateToMyAttendance)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SHARED CALL-CARD CONTEXT — previous responses + attendance + new/existing tag.
// Rendered inline on every calling card (session AND pool) so the caller never has to open a
// detail screen just to see who they're about to call.
// ─────────────────────────────────────────────────────────────────────────────
private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

// "2026-06-20T..." / "2026-06-20" -> "20 Jun". Best-effort; returns the raw head on any surprise.
private fun shortDate(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val d = iso.take(10)
    val parts = d.split("-")
    if (parts.size != 3) return d
    val mon = parts[1].toIntOrNull()?.let { MONTHS.getOrNull(it - 1) } ?: parts[1]
    return "${parts[2].trimStart('0').ifEmpty { "0" }} $mon"
}

@Composable
fun NewExistingTag(ctx: PersonContext) {
    val isNew = ctx.isNew
    Surface(
        color = if (isNew) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            if (isNew) "NEW" else "EXISTING",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            color = if (isNew) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
fun PersonContextBlock(ctx: PersonContext, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        // Attendance summary
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(Icons.Default.EventAvailable, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (ctx.attendanceCount > 0) "Attended ${ctx.attendanceCount} class(es) · last ${shortDate(ctx.lastAttended)}" else "Not attended yet",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Current batches
        if (ctx.currentBatches.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Batches: ${ctx.currentBatches.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Where they live + occupation
        if (!ctx.address.isNullOrBlank() || !ctx.occupation.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    listOfNotNull(ctx.address?.ifBlank { null }, ctx.occupation?.ifBlank { null }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        // Previous responses (last 4) — with an inline expander to show all of them. Three columns,
        // date · response · remark, so each remark sits beside the response it was logged with. The
        // remark used to trail the response on one clipped line and fell off the card on a phone.
        if (ctx.recentCalls.isNotEmpty()) {
            var showAll by remember(ctx) { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Previous responses & remarks", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            val shown = if (showAll) ctx.recentCalls else ctx.recentCalls.take(4)
            val headColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            Column(modifier = Modifier.fillMaxWidth().padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Date", style = MaterialTheme.typography.labelSmall, color = headColor, modifier = Modifier.width(46.dp))
                    Text("Response", style = MaterialTheme.typography.labelSmall, color = headColor, modifier = Modifier.weight(1f))
                    Text("Remark", style = MaterialTheme.typography.labelSmall, color = headColor, modifier = Modifier.weight(1f))
                }
                shown.forEach { r ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(shortDate(r.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(46.dp))
                        Text(r.outcome, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(
                            r.remark?.ifBlank { null } ?: "—",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (ctx.recentCalls.size > 4) {
                Text(
                    if (showAll) "Show less" else "See full history (${ctx.recentCalls.size})",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 18.dp).clickable { showAll = !showAll },
                )
            }
        } else {
            Row(modifier = Modifier.padding(start = 18.dp)) {
                Text("No previous calls logged", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CALLING QUEUE SCREEN
// Outcome selection now uses the expandable OutcomeAccordion (inline, no dialog)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun CallingQueueScreen(onViewDetail: (String) -> Unit, onNavigateBack: () -> Unit) {
    val user = AppDatabase.currentUser ?: return
    val scope = rememberCoroutineScope()
    var isLoadingQueue by remember { mutableStateOf(false) }

    // Everything is session-based now — the caller's whole workload is their session-call queue.
    // (The old general pool-calls path is retired; assignment happens only through calling sessions.)
    var sessionCalls by remember { mutableStateOf<List<SessionCallAssignment>>(emptyList()) }
    var updatingSessionId by remember { mutableStateOf<String?>(null) }
    var expandedCallId by remember { mutableStateOf<String?>(null) }
    // Phone Back closes an open calling card first, exactly like tapping its header.
    BackHandler(enabled = expandedCallId != null) { expandedCallId = null }
    val summary = AppDatabase.sessionCallSummary

    // Saving no longer waits for the refresh below, so two reloads can be in flight at once. If an
    // earlier one returned last it would replace a fresher list with a staler one. The caller's own
    // answers are never at risk either way — fetchSessionCallQueue overlays pendingCallOverrides —
    // but the server-side view could visibly go backwards. Only the newest reload may paint.
    var reloadSeq by remember { mutableStateOf(0) }

    suspend fun reload() {
        reloadSeq += 1
        val mine = reloadSeq
        isLoadingQueue = true
        val fetched = AppDatabase.fetchSessionCallQueue()
        if (mine == reloadSeq) {
            sessionCalls = fetched
            isLoadingQueue = false
        }
    }
    LaunchedEffect(AppDatabase.selectedCategory, AppDatabase.dataRevision) { reload() }

    val logOutcome: (SessionCallAssignment, String, String?, Boolean, String?) -> Unit = { call, outcome, remark, answered, dropdownId ->
        updatingSessionId = call.id
        scope.launch {
            // Pass person info so an offline mark can render an optimistic Follow-up card + queue row
            // without any server round-trip.
            val ok = AppDatabase.updateSessionCallStatus(
                call.id, outcome, remark,
                mobile = call.mobile, name = call.name, sessionName = call.sessionName,
                answered = answered, dropdownId = dropdownId
            )
            // Free the caller the moment the answer is durable, and let the list catch up behind
            // them. updateSessionCallStatus has already parked the outcome for retry AND painted it
            // optimistically before returning, so there is nothing left to wait for — yet the Save
            // button used to stay disabled through a full /session-call-queue refetch. Measured
            // 2026-08-13: the save itself is ~33 ms server-side, but that refetch costs ~178 ms of
            // server work for an 80-person queue plus ~250 ms of tunnel round trip, and it grows
            // with the queue (one caller now holds 143). That gap — not the save — is what callers
            // meant by "saving takes about a second".
            if (ok) {
                expandedCallId = null
                // Paint the answer from the override we just placed, with NO network call. The
                // refetch used to happen right here, which put a GET on the wire alongside the
                // PATCH and asked the server what it held before it held it. updateSessionCallStatus
                // now bumps dataRevision once the write has settled, and the LaunchedEffect above
                // does the refetch then — correctly ordered, and still exactly one per save.
                sessionCalls = AppDatabase.overlayPendingOverrides(sessionCalls)
            }
            updatingSessionId = null
        }
    }

    // People who answered and asked to be called on a later date are held aside so the working list
    // only contains people to call NOW. They return to the list on their own once the date passes —
    // the server decides that, so nothing here needs a timer.
    val workingCalls = sessionCalls.filter { !it.isScheduled }
    val scheduledCalls = sessionCalls.filter { it.isScheduled }.sortedBy { it.snoozeUntil ?: "" }
    var scheduledExpanded by rememberSaveable { mutableStateOf(false) }

    // Every call belongs to a calling session — new people (zero attendance) are highest-value and
    // sorted to the top within their own session's group (tagged with a "N new" badge on the
    // header), rather than being pulled out into a cross-session bucket that separated them from
    // the session banner they were actually assigned under.
    val callsBySession = workingCalls.groupBy { it.sessionName.ifBlank { "Session" } }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Header
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onNavigateBack,
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
            ) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Calling", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (isLoadingQueue) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    }
                }
                Text("Tap a row to open the call", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ── Today progress strip ──────────────────────────────────────────────
        TodayStrip(total = summary.total, done = summary.done, left = summary.left)
        Spacer(modifier = Modifier.height(12.dp))

        if (sessionCalls.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color(0xFF2E7D32))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("All caught up — nobody to call right now!", fontWeight = FontWeight.SemiBold, color = Color(0xFF2E7D32))
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Everyone left is deferred — say so, rather than showing a bare Scheduled section
                // under a screen that looks empty.
                if (workingCalls.isEmpty()) {
                    item(key = "allclear") { AllClearBanner() }
                }

                // ── 📢 Grouped per session — new people sorted first within their own session ──
                callsBySession.forEach { (sessionName, callsRaw) ->
                    val calls = callsRaw.sortedWith(compareBy({ it.callStatus != "pending" }, { !it.context.isNew }))
                    val newCount = calls.count { it.context.isNew }
                    item(key = "schdr_$sessionName") {
                        CallGroupHeader("📢 $sessionName", calls.size, newCount = newCount, note = calls.firstOrNull()?.callerNote)
                    }
                    items(calls, key = { "sc_${it.id}" }) { call ->
                        CallRowOrCard(call, expandedCallId == "sc_${call.id}", updatingSessionId == call.id,
                            onExpand = { expandedCallId = "sc_${call.id}" }, onCollapse = { expandedCallId = null }, onLog = logOutcome)
                    }
                }

                // ── 🗓 Scheduled — deferred people, collapsed so they never crowd the working list ──
                if (scheduledCalls.isNotEmpty()) {
                    item(key = "sched_hdr") {
                        ScheduledSectionHeader(
                            count = scheduledCalls.size,
                            expanded = scheduledExpanded,
                            onToggle = { scheduledExpanded = !scheduledExpanded },
                        )
                    }
                    if (scheduledExpanded) {
                        items(scheduledCalls, key = { "sch_${it.id}" }) { call ->
                            // Still fully callable — a caller who wants to ring someone ahead of
                            // their date can just open the card as usual.
                            CallRowOrCard(call, expandedCallId == "sch_${call.id}", updatingSessionId == call.id,
                                onExpand = { expandedCallId = "sch_${call.id}" }, onCollapse = { expandedCallId = null }, onLog = logOutcome)
                        }
                    }
                }
            }
        }
    }
}

// A single call: a compact one-liner that expands into the full calling card in place.
@Composable
private fun CallRowOrCard(
    call: SessionCallAssignment,
    expanded: Boolean,
    isUpdating: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onLog: (SessionCallAssignment, String, String?, Boolean, String?) -> Unit,
) {
    if (expanded) {
        SessionCallQueueCard(call, isUpdating = isUpdating, onCollapse = onCollapse) { outcome, remark, answered, dropdownId ->
            onLog(call, outcome, remark, answered, dropdownId)
        }
    } else {
        CompactCallRow(
            name = call.name?.ifBlank { call.mobile } ?: call.mobile,
            mobile = call.mobile,
            isNew = call.context.isNew,
            // A deferred row leads with WHEN it comes back — that is the only thing the caller needs
            // from it at a glance; the raw status ("Deferred") tells them nothing actionable.
            statusText = if (call.isScheduled) dueLabel(call.snoozeUntil)
                         else call.callStatus.replace("_", " ").replaceFirstChar { it.uppercase() },
            statusActive = !call.isScheduled && (call.callStatus == "will_attend" || call.callStatus == "attended"),
            subtitle = call.context.recentCalls.firstOrNull()?.outcome,
            onClick = onExpand,
        )
    }
}

// "Due today" / "5 Aug" for a deferred person. The server stores midnight of the target day, which
// serialises as UTC, so it is parsed as UTC and rendered in the phone's own timezone — otherwise an
// IST midnight reads as the previous day.
private fun dueLabel(snoozeUntil: String?): String {
    if (snoozeUntil.isNullOrBlank()) return "Scheduled"
    return try {
        val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val due = parser.parse(snoozeUntil.take(19)) ?: return "Scheduled"
        val startOfToday = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.time
        val days = ((due.time - startOfToday.time) / 86_400_000L)
        when {
            days <= 0L -> "Due today"
            days == 1L -> "Tomorrow"
            else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(due)
        }
    } catch (e: Exception) { "Scheduled" }
}

// Shown when every remaining person is deferred, so an empty working list doesn't read as a bug.
@Composable
private fun AllClearBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2E7D32).copy(alpha = 0.10f)),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                "Nobody to call right now — everyone left is scheduled for later.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF2E7D32),
            )
        }
    }
}

// Collapsed by default: these people are deliberately NOT today's work, so they stay out of the way
// until the caller chooses to look, or until their date arrives and the server moves them back up.
@Composable
private fun ScheduledSectionHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onToggle() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🗓", fontSize = 15.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Scheduled  ($count)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Asked to be called later — they come back on their date",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Hide scheduled" else "Show scheduled",
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// The caller's "Today" progress: to-call / done / left + a thin bar. Gives momentum + a sense of
// how much is left, which the old queue never showed.
@Composable
private fun TodayStrip(total: Int, done: Int, left: Int) {
    val progress = if (total > 0) done.toFloat() / total else 0f
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("$total to call", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("$done done · $left left", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            )
        }
    }
}

// Section header used to group the caller's calls (by calling session, or the pool group).
@Composable
private fun CallGroupHeader(title: String, count: Int, newCount: Int = 0, note: String? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Event, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            if (newCount > 0) {
                Surface(color = MaterialTheme.colorScheme.tertiary, shape = RoundedCornerShape(10.dp)) {
                    Text("$newCount new", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onTertiary)
                }
            }
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp)) {
                Text("$count", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        // Admin's note for this caller on this session — set from the Pool Assignment screen.
        if (!note.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "Note: $note",
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 26.dp)
            )
        }
    }
}

// Compact one-line row for the queue — lets a caller scan 100s of people and tap the one they're
// calling to expand it into the full card. New people get a single quiet signal (a thin left
// accent bar + a soft tonal "NEW" chip) instead of stacking a tinted background, an accent dot,
// AND a solid badge on top of each other — that combination read as loud/cluttered next to the
// status badge already on the row.
@Composable
private fun CompactCallRow(
    name: String,
    mobile: String,
    isNew: Boolean,
    statusText: String,
    statusActive: Boolean,
    subtitle: String?,
    onClick: () -> Unit,
    // Overrides the badge style ("active"/"pending"/"inactive") when two states are not enough.
    statusKind: String? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            if (isNew) {
                Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.tertiary))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                        if (isNew) {
                            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(4.dp)) {
                                Text("NEW", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                            }
                        }
                    }
                    Text(
                        buildString { append(mobile); if (!subtitle.isNullOrBlank()) append(" · $subtitle") },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                if (statusText.isNotBlank()) StatusBadge(text = statusText, status = statusKind ?: if (statusActive) "active" else "pending")
                Icon(Icons.Default.ChevronRight, contentDescription = "Open", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// One person to call inside a session group. Self-contained: name + new/existing tag, mobile/batch/
// occupation, attendance summary, previous responses, Call/WhatsApp, an optional note, and the
// outcome buttons. A caller works the whole queue without opening Details.
@Composable
private fun SessionCallQueueCard(call: SessionCallAssignment, isUpdating: Boolean, onCollapse: () -> Unit, onSetOutcome: (String, String?, Boolean, String?) -> Unit) {
    val context = LocalContext.current
    var loggerOpen by remember(call.id) { mutableStateOf(false) }
    val rotate by animateFloatAsState(if (loggerOpen) 180f else 0f, label = "sc_log_${call.id}")
    // Same quiet left accent bar as the compact row (rather than a full tinted card background)
    // so "new" reads as one consistent signal whether the row is collapsed or expanded.
    ElevatedCard(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
      Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        if (call.context.isNew) {
            Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.tertiary))
        }
        Column(modifier = Modifier.weight(1f).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header — name + tag, mobile/batch, occupation. Tap header to collapse back to the list.
            Row(modifier = Modifier.fillMaxWidth().clickable { onCollapse() }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(call.name?.ifBlank { call.mobile } ?: call.mobile, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        NewExistingTag(call.context)
                    }
                    Text(call.mobile + (if (call.batchName.isNotBlank()) " · ${call.batchName}" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!call.context.occupation.isNullOrBlank()) {
                        Text(call.context.occupation!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    StatusBadge(call.callStatus.replace("_", " ").replaceFirstChar { it.uppercase() }, if (call.callStatus == "will_attend" || call.callStatus == "attended") "active" else "pending")
                    Icon(Icons.Default.ExpandLess, contentDescription = "Collapse", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Context — attendance + previous responses
            PersonContextBlock(call.context)

            HorizontalDivider()

            // Call / WhatsApp + Log outcome toggle
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${call.mobile}"))) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp)); Text("Call", fontSize = 13.sp)
                }
                Button(
                    onClick = { openWhatsApp(context, call.mobile, call.whatsappMessage) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE8F5E9), contentColor = Color(0xFF2E7D32)),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp)); Text("WhatsApp", fontSize = 13.sp)
                }
            }
            Button(
                onClick = { loggerOpen = !loggerOpen },
                modifier = Modifier.fillMaxWidth(),
                colors = if (loggerOpen) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()
            ) {
                Text(if (loggerOpen) "Cancel" else "Log Outcome", fontSize = 13.sp)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(15.dp).rotate(rotate))
            }

            // Outcome — the SAME configurable calling-outcome list pool calls use.
            AnimatedVisibility(visible = loggerOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column {
                    HorizontalDivider(modifier = Modifier.padding(bottom = 10.dp))
                    OutcomeTwoStep(
                        onSave = { outcome, remark, answered, dropdownId ->
                            if (!isUpdating) onSetOutcome(outcome, remark, answered, dropdownId)
                        },
                        saveEnabled = !isUpdating,
                        resetKey = call.id,
                    )
                }
            }
        }
      }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// STUDENT CALL DETAIL SCREEN
// Full calling workspace with expandable outcome accordion + call history
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun StudentCallDetailScreen(studentId: String, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val student = AppDatabase.registrations.find { it.id == studentId } ?: return
    var historyExpanded by remember { mutableStateOf(false) }
    val historyRotation by animateFloatAsState(if (historyExpanded) 180f else 0f, label = "history_rotate")

    val callHistory = AppDatabase.callAttempts.filter { it.registrationId == studentId }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Header
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, contentDescription = null) }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("Call Workspace", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(student.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Student profile card with call/WA buttons
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(student.name, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
                            Text("${student.category} · ${AppDatabase.centres.find { it.id == student.centreId }?.name ?: student.centreId}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(student.address, style = MaterialTheme.typography.bodySmall)
                            if (student.occupation.isNotBlank()) Text(student.occupation, style = MaterialTheme.typography.bodySmall)
                        }
                        if (callHistory.isNotEmpty()) {
                            StatusBadge(text = "${callHistory.size} calls", status = "active")
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${student.mobile}"))) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Phone, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Place Call")
                        }
                        Button(
                            onClick = { openWhatsApp(context, student.mobile) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Chat, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("WhatsApp")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Collapsible call history section
        if (callHistory.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { historyExpanded = !historyExpanded }.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Previous Call History", fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(8.dp))
                                StatusBadge(text = "${callHistory.size}", status = "active")
                            }
                            Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp).rotate(historyRotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AnimatedVisibility(visible = historyExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                                HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                                callHistory.reversed().forEach { ca ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                                Text(ca.dateStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                StatusBadge(text = ca.outcome.take(14), status = "active")
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
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // Outcome accordion — expandable in-place
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // An outcome picker used to sit here. It saved the call as an ATTENDANCE row
                    // (markAttendance) — marking the person present at today's session — and never
                    // reached the calling server, so "Not interested" / "Wrong number" removed no one.
                    Text("Log the call from the Calling tab", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Responses saved there reach the calling server: Not interested and Wrong number take the person out of calling at once.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// FOLLOW-UP SCREEN — with urgency badges and priority ordering
// ─────────────────────────────────────────────────────────────────────────────
// One follow-up, opened: the SAME calling card the Calling tab shows — name + new/existing tag,
// mobile/session, attendance, batches, address, previous responses with remarks, Call/WhatsApp —
// plus what only a follow-up has: why it exists, how urgent it is, and whether it is locked.
@Composable
private fun FollowUpCallCard(
    student: Registration,
    urgencyLabel: String,
    overdue: Boolean,
    urgencyColor: Color,
    onCollapse: () -> Unit,
    onLogResolution: () -> Unit,
) {
    val context = LocalContext.current
    // A server-sent context always differs from the default (the server sets isNew = attended < 3,
    // the default is false), so equality means there is none: an optimistic card whose answer has
    // not synced, or a backend that predates follow-up context. Say so instead of claiming
    // "Not attended yet / No previous calls" about someone we simply have no data for.
    val hasContext = student.context != PersonContext()
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
      Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        if (hasContext && student.context.isNew) {
            Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.tertiary))
        }
        Column(modifier = Modifier.weight(1f).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header — tap to collapse back to the list, same as the Calling tab.
            Row(modifier = Modifier.fillMaxWidth().clickable { onCollapse() }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(student.name.ifBlank { student.mobile }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        if (hasContext) NewExistingTag(student.context)
                    }
                    Text(student.mobile + (student.sessionName?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val occupation = student.context.occupation ?: student.occupation.ifBlank { null }
                    if (occupation != null) {
                        Text(occupation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (student.isLocked) {
                        Text(student.lockedReason ?: "Retrieved — locked", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    StatusBadge(text = urgencyLabel, status = if (overdue) "inactive" else "pending")
                    Icon(Icons.Default.ExpandLess, contentDescription = "Collapse", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Why this follow-up exists
            Card(
                colors = CardDefaults.cardColors(containerColor = urgencyColor.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Reason: ${student.remark}", color = urgencyColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            }

            // Context — attendance + batches + previous responses & remarks
            if (hasContext) {
                PersonContextBlock(student.context)
            } else {
                Text("Call history appears here once this answer has synced.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${student.mobile}"))) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp)); Text("Call", fontSize = 13.sp)
                }
                Button(
                    onClick = { openWhatsApp(context, student.mobile, student.whatsappMessage) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE8F5E9), contentColor = Color(0xFF2E7D32)),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp)); Text("WhatsApp", fontSize = 13.sp)
                }
            }
            Button(onClick = onLogResolution, enabled = !student.isLocked, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (student.isLocked) "Locked" else "Log Resolution", fontSize = 13.sp)
            }
        }
      }
    }
}

@Composable
fun FollowUpScreen(onViewDetail: (String) -> Unit) {
    var isLoadingFollowups by remember { mutableStateOf(false) }
    // Which follow-up card is open. Keyed by id AND mobile: the list merges locally-derived rows
    // with server rows, whose ids come from different tables and are not guaranteed distinct.
    var expandedFollowupKey by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = expandedFollowupKey != null) { expandedFollowupKey = null }
    var followupsList by remember {
        mutableStateOf(
            AppDatabase.registrations.filter {
                it.centreId == AppDatabase.selectedFlaskCentreId && it.category == AppDatabase.selectedCategory && it.status == "active" &&
                (it.remark.contains("followup", ignoreCase = true) || it.remark.contains("needs", ignoreCase = true) || it.remark.contains("callback", ignoreCase = true))
            }
        )
    }

    // Fetch follow-ups. Runs online AND offline: fetchFollowups() serves the live list when the
    // server is reachable (refreshing its cache), or the cached list + un-synced positive responses
    // when it isn't — so the tab behaves identically either way.
    LaunchedEffect(AppDatabase.selectedCategory, AppDatabase.dataRevision) {
        isLoadingFollowups = true
        val backendFollowups = AppDatabase.fetchFollowups()
        if (backendFollowups.isNotEmpty()) {
            val localIds = followupsList.map { it.id }.toSet()
            followupsList = followupsList + backendFollowups.filter { it.id !in localIds }
        }
        isLoadingFollowups = false
    }

    // Oldest-created follow-up = been waiting longest to be confirmed = highest priority to call
    // before class. Local (non-backend) entries have createdAtMillis = 0, which naturally sorts
    // them first too (they predate this instant-follow-up feature and need attention regardless).
    fun urgencyOf(student: Registration): Int = when {
        student.createdAtMillis == 0L -> 0
        System.currentTimeMillis() - student.createdAtMillis > 24 * 60 * 60 * 1000L -> 0
        else -> 1
    }
    val sortedList = followupsList.sortedBy { it.createdAtMillis }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pending Follow-ups", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (isLoadingFollowups) {
                Spacer(modifier = Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            }
        }
        Text("${sortedList.size} students flagged for secondary engagement. Ordered by urgency.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(16.dp))

        if (sortedList.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.TaskAlt, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color(0xFF2E7D32))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No pending follow-ups — great work!", color = Color(0xFF2E7D32), fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            // Two-level grouping. "Call back later" is a POSITIVE response but NOT a promise to
            // attend, so it gets its own heading: blending it with confirmed attendees would let a
            // caller read "ring me back later" as "they are coming", and plan the class on it.
            val byKind = sortedList.groupBy {
                if (it.followupKind == "call_back_later") "call_back_later" else "will_attend"
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
              listOf("will_attend", "call_back_later").forEach { kindKey ->
                val inKind = byKind[kindKey].orEmpty()
                if (inKind.isNotEmpty()) {
                item {
                    Text(
                        if (kindKey == "call_back_later") "☎  Asked to call back (${inKind.size})"
                        else "✅  Said they will attend (${inKind.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (kindKey == "call_back_later") Color(0xFFEF6C00) else Color(0xFF2E7D32),
                        modifier = Modifier.padding(top = 18.dp, bottom = 2.dp)
                    )
                }
                val grouped = inKind.groupBy { it.sessionName ?: "Other follow-ups" }
                    .toList().sortedBy { it.first }
                grouped.forEach { (sessionName, studentsInSession) ->
                    item {
                        Text(
                            "📢 $sessionName",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
                        )
                    }
                    items(studentsInSession) { student ->
                    val urgency = urgencyOf(student)
                    val urgencyColor = when (urgency) {
                        0 -> Color(0xFFC62828) // red = overdue
                        else -> Color(0xFFEF6C00) // amber = due soon
                    }
                    val urgencyLabel = if (urgency == 0) "OVERDUE" else "DUE SOON"

                    // Same pattern as the Calling tab: a compact row that opens into the full card.
                    val cardKey = "${student.id}|${student.mobile}"
                    if (expandedFollowupKey == cardKey) {
                        FollowUpCallCard(
                            student = student,
                            urgencyLabel = urgencyLabel,
                            overdue = urgency == 0,
                            urgencyColor = urgencyColor,
                            onCollapse = { expandedFollowupKey = null },
                            onLogResolution = { onViewDetail(student.id) },
                        )
                    } else {
                        Box(modifier = Modifier.padding(vertical = 4.dp)) {
                            CompactCallRow(
                                name = student.name.ifBlank { student.mobile },
                                mobile = student.mobile,
                                isNew = student.context != PersonContext() && student.context.isNew,
                                statusText = if (student.isLocked) "LOCKED" else urgencyLabel,
                                statusActive = false,
                                subtitle = student.remark,
                                onClick = { expandedFollowupKey = cardKey },
                                statusKind = if (urgency == 0 || student.isLocked) "inactive" else "pending",
                            )
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

// ─────────────────────────────────────────────────────────────────────────────
// BATCH CALLING SCREEN — with bulk "Mark All Done" option
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun BatchCallingScreen(onViewDetail: (String) -> Unit) {
    val context = LocalContext.current
    // Sticky target batch. Same reason as the other pickers: switching drawer tabs disposes this
    // screen, and a caller working one batch had to re-select it every time they came back. Falls
    // through to the first batch of the centre+category when nothing was remembered or the
    // remembered batch has since gone.
    val batchesHere = AppDatabase.batches.filter {
        it.centreId == AppDatabase.selectedFlaskCentreId && it.category == AppDatabase.selectedCategory
    }
    var selectedBatch by remember {
        mutableStateOf(
            AppDatabase.uiSelection("caller.batchId")?.let { id -> batchesHere.firstOrNull { it.id == id } }
                ?: batchesHere.firstOrNull()
        )
    }
    val batchStudents = AppDatabase.registrations.filter {
        it.status == "active" && it.centreId == AppDatabase.selectedFlaskCentreId && it.category == AppDatabase.selectedCategory &&
        (selectedBatch == null || it.batchIds.contains(selectedBatch?.id))
    }
    // "Bulk Done" and "Send Back" used to live here. Both saved a phone call as an ATTENDANCE row
    // (markAttendance), so one Bulk Done tap on 25 Sep 2026 marked 707 people present at a session
    // they never came to. Removed; calls are logged per person from Detail / Calling.

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Header
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Batch Calling", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${batchStudents.size} students in batch", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Batch selector — ExposedDropdownMenuBox style
        var expandedB by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expandedB, onExpandedChange = { expandedB = it }) {
            OutlinedTextField(
                value = selectedBatch?.name ?: "All Batches",
                onValueChange = {},
                readOnly = true,
                label = { Text("Target Batch") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedB) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            ExposedDropdownMenu(expanded = expandedB, onDismissRequest = { expandedB = false }) {
                AppDatabase.batches.filter { it.centreId == AppDatabase.selectedFlaskCentreId && it.category == AppDatabase.selectedCategory }.forEach { b ->
                    DropdownMenuItem(
                        text = {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text(b.name)
                                Text("${b.memberCount}m", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        trailingIcon = if (b.isDefault) ({ StatusBadge(text = "Default", status = "active") }) else null,
                        onClick = {
                            selectedBatch = b
                            AppDatabase.setUiSelection("caller.batchId", b.id)
                            expandedB = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (batchStudents.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No active students in this batch.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(batchStudents) { student ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(student.name, fontWeight = FontWeight.Bold)
                                Text(student.mobile, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(
                                    onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${student.mobile}"))) },
                                    modifier = Modifier.size(32.dp)
                                ) { Icon(Icons.Default.Phone, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
                                IconButton(
                                    onClick = { openWhatsApp(context, student.mobile) },
                                    modifier = Modifier.size(32.dp)
                                ) { Icon(Icons.Default.Chat, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp)) }
                                Button(onClick = { onViewDetail(student.id) }, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.height(30.dp)) {
                                    Text("Detail", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

}

// ─────────────────────────────────────────────────────────────────────────────
// MY ATTENDANCE SCREEN — with stats summary at top
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun MyAttendanceScreen(onNavigateBack: () -> Unit) {
    var noteVal by remember { mutableStateOf("") }
    var selectedRecordForNote by remember { mutableStateOf<CallAttempt?>(null) }
    var requestMoveDialog by remember { mutableStateOf(false) }
    var filterExpanded by remember { mutableStateOf(false) }
    val filterRotation by animateFloatAsState(if (filterExpanded) 180f else 0f, label = "filter_rotate")

    val user = AppDatabase.currentUser ?: return
    val myRecords = AppDatabase.callAttempts.filter { it.callerName == user.name }.reversed()

    // Outcome breakdown for stats card
    val outcomeCounts = myRecords.groupBy { it.outcome }.mapValues { it.value.size }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, contentDescription = null) }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("My Call Notebook", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${myRecords.size} total entries for ${user.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Stats summary card (expandable)
        if (myRecords.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { filterExpanded = !filterExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Outcome Breakdown", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp).rotate(filterRotation))
                    }
                    AnimatedVisibility(visible = filterExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                        Column(modifier = Modifier.padding(top = 10.dp)) {
                            outcomeCounts.forEach { (outcome, count) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(outcome, style = MaterialTheme.typography.bodySmall)
                                    Text("$count", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                }
                                LinearProgressIndicator(
                                    progress = { count.toFloat() / myRecords.size.toFloat() },
                                    modifier = Modifier.fillMaxWidth().height(3.dp).padding(bottom = 2.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        Button(
            onClick = { requestMoveDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Icon(Icons.Default.SwapCalls, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Propose Batch Transfer Request")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (myRecords.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PhoneDisabled, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No calling activity logged yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(myRecords) { ca ->
                    val studentName = AppDatabase.registrations.find { it.id == ca.registrationId }?.name ?: "Unknown"
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(studentName, fontWeight = FontWeight.Bold)
                                    Text(ca.dateStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                StatusBadge(text = ca.outcome.take(14), status = "active")
                            }
                            if (ca.notes.isNotBlank()) {
                                Text(ca.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                            }
                            TextButton(
                                onClick = { selectedRecordForNote = ca },
                                contentPadding = PaddingValues(horizontal = 0.dp)
                            ) {
                                Icon(Icons.Default.NoteAdd, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Append Note", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // Note append dialog
    selectedRecordForNote?.let { record ->
        AlertDialog(
            onDismissRequest = { selectedRecordForNote = null },
            title = { Text("Append Note") },
            text = {
                Column {
                    Text("Add a private note to the call log for ${AppDatabase.registrations.find { it.id == record.registrationId }?.name ?: "this student"}:")
                    Spacer(modifier = Modifier.height(12.dp))
                    AppTextField(value = noteVal, onValueChange = { noteVal = it }, label = "Note", singleLine = false, maxLines = 3)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        AppDatabase.callAttempts = AppDatabase.callAttempts.map {
                            if (it.id == record.id) it.copy(notes = "${it.notes} | ${noteVal}") else it
                        }
                        selectedRecordForNote = null; noteVal = ""
                    },
                    enabled = noteVal.isNotEmpty()
                ) { Text("Append") }
            },
            dismissButton = { TextButton(onClick = { selectedRecordForNote = null }) { Text("Cancel") } }
        )
    }

    // Batch transfer dialog
    if (requestMoveDialog) {
        var selectedSource by remember { mutableStateOf(AppDatabase.batches.firstOrNull { it.centreId == AppDatabase.selectedFlaskCentreId } ?: AppDatabase.batches.first()) }
        var selectedTarget by remember { mutableStateOf(AppDatabase.batches.firstOrNull { it.centreId == AppDatabase.selectedFlaskCentreId && it.id != selectedSource.id } ?: AppDatabase.batches.last()) }
        var transferReason by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { requestMoveDialog = false },
            title = { Text("Propose Batch Transfer") },
            text = {
                Column {
                    Text("Source Batch", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        AppDatabase.batches.filter { it.centreId == AppDatabase.selectedFlaskCentreId }.take(3).forEach { b ->
                            FilterChip(selected = selectedSource.id == b.id, onClick = { selectedSource = b }, label = { Text(b.name, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.weight(1f))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Target Batch", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        AppDatabase.batches.filter { it.centreId == AppDatabase.selectedFlaskCentreId && it.id != selectedSource.id }.take(3).forEach { b ->
                            FilterChip(selected = selectedTarget.id == b.id, onClick = { selectedTarget = b }, label = { Text(b.name, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.weight(1f))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    AppTextField(value = transferReason, onValueChange = { transferReason = it }, label = "Reason for Transfer", singleLine = false, maxLines = 3)
                }
            },
            confirmButton = {
                Button(
                    onClick = { AppDatabase.submitMoveToBatch("r1", selectedSource.id, selectedTarget.id, transferReason); requestMoveDialog = false; transferReason = "" },
                    enabled = transferReason.isNotEmpty() && selectedSource.id != selectedTarget.id
                ) { Text("Submit Request") }
            },
            dismissButton = { TextButton(onClick = { requestMoveDialog = false }) { Text("Cancel") } }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CALLING SCOREBOARD ("My Attendance") — drill down batch → session → people,
// showing how many the caller CALLED vs how many CAME. Backed by /my-attendance/calling/*.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun CallingScoreboardScreen(onNavigateBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var batches by remember { mutableStateOf<List<CallingBatchStat>>(emptyList()) }
    var selectedBatch by remember { mutableStateOf<CallingBatchStat?>(null) }
    var sessions by remember { mutableStateOf<List<CallingSessionStat>>(emptyList()) }
    var selectedSession by remember { mutableStateOf<CallingSessionStat?>(null) }
    var people by remember { mutableStateOf<List<CalledPerson>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(AppDatabase.selectedCategory, AppDatabase.dataRevision) {
        loading = true
        batches = AppDatabase.fetchCallingBatchesMA()
        loading = false
    }

    val dateFmt = remember { java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()) }
    fun fmtDate(ms: Long): String = if (ms > 0) dateFmt.format(java.util.Date(ms)) else "Session"

    fun openBatch(b: CallingBatchStat) {
        selectedBatch = b; selectedSession = null; people = emptyList()
        scope.launch { loading = true; sessions = AppDatabase.fetchCallingSessionsMA(b.batchId); loading = false }
    }
    fun openSession(s: CallingSessionStat) {
        selectedSession = s
        scope.launch { loading = true; people = AppDatabase.fetchCallingPeopleMA(s.campaignId); loading = false }
    }
    fun goBack() {
        when {
            selectedSession != null -> { selectedSession = null; people = emptyList() }
            selectedBatch != null -> { selectedBatch = null; sessions = emptyList() }
            else -> onNavigateBack()
        }
    }
    // Phone Back walks the drill-down (people → sessions → batches) like the ← arrow does; at the
    // top level it falls through to the shell, which handles leaving this screen.
    BackHandler(enabled = selectedBatch != null || selectedSession != null) { goBack() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { goBack() }) { Icon(Icons.Default.ArrowBack, contentDescription = null) }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                val title = when {
                    selectedSession != null -> "Session Result"
                    selectedBatch != null -> selectedBatch!!.batchName
                    else -> "My Attendance"
                }
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                val sub = when {
                    selectedSession != null -> "${fmtDate(selectedSession!!.scheduledAtMillis)} · ${selectedSession!!.batchName}"
                    selectedBatch != null -> "Pick a calling session"
                    else -> "Your calling sessions — called vs came"
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (loading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        Spacer(modifier = Modifier.height(12.dp))

        when {
            selectedSession != null -> {
                val s = selectedSession!!
                SbScore(s.calledCount, s.attendedCount)
                Spacer(modifier = Modifier.height(10.dp))
                if (people.isEmpty() && !loading) {
                    SbEmpty("No calls logged for this session.")
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(people) { p -> SbPersonRow(p) }
                    }
                }
            }
            selectedBatch != null -> {
                if (sessions.isEmpty() && !loading) {
                    SbEmpty("No calling sessions with your calls in this batch.")
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(sessions) { s ->
                            SbCard(fmtDate(s.scheduledAtMillis), "Tap to see who you called", s.calledCount, s.attendedCount) { openSession(s) }
                        }
                    }
                }
            }
            else -> {
                if (batches.isEmpty() && !loading) {
                    SbEmpty("You haven't made any session calls yet.")
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(batches) { b ->
                            SbCard(b.batchName, "Tap to see sessions", b.calledCount, b.attendedCount) { openBatch(b) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SbScore(called: Int, came: Int) {
    val rate = if (called > 0) came * 100 / called else 0
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SbStat("Called", "$called", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
        SbStat("Came", "$came", Color(0xFF2E7D32), Modifier.weight(1f))
        SbStat("Rate", "$rate%", MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
    }
}

@Composable
private fun SbStat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f))) {
        Column(modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SbCard(title: String, subtitle: String, called: Int, came: Int, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() }) {
        Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("$called called", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(10.dp))
            Text("$came came", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
        }
    }
}

@Composable
private fun SbPersonRow(p: CalledPerson) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.SemiBold)
                Text(p.outcomeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val badgeBg = if (p.came) Color(0xFF2E7D32).copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            val badgeFg = if (p.came) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
            Surface(color = badgeBg, shape = MaterialTheme.shapes.small) {
                Text(if (p.came) "Came" else "No show", color = badgeFg, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SbEmpty(msg: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
        Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SESSION CALL SCREEN — caller's queue for upcoming planned sessions
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SessionCallScreen() {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<SessionCallAssignment>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var updatingId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(AppDatabase.selectedCategory, AppDatabase.dataRevision) {
        isLoading = true
        items = AppDatabase.fetchSessionCallQueue()
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Session Calls", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("${items.size} people to call for upcoming sessions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(12.dp))

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.EventAvailable, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No session calls assigned", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Column
        }

        error?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                Text(it, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.id }) { assignment ->
                val statusColor = when (assignment.callStatus) {
                    "will_attend" -> MaterialTheme.colorScheme.primary
                    "wont_attend" -> MaterialTheme.colorScheme.error
                    // Positive but not a confirmed yes — deliberately its own colour so it is never
                    // read as either a will-attend or a dead number.
                    "call_back_later" -> MaterialTheme.colorScheme.tertiary
                    "called", "no_answer" -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(assignment.mobile, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text("${assignment.batchName} · ${assignment.scheduledAt.take(10)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            StatusBadge(assignment.callStatus.replace("_", " ").replaceFirstChar { it.uppercase() }, if (assignment.callStatus == "will_attend") "active" else "pending")
                        }
                        // Outcome buttons
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "will_attend" to "Will Attend",
                                "wont_attend" to "Won't Attend",
                                "no_answer" to "No Answer"
                            ).forEach { (status, label) ->
                                val isSelected = assignment.callStatus == status
                                val isUpdating = updatingId == assignment.id
                                OutlinedButton(
                                    onClick = {
                                        if (!isSelected && !isUpdating) {
                                            updatingId = assignment.id
                                            scope.launch {
                                                val ok = AppDatabase.updateSessionCallStatus(assignment.id, status)
                                                if (ok) {
                                                    items = items.map { if (it.id == assignment.id) it.copy(callStatus = status) else it }
                                                } else {
                                                    error = "Failed to update status"
                                                }
                                                updatingId = null
                                            }
                                        }
                                    },
                                    enabled = !isSelected && updatingId != assignment.id,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                ) {
                                    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            }
                        }
                        // Dial button
                        val ctx = LocalContext.current
                        FilledTonalButton(
                            onClick = { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:${assignment.mobile}"))) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Call ${assignment.mobile}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
