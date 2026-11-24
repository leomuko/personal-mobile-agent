#!/bin/bash
# Device-prep S24 runs (docs/P2_DEVICE_PREP_RESULTS.md). Each run needs separate authorization.
#   scripts/verify-chat-context.sh measure 2048|4096|8192 [--repeat 1-5]   context measurement (supervised)
#   scripts/verify-chat-context.sh sqlcipher                               SQLCipher passage + grounding gate
#   scripts/verify-chat-context.sh ui                                      S5 Compose UI tests + S24 captures
#   scripts/verify-chat-context.sh quality --set tuning|heldout|dev [--passes 1|3] [--candidates r1|r2] [--assets-only]   S6 capture
# Targets only dev.edgecompanion.app.secureqa and its test package: installs with `install -r`,
# runs `am instrument`, never uninstalls, clears or touches debug/release data.
# Both APKs are copied into this run's private results directory right after the build; only those
# staged copies are identity-checked, hashed and installed, so a later rebuild cannot change them.
# Bash 3.2 compatible (macOS /bin/bash): supervisor PID is $$; no Bash 4-only features.
# Bash 3.2 errexit ignores a failing bare [[ ]], so every gate is written `check || fail reason`.
set -Eeuo pipefail

usage() { printf 'Usage: %s measure 2048|4096|8192 [--repeat 1-5] | %s sqlcipher | %s ui | %s quality --set tuning|heldout|dev [--passes 1|3] [--candidates r1|r2] [--assets-only]\n' "$0" "$0" "$0" "$0" >&2; }
MODE="${1:-}"
PROFILE=""
REPEAT=""
QUALITY_SET=""
QUALITY_PASSES=""
QUALITY_LOCK=""
QUALITY_CANDIDATE_ROUND=""
QUALITY_CANDIDATE_LOCK=""
QUALITY_FIXTURE="answer-quality-v1"
QUALITY_ASSETS_ONLY=false
QUALITY_DEV_SNAPSHOT=""
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
  sqlcipher|ui) [[ $# -eq 1 ]] || { usage; exit 2; } ;;
  quality)
    [[ $# -ge 3 && "$2" == --set ]] || { usage; exit 2; }
    QUALITY_SET="$3"
    case "$QUALITY_SET" in
      tuning) QUALITY_ITEMS=10; QUALITY_SCOPE_BLOCKS=1 ;;
      heldout) QUALITY_ITEMS=102; QUALITY_SCOPE_BLOCKS=2 ;;
      dev) QUALITY_ITEMS=40; QUALITY_SCOPE_BLOCKS=1; QUALITY_FIXTURE="answer-quality-dev-v1" ;;
      *) usage; exit 2 ;;
    esac
    shift 3
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --assets-only)
          [[ "$QUALITY_ASSETS_ONLY" == false ]] || { usage; exit 2; }
          QUALITY_ASSETS_ONLY=true
          shift
          continue ;;
        --passes)
          [[ $# -ge 2 ]] || { usage; exit 2; }
          [[ -z "$QUALITY_PASSES" && ( "$2" == 1 || "$2" == 3 ) ]] || { usage; exit 2; }
          QUALITY_PASSES="$2" ;;
        --candidates)
          [[ $# -ge 2 ]] || { usage; exit 2; }
          [[ -z "$QUALITY_CANDIDATE_ROUND" && ( "$2" == r1 || "$2" == r2 ) ]] || { usage; exit 2; }
          QUALITY_CANDIDATE_ROUND="$2" ;;
        *) usage; exit 2 ;;
      esac
      shift 2
    done
    QUALITY_PASSES="${QUALITY_PASSES:-1}"
    [[ "$QUALITY_SET" != dev || "$QUALITY_PASSES" == 1 ]] || { usage; exit 2; }
    [[ "$QUALITY_SET" != heldout || -z "$QUALITY_CANDIDATE_ROUND" ]] ||
      { printf 'Candidates are refused for heldout\n' >&2; exit 2; }
    [[ "$QUALITY_ASSETS_ONLY" == false || "$QUALITY_SET" == dev || -n "$QUALITY_CANDIDATE_ROUND" ]] ||
      { printf 'Asset-only checks require dev or a candidate round\n' >&2; exit 2; }
    PROFILE=4096
    ;;
  *) usage; exit 2 ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ -n "$QUALITY_CANDIDATE_ROUND" ]]; then
  QUALITY_CANDIDATE_DIR="$ROOT/planning/fixtures/grounded-prompt-candidates-$QUALITY_CANDIDATE_ROUND"
  candidate_info="$(node --input-type=module -e '
    import { loadCandidates } from "./scripts/answer-quality.mjs";
    const candidates = loadCandidates(process.argv[1]);
    console.log(candidates.lockSha256, candidates.entries.length);
  ' "$QUALITY_CANDIDATE_DIR")" || { printf 'Candidate files or lock are invalid\n' >&2; exit 2; }
  read -r QUALITY_CANDIDATE_LOCK QUALITY_SCOPE_BLOCKS <<< "$candidate_info" || exit 2
  QUALITY_ITEMS=$((QUALITY_ITEMS * QUALITY_SCOPE_BLOCKS))
