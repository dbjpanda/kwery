#!/usr/bin/env bash
#
# Prove the offline mutation queue survives a real OS process kill, not just
# a fresh object reopening the same store inside the same JVM.
#
# JUnit has no way to kill and relaunch a process from inside a test, so this
# drives dev.kwery.android.ProcessKillQueueTest as two separate `am instrument`
# invocations, with a real `am force-stop` between them:
#
#   1. step1_writeThenAwaitKill    — enqueue a write while offline, then exit
#      normally (the process is killed by this script, not by the test).
#   2. am force-stop                — an actual SIGKILL-class termination via
#      ActivityManager, not a clean process exit.
#   3. step2_verifyAfterRealKillThenDeliver — a fresh instrumentation process
#      opens the same store, asserts the write is still there, then delivers
#      it and asserts it is removed.
#
# Requires a running, unlocked emulator or device (`adb devices`).
#
#     ./scripts/process-kill-test.sh
#
set -euo pipefail

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly PACKAGE="dev.kwery.android.test"
readonly RUNNER="androidx.test.runner.AndroidJUnitRunner"
readonly TEST_CLASS="dev.kwery.android.ProcessKillQueueTest"
readonly APK="${REPO_ROOT}/kwery-android/build/outputs/apk/androidTest/debug/kwery-android-debug-androidTest.apk"

ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
if [[ ! -x "${ADB}" ]]; then
    ADB="$(command -v adb)"
fi

if ! "${ADB}" get-state 1>/dev/null 2>&1; then
    echo "No device/emulator attached. Start one and re-run." >&2
    exit 1
fi

echo "Building the instrumentation APK..."
"${REPO_ROOT}/gradlew" -p "${REPO_ROOT}" :kwery-android:assembleDebugAndroidTest --console=plain -q

echo "Installing fresh (clears any queue file from a previous run)..."
"${ADB}" uninstall "${PACKAGE}" >/dev/null 2>&1 || true
"${ADB}" install -r "${APK}" >/dev/null

echo
echo "== step 1: write while offline, then exit normally =="
"${ADB}" shell am instrument -w \
    -e class "${TEST_CLASS}#step1_writeThenAwaitKill" \
    "${PACKAGE}/${RUNNER}"

echo
echo "== killing the process for real (am force-stop) =="
"${ADB}" shell am force-stop "${PACKAGE}"
sleep 1
if "${ADB}" shell ps 2>/dev/null | grep -q "${PACKAGE}"; then
    echo "Process is still running after force-stop — not a real kill." >&2
    exit 1
fi
echo "Confirmed: process is not running."

echo
echo "== step 2: fresh process verifies the write survived, then delivers it =="
"${ADB}" shell am instrument -w \
    -e class "${TEST_CLASS}#step2_verifyAfterRealKillThenDeliver" \
    "${PACKAGE}/${RUNNER}"

echo
echo "Done. Both steps reported OK means the write was on disk after a real"
echo "process kill, and resume() delivered it correctly on the next launch."
