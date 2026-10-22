# V4 Initial Focused Review (Before Corrections)

Date: 2026-10-22. Independent reviewer: Kuhn, agent
`01a164aa-4f29-7bd0-950a-ea4ac07ca6ed`. Read-only review; no device execution.

Verdict: **FAIL** for `v4-implementation-and-host-qa` at the initial snapshot.
This is retained historical feedback, not approval of the later corrected source.

Snapshot identity (285 files):
`391065bd3d118a6a534026a3ac2d445b81e5460d5bfa7e11cd06161711b9914b`.

## Findings

1. P1: device continuation did not check the full telemetry contract. A trace with
   `failedSamples=1` and `telemetryFailed=false` could advance to request two;
   the final host classifier would reject it only afterward. Require complete
   on-device per-case acceptance before advancing and cover the stopped loop.
2. P2: host classification did not bind stage timestamps/order to the request and
   Gemma loading. Reversed/missing sample timestamps and an unrelated trace start
   could still pass. Bind clock intervals and derive exact load observations.

Four focused Node tests and read-only synthetic probes reproduced these gaps.
The missing-authorization wrapper blocked before ADB. Broad main-run test results
were not duplicated. No native test, app state or held-out data was accessed.

## Implementer's Response

Added a complete test-only `V4CaseGate` used before the next device request, with
unit coverage showing a sampling failure starts only one request. Added explicit
case/campaign monotonic intervals, timestamp/order/resource checks and exact
trace-to-load correlation in both device and host acceptance. The final report
must separately identify the follow-up review; this FAIL is never converted into
an approval attestation or silently replaced.
