# Helix OTA — `ota-android-agent` Build Status

| Field | Value |
| --- | --- |
| Module | `ota-android-agent` (1.0.0-MVP) |
| Build tool | system Gradle **9.5.0** (no wrapper generated, per instructions) |
| JDK | toolchain **17** (`/opt/homebrew/Cellar/openjdk@17`) for `:core`; launcher JVM is 25 |
| Date | 2026-06-07 |

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

### `:core` unit tests — PASS

```
gradle --no-daemon --console=plain :core:test
```

`BUILD SUCCESSFUL` — **36 tests, 0 failures** across 4 suites:

| Suite | Tests | Covers |
| --- | --- | --- |
| `VerifyBeforeApplyTest` | 11 | every verify branch (Apply, hash mismatch, signature invalid, malformed digest), decision **ordering**, case-insensitive/trimmed hash compare, mutation-immunity assertion |
| `JitterTest` | 6 | jitter **bounds** over 10k seeded draws, determinism with a seeded RNG, zero-jitter, zero-base, negative-arg guards |
| `PollStateMachineTest` | 10 | full happy path, every terminal branch, and **illegal transitions throw** (apply unreachable without a passed verify) |
| `CodecRoundTripTest` | 9 | DTO **round-trip**, spec-example parse, 6-value vs 7-value enum invariants, JSON escaping |

(Test counts come from `core/build/test-results/test/*.xml`: 11 + 6 + 10 + 9 = 36.)

---

## What does NOT build here (honest result) — `:android`

```
gradle --no-daemon --console=plain :android:assembleRelease
```

**`BUILD FAILED`** — root cause:

```
An exception occurred applying plugin request [id: 'org.jetbrains.kotlin.android']
> Failed to apply plugin 'org.jetbrains.kotlin.android'.
   > Could not create an instance of type ...KotlinAndroidTarget.
      > Could not generate a decorated class for type KotlinAndroidTarget.
         > com/android/build/gradle/api/BaseVariant
```

This is the **expected AGP-on-Gradle-9.5 incompatibility**, not a code defect:

- The Android Gradle Plugin (8.7.3) and the Kotlin-Android plugin reference
  `com.android.build.gradle.api.BaseVariant`, an API surface that does **not** load under the
  system **Gradle 9.5** here. AGP 8.7.x is validated against Gradle **8.x**, and AGP fails fast
  on newer Gradle. There is no AGP release in this environment compatible with Gradle 9.5.
- The failure occurs at **plugin application / configuration time** — before any of the agent's
  Kotlin is compiled — so it reflects the toolchain, not the `:android` source.

Because `:android` plugin configuration fails, `gradle :core:test` is run with
`org.gradle.configureondemand=true` (set in `gradle.properties`) so the `:core` task does **not**
configure the `:android` project. This is why `:core:test` passes cleanly.

### What the `:android` layer needs to build

To compile/assemble `:android`, use the standard Android toolchain (any of):

- **Gradle 8.x** (e.g. 8.9–8.11) matched to **AGP 8.7.x** — the supported pairing; or
- the Android Studio / AOSP-provided Gradle + AGP, with the Android SDK (`compileSdk 34`)
  and `ANDROID_HOME` configured.

The `:android` Kotlin sources are written against **real** public AndroidX WorkManager APIs and
the public Android SDK; no stubs are used.

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
