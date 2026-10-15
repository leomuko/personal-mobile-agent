#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ $# -gt 1 || ( $# -eq 1 && "$1" != --android-pressure ) ]]; then
  printf 'Usage: bash scripts/verify-memory-reliability.sh [--android-pressure]\n' >&2
  exit 2
fi
if [[ $# -eq 1 ]]; then export MEMORY_PREFLIGHT_MODE=android-pressure; fi
export MEMORY_RELIABILITY=true
exec bash "$ROOT/scripts/verify-conversation-native.sh"
