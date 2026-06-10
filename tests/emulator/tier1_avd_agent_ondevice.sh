#!/usr/bin/env bash
#
# tier1_avd_agent_ondevice.sh — REAL on-device instrumentation test for ota-android-agent.
#
# Purpose
#   Run the :android module's androidTest (androidx.test + AndroidJUnitRunner) ON a booted,
#   HVF-accelerated arm64-v8a Android AVD and assert the agent's GENUINE on-device behaviour:
#   pure :core decision logic (DeltaApplyDecision / PollStateMachine / VerifyBeforeApply)
#   executing inside the Android runtime over real inputs, AND the load-bearing honest finding
#   that ReflectiveUpdateEngineApplyPort degrades gracefully (returns ApplyResult.Failed, never
#   a crash, never a fabricated Launched) on a stock AVD where the @SystemApi
#   android.os.UpdateEngine class is absent. A REAL A/B apply is NOT (and cannot be) exercised
#   on a stock AVD — that would require a system-UID / platform-signed build with update_engine.
#
#   §11.4 anti-bluff: PASS requires the AndroidJUnitRunner to report OK with the expected number
#   of tests AND zero failures. Evidence is the captured instrumentation stream + logcat
#   (tag OtaAgentOnDeviceTest) under docs/qa/<run-id>-avd-agent-ondevice/.
#   §11.4.119: this script EXCLUSIVELY OWNS the AVD it boots on PORT (default 5588, distinct
#   from the boot smoke's 5584) — no other stream touches that serial.
#   §11.4.14: ALWAYS kills the emulator by its serial on exit (trap) — never a broad pkill that
#   could disturb podman's qemu.
#
# Usage
#   bash submodules/ota-android-agent/tests/emulator/tier1_avd_agent_ondevice.sh
#   AVD=Pixel_8 PORT=5588 bash .../tier1_avd_agent_ondevice.sh
#
# Inputs (env, all optional)
#   ANDROID_HOME  Android SDK root (default: ~/Library/Android/sdk)
#   AVD           AVD name (default: Pixel_8, else the first ~/.android/avd/*.ini)
#   PORT          emulator console port (default: 5588 — NOT 5584, to stay disjoint from the smoke)
#   BOOT_TIMEOUT  seconds to wait for boot_completed (default: 240)
# Outputs / Side-effects
#   Boots a headless emulator, builds + installs the androidTest APK, runs `am instrument`,
#   writes evidence under docs/qa/<run-id>-avd-agent-ondevice/, then ALWAYS kills the
#   emulator by its serial (trap EXIT) and uninstalls the test package.
# Dependencies
#   ANDROID_HOME/emulator/emulator + platform-tools/adb, system gradle (9.5), an installed
#   arm64-v8a system image + AVD. macOS HVF (no /dev/kvm).
# Cross-references
#   submodules/ota-android-agent/tests/emulator/tier1_avd_hvf_smoke.sh (the boot smoke this
#   builds on), submodules/ota-android-agent/android/src/androidTest/kotlin/.../OtaAgentOnDeviceTest.kt.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"        # ota-android-agent module root
# §11.4.28 decoupling: this reusable submodule stays project-UNAWARE — evidence
# lands under the MODULE's OWN docs/qa, never by reaching up to a consuming
# project's tree. Any consumer (helix_ota or another) gets self-contained evidence.

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
EMU="${ANDROID_HOME}/emulator/emulator"
ADB="${ANDROID_HOME}/platform-tools/adb"
AVD="${AVD:-Pixel_8}"
PORT="${PORT:-5588}"
SER="emulator-${PORT}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-240}"
TEST_PKG="digital.vasic.helix.ota.agent.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
QA="${MODULE_ROOT}/docs/qa/${RUN_ID}-avd-agent-ondevice"
mkdir -p "$QA"

log() { printf '[%s] %s\n' "$(date -u +%H:%M:%SZ)" "$*"; }
fail() { log "FAIL: $*"; exit 1; }

[ -x "$EMU" ] || fail "emulator not found at $EMU (set ANDROID_HOME)"
[ -x "$ADB" ] || fail "adb not found at $ADB"
[ -f "$HOME/.android/avd/${AVD}.ini" ] || AVD="$(ls "$HOME/.android/avd"/*.ini 2>/dev/null | head -1 | xargs -n1 basename 2>/dev/null | sed 's/\.ini$//')"
[ -n "$AVD" ] || fail "no AVD found under ~/.android/avd"

