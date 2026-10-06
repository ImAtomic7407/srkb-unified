package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import com.example.data.*
import com.example.ui.components.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// LOGIN SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun LoginScreen(
    onNavigateToMain: () -> Unit,
    onNavigateToForceChange: () -> Unit,
    onNavigateToWaiting: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("attendease_prefs", Context.MODE_PRIVATE) }

    var username by remember { mutableStateOf(prefs.getString("saved_username", "") ?: "") }
    var password by remember { mutableStateOf(prefs.getString("saved_password", "") ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showRegisterDialog by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }

    // Offline auto-resume: a user who has logged in online at least once is authorized to re-enter
    // the app with the server down. Probe reachability first (isOnline still defaults optimistically
    // until the first probe lands), and if we're genuinely offline, prime the caches and walk in.
    LaunchedEffect(Unit) {
        if (AppDatabase.isOfflineLoginAuthorized()) {
            AppDatabase.refreshReachability()
            if (!AppDatabase.isOnline) {
                AppDatabase.loadCachedData()
                delay(600)
                onNavigateToMain()
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 400.dp)
        ) {
            // Offline resume banner
            AnimatedVisibility(visible = !AppDatabase.isOnline) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudOff, contentDescription = null, tint = Color(0xFFE65100))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                "Offline — resuming as ${AppDatabase.currentUser?.name ?: "cached user"}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100)
                            )
                            Text("Redirecting to last screen…", style = MaterialTheme.typography.labelSmall, color = Color(0xFFE65100))
                        }
                    }
                }
            }

            // Main login card
            Surface(
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.LockPerson,
                        contentDescription = "App Logo",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "AttendEase Unified",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Sign in to access your dashboard",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    // Username field
                    AppTextField(
                        value = username,
                        onValueChange = { username = it; errorMessage = null },
                        label = "Username or Mobile",
                        leadingIcon = Icons.Default.Person,
                        modifier = Modifier.testTag("username_input")
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Password field with show/hide toggle
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; errorMessage = null },
                        label = { Text("Password") },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("password_input"),
                        shape = RoundedCornerShape(12.dp),
                        isError = errorMessage != null
                    )

                    // Error message
                    AnimatedVisibility(visible = errorMessage != null) {
                        Text(
                            text = errorMessage ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp).fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val scope = rememberCoroutineScope()
                    Button(
                        onClick = {
                            isSubmitting = true
                            errorMessage = null
                            scope.launch {
                                // Offline sign-in: if the server is down but this device was already
                                // authorized by a prior online login, resume on cached data instead of
                                // failing. No question asked — that's the whole point of offline mode.
                                if (!AppDatabase.isOnline && AppDatabase.isOfflineLoginAuthorized()) {
                                    AppDatabase.loadCachedData()
                                    isSubmitting = false
                                    onNavigateToMain()
                                    return@launch
                                }
                                val error = AppDatabase.logIn(username, password)
                                isSubmitting = false
                                if (error == null) {
                                    prefs.edit()
                                        .putString("saved_username", username)
                                        .putString("saved_password", password)
                                        .apply()
                                    val user = AppDatabase.currentUser
                                    when {
                                        user?.status == "pending" -> onNavigateToWaiting()
                                        AppDatabase.mustChangePassword -> onNavigateToForceChange()
                                        else -> {
                                            AppDatabase.loadDataFromBackend(this)
                                            onNavigateToMain()
                                        }
                                    }
                                } else {
                                    errorMessage = error
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp).testTag("submit_button"),
                        enabled = username.isNotEmpty() && password.isNotEmpty() && !isSubmitting,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                        } else {
                            Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sign In", fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = { showRegisterDialog = true }, modifier = Modifier.testTag("request_access_button")) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Request Account Access")
                    }
                }
            }

        }
    }

    if (showRegisterDialog) {
        RegisterRequestDialog(onDismiss = { showRegisterDialog = false })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// FORCE PASSWORD CHANGE SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ForcePasswordChangeScreen(onPasswordChanged: () -> Unit, onLogout: () -> Unit) {
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var newPasswordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Strength computation
    val strength = when {
        newPassword.length < 4 -> 0
        newPassword.length < 8 -> 1
        newPassword.length < 12 && newPassword.any { it.isDigit() } -> 2
        newPassword.length >= 12 && newPassword.any { it.isDigit() } && newPassword.any { !it.isLetterOrDigit() } -> 4
        else -> 3
    }
    val strengthLabel = listOf("", "Weak", "Fair", "Strong", "Very Strong")
    val strengthColor = listOf(Color.Transparent, Color(0xFFC62828), Color(0xFFEF6C00), Color(0xFF2E7D32), Color(0xFF1565C0))

    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 400.dp)) {
            Surface(shape = RoundedCornerShape(24.dp), shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Change Password Required", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(
                        "An admin reset your credentials. Set a new permanent password before continuing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    // New password field
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it; error = null },
                        label = { Text("New Password") },
                        visualTransformation = if (newPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { newPasswordVisible = !newPasswordVisible }) {
                                Icon(if (newPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    // Strength bar
                    AnimatedVisibility(visible = newPassword.isNotEmpty()) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                (1..4).forEach { level ->
                                    Box(
                                        modifier = Modifier.weight(1f).height(4.dp).then(
                                            if (strength >= level) Modifier else Modifier
                                        ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        LinearProgressIndicator(
                                            progress = { if (strength >= level) 1f else 0f },
                                            modifier = Modifier.fillMaxWidth().height(4.dp),
                                            color = strengthColor[strength],
                                            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                        )
                                    }
                                }
                            }
                            if (strength > 0) {
                                Text(strengthLabel[strength], style = MaterialTheme.typography.labelSmall, color = strengthColor[strength], modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Confirm password field
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it; error = null },
                        label = { Text("Confirm New Password") },
                        visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        leadingIcon = { Icon(Icons.Default.LockReset, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                Icon(if (confirmPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                            }
                        },
                        isError = confirmPassword.isNotEmpty() && confirmPassword != newPassword,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    // Match indicator
                    AnimatedVisibility(visible = confirmPassword.isNotEmpty()) {
                        Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (confirmPassword == newPassword) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (confirmPassword == newPassword) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (confirmPassword == newPassword) "Passwords match" else "Passwords do not match",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (confirmPassword == newPassword) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    AnimatedVisibility(visible = error != null) {
                        Text(error ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            when {
                                newPassword.length < 8 -> error = "Password must be at least 8 characters."
                                newPassword != confirmPassword -> error = "Passwords do not match."
                                else -> {
                                    isSubmitting = true
                                    scope.launch {
                                        val ok = AppDatabase.changePasswordOnBackend(newPassword)
                                        isSubmitting = false
                                        if (ok) {
                                            AppDatabase.mustChangePassword = false
                                            // Same load the normal login performs. Without it this
                                            // route reaches the app with NOTHING fetched — no
                                            // outcome responses, batches, centres or members — and
                                            // the caller's picker renders "No response is set up".
                                            AppDatabase.loadDataFromBackend(this)
                                            onPasswordChanged()
                                        } else {
                                            error = "Failed to save password. Check your connection and try again."
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        enabled = newPassword.isNotEmpty() && confirmPassword.isNotEmpty() && !isSubmitting
                    ) {
                        if (isSubmitting) CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                        else Text("Change Password & Continue", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onLogout) {
                        Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Logout")
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// WAITING APPROVAL SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun WaitingApprovalScreen(onLogout: () -> Unit, onApproved: () -> Unit) {
    var currentStatus by remember { mutableStateOf("pending") }
    var pollCount by remember { mutableStateOf(0) }
    var isChecking by remember { mutableStateOf(false) }
    var showContactAdvisory by remember { mutableStateOf(false) }

    // Poll backend every 5 seconds for real approval status
    LaunchedEffect(Unit) {
        while (currentStatus == "pending") {
            delay(5000)
            isChecking = true
            val status = AppDatabase.checkApprovalStatus()
            pollCount++
            currentStatus = status
            isChecking = false
            if (pollCount >= 3) showContactAdvisory = true
            when (status) {
                "approved" -> {
                    AppDatabase.approveRequest(AppDatabase.currentUser?.id ?: "")
                    // Same reason as the forced-password-change route above: this jumps straight
                    // to main, so nothing would be loaded for a freshly approved user.
                    AppDatabase.loadDataFromBackend(this)
                    onApproved()
                }
                "rejected" -> {
                    // Stay on this screen; UI shows rejected badge; user can sign out
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 400.dp)) {
            // Animated icon
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(96.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.HourglassEmpty, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))

            Text("Awaiting Approval", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Your request for centre access is pending admin review. This page checks automatically every few seconds.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))
            StatusBadge(text = "Status: $currentStatus", status = currentStatus)

            // Poll indicator
            Spacer(modifier = Modifier.height(12.dp))
            if (isChecking) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Checking…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("Last checked $pollCount time(s)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Contact advisory after extended wait
            AnimatedVisibility(visible = showContactAdvisory, enter = fadeIn() + expandVertically()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFE65100), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Still waiting? Contact your centre administrator directly to expedite approval.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE65100)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = {
                    isChecking = true
                    pollCount++
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Check Now")
            }
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onLogout) {
                Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Cancel & Sign Out")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CENTRE SELECTOR SHEET
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun CentreSelectorSheet(
    onDismiss: () -> Unit,
    onCentreSelected: (Centre) -> Unit,
    showTransferRequest: Boolean = false,
    onRequestTransfer: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Select Active Centre", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Switching centre reloads all data for that scope.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn {
                items(AppDatabase.centres) { centre ->
                    val isSelected = centre.id == AppDatabase.selectedCentreId
                    val userRole = AppDatabase.currentUser?.role
                    val userCentreId = AppDatabase.currentUser?.centreId
                    val hasAccess = userRole == "SUPER_ADMIN" || centre.id == userCentreId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCentreSelected(centre); onDismiss() }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (hasAccess) Icons.Default.LocationOn else Icons.Default.Lock,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else if (hasAccess) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                centre.name,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else if (hasAccess) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                            Text("Code: ${centre.code}", style = MaterialTheme.typography.bodySmall)
                            // Category badges
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                                if (centre.enabledYouth) StatusBadge(text = "Youth", status = "active")
                                if (centre.enabledCongregation) StatusBadge(text = "Congregation", status = "active")
                                if (centre.status == "inactive") StatusBadge(text = "Inactive", status = "inactive")
                                if (!hasAccess) StatusBadge(text = "Login Required", status = "inactive")
                            }
                        }
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }

            // Registerer-mode: request centre transfer
            if (showTransferRequest && onRequestTransfer != null) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = { onDismiss(); onRequestTransfer() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.MoveToInbox, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Request Centre Transfer")
                }
            }

            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Cancel")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NOTIFICATIONS SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun NotificationsScreen() {
    var notificationsList by remember { mutableStateOf(AppDatabase.notifications) }
    var filterUnreadOnly by remember { mutableStateOf(false) }
    val expandedIds = remember { mutableStateOf<Set<String>>(emptySet()) }
    val actedIds = remember { mutableStateOf<Map<String, String>>(emptyMap()) } // notif id → "approved"/"denied"
    val scope = rememberCoroutineScope()
    val canActOnRequests = AppDatabase.currentUser?.role.let { it == "ADMIN" || it == "SUPER_ADMIN" }

    LaunchedEffect(AppDatabase.dataRevision) {
        AppDatabase.fetchNotificationsFromBackend()
        notificationsList = AppDatabase.notifications
    }

    val filteredList = if (filterUnreadOnly) notificationsList.filter { !it.isRead } else notificationsList

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Inbox", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            TextButton(onClick = {
                AppDatabase.notifications = AppDatabase.notifications.map { it.copy(isRead = true) }
                notificationsList = AppDatabase.notifications
            }) {
                Text("Mark all read")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // Filter chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !filterUnreadOnly, onClick = { filterUnreadOnly = false }, label = { Text("All (${notificationsList.size})") })
            FilterChip(
                selected = filterUnreadOnly,
                onClick = { filterUnreadOnly = true },
                label = { Text("Unread (${notificationsList.count { !it.isRead }})") }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (filteredList.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color(0xFF2E7D32))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("You're all caught up!", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filteredList) { item ->
                    val isExpanded = expandedIds.value.contains(item.id)
                    val expandRotation by animateFloatAsState(if (isExpanded) 180f else 0f, label = "notif_expand")

                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (item.isRead) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        border = if (!item.isRead) CardDefaults.outlinedCardBorder() else null
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Header row — tap to expand
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    expandedIds.value = if (isExpanded) expandedIds.value - item.id else expandedIds.value + item.id
                                    // Mark as read on open
                                    if (!item.isRead) {
                                        AppDatabase.notifications = AppDatabase.notifications.map {
                                            if (it.id == item.id) it.copy(isRead = true) else it
                                        }
                                        notificationsList = AppDatabase.notifications
                                    }
                                },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        imageVector = when (item.type) {
                                            "approval" -> Icons.Default.HowToReg
                                            "warning" -> Icons.Default.Warning
                                            "success" -> Icons.Default.CheckCircle
                                            else -> Icons.Default.Notifications
                                        },
                                        contentDescription = null,
                                        tint = if (!item.isRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        item.title,
                                        fontWeight = if (!item.isRead) FontWeight.Bold else FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusBadge(text = if (item.isRead) "Read" else "New", status = if (item.isRead) "read" else "pending")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp).rotate(expandRotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            // Expandable body
                            AnimatedVisibility(visible = isExpanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                Column(modifier = Modifier.padding(top = 12.dp)) {
                                    HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
                                    Text(item.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(item.createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))

                                    // Inline approve / deny for access-request notifications
                                    if (item.type == "user_request_created" && item.referenceId != null && canActOnRequests) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        val acted = actedIds.value[item.id]
                                        if (acted != null) {
                                            Text(
                                                text = if (acted == "approved") "Approved" else "Denied",
                                                color = if (acted == "approved") Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        } else {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                OutlinedButton(
                                                    onClick = {
                                                        scope.launch {
                                                            val ok = AppDatabase.rejectUserApi(item.referenceId)
                                                            if (ok) {
                                                                actedIds.value = actedIds.value + (item.id to "denied")
                                                                AppDatabase.fetchNotificationsFromBackend()
                                                                notificationsList = AppDatabase.notifications
                                                            }
                                                        }
                                                    },
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                                ) { Text("Deny") }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Button(
                                                    onClick = {
                                                        scope.launch {
                                                            val ok = AppDatabase.approveUserApi(item.referenceId)
                                                            if (ok) {
                                                                actedIds.value = actedIds.value + (item.id to "approved")
                                                                AppDatabase.fetchNotificationsFromBackend()
                                                                notificationsList = AppDatabase.notifications
                                                            }
                                                        }
                                                    }
                                                ) { Text("Approve") }
                                            }
                                        }
                                    }
                                }
                            }

                            // Timestamp always shown when collapsed
                            if (!isExpanded) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(item.createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PROFILE SCREEN
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ProfileScreen(onLogout: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("attendease_prefs", Context.MODE_PRIVATE) }
    val user = AppDatabase.currentUser ?: return
    var permissionsExpanded by remember { mutableStateOf(false) }
    val permRotation by animateFloatAsState(if (permissionsExpanded) 180f else 0f, label = "perm_rotate")

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Avatar
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(96.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(user.name.take(2).uppercase(), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(user.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("@${user.username}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        StatusBadge(text = user.role, status = "active")

        Spacer(modifier = Modifier.height(24.dp))

        // Info matrix card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Session Context", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(12.dp))

                listOf(
                    "Active Centre" to (AppDatabase.centres.find { it.id == AppDatabase.selectedCentreId }?.name ?: AppDatabase.selectedCentreId),
                    "Category" to AppDatabase.selectedCategory,
                    "Current Mode" to AppDatabase.currentMode.replaceFirstChar { it.uppercase() },
                    "Network" to if (AppDatabase.isOnline) "Online" else "Offline"
                ).forEach { (label, value) ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // Expandable permissions section
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { permissionsExpanded = !permissionsExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Permissions (${user.permissions.size})", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp).rotate(permRotation), tint = MaterialTheme.colorScheme.primary)
                }
                AnimatedVisibility(visible = permissionsExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                    Column(modifier = Modifier.padding(top = 10.dp)) {
                        if (user.permissions.isEmpty()) {
                            Text("No explicit permissions set (role-based access applies).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            user.permissions.forEach { perm ->
                                Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF2E7D32))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(perm, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Mode switch — only for users with BOTH calling_access AND Flask attendance access
        val canSwitchMode = user.permissions.contains("calling_access") && user.isAeAccessEnabled
        if (canSwitchMode) {
            Button(
                onClick = {
                    AppDatabase.currentMode = if (AppDatabase.currentMode == "registerer") "caller" else "registerer"
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Icon(Icons.Default.Cached, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Switch to ${if (AppDatabase.currentMode == "registerer") "Caller" else "Registerer"} mode")
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        OutlinedButton(
            onClick = {
                prefs.edit().remove("saved_username").remove("saved_password").apply()
                onLogout()
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Default.Logout, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sign Out")
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = { AccountDeletion.open(context) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Delete my account", color = MaterialTheme.colorScheme.error)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// REGISTER REQUEST DIALOG
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun RegisterRequestDialog(onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var dialogCentres by remember { mutableStateOf(AppDatabase.centres) }
    var selectedCentre by remember { mutableStateOf(AppDatabase.centres.firstOrNull()) }
    var wantCaller by remember { mutableStateOf(true) }
    var wantRegisterer by remember { mutableStateOf(false) }
    var isSubmitted by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Load public centre list if not already available (user is not logged in yet)
    LaunchedEffect(Unit) {
        if (dialogCentres.isEmpty()) {
            val fetched = AppDatabase.fetchPublicCentres()
            if (fetched.isNotEmpty()) {
                dialogCentres = fetched
                if (selectedCentre == null) selectedCentre = fetched.first()
            }
        }
    }

    val roleDescriptions = mapOf(
        "CALLER" to "Makes phone calls to registered students, logs outcomes, and handles follow-ups.",
        "REGISTERER" to "Marks student attendance at sessions, registers new students, and manages batch placements."
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(8.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                if (isSubmitted) {
                    // Success state
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(64.dp).align(Alignment.CenterHorizontally))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Request Submitted!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Your access request is pending admin review. You'll be notified when approved.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Done") }
                } else {
                    Text("Request Account Access", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))

                    AppTextField(value = name, onValueChange = { name = it }, label = "Your Full Name", leadingIcon = Icons.Default.Person)
                    Spacer(modifier = Modifier.height(8.dp))
                    AppTextField(value = username, onValueChange = { username = it }, label = "Desired Username", leadingIcon = Icons.Default.AlternateEmail)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Set Password") },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Centre selection
                    Text("Target Centre", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))
                    if (dialogCentres.isEmpty()) {
                        Text("Loading centres…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        dialogCentres.chunked(2).forEach { row ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.forEach { centre ->
                                    FilterChip(
                                        selected = selectedCentre?.id == centre.id,
                                        onClick = { selectedCentre = centre },
                                        label = { Text(centre.name, style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Role selection — multi-select
                    Text("Requested Access", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Select one or both roles", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = wantCaller,
                            onClick = { if (!wantCaller || wantRegisterer) wantCaller = !wantCaller },
                            label = { Text("Caller") },
                            leadingIcon = if (wantCaller) { { Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(16.dp)) } } else null,
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = wantRegisterer,
                            onClick = { if (!wantRegisterer || wantCaller) wantRegisterer = !wantRegisterer },
                            label = { Text("Registerer") },
                            leadingIcon = if (wantRegisterer) { { Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(16.dp)) } } else null,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            if (wantCaller) Text("• Caller: makes calls, logs outcomes, handles follow-ups", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (wantRegisterer) Text("• Registerer: marks attendance, registers students, manages batches", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                submitError = null
                                isSubmitting = true
                                scope.launch {
                                    val ok = AppDatabase.submitRegisterRequest(
                                        name = name,
                                        username = username,
                                        password = password,
                                        centreId = selectedCentre?.id ?: "",
                                        role = "CALLER",
                                        aeAccess = wantRegisterer
                                    )
                                    isSubmitting = false
                                    if (ok) {
                                        val roleLabel = when {
                                            wantCaller && wantRegisterer -> "Caller + Registerer"
                                            wantRegisterer -> "Registerer"
                                            else -> "Caller"
                                        }
                                        // Also add locally so admin sees it without a refresh
                                        val newUser = User(
                                            id = "u_${System.currentTimeMillis()}",
                                            username = username,
                                            name = name,
                                            role = "CALLER",
                                            status = "pending",
                                            isAeAccessEnabled = wantRegisterer,
                                            centreId = selectedCentre?.id ?: ""
                                        )
                                        AppDatabase.users = AppDatabase.users + newUser
                                        AppDatabase.notifications = listOf(
                                            Notification("n_${System.currentTimeMillis()}", "New Access Proposal", "Access request submitted by $name ($roleLabel) for ${selectedCentre?.name ?: ""}.", "Just now")
                                        ) + AppDatabase.notifications
                                        isSubmitted = true
                                    } else {
                                        submitError = "Submission failed. Check connection and try again."
                                    }
                                }
                            },
                            enabled = name.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty() && selectedCentre != null && (wantCaller || wantRegisterer) && !isSubmitting
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(if (isSubmitting) "Submitting…" else "Submit Request")
                        }
                        submitError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
// PERSON COMPACT CARD
// Dialog-style card that appears when a caller or registerer selects a person.
// Shows last 3 call responses, batch memberships, address, and centre.
// "Edit Batches" triggers the BatchSelectDialog callback.
// =============================================================================
@Composable
fun PersonCompactCard(
    registration: Registration,
    onDismiss: () -> Unit,
    onEditBatches: () -> Unit
) {
    val callAttempts = AppDatabase.callAttempts
        .filter { it.registrationId == registration.id }
        .reversed()
        .take(3)
    val batchNames = registration.batchIds.mapNotNull { id ->
        AppDatabase.batches.find { it.id == id }
    }
    val centreName = AppDatabase.centres.find { it.id == registration.centreId }?.name
        ?: registration.centreId

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 3.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // ── Header ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            registration.name,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            registration.mobile,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(10.dp))

                // ── Profile quick-facts ──
                listOf(
                    Icons.Default.Home to registration.address,
                    Icons.Default.Work to registration.occupation,
                    Icons.Default.LocationOn to centreName
                ).filter { it.second.isNotBlank() }.forEach { (icon, value) ->
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(value, style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ── Batch memberships ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Batches",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(
                        onClick = onEditBatches,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Edit", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (batchNames.isEmpty()) {
                    Text(
                        "No batch assigned",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        batchNames.forEach { batch ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Group,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(batch.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                if (batch.isDefault) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    StatusBadge(text = "Default", status = "active")
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ── Last 3 call responses ──
                Text(
                    if (callAttempts.isEmpty()) "No Call History" else "Last ${callAttempts.size} Responses",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (callAttempts.isEmpty()) {
                    Text(
                        "No calls logged yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        callAttempts.forEach { ca ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            ca.outcome,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (ca.notes.isNotBlank()) {
                                            Text(
                                                ca.notes,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Text(
                                        ca.dateStr,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

// =============================================================================
// SHARED BATCH MEMBERSHIP DIALOG
// Multi-select checkbox list of batches for a registration.
// Respects R3 rule (default/non-default mutually exclusive).
// Saves to SRKB backend via setRegistrationBatches.
// =============================================================================
@Composable
fun SharedBatchMembershipDialog(student: Registration, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var selectedIds by remember { mutableStateOf(student.batchIds) }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val availableBatches = AppDatabase.batches.filter {
        it.centreId == AppDatabase.selectedFlaskCentreId && it.category == AppDatabase.selectedCategory
    }
    val hasDefault = selectedIds.any { id -> availableBatches.find { it.id == id }?.isDefault == true }
    val hasNonDefault = selectedIds.any { id -> availableBatches.find { it.id == id }?.isDefault == false }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    "Edit Batch Memberships",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    student.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))

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
                    Text(
                        "R3: Default and non-default batches are mutually exclusive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                availableBatches.forEach { b ->
                    val isSelected = selectedIds.contains(b.id)
                    val wouldConflict = !isSelected && ((b.isDefault && hasNonDefault) || (!b.isDefault && hasDefault))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !wouldConflict && !isSaving) {
                                selectedIds = if (isSelected) {
                                    selectedIds - b.id
                                } else if (b.isDefault) {
                                    (selectedIds.filter { id -> availableBatches.find { it.id == id }?.isDefault == true }) + b.id
                                } else {
                                    (selectedIds.filter { id -> availableBatches.find { it.id == id }?.isDefault == false }) + b.id
                                }
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = isSelected, onCheckedChange = null, enabled = !wouldConflict && !isSaving)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                b.name,
                                color = if (wouldConflict) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                        else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (b.isDefault) FontWeight.Bold else FontWeight.Normal
                            )
                            if (b.isDefault) Text("Default (R3 exclusive)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            if (wouldConflict) Text("Conflicts with selection", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                        }
                        Text("${b.memberCount}m", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }

                saveError?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            saveError = null
                            isSaving = true
                            scope.launch {
                                val ok = AppDatabase.setRegistrationBatches(student.id, selectedIds)
                                isSaving = false
                                if (ok) onDismiss()
                                else saveError = "Failed to save — check connection"
                            }
                        },
                        enabled = !isSaving
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text("Save")
                    }
                }
            }
        }
    }
}
