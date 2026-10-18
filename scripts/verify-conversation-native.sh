#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the authorized S24}"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
export ADB
export MEMORY_PREFLIGHT_MODE="${MEMORY_PREFLIGHT_MODE:-baseline}"
case "$MEMORY_PREFLIGHT_MODE" in
  baseline) ;;
  android-pressure)
    if [[ "${MEMORY_RELIABILITY:-false}" != true ]]; then
      printf 'Android-pressure preflight is restricted to the memory-reliability workload.\n' >&2
      exit 2
    fi
    printf 'Android-pressure experiment selected: host and isolated QA app RAM floors are bypassed only with lowMemory=false; thermal/OS-pressure stops remain active.\n'
    ;;
  *) printf 'Unknown MEMORY_PREFLIGHT_MODE. Use baseline or android-pressure.\n' >&2; exit 2 ;;
esac
PACKAGE=dev.edgecompanion.app.secureqa
mkdir -p "$ROOT/benchmarks/results"
RESULTS="$(mktemp -d "$ROOT/benchmarks/results/chat-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")"
export MEMORY_RUN_ID="$(node -e 'process.stdout.write(require("node:crypto").randomUUID())')"
export QA_SUPERVISION_RUN_ID="$MEMORY_RUN_ID"
export QA_SUPERVISOR_PID=$$
node --input-type=module - "$RESULTS/run-context.json" "$MEMORY_RUN_ID" <<'JS'
import { writeFileSync } from 'node:fs';
writeFileSync(process.argv[2], JSON.stringify({ runId: process.argv[3], preflightMode: process.env.MEMORY_PREFLIGHT_MODE }) + '\n');
JS
printf 'Native conversation reports: %s\n' "$RESULTS"
STAGE=setup
STATUS=failed
MONITOR_PID=""
stage() { STAGE="$1"; printf '%s %s\n' "$(date -u +%FT%TZ)" "$STAGE" | tee -a "$RESULTS/stages.txt"; }
cleanup() {
  local code=$?
  trap - EXIT
  if [[ -n "$MONITOR_PID" ]]; then
    kill "$MONITOR_PID" 2>/dev/null || true
    wait "$MONITOR_PID" 2>/dev/null || true
  fi
  if [[ "$STAGE" == instrumentation || "$STAGE" == validation || "$STAGE" == complete ]]; then
    if ! "$ADB" -s "$ANDROID_SERIAL" exec-out run-as "$PACKAGE" cat "no_backup/qa-supervision/$QA_SUPERVISION_RUN_ID/status.json" > "$RESULTS/supervision.json" ||
        ! node scripts/verify-qa-supervision.mjs "$RESULTS/supervision.json" "$QA_SUPERVISION_RUN_ID"; then
      STATUS=failed
      if [[ "$code" == 0 ]]; then code=1; fi
    fi
    if [[ "${MEMORY_RELIABILITY:-false}" == true ]]; then
      "$ADB" -s "$ANDROID_SERIAL" exec-out run-as "$PACKAGE" cat no_backup/memory-reliability.json > "$RESULTS/memory-reliability.json" 2> "$RESULTS/memory-report-export.txt" || true
      "$ADB" -s "$ANDROID_SERIAL" exec-out run-as "$PACKAGE" cat no_backup/memory-samples.json > "$RESULTS/memory-samples.json" 2> "$RESULTS/memory-samples-export.txt" || true
    fi
    if ! bash scripts/collect-memory-exits.sh "$RESULTS"; then
      if [[ "$STATUS" == passed ]]; then STATUS=failed; fi
      if [[ "$code" == 0 ]]; then code=1; fi
    fi
    if [[ "${MEMORY_RELIABILITY:-false}" == true ]]; then
      if ! node scripts/analyze-memory-reliability.mjs "$RESULTS" > "$RESULTS/memory-analysis.txt" 2>&1; then
        if [[ "$STATUS" == passed ]]; then STATUS=failed; fi
        if [[ "$code" == 0 ]]; then code=1; fi
      fi
    fi
  fi
  RESULT_STAGE="$STAGE" RESULT_STATUS="$STATUS" RESULT_EXIT="$code" node --input-type=module - "$RESULTS/result.json" <<'JS'
import { writeFileSync } from 'node:fs';
writeFileSync(process.argv[2], JSON.stringify({ runId: process.env.MEMORY_RUN_ID, preflightMode: process.env.MEMORY_PREFLIGHT_MODE,
  status: process.env.RESULT_STATUS, stage: process.env.RESULT_STAGE,
  exitCode: Number(process.env.RESULT_EXIT), recordedAt: new Date().toISOString() }, null, 2) + '\n');
JS
  printf 'Native conversation %s at %s (exit %s). Reports: %s\n' "$STATUS" "$STAGE" "$code" "$RESULTS"
  exit "$code"
}
trap cleanup EXIT
stage build
PRESSURE_TEST=false
if [[ "$MEMORY_PREFLIGHT_MODE" == android-pressure ]]; then PRESSURE_TEST=true; fi
"${GRADLE:-./gradlew}" :androidApp:assembleSecureQa :androidApp:assembleSecureQaAndroidTest -PtestBuildType=secureQa \
  -PandroidPressureMemoryTest="$PRESSURE_TEST" --console=plain 2>&1 | tee "$RESULTS/build.txt"
