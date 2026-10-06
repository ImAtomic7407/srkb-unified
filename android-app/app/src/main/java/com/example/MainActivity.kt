package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.*
import com.example.ui.components.StatusBadge
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import kotlin.reflect.KProperty

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restore a persisted session (token + user) before the first compose, so a relaunch with
        // no network still lands the user back in the app (LoginScreen's offline auto-resume needs
        // currentUser to already be non-null when it runs).
        AppDatabase.initAppContext(applicationContext)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                AppNavigator()
                // Drawn over whatever screen is showing, so a build the server has
                // retired is blocked everywhere rather than only on one screen.
                UpdateGate()
            }
        }
    }
}

// UpdateGate() lives in the FLAVOUR source sets, not here:
//
//     app/src/play/java/com/example/UpdateGate.kt      -> points at the Play Store
//     app/src/sideload/java/com/example/UpdateGate.kt  -> opens the download page in a browser
//
// It is split because the two channels have genuinely different correct behaviour, not because
// of styling. Play's Device and Network Abuse policy forbids a Play-distributed build from
// updating itself by any route other than Play, so the play flavour must never carry a download
// path; the sideload build is not on Play and its ONLY route is the download page. A single
// shared dialog would have to be wrong for one of them. The version check itself
// (AppData.checkForUpdate) is shared and lives in main.

@Composable
fun AppNavigator() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "login") {
        composable("login") {
            LoginScreen(
                onNavigateToMain = {
                    navController.navigate("main") {
                        popUpTo("login") { inclusive = true }
                    }
                },
                onNavigateToForceChange = {
                    navController.navigate("force_change") {
                        popUpTo("login") { inclusive = true }
                    }
                },
                onNavigateToWaiting = {
                    navController.navigate("waiting_approval") {
                        popUpTo("login") { inclusive = true }
                    }
                }
            )
        }

        composable("force_change") {
            ForcePasswordChangeScreen(
                onPasswordChanged = {
                    navController.navigate("main") {
                        popUpTo("force_change") { inclusive = true }
                    }
                },
                onLogout = {
                    AppDatabase.logout()
                    navController.navigate("login") {
                        popUpTo("force_change") { inclusive = true }
                    }
                }
            )
        }

        composable("waiting_approval") {
            WaitingApprovalScreen(
                onLogout = {
                    AppDatabase.logout()
                    navController.navigate("login") {
                        popUpTo("waiting_approval") { inclusive = true }
                    }
                },
                onApproved = {
                    navController.navigate("main") {
                        popUpTo("waiting_approval") { inclusive = true }
                    }
                }
            )
        }

        composable("main") {
            MainDrawerShell(
                onLogout = {
                    AppDatabase.logout()
                    navController.navigate("login") {
                        popUpTo("main") { inclusive = true }
                    }
                }
            )
        }
    }
}

/**
 * Which screens may be restored when the shell is re-entered or the app is reopened.
 *
 * Deliberately an ALLOWLIST, and gated by exactly the same role/mode conditions the drawer uses
 * to decide which items it shows. Two things go wrong otherwise: a screen the current role cannot
 * reach renders blank with no way back except the drawer, and `student_details` depends on
 * `studentIdForDetails`, which is intentionally NOT persisted, so restoring it opens an empty
 * detail page. Anything unlisted - including `notifications` and any screen added later - falls
 * back to the role's normal landing page.
 */
private fun canRestoreScreen(screen: String, role: String?, mode: String): Boolean = when (screen) {
    "dashboard" -> role != "SUPER_ADMIN"
    "profile" -> true
    "registerer_mark_attendance", "registerer_new_student",
    "registerer_centre_change" -> role != "SUPER_ADMIN" && mode == "registerer"
    "registerer_archive" -> role == "ADMIN" || (role != "SUPER_ADMIN" && mode == "registerer")
    "caller_queue", "caller_followups", "caller_my_attendance",
    "caller_batch_calling" -> role != "SUPER_ADMIN" && mode == "caller"
    "admin_analytics", "admin_batches", "admin_pools", "admin_session_campaigns",
    "admin_downloads", "admin_sync_config", "admin_users" -> role == "ADMIN"
    "admin_dropdowns" -> role == "ADMIN" || role == "SUPER_ADMIN"
    "host_stats", "host_inactive_centres", "host_centres_inventory",
    "host_reports", "host_admin_management" -> role == "SUPER_ADMIN"
    else -> false
}

