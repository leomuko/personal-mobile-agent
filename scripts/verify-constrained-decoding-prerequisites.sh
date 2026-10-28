#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENV="${CONSTRAINED_REFERENCE_VENV:-$ROOT/build/constrained-decoding-reference}"
PROOF="$ROOT/build/constrained-decoding/prerequisites.json"
# A failed setup or verifier must not leave an older successful proof admissible.
rm -f "$PROOF"
if [[ ! -x "$VENV/bin/python" ]]; then
  "${PYTHON:-python3}" -m venv "$VENV"
fi
"$VENV/bin/python" -m pip install --disable-pip-version-check --require-hashes --only-binary=:all: \
  -r "$ROOT/scripts/requirements/constrained-decoding.txt"
mkdir -p "$ROOT/build/output-contract-audit"
for name in sentencepiece_tokenizer.cc buffered_streaming_detokenizer.cc; do
  destination="$ROOT/build/output-contract-audit/$name"
  if [[ ! -f "$destination" ]]; then
    curl --fail --silent --show-error --location \
      "https://raw.githubusercontent.com/google-ai-edge/LiteRT-LM/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/support/tokenizer/$name" \
      --output "$destination"
  fi
done
"$VENV/bin/python" -O -m unittest discover -s "$ROOT/scripts/tests" -p test_constrained_decoding.py
exec "$VENV/bin/python" -O "$ROOT/scripts/verify-constrained-decoding.py" --root "$ROOT" --output "$PROOF" "$@"
