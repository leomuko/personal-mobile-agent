#!/usr/bin/env bash
set -euo pipefail
: "${ADB:?Set ADB}"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL}"
: "${MEMORY_POLICY_REPORT:?Set MEMORY_POLICY_REPORT}"
FOLDER="$(dirname "$MEMORY_POLICY_REPORT")"
mkdir -p "$FOLDER"
MEMORY_RUN_ID="${MEMORY_RUN_ID:-$(node -e 'process.stdout.write(require("node:crypto").randomUUID())')}"
[[ "$MEMORY_RUN_ID" =~ ^[A-Za-z0-9_-]{1,80}$ ]]
"$ADB" -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class dev.edgecompanion.app.MemoryPolicyProbeTest,dev.edgecompanion.app.MemoryExitProbeTest \
  -e memoryRunId "$MEMORY_RUN_ID" -e memoryExitPhase before \
  dev.edgecompanion.app.secureqa.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$FOLDER/policy-probe.txt"
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED|INSTRUMENTATION_STATUS_CODE: -[1-4]' "$FOLDER/policy-probe.txt"; then exit 1; fi
grep -Eq '^OK \(2 tests\)$' "$FOLDER/policy-probe.txt"
"$ADB" -s "$ANDROID_SERIAL" exec-out run-as dev.edgecompanion.app.secureqa cat no_backup/memory-policy.json > "$MEMORY_POLICY_REPORT"
"$ADB" -s "$ANDROID_SERIAL" exec-out run-as dev.edgecompanion.app.secureqa cat no_backup/memory-baseline.json > "$FOLDER/memory-baseline.json"
"$ADB" -s "$ANDROID_SERIAL" exec-out run-as dev.edgecompanion.app.secureqa cat no_backup/memory-exits.json > "$FOLDER/exits-before.json"
