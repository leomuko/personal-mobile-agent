#!/usr/bin/env bash
set -euo pipefail

# Only the isolated QA package is installed or has its permissions changed.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the designated synthetic-data emulator serial}"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
PACKAGE=dev.edgecompanion.app.secureqa
REPORT=androidApp/build/reports/secure-qa-instrumentation.txt

if [[ "$ANDROID_SERIAL" != emulator-* ]]; then
  printf '%s\n' 'This permission-mutating script is restricted to a designated emulator.' >&2
  exit 1
fi

./gradlew :androidApp:assembleSecureQa :androidApp:assembleSecureQaAndroidTest -PtestBuildType=secureQa --console=plain
"$ADB" -s "$ANDROID_SERIAL" install -r androidApp/build/outputs/apk/secureQa/androidApp-secureQa.apk
"$ADB" -s "$ANDROID_SERIAL" install -r androidApp/build/outputs/apk/androidTest/secureQa/androidApp-secureQa-androidTest.apk
"$ADB" -s "$ANDROID_SERIAL" shell am force-stop "$PACKAGE"
"$ADB" -s "$ANDROID_SERIAL" shell pm revoke "$PACKAGE" android.permission.POST_NOTIFICATIONS
"$ADB" -s "$ANDROID_SERIAL" shell pm revoke "$PACKAGE" android.permission.CAMERA
"$ADB" -s "$ANDROID_SERIAL" shell pm clear-permission-flags "$PACKAGE" android.permission.CAMERA user-set user-fixed

cleanup() {
  "$ADB" -s "$ANDROID_SERIAL" shell am force-stop "$PACKAGE"
  "$ADB" -s "$ANDROID_SERIAL" shell appops set "$PACKAGE" SYSTEM_ALERT_WINDOW default
}
trap cleanup EXIT
mkdir -p "$(dirname "$REPORT")"
"$ADB" -s "$ANDROID_SERIAL" shell am instrument -w -r -e notificationDenied true -e cameraPermissionDenied true \
  "$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" | tee "$REPORT"

# am instrument can exit zero even when assertions or the instrumentation process fail.
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED|INSTRUMENTATION_STATUS_CODE: -[1-4]' "$REPORT"; then
  exit 1
fi
grep -Eq 'OK \([0-9]+ tests?\)' "$REPORT"
