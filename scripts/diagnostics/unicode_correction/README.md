# One Unicode-value compiler backport (host only)

Implementation of reviewed proposal `unicode-grammar-correction-host-v1-draft-1`,
authorized by the user's subsequent `proceed`. This tool cannot run ADB, load a
model or modify the app's packaged runtime. No dependency or feature upgrade.

## Provenance and boundary

`PATCH.unicode-value-strings` adapts the plain-string algorithm from
`guidance-ai/llguidance@7aa1c3b67ec97cee62dc23123204a4351e9d40d4` (PR #374).
The full applicable MIT notice is retained in `LICENSE.upstream`. Immutable
upstream audit hashes are in
`planning/fixtures/unicode-correction-proposal-v1/upstream-audit.json`.

The patch touches only a copied LLGuidance 1.3.0 `src/json/compiler.rs`:

- Default-false `json_allow_general_unicode_escapes` applies to regex-free value
  strings; arbitrary regexes, constants and dynamic keys retain the pinned path.
- One escaped surrogate pair is one scalar, not two UTF-16 code units. Bounds
  use checked conversions. Compiled scalar and length expressions are cached.
- Pinned named escapes remain fixed. Literal DEL is allowed in the opt-in path;
  raw U+0000–U+001F remains invalid. No Unicode normalization or quote repair.
- The upstream configurable-escape API, dynamic-key/any-schema rewrites, Python
  bindings, caches in other versions and intervening commits are not imported.

Derivre 0.3.8, toktrie 1.5.0, Rust 1.87.0, the original Cargo.lock/40 registry
identities, dependency features and six LiteRT patches stay unchanged. A seventh
patch exists only in the new diagnostic copy. The original source workspace,
39/41 failure, bounded `JsonQuote` diagnosis, strict validator and frozen schemas
are preserved. An opt-in annotation is added only in new candidate schema files.

## What is tested

`compiler_probe.rs` uses **existing public Rust APIs** to compile real JSON
schemas and feed their parser one byte at a time with the stock byte alphabet.
It does not reimplement the tested regex, add a Rust/C API, call SentencePiece,
use Gemma tokens or claim native model behavior. These tests live in a root bin
so they need no additional LLGuidance test dependency.

`fixtures.py` generates the frozen 910-case corpus without results: exhaustive
ASCII, Unicode boundaries/noncharacters, scalar-length/combining/supplementary
boundaries, 32 deterministic generated sequences, the retained 18 valid strings,
default-off compatibility, malformed input, checked-bound and pattern/constant
regressions. The independent oracle decodes JSON, rejects unpaired surrogates
and checks decoded scalar length with the existing Python schema validator.

`run.py` verifies proposal/fixture identities, exact indexed patch context, base
preparation, unchanged historical source snapshots, the materialized derivre
archive and unchanged Cargo.lock. Preparation refuses an existing directory.
Execution checks all prepared files and stages private input/executable copies.
Source edits require fresh preparation; reports are never overwritten.

The unchanged broker owns processes and callbacks. `/usr/bin/time -l` runs inside
that same owned process group and measures each native diagnostic process. Its
macOS system-counter reads must be permitted. A denied counter/failed resource
measurement is an infrastructure failure, never an acceptance pass. Both byte
and mask probes retain the 30-second host watchdog; byte batches contain at most
64 cases/1 MiB input, case strings at most 8 KiB. These host timings do not alter
any search or native model deadline. RSS excludes the separate Python broker.

## Reproduction with the retained pinned base

Requires the existing source preparation and baseline evidence described in
`../mask_replay/README.md`, cached toolchain and pinned Python environment. No
weights are downloaded or committed. A fresh checkout without those local
inputs must first recreate their exact pinned preparations; missing inputs
block rather than trigger fallback or proof reuse.

```sh
export CARGO_HOME="$PWD/build/llguidance-mask-replay/cargo-home"
export RUSTUP_HOME="$PWD/build/llguidance-mask-replay/rustup-home"
export PATH="$CARGO_HOME/bin:$PATH"
export PYTHONPYCACHEPREFIX=/tmp/edge-mask-pycache

build/constrained-decoding-reference/bin/python \
  scripts/diagnostics/unicode_correction/run.py prepare
cargo build --release --locked --offline --bins \
  --manifest-path build/unicode-correction-v1/prepared/workspace/Cargo.toml
cargo test --release --locked --offline --bins \
  --manifest-path build/unicode-correction-v1/prepared/workspace/Cargo.toml
cargo clippy --release --locked --offline --no-deps --bins \
  --manifest-path build/unicode-correction-v1/prepared/workspace/Cargo.toml -- -D warnings
rustfmt --check --edition 2021 scripts/diagnostics/unicode_correction/compiler_probe.rs
build/constrained-decoding-reference/bin/python -W error::ResourceWarning \
  -m unittest discover -s scripts/tests -p 'test_*.py' -v
build/constrained-decoding-reference/bin/python \
  scripts/diagnostics/unicode_correction/run.py run \
  --out build/unicode-correction-v1/NEW-CAMPAIGN
```

Choose a new `--workspace` when repeating preparation and supply it when running.
The source/preparation hash must match; adding `--serial` or device flags is not
supported. Use the existing host-only `GrammarProgressFixturesTest` separately to
verify the unchanged strict-context outcomes for all 14 structural witnesses.

Successful default-off checks require the same six reconstructed histories,
16 masks and six tokenizer transcripts with the option both absent and false.
The corrected opt-in candidate must pass the **same 41 named checks**, without
replacing the original escaped witness or changing expected strict rejections.
Failures stop subsequent phases and remain retained. Unavailable tokenizations
cannot earn credit. The prior 39/41 result remains a historical failure even if
this separately versioned candidate passes.

A host pass is not Android integration, a packaged-AAR change, an S24 proposal,
proof of Gemma's newline choice or semantic-search enablement. P2 is incomplete.
