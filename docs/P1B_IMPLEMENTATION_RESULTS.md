# P1B Implementation Evidence

Date: 2026-10-13; updated 2026-10-14. Branch: `dev`. Implementation is recorded in focused P1B commits; no push has been performed for this batch. The internal pipeline and focused native/process-restart checks have passing evidence, but supervised S24 native acceptance remains yellow; broader mobile/release acceptance is open.

## Implemented

- Request-correlated chat API separate from synthetic test prompts, explicit terminal outcomes, immutable message snapshots, runtime ownership/admission and Models-panel exclusion. Each chat request creates a fresh native conversation with structured initial history; no application-side prompt templating is sent to the SDK.
- Conservative, model-specific prompt bound tied to the pinned artifact: `128 + sum(96 + 3 * UTF8(payloadBytes))`. Thinking, tools, system messages and multimodal content are disabled. The fixed template adds fewer than 32 ASCII bytes per message plus a short BOS/generation prefix. Identity normalization has no character map or dummy prefix; escaping a space expands it to three UTF-8 bytes. BPE merges cannot increase the initial byte-fallback piece count. The reserves cover template boundaries and output remains separately budgeted. Malformed surrogate sequences and reserved control markers are rejected. Actual SDK prefill counts are checked against the bound before reporting successful completion.
- At 2048 total context and 512 reserved output, one current ASCII message admits at most 437 bytes. Unicode consumes more bytes; retained complete turns consume budget. Oversized current input keeps the draft; old complete pairs are removed together. This conservative restriction is explicit and can be relaxed only with verified exact counting or a revised proof.
- SQLDelight migration 3 -> 4: existing rows become USER with their original IDs/turn linkage; assistant attempts store pending/terminal status. Assistant text is persisted only on completion. Its transaction does not attach pending files, clear the next draft or advance the submission epoch. Search indexing and completion idempotency are tested. Reopen marks pending attempts INTERRUPTED.
- Durable controller state and transient generation state compose into one session state. Both hosts render roles, streaming output, Stop and retry. Clearing invalidates callbacks and blocks admission until cancellation/deletion finish. Retry reuses the committed user message. Cancel during initialization preserves ownership before native release. Generation remains disabled in release builds.
- Attachment-only submissions remain saved user turns without inference; mixed submissions send text only. Capture/voice interpretation and deferred audio policies are unchanged.

## Budget Verification

The host-only verifier uses SentencePiece 0.2.0 and protobuf 5.28.3, pinned in `scripts/requirements/chat-budget.txt`. The wrapper creates an isolated environment in ignored `build/token-verification/` and installs those requirements. Python 3.9+ with venv/pip is required; initial package installation needs network access or cached wheels. It verifies the full model hash and embedded tokenizer hash, BPE/byte-fallback flags and identity normalization fields. It tests 2,009 single-turn inputs and 1,000 history cases, including randomized Unicode, whitespace, combining marks, compatibility characters and emoji, against the audited text-only template. The app does not depend on Python or these host packages.

Tokenizer SHA-256: `e594c8a90eb08d8bda498ff4747977dc827ae0c3c56b5c0d41a605a22d02ef03`.

```sh
bash scripts/verify-chat-budget.sh
# For an artifact elsewhere:
bash scripts/verify-chat-budget.sh --model /absolute/path/gemma-4-E2B-it.litertlm
```

Weights are not included in Git. Place the approved hash-pinned artifact at `models/gemma-4-E2B-it.litertlm` or supply `--model`. Neither script downloads weights. `PYTHON` selects the setup interpreter; `CHAT_BUDGET_VENV` selects a separate environment. Missing dependencies/model files produce actionable errors rather than an unexplained import traceback.

