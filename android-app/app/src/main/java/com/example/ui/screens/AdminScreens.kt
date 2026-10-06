package com.example.ui.screens

import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.example.data.*
import com.example.ui.components.*
import java.util.Calendar
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// How long the Pool page waits after the last filter tap before loading the list once.
private const val FILTER_SETTLE_MS = 400L

// ─────────────────────────────────────────────────────────────────────────────
// BATCH MANAGEMENT SCREEN — expandable batch rows with member list
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun BatchManagementScreen(onViewStudentDetails: (String) -> Unit) {
    // Batches + membership now come from the SRKB calling backend (accurate member_count from
    // unique AttendEase attendees; named members from /pool/batch-members), not the Flask feed.
    var batchesList by remember { mutableStateOf<List<Batch>>(emptyList()) }
    var loadingBatches by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }
    var moveRequests by remember { mutableStateOf(AppDatabase.batchMoveRequests) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newBatchName by remember { mutableStateOf("") }
    var newBatchIsDefault by remember { mutableStateOf(false) }
    val expandedBatchIds = remember { mutableStateOf<Set<String>>(emptySet()) }
    val membersByBatch = remember { mutableStateMapOf<String, List<NamedMember>>() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var batchToDelete by remember { mutableStateOf<Batch?>(null) }

    // structureRevision: a batch another admin just created must appear here without this screen
    // being torn down and rebuilt, which until now was the only thing that ever reloaded it.
    LaunchedEffect(
        AppDatabase.selectedCategory, AppDatabase.selectedCentreId, reloadTick,
        AppDatabase.structureRevision,
    ) {
        loadingBatches = true
        membersByBatch.clear()
        batchesList = AppDatabase.fetchCallingBatches()
        loadingBatches = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Batch Management", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Manage rosters, transfer requests (C5)", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("New Batch")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Summary chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = false, onClick = {}, label = { Text("${batchesList.size} batches") }, leadingIcon = { Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(14.dp)) })
            val totalMembers = batchesList.sumOf { it.memberCount }
            FilterChip(selected = false, onClick = {}, label = { Text("$totalMembers members") }, leadingIcon = { Icon(Icons.Default.People, contentDescription = null, modifier = Modifier.size(14.dp)) })
            val pendingCount = moveRequests.count { it.status == "pending" }
            if (pendingCount > 0) {
                FilterChip(selected = true, onClick = {}, label = { Text("$pendingCount transfers pending") }, leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(14.dp)) })
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            // Active batches
            item {
                Text("Active Batches", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                if (loadingBatches && batchesList.isEmpty())
                    Text("Loading batches…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (batchesList.isEmpty())
                    Text("No batches for this centre / category.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
            }

            items(batchesList) { b ->
                val isExpanded = expandedBatchIds.value.contains(b.id)
                val expandRotation by animateFloatAsState(if (isExpanded) 180f else 0f, label = "batch_expand_${b.id}")
                val loaded = membersByBatch[b.id]
                val batchMembers = loaded ?: emptyList()
                if (isExpanded) {
                    LaunchedEffect(b.id, reloadTick) {
                        if (membersByBatch[b.id] == null) {
                            membersByBatch[b.id] = AppDatabase.fetchNamedBatchMembers(b.id)
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column {
                        // Header row
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                expandedBatchIds.value = if (isExpanded) expandedBatchIds.value - b.id else expandedBatchIds.value + b.id
                            }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(b.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    if (b.isDefault) StatusBadge(text = "Default", status = "active")
                                }
                                Text("${b.memberCount} members · ${b.category}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp).rotate(expandRotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = { batchToDelete = b },
                                enabled = !b.isDefault,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = if (b.isDefault) Color.Gray else MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }

                        // Expandable member list
                        AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                                HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                                if (loaded == null) {
                                    Text("Loading members…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else if (batchMembers.isEmpty()) {
                                    Text("No members in this batch.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    batchMembers.take(8).forEach { m ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(m.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                                Text(m.mobile, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Text("${m.attendanceCount}×", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    if (batchMembers.size > 8) {
                                        Text("…and ${batchMembers.size - 8} more", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Transfer requests
            if (moveRequests.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Transfer Requests (C5)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(6.dp))
                }

                items(moveRequests) { req ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = when (req.status) {
                                "approved" -> Color(0xFFE8F5E9)
                                "rejected" -> Color(0xFFFFEBEE)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            }
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(req.studentName, fontWeight = FontWeight.Bold)
                                StatusBadge(text = req.status, status = req.status)
                            }
                            val sourceName = AppDatabase.batches.find { it.id == req.sourceBatchId }?.name ?: req.sourceBatchId
                            val targetName = AppDatabase.batches.find { it.id == req.targetBatchId }?.name ?: req.targetBatchId
                            Text("$sourceName → $targetName", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(req.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                            if (req.status == "pending") {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { onViewStudentDetails(req.registrationId) }) { Text("View Profile") }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    OutlinedButton(onClick = {
                                        AppDatabase.batchMoveRequests = AppDatabase.batchMoveRequests.map { if (it.id == req.id) it.copy(status = "rejected") else it }
                                        moveRequests = AppDatabase.batchMoveRequests
                                    }) { Text("Reject") }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Button(onClick = {
                                        AppDatabase.batchMoveRequests = AppDatabase.batchMoveRequests.map { if (it.id == req.id) it.copy(status = "approved") else it }
                                        AppDatabase.registrations = AppDatabase.registrations.map {
                                            if (it.id == req.registrationId) it.copy(batchIds = listOf(req.targetBatchId)) else it
                                        }
                                        moveRequests = AppDatabase.batchMoveRequests
                                    }) { Text("Approve") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create New Batch") },
            text = {
                Column {
                    Text("Assign to ${AppDatabase.selectedCategory} · ${AppDatabase.centres.find { it.id == AppDatabase.selectedCentreId }?.name}")
                    Spacer(modifier = Modifier.height(12.dp))
                    AppTextField(value = newBatchName, onValueChange = { newBatchName = it }, label = "Batch Name", leadingIcon = Icons.Default.Group)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { newBatchIsDefault = !newBatchIsDefault }) {
                        Checkbox(checked = newBatchIsDefault, onCheckedChange = { newBatchIsDefault = it })
                        Text("Set as default batch (R1 auto-assign)")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val nm = newBatchName.trim()
                        scope.launch {
                            AppDatabase.createBatch(nm)   // persists to AttendEase; mirrors to the calling backend
                            reloadTick++
                        }
                        showCreateDialog = false; newBatchName = ""; newBatchIsDefault = false
                    },
                    enabled = newBatchName.isNotEmpty()
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") } }
        )
    }

    // Hard-delete confirmation — routes through Flask (the batch owner) for a real, permanent delete.
    batchToDelete?.let { bd ->
        AlertDialog(
            onDismissRequest = { batchToDelete = null },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete \"${bd.name}\" permanently?") },
            text = {
                Column {
                    Text("This PERMANENTLY deletes the batch and its attendance records from the attendance system and the calling backend. This cannot be undone.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("${bd.memberCount} members · ${bd.category}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val target = bd
                    batchToDelete = null
                    scope.launch {
                        val (ok, msg) = AppDatabase.deleteBatchOnFlask(target.id)
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        if (ok) reloadTick++
                    }
                }) { Text("Delete permanently") }
            },
            dismissButton = { TextButton(onClick = { batchToDelete = null }) { Text("Cancel") } }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// POOL ASSIGNMENT SCREEN — range-based assignment, scrollable, per-tab state
// ─────────────────────────────────────────────────────────────────────────────
private data class RangeEntry(val id: String, val from: String, val to: String, val callerId: String)

/**
 * Reusable "assign by range" block: from-to row numbers → one caller each.
 *
 * `orderedTargetIds` MUST be positional against the table rendered directly above this block:
 * index i is the row the admin sees numbered i+1, and null means that row cannot be allotted
 * (no batch member). Passing a differently-ordered or differently-filtered list is the 2026-08-15
 * data-loss bug — see [RangeAssignment] for what that did. The nullable-and-positional shape is
 * what stops a hole from shifting every row beneath it.
 *
 * When `enabled` is false (e.g. a retrieved session) the inputs and Assign button are locked.
 * On Assign it hands back a resolved [RangeAssignment.Plan], never raw row numbers.
 */
@Composable
private fun RangeAssignBlock(
    title: String,
    subtitle: String,
    ranges: androidx.compose.runtime.snapshots.SnapshotStateList<RangeEntry>,
    callers: List<User>,
    orderedTargetIds: List<String?>,
    enabled: Boolean,
    busy: Boolean,
    resultText: String?,
    // Rows (0-based, same indexing as orderedTargetIds) whose person is held somewhere else.
    busyRowIndices: Set<Int> = emptySet(),
    // The card was always green with a tick, so a refusal read as a success.
    resultIsError: Boolean = false,
    assignButtonText: String,
    onAssign: (RangeAssignment.Plan) -> Unit,
) {
    var seq by remember { mutableStateOf(1) }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (enabled) {
            TextButton(onClick = { ranges.add(RangeEntry("r${seq++}_${ranges.size}", "", "", "")) }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Row")
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    ranges.forEachIndexed { rowIdx, entry ->
        key(entry.id) {
            var callerOpen by remember { mutableStateOf(false) }
            val callerName = callers.find { it.id == entry.callerId }?.name ?: "Select Caller"
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                border = CardDefaults.outlinedCardBorder(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier.size(24.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${rowIdx + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    OutlinedTextField(
                        value = entry.from,
                        onValueChange = { v -> if (v.all { it.isDigit() } && v.length <= 4) { val i = ranges.indexOfFirst { it.id == entry.id }; if (i >= 0) ranges[i] = ranges[i].copy(from = v) } },
                        label = { Text("From", fontSize = 10.sp) },
                        modifier = Modifier.width(68.dp), shape = RoundedCornerShape(8.dp),
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true, enabled = enabled,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Text("–", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = entry.to,
                        onValueChange = { v -> if (v.all { it.isDigit() } && v.length <= 4) { val i = ranges.indexOfFirst { it.id == entry.id }; if (i >= 0) ranges[i] = ranges[i].copy(to = v) } },
                        label = { Text("To", fontSize = 10.sp) },
                        modifier = Modifier.width(68.dp), shape = RoundedCornerShape(8.dp),
                        textStyle = MaterialTheme.typography.bodySmall, singleLine = true, enabled = enabled,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        ExposedDropdownMenuBox(expanded = callerOpen && enabled, onExpandedChange = { if (enabled) callerOpen = it }) {
                            OutlinedTextField(
                                value = callerName, onValueChange = {}, readOnly = true,
                                label = { Text("Caller", fontSize = 10.sp) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = callerOpen) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(), enabled = enabled,
                                shape = RoundedCornerShape(8.dp), textStyle = MaterialTheme.typography.bodySmall, singleLine = true
                            )
                            ExposedDropdownMenu(expanded = callerOpen, onDismissRequest = { callerOpen = false }) {
                                callers.forEach { c ->
                                    DropdownMenuItem(
                                        text = { Text(c.name, style = MaterialTheme.typography.bodySmall) },
                                        onClick = { val i = ranges.indexOfFirst { it.id == entry.id }; if (i >= 0) ranges[i] = ranges[i].copy(callerId = c.id); callerOpen = false }
                                    )
                                }
                            }
                        }
                    }
                    if (ranges.size > 1 && enabled) {
                        IconButton(onClick = { ranges.removeAll { it.id == entry.id } }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    AnimatedVisibility(visible = resultText != null) {
        val fg = if (resultIsError) Color(0xFFC62828) else Color(0xFF2E7D32)
        Card(colors = CardDefaults.cardColors(containerColor = if (resultIsError) Color(0xFFFFEBEE) else Color(0xFFE8F5E9)), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (resultIsError) Icons.Default.Error else Icons.Default.CheckCircle, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(resultText ?: "", style = MaterialTheme.typography.bodySmall, color = fg)
            }
        }
    }

    Button(
        onClick = {
            onAssign(
                RangeAssignment.plan(
                    ranges.toList().map {
                        RangeAssignment.Range(
                            from = it.from.toIntOrNull(),
                            to = it.to.toIntOrNull(),
                            callerId = it.callerId,
                        )
                    },
                    orderedTargetIds,
                    busyRowIndices,
                )
            )
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled && !busy && ranges.any { it.from.isNotEmpty() && it.to.isNotEmpty() && it.callerId.isNotEmpty() }
    ) {
        Icon(Icons.Default.AssignmentInd, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(assignButtonText, fontWeight = FontWeight.SemiBold)
    }
}

// Friendly label + colour for a session-call response status (Thing 4: pool-assignment per-caller
// summary + per-number response).
//
// EVERY status the backend can store must appear here. It used to fall through to "Pending" for
// anything unlisted, which meant call_back_later and deferred — two real, filed answers — were
// shown to the admin as if the caller had never rung. Callers saw people they had personally
// answered for sitting under "Pending" and reported responses as lost; nothing was lost, the
// screen simply had no name for those two outcomes.
private fun callStatusDisplay(status: String): Pair<String, Color> = when (status) {
    "attended" -> "Attended" to Color(0xFF2E7D32)
    "will_attend" -> "Will attend" to Color(0xFF2E7D32)
    "called" -> "Called" to Color(0xFF1565C0)
    "call_back_later" -> "Asked to call back" to Color(0xFF6A1B9A)
    "deferred" -> "Scheduled later" to Color(0xFF00695C)
    "no_answer" -> "No answer" to Color(0xFFB26A00)
    "wont_attend" -> "Won't attend" to Color(0xFFC62828)
    "pending" -> "Not called" to Color(0xFF757575)
    // An unrecognised status is a gap in THIS list, not an un-called person. Show it verbatim so a
    // new backend outcome is obviously unmapped instead of silently reading as "never rung".
    else -> status.replace("_", " ").replaceFirstChar { it.uppercase() } to Color(0xFF757575)
}

// One-line outcome summary for a caller's assigned rows, e.g. "3 will attend · 2 no answer · 15 not called".
// Driven by the statuses actually present rather than a hard-coded whitelist: the old fixed list
// silently DROPPED call_back_later and deferred, so the parts never added up to the caller's
// assigned total and work that had been done simply vanished from the summary.
private fun callerOutcomeSummary(statuses: List<String>): String {
    // Fixed order first so the common outcomes read consistently, then anything new, so an
    // unmapped status still shows up rather than disappearing.
    val preferred = listOf("pending", "will_attend", "call_back_later", "deferred", "called", "no_answer", "wont_attend", "attended")
    val counts = statuses.groupingBy { it }.eachCount()
    val ordered = preferred.filter { counts.containsKey(it) } + counts.keys.filterNot { it in preferred }.sorted()
    return ordered.joinToString(" · ") { key ->
        "${counts[key]} ${callStatusDisplay(key).first.lowercase()}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchPoolsScreen() {
    // ── State (backend-driven) ────────────────────────────────────────────────
    var activeTab   by remember { mutableStateOf(0) }   // 0 = Unassigned · 1 = Follow-ups · 2 = All
    var search      by remember { mutableStateOf("") }
    var refreshTick by remember { mutableStateOf(0) }
    var loading     by remember { mutableStateOf(false) }
    var loadError   by remember { mutableStateOf<String?>(null) }
    var loadedPool  by remember { mutableStateOf<List<Registration>>(emptyList()) }
    val scope = rememberCoroutineScope()

    // ── Page-level batch + session scope (Option 1: a selected session scopes the WHOLE page) ──
    // batch = null → all batches; campaign = null → Pool mode (registration pool). When a calling
    // session is picked, the page switches to that session's roster + its isolated caller assignments.
    var batchList        by remember { mutableStateOf<List<Batch>>(emptyList()) }
    // Sticky page scope. Leaving for another drawer tab disposes this screen outright, so both
    // the batch and the calling session had to be re-picked on every return - the complaint this
    // fixes. Held as an ID rather than an object, and re-validated against the freshly loaded
    // lists below before it is trusted.
    var selectedBatchId  by remember { mutableStateOf(AppDatabase.uiSelection("pool.batchId")) }
    var sessionCampaigns by remember { mutableStateOf<List<SessionCampaign>>(emptyList()) }
    var selectedCampaign by remember { mutableStateOf<SessionCampaign?>(null) }
    // All centre sessions (calling + direct-attendance) for the LOWER "Filter: session" dropdown.
    var centreSessions   by remember { mutableStateOf<List<CentreSession>>(emptyList()) }
    var sessionMembers   by remember { mutableStateOf<List<BatchMemberInfo>>(emptyList()) }
    // Every batch's members for this centre+category, so a row can be allotted to a calling session
    // even when it belongs to a DIFFERENT batch — assembling people from several batches into one
    // session is what this screen is for. Tri-state: null = the roster never loaded (do not tell the
    // admin those people have no batch), empty = the centre genuinely has none.
    var centreRoster     by remember { mutableStateOf<List<BatchMemberInfo>?>(null) }
    val sessionMode = selectedCampaign != null

    // ── Lower set: list FILTER (narrows the visible rows only — never changes page scope) ──
    // A batch (or a session, which resolves to its batch) restricts the list to people who are
    // members of that batch, by intersecting each row's mobile with the batch's member mobiles.
    // Null on both = no filtering. This works in pool mode too (pool rows carry no batch column).
    // Both are MULTI-select. Picking several batches, or several sessions, unions their people —
    // one allotment pass can then cover "everyone who came to any of these three sessions" instead
    // of forcing one round-trip through the dropdowns per session.
    // Empty set = nothing chosen = no filtering from that dropdown.
    var filterBatchIds   by remember { mutableStateOf<Set<String>>(emptySet()) }
    var filterSessionIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Tri-state and load-bearing: null = NO FILTER (show everything); empty = the filter ran and
    // matched nobody. Collapsing the two shows the whole pool when it should show none.
    var filterMobileSet by remember { mutableStateOf<Set<String>?>(null) }
    // The full rows behind a session filter, so people the pool table cannot hold (see baseItems)
    // can still be listed. Only read while filterSessionIds is non-empty, so every place that clears
    // the session filter also retires these without touching them.
    var filterSessionPeople by remember { mutableStateOf<List<SessionPerson>>(emptyList()) }
    // When a session is picked: which of its people to show, by the response they gave when called
    // and whether they attended — an encoded PoolFilter. Only meaningful with a session selected: a
    // response belongs to a calling session, not to a batch. Opens on "attended", as it always did.
    // Still a String so the allotment snapshot and the restore tap carry it unchanged; an old
    // allotment's "both" stays as it is and is fetched the old way (see PoolFilter).
    var filterInclude   by remember { mutableStateOf(PoolFilter.DEFAULT.encode()) }
    var showFilterSheet by remember { mutableStateOf(false) }
    // The session-people request failed (as opposed to genuinely matching nobody). Kept separate so
    // an unreachable backend can never masquerade as "this session had no one".
    var filterLoadFailed by remember { mutableStateOf(false) }
    // Set when the admin taps an allotment's list heading and the filter is put back to what it was
    // when that allotment was made. Named so the change to the sheet is obviously deliberate.
    var restoredFrom by remember { mutableStateOf<String?>(null) }
    // The filter the restore produced. The banner must stop claiming "this is that allotment's list"
    // the moment the admin ticks something else, or it becomes a confident lie about the sheet.
    var restoredSignature by remember { mutableStateOf<String?>(null) }
    // An allotment waiting on the "this caller already has an allotment here" prompt.
    var pendingAssign by remember { mutableStateOf<RangeAssignment.Plan?>(null) }
    // "New Registered" tab (activeTab == 3): registration-date window + which disposition types to show.
    val newRegAllTypes = listOf("new", "assigned", "in_batch", "removed_archived")
    var newRegTypes  by remember { mutableStateOf(newRegAllTypes.toSet()) }
    var newRegFromMs by remember { mutableStateOf(System.currentTimeMillis() - 30L * 86_400_000L) }
    var newRegToMs   by remember { mutableStateOf(System.currentTimeMillis()) }

    // ── Create-calling-session dialog (top set) ───────────────────────────────
    var showCreateDialog by remember { mutableStateOf(false) }

    // Load batches + calling sessions for the dropdowns (refreshes with centre/category/assignments).
    // Lifecycle state for the selected campaign
    var lifecycleWorking by remember { mutableStateOf(false) }
    var lifecycleError   by remember { mutableStateOf<String?>(null) }
    var showCompleteConfirm by remember { mutableStateOf(false) }
    var summaryPreview   by remember { mutableStateOf<SessionSummary?>(null) }

    // structureRevision is the fix for "he made a calling session and I could not see it". This
    // screen reloaded only on centre/category or the manual Refresh, so a session created by
    // somebody else showed up only after the tab was switched away and back — which disposed and
    // rebuilt the whole composable, and is exactly the workaround people had found. The selection
    // re-sync directly below is what makes a live key safe here: the admin’s chosen session is
    // matched by id against the fresh list rather than being dropped.
    LaunchedEffect(
        AppDatabase.selectedCentreId, AppDatabase.selectedCategory, refreshTick,
        AppDatabase.structureRevision,
    ) {
        batchList = AppDatabase.fetchCallingBatches()
        sessionCampaigns = AppDatabase.fetchSessionCampaigns("all")
        centreSessions = AppDatabase.fetchCentreSessions()
        // Keep the selected session in sync with freshly-loaded data (so assignments re-read).
        // ALSO drop a session belonging to the OTHER category. listSessionCampaigns filters by
        // centre and kind but NOT by category — session_campaigns has no category column, it is
        // implied by the batch — so switching Youth<->Congregation leaves the previous category's
        // calling session selected and perfectly usable-looking. batchList IS category-scoped
        // (/batches?category=), so it is the authority on whether this session belongs here.
        // Guarded on a loaded batchList: an empty one means the request failed, and clearing the
        // admin's session on a failed load would be a bug of its own.
        selectedCampaign = selectedCampaign
            ?.let { cur -> sessionCampaigns.find { it.id == cur.id } }
            ?.takeIf { cur -> batchList.isEmpty() || batchList.any { it.id == cur.batchId } }

        // Nothing selected yet — restore the admin's last scope. The guards are STRICTER than the
        // re-sync above: the campaign must exist AND batchList must have actually loaded and
        // contain its batch. A restore on an empty batchList would silently scope the page to a
        // session from the other category, which is the failure the comment above describes.
        if (selectedCampaign == null) {
            val restored = AppDatabase.uiSelection("pool.campaignId")
                ?.let { id -> sessionCampaigns.find { it.id == id } }
                ?.takeIf { cur -> batchList.any { it.id == cur.batchId } }
            if (restored != null) {
                selectedCampaign = restored
                selectedBatchId = restored.batchId
            }
        }
        // Drop a restored batch id that no longer exists in this centre+category, so the page does
        // not filter against a batch the admin cannot see. Guarded on a loaded list for the same
        // reason as above.
        if (selectedBatchId != null && batchList.isNotEmpty() && batchList.none { it.id == selectedBatchId }) {
            selectedBatchId = null
            AppDatabase.setUiSelection("pool.batchId", null)
        }
    }
    // Load the selected session's batch roster whenever the session changes.
    //
    // KEYED ON CATEGORY AND CENTRE, NOT JUST THE SESSION. Both fetches read
    // AppDatabase.selectedCategory/selectedCentreId at CALL time, so without those keys this block
    // never re-runs on a category switch and the screen keeps the roster it loaded under the old
    // one. The assign block then matches congregation rows against a youth roster, finds nothing,
    // and reports "N of the N rows above aren't in any batch yet" — a confident lie about the data,
    // because the roster is non-empty (so the "didn't load" branches don't fire) and simply belongs
    // to the wrong category. Confirmed live on 2026-08-23 afternoon: 15 congregation rows, all 15
    // reported as "not in any batch", while every one of them held an active batch_members row.
    // It also explains why tapping Refresh "fixed" it — refreshTick was the ONLY key that could
    // rescue a stale roster. selectedCategory defaults to "Youth", so a fresh login lands here easily.
    LaunchedEffect(selectedCampaign?.id, AppDatabase.selectedCentreId, AppDatabase.selectedCategory, refreshTick) {
        val camp = selectedCampaign
        sessionMembers = if (camp != null) AppDatabase.fetchBatchMembers(camp.batchId) else emptyList()
        // Only needed while a session is selected — it exists purely to resolve cross-batch rows in
        // the assign block below. Cleared with the session so a stale centre's roster can't linger.
        centreRoster = if (camp != null) AppDatabase.fetchCentreRosterMembers() else null
    }
    // Sessions offered in the (top) scope dropdown: calling sessions only (these are exactly the
    // SessionCampaigns), narrowed to the selected batch, most-recent first, last 4.
    val sessionsForDropdown = sessionCampaigns
        .filter { selectedBatchId == null || it.batchId == selectedBatchId }
        .sortedByDescending { it.scheduledAt }
        .take(4)

    // Lower-set filter builds the set of digit-only mobiles we intersect the visible rows against.
    //   • session(s) picked (filterSessionIds) → THAT SESSION'S people, per the "Show" mode:
    //     who attended it / who answered positively when called for it / both.
    //   • batch only → that batch's roster (/batch-members), i.e. everyone you could assign,
    //     whether or not a session has happened yet.
    //
    // This used to resolve a picked session to `fetchBatchMembers(session.batchId)` — the session's
    // BATCH ROSTER. Since the session dropdown is already scoped to the selected batch, that handed
    // back the exact same set as picking the batch alone, so choosing a session inside the batch
    // narrowed nothing whatsoever. That is the "selecting a session does nothing" report, and it is
    // a client-side bug: this screen never asked the backend to combine batch and session at all.
    //
    // The old code was a deliberate tradeoff ("always show the students you can assign, even before
    // a session has happened"), and that concern is real but now answered two ways: picking no
    // session still gives the full batch roster, and a calling session that has not happened yet
    // still has positive responders to show under Show → Positive response.
    // selectedCampaign is a key because "busy elsewhere" is judged against it: being in the session
    // you are assigning into is not elsewhere.
    LaunchedEffect(filterBatchIds, filterSessionIds, filterInclude, refreshTick, selectedCampaign?.id) {
        // Let the taps settle first. The batch and session dropdowns stay open for multi-select, so
        // ticking four sessions changed the keys four times and started four loads; only the last
        // one mattered. A new tap restarts this block and cancels the wait, so the list loads ONCE,
        // for everything ticked, shortly after the admin stops tapping.
        kotlinx.coroutines.delay(FILTER_SETTLE_MS)
        filterLoadFailed = false
        filterMobileSet = when {
            filterSessionIds.isNotEmpty() -> {
                // Union across every chosen session. A person who came to any one of them qualifies —
                // intersecting instead would answer "who came to ALL of these", which is not what a
                // multi-select filter row means anywhere else in this app.
                val union = mutableSetOf<String>()
                val people = mutableListOf<SessionPerson>()
                val outcomeFilter = PoolFilter.decode(filterInclude)
                // One request for every chosen session (the server unions them). The old path asked
                // once per session, one after another, which is what made this list slow.
                val legs = if (outcomeFilter != null) {
                    val one = AppDatabase.fetchSessionPeopleFiltered(filterSessionIds, outcomeFilter, selectedCampaign?.id)
                    val legacy = outcomeFilter.legacyEquivalent()
                    // A server without /pool/session-people still answers the old three questions.
                    // Those old requests are one per session, so they go out together, not in turn.
                    if (one == null && legacy != null) {
                        coroutineScope {
                            filterSessionIds.map { sid -> async { AppDatabase.fetchSessionPeople(sid, legacy, selectedCampaign?.id) } }.awaitAll()
                        }
                    } else listOf(one)
                } else {
                    // Only an old allotment's "both" gets here.
                    coroutineScope {
                        filterSessionIds.map { sid -> async { AppDatabase.fetchSessionPeople(sid, filterInclude, selectedCampaign?.id) } }.awaitAll()
                    }
                }
                for (leg in legs) {
                    if (leg == null) {
                        // Any failed leg makes the union incomplete. Say so rather than quietly
                        // under-filtering, which looks like a correct-but-short list.
                        filterLoadFailed = true
                    } else {
                        for (p in leg) if (union.add(p.mobile)) people += p
                    }
                }
                filterSessionPeople = people
                // Never fall back to null on failure — null means "no filter", which would show the
                // entire pool as though every session matched everybody.
                union
            }
            filterBatchIds.size == 1 ->
                AppDatabase.fetchBatchMembers(filterBatchIds.first()).map { it.mobile.filter(Char::isDigit) }.toSet()
            filterBatchIds.isNotEmpty() -> {
                // Several batches: ONE request for the whole centre roster, kept to the ticked
                // batches here, instead of one request per batch in turn. It is the same server
                // query (getBatchMembers with no batch_id), so the people are exactly the same.
                val roster = AppDatabase.fetchCentreRosterMembers()
                if (roster == null) {
                    filterLoadFailed = true
                    emptySet()
                } else {
                    roster.filter { it.batchId.toString() in filterBatchIds }
                        .map { it.mobile.filter(Char::isDigit) }
                        .toSet()
                }
            }
            else -> null
        }
    }

    val filterSignature = "${filterBatchIds.sorted()}|${filterSessionIds.sorted()}|$filterInclude"
    LaunchedEffect(filterSignature) {
        if (restoredSignature != null && filterSignature != restoredSignature) {
            restoredFrom = null
            restoredSignature = null
        }
    }

    // Lower "Filter: session" options are scoped to the batch in play (the lower batch filter, else
    // the top page-scope batch) — showing ALL centre sessions at once was never the intent.
    // Scope = the batches chosen in the lower filter, falling back to the top page-scope batch.
    // With nothing picked at all, fall back to EVERY batch in the current category rather than to
    // an empty scope. An empty scope made the dropdown a dead end — it opened onto "Pick a batch to
    // see its sessions" and nothing else, which reads as "this category has no sessions". That is
    // exactly how congregation looked: it has a single batch ("sunday 4 pm"), so the gate demanded
    // a choice that had only one possible answer before it would show anything.
    //
    // Safe to widen: this set decides only which sessions are OFFERED. Filtering is driven by
    // filterBatchIds/filterSessionIds, which are untouched here. And the sessions are already
    // appended under a header per batch, so the undifferentiated list the original scoping guarded
    // against does not come back.
    val lowerSessionScopeBatches: Set<String> =
        filterBatchIds
            .ifEmpty { setOfNotNull(selectedBatchId) }
            .ifEmpty { batchList.map { it.id }.toSet() }
    val lowerSessionOptions = centreSessions.filter { it.batchId != null && it.batchId in lowerSessionScopeBatches }
    // With several batches in scope the session lists are APPENDED under a header per batch, rather
    // than run together into one undifferentiated list where "8 Aug" from two batches is ambiguous.
    // Ordered by the batch order shown in the batch dropdown so the two agree.
    val lowerSessionGroups: List<Pair<String, List<CentreSession>>> =
        batchList.filter { it.id in lowerSessionScopeBatches }
            .map { b -> b.name to lowerSessionOptions.filter { it.batchId == b.id } }
            .filter { it.second.isNotEmpty() }

    // Pool semantics per tab. 0 → new pool, unassigned only; 1 → remaining follow-ups;
    // 2 → new pool, all (assigned + unassigned); 3 → "New Registered" registered-by-date list.
    val poolType       = if (activeTab == 1) "followup" else "new"
    val assignedStatus = if (activeTab == 0) "unassigned" else "all"

    fun isoDate(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(ms))

    // Load the pool whenever the tab, category, centre, or a successful assignment (refreshTick)
    // changes. Tab 3 loads the registered-by-date list (reloads when the From/To window changes);
    // all other tabs load the curated calling pool.
    LaunchedEffect(activeTab, AppDatabase.selectedCategory, AppDatabase.selectedCentreId, refreshTick, newRegFromMs, newRegToMs) {
        loading = true; loadError = null
        // null = the request itself failed; empty = the backend really has nobody. Reporting a
        // failed request as "no one registered in this date range" told the admin their import had
        // not arrived when in fact the app never got an answer — say which it was.
        val fetched = if (activeTab == 3) {
            AppDatabase.fetchRegisteredByDate(isoDate(newRegFromMs), isoDate(newRegToMs))
        } else {
            AppDatabase.fetchCallingPool(poolType, assignedStatus)
        }
        loadedPool = fetched ?: emptyList()
        loading = false
        loadError = when {
            fetched == null -> "Couldn't reach the calling backend — this is a connection problem, " +
                "not empty data. Tap Refresh; if it keeps failing, check that the server is running."
            fetched.isEmpty() && activeTab == 3 -> "No one registered in this date range."
            fetched.isEmpty() -> "No people in this pool."
            else -> null
        }
    }

    val callers = AppDatabase.users.filter { it.role == "CALLER" && it.status == "active" }

    // The pool table is a FIXED people-browser: it always shows the registration pool filtered by the
    // lower batch/session filters, and never changes when a calling session is picked at the top.
    // Selecting a calling session only drives the separate Assignment area rendered below.
    //
    // EXCEPT that a session filter answers "who came to this session", and the pool alone cannot:
    // /pool/new-registrations holds only stage=registered people, so anyone in follow-up vanished
    // from the answer. (2026-09-12 CTRA BG: 13 present, 5 listed. Those 8 were a server bug - their
    // follow-ups had closed but their stage was never released - fixed on the server 2026-09-24.)
    // Someone GENUINELY in follow-up must still be listed, with that state, so the admin can see
    // them; so with a session filter on, the session's own rows are added for anyone the pool left
    // out. They land in the "Busy elsewhere" group (busyOf) and are never allotted - one number,
    // one place - and the server refuses them too.
    //   • Unassigned tab: only people the pool dropped for their STAGE (follow-up, no registration).
    //     A registered person missing from it is missing because they hold a pool assignment, and
    //     re-adding them would put assigned people in the Unassigned tab.
    //   • All tab: anyone missing.
    //   • Never removed/archived — the pool excludes them on purpose.
    val baseItems: List<Registration> =
        if (filterSessionIds.isEmpty() || (activeTab != 0 && activeTab != 2)) loadedPool
        else {
            val inPool = loadedPool.mapTo(HashSet()) { it.mobile.filter(Char::isDigit) }
            loadedPool + filterSessionPeople.filter { p ->
                p.mobile !in inPool &&
                    p.stage != "removed" && p.stage != "archived" &&
                    (activeTab == 2 || p.stage != "registered")
            }.map { it.row }
        }

    val currentItems = baseItems.filter { reg ->
        (search.isBlank() ||
            reg.name.contains(search, ignoreCase = true) ||
            reg.mobile.contains(search, ignoreCase = true)) &&
        // Lower-set filter: keep only rows whose mobile is in the chosen batch's membership.
        (filterMobileSet == null || filterMobileSet!!.contains(reg.mobile.filter(Char::isDigit))) &&
        // New Registered tab: keep only rows whose disposition is among the selected types.
        (activeTab != 3 || reg.regTypes.any { it in newRegTypes })
    }

    // Counts come straight from the backend pool — no recomputation over a stale local blob.
    // When a calling session is selected at top, "assigned" must mean assigned to THIS session
    // (SessionCallAssignment, via assignSessionCallers -> /session-campaigns/:id/assign), not the
    // pool-wide currentAssignedCallerId field — those are two separate assignment systems that
    // don't touch each other, so without this the counters never moved after assigning callers
    // to a session.
    // When a calling session is selected the counters describe THE SESSION, taken from its own
    // assignment rows and its batch roster — not from the pool table below.
    //
    // They used to be derived by intersecting the displayed pool rows with the assigned mobiles,
    // which silently undercounts: the pool is the "new registrations" list and drops anyone whose
    // stage is followup_active, plus anyone whose last outcome mapped to mark_invalid or
    // remove_from_running. Those people are still genuinely assigned to the session — they just
    // cannot appear in that particular list — so a fully-assigned session reported a shortfall
    // (observed live: 126 assigned reported as 105, because 12 sat in follow-up and 9 had been
    // marked invalid / not-interested).
    val sessionAssignedCount = selectedCampaign?.assignmentDetails?.size
    val sessionRosterCount   = if (selectedCampaign != null) sessionMembers.size else null

    // Who is actually being called right now — the ONLY source the Caller column is allowed to use.
    // Empty unless a calling session is selected AND it is still active AND it has assignments.
    // See PoolCallerResolution for why the registration's own current_assigned_caller_id must not
    // be trusted here (147 people at this centre carry one with zero live assignments in existence).
    val liveCallers = PoolCallerResolution.liveCallerByMobile(selectedCampaign)
    val sessionIsLive = selectedCampaign?.status == "active"

    // Unassigned people first, already-assigned after — a stable partition, so within each group
    // the original order is unchanged. This is what the "#" column and the range-assign row numbers
    // below are keyed off (RangeAssignment.targetIdsForVisibleRows reads the SAME order), so grouping
    // here means "assign rows 1–N" always lands on a clean block of people who still need a caller,
    // instead of skipping over whoever already has one scattered through the list.
    // Where each listed person is held right now, if anywhere ("one number, one place"). Known only
    // for rows that came through a session filter - that is what carries the server's `busy`. For
    // any other row the server's assign guard is still the backstop, and it names who it refused.
    val busyByDigits: Map<String, PersonBusy> =
        if (filterSessionIds.isEmpty()) emptyMap()
        else filterSessionPeople.mapNotNull { p -> p.busy?.let { p.mobile to it } }.toMap()
    fun busyOf(reg: Registration): PersonBusy? = busyByDigits[reg.mobile.filter(Char::isDigit)]

    // Three groups, in this order: free -> busy elsewhere -> already in this session. Free people come
    // first so "rows 1-N" is always a clean block of people who can actually be allotted.
    val unassignedItems = currentItems.filter { PoolCallerResolution.callerFor(liveCallers, it.mobile) == null && busyOf(it) == null }
    val busyItems = currentItems.filter { PoolCallerResolution.callerFor(liveCallers, it.mobile) == null && busyOf(it) != null }
    val assignedItems = currentItems.filter { PoolCallerResolution.callerFor(liveCallers, it.mobile) != null }
    val orderedItems = unassignedItems + busyItems + assignedItems

    // Which population the three figures describe — see PoolCounts. On the assignment tabs with a
    // session selected they are the SESSION's own (roster + assignment rows, never an intersection
    // with the rows below, which undercounts). On the New Registered tab they always describe the
    // list on screen: it is a date-window browse, and headlining it with the batch roster showed
    // 463 over a list of the 99 people imported that day.
    // The lower batch/session filter is an explicit "show me this subset". Once it is on, the
    // figures must describe the subset: with a calling session selected at the top they otherwise
    // stayed pinned to the session's batch roster (463) above a filtered sheet of 21, which is
    // exactly the "numbers above the sheet don't change" report.
    val lowerFilterActive = filterMobileSet != null
    val counts = PoolCounts.of(
        activeTab = activeTab,
        visibleMobiles = currentItems.map { it.mobile },
        liveCallers = liveCallers,
        sessionRosterCount = sessionRosterCount,
        sessionAssignedCount = sessionAssignedCount,
        listIsFiltered = lowerFilterActive,
    )
    val totalCount    = counts.total
    val assignedCount = counts.assigned
    val pendingCount  = counts.pending

    // ── Per-tab range assignment state ────────────────────────────────────────
    val tab0Ranges = remember { mutableStateListOf(RangeEntry("t0_0", "", "", "")) }
    val tab1Ranges = remember { mutableStateListOf(RangeEntry("t1_0", "", "", "")) }
    val tab2Ranges = remember { mutableStateListOf(RangeEntry("t2_0", "", "", "")) }
    val tab3Ranges = remember { mutableStateListOf(RangeEntry("t3_0", "", "", "")) }
    val currentRanges = when (activeTab) { 0 -> tab0Ranges; 1 -> tab1Ranges; 3 -> tab3Ranges; else -> tab2Ranges }

    var assignResult by remember { mutableStateOf<String?>(null) }

    // Separate range state + result for the per-session Assignment area (independent of the pool tabs).
    val sessionRanges = remember { mutableStateListOf(RangeEntry("s_0", "", "", "")) }
    var sessionAssignResult by remember { mutableStateOf<String?>(null) }
    var sessionAssignFailed by remember { mutableStateOf(false) }

    val outerScroll = rememberScrollState()

    // ── UI — single scrollable column with nested student list ────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(outerScroll)
            .padding(16.dp)
    ) {
        // Title
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Pool Assignment", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${AppDatabase.selectedCategory} · ${AppDatabase.centres.find { it.id == AppDatabase.selectedCentreId }?.name ?: "Centre"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { refreshTick++ }, enabled = !loading) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (loading) "Loading…" else "Refresh")
            }
        }

        IntegrityBanner(refreshKey = refreshTick)

        Spacer(modifier = Modifier.height(12.dp))

        // ══ TOP SET — Manage calling session (switches the WHOLE page + create new) ══════════
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)),
            border = CardDefaults.outlinedCardBorder()
        ) {
          Column(modifier = Modifier.padding(12.dp)) {
            Text("Manage calling session", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Switch the whole page to a calling session, or create a new one.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var batchOpen by remember { mutableStateOf(false) }
            val batchLabel = batchList.find { it.id == selectedBatchId }?.name ?: "All batches"
            Box(modifier = Modifier.weight(1f)) {
                ExposedDropdownMenuBox(expanded = batchOpen, onExpandedChange = { batchOpen = it }) {
                    OutlinedTextField(
                        value = batchLabel, onValueChange = {}, readOnly = true,
                        label = { Text("Batch", fontSize = 10.sp) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = batchOpen) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp), textStyle = MaterialTheme.typography.bodySmall, singleLine = true
                    )
                    ExposedDropdownMenu(expanded = batchOpen, onDismissRequest = { batchOpen = false }) {
                        DropdownMenuItem(text = { Text("All batches", style = MaterialTheme.typography.bodySmall) }, onClick = {
                            selectedBatchId = null; selectedCampaign = null; batchOpen = false
                            AppDatabase.setUiSelection("pool.batchId", null)
                            AppDatabase.setUiSelection("pool.campaignId", null)
                            filterBatchIds = emptySet(); filterSessionIds = emptySet(); filterMobileSet = null
                        })
                        batchList.forEach { b ->
                            DropdownMenuItem(text = { Text("${b.name} (${b.memberCount})", style = MaterialTheme.typography.bodySmall) }, onClick = {
                                selectedBatchId = b.id
                                if (selectedCampaign?.batchId != b.id) selectedCampaign = null
                                AppDatabase.setUiSelection("pool.batchId", b.id)
                                AppDatabase.setUiSelection("pool.campaignId", selectedCampaign?.id)
                                batchOpen = false
                                // A new top-level scope invalidates any lower filter scoped to the old batch/session.
                                filterBatchIds = emptySet(); filterSessionIds = emptySet(); filterMobileSet = null
                            })
                        }
                    }
                }
            }
            var sessOpen by remember { mutableStateOf(false) }
            val sessLabel = selectedCampaign?.let { it.sessionName.ifBlank { it.batchName } } ?: "Pool (no session)"
            Box(modifier = Modifier.weight(1f)) {
                ExposedDropdownMenuBox(expanded = sessOpen, onExpandedChange = { sessOpen = it }) {
                    OutlinedTextField(
                        value = sessLabel, onValueChange = {}, readOnly = true,
                        label = { Text("Calling session", fontSize = 10.sp) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sessOpen) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp), textStyle = MaterialTheme.typography.bodySmall, singleLine = true
                    )
                    ExposedDropdownMenu(expanded = sessOpen, onDismissRequest = { sessOpen = false }) {
                        DropdownMenuItem(text = { Text("Pool (no session)", style = MaterialTheme.typography.bodySmall) }, onClick = {
                            selectedCampaign = null; sessOpen = false
                            AppDatabase.setUiSelection("pool.campaignId", null)
                            filterBatchIds = emptySet(); filterSessionIds = emptySet(); filterMobileSet = null
                        })
                        if (sessionsForDropdown.isEmpty()) {
                            DropdownMenuItem(enabled = false, text = { Text("No calling sessions for this batch", style = MaterialTheme.typography.labelSmall) }, onClick = {})
                        }
                        sessionsForDropdown.forEach { camp ->
                            DropdownMenuItem(text = {
                                Text("${camp.sessionName.ifBlank { camp.batchName }} · ${camp.batchName}", style = MaterialTheme.typography.bodySmall)
                            }, onClick = {
                                selectedCampaign = camp
                                selectedBatchId = camp.batchId
                                AppDatabase.setUiSelection("pool.campaignId", camp.id)
                                AppDatabase.setUiSelection("pool.batchId", camp.batchId)
                                sessOpen = false
                                // Switching the session scope must not carry over a stale lower filter —
                                // this is exactly what made the newly-selected session look like it had
                                // nobody to assign (a leftover attendance-based filter zeroing out rows).
                                filterBatchIds = emptySet(); filterSessionIds = emptySet(); filterMobileSet = null
                            })
                        }
                    }
                }
            }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = { showCreateDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Create calling session")
            }
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ── Search ─────────────────────────────────────────────────────────────
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Search name or mobile") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (search.isNotEmpty()) IconButton(onClick = { search = "" }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        // ══ LOWER SET — Filter people (narrows the list only; does NOT change page scope) ═══════
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            var fBatchOpen by remember { mutableStateOf(false) }
            // Never a comma-joined list: this field is weight(1f) and singleLine, so three batch
            // names would be clipped to something unreadable like "BG, HM, Bazar Sa…".
            val fBatchLabel = when (filterBatchIds.size) {
                0 -> "All batches"
                1 -> batchList.find { it.id == filterBatchIds.first() }?.name ?: "1 batch"
                else -> "${filterBatchIds.size} batches"
            }
            Box(modifier = Modifier.weight(1f)) {
                ExposedDropdownMenuBox(expanded = fBatchOpen, onExpandedChange = { fBatchOpen = it }) {
                    OutlinedTextField(
                        value = fBatchLabel, onValueChange = {}, readOnly = true,
                        label = { Text("Filter: batch", fontSize = 10.sp) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fBatchOpen) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp), textStyle = MaterialTheme.typography.bodySmall, singleLine = true
                    )
                    ExposedDropdownMenu(expanded = fBatchOpen, onDismissRequest = { fBatchOpen = false }) {
                        // "All batches" is the clear-everything item, so it DOES close the menu.
                        DropdownMenuItem(text = { Text("All batches", style = MaterialTheme.typography.bodySmall) }, onClick = {
                            filterBatchIds = emptySet(); filterSessionIds = emptySet(); fBatchOpen = false
                        })
                        batchList.forEach { b ->
                            val checked = b.id in filterBatchIds
                            DropdownMenuItem(
                                leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
                                text = { Text("${b.name} (${b.memberCount})", style = MaterialTheme.typography.bodySmall) },
                                onClick = {
                                    // Multi-select: toggle and STAY OPEN. Closing after each tap would
                                    // make picking three batches feel like the menu was misbehaving.
                                    filterBatchIds = if (checked) filterBatchIds - b.id else filterBatchIds + b.id
                                    // Drop any chosen session that no longer has a batch in scope,
                                    // otherwise an invisible session keeps filtering the list.
                                    filterSessionIds = filterSessionIds.filter { sid ->
                                        val bid = centreSessions.find { it.aeSessionId == sid }?.batchId
                                        bid != null && (filterBatchIds.isEmpty() || bid in filterBatchIds)
                                    }.toSet()
                                }
                            )
                        }
                    }
                }
            }
            var fSessOpen by remember { mutableStateOf(false) }
            val fSessLabel = when (filterSessionIds.size) {
                0 -> "All sessions"
                1 -> centreSessions.find { it.aeSessionId == filterSessionIds.first() }?.displayName ?: "1 session"
                else -> "${filterSessionIds.size} sessions"
            }
            Box(modifier = Modifier.weight(1f)) {
                // Session filter is meaningless for the registered-by-date view → grayed out on tab 3.
                val sessionFilterEnabled = activeTab != 3
                ExposedDropdownMenuBox(expanded = fSessOpen && sessionFilterEnabled, onExpandedChange = { if (sessionFilterEnabled) fSessOpen = it }) {
                    OutlinedTextField(
                        value = if (sessionFilterEnabled) fSessLabel else "N/A here", onValueChange = {}, readOnly = true,
                        enabled = sessionFilterEnabled,
                        label = { Text("Filter: session", fontSize = 10.sp) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fSessOpen) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp), textStyle = MaterialTheme.typography.bodySmall, singleLine = true
                    )
                    ExposedDropdownMenu(expanded = fSessOpen, onDismissRequest = { fSessOpen = false }) {
                        DropdownMenuItem(text = { Text("All sessions", style = MaterialTheme.typography.bodySmall) }, onClick = {
                            filterSessionIds = emptySet(); fSessOpen = false
                        })
                        // Sessions are scoped to the batches in play (lower batch filter, else the
                        // top page batch). Sessions belong to a batch — showing every centre session
                        // at once was never the intent.
                        // Only reachable before the batch list has loaded (or if the category has no
                        // batches at all) — with nothing picked the scope now falls back to every
                        // batch in the category, so this is no longer a dead end the admin has to
                        // guess their way out of.
                        if (lowerSessionScopeBatches.isEmpty()) {
                            DropdownMenuItem(enabled = false, text = { Text("No batches in this category yet", style = MaterialTheme.typography.labelSmall) }, onClick = {})
                        } else if (lowerSessionOptions.isEmpty()) {
                            DropdownMenuItem(enabled = false, text = { Text("No sessions for these batches", style = MaterialTheme.typography.labelSmall) }, onClick = {})
                        }
                        // Several batches in scope → their session lists are appended, each under its
                        // own batch header. With one batch the header is redundant, so it is omitted
                        // and the list looks exactly as it did before.
                        lowerSessionGroups.forEach { (batchName, sessions) ->
                            if (lowerSessionGroups.size > 1) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    enabled = false,
                                    text = {
                                        Text(
                                            batchName.uppercase(),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    onClick = {}
                                )
                            }
                            sessions.forEach { sess ->
                                val tag = if (sess.kind == "called") "📞 calling" else "direct"
                                val dateSuffix = sess.date?.let { " · $it" } ?: ""
                                val checked = sess.aeSessionId in filterSessionIds
                                DropdownMenuItem(
                                    leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
                                    text = {
                                        Text("${sess.displayName} · $tag$dateSuffix", style = MaterialTheme.typography.bodySmall)
                                    },
                                    onClick = {
                                        // Toggle and stay open — see the batch dropdown above.
                                        filterSessionIds =
                                            if (checked) filterSessionIds - sess.aeSessionId
                                            else filterSessionIds + sess.aeSessionId
                                        // Keep the session's batch in scope so both dropdowns agree.
                                        // ADD rather than replace: picking sessions from two batches
                                        // must widen the scope, which is what makes the grouped list
                                        // above appear at all.
                                        sess.batchId?.let { if (filterBatchIds.isNotEmpty()) filterBatchIds = filterBatchIds + it }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Who to show from the picked sessions — only meaningful once a session is picked ────────
        // Used to be three fixed chips (Attended / Positive response / Need check). Those could not
        // ask "who said yes and then did not come", so they became one button that opens a sheet of
        // response groups, single responses and attended / not attended. See PoolFilter.
        if (filterSessionIds.isNotEmpty() && activeTab != 3) {
            Spacer(modifier = Modifier.height(6.dp))
            val current = PoolFilter.decode(filterInclude)
            OutlinedButton(
                onClick = { showFilterSheet = true },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Default.FilterAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Show: " + (current?.describe() ?: Allotment.includeLabel(filterInclude)),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            if (showFilterSheet) {
                PoolFilterSheet(
                    sessionIds = filterSessionIds,
                    initial = current ?: PoolFilter.DEFAULT,
                    onApply = { filterInclude = it.encode(); showFilterSheet = false },
                    onDismiss = { showFilterSheet = false },
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── POOL TABLE tabs (scrollable — the pool table is a fixed people-browser) ──
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            fun lbl(base: String, tab: Int) = if (activeTab == tab) "$base ($totalCount)" else base
            // Follow-ups tab removed per design (follow-ups are data, not a pool tab). Tab index 1 is
            // intentionally left unused so the other tab indices / pool logic stay unchanged.
            FilterChip(selected = activeTab == 0, onClick = { activeTab = 0; assignResult = null }, label = { Text(lbl("Unassigned", 0)) })
            FilterChip(selected = activeTab == 2, onClick = { activeTab = 2; assignResult = null }, label = { Text(lbl("All", 2)) })
            // Clearing the session filter here must also clear the batch filter. Picking a session
            // in the lower dropdown ADDS to filterBatchIds as a side effect, so clearing only the
            // session left a batch filter the admin never chose still narrowing the list — and a
            // freshly imported person who is not in that batch silently vanished from a tab whose
            // whole premise is "who registered in this date window".
            FilterChip(selected = activeTab == 3, onClick = {
                activeTab = 3; assignResult = null
                filterSessionIds = emptySet(); filterBatchIds = emptySet(); filterMobileSet = null
            }, label = { Text(lbl("New Registered", 3)) })
        }

        // ── New Registered controls: registration-date window + which types to show (tab 3 only) ──
        if (activeTab == 3) {
            Spacer(modifier = Modifier.height(8.dp))
            var showFromPicker by remember { mutableStateOf(false) }
            var showToPicker   by remember { mutableStateOf(false) }
            var typesMenuOpen  by remember { mutableStateOf(false) }
            // These describe WHERE a person currently sits, not how recently they registered —
            // the date window above already decides that. The backend derives them as
            // assigned = has a live caller assignment, in_batch = is in an active batch, and
            // new = NEITHER of those (pool.service.ts). "new" is therefore a residual, not a
            // synonym for recent: anything imported lands in a batch, so a person imported five
            // minutes ago is "in_batch" and never "new". The old label "New (unassigned)" read as
            // "recently registered", so narrowing to it hid every freshly imported person on the
            // one tab built to show them. Say what the filter actually does instead.
            val typeLabels = mapOf(
                "new" to "Not in a batch yet",
                "assigned" to "Assigned to a caller",
                "in_batch" to "In a batch",
                "removed_archived" to "Removed / Archived"
            )

            // From / To date pickers
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showFromPicker = true }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                    Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("From ${isoDate(newRegFromMs)}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                OutlinedButton(onClick = { showToPicker = true }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                    Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("To ${isoDate(newRegToMs)}", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }

            // Which types to show (multi-select)
            Spacer(modifier = Modifier.height(6.dp))
            Box {
                val typeSummary = when {
                    newRegTypes.size == newRegAllTypes.size -> "All types"
                    newRegTypes.isEmpty() -> "None selected"
                    else -> newRegTypes.mapNotNull { typeLabels[it] }.joinToString(", ")
                }
                Column {
                    AssistChip(
                        onClick = { typesMenuOpen = true },
                        leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("Show: $typeSummary", maxLines = 1) }
                    )
                    // Narrowing this filter used to be indistinguishable from the data being
                    // missing: people registered inside the date window simply were not on screen
                    // and nothing said why. Always account for them.
                    val hiddenByType = baseItems.count { reg -> reg.regTypes.none { it in newRegTypes } }
                    if (hiddenByType > 0) {
                        Text(
                            "$hiddenByType more registered in this window, hidden by this filter.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                DropdownMenu(expanded = typesMenuOpen, onDismissRequest = { typesMenuOpen = false }) {
                    newRegAllTypes.forEach { t ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = t in newRegTypes, onCheckedChange = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(typeLabels[t] ?: t)
                                }
                            },
                            onClick = { newRegTypes = if (t in newRegTypes) newRegTypes - t else newRegTypes + t }
                        )
                    }
                }
            }

            if (showFromPicker) {
                val pickerState = rememberDatePickerState(initialSelectedDateMillis = newRegFromMs)
                DatePickerDialog(
                    onDismissRequest = { showFromPicker = false },
                    confirmButton = { TextButton(onClick = { pickerState.selectedDateMillis?.let { newRegFromMs = it }; showFromPicker = false }) { Text("OK") } },
                    dismissButton = { TextButton(onClick = { showFromPicker = false }) { Text("Cancel") } }
                ) { DatePicker(state = pickerState) }
            }
            if (showToPicker) {
                val pickerState = rememberDatePickerState(initialSelectedDateMillis = newRegToMs)
                DatePickerDialog(
                    onDismissRequest = { showToPicker = false },
                    confirmButton = { TextButton(onClick = { pickerState.selectedDateMillis?.let { newRegToMs = it }; showToPicker = false }) { Text("OK") } },
                    dismissButton = { TextButton(onClick = { showToPicker = false }) { Text("Cancel") } }
                ) { DatePicker(state = pickerState) }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Load summary when a completed session is selected (used by the Assignment area below).
        LaunchedEffect(selectedCampaign?.id, selectedCampaign?.status) {
            if (selectedCampaign?.status == "completed" && summaryPreview?.campaignId != selectedCampaign?.id) {
                summaryPreview = AppDatabase.fetchSessionSummary(selectedCampaign!!.id)
            }
        }

        // ── Stats counter (pool — the fixed pool table's own counts) ──────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$totalCount", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    // Name the population the numbers describe — "Total" over a pool table while the
                    // figures came from a session was most of why the mismatch was so hard to read.
                    Text(
                        counts.totalLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(modifier = Modifier.width(1.dp).height(36.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$assignedCount", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                    Text("Assigned", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(modifier = Modifier.width(1.dp).height(36.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$pendingCount", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    Text("To Assign", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // A figure directly above a list is read as counting that list. Where it does not,
            // say so with both numbers, rather than relying on the tile caption alone — that was
            // already tried ("Total" -> "In batch") and 463 over a 99-row list still read as broken.
            if (counts.describesSession && counts.showing != counts.total) {
                Text(
                    "These are the calling session's figures — ${counts.total} people are in its " +
                        "batch. The list below shows ${counts.showing}.",
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (!counts.describesSession && sessionRosterCount != null) {
                Text(
                    if (lowerFilterActive)
                        "Filtered — these figures count the ${counts.showing} people listed below. " +
                            "The calling session's own batch holds $sessionRosterCount. Clear the " +
                            "filter to see the session's figures again."
                    else
                        "These figures count the list below, not the calling session — its batch holds " +
                            "$sessionRosterCount people in total.",
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // The filter was just put back from an allotment heading. Say so — the sheet changing
            // under the admin without explanation is exactly the "everything refreshed and I lost
            // my list" complaint, only in reverse.
            restoredFrom?.let { heading ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Showing the list this allotment came from — $heading.",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                    TextButton(onClick = {
                        filterBatchIds = emptySet(); filterSessionIds = emptySet(); restoredFrom = null
                    }) { Text("Clear", style = MaterialTheme.typography.labelSmall) }
                }
            }
            // Say why the Caller column is blank, rather than leaving it to be read as lost data.
            // "Assigned" above is that session's final figure; nobody is being called for it now.
            if (selectedCampaign != null && !sessionIsLive) {
                Text(
                    "This session is over — its allotments are below. Nobody is being called from " +
                        "this list right now, so the Caller column is blank.",
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (selectedCampaign == null) {
                Text(
                    "No calling session selected — pick one above to see who each person is assigned to.",
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ── Student list — fixed-height scrollable window ──────────────────────
        Card(modifier = Modifier.fillMaxWidth(), border = CardDefaults.outlinedCardBorder()) {
            Box(modifier = Modifier.fillMaxWidth().height(260.dp)) {
                if (currentItems.isEmpty()) {
                    // A lower filter can still narrow the list to zero (e.g. filtering a pool by a batch
                    // none of the pool people belong to). Distinguish that from "genuinely nothing here"
                    // so it's obvious how to recover.
                    val filterActive = filterBatchIds.isNotEmpty() || filterSessionIds.isNotEmpty()
                    val filteredToZero = filterActive && baseItems.isNotEmpty() && search.isBlank() && loadError == null
                    // The "Show:" type filter can empty the list too, and it is a separate control
                    // from the batch/session filter above — saying "no people in this pool" while
                    // rows had in fact been fetched and then hidden is what made an import look lost.
                    val hiddenByTypeOnly = activeTab == 3 && baseItems.isNotEmpty() &&
                        search.isBlank() && !filterActive && loadError == null
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                when {
                                    loadError != null -> loadError!!
                                    // A failed request must never read as "this session had nobody".
                                    filterLoadFailed -> "Couldn't load the people for this filter — the calling " +
                                        "backend didn't answer. This is a connection problem, not an empty list. Tap Refresh."
                                    search.isNotBlank() -> "No one matches \"$search\"."
                                    // The filter narrows THIS POOL TAB; it cannot conjure people the tab
                                    // doesn't hold. Someone who answered positively but is already assigned
                                    // is absent from the Unassigned tab by definition — say so, rather than
                                    // letting it look like the session filter is broken.
                                    filteredToZero && filterSessionIds.isNotEmpty() -> {
                                        val n = filterSessionIds.size
                                        val subject = if (n == 1) "that session" else "any of those $n sessions"
                                        // Name the filter rather than guess at the reason: with any mix
                                        // of responses and attendance possible, a fixed sentence per
                                        // mode would state the wrong thing for most of them.
                                        val outcome = PoolFilter.decode(filterInclude)
                                        when {
                                            outcome == PoolFilter.DEFAULT ->
                                                "Nobody in this tab attended $subject. If it hasn't happened yet, " +
                                                    "tap Show and pick the Positive responses instead."
                                            outcome != null ->
                                                "Nobody in this tab matches \"${outcome.describe()}\" for $subject."
                                            else -> "Nobody in this tab attended $subject or answered positively for it."
                                        } + " (This tab holds ${baseItems.size} people.)"
                                    }
                                    filteredToZero -> "Filtered to zero — the selected filter excludes everyone in this pool."
                                    hiddenByTypeOnly -> "${baseItems.size} people registered in this date range, but the " +
                                        "\"Show:\" filter hides all of them. Tap Show: and tick more options."
                                    else -> "No people in this pool."
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (filteredToZero) {
                                Spacer(modifier = Modifier.height(6.dp))
                                TextButton(onClick = { filterBatchIds = emptySet(); filterSessionIds = emptySet(); filterMobileSet = null }) {
                                    Text("Clear filter")
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("#", modifier = Modifier.width(30.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Student", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Caller", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        val poolRow: @Composable (Int, Registration) -> Unit = { index, reg ->
                            // Resolved from the selected LIVE session's own assignment rows, never
                            // from reg.callerAssignedId — that field is a leftover that is never
                            // cleared when a session ends, so it kept showing callers' names months
                            // after the work finished. See PoolCallerResolution.
                            val assignedCallerName = PoolCallerResolution.callerFor(liveCallers, reg.mobile)
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .background(if (index % 2 == 0) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${index + 1}", modifier = Modifier.width(30.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(reg.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                    if (reg.mobile.isNotEmpty()) Text(reg.mobile, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    // Where they are held right now - and so why they can't be allotted.
                                    val held = if (assignedCallerName == null) busyOf(reg) else null
                                    if (held != null) Text(
                                        held.label + (held.caller?.let { " · $it" } ?: ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFB26A00),
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                if (assignedCallerName != null) StatusBadge(text = assignedCallerName.split(" ").first(), status = "active")
                                else if (busyOf(reg) != null) StatusBadge(text = "Busy", status = "warning")
                                else StatusBadge(text = "—", status = "pending")
                            }
                        }
                        // Two groups, not one interleaved list — unassigned first so a range-assign
                        // of "rows 1–N" always covers exactly the people who still need a caller.
                        if (unassignedItems.isNotEmpty()) {
                            item {
                                Text(
                                    "Unassigned · ${unassignedItems.size}",
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        itemsIndexed(unassignedItems) { index, reg -> poolRow(index, reg) }
                        if (busyItems.isNotEmpty()) {
                            item {
                                Text(
                                    "Busy elsewhere · ${busyItems.size} — can't be assigned until they come back",
                                    modifier = Modifier.fillMaxWidth()
                                        .background(Color(0xFFB26A00).copy(alpha = 0.10f))
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFB26A00),
                                )
                            }
                        }
                        itemsIndexed(busyItems) { index, reg -> poolRow(unassignedItems.size + index, reg) }
                        if (assignedItems.isNotEmpty()) {
                            item {
                                Text(
                                    "Already assigned · ${assignedItems.size}",
                                    modifier = Modifier.fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        itemsIndexed(assignedItems) { index, reg -> poolRow(unassignedItems.size + busyItems.size + index, reg) }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // The old general "Assign by Range" (pool → caller, no session) tool was removed: all caller
        // assignment now happens through calling sessions only. Pick a calling session above to
        // assign its roster to callers via the session assignment area below.
        if (!sessionMode) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            ) {
                Text(
                    "Pick or create a calling session above to assign these people to callers.",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ══ ASSIGNMENT AREA — the selected calling session's allotments (who → whom) ═════════════
        // Independent of the pool table above. Shows this session's assignment rows and stays
        // available until the session is Completed; once Retrieved (closed), assigning is LOCKED
        // (no new rows) but the existing allotments stay visible.
        if (sessionMode) {
            val camp = selectedCampaign!!
            val campStatus = camp.status  // "active" | "closed" | "completed"

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = when (campStatus) {
                    "completed" -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    "closed"    -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    else        -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                })
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Assignments · ${camp.sessionName.ifBlank { camp.batchName }}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                "Batch ${camp.batchName} · ${when (campStatus) { "completed" -> "completed"; "closed" -> "retrieval — locked"; else -> "defined" }}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            when (campStatus) {
                                "active" -> {
                                    OutlinedButton(
                                        onClick = {
                                            lifecycleError = null; lifecycleWorking = true
                                            scope.launch {
                                                val ok = AppDatabase.closeSessionCampaign(camp.id)
                                                if (ok) { val fresh = AppDatabase.fetchSessionCampaigns("all"); sessionCampaigns = fresh; selectedCampaign = fresh.find { it.id == camp.id } }
                                                else lifecycleError = "Failed to close session"
                                                lifecycleWorking = false
                                            }
                                        },
                                        enabled = !lifecycleWorking,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Retrieve", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                "closed" -> {
                                    TextButton(
                                        onClick = {
                                            lifecycleError = null; lifecycleWorking = true
                                            scope.launch {
                                                val ok = AppDatabase.reopenSessionCampaign(camp.id)
                                                if (ok) { val fresh = AppDatabase.fetchSessionCampaigns("all"); sessionCampaigns = fresh; selectedCampaign = fresh.find { it.id == camp.id } }
                                                else lifecycleError = "Failed to reopen"
                                                lifecycleWorking = false
                                            }
                                        },
                                        enabled = !lifecycleWorking,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) { Text("Reopen", style = MaterialTheme.typography.labelSmall) }
                                    Button(
                                        onClick = {
                                            lifecycleError = null; lifecycleWorking = true
                                            scope.launch {
                                                summaryPreview = AppDatabase.fetchSessionSummary(camp.id)
                                                showCompleteConfirm = true
                                                lifecycleWorking = false
                                            }
                                        },
                                        enabled = !lifecycleWorking,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Completed", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                else -> { /* completed — no buttons */ }
                            }
                        }
                    }
                    if (lifecycleError != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(lifecycleError!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (campStatus == "completed") {
                // Completed → follow-ups for this session are cleared; show the result summary.
                val summary = summaryPreview
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Session Complete", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        if (summary == null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Loading summary…", style = MaterialTheme.typography.bodySmall)
                        } else {
                            // Concise completion report. Strict additive hierarchy:
                            //   total = came + absent ;  absent = kept-in-roster + removed + skipped
                            val greenC  = Color(0xFF2E7D32)
                            val blueC   = Color(0xFF1565C0)
                            val redC    = MaterialTheme.colorScheme.error
                            val muted   = MaterialTheme.colorScheme.onSurfaceVariant
                            val rate    = if (summary.total > 0) summary.came.toFloat() / summary.total else 0f
                            val ratePct = (rate * 100).toInt()

                            // Attendance bar
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Attendance", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Text("${summary.came}/${summary.total} · $ratePct%",
                                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                                    color = if (ratePct >= 50) greenC else redC)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                Box(modifier = Modifier.fillMaxWidth(rate.coerceIn(0f, 1f)).height(8.dp).background(greenC))
                            }

                            // Came / Absent
                            Spacer(modifier = Modifier.height(14.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${summary.came}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = greenC)
                                    Text("Came", style = MaterialTheme.typography.labelSmall, color = muted)
                                }
                                Box(modifier = Modifier.width(1.dp).height(36.dp).background(MaterialTheme.colorScheme.outlineVariant))
                                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${summary.absent}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = redC)
                                    Text("Absent", style = MaterialTheme.typography.labelSmall, color = muted)
                                }
                            }

                            // Absentee outcomes — labels + numbers only
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Absentee outcomes", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            listOf(
                                Triple("Kept in roster", summary.toFollowup, blueC),
                                Triple("Removed", summary.toRemove, redC),
                                Triple("Skipped (no record)", summary.skippedNoReg, muted)
                            ).forEach { (label, count, color) ->
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(label, style = MaterialTheme.typography.bodyMedium)
                                    Text("$count", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = color)
                                }
                            }
                            val accounted = summary.toFollowup + summary.toRemove + summary.skippedNoReg
                            if (accounted != summary.absent) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("⚠ ${summary.absent - accounted} unaccounted", style = MaterialTheme.typography.labelSmall, color = redC, fontWeight = FontWeight.Medium)
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Text("Follow-ups closed", style = MaterialTheme.typography.labelSmall, color = muted)

                            // Call responses — labels + numbers only
                            if (summary.byResponse.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider()
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Call responses", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(4.dp))
                                val niceLabel = mapOf(
                                    "will_attend" to "Will attend",
                                    "wont_attend" to "Won't attend",
                                    "no_answer"   to "No answer",
                                    "called"      to "Called",
                                    "pending"     to "Not called"
                                )
                                summary.byResponse.entries.sortedByDescending { it.value }.forEach { (label, count) ->
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(niceLabel[label] ?: label.replace("_", " ").replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall)
                                        Text("$count", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                // Export Report panel for completed session campaigns
                val campaignKey = selectedCampaign?.id
                var exportField by remember(campaignKey) { mutableStateOf<String?>(null) }
                var exportOrder by remember(campaignKey) { mutableStateOf<String?>(null) }
                var exportCohort by remember(campaignKey) { mutableStateOf<String?>(null) }
                var exportBusy by remember(campaignKey) { mutableStateOf(false) }
                var exportMessage by remember(campaignKey) { mutableStateOf<String?>(null) }
                var exportSuccess by remember(campaignKey) { mutableStateOf(false) }

                var menuClassesAttended by remember(campaignKey) { mutableStateOf(false) }
                var menuByCallers by remember(campaignKey) { mutableStateOf(false) }
                var menuNewOld by remember(campaignKey) { mutableStateOf(false) }

                val canDownload = !exportBusy && (
                    exportField == "direct_called" ||
                    (exportField in listOf("classes_attended", "by_caller") && exportOrder != null) ||
                    (exportField == "new_old" && exportCohort != null)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Export Report", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Download a detailed CSV breakdown for this session.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // 1) Class attended
                                Box(modifier = Modifier.weight(1f)) {
                                    val isSelected = exportField == "classes_attended"
                                    val isEnabled = exportField == null || isSelected
                                    OutlinedButton(
                                        onClick = {
                                            if (isSelected) {
                                                exportField = null; exportOrder = null; exportCohort = null
                                            } else {
                                                menuClassesAttended = true
                                            }
                                        },
                                        enabled = isEnabled,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = if (isSelected) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors()
                                    ) {
                                        Text(
                                            text = when {
                                                isSelected && exportOrder == "asc" -> "Class: Asc"
                                                isSelected && exportOrder == "desc" -> "Class: Desc"
                                                else -> "Class attended"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = menuClassesAttended,
                                        onDismissRequest = { menuClassesAttended = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Ascending") },
                                            onClick = {
                                                exportField = "classes_attended"
                                                exportOrder = "asc"
                                                exportCohort = null
                                                menuClassesAttended = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Descending") },
                                            onClick = {
                                                exportField = "classes_attended"
                                                exportOrder = "desc"
                                                exportCohort = null
                                                menuClassesAttended = false
                                            }
                                        )
                                    }
                                }

                                // 2) By callers
                                Box(modifier = Modifier.weight(1f)) {
                                    val isSelected = exportField == "by_caller"
                                    val isEnabled = exportField == null || isSelected
                                    OutlinedButton(
                                        onClick = {
                                            if (isSelected) {
                                                exportField = null; exportOrder = null; exportCohort = null
                                            } else {
                                                menuByCallers = true
                                            }
                                        },
                                        enabled = isEnabled,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = if (isSelected) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors()
                                    ) {
                                        Text(
                                            text = when {
                                                isSelected && exportOrder == "asc" -> "Callers: Asc"
                                                isSelected && exportOrder == "desc" -> "Callers: Desc"
                                                else -> "By callers"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = menuByCallers,
                                        onDismissRequest = { menuByCallers = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Ascending") },
                                            onClick = {
                                                exportField = "by_caller"
                                                exportOrder = "asc"
                                                exportCohort = null
                                                menuByCallers = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Descending") },
                                            onClick = {
                                                exportField = "by_caller"
                                                exportOrder = "desc"
                                                exportCohort = null
                                                menuByCallers = false
                                            }
                                        )
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // 3) New / Old
                                Box(modifier = Modifier.weight(1f)) {
                                    val isSelected = exportField == "new_old"
                                    val isEnabled = exportField == null || isSelected
                                    OutlinedButton(
                                        onClick = {
                                            if (isSelected) {
                                                exportField = null; exportOrder = null; exportCohort = null
                                            } else {
                                                menuNewOld = true
                                            }
                                        },
                                        enabled = isEnabled,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = if (isSelected) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors()
                                    ) {
                                        Text(
                                            text = when {
                                                isSelected && exportCohort == "new" -> "Cohort: New"
                                                isSelected && exportCohort == "old" -> "Cohort: Old"
                                                else -> "New / Old"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = menuNewOld,
                                        onDismissRequest = { menuNewOld = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("New") },
                                            onClick = {
                                                exportField = "new_old"
                                                exportCohort = "new"
                                                exportOrder = null
                                                menuNewOld = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Old") },
                                            onClick = {
                                                exportField = "new_old"
                                                exportCohort = "old"
                                                exportOrder = null
                                                menuNewOld = false
                                            }
                                        )
                                    }
                                }

                                // 4) Direct or Called
                                Box(modifier = Modifier.weight(1f)) {
                                    val isSelected = exportField == "direct_called"
                                    val isEnabled = exportField == null || isSelected
                                    OutlinedButton(
                                        onClick = {
                                            if (isSelected) {
                                                exportField = null; exportOrder = null; exportCohort = null
                                            } else {
                                                exportField = "direct_called"
                                                exportOrder = null
                                                exportCohort = null
                                            }
                                        },
                                        enabled = isEnabled,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = if (isSelected) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors()
                                    ) {
                                        Text(
                                            text = if (isSelected) "Direct / Called" else "Direct or Called",
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (exportField != null) {
                                TextButton(
                                    onClick = { exportField = null; exportOrder = null; exportCohort = null },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("Clear selection", style = MaterialTheme.typography.labelSmall)
                                }
                            } else {
                                Spacer(modifier = Modifier.width(1.dp))
                            }

                            Button(
                                onClick = {
                                    exportBusy = true
                                    exportMessage = null
                                    exportSuccess = false
                                    scope.launch {
                                        val (ok, msg) = AppDatabase.downloadSessionReport(
                                            campaignId = selectedCampaign!!.id,
                                            field = exportField!!,
                                            order = exportOrder,
                                            cohort = exportCohort
                                        )
                                        exportBusy = false
                                        exportSuccess = ok
                                        exportMessage = msg
                                    }
                                },
                                enabled = canDownload,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                if (exportBusy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Downloading…", style = MaterialTheme.typography.labelSmall)
                                } else {
                                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Download", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        if (exportMessage != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = exportMessage!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (exportSuccess) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            } else {
                // Still used by the "Assign callers to this session" range-assign block below.
                //
                // ORDER IS LOAD-BEARING. targetIdsForVisibleRows resolves by mobile and keeps the
                // FIRST match, so putting this session's own batch first means everyone who was
                // already assignable resolves to exactly the id they resolved to before — the centre
                // roster only ever fills gaps it could not fill. A person who is in both this batch
                // and another is therefore allotted through THIS session's batch, which is what the
                // completion/eviction logic (scoped to campaign.batchId) expects to find.
                val centreRosterFailed = centreRoster == null
                val roster = sessionMembers + (centreRoster ?: emptyList())
                // Active or Retrieved → live per-caller assignment view: every assigned row grouped
                // by caller, an expand button revealing that caller's rows, a note (shown beside the
                // session name in their My Callings), and an instant soft-remove per person. Updates
                // live off camp.assignmentDetails, so it reflects new assignments as they're made —
                // even mid-assignment, without needing to leave this screen.
                // One card per ALLOTMENT, not per caller: a caller given people from two different
                // lists gets two cards, each headed with the list it came from. Grouping by caller
                // alone silently swallowed the second allotment into the first. Rows written before
                // provenance existed have no key and still collapse into one un-headed card per
                // caller, so old sessions look exactly as they did. See Allotment.group.
                val allotmentGroups = Allotment.group(camp.assignmentDetails)
                var expandedGroupId by remember(camp.id) { mutableStateOf<String?>(null) }
                val noteDrafts = remember(camp.id) { mutableStateMapOf<String, String>() }
                var noteSavingCallerId by remember(camp.id) { mutableStateOf<String?>(null) }
                var removingAssignmentId by remember(camp.id) { mutableStateOf<String?>(null) }

                Text("Allotted callers (${camp.assignmentDetails.size})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))

                if (allotmentGroups.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth(), border = CardDefaults.outlinedCardBorder()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            Text("No one assigned to this session yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    // The note belongs to the (campaign, caller) pair, not to one allotment, so it
                    // is edited on the caller's FIRST card only — two editors for one note would
                    // let the admin type into one and lose it by saving the other.
                    val firstGroupIdOfCaller = allotmentGroups
                        .groupBy { it.callerId }
                        .mapValues { (_, gs) -> "${gs.first().callerId}|${gs.first().key}" }

                    allotmentGroups.forEach { group ->
                        val callerId = group.callerId
                        val callerName = group.callerName
                        val rows = group.rows
                        val groupId = "$callerId|${group.key}"
                        val expanded = expandedGroupId == groupId
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), border = CardDefaults.outlinedCardBorder()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(callerName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        // "9 assigned · rows 7–15" — the count alone never said WHICH
                                        // rows, so an allotment could not be checked against the sheet.
                                        Text(
                                            buildString {
                                                append("${rows.size} assigned")
                                                group.rowRange?.let { append(" · rows $it") }
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        // The list this allotment was picked from. Tapping it puts the
                                        // filter back the way it was, so the admin can see the sheet
                                        // these people came off instead of rebuilding it from memory.
                                        group.label?.let { heading ->
                                            val restorable = Allotment.decode(group.filter)
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Surface(
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = if (restorable == null) Modifier else Modifier.clickable {
                                                    val b = restorable.batchIds.toSet()
                                                    val s = restorable.sessionIds.toSet()
                                                    filterBatchIds = b
                                                    filterSessionIds = s
                                                    // Old chip values come back as their new-format
                                                    // equivalent, so the Show button can display them.
                                                    val inc = PoolFilter.normalize(restorable.include)
                                                    filterInclude = inc
                                                    restoredFrom = heading
                                                    restoredSignature =
                                                        "${b.sorted()}|${s.sorted()}|$inc"
                                                },
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    if (restorable != null) {
                                                        Icon(
                                                            Icons.Default.FilterAlt,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(13.dp),
                                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                    }
                                                    Text(
                                                        heading,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                        fontWeight = FontWeight.Medium,
                                                    )
                                                }
                                            }
                                        }
                                        // Thing 4: per-caller outcome summary line
                                        val summary = callerOutcomeSummary(rows.map { it.callStatus })
                                        if (summary.isNotEmpty()) {
                                            Text(summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                    IconButton(onClick = { expandedGroupId = if (expanded) null else groupId }) {
                                        Icon(
                                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = if (expanded) "Collapse" else "Expand"
                                        )
                                    }
                                }
                                if (expanded) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    // One note per caller per session — so it is edited on their
                                    // first card only, even when they hold several allotments.
                                    if (firstGroupIdOfCaller[callerId] == groupId) {
                                        var noteText by remember(camp.id, callerId) { mutableStateOf(noteDrafts[callerId] ?: "") }
                                        AppTextField(
                                            value = noteText,
                                            onValueChange = { noteText = it; noteDrafts[callerId] = it },
                                            label = "Note for $callerName (shown in their My Callings)"
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        TextButton(
                                            onClick = {
                                                noteSavingCallerId = callerId
                                                scope.launch {
                                                    AppDatabase.setSessionCallerNote(camp.id, callerId, noteText)
                                                    noteSavingCallerId = null
                                                }
                                            },
                                            enabled = noteSavingCallerId != callerId
                                        ) { Text(if (noteSavingCallerId == callerId) "Saving…" else "Save note") }
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }
                                    rows.forEach { row ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(row.batchMemberName ?: row.batchMemberMobile, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                                if (row.batchMemberName != null) Text(row.batchMemberMobile, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            // Thing 4: the response this number was given, on the right
                                            val (respLabel, respColor) = callStatusDisplay(row.callStatus)
                                            Surface(color = respColor.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)) {
                                                Text(respLabel, style = MaterialTheme.typography.labelSmall, color = respColor, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                            }
                                            IconButton(
                                                onClick = {
                                                    removingAssignmentId = row.id
                                                    scope.launch {
                                                        val ok = AppDatabase.removeSessionCallAssignment(camp.id, row.id)
                                                        if (ok) {
                                                            val fresh = AppDatabase.fetchSessionCampaigns("all")
                                                            sessionCampaigns = fresh
                                                            selectedCampaign = fresh.find { it.id == camp.id }
                                                        }
                                                        removingAssignmentId = null
                                                    }
                                                },
                                                enabled = removingAssignmentId != row.id
                                            ) {
                                                Icon(Icons.Default.RemoveCircleOutline, contentDescription = "Remove from this session", tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (campStatus != "active") {
                    Text("This session is retrieved — its assignments are locked. Reopen it to add or change assignments.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Row numbers mean the numbers in the "#" column of the table at the top of this
                // page — the filtered sheet the admin is actually reading — NOT this session's
                // batch roster. Those are different lists of different lengths in different
                // orders; conflating them handed callers people the admin had never seen.
                // orderedItems, not currentItems: the table groups unassigned-before-assigned, and
                // the row numbers here must match what's actually on screen.
                val assignTargets = RangeAssignment.targetIdsForVisibleRows(
                    visibleMobiles = orderedItems.map { it.mobile },
                    roster = roster,
                )
                val notInBatch = assignTargets.count { it == null }

                // Does the allotment, and stamps it with the list it came from so the card above can
                // be headed with that list and can put the filter back later.
                // `replace` soft-removes what those callers already hold in this session first.
                val performAssign: (RangeAssignment.Plan, Boolean) -> Unit = { plan, replace ->
                    sessionAssignResult = "Assigning…"
                    scope.launch {
                        val stamp = System.currentTimeMillis()
                        val heading = Allotment.label(
                            sessionNames = filterSessionIds.mapNotNull { sid ->
                                centreSessions.find { it.aeSessionId == sid }?.displayName
                            },
                            batchNames = filterBatchIds.mapNotNull { bid -> batchList.find { it.id == bid }?.name },
                            include = filterInclude,
                        )
                        val allotments = plan.byCaller.keys.map { cId ->
                            AllotmentWrite(
                                callerId = cId,
                                // A fresh key per caller per press: that is what keeps this
                                // allotment separate from one the caller already has.
                                key = Allotment.newKey(cId, stamp),
                                label = heading,
                                filter = Allotment.encode(
                                    AllotmentSource(
                                        // The lower session dropdown only offers sessions of the
                                        // batches in scope, so a sessions-only allotment has to
                                        // carry that batch too — otherwise restoring it filters by
                                        // a session the dropdown cannot show as ticked. Adding the
                                        // batch changes nothing about who is listed: the session
                                        // branch of the filter wins whenever sessions are chosen.
                                        batchIds = if (filterSessionIds.isNotEmpty())
                                            filterBatchIds.ifEmpty { setOfNotNull(selectedBatchId) }.toList()
                                        else filterBatchIds.toList(),
                                        sessionIds = filterSessionIds.toList(),
                                        include = filterInclude,
                                        rows = plan.rowsByCaller[cId].orEmpty(),
                                    )
                                ),
                                replace = replace,
                            )
                        }
                        val pairs = plan.byCaller.flatMap { (cId, ids) -> ids.map { Pair(it, cId) } }
                        val error = AppDatabase.assignSessionCallersOrError(camp.id, pairs, allotments)
                        val ok = error == null
                        sessionAssignFailed = !ok
                        sessionAssignResult = if (!ok) error else buildString {
                            append("Assigned ${pairs.size} call(s) from rows ${plan.coveredFrom}–${plan.coveredTo}.")
                            if (replace) append(" Previous people for those callers were removed.")
                            // Never let a partial allotment read as a complete one.
                            if (plan.unassignableRows > 0)
                                append(
                                    if (centreRosterFailed)
                                        " ${plan.unassignableRows} row(s) skipped — the centre roster didn't load, " +
                                            "so rows outside ${camp.batchName} couldn't be matched. Tap Refresh and re-check."
                                    else
                                        " ${plan.unassignableRows} row(s) skipped — not in any batch yet."
                                )
                            if (plan.busyRows > 0)
                                append(" ${plan.busyRows} row(s) skipped — busy elsewhere (follow-up or another session's calling).")
                            if (plan.outOfRangeRows > 0)
                                append(" ${plan.outOfRangeRows} row number(s) past the end of the table.")
                        }
                        if (ok) refreshTick++
                    }
                }

                RangeAssignBlock(
                    title = "Assign callers to this session",
                    subtitle = buildString {
                        append("Row numbers from the table above (")
                        append(currentItems.size)
                        append(if (currentItems.size == 1) " row" else " rows")
                        if (lowerFilterActive) append(", filtered")
                        append("). Each range → one caller (this session only).")
                    },
                    ranges = sessionRanges,
                    callers = callers,
                    orderedTargetIds = assignTargets,
                    busyRowIndices = busyItems.indices.map { unassignedItems.size + it }.toSet(),
                    resultIsError = sessionAssignFailed,
                    enabled = campStatus == "active",
                    busy = lifecycleWorking,
                    resultText = sessionAssignResult,
                    assignButtonText = "Assign to this session",
                    onAssign = { plan ->
                        if (plan.assignedCount == 0) {
                            // Say WHICH way it came to nothing — "enter a valid range" is wrong and
                            // confusing when the range was fine and the people simply aren't in the
                            // batch this session calls.
                            sessionAssignResult = when {
                                plan.busyRows > 0 && plan.unassignableRows == 0 ->
                                    "Everyone in those rows is busy elsewhere — in a follow-up or waiting " +
                                        "for a call in another session. They can be assigned once they come back."
                                // A roster that never arrived makes EVERY row unassignable. Saying
                                // "they aren't in this batch" would be a confident lie about the
                                // data — the same failure-vs-empty conflation filterLoadFailed and
                                // loadError exist to prevent everywhere else on this screen.
                                plan.unassignableRows > 0 && roster.isEmpty() ->
                                    "The member lists haven't loaded, so nothing can be matched " +
                                        "yet. Tap Refresh and try again."
                                plan.unassignableRows > 0 && centreRosterFailed ->
                                    "The centre-wide roster didn't load, so rows outside " +
                                        "${camp.batchName} can't be matched yet. Tap Refresh and try again."
                                plan.unassignableRows > 0 ->
                                    "None of those rows can be allotted — they aren't in any batch " +
                                        "yet, so there is no membership to allot to this session."
                                plan.outOfRangeRows > 0 ->
                                    "That range is past the end of the table (${currentItems.size} rows)."
                                else -> "Enter valid ranges and pick a caller first."
                            }
                        } else if (plan.byCaller.keys.any { cId -> camp.assignmentDetails.any { it.callerId == cId } }) {
                            // This caller already holds people in this session. Merging silently is
                            // what hid a second allotment inside the first, and replacing silently
                            // would throw away work — so ask which was meant.
                            pendingAssign = plan
                        } else {
                            performAssign(plan, false)
                        }
                    }
                )

                // ── "This caller already has an allotment here" ───────────────────────────
                pendingAssign?.let { plan ->
                    val clashing = plan.byCaller.keys.filter { cId -> camp.assignmentDetails.any { it.callerId == cId } }
                    val names = clashing.mapNotNull { cId -> callers.find { it.id == cId }?.name }
                        .ifEmpty { listOf("that caller") }
                    val heldNow = camp.assignmentDetails.count { it.callerId in clashing }
                    val nameList = names.joinToString(", ")
                    AlertDialog(
                        onDismissRequest = { pendingAssign = null },
                        title = { Text("$nameList already has people here") },
                        text = {
                            Text(
                                "$nameList already hold $heldNow ${if (heldNow == 1) "person" else "people"} in this " +
                                    "session, and you are allotting ${plan.assignedCount} more. Keeping them separate " +
                                    "gives this allotment its own heading, so you can still see which list each one " +
                                    "came from.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        confirmButton = {
                            Column {
                                TextButton(onClick = { pendingAssign = null; performAssign(plan, false) }) {
                                    Text("Keep as a separate allotment")
                                }
                                TextButton(onClick = { pendingAssign = null; performAssign(plan, true) }) {
                                    Text(
                                        "Replace — drop the $heldNow they hold now",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                TextButton(onClick = { pendingAssign = null }) { Text("Cancel") }
                            }
                        },
                    )
                }
                if (notInBatch > 0 && campStatus == "active") {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        when {
                            roster.isEmpty() ->
                                "The member lists haven't loaded, so no row can be matched yet. This is a " +
                                    "loading problem, not an empty batch — tap Refresh."
                            centreRosterFailed ->
                                "The centre-wide roster didn't load, so $notInBatch row(s) outside " +
                                    "${camp.batchName} can't be allotted yet. This is a loading problem — " +
                                    "tap Refresh."
                            else ->
                                "$notInBatch of the ${currentItems.size} rows above aren't in any batch yet " +
                                    "and can't be allotted. They keep their row numbers — only they are skipped."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    // ── Complete session confirm dialog ──────────────────────────────────────
    if (showCompleteConfirm) {
        val preview = summaryPreview
        // Outstanding responses are a WARNING, not a block.
        //
        // This used to disable the button entirely, and the backend returned 409 to match, so a
        // single caller who never opened the app could hold a finished session open indefinitely.
        // Neither side blocks now: an unanswered assignment is excluded from the strike tally, so
        // it costs the person nothing, and a late response is still accepted after completion and
        // re-runs the keep/remove decision. The count is shown so the decision is informed.
        val pendingResponses = preview?.byResponse?.get("pending") ?: 0
        val hasPending = pendingResponses > 0
        AlertDialog(
            onDismissRequest = { if (!lifecycleWorking) { showCompleteConfirm = false } },
            title = { Text("Mark session completed?") },
            text = {
                Column {
                    Text("This maps absent members to Follow-up or Removed. Responses that arrive after completion are still accepted and applied.", style = MaterialTheme.typography.bodySmall)
                    if (hasPending) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("$pendingResponses response(s) not collected yet", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "You can still complete. These people are NOT counted absent and take no strike — " +
                                        "if their caller syncs later, the response is accepted and applied then.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                    if (preview != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Preview", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("• ${preview.came} came · ${preview.absent} absent", style = MaterialTheme.typography.bodySmall)
                                Text("• ${preview.toFollowup} → kept in roster (registered pool)", style = MaterialTheme.typography.bodySmall)
                                Text("• ${preview.toRemove} → Removed", style = MaterialTheme.typography.bodySmall)
                                if (preview.skippedNoReg > 0) Text("• ${preview.skippedNoReg} skipped (no registration)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        lifecycleWorking = true
                        scope.launch {
                            val summary = AppDatabase.completeSessionCampaign(selectedCampaign!!.id)
                            if (summary != null) {
                                summaryPreview = summary
                                val fresh = AppDatabase.fetchSessionCampaigns("all")
                                sessionCampaigns = fresh
                                selectedCampaign = fresh.find { it.id == selectedCampaign?.id }
                                showCompleteConfirm = false
                            } else {
                                lifecycleError = "Failed to complete session"
                                showCompleteConfirm = false
                            }
                            lifecycleWorking = false
                        }
                    },
                    enabled = !lifecycleWorking
                ) { Text(if (lifecycleWorking) "Applying…" else "Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showCompleteConfirm = false }, enabled = !lifecycleWorking) { Text("Cancel") }
            }
        )
    }

    // ── Create calling session dialog (top set) — registers in BOTH systems (AE + SRKB) ──
    if (showCreateDialog) {
        var newName by remember { mutableStateOf("") }
        var newBatchId by remember { mutableStateOf(selectedBatchId ?: batchList.firstOrNull()?.id) }
        var newDateCal by remember { mutableStateOf<Calendar?>(null) }
        var showWheelPicker by remember { mutableStateOf(false) }
        var creating by remember { mutableStateOf(false) }
        var createErr by remember { mutableStateOf<String?>(null) }
        var batchPickOpen by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!creating) showCreateDialog = false },
            title = { Text("Create calling session") },
            text = {
                Column {
                    Text("Becomes a session in both systems and appears in the dropdown above.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    AppTextField(value = newName, onValueChange = { newName = it }, label = "Session name")
                    Spacer(modifier = Modifier.height(10.dp))
                    ExposedDropdownMenuBox(expanded = batchPickOpen, onExpandedChange = { batchPickOpen = it }) {
                        OutlinedTextField(
                            value = batchList.find { it.id == newBatchId }?.name ?: "Select batch",
                            onValueChange = {}, readOnly = true,
                            label = { Text("Batch") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = batchPickOpen) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(), shape = RoundedCornerShape(8.dp), singleLine = true
                        )
                        ExposedDropdownMenu(expanded = batchPickOpen, onDismissRequest = { batchPickOpen = false }) {
                            batchList.forEach { b ->
                                DropdownMenuItem(text = { Text("${b.name} (${b.memberCount})") }, onClick = { newBatchId = b.id; batchPickOpen = false })
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(onClick = { showWheelPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(newDateCal?.let { formatPickedDateTime(it) } ?: "Pick date & time")
                    }
                    if (createErr != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(createErr!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !creating && newName.isNotBlank() && newBatchId != null && newDateCal != null,
                    onClick = {
                        creating = true; createErr = null
                        scope.launch {
                            val created = AppDatabase.createPlannedSession(newName.trim(), newBatchId!!, formatForScheduling(newDateCal!!))
                            creating = false
                            if (created == null) {
                                createErr = "Couldn't create — check your connection and try again."
                            } else {
                                showCreateDialog = false
                                refreshTick++   // reload sessionCampaigns, then scope to the new one
                                val fresh = AppDatabase.fetchSessionCampaigns("all")
                                sessionCampaigns = fresh
                                fresh.find { it.aeSessionId == created.id }?.let {
                                    selectedCampaign = it; selectedBatchId = it.batchId
                                }
                            }
                        }
                    }
                ) { Text(if (creating) "Creating…" else "Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }, enabled = !creating) { Text("Cancel") } }
        )

        if (showWheelPicker) {
            WheelDateTimePickerDialog(
                initialCalendar = newDateCal ?: Calendar.getInstance(),
                onDismiss = { showWheelPicker = false },
                onConfirm = { cal -> newDateCal = cal; showWheelPicker = false }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ATTENDANCE SYNC CONFIG SCREEN — with sync progress bar
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AttendanceSyncConfigScreen() {
    var urlVal by remember { mutableStateOf(AppDatabase.backendUrl) }
    var configLocked by remember { mutableStateOf(true) }
    var testing by remember { mutableStateOf(false) }
    var testResultText by remember { mutableStateOf<String?>(null) }
    var testResultIsSuccess by remember { mutableStateOf(false) }
    var lastUpdateMs by remember { mutableStateOf<Long?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refreshLastUpdate() {
        checkingUpdate = true
        lastUpdateMs = AppDatabase.fetchMasterLastUpdate()
        checkingUpdate = false
    }

    LaunchedEffect(Unit) { refreshLastUpdate() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("AttendEase Sync Config", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Connect to the Python/Flask AttendEase backend and import session records.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Connection Setup", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    StatusBadge(text = if (configLocked) "Locked" else "Editable", status = if (configLocked) "inactive" else "pending")
                }
                Spacer(modifier = Modifier.height(8.dp))

                AppTextField(
                    value = urlVal,
                    onValueChange = { urlVal = it },
                    label = "Backend URL",
                    leadingIcon = Icons.Default.Link,
                    enabled = !configLocked
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Test result
                AnimatedVisibility(visible = testResultText != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = if (testResultIsSuccess) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (testResultIsSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (testResultIsSuccess) Color(0xFF2E7D32) else Color(0xFFC62828),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(testResultText ?: "", style = MaterialTheme.typography.bodySmall, color = if (testResultIsSuccess) Color(0xFF2E7D32) else Color(0xFFC62828))
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            testing = true
                            testResultText = null
                            scope.launch {
                                val (ok, msg) = AppDatabase.probeAttendanceBackend(urlVal)
                                testResultIsSuccess = ok
                                testResultText = msg
                                testing = false
                            }
                        },
                        // Testing an already-saved URL is just as useful as testing an edit, so this
                        // is no longer gated behind unlocking the field.
                        enabled = !testing
                    ) {
                        if (testing) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Test")
                    }
                    Button(onClick = {
                        if (configLocked) {
                            configLocked = false
                        } else {
                            AppDatabase.backendUrl = urlVal
                            configLocked = true
                            testResultText = "URL saved: $urlVal"
                            testResultIsSuccess = true
                        }
                    }) {
                        Icon(if (configLocked) Icons.Default.Lock else Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (configLocked) "Unlock" else "Save & Lock")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // This screen used to list sessions with per-session "Import" buttons. Nothing was ever
        // imported — the button ran a 1.5-second fake progress bar and then posted an "Import
        // Complete" notification. Sync is automatic now (attendance is pushed as it is marked), so
        // the honest replacement is to report the live state rather than offer a button that lies.
        Text("Sync status", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(6.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (AppDatabase.isOnline) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (AppDatabase.isOnline) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (AppDatabase.isOnline) "Connected — attendance syncs as it is marked"
                            else "Offline — marks are queued on this device",
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "There is nothing to import by hand.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Master workbook last written", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            when {
                                checkingUpdate -> "Checking…"
                                lastUpdateMs != null -> java.text.SimpleDateFormat("d MMM yyyy, h:mm a", java.util.Locale.getDefault())
                                    .format(java.util.Date(lastUpdateMs!!))
                                else -> "Unavailable — backend not reachable"
                            },
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    IconButton(onClick = { scope.launch { refreshLastUpdate() } }, enabled = !checkingUpdate) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                val pendingMarks = AppDatabase.offlineQueue.count { it.status == "pending" }
                val stuckMarks = AppDatabase.offlineQueue.count { it.status == "failed_needs_attention" }
                Text(
                    "Queued offline marks: $pendingMarks waiting" +
                        if (stuckMarks > 0) " · $stuckMarks need attention" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (stuckMarks > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MANAGE USERS SCREEN — approve requests, add/reset/remove callers & registerers
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ManageUsersScreen() {
    var activeTab by remember { mutableStateOf(0) }
    var usersList by remember { mutableStateOf(AppDatabase.users) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Reset-password dialog
    var selectedUserForReset by remember { mutableStateOf<User?>(null) }
    var adminResetPasswordText by remember { mutableStateOf("") }
    var resetError by remember { mutableStateOf<String?>(null) }
    var showResetPassword by remember { mutableStateOf(false) }

    // Add-user dialog
    var showAddDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newUsername by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var newRole by remember { mutableStateOf("CALLER") }
    var newAeAccess by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }

    // Change-username dialog
    var selectedUserForUsername by remember { mutableStateOf<User?>(null) }
    var changeUsernameText by remember { mutableStateOf("") }

    // Confirm-delete dialog
    var selectedUserForDelete by remember { mutableStateOf<User?>(null) }

    fun reload() { scope.launch { AppDatabase.fetchUsers(); usersList = AppDatabase.users } }

    LaunchedEffect(Unit) { isLoading = true; AppDatabase.fetchUsers(); usersList = AppDatabase.users; isLoading = false }

    val tabItems = listOf(
        Triple("Requests", usersList.count { it.status == "pending" }, Icons.Default.HourglassTop),
        Triple("Active", usersList.count { it.status == "active" }, Icons.Default.CheckCircle),
        Triple("Inactive", usersList.count { it.status == "inactive" }, Icons.Default.Block)
    )
    val filteredList = when (activeTab) {
        0 -> usersList.filter { it.status == "pending" }
        1 -> usersList.filter { it.status == "active" }
        else -> usersList.filter { it.status == "inactive" }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Account Management", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Approve requests, manage callers & registerers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        TabRow(selectedTabIndex = activeTab) {
            tabItems.forEachIndexed { idx, (label, count, _) ->
                Tab(
                    selected = activeTab == idx,
                    onClick = { activeTab = idx },
                    text = { Text("$label${if (count > 0) " ($count)" else ""}") }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (isLoading) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (filteredList.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(tabItems[activeTab].third, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No ${tabItems[activeTab].first.lowercase()} users.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filteredList) { u ->
                    val centreName = AppDatabase.centres.find { it.id == u.centreId }?.name ?: u.centreId
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(u.name, fontWeight = FontWeight.Bold)
                                    Text("Username: ${u.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Password: ••••••••", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                                        StatusBadge(text = centreName, status = "active")
                                        StatusBadge(text = u.role, status = if (u.role == "ADMIN") "pending" else "active")
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp)) {
                                            Row(modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text("Caller", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Surface(
                                            color = if (u.isAeAccessEnabled) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Row(modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    if (u.isAeAccessEnabled) Icons.Default.EventAvailable else Icons.Default.EventBusy,
                                                    contentDescription = null, modifier = Modifier.size(10.dp),
                                                    tint = if (u.isAeAccessEnabled) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                                )
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    "Registerer",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (u.isAeAccessEnabled) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                                StatusBadge(text = u.status, status = u.status)
                            }

                            // Workspace-role picker for non-pending users. One control sets the whole
                            // permission bundle: Registerer (attendance/registration only), Caller
                            // (calling/follow-ups only), or Both. Replaces the old AE-only switch.
                            if (u.status != "pending") {
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Workspace Role", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Text("Registerer marks attendance; Caller works the calling list; Both does either.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(6.dp))
                                val roleOptions = listOf("registerer" to "Registerer", "caller" to "Caller", "both" to "Both")
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                        .padding(4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    roleOptions.forEach { (value, label) ->
                                        val selected = u.roleMode == value
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .background(
                                                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                    RoundedCornerShape(6.dp)
                                                )
                                                .clickable(enabled = !selected) {
                                                    scope.launch {
                                                        val r = AppDatabase.setUserRoleApi(u.id, value)
                                                        reload()
                                                        val msg = when {
                                                            r.verified -> "Role set to $label — verified ✓"
                                                            r.success -> "Role saved — access still syncing…"
                                                            else -> "Couldn't update role — try again"
                                                        }
                                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                                .padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                label,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                when (u.status) {
                                    "pending" -> {
                                        TextButton(
                                            onClick = { selectedUserForDelete = u },
                                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                        ) { Text("Remove") }
                                        Spacer(modifier = Modifier.width(4.dp))
                                        OutlinedButton(onClick = {
                                            scope.launch { AppDatabase.rejectUserApi(u.id); reload() }
                                        }) { Text("Reject") }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(onClick = {
                                            scope.launch { AppDatabase.approveUserApi(u.id); reload() }
                                        }) { Text("Approve") }
                                    }
                                    "active" -> {
                                        TextButton(
                                            onClick = { selectedUserForDelete = u },
                                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                        ) { Text("Delete") }
                                        TextButton(onClick = { selectedUserForUsername = u; changeUsernameText = u.username }) {
                                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Username")
                                        }
                                        TextButton(onClick = { selectedUserForReset = u; adminResetPasswordText = ""; resetError = null; showResetPassword = false }) {
                                            Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Password")
                                        }
                                        OutlinedButton(onClick = {
                                            scope.launch { AppDatabase.deactivateUserApi(u.id); reload() }
                                        }) { Text("Deactivate") }
                                    }
                                    else -> {
                                        TextButton(
                                            onClick = { selectedUserForDelete = u },
                                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                        ) { Text("Delete") }
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Button(onClick = {
                                            scope.launch { AppDatabase.activateUserApi(u.id); reload() }
                                        }) { Text("Re-Activate") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Reset Password Dialog ──────────────────────────────────────────────────
    selectedUserForReset?.let { u ->
        AlertDialog(
            onDismissRequest = { selectedUserForReset = null },
            icon = { Icon(Icons.Default.Key, contentDescription = null) },
            title = { Text("Reset Password — ${u.name}") },
            text = {
                Column {
                    Text("Set a temporary password. ${u.name} will be asked to change it on next login.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = adminResetPasswordText,
                        onValueChange = { adminResetPasswordText = it; resetError = null },
                        label = { Text("New Password (min 8 chars)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showResetPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showResetPassword = !showResetPassword }) {
                                Icon(if (showResetPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = if (showResetPassword) "Hide" else "Show")
                            }
                        }
                    )
                    resetError?.let { err ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val ok = AppDatabase.adminResetPasswordApi(u.username, adminResetPasswordText)
                            if (ok) { selectedUserForReset = null; adminResetPasswordText = "" }
                            else resetError = "Reset failed — check connection"
                        }
                    },
                    enabled = adminResetPasswordText.length >= 8
                ) { Text("Apply & Force Change") }
            },
            dismissButton = { TextButton(onClick = { selectedUserForReset = null }) { Text("Cancel") } }
        )
    }

    // ── Change Username Dialog ─────────────────────────────────────────────────
    selectedUserForUsername?.let { u ->
        AlertDialog(
            onDismissRequest = { selectedUserForUsername = null },
            icon = { Icon(Icons.Default.Edit, contentDescription = null) },
            title = { Text("Change Username — ${u.name}") },
            text = {
                Column {
                    Text("Current: ${u.username}")
                    Spacer(modifier = Modifier.height(12.dp))
                    AppTextField(value = changeUsernameText, onValueChange = { changeUsernameText = it }, label = "New Username")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val ok = AppDatabase.changeUsernameApi(u.id, changeUsernameText.trim())
                            if (ok) { selectedUserForUsername = null; reload() }
                        }
                    },
                    enabled = changeUsernameText.isNotBlank() && changeUsernameText != u.username
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { selectedUserForUsername = null }) { Text("Cancel") } }
        )
    }

    // ── Confirm Delete Dialog ──────────────────────────────────────────────────
    selectedUserForDelete?.let { u ->
        AlertDialog(
            onDismissRequest = { selectedUserForDelete = null },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete ${u.name}?") },
            text = { Text("This permanently removes @${u.username} from the system. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            AppDatabase.deleteUserApi(u.id)
                            selectedUserForDelete = null
                            reload()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { selectedUserForDelete = null }) { Text("Cancel") } }
        )
    }

    // ── Add User Dialog ────────────────────────────────────────────────────────
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
            title = { Text("Add Caller / Registerer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTextField(value = newName, onValueChange = { newName = it }, label = "Full Name")
                    AppTextField(value = newUsername, onValueChange = { newUsername = it; addError = null }, label = "Username / Mobile")
                    AppTextField(value = newPassword, onValueChange = { newPassword = it; addError = null }, label = "Password (min 6 chars)")
                    Text("Role", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = newRole == "CALLER",
                            onClick = { newRole = "CALLER" },
                            label = { Text("Caller") }
                        )
                        FilterChip(
                            selected = newRole == "ADMIN",
                            onClick = { newRole = "ADMIN" },
                            label = { Text("Admin") }
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Attendance (AE) Access", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = newAeAccess, onCheckedChange = { newAeAccess = it })
                    }
                    addError?.let { err ->
                        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val ok = AppDatabase.createAdminUserApi(newName.trim(), newUsername.trim(), newPassword, newRole, newAeAccess)
                            if (ok) {
                                showAddDialog = false
                                newName = ""; newUsername = ""; newPassword = ""; newRole = "CALLER"; newAeAccess = false; addError = null
                                reload()
                            } else {
                                addError = "Failed — username may already exist"
                            }
                        }
                    },
                    enabled = newName.isNotBlank() && newUsername.isNotBlank() && newPassword.length >= 6
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CENTRE DIRECTORY — Responses / Remarks / Addresses
//
// Replaces the old "Dictionary Options" screen, which listed every DropdownContext the database
// has ever had — including `batch_calling` and `removed_reuse`, features that no longer exist —
// and offered all seventeen raw DropdownAction enum names ("move_to_followup") to centre admins
// who have never seen an enum. It also listed the address and remark lists read-only, because
// until now they were global and only the host machine could write them.
//
// Three tabs, scoped to the admin's own centre. The response editor makes a contradiction
// UNSELECTABLE rather than accepting it and warning afterwards: choosing "Keep calling them"
// simply removes "They said yes" from the meanings on offer. The table that decides lives in
// ResponseRules, mirroring the server's, and the server still normalises every write.
// ─────────────────────────────────────────────────────────────────────────────

/** A labelled picker where illegal choices are visible but not selectable. */
@Composable
private fun ConstrainedPicker(
    label: String,
    value: String,
    options: List<String>,
    display: (String) -> String,
    enabledFor: (String) -> Boolean = { true },
    // Why an option is unavailable. Shown next to the greyed entry so the rule teaches itself
    // instead of leaving the admin tapping something that does nothing.
    reasonFor: (String) -> String = { "" },
    locked: Boolean = false,
    lockedNote: String = "",
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(2.dp))
        Box {
            OutlinedButton(
                onClick = { if (!locked) expanded = true },
                enabled = !locked,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(display(value), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    Icon(if (locked) Icons.Default.Lock else Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(if (locked) 14.dp else 24.dp))
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { opt ->
                    val ok = enabledFor(opt)
                    DropdownMenuItem(
                        enabled = ok,
                        text = {
                            Column {
                                Text(display(opt), style = MaterialTheme.typography.bodySmall)
                                if (!ok && reasonFor(opt).isNotBlank()) {
                                    Text(reasonFor(opt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        onClick = { if (ok) { onPick(opt); expanded = false } },
                    )
                }
            }
        }
        if (locked && lockedNote.isNotBlank()) {
            Text(lockedNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Everything the editor holds, kept together so normalise() can rewrite it as one move. */
private data class ResponseDraft(
    val label: String = "",
    val action: String = ResponseMeaning.ACTION_CONTINUE_CALLING,
    val sentiment: String = ResponseMeaning.NOT_REACHED,
    val countsAsStrike: Boolean = false,
    val removeAfterConsecutive: String = "",
    val answeredScope: String = ResponseMeaning.SCOPE_BOTH,
    val deferDays: String = "7",
    val sortOrder: String = "50",
    val category: String = "both",
) {
    /**
     * Re-settle every field against the behaviour currently chosen.
     *
     * Called on every change so the screen can never sit in a state the server would rewrite behind
     * the admin's back — picking a behaviour that forbids the current meaning moves the meaning in
     * front of them, at that moment, rather than silently on save.
     */
    fun settled(): ResponseDraft {
        val n = ResponseRules.normalise(
            action = action,
            sentiment = sentiment,
            countsAsStrike = countsAsStrike,
            removeAfterConsecutive = removeAfterConsecutive.toIntOrNull(),
            answeredScope = answeredScope,
            deferDays = deferDays.toIntOrNull(),
        )
        return copy(
            sentiment = n.sentiment,
            countsAsStrike = n.countsAsStrike,
            removeAfterConsecutive = n.removeAfterConsecutive?.toString() ?: "",
            answeredScope = n.answeredScope,
            deferDays = (n.deferDays ?: deferDays.toIntOrNull() ?: 7).toString(),
        )
    }
}

@Composable
private fun ResponseEditorDialog(
    title: String,
    draft: ResponseDraft,
    onDraftChange: (ResponseDraft) -> Unit,
    header: (@Composable () -> Unit)? = null,
    confirmText: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val allowedSentiments = ResponseRules.allowedSentiments(draft.action)
    val strikeAllowed = ResponseRules.strikeAllowed(draft.sentiment)
    // Wider than strikeAllowed on purpose: "couldn't reach them" can never strike, but a centre may
    // still cap how many unanswered calls in a row it will carry. Gating the field on the strike
    // toggle (as this screen used to) made that policy impossible to type in at all.
    val consecutiveAllowed = ResponseRules.consecutiveRuleAllowed(draft.sentiment)
    val scopeLocked = ResponseRules.scopeLocked(draft.action)
    val isDeferral = ResponseRules.needsDeferDays(draft.action)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                header?.invoke()

                AppTextField(
                    value = draft.label,
                    onValueChange = { onDraftChange(draft.copy(label = it)) },
                    label = "Name shown to callers",
                )

                ConstrainedPicker(
                    label = "What happens when a caller picks it",
                    value = draft.action,
                    options = ResponseRules.CALLING_ACTIONS,
                    display = { ResponseRules.actionLabel(it) },
                ) { onDraftChange(draft.copy(action = it).settled()) }
                if (ResponseRules.actionHelp(draft.action).isNotBlank()) {
                    Text(
                        ResponseRules.actionHelp(draft.action),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (isDeferral) {
                    AppTextField(
                        value = draft.deferDays,
                        onValueChange = { onDraftChange(draft.copy(deferDays = it.filter(Char::isDigit).take(3))) },
                        label = "Call again after (days)",
                        keyboardType = KeyboardType.Number,
                    )
                }

                ConstrainedPicker(
                    label = "What it means",
                    value = draft.sentiment,
                    options = ResponseRules.ALL_SENTIMENTS,
                    display = { ResponseRules.sentimentLabel(it) },
                    enabledFor = { allowedSentiments.contains(it) },
                    reasonFor = { "Not possible with \"${ResponseRules.actionLabel(draft.action)}\"" },
                ) { onDraftChange(draft.copy(sentiment = it).settled()) }

                // Strike. Never available for "couldn't reach them" — failing to get through is not
                // the person's fault, and a strike eventually removes them from the programme.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Counts against them", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (strikeAllowed) "Repeated, this can take them off the calling list."
                            else "Only available for \"They said no\" — you can't hold it against " +
                                "someone that they agreed, or that you couldn't reach them.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = draft.countsAsStrike,
                        enabled = strikeAllowed && !busy,
                        onCheckedChange = { onDraftChange(draft.copy(countsAsStrike = it).settled()) },
                    )
                }

                if (consecutiveAllowed) {
                    AppTextField(
                        value = draft.removeAfterConsecutive,
                        onValueChange = { onDraftChange(draft.copy(removeAfterConsecutive = it.filter(Char::isDigit).take(2))) },
                        label = "Take them off after this many in a row (blank = never)",
                        keyboardType = KeyboardType.Number,
                    )
                    // The range is stated in words rather than left to a 422. Typing 1 here used to
                    // post and come back as a bare "Validation failed", with nothing on screen
                    // explaining that 1 is refused by design or what to use instead.
                    val outOfRange = ResponseRules.consecutiveOutOfRange(draft.removeAfterConsecutive)
                    Text(
                        if (outOfRange)
                            "Enter ${ResponseRules.MIN_CONSECUTIVE}-${ResponseRules.MAX_CONSECUTIVE}. " +
                                "For removal on a single response, set the behaviour to " +
                                "\"Taken out of the calling list\" instead - a limit of 1 is the same thing " +
                                "said less clearly, so it is not accepted."
                        else
                            "Counted only for THIS response, newest first, and the run resets the moment " +
                                "they answer anything else. Removal happens when the next such response is " +
                                "recorded - never retroactively - and anyone who has attended since the run " +
                                "began is left alone.",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (outOfRange) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ConstrainedPicker(
                    label = "Show under",
                    value = draft.answeredScope,
                    options = ResponseRules.ALL_SCOPES,
                    display = { ResponseRules.scopeLabel(it) },
                    locked = scopeLocked,
                    lockedNote = "A wrong number can be discovered either way, so this one always shows under both.",
                ) { onDraftChange(draft.copy(answeredScope = it).settled()) }

                ConstrainedPicker(
                    label = "Applies to",
                    value = draft.category,
                    options = listOf("both", "youth", "congregation"),
                    display = { it.replaceFirstChar { c -> c.uppercase() } },
                ) { onDraftChange(draft.copy(category = it)) }

                AppTextField(
                    value = draft.sortOrder,
                    onValueChange = { onDraftChange(draft.copy(sortOrder = it.filter(Char::isDigit).take(4))) },
                    label = "Position in the list",
                    keyboardType = KeyboardType.Number,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !busy && draft.label.isNotBlank() &&
                    (!isDeferral || (draft.deferDays.toIntOrNull() ?: 0) > 0),
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/** One response in the list — everything about it readable without opening the editor. */
@Composable
private fun ResponseRow(
    opt: AdminDropdownOption,
    isHost: Boolean,
    working: Boolean,
    onEdit: () -> Unit,
    onRevert: () -> Unit,
    onToggleActive: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (opt.isActive) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
    ) {
        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        opt.label,
                        fontWeight = FontWeight.Medium,
                        color = if (opt.isActive) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    if (opt.isSystem) {
                        Spacer(Modifier.width(5.dp))
                        Icon(Icons.Default.Lock, contentDescription = "Built in", modifier = Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    ResponseRules.actionLabel(opt.action) + " · " + ResponseRules.sentimentLabel(opt.sentiment),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ResponseRules.scopeLabel(opt.answeredScope),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (opt.countsAsStrike) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (opt.removeAfterConsecutive != null) "counts · ${opt.removeAfterConsecutive} in a row"
                            else "counts against them",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    opt.deferDays?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (it == 1) "next day" else "+$it days",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        // This centre changed a shared response. Say so — it explains why their
                        // wording differs from another centre's.
                        opt.isOverride -> {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(2.dp))
                            Text("Yours", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                        opt.isGlobal -> {
                            Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(2.dp))
                            Text(
                                if (isHost) "Shared by all centres" else "Shared default",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        else -> Text("Added here", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (opt.isOverride) {
                IconButton(onClick = onRevert, enabled = !working, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Undo, contentDescription = "Use the shared version again", modifier = Modifier.size(16.dp))
                }
            }
            IconButton(onClick = onEdit, enabled = !working, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp))
            }
            Switch(checked = opt.isActive, enabled = !working, onCheckedChange = onToggleActive)
        }
    }
}

/**
 * The Remarks and Addresses tabs — one body, two endpoints, because they are the same list with a
 * different name. Rows shared by every centre are shown but not editable here; the server refuses
 * them with the reason, and repeating it up front saves the round trip.
 */
@Composable
private fun FlaskListTab(
    kind: String,          // "remarks" | "addresses"
    singular: String,
    isHost: Boolean,
    onBanner: (Boolean, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var rows by remember(kind) { mutableStateOf<List<FlaskOption>>(emptyList()) }
    var loading by remember(kind) { mutableStateOf(true) }
    var working by remember(kind) { mutableStateOf(false) }
    var editing by remember(kind) { mutableStateOf<FlaskOption?>(null) }
    var editText by remember(kind) { mutableStateOf("") }
    var adding by remember(kind) { mutableStateOf(false) }
    var newText by remember(kind) { mutableStateOf("") }
    var deleting by remember(kind) { mutableStateOf<FlaskOption?>(null) }

    suspend fun reload() {
        loading = true
        rows = AppDatabase.fetchFlaskOptions(kind)
        loading = false
    }
    LaunchedEffect(kind, AppDatabase.selectedFlaskCentreId) { reload() }

    fun mutate(okMessage: String, block: suspend () -> Pair<Boolean, String?>) {
        working = true
        scope.launch {
            val (ok, err) = block()
            onBanner(!ok, if (ok) okMessage else (err ?: "The change could not be saved."))
            if (ok) reload()
            working = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Shown to registerers on the ${if (kind == "remarks") "attendance" else "registration"} screen.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { newText = ""; adding = true }, enabled = !working) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("New")
            }
        }
        if (loading || working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        if (rows.isEmpty() && !loading) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(8.dp))
                    Text("Nothing in this list yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { scope.launch { reload() } }) { Text("Reload") }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(rows, key = { "${kind}_${it.id}" }) { row ->
                    // A shared row belongs to every centre; only the host may change it.
                    val mine = !row.isShared || isHost
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(row.text, fontWeight = FontWeight.Medium)
                                Text(
                                    if (row.isShared) {
                                        if (isHost) "Shared by all centres" else "Shared — add your own to change what your centre sees"
                                    } else "Yours",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (row.isShared) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (mine) {
                                IconButton(onClick = { editing = row; editText = row.text }, enabled = !working, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Edit, contentDescription = "Rename", modifier = Modifier.size(16.dp))
                                }
                                IconButton(onClick = { deleting = row }, enabled = !working, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            } else {
                                Icon(Icons.Default.Lock, contentDescription = "Shared", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { if (!working) adding = false },
            title = { Text("New $singular") },
            text = {
                Column {
                    Text(
                        "Added for this centre only. It appears alongside the shared ones.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    AppTextField(value = newText, onValueChange = { newText = it }, label = singular.replaceFirstChar { it.uppercase() })
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val t = newText.trim()
                        mutate("\"$t\" added.") { AppDatabase.createFlaskOption(kind, t) }
                        adding = false
                    },
                    enabled = !working && newText.isNotBlank(),
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }, enabled = !working) { Text("Cancel") } },
        )
    }

    editing?.let { row ->
        AlertDialog(
            onDismissRequest = { if (!working) editing = null },
            title = { Text("Rename") },
            text = { AppTextField(value = editText, onValueChange = { editText = it }, label = singular.replaceFirstChar { it.uppercase() }) },
            confirmButton = {
                Button(
                    onClick = {
                        val t = editText.trim()
                        mutate("Renamed to \"$t\".") { AppDatabase.renameFlaskOption(kind, row.id, t) }
                        editing = null
                    },
                    enabled = !working && editText.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }, enabled = !working) { Text("Cancel") } },
        )
    }

    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { if (!working) deleting = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Remove \"${row.text}\"?") },
            text = { Text("It disappears from the list your staff pick from. Records that already used it keep it.") },
            confirmButton = {
                Button(
                    onClick = {
                        mutate("\"${row.text}\" removed.") { AppDatabase.deleteFlaskOption(kind, row.id) }
                        deleting = null
                    },
                    enabled = !working,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }, enabled = !working) { Text("Keep") } },
        )
    }
}

@Composable
fun DropdownSettingsScreen() {
    val scope = rememberCoroutineScope()
    // A centre admin has full control over their OWN centre's directory. Editing or hiding a shared
    // response does not modify it — the backend records a centre-scoped override, so no other centre
    // is affected and the change can be reverted. The host edits the shared ones themselves.
    val isHost = AppDatabase.currentUser?.role == "SUPER_ADMIN"

    var tab by rememberSaveable { mutableStateOf(0) }
    var banner by remember { mutableStateOf<Pair<Boolean, String>?>(null) }  // isError to text

    var rows by remember { mutableStateOf<List<AdminDropdownOption>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var revertTarget by remember { mutableStateOf<AdminDropdownOption?>(null) }
    var editing by remember { mutableStateOf<AdminDropdownOption?>(null) }
    var draft by remember { mutableStateOf(ResponseDraft()) }
    var showAdd by remember { mutableStateOf(false) }

    suspend fun reload() {
        loading = true
        rows = AppDatabase.fetchAdminDropdowns()
        loading = false
    }
    LaunchedEffect(AppDatabase.selectedCentreId) { reload() }

    // Runs a write, surfaces the server's own reason on failure, and only refetches on success.
    fun mutate(okMessage: String, block: suspend () -> Pair<Boolean, String?>) {
        working = true
        scope.launch {
            val (ok, err) = block()
            banner = if (ok) Pair(false, okMessage) else Pair(true, err ?: "The change could not be saved.")
            if (ok) reload()
            working = false
        }
    }

    // Only the calling context is configurable here. `batch_calling` and `removed_reuse` are gone
    // with the features that used them; follow-up outcomes are not part of this screen.
    val responses = rows.filter { it.context == "calling" }.sortedWith(compareBy({ it.sortOrder }, { it.id }))

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Directory", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            if (isHost) "Shared lists used by every centre. Changes apply everywhere, immediately."
            else "What your staff pick from. Changes affect this centre only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        TabRow(selectedTabIndex = tab) {
            listOf("Responses", "Remarks", "Addresses", "WhatsApp").forEachIndexed { i, name ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(name, style = MaterialTheme.typography.labelLarge) })
            }
        }

        banner?.let { (isError, text) ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { banner = null }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        when (tab) {
            0 -> Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${responses.count { it.isActive }} of ${responses.size} shown to callers",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { draft = ResponseDraft().settled(); showAdd = true },
                        enabled = !working,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New")
                    }
                }
                if (loading || working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                if (responses.isEmpty() && !loading) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            Spacer(Modifier.height(8.dp))
                            Text("Responses could not be loaded.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { scope.launch { reload() } }) { Text("Retry") }
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(responses, key = { "resp_${it.id}" }) { opt ->
                            ResponseRow(
                                opt = opt,
                                isHost = isHost,
                                working = working,
                                onEdit = {
                                    editing = opt
                                    draft = ResponseDraft(
                                        label = opt.label,
                                        action = opt.action ?: ResponseMeaning.ACTION_CONTINUE_CALLING,
                                        sentiment = opt.sentiment,
                                        countsAsStrike = opt.countsAsStrike,
                                        removeAfterConsecutive = opt.removeAfterConsecutive?.toString() ?: "",
                                        answeredScope = opt.answeredScope,
                                        deferDays = (opt.deferDays ?: 7).toString(),
                                        sortOrder = opt.sortOrder.toString(),
                                        category = opt.category,
                                    ).settled()
                                },
                                onRevert = { revertTarget = opt },
                                onToggleActive = { active ->
                                    val where = if (isHost) "" else " for this centre"
                                    mutate(if (active) "\"${opt.label}\" is back in the caller's list$where." else "\"${opt.label}\" hidden from callers$where.") {
                                        AppDatabase.setDropdownOptionActive(opt.id, active)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            1 -> Box(Modifier.weight(1f)) {
                FlaskListTab("remarks", "remark", isHost) { isError, text -> banner = Pair(isError, text) }
            }
            2 -> Box(Modifier.weight(1f)) {
                FlaskListTab("addresses", "address", isHost) { isError, text -> banner = Pair(isError, text) }
            }
            else -> Box(Modifier.weight(1f)) {
                WhatsappMessagesTab { isError, text -> banner = Pair(isError, text) }
            }
        }
    }

    // ── Edit ──────────────────────────────────────────────────────────────────
    editing?.let { opt ->
        ResponseEditorDialog(
            title = "Edit response",
            draft = draft,
            onDraftChange = { draft = it },
            header = {
                // Be explicit about reach: a centre admin editing a shared default is about to
                // create their own version, not change what other centres see.
                if (!isHost && opt.isGlobal) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f))) {
                        Text(
                            "This is a shared default. Saving creates your centre's own version — other centres keep theirs, and you can revert any time.",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                } else if (isHost && opt.isGlobal) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))) {
                        Text(
                            "Shared response — this change reaches every centre that has not customised it.",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            },
            confirmText = "Save",
            busy = working,
            onConfirm = {
                val d = draft.settled()
                val isDeferral = ResponseRules.needsDeferDays(d.action)
                mutate("\"${d.label.trim()}\" saved.") {
                    AppDatabase.updateDropdownOption(
                        id = opt.id,
                        label = d.label.trim(),
                        action = d.action,
                        sortOrder = d.sortOrder.toIntOrNull() ?: opt.sortOrder,
                        deferDays = if (isDeferral) d.deferDays.toIntOrNull() else null,
                        // Leaving deferral behind must drop the interval, or the response would
                        // keep a stale one that no longer matches what it does.
                        clearDeferDays = !isDeferral,
                        sentiment = d.sentiment,
                        countsAsStrike = d.countsAsStrike,
                        removeAfterConsecutive = d.removeAfterConsecutive.toIntOrNull(),
                        clearRemoveAfterConsecutive = d.removeAfterConsecutive.toIntOrNull() == null,
                        answeredScope = d.answeredScope,
                    )
                }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    // ── Revert-to-shared confirmation ─────────────────────────────────────────
    revertTarget?.let { opt ->
        AlertDialog(
            onDismissRequest = { if (!working) revertTarget = null },
            icon = { Icon(Icons.Default.Undo, contentDescription = null) },
            title = { Text("Use the shared version?") },
            text = {
                Text(
                    "\"${opt.label}\" will be replaced by the shared default again. Your centre's changes to " +
                        "this response are discarded; responses you added yourself are unaffected."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        mutate("Reverted to the shared version.") { AppDatabase.revertDropdownOverride(opt.id) }
                        revertTarget = null
                    },
                    enabled = !working
                ) { Text("Revert") }
            },
            dismissButton = { TextButton(onClick = { revertTarget = null }, enabled = !working) { Text("Keep mine") } }
        )
    }

    // ── Add ───────────────────────────────────────────────────────────────────
    if (showAdd) {
        ResponseEditorDialog(
            title = "New response",
            draft = draft,
            onDraftChange = { draft = it },
            header = {
                Text(
                    "Added for this centre only. It appears alongside the shared responses.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = "Add",
            busy = working,
            onConfirm = {
                val d = draft.settled()
                val isDeferral = ResponseRules.needsDeferDays(d.action)
                mutate("\"${d.label.trim()}\" added for this centre.") {
                    AppDatabase.createDropdownOption(
                        label = d.label.trim(),
                        context = "calling",
                        category = d.category,
                        action = d.action,
                        sortOrder = d.sortOrder.toIntOrNull() ?: 50,
                        deferDays = if (isDeferral) d.deferDays.toIntOrNull() else null,
                        sentiment = d.sentiment,
                        countsAsStrike = d.countsAsStrike,
                        removeAfterConsecutive = d.removeAfterConsecutive.toIntOrNull(),
                        answeredScope = d.answeredScope,
                    )
                }
                showAdd = false
            },
            onDismiss = { showAdd = false },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DOWNLOADS SCREEN — with refresh button and horizontal scroll for wide sheets
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun DownloadsScreen() {
    var serverFiles by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }
    var isRefreshing by remember { mutableStateOf(false) }
    var selectedSheetName by remember { mutableStateOf<String?>(null) }
    var selectedSheetData by remember { mutableStateOf<Pair<List<String>, List<Map<String, Any>>>?>(null) }
    var isLoadingSheet by remember { mutableStateOf(false) }
    // Which sheet is mid-download, and the outcome of the last one (ok to destination-or-reason).
    var downloadingName by remember { mutableStateOf<String?>(null) }
    var downloadResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun loadFiles() {
        isRefreshing = true
        serverFiles = AppDatabase.fetchServerFiles()
        isRefreshing = false
    }

    // Keyed on track AND centre, not Unit. The list is now filtered server-side by
    // ?category=, so flipping Youth/Congregation (or switching centre) has to refetch —
    // with LaunchedEffect(Unit) the screen would keep showing the previous track's sheets
    // until the user found the refresh button, which is precisely the "changing the category
    // doesn't change the tab" complaint this release is meant to end.
    LaunchedEffect(AppDatabase.selectedCategory, AppDatabase.selectedFlaskCentreId) { loadFiles() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Downloads & Exports", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Showing ${AppDatabase.selectedCategory} sheets for this centre.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = { scope.launch { loadFiles() } }) {
                if (isRefreshing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (serverFiles.isEmpty() && !isRefreshing) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(8.dp))
                    // Say WHICH list is empty. Since 2026-08-18 this tab is filtered by track,
                    // and a centre that runs no congregation class genuinely has no congregation
                    // sheets — Centre A is currently the only centre with a congregation batch.
                    // The old flat "No server files found." made that correct result look like a
                    // failure and sent people hunting for a bug that was not there.
                    Text(
                        "No ${AppDatabase.selectedCategory.lowercase()} sheets for this centre.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Sheets are listed per centre and per track. Switch the track at the top " +
                            "if you were expecting a different list.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(0.8f)
                    )
                    TextButton(onClick = { scope.launch { loadFiles() } }) { Text("Retry") }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(serverFiles) { file ->
                    val name = file["name"] ?: ""
                    val size = file["size"] ?: "0"
                    val time = file["time"] ?: ""
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                            selectedSheetName = name
                            isLoadingSheet = true
                            scope.launch { selectedSheetData = AppDatabase.fetchSheetData(name); isLoadingSheet = false }
                        }
                    ) {
                        Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(name, fontWeight = FontWeight.Bold)
                                Text("${size}B · modified $time", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row {
                                IconButton(
                                    onClick = {
                                        downloadingName = name
                                        scope.launch {
                                            val (ok, detail) = AppDatabase.downloadSheetFile(name)
                                            downloadingName = null
                                            downloadResult = Pair(ok, detail)
                                        }
                                    },
                                    enabled = downloadingName == null
                                ) {
                                    if (downloadingName == name) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Download, contentDescription = "Download", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    downloadResult?.let { (ok, detail) ->
        AlertDialog(
            onDismissRequest = { downloadResult = null },
            icon = {
                Icon(
                    if (ok) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            },
            title = { Text(if (ok) "Saved" else "Could not download") },
            text = { Text(if (ok) "Saved to $detail" else detail) },
            confirmButton = { Button(onClick = { downloadResult = null }) { Text("Close") } }
        )
    }

    if (isLoadingSheet) {
        Dialog(onDismissRequest = { isLoadingSheet = false }) {
            Card(modifier = Modifier.size(120.dp), shape = RoundedCornerShape(16.dp)) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Loading…", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    selectedSheetData?.let { data ->
        val headers = data.first
        val rows = data.second
        AlertDialog(
            onDismissRequest = { selectedSheetData = null },
            title = { Text("Sheet: $selectedSheetName", style = MaterialTheme.typography.titleSmall) },
            text = {
                Box(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                    if (headers.isEmpty()) {
                        Text("No data — empty sheet or backend offline.", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            // Headers
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                                ) {
                                    Row(modifier = Modifier.padding(8.dp)) {
                                        headers.forEach { h ->
                                            Text(h, fontWeight = FontWeight.Bold, modifier = Modifier.width(110.dp), fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                            // Rows
                            items(rows) { row ->
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    headers.forEach { h ->
                                        Text(row[h]?.toString() ?: "—", modifier = Modifier.width(110.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { selectedSheetData = null }) { Text("Close") } }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SESSION CAMPAIGN SCREEN — admin creates planned sessions and assigns callers
// ─────────────────────────────────────────────────────────────────────────────
/**
 * Stuck data, shown where it bites. The server's own checks used to report only to its logs, so 25
 * people sat invisible to the Pool page for weeks and two sessions nobody closed kept 40 people
 * "busy" - all found only when a count looked wrong. Now the admin sees it on the day. Shows
 * nothing when everything is fine or the check can't be reached.
 */
@Composable
fun IntegrityBanner(refreshKey: Any?) {
    var issues by remember { mutableStateOf<List<IntegrityIssue>>(emptyList()) }
    LaunchedEffect(refreshKey, AppDatabase.selectedCentreId) {
        issues = AppDatabase.fetchIntegrityIssues() ?: emptyList()
    }
    if (issues.isEmpty()) return
    val isError = issues.any { it.severity == "error" }
    val fg = if (isError) Color(0xFFC62828) else Color(0xFFB26A00)
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isError) Color(0xFFFFEBEE) else Color(0xFFFFF3E0)),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Needs attention", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = fg)
            }
            issues.forEach { issue ->
                Spacer(modifier = Modifier.height(6.dp))
                Text("${issue.count} · ${issue.title}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = fg)
                issue.examples.forEach { Text("• $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(issue.action, style = MaterialTheme.typography.labelSmall, color = fg)
            }
        }
    }
}

@Composable
fun SessionCampaignScreen() {
    val scope = rememberCoroutineScope()
    var campaigns by remember { mutableStateOf<List<SessionCampaign>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    // Create dialog state
    var newSessionName by remember { mutableStateOf("") }
    var selectedBatchId by remember { mutableStateOf<String?>(null) }
    var scheduledAtCal by remember { mutableStateOf<Calendar?>(null) }
    var showWheelPicker by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }

    // Assign callers state
    var assigningCampaign by remember { mutableStateOf<SessionCampaign?>(null) }
    var batchMembersForAssign by remember { mutableStateOf<List<BatchMemberInfo>>(emptyList()) }
    // Map: batchMemberId (String) -> callerId (String?)
    var memberAssignments by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var isSaving by remember { mutableStateOf(false) }
    var assignSaveError by remember { mutableStateOf<String?>(null) }

    val centreBatches = AppDatabase.batches.filter {
        it.centreId == AppDatabase.selectedFlaskCentreId && it.category == AppDatabase.selectedCategory && it.status == "active" && !it.isDefault
    }
    // Deactivated staff must not be assignable — the pool-assignment screen already filtered on
    // status and this one did not, so a removed caller still showed up as a valid choice here.
    val callers = AppDatabase.users.filter { it.role == "CALLER" && it.status == "active" }

    suspend fun reload() {
        isLoading = true
        campaigns = AppDatabase.fetchSessionCampaigns("active")
        isLoading = false
    }

    LaunchedEffect(Unit) { reload() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Planned Sessions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${campaigns.size} active campaigns", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("New Session")
            }
        }

        IntegrityBanner(refreshKey = campaigns)

        Spacer(modifier = Modifier.height(12.dp))

        if (isLoading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }

        error?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                Text(it, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(campaigns, key = { it.id }) { campaign ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(campaign.sessionName.ifBlank { campaign.batchName }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text("Batch: ${campaign.batchName} · ${campaign.scheduledAt.take(10)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${campaign.assignmentCount} callers assigned", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            OutlinedButton(
                                onClick = {
                                    assigningCampaign = campaign
                                    assignSaveError = null
                                    memberAssignments = emptyMap()
                                    scope.launch {
                                        batchMembersForAssign = AppDatabase.fetchBatchMembers(campaign.batchId)
                                        // Pre-select callers already assigned so previously-assigned rows load.
                                        memberAssignments = batchMembersForAssign.associate { m -> m.id to campaign.assignments[m.id] }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Assign Callers", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Create Planned Session Dialog ─────────────────────────────────────────
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { if (!isCreating) showCreateDialog = false },
            title = { Text("Create Planned Session") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppTextField(value = newSessionName, onValueChange = { newSessionName = it }, label = "Session Name")
                    // Was `centreBatches.take(4)` as a row of chips, which made the 5th and any later
                    // batch impossible to pick — no scroll, no overflow, silently unreachable.
                    Text("Batch", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    if (centreBatches.isEmpty()) {
                        Text(
                            "No active batches for ${AppDatabase.selectedCategory} at this centre. Create one in Batch Management first.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        var batchExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { batchExpanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        centreBatches.find { it.id == selectedBatchId }?.name ?: "Choose a batch",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1
                                    )
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                            }
                            DropdownMenu(expanded = batchExpanded, onDismissRequest = { batchExpanded = false }) {
                                centreBatches.forEach { b ->
                                    DropdownMenuItem(
                                        text = { Text(b.name, style = MaterialTheme.typography.bodySmall) },
                                        onClick = { selectedBatchId = b.id; batchExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = { showWheelPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(scheduledAtCal?.let { formatPickedDateTime(it) } ?: "Pick date & time")
                    }
                    createError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cal = scheduledAtCal
                        if (newSessionName.isNotBlank() && selectedBatchId != null && cal != null) {
                            isCreating = true
                            createError = null
                            scope.launch {
                                val result = AppDatabase.createPlannedSession(newSessionName.trim(), selectedBatchId!!, formatForScheduling(cal))
                                if (result != null) {
                                    showCreateDialog = false; newSessionName = ""; selectedBatchId = null; scheduledAtCal = null
                                    reload()
                                } else {
                                    createError = "Failed to create session — check your connection."
                                }
                                isCreating = false
                            }
                        }
                    },
                    enabled = !isCreating && newSessionName.isNotBlank() && selectedBatchId != null && scheduledAtCal != null
                ) {
                    if (isCreating) CircularProgressIndicator(modifier = Modifier.size(16.dp)) else Text("Create")
                }
            },
            dismissButton = { TextButton(onClick = { if (!isCreating) showCreateDialog = false }) { Text("Cancel") } }
        )

        if (showWheelPicker) {
            WheelDateTimePickerDialog(
                initialCalendar = scheduledAtCal ?: Calendar.getInstance(),
                onDismiss = { showWheelPicker = false },
                onConfirm = { cal -> scheduledAtCal = cal; showWheelPicker = false }
            )
        }
    }

    // ── Assign Callers Dialog ─────────────────────────────────────────────────
    assigningCampaign?.let { campaign ->
        AlertDialog(
            onDismissRequest = { if (!isSaving) assigningCampaign = null },
            title = { Text("Assign Callers — ${campaign.batchName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (batchMembersForAssign.isEmpty()) {
                        Text("Loading batch members...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        batchMembersForAssign.forEach { member ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(member.name ?: member.mobile, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                    if (member.name != null) Text(member.mobile, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                var callerExpanded by remember { mutableStateOf(false) }
                                val assignedCallerId = memberAssignments[member.id]
                                val assignedCallerName = callers.find { it.id == assignedCallerId }?.name ?: "Pick"
                                Box {
                                    OutlinedButton(
                                        onClick = { callerExpanded = true },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(assignedCallerName, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    }
                                    DropdownMenu(expanded = callerExpanded, onDismissRequest = { callerExpanded = false }) {
                                        // An assignment could previously be changed but never cleared,
                                        // even though the backend has an unassign route.
                                        DropdownMenuItem(
                                            text = { Text("Unassigned", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) },
                                            onClick = {
                                                memberAssignments = memberAssignments + (member.id to null)
                                                callerExpanded = false
                                            }
                                        )
                                        HorizontalDivider()
                                        callers.forEach { caller ->
                                            DropdownMenuItem(
                                                text = { Text(caller.name, style = MaterialTheme.typography.bodySmall) },
                                                onClick = {
                                                    memberAssignments = memberAssignments + (member.id to caller.id)
                                                    callerExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                val pairs = memberAssignments.entries
                    .filter { it.value != null }
                    .map { Pair(it.key, it.value!!) }
                // Members who HAD a caller on the server and have just been set back to Unassigned.
                // Their assignment rows have to be removed explicitly — the assign call only adds.
                val toClear = campaign.assignments.keys
                    .filter { bmId -> memberAssignments.containsKey(bmId) && memberAssignments[bmId] == null }
                    .mapNotNull { bmId -> campaign.assignmentDetails.find { it.batchMemberId == bmId }?.id }
                Column(horizontalAlignment = Alignment.End) {
                    // The server refuses a save that would pull in someone already held elsewhere
                    // (open follow-up, or waiting for a call in another session) and names them.
                    // This screen used to ignore the answer and close the dialog, so a refused save
                    // looked exactly like a successful one.
                    assignSaveError?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Button(
                        onClick = {
                            isSaving = true
                            assignSaveError = null
                            scope.launch {
                                toClear.forEach { AppDatabase.removeSessionCallAssignment(campaign.id, it) }
                                val err = if (pairs.isNotEmpty()) AppDatabase.assignSessionCallersOrError(campaign.id, pairs) else null
                                isSaving = false
                                if (err == null) assigningCampaign = null else assignSaveError = err
                                reload()
                            }
                        },
                        enabled = !isSaving && (pairs.isNotEmpty() || toClear.isNotEmpty())
                    ) {
                        if (isSaving) CircularProgressIndicator(Modifier.size(16.dp)) else Text("Save")
                    }
                }
            },
            dismissButton = { TextButton(onClick = { assigningCampaign = null }) { Text("Cancel") } }
        )
    }
}

/**
 * Directory Setup → "WhatsApp": one message per CALLING session.
 *
 * The message is what the caller's WhatsApp button opens with, already filled in. It is stored raw
 * (with {name}-style placeholders) and rendered per person by the server, so the caller never edits
 * text on a phone and every caller working the same session sends the same words.
 *
 * Only calling sessions are listed. `fetchSessionCampaigns` filters `kind = called` server-side, so
 * the auto-created direct-attendance campaigns (one per marked session) never appear here — there
 * is nobody to send a pre-class message to on those.
 */
@Composable
private fun WhatsappMessagesTab(onBanner: (Boolean, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var campaigns by remember { mutableStateOf<List<SessionCampaign>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var savingId by remember { mutableStateOf<String?>(null) }
    // Edits in progress, keyed by campaign id. Held here rather than inside each row so a reload
    // does not silently discard what the admin has typed but not yet saved.
    val drafts = remember { mutableStateMapOf<String, String>() }

    suspend fun reload() {
        loading = true
        campaigns = AppDatabase.fetchSessionCampaigns("all")
        drafts.clear()
        loading = false
    }
    LaunchedEffect(AppDatabase.selectedCentreId, AppDatabase.selectedCategory) { reload() }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "The message your callers send on WhatsApp, one per calling session. " +
                "Leave it empty and WhatsApp opens with no text, as before.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
        Text(
            "You can use {name}, {session}, {batch} and {date} — each is filled in per person.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        if (campaigns.isEmpty() && !loading) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Chat,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                    Spacer(Modifier.height(8.dp))
                    // Says WHY it is empty. "No calling sessions" is a fact about this centre and
                    // category, not a failure — without saying so it reads as a broken screen.
                    Text(
                        "No calling sessions in ${AppDatabase.selectedCategory} yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Create one under Planned Sessions to give it a message.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { scope.launch { reload() } }) { Text("Retry") }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(campaigns, key = { "wa_${it.id}" }) { camp ->
                    val saved = camp.whatsappMessage
                    val text = drafts[camp.id] ?: saved
                    val dirty = text != saved
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    camp.sessionName.ifBlank { camp.aeSessionId },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                StatusBadge(camp.status.replaceFirstChar { it.uppercase() }, if (camp.status == "active") "active" else "pending")
                            }
                            Text(
                                camp.batchName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            AppTextField(
                                value = text,
                                onValueChange = { drafts[camp.id] = it },
                                label = "WhatsApp message",
                                placeholder = "Hare Krishna {name}, class is on {date}",
                                singleLine = false,
                                maxLines = 5,
                                enabled = savingId != camp.id,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = {
                                        savingId = camp.id
                                        scope.launch {
                                            val ok = AppDatabase.setCampaignWhatsappMessage(camp.id, text)
                                            savingId = null
                                            if (ok) {
                                                onBanner(
                                                    false,
                                                    if (text.isBlank()) "Message cleared — WhatsApp will open with no text."
                                                    else "Message saved for this calling session.",
                                                )
                                                reload()
                                            } else {
                                                onBanner(true, "The message could not be saved.")
                                            }
                                        }
                                    },
                                    enabled = dirty && savingId != camp.id,
                                ) { Text(if (savingId == camp.id) "Saving…" else "Save") }
                                if (dirty) {
                                    TextButton(
                                        onClick = { drafts.remove(camp.id) },
                                        enabled = savingId != camp.id,
                                    ) { Text("Undo") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
