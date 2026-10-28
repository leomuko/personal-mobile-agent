# Model-free LLGuidance mask replay

This diagnostic is outside Gradle and application packaging. It builds LLGuidance
1.3.0 and toktrie 1.5.0 with LiteRT-LM's exact pinned dependency patches, features
and registry versions. It calls the same C constraint API, without loading
Gemma, the packaged JNI library, a sampler or GPU. It never enables search.

The host Python broker uses SentencePiece 0.2.0 and the exact embedded tokenizer
for every native tokenization callback, including on the emulator. Raw byte
callbacks and responses are retained. This is not an on-device SentencePiece
build; agreement with the inspected C++ wrapper is a source-level boundary.

Use the existing hash-pinned environment established by
`scripts/verify-constrained-decoding-prerequisites.sh`. Supply your local pinned
model path; no weights are downloaded. Generated vocabulary/tokenizer files,
source packages, compiler and reports belong under ignored `build/` only.

```sh
build/constrained-decoding-reference/bin/python \
  scripts/diagnostics/mask_replay/prepare.py \
  --out build/mask-replay-new --model models/gemma-4-E2B-it.litertlm
```

Preparation fails rather than overwriting a workspace, authenticates the full
model/tokenizer/schema and source archives, checks the exact metadata bytes for
start `[2]` and stops `[1],[50],[106]`, and applies all five LLGuidance patches
and the toktrie patch. The PERF patch has inconsistent declared hunk counts;
the preparer uses its entire exact old/new context once, without fuzz or an
edited patch. Missing/ambiguous context rejects. Source notices are retained.
The runtime provider chooses the first single-token stop; replay EOS is **1**.

With Rust **1.87.0** and an isolated CARGO_HOME/RUSTUP_HOME under `build/`:

```sh
cargo build --release --locked --manifest-path build/mask-replay-new/workspace/Cargo.toml
```

For ARM64 Android, add the `aarch64-linux-android` Rust target and use the installed
Android NDK compiler, for example:

```sh
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ANDROID_NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android31-clang" \
RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384' \
cargo build --release --locked --target aarch64-linux-android \
  --manifest-path build/mask-replay-new/workspace/Cargo.toml
```

The committed diagnostic Cargo.lock is the resolved subset of the pinned runtime
lock. The runner independently checks every registry name/version/checksum against
that upstream lock. There is no dependency upgrade or change to the app's build.

```sh
build/constrained-decoding-reference/bin/python scripts/diagnostics/mask_replay/run.py \
  --workspace build/mask-replay-new --out build/mask-replay-host-new

build/constrained-decoding-reference/bin/python scripts/diagnostics/mask_replay/run.py \
  --workspace build/mask-replay-new --out build/mask-replay-emulator-new \
  --serial emulator-5554 --adb "$ANDROID_SDK_ROOT/platform-tools/adb"
```

The runner only accepts an existing ARM64 emulator with `ro.kernel.qemu=1`.
It never starts, wipes, stops or installs an application on that emulator, and
rejects a physical-device serial before ADB access. It stages private executable
and vocabulary copies, verifies remote hashes, uses a unique temporary directory
and a native `timeout 30`, and removes only its own files. Uncertain cleanup is a
failed run. The host broker has its own 30-second process bound and kills only
the diagnostic process group it created. It preserves failures and refuses to
replace an existing report directory. None of these diagnostic timings are a
production latency or complete retrieval measurement.

The exact exposed text prefix is reconstructed in two ways: canonical
SentencePiece IDs and literal-byte token IDs. **Neither is the missing native
sampled-token history.** Both vocabulary forms are tested: raw pieces (the
inspected provider path) and individually decoded pieces (a diagnostic comparison,
not a proposed replacement tokenizer). The replay checks full masks at 0, 1, 2,
16, 187 and 228 consumed single-newline tokens. Newline characters from the saved
output cannot identify the actual number or grouping of sampled newline tokens.
A separate branch consumes the required `0` and checks its next mask.

`completed` in report.json means diagnostic collection completed, including
explicitly reported `prefix-rejected` reconstructions. It is not Gemma success,
semantic qualification or evidence that all possible prefix segmentations work.
Complete 262144-bit masks, their hashes, exact configuration, tokenization
transcripts, executable/input/tool hashes and failures are preserved.

Run the small broker/preparation regressions with:

```sh
PYTHONPYCACHEPREFIX=/tmp/edge-mask-pycache \
build/constrained-decoding-reference/bin/python -W error::ResourceWarning \
  -m unittest discover -s scripts/tests -p test_mask_replay.py -v
```

The frozen schema, validator, eight stopped V2 cases and historical failed outputs
remain unchanged. No result from this tool identifies Gemma's token probabilities
or proves the cause of the S24 repetition. Full model execution needs a separately
reviewed scope and authorization; do not repurpose the old V2 ledger.
