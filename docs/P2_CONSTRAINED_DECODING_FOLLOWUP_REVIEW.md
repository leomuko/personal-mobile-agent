# Constrained-Decoding V2 Focused Follow-up Review

2026-10-26. Reviewer: OpenAI Codex, the retained user-authorized primary independent
QA reviewer. No delegation or substitute reviewer.

**Verdict: PASS for `constrained-decoding-v2-integration-and-host-qa`.**
CD-IMPL-001 and CD-IMPL-002 are resolved in the exact snapshot below. No unresolved
finding was identified in the corrections or affected callers. This attestation
permits preparing the actual execution freeze; it does not attest an APK/freeze
or grant device permission. `deviceExecutionAuthorized: false` and
`semanticEnablement: false`.

## Snapshot And Preserved Review

Workspace: `/Users/kezileo/Desktop/personal_mobile_agent`; branch `dev`.
Base/HEAD: `ae678b7ecd3760ba3739bf89127ac5cb8bae2fa1`. No staged changes.
Applied the existing AGENTS.md/QA_AGENT_PROMPT.md and Kotlin review/testing,
concurrency and platform-boundary criteria. This is a focused follow-up to the
two findings, not another full-project review.

Independently recomputed `constrainedSnapshot('.')`, including tracked and untracked
files, and SHA-256 of its canonical `JSON.stringify` representation:

```text
158630554bae1071105b3b50d24bed342d142178b51a71780601b983da4f9d9b
```

All 341 entries exactly match the sources map in
`build/constrained-decoding/review-snapshot-followup.json`, whose file SHA-256 is
`d64f1b3929509d3fee62badacc2c282d92a73cae68bdbe83ad82f2a5a6dd3c20`.
Comparison with the original 339-entry sources map found 11 modified paths, two
added paths and no removals. The added paths are the QA JSON serialization helper
and its unit test. The attestation JSON records all 13 current content hashes.

Previous canonical snapshot:
`7c9026d54501e9a7e2a4d316e7c83cbd986018abbe8d4c38420eaaac67a63374`.
The original FAIL report remains byte-identical, SHA-256:
`d2bee3f6ac09da4c3da69770df5628c6593b58a72925f8c3b6fe9d5ab6cba098`.
It remains the accurate review of that earlier snapshot.

No protocol, schema, case, preparation lock, prerequisite implementation/proof,
strict-validator, admission/freezing logic, pressure policy or SDK adapter file
changed relative to the original reviewed map. The V2 preparation-lock SHA stays
`5e8edd23535d26c9985a28fd59e51c29b60974561580c0f807fec0f5b297e1d9`;
all its bindings still match. Historical false flags were not rewritten.

Separately read `docs/P2_CONSTRAINED_DECODING_REVIEW_FIXES.md`, SHA-256
`2ade028ad13a971c6ddb57117f65949cfb1e25e5423b94bf4e08ebe0d319ab5b`.

## CD-IMPL-001: Resolved

[ConstraintTrace](/Users/kezileo/Desktop/personal_mobile_agent/shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/verification/ConstrainedDecoding.kt:12)
now retains points, bounded raw diagnostics and sealing in one immutable atomic
state. The session captures callback text before terminal/delivery filtering.
Received UTF-16-unit counts and explicit truncation remain separate from the
retained prefix; sealed evidence cannot be changed by post-close callbacks.
ModelManager seals and forwards the raw snapshot through cleanup, including
rejection/error paths. Ordinary unconstrained verification retains its existing
default behavior.

[VerificationRun.cleaned](/Users/kezileo/Desktop/personal_mobile_agent/shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/verification/VerificationCall.kt:91)
revokes delivery on truncation or a mismatch between a structurally validated
input and final raw diagnostics. It retains the latched outcome and cannot use
raw diagnostics to manufacture eligibility. Both guards block truncated captures;
the decoding-cancellation guard also requires nonempty raw text. The host replay
requires callback-buffer provenance, consistent received length and canonical
UTF-16 hex. The collector writes those fields with standard JSON surrogate
escapes, preserving interrupted Unicode rather than repairing a model response.
Diagnostics do not enter a later model/encoder request or substitute for oracle
or strict-validation data.

Replayed the original compiled-host fake-handle probe, adapting only the trace
API. Send delivered callback text `{"o":`, then cancelled before the delivery
queue drained; cancel supplied the terminal callback. Observed:

```text
events=[Cancelled], closes=1, no quarantine
diagnostics.text={"o":
receivedUtf16Units=5, truncated=false, sealed=true
post-close callback leaves the snapshot unchanged
```

