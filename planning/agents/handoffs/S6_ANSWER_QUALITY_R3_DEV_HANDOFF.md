# S6 answer quality, round 3 dev handoff (planner rulings on QA round 2)

2026-11-23. From the planner to the dev agent. This is the last dev round for
S6 (at most 3 rounds).

Base task: [S6_ANSWER_QUALITY_DEV_HANDOFF.md](S6_ANSWER_QUALITY_DEV_HANDOFF.md).
Rulings so far: [S6_ANSWER_QUALITY_R2_DEV_HANDOFF.md](S6_ANSWER_QUALITY_R2_DEV_HANDOFF.md).
R1–R3 and every round-2 ruling still apply.

QA report: `build/qa-s6-answer-quality-2026-11-23-r2/QA_REPORT.md`, verdict
PASS WITH FINDINGS, all P3. Read it, including the probes in that folder.

Host work only. No device runs, real adb, commits, staging or stashes. Keep
this round small: touch only what each ruling below needs. Do not rework
anything that QA marked fixed.

## Rulings

**S6Q2-F1: fix the fixture.**
- In `diagnostic-contradiction-03` and `-05`, remove the qualifiers ("for the
  bread trials", "for the winter rug pattern") or reword them. The two
  passages must then plainly state conflicting values for the same thing.
- Add a labelling rule to the guide: if the sources qualify the values
  differently and the answer reconciles them correctly, label it Noted
  conflict.
- The validator, or a test, must pin that each contradiction pair names the
  same entity and field without a distinguishing qualifier. Pick the cheapest
  check that would have caught these two.

**S6Q2-F2: fix the fixture.**
- Reword `answer-paraphrase-04` and `-09` so each has exactly one reasonable
  reading that matches its gold span. Keep keyword overlap with the gold
  passage at 2 or less, and record the new values.
- Do not change the other 8 paraphrases.

**S6Q2-F3: docs plus a flag.**
- Record the raw decode count, as today.
- Rename the derived field, or document it, so that values ≥ 511 are flagged
  as "possibly at cap".
- Add this to the docs: "cap detection is heuristic until confirmed on S24".

**S6Q2-F4: guard it.**
- `report` must name `deadlineSource` in its output and refuse to score a run
  whose deadline came from `ANSWER_QUALITY_DEADLINE_SECONDS`.
- Add one test for each.

**S6Q2-F5: add tests.**
- One fake-adb test: a post-build binding mismatch fails with
  `quality-source-binding`.
- One fake-adb test: prompt or sampler source drift is detected.
- The pass summary lists counts of every typed no-answer result, not only
  `NO_MATCH`.
- "Ostravel": leave it.

**P2 (Kotlin PassageRulesTest): not now.** It is deferred until any change to
`PassageRules` or to a v2 fixture. Add one line saying so to the docs'
known-limits section.

**Tuning-set coverage (QA observation): docs only.** State plainly that the
tuning set has no paraphrase, near-miss, bait or contradiction items, so
tuning results say nothing about those behaviours.

## Report back

Use the dev-brief handback format, plus:
- for each ruling, what changed and the test that pins it;
- the new `items.json`, lock and library hashes;
- the new overlap values for paraphrase-04 and -09;
- the retrieval-probe estimate rerun. Report it whether or not it changed;
- check results with counts, using the same commands as the base handoff.
