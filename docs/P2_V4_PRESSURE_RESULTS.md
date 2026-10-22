# V4 Android-Pressure S24 Result

Date: 2026-10-22. Status: **BLOCKED at strict output validation**.

## Approved Scope

The user authorized the S24 experiment and explicitly replaced fixed preload
headroom with OS lowMemory-based memory admission. The [profile amendment](P2_V4_PRESSURE_PROFILE.md)
uses the existing isolated `memory-android-pressure-test-v1`; ordinary app policy
is unchanged. Independent reviewer Locke passed the 288-file implementation/
host-QA snapshot, recorded in `docs/P2_V4_PRESSURE_REVIEW.json`.

Host checks: 84 Node, 12 optimized-Python, 154 shared JVM, 135 shared Android-host,
and 20 app unit tests for each variant passed. Builds and lint completed (zero
errors, 30 warnings). The reviewer separately passed six focused Node tests,
140 in-memory probes, 12 compiled Kotlin test executions and unknown-profile
rejection. These are not evidence of model output quality.

## Frozen Attempt

- Device: unlocked Samsung SM-S928B, designated serial `R5CX22V8D6B`.
- Run: `5414a589-fb27-450d-b0a9-539031011329`.
- Folder: `benchmarks/results/p2-v4-feasibility-gate-4guyjf/`.
- Freeze: `benchmarks/results/p2-v4-execution-freeze-20261022-android-pressure-1/execution-freeze.json`.
- Freeze SHA-256: `e30f6cb23860ee47d8c42ac6ae76a3eccdc1d199f3833c3c13d351f06a2dec91`.
- Source snapshot SHA-256: `61cee1e16656eb65f5145703dd3060c668c8ad3a9182aa1a9eb7d2bfd3a9366a`.
- Profile SHA-256: `83c6e3f2ac535be423bfd0e359546098c51743be3bbf52353ca065d87ff85113`.
- Plan SHA-256: `cb207461dafb10bffc34834368aa6e7ec5707e0fa9dce7ffd4720dac5c27d496`.

The exact pressure-profile APKs were installed without app-data reset. Three
fresh stable preflight samples passed with lowMemory=false, thermal status 0
and no fixed requiredAvailableMemoryBytes floor. The last sample reported
4027727872 available bytes. That observation did not block admission.

## First Request

`v3k01qc`, cold-all-unloaded, GPU, no fallback. One request reached a terminal
outcome, zero produced an eligible structurally valid result, seven were not run.

| Observation | Value |
| --- | --- |
| Outcome | INVALID_OUTPUT; validation rejection JSON |
| Delivery | REVOKED; no usable result published |
| Decision | 19686 ms |
| Full request through cleanup/evidence capture | 20138 ms |
| First text | 15610 ms |
| Encoder preparation/confirmed cleanup | 668 ms |
| Final runtime cleanup interval | 448 ms |
| SDK input/output tokens | 348 / 149 (2048 context / 512 output unchanged) |
| Conservative input bound | 1205 |
| Encoder worker death / first Gemma load | 208351285 / 208355059 monotonic ms |
| Runtime / encoder cleanup | CONFIRMED / CONFIRMED |
| Final runtime | UNLOADED |
| Sampled peak app PSS | approximately 1.949 GiB; stage-sampled, not a guaranteed peak |
| Failed/dropped allocation telemetry samples | 0 / 0 |
| Android lowMemory / thermal | false / 0 |
| Supervised skin samples | 34.3-35.6 C |

The model returned Markdown-fenced JSON instead of the required single raw JSON
object. The frozen strict validator rejected it. The raw text is retained exactly
in `encoder.json`; code fences were not stripped, fields repaired, or output
re-prompted. Inspection also identifies three citations for one detail (two are
allowed), and citation objects using `t` instead of the required span-end `e`
and quotation `q` fields. Removing fences alone would not satisfy the contract.
These are host observations of the unchanged text, not additional on-device
validator outcomes: downstream schema/span/semantic evaluation was not reached
after the JSON rejection. No semantic support, conflict handling or
injection-resistance result is claimed. See the separate
[output-adherence investigation](../planning/P2_OUTPUT_ADHERENCE_INVESTIGATION.md).

This one request's terminal and full-cleanup observations are below 30000 ms, but
it **failed the required output gate**. It is not a feasibility pass, p95 estimate,
accuracy assessment or evidence that all eight requests would meet the deadline.

## Stop, Cleanup And Evidence

The on-device gate recorded `caseGateAccepted=false` and stopped before request
two. The runtime report ends with finalCleanup=CONFIRMED. Supervision closed with
reason=null; its normal-close `cleanupConfirmed=false` field is not the runtime
cleanup result (the supervisor's emergency cleanup callback was not exercised).
Runtime/encoder cleanup is evidenced separately above, including POST_RELEASE.
No safety-stop file was present. The wrapper exited **2** with V4_FEASIBILITY,
not a successful model-quality result. The instrumentation's `OK (1 test)` means
the harness recorded and classified the blocking outcome, not that V4 passed.

Host replay of `classifyV4` reproduces blocked, completedRequests=1 and
notRunRequests=7; this counter denotes terminal requests, not successful answers.
The closed-supervision record validates for the exact run and start time.
Raw model output, memory/worker traces, heartbeats, thermal samples, instrumentation,
plan, code/APK hashes, freeze, authorization and the immutable ledger are retained.

Both previous baseline attempts remain unchanged. No repeat native run, prompt
change, backend change, relaxed validator, larger campaign or held-out collection
followed this result. Further investigation requires separate authorization and
versioned preparation; semantic enablement and full P2 acceptance remain blocked.
