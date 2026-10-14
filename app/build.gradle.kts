import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Stable product version, with strictly validated CI run-number overrides.
val VERSION_BASE = 300000
val CI_BUILD_NUMBER = System.getenv("CI_BUILD_NUMBER")?.let { raw ->
    raw.toIntOrNull()?.takeIf { it >= 0 && it <= 2_100_000_000 - VERSION_BASE }
        ?: throw GradleException("CI_BUILD_NUMBER must be a non-negative integer within Android's versionCode range")
} ?: 0

val lifeOsSigning = listOf("LIFEOS_KEYSTORE_PATH", "LIFEOS_KEYSTORE_PASSWORD", "LIFEOS_KEY_ALIAS", "LIFEOS_KEY_PASSWORD")
    .map { System.getenv(it)?.takeIf(String::isNotBlank) }
val hasLifeOsSigning = lifeOsSigning.all { it != null }

android {
    namespace = "com.qq.closie"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.qqhome.lifeos"
        minSdk = 26
        targetSdk = 35
        versionCode = VERSION_BASE + CI_BUILD_NUMBER
        versionName = "0.3.0"
    }

    signingConfigs {
        if (hasLifeOsSigning) {
            create("lifeOsRelease") {
                storeFile = File(requireNotNull(lifeOsSigning[0]))
                storePassword = lifeOsSigning[1]
                keyAlias = lifeOsSigning[2]
                keyPassword = lifeOsSigning[3]
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Standard Android debug key; never touches the release update chain.
        }
        release {
            if (hasLifeOsSigning) signingConfig = signingConfigs.getByName("lifeOsRelease")
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

    // Schema fixtures are exposed to Robolectric/debug migration tests through merged assets.
    // The release APK does not package them; exported app/schemas files remain in Git.
    sourceSets { getByName("debug").assets.srcDir("$projectDir/schemas") }

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
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
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
    testImplementation("androidx.navigation:navigation-testing:2.8.5")
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
