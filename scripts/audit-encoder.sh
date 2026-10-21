#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENV="${ENCODER_AUDIT_VENV:-$ROOT/build/encoder-verification}"
PYTHON="${PYTHON:-python3}"
if [[ ! -x "$VENV/bin/python" ]]; then
  "$PYTHON" -m venv "$VENV"
fi
"$VENV/bin/python" -c 'import sys; sys.exit(0 if (3, 9) <= sys.version_info[:2] <= (3, 12) else "Encoder audit requires Python 3.9-3.12 for the pinned NumPy wheel")'
"$VENV/bin/python" -m pip install --disable-pip-version-check -r "$ROOT/scripts/requirements/encoder-audit.txt" >&2
exec "$VENV/bin/python" "$ROOT/scripts/audit-encoder.py" "$@"
