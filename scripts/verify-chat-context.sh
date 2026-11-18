#!/bin/bash
# Device-prep S24 runs (docs/P2_DEVICE_PREP_RESULTS.md). Each run needs separate authorization.
#   scripts/verify-chat-context.sh measure 4096|8192   Part B context measurement (supervised)
#   scripts/verify-chat-context.sh sqlcipher           Part C SQLCipher passage gate
# Targets only dev.edgecompanion.app.secureqa and its test package: installs with `install -r`,
# runs `am instrument`, never uninstalls, clears or touches debug/release data.
# Bash 3.2 compatible (macOS /bin/bash): supervisor PID is $$; no Bash 4-only features.
# Bash 3.2 errexit ignores a failing bare [[ ]], so every gate is written `check || fail reason`.
set -Eeuo pipefail

usage() { printf 'Usage: %s measure 4096|8192 | %s sqlcipher\n' "$0" "$0" >&2; }
MODE="${1:-}"
PROFILE=""
case "$MODE" in
  measure)
    [[ $# -eq 2 ]] || { usage; exit 2; }
    PROFILE="$2"
    case "$PROFILE" in 4096|8192) ;; *) usage; exit 2 ;; esac
    ;;
  sqlcipher) [[ $# -eq 1 ]] || { usage; exit 2; } ;;
  *) usage; exit 2 ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the authorized S24 serial}"
[[ "$ANDROID_SERIAL" =~ ^[A-Za-z0-9._:-]+$ ]] || { printf 'Invalid ANDROID_SERIAL\n' >&2; exit 2; }
export ANDROID_SERIAL
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="${ADB:-$SDK/platform-tools/adb}"
export ADB
GRADLE="${GRADLE:-./gradlew}"
APK_ROOT="${APK_ROOT:-androidApp/build/outputs/apk}"
RESULTS_ROOT="${RESULTS_ROOT:-$ROOT/benchmarks/results}"
if [[ "$MODE" == measure ]]; then DEADLINE="${CHAT_CONTEXT_DEADLINE_SECONDS:-900}"
else DEADLINE="${PASSAGE_DEADLINE_SECONDS:-600}"; fi
STOP_GRACE="${QA_STOP_GRACE_SECONDS:-30}"
THERMAL_READY="${THERMAL_READY_SECONDS:-15}"
[[ "$DEADLINE" =~ ^[1-9][0-9]{0,4}$ && "$STOP_GRACE" =~ ^[1-9][0-9]{0,3}$ && "$THERMAL_READY" =~ ^[1-9][0-9]{0,2}$ ]] ||
  { printf 'Deadline, stop grace and thermal readiness must be positive whole seconds\n' >&2; exit 2; }

PACKAGE=dev.edgecompanion.app.secureqa
RUNNER="$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner"
APP_APK="$APK_ROOT/secureQa/androidApp-secureQa.apk"
TEST_APK="$APK_ROOT/androidTest/secureQa/androidApp-secureQa-androidTest.apk"
FAILURE_PATTERN='FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED|INSTRUMENTATION_STATUS_CODE: -[1-4]'

STAMP="$(date -u +%Y%m%dT%H%M%SZ)" || exit 2
if [[ "$MODE" == measure ]]; then RUN_ID="context-$PROFILE-$STAMP-$$"
else RUN_ID="passage-$STAMP-$$"; fi
mkdir -p "$RESULTS_ROOT" || exit 2
RESULTS="$(mktemp -d "$RESULTS_ROOT/$RUN_ID-XXXXXX")" || exit 2
if [[ "$MODE" == measure ]]; then
  export QA_SUPERVISION_RUN_ID="$RUN_ID"
  # The thermal monitor is started directly by this process, so its parent is this script's PID.
  export QA_SUPERVISOR_PID=$$
fi

STAGE=setup
STATUS=failed
REASON=""
MONITOR_PID=""
INSTR_PID=""
STARTED_MONITOR_PID=""
STARTED_INSTR_PID=""
EVIDENCE_COLLECTED=false

