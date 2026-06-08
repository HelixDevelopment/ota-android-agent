# DeltaApplyDecision — :core test evidence

Device-side delta-apply DECISION logic — pure Kotlin, JVM-unit-testable (no Android,
no device, no hardware). Decides whether to fetch+apply a base→target delta artifact
or fall back to the full payload. Modeled as a pure transition like `VerifyBeforeApply`.

## Files added (`:core`)

- `core/src/main/kotlin/digital/vasic/helix/ota/core/delta/DeltaApplyDecision.kt`
  - `DeltaOffer` — device-side view of the optional delta sidecar (base version +
    base SHA-256 + delta url + delta size). Marked as the device-side projection,
    NOT a wire DTO.
  - `ApplyChoice` enum — `USE_DELTA` | `FULL_PAYLOAD`.
  - `DeltaReason` enum — `BASE_MATCHES` | `NO_DELTA_OFFERED` | `BASE_MISMATCH` |
    `DELTA_MALFORMED`.
  - `DeltaDecision(choice, reason)`.
  - `DeltaApplyDecision.decide(currentVersion, currentSha256, offer)` — pure function.
    USE_DELTA only when the offer's base (version + SHA-256) exactly matches the
    device's current state AND the offer is well-formed; otherwise FULL_PAYLOAD.
- `core/src/test/kotlin/digital/vasic/helix/ota/core/delta/DeltaApplyDecisionTest.kt`
  — 11 JVM unit tests (kotlin.test), matching the existing `VerifyBeforeApplyTest` style.

## Decision logic (ordering is load-bearing)

1. No offer            → FULL_PAYLOAD (NO_DELTA_OFFERED)
2. Malformed offer OR malformed device state → FULL_PAYLOAD (DELTA_MALFORMED)
   (blank base version / base SHA-256 / url, deltaSize ≤ 0, blank current version/SHA)
3. Base version OR base SHA-256 ≠ device's current state → FULL_PAYLOAD (BASE_MISMATCH)
4. Else                → USE_DELTA (BASE_MATCHES)

SHA-256 compare: trimmed + case-insensitive. Version compare: trimmed + exact.

## Cases covered

- base matches (version + hash) → USE_DELTA
- base version mismatch → FULL_PAYLOAD
- base hash mismatch → FULL_PAYLOAD
- no delta offered → FULL_PAYLOAD
- malformed delta: blank url / non-positive size / blank base fields → FULL_PAYLOAD
- blank device state → FULL_PAYLOAD (never risk an unverifiable base)
- base-hash compare case-insensitive + trimmed → USE_DELTA
- ordering: malformed precedes mismatch
- mutation-immunity: base-match gate is load-bearing

## REAL Gradle run (no bluff — actually executed)

Tooling: system Gradle 9.5.0 (no wrapper), `jvmToolchain(17)`.

Command (from submodule root `submodules/ota-android-agent/`):

    gradle :core:test --console=plain

Result: **BUILD SUCCESSFUL**. All 11 `DeltaApplyDecisionTest` cases PASSED; whole
`:core` suite = **47 tests, 0 failures** (verified count from
`core/build/reports/tests/test/index.html`).

Captured `DeltaApplyDecisionTest` output (re-run with `--rerun-tasks`):

    DeltaApplyDecisionTest > fullPayload_whenDeltaMalformed_nonPositiveSize() PASSED
    DeltaApplyDecisionTest > fullPayload_whenDeviceStateBlank() PASSED
    DeltaApplyDecisionTest > mutationImmunity_baseMatchGateIsLoadBearing() PASSED
    DeltaApplyDecisionTest > baseHashComparison_isCaseInsensitiveAndTrimmed() PASSED
    DeltaApplyDecisionTest > useDelta_whenBaseVersionAndHashMatch() PASSED
    DeltaApplyDecisionTest > fullPayload_whenDeltaMalformed_blankUrl() PASSED
    DeltaApplyDecisionTest > fullPayload_whenBaseVersionMismatch() PASSED
    DeltaApplyDecisionTest > fullPayload_whenDeltaMalformed_blankBaseFields() PASSED
    DeltaApplyDecisionTest > fullPayload_whenBaseHashMismatch() PASSED
    DeltaApplyDecisionTest > ordering_malformedTakesPrecedenceOverMismatch() PASSED
    DeltaApplyDecisionTest > fullPayload_whenNoDeltaOffered() PASSED
    BUILD SUCCESSFUL
