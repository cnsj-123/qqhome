plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false
    // KSP 1.9.25-1.0.20 is the matching release for Kotlin 1.9.25.
    id("com.google.devtools.ksp") version "1.9.25-1.0.20" apply false
}
