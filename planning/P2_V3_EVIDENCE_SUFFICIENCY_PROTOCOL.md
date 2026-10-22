# P2 V3: Evidence Sufficiency Preparation

## Scope And Status

Deliverable: protocol, rubric, synthetic datasets, exact evidence packs, hash
freeze and independent judgment review. No verifier, collector, native workload,
search integration or device permission changes belong to this preparation step.
Semantic search remains disabled. V1/V2 failures and unused V2 held-out material
remain unchanged. The machine-readable contract is
`fixtures/evidence-sufficiency-v3/protocol.json`; the interpretation rubric is
`P2_V3_JUDGMENT_RUBRIC.md`. Any contradiction blocks execution rather than allowing
the implementer to choose a more permissive rule.

## Frozen Investigation

Separate topical relevance from whether delivered evidence answers every requested
detail. Keep the pinned 100-dimensional USE head, preprocessing, normalization and
192-token gate. The only eligible approach is retrieval plus the existing pinned
Gemma E2B GPU runtime. Unfiltered retrieval alone is a diagnostic comparator, not a
sufficiency classifier. No cosine cutoff or new model is fitted in this protocol.

Each split has its own 60-passage searchable corpus and 60 English questions:
20 supported, 10 conflicting, 20 missing-detail, 10 unrelated. Requested details
are completely judged against all 60 passages. Each question also has three fixed
component candidates, exact rendered payload, delivered intervals and reviewed
delivered labels. These fixed packs isolate verifier behavior from ranking.
Questions, corpora, distractors, matrices, pack loss and test counts are frozen
before any vectors or verifier outputs. Synthetic structural templates are shared;
domain families, entity identities and facts are split-disjoint. No multilingual
or deployment-generalization claim follows from this narrow experiment.

New families: transmitter testing, snowmaking controls, robotic pallet handling;
planetarium projection, desalination, railway motors; microfilm processing,
volcanic monitoring, grain milling. They are distinct from the old campaigns.

## Exact Pack And Output

One USER message, no history/system message. Concatenate the instruction file
without its final LF, LF + `DATA` + LF, compact ECMAScript JSON, then LF + `END DATA`.
JSON keys and order: `q,d,p`; detail `i,e,a,c,u`; passage `p,r,b,t`.
The frozen template and payload bytes, not prose paraphrases, identify the prompt.
Detail fields come from the question only; annotations/labels never enter DATA.

Rank by descending cosine, passage-ID ascending for ties. Use at most three
candidates, a Unicode-scalar prefix of at most 160 UTF-8 bytes per passage, no
normalization or ellipsis. Drop trailing candidates until the full message fits;
never select around a missing fact or change the question. Question limit is 180
UTF-8 bytes; at most two details. Zero candidates after fitting is PACK_REJECTED.
The instruction template and input strings may not contain the existing budget's
reserved control sequences or malformed Unicode.

Existing Gemma budget v2: `128 + 96 + UTF8(payload) + 512 <= 2048`.
Thus payload cap is 1312 bytes, not 2048 characters or a second evidence budget.
Output remains 512 tokens. Sampling is pinned to temperature 0, topK 1, seed 0;
unsupported runtime settings block the future experiment, not silent substitution.

Output schema is frozen in protocol.json: `o` plus all ordered detail decisions,
with immutable passage/revision references and original UTF-16 half-open spans.
Only one strict JSON object; no answer prose, extra/duplicate keys or trailing data.
At most two citations per detail/four total, quote at most 96 UTF-8 bytes, JSON at
most 1536 UTF-8 bytes and 512 generated tokens. M/U details cite nothing. C requires
both incompatible records. Validation rejects unknown IDs, undelivered spans,
surrogate splits and quotation mismatch. Semantic support is separately judged.
No parser or generation code is supplied in this preparation slice.

## Numerical Criteria

Relevance is reported independently: macro Recall@5 of grade-3 passages at least
90%, graded nDCG@10 at least 0.80 using gain `2^grade - 1`. Recall excludes and
separately reports questions with no grade-3 passage; nDCG uses those with any
positive grade. Unrelated questions are reported separately, not assigned an
artificial perfect ranking score.

Fixed-pack sufficiency gates (all required):

| Metric | Point-estimate gate | Population |
| --- | --- | --- |
| Supported recovery | >=90% | Delivered S questions |
| Support precision | >=95% | All attempted S claims, zero if none |
| False support | <=5% | Delivered C/M/U questions |
| Conflict recovery | >=90% | Delivered C questions |
| Conflict precision | >=95% | All attempted C claims, zero if none |
| Correct missing abstention | >=95% | Delivered M questions |
| Correct unrelated abstention | >=95% | Delivered U questions |
| Valid completion | >=95% | All submitted questions |
| Invalid references accepted | 0 | All citations |

