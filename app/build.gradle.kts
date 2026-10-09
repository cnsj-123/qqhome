plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Installation identity is stable; namespace remains the existing Kotlin implementation.
// Only an unset version code uses the development fallback; invalid overrides fail.
val lifeOsVersionCode = providers.environmentVariable("LIFEOS_VERSION_CODE").orNull?.let { supplied ->
    requireNotNull(supplied.trim().toIntOrNull()?.takeIf { it > 0 }) {
        "LIFEOS_VERSION_CODE must be a positive Int (1..2147483647)"
    }
} ?: 1
val lifeOsVersionName = providers.environmentVariable("LIFEOS_VERSION_NAME").orNull
    ?.trim()?.takeIf { it.isNotEmpty() } ?: "0.1.0"

val lifeOsKeystorePath = providers.environmentVariable("LIFEOS_KEYSTORE_PATH").orNull?.takeIf { it.isNotBlank() }
val lifeOsKeystorePassword = providers.environmentVariable("LIFEOS_KEYSTORE_PASSWORD").orNull?.takeIf { it.isNotBlank() }
val lifeOsKeyAlias = providers.environmentVariable("LIFEOS_KEY_ALIAS").orNull?.takeIf { it.isNotBlank() }
val lifeOsKeyPassword = providers.environmentVariable("LIFEOS_KEY_PASSWORD").orNull?.takeIf { it.isNotBlank() }
val lifeOsSigningConfigured = listOf(lifeOsKeystorePath, lifeOsKeystorePassword, lifeOsKeyAlias, lifeOsKeyPassword)
    .all { it != null }

android {
    namespace = "com.xiaoming.closie"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.qqhome.lifeos"
        minSdk = 26
        targetSdk = 35
        versionCode = lifeOsVersionCode
        versionName = lifeOsVersionName
    }

    signingConfigs {
        if (lifeOsSigningConfigured) {
            create("lifeOsRelease") {
                storeFile = file(requireNotNull(lifeOsKeystorePath))
                storePassword = lifeOsKeystorePassword
                keyAlias = lifeOsKeyAlias
                keyPassword = lifeOsKeyPassword
            }
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            if (lifeOsSigningConfigured) signingConfig = signingConfigs.getByName("lifeOsRelease")
            // No debug-key or generated-key fallback for release builds.
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
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
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
