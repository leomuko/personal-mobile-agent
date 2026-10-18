# Native Supervision-Loss Checks: 2026-10-18

## Scope and Verdict

PASS for the targeted supervision-loss gate on the connected S24 Ultra (SM-S928B, GPU, no CPU fallback): loss during LiteRT initialization, prefill and decoding, followed by fresh-process load/generate/unload recovery. This is not full mobile QA, sustained acceptance, memory-policy calibration or release approval. No sustained workload ran.

The user explicitly authorized the existing `memory-android-pressure-test-v1` QA profile for these checks: memory admission requires Android `lowMemory=false`, while required signals, minimum total RAM, thermal limits, ownership and cleanup safeguards remain enforced. Ordinary app policy is unchanged. The installed isolated QA build retains this experimental profile; it has not been promoted to ordinary builds.

## Instrumentation and Evidence

- `LiteRtLlmEngine` has an optional metadata-only phase observer, unused by ordinary app construction. Initialization is marked immediately before `Engine.initialize()` and ends after it returns. Prefill spans `sendMessageAsync()` submission to the first nonempty text callback; decoding then spans callbacks to terminal completion. These are adapter-observed intervals, not hardware-kernel profiling.
- The opt-in `NativeSupervisionTest` uses the real adapter, `ModelManager`, pinned private assets and shared resource policy. It does not write conversation history or use personal prompts/attachments. Its observer only updates an atomic in-memory trace; it does not wait for the host, pause inference, or block callbacks on telemetry/file writes.
- The host renews run-scoped heartbeats while monitoring thermal signals. At the requested native interval it stops renewal and removes only that run's heartbeat. USB stays connected and thermal monitoring continues through termination. The device guard must observe `UNAVAILABLE`, record the same native phase before cleanup, and terminate its QA process. A missed phase is not a pass.
- Each loss record is correlated to the run ID, phase timeline, model/runtime pin, GPU backend and shared policy. The host verifies process absence. Recovery additionally requires successful JUnit output, a fresh closed supervision proof, real nonempty model output, and `UNLOADED` after release.
- Final evidence: ignored `benchmarks/results/native-supervision-Ct4yD8/`. Includes `summary.json`, `budget.json`, source/APK SHA-256 fingerprints, per-case instrumentation, thermal samples, policy exports, phase/loss traces, supervision proofs and monotonic-clock `stop-observation.json` records. Device serials and generated text are not exported in these reports. A final stricter validator check also requires an active manager state with no runtime error; all saved native loss records passed, with that validator's hash recorded in `strict-validation.json`.

Pinned model SHA-256: `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`; LiteRT-LM `0.17.1`. Context stayed **2048**, maximum output **512**. The longer synthetic prefill fixture has **1450** tokens under the pinned audited template; an additional **64-token** template allowance plus the output reserve still fits. The host verifier checks both artifact/tokenizer hashes and the fixture fingerprint before native work. This fixed synthetic benchmark fixture does not bypass or enlarge real-chat budgeting.

## Passing Run

| Case | Native Phase at Loss | Cleanup Evidence | QA Absence Observed After Loss | Whole Case Time |
| --- | --- | --- | --- | --- |
| Initialization loss | INITIALIZING, before initialize returned | Cleanup unconfirmed; independent hard-stop terminated QA | 5,971 ms | 12,008 ms |
| Prefill loss | PREFILL, before first text callback | UNLOADED confirmed; 376 ms from loss capture to cleanup completion, then terminated | 1,699 ms | 16,467 ms |
| Decoding loss | DECODING, after first text callback | UNLOADED confirmed; 311 ms from loss capture to cleanup completion, then terminated | 1,699 ms | 16,990 ms |
| Recovery | Fresh initialize, prefill, decode, terminal and release | JUnit OK (1 test), UNLOADED, closed guard | Not a loss case | 31,300 ms |