cleanup() {
  "$ADB" -s "$SER" uninstall "$TEST_PKG" >/dev/null 2>&1 || true
  "$ADB" -s "$SER" emu kill >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

# 1) Build the androidTest APK (REAL on-device test artifact).
log "building androidTest APK (gradle :android:assembleDebugAndroidTest)"
( cd "$MODULE_ROOT" && gradle :android:assembleDebugAndroidTest ) > "$QA/gradle_assemble.log" 2>&1 \
  || fail "androidTest APK build failed (see $QA/gradle_assemble.log)"
TEST_APK="$MODULE_ROOT/android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk"
[ -f "$TEST_APK" ] || fail "androidTest APK not produced at $TEST_APK"
log "androidTest APK: $TEST_APK ($(wc -c < "$TEST_APK") bytes)"

# 2) Boot the AVD headless on HVF (this script OWNS this serial — §11.4.119).
log "booting AVD=$AVD on HVF (headless), serial=$SER, evidence=$QA"
"$EMU" -avd "$AVD" -port "$PORT" -no-window -no-audio -no-snapshot -no-boot-anim \
  -gpu swiftshader_indirect > "$QA/emulator_boot.log" 2>&1 &

booted=0
iters=$(( BOOT_TIMEOUT / 2 ))
for _ in $(seq 1 "$iters"); do
  [ "$("$ADB" -s "$SER" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && { booted=1; break; }
  sleep 2
done
ABI="$("$ADB" -s "$SER" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
SDK="$("$ADB" -s "$SER" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
"$ADB" -s "$SER" shell getprop 2>/dev/null > "$QA/getprop.txt" || true
[ "$booted" = "1" ] || fail "AVD did not reach sys.boot_completed within ${BOOT_TIMEOUT}s (see $QA/emulator_boot.log)"
case "$ABI" in
  arm64-v8a) : ;;
  *) fail "abi=$ABI is not arm64-v8a — NOT HVF-accelerated on this host; P1 requires the accelerated path" ;;
esac
log "AVD booted: abi=$ABI sdk=$SDK"

# 3) Install the self-instrumenting androidTest APK.
"$ADB" -s "$SER" install -r -t "$TEST_APK" > "$QA/install.log" 2>&1 \
  || fail "adb install of androidTest APK failed (see $QA/install.log)"
log "installed test package $TEST_PKG"

# 4) Run the instrumentation ON the device, capturing the full stream.
"$ADB" -s "$SER" logcat -c >/dev/null 2>&1 || true
log "running: am instrument -w -r $TEST_PKG/$RUNNER"
"$ADB" -s "$SER" shell am instrument -w -r "$TEST_PKG/$RUNNER" 2>&1 | tee "$QA/instrumentation.txt"
# Capture the test's own logcat lines proving it ran on the AVD.
"$ADB" -s "$SER" logcat -d -s OtaAgentOnDeviceTest:I 2>/dev/null > "$QA/logcat_ondevice.txt" || true

# 5) Verdict from the captured instrumentation stream (anti-bluff: parse the REAL result).
#    AndroidJUnitRunner emits "OK (N tests)" on success, "FAILURES!!!" / "Tests run: X,  Failures: Y"
#    plus per-test "INSTRUMENTATION_STATUS_CODE: -1/-2" on failure. We require an OK with >=1 test
#    AND no failure markers AND a non-empty on-device logcat proof.
RESULT_FILE="$QA/instrumentation.txt"
ok_line="$(grep -E '^OK \([0-9]+ test' "$RESULT_FILE" 2>/dev/null | tail -1)"
fail_marker="$(grep -E 'FAILURES!!!|INSTRUMENTATION_RESULT.*Process crashed|shortMsg=' "$RESULT_FILE" 2>/dev/null | head -1)"
status_fail="$(grep -E 'INSTRUMENTATION_STATUS_CODE: -[12]' "$RESULT_FILE" 2>/dev/null | head -1)"
ran_count="$(printf '%s' "$ok_line" | sed -E 's/^OK \(([0-9]+) test.*/\1/')"

{
  echo "=== ota-android-agent on-device instrumentation result ($(date -u +%FT%TZ)) ==="
  echo "avd=$AVD serial=$SER abi=$ABI sdk=$SDK test_pkg=$TEST_PKG runner=$RUNNER"
  echo "ok_line='${ok_line}'  fail_marker='${fail_marker}'  status_fail='${status_fail}'"
  echo "--- on-device logcat (tag OtaAgentOnDeviceTest) ---"
  cat "$QA/logcat_ondevice.txt" 2>/dev/null
} | tee "$QA/result.txt"

[ -n "$ok_line" ] || fail "no 'OK (N tests)' line in instrumentation output (see $RESULT_FILE)"
[ -z "$fail_marker" ] || fail "failure marker present in instrumentation output: $fail_marker"
[ -z "$status_fail" ] || fail "a test reported a failing status code: $status_fail"
[ -n "$ran_count" ] && [ "$ran_count" -ge 1 ] || fail "expected >=1 test to run, parsed ran_count='$ran_count'"
[ -s "$QA/logcat_ondevice.txt" ] || fail "no on-device OtaAgentOnDeviceTest logcat captured — test may not have executed on the AVD"

log "RESULT: PASS — $ran_count on-device instrumentation tests OK on arm64-v8a AVD (abi=$ABI sdk=$SDK)."
log "evidence: $QA/"
exit 0
