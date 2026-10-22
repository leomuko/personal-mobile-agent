# P2 V3: Validator And Small Feasibility Experiment

Date: 2026-10-22. Branch dev, base 2f36abd. No commits created.

## Verdict

**BLOCKED at the whole-pipeline feasibility gate.** The strict output validator
and opt-in request-owned verification path are implemented. The first supervised
S24 request exceeded five seconds during Gemma loading. It produced no verifier
decision or answer. Cleanup was confirmed; the remaining seven requests were not
run. No larger campaign, development selection, held-out collection or semantic
enablement is permitted by this result. This is not a quality-gate pass.

The user's subsequent authorization covers the validator and small supervised
experiment only. The preparation freeze/approval retain their original scope and
bytes; their `nativeCollectionAuthorized=false` fields are not rewritten to imply
that judgment approval itself authorized execution.

## Implemented Boundary

- `EvidenceOutputValidator` is a bounded schema-specific shared decoder. It rejects
  duplicate decoded keys, unknown/missing fields, trailing/lenient JSON, invalid
  state/detail order, nonintegral coordinates, fabricated/undelivered references,
  revision mismatch, invalid UTF-16 boundaries and quotation mismatch. Input JSON,
  detail/citation counts and quoted text have frozen limits. Numeric coordinates
  are checked exactly without floating-point rounding into integers.
- A valid result is explicitly **StructurallyValid**, not semantically supported.
  Two exact quotations do not prove a real conflict. Human labeled evaluation is
  still required and was not reached on device.
- `ModelManager.verifyEvidence` owns the encoder, confirmed encoder cleanup,
  pinned Gemma generation, bounded output and validation under the existing
  reservation. No second scheduler or UI integration was added. Panel operations
  cannot cancel/acquire this owner. Context/host invalidation remains enforced.
- The caller supplies the monotonic foreground submission time before packing;
  one opt-in deadline continues past Gemma admission and output validation.
  Ordinary chat keeps its existing behavior. Timeout invalidates the result while
  native cleanup remains separately pending/confirmed/uncertain. The reservation
  remains held; uncertain cleanup requires restart. A later cleanup result cannot
  replace the already-latched terminal decision.
- After focused independent review, usable candidate validation is withheld until
  cleanup and a publication-eligibility check. Stop/context/host invalidation can
  revoke delivery through cleanup without replacing the original terminal decision.
  Revoked results carry no usable validated output. Output detail states aggregate
  the delivered evidence; they are not individual passage/detail matrix labels.
- Successful host evidence now requires ELIGIBLE delivery, healthy telemetry,
  fresh worker identity/phases, confirmed worker death and a real Gemma-load trace.
  Empty/missing lifecycle telemetry cannot establish a successful handoff.
- The QA wrapper checks the exact independently approved freeze, all 15 bound
  files, selected fixed-pack hashes and current encoder counting proof before ADB
  access. The device plan contains only the eight frozen calibration requests,
  question-derived details and delivered source text: no gold labels, held-out
  data or semantic scoring. Attempts get unique folders and source/APK hashes.
- Every encoder worker is fresh and must die before Gemma admission. The second
  planned state reuses admission metadata only; it does not claim a warm resident
  encoder graph. It was not executed after the cold failure.

## Supervised S24 Evidence

Folder: `benchmarks/results/p2-v3-feasibility-gate-vJlgY2/`.
Run: `f8bf3e41-f552-4ec6-82d6-8271d6594491`.
Device: designated Samsung SM-S928B, serial R5CX22V8D6B.
Backend: GPU, no fallback. Context/output reservation: 2048/512.
Memory profile: existing isolated `memory-android-pressure-test-v1`, explicitly
selected by QA build and wrapper. Ordinary debug/release thresholds are unchanged.
Android OS-pressure and thermal stops, resource signals and supervision remained
active. No model download, app-data reset or unrelated app/device changes occurred.

This attempt predates the focused review's delivery-revocation and telemetry
hardening. Its 29 exact runtime source files are preserved in `source-snapshot/`
and match its recorded source hashes. The latest source was compiled/host-tested,
but **not rerun natively** after this blocking result. Treat the run as historical
evidence for that precise implementation, not current-source native acceptance.

Frozen preparation SHA:
`eef782b3c37ac39f21871712553e366cd5fe8a40a76ded585955bb5a38db8db9`.
Plan SHA:
`7197a8464d5fa5357277c572a45e23ccdd66f7f57850090c9d08de657317b433`.

