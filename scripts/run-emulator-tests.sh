#!/usr/bin/env bash
# Runs the instrumented tests on a connected device or emulator. On failure it
# prints the crash buffer and the app's log so the cause shows up in CI output.
set -uo pipefail

adb logcat -c || true
./gradlew --stacktrace connectedDebugAndroidTest
status=$?

echo "::group::Failed tests"
find app/build/outputs/androidTest-results -name '*.xml' -exec grep -h -B1 -A40 '<failure' {} + 2>/dev/null | head -600 || true
echo "::endgroup::"
echo "::group::Crash buffer"
adb logcat -d -b crash || true
echo "::endgroup::"
echo "::group::App log"
adb logcat -d -v threadtime | grep -E 'Pepperjack|AndroidRuntime|DEBUG|libc|scudo|JNI|TestRunner' | tail -400 || true
echo "::endgroup::"
exit $status
