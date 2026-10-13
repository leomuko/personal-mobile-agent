# Phase 1 Implementation Evidence

Initial evidence: 2026-10-02. Updated: 2026-10-13. Branch: `dev`. Recorded for the Phase 1 development checkpoint.

Follow-up: [QA revisions and verification on 2026-10-13](PHASE_1_QA_REVISIONS.md) add explicit text extraction, visible command rejection and in-flight available-memory monitoring. Focused S24 model tests and a subsequent native GPU smoke passed. The detailed 2026-10-02 measurements below predate that policy change; sustained validation of the revised monitor remains pending. Full S24 instrumentation and release dependency packaging remain open.

**Status: P1A development foundation implemented; emulator CPU and S24 CPU/GPU native smoke verified. The 20-repetition S24 GPU latency gates passed, but Phase 1 acceptance is not complete. P1B ordinary assistant chat remains gated.**

## Implemented

- Trusted Gemma 4 E2B catalog and manifest-consistency regression: immutable revision/SHA, Long byte counts, 2048 configured context and 512 maximum output tokens. LiteRT-LM 0.17.1 is pinned and coroutines core/Android/test aligned to 1.11.0.
- Bounded off-main-thread private import with size/storage preflight, streamed hashing, cancellation, disk-full handling, file/directory sync, immutable promotion, corruption repair and startup re-verification. A single pinned hash path is the installation source of truth; no model blob is stored in SQLDelight.
- Android LiteRT-LM adapter for CPU/GPU selection, text-only streaming, SDK token metrics, out-of-band cancellation, fresh conversations after cancellation and terminal-before-close handling. Unconfirmed cancellation/initialization/release quarantines the runtime rather than retrying over uncertain handles. Automatic backend fallback is deliberately disabled.
- One application-scoped Koin manager with separate asset/runtime states, duplicate-work admission, release admission barrier, memory/thermal policy, 60-second idle unload, and Activity presence coordination.
- Internal-only Settings Models panel and feature-local ViewModel for import, verify, load, fixed test prompts, cancel, unload and latest timing/token output. Release builds cannot trigger these development actions. Installing a test model does not activate normal assistant replies.
- Script-driven, opt-in native harness with synthetic-only reports, raw latency samples, SDK token/rate metrics, 500 ms PSS/thermal samples, battery/charging state, explicit backend and APK/model/runtime provenance. A small run cannot pass p95 acceptance gates.

## Verified

