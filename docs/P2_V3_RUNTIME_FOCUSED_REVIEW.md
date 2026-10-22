# V3 Runtime Focused Independent Review

Reviewed 2026-10-22. Verdict: **FAIL for this runtime/feasibility review scope**.
No production integration, semantic support, quality, performance, or device acceptance is established.

## Findings

### P1: Stop and stale ownership do not revoke delivery during verifier cleanup

Location: [ModelManager.kt:319](/Users/kezileo/Desktop/personal_mobile_agent/shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/ModelManager.kt:319), [VerificationCall.kt:68](/Users/kezileo/Desktop/personal_mobile_agent/shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/verification/VerificationCall.kt:68), and the final emission at ModelManager.kt:60. Confidence: high; reproduced against existing compiled JVM classes.

Validation fixes `STRUCTURALLY_VALID` before `engine.release()` finishes. While release is suspended, `foreground` and `generation` have already been cleared and the preparation monitor has been cancelled. Stop cannot change the fixed decision; an epoch change is never rechecked; host loss cannot find this preparation to invalidate. After cleanup, `events` still delivers the valid output with no stale-publication indicator.

Reproduction: use the existing `ForegroundHandoffTest.Fixture` through reflection, return structurally valid synthetic U JSON, and suspend `Engine.release()` on its existing `releaseGate`. At this point state is valid with cleanup PENDING and the event collector has received zero events. Independently apply `call.cancel()`, increment the fixture epoch, or call `manager.releaseForHostLoss()`. Open the release gate. Each case delivers one `STRUCTURALLY_VALID` result with its validation intact, cleanup CONFIRMED, and error null. The Java source-launcher probe exited 0. No project source/test was edited.

Impact: a stopped or obsolete request can deliver usable validation after its owner/context has ceased to be valid. The frozen whole-pipeline contract explicitly requires Stop to invalidate results and context/host validity to be rechecked before publication.

Minimal recommendation: retain the exactly-once terminal decision and independent cleanup, but track revocable publication eligibility through cleanup and recheck the captured owner/context immediately before delivering validation. Do not implement this by turning cleanup completion into a second terminal outcome. Add release-gated Stop, epoch-change and host-loss regressions; retain the current pending-reservation checks.

### P2: Missing native lifecycle evidence can pass feasibility

Location: [v3-feasibility.mjs:58](/Users/kezileo/Desktop/personal_mobile_agent/scripts/v3-feasibility.mjs:58) and :68; corresponding conditional instrumentation check at [V3FeasibilityTest.kt:137](/Users/kezileo/Desktop/personal_mobile_agent/androidApp/src/androidTest/kotlin/dev/edgecompanion/app/V3FeasibilityTest.kt:137). Confidence: high; reproduced with wholly synthetic host data.

The classifier requires arrays but permits empty `gemmaLoads` and `workerEvents`. Death evidence is checked only when `gemmaLoads.length` is nonzero. Eight otherwise complete successful cases with both arrays empty and `confirmedWorkerDeath=false` return `status: passed`. No worker instance, native phases, or death timestamp is needed. The current positive Node fixture itself uses an empty worker-event array.

Impact: absent/dropped telemetry can be accepted as evidence of the required encoder-death-before-Gemma allocation sequence. The instrumented test also skips the ordering assertion for an empty load trace. This is especially relevant because memory sampling can fail without failing generation. A reported cold successful request must have performed native loading.

Minimal recommendation: successful requests must contain the expected Gemma allocation event and fresh, valid encoder worker/death evidence, with consistent phase/timestamp ordering. Reject incomplete telemetry rather than treating empty arrays as proof of no overlap. Preserve legitimate no-load traces for early rejected/timed-out cases. Add missing-load, empty-worker-events, missing/dead-worker-identity and failed-telemetry regressions.

## Open Question: Aggregate M/U Interpretation

Location: [EvidenceOutputValidator.kt:62](/Users/kezileo/Desktop/personal_mobile_agent/shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/verification/EvidenceOutputValidator.kt:62). This is not a confirmed defect or a new semantic judgment.

The validator forces overall U whenever every requested detail is U. A one-detail `{"o":"M","d":[{"i":"a","s":"U","e":[]}]}` is consequently rejected as SCHEMA; a synthetic probe confirms that behavior without model or corpus scoring. The rubric calls wrong-cycle passage/detail judgments U and permits overall M for topical wrong-cycle evidence. However, that table describes individual passage/detail judgments and does not explicitly settle whether the output's aggregated detail must itself be M in that situation.

