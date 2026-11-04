#!/usr/bin/env bash
# Runs the instrumented tests on a connected device or emulator. On failure it
# prints the crash buffer and the app's log so the cause shows up in CI output.
set -uo pipefail

work="${RUNNER_TEMP:-$(mktemp -d)}"
log="$work/device-logcat.txt"
heavy=io.github.l0gic0l.pepperjack.BenchSupplyLiveTest

adb logcat -c || true
# The device's log is kept on the host as it is written, so it survives the device going away.
adb logcat -v threadtime > "$log" 2>/dev/null &
logcat=$!

./gradlew --stacktrace connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=$heavy
status=$?
rm -rf "$work/results-main" && cp -r app/build/outputs/androidTest-results "$work/results-main" 2>/dev/null || true

# The bench supply's live view is the heaviest thing the app draws: it runs on its own, last.
./gradlew --stacktrace connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=$heavy
heavy_status=$?
[ "$status" -eq 0 ] && status=$heavy_status

kill "$logcat" 2>/dev/null || true
echo "::group::Failed tests"
find "$work/results-main" app/build/outputs/androidTest-results -name '*.xml' -exec grep -h -B1 -A40 '<failure' {} + 2>/dev/null | head -600 || true
echo "::endgroup::"
echo "::group::Crash buffer"
# adb waits for a device forever, so these are time-limited in case it has gone.
timeout 30 adb logcat -d -b crash || echo "(no device)"
echo "::endgroup::"
echo "::group::App log"
grep -E 'Pepperjack|AndroidRuntime|DEBUG|libc|scudo|JNI|TestRunner|lowmemorykiller|ActivityManager: (Killing|Process)' "$log" | tail -400 || true
echo "::endgroup::"
exit $status
