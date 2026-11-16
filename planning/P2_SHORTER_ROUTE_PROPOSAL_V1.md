# P2 shorter route — proposal v1

2026-11-10; **revised 2026-11-11** after an independent review
(`build/qa-shorter-route-2026-11-11-1/review.json`, findings ROUTE-F1 to F5,
all accepted) and the user's acceptance of decisions D1–D4. The route is
accepted in direction; **no implementation has started**. It changes no source,
freeze, fixture, threshold, dependency or Git state. P2 remains incomplete;
semantic search stays disabled.

## Revision 2026-11-11: corrected diagnosis

The first version said the encoder "misses the relevant passage in more than
half of the answerable English queries". **That was wrong.** The published
0.45 was measured *after* the calibrated cosine cutoff removed hits. Recomputed
on the host from the retained vectors of run `p2-semantic-gate-8SCUVh`
(read-only; exploratory, because these held-out rows are already exposed):

| English held-out, 20 answerable / 20 unanswerable | Recall@5 | nDCG@10 | Relevant passage ranked first | False matches |
| --- | ---: | ---: | ---: | ---: |
| With the calibrated cutoff 0.8423 (published) | 0.45 | 0.45 | 9/20 | 0/20 |
| No cutoff (ranking only) | 1.00 | 0.963 | 18/20 (other 2 ranked second) | 20/20 |

Top-score ranges overlap: answerable 0.762–0.913, unanswerable 0.719–0.802.
Swahili and Japanese rank the relevant passage near the bottom either way.

What this does and does not show:

- On this 34-passage English corpus the pinned encoder **ranks well**. The
  gate failed on **deciding answerability from a score cutoff** fitted to 12
  calibration rows, not on ranking.
- It says nothing about ranking at library scale, about other languages, or
  about any cutoff chosen after seeing these rows.
- The hard problem is therefore **abstention and sufficiency**, which is also
  what the earlier calibration and verifier campaigns ran into. Replacing the
  encoder is not established as necessary.

Consequences for the tracks below: Track B becomes a comparison with USE-QA
kept as the control, and no longer blocks product work. Track A needs an
explicit source-scope and abstention contract instead of relying on a
retrieval cutoff. The original text of the tables and tracks is kept below,
with corrections marked.

## Accepted decisions (user, 2026-11-11)

| # | Decision |
| --- | --- |
| D1 | Host bake-off of keyword-only, small permissively licensed encoders and their fusion, with USE-QA as control. One-time public model downloads allowed after licence and mobile-artifact confirmation. |
| D2 | Measure 4096 and 8192 context on the S24; choose from measurement. Nothing larger than 2048 becomes the ordinary default before that. |
| D3 | Retrieval runs automatically on ordinary chat turns when Sources is non-empty; with no hit it is ordinary chat with no claim about sources. **Refined after review:** a separate, explicit, persisted *source-scoped* request state carries the "your sources do not cover this" behaviour. How the user enters that state is open (D5). |
| D4 | QA-only paths (V3, V4, constrained decoding) stay untouched and pinned to 2048/512. |
| D5 (open) | The UI affordance for a source-scoped request. Not in the approved screenshots; needs the user's choice before S5. |

## Scope statement

This route delivers an **interim milestone**: grounded answers over text
Sources with keyword passage retrieval and consulted-passage provenance. It is
not P2 completion. Explicit Memory, text-PDF ingestion, semantic recall and the
consolidated P2 acceptance campaign (including the 50 answerable / 20
unanswerable quality targets) remain owed and unchanged. Keyword-only delivery
is a degraded mode, not a substitute for semantic recall.

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

1. ~~A verifier cannot recover recall.~~ **Withdrawn; see the corrected
   diagnosis above.** Ranking was not the failure; the score cutoff was.
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
- **Revised 2026-11-11.** Two request states, persisted with the turn:
  - *Ordinary chat:* retrieval augments the prompt when there are hits; with
    none it is plain chat and makes no statement about sources.
  - *Source-scoped:* with no hit, the app replies that the sources do not
    cover it and does not load Gemma. With hits, the model is instructed to
    answer only from the passages and to say when the requested detail is
    absent.
- A retrieved, relevant passage can still lack the requested detail. That
  case, contradictions between passages and unsupported answers are measured
  in the answer-quality slice. A score cutoff alone is not treated as proof of
  answerability.
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

### Track B — compare retrieval configurations, host first

**Revised 2026-11-11:** this is a comparison, not a replacement, and it does
not block Track A. USE-QA stays in as the control, scored from its retained
vectors. Every configuration reports three things separately: ranking with no
cutoff, ranking after its own calibrated cutoff, and the false-match rate
against the ≤5% gate. Keyword (BM25), cosine and rank fusion produce different
score scales, so each gets its own stated ranking and calibration rule. All
results on the v1 rows are exploratory.

The original steps follow.

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

**Superseded 2026-11-11** by the order in
[P2_SLICE_PLAN_V1.md](P2_SLICE_PLAN_V1.md): passage retrieval first, then
context profiles, grounded prompt and UI; the retrieval comparison runs
separately. The original table is kept for the record.

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
