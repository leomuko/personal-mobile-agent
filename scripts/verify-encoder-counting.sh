#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ $# -ne 2 ]]; then echo "Usage: $0 /absolute/path/to/pinned-use.tflite /absolute/path/to/sdk-transport.tsv" >&2; exit 2; fi
# Invalidate before setup, corpus preparation or Gradle can fail under set -e.
rm -f -- "$ROOT/build/encoder-counting/counting-proof.json"
VENV="${ENCODER_AUDIT_VENV:-$ROOT/build/encoder-verification}"
PYTHON="${PYTHON:-python3}"
if [[ ! -x "$VENV/bin/python" ]]; then "$PYTHON" -m venv "$VENV"; fi
"$VENV/bin/python" -m pip install --disable-pip-version-check -r "$ROOT/scripts/requirements/encoder-audit.txt" >&2
"$VENV/bin/python" "$ROOT/scripts/setup-encoder-reference.py"
"$VENV/bin/python" "$ROOT/scripts/prepare-encoder-counting.py" --model "$1" \
  --reference "$ROOT/build/encoder-counting-toolchain/oracle" --output "$ROOT/build/encoder-counting" --transport-snapshot "$2"
cd "$ROOT"
P2_ENCODER_COUNTING_DIR="$ROOT/build/encoder-counting" ./gradlew :shared:jvmTest \
  --tests '*UseTokenizerReferenceTest*' --rerun-tasks --console=plain
"$VENV/bin/python" "$ROOT/scripts/record-encoder-counting.py"
echo "Host counting differential passed. Android admission and native handoff acceptance remain separate."
