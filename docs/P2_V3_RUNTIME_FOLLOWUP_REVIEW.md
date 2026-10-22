# V3 Runtime Focused Follow-Up Review

Reviewed 2026-10-22. **PASS for closure of the two original host/runtime findings.** No new actionable defect was established in this focused follow-up. The native whole-pipeline feasibility gate remains **BLOCKED**; this verdict does not approve another run or a larger campaign.

## Original Findings

1. **P1 closed: stale delivery during cleanup.** [VerificationCall.kt:25](/Users/kezileo/Desktop/personal_mobile_agent/shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/verification/VerificationCall.kt:25) retains a private candidate. Confirmed cleanup and current eligibility are required to publish it. Stop and invalidation monotonically revoke delivery and clear usable validation without replacing the first decision. [ModelManager.kt:322](/Users/kezileo/Desktop/personal_mobile_agent/shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/ModelManager.kt:322) keeps foreground/generation ownership through verification cleanup, checks context/lifetime before publication, and checks again immediately before the public event emission at :60. Three release-gated regressions assert reservation retention, one outcome, REVOKED delivery, null validation and confirmed cleanup after Stop, epoch change and host loss. Independent replay of all three original synthetic probes confirms that behavior.

2. **P2 closed: incomplete native lifecycle evidence accepted as success.** [v3-feasibility.mjs:65](/Users/kezileo/Desktop/personal_mobile_agent/scripts/v3-feasibility.mjs:65) requires ELIGIBLE delivery. Successful requests require healthy telemetry, exactly one Gemma-load observation, a unique nonempty worker identity, confirmed worker death, ordered CHECKING/LOADING/ENCODING events with bounded PSS and valid PID/times, and death before Gemma loading. The native harness writes delivery/telemetry status and rejects missing positive load/phase/death evidence; host admission checks the fuller recorded metadata. Added regressions cover missing/invalid telemetry and revoked publication. Synthetic replay of the original empty-load/empty-worker evidence now rejects.

The earlier M/U open question is resolved as the implementation's explicit aggregate-output interpretation: output detail states describe the complete delivered evidence set, whereas the rubric matrix classifies individual passage/detail pairs. The source comment and new regression make this interpretation explicit. No rubric, gold, or semantic judgment was changed or newly assessed.

## Verification

- **PASSED:** direct JUnitCore run against the updated existing JVM classes: **50 tests**, exit 0. This includes 33 ForegroundHandoffTest and 17 EvidenceOutputValidatorTest cases. No Gradle process was started by this review.
- **PASSED:** stdin-only Java source-launcher replay of Stop, epoch change and host loss during release-gated cleanup, exit 0. Each scenario withheld validation while cleanup was pending, then retained STRUCTURALLY_VALID as the first decision while delivering REVOKED, null validation and confirmed cleanup.
- **PASSED:** **21 synthetic host-classifier checks**, exit 0: one positive control; 17 incomplete/invalid/duplicate lifecycle-evidence rejections; two pending/revoked delivery blocks; one timeout block. Imported only classifyFeasibility; did not load the frozen feasibility plan or any held-out examples.
- **PASSED:** all **29** archived source files match the saved native attempt's source-sha256.json. Six current implementation files differ from that archive, consistently with this hardening: ModelManager, ForegroundEncoder, EvidenceOutputValidator, VerificationCall, v3-feasibility.mjs and V3FeasibilityTest.
- **PASSED:** historical V2 loadLock still rejects with Stale/unfrozen investigation. The immutable legacy test hash remains unchanged.
- **NOT RUN by this reviewer:** Gradle/recompilation, full Node suite, full platform builds/lint, Android instrumentation, new device/model activity, qualification/scoring or held-out access. Parent's updated results document records 148 shared JVM tests, 130 Android-host tests, 15 debug and 15 secure-QA app tests, build/lint passes, and 16 focused Node/preparation checks. These broader results were not independently rerun here. The complete Node suite remains reported as 66 passed / 1 known historical-fixture failure, not green.

The source/test layering remains suitable: common Kotlin tests with virtual time and fakes cover state/ownership; synthetic host checks cover evidence admission; native instrumentation remains separate evidence. No UI, Compose, Robolectric or screenshot expansion was needed for these fixes.

## Saved Native Evidence

Read only the retained attempt at [p2-v3-feasibility-gate-vJlgY2](/Users/kezileo/Desktop/personal_mobile_agent/benchmarks/results/p2-v3-feasibility-gate-vJlgY2), including its source manifest/archive, result.json, encoder.json and supervision.json.

The first cold request records TIMED_OUT at **5067 ms**, validation NOT_REACHED, encoder/runtime cleanup CONFIRMED, runtime UNLOADED, and total wall time **14459 ms**. The 370 ms cleanup field is only the final engine-close interval, not the complete timeout-to-cleanup duration. The retained memory stages show loading and subsequent cleanup, with no prefill/decoding stage. The result records blocked/V3_FEASIBILITY, one attempted request and seven not run, largeCampaignAllowed=false and semanticEnablement=false. The saved supervision status is closed; application cleanup evidence is in the request/runtime fields.

This run predates the hardened delivery/telemetry contract and is not current-source native acceptance. The updated [feasibility results](/Users/kezileo/Desktop/personal_mobile_agent/docs/P2_V3_FEASIBILITY_RESULTS.md:68) now states that distinction and points to the exact source archive. Old raw evidence was not rewritten or reclassified under the new evidence schema. No additional native execution is needed or authorized by this review; the original five-second failure continues to block progression pending the separate architecture/performance decision.

## Scope And Preservation

