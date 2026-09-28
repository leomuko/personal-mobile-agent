#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
: "${ANDROID_SERIAL:?Set the designated synthetic-data emulator serial}"
[[ "$ANDROID_SERIAL" == emulator-* ]] || exit 1
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PACKAGE=dev.edgecompanion.app.secureqa
REPORT=androidApp/build/reports/attachment-process-restart.txt
mkdir -p "$(dirname "$REPORT")"
run_phase() {
    "$ADB" -s "$ANDROID_SERIAL" shell am instrument -w -r \
        -e class dev.edgecompanion.app.AttachmentRestartTest \
        -e restartPhase "$1" "$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner"
}
run_phase seed | tee "$REPORT"
grep -q 'OK (1 test)' "$REPORT"
"$ADB" -s "$ANDROID_SERIAL" shell am force-stop "$PACKAGE"
run_phase verify | tee -a "$REPORT"
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[1-4]' "$REPORT"; then exit 1; fi
[[ "$(grep -c 'OK (1 test)' "$REPORT")" == 2 ]]
"$ADB" -s "$ANDROID_SERIAL" shell am force-stop "$PACKAGE"