device() { "$ADB" -s "$ANDROID_SERIAL" "$@"; }
stage() { STAGE="$1"; printf '%s %s\n' "$(date -u +%FT%TZ)" "$STAGE" | tee -a "$RESULTS/stages.txt" || exit 1; }
# Records why the run failed, then exits through finish().
fail() { REASON="$1"; exit "${2:-1}"; }

# Fails closed unless build-tools aapt2 reads exactly the expected application ID from the APK.
verify_apk_package() {
  local apk="$1" expected="$2" dir="" newest="" actual=""
  # Newest installed build-tools that has an executable aapt2, from the same SDK root as adb.
  newest="$(for dir in "$SDK"/build-tools/*; do
      if [[ -x "$dir/aapt2" ]]; then printf '%s\n' "${dir##*/}"; fi
    done | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1)" || fail apk-identity
  [[ -n "$newest" ]] || fail apk-identity
  actual="$("$SDK/build-tools/$newest/aapt2" dump packagename "$apk" 2>> "$RESULTS/apk-identity-errors.txt")" ||
    fail apk-identity
  printf '%s %s %s\n' "$expected" "$actual" "$apk" >> "$RESULTS/apk-identity.txt" || fail apk-identity
  [[ "$actual" == "$expected" ]] || fail apk-identity
}

# Stops one process this script started, then reaps it.
stop_owned() {
  local pid="$1"
  [[ -n "$pid" ]] || return 0
  if kill -0 "$pid" 2>/dev/null; then kill -TERM "$pid" 2>/dev/null || true; fi
  wait "$pid" 2>/dev/null || true
}

pull() {
  if device exec-out run-as "$PACKAGE" cat "$1" > "$RESULTS/$2" 2>> "$RESULTS/pull-errors.txt"; then return 0; fi
  rm -f "$RESULTS/$2"
  return 1
}

# Retains whatever device evidence exists; missing files are recorded, never fatal here.
collect_evidence() {
  [[ "$EVIDENCE_COLLECTED" == false ]] || return 0
  EVIDENCE_COLLECTED=true
  if [[ "$MODE" == measure ]]; then
    pull "no_backup/chat-context-$RUN_ID.json" chat-context.json || true
    pull "no_backup/chat-context-$RUN_ID-telemetry.json" partial-telemetry.json || true
    pull "no_backup/chat-context-$RUN_ID-supervision-stop.json" supervision-stop.json || true
    pull "no_backup/qa-supervision/$RUN_ID/status.json" supervision.json || true
    device shell dumpsys thermalservice > "$RESULTS/thermal-final.txt" 2>> "$RESULTS/pull-errors.txt" ||
      rm -f "$RESULTS/thermal-final.txt"
  fi
}