- Debug and unsigned release APK builds, shared JVM tests, Android debug unit tests, UI JVM compilation and debug lint passed. Shared suite: 40 tests; Android local suite: 6 tests. Lint: 0 errors, 20 warnings; existing deprecation/freshness warnings remain.
- Isolated secure-QA emulator regression suite: 48 tests passed. This includes eight asset/import/catalog tests and one Models panel state/control test. The separately opted-in native test class is excluded rather than counted as a normal-suite pass.
- ARM64 emulator CPU native smoke passed: load, nonempty streaming, cancel while streaming, prompt after cancel, active release, reload, another prompt and idle release.
- Native host test passed: activity recreation and main/drawer handoff retained a usable runtime; backgrounding released it; foreground reload/generation worked. This is not a physical lock/call/reboot matrix.
- Host model file is exactly 2,588,147,712 bytes and matches SHA-256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`. It is ignored by Git. The app rehashed the developer-staged private copy before loading.
- AAR inspection: minSdk 24; ARM64 and x86-64 JNI binaries have 16 KB ELF LOAD alignment. QA APK zip alignment passed. x86-64 native execution was not tested. Debug/release merged app manifests have no INTERNET permission; network-disconnected/reboot acceptance remains separate work.

The dedicated `EdgeCompanion_Gemma_8GB` AVD was cold-restarted with user approval, without wiping data. It reported 8,129,336 KiB total RAM, approximately 7.75 GiB usable. Existing `/data` remains about 6 GB even though 16 GB is configured. To avoid two multi-gigabyte copies on that partition, this native spike used a single developer-staged copy in the QA app's private model directory. Synthetic instrumentation, not this staging step, verifies the importer. Real-provider import of the full artifact remains a manual acceptance case.

## Initial Emulator Observations

These are individual CPU smoke observations, not p95, S24 performance, battery-efficiency or release-readiness claims. Native cold timing excludes hash verification and filesystem caches were not cleared.

| Observation | Final smoke sample |
| --- | ---: |
| Load | 8,198 ms |
| Cold load plus first nonempty output | 11,631 ms |
| Warm first output | 421 ms |
| Cancel through terminal reconciliation | 22 ms |
| Idle release | 79 ms |
| Active release | 138 ms |
| SDK short decode rate | 21.33 tokens/s |
| Sampled peak app PSS | 3.48 GiB |

**Memory exceeds the provisional 2.5 GiB target.** Do not change the target to turn this into a pass. Profile on S24 with explicit CPU/GPU runs before deciding on configuration changes. Emulator thermal status stayed at 0 and battery at 100% while plugged in; neither establishes phone thermal/battery behavior. Skin temperature was unavailable.

The final sample and both passing native tests are in ignored `benchmarks/results/native-20261002T034734Z-CPU/`. Earlier reports are retained under `native-20261002T034250Z-CPU/` and `native-20261002T034425Z-CPU/`; timings varied with emulator/host conditions. The final report explicitly records `pssTargetMet: false`. The native script preserves failures; JSON smoke status alone does not replace the accompanying instrumentation result for host tests.

## S24 Ultra Native Evidence

The connected S24 Ultra reported Android 16, ARM64, approximately 10.8 GiB total RAM, and more than 58 GB free on `/data` before model staging. The isolated `secureQa` app and test APK were updated without clearing their data; the 2,588,147,712-byte model was staged once in that app's private no-backup directory and reverified against the trusted SHA-256 on-device. No unrelated app was stopped. The synthetic test prompts and results did not enter personal chat. These runs used USB charging and did not disconnect the phone's network.

| Observation | CPU smoke, n=1 | GPU repeated, n=20 |
| --- | ---: | ---: |
| Cold load plus first output | 14,777 ms (individual) | p95 11,330 ms, gate <=12,000 ms: pass |
| Warm first output | 982 ms (individual) | p95 221 ms, gate <=3,000 ms: pass |
| Cancel to reconciled terminal | 43 ms (individual) | p95 46 ms, gate <=2,000 ms: pass |
| Idle release | 300 ms (individual) | p95 400 ms, gate <=3,000 ms: pass |
| Active release | 338 ms (individual) | p95 457 ms, gate <=5,000 ms: pass |
| Sampled peak app PSS | 3.46 GiB, over target | 1.90 GiB, within provisional 2.5 GiB target |
| Short decode rate | 16.06 tokens/s (individual) | recorded per sample in raw report |

Both native test cases passed on each backend: load/stream/cancel/reload and main/drawer/background/foreground handoff. The repeated GPU run took about 9.2 minutes and produced 20 samples per latency measure; its nearest-rank p95 values are from that run only. Engine-unloaded timing is not a device cold boot and excludes the model verification pass; OS page-cache state was uncontrolled. The S24 GPU run's supervised HAL skin readings ranged from 36.3 C to 42 C and Android thermal status reached 2, below the abort limits of 45 C/status 3. Battery was 25% before and after while USB powered; that is not an energy-efficiency result. The CPU one-sample cold timing and PSS exceed the provisional targets, so CPU cannot be treated as an accepted fallback without a reviewed decision and more evidence.

Raw reports: `benchmarks/results/native-20261002T084317Z-CPU/` and `benchmarks/results/native-20261002T085555Z-GPU/`; thermal samples: `benchmarks/results/s24-thermal-20261002T084317Z/` and `benchmarks/results/s24-thermal-20261002T085555Z/`. These directories are ignored by Git and contain no serial number, prompt response text, or personal data.

### Supervised Mixed Workload

The first sustained attempt stopped after roughly 22 seconds when the app's read-only available-memory signal dipped to 2.94 GiB, below the 3 GiB admission floor. No mixed prompt was admitted. This is retained as a failed attempt in `benchmarks/results/native-20261002T091603Z-GPU/` and is not folded into passing samples. The phone's running apps and settings were left unchanged; memory later recovered naturally and thermal status returned to 0.

The single supervised retry completed **20 sustained minutes** on GPU: 400 alternating short/medium synthetic prompts, 80 mid-stream cancellations, 40 unload/reload cycles, and both native instrumentation tests passing. The fixed prompts measured 17 and 129 input tokens. The full instrumented session took 20.9 minutes; the external thermal log spans 21.5 minutes including build/install and the second lifecycle test. Its 255 five-second samples reached 43.4 C skin and status 2; no safety stop occurred. The minimum sampled available RAM was 3.01 GiB, narrowly above the admission floor. Sampled peak app PSS was 1.90 GiB, below the provisional 2.5 GiB target. First-half and second-half sampled peaks were approximately 1.902 and 1.905 GiB; this is not a full post-release memory-leak assessment. Battery moved from 29% to 27% while USB powered, so a paired idle/active energy comparison remains necessary.

The completed report is `benchmarks/results/native-20261002T092338Z-GPU/`; the separate thermal trace is `benchmarks/results/s24-thermal-20261002T092338Z/`. `acceptanceComplete` remains false because this mixed run does not include near-context input, real multi-turn conversation, prefill-specific cancellation or recovery from native faults. Its first-output timing is the first nonempty streamed callback, not a separately exposed single-token timestamp.

## Fixes Discovered During Verification

- Qualified manager cancellation to avoid accidentally resolving coroutine-scope `cancel()`; corrected the adapter's nullable Unit return.
- Published native conversation ownership before cancellable dispatcher boundaries, suppressed late/empty output, prevented a Finished event following an error, and made cancel signalling idempotent.
- Added release admission and uncertain-cleanup tests, streamed-import cancellation/disk-full tests and directory-sync handling.
- Fixed the opt-in JUnit method's inferred non-void return. The original instrumented run failed on that declaration; the corrected test subsequently executed successfully.
- Kept native tests outside ordinary secure QA. An assumption skip had been treated as an error by the intentionally strict QA script; explicit exclusion keeps native evidence separate.

## Open Acceptance Work

1. Obtain paired idle/active battery baselines and inspect post-release memory trends more directly. Repeat CPU profiling or explicitly reject CPU as a fallback under the current 2.5 GiB target. The low-memory abort during the first sustained attempt needs a repeatability/resource-policy decision; do not hide it behind the completed retry. E4B stays deferred until E2B viability is accepted.
2. The Kotlin API's pre-generation tokenizer/template budgeting path is not verified. Only three bounded synthetic prompts are accepted by the adapter. Long-input, exact context-boundary, multi-turn and arbitrary personal chat are not implemented by this spike.
3. GPU initialization cleanup/fallback classification, prefill-specific cancellation, native error/recovery injection, screen lock, disconnected-network operation, full provider import, process/reboot restoration with weights, and post-release memory trends need further evidence.
4. Full benchmark UI/history and multi-version rollback remain follow-up work; the current harness is script-driven and the installer has one immutable catalog entry. The completed sustained case mixes two bounded synthetic prompt fixtures, cancellation and reload cycles, but does not cover a verified near-context prompt or true multi-turn conversation.
5. Distribution signing and transitive native-license notice review remain required. Release APK compilation is not release acceptance.
6. P1B context construction, persistent assistant replies/partial state, stop/retry and conversation reconstruction are intentionally not enabled before the agreed runtime/device gate. Deferred AUDIO-001/AUDIO-002 are unchanged.

See [Phase 1 contracts](../planning/PHASE_1_PLAN.md), [runtime ADR](decisions/0003-gemma4-litert-lm.md), and [benchmark commands](../benchmarks/README.md).
