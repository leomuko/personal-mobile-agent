#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the authorized synthetic-data device}"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
PACKAGE=dev.edgecompanion.app.secureqa
RESULTS="benchmarks/results/chat-restart-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$RESULTS"
./gradlew :androidApp:assembleSecureQa :androidApp:assembleSecureQaAndroidTest -PtestBuildType=secureQa --console=plain
"$ADB" -s "$ANDROID_SERIAL" install -r androidApp/build/outputs/apk/secureQa/androidApp-secureQa.apk
"$ADB" -s "$ANDROID_SERIAL" install -r androidApp/build/outputs/apk/androidTest/secureQa/androidApp-secureQa-androidTest.apk
for PHASE in seed verify; do
  "$ADB" -s "$ANDROID_SERIAL" shell am force-stop "$PACKAGE"
  "$ADB" -s "$ANDROID_SERIAL" shell am instrument -w -r -e class dev.edgecompanion.app.ConversationRestartTest \
    -e chatRestartPhase "$PHASE" "$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" | tee "$RESULTS/$PHASE.txt"
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED|INSTRUMENTATION_STATUS_CODE: -[1-4]' "$RESULTS/$PHASE.txt"; then exit 1; fi
  grep -Eq 'OK \([0-9]+ tests?\)' "$RESULTS/$PHASE.txt"
done
printf 'Conversation restart reports: %s\n' "$RESULTS"
