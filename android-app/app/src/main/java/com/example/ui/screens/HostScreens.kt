package com.example.ui.screens

import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.*
import com.example.ui.components.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// HOST STATS DASHBOARD — tappable stat cards + expandable centre density rows
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun HostStatsDashboardScreen(onNavigateToCentreManagement: () -> Unit, onNavigateToInactiveMonitor: () -> Unit) {
    var totalCentres by remember { mutableStateOf(0) }
    var totalRegistrations by remember { mutableStateOf(0) }
    var pendingAdmins by remember { mutableStateOf(0) }
    var centreSummaries by remember { mutableStateOf<List<com.example.data.CentreStatSummary>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    LaunchedEffect(AppDatabase.dataRevision) {
        isLoading = true
        AppDatabase.loadDataFromBackend(scope)
        val stats = AppDatabase.fetchHostStats()
        AppDatabase.fetchUsers()
        if (stats != null) {
            totalCentres = stats.centreCount
            totalRegistrations = stats.totalRegistrations
            centreSummaries = stats.byCentre
        }
        pendingAdmins = AppDatabase.users.count { it.status == "pending" && it.role == "ADMIN" }
        isLoading = false
    }

    val allCentres = AppDatabase.centres
    val activeCentres = allCentres.filter { it.status == "active" }
    val pendingUsers = AppDatabase.users.filter { it.status == "pending" }
    val expandedCentreIds = remember { mutableStateOf<Set<String>>(emptySet()) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("System Overview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Host-level dashboard — all centres, all users.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(16.dp))

        // Top stat cards — tappable
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(
                label = "Centres",
                value = if (totalCentres > 0) totalCentres.toString() else activeCentres.size.toString(),
                icon = Icons.Default.LocationCity,
                onClick = onNavigateToCentreManagement,
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = "Total Regs.",
                value = totalRegistrations.toString(),
                icon = Icons.Default.Groups,
                onClick = {},
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = "Pending Adm.",
                value = if (pendingAdmins > 0 || pendingUsers.isEmpty()) pendingAdmins.toString() else pendingUsers.size.toString(),
                icon = Icons.Default.HourglassTop,
                onClick = {},
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Centre Density", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(activeCentres) { centre ->
                // Use per-centre stats from host/stats/summary — real data, not local cache
                val cStat = centreSummaries.find { it.centreId == centre.id }
                val regCount = cStat?.registrations ?: 0
                val callingCount = cStat?.activeCalling ?: 0
                val followupCount = cStat?.followups ?: 0
                val maxPossible = (centreSummaries.maxOfOrNull { it.registrations }?.coerceAtLeast(1) ?: 1).toFloat()
                val isExpanded = expandedCentreIds.value.contains(centre.id)
                val rotateAngle by animateFloatAsState(if (isExpanded) 180f else 0f, label = "density_expand_${centre.id}")

                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                expandedCentreIds.value = if (isExpanded) expandedCentreIds.value - centre.id else expandedCentreIds.value + centre.id
                            }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(centre.name, fontWeight = FontWeight.Bold)
                                    StatusBadge(text = centre.status, status = centre.status)
                                }
                                Text("$regCount registered", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(progress = { regCount / maxPossible }, modifier = Modifier.fillMaxWidth().height(4.dp))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp).rotate(rotateAngle), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                            Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                                HorizontalDivider(modifier = Modifier.padding(bottom = 10.dp))

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // Registrations card
                                    Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text("Registered", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color(0xFF1565C0))
                                            Text("$regCount", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color(0xFF1565C0))
                                            Text("in system", style = MaterialTheme.typography.labelSmall, color = Color(0xFF1565C0))
                                        }
                                    }
                                    // Calling activity card
                                    Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color(0xFFF3E5F5))) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text("Active Calls", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color(0xFF6A1B9A))
                                            Text("$callingCount", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color(0xFF6A1B9A))
                                            Text("in queue", style = MaterialTheme.typography.labelSmall, color = Color(0xFF6A1B9A))
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(selected = false, onClick = {}, label = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.FollowTheSigns, null, modifier = Modifier.size(12.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("$followupCount follow-ups")
                                        }
                                    })
                                    val centreAdmins = AppDatabase.users.filter { it.centreId == centre.id && it.role == "ADMIN" }
                                    if (centreAdmins.isNotEmpty()) {
                                        FilterChip(selected = false, onClick = {}, label = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.AdminPanelSettings, null, modifier = Modifier.size(12.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("${centreAdmins.size} admin(s)")
                                            }
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onNavigateToInactiveMonitor) {
                        Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("View Inactive Centre Monitor")
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// INACTIVE CENTRE MONITOR — actual countdown in days, expandable cards
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun InactiveCentreMonitorScreen(onNavigateToCentreManagement: () -> Unit) {
    val allCentres = AppDatabase.centres
    val activeCentres = allCentres.filter { it.status == "active" }
    val expandedIds = remember { mutableStateOf<Set<String>>(emptySet()) }

    data class CentreActivity(
        val centre: Centre,
        val lastSessionDate: String?,
        val daysSinceActivity: Int,
        val regCount: Int
    )

    val centreActivities = activeCentres.map { centre ->
        val centreBatchIds = AppDatabase.batches.filter { it.centreId == centre.id }.map { it.id }.toSet()
        val centreSessionDates = AppDatabase.sessions
            .filter { it.batchId in centreBatchIds }
            .mapNotNull { it.date }
            .sortedDescending()
        val lastDate = centreSessionDates.firstOrNull()
        val daysSince = if (lastDate != null) {
            val parts = lastDate.split("-")
            if (parts.size == 3) {
                try { ((2026 - parts[0].toInt()) * 365 + (6 - parts[1].toInt()) * 30 + (13 - parts[2].toInt())).coerceAtLeast(0) }
                catch (e: Exception) { 30 }
            } else 30
        } else 90
        CentreActivity(
            centre = centre,
            lastSessionDate = lastDate,
            daysSinceActivity = daysSince,
            regCount = AppDatabase.registrations.count { it.centreId == centre.id && it.status == "active" }
        )
    }.sortedByDescending { it.daysSinceActivity }

    val alertThreshold = 30
    val warnThreshold = 14
    val alertCentres = centreActivities.filter { it.daysSinceActivity >= alertThreshold }
    val warnCentres = centreActivities.filter { it.daysSinceActivity in warnThreshold until alertThreshold }
    val healthyCentres = centreActivities.filter { it.daysSinceActivity < warnThreshold }

    @Composable
    fun CentreActivityCard(ca: CentreActivity) {
        val alertLevel = when {
            ca.daysSinceActivity >= alertThreshold -> "alert"
            ca.daysSinceActivity >= warnThreshold -> "warn"
            else -> "ok"
        }
        val isExpanded = expandedIds.value.contains(ca.centre.id)
        val expandRotation by animateFloatAsState(if (isExpanded) 180f else 0f, label = "monitor_expand_${ca.centre.id}")
        val alertColor = when (alertLevel) { "alert" -> Color(0xFFFFEBEE); "warn" -> Color(0xFFFFF8E1); else -> MaterialTheme.colorScheme.surface }
        val textColor = when (alertLevel) { "alert" -> Color(0xFFC62828); "warn" -> Color(0xFFE65100); else -> Color(0xFF2E7D32) }

        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = alertColor)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        expandedIds.value = if (isExpanded) expandedIds.value - ca.centre.id else expandedIds.value + ca.centre.id
                    }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        when (alertLevel) { "alert" -> Icons.Default.Error; "warn" -> Icons.Default.Warning; else -> Icons.Default.CheckCircle },
                        contentDescription = null, modifier = Modifier.size(20.dp), tint = textColor
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(ca.centre.name, fontWeight = FontWeight.Bold)
                        if (ca.lastSessionDate != null) {
                            Text("Last: ${ca.lastSessionDate} · ${ca.daysSinceActivity}d ago", style = MaterialTheme.typography.bodySmall, color = textColor)
                        } else {
                            Text("No sessions recorded", style = MaterialTheme.typography.bodySmall, color = textColor)
                        }
                    }
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = textColor)) {
                        Text(
                            if (ca.daysSinceActivity > 0) "${ca.daysSinceActivity}d" else "Today",
                            color = Color.White, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp).rotate(expandRotation), tint = textColor)
                }

                AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                    Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                        HorizontalDivider(color = textColor.copy(alpha = 0.2f), modifier = Modifier.padding(bottom = 8.dp))
                        Text("${ca.regCount} active registrations", style = MaterialTheme.typography.bodySmall)
                        val centreAdmins = AppDatabase.users.filter { it.centreId == ca.centre.id && it.role == "ADMIN" && it.status == "active" }
                        if (centreAdmins.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Admins: ${centreAdmins.joinToString { it.name }}", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {}, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), modifier = Modifier.height(34.dp)) {
                                Text("Send Reminder", fontSize = 12.sp)
                            }
                            if (alertLevel == "alert") {
                                Button(
                                    onClick = onNavigateToCentreManagement,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.height(34.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                                ) { Text("Manage Centre", fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Inactive Centre Monitor", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Flag centres with no session activity in past $warnThreshold–$alertThreshold days.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (alertCentres.isNotEmpty()) FilterChip(selected = true, onClick = {}, label = { Text("${alertCentres.size} critical") }, leadingIcon = { Icon(Icons.Default.Error, null, modifier = Modifier.size(14.dp)) })
            if (warnCentres.isNotEmpty()) FilterChip(selected = false, onClick = {}, label = { Text("${warnCentres.size} at-risk") }, leadingIcon = { Icon(Icons.Default.Warning, null, modifier = Modifier.size(14.dp)) })
            FilterChip(selected = false, onClick = {}, label = { Text("${healthyCentres.size} healthy") }, leadingIcon = { Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(14.dp)) })
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            if (alertCentres.isNotEmpty()) {
                item { Text("Critical (>$alertThreshold days)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = Color(0xFFC62828)); Spacer(modifier = Modifier.height(4.dp)) }
                items(alertCentres) { CentreActivityCard(it) }
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
            if (warnCentres.isNotEmpty()) {
                item { Text("At Risk ($warnThreshold–$alertThreshold days)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = Color(0xFFE65100)); Spacer(modifier = Modifier.height(4.dp)) }
                items(warnCentres) { CentreActivityCard(it) }
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
            if (healthyCentres.isNotEmpty()) {
                item { Text("Active (<$warnThreshold days)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = Color(0xFF2E7D32)); Spacer(modifier = Modifier.height(4.dp)) }
                items(healthyCentres) { CentreActivityCard(it) }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CENTRE MANAGEMENT SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun CentreManagementScreen() {
    var centresList by remember { mutableStateOf(AppDatabase.centres) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var selectedCentreForEdit by remember { mutableStateOf<Centre?>(null) }
    var selectedCentreForAdmin by remember { mutableStateOf<Centre?>(null) }
    val expandedCentreIds = remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // The list is loaded from the server in the background; follow it rather than a snapshot
    // taken when this screen opened.
    LaunchedEffect(AppDatabase.centres) { centresList = AppDatabase.centres }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Centre Management", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Create, configure, and manage all centres.", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("New Centre")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = false, onClick = {}, label = { Text("${centresList.count { it.status == "active" }} active") })
            FilterChip(selected = false, onClick = {}, label = { Text("${centresList.count { it.status == "inactive" }} inactive") })
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(centresList, key = { it.id }) { centre ->
                val isExpanded = expandedCentreIds.value.contains(centre.id)
                val expandRotation by animateFloatAsState(if (isExpanded) 180f else 0f, label = "cm_expand_${centre.id}")
                val centreRegs = AppDatabase.registrations.filter { it.centreId == centre.id && it.status == "active" }
                val centreAdmins = AppDatabase.users.filter { it.centreId == centre.id && (it.role == "ADMIN" || it.role == "SUPER_ADMIN") && it.status == "active" }
                val centreBatches = AppDatabase.batches.filter { it.centreId == centre.id }

                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = if (centre.status == "active") MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column {
                        // Header row — tap to expand
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                expandedCentreIds.value = if (isExpanded) expandedCentreIds.value - centre.id else expandedCentreIds.value + centre.id
                            }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(centre.name, fontWeight = FontWeight.Bold)
                                    StatusBadge(text = centre.status, status = centre.status)
                                    StatusBadge(
                                        text = if (centre.enabledYouth && centre.enabledCongregation) "Both" else if (centre.enabledYouth) "Youth" else "Cong.",
                                        status = "active"
                                    )
                                    if (centre.isSheetEnabled) StatusBadge(text = "Sheet", status = "approved")
                                }
                                Text(
                                    "${centreRegs.size} regs · ${centreBatches.size} batches · ${centreAdmins.size} admins",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (centre.googleSheetId.isNotEmpty()) {
                                    Text("Sheet: …${centre.googleSheetId.takeLast(12)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp).rotate(expandRotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                            Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                                HorizontalDivider(modifier = Modifier.padding(bottom = 10.dp))

                                if (centreAdmins.isNotEmpty()) {
                                    Text("Admins", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                                    centreAdmins.forEach { u ->
                                        Row(modifier = Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Person, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(u.name, style = MaterialTheme.typography.bodySmall)
                                            Text("@${u.username}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(
                                        onClick = { selectedCentreForEdit = centre },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(12.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Edit Config", fontSize = 12.sp)
                                    }
                                    Button(
                                        onClick = { selectedCentreForAdmin = centre },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Icon(Icons.Default.PersonAdd, null, modifier = Modifier.size(12.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Assign Admin", fontSize = 12.sp)
                                    }
                                    if (centre.status == "active") {
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    if (!AppDatabase.setCentreActiveOnBackend(centre.id, false)) {
                                                        Toast.makeText(context, "Couldn't deactivate on the server.", Toast.LENGTH_LONG).show()
                                                        return@launch
                                                    }
                                                    AppDatabase.centres = AppDatabase.centres.map { if (it.id == centre.id) it.copy(status = "inactive") else it }
                                                    centresList = AppDatabase.centres
                                                }
                                            },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            modifier = Modifier.height(34.dp)
                                        ) { Text("Deactivate", fontSize = 12.sp) }
                                    } else {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    if (!AppDatabase.setCentreActiveOnBackend(centre.id, true)) {
                                                        Toast.makeText(context, "Couldn't reactivate on the server.", Toast.LENGTH_LONG).show()
                                                        return@launch
                                                    }
                                                    AppDatabase.centres = AppDatabase.centres.map { if (it.id == centre.id) it.copy(status = "active") else it }
                                                    centresList = AppDatabase.centres
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            modifier = Modifier.height(34.dp)
                                        ) { Text("Reactivate", fontSize = 12.sp) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Create Centre full-screen dialog ──────────────────────────────────────
    if (showCreateDialog) {
        CreateCentreDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { newCentre, adminUser ->
                AppDatabase.centres = AppDatabase.centres + newCentre
                if (adminUser != null) AppDatabase.users = AppDatabase.users + adminUser
                centresList = AppDatabase.centres
                showCreateDialog = false
            }
        )
    }

    // ── Edit Centre config dialog ─────────────────────────────────────────────
    selectedCentreForEdit?.let { c ->
        EditCentreDialog(
            centre = c,
            onDismiss = { selectedCentreForEdit = null },
            onSave = { updated ->
                val error = AppDatabase.updateCentreOnBackend(updated)
                if (error == null) {
                    AppDatabase.centres = AppDatabase.centres.map { if (it.id == updated.id) updated else it }
                    centresList = AppDatabase.centres
                    selectedCentreForEdit = null
                    Toast.makeText(context, "Saved. Admins see the change the next time their app refreshes.", Toast.LENGTH_LONG).show()
                }
                error
            }
        )
    }

    // ── Assign Admin dialog ───────────────────────────────────────────────────
    selectedCentreForAdmin?.let { c ->
        AssignAdminDialog(
            centre = c,
            onDismiss = { selectedCentreForAdmin = null },
            onCreated = { newUser ->
                AppDatabase.users = AppDatabase.users + newUser
                selectedCentreForAdmin = null
            }
        )
    }
}

// ── Create Centre Dialog ──────────────────────────────────────────────────────
@Composable
private fun CreateCentreDialog(onDismiss: () -> Unit, onCreate: (Centre, User?) -> Unit) {
    // Centre identity
    var centreName by remember { mutableStateOf("") }
    var centreIdRaw by remember { mutableStateOf("") }
    val centreIdSuggested = centreName.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").take(20)
    var centreIdEdited by remember { mutableStateOf(false) }
    val centreId = if (centreIdEdited) centreIdRaw else centreIdSuggested

    // Config
    var googleSheetId by remember { mutableStateOf("") }
    var sheetEnabled by remember { mutableStateOf(false) }

    // Categories
    var enableYouth by remember { mutableStateOf(true) }
    var enableCongregation by remember { mutableStateOf(false) }

    // Admin account
    var createAdmin by remember { mutableStateOf(true) }
    var adminName by remember { mutableStateOf("") }
    var adminUsername by remember { mutableStateOf("") }
    var adminPassword by remember { mutableStateOf("") }
    var adminPasswordVisible by remember { mutableStateOf(false) }

    // Submission state
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val canCreate = centreName.isNotEmpty() && centreId.isNotEmpty() &&
        (enableYouth || enableCongregation) &&
        (!createAdmin || (adminName.isNotEmpty() && adminUsername.isNotEmpty() && adminPassword.length >= 6))

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(8.dp)
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {

                // Title
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationCity, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Create New Centre", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(20.dp))

                // ── Section 1: Identity ───────────────────────────────────────
                SectionLabel("Centre Identity")
                AppTextField(value = centreName, onValueChange = { centreName = it; if (!centreIdEdited) centreIdRaw = centreIdSuggested }, label = "Centre Name *", leadingIcon = Icons.Default.LocationCity)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = centreId,
                    onValueChange = { centreIdRaw = it.lowercase().replace(Regex("[^a-z0-9_]"), ""); centreIdEdited = true },
                    label = { Text("Centre ID *") },
                    leadingIcon = { Icon(Icons.Default.Tag, null) },
                    supportingText = { Text("Unique short ID, auto-filled from name. No spaces.", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))

                // ── Section 2: Google Sheet Config ────────────────────────────
                SectionLabel("Google Sheet Configuration")
                AppTextField(value = googleSheetId, onValueChange = { googleSheetId = it; if (it.isNotEmpty()) sheetEnabled = true }, label = "Google Sheet ID (optional)", leadingIcon = Icons.Default.TableChart)
                AnimatedVisibility(visible = googleSheetId.isNotEmpty()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = sheetEnabled, onCheckedChange = { sheetEnabled = it })
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Enable Sheet Sync", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                            Text(if (sheetEnabled) "Attendance will sync to this sheet." else "Sheet linked but sync is off.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // ── Section 3: Categories ─────────────────────────────────────
                SectionLabel("Enabled Audience Categories")
                Text("At least one must be selected.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(6.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
                    Column(modifier = Modifier.padding(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().clickable { if (enableCongregation || !enableYouth) enableYouth = !enableYouth }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = enableYouth, onCheckedChange = { if (!it && !enableCongregation) return@Checkbox; enableYouth = it })
                            Column {
                                Text("Youth", fontWeight = FontWeight.Medium)
                                Text("Standard Youth batch programme.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth().clickable { if (enableYouth || !enableCongregation) enableCongregation = !enableCongregation }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = enableCongregation, onCheckedChange = { if (!it && !enableYouth) return@Checkbox; enableCongregation = it })
                            Column {
                                Text("Congregation", fontWeight = FontWeight.Medium)
                                Text("Adult congregation programme.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // ── Section 4: Initial Admin Account ─────────────────────────
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Initial Admin Account", modifier = Modifier.weight(1f))
                    Switch(checked = createAdmin, onCheckedChange = { createAdmin = it })
                }
                Text(
                    if (createAdmin) "Create an admin account for this centre." else "Skip — add admins later via Manage Users.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AnimatedVisibility(visible = createAdmin, enter = expandVertically(), exit = shrinkVertically()) {
                    Column(modifier = Modifier.padding(top = 10.dp)) {
                        AppTextField(value = adminName, onValueChange = { adminName = it }, label = "Admin Full Name *", leadingIcon = Icons.Default.Person)
                        Spacer(modifier = Modifier.height(8.dp))
                        AppTextField(value = adminUsername, onValueChange = { adminUsername = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' } }, label = "Admin Username *", leadingIcon = Icons.Default.AlternateEmail)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = adminPassword,
                            onValueChange = { adminPassword = it },
                            label = { Text("Admin Password * (min 6 chars)") },
                            leadingIcon = { Icon(Icons.Default.Lock, null) },
                            trailingIcon = {
                                IconButton(onClick = { adminPasswordVisible = !adminPasswordVisible }) {
                                    Icon(if (adminPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                                }
                            },
                            visualTransformation = if (adminPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            isError = adminPassword.isNotEmpty() && adminPassword.length < 6,
                            supportingText = if (adminPassword.isNotEmpty() && adminPassword.length < 6) {
                                { Text("Password must be at least 6 characters", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                            } else null
                        )
                    }
                }

                // ── Error ─────────────────────────────────────────────────────
                errorMsg?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(it, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // ── Buttons ───────────────────────────────────────────────────
                Spacer(modifier = Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            errorMsg = null
                            isSubmitting = true
                            scope.launch {
                                // 1. Create centre via backend
                                val newCentre = AppDatabase.createCentreOnBackend(
                                    centreId = centreId,
                                    name = centreName,
                                    googleSheetId = googleSheetId.trim(),
                                    sheetEnabled = sheetEnabled,
                                    enabledYouth = enableYouth,
                                    enabledCongregation = enableCongregation
                                )
                                if (newCentre == null) {
                                    errorMsg = "Failed to create centre on server. Check the connection and that the name is unique."
                                    isSubmitting = false
                                    return@launch
                                }
                                // 2. Create admin account if requested
                                var newAdminUser: User? = null
                                if (createAdmin && adminUsername.isNotEmpty() && adminPassword.length >= 6) {
                                    val adminCreated = AppDatabase.createAdminUser(
                                        name = adminName,
                                        username = adminUsername,
                                        password = adminPassword,
                                        centreId = centreId,
                                        centreCode = centreId
                                    )
                                    newAdminUser = User(
                                        id = "u_${System.currentTimeMillis()}",
                                        username = adminUsername,
                                        name = adminName,
                                        role = "ADMIN",
                                        status = if (adminCreated) "active" else "error",
                                        centreId = centreId
                                    )
                                }
                                isSubmitting = false
                                onCreate(newCentre, newAdminUser)
                            }
                        },
                        enabled = canCreate && !isSubmitting
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (isSubmitting) "Creating…" else "Create Centre")
                    }
                }
            }
        }
    }
}

// ── Edit Centre Config Dialog ─────────────────────────────────────────────────
@Composable
private fun EditCentreDialog(centre: Centre, onDismiss: () -> Unit, onSave: suspend (Centre) -> String?) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf(centre.name) }
    var editSheetId by remember { mutableStateOf(centre.googleSheetId) }
    var editSheetEnabled by remember { mutableStateOf(centre.isSheetEnabled) }
    var editYouth by remember { mutableStateOf(centre.enabledYouth) }
    var editCongregation by remember { mutableStateOf(centre.enabledCongregation) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Edit Centre — ${centre.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("ID: ${centre.id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(20.dp))

                SectionLabel("Display Name")
                AppTextField(value = editName, onValueChange = { editName = it }, label = "Centre Name *", leadingIcon = Icons.Default.LocationCity)
                Spacer(modifier = Modifier.height(16.dp))

                SectionLabel("Google Sheet Configuration")
                AppTextField(value = editSheetId, onValueChange = { editSheetId = it; if (it.isNotEmpty() && !editSheetEnabled) editSheetEnabled = true }, label = "Google Sheet ID (optional)", leadingIcon = Icons.Default.TableChart)
                AnimatedVisibility(visible = editSheetId.isNotEmpty()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = editSheetEnabled, onCheckedChange = { editSheetEnabled = it })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (editSheetEnabled) "Sheet sync enabled" else "Sheet sync paused", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                SectionLabel("Audience Categories")
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
                    Column(modifier = Modifier.padding(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().clickable { if (editCongregation) editYouth = !editYouth }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = editYouth, onCheckedChange = { if (!it && !editCongregation) return@Checkbox; editYouth = it })
                            Text("Youth", fontWeight = FontWeight.Medium)
                        }
                        Row(modifier = Modifier.fillMaxWidth().clickable { if (editYouth) editCongregation = !editCongregation }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = editCongregation, onCheckedChange = { if (!it && !editYouth) return@Checkbox; editCongregation = it })
                            Text("Congregation", fontWeight = FontWeight.Medium)
                        }
                    }
                }

                saveError?.let {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            saving = true
                            saveError = null
                            scope.launch {
                                saveError = onSave(centre.copy(
                                    name = editName,
                                    googleSheetId = editSheetId.trim(),
                                    isSheetEnabled = editSheetEnabled,
                                    enabledYouth = editYouth,
                                    enabledCongregation = editCongregation
                                ))
                                saving = false
                            }
                        },
                        enabled = !saving && editName.isNotEmpty() && (editYouth || editCongregation)
                    ) { Text(if (saving) "Saving…" else "Save Changes") }
                }
            }
        }
    }
}

// ── Assign Admin Dialog ───────────────────────────────────────────────────────
@Composable
private fun AssignAdminDialog(
    centre: Centre,
    onDismiss: () -> Unit,
    onCreated: (User) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val canCreate = name.isNotEmpty() && username.isNotEmpty() && password.length >= 6 && !isSubmitting

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PersonAdd, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Assign Admin — ${centre.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Text("Create a new admin account with full control over this centre.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Full Name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username, onValueChange = { username = it.lowercase().replace(" ", "_") },
                    label = { Text("Username") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password (min 6 chars)") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, modifier = Modifier.size(18.dp))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorText.isNotEmpty()) {
                    Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            isSubmitting = true
                            errorText = ""
                            scope.launch {
                                val ok = AppDatabase.createAdminUser(
                                    name = name.trim(),
                                    username = username.trim(),
                                    password = password,
                                    centreId = centre.id,
                                    centreCode = centre.code
                                )
                                isSubmitting = false
                                if (ok) {
                                    onCreated(User(
                                        id = "u_${System.currentTimeMillis()}",
                                        username = username.trim(),
                                        name = name.trim(),
                                        role = "ADMIN",
                                        status = "active",
                                        centreId = centre.id
                                    ))
                                } else {
                                    errorText = "Failed — check server or username already taken."
                                }
                            }
                        },
                        enabled = canCreate
                    ) {
                        if (isSubmitting) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("Create Admin")
                    }
                }
            }
        }
    }
}

// ── Section label helper ──────────────────────────────────────────────────────
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(bottom = 6.dp))
}

// ─────────────────────────────────────────────────────────────────────────────
// HOST ADMIN MANAGEMENT — SUPER_ADMIN manages all ADMIN (centre admin) users
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun HostAdminManagementScreen() {
    var activeTab by remember { mutableStateOf(0) }
    var adminList by remember { mutableStateOf(AppDatabase.users.filter { it.role == "ADMIN" }) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var selectedAdminForReset by remember { mutableStateOf<User?>(null) }
    var adminResetText by remember { mutableStateOf("") }
    var showResetPw by remember { mutableStateOf(false) }
    var resetErr by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        isLoading = true
        AppDatabase.fetchUsers()
        adminList = AppDatabase.users.filter { it.role == "ADMIN" }
        isLoading = false
    }

    val tabItems = listOf(
        Triple("Requests", adminList.count { it.status == "pending" }, Icons.Default.HourglassTop),
        Triple("Active", adminList.count { it.status == "active" }, Icons.Default.CheckCircle),
        Triple("Inactive", adminList.count { it.status == "inactive" }, Icons.Default.Block)
    )
    val filteredList = when (activeTab) {
        0 -> adminList.filter { it.status == "pending" }
        1 -> adminList.filter { it.status == "active" }
        else -> adminList.filter { it.status == "inactive" }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Centre Admin Management", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Approve, activate, or deactivate centre admin accounts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text("No ${tabItems[activeTab].first.lowercase()} centre admins.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    Text("@${u.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                                        com.example.ui.components.StatusBadge(text = "ADMIN", status = "active")
                                        com.example.ui.components.StatusBadge(text = centreName, status = "active")
                                    }
                                }
                                com.example.ui.components.StatusBadge(text = u.status, status = u.status)
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                when (u.status) {
                                    "pending" -> {
                                        OutlinedButton(onClick = {
                                            scope.launch {
                                                AppDatabase.rejectUserApi(u.id)
                                                AppDatabase.fetchUsers()
                                                adminList = AppDatabase.users.filter { it.role == "ADMIN" }
                                            }
                                        }) { Text("Reject") }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(onClick = {
                                            scope.launch {
                                                AppDatabase.approveUserApi(u.id)
                                                AppDatabase.fetchUsers()
                                                adminList = AppDatabase.users.filter { it.role == "ADMIN" }
                                            }
                                        }) { Text("Approve") }
                                    }
                                    "active" -> {
                                        OutlinedButton(onClick = {
                                            selectedAdminForReset = u
                                            adminResetText = ""
                                            resetErr = null
                                            showResetPw = false
                                        }) { Text("Reset Password") }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    AppDatabase.deactivateUserApi(u.id)
                                                    AppDatabase.fetchUsers()
                                                    adminList = AppDatabase.users.filter { it.role == "ADMIN" }
                                                }
                                            },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                        ) { Text("Deactivate") }
                                    }
                                    else -> {
                                        Button(onClick = {
                                            scope.launch {
                                                AppDatabase.activateUserApi(u.id)
                                                AppDatabase.fetchUsers()
                                                adminList = AppDatabase.users.filter { it.role == "ADMIN" }
                                            }
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

    // Reset Password Dialog
    selectedAdminForReset?.let { u ->
        AlertDialog(
            onDismissRequest = { selectedAdminForReset = null },
            icon = { Icon(Icons.Default.Key, contentDescription = null) },
            title = { Text("Reset Password \u2014 ${u.name}") },
            text = {
                Column {
                    Text("Set a temporary password. ${u.name} will be asked to change it on next login.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = adminResetText,
                        onValueChange = { adminResetText = it; resetErr = null },
                        label = { Text("New Password (min 8 chars)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showResetPw) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showResetPw = !showResetPw }) {
                                Icon(
                                    if (showResetPw) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showResetPw) "Hide" else "Show"
                                )
                            }
                        }
                    )
                    resetErr?.let { err ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val ok = AppDatabase.adminResetPasswordApi(u.username, adminResetText)
                            if (ok) { selectedAdminForReset = null; adminResetText = "" }
                            else resetErr = "Reset failed \u2014 check connection"
                        }
                    },
                    enabled = adminResetText.length >= 8
                ) { Text("Apply & Force Change") }
            },
            dismissButton = { TextButton(onClick = { selectedAdminForReset = null }) { Text("Cancel") } }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SYSTEM WIDE REPORTS — batch breakdown, centre filter, export button
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SystemWideReportsScreen() {
    var selectedReport by remember { mutableStateOf("Attendance Overview") }
    var reportExpanded by remember { mutableStateOf(false) }
    var selectedCentreFilter by remember { mutableStateOf("All Centres") }
    var centreFilterExpanded by remember { mutableStateOf(false) }
    var reportGenerated by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val reportTypes = listOf("Attendance Overview", "Batch Density", "Calling Outcomes", "User Activity", "Inactive Students", "Centre Comparison")
    val centreOptions = listOf("All Centres") + AppDatabase.centres.filter { it.status == "active" }.map { it.name }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("System-Wide Reports", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Aggregate data across all active centres.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(modifier = Modifier.height(16.dp))

        ExposedDropdownMenuBox(expanded = reportExpanded, onExpandedChange = { reportExpanded = it }) {
            OutlinedTextField(
                value = selectedReport,
                onValueChange = {},
                readOnly = true,
                label = { Text("Report Type") },
                leadingIcon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = reportExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            ExposedDropdownMenu(expanded = reportExpanded, onDismissRequest = { reportExpanded = false }) {
                reportTypes.forEach { r ->
                    DropdownMenuItem(text = { Text(r) }, onClick = { selectedReport = r; reportExpanded = false; reportGenerated = false })
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        ExposedDropdownMenuBox(expanded = centreFilterExpanded, onExpandedChange = { centreFilterExpanded = it }) {
            OutlinedTextField(
                value = selectedCentreFilter,
                onValueChange = {},
                readOnly = true,
                label = { Text("Filter by Centre") },
                leadingIcon = { Icon(Icons.Default.LocationCity, contentDescription = null) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = centreFilterExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            ExposedDropdownMenu(expanded = centreFilterExpanded, onDismissRequest = { centreFilterExpanded = false }) {
                centreOptions.forEach { opt ->
                    DropdownMenuItem(text = { Text(opt) }, onClick = { selectedCentreFilter = opt; centreFilterExpanded = false; reportGenerated = false })
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    isGenerating = true; reportGenerated = false
                    scope.launch {
                        kotlinx.coroutines.delay(1200)
                        isGenerating = false; reportGenerated = true
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isGenerating) "Generating…" else "Generate Report")
            }
            if (reportGenerated) {
                OutlinedButton(onClick = { showExportDialog = true }) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export")
                }
            }
        }

        if (isGenerating) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Spacer(modifier = Modifier.height(12.dp))

        val filteredRegs = if (selectedCentreFilter == "All Centres") {
            AppDatabase.registrations.filter { it.status == "active" }
        } else {
            val centreId = AppDatabase.centres.find { it.name == selectedCentreFilter }?.id
            AppDatabase.registrations.filter { it.status == "active" && it.centreId == centreId }
        }

        AnimatedVisibility(visible = reportGenerated, modifier = Modifier.weight(1f)) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text("$selectedReport · $selectedCentreFilter", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                when (selectedReport) {
                    "Attendance Overview" -> {
                        val centres = if (selectedCentreFilter == "All Centres") AppDatabase.centres.filter { it.status == "active" } else AppDatabase.centres.filter { it.name == selectedCentreFilter }
                        items(centres) { centre ->
                            val cRegs = filteredRegs.filter { it.centreId == centre.id }
                            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(centre.name, fontWeight = FontWeight.Bold)
                                        Text("${cRegs.size} active", style = MaterialTheme.typography.bodySmall)
                                    }
                                    val batchCount = AppDatabase.batches.count { it.centreId == centre.id && it.status == "active" }
                                    Text("$batchCount batches", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    "Batch Density" -> {
                        val batchesToShow = AppDatabase.batches.filter { b ->
                            b.status == "active" && (selectedCentreFilter == "All Centres" || AppDatabase.centres.find { it.id == b.centreId }?.name == selectedCentreFilter)
                        }
                        items(batchesToShow) { batch ->
                            val batchMemberCount = AppDatabase.registrations.count { it.batchIds.contains(batch.id) && it.status == "active" }
                            val centreName = AppDatabase.centres.find { it.id == batch.centreId }?.name ?: batch.centreId
                            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(batch.name, fontWeight = FontWeight.Bold)
                                        Text("$centreName · ${batch.category}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("$batchMemberCount members", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                        if (batch.isDefault) { Spacer(modifier = Modifier.width(6.dp)); StatusBadge("Default", "active") }
                                    }
                                }
                            }
                        }
                    }
                    "Calling Outcomes" -> {
                        val calls = AppDatabase.callAttempts.filter { ca ->
                            selectedCentreFilter == "All Centres" || run {
                                val centreId = AppDatabase.registrations.find { it.id == ca.registrationId }?.centreId
                                AppDatabase.centres.find { it.id == centreId }?.name == selectedCentreFilter
                            }
                        }
                        val grouped = calls.groupBy { it.outcome }
                        if (calls.isEmpty()) {
                            item { Text("No call data available.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
                        } else {
                            items(grouped.entries.toList()) { (outcome, attempts) ->
                                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(outcome, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                            Text("${attempts.size} calls", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        val ratio = attempts.size.toFloat() / calls.size
                                        LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth().height(4.dp))
                                        Text("${(ratio * 100).toInt()}% of all calls", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        item {
                            Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.BarChart, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("$selectedReport — live backend required.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!reportGenerated && !isGenerating) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.BarChart, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Select a report type and tap Generate.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            icon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
            title = { Text("Export Report") },
            text = {
                Column {
                    Text("\"$selectedReport\" will be compiled as CSV.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Filter: $selectedCentreFilter", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { Button(onClick = { showExportDialog = false }) { Text("Export CSV") } },
            dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text("Cancel") } }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ANALYTICS DASHBOARD — attendance trend chart + per-centre workforce counts
// scopeCentreId = null → SUPER_ADMIN (all centres); non-null → ADMIN (own centre)
// ─────────────────────────────────────────────────────────────────────────────

private val TREND_COLORS = listOf(
    Color(0xFF1976D2), Color(0xFF388E3C), Color(0xFFF57C00),
    Color(0xFFC2185B), Color(0xFF7B1FA2), Color(0xFF0097A7),
    Color(0xFFE64A19), Color(0xFF5D4037), Color(0xFF455A64), Color(0xFF303F9F)
)

@Composable
fun AnalyticsDashboardScreen(scopeCentreId: String? = null) {
    val isSuperAdmin = scopeCentreId == null

    var selectedTab by remember { mutableStateOf(0) }
    var trendBatches by remember { mutableStateOf<List<AttendanceTrendBatch>>(emptyList()) }
    var centreUserCounts by remember { mutableStateOf<List<CentreUserCount>>(emptyList()) }
    var hostStats by remember { mutableStateOf<HostStats?>(null) }
    var adminStats by remember { mutableStateOf<DashboardStats?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(AppDatabase.dataRevision) {
        isLoading = true
        coroutineScope {
            val trendJob = async { AppDatabase.fetchAttendanceTrend(centreId = scopeCentreId) }
            val usersJob = if (isSuperAdmin) async { AppDatabase.fetchCentreUserCounts() } else null
            val statsJob = if (isSuperAdmin) async { AppDatabase.fetchHostStats() } else null
            val adminJob = if (!isSuperAdmin) async { AppDatabase.fetchDashboardStats() } else null
            trendBatches = trendJob.await()
            centreUserCounts = usersJob?.await() ?: emptyList()
            hostStats = statsJob?.await()
            adminStats = adminJob?.await()
        }
        isLoading = false
    }

    val tabs = listOf("Attendance", "Calling", "Registration")

    Column(modifier = Modifier.fillMaxSize()) {
        if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("Analytics Dashboard", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (isSuperAdmin) "System-wide view across all active centres" else "Scoped to your centre",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── SUPER_ADMIN: per-centre workforce cards ──────────────────────
            if (isSuperAdmin) {
                item {
                    Text("Workforce per Centre", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (centreUserCounts.isEmpty() && !isLoading) {
                        Text("No workforce data loaded.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                    } else {
                        centreUserCounts.forEach { cu ->
                            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), border = CardDefaults.outlinedCardBorder()) {
                                Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(cu.centreName, fontWeight = FontWeight.Medium)
                                        Text(cu.centreCode, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        AnalyticsChip(label = "${cu.callerCount} callers", containerColor = Color(0xFFE3F2FD))
                                        AnalyticsChip(label = "${cu.adminCount} admins", containerColor = Color(0xFFF3E5F5))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── ADMIN: own-centre quick stats ────────────────────────────────
            if (!isSuperAdmin && adminStats != null) {
                item {
                    Text("Your Centre At a Glance", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AdminQuickStatCard("Registrations", adminStats!!.newRegistrations.toString(), Icons.Default.Groups, modifier = Modifier.weight(1f))
                        AdminQuickStatCard("Active Calling", adminStats!!.activeCalling.toString(), Icons.Default.PhoneInTalk, modifier = Modifier.weight(1f))
                        AdminQuickStatCard("Follow-ups", adminStats!!.pendingFollowups.toString(), Icons.Default.Repeat, modifier = Modifier.weight(1f))
                    }
                }
            }

            // ── Metric toggle + chart ─────────────────────────────────────────
            item {
                Text("Activity Trends", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tabs.forEachIndexed { idx, tab ->
                        FilterChip(
                            selected = selectedTab == idx,
                            onClick = { selectedTab = idx },
                            label = { Text(tab) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                // hostStats is cross-centre and is fetched for SUPER_ADMIN only, so a centre admin
                // used to get two permanently empty charts that read as "no activity" rather than
                // "never loaded". A centre admin gets their OWN centre's real breakdown instead —
                // deliberately not the cross-centre bars, which are not theirs to see.
                when (selectedTab) {
                    0 -> AttendanceTrendChart(batches = trendBatches)
                    1 -> if (isSuperAdmin) {
                        CentreMetricBars(
                            summaries = hostStats?.byCentre ?: emptyList(),
                            label = "Active Calling",
                            extractor = { it.activeCalling }
                        )
                    } else {
                        MetricBreakdown(
                            title = "Calling workload · ${AppDatabase.selectedCategory}",
                            items = adminStats?.let {
                                listOf(
                                    "In active calling" to it.activeCalling,
                                    "Awaiting follow-up" to it.pendingFollowups,
                                    "On batch rosters" to it.batchCallingCount,
                                    "Personally tracked" to it.myAttendanceCount
                                )
                            } ?: emptyList()
                        )
                    }
                    2 -> if (isSuperAdmin) {
                        CentreMetricBars(
                            summaries = hostStats?.byCentre ?: emptyList(),
                            label = "Registrations",
                            extractor = { it.registrations }
                        )
                    } else {
                        MetricBreakdown(
                            title = "Registrations · ${AppDatabase.selectedCategory}",
                            items = adminStats?.let {
                                listOf(
                                    "Not yet assigned" to it.newRegistrations,
                                    "Total on the books" to it.totalRegistrations,
                                    "Removed" to it.removedCount,
                                    "Archived" to it.archivedCount
                                )
                            } ?: emptyList()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsChip(label: String, containerColor: Color) {
    Surface(shape = RoundedCornerShape(12.dp), color = containerColor) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AdminQuickStatCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Single-centre breakdown: labelled counts with bars scaled to the largest value.
 *
 * The cross-centre [CentreMetricBars] compares centres against each other, which a centre admin can
 * neither see nor act on. This compares the buckets *within* one centre, which is the question they
 * actually have.
 */
@Composable
private fun MetricBreakdown(title: String, items: List<Pair<String, Int>>) {
    if (items.isEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Centre figures could not be loaded.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        return
    }
    val maxVal = items.maxOf { it.second }.coerceAtLeast(1)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(10.dp))
            items.forEach { (label, value) ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(
                        value.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                LinearProgressIndicator(
                    progress = { value.toFloat() / maxVal },
                    modifier = Modifier.fillMaxWidth().height(5.dp)
                )
                Spacer(modifier = Modifier.height(9.dp))
            }
        }
    }
}

@Composable
private fun CentreMetricBars(
    summaries: List<CentreStatSummary>,
    label: String,
    extractor: (CentreStatSummary) -> Int
) {
    if (summaries.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
            Text("No data available. Tap the Attendance tab for trend data.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val maxVal = summaries.maxOfOrNull { extractor(it) }?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        summaries.forEach { s ->
            val v = extractor(s)
            val name = AppDatabase.centres.find { it.id == s.centreId }?.name ?: s.centreCode
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text("$v $label", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { v.toFloat() / maxVal }, modifier = Modifier.fillMaxWidth().height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun AttendanceTrendChart(batches: List<AttendanceTrendBatch>) {
    if (batches.isEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ShowChart, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No attendance data found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Attendance must be marked via the app first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        return
    }

    val allDates = batches.flatMap { b -> b.points.map { it.date } }.distinct().sorted()
    val maxCount = batches.flatMap { it.points }.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    val nDates = allDates.size.coerceAtLeast(2)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Attendance per Batch — Last 30 Days", fontWeight = FontWeight.Medium)
            Text("${allDates.size} dates · ${batches.size} batches · peak $maxCount attendees", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(10.dp))

            val hScrollState = rememberScrollState()
            val pointWidthPx = 44
            val canvasWidthDp = (nDates * pointWidthPx).coerceAtLeast(280)
            val onSurfaceColor = MaterialTheme.colorScheme.onSurface

            Box(modifier = Modifier.fillMaxWidth().horizontalScroll(hScrollState)) {
                Canvas(modifier = Modifier.width(canvasWidthDp.dp).height(220.dp)) {
                    val padL = 44f; val padB = 38f; val padT = 12f; val padR = 12f
                    val cW = size.width - padL - padR
                    val cH = size.height - padT - padB

                    // Grid + Y axis labels
                    for (i in 0..4) {
                        val yFrac = i / 4f
                        val y = padT + cH * (1f - yFrac)
                        drawLine(Color(0xFFE0E0E0), Offset(padL, y), Offset(size.width - padR, y), 1.5f)
                        drawContext.canvas.nativeCanvas.drawText(
                            "${(maxCount * yFrac).toInt()}",
                            padL - 6f, y + 5f,
                            android.graphics.Paint().apply {
                                textSize = 22f; textAlign = android.graphics.Paint.Align.RIGHT
                                color = android.graphics.Color.GRAY
                            }
                        )
                    }

                    // X axis date labels — show every Nth to avoid crowding
                    val labelEvery = ((nDates / 6) + 1).coerceAtLeast(1)
                    allDates.forEachIndexed { idx, date ->
                        if (idx % labelEvery == 0 || idx == nDates - 1) {
                            val x = padL + (idx.toFloat() / (nDates - 1)) * cW
                            drawContext.canvas.nativeCanvas.drawText(
                                date.takeLast(5),
                                x, size.height - 6f,
                                android.graphics.Paint().apply {
                                    textSize = 20f; textAlign = android.graphics.Paint.Align.CENTER
                                    color = android.graphics.Color.GRAY
                                }
                            )
                        }
                    }

                    // Draw each batch line
                    batches.forEachIndexed { bIdx, batch ->
                        val lineColor = TREND_COLORS[bIdx % TREND_COLORS.size]
                        val path = Path()
                        var first = true
                        batch.points.forEach { pt ->
                            val dIdx = allDates.indexOf(pt.date)
                            if (dIdx < 0) return@forEach
                            val x = padL + (dIdx.toFloat() / (nDates - 1)) * cW
                            val y = padT + cH * (1f - pt.count.toFloat() / maxCount)
                            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                            drawCircle(lineColor, 5f, Offset(x, y))
                        }
                        if (!first) drawPath(path, lineColor, style = Stroke(width = 3.5f))
                    }

                    // Axes
                    drawLine(Color(0xFF9E9E9E), Offset(padL, padT), Offset(padL, size.height - padB), 2f)
                    drawLine(Color(0xFF9E9E9E), Offset(padL, size.height - padB), Offset(size.width - padR, size.height - padB), 2f)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Legend — 2 columns
            batches.chunked(2).forEach { pair ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { batch ->
                        val color = TREND_COLORS[batches.indexOf(batch) % TREND_COLORS.size]
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).padding(vertical = 2.dp)) {
                            Box(modifier = Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(batch.batchName, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                    if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
