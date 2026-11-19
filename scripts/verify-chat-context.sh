#!/bin/bash
# Device-prep S24 runs (docs/P2_DEVICE_PREP_RESULTS.md). Each run needs separate authorization.
#   scripts/verify-chat-context.sh measure 2048|4096|8192 [--repeat 1-5]   context measurement (supervised)
#   scripts/verify-chat-context.sh sqlcipher                               SQLCipher passage + grounding gate
# Targets only dev.edgecompanion.app.secureqa and its test package: installs with `install -r`,
# runs `am instrument`, never uninstalls, clears or touches debug/release data.
# Both APKs are copied into this run's private results directory right after the build; only those
# staged copies are identity-checked, hashed and installed, so a later rebuild cannot change them.
# Bash 3.2 compatible (macOS /bin/bash): supervisor PID is $$; no Bash 4-only features.
# Bash 3.2 errexit ignores a failing bare [[ ]], so every gate is written `check || fail reason`.
set -Eeuo pipefail

usage() { printf 'Usage: %s measure 2048|4096|8192 [--repeat 1-5] | %s sqlcipher\n' "$0" "$0" >&2; }
MODE="${1:-}"
PROFILE=""
REPEAT=""
case "$MODE" in
  measure)
    [[ $# -eq 2 || $# -eq 4 ]] || { usage; exit 2; }
    PROFILE="$2"
    case "$PROFILE" in 2048|4096|8192) ;; *) usage; exit 2 ;; esac
    REPEAT=3
    if [[ $# -eq 4 ]]; then
      [[ "$3" == --repeat ]] || { usage; exit 2; }
      REPEAT="$4"
    fi
    case "$REPEAT" in 1|2|3|4|5) ;; *) usage; exit 2 ;; esac
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
# The measurement deadline scales with the repeat count N: 270 + 630 * N seconds, where
# 630 = 3 cases x (180 s per-case generation timeout + 30 s cleanup timeout) and 270 s covers the
# app release, telemetry and reporting. N=1 gives the earlier 900 s default. An explicit
# CHAT_CONTEXT_DEADLINE_SECONDS replaces the formula and is recorded as an override.
DEADLINE_FORMULA=""
if [[ "$MODE" == measure ]]; then
  DEADLINE_FORMULA="270 + 630 * repeat"
  DEADLINE="${CHAT_CONTEXT_DEADLINE_SECONDS:-$((270 + 630 * REPEAT))}"
else DEADLINE="${PASSAGE_DEADLINE_SECONDS:-600}"; fi
STOP_GRACE="${QA_STOP_GRACE_SECONDS:-30}"
THERMAL_READY="${THERMAL_READY_SECONDS:-15}"
[[ "$DEADLINE" =~ ^[1-9][0-9]{0,4}$ && "$STOP_GRACE" =~ ^[1-9][0-9]{0,3}$ && "$THERMAL_READY" =~ ^[1-9][0-9]{0,2}$ ]] ||
  { printf 'Deadline, stop grace and thermal readiness must be positive whole seconds\n' >&2; exit 2; }

PACKAGE=dev.edgecompanion.app.secureqa
RUNNER="$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner"
APP_APK="$APK_ROOT/secureQa/androidApp-secureQa.apk"
TEST_APK="$APK_ROOT/androidTest/secureQa/androidApp-secureQa-androidTest.apk"
EXPECTED_OUTPUT_TOKENS=512
MANIFEST="$ROOT/planning/models/gemma-4-e2b-it.json"
FAILURE_PATTERN='FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED|INSTRUMENTATION_STATUS_CODE: -[1-4]'

STAMP="$(date -u +%Y%m%dT%H%M%SZ)" || exit 2
if [[ "$MODE" == measure ]]; then RUN_ID="context-$PROFILE-$STAMP-$$"
else RUN_ID="passage-$STAMP-$$"; fi
mkdir -p "$RESULTS_ROOT" || exit 2
RESULTS="$(mktemp -d "$RESULTS_ROOT/$RUN_ID-XXXXXX")" || exit 2
# Run-owned copies of the APKs inside the private (mktemp -d, mode 0700) results directory: the
# only files this script ever installs.
STAGED="$RESULTS/apks"
STAGED_APP="$STAGED/androidApp-secureQa.apk"
STAGED_TEST="$STAGED/androidApp-secureQa-androidTest.apk"
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
EVIDENCE_LINES=""

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

