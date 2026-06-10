# Helix OTA — `ota-android-agent` Build Status

| Field | Value |
| --- | --- |
| Module | `ota-android-agent` (1.0.0-MVP) |
| Build tool | system Gradle **9.5.0** (no wrapper generated, per instructions) |
| JDK | toolchain **17** (`/opt/homebrew/Cellar/openjdk@17`) for `:core`; launcher JVM is 25 |
| Date | 2026-06-10 |

This document records **exactly** what builds and tests pass in this environment versus what
requires the full AOSP / system-API toolchain. No result here is faked (anti-bluff §7.1).

---

## Module layout

```
ota-android-agent/
├── settings.gradle.kts        # rootProject + include :core, :android + pluginManagement repos
├── build.gradle.kts           # root: kotlin("jvm") 2.2.0 apply false
├── gradle.properties          # JDK-17 toolchain path + configure-on-demand
├── core/                      # PURE Kotlin/JVM library (NO Android plugin) — fully tested
└── android/                   # com.android.library — Android-framework wiring only; depends on :core
```

- **`:core`** — `org.jetbrains.kotlin.jvm` 2.2.0, **no Android plugin**. Holds all
  framework-independent logic and DTOs:
  - Protocol DTOs mirroring the OTA REST contract: `UpdateCheckRequest`, `UpdateAvailable`,
    `TelemetryReport` (+ `PayloadProperties`, `TelemetryEventRecord`, `DeviceHealth`, `TelemetryAck`).
  - Enums: the **6-value** `TelemetryEvent` (`TelemetryEventType` schema, no `idle`) and the
    distinct **7-value** `UpdateState` (`UpdateState` schema, includes `idle`) — per `api/endpoints.md`.
  - Dependency-free manual JSON (`json/Json.kt`) + DTO codecs (`protocol/Codecs.kt`).
  - `verify/VerifyBeforeApply.kt` — the verify-before-apply **decision** (pure function:
    actual SHA-256 + expected SHA-256 + signature-valid → `Decision.Apply` / `Reject(reason)`).
  - `poll/Jitter.kt` — jitter computation `base + uniform[0, jitterMax)` with an **injectable RNG**.
  - `poll/PollStateMachine.kt` — the `PollOutcome` / poll-cycle state machine.
- **`:android`** — `com.android.library` + `kotlin("android")`, depends on `:core`. Holds
  **only** the Android-framework wiring (REAL Kotlin, no stubs):
  - `poll/OtaPollWorker.kt` — `CoroutineWorker` driving one poll→download→verify→apply cycle
    over the `:core` `PollStateMachine`; maps the terminal state onto `Result.success/retry/failure`.
  - `poll/PollScheduler.kt` — WorkManager `PeriodicWorkRequest` wiring (15 min + jitter), using
    the `:core` `Jitter` function for the (testable) delay value.
  - `apply/ApplyPort.kt` — the **decoupling boundary**: the agent depends on this small interface,
    **not** on the `ota-update-engine-bridge` artifact (§11.4.28 keeps modules decoupled).
  - `apply/ReflectiveUpdateEngineApplyPort.kt` — drives `android.os.UpdateEngine` via **reflection**.

---

## What PASSES here (verified, reproducible)

### `:android` AAR — PASS (2026-06-10)

```
gradle --no-daemon --console=plain :android:assembleRelease
```

`BUILD SUCCESSFUL` — produces `android/build/outputs/aar/android-release.aar` (≈33 KB),
containing a real `classes.jar` (35 entries) with the compiled agent classes
(`OtaPollWorker`, `PollScheduler`, `ApplyPort`, `ReflectiveUpdateEngineApplyPort`, the
`PollResult` / `VerifyResult` / `ApplyResult` value types, `AgentDependencies`, etc.).

**Fix that unblocked it (2026-06-10):** the prior `BUILD FAILED` (`KotlinAndroidTarget` →
`com/android/build/gradle/api/BaseVariant`) was NOT a structural AGP-on-Gradle-9.5
incompatibility — it was caused by `:android` applying `kotlin("android")` **without a
pinned version** while the root only pinned `kotlin("jvm")`. The unpinned Kotlin-Android
plugin resolved a Kotlin Gradle plugin that referenced the AGP-removed `BaseVariant` API.
Pinning all three plugins at the root with `apply false` (`org.jetbrains.kotlin.jvm`,
`org.jetbrains.kotlin.android`, `com.android.library` — all version-aligned: Kotlin 2.2.0 +
AGP 8.5.2) and applying them version-less per module — exactly mirroring the proven-working
`ota-update-engine-bridge` `:android` configuration — makes AGP 8.5.2 + Kotlin 2.2.0 build
cleanly on **Gradle 9.5.0**. The agent additionally required `android.useAndroidX=true` in
`gradle.properties` because `:android` depends on `androidx.work:work-runtime-ktx`.

