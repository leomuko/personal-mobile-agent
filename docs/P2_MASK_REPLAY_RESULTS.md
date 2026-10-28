# P2: Model-Free Host And Emulator Mask Replay

2026-10-28. Scope: the user-authorized host reproduction and complementary
emulator diagnostic following the reviewed whitespace investigation. No Gemma
generation, app installation, physical-device execution, production runtime
change, output repair or Git operation occurred in this slice.

**PASS for standalone replay and host/emulator equivalence. Native Gemma/SDK
feasibility remains blocked.** P2 is incomplete and semantic search stays disabled.

## What Was Reproduced

A separate diagnostic executable builds LLGuidance 1.3.0 and toktrie 1.5.0 with
the pinned LiteRT-LM dependency patches and features. It calls the same C
tokenizer/constraint functions used by the inspected provider. It is not linked
into the app or its Gradle modules and does not load the packaged JNI library,
Gemma, a sampler, or GPU code. The app remains Kotlin/KMP.

The [source and reproduction instructions](../scripts/diagnostics/mask_replay/README.md)
cover the isolated Rust 1.87.0 toolchain, pinned Cargo.lock, preparation checks,
exact patch application, host broker and ARM64 emulator runner. Compiler,
dependencies, vocabulary/tokenizer bytes and binary artifacts remain under
ignored `build/llguidance-mask-replay/`. No model weights were downloaded,
exported to the emulator or added to Git.

Each callback is serialized and serviced by host SentencePiece 0.2.0 using the
exact embedded tokenizer. Emulator execution therefore exercises the Android
grammar binary with a host tokenizer callback broker; it is not an independent
Android SentencePiece implementation. Callback byte strings and returned IDs
are retained, including the provider's size-query/retry calls.

## Input And Configuration Fidelity

- Source archives match the pinned Cargo checksums for LLGuidance and toktrie.
  All five LLGuidance patches and the toktrie patch are applied in upstream
  order. The PERF patch's declared hunk counts disagree with its body: the
  preparer matches and replaces the complete exact old/new context once,
  without fuzz or changing the patch bytes. Missing or ambiguous context fails.
- All 40 registry dependencies in the resolved diagnostic lock match the
  upstream lock's name/version/checksum. Features are `lark`, `rayon`, `ahash`,
  with default features disabled, matching the inspected runtime workspace.
- The full pinned model, embedded tokenizer, 262144 vocabulary entries and
  exact unchanged sentinel schema are authenticated during preparation.
- Exact model metadata bytes encode start `[2]` and ordered stops `[1],[50],[106]`.
  The inspected provider factory chooses the first single-token stop: replay
  EOS is **1**, not a guessed end-of-turn ID. Native session getters were not
  available from the historical attempt, so this is a source/metadata-derived
  configuration, not a recovered runtime trace.
- The C API uses default constraint initialization, disabled fast-forward and
  backtracking, and no approximate tokenizer. Raw piece bytes reproduce the
  inspected provider boundary. Individually decoded piece bytes are a separate
  comparison, not a proposed production replacement.
- Both saved raw outputs remain unchanged. Their common exact text prefix
  through `"b":` is reconstructed using canonical SentencePiece IDs and an
  alternative literal-byte token sequence. **Neither is the unavailable sampled
  token history.** The alternative is explicitly retained even when rejected.

## Results

Six diagnostics ran on the Mac host and on the existing ARM64 Android 16/API 36
emulator, `emulator-5554`. All **16 complete 262144-bit masks** and all six
tokenizer transcript hashes match across environments.

| Reconstruction | Raw-piece vocabulary | Decoded-byte comparison |
| --- | --- | --- |
| Canonical prefix, before/after newlines | Replayed; 63 allowed tokens | Replayed; 99 allowed tokens |
| Literal-byte prefix | Rejected at token position 53, ID 236751 | Same rejection |
| Canonical prefix followed by `0` | Replayed; 64 allowed tokens afterward | Replayed; 102 allowed tokens afterward |

Canonical-prefix masks were collected at 0, 1, 2, 16, 187 and 228 consumed
single-newline tokens. In both vocabulary variants, at every checkpoint:

- Token 107 (one newline), token 108 (two newlines), and token 236771 (`0`) are
  allowed. Their masks stay byte-identical across these checkpoints within
  each variant.
- Token 236770 (`1`) and EOS 1 are disallowed: `b` is constrained to exactly 0,
  and the object is incomplete.
- Space token 236743 is disallowed for raw pieces but allowed for decoded bytes.
  Raw `▁` and decoded space therefore demonstrably affect this reconstructed
  mask. This does not establish that this difference caused the S24 loop.

