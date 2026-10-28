#!/usr/bin/env bash
# Host-only campaign. No device selectors, downloads, fallback or prior-proof reuse.
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
if [[ $# -ne 2 ]]; then
  printf 'Usage: %s PREPARED_WORKSPACE NEW_REPORT_DIRECTORY\n' "$0" >&2
  exit 64
fi
WORKSPACE="$(cd -- "$1" && pwd -P)"
REPORT="$2"
if [[ -e "$REPORT" ]]; then
  printf 'Refusing to replace retained report: %s\n' "$REPORT" >&2
  exit 1
fi
PYTHON="${GRAMMAR_PYTHON:-$ROOT/build/constrained-decoding-reference/bin/python}"
CARGO="${GRAMMAR_CARGO:-$ROOT/build/llguidance-mask-replay/cargo-home/bin/cargo}"
[[ -x "$PYTHON" && -x "$CARGO" ]] || { printf 'Pinned Python/Rust toolchain is required.\n' >&2; exit 1; }
mkdir -p -- "$REPORT"
REPORT="$(cd -- "$REPORT" && pwd -P)"
trap 'result=$?; printf "%s\n" "$result" > "$REPORT/exit-status.txt"' EXIT
export PYTHONPYCACHEPREFIX="${PYTHONPYCACHEPREFIX:-/tmp/edge-mask-pycache}"
export CARGO_HOME="${CARGO_HOME:-$ROOT/build/llguidance-mask-replay/cargo-home}"
export RUSTUP_HOME="${RUSTUP_HOME:-$ROOT/build/llguidance-mask-replay/rustup-home}"
export PATH="$(dirname -- "$CARGO"):$PATH"
cd -- "$ROOT"
[[ "$("$CARGO" --version)" == "cargo 1.87.0 "* ]] || { printf 'Rust 1.87.0 is required.\n' >&2; exit 1; }
[[ "$(rustc --version)" == "rustc 1.87.0 "* ]] || { printf 'Rust 1.87.0 is required.\n' >&2; exit 1; }
"$CARGO" --version > "$REPORT/toolchain.txt"
rustc --version >> "$REPORT/toolchain.txt"
"$CARGO" fmt --manifest-path "$WORKSPACE/workspace/Cargo.toml" -- --check > "$REPORT/fmt.log" 2>&1
"$CARGO" build --locked --offline --release --manifest-path "$WORKSPACE/workspace/Cargo.toml" > "$REPORT/build.log" 2>&1
"$CARGO" clippy --locked --offline --no-deps --manifest-path "$WORKSPACE/workspace/Cargo.toml" -- -D warnings > "$REPORT/clippy.log" 2>&1
"$CARGO" test --locked --offline --manifest-path "$WORKSPACE/workspace/Cargo.toml" > "$REPORT/rust-tests.log" 2>&1
"$PYTHON" -W error::ResourceWarning -m unittest discover -s "$ROOT/scripts/tests" -p 'test_*.py' -v > "$REPORT/python-tests.log" 2>&1
./gradlew :shared:jvmTest :shared:testAndroidHostTest --offline --rerun-tasks --console=plain > "$REPORT/kotlin-tests.log" 2>&1
"$PYTHON" "$ROOT/scripts/diagnostics/mask_replay/grammar_progress.py" --workspace "$WORKSPACE" --out "$REPORT/replay" > "$REPORT/replay.log" 2>&1
