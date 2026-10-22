# P2 Calibration-Policy Qualification Results

Recorded 2026-10-22. **Corpus correction and independent reapproval: PASS.**
**Native calibration/development collection: PASS with one retained interrupted
attempt. Development policy selection: FAILED. Held-out qualification: BLOCKED.**
Semantic search remains disabled. No indexing, vector persistence, hybrid UI,
encoder replacement, policy retuning or gate relaxation was implemented.

## Renewed Corpus

The only fixture correction is `dev020n1`:

- Before: "Borage stand: How deep are the oil cups?"
- After: "Borage stand: What is the oil cup depth in millimetres?"

The all-zero intended-negative judgment is unchanged. "Shallow" answers the old
qualitative question but supplies no numerical millimetre depth. The separate QA
agent [reapproved the complete corpus](P2_CALIBRATION_JUDGMENT_REAPPROVAL.md),
including all 320 queries, 109 passages and 11,960 pair judgments, and issued
`planning/fixtures/calibration-policy-v2/independent-review.json` before collection.

The original rejected calibration/development/heldout/protocol/lock/report bytes
are preserved in
`planning/fixtures/calibration-policy-v2/rejected/2026-10-22-depth-ambiguity/`.
Its `snapshot.json` binds each archived file. The original [FAIL report](P2_CALIBRATION_JUDGMENT_REVIEW.md)
is unchanged. The byte-exact preservation/delta regression proves that only the
authorized question changed; calibration, heldout, passages, IDs, ordering,
judgments, implementation, encoder, policies and gates remain unchanged.

| Identity | SHA-256 |
| --- | --- |
| Rejected lock | `683c371622fa303de564448127341332a9be870fe99abd63ef6e50fe4e4a8140` |
| Renewed lock | `ed4712c0cb6261b83591f3e6678c71c1565a6270e1388dda529583218872d8d1` |
| Renewed development dataset | `0d171bafc54aaeead2c59e1e6dd187494a8994a55c86d60bf050856019b2698a` |
| Unchanged protocol | `537f34bcc80506bc68c150c1563689b51107e093e53f03a33658ccd06134b55a` |

No vector or score informed the correction or either judgment review. V1's failed
fixture/fitter/evidence remains unchanged. Frozen source JSON authoring metadata
is retained; the separate hash-bound approval attestation is authoritative for
independent review, not an edited authoring-status label.

## Collection Evidence

Device: designated Samsung SM-S928B / S24 Ultra, serial `R5CX22V8D6B`. Only the
secure-QA app and test APKs were updated with `install -r`; stored data was not
wiped. Only synthetic fixture text and the pinned public encoder were used.

Profile: explicitly authorized isolated `memory-android-pressure-test-v1` with
matching secure-QA build flag and supervised `--android-pressure` execution.
Normal app policy, minimum total RAM, Android low-memory and thermal stops,
five-second per-request deadline and cleanup/quarantine requirements are unchanged.
The pinned model size is 6,120,274 bytes, SHA-256
`89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749`.

Nine successful batches contain **267 accepted vectors**: 159 calibration
(39 passages + 120 queries) and 108 development (28 passages + 80 queries).
Every batch passed its counting/loading/encoding cancellation checks: **27 checks**,
maximum recorded stop-to-terminal 104 ms. All accepted rows and batch-final states
have confirmed cleanup; native worker death is confirmed. Metadata records
11-64 tokens, encoder-only requests of 439-1,334 ms, maximum sampled worker PSS
96,783 KiB, thermal statuses 0/1/2, and Android `lowMemory=false`.
These are encoder-only measurements, not full retrieval timings, continuous peak
memory, GPU/Gemma benchmarks, broad device compatibility or P2 acceptance.

All nine successes share the same app/test/source snapshot:

- App APK: `977079960f3dc1c71f20f200510cea709bf6ae172e154991f480125c60b2b4e1`.
- Test APK: `5621eba58c0260110f5ab069b4bbd1839ca172052d9700dd64ec942f3a0bcbd1`.
- Source snapshot: `d303e64d7212c18f19d3c87de57aba564cbdf543a14e0d2826da454c79922ab7`.

Folders below are under `benchmarks/results/`. Exact run IDs, hashes and successful
attempt paths are bound in the root's `.collection/*-accepted.json` receipts and
assembled collection provenance. Assembly rejects missing, duplicate, stale,
unsafe or incompatible batches and does not select the latest attempt silently.

| Split/batch | Evidence folder | Accepted vectors |
| --- | --- | ---: |
| Calibration 0 | `p2-calibration-gate-nqsLVc` | 32 |
| Calibration 1 | `p2-calibration-gate-KIRBjV` | 32 |
| Calibration 2 | `p2-calibration-gate-9NpSlq` | 32 |
| Calibration 3 retry | `p2-calibration-gate-XgFVwY` | 32 |
| Calibration 4 | `p2-calibration-gate-ELFT31` | 31 |
| Development 0 | `p2-calibration-gate-IyLfyO` | 32 |
| Development 1 | `p2-calibration-gate-xGFUEm` | 32 |
| Development 2 | `p2-calibration-gate-dhx2wR` | 32 |
| Development 3 | `p2-calibration-gate-0Q3Moz` | 12 |

### Retained Infrastructure Failure

