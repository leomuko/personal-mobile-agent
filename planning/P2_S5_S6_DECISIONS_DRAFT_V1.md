# P2 decisions draft: D5 details (S5) and the S6 answer-quality rubric

2026-11-19. **Approved by the user on 2026-11-19: every recommended answer below
(D5.1–D5.7, S24 screenshots for S5, and the S6 rubric as drafted).** Nothing here is
implemented yet. Slice context: [P2_SLICE_PLAN_V1.md](P2_SLICE_PLAN_V1.md), S5 and S6.
Each open point carries a **recommended** answer. Approve as written or
change individual items.

---

## Part 1: D5, a source-scoped request

**Decided by the user (2026-11-19):** a **toggle chip next to the input box**.
When the chip is on, the next send is a `SOURCES` request, as built in S4.
When it is off, the request is `ORDINARY`, with automatic retrieval and plain
chat if nothing matches.

Details to confirm:

| # | Question | Recommended | Alternative |
| --- | --- | --- | --- |
| D5.1 | Where the chip sits | In the composer row, between `+` and the text field, as a compact chip with a sources icon. It stays in this place in the drawer and the full app. | A row above the composer. This costs vertical space in the drawer. |
| D5.2 | Label | "My sources" with a check mark when on. The accessibility name is "Answer from my sources only, on/off". | "Sources only" |
| D5.3 | How long it stays on | **Sticky.** It stays on until the user turns it off. It is saved with the draft, so the drawer, the full app and a restart all show the same state, like the unsent text today. | One-shot: it resets after each send. This is safer against forgetting, but costs a tap per question. |
| D5.4 | Empty library | The chip is shown but disabled, with the hint "Add a source to use this". It does not silently send ordinary chat. | Always enabled. This returns the "not indexed" or "no match" result. |
| D5.5 | Retry after a temporary no-answer (S4-F3) | Retry is offered for `NOT_INDEXED` and `RETRIEVAL_INCOMPLETE`, which can change. It is not offered for `NO_MATCH` or `PASSAGES_UNUSABLE`. Retry keeps the stored scope, as today. | Retry for every reason |
| D5.6 | How a no-answer reads | One short assistant line rendered from resource strings for each reason, for example "Your sources don't cover this." It carries the mascot styling with no passage row. Nothing is shown as "verified". | |
| D5.7 | Indicator on a sent message | A small "From my sources" label under the user's message, so the history shows which turns were scoped. | No marker |

S5 wording rule, unchanged: the row under an answer says **"Passages consulted"**.
It never says "verified", "supported by" or "cited".

**Evidence conflict to resolve.** S5's "Done when" asks for **emulator**
screenshots. The current instruction is S24-only. Recommended: replace this
with S24 screenshots of the `secureQa` build plus Compose tests. Each S24 run
still needs your go-ahead.

---

## Part 2: the S6 answer-quality rubric

### What is measured

The plan's memory/RAG gate (`IMPLEMENTATION_PLAN.md`, quality table) on
keyword-grounded answers over text sources:
- **50 answerable** questions, with **≥ 90% supported answers**;
- **20 unanswerable** questions, with **≥ 95% abstention**;
- **every returned reference resolves.**

S6 is the first honest measurement against these targets. If they are missed,
it reports that. It is not P2 acceptance.

### Fixture (synthetic, frozen before any run)

| Set | Count | What it holds |
| --- | --- | --- |
| Answerable, single passage | 30 | The answer is stated in one passage |
| Answerable, two passages | 10 | The answer needs facts from two passages of one or two sources |
| Answerable, paraphrased question | 10 | The question shares few keywords with the passage. This tests keyword retrieval honestly. |
| Unanswerable, off-topic | 8 | Nothing in the library is related |
| Unanswerable, relevant passage but missing detail | 8 | The topic is present, but the asked detail (a date, name or number) is not |
| Unanswerable, near-miss entity | 4 | The same fact exists about a different person or project |
| Diagnostic: contradictory passages | 6 | Two sources disagree. Reported separately and not counted in the 50/20. |
| Diagnostic: unsupported-answer bait | 6 | Questions that invite general-knowledge answers, such as "what is the capital…" phrased about the user's notes. Reported separately. |

