# Phase 1 Commit Plan

Prepared: 2026-10-13. Branch: `dev`. The user authorized this nine-commit sequence; pushing remains outside that authorization.

This is a development checkpoint for the P1A runtime foundation. It is not full P1A acceptance, P1B chat integration or a release-ready build. The evidence refresh described in commit 9 is included in this sequence.

Use the order below so contracts precede their consumers. Include each new file with its regression coverage rather than first committing an intentionally defective implementation and then committing its correction. The nine groups do not require splitting existing source files or staging partial hunks.

## 1. feat(inference): define pinned model catalog and runtime contracts

- `.gitignore`
- `planning/models/gemma-4-e2b-it.json`
- `shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/Inference.kt`
- `shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/ModelManifest.kt`
- `shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/ModelAssets.kt`

Scope: immutable E2B metadata, explicit context/output limits, asset/runtime/error models, vendor-independent engine events, bounded synthetic fixtures, and exclusion of model weights from Git.

## 2. feat(models): add verified private imports and recovery tests

- `shared/src/androidMain/kotlin/dev/edgecompanion/core/inference/assets/PrivateModelAssets.kt`
- `androidApp/src/androidTest/kotlin/dev/edgecompanion/app/ModelAssetsTest.kt`
- `androidApp/build.gradle.kts`

Scope: streamed bounded import, hash/storage checks, atomic promotion, startup reconciliation, cancellation and corruption handling. The Gradle change bundles the trusted planning manifest into the instrumentation APK for catalog consistency checks.

## 3. feat(litert): add pinned native adapter and text extraction tests

- `gradle/libs.versions.toml`
- `shared/build.gradle.kts`
- `shared/src/androidMain/kotlin/dev/edgecompanion/core/inference/litert/LiteRtLlmEngine.kt`
- `shared/src/androidHostTest/kotlin/dev/edgecompanion/core/inference/litert/LiteRtMessageTextTest.kt`
- `androidApp/src/main/AndroidManifest.xml`

Scope: LiteRT-LM 0.17.1, aligned coroutines, explicit CPU/GPU backends, streaming, out-of-band cancellation and guarded cleanup. Extract only `Content.Text`; test mixed/non-text/empty content. Use Java 21 only for Android host tests that load the SDK. Declare optional native GPU libraries.

Known release limitation: this dependency is currently on `androidMain`, so disabled runtime controls do not exclude native libraries from the release APK. Keep this commit as development work; isolate the dependency behind an internal build boundary before distribution. That packaging fix is not currently implemented and must not be implied by this commit message.

## 4. feat(inference): add model scheduling and resource safeguards

- `shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/ModelManager.kt`
- `shared/src/commonTest/kotlin/dev/edgecompanion/core/inference/ModelManagerTest.kt`

Scope: serialized admission, visible busy-command rejection without corrupting active runtime state, cancel/release coordination, idle unload and resource policy. Include the regression fixes: the same 3 GiB floor during generation, three consecutive low-memory samples before cancellation, immediate OS/thermal/unknown-signal faults, and recovery counter reset.

## 5. feat(android): wire app-scoped model ownership and lifecycle

- `androidApp/src/main/kotlin/dev/edgecompanion/app/di/ModelModule.kt`
- `androidApp/src/main/kotlin/dev/edgecompanion/app/inference/AndroidModelResources.kt`
- `androidApp/src/main/kotlin/dev/edgecompanion/app/inference/ModelRuntimeLifecycle.kt`
- `androidApp/src/main/kotlin/dev/edgecompanion/app/CompanionApplication.kt`
- `ui/src/commonMain/kotlin/dev/edgecompanion/ui/settings/viewmodel/ModelsViewModel.kt`

Scope: Koin singleton ownership, native Android resource signals, host-presence coordination, internal runtime gating and thin presentation commands. No model handles in Activities or ViewModels.

## 6. feat(settings): add internal model controls and import UI

