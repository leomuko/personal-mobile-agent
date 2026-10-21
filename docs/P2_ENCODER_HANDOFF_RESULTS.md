# P2 Encoder Admission and Foreground Handoff Results

Date: 2026-10-21. **Focused prerequisite PASS; P2 incomplete.** No semantic search,
vector persistence, PDF expansion, grounded answers or indexing was enabled.
The production conversation and Sources pilot remain on their existing paths.

Review follow-up: queued panel cancellation and pre-verification proof invalidation
are now fixed with host regressions. The S24 evidence below predates this
cancellation patch and is historical prerequisite evidence, not a native rerun
of the current source. No device workload was repeated for these two fixes.

## Delivered

- Artifact-specific optimized tokenizer port in common code, with verified
  Android asset loading and bounded preprocessing. Full token-ID differential
  testing uses compiled, hash-pinned upstream C++, not a guessed character ratio.
- Exact SDK string-packet bytes are counted. Immutable receipts bind text,
  formatting, tokenizer/artifact/runtime identity, transport digest and all
  branch limits. The isolated worker independently rechecks before model creation.
- Request-owned prerequisite pipeline in the existing ModelManager: release
  resident Gemma, count, encode, confirm worker death/cleanup, recheck deadline
  and context eligibility, then admit Gemma. Vectors are validated and discarded.
- Opaque cancellation authority, first invalidation cause, typed terminal
  outcomes, pending/confirmed/uncertain cleanup, quarantine, panel BUSY behavior,
  context/lifetime invalidation and shutdown reconciliation.
- Separate SDK-byte capture, host oracle, Android admission, encoder-only and
  supervised encoder-to-Gemma evidence. No test reads personal notes or sources.

Contracts, limits and upstream notices are in [ADR 0005](decisions/0005-verified-encoder-admission-and-handoff.md)
and [encoder notices](licenses/ENCODER_NOTICE.md). This is an internal prerequisite
API, not permission to bypass it when implementing retrieval.

## Host Verification

- Fresh full shared JVM run: **113 tests, zero failures/skips**, including two
  opt-in token-ID/admission tests and 15 foreground ownership/state-machine tests.
- Android-host: **95 tests**; UI JVM: **11 tests**; app debug and QA: **15 tests
  each**, zero failures. Some unchanged targets were up-to-date; the shared JVM
  result was explicitly rerun with the counting fixture enabled.
- All **29 Node regressions** passed. Seven encoder-proof publication/wrapper regressions
  and five existing budget-verifier regressions passed under Python optimization.
- Debug/release/QA builds and both instrumentation APK variants compiled. Lint:
  **zero errors, 30 warnings**, all reported categories being dependency/target
  freshness or existing UseKtx suggestions. No distribution-readiness claim.
- Shell syntax and whitespace checks passed.

The ignored `build/encoder-counting/` contains source/corpus-correlated proof,
the upstream reference configuration, 1,038 synthetic byte/token-ID cases and
Android admission evidence. The corpus includes whitespace, normalization,
emoji, combining characters, multilingual text, NUL, long identifiers and exact
191/192/193 boundaries. Malformed UTF-16 is rejected before SDK transport.
Final admission run `1c6c974d-dcc1-4764-ac76-85950a7f2f42` passed all **1,038 cases**;
**579** over-limit cases were explicitly rejected without native model encoding.
SDK transport capture is separately run-correlated and retained beside that proof.
The complete wrapper removes any previous success before dependency setup,
corpus preparation or Gradle, not only in the publisher. The publisher also
invalidates before checking fresh XML, source/corpus hashes and non-skipped
results. Copied-wrapper tests inject failures in all four phases with unchanged
source/corpus hashes and an older valid proof; admission rejects before ADB.
The full real wrapper passed again with all 1,038 cases. Earlier Android admission
logs remain historical; that device step was not repeated after proof regeneration.

## S24 Supervised Handoff

Samsung SM-S928B, API 36/arm64. Final evidence is in ignored
`benchmarks/results/p2-foreground-gate-6CEbkE/`, run
`a408167e-7bea-41b0-bcf7-75febb5d128c`: **OK (1 test), seven cases passed**.
The folder records app/test APK hashes, changed runtime source hashes, preflight,
policy, instrumentation, device-local supervision, thermal sampling and worker
events. `encoder.json` includes parent memory stage samples without prompt text.

| Case | Preparation | Total request | Outcome |
| --- | ---: | ---: | --- |
| Cold encoder to Gemma | 575 ms | 15,342 ms | Completed |
| Reclaim resident Gemma, near-budget prompt | 888 ms | 14,795 ms | Completed |
| Stop during counting | Not admitted | 3 ms | Cancelled |
| Stop during worker loading | Not admitted | 411 ms | Cancelled |
| Stop during repeated native encoding batch | Not admitted | 505 ms | Cancelled |
| Withheld result beyond deadline | Not admitted | 5,012 ms | Timed out |
| Fresh worker, Unicode prompt | 673 ms | 14,745 ms | Completed |

These are individual observations, not percentiles or general latency promises.
Total time includes counting/loading/generation; it is not Stop-to-death latency.
The timeout deliberately withholds an actual encoder result; it does not simulate
or prove recovery from an uninterruptible native hang.

