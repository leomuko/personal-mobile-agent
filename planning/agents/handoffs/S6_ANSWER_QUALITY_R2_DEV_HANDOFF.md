# S6 answer quality, round 2 dev handoff (planner rulings on QA round 1)

2026-11-23. From the planner to the dev agent.

Base task: [S6_ANSWER_QUALITY_DEV_HANDOFF.md](S6_ANSWER_QUALITY_DEV_HANDOFF.md).
Its boundaries, allowed paths, out-of-scope list and checks still apply.
QA report: `build/qa-s6-answer-quality-2026-11-23-r1/QA_REPORT.md` (verdict
PASS WITH FINDINGS, 0 P0/P1). Read it in full, including
`retrieval-probe.mjs` in the same folder.

Host work only. No device runs, real adb, commits, staging or stashes. The
fixture is still provisional (ruling R1), so revising it now needs no new
version.

## Standing rulings from round 1

- **R1.** The lock is provisional. It freezes only after QA passes and the user
  authorizes the first S24 run. After the freeze, any change needs a new
  version (`answer-quality-v2`).
- **R2.** S24 order: tuning×1, then tuning×3 for determinism, then heldout×1.
  Heldout×3 runs only if tuning×3 shows a difference between passes. A heldout×1
  report states "determinism not established on the held-out set". That is
  accepted.
- **R3.** Tuning is never scored toward acceptance.

## Rulings on QA findings

**S6Q-F1: revise (required).** The rubric and the base handoff ask for
realistic note length. The fixture must actually exercise retrieval.
- Lengthen the library so most sources split into several passages under the
  real `PassageRules`/`SourceTextRules`. Each project's coined name should
  appear in **clearly more than 4 passages**, so naming the project does not
  pack every note about it. Keep 12–20 sources. Stay well inside
  `SourceLimits`.
- Add plausible unrelated filler: routine log lines, meeting notes and
  side-tasks, all synthetic.
- Paraphrase items must not be answerable just by retrieving everything about
  the project.
- Re-run the QA retrieval probe (or your own equivalent built on the real
  scoring) and report how many answerable and contradiction items pack their
  gold passage. That count is an estimate, not a target. Do not tune items to
  make retrieval fail or pass. Report the number honestly.
- Correct `docs/P2_ANSWER_QUALITY_RESULTS.md` so it claims only what the fixture
  does.

**S6Q-F2: replace (required).** The rubric defines near-miss as "the same fact
exists about a different person or project".
- Each of the 4 near-miss questions names a real, clearly distinct entity from
  the library, not a spelling variant. That entity lacks the asked fact, while
  a different entity has it.
- Document the labelling rule. A reply that gives the other entity's value
  without flagging the mismatch is **Wrong**. A reply that says it is not in
  the sources is **Abstained**.

**S6Q-F3: revise.**
- Forbidden facts are plausible distractors, preferably values that exist in
  the library for a neighbouring entity or field. Do not reuse one placeholder
  across many items.
- `precheck` reports which contradiction values appear in each answer.

**S6Q-F4: revise.** Phrase all 6 bait items so they invite a real
general-knowledge answer about something in the notes. Examples: the boiling
point of the liquid used, or the capital of a real country mentioned in a
note. Use the likely general-knowledge value as a forbidden fact. Keep the
notes free of that fact.

**S6Q-F5: fix (test-only).**
- An `ObservedRuntime` invariant breach (message count, backend, profile) must
  fail the instrumentation run with a distinct reason. It must not become an
  `EMPTY_OUTPUT` Error row.
- Check that each consulted passage's text is present in the request actually
  sent to the engine, and fail on a mismatch.
- Record the per-item retrieval summary and whether decoding hit the
  512-token cap.
- No production change. If one turns out to be needed, stop and report.

**S6Q-F6: fix.**
- `report` rejects answer-type labels (Supported, Partial, Wrong, Hedged guess)
  on rows with no answer.
- Typed no-answer rows can take only Abstained, or Error when the device
  recorded an error. Document this in the labelling guide.

**S6Q-F7: add tests.**
- A tuning report carries no held-out rates or targets.
- A fake-adb quality deadline path.
- A pin of the JS passage splitter to the Kotlin rules: shared golden vectors
  checked on both sides. Use an existing host test location in allowed paths.
  If the Kotlin side needs a path outside them, report it instead.
- A fake capture with non-empty `consulted`.

**S6Q-F8: fix docs.**
- Update the run plan in `docs/P2_ANSWER_QUALITY_RESULTS.md` to R2.
- Record R1's post-freeze versioning rule in the docs, not in the lock.

**S6Q-F9: extend the binding.**
- Hash `scripts/answer-quality.mjs`, `AnswerQualityTest.kt`,
  `AnswerQualityFixture.kt` and the fixture lock into
  `quality-source-binding.json`, so untracked S6 files are still bound.
- Whether S6 is committed before the device run is the user's call. The
  planner will ask.

**S6Q-F10: reduce.** At least 5 of the 10 two-passage items must combine
facts that no single-passage item asks for. The rest may stay paired. State
which are which in the fixture summary.

## Report back

Use the dev-brief handback format, plus:
- for each finding, what changed and the test that pins it;
- the new fixture summary: counts, library bytes and passage counts per
  source, passages per project name, paraphrase overlap, the retrieval-probe
  estimate, and the new library and lock hashes;
- updated deadlines, if the formula's inputs changed;
- check results with counts: the same commands as in the base handoff.