shasum -a 256 androidApp/build/outputs/apk/secureQa/androidApp-secureQa.apk > "$RESULTS/apk-sha256.txt"
git rev-parse HEAD > "$RESULTS/app-head.txt"
git diff --stat > "$RESULTS/working-tree-stat.txt"
stage install-app
"$ADB" -s "$ANDROID_SERIAL" install -r androidApp/build/outputs/apk/secureQa/androidApp-secureQa.apk 2>&1 | tee "$RESULTS/install-app.txt"
stage install-tests
"$ADB" -s "$ANDROID_SERIAL" install -r androidApp/build/outputs/apk/androidTest/secureQa/androidApp-secureQa-androidTest.apk 2>&1 | tee "$RESULTS/install-tests.txt"
stage resource-preflight
export MEMORY_POLICY_REPORT="$RESULTS/memory-policy.json"
bash scripts/export-memory-policy.sh
export RESOURCE_REPORT="$RESULTS/resources.jsonl"
if node scripts/chat-resource-preflight.mjs 2>&1 | tee "$RESULTS/preflight.txt"; then :; else STATUS=blocked; exit 2; fi
stage thermal-monitor
export THERMAL_REPORT_DIR="$RESULTS/thermal"
node scripts/monitor-model-thermal.mjs > "$RESULTS/thermal-monitor.txt" 2>&1 &
MONITOR_PID=$!
for ((i=0; i<15; i++)); do
  if [[ -f "$THERMAL_REPORT_DIR/samples.csv" ]] && [[ $(wc -l < "$THERMAL_REPORT_DIR/samples.csv") -gt 1 ]]; then break; fi
  if ! kill -0 "$MONITOR_PID" 2>/dev/null; then STATUS=blocked; exit 2; fi
  sleep 1
done
if [[ ! -f "$THERMAL_REPORT_DIR/samples.csv" ]] || [[ $(wc -l < "$THERMAL_REPORT_DIR/samples.csv") -le 1 ]]; then STATUS=blocked; exit 2; fi
[[ ! -f "$THERMAL_REPORT_DIR/safety-stop.json" ]]
stage instrumentation
if [[ "${MEMORY_RELIABILITY:-false}" == true ]]; then
  "$ADB" -s "$ANDROID_SERIAL" shell run-as "$PACKAGE" rm -f no_backup/memory-reliability.json no_backup/memory-samples.json
  "$ADB" -s "$ANDROID_SERIAL" shell am instrument -w -r -e class dev.edgecompanion.app.MemoryReliabilityTest \
    -e nativeMemory true -e supervisedThermal true -e memoryCycles "${MEMORY_CYCLES:-5}" \
    -e memorySustainedMinutes "${MEMORY_SUSTAINED_MINUTES:-20}" \
    -e memoryRunId "$MEMORY_RUN_ID" \
    -e supervisionRunId "$QA_SUPERVISION_RUN_ID" \
    -e memoryPreflightMode "$MEMORY_PREFLIGHT_MODE" \
    "$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "$RESULTS/instrumentation.txt"
else
  "$ADB" -s "$ANDROID_SERIAL" shell am instrument -w -r -e class dev.edgecompanion.app.ConversationNativeTest \
    -e nativeChat true -e supervisedThermal true -e supervisionRunId "$QA_SUPERVISION_RUN_ID" \
    "$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "$RESULTS/instrumentation.txt"
fi
stage validation
[[ ! -f "$THERMAL_REPORT_DIR/safety-stop.json" ]]
kill -0 "$MONITOR_PID"
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED|INSTRUMENTATION_STATUS_CODE: -[1-4]' "$RESULTS/instrumentation.txt"; then exit 1; fi
grep -Eq '^OK \(1 test\)$' "$RESULTS/instrumentation.txt"
STATUS=passed
stage complete