fi
if [[ "$QUALITY_ASSETS_ONLY" == false ]]; then
  : "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the authorized S24 serial}"
  [[ "$ANDROID_SERIAL" =~ ^[A-Za-z0-9._:-]+$ ]] || { printf 'Invalid ANDROID_SERIAL\n' >&2; exit 2; }
  export ANDROID_SERIAL
fi
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
DEADLINE_OVERRIDE=""
if [[ "$MODE" == measure ]]; then
  DEADLINE_FORMULA="270 + 630 * repeat"
  DEADLINE_OVERRIDE="${CHAT_CONTEXT_DEADLINE_SECONDS:-}"
  DEADLINE="${CHAT_CONTEXT_DEADLINE_SECONDS:-$((270 + 630 * REPEAT))}"
elif [[ "$MODE" == ui ]]; then
  # Fixed, no override: 27 Compose/activity tests and 40 PNG captures, no model; well under 15 minutes.
  DEADLINE=900
elif [[ "$MODE" == quality ]]; then
  # Per item: 90 seconds to finish, 20 to settle a timeout, 10 for evidence.
  # Each scope block gets 240 seconds for engine transitions. Admission and cleanup get 300 seconds.
  # An explicit ANSWER_QUALITY_DEADLINE_SECONDS replaces the formula and is recorded as an override.
  DEADLINE_FORMULA="300 + passes * (240 * scopeBlocksPerPass + 120 * itemsPerPass)"
  DEADLINE_OVERRIDE="${ANSWER_QUALITY_DEADLINE_SECONDS:-}"
  cooldown=0
  if [[ -n "$QUALITY_CANDIDATE_ROUND" ]]; then
    cooldown=900
    DEADLINE_FORMULA="300 + passes * ((240 + 900) * scopeBlocksPerPass + 120 * itemsPerPass)"
  fi
  DEADLINE="${ANSWER_QUALITY_DEADLINE_SECONDS:-$((300 + QUALITY_PASSES * ((240 + cooldown) * QUALITY_SCOPE_BLOCKS + 120 * QUALITY_ITEMS)))}"
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
# ui mode: PassagesUiTest 6 + PassagesCaptureTest 9 + ComposerLayoutTest 7 + HostRegressionTest 5.
UI_CLASSES=dev.edgecompanion.app.PassagesUiTest,dev.edgecompanion.app.PassagesCaptureTest,dev.edgecompanion.app.ComposerLayoutTest,dev.edgecompanion.app.HostRegressionTest
UI_EXPECTED_TESTS=27
# One PNG per planned capture variant (docs/P2_PASSAGES_UI_RESULTS.md, items 1-9).
UI_EXPECTED_SCREENSHOTS=40
LIST_END=END-OF-CAPTURE-LIST

STAMP="$(date -u +%Y%m%dT%H%M%SZ)" || exit 2
if [[ "$MODE" == measure ]]; then RUN_ID="context-$PROFILE-$STAMP-$$"
elif [[ "$MODE" == ui ]]; then RUN_ID="passages-ui-$STAMP-$$"
elif [[ "$MODE" == quality ]]; then RUN_ID="answer-quality-$QUALITY_SET-$STAMP-$$"
else RUN_ID="passage-$STAMP-$$"; fi
# The capture test writes only under this run's own directory in the secureQa app's no_backup.
CAPTURE_REMOTE="no_backup/passages-captures/$RUN_ID"
mkdir -p "$RESULTS_ROOT" || exit 2
RESULTS="$(mktemp -d "$RESULTS_ROOT/$RUN_ID-XXXXXX")" || exit 2
# Run-owned copies of the APKs inside the private (mktemp -d, mode 0700) results directory: the
# only files this script ever installs.
STAGED="$RESULTS/apks"
STAGED_APP="$STAGED/androidApp-secureQa.apk"
STAGED_TEST="$STAGED/androidApp-secureQa-androidTest.apk"
if [[ "$MODE" == measure || "$MODE" == quality ]]; then
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
MONITOR_CLEAN=true
FINAL_THERMAL_REASON=""
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