/**
 * Back stack for the drawer shell's single `activeScreen` slot, so the phone's Back button walks
 * back through the screens the operator actually came through instead of closing the app.
 *
 * Used as a delegate (`var activeScreen by nav`), so every existing `activeScreen = "x"` call site
 * becomes a push without being rewritten. Rules:
 * - Going to a screen already on the stack (e.g. a screen's own ← arrow going to "dashboard")
 *   unwinds to it instead of stacking a loop; going to the landing page clears the stack.
 * - Detail screens are never pushed: they depend on `studentIdForDetails`, which is not kept, so
 *   Back landing on one would open an empty "reference error" page.
 * - Drawer picks, mode switches and centre switches are top-level moves ([resetTo]): Back from
 *   there goes to the role's landing page, and from the landing page Android leaves the app as usual.
 */
private class ScreenBackStack(initial: String, private val landing: String) {
    private var current by mutableStateOf(initial)
    private val history = mutableStateListOf<String>()

    val canGoBack: Boolean get() = history.isNotEmpty() || current != landing

    operator fun getValue(thisRef: Any?, property: KProperty<*>): String = current
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: String) = push(value)

    private fun push(screen: String) {
        if (screen == current) return
        val earlier = history.lastIndexOf(screen)
        when {
            screen == landing -> history.clear()
            earlier >= 0 -> while (history.size > earlier) history.removeAt(history.lastIndex)
            current !in TRANSIENT_SCREENS -> {
                history.add(current)
                if (history.size > MAX_DEPTH) history.removeAt(0)
            }
        }
        current = screen
    }

    fun resetTo(screen: String) {
        history.clear()
        if (screen != landing) history.add(landing)
        current = screen
    }

    fun back() {
        current = if (history.isNotEmpty()) history.removeAt(history.lastIndex) else landing
    }

    private companion object {
        val TRANSIENT_SCREENS = setOf("student_details", "caller_active_detail")
        const val MAX_DEPTH = 20
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainDrawerShell(onLogout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val user = AppDatabase.currentUser
    val isOnline = AppDatabase.isOnline
    val selectedCategory = AppDatabase.selectedCategory
    val selectedCentreId = AppDatabase.selectedCentreId
    val currentMode = AppDatabase.currentMode

    // Come back to where you left off - including after a cold, offline start. Restored through
    // an allowlist (see canRestoreScreen) rather than trusting the stored value, and dropped
    // entirely on logout so one operator never lands on another's page.
    val defaultScreen = if (user?.role == "SUPER_ADMIN") "host_stats" else "dashboard"
    val nav = remember {
        ScreenBackStack(
            initial = AppDatabase.uiSelection("activeScreen", scoped = false)
                ?.takeIf { canRestoreScreen(it, user?.role, currentMode) }
                ?: defaultScreen,
            landing = defaultScreen,
        )
    }
    var activeScreen by nav
    // Phone Back = go back one screen. Registered first, so anything a screen registers for its own
    // inner state (an open card, a drill-down) and the open drawer below all take priority over it.
    BackHandler(enabled = nav.canGoBack) { nav.back() }
    LaunchedEffect(activeScreen, currentMode) {
        if (canRestoreScreen(activeScreen, user?.role, currentMode)) {
            AppDatabase.setUiSelection("activeScreen", activeScreen, scoped = false)
        }
    }
    var studentIdForDetails by remember { mutableStateOf<String?>(null) }
    var showCentreSheet by remember { mutableStateOf(false) }
    var showCentreLoginDialog by remember { mutableStateOf(false) }
    var pendingCentreSwitch by remember { mutableStateOf<Centre?>(null) }
    var centreLoginUsername by remember { mutableStateOf("") }
    var centreLoginPassword by remember { mutableStateOf("") }
    var centreLoginError by remember { mutableStateOf<String?>(null) }
    var centreLoginSubmitting by remember { mutableStateOf(false) }

    val activeCentre = AppDatabase.centres.find { it.id == selectedCentreId }
        ?: AppDatabase.centres.firstOrNull()
        ?: Centre(id = "", name = "—", code = "")

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight(),
                drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Drawer Header Profile Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = user?.name ?: "Unknown Operator",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = user?.role ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showCentreSheet = true },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(activeCentre.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // Category Switcher — only for operational roles (not SUPER_ADMIN)
                    val supportsBoth = activeCentre.enabledYouth && activeCentre.enabledCongregation
                    val supportsEither = activeCentre.enabledYouth || activeCentre.enabledCongregation
                    if (supportsEither && user?.role != "SUPER_ADMIN") {
                        // Auto-fix: if current category is unsupported by this centre, switch to one that is
                        // Keyed on the flags as well: they arrive from the server after the centre
                        // is picked, and a super-admin can switch one off later. Keyed only on the
                        // centre, a saved "Congregation" stuck even after it was switched off.
                        LaunchedEffect(selectedCentreId, activeCentre.enabledYouth, activeCentre.enabledCongregation, selectedCategory) {
                            val fixed = when {
                                !activeCentre.enabledYouth && selectedCategory == "Youth" -> "Congregation"
                                !activeCentre.enabledCongregation && selectedCategory == "Congregation" -> "Youth"
                                else -> null
                            }
                            if (fixed != null) {
                                AppDatabase.selectedCategory = fixed
                                AppDatabase.bumpDataRevision()
                                AppDatabase.loadDataFromBackend(scope)
                            }
                        }

                        Text("Active Audience Classification", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        if (supportsBoth) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(
                                            if (selectedCategory == "Youth") MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                            RoundedCornerShape(6.dp)
                                        )
                                        .clickable {
                                            if (AppDatabase.selectedCategory != "Youth") {
                                                AppDatabase.selectedCategory = "Youth"
                                                AppDatabase.bumpDataRevision()
                                                AppDatabase.loadDataFromBackend(scope)
                                            }
                                            Toast.makeText(context, "Switched scope: Youth classification", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Youth", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(
                                            if (selectedCategory == "Congregation") MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                            RoundedCornerShape(6.dp)
                                        )
                                        .clickable {
                                            if (AppDatabase.selectedCategory != "Congregation") {
                                                AppDatabase.selectedCategory = "Congregation"
                                                AppDatabase.bumpDataRevision()
                                                AppDatabase.loadDataFromBackend(scope)
                                            }
                                            Toast.makeText(context, "Switched scope: Congregation records", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Congregation", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        } else {
                            // Single category — show a read-only locked chip
                            val onlyCategory = if (activeCentre.enabledYouth) "Youth" else "Congregation"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                    .padding(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(onlyCategory, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Mode switcher — dual-capability users (calling + attendance access), plus ADMINs
                    // who oversee both workflows and need to reach the caller screens (Session Calls,
                    // My Attendance) without being locked to registerer mode.
                    val canSwitchMode = user?.role == "ADMIN"
                        || (user?.permissions?.contains("calling_access") == true && user.isAeAccessEnabled)
                    if (canSwitchMode) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("ACTIVE WORKSPACE MODE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(
                                        if (currentMode == "registerer") MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        AppDatabase.currentMode = "registerer"
                                        nav.resetTo("dashboard")
                                        scope.launch { drawerState.close() }
                                        Toast.makeText(context, "Switched to Registerer mode", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                    Icon(Icons.Default.AppRegistration, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Registerer", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(
                                        if (currentMode == "caller") MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        AppDatabase.currentMode = "caller"
                                        nav.resetTo("dashboard")
                                        scope.launch { drawerState.close() }
                                        Toast.makeText(context, "Switched to Caller mode", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Caller", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Unified workspace section items — hidden from SUPER_ADMIN
                    if (user?.role != "SUPER_ADMIN") Text("WORKSPACE CORE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                    if (user?.role != "SUPER_ADMIN") NavigationDrawerItem(
                        icon = { Icon(Icons.Default.SpaceDashboard, contentDescription = null) },
                        label = { Text("${if (currentMode == "registerer") "Registerer" else "Caller"} Dashboard") },
                        selected = activeScreen == "dashboard",
                        onClick = { nav.resetTo("dashboard"); scope.launch { drawerState.close() } }
                    )

                    if (user?.role != "SUPER_ADMIN" && currentMode == "registerer") {
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.AppRegistration, contentDescription = null) },
                            label = { Text("Attendance Marking") },
                            selected = activeScreen == "registerer_mark_attendance",
                            onClick = { nav.resetTo("registerer_mark_attendance"); scope.launch { drawerState.close() } }
                        )
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.PersonAddAlt1, contentDescription = null) },
                            label = { Text("New Student Signup") },
                            selected = activeScreen == "registerer_new_student",
                            onClick = { nav.resetTo("registerer_new_student"); scope.launch { drawerState.close() } }
                        )
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.MoveToInbox, contentDescription = null) },
                            label = { Text("Transfer Centre") },
                            selected = activeScreen == "registerer_centre_change",
                            onClick = { nav.resetTo("registerer_centre_change"); scope.launch { drawerState.close() } }
                        )
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.Inventory, contentDescription = null) },
                            label = { Text("Archived Storage") },
                            selected = activeScreen == "registerer_archive",
                            onClick = { nav.resetTo("registerer_archive"); scope.launch { drawerState.close() } }
                        )
                    } else if (user?.role != "SUPER_ADMIN") {
                        // Caller operations — unified into two primary tabs (Calling + Follow-ups).
                        // Everything is session-based now; the old separate Session Calls / Batch
                        // Calling screens are folded into the single "Calling" tab. My Attendance is
                        // demoted below a divider (read-only scoreboard, not primary calling work).
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) },
                            label = { Text("Calling") },
                            selected = activeScreen == "caller_queue",
                            onClick = { nav.resetTo("caller_queue"); scope.launch { drawerState.close() } }
                        )
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.CrisisAlert, contentDescription = null) },
                            label = { Text("Follow-ups") },
                            selected = activeScreen == "caller_followups",
                            onClick = { nav.resetTo("caller_followups"); scope.launch { drawerState.close() } }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.LibraryBooks, contentDescription = null) },
                            label = { Text("My Attendance") },
                            selected = activeScreen == "caller_my_attendance",
                            onClick = { nav.resetTo("caller_my_attendance"); scope.launch { drawerState.close() } }
                        )
                    }

                    // Administrative Section (only for ADMIN, not SUPER_ADMIN who has a separate authority section)
                    if (user?.role == "ADMIN") {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("ADMIN CHANNELS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)

                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.AutoGraph, contentDescription = null) },
                            label = { Text("Centre Analytics") },
                            selected = activeScreen == "admin_analytics",
                            onClick = { nav.resetTo("admin_analytics"); scope.launch { drawerState.close() } }
                        )
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.Layers, contentDescription = null) },
                            label = { Text("Batch Management") },
                            selected = activeScreen == "admin_batches",
                            onClick = { nav.resetTo("admin_batches"); scope.launch { drawerState.close() } }
                        )
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.JoinRight, contentDescription = null) },
                            label = { Text("Pool Distributions") },
                            selected = activeScreen == "admin_pools",
                            onClick = { nav.resetTo("admin_pools"); scope.launch { drawerState.close() } }
                        )
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                            label = { Text("Planned Sessions") },
                            selected = activeScreen == "admin_session_campaigns",
                            onClick = { nav.resetTo("admin_session_campaigns"); scope.launch { drawerState.close() } }
                        )
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.Inventory, contentDescription = null) },
                            label = { Text("Archive") },
                            selected = activeScreen == "registerer_archive",
                            onClick = { nav.resetTo("registerer_archive"); scope.launch { drawerState.close() } }
                        )
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                            label = { Text("Excel Data Exporters") },
                            selected = activeScreen == "admin_downloads",
                            onClick = { nav.resetTo("admin_downloads"); scope.launch { drawerState.close() } }
                        )
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.DisplaySettings, contentDescription = null) },
                            label = { Text("Directory Setup") },
                            selected = activeScreen == "admin_dropdowns",
                            onClick = { nav.resetTo("admin_dropdowns"); scope.launch { drawerState.close() } }
                        )
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.SettingsInputComponent, contentDescription = null) },
                            label = { Text("Link Sync Config") },
                            selected = activeScreen == "admin_sync_config",
                            onClick = { nav.resetTo("admin_sync_config"); scope.launch { drawerState.close() } }
                        )
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.Groups3, contentDescription = null) },
                            label = { Text("Manage Staff") },
                            selected = activeScreen == "admin_users",
                            onClick = { nav.resetTo("admin_users"); scope.launch { drawerState.close() } }
                        )
                    }

                    // Master Host (Only visible to SUPER_ADMIN role ihost)
                    if (user?.role == "SUPER_ADMIN") {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("SUPER HOST AUTHORITY", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)

                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.Hub, contentDescription = null) },
                            label = { Text("Commander Dashboard") },
                            selected = activeScreen == "host_stats",
                            onClick = { nav.resetTo("host_stats"); scope.launch { drawerState.close() } }
                        )
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.DomainVerification, contentDescription = null) },
                            label = { Text("Inactive Clocks Monitor") },
                            selected = activeScreen == "host_inactive_centres",
                            onClick = { nav.resetTo("host_inactive_centres"); scope.launch { drawerState.close() } }
                        )
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.MapsHomeWork, contentDescription = null) },
                            label = { Text("Centre Inventory setup") },
                            selected = activeScreen == "host_centres_inventory",
                            onClick = { nav.resetTo("host_centres_inventory"); scope.launch { drawerState.close() } }
                        )
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.AutoGraph, contentDescription = null) },
                            label = { Text("System Wide Metrics") },
                            selected = activeScreen == "host_reports",
                            onClick = { nav.resetTo("host_reports"); scope.launch { drawerState.close() } }
                        )
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.ManageAccounts, contentDescription = null) },
                            label = { Text("Centre Admin Management") },
                            selected = activeScreen == "host_admin_management",
                            onClick = { nav.resetTo("host_admin_management"); scope.launch { drawerState.close() } }
                        )
                        // The shared (global) dictionary options can only be edited by the host, yet
                        // this screen used to appear for centre admins only — leaving the one role
                        // with permission over the shared list unable to reach it at all.
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.DisplaySettings, contentDescription = null) },
                            label = { Text("Shared Directory") },
                            selected = activeScreen == "admin_dropdowns",
                            onClick = { nav.resetTo("admin_dropdowns"); scope.launch { drawerState.close() } }
                        )
                    }

                    // Baseline operations
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                        label = { Text("Personal Profile") },
                        selected = activeScreen == "profile",
                        onClick = { nav.resetTo("profile"); scope.launch { drawerState.close() } }
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.ExitToApp, contentDescription = null) },
                        label = { Text("Sign Out Session") },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onLogout()
                        }
                    )
                }
            }
        },
        content = {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            // Screen names are derived from the route, so long ones like
                            // "REGISTERER MARK ATTENDANCE" wrapped onto a second line and made
                            // the bar nearly twice as tall. One line, ellipsised — and while the
                            // keyboard is up the workspace subtitle drops out too, since that is
                            // reference information and the space belongs to the form.
                            val typing = WindowInsets.isImeVisible
                            Column {
                                val titleStr = when (activeScreen) {
                                    "dashboard" -> activeCentre.name
                                    else -> activeScreen.replace("_", " ").uppercase()
                                }
                                Text(
                                    text = titleStr,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = if (typing) 15.sp else 18.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                if (!typing) {
                                    Text(
                                        text = "Workspace mode: $currentMode • $selectedCategory",
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        },
                        actions = {
                            // Online/Offline status toggler trigger (C6)
                            Row(
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .background(
                                        color = if (isOnline) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                                        shape = RoundedCornerShape(20.dp)
                                    )
                                    .clickable {
                                        // Connectivity is auto-detected now; tapping just forces an
                                        // immediate re-check + sync instead of waiting for the poll.
                                        AppDatabase.recheckConnectivity()
                                        AppDatabase.syncOfflineQueue()
                                        Toast.makeText(context, if (isOnline) "Checking connection…" else "Reconnecting…", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(if (isOnline) Color(0xFF2E7D32) else Color(0xFFC62828), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isOnline) "ONLINE" else "OFFLINE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isOnline) Color(0xFF2E7D32) else Color(0xFFC62828)
                                )
                            }

                            val failedUploads = AppDatabase.offlineQueue.filter { it.status == "failed_needs_attention" }
                            if (failedUploads.isNotEmpty()) {
                                IconButton(onClick = {
                                    failedUploads.forEach { AppDatabase.retryFailedItem(it.id) }
                                    Toast.makeText(context, "Retrying ${failedUploads.size} failed upload(s)...", Toast.LENGTH_SHORT).show()
                                }) {
                                    Box {
                                        Icon(Icons.Default.CloudOff, contentDescription = "Failed uploads — tap to retry", tint = Color(0xFFC62828))
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .background(Color.Red, CircleShape)
                                                .padding(horizontal = 3.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = if (failedUploads.size > 9) "9+" else failedUploads.size.toString(),
                                                color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }

                            IconButton(onClick = { activeScreen = "notifications" }) {
                                Box {
                                    Icon(Icons.Default.Notifications, contentDescription = "Inbox")
                                    val unread = AppDatabase.notifications.count { !it.isRead }
                                    if (unread > 0) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .background(Color.Red, CircleShape)
                                                .padding(horizontal = 3.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = if (unread > 9) "9+" else unread.toString(),
                                                color = Color.White,
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.Bold,
                                                lineHeight = 10.sp
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                        )
                    )
                },
                content = { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (activeScreen) {
                            "dashboard" -> {
                                if (currentMode == "registerer") {
                                    RegistererDashboard(
                                        onNavigateToMarkAttendance = { activeScreen = "registerer_mark_attendance" },
                                        onNavigateToRegisterStudent = { activeScreen = "registerer_new_student" },
                                        onNavigateToLists = { activeScreen = "admin_downloads" }
                                    )
                                } else {
                                    CallerDashboard(
                                        onNavigateToQueue = { activeScreen = "caller_queue" },
                                        onNavigateToFollowups = { activeScreen = "caller_followups" },
                                        onNavigateToMyAttendance = { activeScreen = "caller_my_attendance" },
                                        onNavigateToBatchCalling = { activeScreen = "caller_batch_calling" }
                                    )
                                }
                            }
                            "registerer_mark_attendance" -> AttendanceMarkingScreen(
                                onNavigateBack = { activeScreen = "dashboard" },
                                onViewStudentDetails = { sId ->
                                    studentIdForDetails = sId
                                    activeScreen = "student_details"
                                }
                            )
                            "registerer_new_student" -> StudentRegistrationScreen(
                                onNavigateBack = { activeScreen = "dashboard" }
                            )
                            "student_details" -> {
                                val sId = studentIdForDetails
                                if (sId != null) {
                                    StudentDetailScreen(
                                        studentId = sId,
                                        // Back to whichever list opened it (Follow-ups, Archive,
                                        // Batches, Mark Attendance) — it used to always be the dashboard.
                                        onNavigateBack = {
                                            studentIdForDetails = null
                                            nav.back()
                                        }
                                    )
                                } else {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Profile reference error.") }
                                }
                            }
                            "registerer_centre_change" -> CentreChangeRequestScreen(
                                onNavigateBack = { activeScreen = "dashboard" }
                            )
                            "registerer_archive" -> ArchivedDataScreen(
                                onViewStudentDetails = { sId ->
                                    studentIdForDetails = sId
                                    activeScreen = "student_details"
                                }
                            )
                            "caller_queue" -> CallingQueueScreen(
                                onViewDetail = { sId ->
                                    studentIdForDetails = sId
                                    activeScreen = "caller_active_detail"
                                },
                                onNavigateBack = { activeScreen = "dashboard" }
                            )
                            "caller_active_detail" -> {
                                val sId = studentIdForDetails
                                if (sId != null) {
                                    StudentCallDetailScreen(
                                        studentId = sId,
                                        onNavigateBack = {
                                            studentIdForDetails = null
                                            activeScreen = "caller_queue"
                                        }
                                    )
                                } else {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Workspace reference error.") }
                                }
                            }
                            "caller_followups" -> FollowUpScreen(
                                onViewDetail = { sId ->
                                    studentIdForDetails = sId
                                    activeScreen = "student_details"
                                }
                            )
                            "caller_batch_calling" -> BatchCallingScreen(
                                onViewDetail = { sId ->
                                    studentIdForDetails = sId
                                    activeScreen = "student_details"
                                }
                            )
                            "caller_session_calls" -> SessionCallScreen()
                            "caller_my_attendance" -> CallingScoreboardScreen(
                                onNavigateBack = { activeScreen = "dashboard" }
                            )
                            "admin_batches" -> BatchManagementScreen(
                                onViewStudentDetails = { sId ->
                                    studentIdForDetails = sId
                                    activeScreen = "student_details"
                                }
                            )
                            "admin_pools" -> BatchPoolsScreen()
                            "admin_session_campaigns" -> SessionCampaignScreen()
                            "admin_sync_config" -> AttendanceSyncConfigScreen()
                            "admin_users" -> ManageUsersScreen()
                            "admin_dropdowns" -> DropdownSettingsScreen()
                            "admin_downloads" -> DownloadsScreen()
                            "host_stats" -> HostStatsDashboardScreen(
                                onNavigateToInactiveMonitor = { activeScreen = "host_inactive_centres" },
                                onNavigateToCentreManagement = { activeScreen = "host_centres_inventory" }
                            )
                            "host_inactive_centres" -> InactiveCentreMonitorScreen(
                                onNavigateToCentreManagement = { activeScreen = "host_centres_inventory" }
                            )
                            "host_centres_inventory" -> CentreManagementScreen()
                            "host_reports" -> AnalyticsDashboardScreen(scopeCentreId = null)
                            "admin_analytics" -> AnalyticsDashboardScreen(scopeCentreId = AppDatabase.selectedFlaskCentreId.ifEmpty { user?.centreId }.takeIf { !it.isNullOrEmpty() })
                            "host_admin_management" -> HostAdminManagementScreen()
                            "notifications" -> NotificationsScreen()
                            "profile" -> ProfileScreen(onLogout = onLogout)
                        }
                    }
                }
            )
        }
    )

    // An open drawer closes on Back before anything else. Composed only while open, so it is always
    // the most recently registered handler and wins over any screen's own handler underneath.
    if (drawerState.isOpen) {
        BackHandler { scope.launch { drawerState.close() } }
    }

    // Centre login popup: shown when an ADMIN clicks a centre they don't have a role in
    if (showCentreLoginDialog && pendingCentreSwitch != null) {
        val pendingCentre = pendingCentreSwitch!!
        Dialog(onDismissRequest = { showCentreLoginDialog = false; pendingCentreSwitch = null }) {
            Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Access ${pendingCentre.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Sign in with credentials that have access to this centre.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = centreLoginUsername,
                        onValueChange = { centreLoginUsername = it; centreLoginError = null },
                        label = { Text("Username / Mobile") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = centreLoginPassword,
                        onValueChange = { centreLoginPassword = it; centreLoginError = null },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (centreLoginError != null) {
                        Text(centreLoginError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { showCentreLoginDialog = false; pendingCentreSwitch = null }, modifier = Modifier.weight(1f)) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                centreLoginSubmitting = true
                                centreLoginError = null
                                scope.launch {
                                    val err = AppDatabase.logIn(centreLoginUsername, centreLoginPassword)
                                    centreLoginSubmitting = false
                                    if (err == null) {
                                        AppDatabase.selectedCentreId = pendingCentre.id
                                        AppDatabase.selectedFlaskCentreId = pendingCentre.code
                                        nav.resetTo("dashboard")
                                        AppDatabase.loadDataFromBackend(scope)
                                        showCentreLoginDialog = false
                                        pendingCentreSwitch = null
                                        Toast.makeText(context, "Switched to ${pendingCentre.name}", Toast.LENGTH_SHORT).show()
                                    } else {
                                        centreLoginError = err
                                    }
                                }
                            },
                            enabled = centreLoginUsername.isNotBlank() && centreLoginPassword.isNotBlank() && !centreLoginSubmitting,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (centreLoginSubmitting) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                            else Text("Sign In")
                        }
                    }
                }
            }
        }
    }

    if (showCentreSheet) {
        Dialog(onDismissRequest = { showCentreSheet = false }) {
            CentreSelectorSheet(
                onDismiss = { showCentreSheet = false },
                onCentreSelected = { centre ->
                    val userCentreId = AppDatabase.currentUser?.centreId
                    val userRole = AppDatabase.currentUser?.role
                    val hasAccess = userRole == "SUPER_ADMIN" || centre.id == userCentreId
                    if (hasAccess) {
                        AppDatabase.selectedCentreId = centre.id
                        AppDatabase.selectedFlaskCentreId = centre.code
                        nav.resetTo("dashboard")
                        AppDatabase.loadDataFromBackend(scope)
                        Toast.makeText(context, "Switched to ${centre.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        pendingCentreSwitch = centre
                        centreLoginUsername = ""
                        centreLoginPassword = ""
                        centreLoginError = null
                        showCentreLoginDialog = true
                    }
                }
            )
        }
    }
}
