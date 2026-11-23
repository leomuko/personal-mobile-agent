# S6 answer quality, final small fix before freeze

2026-11-23. From the planner to the dev agent.

**Authorization:** on 2026-11-23, after QA round 3 (PASS WITH FINDINGS,
all P3), the user chose "Small fix, then freeze". This adds one round beyond
the three planned. The user also chose to run the S24 uncommitted, so there is
no commit.

Earlier handoffs (base, R2, R3) still apply. QA report:
`build/qa-s6-answer-quality-2026-11-23-r3/QA_REPORT.md`.

Host work only. No device runs, real adb, or Git writes. One Gradle invocation
at a time. Change only what is listed below.

## Fixes

1. **S6Q3-F1 (fixture).**
   - In `library/velquorin-handover.txt`, delete the sentence after "The
     Velquorin gate tally is 8219." that dates the copy ("I copied that … on
     Monday evening, after the last van left.").
   - Leave every other library line as it is.
   - Add a labelling line to the guide: a date or provenance remark is not a
     qualifier, so an answer that reconciles values by time without noting the
     conflict is not Noted conflict.
2. **S6Q3-F2 (validator).**
   - Each contradiction quote must be a whole sentence of its passage, under
     the same sentence rule the docs already use or an equivalent explicit
     rule.
   - Add a test that rejects a shortened quote with a qualifier outside the
     span.
   - Document that a qualifier in the next sentence is left to human review.
3. **S6Q3-F3 (test hygiene).** Add `FAKE_GRADLE_APPEND` and `FAKE_QUALITY` to
   the environment scrub list in `scripts/tests/verify-chat-context.test.mjs`.
4. **Docs.** Add one known-limits line: the constrained-decoding sampler in
   `LiteRtLlmEngine.kt` is not refused by the binding check. Quality runs
   cannot reach that path.

Then regenerate the lock, and rerun the retrieval probe. Report its result
whether or not it changed.

## Report back

- What changed, and the test that pins it.
- New hashes for `items.json` (if it changed), the lock, the library digest,
  `velquorin-handover.txt`, and every changed script, test or doc.
- The probe result.
- Check results with counts, using the same commands as the base handoff.