# Join the owned monitor before trusting its final marker. A stuck or unexpectedly exited
# monitor cannot certify a clean run.
stop_thermal_monitor() {
  local pid="$MONITOR_PID" until=$((SECONDS + 15)) signalled=false status=0
  [[ -n "$pid" ]] || return 0
  if kill -TERM "$pid" 2>/dev/null; then signalled=true; fi
  while kill -0 "$pid" 2>/dev/null; do
    if (( SECONDS >= until )); then
      kill -KILL "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
      MONITOR_PID=""
      return 1
    fi
    sleep 1
  done
  wait "$pid" 2>/dev/null || status=$?
  MONITOR_PID=""
  [[ "$signalled" == true && ( "$status" == 0 || "$status" == 143 ) ]] || return 1
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

# Match the monitor's status and HAL SKIN parsing against the app-exported policy.
check_final_thermal() {
  node --input-type=module - "$RESULTS/thermal-final.txt" <<'JS'
import { readFileSync } from 'node:fs';
import { readMemoryPolicy } from './scripts/memory-policy.mjs';
try {
  const policy = readMemoryPolicy();
  const output = readFileSync(process.argv[2], 'utf8');
  const status = Number(/Thermal Status:\s*(\d+)/.exec(output)?.[1]);
  const hal = output.split('Current temperatures from HAL:')[1] || '';
  const skin = Number(/Temperature\{mValue=([\d.]+), mType=3, mName=SKIN/.exec(hal)?.[1]);
  if (!Number.isFinite(status) || !Number.isFinite(skin) || !hal.includes('mName=SKIN'))
    throw new Error('thermal status or SKIN sensor unavailable');
  if (status >= policy.severeThermalStatus || skin >= policy.maxSkinCelsius) console.log('thermal-stop');
} catch { console.log('thermal-final-unavailable'); }
JS
}

# Retains whatever device evidence exists; each file's state goes to evidence.json, never fatal here.
collect_evidence() {
  [[ "$EVIDENCE_COLLECTED" == false ]] || return 0
  EVIDENCE_COLLECTED=true
  if [[ "$MODE" == measure || "$MODE" == quality ]]; then
    if [[ "$MODE" == measure ]]; then
      pull "no_backup/chat-context-$RUN_ID.json" chat-context.json chatContext || true
      pull "no_backup/chat-context-$RUN_ID-telemetry.json" partial-telemetry.json partialTelemetry || true
      pull "no_backup/chat-context-$RUN_ID-supervision-stop.json" supervision-stop.json supervisionStop || true
    else
      pull "no_backup/answer-quality-$RUN_ID.json" answer-quality.json answerQuality || true
      pull "no_backup/answer-quality-$RUN_ID-supervision-stop.json" supervision-stop.json supervisionStop || true
    fi
    pull "no_backup/qa-supervision/$RUN_ID/status.json" supervision.json supervision || true
    # Join before the final reading. A pending monitor stop record already counts as a stop.
    stop_thermal_monitor || MONITOR_CLEAN=false
    if device shell dumpsys thermalservice > "$RESULTS/thermal-final.txt" 2>> "$RESULTS/pull-errors.txt"; then
      note_evidence thermalFinal present
      FINAL_THERMAL_REASON="$(check_final_thermal)" || FINAL_THERMAL_REASON=thermal-final-unavailable
    else
      rm -f -- "${RESULTS:?}/thermal-final.txt"
      note_evidence thermalFinal unavailable
      FINAL_THERMAL_REASON=thermal-final-unavailable
    fi
    EVIDENCE_LINES="$EVIDENCE_LINES" node -e 'const out = {};
      for (const line of process.env.EVIDENCE_LINES.split("\n").filter(Boolean)) {
        const at = line.indexOf("="); out[line.slice(0, at)] = line.slice(at + 1); }
      require("node:fs").writeFileSync(process.argv[1], JSON.stringify(out, null, 2) + "\n");' \
      "$RESULTS/evidence.json" || true
  elif [[ "$MODE" == ui ]]; then
    pull "no_backup/passages-captures/$RUN_ID.json" capture-metadata.json captureMetadata || true
    pull_screenshots || true
    write_ui_evidence || true
  fi
}

# Prints the entries of this run's capture directory. Fails unless the device printed the end
# marker, so an unreadable listing is never mistaken for an empty one.
list_screenshots() {
  local out=""
  out="$(device shell "run-as $PACKAGE sh -c 'if [ -d $CAPTURE_REMOTE ]; then ls -1 $CAPTURE_REMOTE; fi; echo $LIST_END'" \
    < /dev/null 2>> "$RESULTS/pull-errors.txt")" || return 1
  out="$(printf '%s\n' "$out" | tr -d '\r')" || return 1
  [[ "$(printf '%s\n' "$out" | tail -n 1)" == "$LIST_END" ]] || return 1
  printf '%s\n' "$out" | sed '$d'
}

# Pulls every NN-name.png the device lists into screenshots/. Other names are only listed (the
# validation rejects them); a failed pull is recorded and its partial file removed.
pull_screenshots() {
  local names="" name=""
  names="$(list_screenshots)" || { note_evidence screenshotList unavailable; return 1; }
  note_evidence screenshotList present
  printf '%s\n' "$names" | sed '/^$/d' > "$RESULTS/screenshot-list.txt" || return 1
  mkdir -p "$RESULTS/screenshots" || return 1
  : > "$RESULTS/screenshot-pull-failures.txt" || return 1
  while IFS= read -r name; do
    if [[ ! "$name" =~ ^[0-9][0-9]-[a-z0-9-]+\.png$ ]]; then continue; fi
    # stdin from /dev/null: adb must not consume the remaining names.
    if ! device exec-out run-as "$PACKAGE" cat "$CAPTURE_REMOTE/$name" < /dev/null \
      > "$RESULTS/screenshots/$name" 2>> "$RESULTS/pull-errors.txt"; then
      rm -f -- "${RESULTS:?}/screenshots/${name:?}"
      printf '%s\n' "$name" >> "$RESULTS/screenshot-pull-failures.txt"
    fi
  done < "$RESULTS/screenshot-list.txt"
}

# evidence.json for ui mode: file states, the device listing, and each pulled PNG's sha256 and size.
write_ui_evidence() {
  EVIDENCE_LINES="$EVIDENCE_LINES" node -e 'const fs = require("node:fs"), path = require("node:path");
    const { createHash } = require("node:crypto");
    const [results, expected] = process.argv.slice(1);
    const lines = file => fs.existsSync(file) ? fs.readFileSync(file, "utf8").split("\n").filter(Boolean) : [];
    const out = {};
    for (const line of process.env.EVIDENCE_LINES.split("\n").filter(Boolean)) {
      const at = line.indexOf("="); out[line.slice(0, at)] = line.slice(at + 1); }
    out.expectedScreenshots = Number(expected);
    out.listedScreenshots = lines(path.join(results, "screenshot-list.txt"));
    out.pullFailures = lines(path.join(results, "screenshot-pull-failures.txt"));
    const dir = path.join(results, "screenshots");
    const names = fs.existsSync(dir) ? fs.readdirSync(dir).sort() : [];
    out.screenshotCount = names.length;
    out.screenshots = {};
    for (const name of names) {
      const bytes = fs.readFileSync(path.join(dir, name));
      out.screenshots[name] = { sha256: createHash("sha256").update(bytes).digest("hex"), bytes: bytes.length };
    }
    fs.writeFileSync(path.join(results, "evidence.json"), JSON.stringify(out, null, 2) + "\n");' \
    "$RESULTS" "$UI_EXPECTED_SCREENSHOTS"
}

# Prints a failure reason and exits non-zero unless exactly the expected PNGs were pulled, each is a
# PNG whose bytes still match its recorded sha256, the capture metadata names the same files for
# this run, and each pulled file has the size and sha256 the device recorded for it.
check_screenshots() {
  node -e 'const fs = require("node:fs"), path = require("node:path");
    const { createHash } = require("node:crypto");
    const [results, runId] = process.argv.slice(1);
    const fail = reason => { console.log(reason); process.exit(1); };
    let e; try { e = JSON.parse(fs.readFileSync(path.join(results, "evidence.json"), "utf8")); }
    catch { fail("screenshots-unavailable"); }
    if (e.screenshotList !== "present") fail("screenshots-unavailable");
    const pattern = /^[0-9]{2}-[a-z0-9-]+\.png$/;
    if (!Array.isArray(e.listedScreenshots) || e.listedScreenshots.some(name => !pattern.test(name)))
      fail("screenshot-name");
    if (!Array.isArray(e.pullFailures) || e.pullFailures.length) fail("screenshot-pull-failed");
    const dir = path.join(results, "screenshots");
    const names = fs.existsSync(dir) ? fs.readdirSync(dir).sort() : [];
    if (JSON.stringify(names) !== JSON.stringify([...e.listedScreenshots].sort())) fail("screenshots-unavailable");
    if (names.length !== e.expectedScreenshots || e.screenshotCount !== names.length) fail("screenshot-count");
    const signature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
    for (const name of names) {
      const bytes = fs.readFileSync(path.join(dir, name));
      if (bytes.length <= signature.length || !bytes.subarray(0, 8).equals(signature)) fail("screenshot-format");
      const recorded = (e.screenshots || {})[name] || {};
      if (recorded.sha256 !== createHash("sha256").update(bytes).digest("hex")) fail("screenshot-manifest");
    }
    if (Object.keys(e.screenshots || {}).sort().join("\n") !== names.join("\n")) fail("screenshot-manifest");
    if (e.captureMetadata !== "present") fail("capture-metadata");
    const m = JSON.parse(fs.readFileSync(path.join(results, "capture-metadata.json"), "utf8"));
    const captured = m && m.captures && typeof m.captures === "object" ? Object.keys(m.captures).sort() : [];
    if (m.runId !== runId || captured.join("\n") !== names.join("\n")) fail("capture-metadata");
    // The device recorded each PNG after writing it; a truncated or altered pull no longer matches.
    for (const name of names) {
      const bytes = fs.readFileSync(path.join(dir, name)), device = m.captures[name] || {};
      if (device.bytes !== bytes.length || device.sha256 !== createHash("sha256").update(bytes).digest("hex"))
        fail("screenshot-pull-mismatch");
    }' \
    "$RESULTS" "$RUN_ID"
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
    if (e[process.argv[2]] !== "present") fail("report");' "$RESULTS/evidence.json" "${1:-chatContext}"
}

quality_source_binding() {
  node --input-type=module - "$ROOT" "$QUALITY_FIXTURE" "$QUALITY_CANDIDATE_ROUND" <<'JS'
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
const [root, fixture, candidateRound] = process.argv.slice(2);
const read = name => readFileSync(join(root, name), 'utf8');
const grounding = read('shared/src/commonMain/kotlin/dev/edgecompanion/core/conversation/session/GroundedPrompt.kt');
const runtime = read('shared/src/androidMain/kotlin/dev/edgecompanion/core/inference/litert/LiteRtLlmEngine.kt');
const sampler = 'SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0)';
const ordinarySampler = /maxOutputToken = request\.maxTokens,\s*thinkingConfig = ThinkingConfig\(enableThinking = false\),\s*samplerConfig = ([^\n]+)\)\)/;
if (!grounding.includes('const val VERSION = "grounded-prompt-v1"') ||
    !grounding.includes('const val MAX_PASSAGES = 4') || runtime.match(ordinarySampler)?.[1] !== sampler) process.exit(1);
const hash = text => createHash('sha256').update(text).digest('hex');
// Untracked S6 files are bound by content, not only by the commit and tracked diff (S6Q-F9).
const bound = ['scripts/answer-quality.mjs',
  'androidApp/src/androidTest/kotlin/dev/edgecompanion/app/AnswerQualityTest.kt',
  'androidApp/src/androidTest/kotlin/dev/edgecompanion/app/support/AnswerQualityFixture.kt',
  `planning/fixtures/${fixture}/${fixture}.lock.json`];
if (candidateRound) {
  if (fixture !== 'answer-quality-dev-v1')
    bound.push('planning/fixtures/answer-quality-dev-v1/answer-quality-dev-v1.lock.json');
  const candidateDir = `planning/fixtures/grounded-prompt-candidates-${candidateRound}`;
  bound.push(`${candidateDir}/candidates.json`, `${candidateDir}/candidates.lock.json`);
  if (candidateRound === 'r2') {
    const sourceDir = 'planning/fixtures/grounded-prompt-candidates-r1';
    bound.push(`${sourceDir}/candidates.json`, `${sourceDir}/candidates.lock.json`);
  }
}
console.log(JSON.stringify({ promptVersion: 'grounded-prompt-v1', groundingSha256: hash(grounding),
  runtimeSourceSha256: hash(runtime), sampler,
  files: Object.fromEntries(bound.map(name => [name, hash(readFileSync(join(root, name)))])) }));
JS
}