Recovery/abstention requires correct overall outcome, every detail and semantic
witnesses, not just a matching letter. Each precision denominator includes valid
predictions of that state plus every invalid/failed request as incorrect. False
support counts incorrect valid S predictions and all invalid/failed requests in
its fixed C/M/U population. A structurally invalid output cannot be accepted. Native
failure, timeout, malformed output, unavailable resource, rejected pack or
non-evaluator cancellation stays a failure in fixed denominators. None earns
abstention credit. Report these error counts alongside quality; a low false-support
rate cannot conceal failures. Human semantic assessment uses the frozen rubric;
structural span equality is necessary but insufficient.

Required delivered-evidence coverage is >=90% on full-corpus S/C questions: all
details must retain support, or both conflicting witnesses. Fixed-pack coverage is
a component diagnostic; actual ranked packs need their own measured coverage.

The unchanged end-to-end gates are macro Recall@5 >=90%, nDCG@10 >=0.80 and
false-match rate <=5%. Full-corpus S/C are answerable; C earns credit only as a
correct conflict report. Full M/U are unanswerable. Witness recall counts required
reviewed supporting passages at5; graded nDCG uses frozen relevance at10. Both are
zero for a wrong overall/detail decision, invalid output, timeout or incomplete
request. Relevance-only retrieval cannot earn sufficiency credit. A top-level S/C
claim or any invalid/failed request on full M/U is a false match. A correct
top-level M with a valid S-detail citation is not a false match. Report the frozen
30-question unanswerable denominator: 1/30 passes 5%; 2/30 fails. Never omit failed
queries, round a failure into a pass, or treat a conflict as an unqualified answer.

Freeze descriptive 95% intervals: 10,000 query-level bootstrap resamples within
each metric population, xorshift32 seed 20261022, paired indices between approaches,
nearest-rank quantiles .025/.975. Xorshift operations, uniform integer selection,
ID ordering and per-population seed reset are fixed in protocol.json. For every
proportion also report two-sided Wilson
with z=1.959963984540054, no continuity correction. Zero-denominator statistics are
unavailable except the explicit zero precision above; they cannot pass. Intervals
do not replace point-estimate gates. Report numerator/denominator, per-query
outcomes and complete error counts, not aggregates alone.

## Separation And Execution Gates

1. Independent review approves all corpus judgments and separation, bound to the
   complete freeze. Approval is not collection/implementation permission.
2. A later authorized implementation proves structural validation, budget/runtime
   feasibility and owner-scoped cancellation. Component runtime checks precede
   any large combined campaign. Four questions per cold-all-unloaded and
   encoder-fresh/Gemma-unloaded state are the frozen small feasibility set: the
   first S, two-detail S, M and C of calibration, in ID order within that set.
3. Calibration permits format validation/diagnostics only, no threshold, prompt or
   parameter tuning. Development relevance, sufficiency and delivered coverage
   must pass before combined development evaluation.
4. Select the eligible approach only when component, end-to-end and performance
   gates pass. Frozen ties: greater end-to-end recall, then nDCG, fewer false
   matches, lower whole-pipeline p95, approach ID ASCII ascending. There is only
   one eligible approach in V3; failure is failure, not permission to add variants.
5. Seal identity, parameters, protocol/rubric/template/data hashes, reviewer
   attestation, collectors, runner code and accepted collection identities before
   even planning held-out collection. No held-out judgments/metrics enter
   calibration/development tool output. Held-out is evaluated once for the locked
   approach; no reselection after exposure.

Infrastructure attempts remain immutable and run-correlated; reject missing,
duplicate, incompatible or partial batches. A repaired infrastructure attempt may
retry collection but cannot silently replace quality results or select a new
policy. Retain failures/timeouts in quality populations. Any later investigation
needs a new version, not reuse of exposed held-out data as independent evidence.

## Whole-Pipeline Safety And Timing

Start one monotonic five-second deadline at foreground submission. It includes
queueing, resource reclamation, packing/counting, encoder load/encoding, confirmed
encoder cleanup, Gemma load/prefill/inference and output validation. Do not reset
at Gemma admission or reuse the existing preparation-only deadline as proof.
Existing targets remain keyword p95 <=1s and complete hybrid p95 <=2s. Report cold
and warm encoder/verifier phases, cancellation, cleanup, full distributions and
failure/timeout counts. A timeout is not a successful five-second response.

Any feasibility request exceeding five seconds blocks the larger combined
campaign and requires an explicit architecture/performance decision. Never exclude
verification timing, change budgets, relax thermal/memory policy or invent a
text-only fallback to pass. A successful small run does not establish capacity.

Use the existing opaque owner through the whole pipeline. Release resident Gemma
before encoder allocation; confirm encoder cleanup before loading Gemma. No
unverified concurrent residency. Stop invalidates results and signals current
native work immediately. Cleanup is separately pending/confirmed/uncertain; keep
the reservation until confirmed and quarantine uncertainty. Recheck context/host
validity before publication. No tools, memory writes or production integration.

## Preparation Evidence

`freeze.json` binds preparation bytes and exact pack hashes. `preserved-v1-v2.json`
records earlier campaign bytes without modifying or re-exporting them. The separate
QA report/attestation supplies the judgment verdict; absent approval is blocking.
Host structural checks do not establish encoder quality, verifier behavior,
latency, cleanup or S24 compatibility. No device campaign belongs to this change.
