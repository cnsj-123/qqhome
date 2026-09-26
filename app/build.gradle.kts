import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// ============================================================================
// Version policy (established in v0.2.0 — never regress, never renumber).
//
// versionName is what the user sees:      0.1.0 → 0.2.0 → 0.3.0 → 1.0.0 → …
// versionCode is a monotonic integer for Android package management:
//
//     versionCode = VERSION_BASE + CI_BUILD_NUMBER
//
//   0.1.0 → shipped as versionCode 1 (historical, before this policy)
//   0.2.0 → base 200000   (200000, 200001, …)
//   0.3.0 → base 300000   (300000, 300001, …)      ← current
//   1.0.0 → base 1000000
//   1.1.x → base 1010000
//   1.2.x → base 1020000
//   1.3.x → base 1030000
//
// CI_BUILD_NUMBER comes from GitHub Actions (`github.run_number`, exported by the
// workflow as CI_BUILD_NUMBER), so every downloadable CI APK gets a strictly larger
// versionCode than the previous run and can always overwrite-install over it.
// Locally (no env var) it defaults to 0 — local builds must never be treated as
// upgrades over a CI build, and that is fine: local installs are uninstalled first.
// ============================================================================
val CI_BUILD_NUMBER: Int = System.getenv("CI_BUILD_NUMBER")?.toIntOrNull() ?: 0
val VERSION_BASE = 300000

// ============================================================================
// CI signing.
//
// A fixed keystore makes every CI-built APK overwrite-installable across runs,
// where the default debug keystore would be regenerated each time. The keystore
// itself is NEVER committed: the workflow decodes ANDROID_KEYSTORE_BASE64 into a
// runner-local file and points ANDROID_KEYSTORE_FILE at it; Gradle only reads env
// vars (ANDROID_KEYSTORE_FILE / ANDROID_KEYSTORE_PASSWORD / ANDROID_KEYSTORE_ALIAS /
// ANDROID_KEY_PASSWORD). When the secrets are absent (e.g. PR CI before setup)
// ciKeystoreFile is null and the build falls back to the default debug keystore —
// tests and builds stay green, the resulting APK simply is not guaranteed to
// upgrade across runs.
// ============================================================================
val ciKeystoreFile: File? = System.getenv("ANDROID_KEYSTORE_FILE")
    ?.takeIf { it.isNotBlank() }
    ?.let { File(it) }
    ?.takeIf { it.isFile }

android {
    namespace = "com.qq.closie"
    compileSdk = 35

    defaultConfig {
        // v0.2.0 is the FINAL applicationId change (com.xiaoming.closie → com.qq.closie).
        // There is no user data to migrate and installing v0.2.0 requires uninstalling the
        // old app — documented and accepted. From here on applicationId is frozen forever:
        // changing it again would strand every future user's data on an "unrelated" app.
        applicationId = "com.qq.closie"
        minSdk = 26
        targetSdk = 35
        versionCode = VERSION_BASE + CI_BUILD_NUMBER
        versionName = "0.3.0"
    }

    signingConfigs {
        if (ciKeystoreFile != null) {
            create("ci") {
                storeFile = ciKeystoreFile
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEYSTORE_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Stable signing for CI artifacts when the secrets exist; default debug
            // keystore otherwise, so PR CI never fails on a missing keystore.
            if (ciKeystoreFile != null) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
        release {
            if (ciKeystoreFile != null) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // ------------------------------------------------------------------------------------------
    // Make the exported Room schemas readable by MigrationTestHelper.
    //
    // `room.schemaLocation` (see the ksp block below) writes each version's schema JSON to
    // app/schemas/, which is what makes migrations reproducible and reviewable in a diff. Room's
    // MigrationTestHelper then needs to read the OLD schema back by name to build a v1 database it
    // can migrate — and it reads it from `Instrumentation.getContext().assets`, with no overload
    // that accepts a File.
    //
    // Why this is attached to the debug source set rather than to `test`:
    // Robolectric resolves assets through the merged MAIN assets directory (see
    // app/build/intermediates/unit_test_config_directory/.../test_config.properties, whose
    // `android_merged_assets` points at mergeDebugAssets). AGP has a `generateDebugUnitTestAssets`
    // task but no `mergeDebugUnitTestAssets`, so `sourceSets["test"].assets` is silently unused and
    // every migration test fails with "FileNotFoundException: Missing file: .../1.json" — which
    // reads like a missing file but is really an unmerged source set.
    //
    // Attaching to `debug` is the narrowest scope that works: unit tests and the debug APK are the
    // only variants that see these files, and a few KB of schema JSON inside a debug build is
    // harmless. The release build is untouched.
    // ------------------------------------------------------------------------------------------
    sourceSets {
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }

    // Every artifact carries its version so a downloaded APK can be identified on sight,
    // and two CI runs never collide under the same generic app-debug.apk name.
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "LifeOS-v${versionName}-build${versionCode}.apk"
        }
    }
}

// Project-level KSP configuration (not inside android { defaultConfig { } }).
// Room exports its schema to app/schemas/ so migrations stay reproducible.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // Room — Life OS foundation database. Explicit migrations only (no destructive fallback).
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test:runner:1.6.2")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("org.robolectric:robolectric:4.12.2")

    // Compose UI smoke tests run on Robolectric (real composition, no device needed).
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
