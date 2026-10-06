plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    // applicationId is NOT set here — it is set per FLAVOUR below, because the two
    // distribution channels are two different Android packages. See productFlavors.
    minSdk = 24
    targetSdk = 36
    // DO NOT PUBLISH BY HAND — run `python publish_app.py --notes "..."` from the repo root.
    //
    // versionCode is what /api/app/version compares against Server file/app_release.json to
    // decide whether a device is out of date. BOTH have to move together. On 2026-08-13 a build
    // shipped with real fixes and this line left at 19, so the server answered
    // `update_available: false` and no device was ever offered it — no error, no log entry, the
    // fixes simply never arrived. This comment used to say "BUMP versionCode ON EVERY PUBLISHED
    // BUILD" and that was not enough, because a comment only works while someone is reading it.
    // publish_app.py bumps both numbers together and refuses to finish unless the running server
    // actually offers the update to the previous build.
    // ONE versionCode, BOTH channels. /api/app/version compares this number against
    // Server file/app_release.json, and that file is the SIDELOAD channel's manifest — so the
    // number here is driven by publish_app.py, which owns the sideload release.
    //
    // Consequence to keep in mind once the Play listing goes live: publishing a sideload build
    // also raises the number a Play device compares itself against. That is why the `play`
    // flavour's UpdateGate reacts ONLY to `updateRequired` (the force-upgrade lever) and ignores
    // `updateAvailable` — otherwise a Play tester would be nagged about a build that only exists
    // on the sideload channel, with an "Open Play Store" button that could not deliver it.
    versionCode = 51
    versionName = "2.40"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // ───────────────────────────── TWO CHANNELS, TWO PACKAGES ─────────────────────────────
  //
  // These exist because the app is distributed two ways at once and the two CANNOT share an
  // applicationId:
  //
  //   sideload — com.example. This is what is ALREADY INSTALLED on every phone in the field
  //              (verified 2026-08-18 against apk/SRKB-Unified-v31-centre-scope-20260817.apk:
  //              package com.example, versionCode 31, signed CN=Android Debug
  //              SHA-256 d02c3e63…). Android keys an installed app by applicationId AND
  //              signing certificate, so BOTH must match or the update is refused. The
  //              sideload channel therefore stays on com.example and on the checked-in
  //              debug.keystore — permanently, for as long as anyone is running a sideloaded
  //              copy. Changing either one turns an in-place update into a second icon
  //              (different id) or a failed install (different key).
  //
  //   play     — in.bestsolarwala.srkb. Google Play REJECTS any applicationId starting with
  //              com.example; it is a reserved sample prefix. Permanent once uploaded: the id
  //              is baked into the listing URL.
  //
  // This used to be one line in defaultConfig that had to be edited by hand between builds.
  // That is the same shape as the 2026-08-13 miss — a step someone has to remember — and the
  // failure is worse here: a sideload APK carrying the Play id installs a SECOND copy of the
  // app beside the real one on every phone. publish_app.py now reads the applicationId back
  // out of output-metadata.json and refuses to publish anything that is not com.example.
  flavorDimensions += "channel"
  productFlavors {
    create("sideload") {
      dimension = "channel"
      applicationId = "com.example"
    }
    create("play") {
      dimension = "channel"
      applicationId = "in.bestsolarwala.srkb"
    }
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.work.runtime.ktx)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.okhttp.sse)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
  compilerOptions {
    freeCompilerArgs.addAll("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
  }
}

