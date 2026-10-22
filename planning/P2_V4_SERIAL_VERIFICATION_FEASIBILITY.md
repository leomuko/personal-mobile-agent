# P2 V4 Draft: Explicit Serial Evidence Verification

Date: 2026-10-22. Protocol identity: `serial-evidence-verification-v4-draft-1`.

**Design proposal only. NOT approved, frozen for execution or implemented.**
Native execution, the remaining V3 cases, larger campaigns, development/held-out
collection and semantic enablement remain blocked. This document grants no
permission through its existence or its relationship to an approved V3 corpus.

## Purpose And Changed Contract

Test whether an explicit verification request can finish under a proposed
**30000 ms whole-operation ceiling** using the existing serial encoder -> confirmed
release -> Gemma path. This is not V3 with a silently relaxed threshold: V3 remains
failed under 5000 ms. The changed operation and experimental ceiling need explicit
approval under [ADR 0006](../docs/decisions/0006-evidence-verification-latency.md).

Bounded passage search retains its five-second deadline and <=1 second keyword /
<=2 seconds complete-hybrid p95 targets. Search cannot advertise verified answers.
No full-library latency or quality claims follow from fixed candidate packs.
Thirty seconds is not a proposed production p95 or a guarantee of feasible inference.

## Immutable Inputs

- Preserve every V1/V2/V3 fixture, lock, approval, raw attempt and failed result.
  V3 freeze SHA-256:
  `eef782b3c37ac39f21871712553e366cd5fe8a40a76ded585955bb5a38db8db9`.
- Use only V3 **calibration** packs `v3k01qc`, `v3k01qm`, `v3k01qs`, `v3k01qt`,
  with exact existing instructions, compact serialization, clipping, passage/
  revision IDs, detail IDs and pack hashes. No prompt fitting or rewritten answers.
- Copy only question-derived details and delivered passage text into the device
  plan. No gold judgments, development/held-out data or selection scores enter it.
- Keep V3's pinned artifact/tokenizer/head/format/runtime/sampler identities. GPU
  only, no fallback; context 2048, reserved output 512, encoder limit 192. Keep
  exact byte admission and SDK token validation. Output schema/span/conversion
  rules and all output size/reference limits remain unchanged.
- Reusing calibration packs tests latency/lifecycle only. It is not independent
  quality evidence and cannot select a policy or certify any supported language.

## Future Execution Preconditions

All must hold before a runner may access a device:

1. Explicit architecture approval identifying this version and 30000 ms ceiling.
2. Separate implementation authorization, followed by focused host tests and
   independent review of the runner, ownership and success classifier.
3. An immutable V4 execution freeze binding this document, the full V3 protocol,
   rubric/instructions, exact four packs, counting proof, code and APK hashes,
   plan schema, resource policy, test order and acceptance checks. Bind a separate
   approval to that new freeze; never edit or reinterpret the V3 approval.
4. Separate authorization for the designated unlocked S24, declared resource
   profile and this eight-request experiment. The existing isolated QA pressure
   profile needs explicit selection; normal app thresholds stay unchanged.
5. Current-source counting and supervision/cancellation prerequisites pass. Old
   native evidence is historical, not a replacement for changed-source checks.

There is no executable V4 runner, approval attestation or device plan in this
preparation change. It must not be passed to the existing V3 wrapper.

## Frozen Proposed Small Sequence

Eight requests, in the following order, with new V4 run/request identities:

| State | Pack order | Meaning |
| --- | --- | --- |
| cold-all-unloaded | qc, qm, qs, qt | Each starts with confirmed Gemma unload, a fresh isolated encoder worker, and process-local admission caches reset. |
| cached-admission-only | qc, qm, qs, qt | Only verified admission metadata may be reused; Gemma begins unloaded and the encoder worker is always fresh. |

Suffixes refer to the four full IDs above. OS filesystem/page caches are not
flushed and remain uncontrolled; record process/cache preparation rather than
claim physical storage coldness. Never call the second state warm encoder or warm
Gemma. No native-allocation overlap is permitted. Close/release after each request
before the next. Source/APK changes or an unexplained worker state invalidate the
sequence. Old V3 attempts cannot be merged into it.

The first timed-out, invalid, failed, rejected, interrupted, revoked, unsafe or
uncertain-cleanup case stops the sequence. Record subsequent requests as not run.
Keep every attempted request. An infrastructure repair needs a new retained attempt
and explicit review; do not retry quality/latency failures until lucky. No prompt,
deadline, backend or sample-size changes within this protocol.

## Deadline, Ownership And Delivery

Start one monotonic deadline at foreground verification submission **before any
queueing or evidence preparation**. Include queueing, reclaiming resident Gemma,
pack selection/construction, asset verification, counting, encoder loading/encoding,
worker death confirmation, Gemma loading, prefill, decoding, output validation and
final publication eligibility. Recheck before each admission and publication.
No reset, clock substitution or allowance for delayed observation. At elapsed
30000 ms the result is unusable even if native work finishes later.

Use the existing ModelManager opaque reservation, captured context epoch and active
request validity across all stages. Panel/install/repair/benchmarks/idle release
cannot steal it. Stop, host loss, context invalidation or deadline expiry signal
the active owner immediately and invalidate publication. Rotation and drawer/full
app transitions retain existing continuity. No tools, memory writes, automatic
indexing or production UI integration.

Exactly one terminal decision; independently track cleanup pending -> confirmed /
uncertain and delivery eligible/revoked. Retain ownership until confirmed cleanup.
Uncertain cleanup requires quarantine/restart; a timeout never proves cleanup
finished at the deadline. Native initialization may remain noncancellable. Record
time from signal to confirmed cleanup, not only the final close duration. A result
revoked during cleanup cannot later become a usable answer.

For this conservative experiment, the whole submission-to-confirmed-cleanup path
must also finish below 30000 ms for a feasibility pass. A timely candidate followed
by longer cleanup is retained as blocked, not published as a late success. Even
then the deadline does not grant permission to abandon native cleanup. Supervision
must remain active through cleanup, with its independent scoped emergency stop.

## Evidence And Decision

Per request retain correlated plan/pack/source/APK/profile hashes, run/worker IDs,
monotonic submission/decision/cleanup times, stage telemetry, Android lowMemory,
available RAM/threshold, app PSS, thermal state, worker death before Gemma load,
SDK token counts, terminal outcome, delivery eligibility, and exact output or its
failure classification. No personal data. Missing/stale telemetry blocks a pass.

Report separately:

- encoder preparation and confirmed release;
- asset verification, Gemma loading, prefill/decoding and validation;
- decision, cancellation signal-to-terminal and signal-to-confirmed-cleanup;
- whole operation including queueing, failures and cleanup;
- cold-all-unloaded versus cached-admission-only, with uncontrolled caches noted.

Pass requires all eight cases structurally valid, eligible, within both timing
checks, confirmed cleaned up/unloaded, healthy resources and closed supervision.
Structural validity **does not establish semantic correctness**. Publish per-case
observations and min/median/max; eight cases do not certify a p95 SLO. Failure
blocks the next campaign and calls for another reviewed decision, not an automatic
deadline change. No larger or unattended sustained workload is authorized.

If feasible, any new campaign must retain V3's separate relevance, sufficiency,
conflict, delivered-evidence and end-to-end metrics, including Recall@5 >=90%,
nDCG@10 >=0.80 and false-match rate <=5%, with the same uncertainty/error rules.
It needs its own frozen selection/performance contract and successful sealed
development selection before held-out planning. This draft cannot grant that
permission or claim the prior five-second performance gates were met.