# Read only the staged archive. Bound names are fixed or checked by loadFixture.
verify_quality_assets() {
  node --input-type=module - "$STAGED_TEST" "$QUALITY_DEV_SNAPSHOT" "$RESULTS" "$QUALITY_CANDIDATE_ROUND" "$QUALITY_CANDIDATE_LOCK" <<'JS'
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { devFixture, loadCandidates, loadFixture, sha256 } from './scripts/answer-quality.mjs';

const [apk, dev, results, round, candidateLock] = process.argv.slice(2);
const binding = JSON.parse(readFileSync(join(results, 'quality-source-binding.json'), 'utf8')).files;
const devPrefix = `assets/${devFixture}/`;
const devLockName = `${devFixture}.lock.json`;
const archive = (...args) => execFileSync('unzip', args, { timeout: 10_000 });
const listed = archive('-Z1', apk).toString('utf8').trimEnd().split('\n');
const expected = new Map();
const bind = (source, bytes) => {
  if (sha256(bytes) !== binding[source]) throw new Error(`Asset snapshot differs from source binding: ${source}`);
};

try {
  const fixture = loadFixture(dev, devFixture);
  for (const name of [devLockName, ...Object.keys(fixture.lock.files)]) {
    const bytes = readFileSync(join(dev, name));
    if (name === devLockName) {
      bind(`planning/fixtures/${devFixture}/${devLockName}`, bytes);
    } else if (sha256(bytes) !== fixture.lock.files[name]) {
      throw new Error(`Dev snapshot differs from fixture lock: ${name}`);
    }
    expected.set(devPrefix + name, bytes);
  }
  if (listed.some(name => name.startsWith(devPrefix) && !name.endsWith('/') && !expected.has(name)))
    throw new Error('Unexpected development asset');
  if (round) {
    const dir = join(results, 'candidates');
    const source = join(dir, 'source-r1');
    loadCandidates(dir, candidateLock, source);
    for (const name of ['candidates.json', 'candidates.lock.json']) {
      const bytes = readFileSync(join(dir, name));
      bind(`planning/fixtures/grounded-prompt-candidates-${round}/${name}`, bytes);
      expected.set('assets/' + name, bytes);
      if (round === 'r2') {
        const original = readFileSync(join(source, name));
        bind(`planning/fixtures/grounded-prompt-candidates-r1/${name}`, original);
        expected.set('assets/source-r1/' + name, original);
      }
    }
  }
  for (const [name, bytes] of expected) {
    if (listed.filter(entry => entry === name).length !== 1)
      throw new Error(`Expected one staged APK entry: ${name}`);
    if (!archive('-p', apk, name).equals(bytes)) throw new Error(`Staged APK asset differs: ${name}`);
  }
  console.log(`Verified ${expected.size} staged test APK assets against the bound source files.`);
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
JS
}

quality_capture_failure() {
  node --input-type=module - "$RESULTS/answer-quality.json" <<'JS'
import { readFileSync } from 'node:fs';
let report;
try { report = JSON.parse(readFileSync(process.argv[2], 'utf8')); } catch { process.exit(0); }
const cases = Array.isArray(report?.cases) ? report.cases : [];
const loads = Array.isArray(report?.loadDiagnostics) ? report.loadDiagnostics : [];
if (report?.runtimeError === 'THERMAL' || cases.some(row => row?.error === 'THERMAL') ||
    loads.some(load => [load?.state?.error, load?.state?.commandError,
      load?.engineLoad?.failureError].includes('THERMAL'))) console.log('thermal-stop');
else if (report?.preconditionFailure === 'thermal-cooldown-timeout') console.log('thermal-cooldown-timeout');
else if (report?.failureType === 'HarnessInvariant') console.log('quality-invariant');
JS
}

check_quality_report() {
  node --input-type=module - "$RESULTS" "$RUN_ID" "$QUALITY_SET" "$QUALITY_PASSES" "$QUALITY_LOCK" "$MANIFEST" "$QUALITY_CANDIDATE_LOCK" <<'JS'
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { checkRun, determinism, fixtureNameForSet, loadCandidates, loadFixture, schedule, sha256, validCandidateCooldowns } from './scripts/answer-quality.mjs';
const [dir, runId, set, passes, lockHash, manifest, candidateLock] = process.argv.slice(2);
const fail = reason => { console.log(reason); process.exit(1); };
try {
  const fixture = loadFixture(join(dir, 'fixture'), fixtureNameForSet(set));
  const bytes = readFileSync(join(dir, 'answer-quality.json'));
  const report = JSON.parse(bytes.toString('utf8'));
  if (fixture.lockSha256 !== lockHash || report.fixtureLockSha256 !== lockHash) fail('fixture-hash');
  if (report.runId !== runId || report.set !== set) fail('quality-run');
  if (report.passes !== Number(passes)) fail('quality-passes');
  if ((report.candidateLockSha256 ?? '') !== candidateLock) fail('candidate-lock');
  const candidateSnapshot = join(dir, 'candidates');
  const candidates = candidateLock ? loadCandidates(candidateSnapshot, candidateLock, join(candidateSnapshot, 'source-r1')) : null;
  if (report.cases?.length !== schedule(fixture.items, set, Number(passes), candidates).length) fail('quality-items');
  const sha = JSON.parse(readFileSync(manifest, 'utf8')).artifact.sha256;
  if (report.modelSha256 !== sha || report.automaticFallback !== false) fail('quality-model');
  checkRun(report, fixture, true, candidates);
  if (candidates && !validCandidateCooldowns(report.candidateCooldowns, report.engineLoads))
    fail('quality-cooldown-evidence');
  writeFileSync(join(dir, 'capture-summary.json'), JSON.stringify({
    runId, set, passes: Number(passes), items: report.cases.length, determinism: determinism(report),
    reportSha256: sha256(bytes),
    ...(candidateLock ? {candidateLockSha256: candidateLock} : {}),
  }, null, 2) + '\n');
} catch { fail('quality-report'); }
JS
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
  node --input-type=module - "$ROOT/scripts/qa-supervision.mjs" "$RESULTS/deadline-stop.json" "$DEADLINE" "${1:-deadline}" <<'JS'
import { writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
const { stopSupervisedQa } = await import(pathToFileURL(process.argv[2]).href);
const runId = process.env.QA_SUPERVISION_RUN_ID;
const result = stopSupervisedQa({ adb: process.env.ADB, serial: process.env.ANDROID_SERIAL, runId });
writeFileSync(process.argv[3], JSON.stringify({ reason: process.argv[5], runId, deadlineSeconds: Number(process.argv[4]),
  ...result, recordedAt: new Date().toISOString() }, null, 2) + '\n');
JS
}

finish() {
  local code=$?
  trap - EXIT
  set +e
  if [[ "$MODE" == quality && -n "$INSTR_PID" ]]; then
    stop_thermal_monitor || true
    publish_deadline_stop interrupted || true
    # Let the supervised test attempt cleanup before pulling retained items.
    for ((i=0; i<STOP_GRACE; i++)); do
      kill -0 "$INSTR_PID" 2>/dev/null || break
      sleep 1
    done
  fi
  stop_owned "$INSTR_PID"
  stop_thermal_monitor || true
  case "$STAGE" in
    instrumentation|deadline|validation|complete)
      collect_evidence
      local thermal_failure=""
      if [[ "$MODE" == quality ]]; then thermal_failure="$(quality_capture_failure)"; fi
      if [[ -e "$RESULTS/thermal/safety-stop.json" || "$thermal_failure" == thermal-stop ||
        "$FINAL_THERMAL_REASON" == thermal-stop ]]; then
        REASON=thermal-stop
        STATUS=failed
      fi
      if [[ "$MODE" == quality && ( "$REASON" == thermal-stop || "$REASON" == thermal-final-unavailable ) &&
        -f "$RESULTS/answer-quality.json" && ! -f "$RESULTS/precheck.json" ]]; then
        node scripts/answer-quality.mjs precheck "$RESULTS" || true
      fi
      ;;
  esac
  if [[ "$STATUS" != passed || "$code" != 0 ]]; then
    STATUS=failed
    if [[ "$code" == 0 ]]; then code=1; fi
    if [[ -z "$REASON" ]]; then REASON="$STAGE"; fi
  fi
  RESULT_MODE="$MODE" RESULT_PROFILE="$PROFILE" RESULT_REPEAT="$REPEAT" RESULT_RUN_ID="$RUN_ID" RESULT_STATUS="$STATUS" \
    RESULT_REASON="$REASON" RESULT_STAGE="$STAGE" RESULT_EXIT="$code" RESULT_DEADLINE="$DEADLINE" \
    RESULT_DEADLINE_FORMULA="$DEADLINE_FORMULA" RESULT_DEADLINE_OVERRIDE="$DEADLINE_OVERRIDE" \
    RESULT_QUALITY_SET="$QUALITY_SET" RESULT_QUALITY_PASSES="$QUALITY_PASSES" RESULT_QUALITY_LOCK="$QUALITY_LOCK" \
    RESULT_CANDIDATE_ROUND="$QUALITY_CANDIDATE_ROUND" RESULT_CANDIDATE_LOCK="$QUALITY_CANDIDATE_LOCK" \
    RESULT_ASSETS_ONLY="$QUALITY_ASSETS_ONLY" \
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
  ...(e.RESULT_MODE === 'quality' ? { qualitySet: e.RESULT_QUALITY_SET,
    qualityPasses: Number(e.RESULT_QUALITY_PASSES), fixtureLockSha256: e.RESULT_QUALITY_LOCK || null } : {}),
  ...(e.RESULT_ASSETS_ONLY === 'true' ? {assetsOnly: true} : {}),
  ...(e.RESULT_CANDIDATE_ROUND ? {candidateRound: e.RESULT_CANDIDATE_ROUND, candidateLockSha256: e.RESULT_CANDIDATE_LOCK} : {}),
  recordedAt: new Date().toISOString() }, null, 2) + '\n');
