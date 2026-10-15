#!/usr/bin/env bash
set -euo pipefail
: "${ADB:?Set ADB}"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL}"
: "${MEMORY_RUN_ID:?Set MEMORY_RUN_ID}"
[[ "$MEMORY_RUN_ID" =~ ^[A-Za-z0-9_-]{1,80}$ ]]
FOLDER="${1:?Set results folder}"
PACKAGE=dev.edgecompanion.app.secureqa
mkdir -p "$FOLDER"
# Never leave a previous host export available when a new final probe fails.
rm -f "$FOLDER/exits-after.json" "$FOLDER/exits-after.tmp"
trap 'rm -f "$FOLDER/exits-after.tmp"' EXIT
if ! "$ADB" -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class dev.edgecompanion.app.MemoryExitProbeTest \
  -e memoryRunId "$MEMORY_RUN_ID" -e memoryExitPhase after \
  "$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" > "$FOLDER/exit-probe.txt" 2>&1; then
  printf 'Final exit probe command failed; no exit evidence exported.\n' >&2
  exit 1
fi
# am instrument can return zero even when the instrumentation itself failed.
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED|INSTRUMENTATION_STATUS_CODE: -[1-4]' "$FOLDER/exit-probe.txt" ||
  ! grep -Eq '^OK \(1 test\)[[:space:]]*$' "$FOLDER/exit-probe.txt"; then
  printf 'Final exit probe did not pass; no exit evidence exported.\n' >&2
  exit 1
fi
"$ADB" -s "$ANDROID_SERIAL" exec-out run-as "$PACKAGE" cat no_backup/memory-exits.json \
  > "$FOLDER/exits-after.tmp" 2> "$FOLDER/exit-export.txt"
node --input-type=module - "$FOLDER/exits-after.tmp" "$MEMORY_RUN_ID" <<'JS'
import { readFileSync } from 'node:fs';
const evidence = JSON.parse(readFileSync(process.argv[2], 'utf8'));
if (evidence.runId !== process.argv[3] || evidence.phase !== 'after' ||
    !Number.isSafeInteger(evidence.capturedAtMs) || evidence.capturedAtMs <= 0 ||
    !Array.isArray(evidence.entries) || typeof evidence.lowMemoryKillReportSupported !== 'boolean') {
  console.error('Final exit probe evidence is missing or belongs to a different run/phase.');
  process.exit(1);
}
JS
mv "$FOLDER/exits-after.tmp" "$FOLDER/exits-after.json"
