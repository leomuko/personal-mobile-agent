# Phase 1: Local Runtime and Assistant Conversation

Updated: 2026-10-02. Status: internal runtime foundation and S24 CPU/GPU native smoke verified; the 20-sample GPU latency gates and a supervised 20-minute mixed synthetic run passed, but full P1A acceptance, the remaining protocol, and P1B remain open. See [implementation evidence](../docs/PHASE_1_IMPLEMENTATION_RESULTS.md).

2026-10-13 follow-up: the revised text extraction, busy-command handling and memory monitor passed local regression checks and a new S24 native GPU smoke. Focused S24 model instrumentation passed 9 tests, but the broad physical suite encountered camera accessibility/permission and notification harness failures. Repeated/sustained measurements of the revised monitor and an internal-only release dependency boundary remain open. See [QA revisions](../docs/PHASE_1_QA_REVISIONS.md).

This supplements the [release plan](IMPLEMENTATION_PLAN.md) and [runtime decision](../docs/decisions/0003-gemma4-litert-lm.md). The roadmap remains eight phases, P0-P7. P1A and P1B are subphases, not additional numbered phases.

- **P1A: Runtime Foundation** proves verified installation, local streaming, cancellation, release, recovery, and resource behavior independently of personal conversations.
- **P1B: Conversation Integration** adds real assistant responses, prompt construction, stop/retry, context trimming, restart reconstruction, and shared mascot/drawer/full-app behavior.

P1A completion alone does not mean the assistant chat is implemented. The mascot and continuous conversation remain the product direction. E2B is the first acceptance candidate; E4B is benchmark-only and does not block P1A.

## Development and Device Strategy

Develop on the Android emulator first, then run a dedicated acceptance session with the user's S24 Ultra connected. Do not require the phone for normal implementation or substitute emulator results for phone evidence.

1. Run shared/local tests with fake engines and injected resource signals.
2. Use the designated emulator for installation, Models UI, state transitions, lifecycle, cancellation integration, and import/restart tests.
3. Inspect the pinned AAR's packaged ABIs and dependencies before attempting native inference. Use CPU explicitly for the first emulator spike, provided its ABI and resources are supported. Emulator GPU availability is not Samsung GPU compatibility.
4. If native inference is unavailable on the current emulator, report it as blocked/unsupported. Continue clearly labelled fake-engine tests; do not mark native load/cancel/release as passed. An ABI-compatible emulator or the S24 session must close the native evidence gap.
5. Run the same native smoke on S24, followed by separate CPU/GPU measurements and the full acceptance matrix. Record actual RAM, available memory, Android/One UI, ABI, storage, charging state, and thermal baseline without publishing device identifiers.

The existing S24 shell/storage QA record is separate from inference acceptance. Its debug-variant instrumentation startup failures remain open; a secure-QA pass must not silently mark debug instrumentation as passing. Do not wipe, uninstall unrelated apps, reboot, or stop an existing emulator merely to unblock development.

## R01: Decision and Immutable Manifest

The checked-in [planning manifest](models/gemma-4-e2b-it.json) is the canonical pin for this slice. It contains no model weights. The app uses a trusted compiled `ModelCatalog`; Android instrumentation checks its immutable values against this JSON bundled in the test APK. User manifests are never trust roots.

- Artifact: `litert-community/gemma-4-E2B-it-litert-lm`, revision `6e5c4f1e395deb959c494953478fa5cec4b8008f`, `gemma-4-E2B-it.litertlm`.
- Expected bytes: `2588147712`, represented as Kotlin `Long` in sizes, progress, and storage calculations.
- SHA-256: `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`. Compare a streamed local digest against this published, pinned value; never trust the first imported file by default.
- Runtime spike pin: `com.google.ai.edge.litertlm:litertlm-android:0.17.1`. No floating dependency versions.
- Artifact and runtime licenses: Apache-2.0; retain notices and review transitive native notices before distribution.
- Required phone ABI: `arm64-v8a`. Current app build filters also include `x86_64`; neither filter proves that the new AAR supplies compatible binaries. Record verified runtime ABIs after inspection/load, not before.
- LiteRT-LM owns the artifact's prompt template and tokenizer. The app supplies structured roles/content and budgets; it must not manually template the same input twice.
- The mobile artifact contains multimodal capabilities; P1A enables text input/output only. Do not initialize vision/audio backends, tools, or automatic action execution. No speech, OCR, or image understanding is implied.