note_evidence() { EVIDENCE_LINES="$EVIDENCE_LINES$1=$2
"; }

# Prints present or absent for an app-private file. `adb exec-out` does not reliably report the
# remote exit status, so existence is read from printed text rather than from an exit code.
remote_state() {
  local out=""
  out="$(device shell "run-as $PACKAGE sh -c 'if [ -f $1 ]; then echo present; else echo absent; fi'" \
    2>> "$RESULTS/pull-errors.txt")" || return 1
  out="$(printf '%s' "$out" | tr -d '\r')" || return 1
  case "$out" in present|absent) printf '%s\n' "$out" ;; *) return 1 ;; esac
}

# Pulls one app-private file and records its evidence state: present (a JSON object), absent,
# malformed (kept as <name>.malformed) or unavailable (existence or contents could not be read).
pull() {
  local remote="$1" name="$2" key="$3" state=""
  state="$(remote_state "$remote")" || { note_evidence "$key" unavailable; return 1; }
  if [[ "$state" == absent ]]; then note_evidence "$key" absent; return 1; fi
  if ! device exec-out run-as "$PACKAGE" cat "$remote" > "$RESULTS/$name" 2>> "$RESULTS/pull-errors.txt"; then
    rm -f -- "${RESULTS:?}/${name:?}"
    note_evidence "$key" unavailable
    return 1
  fi
  if node -e 'const v = JSON.parse(require("node:fs").readFileSync(process.argv[1], "utf8"));
    if (v === null || typeof v !== "object" || Array.isArray(v)) process.exit(1);' "$RESULTS/$name" 2>/dev/null; then
    note_evidence "$key" present
    return 0
  fi
  mv -f -- "${RESULTS:?}/${name:?}" "$RESULTS/$name.malformed" || true
  note_evidence "$key" malformed
  return 1
}

# Retains whatever device evidence exists; each file's state goes to evidence.json, never fatal here.
collect_evidence() {
  [[ "$EVIDENCE_COLLECTED" == false ]] || return 0
  EVIDENCE_COLLECTED=true
  if [[ "$MODE" == measure ]]; then
    pull "no_backup/chat-context-$RUN_ID.json" chat-context.json chatContext || true
    pull "no_backup/chat-context-$RUN_ID-telemetry.json" partial-telemetry.json partialTelemetry || true
    # Optional: the device writes this marker only after a supervision loss; "absent" is normal.
    pull "no_backup/chat-context-$RUN_ID-supervision-stop.json" supervision-stop.json supervisionStop || true
    pull "no_backup/qa-supervision/$RUN_ID/status.json" supervision.json supervision || true
    if device shell dumpsys thermalservice > "$RESULTS/thermal-final.txt" 2>> "$RESULTS/pull-errors.txt"; then
      note_evidence thermalFinal present
    else
      rm -f -- "${RESULTS:?}/thermal-final.txt"
      note_evidence thermalFinal unavailable
    fi
    EVIDENCE_LINES="$EVIDENCE_LINES" node -e 'const out = {};
      for (const line of process.env.EVIDENCE_LINES.split("\n").filter(Boolean)) {
        const at = line.indexOf("="); out[line.slice(0, at)] = line.slice(at + 1); }
      require("node:fs").writeFileSync(process.argv[1], JSON.stringify(out, null, 2) + "\n");' \
      "$RESULTS/evidence.json" || true
  fi
}

# Prints a failure reason and exits non-zero unless the pulled evidence is complete and well formed.
check_evidence() {
  node -e 'const fs = require("node:fs");
    const fail = reason => { console.log(reason); process.exit(1); };
    let e; try { e = JSON.parse(fs.readFileSync(process.argv[1], "utf8")); } catch { fail("evidence-unavailable"); }
    if (Object.values(e).includes("malformed")) fail("malformed-evidence");
    if (e.supervisionStop === "present") fail("supervision-loss");
    if (e.supervisionStop !== "absent") fail("evidence-unavailable");
    if (e.supervision !== "present") fail("supervision");
    if (e.chatContext !== "present") fail("report");' "$RESULTS/evidence.json"
}