The actual shared guard accepts this correctly timed cancellation with preserved
diagnostics. Delivery still contains no Token event. Fresh tests additionally
exercise FIRST_TEXT-observer cancellation before collection, more text during
cancellation, late/post-close callbacks, fragmented Unicode, bounded truncation,
raw mismatch revocation and JSON surrogate escaping. These are host observations,
not native behavior claims.

## CD-IMPL-002: Resolved

The [shared guard](/Users/kezileo/Desktop/personal_mobile_agent/shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/verification/ConstrainedGuard.kt:28)
and [host guard](/Users/kezileo/Desktop/personal_mobile_agent/scripts/constrained-decoding-evidence.mjs:51)
now enforce publication <= send <= first text <= terminal <= close when the
relevant points exist, including send <= terminal without text. Existing targeted
cancellation intervals remain required. There is no artificial requirement for
the terminal callback to occur after SEND_RETURN.

Independently repeated both original synthetic counterexamples. The complete
host classifier now rejects cd06 FIRST_TEXT/CANCEL_SIGNAL before SEND_ENTER and
positive FIRST_TEXT after TERMINAL. The compiled shared guard returns
`passed=false, classification=PHASE_ORDER` for the original invalid cd06 ordering.
The unchanged valid ordering still passes. A terminal callback preceding send
return remains accepted in the synthetic replay. Regressions cover missing and
reversed terminal boundaries and empty/truncated cancellation diagnostics too.

## Fresh Independent Verification

- `node --test scripts/tests/constrained-decoding.test.mjs`: exit 0; 14 passed,
  zero failures/skips.
- `node scripts/host-test-suite.mjs`: exit 0; 129 passed, zero failures/skips.
  The 14 targeted cases are part of this total, not additional unique cases.
- Fresh offline Gradle invocation, after the parent's baseline completed:

  ```sh
  ./gradlew :shared:jvmTest --tests '*ConstrainedGuardTest' --tests '*ForegroundHandoffTest' :shared:testAndroidHostTest --tests '*ConstrainedSessionTest' :androidApp:testSecureQaUnitTest --tests '*QaJsonDiagnosticsTest' -PtestBuildType=secureQa -PandroidPressureMemoryTest=true --offline --rerun-tasks
  ```

  Exit 0. XML reports: 52 JVM cases, 11 Android-host cases and one secure-QA app
  unit case, all passed with zero failures/errors/skips. The current saved XMLs
  contain these focused selections; they must not be labeled a fresh full suite.
- Original callback-loss probe, compiled shared phase probe and complete host
  phase probes reproduced the corrected behavior described above. No source or
  test file was written for those in-memory probes.
- Exact follow-up/original map comparison, preparation admission/hash checks,
  original FAIL preservation, no stale trace.points callers in active source,
  no staged changes and `git diff --check` passed.

Inspected supplied completed logs, without relabeling them independent executions:
`followup-gradle-qa-final.txt` reports successful QA app/test APK assembly and
full shared/app-QA tests; `followup-baseline.txt` reports successful debug/release
builds, debug app tests and lint; `followup-host-suite.txt` reports 129 Node passes.
All are under `build/constrained-decoding/`. Builds/lint and the broader Gradle
population were not independently rerun in this focused follow-up.

The previously independently reproduced Draft-07 and exhaustive single-token
proof are unchanged and hash-admitted, not claimed as freshly re-executed here.
No packages were installed. Native SDK initialization, schema enforcement,
token accounting, cancellation timing, resource behavior and cleanup still need
the separately gated actual experiment. No device/ADB/installation/model workload
or real execution-freeze creation was performed by this reviewer.

## Attestation And Limits

The new `planning/fixtures/constrained-decoding-v2/implementation-review.json`
binds this PASS to the exact 341-file snapshot and this report's bytes. It carries
review provenance, resolved findings, the changed-file hash map, verification
metadata and explicit limitations. It grants no execution permission.

The authorized integration remains nine ordered cases, each strictly below
30000 ms including cleanup, the isolated Android-pressure profile, cd07-only cap
1 and 512 reserved output tokens within 2048 context. The independent 40-second
emergency cleanup stop supplies no additional accepted inference time. No scope,
deadline, owner, oracle, strict-validator or semantic-enablement relaxation was
found in the fixes. Existing deferred issues remain unchanged; P2 is incomplete.

Next gate: prepare the actual source/APK/schema/pack/proof/profile freeze and
return it to this same reviewer for separate correspondence review. Device
authorization must remain separate and bind that final freeze.

Only authored paths in this follow-up:
`docs/P2_CONSTRAINED_DECODING_FOLLOWUP_REVIEW.md` and
`planning/fixtures/constrained-decoding-v2/implementation-review.json`.
Original reports, locks, sources, tests and configuration were preserved. Checks
generated ordinary build/test outputs only. No Git mutation or delegation.
