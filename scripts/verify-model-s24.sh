#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the connected S24}"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
MODEL="$("$ADB" -s "$ANDROID_SERIAL" shell getprop ro.product.model | tr -d '\r')"
[[ "$MODEL" == SM-S928B ]] || { echo 'Expected the designated S24 Ultra' >&2; exit 2; }
THERMAL_REPORT_DIR="benchmarks/results/s24-thermal-$(date -u +%Y%m%dT%H%M%SZ)"
export THERMAL_REPORT_DIR
mkdir -p "$ROOT/build"
node scripts/monitor-model-thermal.mjs > "$ROOT/build/s24-thermal-monitor.log" 2>&1 &
MONITOR_PID=$!
cleanup() {
  kill "$MONITOR_PID" 2>/dev/null || true
  wait "$MONITOR_PID" 2>/dev/null || true
}
trap cleanup EXIT
set +e
bash scripts/verify-model-runtime.sh
RESULT=$?
set -e
if [[ -f "$THERMAL_REPORT_DIR/safety-stop.json" ]]; then
  echo "Thermal safety stop: $THERMAL_REPORT_DIR/safety-stop.json" >&2
  exit 2
fi
echo "S24 thermal samples: $THERMAL_REPORT_DIR/samples.csv"
exit "$RESULT"