The runtime POM declares coroutines-android 1.11.0. The project now explicitly aligns core/Android/test to 1.11.0 and resolved runtime dependencies were inspected. The pinned AAR has minSdk 24, arm64-v8a and x86_64 JNI libraries, each with 16 KB LOAD alignment; APK zip alignment was also checked. These are packaging checks, not proof of native execution on either device.

## R02: Explicit Configuration and State

```yaml
artifactMaxContextTokens: 32000
configuredContextTokens: 2048
maxOutputTokens: 512
```

The artifact maximum is metadata, not the initial allocation. Remove the hidden 4096-token default from `ModelConfig` when implementing the configuration contract. Count the actual templated input, special tokens, and output reservation: total must fit 2048. Account for reasoning tokens in the output budget if enabled. Reject over-budget input with a typed error during P1A; P1B adds explicit trimming policy. Benchmark 4096 and optionally 8192 only after the first configuration passes.

Asset state: `NotInstalled`, `Importing`, `Verifying`, `Installed`, `RepairRequired`, `Failed`.

Runtime state: `Unloaded`, `Loading`, `Loaded`, `Generating`, `Cancelling`, `Releasing`, `Blocked`, `Failed`.

Installed means verified bytes are available; it does not guarantee device compatibility, loading, or generation. Persist installation metadata, never native handles or a runtime-loaded flag. Reconcile files and metadata on process start. Typed errors distinguish corruption, insufficient space, unsupported ABI/backend, resource blocking, context overflow, failed load, cancellation timeout, and release failure; localized display text stays in UI resources.

## R03: Bounded, Crash-Safe Import

Use `noBackupFilesDir/models/` for weights and staging, on the same filesystem for atomic promotion. Store metadata only in SQLDelight if a schema is needed; never store model blobs there. Public weights need not be encrypted; personal prompts, KV/session data, and recordings are not model weights and retain separate privacy requirements.

1. Validate the selected artifact against the trusted catalog. Provider-reported length may be unknown or inaccurate; it is only an early rejection aid.
2. Preflight expected bytes and free-space headroom, accounting for staging, retained versions, runtime cache allowance, and any additional copy required by the implementation. Existing installed bytes already consume free space; avoid double-counting them. Record the chosen formula and reserve in tests.
3. Stream into a uniquely named private staging file off the main thread with bounded buffers; never read the multi-gigabyte file into a byte array.
4. Enforce a maximum of the expected byte count while copying, support user cancellation, and handle disk-full even after successful preflight.
5. Require exact final size and a matching streamed SHA-256 before installation. Clean up rejected staging files without deleting the working version.
6. Flush/close the completed file, then atomically promote to an immutable versioned destination. Never truncate or overwrite a loaded model; coordinate install/repair/removal with the runtime owner.
7. Persist the installed pointer and verification metadata only after promotion. Retain the previous verified version until the replacement has loaded successfully and no runtime uses the old path.
8. Reconcile incomplete staging, valid promoted-but-unregistered files, missing registered files, and interrupted pointer updates on restart. Reverify orphaned files before adoption; never infer `Installed` from a filename alone.

The first spike may use a developer-staged file, but must verify it before load. Production import/repair UI follows after the native viability check. No public HF download, login, or network permission is required by this slice.

## R04: Runtime Adapter and Cancellation Contract

Extend `LocalLlmEngine`; isolate LiteRT-LM types in `shared/src/androidMain/.../inference/litert/`. Shared requests/events/errors remain vendor-independent. Initialization, hashing, generation, and close work must not block the main thread.

One application-scoped owner creates at most one engine and one active native conversation. A dev test conversation is not the app's persisted personal conversation. Independent benchmark samples use fresh conversations; the explicit multi-turn case alone reuses its conversation.