Official contract references: [LiteRT-LM pinned container schema](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.17.1/schema/core/litertlm_header_schema.fbs), [pinned Gemma text processor](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.17.1/runtime/conversation/model_data_processor/gemma4_data_processor.cc), and [SentencePiece normalization schema](https://github.com/google/sentencepiece/blob/v0.2.0/src/sentencepiece_model.proto). The template audited for this artifact is the one embedded in the hash-pinned weights; the repository template alone is not treated as its identity.

## Verification Status

Passed: debug/release APK builds, UI JVM compilation, shared JVM tests (58), shared Android-host tests (43), Android unit tests (6), and debug lint (0 errors, 23 warnings). Coverage includes migration, draft-during-streaming, duplicate submission, clear/completion/admission races, stale callbacks, retry, save failures, cancellation, resource faults and initialization ownership. The full secure-QA emulator suite passed 49 tests. A final focused emulator rerun passed 1 test covering the microphone guard and streaming visibility adjustments made afterward.

The first native attempt lost its ADB transport with no terminal result; this is inconclusive. A subsequent S24 attempt failed with LOW_MEMORY after loading, before a completed assistant reply. The user turn remained saved and no completed assistant message was manufactured. These attempts are retained in ignored reports and are not counted as passes.

After RAM recovered naturally, the supervised S24 conversation test passed (1 test, approximately 43 seconds). It exercised real Unicode text input, structured history, main/drawer handoff, Activity recreation, draft edits during streaming, cancellation, release/reload, retry and reopen. It confirmed two user turns and two completed assistant responses without duplicating the cancelled turn. The 11 thermal samples reached 37.8 C skin and status 0, with no safety stop. Evidence: `benchmarks/results/chat-20261013T194721Z/`. No resource threshold was reduced and no unrelated apps were closed.

The separate S24 process-stop/reopen scenario passed both its seed and verify instrumentation phases. A synthetic pending attempt became INTERRUPTED in a new process, while the unsent draft, user ID/role, and encrypted attachment bytes/linkage survived. No partial text or active generation reappeared. Evidence: `benchmarks/results/chat-restart-20261013T195048Z/`. This seeds pending metadata rather than killing native inference mid-decode.

The final two physical Compose UI tests initially failed to find a hierarchy while the phone's keyguard was showing. That locked-device attempt is not counted as a pass. After the user unlocked the S24, the focused rerun passed both `AssistantPresentationTest` and `ModelsPanelTest` (2 tests, approximately 2 seconds), verifying role-aware streaming, Stop/retry, microphone disabling during generation, and Models-panel controls. These synthetic UI checks are separate from the successful native and storage tests.

The historical P1A runtime smoke and sustained workload do not certify this new chat pipeline. The existing broad S24 accessibility/permission/notification harness failures and release-native packaging issue remain separate open work. Full provider import, disconnected-network testing, process death during actual native decoding, device reboot restoration and release distribution acceptance remain open. This implementation does not claim the whole Phase 1 or mobile matrix is complete.

## Native Command

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ANDROID_SERIAL=DEVICE_ID bash scripts/verify-conversation-native.sh
```

This installs only the isolated QA APKs and uses synthetic chat fixtures. It checks real generation with Unicode and history, host handoff, draft preservation, Stop, release/reload, retry without duplicate user messages, and Activity reopen. It does not export chat/database contents or prove process-death restoration. Native tests are excluded from the ordinary secure-QA suite.

The wrapper now retains build/install/preflight/instrumentation logs, timestamps, APK hash, working-tree statistics and a final `result.json`, even on exits before instrumentation. The old empty report lacks enough evidence to identify its exact failure; it is not retrospectively counted as a pass. Host Node.js is required. Preflight waits at most 60 seconds (configurable `CHAT_PREFLIGHT_SECONDS=10..300`) for three consecutive healthy samples at five-second intervals. Low memory or insufficient load headroom waits; unknown/unsafe thermal or unsupported hardware blocks immediately. It checks Linux MemAvailable against the unchanged 3 GiB runtime floor plus a conservative 2.5 GiB load reserve (the existing peak-PSS target), totaling 5.5 GiB before loading. It also checks 7 GiB total memory and the existing thermal limits. This is a supervised-test reserve, not a new application admission limit or guaranteed memory bound. Host samples are advisory: the app's ActivityManager/lowMemory admission and monitoring policy remains authoritative and unchanged. No app is closed to free memory, and there is no automatic inference retry. A preflight block exits 2; native failures remain failed, not silently retried. The thermal supervisor starts immediately before instrumentation, and a missing/dead supervisor cannot produce a pass. Native checkpoints report only runtime/resource state, not conversation content.

Host harness regressions:

```sh
node --test scripts/tests/chat-resource-preflight.test.mjs
```

## QA Follow-Up: 2026-10-13

- Reproducibility: a fresh `/private/tmp` virtual environment installed the pinned dependencies through the new wrapper and passed all 2,009 single-turn / 1,000 history cases. A missing model path produced an actionable error and nonzero exit. No pre-existing virtual environment was required.
- Local validation: debug build, shared JVM/Android-host tests, Android debug unit tests and lint passed. An initial command incorrectly combined `testDebugUnitTest` with the secure-QA test variant; Gradle rejected that invocation. The corrected debug baseline and separate secure-QA APK/test builds passed. Four host regressions passed, including load-reserve boundaries, bounded/stable resource sampling and build-failure report retention. Shell syntax and diff whitespace checks passed.
- Attachment guidance now appears only for unsent attachments or attachments linked to the actively generating user turn. Historical attachments alone do not trigger it. The S24 focused UI run passed 3 tests (approximately 3.3 seconds): both AssistantPresentationTest cases and ModelsPanelTest, including draft/current-turn/history transitions, Stop/retry, microphone disabling and model controls.
- The first diagnostic wrapper run failed with a null model error (`chat-20261013T205244Z-x49HS7`); it remains failed. The test previously dismissed the drawer without explicitly establishing the full-app host. The test now models Expand by starting the main host and waiting for RESUMED before drawer dismissal, rather than assuming the OS foreground task. This does not change production lifecycle policy or retrospectively explain the earlier interruption.
- The next diagnostic run failed LOW_MEMORY (`chat-20261013T205451Z-vwrFNX`): approximately 4.46 GiB was available at submission, but after loading only 2.93 GiB remained. The unchanged 3 GiB floor rejected it; attempt status/error and native cleanup are captured. This revealed why a preflight checking only the unloaded floor was insufficient.
- After adding the 2.5 GiB load reserve, the final wrapper run correctly blocked before instrumentation with LOAD_HEADROOM after a 15-second wait (`chat-20261013T205611Z-jAfRI1`). Available RAM stayed about 4.5 GiB, below the 5.5 GiB supervised-test reserve. `result.json` records blocked/resource-preflight/exit 2, with all stage logs retained. No inference was launched and no unrelated apps were closed. The gate is still yellow: this revision validates safe blocking and diagnostics, not a clean native acceptance pass in the current phone state.

## Final Review Fixes: 2026-10-14

- One shared `AssistantTopBar` now serves Chat, Settings, Memory, Sources and both conversation hosts. It has an 80 dp minimum body height, a consistent 40 dp mascot, fixed 48 dp controls, aligned title/status content and a theme-token divider. Large text can grow the bar vertically. The full app supplies a navigation-menu slot; the floating drawer supplies Expand/Close actions without adding a menu. Colors come from surface/onSurface/onSurfaceVariant/primary/outlineVariant, not header-local hex values. The drawer handle also uses the outlineVariant token.
- The Python verifier now uses explicit conditional checks and `SystemExit` for model/tokenizer hashes, tokenizer contract flags, truncated sections and budget overflow. It prints success only after all checks complete. Five host regressions pass under `python -O`, including both digest checks, all tokenizer assumptions, budget boundaries, wrong-artifact subprocess rejection and a guard against reintroducing optimizable assertions. The full pinned model verifier passed in normal and optimized mode: 2,009 single-turn and 1,000 history cases each.
- Local debug build, shared JVM/Android-host tests, Android debug unit tests, debug lint and shared UI JVM compilation passed. Lint: 0 errors, 23 existing warnings. Secure-QA app/test builds passed. The four resource-harness host regressions and diff checks passed. No release APK or full instrumentation suite was rerun for this focused cleanup.
- On the unlocked S24, `AssistantTopBarTest` plus `AssistantPresentationTest` passed all 4 tests (approximately 6.3 seconds). Checks cover consistent geometry across Chat/Settings/Memory/Sources and both hosts, mascot/control dimensions, title/status alignment, compact 320 dp width at font scale 1.5, action overlap, and actual rendered surface/text/icon/status/divider colors under a deliberately different test theme. Existing Stop/retry and attachment-guidance regressions passed alongside them. Pixel checks use synthetic test content; no personal screenshots were exported.
- The supervised native wrapper was run once after the cleanup and reported BLOCKED at resource-preflight, exit 2 (`benchmarks/results/chat-20261014T062138Z-5c3x9T/`). Available memory was approximately 3.9 GiB, below the unchanged 5.5 GiB supervised-test load reserve. The app's 3 GiB runtime floor was not changed, no inference started, and no unrelated apps were closed. This is not a native acceptance pass. P1B UI/verifier revisions are complete; supervised native acceptance remains yellow until a clean wrapper run passes.
- Scope remains text-only. Attachments are preserved/previewed without interpretation; Models controls remain internal runtime tooling. Memory and Sources remain shell screens, with source/text/PDF and memory work in P2; scanned-page/receipt OCR and expense extraction remain P5.

Reproduce the verifier checks after the setup command above:

```sh
build/token-verification/bin/python -O -m unittest discover -s scripts/tests -p 'test_chat_budget.py' -v
build/token-verification/bin/python -O scripts/verify-chat-budget.py
```

For the separate synthetic process-restoration scenario:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ANDROID_SERIAL=DEVICE_ID bash scripts/verify-conversation-restart.sh
```

That scenario replaces only isolated QA conversation fixtures and force-stops only the QA package between the two instrumentation processes. It leaves model weights and the ordinary app package intact.