If aggregate detail states use the same subject-relevance rule as overall states, the current rejection is consistent. If aggregate U retains the table's wrong-cycle meaning, M/all-U would need to remain structurally admissible. The probe alone cannot resolve that interpretation, and no frozen labels were re-adjudicated here.

Recommendation: document the aggregate-detail interpretation against the frozen contract and test the agreed structural relation without changing the rubric, examples or gold labels. Do not count this open question as a reproduced semantic defect.

## Scope And Snapshot

Repository root: `/Users/kezileo/Desktop/personal_mobile_agent`. Branch `dev` is supplied by project instructions; branch/base/HEAD and Git status were deliberately not queried because this invocation prohibits all Git operations. This is a focused sidecar review, not an initial whole-product audit. Untracked files were included through direct filesystem inspection. No prior reviewed snapshot was supplied.

Read AGENTS.md, QA_AGENT_PROMPT.md, README, implementation/deferred plans, the approved-reference index, the frozen V3 prose protocol/rubric/machine protocol/instructions, and the requested source/tests plus affected cleanup, admission, telemetry and historical-lock callers. Applied `kotlin-kmp-code-review` with concurrency, architecture/state, data/security and code-quality/testing references, and `kotlin-testing-kmp`. No UI changes were in scope; approved screenshots were not visually re-reviewed. No architectural/module ownership change requiring a separate architecture review was identified.

Reviewed source hashes were checked twice and unchanged. SHA-256 paths below are relative to the repository root:

```text
b1a0ec8e2355834184180dd848af3d767b0b5374cd66af2c7ee66702428b5de1 shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/verification/EvidenceOutputValidator.kt
62cb2e8b4a2a9fd0453a3f150a0535bd92a5dae81c32fbefbac3d853ce505cbe shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/verification/VerificationCall.kt
0d4bf6080471d794025fb0eba3b30471cf44509427bb2653942c5352bd19958e shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/ModelManager.kt
cc05b883a50833b151181ded525ad57c950b16bb51f1bfa6bc277ee39ffd2728 shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/embedding/ForegroundEncoder.kt
7d1bd0bc442540bc25f207ca1e96ea321d05b7be294a50fae7cae8612973676b shared/src/commonTest/kotlin/dev/edgecompanion/core/inference/verification/EvidenceOutputValidatorTest.kt
6b92e730c5e38af5306e5b96449978d9745c02c07f09b63b004488f72169f11f shared/src/commonTest/kotlin/dev/edgecompanion/core/inference/ForegroundHandoffTest.kt
56ae3919695dce071ffaa3bbe6379cd3c936714b2ac6b6d908cdbb6783aa0824 scripts/v3-feasibility.mjs
9cb9c7d777d8f5c827f4c45fa274ada86e94b2788ba8fa87397fb02b6204f162 scripts/run-p2-embedding-gate.mjs
b82e1c7aa98c4d89a7c2d153b1d4b2bb3f360ffcfab78355b3767a847a3c27e9 scripts/tests/v3-feasibility.test.mjs
91e85d45528676e62f77504fc35bad68be2ec4a158552513be81b6cfdb1f7b57 androidApp/src/androidTest/kotlin/dev/edgecompanion/app/V3FeasibilityTest.kt
35720368cb2999ea192fbca48d398dd563d261171fa36f29a5adea75ebb0240c scripts/tests/calibration-policy.test.mjs
8db352da50bf3878f7c811eb20a9a131c75310398db743950380442b25f9d569 planning/P2_V3_EVIDENCE_SUFFICIENCY_PROTOCOL.md
5d154d2e679772f6c40ef04af578d2511d0e6e4d7576cebb74da32c2834bb399 planning/P2_V3_JUDGMENT_RUBRIC.md
b15e10a1981b15df3dde0b01a8d453c2935905e5bee7189b965960896e1ac4ad planning/fixtures/evidence-sufficiency-v3/protocol.json
eef782b3c37ac39f21871712553e366cd5fe8a40a76ded585955bb5a38db8db9 planning/fixtures/evidence-sufficiency-v3/freeze.json
```

The direct JVM checks used existing build outputs, not a fresh compilation. Key class SHA-256 identities:

```text
1b5b0ca23994a65cc8781d0eadb47c5835695c09bc6e1e24f360f25e3332ba70 ModelManager.class
37489a93da06ffc79a6be01df4762b1f6fbc6f0607a1889375ff437463f74f03 VerificationRun.class
9d3f8411e5b31ba8eae14fa92e2eaaced909a9ae703eb1ed8068114fe2986bef ForegroundHandoffTest.class
```

## Verification

- PASSED: direct `org.junit.runner.JUnitCore` invocation of EvidenceOutputValidatorTest and ForegroundHandoffTest using the existing JVM main/test classes, Kotlin 2.4.20, coroutines 1.11.0 and JUnit 4.13.2: 46 tests, exit 0. No Gradle process was started. Existing Gradle XML reports remain at `shared/build/test-results/jvmTest/TEST-dev.edgecompanion.core.inference.verification.EvidenceOutputValidatorTest.xml` and `TEST-dev.edgecompanion.core.inference.ForegroundHandoffTest.xml`; this direct run did not regenerate them.
- REPRODUCED: three release-gated stale-delivery scenarios and the M/all-U schema rejection, via `java --source 17 -cp <existing JVM/test dependencies> /dev/stdin`, exit 0. The harness lived only in stdin and used the existing test doubles. Initial JShell attempts had launcher/classpath problems; the successful Java launcher reran the observations independently.
- REPRODUCED: missing-native-evidence acceptance via `node --input-type=module`, importing only `classifyFeasibility` with eight synthetic rows; exit 0 and result `passed`. No feasibility-plan loader or frozen dataset was used.
- PASSED: read-only historical V2 lock probe confirms `loadLock` rejects with `Stale/unfrozen investigation`; current-source differences are precisely runner, ModelManager and ForegroundEncoder. This reads the historical lock/protocol and runtime sources, not held-out examples.
- NOT RUN: Gradle, native/device/emulator/model activity, feasibility collection, all-Node suite, new qualification/scoring and held-out data access. Parent-reported seven Node passes and build/JVM/local/lint passes are contextual evidence, not independently rerun source builds here. Secure-QA compilation was still pending in the supplied status.

Tests are appropriately layered: bounded parser and owner/deadline behavior use common Kotlin tests with fakes and virtual time; host admission uses synthetic Node tests; Android instrumentation is reserved for native scheduling/cleanup. No additional Robolectric, Compose, UI or screenshot tests are needed for these changes. The missing tests are the specific cleanup publication and admission-evidence cases above. Duplicate decoded keys, Unicode/surrogate handling, exact non-rounded numeric coordinates, delivered references/quotations, bounded parsing and suppression of raw generated chat text are concretely covered; no additional defect was established in those areas.

## Historical V2 Fixture Follow-Up

The known last calibration-policy test uses live `loadLock` to inspect a historical archive. Authorized runtime changes invalidate the old executable source identity, so its admission failure is correct. This is a historical-test compatibility issue, not permission to refresh the campaign lock, approvals or frozen bytes. Keep the immutable test bytes and original failure evidence, and continue reporting the full suite as having that known failure.

Minimal subsequent maintenance: add a separate test outside the frozen material that inspects the historical lock as archival JSON, verifies the preserved artifact hashes/exact approved question change, and independently asserts that current `loadLock` rejects the stale campaign. Such a test needs no held-out decoding, collection, fitting or scoring. It preserves historical integrity and current admission behavior. If a green default test command is later required, authorize an explicit versioned suite-selection/known-failure policy for that exact archival test; do not weaken `loadLock`, silently skip the whole file, or claim the unchanged all-suite invocation passes. No such maintenance was implemented here.

## Remaining Limits And Next Actions

Parent should assess the two confirmed findings before treating host prechecks as sufficient to start native feasibility. Review findings do not authorize this sidecar to fix code. Rerun focused tests after any authorized fixes and bind the resulting evidence to the new source snapshot. Native feasibility remains separate evidence and never authorizes a large campaign or semantic enablement.

BUILD-001, MODEL-001, AUDIO-001 and AUDIO-002 remain as recorded in DEFERRED_ISSUES.md. UI-001/UI-002 are implemented with outstanding broader physical-device acceptance in the current deferred-issues record; the older QA prompt's description is stale. This review changes none of those statuses.

Only this report was written. Production/test/protocol source, frozen V1/V2/V3 artifacts, approvals, app data and Git state were not changed.