JS
  if [[ "$QUALITY_ASSETS_ONLY" == true ]]; then
    printf 'Asset check only, no device run. %s at %s (exit %s). Reports: %s\n' "$STATUS" "$STAGE" "$code" "$RESULTS"
  else
    printf 'Device-prep %s %s at %s (exit %s). Reports: %s\n' "$MODE" "$STATUS" "$STAGE" "$code" "$RESULTS"
  fi
  exit "$code"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

printf 'Device-prep reports: %s\n' "$RESULTS"
if [[ "$MODE" == quality ]]; then
  stage fixture
  cp -R "$ROOT/planning/fixtures/$QUALITY_FIXTURE" "$RESULTS/fixture" || fail fixture
  QUALITY_LOCK="$(node --input-type=module -e \
    'import { loadFixture } from "./scripts/answer-quality.mjs"; console.log(loadFixture(process.argv[1], process.argv[2]).lockSha256)' \
    "$RESULTS/fixture" "$QUALITY_FIXTURE")" || fail fixture-hash
  if [[ "$QUALITY_SET" == dev ]]; then
    QUALITY_DEV_SNAPSHOT="$RESULTS/fixture"
  elif [[ -n "$QUALITY_CANDIDATE_LOCK" ]]; then
    QUALITY_DEV_SNAPSHOT="$RESULTS/dev-fixture"
    cp -R "$ROOT/planning/fixtures/answer-quality-dev-v1" "$QUALITY_DEV_SNAPSHOT" || fail fixture
  fi
  if [[ -n "$QUALITY_CANDIDATE_LOCK" ]]; then
    mkdir -m 700 "$RESULTS/candidates" || fail candidate-lock
    cp "$QUALITY_CANDIDATE_DIR/candidates.json" "$QUALITY_CANDIDATE_DIR/candidates.lock.json" "$RESULTS/candidates/" ||
      fail candidate-lock
    if [[ "$QUALITY_CANDIDATE_ROUND" == r2 ]]; then
      source_round="$ROOT/planning/fixtures/grounded-prompt-candidates-r1"
      mkdir -m 700 "$RESULTS/candidates/source-r1" || fail candidate-lock
      cp "$source_round/candidates.json" "$source_round/candidates.lock.json" "$RESULTS/candidates/source-r1/" ||
        fail candidate-lock
    fi
    node --input-type=module -e '
      import { join } from "node:path";
      import { loadCandidates } from "./scripts/answer-quality.mjs";
      loadCandidates(process.argv[1], process.argv[2], join(process.argv[1], "source-r1"));
    ' "$RESULTS/candidates" "$QUALITY_CANDIDATE_LOCK" || fail candidate-lock
  fi
  quality_source_binding > "$RESULTS/quality-source-binding.json" || fail quality-source-binding
