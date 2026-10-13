#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the designated emulator or authorized S24}"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
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
"$ADB" -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class dev.edgecompanion.app.ModelRuntimeTest -e nativeModel true \
  -e modelBackend "$BACKEND" -e modelRepetitions "${MODEL_REPETITIONS:-1}" \
  -e modelSustainedMinutes "${MODEL_SUSTAINED_MINUTES:-0}" -e supervisedThermal "${SUPERVISED_THERMAL:-false}" \
  "$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" | tee "$RESULTS/instrumentation.txt"
"$ADB" -s "$ANDROID_SERIAL" exec-out run-as "$PACKAGE" cat no_backup/model-benchmark.json > "$RESULTS/runtime.json"
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED|INSTRUMENTATION_STATUS_CODE: -[1-4]' "$RESULTS/instrumentation.txt"; then
  exit 1
fi
grep -Eq 'OK \([0-9]+ tests?\)' "$RESULTS/instrumentation.txt"
printf 'Native smoke reports: %s\n' "$RESULTS"
