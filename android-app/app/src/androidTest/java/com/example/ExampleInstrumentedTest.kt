package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
  @Test
  fun useAppContext() {
    // Context of the app under test.
    val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    // BuildConfig, not a literal: this file is shared by both flavours and the two have
    // DIFFERENT applicationIds on purpose (com.example for sideload, in.bestsolarwala.srkb
    // for Play). A hardcoded id here fails on whichever flavour it does not name.
    assertEquals(BuildConfig.APPLICATION_ID, appContext.packageName)
  }
}
