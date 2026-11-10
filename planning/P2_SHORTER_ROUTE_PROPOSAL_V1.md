# P2 shorter route — proposal v1

2026-11-10. **Proposal for user review. Nothing here is implemented, approved or
authorized.** It changes no source, freeze, fixture, threshold, dependency or Git
state. P2 remains incomplete; semantic search stays disabled.

## Why a different route

The current chain is solving a problem that is three steps removed from the
original blocker, and none of those steps repairs it.

| Date | Gate | Result | What it actually blocks |
| --- | --- | --- | --- |
| 10-21 | Encoder retrieval quality (USE-QA, 100-dim) | English Recall@5 **0.45** / nDCG@10 **0.45**; required 0.90 / 0.80 | Finding the right passage at all |
| 10-22 | V3 / V4 Gemma evidence verifier | Timeout at 5 s; invalid output at 19.7 s | Model-written citation JSON |
| 10-24 | Output adherence | Wrong citation keys at 17.5 s | Same |
| 10-26 | Constrained decoding | Two 30 s timeouts, incomplete JSON | Same |
| 10-28 → now | Custom LiteRT-LM runtime, Bazel, Dawn, Abseil, Clang, linker | Blocked on `ml_drift`, then on `--ld-path` under sandbox | Building a runtime that could host a grammar fix |

Two observations follow from the retained evidence:

1. **A verifier cannot recover recall.** The encoder misses the relevant passage
   in more than half of the answerable English queries. No amount of verified,
   schema-valid citation output helps when the passage was never retrieved. The
   V4 draft explicitly kept the same encoder.
2. **The product plan does not require model-written citations.** Section 6 of
   `IMPLEMENTATION_PLAN.md` says citations "must be assembled from retrieved
   source IDs and spans". The app already knows which passages it put in the
   prompt. The `p,r,b,e,q` output contract is what created the need for
   structured output, constrained decoding and, eventually, a custom runtime.

## Proposed route

Three independent tracks. A and B are the product path. C parks the current
chain without discarding it.

### Track A — grounded answers with app-assembled citations

Use the published LiteRT-LM 0.17.1 runtime unchanged, with plain-text output.

- Retrieval returns ranked passages with their source ID, revision and span.
  Keyword (existing FTS) is available today; Track B adds semantic candidates.
- The app places the top passages in the prompt and records exactly which ones.
  The reply is plain streamed text, like ordinary chat.
- The UI shows those recorded passages as **"passages consulted"**, each opening
  the exact revision and span. This is a provenance statement, not a claim that
  the model's sentence is supported by that span. Wording must stay honest.
- Abstention is decided by the app before the model runs: when no passage
  clears the retrieval cutoff, the assistant says the sources do not cover it
  and does not load Gemma for a grounded answer.
- The existing context fence, delete invalidation, single `ModelManager` owner,
  cancellation and cleanup rules are reused as they are.

What this removes: structured output, `EvidenceOutputValidator` in the answer
path, constrained decoding, the Unicode grammar patch, the isolated runtime and
the whole SDK source-build chain.

What it does not give: per-sentence verified evidence. That becomes a later,
optional feature rather than a prerequisite for semantic recall.

**Prerequisite the user must decide — prompt budget.** `GemmaChatBudget` admits
about 1,312 ASCII bytes for the current message plus all history, because it
assumes one token per byte inside a 2048/512 configuration. Retrieved passages
do not fit in that. Two options, both needing evidence before use:

- A1. Replace the byte bound with an exact on-device token count, if the pinned
  SDK exposes one. *Not verified; check the 0.17.1 API first.*
- A2. Qualify a larger configured context (the artifact declares 32,000) with
  measured S24 load time, memory and first-token latency. The accepted P2
  contract currently forbids enlarging 2048 to pass a gate, so this needs an
  explicit new decision, not a quiet change.

