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
# The host's and the device's memory, every 10 s, in case either runs short.
(while sleep 10; do
    echo "$(date -u +%T) host $(free -m | awk '/^Mem:/ {print $3 "/" $2 " MB used"}')" \
        "device $(timeout 5 adb shell "grep -E 'MemAvailable' /proc/meminfo" 2>/dev/null | tr -s ' ' | tr -d '\r')" \
        "app $(timeout 5 adb shell "dumpsys meminfo io.github.l0gic0l.pepperjack | grep -E 'TOTAL PSS|Native Heap:'" 2>/dev/null | tr -s ' ' | tr '\n\r' '  ')"
done) > "$work/host-memory.txt" &
memwatch=$!

echo "Host: $(uname -srvm); KVM: $(ls -l /dev/kvm 2>&1); $(nproc) CPUs"
ui=io.github.l0gic0l.pepperjack.UserFlowTest

# The UI flows first, on a fresh device, then everything else: if the emulator goes away,
# which group it was in says whether the live views or what ran before them are to blame.
./gradlew --stacktrace connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=$ui
status=$?
rm -rf "$work/results-ui" && cp -r app/build/outputs/androidTest-results "$work/results-ui" 2>/dev/null || true

./gradlew --stacktrace connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.notClass=$ui,$heavy"
rest_status=$?
[ "$status" -eq 0 ] && status=$rest_status
rm -rf "$work/results-main" && cp -r app/build/outputs/androidTest-results "$work/results-main" 2>/dev/null || true

# The bench supply's live view is the heaviest thing the app draws: it runs on its own, last.
./gradlew --stacktrace connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=$heavy
heavy_status=$?
[ "$status" -eq 0 ] && status=$heavy_status

kill "$logcat" "$memwatch" 2>/dev/null || true
echo "::group::Failed tests"
find "$work/results-ui" "$work/results-main" app/build/outputs/androidTest-results -name '*.xml' -exec grep -h -B1 -A40 '<failure' {} + 2>/dev/null | head -600 || true
echo "::endgroup::"
echo "::group::Crash buffer"
# adb waits for a device forever, so these are time-limited in case it has gone.
timeout 30 adb logcat -d -b crash || echo "(no device)"
echo "::endgroup::"
echo "::group::App log"
# Without the tests' semantics-tree dumps, which would crowd everything else out.
grep -E 'Pepperjack|AndroidRuntime|DEBUG|libc|scudo|JNI|TestRunner|lowmemorykiller|lmkd|ActivityManager: (Killing|Process)' "$log" | grep -v 'PepperjackTree' | tail -400 || true
echo "::endgroup::"
echo "::group::Host and device memory, and OOM kills"
tail -60 "$work/host-memory.txt" 2>/dev/null || true
sudo -n dmesg -T 2>/dev/null | grep -iE 'out of memory|killed process|oom' | tail -20 || true
echo "::endgroup::"
echo "::group::Emulator crash reports"
# The emulator has gone away without a word: its crash handler's minidumps say where, and
# the host's logs say whether something else ended it.
grep -m1 'model name' /proc/cpuinfo || true
sudo -n dmesg -T 2>/dev/null | grep -iE 'segfault|general protection|trap|qemu|kvm|killed' | tail -20 || true
sudo -n journalctl --no-pager -q --since '-90 min' 2>/dev/null | grep -iE 'oom|killed|qemu|emulator|core ?dump' | tail -20 || true
python3 "$(dirname "$0")/minidump-summary.py" /tmp/android-runner "$HOME/.android" || true
echo "::endgroup::"
exit $status