Whole-case times include model verification, initialization and host polling; they are not stop latencies. QA-absence observations use a host monotonic clock, from successful heartbeat-removal command completion through instrumentation termination, proof export and `pidof` absence confirmation. All stayed within the harness's nine-second observation bound; these are conservative host observations, not exact native stop timestamps. Initialization deliberately exercises the emergency path: cleanup was not confirmed within the guard's five-second wait, with the last phase trace still INITIALIZING. This is not evidence of a graceful cancellable initializer. The recovery summary's `cleanupConfirmed=false` means the loss-only cleanup callback was not invoked; successful normal unload is independently recorded in `native-recovery.json`.

Android `lowMemory=false` was recorded at all three loss captures and after recovery. All final-run thermal samples reported status **0**; maximum sampled skin temperature was **37.8 C**. These are observations, not guarantees against future pressure, scheduling delays or native hangs.

## Earlier Attempts Preserved

- `native-supervision-MVvk65`: baseline preflight **blocked** by `LOAD_HEADROOM`; no native initialization ran. This was not treated as a model/device failure.
- `native-supervision-EqM3Dj`: authorized pressure profile admitted the test, but QA-private model storage was empty. Instrumentation failed with `NOT_INSTALLED`; no native initialization ran. The early host error was an unavailable loss report, not model output evidence.
- The existing local model was then provisioned into QA-private storage as a test fixture. Host and device copies matched the pinned SHA-256 and **2,588,147,712 bytes** before immutable-path promotion; every subsequent native load also used normal asset verification. This provisioning is not additional installer-UI coverage. Ordinary app storage was not copied or cleared.
- `native-supervision-mq0jz9`: initialization loss passed, but the original short prefill fixture had already entered decoding when loss was observed. That attempt remains **failed**. The replacement longer fixture was tokenizer-verified; no artificial prefill sleep, increased context, relaxed thermal stop or retry-to-hide-failure was used.
- `native-supervision-0w0x7n`: all four phase/recovery cases passed before explicit monotonic stop-observation records were added. A final bounded repeat (`Ct4yD8`, above) captured and enforced those timing records. Neither run was a sustained test.

## Local Verification

- Passed: 25 Node host tests, including missing/stale/foreign phase evidence, missed phases, live processes, wrong prefill fixture/context, and existing supervision/memory/preflight regressions.
- Passed: shared JVM and Android-host tests, 13 app unit tests in debug and 13 in pressure-profile secure QA, debug build/lint, secure-QA app and instrumentation builds.
- Passed: 5 Python verifier tests, the full pinned-tokenizer verifier (2009 single-turn and 1000 history cases plus the prefill fixture), shell/Node syntax and whitespace checks.
- One combined Gradle verification invocation was rejected because `testDebugUnitTest` is unavailable when `testBuildType=secureQa`. Separate debug and secure-QA invocations passed afterward; this was a task-selection error, not a test assertion failure.

## Reproduce

Use the designated connected device, matching QA/test APKs, the pinned model installed in isolated QA-private storage, and pinned host tokenizer dependencies. Build/install the baseline variant for baseline testing, or explicitly select the existing authorized QA experiment:

```sh
./gradlew :androidApp:assembleSecureQa :androidApp:assembleSecureQaAndroidTest \
  -PtestBuildType=secureQa -PandroidPressureMemoryTest=true --console=plain
# Install both resulting APKs with adb install -r; do not clear QA storage.
bash scripts/verify-chat-budget.sh
ANDROID_SERIAL=DEVICE node scripts/probe-native-supervision.mjs --android-pressure
```

Without `--android-pressure`, the host requires the installed baseline policy and its unchanged RAM gates. `CHAT_BUDGET_PYTHON` can select an existing interpreter with the pinned verifier dependencies. Keep blocked/failed run directories; do not relabel them after a later pass.

## Remaining Limits

Physical cable removal/reconnection, host SIGKILL during a live native workload, an ADB stall while work continues, scheduling stress and the full mobile lifecycle/offline matrix were not run here. Controlled heartbeat unavailability validates the device-local loss/cleanup/termination path during real native work; it does not prove all physical transport and host-failure interleavings. No new unattended sustained test was started, and deferred production memory/audio/release-packaging decisions remain unchanged.
