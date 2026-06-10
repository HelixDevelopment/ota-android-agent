# QA evidence — `ota-android-agent` `:android` AAR build

**Revision:** 1
**Last modified:** 2026-06-10T11:36:08Z

## Summary

Produced the `:android` AAR (NEXT item #2) on the host toolchain
(Gradle 9.5.0 + Kotlin 2.3.20 toolchain / Kotlin-Gradle-plugin 2.2.0 + AGP 8.5.2,
Android SDK `compileSdk 34`, JDK 17). Both targets pass:

- `gradle :android:assembleRelease` → **BUILD SUCCESSFUL**, real AAR produced.
- `gradle :core:test --rerun-tasks` → **BUILD SUCCESSFUL**, 47 tests PASSED, 0 FAILED
  (the working `:core` deliverable is NOT broken by the change).

## Root cause & fix (anti-bluff §11.4.6 — proven, not guessed)

The prior failure was attributed to an "AGP-on-Gradle-9.5 incompatibility" but the
captured baseline error (`00_baseline_assembleRelease.log`) showed the true cause:

```
Failed to apply plugin 'org.jetbrains.kotlin.android'.
  > Could not create an instance of type ...KotlinAndroidTarget.
     > com/android/build/gradle/api/BaseVariant
```

The `:android` module applied `kotlin("android")` **without a pinned version** while the
root only pinned `kotlin("jvm") 2.2.0`. The unpinned Kotlin-Android plugin resolved a Kotlin
Gradle plugin that references the AGP-removed `BaseVariant` API → crash at plugin-apply time.

**Fix** — mirror the proven-working `ota-update-engine-bridge` `:android` config:
pin all three plugins at the root with `apply false`
(`org.jetbrains.kotlin.jvm` 2.2.0, `org.jetbrains.kotlin.android` 2.2.0,
`com.android.library` 8.5.2) and apply them version-less per module. This is a host/version
alignment issue (NOT structurally impossible per §11.4.112) — AGP 8.5.2 + Kotlin 2.2.0 builds
cleanly on Gradle 9.5.0 once the plugin versions are aligned.

Second (legitimate, additive) requirement surfaced after the plugin fix
(`01_assembleRelease.log`): `:android` depends on `androidx.work:work-runtime-ktx`, so
`android.useAndroidX=true` was added to `gradle.properties`. After that → BUILD SUCCESSFUL
(`02_assembleRelease.log`).

## Files changed (in `submodules/ota-android-agent/`)

- `build.gradle.kts` — root now pins all three plugins (`apply false`), mirroring the bridge.
- `android/build.gradle.kts` — `:android` applies `com.android.library` +
  `org.jetbrains.kotlin.android` version-less; added `buildTypes { release {...} }` +
  `kotlin { jvmToolchain(17) }` (matching the bridge); dropped the inline plugin version.
- `gradle.properties` — added `android.useAndroidX=true`.
- `BUILD_STATUS.md` — updated to record the now-successful `:android` AAR build + corrected
  root-cause attribution.

## Artifact

`android/build/outputs/aar/android-release.aar` — 33519 bytes; contains `classes.jar`
(36904 bytes, 35 entries) with the compiled agent classes. See `03_aar_ls.txt`,
`04_aar_contents.txt`, `05_classes_jar.txt`.

## Evidence files

| File | Content |
| --- | --- |
| `00_baseline_assembleRelease.log` | Pre-fix BUILD FAILED — `BaseVariant` plugin-apply crash |
| `01_assembleRelease.log` | After plugin-pin fix — fails on `android.useAndroidX` (real, expected) |
| `02_assembleRelease.log` | After useAndroidX fix — **BUILD SUCCESSFUL** |
| `03_aar_ls.txt` | `ls -la` of the produced AAR (33519 bytes) |
| `04_aar_contents.txt` | `unzip -l` of the AAR — R.txt, AndroidManifest.xml, classes.jar, aar-metadata |
| `05_classes_jar.txt` | `unzip -l` of classes.jar — 35 entries, real compiled agent classes |
| `06_core_test.log` | `:core:test --rerun-tasks` — 47 PASSED, 0 FAILED |
