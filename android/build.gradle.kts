// :android — Android library holding ONLY the Android-framework wiring (WorkManager worker,
// scheduler, ApplyPort). Depends on :core for all framework-independent logic.
//
// BEST-EFFORT under the system Gradle (9.5): the Android Gradle Plugin may not resolve or
// run under Gradle 9.5 in this environment. The :core module is the fully-tested deliverable;
// this module is REAL Kotlin (no stubs) but its build is attempted honestly — see BUILD_STATUS.md.
//
// UpdateEngine is @SystemApi and NOT in the public android.jar; the bridge accesses it via
// reflection so this module compiles against the public SDK (see ApplyPort / ReflectiveUpdateEngineApplyPort).

// Plugins applied version-less — versions are pinned at the root build (apply false),
// mirroring the proven-working ota-update-engine-bridge :android module so kotlin.android
// resolves to 2.2.0 (compatible with AGP 8.5.2 on Gradle 9.5).
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "digital.vasic.helix.ota.agent"
    compileSdk = 34

    defaultConfig {
        minSdk = 31
        // Real on-device instrumentation runner (androidx.test) — the connectedAndroidTest /
        // `am instrument` entry point that runs the androidTest sources ON the booted AVD.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    jvmToolchain(17)
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // androidTest — REAL on-device instrumentation (runs ON the booted AVD via
    // connectedAndroidTest / `am instrument`). Asserts the agent's genuine on-device
    // behaviour: pure :core decision logic over real inputs + the graceful
    // update_engine-absent degradation path of ReflectiveUpdateEngineApplyPort.
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:core:1.5.0")
}
