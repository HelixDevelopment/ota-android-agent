// Helix OTA — ota-android-agent root build.
// Two modules:
//   :core    — PURE Kotlin/JVM library (framework-independent logic + DTOs), fully unit-tested.
//   :android — Android library (com.android.library) holding ONLY the Android-framework wiring;
//              depends on :core. Best-effort under Gradle 9.5 (AGP may not resolve here).
//
// Plugins are declared (apply false) at the root and applied per-module so each
// module stays decoupled (HelixConstitution §11.4.28). All three plugin versions are
// pinned here so each module applies them version-less and they resolve consistently:
// kotlin.android MUST be pinned to a version compatible with AGP 8.5.2 on Gradle 9.5
// (an unpinned kotlin("android") otherwise resolves a Kotlin Gradle plugin that
// references the AGP-removed `com.android.build.gradle.api.BaseVariant` and fails).
// This mirrors the proven-working ota-update-engine-bridge :android configuration.
// No wrapper is generated — the system Gradle (9.5) is used.

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    id("com.android.library") version "8.5.2" apply false
}