- Serialize state transitions and generation admission, not the entire command loop behind a long-running generation. A second generation is rejected as busy until the first is terminal. Publish rejected commands through `commandError` without replacing the active runtime status, output or runtime error; clear the rejection when the next command is admitted or cancellation is requested.
- `cancel()` signals the active native conversation out-of-band, without waiting for a generation-wide mutex, queued command, or blocked inference dispatcher. Guard handle lifetime so cancel cannot race a completed close.
- Give every operation a generation/session identity. Once cancelled, ignore further output from that generation, but still process its native completion signal for cleanup. Suppressing stale output must not suppress the completion needed to release resources.
- Coroutine/Flow cancellation alone is not evidence of native completion. Completion means the native operation is quiescent and its terminal result is reconciled exactly once.
- `release()` prevents new work, requests cancellation when necessary, awaits confirmed native completion, then closes conversation before engine. Make repeated cancel/release calls safe. Do not close a live handle merely because an acceptance deadline expired.
- If native completion cannot be confirmed, mark the runtime failed/unusable, prohibit new loads over uncertain resources, and surface a recovery requirement. A coroutine timeout does not kill a native thread. Capture a failed test rather than claiming successful release; process-level test recovery must not erase personal data.
- After cancellation, reset/recreate the test conversation before reuse unless the pinned runtime's continuation semantics are verified. Test cancel-then-generate explicitly.
- Document whether SDK stream events are deltas or accumulated text and normalize them once. Do not count callbacks/chunks as tokenizer tokens.

## R05: Manager, Resource and Backend Policy

Use Koin for a single application-scoped `ModelManager`. Keep portable admission/state/budget rules in shared code with fakeable clock, engine, and resource inputs. Android adapters provide filesystem, lifecycle, memory/thermal signals, and LiteRT-LM calls. Activities/ViewModels observe state and request operations; they never own engine handles. Retain the existing three-module architecture.

Host switching/rotation must not duplicate inference or unload the engine. On confirmed backgrounding/lock, cancel dev generation, release safely, and do not automatically resume it. Define presence coordination explicitly so the main-to-drawer transition is not mistaken for backgrounding. Proposed idle unload: 60 seconds with no request or active session lease, using an injected clock; benchmark runs explicitly hold/release their lease.

Preflight resource state before load/generate and observe it during work. Block new work and cancel safely on severe thermal pressure or known unsafe memory conditions; unknown signals are not healthy readings. The 8 GB device-memory metadata is not an available-memory guarantee.

The available-memory floor is 3 GiB both at admission and during generation. Admission rejects a single below-floor snapshot. During generation, three consecutive one-second below-floor samples trigger cancellation; a healthy sample resets the counter. This debounces brief RAM fluctuations without lowering the threshold. Android's `lowMemory` signal, severe thermal pressure, unknown resource signals and unsupported total RAM trigger cancellation on the first monitoring sample. This policy changed on 2026-10-13; earlier benchmark results are historical and do not verify the revised monitor.

- Emulator spike: explicit CPU, with supported ABI/resources verified first.
- S24 normal candidate: GPU preferred; CPU fallback only for classified, recoverable initialization failures after confirmed cleanup.
- Benchmarks: explicit backend with automatic fallback disabled; failed GPU runs stay GPU failures, not CPU results under a GPU label.
- Never restart a partially streamed answer on another backend. No retry loops around native crashes/hangs. Record requested backend, selected backend, and fallback reason.
- NPU and speculative decoding are not assumed or enabled for the initial baseline. Record reasoning/sampling settings explicitly. No cloud fallback exists.

## R06: Internal Models Panel

Add a feature-local Models screen/components/ViewModel under `ui/.../settings/`, with module-owned DI bindings. Expose readiness, artifact details, import/verify/repair, load/unload, synthetic test prompt, cancel, benchmark, and latest results labelled by actual device/backend.

Gate the panel and test entry points to development/internal builds, not just a hidden navigation item. Retain secure-window behavior for secure QA. Keep synthetic test state/results out of personal chat/history/memory and do not log arbitrary prompts or generated personal content. Do not label emulator metrics as S24 metrics or show an installed model as already running.