fi
stage build
if [[ "$MODE" == measure || "$MODE" == quality ]]; then
  args=(--offline -PtestBuildType=secureQa "-PchatContextTokens=$PROFILE" -PandroidPressureMemoryTest=false)
  if [[ -n "$QUALITY_CANDIDATE_ROUND" ]]; then args+=("-PanswerQualityCandidateRound=$QUALITY_CANDIDATE_ROUND"); fi
  "$GRADLE" "${args[@]}" \
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
if [[ "$MODE" == quality ]]; then
  quality_source_binding | cmp - "$RESULTS/quality-source-binding.json" || fail quality-source-binding
fi
if [[ -n "$QUALITY_DEV_SNAPSHOT" ]]; then
  stage quality-assets
  verify_staged || fail apk-integrity
  verify_quality_assets > "$RESULTS/quality-assets.txt" 2>&1 || fail quality-assets
  if [[ "$QUALITY_ASSETS_ONLY" == true ]]; then
    STATUS=passed
    exit 0
  fi
fi

stage install-app
verify_staged || fail apk-integrity
device install -r "$STAGED_APP" 2>&1 | tee "$RESULTS/install-app.txt" || fail install-app
grep -q '^Success' "$RESULTS/install-app.txt" || fail install-app
stage install-tests
verify_staged || fail apk-integrity
device install -r "$STAGED_TEST" 2>&1 | tee "$RESULTS/install-tests.txt" || fail install-tests
grep -q '^Success' "$RESULTS/install-tests.txt" || fail install-tests

