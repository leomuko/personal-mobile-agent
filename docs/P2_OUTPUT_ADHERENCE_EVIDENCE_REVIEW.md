# Output-Adherence S24 Evidence Review

Date: 2026-10-24. Reviewer: OpenAI Codex, the retained user-authorized primary
independent QA reviewer. No delegation, device access or source fixes.

**Campaign integrity: PASS. Qualification: BLOCKED.** The retained evidence
supports a correctly stopped first-control failure, not successful qualification.
No actionable evidence or results-report defect was found. This review grants no
retry, continuation, larger campaign or semantic enablement.

## Reviewed Run And Snapshot

- Run: `f5b59c18-5905-483c-b9ad-88e719a5ef17`.
- Evidence: `benchmarks/results/p2-output-adherence-gate-4Ke22T/`.
- Freeze bundle: `benchmarks/results/p2-output-adherence-execution-freeze-20261024-1/`.
- Freeze SHA-256:
  `e90c8492826ed045d5c902cc4755d2c718d7d3e94022bca97ca316f176e1b747`.
- Source snapshot, recomputed from imported `adherenceSnapshot('.')`:
  `4672d0caf4206a026dbcea7f9f6f71c30fef49df29234a1bd59194cc497486d5`.
  All 309 source entries and the two frozen APKs still match their frozen hashes.
- Branch `dev`; reviewed checkpoint/HEAD
  `57ebf3653578c48cc5670b9c95f8f11b7594b2aa`.
- Results document reviewed without edits: `docs/P2_OUTPUT_ADHERENCE_RESULTS.md`,
  SHA-256 `8b996b1fe076c65b1569f22dcfdd103b63668cdbc9d659c684469537971cb896`.

Applied the already-read independent QA, Kotlin review and testing criteria to
the evidence replay and relevant frozen classifier/supervision contracts. No
implementation or broad code review was reopened.

## Independent Replay

Read-only replay of `admitAdherenceExecution` accepts the existing recorded human
authorization for its designated serial, SM-S928B, exact freeze/profile and
six-request scope. The run's authorization copy is byte-identical to the bundle's
`device-authorization.json`; no new authorization was manufactured. The run's
freeze copy is also byte-identical to the reviewed freeze.

Ran `classifyAdherence` on the actual encoder.json using the frozen plan/oracle,
run ID, plan/freeze hashes, policy and recorded start time. The replay returns:

```json
{
  "status": "blocked",
  "completedRequests": 1,
  "notRunRequests": 5,
  "largeCampaignAllowed": false,
  "semanticEnablement": false,
  "strictValidRequests": 0,
  "shapeCorrectRequests": 0,
  "semanticQualityQualified": false
}
```

Every returned field agrees with result.json. The only case is oa01, with
INVALID_OUTPUT / SCHEMA, runtime and final delivery REVOKED, controlShape
NOT_REACHED and caseGateAccepted=false. No successful abstention or supported
answer is credited. No oa02-oa06 results are present.

Also replayed the exact 113-byte diagnostic string through the unchanged compiled
JVM EvidenceOutputValidator with the original delivered passage/context and
52 generated tokens. The validator returns SCHEMA. The citation has `p,r,b,t`;
the required `e,q` fields are absent. The text is plain JSON, but it violates the
strict output schema. It was never repaired or resubmitted for acceptance.

The raw evidence file and diagnostic-string hashes were unchanged by replay.
Initial local JVM-tool/classpath probes did not run the validator successfully;
explicit loading of the existing shared JVM JAR and Kotlin standard library via
JShell resolved that tooling issue. The successful replay reported
`QA_RAW_VALIDATION=SCHEMA`. No source or test file was created or changed.

Instrumentation records `OK (1 test)` and 21.422 seconds, meaning the harness
completed its safety/first-block handling. This is independent of qualification.
The frozen wrapper maps the replayed blocked result to exit 2, consistent with
the user-reported host exit. A standalone captured host-process exit-code file
is not present in this archive.

## Identity, Ownership And Cleanup

The retained exact input plan and separate oracle hash/deeply match the approved
freeze. OA01's pack hash, question identity, detail order, input bound 1175 and
delivered source all match. The oracle remains separate from model input.

All 37 recorded execution-source hashes match frozen/current source. Recorded
app/test APK hash markers match the reviewed frozen pair:

- App: `5a1003525e9856e7853aa36c6b4c5ef88ff1c4f2633d757bb152e14999ee628a`.
- Tests: `edf219a2fe5cf2f7c5fd8567ad76807606fc226cb8c74a8e95217de412dbdd83`.

The reviewed runner installs verified private copies before exporting/comparing
the installed resource profile. The retained profile exactly matches the freeze.
These are consistent recorded execution bindings; the archive does not contain
a separate per-install command transcript or independent installed-APK readback.
No device inspection was performed to extend those claims.

One retained OA campaign directory and one exclusive ledger were present. The
ledger binds this freeze, run and absolute evidence directory, with its start
before device work. Its SHA-256 is
`7923dfb5ebe482a2e637e900a723a02b49020f8ae69aa32b2211fdb8043fc47a`.
There is no retained retry or continuation for this freeze in the inspected
artifacts; no ledger was reserved, removed or reset by this review.