publish_deadline_stop() {
  node --input-type=module - "$ROOT/scripts/qa-supervision.mjs" "$RESULTS/deadline-stop.json" "$DEADLINE" <<'JS'
import { writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
const { stopSupervisedQa } = await import(pathToFileURL(process.argv[2]).href);
const runId = process.env.QA_SUPERVISION_RUN_ID;
const result = stopSupervisedQa({ adb: process.env.ADB, serial: process.env.ANDROID_SERIAL, runId });
writeFileSync(process.argv[3], JSON.stringify({ reason: 'deadline', runId, deadlineSeconds: Number(process.argv[4]),
  ...result, recordedAt: new Date().toISOString() }, null, 2) + '\n');
JS
}

finish() {
  local code=$?
  trap - EXIT
  set +e
  stop_owned "$INSTR_PID"
  stop_owned "$MONITOR_PID"
  case "$STAGE" in instrumentation|deadline|validation|complete) collect_evidence ;; esac
  if [[ "$STATUS" != passed || "$code" != 0 ]]; then
    STATUS=failed
    if [[ "$code" == 0 ]]; then code=1; fi
    if [[ -z "$REASON" ]]; then REASON="$STAGE"; fi
  fi
  RESULT_MODE="$MODE" RESULT_PROFILE="$PROFILE" RESULT_RUN_ID="$RUN_ID" RESULT_STATUS="$STATUS" \
    RESULT_REASON="$REASON" RESULT_STAGE="$STAGE" RESULT_EXIT="$code" RESULT_DEADLINE="$DEADLINE" \
    RESULT_SUPERVISOR="$$" RESULT_MONITOR="$STARTED_MONITOR_PID" RESULT_INSTR="$STARTED_INSTR_PID" \
    node --input-type=module - "$RESULTS/result.json" <<'JS'
import { writeFileSync } from 'node:fs';
const e = process.env;
const pid = value => (value ? Number(value) : null);
writeFileSync(process.argv[2], JSON.stringify({ runId: e.RESULT_RUN_ID, mode: e.RESULT_MODE,
  profile: e.RESULT_PROFILE ? Number(e.RESULT_PROFILE) : null, status: e.RESULT_STATUS,
  reason: e.RESULT_REASON || null, stage: e.RESULT_STAGE, exitCode: Number(e.RESULT_EXIT),
  deadlineSeconds: Number(e.RESULT_DEADLINE), supervisorPid: Number(e.RESULT_SUPERVISOR),
  monitorPid: pid(e.RESULT_MONITOR), instrumentationPid: pid(e.RESULT_INSTR),
  recordedAt: new Date().toISOString() }, null, 2) + '\n');
JS
  printf 'Device-prep %s %s at %s (exit %s). Reports: %s\n' "$MODE" "$STATUS" "$STAGE" "$code" "$RESULTS"
  exit "$code"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

printf 'Device-prep reports: %s\n' "$RESULTS"
stage build
if [[ "$MODE" == measure ]]; then
  "$GRADLE" --offline -PtestBuildType=secureQa -PchatContextTokens="$PROFILE" -PandroidPressureMemoryTest=false \
    :androidApp:assembleSecureQa :androidApp:assembleSecureQaAndroidTest --console=plain 2>&1 | tee "$RESULTS/build.txt" ||
    fail build
else
  "$GRADLE" --offline -PtestBuildType=secureQa \
    :androidApp:assembleSecureQa :androidApp:assembleSecureQaAndroidTest --console=plain 2>&1 | tee "$RESULTS/build.txt" ||
    fail build
fi
shasum -a 256 "$APP_APK" "$TEST_APK" > "$RESULTS/apks.sha256" || fail build
git rev-parse HEAD > "$RESULTS/app-head.txt" || fail build
# HEAD alone does not identify local changes. Record their digest, not file contents.
git diff --binary | shasum -a 256 > "$RESULTS/tracked-diff-sha256.txt" || fail build

stage install-app
verify_apk_package "$APP_APK" "$PACKAGE"
device install -r "$APP_APK" 2>&1 | tee "$RESULTS/install-app.txt" || fail install-app
grep -q '^Success' "$RESULTS/install-app.txt" || fail install-app
stage install-tests
verify_apk_package "$TEST_APK" "$PACKAGE.test"
device install -r "$TEST_APK" 2>&1 | tee "$RESULTS/install-tests.txt" || fail install-tests
grep -q '^Success' "$RESULTS/install-tests.txt" || fail install-tests

if [[ "$MODE" == measure ]]; then
  stage memory-policy
  export MEMORY_POLICY_REPORT="$RESULTS/memory-policy.json"
  "$BASH" scripts/export-memory-policy.sh || fail memory-policy
  stage thermal-monitor
  export THERMAL_REPORT_DIR="$RESULTS/thermal"
  SAMPLES="$THERMAL_REPORT_DIR/samples.csv"
  node scripts/monitor-model-thermal.mjs > "$RESULTS/thermal-monitor.txt" 2>&1 &
  MONITOR_PID=$!
  STARTED_MONITOR_PID="$MONITOR_PID"
  READY=false
  for ((i=0; i<THERMAL_READY; i++)); do
    if [[ -f "$SAMPLES" && "$(wc -l < "$SAMPLES")" -gt 1 ]]; then READY=true; break; fi
    kill -0 "$MONITOR_PID" 2>/dev/null || fail thermal-monitor 2
    sleep 1
  done
  [[ "$READY" == true ]] || fail thermal-monitor
  [[ ! -f "$THERMAL_REPORT_DIR/safety-stop.json" ]] || fail thermal-monitor
  kill -0 "$MONITOR_PID" 2>/dev/null || fail thermal-monitor
fi

stage instrumentation
# adb itself is the background job (no function, subshell or pipe), so $! is the client process
# that the deadline and finish() stop.
if [[ "$MODE" == measure ]]; then
  "$ADB" -s "$ANDROID_SERIAL" shell am instrument -w -r -e class dev.edgecompanion.app.ChatContextMeasurementTest \
    -e chatContextMeasurement true -e supervisedThermal true -e supervisionRunId "$RUN_ID" \
    "$RUNNER" > "$RESULTS/instrumentation.txt" 2>&1 &
else
  "$ADB" -s "$ANDROID_SERIAL" shell am instrument -w -r -e class dev.edgecompanion.app.PassageSqlCipherTest \
    -e p2PassageSqlCipher true "$RUNNER" > "$RESULTS/instrumentation.txt" 2>&1 &
fi
INSTR_PID=$!
STARTED_INSTR_PID="$INSTR_PID"
STARTED_AT=$SECONDS
DEADLINE_HIT=false
while kill -0 "$INSTR_PID" 2>/dev/null; do
  if (( SECONDS - STARTED_AT >= DEADLINE )); then DEADLINE_HIT=true; break; fi
  sleep 1
done

if [[ "$DEADLINE_HIT" == true ]]; then
  stage deadline
  REASON=deadline
  if [[ "$MODE" == measure ]]; then
    stop_owned "$MONITOR_PID"   # First, so no heartbeat from this script follows the stop.
    MONITOR_PID=""
    # Run-specific stop; the device watcher records the loss and stops the isolated QA process.
    publish_deadline_stop || true
  else
    # No device-side stop exists for this mode: only the host client is stopped, and the
    # device test may keep running until it ends on its own.
    printf '{"reason":"deadline","runId":"%s","deadlineSeconds":%s}\n' "$RUN_ID" "$DEADLINE" > "$RESULTS/deadline-stop.json"
  fi
  for ((i=0; i<STOP_GRACE; i++)); do
    kill -0 "$INSTR_PID" 2>/dev/null || break
    sleep 1
  done
  stop_owned "$INSTR_PID"
  INSTR_PID=""
  collect_evidence
  exit 1
fi

INSTR_CODE=0
wait "$INSTR_PID" || INSTR_CODE=$?
INSTR_PID=""
stage validation
collect_evidence
[[ "$INSTR_CODE" == 0 ]] || fail instrumentation
if grep -Eq "$FAILURE_PATTERN" "$RESULTS/instrumentation.txt"; then fail instrumentation; fi
if [[ "$MODE" == measure ]]; then
  grep -Eq '^OK \(1 test\)$' "$RESULTS/instrumentation.txt" || fail instrumentation
  [[ -f "$RESULTS/supervision.json" ]] || fail supervision
  node scripts/verify-qa-supervision.mjs "$RESULTS/supervision.json" "$RUN_ID" || fail supervision
  kill -0 "$MONITOR_PID" 2>/dev/null || fail thermal-monitor
  [[ ! -f "$THERMAL_REPORT_DIR/safety-stop.json" ]] || fail thermal-monitor
  [[ -f "$RESULTS/chat-context.json" ]] || fail report
  node -e 'const r = JSON.parse(require("node:fs").readFileSync(process.argv[1], "utf8"));
    if (r.runId !== process.argv[2] || r.status !== "measured" || r.cleanupConfirmed !== true) process.exit(1);' \
    "$RESULTS/chat-context.json" "$RUN_ID" || fail report
else
  grep -Eq '^OK \(3 tests\)$' "$RESULTS/instrumentation.txt" || fail instrumentation
fi
REASON=""
STATUS=passed
stage complete
