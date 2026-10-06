package com.example

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * The in-app updater is three pieces that have to agree with each other, and nothing in the
 * compiler checks that they do:
 *
 *   1. AppUpdateManager.downloadApk writes to getExternalFilesDir(DIRECTORY_DOWNLOADS)
 *   2. app/src/sideload/res/xml/file_paths.xml declares which roots the FileProvider will serve
 *   3. AppUpdateManager.triggerInstall asks for the authority "${packageName}.fileprovider",
 *      which app/src/sideload/AndroidManifest.xml has to actually declare
 *
 * Get any one of them wrong and FileProvider.getUriForFile throws "Failed to find configured root
 * that contains …", triggerInstall catches it and returns false, and the user is silently pushed
 * to the browser fallback on every single update — the same shape as the wrong-base-URL defect
 * this feature already shipped once. That failure is invisible until somebody watches a real phone
 * try to update. This test closes the gap on the build machine instead.
 *
 * **Why it re-implements the root lookup instead of just calling FileProvider.getUriForFile.**
 * That was the first version of this test, and it fails on Windows for a reason that has nothing
 * to do with this app: androidx.core 1.18.0's FileProvider.belongsToRoot() is
 * `path.startsWith(rootPath + "/")` with a hard-coded forward slash (verified by disassembling
 * core-1.18.0.aar on 2026-08-18). Robolectric hands out real Windows paths with backslashes, so
 * NO configuration can ever match there and the check would be vacuous — red on this machine and
 * green nowhere. Android itself is POSIX, so the '/' is correct on a device.
 *
 * So the roots are read from the same meta-data XML FileProvider reads, resolved to the same
 * directories FileProvider resolves them to, and matched by File ancestry rather than by string
 * prefix. Everything that can actually be got wrong in this repo — a missing provider, a missing
 * or edited file_paths.xml, a download destination moved outside the declared roots — still fails
 * this test. Only androidx's own separator handling is out of scope, and that is not ours.
 *
 * Lives in testSideload, not test: the play flavour must have none of this and has its own test
 * asserting the opposite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SideloadUpdaterWiringTest {

    private fun authority(c: Context) = "${c.packageName}.fileprovider"

    /** The roots FileProvider will serve, read from the provider's own FILE_PROVIDER_PATHS meta-data. */
    private fun declaredRoots(c: Context): Map<String, File> {
        val info = c.packageManager.resolveContentProvider(authority(c), PackageManager.GET_META_DATA)
        assertNotNull(
            "No content provider declared for authority ${authority(c)} — AppUpdateManager." +
                "triggerInstall() cannot hand the APK to the installer without it. It belongs in " +
                "app/src/sideload/AndroidManifest.xml.",
            info
        )
        val parser = info!!.loadXmlMetaData(c.packageManager, "android.support.FILE_PROVIDER_PATHS")
        assertNotNull("Provider has no android.support.FILE_PROVIDER_PATHS meta-data", parser)

        val roots = LinkedHashMap<String, File>()
        var type = parser!!.next()
        while (type != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG) {
                val name = parser.getAttributeValue(null, "name")
                val path = parser.getAttributeValue(null, "path")
                // Same tag-to-directory mapping as FileProvider.parsePathStrategy.
                val target: File? = when (parser.name) {
                    "root-path" -> File("/")
                    "files-path" -> c.filesDir
                    "cache-path" -> c.cacheDir
                    "external-path" -> Environment.getExternalStorageDirectory()
                    "external-files-path" -> c.getExternalFilesDirs(null).firstOrNull()
                    "external-cache-path" -> c.externalCacheDirs.firstOrNull()
                    "external-media-path" -> c.externalMediaDirs.firstOrNull()
                    else -> null
                }
                if (target != null && !name.isNullOrEmpty()) {
                    roots[name] = (if (path.isNullOrEmpty()) target else File(target, path)).canonicalFile
                }
            }
            type = parser.next()
        }
        return roots
    }

    @Test
    fun `the downloaded apk sits inside a root the FileProvider will serve`() {
        val c = ApplicationProvider.getApplicationContext<Context>()

        // Byte-for-byte the destination AppUpdateManager.downloadApk uses.
        val destDir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: c.cacheDir
        destDir.mkdirs()
        val apkFile = File(destDir, "attendease_update.apk").canonicalFile

        val roots = declaredRoots(c)
        assertTrue("file_paths.xml declared no usable roots at all", roots.isNotEmpty())

        val match = roots.entries.firstOrNull { (_, root) ->
            generateSequence(apkFile.parentFile) { it.parentFile }.any { it == root }
        }

        assertTrue(
            "The update APK is written to $apkFile, which is not inside any root the " +
                "FileProvider serves. triggerInstall() would throw and the user would be pushed " +
                "to the browser on every update. Declared roots: $roots",
            match != null
        )
    }

    @Test
    fun `the sideload build asks for permission to install packages`() {
        val c = ApplicationProvider.getApplicationContext<Context>()
        val perms = c.packageManager
            .getPackageInfo(c.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toList() ?: emptyList()

        assertTrue(
            "REQUEST_INSTALL_PACKAGES missing from the sideload manifest — the in-app updater " +
                "cannot launch the installer without it. Declared perms: $perms",
            perms.contains("android.permission.REQUEST_INSTALL_PACKAGES")
        )
    }
}