- `androidApp/src/main/kotlin/dev/edgecompanion/app/inference/ModelDocumentSource.kt`
- `androidApp/src/main/kotlin/dev/edgecompanion/app/MainActivity.kt`
- `ui/src/commonMain/kotlin/dev/edgecompanion/ui/app/CompanionScreen.kt`
- `ui/src/commonMain/kotlin/dev/edgecompanion/ui/settings/screen/SettingsScreen.kt`
- `ui/src/commonMain/kotlin/dev/edgecompanion/ui/settings/screen/ModelsPanel.kt`
- `ui/src/commonMain/composeResources/values/strings.xml`
- `androidApp/src/androidTest/kotlin/dev/edgecompanion/app/ModelsPanelTest.kt`

Scope: provider import bridge, readiness and backend controls, synthetic prompts, cancel/unload, localized failure and busy feedback, and panel regression coverage. Ordinary assistant chat remains gated.

## 7. feat(benchmarks): add latency summaries and acceptance math

- `shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/RuntimeMeasurements.kt`
- `shared/src/commonTest/kotlin/dev/edgecompanion/core/inference/RuntimeMeasurementsTest.kt`

Scope: raw-sample latency summaries, nearest-rank p95 and minimum sample-count enforcement. Single-run smoke numbers cannot become percentile acceptance claims.

## 8. test(inference): add native smoke and supervised S24 harness

- `androidApp/src/androidTest/kotlin/dev/edgecompanion/app/ModelRuntimeTest.kt`
- `scripts/verify-model-runtime.sh`
- `scripts/verify-model-s24.sh`
- `scripts/monitor-model-thermal.mjs`
- `scripts/verify-secure-qa.sh`

Scope: opt-in native load/stream/cancel/release/reload and host tests, stronger plain-text answer-shape assertions, explicit backend reporting, mixed workload, resource measurements and separate thermal supervision. Exclude native tests from ordinary secure QA. Reports remain ignored synthetic artifacts.

## 9. docs(phase1): record runtime decisions, evidence and remaining gates

- `docs/decisions/0003-gemma4-litert-lm.md`
- `planning/PHASE_1_PLAN.md`
- `planning/IMPLEMENTATION_PLAN.md`
- `docs/PHASE_1_IMPLEMENTATION_RESULTS.md`
- `docs/PHASE_1_QA_REVISIONS.md`
- `docs/PHASE_1_COMMIT_PLAN.md`
- `benchmarks/README.md`
- `README.md`

This group records the new QA session:

- Focused S24 model tests: 9 passed; native GPU smoke: 2 passed. Verified report `benchmarks/results/native-20261013T181209Z-GPU/runtime.json` records 9,821 ms cold load plus first output, 138 ms warm first output, 29 ms cancel, 401 ms active release, 406 ms idle release and 1,948,760 KB sampled peak PSS. These are individual observations; `acceptanceComplete` remains false.
- Preserve the earlier low-memory admission failure as an earlier attempt, alongside the later passing smoke.
- Record that the broad 48-test S24 run encountered accessibility/permission/notification harness failures. The 48-test emulator pass does not establish a physical-device pass.
- Distinguish the historical 2026-10-02 sustained run from sustained validation of the revised memory monitor, which remains pending.
- Record the approximately 109 MB release APK and disabled-runtime packaging limitation. No release-readiness claim.

## Exclusions and Follow-ups

Do not stage model weights, `benchmarks/results/`, generated APKs/build outputs, local SDK configuration, credentials, personal app storage or snapshots.

`docs/S24_ULTRA_QA_2026-10-01.md` predates this implementation and is currently untracked. Keep it outside these commits unless its owner explicitly includes it; do not use `git add .`.

The existing S24 test files `CameraPermissionFlowTest.kt`, `CaptureFlowTest.kt` and `ServiceRegressionTest.kt` are unchanged in this phase. Harness fixes and an internal-only native dependency boundary should be separate follow-up work with their own validation, not listed as already fixed by these commits. Deferred audio issues remain outside scope.

At each boundary, check that included new files and dependencies compile together. Build per-commit validation has not been performed; the current completed working tree has validation evidence in the results documents.