`p2-calibration-gate-QKeOtK`, run
`b181d033-3226-4bb8-ba4e-08b7475673ef`, is the failed calibration-3 attempt.
It contains six partial vectors and no accepted receipt. Host ADB connectivity
was interrupted: the monitor records `thermal_signal_unavailable`, failed ADB
server startup and a lost stop publication; the wrapper exited with
`Unexpected end of JSON input` before obtaining closed supervision evidence.
This attempt is failed, not a partial quality pass or a thermal-success claim.

Before retry, the device's same-run status was explicitly read and recorded in
that folder's `recovery.json`: `terminated`, `STOP_REQUESTED`,
`cleanupConfirmed=true`. No collector remained active. The first three successful
batches were reused, not recollected. The expired host temporary model also
blocked a retry before admission; only the known public model was restored from
the QA app and its exact size/SHA verified. No personal files/database were read.
Only the incomplete batch retried; failed evidence and partial vectors remain
retained and excluded from fitting/selection.

## Frozen Development Selection

One `select` invocation fitted the three predefined policies on calibration data
and evaluated the fits on development only. It exited **2**, sealed
`status: quality-failed` and `selected: null`. No second fitting/selection campaign
was run. Gates remain Recall@5 >= 0.90, nDCG@10 >= 0.80, false-match rate <= 0.05.

| Policy | Calibration cutoff | Calibration recall / nDCG / false matches | Development recall / nDCG / false matches |
| --- | ---: | --- | --- |
| F1 | 0.7754850435618785 | 0.775 / 0.745774 / 52 of 60 | 0.875 / 0.862285 / 32 of 40 (80%) |
| F2 | 0.7555694636177266 | 0.875 / 0.822610 / 56 of 60 | 0.8875 / 0.869949 / 36 of 40 (90%) |
| Constrained recall | 0.8930140721562001 | 0.066667 / 0.066667 / 3 of 60 | 0.0125 / 0.015329 / 0 of 40 (0%) |

Development macro-recall sums/denominators are 35/40, 35.5/40 and 0.5/40;
fractional contributions arise from queries with multiple relevant passages.
Per-query recall/nDCG, accepted passage IDs, sums/denominators, cutoff parameters
and calibration objectives remain in `selection/selection.json`.

Descriptive development 95% intervals use the frozen 10,000 paired query bootstrap
samples and Wilson rules; they do not replace point-estimate gates:

| Policy | Recall@5 interval | nDCG@10 interval | False-match interval |
| --- | --- | --- | --- |
| F1 | [0.7875, 0.9500] | [0.777250, 0.933190] | [0.652427, 0.895000] |
| F2 | [0.8000, 0.9625] | [0.785722, 0.940410] | [0.769482, 0.960420] |
| Constrained recall | [0, 0.0375] | [0, 0.045986] | [0, 0.087622] |

The lower cutoffs retain more relevant passages but admit too many missing-detail
negatives; the constrained policy protects no-match behavior at the cost of recall.
This is failure of these frozen policies on this synthetic scope, not proof that
every calibration policy or encoder configuration is incapable. No new policy,
cutoff adjustment, encoder change or gate relaxation follows automatically.

## Artifacts and Gate Enforcement

Campaign directory: `benchmarks/results/p2-calibration-policy-v2-ed4712c0/`.

| Artifact | SHA-256 |
| --- | --- |
| `calibration.json` | `8e4c1db6e9f4d0e7a5adab15558df3100954795289896ca565e9c250b24cab4e` |
| `development.json` | `90f66969e885a41b5fb060471a5abbf5d70288079e60a6fc2cbc1144a17de934` |
| `development-summary.json` | `0698bd5fc6c9bf680322b326e48242c5d73e2f8ca1f79d1f9bf221a2319e97ce` |
| `selection/selection.json` | `eb1744f10520a1d58241a885eca7d2f5a664398148f2f7df3ed26bb6a474aa60` |

`execution.json` and `selection-seal.json` in the frozen-input root bind this single
selection and its output directory. Actual held-out planning was checked after
failure and rejected with missing/incompatible selected policy, before export.
There are no held-out batch receipts, no held-out collection, no `exposed.json`
and no held-out metrics. Held-out text/judgments were reviewed before collection;
its model results were never exposed. Do not tune using it or present this failed
development campaign as independent held-out qualification.

## Verification and Exit

- PASS: byte-exact rejected snapshot, single-wording delta, all-zero judgment
  preservation, unchanged protocol/policies/gates/encoder/implementation.
- PASS: independent reapproval and approval-validation command for renewed hashes.
- PASS: 51 Node host tests, zero failures/skips, including freeze-preservation,
  owner/cleanup, selection-before-export and objective regressions.
- PASS: baseline debug build/shared JVM task/lint and secure-QA app/test assembly;
  most Gradle outputs were up-to-date. No new all-device mobile-matrix claim.
- PASS: nine accepted supervised native batches and 27 cancellation checks.
- FAILED, RETAINED: one ADB-interrupted collection attempt, safely reconciled
  before the explicit infrastructure retry; one development selection failure.
- BLOCKED / NOT RUN: held-out model collection/evaluation, semantic enablement,
  indexing, full-library capacity/latency and consolidated P2 acceptance.

The immediate corrected/frozen/independently approved corpus deliverable is met.
Calibration-policy qualification remains unsuccessful. Preserve this failed
campaign and propose a separately versioned investigation before any further
tuning. The encoder was not replaced; semantic search remains disabled.
