# P2 Output-Adherence Investigation

Date: 2026-10-22. Scope: separate host diagnosis and investigation planning.
**No verifier implementation, prompt change, native execution or semantic enablement.**
This does not reopen V4's seven unrun requests or authorize another experiment.
The wrapper fix is tracked separately in
[wrapper hardening](../docs/P2_V4_WRAPPER_HARDENING.md).

## Preserved Baseline

Run `5414a589-fb27-450d-b0a9-539031011329`, case `v3k01qc`, remains failed.
Raw evidence: `benchmarks/results/p2-v4-feasibility-gate-4guyjf/encoder.json`.

| Binding | SHA-256 |
| --- | --- |
| Raw encoder.json bytes | b199e9850da6f9a6a190ec79e4007203cd24f4e9e6f0228c2142d89930ae1365 |
| diagnosticOutput UTF-8 bytes | f0736f212b23a8a0c5f41833b9012d95f3780a90d56a59226ded9c9740775187 |
| Unchanged EvidenceOutputValidator.kt | 10be77c8586f0ee9931c9c33b965acd3608fe379010afde75e27b1dfd5526000 |
| Execution freeze | e30f6cb23860ee47d8c42ac6ae76a3eccdc1d199f3833c3c13d351f06a2dec91 |

The exact diagnostic text is 388 UTF-8 bytes. Preserve it and the original pack,
instructions, approvals, APKs, timing, cleanup and failed classification. Existing
V1-V4 artifacts are not tuning outputs to overwrite.

## Observed Defects

1. Markdown fences violate the single-raw-JSON-object rule. The device recorded
   `INVALID_OUTPUT / JSON` and revoked delivery at this first rejection.
2. Three citation entries violate the maximum of two per detail.
3. Citation objects use `t`; the output contract requires `p,r,b,e,q`, including
   an integer half-open UTF-16 end and an exact quotation. End/quotation fields
   are absent. Input passages do use `t`; input/output schema confusion is a
   hypothesis, not a demonstrated cause.

The latter two observations come from reading the unchanged text; the device
did not progress past JSON rejection. Fence removal cannot cure them. No
repaired output is a success, and no semantic/injection/conflict score is inferred.
The existing instructions already request JSON-only output and show `b,e,q`;
the investigation must not assume these requirements were simply missing.

## Separate Questions

- Can the pinned runtime/model obey the complete output contract, not just emit
  parseable JSON? Separate syntax, schema, allowed references, span bounds,
  quotation equality and aggregate-state checks.
- Are exact coordinates/quotations and citation limits feasible together within
  the unchanged output budget? Structural correctness still does not prove support.
- Does an input/output-schema distinction explain the observed fields? Test any
  proposed explanation under a new frozen protocol, not by rewriting this run.
- Is constrained decoding supported by the pinned runtime, and does it express
  this contract? This is unverified; do not assume availability or integration.
- After structural feasibility, can semantic sufficiency and conflict handling
  pass their separate labeled gates, including untrusted source instructions?

## Contract For Any Future Experiment

First propose one separately versioned protocol for review. Freeze the hypothesis,
candidate instructions/decoding configuration, synthetic calibration-only cases,
strict expected structures, case order, stop rules and acceptance before inference.
Do not repeatedly tune against development or held-out judgments. Keep V2 held-out
data unused and V3/V4 failures intact. Use no tools, memory writes or personal data.

Keep the existing strict validator byte-identical. No fence stripping, unknown-key
aliases, quotation repair, fabricated span ends, relaxed citation limits or retries
that hide failures. Use host validator regressions first, with malformed cases
counted as failures rather than successful abstentions. Report structural adherence
separately from semantic correctness; neither follows from eight timing samples.

Preserve 2048 context / 512 reserved output, 192-token encoder admission, serial
ownership, immediate cancellation, confirmed cleanup/quarantine and the declared
resource profile. Search retains five seconds. Any future explicit-verification
latency contract requires its own decision; V4's limited 30-second authorization
does not authorize changed prompts, additional requests or a production target.

After implementation and host QA, obtain independent review and a new execution
freeze binding code, APKs, packs, profile and checks. Obtain separate S24 execution
authorization before device access. Stop at the first blocking result and retain
all failures/timeouts and cleanup measurements. No larger campaign or semantic
enablement follows automatically from structural feasibility.

## Current Status

Host inspection only. No outputs transformed, new prompts run, validator changes,
device calls or new freezes/approvals. Native feasibility, the remaining V4 cases,
semantic search and consolidated P2 acceptance remain blocked.