Every item records:
- the question;
- the expected answer as a list of **required facts**;
- **forbidden facts**, meaning plausible wrong values;
- the passages that contain the answer.

A separate small **tuning set** of about 10 items may be used to adjust
instruction wording. The 70 held-out items are never used for tuning. After the
first held-out run, the prompt is frozen for that run's report.

### Run conditions

- S24 Ultra, `secureQa`, profile 4096/512, `grounded-prompt-v1` or its
  frozen successor, and the ordinary chat decoding settings, recorded in the
  report.
- All 70 + 12 items run in **`SOURCES` scope**, because abstention is that
  scope's contract.
- A 20-item `ORDINARY` subset is reported for information only.
- Each item runs in a fresh conversation with the fixture library imported.
- One pass. If decoding is not deterministic, run three passes and report each
  pass plus the worst case.
- Each run needs your go-ahead, as usual.

### Labels (one per answer)

| Label | Definition |
| --- | --- |
| **Supported** | Every required fact is present. Every factual claim that answers the question is stated in, or a direct restatement of, a passage that was packed. No forbidden fact appears. Wording, order and harmless filler do not matter. |
| **Partial** | The core is right, but a required fact is missing or an unsupported extra claim is added. **Counts as not supported.** |
| **Unsupported** | The answer states something the packed passages do not say, including correct general knowledge. |
| **Wrong** | A forbidden fact appears, or the answer contradicts the passages. |
| **Abstained** | Either the typed no-answer result with no model call, or a model reply that says the sources do not contain the answer, without guessing. |
| **Hedged guess** | "The sources don't say, but it's probably X." **Counts as not abstained** on unanswerable items and as unsupported on answerable ones. |
| **Error** | Timeout, crash or empty output. Counts as a failure. It is not excluded. |

### Scoring

- **Supported rate** = Supported / 50. An Abstained answer on an answerable item
  counts against this rate and is listed separately as a retrieval or packing
  miss.
- **Abstention rate** = Abstained / 20. The three unanswerable kinds are also
  reported separately.
- **Reference check** (deterministic): every consulted reference resolves to a
  live source, revision and span, and 100% is required. The report also
  records whether the answer-bearing passage was packed. This separates
  retrieval misses from model misses.
- **Diagnostics:**
  - For contradictions, the "noted conflict" rate. Picking one value silently
    counts as not noted.
  - For unsupported-answer bait, the abstained or unsupported counts.

### Who judges

1. **Deterministic pre-check on the host.** It checks the typed no-answer
   result, finds required and forbidden facts by normalized string match
   (numbers, dates, names), and runs the reference resolution check.
2. **A fresh QA agent** labels every answer using this rubric. It reads only
   the synthetic transcripts and gives a one-line reason for each label. Using
   the on-device model as its own judge is not allowed.
3. **You review** every failure, every Partial, and 10 random Supported items.
   Your labels win. Disagreements are listed in the report.

### Reporting

The report contains:
- the counts and rates against the targets;
- a table for each item;
- the retrieval-vs-model split;
- the diagnostics;
- the conditions.

A missed target is stated plainly, with the leading failure kinds. Nothing is
re-run to improve a number without being reported as a second run.

---

## Decisions requested

1. D5.1–D5.7. Approve the recommended column, or change items.
2. S5 evidence: S24 screenshots instead of emulator screenshots.
3. The S6 fixture mix, run conditions, labels, scoring and judging. In
   particular:
   - "Partial counts as not supported";
   - "hedged guess counts as not abstained";
   - `SOURCES` scope for the scored set.