The blocked classifier branch intentionally does not exercise every successful
case telemetry check, so lifecycle/timing fields were independently asserted:

| Recorded observation | Audited result |
| --- | --- |
| Runtime / final decision / whole request | 17098 / 17472 / 17473 ms; ordered, whole request below 30000 ms |
| Runtime cleanup / encoder preparation | 369 / 660 ms; separately retained |
| Worker | Fresh instance; CHECKING, LOADING, ENCODING in order on one PID |
| Encoder death / Gemma load | 356376425 / 356380365 monotonic ms; death precedes load by 3940 ms |
| Native initial states | No resident encoder worker; Gemma UNLOADED; cold tokenizer admission |
| Worker maximum sampled PSS | 94452 KiB, below 384 MiB cap |
| SDK input / output | 316 / 52 tokens; within 2048/512 limits |
| Normal encoder / runtime cleanup | Both CONFIRMED; final runtime UNLOADED; finalCleanup CONFIRMED |
| POST_RELEASE sample | UNLOADED, engineOwnedAtEvent=false |

The single worker and one Gemma-load observation support serial allocation for
this executed request. They establish no behavior for the five unrun controls.

## Resources And Supervision

All six allocation samples carry the frozen pressure profile and GPU backend,
valid ordered event/sample times, positive memory signals, lowMemory=false and
thermal status 0. No telemetry failure or dropped sample is recorded. Maximum
sampled app PSS is 2,089,871,360 bytes; this is not a continuous peak bound.

Three fresh preflight samples pass the declared pressure policy with valid total
RAM, OS-pressure and thermal signals. Available-memory floors are explicitly
unenforced only under this isolated profile. Five thermal samples remain at
status 0, with maximum skin temperature 36.3 C below 45 C. No safety-stop artifact
exists. Ordinary application thresholds remain unchanged.

Five run-correlated heartbeat records have increasing sequences/device times;
maximum observed spacing is 5320 ms, below the 20000 ms lease. Normal closure is
1244 ms after the last retained heartbeat. `validSupervision` accepts the fresh
closed record with reason=null and the frozen lease/poll/cleanup settings.

Supervision `cleanupConfirmed=false` is correct for normal closure: the source's
`status("closed")` uses the default false value, while the cleanup callback and
its result are reserved for the loss path. The lost flag is checked before normal
closure. This does not negate normal runtime/encoder cleanup, which is separately
confirmed in the request and final evidence. A closed lease alone would not be
sufficient model-cleanup proof; the independent cleanup fields above supply it.

## Report Accuracy And Preservation

The results document accurately distinguishes the failed schema qualification,
first-block stopping, harness completion, normal cleanup, bounded single-case
timing and disabled semantic search. Its numeric observations and listed evidence
hashes match this audit. Earlier host/build counts refer to the prior reviews;
no such suite or build was rerun for this evidence audit.

| Retained artifact | SHA-256 |
| --- | --- |
| result.json | `1964af5aa207898514e452aea22cefe3eb6909f1dc19e8b6ceb84d2267bbcb51` |
| encoder.json | `6bbe7b8c22c76396f1a9e8ef7f53047d8b806c01cefac8500e95f733a7aa6649` |
| Exact diagnostic string | `dfb948c62558b2ce609b6c7927d21e1619f09c6a57cdb377db5cc4b5629d5786` |
| instrumentation.txt | `85df7a2a8bfd6676f6b5709dd2a1591039bc0e8ad7a3c96b23152fcddbd61b9c` |
| supervision.json | `392b5dc74de0c0c51287ad2f8c214d05b5de72eaf4f5a0d94fc4080a1f0c9cf5` |
| Execution authorization | `9c86d10093df69911bad5abf0378b30fbc441736195cb2a25202dbd81283947f` |

The complete 21-file run archive identity is
`422b9168f2723654b44d14141bb53eee28a35f853e9c90ce76f44be0ca9bdbd3`, computed
as SHA-256 of compact JSON for the lexically sorted map from repository-relative
file paths (including the evidence-directory prefix) to each raw-file SHA-256.
This includes thermal, preflight, policy, source and APK-marker artifacts.

Original preparation and all prior reports remain untouched. The original FAIL
report still hashes to
`708bceabd0537e1493d4f3bb3174c0e0002f730c9941ff45bdda7583afabce31`.
The executable snapshot and all frozen artifact hashes remain unchanged. No
historical output was transformed or reclassified.

Passed: admission/identity replay, actual classifier replay, exact JVM validator
rejection, independent lifecycle/resource/supervision checks, archive hashes and
results-document comparison. No unresolved evidence-integrity check remains.
Qualification remains BLOCKED on schema adherence, independently of those passes.

Not performed: ADB, device/native workloads, retries, continuation, source/test
fixes, builds, broad test suites, Git mutations or new approval records. Hardware
readiness/Android-version observations from the earlier authorized session were
not remeasured. Deferred issues remain unchanged; P2 is incomplete.

Only authored path: `docs/P2_OUTPUT_ADHERENCE_EVIDENCE_REVIEW.md`. Preserve the
failed request and five not-run controls. Any further experiment requires a new
reviewed scope; this campaign-integrity PASS does not reopen the stopped run.
