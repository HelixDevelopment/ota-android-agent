// :android — Android library holding ONLY the Android-framework wiring (WorkManager worker,
// scheduler, ApplyPort). Depends on :core for all framework-independent logic.
//
// BEST-EFFORT under the system Gradle (9.5): the Android Gradle Plugin may not resolve or
// run under Gradle 9.5 in this environment. The :core module is the fully-tested deliverable;
// this module is REAL Kotlin (no stubs) but its build is attempted honestly — see BUILD_STATUS.md.
//
// UpdateEngine is @SystemApi and NOT in the public android.jar; the bridge accesses it via
// reflection so this module compiles against the public SDK (see ApplyPort / ReflectiveUpdateEngineApplyPort).

plugins {
    id("com.android.library") version "8.5.2"
    // Kotlin version inherited from the root classpath (kotlin("jvm") 2.2.0 apply false)
    // to avoid an "already on the classpath" plugin-version conflict under Gradle 9.5.
    kotlin("android")
}

android {
    namespace = "digital.vasic.helix.ota.agent"
    compileSdk = 34

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