## R07: Benchmarks and Acceptance Gates

These are initial engineering gates, not measured claims. The first spike records raw observations only. A threshold change requires a documented reason, before/after values, and rerunning the affected acceptance case; do not retroactively turn a failed result into a pass.

| Measure | Initial S24 gate / definition |
| --- | --- |
| Cold load plus first token | p95 <= 12 seconds; engine unloaded at start; disclose filesystem/compiler cache state |
| Warm first token | p95 <= 3 seconds for <= 1000 actual input tokens; loaded engine, fresh conversation |
| Cancel latency | p95 <= 2 seconds from cancel request to reconciled native terminal state |
| Release from idle | p95 <= 3 seconds from release request to completed close |
| Release with active generation | p95 <= 5 seconds including cancellation/native completion and close |
| Short-prompt decode throughput | Record actual output tokens/sec; no hard throughput gate for first pass |
| Peak app PSS | Target <= 2.5 GiB for E2B/2048 first pass; exceedance requires an explicit decision, not a claim of success |
| Thermal abort | Stop sustained workload at Android thermal status >= 3 (SEVERE), or measured skin temperature >= 45 C |
| Sustained run | 20 minutes completed without ANR/OOM, unsafe continuation, or wedged cancel/release; an early safety stop is an incomplete/failed sustained gate |
| Battery | Before/after level, charging state and duration required; repeated paired idle comparison, charge/energy counters where available |

Android's public `PowerManager` thermal status is the app-side signal. Ordinary apps cannot assume access to `HardwarePropertiesManager` skin temperatures. For the supervised S24 run, record skin temperature from an available authorized test-harness source and stop at 45 C; missing skin readings are `unavailable`, not zero. Never substitute battery temperature for skin temperature. Document missing coverage; do not claim the skin gate was verified. If thermal monitoring is unavailable entirely, do not run the sustained benchmark unattended. These thresholds are project abort policies, not manufacturer safety guarantees.

Measurement protocol:

- Use monotonic timing. Separate load, prefill/first token, decode, cancellation, and release. Do not include warmup in warm timing; disclose its cost and reset conversation afterward.
- Run at least 20 measured repetitions per latency/configuration case for acceptance percentiles, report sample count and nearest-rank p95 plus raw samples; a one-run spike cannot establish p95. Record all failures rather than dropping them from reports.
- Pin synthetic prompts, formatted input/output token counts, sampling/reasoning settings, context/output limits, model hash, runtime/app revisions, backend, device/build facts, cache state, and charging state. Token metrics unavailable from the runtime must remain unavailable, not estimates from words/chunks.
- Cover short prompt, <=1000-token prompt, multi-turn, context-boundary rejection, cancel during prefill and decode, load/unload cycles, restart, post-reboot verification, and the 20-minute mixed workload. Device reboots are a supervised S24 test action, not part of routine emulator development.
- Sample PSS at a recorded cadence and call it sampled peak app PSS. Report native/GPU allocations separately when available; PSS is not proof of total GPU residency. Check post-release memory trend across repeated cycles for sustained growth.
- Record thermal throughout the run and a cooldown baseline between comparable cases. Battery percentage is coarse; short-prompt deltas or USB-powered runs do not establish energy efficiency.
- Store raw reports under ignored `benchmarks/results/`, with separate emulator/physical and backend identities. Check in only a sanitized summary with synthetic workload references, result status, and known limitations. No serials, private content, keys, tokens, model binaries, or personal database exports.

Verify first-load and subsequent generation with model already imported and networking unavailable. Inspect merged manifests/dependencies for unexpected network permissions or telemetry. Installation without network and inference without network are separate checks; no claim of zero feature traffic without evidence.

## R08: Tests and Completion