if [[ "$MODE" == measure || "$MODE" == quality ]]; then
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
elif [[ "$MODE" == ui ]]; then
  "$ADB" -s "$ANDROID_SERIAL" shell am instrument -w -r -e class "$UI_CLASSES" \
    -e passagesCaptureRunId "$RUN_ID" "$RUNNER" > "$RESULTS/instrumentation.txt" 2>&1 &
elif [[ "$MODE" == quality ]]; then
  quality_classes=dev.edgecompanion.app.AnswerQualityTest
  quality_ok='OK (1 test)'
  if [[ -n "$QUALITY_CANDIDATE_LOCK" ]]; then
    quality_classes="dev.edgecompanion.app.support.AnswerQualityCandidateTest,$quality_classes"
    quality_ok='OK (2 tests)'
  fi
  args=(-e class "$quality_classes"
    -e answerQuality true -e supervisedThermal true -e supervisionRunId "$RUN_ID"
    -e answerQualitySet "$QUALITY_SET" -e answerQualityPasses "$QUALITY_PASSES" -e answerQualityLockSha256 "$QUALITY_LOCK")
  if [[ -n "$QUALITY_CANDIDATE_LOCK" ]]; then args+=(-e candidates "$QUALITY_CANDIDATE_LOCK"); fi
  "$ADB" -s "$ANDROID_SERIAL" shell am instrument -w -r "${args[@]}" "$RUNNER" > "$RESULTS/instrumentation.txt" 2>&1 &
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
  if [[ "$MODE" == measure || "$MODE" == quality ]]; then
    stop_thermal_monitor || true  # First, so no heartbeat follows the stop.
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
if [[ "$MODE" == measure || "$MODE" == quality ]]; then
  CAPTURE_FAILURE=""
  if [[ "$MODE" == quality ]]; then
    CAPTURE_FAILURE="$(quality_capture_failure)" || fail quality-report
  fi
  [[ ! -e "$THERMAL_REPORT_DIR/safety-stop.json" && "$CAPTURE_FAILURE" != thermal-stop &&
    "$FINAL_THERMAL_REASON" != thermal-stop ]] || fail thermal-stop
  [[ -z "$FINAL_THERMAL_REASON" ]] || fail "$FINAL_THERMAL_REASON"
  [[ -z "$CAPTURE_FAILURE" ]] || fail "$CAPTURE_FAILURE"