**User decision, 2026-11-10:** enlarging the 2048 configured context is
permitted for prompt budgeting (option A2). This supersedes the earlier P2
contract rule for this route only. The new value is not yet chosen and still
needs measured S24 load time, memory and first-token latency before it is
relied on. Implementation has not started.

### Track B — replace the encoder candidate, host first

The qualification harness, fixture format, metrics and thresholds already exist
and worked: they correctly rejected a weak candidate.

1. **Host-only bake-off** (no device, no app change). Score on the existing
   passages and queries: (a) keyword/BM25 alone, (b) two or three current small
   embedding models, (c) keyword + embedding fused. Candidate names are for the
   user to pick; any model must have a mobile-runnable artifact, a compatible
   licence and a known tokenizer. *Availability of each artifact for the pinned
   runtimes is unverified.*
2. Gate the **hybrid** result, since the plan specifies combined ranking. The
   10-21 gate measured the encoder alone.
3. Because the 20/20 English held-out rows have now been observed once, issue
   a new versioned fixture (`semantic-retrieval-v2`) with fresh held-out
   queries before claiming a pass. The v1 fixture, lock and failed result stay
   byte-identical.
4. Only the winning candidate goes to the S24, through the existing
   encoder-only path and the existing handoff tests.

If no candidate plus keyword fusion passes, P2 ships keyword-only retrieval
under Track A and says so. That is still a usable Sources feature.

### Track C — park the verifier and runtime-build chain

- Stop at the current reviewed checkpoint. Do not authorize the `access-v2-1`
  native run: its answer (why Apple Clang rejects `--ld-path` under the inner
  sandbox) is not on the product path under this route.
- Preserve every failed result, freeze, lock and raw capture unchanged. With
  the user's authorization, commit the 35 untracked files from 11-07 to 11-10
  as a labelled "parked" checkpoint so the work is not lost.
- Reopen only on a concrete trigger: P4 typed actions will need reliable
  structured output. At that point, first check whether a newer published
  LiteRT-LM release already contains the grammar fix, before resuming a source
  build. *Not checked as part of this proposal.*

## Suggested order

| Step | Scope | Needs |
| --- | --- | --- |
| 1 | Decide A1 vs A2 and whether to park Track C | User decision |
| 2 | Host bake-off (B1–B2) | Host only; choice of candidates |
| 3 | Prompt-budget evidence (A1 API check, or A2 S24 measurement) | A2 needs device authorization |
| 4 | Retrieval-into-prompt and "passages consulted" UI, keyword first | Normal feature work, baseline Gradle checks, emulator tests |
| 5 | v2 fixture, then S24 qualification of the chosen encoder | Device authorization |
| 6 | Indexing, hybrid ranking, then the consolidated P2 acceptance campaign | As in the accepted P2 contract |

Steps 2 and 3 are independent and can overlap. Step 4 does not wait for step 5.

## Risks and open questions

- **Answer quality is unmeasured.** Plain-text grounded answers from Gemma 4
  E2B have never been evaluated here. The plan's memory/RAG gate (≥90%
  supported answers, ≥95% abstention) still applies and may fail.
- **Abstention moves to the retrieval cutoff.** The earlier V1/V2 calibration
  policy campaigns failed their gates; a new cutoff policy needs its own
  measurement and may need the model to decline as a second line of defence.
- **Latency.** Cold encoder-then-Gemma requests took roughly 15–17 s to first
  text on the S24 in the retained runs. Removing the verifier does not shorten
  that. Residency or prewarming remain separate, unapproved decisions.
- **"Passages consulted" is weaker than "verified evidence".** If the product
  must claim verification, this route does not deliver it.
- **Process weight.** The freeze / independent-review / single-attempt ceremony
  fits device and native experiments. Applying it to steps 2 and 4 would
  recreate the current pace; the user should say which steps need it.

## What this proposal does not do

No source or test change, no dependency or version change, no threshold or
context change, no fixture rewrite, no device or native execution, no commit.
`DEFERRED_ISSUES.md` items stay deferred. Each step above needs its own
authorization before work starts.
