#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the connected S24}"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
MODEL="$("$ADB" -s "$ANDROID_SERIAL" shell getprop ro.product.model | tr -d '\r')"
[[ "$MODEL" == SM-S928B ]] || { echo 'Expected the designated S24 Ultra' >&2; exit 2; }
export SUPERVISED_THERMAL=true
exec bash scripts/verify-model-runtime.sh
