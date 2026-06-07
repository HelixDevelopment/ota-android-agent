// Helix OTA — ota-android-agent root build.
// Two modules:
//   :core    — PURE Kotlin/JVM library (framework-independent logic + DTOs), fully unit-tested.
//   :android — Android library (com.android.library) holding ONLY the Android-framework wiring;
//              depends on :core. Best-effort under Gradle 9.5 (AGP may not resolve here).
//
// Plugins are declared `apply false` at the root so each module applies what it needs.
// No wrapper is generated — the system Gradle (9.5) is used.

plugins {
    kotlin("jvm") version "2.2.0" apply false
}
