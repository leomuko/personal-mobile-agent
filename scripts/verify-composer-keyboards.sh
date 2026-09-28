#!/usr/bin/env bash
set -euo pipefail

# Run after verify-secure-qa.sh has installed the isolated application/test APKs.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the designated synthetic-data emulator serial}"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
PACKAGE=dev.edgecompanion.app.secureqa
if [[ "$ANDROID_SERIAL" != emulator-* ]]; then
  printf '%s\n' 'Keyboard-setting mutation is restricted to the designated emulator.' >&2
  exit 1
fi
original="$("$ADB" -s "$ANDROID_SERIAL" shell settings get secure show_ime_with_hard_keyboard | tr -d '\r')"
cleanup() {
  if [[ "$original" == null ]]; then
    "$ADB" -s "$ANDROID_SERIAL" shell settings delete secure show_ime_with_hard_keyboard >/dev/null
  else
    "$ADB" -s "$ANDROID_SERIAL" shell settings put secure show_ime_with_hard_keyboard "$original"
  fi
  "$ADB" -s "$ANDROID_SERIAL" shell am force-stop "$PACKAGE"
}
trap cleanup EXIT
mkdir -p androidApp/build/reports
for mode in 0 1; do
  "$ADB" -s "$ANDROID_SERIAL" shell settings put secure show_ime_with_hard_keyboard "$mode"
  for repeat in 1 2; do
    "$ADB" -s "$ANDROID_SERIAL" shell am force-stop "$PACKAGE"
    report="androidApp/build/reports/composer-keyboard-${mode}-${repeat}.txt"
    expect=false
    if [[ "$mode" == 1 ]]; then expect=true; fi
    "$ADB" -s "$ANDROID_SERIAL" shell am instrument -w -r -e expectIme "$expect" \
      -e class 'dev.edgecompanion.app.CaptureFlowTest#keyboardExpansionKeepsFocusAndCollapseKeepsDraft,dev.edgecompanion.app.ComposerLayoutTest#focusWithoutSoftwareKeyboardExpandsAndBackPreservesDraftInBothHosts' \
      "$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" | tee "$report"
    if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED|INSTRUMENTATION_STATUS_CODE: -[1-4]' "$report"; then exit 1; fi
    grep -Eq 'OK \(2 tests\)' "$report"
  done
done