Focused follow-up only, using the previously read kotlin-kmp-code-review and kotlin-testing-kmp skills and relevant references. AGENTS.md and the QA boundaries from the original review remain applicable. Branch dev and base 2f36abd are parent-reported context; no Git operations were performed to verify them. No whole-product or additional architecture audit was performed.

The original [focused review](/Users/kezileo/Desktop/personal_mobile_agent/docs/P2_V3_RUNTIME_FOCUSED_REVIEW.md) remains verbatim, SHA-256 978fa3bc6a83c050b6d316c9352103201237fc4173da666b1512bb43d8402531. Its FAIL verdict describes the previous snapshot and is not overwritten. Frozen V3 protocol/rubric/freeze and the legacy test hashes match that review. Deferred release, memory-policy, audio and broader device-acceptance statuses remain unchanged.

The historical V2 fixture follow-up recommendation remains: keep executable stale-source rejection intact and preserve historical bytes. Any separate archival-test migration needs its own authorized maintenance; none was implemented here.

Only this follow-up report was written. Source, tests, protocol, approvals, raw native evidence, original report, app data and Git state remained read-only.

## Snapshot Identity

SHA-256 paths below are relative to /Users/kezileo/Desktop/personal_mobile_agent. Compiled-class identities bind the independent host probes; they do not substitute for fresh compilation or native verification.

```text
978fa3bc6a83c050b6d316c9352103201237fc4173da666b1512bb43d8402531 docs/P2_V3_RUNTIME_FOCUSED_REVIEW.md
6e9682e7a2512183c855a9346ca2758015b1ac48d457b3d74d5379fce5b46828 shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/verification/VerificationCall.kt
7f0d077589e422dbb4812f9cea847d1799bb473a319447e4db72a6c3b2312286 shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/ModelManager.kt
ebacd6dc9748f11c23374529ade07a4acb9b9df4d71a536d1644e3d7390c7170 shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/embedding/ForegroundEncoder.kt
10be77c8586f0ee9931c9c33b965acd3608fe379010afde75e27b1dfd5526000 shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/verification/EvidenceOutputValidator.kt
ba4dd5c9532a7e7b86471e1c5bd5a9578d8c884537b63204259a4692479ec978 shared/src/commonTest/kotlin/dev/edgecompanion/core/inference/ForegroundHandoffTest.kt
5ed5d829db8b070f73bd3f49ea93b13a529027b37981bf0e3a1a1b58cc30d66f shared/src/commonTest/kotlin/dev/edgecompanion/core/inference/verification/EvidenceOutputValidatorTest.kt
da687db233ea3c02f8455afd5afd4537b95c274b72a63742847586f970ea1a0a scripts/v3-feasibility.mjs
a3870ceb30aed2051871a3e983dc85e434229b63c178964e15c12dd3844ae026 scripts/tests/v3-feasibility.test.mjs
9cb9c7d777d8f5c827f4c45fa274ada86e94b2788ba8fa87397fb02b6204f162 scripts/run-p2-embedding-gate.mjs
875b046dc9cabd687e138b8ce67d674ed583d80de7b19bb4985b953b3e64f245 androidApp/src/androidTest/kotlin/dev/edgecompanion/app/V3FeasibilityTest.kt
8db352da50bf3878f7c811eb20a9a131c75310398db743950380442b25f9d569 planning/P2_V3_EVIDENCE_SUFFICIENCY_PROTOCOL.md
5d154d2e679772f6c40ef04af578d2511d0e6e4d7576cebb74da32c2834bb399 planning/P2_V3_JUDGMENT_RUBRIC.md
b15e10a1981b15df3dde0b01a8d453c2935905e5bee7189b965960896e1ac4ad planning/fixtures/evidence-sufficiency-v3/protocol.json
eef782b3c37ac39f21871712553e366cd5fe8a40a76ded585955bb5a38db8db9 planning/fixtures/evidence-sufficiency-v3/freeze.json
35720368cb2999ea192fbca48d398dd563d261171fa36f29a5adea75ebb0240c scripts/tests/calibration-policy.test.mjs
09149da42272ae0d829ad3a5c3c7e2a858600687ed54de5778c27a4ab983fdf3 benchmarks/results/p2-v3-feasibility-gate-vJlgY2/source-sha256.json
2658fe578fdbcd716505bc2941f5ad25cc237b4161389a6178128c29e499de41 benchmarks/results/p2-v3-feasibility-gate-vJlgY2/result.json
3c8fe2e5bf53ec8db0c32344947215fc19a9731c33f352b9f82062e61c51e00d benchmarks/results/p2-v3-feasibility-gate-vJlgY2/encoder.json
8e764cd3e6cd2765ce5a45f4999fa1bfad30358ccb87545ecd74075a1e5bc4db benchmarks/results/p2-v3-feasibility-gate-vJlgY2/supervision.json
b8ba24f4c26e362f1afdd6a7b5ad19542ed430e6a36017802823449d27d654ec shared/build/classes/kotlin/jvm/main/dev/edgecompanion/core/inference/ModelManager.class
8603b9c1708aaab645446d249c78a2467d8dc06bde5c17ceb1a149b4bf4eb3e7 shared/build/classes/kotlin/jvm/main/dev/edgecompanion/core/inference/verification/VerificationRun.class
b084ea7f8bb67d22e6b2d36c826f296632c49331353bed475aa77ee32d9e92ec shared/build/classes/kotlin/jvm/test/dev/edgecompanion/core/inference/ForegroundHandoffTest.class
```

Next action: parent may record closure of the two host findings against this snapshot. Keep native feasibility BLOCKED and current-source native behavior unverified; do not infer semantic support or campaign permission from host success.

