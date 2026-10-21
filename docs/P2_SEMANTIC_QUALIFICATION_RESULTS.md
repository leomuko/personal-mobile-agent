# P2 Semantic Qualification: Blocked

Date: 2026-10-21. **The requested indexing/hybrid-search slice is incomplete.**
The blocking quality gate failed. Runtime safety and qualification tooling have
focused evidence, but semantic search remains disabled. No production index,
vector persistence, indexing UI, embedding installer/repair UI or search
integration was added. Existing text Sources and keyword search are unchanged.

## Contract and Delivered Prerequisites

The [accepted execution contract](../planning/P2_SEMANTIC_SEARCH_CONTRACT.md)
preserves the existing manager/database, immutable index publication, quotas,
bounded preemption/retrieval, owned assets and explicit outcomes. Its dependent
features were not built after qualification failed.

- Frozen [fixture](../planning/fixtures/semantic-retrieval-v1.json) and
  [lock](../planning/fixtures/semantic-retrieval-v1.lock.json), including candidate,
  judgments, split, language scope, ranking/calibration rules and thresholds.
  SHA-256: `32f9d6d231fb5f5acca593eb27e128bc0f09266e311375db82cff100b6325abe`.
- Encoder-only prerequisite API under ModelManager's existing mutex/opaque owner.
  It reclaims resident Gemma, admits exact input, encodes and waits for confirmed
  cleanup, without loading Gemma to obtain a vector. It returns one typed terminal
  outcome and transient defensive vector copies; unavailable is not empty success.
- Shared cancellation/epoch/lifetime/deadline checks, duplicate-owner rejection,
  pending cleanup reservation and restart quarantine apply. Privileged release
  now also joins already-owned native cancellation signals, including their
  telemetry completion, before returning quiescent.
- QA adapter waits for observed Binder death; a dead-object send exception no
  longer completes its death deferred. Late replies/death evidence cannot update
  another worker instance. Link failure without confirmed death fails closed.
- QA-only frozen vector collection through that owner, plus host ranking,
  calibration and per-language metrics. Reports contain only public synthetic
  fixtures/embeddings and resource/identity metadata, never personal library data.

The encoder-only primitive is not a finished foreground SearchRequest pipeline:
production retrieval must still retain its reservation through scanning/result
publication and connect the real revision/fence snapshot. No background indexer,
recording-preemption integration or automatic resume behavior was implemented.

## Frozen Evaluation

Candidate remains the pinned USE-QA artifact, MediaPipe tasks-text 1.1.1, SDK
embedding head 0 (100 dimensions), SDK L2 normalization, response-text formatting
for both documents/questions, empty query/context branches, and 192-token gate.
The SDK head index is not a claim about the ordering of raw graph output tensors.
No alternate head, formatting, tokenizer or runtime was selected after evaluation.

There are 34 passages and 60 queries: six English calibration positives plus six
calibration negatives; 20 held-out English answerable and 20 unanswerable queries;
three answerable/one unanswerable cross-language diagnostic for each of Swahili
and Japanese. English alone was declared required before measurement. Diagnostic
languages do not acquire supported-language status even if a small sample passes.
Contradictory relevant passages retain relevance judgments, not truth endorsement.

Cutoff selection sees calibration rows only. It maximizes query-answerability F1;
ties prefer fewer false positives then the larger cutoff. Cosine scores rank
descending, exact ties use passage IDs, and cutoff filtering precedes Recall@5/
nDCG@10. Gains are `2^grade - 1`. False match means any accepted passage for an
unanswerable query. These definitions were frozen, not changed after measurement.

The calibration cutoff was **0.8422642668438602**, with calibration F1 1.0.
Both complete collections produced the same cutoff and held-out metrics:

| Language | Scope | Recall@5 | nDCG@10 | False Matches |
| --- | --- | ---: | ---: | ---: |
| English | Required; 20 answerable/20 unanswerable | **0.45** | **0.45** | 0/20 |
| Swahili | Diagnostic only; 3/1 | 0 | 0 | 0/1 |
| Japanese | Diagnostic only; 3/1 | 0 | 0 | 0/1 |

Required gates remain Recall@5 >= 0.90, nDCG@10 >= 0.80 and false-match rate <=
0.05. English fails the first two. Absence of false matches on this small set
does not compensate for missed relevant passages or prove general abstention.
Embeddings varied across inputs; a constant head was not established. This is
a failure of the pinned candidate/configuration/calibration policy against the
frozen gate, not proof that Gemma chat cannot run or that every encoder is unsuitable.
No cutoff/fixture tuning was performed using held-out results.

## Final S24 Evidence

Designated S24 Ultra SM-S928B, existing isolated
`memory-android-pressure-test-v1` profile: Android lowMemory=false required, thermal
and cleanup stops retained. Ordinary 3 GiB admission floors and release packaging
are unchanged. This is not a low-end-device or default-policy qualification.

