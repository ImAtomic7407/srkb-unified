package com.example

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.data.AppDatabase

/**
 * PLAY CHANNEL update gate. Blocks the app when the server says this build is retired.
 *
 * ─── Two deliberate differences from the sideload gate. Both matter. ───
 *
 * 1. It reacts ONLY to `updateRequired`, never to `updateAvailable`.
 *
 *    Play owns the "a newer version is available" nudge; it shows it in the Play app and can
 *    auto-update in the background, so duplicating it here would be noise at best. At worst it
 *    is a dead end: `versionCode` is a single field shared by both flavours, and it is driven by
 *    publish_app.py, which publishes the SIDELOAD channel. So the moment a sideload build goes
 *    out, /api/app/version starts telling Play devices a newer build exists — one that is not on
 *    Play and that this dialog's button could never fetch. Ignoring `updateAvailable` closes
 *    that off by construction rather than by remembering to keep two numbers in step.
 *
 *    `updateRequired` is kept because it is the force-upgrade lever — the only way to stop a
 *    stale build writing attendance against a schema it no longer understands. It is raised by
 *    hand (`--force-minimum`), never automatically, so it cannot fire by accident.
 *
 * 2. It cannot download or install anything.
 *
 *    Play's Device and Network Abuse policy forbids an app distributed through Play from
 *    updating or replacing itself by any route other than Play — and that includes handing the
 *    user a browser link to an APK, which is the same self-update by a longer road. The
 *    in-app downloader, the REQUEST_INSTALL_PACKAGES permission and the FileProvider were all
 *    removed on 2026-08-18. Play scans the MERGED MANIFEST, so the permission alone fails review
 *    even with no code path reaching it. Nothing in this file, and nothing in
 *    app/src/main/AndroidManifest.xml, may reintroduce them.
 *
 * Renders nothing until the server has actually answered. That is not a detail: a device that is
 * offline, or talking to a server predating this endpoint, must never be gated — otherwise the
 * update check would lock out exactly the offline users the rest of the app works to support.
 */
@Composable
fun UpdateGate() {
    val status = AppDatabase.updateStatus ?: return
    if (!status.updateRequired) return

    val context = LocalContext.current

    // packageName is read at runtime rather than hardcoded so this survives an applicationId
    // change — which is exactly what the Play move was.
    fun openPlayStore() {
        val pkg = context.packageName
        // market:// opens the Play app directly. The web listing is the fallback for a device
        // with no Play app: a Play-distributed build should always have one, but a tester
        // sideloading this same APK onto an emulator without Play services would otherwise hit
        // an unhandled-intent crash.
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$pkg")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { context.startActivity(market) }
            .recoverCatching { context.startActivity(web) }
            .onFailure {
                Toast.makeText(
                    context,
                    "Couldn't open the Play Store. Please update the app from Play manually.",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    AlertDialog(
        // Mandatory by definition here — this gate only renders when updateRequired is set —
        // so the dialog is not dismissable by back press or by tapping outside.
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
        title = { Text("Update required") },
        text = {
            Column {
                Text(
                    "This version is no longer supported. Please update from the Play Store to " +
                        "continue using the app.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "You have build ${status.currentVersionCode} · latest is " +
                        "${status.latestVersionName.ifBlank { "—" }} (build ${status.latestVersionCode})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Anything you saved offline is still on this device and will sync once " +
                        "you're on the new version.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { Button(onClick = { openPlayStore() }) { Text("Open Play Store") } }
    )
}
