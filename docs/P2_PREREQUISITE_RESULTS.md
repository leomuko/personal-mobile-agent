# P2 Prerequisite Results

Date: 2026-10-18. Branch: `dev`, implementation changes uncommitted above `ac0f24a`.

**P2 is not complete.** This delivery implements prerequisite adapters and focused checks, not Sources/Memory/retrieval UI. Encoder adoption is blocked on verified preflight counting. The accepted gate-first contract is preserved; no context, output reservation, ordinary-app memory floor or deferred feature was relaxed to manufacture readiness.

## Implemented

- Artifact-specific Gemma prompt-bound v2: `128 + sum(96 + UTF8(payloadBytes))`, including the pinned NORMAL metaspace proof. Current-message capacity is 1312 ASCII bytes without history, with 2048 context and 512 output unchanged. All grounded instructions/evidence would share this budget. Actual SDK prefill-count checking remains enabled.
- Shared typed document/input/reader contracts; Android Tink 1.18.0 authenticated streaming, versioned authenticated header and source/revision binding, 64 KiB application reads, actual 20 MiB size enforcement, immutable publication, full authentication before reader publication, bounded seek reads and two reader slots per store. Dispatcher-handoff cancellation closes unpublished readers.
- Android Keystore-encrypted Tink keysets, atomic persistence and namespace binding. Missing keys/keysets, corruption and unreadable storage fail without resetting content. No plaintext keyset fallback.
- Publication/deletion coordination uses a per-root in-process mutex and OS file lock. The existence check and rename happen together under that lock; another cooperating adapter/process cannot replace a published revision. The lock file is empty coordination metadata, not a document or key.
- A read-only Android proxy file descriptor bridges authenticated seek reads to the native PDF parser without a plaintext temporary PDF. S24 synthetic two-page extraction works at API 35+; corruption is rejected before descriptor publication.
- QA-only MediaPipe 1.1.1 encoder lifecycle spike in a request-specific isolated process. Cancellation is scoped by request ID and confirmed by Binder death. Parent-death handling, a fixed five-second worker deadline, parent resource-policy samples every 100 ms, 500 ms worker leases and a sampled 384 MiB worker-PSS ceiling constrain the probe. Diagnostic sends are not in front of its independent stop.
- Isolated processes skip normal application/Koin/database initialization. Inherited SDK network permissions are removed from the QA manifest; a device regression checks that Internet/network-state permissions are absent.
- Reproducible, host-only metadata auditor with pinned dependencies and a supervised, run-correlated native lifecycle harness. Public weights are verified and imported only into the QA package; the app does not download them.

These adapters are not wired into a production source repository or foreground retrieval scheduler. Existing drafts, conversation/attachment persistence, mascot behavior and deferred audio behavior remain unchanged.

## Passed

Local checks:

| Check | Result |
| --- | --- |
| `:shared:jvmTest` | 82 tests, zero failures |
| `:shared:testAndroidHostTest` | 77 tests, zero failures; includes 10 document-storage regressions |
| `:androidApp:testDebugUnitTest` | 13 tests, zero failures |
| `:androidApp:testSecureQaUnitTest -PtestBuildType=secureQa` | 13 tests, zero failures |
| Debug APK, QA APK and QA instrumentation compilation | Passed |
| `:androidApp:lintDebug` | Passed, zero errors, 27 warnings; includes a newer-version advisory for the pinned Tink dependency |
| `node --test scripts/tests/*.test.mjs` | 27 host regressions passed |
| Budget verifier tests under `python -O` | Five passed |
| Full pinned-model budget verifier, normal and optimized Python | 2009 single-turn and 1000 history cases passed in each mode |
| Encoder-audit setup from a fresh isolated environment | Pinned packages installed; structured audit completed |

Storage regressions include wrong keys, corrupt headers/tags, truncation, swapped/duplicated segments, source/revision substitution, segment authentication after a later seek, immutable version/path collisions, concurrent publisher instances, false/unknown input sizes, provider failures, writer-wait cancellation, reader-slot recovery and deterministic cancellation at dispatcher handoff. These tests do not replace a future provider-stall or process-restart acceptance matrix.

S24 Ultra (`SM-S928B`), isolated Assistant (QA):

- `ProtectedDocumentKeyTest` + `ProtectedPdfAccessTest`: **OK (6 tests)** on the final file-lock implementation. Raw synthetic instrumentation output: ignored `build/p2-document-instrumentation.txt`.
- `EmbeddingPrerequisiteTest`: **OK (1 test)** with three native cases: cold/warm, cancellation of an active repeated batch, and fresh-process recovery. Final evidence: ignored `benchmarks/results/p2-encoder-gate-rLEonL/`, including APK/source hashes, policy/preflight, thermal/supervision, instrumentation and encoder metadata.
- Explicit profile: `memory-android-pressure-test-v1`; Android reported `lowMemory=false` and thermal status 0. Ordinary debug/release memory policy is unchanged. No Gemma load/generation or sustained workload ran in this probe.