All allocated encoder workers had distinct instances/PIDs and confirmed Binder
death before any recorded Gemma engine-loading event. Engine events are recorded
inside ModelManager before calling load, not inferred from delayed UI/StateFlow
observation. Counting cancellation allocated no encoder worker. None of the
cancel/timeout cases loaded Gemma. Panel Cancel/release/load/test commands could
not interfere with the foreground reservation. Final Gemma release was confirmed.
The supervision status was freshly `closed`; its emergency-cleanup flag is not
the normal model-release result, which is separately recorded as CONFIRMED.

The three completed prompts recorded SDK input counts **30, 616 and 23**, below
their respective budget-v2 upper bounds **321, 1463 and 288**. All retained the
2048 context and 512 output reservation. Peak worker PSS among recorded phase
samples was 96,075 KiB, below the existing 384 MiB cap; this is not a continuous
peak measurement. Thermal status stayed 0 in the recorded workload.

## Separate Encoder-Only Evidence

Final short rerun: ignored `benchmarks/results/p2-encoder-gate-GgZdGG/`, run
`84d3c1ea-5da7-4aeb-93b9-bcd4b4547dd5`, **OK (1 test), three cases passed**.
Cold/warm case: 503 ms overall, SDK initialization 59 ms, encodes 21/15 ms.
Fresh recovery: 530 ms overall, initialization 49 ms, encodes 28/27 ms.
Active repeated-batch cancellation to confirmed worker death: 19 ms. Output was
100 dimensions. These encoder-only timings exclude Gemma and do not prove the
full retrieval deadline or semantic quality.

Both native runs used the explicitly approved isolated QA
`memory-android-pressure-test-v1` profile: available-RAM floors bypassed only
with Android `lowMemory=false`; required signals, thermal and ownership/cleanup
stops retained. This does not calibrate or change ordinary-app 3 GiB floors,
approve concurrent model residency, or replace the later mixed sustained test.

## Failed and Superseded Development Attempts

- The two new ownership regressions failed against pre-fix code: reversed dispatch
  allowed idle Cancel to stop foreground preparation, and the pending-native-signal
  test reached its test timeout after replacement was admitted. Both now pass,
  including confirmed recovery after the original signal completes.
- Copied-wrapper failures at install/setup/prepare/Gradle all reproduced retained
  success before the early invalidation fix. All now remove the old proof; the
  success path publishes only after Gradle. No device access occurs in these probes.

- An SDK-byte assertion failed on the S24: a supplementary scalar produced four
  native packet bytes rather than the assumed six-byte modified UTF-8 form.
  Generic codecs were removed from the Android path. Exact SDK packet bytes now
  feed counting and the reproducible reference. The earlier assertion failure
  was observed in tool output; it was not separately archived as a log file.
- Transport export initially exceeded Node's default output buffer. Export is
  now explicitly bounded at 8 MiB. A stale two-column corpus then failed the
  new three-column test; fresh capture/reference generation resolved it.
- Fresh toolchain extraction initially rejected irrelevant upstream test
  symlinks; it now validates paths and extracts only regular files/directories.
- Intermediate handoff pass `p2-foreground-gate-2q5eEj` predates exact transport,
  stronger load timestamps and near-budget/Unicode checks. It is retained but
  is not substituted for the final seven-case run above.
- One host invocation requested debug instrumentation with `testBuildType=secureQa`
  and failed task discovery before execution. Separate correctly configured
  invocations passed. No skipped or failed run is counted as acceptance.

## Reproduction

Use the pinned public encoder file; no model weights are bundled or silently
downloaded. The existing Gemma model must already be verified in Assistant (QA).
The reference toolchain needs Python with the pinned encoder-audit requirements,
CMake and clang++; initial dependency setup may need network access.

```sh
./gradlew :androidApp:assembleSecureQa :androidApp:assembleSecureQaAndroidTest \
  -PtestBuildType=secureQa -PandroidPressureMemoryTest=true --console=plain
# Install the matching QA app/test APKs with adb install -r on the designated device.
ANDROID_SERIAL=YOUR_DEVICE node scripts/capture-encoder-transport.mjs
bash scripts/verify-encoder-counting.sh /absolute/path/to/pinned-use.tflite \
  "$PWD/build/encoder-counting/sdk-transport.tsv"
ANDROID_SERIAL=YOUR_DEVICE node scripts/run-encoder-admission.mjs
ANDROID_SERIAL=YOUR_DEVICE P2_ENCODER_MODEL=/absolute/path/to/pinned-use.tflite \
  node scripts/run-p2-embedding-gate.mjs --foreground --android-pressure
ANDROID_SERIAL=YOUR_DEVICE P2_ENCODER_MODEL=/absolute/path/to/pinned-use.tflite \
  node scripts/run-p2-embedding-gate.mjs --android-pressure
```

The pressure flag is explicit QA authorization, never an implicit normal-build
default. Default host runs skip the two large-fixture differential tests;
`verify-encoder-counting.sh` supplies the fixture and requires two fresh unskipped
results. Receipts cannot be replaced with hand-authored count numbers.

## Remaining Gates / Not Run

No arbitrary production embedding, semantic relevance evaluation, vectors,
retrieval/citations, background indexing, PDF isolation, library capacity campaign,
full mobile matrix, new sustained workload or consolidated P2 acceptance ran.
Model-specific formatting/head selection still needs semantic evaluation before
retrieval adoption. Future code must integrate the real transactional context
epoch/fence into the prerequisite API, preserve reservation through retrieval,
and qualify the complete five-second path. Emulator/API 31-34 and other device
compatibility are not inferred from this S24 pass. Deferred release packaging,
default memory-policy decisions and audio issues remain unchanged.