# Prints a failure reason and exits non-zero unless the device reported exactly what was requested.
check_report() {
  node -e 'const fs = require("node:fs");
    const [file, runId, profileText, repeatText, outputText, manifest] = process.argv.slice(1);
    const profile = Number(profileText), repeat = Number(repeatText), output = Number(outputText);
    const fail = reason => { console.log(reason); process.exit(1); };
    const r = JSON.parse(fs.readFileSync(file, "utf8"));
    const sha = JSON.parse(fs.readFileSync(manifest, "utf8")).artifact.sha256;
    if (r.schema !== 2 || r.runId !== runId || r.status !== "measured" || r.cleanupConfirmed !== true) fail("report");
    if (r.buildType !== "secureQa") fail("report-build-type");
    if (r.bakedContextTokens !== profile) fail("report-profile");
    if (r.reservedOutputTokens !== output) fail("report-output");
    if (r.backend !== "GPU" || r.automaticFallback !== false) fail("report-backend");
    if (typeof sha !== "string" || !/^[0-9a-f]{64}$/.test(sha) || r.modelSha256 !== sha) fail("report-model");
    const names = ["near_bound", "short_control", "long_answer"];
    const cases = Array.isArray(r.cases) ? r.cases : [];
    if (r.repeat !== repeat || cases.length !== names.length * repeat) fail("report-cases");
    const seen = new Set();
    for (const c of cases) {
      const key = c.case + "#" + c.iteration;
      if (!names.includes(c.case) || !Number.isInteger(c.iteration) || c.iteration < 1 || c.iteration > repeat ||
        seen.has(key) || c.status !== "measured" || c.cleanupConfirmed !== true) fail("report-cases");
      seen.add(key);
      const resident = c.resident || {};
      if (resident.loadedContextTokens !== profile) fail("report-profile");
      if (resident.loadedOutputTokens !== output) fail("report-output");
      if (resident.backend !== "GPU") fail("report-backend");
      // A short long answer passes, but its decode-rate flag must match its token count.
      if (c.case === "long_answer" && (typeof c.outputTokens !== "number" ||
        c.decodeRateMeaningful !== (c.outputTokens >= 128))) fail("report-long-answer");
      if (c.case !== "long_answer" && c.decodeRateMeaningful !== false) fail("report-cases");
    }' "$RESULTS/chat-context.json" "$RUN_ID" "$PROFILE" "$REPEAT" "$EXPECTED_OUTPUT_TOKENS" "$MANIFEST"
}