Final native encoder observations (single cases, not percentiles):

| Observation | Result |
| --- | --- |
| Cold worker startup, verified asset, load, two encodes, confirmed death and client cleanup | 530 ms |
| SDK cold load | 108 ms |
| First/second encode | 34 / 31 ms |
| Cancel request to confirmed worker death | 28 ms |
| Fresh-process recovery through cleanup | 437 ms |
| Maximum recorded worker PSS | 95,268 KiB, approximately 93.0 MiB |
| Observed SDK output | One 100-dimensional, finite, L2-normalized vector |

The prototype leaves Models-panel/native arbitration for the production coordinator; its isolated measurements do not certify concurrent UI operations, foreground encoder-to-Gemma handoff or semantic quality. Do not use it as approval for unattended mixed workloads.

## Failed Attempts

Failed development attempts were not relabeled as passes:

- `p2-encoder-gate-c0iQgR`: Android rejected hyphenated isolated instance names before native work; the instance identifier was corrected.
- `p2-encoder-gate-yFxbTC`: no phase/terminal result; this motivated typed diagnostics, not a longer deadline.
- `p2-encoder-gate-8xqDK1`: the isolated UID's resource precheck failed with `NullPointerException`. Resource policy sampling moved to the owning app; the worker now requires fresh, fail-closed leases. The same thermal/OS-pressure/admission policy remains enforced.
- A proposed hard-link publication method passed host tests but was denied by S24 filesystem policy: six tests ran, four failed with `AccessDeniedException`. It was replaced with the cooperating-process publication lock, and the final six-test device run passed. The failed raw terminal output was not archived as a report file.

The earlier encoder lifecycle pass in `p2-encoder-gate-Yty8hz` remains separate from the final source/cleanup recheck in `p2-encoder-gate-rLEonL`.

## Blocked Gate

The candidate is not approved for free-form semantic retrieval. Its audited graph contains three `RaggedTensorToTensor` shapes of `[-1, 192]`. The optimized SentencePiece configuration is 384,344 bytes, SHA-256 `ab32418a9a7f2c0c95e3db6233f2fbd8243e2929a84cd5feec9c1b1ca615c420`, with 8000 piece scores and a 60,382-byte normalization replacement table. It is not the identity-normalized Gemma tokenizer and not a SentencePiece ModelProto. The public SDK/provider exposes embedding but no exact-count API.

Consequently, neither a successful vector nor the Gemma byte proof proves that a source chunk/question avoided clipping. The host audit explicitly reports `semanticAdoptionReady: false`. Resolve a vetted exact-count bridge or select another fully verified encoder before chunking/indexing/query embedding is enabled. Do not silently clip text, substitute a guessed ratio, increase the 192-token shape, or treat lifecycle evidence as this gate passing.

## Not Run / Not Implemented

- New representative grounded Gemma prompt/native token acceptance; ordinary chat's new bound has host proof, not a new S24 generation pass.
- Production native reservation, indexing priority, Models-panel BUSY/cancel ownership and the complete five-second retrieval deadline.
- Untrusted PDF worker isolation, parser time/memory/output limits, real provider cancellation, password/scanned-page recovery and API 31-34 PDF extraction compatibility. The synthetic protected-FD result proves input access only.
- Library schema/quota/restart reconciliation, Sources browsing/keyword search, semantic ranking, durable citations, explicit Memory and transactional context fences. Existing screens remain shells.
- Full mobile permission/appearance/lifecycle matrix, 70-question grounding evaluation, maximum-capacity retrieval and the consolidated mixed 20-minute P2 campaign.
- The separately deferred standalone Gemma smoke, release packaging and deferred audio changes.

## Reproduce Focused Checks

```sh
./gradlew :androidApp:assembleDebug :shared:jvmTest :shared:testAndroidHostTest \
  :androidApp:testDebugUnitTest :androidApp:lintDebug --console=plain
bash scripts/verify-chat-budget.sh --model /absolute/path/gemma-4-E2B-it.litertlm
bash scripts/audit-encoder.sh /absolute/path/universal_sentence_encoder.tflite
node --test scripts/tests/*.test.mjs
```

The encoder auditor requires Python 3.9-3.12 and creates ignored `build/encoder-verification/`; initial host package installation may require network access. It does not download weights or enable semantic readiness.

For the opt-in native encoder gate, first build and install matching secure-QA app/test APKs with `-PtestBuildType=secureQa -PandroidPressureMemoryTest=true`, then use the designated S24 serial and hash-pinned public candidate:

```sh
ANDROID_SERIAL=YOUR_DESIGNATED_DEVICE P2_ENCODER_MODEL=/absolute/path/universal_sentence_encoder.tflite \
  node scripts/run-p2-embedding-gate.mjs --android-pressure
```

The harness validates the installed shared policy, requires a live supervisor, preserves failure reports, checks fresh run-correlated evidence and never issues package-wide force-stop. It is a focused prerequisite, not the final P2 campaign.
