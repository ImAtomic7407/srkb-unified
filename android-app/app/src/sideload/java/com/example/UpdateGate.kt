package com.example

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.data.AppDatabase
import com.example.data.AppUpdateManager
import kotlinx.coroutines.launch
import java.io.File

/**
 * SIDELOAD CHANNEL update gate — downloads and installs the new build in place, without a browser.
 *
 * This build is not distributed through Play, so Play's self-update policy does not apply to it
 * and an in-app downloader is legitimate here. It is also the ONLY route: there is no store to
 * defer to. So unlike the play flavour, this one reacts to `updateAvailable` as well as
 * `updateRequired`, and "Update Now" fetches the APK itself and hands it to Android's package
 * installer. The permission and FileProvider it needs live in app/src/sideload/AndroidManifest.xml
 * and never merge into the Play bundle.
 *
 * **Browser Download stays on the dialog on purpose.** The in-app path has never run on a real
 * handset — it was written 2026-08-17, reviewed, two real defects fixed (it prefixed the wrong
 * backend, and its progress bar froze on a chunked response), then removed for the Play release
 * before it ever shipped. The first build carrying it is the first one anybody can exercise it on.
 * Until it has been used in the field, every failure inside it must still leave the user with the
 * flow that is proven to work, so: an install-permission refusal sends them to Settings and says
 * why, a failed download says so and points at the browser button, and the browser button is
 * present the whole time. It must never dead-end.
 *
 * The in-place update only works while BOTH of these hold, and neither is checked by the build:
 *   * applicationId stays `com.example`  (set on the sideload flavour in build.gradle.kts)
 *   * the APK stays signed with the checked-in `debug.keystore`  (CN=Android Debug,
 *     SHA-256 d02c3e63…, verified against the shipped v31 APK on 2026-08-18)
 * Change the id and the user gets a second icon; change the key and the install is refused
 * outright. publish_app.py asserts the applicationId; the key comes from the debug build type.
 *
 * Renders nothing until the server has actually answered — a device that is offline, or talking
 * to a server predating this endpoint, must never be gated.
 */
@Composable
fun UpdateGate() {
    val status = AppDatabase.updateStatus ?: return
    if (!status.updateAvailable && !status.updateRequired) return

    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (dismissed && !status.updateRequired) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mandatory = status.updateRequired

    // Both are already absolute — AppData prefixes backendUrl onto the server's `apk_url` and
    // `download_page` when it builds AppUpdateStatus (AppData.kt:652). apkUrl is blank only when
    // the server has no APK on disk, in which case the download page is the honest target.
    val apkTarget = status.apkUrl.ifBlank {
        status.downloadPageUrl.ifBlank { AppDatabase.backendUrl.trimEnd('/') + "/app" }
    }

    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadedBytesText by remember { mutableStateOf("") }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var cancelRequested by remember { mutableStateOf(false) }
    // Whether the server told us the total size. A chunked or gzipped response (which the
    // Cloudflare tunnel can produce) reports contentLength = -1, and a determinate bar would then
    // sit frozen at empty for the whole ~20MB while the byte counter climbed — indistinguishable
    // from a hung download. Drives an indeterminate bar in that case.
    var totalKnown by remember { mutableStateOf(true) }

    fun openBrowserFallback() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(apkTarget)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure {
            Toast.makeText(
                context,
                "Couldn't open the browser. Go to $apkTarget on this phone to download the update.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun startInAppDownload() {
        // Checked BEFORE the download, not after: sending the user to Settings with a finished
        // 20MB file sitting in the cache and no way to open it is the worst version of this.
        // Returning from Settings and tapping Update Now again re-runs this check and proceeds.
        if (!AppUpdateManager.canInstallPackages(context)) {
            Toast.makeText(
                context,
                "Allow \"Install unknown apps\" for this app, then tap Update Now again.",
                Toast.LENGTH_LONG
            ).show()
            AppUpdateManager.openInstallPermissionSettings(context)
            return
        }

        isDownloading = true
        downloadProgress = 0f
        downloadedBytesText = "Starting download…"
        downloadError = null
        cancelRequested = false

        scope.launch {
            val file = AppUpdateManager.downloadApk(
                context = context,
                rawUrl = apkTarget,
                onProgress = { downloaded, total ->
                    totalKnown = total > 0
                    if (total > 0) {
                        downloadProgress = downloaded.toFloat() / total.toFloat()
                        val dlMb = String.format("%.1f", downloaded / (1024f * 1024f))
                        val totMb = String.format("%.1f", total / (1024f * 1024f))
                        downloadedBytesText = "$dlMb MB / $totMb MB"
                    } else {
                        val dlMb = String.format("%.1f", downloaded / (1024f * 1024f))
                        downloadedBytesText = "$dlMb MB downloaded"
                    }
                },
                onCancelCheck = { cancelRequested }
            )

            isDownloading = false
            if (file != null) {
                downloadedFile = file
                val ok = AppUpdateManager.triggerInstall(context, file)
                if (!ok) {
                    downloadError = "Couldn't open the installer. Use Browser Download instead."
                }
            } else if (!cancelRequested) {
                downloadError = "Download failed. Check your connection, or use Browser Download."
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!mandatory && !isDownloading) dismissed = true },
        properties = DialogProperties(
            dismissOnBackPress = !mandatory && !isDownloading,
            dismissOnClickOutside = !mandatory && !isDownloading,
        ),
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
        title = { Text(if (mandatory) "Update required" else "Update available") },
        text = {
            Column {
                Text(
                    if (mandatory)
                        "This version is no longer supported. Update to continue using the app."
                    else
                        "A newer version of the app is ready.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "You have build ${status.currentVersionCode} · latest is " +
                        "${status.latestVersionName.ifBlank { "—" }} (build ${status.latestVersionCode})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (status.notes.isNotEmpty() && !isDownloading) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "What's new",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    status.notes.take(6).forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (isDownloading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Downloading update…",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (totalKnown) {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        downloadedBytesText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (downloadedFile != null && !isDownloading) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Downloaded. Tap Install Now and confirm on the Android screen that follows.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (downloadError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        downloadError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (!isDownloading && downloadedFile == null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Downloads inside the app — no browser needed. It updates the app in " +
                            "place, so your login and anything saved offline are kept.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (isDownloading) {
                OutlinedButton(onClick = { cancelRequested = true }) { Text("Cancel Download") }
            } else if (downloadedFile != null) {
                Button(onClick = { AppUpdateManager.triggerInstall(context, downloadedFile!!) }) {
                    Text("Install Now")
                }
            } else {
                Button(onClick = { startInAppDownload() }) { Text("Update Now") }
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isDownloading) {
                    TextButton(onClick = { openBrowserFallback() }) {
                        Text("Browser Download", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (!mandatory && !isDownloading) {
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = { dismissed = true }) { Text("Later") }
                }
            }
        }
    )
}
