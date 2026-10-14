#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENV="${CHAT_BUDGET_VENV:-$ROOT/build/token-verification}"
PYTHON="${PYTHON:-python3}"
if [[ ! -x "$VENV/bin/python" ]]; then
  "$PYTHON" -m venv "$VENV"
fi
"$VENV/bin/python" -m pip install --disable-pip-version-check -r "$ROOT/scripts/requirements/chat-budget.txt"
exec "$VENV/bin/python" "$ROOT/scripts/verify-chat-budget.py" "$@"