The separate zero-continuation branch successfully commits `0`; a second `0`
or `1` is then disallowed. Prefix reconstruction can therefore reach a state
where the required numeric token is allowed and consumable. The rejected
literal-byte reconstruction cannot establish the stalled-position mask for that
alternative history and receives no such credit. Zero-based position 53 refers
to that reconstructed token sequence, not a recovered S24 decode step.

Counts of saved newline characters are not counts of sampled tokens. Consuming
228 single-newline tokens here does not assert that the phone generated 228
such tokens. No logits, probabilities, greedy-selection trace, native GPU mask
or complete Gemma response were collected.

The observed source-level whitespace mechanism is now reproduced in these
specific native diagnostic states, on host and emulator. It is a **possible
mechanism**, not proof of why Gemma selected whitespace or that the packaged
SDK used exactly these masks. The executable is a source build, not the
published AAR's native library; no reproducible-build equivalence is claimed.

## Bounds, Isolation And Cleanup

The broker bounds protocol lines, callback count, prefix length and newline
checkpoints. Its per-process watchdog is 30 seconds. The emulator adds a native
`timeout 30`; uncertain remote cleanup fails collection. These are diagnostic
process bounds, not a redefinition or measurement of the approved complete
Gemma request deadline or five-second search contract.

The runner rejects physical serials before ADB access and confirms QEMU/ARM64.
It stages private executable/vocabulary copies, checks remote SHA-256 values,
uses a unique `/data/local/tmp/edge-mask-replay-*` directory, and removes only
that directory. Cleanup was confirmed. No emulator was started, stopped or
wiped; no APK was installed and no app data or permission changed. The existing
emulator's RAM setting was not changed. No parallel S24 work ran.

Individual emulator diagnostics took 1088–1343 ms including host callback
transport and process completion. This is not model throughput, a search latency
qualification, a cold/warm Gemma measurement or evidence for an S24 timing gate.

## Verification And Evidence

Passed:

- All 29 Python host regressions, including nine new checks for exact patching,
  traversal/ambiguity rejection, lock identity, physical-device refusal,
  tokenizer bytes, failed-output preservation and owned-process timeout cleanup.
- Locked/offline final host and Android ARM64 builds with Rust 1.87.0; Android
  linking used installed NDK 29/API 31 with 16 KiB page alignment.
- Independent primary-QA review of seven new diagnostic files: no blocking
  findings. QA reran all nine new tests and six host diagnostics; all 16 masks
  matched the main host replay. No source changed during that completed review.
- Six emulator diagnostics and bitwise comparison of all 16 masks, plus exact
  equality of all six tokenizer transcript hashes; remote cleanup confirmed.
- Both historical bundles' 343 current and archived source/APK bindings per
  bundle remain unchanged, as do the validator and both failed raw outputs.

Not run: new Gradle builds/tests (the application was unchanged by this slice),
Gemma generation, packaged JNI/GPU mask tracing, S24 execution, the eight stopped
V2 cases, semantic accuracy, or any latency qualification. The literal-byte
reconstructions are recorded rejections, not successful stalled-state replays.

Retained ignored artifacts:

| Artifact | SHA-256 |
| --- | --- |
| Seven-file reviewed source snapshot | `998b6e54b70beb99753bf8d60b223354f350d5fd5d0b1c5b845c86e2138c8fb1` |
| `host-final-locked/report.json` | `8b435eabe1b2cf7e0a6c437f5b1e724f6fd291791f7ea4c57257637a4d1acf8c` |
| `emulator-20261028-1/report.json` | `0bcbe9217e6e9f042fc0615b42efd40dbf3824b9567d661f54e1ee6984eba4d3` |
| `host-emulator-comparison.json` | `fa6c9d95945a55c4ddc9d836510f6af7aa758a10692dc022eda7fe94f212aca4` |
| Host executable | `fc6c734d2622e24229bceb6e5948f676bc95a8113f41e67d11ce90ee275d1c26` |
| Android ARM64 executable | `8ef5aa1c798c542d4c9875fcf619b6e141f3b03a396772cbe1d9cb1daf5a9ff2` |

Paths are relative to `build/llguidance-mask-replay/`. Each report retains exact
configurations, full masks, transcript/source/input/binary hashes and timings.
QA's independent host replay is `qa-independent-20261028-1/report.json`.
Earlier setup/build/host attempts remain separately retained, including the
initial standard-patch rejection and Android `c_char` ABI compile error that
were resolved before the reviewed final binaries and emulator run.

## Remaining Decision

The diagnostic does not justify another identical Gemma run, a deadline increase,
whitespace repair or a tokenizer substitution. A later reviewed proposal can
investigate progress-bounded grammar/output construction using these results,
or separately obtain native token/mask instrumentation. It must preserve the
strict validator and historical failures and distinguish source-build evidence
from packaged SDK behavior. No such runtime or protocol change is adopted here.