Final collection: ignored `benchmarks/results/p2-semantic-gate-8SCUVh/`, run
`e1671616-0078-4139-9699-9b46677e2f0f`.

- Instrumentation: **OK (1 test)**; 94 vectors collected, every allocated worker's
  death/cleanup confirmed, no Gemma loads. Collection passes; semantic gate is
  explicitly **blocked**, wrapper exit code 2, `blockingGate=SEMANTIC_QUALITY`.
- Counting/loading/repeated-native-encoding Stop checks passed. Stop-to-terminal
  observations were 3/79/76 ms; counting cancellation deliberately suspends the
  admission stage and allocates no worker. These are individual observations,
  not p95 guarantees or a native-hang recovery claim.
- Inputs counted 4-40 tokens; this corpus does not replace the 192-token boundary
  proof. Individual encoder-only requests including cleanup/metadata took
  521-1030 ms. Peak recorded worker PSS was 96,641 KiB. These are phase samples,
  not a continuous peak measurement or a complete hybrid-retrieval benchmark.
- Folder records APK/source hashes, frozen fixture hash, policy/preflight,
  supervision/thermal data, instrumentation, native vector collection and host
  `quality.json`. Final qualification source hashes matched the working tree;
  independent replay reproduced the published metrics exactly.

Existing seven-case encoder-to-Gemma regression passed on the final code in
`benchmarks/results/p2-foreground-gate-3hNarR/`, run
`e1c8c092-f7cd-4f42-ad74-db147936e661`, **OK (1 test)**, confirmed final cleanup.
Its source hashes also matched. This verifies the existing runtime handoff,
not indexing preemption, physical drawer/full-app UI transitions or P2 acceptance.

## Failed and Earlier Attempts

- `p2-semantic-gate-WNZYQm`: stopped before collecting quality vectors when an
  encoding-cancellation check found cleanup could finish via DeadObjectException
  before recorded Binder death. The adapter now requires the death observation.
- `p2-semantic-gate-4CUUK2`: 94 vectors and all three cancellation checks passed;
  the semantic gate failed with the same metrics above. It predates the final
  release-quiescence change and is retained separately, not substituted for it.
- `p2-foreground-gate-K6dfhH`: first two Gemma requests and counting cancellation
  passed, then the loading-phase wait timed out; final cleanup was confirmed.
- `p2-foreground-gate-KA8K6E`: typed diagnostics identified REJECTED/BUSY with no
  new worker, not a slow model load. Prior cancellation telemetry was still owned
  after privileged release returned. Release now waits for those signals; the
  extended shared regression and final seven-case S24 run pass. Neither five-second
  deadline nor memory/thermal limits were extended to obtain that pass.

## Local Verification

- **263 Kotlin tests**: 120 shared JVM (including the 1,038-case differential),
  102 Android-host, 11 UI JVM, 15 debug app and 15 QA app; zero reported skips/failures.
- **35 Node tests** and **12 optimized-Python tests** pass. New checks cover frozen
  identity/thresholds, calibration isolation from held-out queries/judgments,
  graded contradictory relevance, constant-head rejection, stale/unsafe collection
  rejection and encoder-only ownership/cancel/deadline/unavailable recovery.
- Debug/QA/release builds, debug/QA instrumentation compilation and lint pass;
  lint reports zero errors and 30 existing freshness/UseKtx warnings. Shell/JS
  syntax and whitespace checks pass. Compilation is not distribution approval.

## Reproduction and Next Gate

Build/install only matching QA app/test APKs with explicit pressure-profile flags,
then run from the repository root with the designated S24 and public pinned model:

```sh
./gradlew :androidApp:assembleSecureQa :androidApp:assembleSecureQaAndroidTest \
  -PtestBuildType=secureQa -PandroidPressureMemoryTest=true --console=plain
ANDROID_SERIAL=YOUR_DEVICE P2_ENCODER_MODEL=/absolute/path/to/pinned-use.tflite \
  node scripts/run-p2-embedding-gate.mjs --semantic-qualification --android-pressure
node --test scripts/tests/semantic-quality.test.mjs
```

The quality wrapper returns 2 on a complete but unqualified collection, 1 on
failure and 0 only if its frozen quality gates pass. No automatic feature-enable
flag is written even on a qualification pass. Fixture changes fail the lock and
native hash check. Any revised candidate/head/format/calibration policy needs
appropriate new identity/counting/runtime evidence and independent held-out
qualification; do not relabel v1 as untouched after using it for development.

Not run/blocked: production installation and repair, index schema/publication,
quotas/checkpoints, real indexing preemption/recording suppression, hybrid search
UI, 20 cold plus 20 warm full queries, 100-source/2,000-chunk and maximum-capacity
campaigns, broader mobile QA and consolidated P2 acceptance. Grounded answers,
citations, Memory, PDF/OCR and mixed sustained P2 work remain later scope. No
commits, branch changes, pushes or personal-data exports were performed.
