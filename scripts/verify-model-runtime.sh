#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the designated emulator or authorized S24}"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
export ADB
if [[ "${SUPERVISED_THERMAL:-true}" != true ]]; then
  printf 'Native runtime QA now requires supervised thermal monitoring and a device lease.\n' >&2
  exit 2
fi
export SUPERVISED_THERMAL=true
export QA_SUPERVISION_RUN_ID="$(node -e 'process.stdout.write(require("node:crypto").randomUUID())')"
export QA_SUPERVISOR_PID=$$
BACKEND="${MODEL_BACKEND:-CPU}"
[[ "$BACKEND" == CPU || "$BACKEND" == GPU ]] || exit 2
PACKAGE=dev.edgecompanion.app.secureqa
RESULTS="benchmarks/results/native-$(date -u +%Y%m%dT%H%M%SZ)-$BACKEND"
mkdir -p "$RESULTS"
git rev-parse HEAD > "$RESULTS/app-head.txt"
# HEAD alone does not identify local changes. Record their digest, not private file contents.
git diff --binary | shasum -a 256 > "$RESULTS/tracked-diff-sha256.txt"
./gradlew :androidApp:assembleSecureQa :androidApp:assembleSecureQaAndroidTest -PtestBuildType=secureQa --console=plain
shasum -a 256 androidApp/build/outputs/apk/secureQa/androidApp-secureQa.apk > "$RESULTS/apk-sha256.txt"
"$ADB" -s "$ANDROID_SERIAL" install -r androidApp/build/outputs/apk/secureQa/androidApp-secureQa.apk
"$ADB" -s "$ANDROID_SERIAL" install -r androidApp/build/outputs/apk/androidTest/secureQa/androidApp-secureQa-androidTest.apk
export MEMORY_POLICY_REPORT="$RESULTS/memory-policy.json"
bash scripts/export-memory-policy.sh
MONITOR_PID=""
cleanup() {
  if [[ -n "$MONITOR_PID" ]]; then
    kill "$MONITOR_PID" 2>/dev/null || true
    wait "$MONITOR_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT
if [[ "${SUPERVISED_THERMAL:-false}" == true ]]; then
  export THERMAL_REPORT_DIR="$RESULTS/thermal"
  node scripts/monitor-model-thermal.mjs > "$RESULTS/thermal-monitor.txt" 2>&1 &
  MONITOR_PID=$!
  for ((i=0; i<15; i++)); do
    if [[ -f "$THERMAL_REPORT_DIR/samples.csv" ]] && [[ $(wc -l < "$THERMAL_REPORT_DIR/samples.csv") -gt 1 ]]; then break; fi
    if ! kill -0 "$MONITOR_PID" 2>/dev/null; then exit 2; fi
    sleep 1
  done
  [[ -f "$THERMAL_REPORT_DIR/samples.csv" && ! -f "$THERMAL_REPORT_DIR/safety-stop.json" ]]
  [[ $(wc -l < "$THERMAL_REPORT_DIR/samples.csv") -gt 1 ]]
fi
"$ADB" -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class dev.edgecompanion.app.ModelRuntimeTest -e nativeModel true \
  -e modelBackend "$BACKEND" -e modelRepetitions "${MODEL_REPETITIONS:-1}" \
  -e modelSustainedMinutes "${MODEL_SUSTAINED_MINUTES:-0}" -e supervisedThermal "${SUPERVISED_THERMAL:-false}" \
  -e supervisionRunId "$QA_SUPERVISION_RUN_ID" \
  "$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" | tee "$RESULTS/instrumentation.txt"
"$ADB" -s "$ANDROID_SERIAL" exec-out run-as "$PACKAGE" cat no_backup/model-benchmark.json > "$RESULTS/runtime.json"
"$ADB" -s "$ANDROID_SERIAL" exec-out run-as "$PACKAGE" cat "no_backup/qa-supervision/$QA_SUPERVISION_RUN_ID/status.json" > "$RESULTS/supervision.json"
node scripts/verify-qa-supervision.mjs "$RESULTS/supervision.json" "$QA_SUPERVISION_RUN_ID"
if [[ -n "$MONITOR_PID" ]]; then
  kill -0 "$MONITOR_PID"
  [[ ! -f "$THERMAL_REPORT_DIR/safety-stop.json" ]]
fi
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED|INSTRUMENTATION_STATUS_CODE: -[1-4]' "$RESULTS/instrumentation.txt"; then
  exit 1
fi
grep -Eq 'OK \([0-9]+ tests?\)' "$RESULTS/instrumentation.txt"
printf 'Native smoke reports: %s\n' "$RESULTS"
