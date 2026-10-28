# Escaped-Unicode boundary diagnosis (host only)

This is a separately versioned investigation of the retained `unicode-escaped`
acceptance failure. It does not patch the grammar, change a witness/schema,
normalize model output or propose device execution.

`byte_probe.rs` uses safe Rust and the exact bounded-string `JsonQuote` AST from
pinned LLGuidance 1.3.0 / derivre 0.3.8. It calls the regex engine directly on
JSON bytes, without a tokenizer or the C API. The companion workspace reuses the
reviewed diagnostic's dependencies, Cargo.lock, features and patches. No new
Rust dependency or application dependency is introduced.

`investigate.py` verifies the frozen diagnostic cases, the retained failed
candidate, the original source workspace, the cached derivre archive and every
materialized crate file. It then compares the tokenizer-free probe with the
existing reviewed C-API mask broker, using the same tokenizer, EOS and raw-piece
vocabulary. Decoded vocabulary is comparison-only. All histories are synthetic
reconstructions, not captured S24 sampled histories.

The ASCII-piece alternate must round-trip exactly. An unavailable piece is
reported as `tokenization-unavailable`, not a grammar rejection, pass or invented
token. In particular the full witness contains a space with no literal ` ` piece.
The original witness is still tested canonically, without rewriting that space.

## Reproduction

Prerequisites are the existing reviewed workspace
`build/grammar-progress-v1-final`, the isolated Rust 1.87.0 toolchain/cache and the
pinned Python environment described in
[the existing diagnostic README](../mask_replay/README.md). Nothing downloads or
installs as part of these commands. The preparation step refuses an existing
destination; select a fresh path when repeating and retain earlier attempts.

From the repository root:

```sh
export CARGO_HOME="$PWD/build/llguidance-mask-replay/cargo-home"
export RUSTUP_HOME="$PWD/build/llguidance-mask-replay/rustup-home"
export PATH="$CARGO_HOME/bin:$PATH"
export PYTHONPYCACHEPREFIX=/tmp/edge-mask-pycache

build/constrained-decoding-reference/bin/python \
  scripts/diagnostics/unicode_boundary/investigate.py prepare
cargo build --release --locked --offline \
  --manifest-path build/unicode-boundary-v1/prepared-final/byte-workspace/Cargo.toml \
  --bin unicode-byte-probe
cargo test --release --locked --offline \
  --manifest-path build/unicode-boundary-v1/prepared-final/byte-workspace/Cargo.toml \
  --bin unicode-byte-probe
cargo clippy --release --locked --offline --no-deps \
  --manifest-path build/unicode-boundary-v1/prepared-final/byte-workspace/Cargo.toml \
  --bin unicode-byte-probe -- -D warnings
rustfmt --check --edition 2021 scripts/diagnostics/unicode_boundary/byte_probe.rs
build/constrained-decoding-reference/bin/python -W error::ResourceWarning \
  -m unittest discover -s scripts/tests -p 'test_*.py' -v
build/constrained-decoding-reference/bin/python \
  scripts/diagnostics/unicode_boundary/investigate.py run \
  --out build/unicode-boundary-v1/NEW-COLLECTION
```

The 18 strings are valid according to the independent JSON/schema reference.
`sourcePredictedByteAcceptance` records a diagnosis prediction, not changed
candidate qualification criteria. `diagnosis-collected` means the investigation
ran; it is not an acceptance pass. The original 39/41 failure and strict
validator remain authoritative and unchanged.

The input cap is 1 MiB, case count 64, case text 8 KiB, prefix count 16 and prefix
text 1 KiB. Regex compilation has a fixed fuel limit. The safe byte probe is a
single process with a 30-second host watchdog. C-API probes retain the existing
reviewed broker's owned-process cleanup and bounded callback handling.

Results and explicit limitations are in
[the investigation report](../../../docs/P2_ESCAPED_UNICODE_BOUNDARY_RESULTS.md).