fi
[[ "$INSTR_CODE" == 0 ]] || fail instrumentation
if grep -Eq "$FAILURE_PATTERN" "$RESULTS/instrumentation.txt"; then fail instrumentation; fi
if [[ "$MODE" == measure ]]; then
  grep -Eq '^OK \(1 test\)$' "$RESULTS/instrumentation.txt" || fail instrumentation
  GATE_REASON="$(check_evidence)" || fail "${GATE_REASON:-evidence-unavailable}"
  node scripts/verify-qa-supervision.mjs "$RESULTS/supervision.json" "$RUN_ID" || fail supervision
  [[ "$MONITOR_CLEAN" == true ]] || fail thermal-monitor
  GATE_REASON="$(check_report)" || fail "${GATE_REASON:-report}"
  write_summary || fail summary
elif [[ "$MODE" == ui ]]; then
  grep -Eq "^OK \($UI_EXPECTED_TESTS tests\)$" "$RESULTS/instrumentation.txt" || fail instrumentation
  GATE_REASON="$(check_screenshots)" || fail "${GATE_REASON:-screenshots}"
elif [[ "$MODE" == quality ]]; then
  grep -Fxq "$quality_ok" "$RESULTS/instrumentation.txt" || fail instrumentation
  GATE_REASON="$(check_evidence answerQuality)" || fail "${GATE_REASON:-evidence-unavailable}"
  node scripts/verify-qa-supervision.mjs "$RESULTS/supervision.json" "$RUN_ID" || fail supervision
  [[ "$MONITOR_CLEAN" == true ]] || fail thermal-monitor
  GATE_REASON="$(check_quality_report)" || fail "${GATE_REASON:-quality-report}"
  node scripts/answer-quality.mjs precheck "$RESULTS" || fail quality-precheck
else
  # PassageSqlCipherTest (3) + GroundingSqlCipherTest (6); a skipped opt-in would not count as passing.
  grep -Eq '^OK \(9 tests\)$' "$RESULTS/instrumentation.txt" || fail instrumentation
fi
stage complete
REASON=""
if [[ "$MODE" == measure || "$MODE" == quality ]]; then
  [[ ! -e "$THERMAL_REPORT_DIR/safety-stop.json" ]] || fail thermal-stop
fi
STATUS=passed
