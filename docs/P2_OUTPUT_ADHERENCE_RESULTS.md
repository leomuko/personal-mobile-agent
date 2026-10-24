# Output-Adherence V1 S24 Results

Date: 2026-10-24. **BLOCKED: first control failed strict output validation.**
P2 remains incomplete; native feasibility is not qualified and semantic search
remains disabled. The remaining five controls were not run.

## Reviewed Execution

The same user-authorized primary independent QA agent performed the corpus,
implementation, correction follow-up and frozen-bundle correspondence reviews.
The initial implementation FAIL is preserved; its runtime-delivery promotion
finding was corrected and independently re-reviewed before freeze preparation.

- Device: Samsung `SM-S928B`, Android 16; designated serial bound in local
  authorization, not used as synthetic source content.
- Protocol: `evidence-output-adherence-v1-draft-1`, six ordered requests, first-block
  stopping, 30,000 ms per request including cleanup.
- Source snapshot: `4672d0caf4206a026dbcea7f9f6f71c30fef49df29234a1bd59194cc497486d5`
  over 309 files; both frozen APKs independently checked before execution.
- Execution freeze:
  `e90c8492826ed045d5c902cc4755d2c718d7d3e94022bca97ca316f176e1b747`.
- Isolated profile: `memory-android-pressure-test-v1`; fresh OS low-memory and
  thermal checks retained, available-memory floors not enforced. Ordinary app
  thresholds are unchanged. Context/output remain 2048/512; encoder limit 192.
- Separate device authorization records the user's instruction to run S24 checks
  and binds the exact freeze, profile, serial and six-request scope.
- Run: `f5b59c18-5905-483c-b9ad-88e719a5ef17`.
- Local artifacts: `benchmarks/results/p2-output-adherence-gate-4Ke22T/`.
- Preserved bundle/authorization:
  `benchmarks/results/p2-output-adherence-execution-freeze-20261024-1/`.

These local evidence directories remain ignored. No model binaries, secrets or
personal conversation data were added to Git. Inputs are the frozen synthetic
controls; the separate QA oracle is not model input.

## Result

| Observation | Result |
| --- | --- |
| Instrumentation | `OK (1 test)`, 21.422 s; harness completion, not quality acceptance |
| Supervised wrapper | Exit 2, `status=blocked`, gate `OUTPUT_ADHERENCE` |
| Executed control | `oa01` only; five controls not run |
| Runtime outcome / delivery | `INVALID_OUTPUT` / `REVOKED` |
| Strict validator | `SCHEMA`; control-shape evaluation not reached |
| Runtime decision | 17,098 ms |
| Final protocol decision | 17,472 ms |
| Whole request including cleanup | 17,473 ms |
| Reported runtime cleanup duration | 369 ms |
| Encoder preparation | 660 ms |
| Encoder cleanup / runtime cleanup | Both `CONFIRMED`; runtime `UNLOADED` |
| Encoder death / Gemma load | 356376425 / 356380365 monotonic ms; serial allocation |
| SDK input / output tokens | 316 / 52 |
| Sampled app PSS maximum | 2,089,871,360 bytes; sampled, not continuous peak proof |
| Recorded memory / thermal | All six allocation samples: `lowMemory=false`, status 0 |
| Telemetry | Zero failed/dropped samples; `telemetryFailed=false` |
| Supervision | Fresh run-correlated `closed` lease, no loss reason |

The output was plain JSON, not Markdown-fenced, but its citation had `p`, `r`,
`b` and `t`. The unchanged contract requires `p`, `r`, `b`, `e` and `q`; the end
offset and quotation fields were absent. It was therefore correctly rejected.
The complete raw output remains in `encoder.json`; it was not repaired, renamed,
unfenced or retried. No supported-answer or semantic-accuracy result follows from
the apparent text content.

Normal supervision closure has `cleanupConfirmed=false` in its lease status:
that field records the emergency-loss cleanup callback, which was not invoked.
Confirmed normal encoder/runtime cleanup is separately recorded in the request
and final report. A closed lease alone is not model-cleanup proof.
Post-run `pidof dev.edgecompanion.app.secureqa:embedding_gate` returned no PID
(exit 1); no process was stopped by that read-only check.

## Evidence Identities

| Artifact | SHA-256 |
| --- | --- |
| `result.json` | `1964af5aa207898514e452aea22cefe3eb6909f1dc19e8b6ceb84d2267bbcb51` |
| `encoder.json` | `6bbe7b8c22c76396f1a9e8ef7f53047d8b806c01cefac8500e95f733a7aa6649` |
| Exact diagnostic string | `dfb948c62558b2ce609b6c7927d21e1619f09c6a57cdb377db5cc4b5629d5786` |
| `instrumentation.txt` | `85df7a2a8bfd6676f6b5709dd2a1591039bc0e8ad7a3c96b23152fcddbd61b9c` |
| `supervision.json` | `392b5dc74de0c0c51287ad2f8c214d05b5de72eaf4f5a0d94fc4080a1f0c9cf5` |
| Execution authorization | `9c86d10093df69911bad5abf0378b30fbc441736195cb2a25202dbd81283947f` |

## Checks And Limits

Host checks passed before execution: 115 Node tests, 159 shared JVM tests,
135 shared Android-host tests, 23 app unit tests per debug/QA variant, debug/QA
builds and QA instrumentation compilation. Independent fresh lint reported zero
errors and 21 warnings; earlier incremental lint had reported 30 warnings.
See the implementation review/follow-up for fresh versus reused result details.

The independently reviewed freeze preserved exact inputs, strict validator,
resource profile and APK identities. The runner installed verified private copies
and retained one attempt ledger. No evidence, approvals or ledger were reset.
Historical V1/V2/V3/V4 failures and the original preparation remain unchanged.

Failed qualification: strict schema adherence on `oa01`. Not run: `oa02`-`oa06`,
larger campaigns, semantic quality, capacity/latency qualification, sustained P2
acceptance and production integration. This is not an OOM or five-second search
result; the separate experiment finished below its approved 30-second ceiling.
It does not change the five-second search contract.

Preserve this failure. Any further output-contract investigation requires a
separately reviewed scope; do not silently repair the result, weaken validation
or continue the five unexecuted controls. No commits or pushes were made.
