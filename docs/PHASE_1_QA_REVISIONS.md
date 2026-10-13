# Phase 1 QA Revisions

Updated: 2026-10-13. Branch: `dev`. Recorded for the Phase 1 development checkpoint.

## Changes

- LiteRT callbacks explicitly concatenate `Content.Text.text` in content order and ignore non-text/empty content. Pinned LiteRT-LM 0.17.1 already renders pure text through `toString()`, so the reported structural-text bug was not reproduced for that path; the explicit mapper now enforces the intended boundary for mixed content as well.
- Android host regressions exercise mixed text/image, non-text-only and empty messages without loading a model. These tests use Java 21 because the pinned SDK's classes are Java 21 bytecode. Compilation and shared JVM tests keep Java 17.
- Native short-prompt assertions require comma-separated word content and reject LiteRT object-representation markers, in addition to nonempty output and SDK metrics. The subsequent S24 GPU smoke passed these assertions.
- Rejected commands publish `commandError = BUSY`, keeping the active runtime status, output and runtime error intact. This covers overlapping generation, verification and release admission, and import attempted while loaded. The Models panel displays this localized rejection. A subsequently admitted command or Cancel clears it.
- Generation monitoring now applies the same 3 GiB available-memory floor as admission. Three consecutive one-second low-available-memory samples cancel; any recovery resets that counter. Android `lowMemory`, severe thermal status, unknown resource signals and unsupported total RAM cancel on the first monitoring sample. Admission still rejects a single unsafe snapshot. The debounce changes timing, not the threshold; the prior 2026-10-02 native measurements do not verify this new monitor.

## Verification

Passed on 2026-10-13:

- Debug/release APK compilation and debug lint: 0 lint errors, 23 warnings.
- Shared JVM tests: 43 passed.
- Shared Android host tests: 31 passed, including two LiteRT text-mapping tests.
- Android debug unit tests: 6 passed.
- Complete secure-QA instrumentation on a temporary read-only `Pixel_7_Pro` emulator instance: 48 passed. The isolated QA package was the only package installed or permission-modified by the script. No saved emulator data was wiped.
- `ModelsPanelTest` on the connected S24 Ultra: 1 passed, including the busy rejection text and reachable Cancel control.

The first Android host-test attempts failed because LiteRT's Java 21 classes were loaded by the Java 17 test launcher; the corrected task-specific launcher passed. A subsequent task-configuration attempt failed due to early named-task lookup; lazy configuration fixed it.

The opted-in S24 GPU smoke was attempted with thermal supervision, but both native cases failed resource admission before generation. The first reported `Resource admission blocked: LOW_MEMORY`; the host-handoff case could not load the model. Available RAM remained approximately 2.6-2.8 GiB during read-only checks, below the unchanged 3 GiB admission floor. The QA process had exited afterward; no unrelated apps were closed or phone settings changed. Treat this as blocked native verification, not an output-mapping failure or a native pass. Raw attempt: ignored `benchmarks/results/native-20261013T175622Z-GPU/`; thermal trace: ignored `benchmarks/results/s24-thermal-20261013T175622Z/`.

The subsequent QA session passed `ModelAssetsTest` plus `ModelsPanelTest` on the S24 (9 tests) and the native GPU smoke (2 tests). The verified `benchmarks/results/native-20261013T181209Z-GPU/runtime.json` records 9,821 ms cold load plus first output, 138 ms warm first output, 29 ms cancellation, 401 ms active release, 406 ms idle release and 1,948,760 KB sampled peak PSS. Automatic fallback was disabled. These are single-run observations; percentile gates are null and `acceptanceComplete` remains false. Battery stayed at 34% while USB powered. The associated thermal trace stayed at status 0; the QA report records skin increasing from 37.6 C to 39.4 C. Preserve the earlier blocked attempt alongside this success.

The broad S24 instrumentation run started 48 tests but encountered test-harness failures: null `uiAutomation.serviceInfo` during camera-permission cleanup, a `SecurityException` while granting camera permission, and a notification-controls assertion failure. Full physical mobile QA therefore remains blocked. The separate emulator pass and focused model passes do not close this gap.

Release packaging also remains open: the approximately 109 MB unsigned release APK includes LiteRT-LM JNI libraries for ARM64 and x86-64 even though native runtime actions are disabled outside debug. Move the dependency behind an internal build boundary before distribution; that change is not part of this checkpoint.

Not run after the monitor revision: repeated latency measurements and the sustained workload. The earlier [Phase 1 results](PHASE_1_IMPLEMENTATION_RESULTS.md) remain historical evidence; full P1A acceptance and P1B integration remain open.
