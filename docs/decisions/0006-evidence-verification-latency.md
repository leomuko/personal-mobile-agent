# Evidence Verification Is Not Bounded Search

Date: 2026-10-22.

Status: the V3 block is retained. The V4 alternative below is **proposed for
review**, not an approved latency contract, runtime change or native authorization.

## Evidence And Limits

The [V3 feasibility report](../P2_V3_FEASIBILITY_RESULTS.md) records one cold GPU
request: timeout observed at 5067 ms, completed cleanup at 14459 ms, and no verifier
inference reached. The encoder had been released before Gemma loading. Sources
for that attempt are archived; subsequent publication hardening is host-tested
only. These observations do not estimate verifier inference time, p95 latency,
accuracy, device incompatibility or the benefit of a different residency strategy.

V1/V2 failed quality gates remain unchanged. Neither topical similarity nor a
structurally valid quotation establishes evidence sufficiency. V2 held-out remains
unused; V3's remaining seven feasibility requests and larger campaign stay blocked.

## Decision Retained Now

- Do not continue V3 or relabel it successful. Its entire pipeline had a five-second
  limit, including Gemma loading/inference and output validation.
- Keep search's five-second hard deadline and proposed p95 targets of <=1 second
  keyword and <=2 seconds complete hybrid. None is newly certified by this work.
- Do not exclude verification from timing while continuing to call the operation
  complete verified search. No production semantic search is enabled.
- Keep one ModelManager owner, serial native allocation, confirmed cleanup,
  context validity and quarantine. No runtime implementation changes accompany
  this decision record.

## Recommended Alternative For Review

Investigate a separate, explicitly requested **evidence-verification operation**,
not a faster search result. Search returns passages and coverage only; it must not
claim those passages answer a question. Verification would then be a separately
visible, cancellable operation with its own end-to-end deadline and terminal
outcome. This describes a possible product boundary, not implemented UI behavior.

[V4 draft](../../planning/P2_V4_SERIAL_VERIFICATION_FEASIBILITY.md) proposes a
**30-second whole-verification deadline** solely for a small feasibility experiment.
Thirty seconds is an explicit experimental ceiling, not a measured SLO, approved
user wait time, extra context allowance or evidence that inference will fit. The
single V3 run cannot justify it statistically. User review must accept or reject
this changed operation/ceiling before implementation or execution. Any different
choice requires a revised version before collection, never a mid-run adjustment.

The experiment would keep the existing pinned encoder and Gemma GPU path, exact
packs, 192-token encoder admission, 2048 context / 512 reserved output, validator,
and normal memory policy. It would not warm or co-reside models to conceal cold
cost. It would not replace the encoder or change quality gates.

## Alternatives Not Adopted

| Alternative | Reason |
| --- | --- |
| Retry V3 until a pass | Selection bias; ignores a blocking frozen result. |
| Start timing after loading or use only warm results | Hides work the user must wait for. |
| Keep Gemma and encoder resident together | Requires separate allocation, pressure, cancellation and sustained verification. Not authorized. |
| Automatically prewarm Gemma | Background residency/battery/host rules are unqualified; not an implicit fix. |
| Smaller verifier, alternate backend or cloud | New artifact/quality/resource or privacy decision; not justified by this single timing result. |
| Keep five seconds and stop this investigation | Valid fallback if the proposed separate operation is not accepted; semantic enablement stays blocked. |

## Future Gates And Consequences

1. Review this decision and the V4 draft. Approval must identify the changed
   operation, deadline and execution scope, not merely acknowledge a code review.
2. If approved, implement and independently review its runner. Freeze code, APKs,
   exact pack/input identities, resource profile and classifier before collection.
3. Run only its small supervised feasibility set after separate device permission.
   An overrun or uncertain cleanup blocks further work; no automatic retries.
4. A feasibility pass could support a **new quality/performance campaign protocol**.
   It cannot waive V3's failed performance gate or inherit permission to evaluate
   development/held-out sets. Review latency usability and measured cold/warm
   timings before authorizing that campaign.

All relevance, sufficiency, conflict and end-to-end quality criteria remain
unchanged. Memory CRUD, PDF ingestion, indexing/capacity and final P2 acceptance
are separate unfinished work. Release packaging and deferred audio behavior are
unchanged. No model workload or app behavior is changed by this document.
