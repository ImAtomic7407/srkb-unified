package com.example.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"

    // Checks if the app is allowed to request package installations on Android 8.0+ (API 26+)
    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    // Opens the system settings page for "Install unknown apps" for this app
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    // Downloads the APK from the given relative or absolute URL with real-time progress reporting.
    // Validates content type / size before offering installation.
    suspend fun downloadApk(
        context: Context,
        rawUrl: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit,
        onCancelCheck: () -> Boolean
    ): File? = withContext(Dispatchers.IO) {
        try {
            val fullUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                rawUrl
            } else {
                // backendUrl (AttendEase), NOT callingBackendUrl.
                //
                // The APK is served by AttendEase's /download/unified-app; the calling backend has
                // no such route. `apk_url` arrives from /api/app/version, which checkForUpdate()
                // fetches with apiGet() — and apiGet builds its URL from `backendUrl`
                // (AppData.kt:1756), while callingApiGet uses `callingBackendUrl`
                // (AppData.kt:1849). So a relative apk_url is relative to backendUrl, which is
                // also what AttendEase's own comment on that field says it means.
                //
                // This was callingBackendUrl, which pointed the download at
                // calling.example.com/download/unified-app -> 404 -> downloadApk returns null
                // and the user silently lands on the browser fallback every single time. The
                // feature looked implemented and could never once have worked.
                val base = AppDatabase.backendUrl.trimEnd('/')
                if (rawUrl.startsWith("/")) "$base$rawUrl" else "$base/$rawUrl"
            }

            Log.i(TAG, "Downloading APK update from: $fullUrl")

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder().url(fullUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Download failed with HTTP ${response.code}")
                return@withContext null
            }

            val body = response.body ?: return@withContext null
            val contentType = body.contentType()?.toString() ?: ""
            val contentLength = body.contentLength()

            // Safety check: ensure response isn't an HTML error page
            if (contentType.contains("text/html", ignoreCase = true)) {
                Log.e(TAG, "Download response is HTML, not an APK: $contentType")
                return@withContext null
            }

            val destDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
            val apkFile = File(destDir, "attendease_update.apk")
            if (apkFile.exists()) {
                apkFile.delete()
            }

            body.byteStream().use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (onCancelCheck()) {
                            Log.i(TAG, "APK Download cancelled by user")
                            output.close()
                            if (apkFile.exists()) apkFile.delete()
                            return@withContext null
                        }
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        withContext(Dispatchers.Main) {
                            onProgress(totalRead, contentLength)
                        }
                    }
                    output.flush()
                }
            }

            // Size validation: plausible APK must be > 1 MB
            if (apkFile.length() < 1_000_000) {
                Log.e(TAG, "Downloaded file too small to be a valid APK: ${apkFile.length()} bytes")
                if (apkFile.exists()) apkFile.delete()
                return@withContext null
            }

            Log.i(TAG, "APK download complete: ${apkFile.absolutePath} (${apkFile.length()} bytes)")
            apkFile
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading APK update: ${e.message}", e)
            null
        }
    }

    // Prompts the Android system package installer to install the downloaded APK file
    fun triggerInstall(context: Context, apkFile: File): Boolean {
        return try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger package installation: ${e.message}", e)
            false
        }
    }
}