# Median and range per case over every recorded iteration; decode rate only for the long answer.
write_summary() {
  node -e 'const fs = require("node:fs");
    const r = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
    const median = v => { const s = [...v].sort((a, b) => a - b), m = Math.floor(s.length / 2);
      return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2; };
    const stats = values => values.every(v => typeof v === "number")
      ? { median: median(values), min: Math.min(...values), max: Math.max(...values), values } : { values };
    const fields = { loadMs: "loadMs", firstTextMs: "firstTextMs", coldToFirstTextMs: "coldRequestFirstTextMs",
      generationMs: "generationMs", inputTokens: "inputTokens", outputTokens: "outputTokens",
      sampledPeakPssBytes: "sampledPeakPssBytes", sampledPeakRssBytes: "sampledPeakRssBytes" };
    const cases = {};
    for (const name of ["near_bound", "short_control", "long_answer"]) {
      const runs = r.cases.filter(c => c.case === name).sort((a, b) => a.iteration - b.iteration);
      const entry = { iterations: runs.map(c => c.iteration) };
      for (const [out, key] of Object.entries(fields)) entry[out] = stats(runs.map(c => c[key]));
      entry.decodeRateMeaningful = name === "long_answer";
      entry.decodeTokensPerSecond = null;
      if (name === "long_answer") {
        // Only iterations that reached 128 output tokens count; short ones are listed, not averaged.
        const meaningful = runs.filter(c => c.decodeRateMeaningful === true);
        const shortIterations = runs.length - meaningful.length;
        entry.decodeTokensPerSecond = meaningful.length
          ? { ...stats(meaningful.map(c => c.decodeTokensPerSecond)), iterations: meaningful.map(c => c.iteration),
              shortIterations }
          : { status: "unavailable", shortIterations, minimumOutputTokens: 128 };
      }
      cases[name] = entry;
    }
    fs.writeFileSync(process.argv[2], JSON.stringify({ runId: r.runId, profile: r.bakedContextTokens,
      repeat: r.repeat, statistic: "median and range (min-max) over engine-cold iterations",
      decodeRateNote: "Decode rate only from long_answer iterations with at least 128 output tokens",
      cases }, null, 2) + "\n");' "$RESULTS/chat-context.json" "$RESULTS/summary.json"
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
  RESULT_MODE="$MODE" RESULT_PROFILE="$PROFILE" RESULT_REPEAT="$REPEAT" RESULT_RUN_ID="$RUN_ID" RESULT_STATUS="$STATUS" \
    RESULT_REASON="$REASON" RESULT_STAGE="$STAGE" RESULT_EXIT="$code" RESULT_DEADLINE="$DEADLINE" \
    RESULT_DEADLINE_FORMULA="$DEADLINE_FORMULA" RESULT_DEADLINE_OVERRIDE="${CHAT_CONTEXT_DEADLINE_SECONDS:-}" \
    RESULT_SUPERVISOR="$$" RESULT_MONITOR="$STARTED_MONITOR_PID" RESULT_INSTR="$STARTED_INSTR_PID" \
    node --input-type=module - "$RESULTS/result.json" <<'JS'
import { writeFileSync } from 'node:fs';
const e = process.env;
const pid = value => (value ? Number(value) : null);
writeFileSync(process.argv[2], JSON.stringify({ runId: e.RESULT_RUN_ID, mode: e.RESULT_MODE,
  profile: e.RESULT_PROFILE ? Number(e.RESULT_PROFILE) : null,
  repeat: e.RESULT_REPEAT ? Number(e.RESULT_REPEAT) : null, status: e.RESULT_STATUS,
  reason: e.RESULT_REASON || null, stage: e.RESULT_STAGE, exitCode: Number(e.RESULT_EXIT),
  deadlineSeconds: Number(e.RESULT_DEADLINE),
  deadlineSource: e.RESULT_DEADLINE_FORMULA ? (e.RESULT_DEADLINE_OVERRIDE ? 'override' : 'formula') : 'default',
  deadlineFormulaSeconds: e.RESULT_DEADLINE_FORMULA || null, supervisorPid: Number(e.RESULT_SUPERVISOR),
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
# Bind the run to these bytes: copy right after the build, check identity and hash the copies, and
# install only the copies (re-verified before each install). Later changes to the build outputs
# cannot reach the device.
stage stage-apks
mkdir -m 700 "$STAGED" || fail stage-apks
cp "$APP_APK" "$STAGED_APP" || fail stage-apks
cp "$TEST_APK" "$STAGED_TEST" || fail stage-apks
chmod 400 "$STAGED_APP" "$STAGED_TEST" || fail stage-apks
verify_apk_package "$STAGED_APP" "$PACKAGE"
verify_apk_package "$STAGED_TEST" "$PACKAGE.test"
(cd "$STAGED" && shasum -a 256 androidApp-secureQa.apk androidApp-secureQa-androidTest.apk) > "$RESULTS/apks.sha256" ||
  fail stage-apks
verify_staged() { (cd "$STAGED" && shasum -a 256 -c "$RESULTS/apks.sha256") >> "$RESULTS/apk-verify.txt" 2>&1; }
git rev-parse HEAD > "$RESULTS/app-head.txt" || fail build
# HEAD alone does not identify local changes. Record their digest, not file contents.
git diff --binary | shasum -a 256 > "$RESULTS/tracked-diff-sha256.txt" || fail build

stage install-app
verify_staged || fail apk-integrity
device install -r "$STAGED_APP" 2>&1 | tee "$RESULTS/install-app.txt" || fail install-app
grep -q '^Success' "$RESULTS/install-app.txt" || fail install-app
stage install-tests
verify_staged || fail apk-integrity
device install -r "$STAGED_TEST" 2>&1 | tee "$RESULTS/install-tests.txt" || fail install-tests
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
    -e chatContextMeasurement true -e supervisedThermal true -e supervisionRunId "$RUN_ID" -e chatContextRepeat "$REPEAT" \
    "$RUNNER" > "$RESULTS/instrumentation.txt" 2>&1 &
else
  "$ADB" -s "$ANDROID_SERIAL" shell am instrument -w -r \
    -e class dev.edgecompanion.app.PassageSqlCipherTest,dev.edgecompanion.app.GroundingSqlCipherTest \
    -e p2PassageSqlCipher true -e p2GroundingSqlCipher true "$RUNNER" > "$RESULTS/instrumentation.txt" 2>&1 &
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
  GATE_REASON="$(check_evidence)" || fail "${GATE_REASON:-evidence-unavailable}"
  node scripts/verify-qa-supervision.mjs "$RESULTS/supervision.json" "$RUN_ID" || fail supervision
  kill -0 "$MONITOR_PID" 2>/dev/null || fail thermal-monitor
  [[ ! -f "$THERMAL_REPORT_DIR/safety-stop.json" ]] || fail thermal-monitor
  GATE_REASON="$(check_report)" || fail "${GATE_REASON:-report}"
  write_summary || fail summary
else
  # PassageSqlCipherTest (3) + GroundingSqlCipherTest (5); a skipped opt-in would not count as passing.
  grep -Eq '^OK \(8 tests\)$' "$RESULTS/instrumentation.txt" || fail instrumentation
fi
REASON=""
STATUS=passed
stage complete