| Layer | Required evidence |
| --- | --- |
| `commonTest` | Manifest/config validation, Long size arithmetic, state transitions, resource admission, context budgeting, duplicate loads, fake engine that runs until cancelled, cancel/release races, stale output vs completion, deterministic idle unload |
| Android local/integration | Streaming size/hash enforcement, disk-full, import cancellation, immutable paths, orphan/pointer reconciliation, load failures, init-only fallback, native error mapping, process restart, real adapter cancellation/cleanup |
| Emulator UI/integration | Internal panel states, blocked/unsupported states, synthetic-only test flow, repeated open/close, main/drawer/rotation/background transitions; native smoke only on a verified compatible ABI |
| S24 acceptance | Repeat native smoke, explicit CPU/GPU results, memory and thermal/battery gates, cancel/reload, lock/background/host transitions, offline execution, supervised restart/reboot checks |

Use fakes for business rules and controlled callback ordering; reserve device tests for actual platform/native behavior. Synchronize tests using observable states/completion signals, not sleeps or retries that hide failures. Keep real model tests opt-in and distinguish skipped/blocked from passed.

P1A completes only when the pinned E2B artifact passes import/recovery, real S24 streaming/cancel/release/reload, meaningful failure behavior, host stability, and the recorded resource gates. E4B may be deferred with a reason. Existing deferred audio privacy/dictation work stays explicitly deferred; runtime acceptance does not close those issues.

## Implementation Order and First Spike

Implementation deviations and remaining gates: the current internal panel and installer were built while emulator/native prerequisites were being resolved. The emulator CPU and physical S24 CPU/GPU native spikes passed; the S24 20-sample GPU latency gates and one supervised 20-minute mixed synthetic run passed, while CPU memory exceeded the provisional target. A first sustained attempt failed the 3 GiB available-memory admission floor; the successful retry does not erase it. The current Kotlin SDK exposes post-generation token counts but no verified standalone preflight tokenizer; consequently the adapter accepts only three bounded synthetic fixture prompts, and arbitrary chat, long/context-boundary and multi-turn benchmark cases remain blocked. No automatic GPU fallback is enabled because failed-init cleanup has not been proved. The single-artifact installer uses the immutable hash path as its pointer and rehashes at startup; multi-version rollback is not implemented. The script-driven harness covers smoke/repetition/cancel/reload and a supervised sustained mixture of short/medium fixed prompts, cancellations and reloads, but not the full near-context or multi-turn protocol. P1A and P1B are not complete.

1. Land the ADR/planning manifest and check native dependency/ABI compatibility. No model file in Git.
2. Implement the smallest verified, internal-only emulator spike before building the full Models UI or general installer: stage and verify E2B, configure 2048 context/512 output, load, stream a synthetic prompt, cancel while active, await terminal completion, release, reload, generate again, and release.
3. Add the bounded installer and recovery tests, shared state machine/manager and out-of-band cancellation regression tests, then the internal panel.
4. Build the reproducible benchmark harness alongside adapter instrumentation, not after acceptance testing.
5. Schedule the S24 session once emulator development is ready. Repeat the smoke with the same pins and run physical acceptance. A native-emulator limitation remains an explicit gap until this session.
6. Proceed to P1B only after the runtime gate passes or a specific limitation is reviewed and accepted; do not silently bypass failed native safety gates.

The accepted [P1B conversation plan](P1B_CONVERSATION_PLAN.md) defines a request-specific pipeline through the existing shared session. Verified prompt budgeting, a correlated runtime API, separate user/assistant persistence transactions and state composition are mandatory before real chat reaches the UI. P1B persists assistant text only on completion, retains durable attempt status for cancellation/failure/process-death reconciliation, supports Stop/Retry without duplicate user turns, and reconstructs context after restart. Partial text remains transient. It does not introduce retrieval, OCR reasoning, speech transcription, attachment interpretation, actions, Gmail, or E4B default support ahead of their milestones.

## Sources

- [Pinned artifact pointer and SHA-256](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/raw/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm)
- [LiteRT-LM 0.17.1 Kotlin API](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.17.1/docs/api/kotlin/getting_started.md)
- [Runtime POM and license/dependency metadata](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/0.17.1/litertlm-android-0.17.1.pom)
- [Android thermal status](https://developer.android.com/reference/android/os/PowerManager#THERMAL_STATUS_SEVERE) and [temperature API access restrictions](https://developer.android.com/reference/android/os/HardwarePropertiesManager#getDeviceTemperatures(int,%20int))
