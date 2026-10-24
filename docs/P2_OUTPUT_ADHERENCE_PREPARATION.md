# Output-Adherence V1 Host Preparation

Date: 2026-10-22. **Host preparation implemented; independent review pending.**
P2 remains incomplete. Native feasibility is blocked and semantic search disabled.

## Implemented Scope

The user authorized proceeding with the next implementation after checkpoint
`57ebf3653578c48cc5670b9c95f8f11b7594b2aa`. This slice implements host-only
preparation for [the separately versioned protocol](../planning/P2_OUTPUT_ADHERENCE_PROTOCOL_V1.md),
not a native collector, execution freeze or new app feature.

- Six synthetic controls cover support, conflicting records, a missing unit with
  untrusted source instructions, unrelated evidence, partial two-detail support
  and original UTF-16 coordinates following astral/combining text.
- Complete passage/detail and delivered-set judgments and representative exact
  witnesses are present for independent review. They are not self-approved.
- Exact model-input packs are whitelisted separately from review-only gold.
  Required records are retained; no truncation or hidden evidence dropping.
- All six payloads fit the existing shared 2048 context / 512 output budget.
  UTF-8 lengths in order: 951, 1066, 969, 956, 1038 and 949 bytes.
- A review lock binds the protocol, candidate, controls, generated packs, owning
  source/tests, unchanged validator, prior protocol identity and version catalog.
  It explicitly sets judgmentApproval=false and executionReady=false.
- A checksummed Base64 TSV carries 83 synthetic positive/negative output fixtures
  to JVM tests that call the real unchanged strict validator. These are authored
  regression inputs, never generated model results or semantic-quality evidence.

No runtime owner, ModelManager API, production UI, database, memory threshold,
validator or dependency changed. The preparation metadata names the actual
`litertlm-android:0.17.1` catalog coordinate and separately preserves V3's historical
runtime label; this is not an SDK replacement or native compatibility claim.

## Reproduction And Current Identity

```sh
node scripts/output-adherence-preparation.mjs check
node scripts/host-test-suite.mjs
env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  P2_ENCODER_COUNTING_DIR="$PWD/build/encoder-counting" \
  P2_V4_PROFILE=android-pressure \
  ./gradlew :shared:jvmTest --console=plain --rerun-tasks
```

The counting directory is the existing pinned host reference setup, not model
weights or new native collection. Fresh checkouts need that prerequisite setup
to execute the existing opt-in tokenizer proof without skips.

Preparation lock SHA-256:
`88a985300cd4cc0c100f6541214328e68759558393fa25b86f8fea8a13b888a2`.
Prepared artifact SHA-256:
`ca7f71bed8a91c3c3a05f369f7ef1b9e085c3dac97c906bc9f624fc65766e068`.

`write` is first publication only and refuses existing derived artifacts. `check`
reproduces exact bytes and detects changed inputs, source, packs or bridge rows.
Neither command can approve judgments or access ADB. No approval was manufactured.

## Verification

- Passed: all 99 canonical Node tests, zero failures/skips; ten new preparation
  regressions cover bounds, malformed input, gold separation, immutable bindings,
  publication errors, tampering and refusal of execution options.
- Passed: all 159 shared JVM tests, zero failures/errors/skips, with forced fresh
  compilation/test execution. Five new tests exercise the 83 output fixtures,
  real prompt budget, non-vacuous control shapes, Unicode and validator identity.
- Existing cancellation, ownership, deadline and cleanup tests passed as part of
  the shared suite. No new native timing policy is installed by these tests.
- The initial host JVM run failed one fixture: an unknown citation ID also broke
  citation ordering, so SCHEMA preceded REFERENCE. Its report, generated artifacts
  and all bound inputs are retained locally under
  `build/output-adherence-preparation-attempts/attempt-1-reference-order/`.
  The fixture's unknown ID now preserves ordering and isolates REFERENCE.
  Controls, candidate instructions and strict validator were unchanged by that fix.

No S24/ADB access, vector collection, native model inference, Android APK build,
instrumentation, execution-freeze refresh or semantic enablement was performed.
Existing Gradle UI host-test configuration warnings remain outside this slice.

## Remaining Gates

Independent QA must review all six controls' complete judgments, source separation,
exact packs, witnesses and new lock. This report is an implementation handoff,
not an independent attestation. Original V1-V4 artifacts, strict validator and
failed outputs remain preserved; V2 held-out data remains unused.

The proposed 30-second ceiling and isolated QA pressure profile need explicit
approval for this protocol. Current question hashes are not encoder admission
receipts. Actual encoder admission, SDK token counting, native output adherence,
cancellation/cleanup and complete pipeline timings are unverified for these packs.

Only after the required review/approval may native integration, a separately
reviewed code/APK execution freeze and separate S24 authorization proceed. Do not
resume the seven V4 requests, relax validation or run a larger campaign. A future
structural pass would not qualify semantic sufficiency or complete P2.
