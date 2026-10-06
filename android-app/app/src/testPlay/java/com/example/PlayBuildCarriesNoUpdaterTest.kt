package com.example

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Google Play's Device and Network Abuse policy forbids an app distributed through Play from
 * updating itself by any route other than Play, and Play scans the MERGED manifest — so
 * REQUEST_INSTALL_PACKAGES alone fails review even with no code path reaching it. The app's other
 * distribution channel (sideload, com.example) genuinely needs that permission and a FileProvider,
 * and they live in app/src/sideload/ so they cannot merge into the bundle.
 *
 * "Cannot merge" is a claim about a build system, and this test is what makes it a checked one.
 * The failure it guards against is a rejected upload discovered days later in Play Console, or
 * worse, a policy strike on a live listing. build-play-bundle.ps1 greps the play merged manifest
 * for the same two things; this catches it earlier, on any run of testPlayDebugUnitTest.
 *
 * If this fails, someone has moved the permission or the provider into app/src/main/. Move it
 * back into app/src/sideload/ — do not relax the assertion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayBuildCarriesNoUpdaterTest {

    @Test
    fun `the play build never asks to install packages`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val perms = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toList() ?: emptyList()

        assertFalse(
            "REQUEST_INSTALL_PACKAGES is in the PLAY merged manifest. Play will reject this " +
                "bundle under Device and Network Abuse. Declared perms: $perms",
            perms.contains("android.permission.REQUEST_INSTALL_PACKAGES")
        )
    }

    @Test
    fun `the play build declares no update FileProvider`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager
            .resolveContentProvider("${context.packageName}.fileprovider", 0)

        assertNull(
            "The updater's FileProvider is in the PLAY merged manifest; it belongs to the " +
                "sideload flavour only. Found: ${info?.name}",
            info
        )
    }
}