| Observation | Result |
| --- | --- |
| First request | v3k01qc, cold-all-unloaded |
| Budget upper bound | 1205 input + 512 output <=2048 |
| Encoder preparation/cleanup before handoff | 592 ms |
| Whole-request timeout observed | 5067 ms, TIMED_OUT |
| Full request including native completion/cleanup | 14459 ms |
| Final engine-close cleanup interval | 370 ms |
| Time from timeout observation through completed cleanup | approximately 9392 ms |
| Encoder worker death | Confirmed before Gemma loading |
| Encoder/runtime final cleanup | CONFIRMED / CONFIRMED |
| Final runtime | UNLOADED |
| Android lowMemory / thermal status | false / 0 |
| Verifier inference / output validation | Not reached |
| Further planned requests | 7 not run |

The 67 ms observation delay is polling/dispatch time, not a larger permitted
deadline. Post-deadline results remain invalid. `cleanupMs=370` is the final close
interval, **not** the entire stop-to-clean duration. Native initialization is
noncancellable in the current adapter: it continued while the decision was already
TIMED_OUT and ownership remained held. Do not claim native resources stopped at
five seconds or that cancellation met a two-second gate.

The stage trace in `encoder.json` records load-stage entry at 3421 ms and loaded
idle at 13958 ms relative to telemetry start, approximately 10.54 seconds between
those observations. Asset verification is additional work before load-stage entry.
These are stage observations, not isolated SDK benchmarking or p95 evidence.
There is no prefill/decoding sample. Highest recorded stage PSS was approximately
1.86 GiB, not a guaranteed process peak; no retained-memory/leak conclusion follows.

The instrumentation harness reports `OK (1 test)` because timeout classification,
cleanup and evidence writing completed. The supervising wrapper exits **2** with
`status=blocked`, `blockingGate=V3_FEASIBILITY`, `largeCampaignAllowed=false` and
`semanticEnablement=false`. Closed run-correlated supervision and thermal logs
were retained. No timeout retry or alternative prompt/policy was attempted.

## Host Verification

Passed:

- Debug assembly, shared JVM tests, app debug unit tests and lint.
- Secure-QA app/test APK assembly, with the existing explicit pressure flag.
- Shared Android-host tests.
- Focused validator and owner/deadline tests, including cancellation during
  noncancellable load, decoding timeout, ownership retention, duplicate collection,
  cleanup failure/quarantine and delayed/queued admission.
- Nine new host harness regressions; seven unchanged V3 preparation checks.
- `git diff --check`, wrapper syntax and frozen artifact preservation.

Fresh post-hardening totals: 148 shared JVM tests (with the configured counting
reference), 130 shared Android-host tests and 15 app unit tests each for debug and
secure-QA, all with zero failures/skips. Lint reports zero errors and 30 warnings.
The original focused review and regressions are preserved in
`docs/P2_V3_RUNTIME_FOCUSED_REVIEW.md`; they describe the pre-hardening snapshot.
The separate `docs/P2_V3_RUNTIME_FOLLOWUP_REVIEW.md` gives a focused PASS for both
fixes after 50 focused JVM tests, three cleanup-revocation probes, 21 synthetic
classifier checks and verification of the 29 archived source hashes. This closes
those code findings only, not the failed native gate or historical-host-test gap.

The complete Node suite is **66 passed / 1 failed**, zero skips. The failure is
`renewed depth freeze preserves rejected bytes and changes only the requested
question` at `scripts/tests/calibration-policy.test.mjs:315`: it calls `loadLock`
against the completed V2 campaign and assumes that its historical runtime hashes
still equal current `ModelManager`/handoff/wrapper code. Those files have now
legitimately changed, so the stale-source admission guard correctly rejects them.
The V2 datasets, lock, rejected snapshot, approval and evidence remain unchanged.
This is not a green whole-host-suite result. Do not refresh the V2 approval/lock,
silence the assertion or weaken stale-code admission to hide the failure. A separate
reviewed historical-fixture test migration is needed; the original test bytes are
also covered by the immutable V3 preservation snapshot and must remain retained.

Not run: full mobile instrumentation matrix, ordinary assistant native chat,
verifier accuracy, development/held-out evaluation, sustained workload, capacity
qualification, warm-state execution or full-library hybrid retrieval.

## Architecture Decision Required

The current serial encoder-then-unloaded-Gemma path failed its frozen cold
five-second gate. One measurement does not establish that every execution would
fail, but it does block this protocol's larger campaign. Do not retry until lucky,
exclude verifier loading/validation from timing, shorten output/context, bypass
resource checks, enable concurrent native residency, select CPU fallback or change
the prompt to obtain a pass.

Next requires an explicit reviewed architecture/performance decision. Any different
latency contract, residency strategy or lighter verification approach needs a
separately versioned protocol and its own safety/quality evidence. This change
chooses none of those alternatives and leaves semantic search disabled.
