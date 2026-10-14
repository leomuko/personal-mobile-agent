#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the authorized S24}"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
export ADB
PACKAGE=dev.edgecompanion.app.secureqa
mkdir -p "$ROOT/benchmarks/results"
RESULTS="$(mktemp -d "$ROOT/benchmarks/results/chat-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")"
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
  RESULT_STAGE="$STAGE" RESULT_STATUS="$STATUS" RESULT_EXIT="$code" node --input-type=module - "$RESULTS/result.json" <<'JS'
import { writeFileSync } from 'node:fs';
writeFileSync(process.argv[2], JSON.stringify({ status: process.env.RESULT_STATUS, stage: process.env.RESULT_STAGE,
  exitCode: Number(process.env.RESULT_EXIT), recordedAt: new Date().toISOString() }, null, 2) + '\n');
JS
  printf 'Native conversation %s at %s (exit %s). Reports: %s\n' "$STATUS" "$STAGE" "$code" "$RESULTS"
  exit "$code"
}
trap cleanup EXIT
stage build
"${GRADLE:-./gradlew}" :androidApp:assembleSecureQa :androidApp:assembleSecureQaAndroidTest -PtestBuildType=secureQa --console=plain 2>&1 | tee "$RESULTS/build.txt"
shasum -a 256 androidApp/build/outputs/apk/secureQa/androidApp-secureQa.apk > "$RESULTS/apk-sha256.txt"
git rev-parse HEAD > "$RESULTS/app-head.txt"
git diff --stat > "$RESULTS/working-tree-stat.txt"
stage install-app
"$ADB" -s "$ANDROID_SERIAL" install -r androidApp/build/outputs/apk/secureQa/androidApp-secureQa.apk 2>&1 | tee "$RESULTS/install-app.txt"
stage install-tests
"$ADB" -s "$ANDROID_SERIAL" install -r androidApp/build/outputs/apk/androidTest/secureQa/androidApp-secureQa-androidTest.apk 2>&1 | tee "$RESULTS/install-tests.txt"
stage resource-preflight
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
"$ADB" -s "$ANDROID_SERIAL" shell am instrument -w -r -e class dev.edgecompanion.app.ConversationNativeTest \
  -e nativeChat true "$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "$RESULTS/instrumentation.txt"
stage validation
[[ ! -f "$THERMAL_REPORT_DIR/safety-stop.json" ]]
kill -0 "$MONITOR_PID"
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED|INSTRUMENTATION_STATUS_CODE: -[1-4]' "$RESULTS/instrumentation.txt"; then exit 1; fi
grep -Eq '^OK \(1 test\)$' "$RESULTS/instrumentation.txt"
STATUS=passed
stage complete