### `:core` unit tests — PASS

```
gradle --no-daemon --console=plain :core:test
```

`BUILD SUCCESSFUL` — **0 failures** across all suites (full `--rerun-tasks` run on
2026-06-10 reported **47 tests PASSED, 0 FAILED**; the per-suite breakdown below counts the
originally-documented 36 from `core/build/test-results/test/*.xml`):

| Suite | Tests | Covers |
| --- | --- | --- |
| `VerifyBeforeApplyTest` | 11 | every verify branch (Apply, hash mismatch, signature invalid, malformed digest), decision **ordering**, case-insensitive/trimmed hash compare, mutation-immunity assertion |
| `JitterTest` | 6 | jitter **bounds** over 10k seeded draws, determinism with a seeded RNG, zero-jitter, zero-base, negative-arg guards |
| `PollStateMachineTest` | 10 | full happy path, every terminal branch, and **illegal transitions throw** (apply unreachable without a passed verify) |
| `CodecRoundTripTest` | 9 | DTO **round-trip**, spec-example parse, 6-value vs 7-value enum invariants, JSON escaping |

(Test counts come from `core/build/test-results/test/*.xml`: 11 + 6 + 10 + 9 = 36.)

---

## Historical note — the prior `:android` `BUILD FAILED` (now RESOLVED 2026-06-10)

The earlier failure (kept for the forensic record) was:

```
An exception occurred applying plugin request [id: 'org.jetbrains.kotlin.android']
> Failed to apply plugin 'org.jetbrains.kotlin.android'.
   > Could not create an instance of type ...KotlinAndroidTarget.
      > Could not generate a decorated class for type KotlinAndroidTarget.
         > com/android/build/gradle/api/BaseVariant
```

This was first attributed to an "AGP-on-Gradle-9.5 incompatibility". That attribution was
**incorrect** (§11.4.6): the true root cause was the **unpinned** `kotlin("android")` plugin in
`:android` (the root pinned only `kotlin("jvm")`), which resolved a Kotlin Gradle plugin
referencing the AGP-removed `BaseVariant` API. Pinning all three plugins to version-aligned
releases at the root (Kotlin 2.2.0 + AGP 8.5.2) — the proven `ota-update-engine-bridge` pattern —
makes `:android:assembleRelease` build cleanly on Gradle 9.5.0. See the PASS section above.

The `:android` Kotlin sources are written against **real** public AndroidX WorkManager APIs and
the public Android SDK; no stubs are used. `org.gradle.configureondemand=true` is retained in
`gradle.properties` (it remains harmless now that `:android` configures cleanly).

### Why `UpdateEngine` is accessed via reflection

`android.os.UpdateEngine` / `UpdateEngineCallback` are **`@SystemApi`** (integration guide §10) and
are **not present in the public `android.jar`**. A direct compile-time reference would not resolve
against the public SDK. `ReflectiveUpdateEngineApplyPort` therefore loads the class via
`Class.forName("android.os.UpdateEngine")` and invokes
`applyPayload(String, long, long, String[])` reflectively, so `:android` **compiles against the
public SDK** while still invoking the real engine at runtime on a **system-UID / platform-signed**
build. On a non-system build the class is absent and `applyVerified` returns `ApplyResult.Failed`
— it never fabricates success.

### Full system / on-device requirements (out of scope for this host)

Running the agent on a device additionally requires (integration guide §10, companion specs):

- The agent shipped **as a system component**: `android:sharedUserId="android.uid.system"` **and**
  platform-signed, **or** a privileged app under `/system/priv-app/` (platform/OEM-signed).
- **Read/write access to `/data/ota_package/`** for the local `file://` apply path.
- **SELinux** policy allowing the agent to bind the `update_engine` service over Binder/AIDL.
- The AOSP Soong build (`Android.bp` with `platform_apis: true`, `certificate: "platform"`) for
  the RK3588 / Orange Pi 5 Max target — see the spec's `build_integration.md`.

---

## Decoupling note (§11.4.28)

The agent does **not** hard-depend on the `ota-update-engine-bridge` artifact. The only OS-apply
seam is the `ApplyPort` interface in `:android`; a bridge implementation (here, the reflective
`UpdateEngine` port) is injected at runtime. `:core` has **zero** Android or bridge dependencies.
