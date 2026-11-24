# S6b round-1 fixes (dev round 2)

2026-11-23. From the planner to the dev agent. The parent handoff,
[S6B_REFUSAL_FIX_DEV_HANDOFF.md](S6B_REFUSAL_FIX_DEV_HANDOFF.md), still
applies in full: allowed paths, contamination rule, boundaries, checks and the
selection rule. This round fixes the independent QA findings. The QA report is
`build/qa-s6b-r1-2026-11-23/review.md` (verdict FAIL, host scope).

The fixture and candidate file are **not frozen yet**. Edit them in place, and
keep the names `answer-quality-dev-v1` and `grounded-prompt-candidates-r1`.
Re-lock both.

Address every finding by ID in the handback: fixed (how), disputed (why) or
deferred (why).

## Required

**S6b-F1 (P2): candidate `exact-fixed`.** Make it exactly the `exact-match`
instruction with only the last sentence replaced by "If the exact answer is
missing, reply with this sentence and stop: Your sources don't cover this."
Every other byte must match `exact-match`. Add a scorer validation (or test)
that pins this relationship, and that `near-question` has the same instruction
as `exact-match` plus its reminder. The set then changes one thing at a time:

| id | instruction | reminder |
| --- | --- | --- |
| v1 | v1 | none |
| exact-match | exact rule | none |
| near-question | exact rule | exact reminder |
| fixed-refusal | v1 evidence rule + fixed refusal | none |
| exact-fixed | exact rule + fixed refusal | none |

**S6b-F2 (P2): ambiguous missing-detail items.** Reword
`unanswerable-missing-detail-07` ("check-in fee" against the register's
"intake fee") and `-05` ("after-hours fee" against "callout fee"). Choose
fields with no synonym, hypernym or near-equivalent anywhere in that entity's
notes. Then review **all 18** missing-detail and near-miss items once more for
the same problem, and list the result per item in the results doc (one line
each: asked field, nearest present field, why it is not a match).

## Also in this round

**S6b-F4 (P3): literal aids.**
- For each missing-detail item, list as forbidden facts every same-entity
  sibling value that the host estimate packs, within the reuse cap of 4. If the
  cap blocks one, add an aid that reports "any in-library value of the asked
  entity present" instead, and say which you chose.
- Widen the refusal-phrase aid so it covers at least "not listed", "not in the
  passages/notes/sources", "could not find", "no information", "does not
  say/mention/state" and "don't/doesn't cover". Add tests with positive and
  negative cases, including an answer that states a value and then hedges.
  It remains an aid; labels decide.

**S6b-F5 (P3): candidate text reaches the request.** Add a `jvmTest` using
the existing assistant/session test doubles. It shows that a non-default
`SourcesPromptText` puts its instruction and reminder in the request the
runtime receives in `SOURCES` scope, and that `ORDINARY` requests are
unchanged. Optionally add a harness check for non-v1 blocks: the sent text
contains the candidate's instruction, and the reminder when present.

**S6b-F6 (P3): fixture variety.**
- Add a second fee line to `kestrelmabrun-close.txt` and
  `tallowdrisket-close.txt`.
- Move 3 of the 10 missing-detail items to non-fee field kinds, for example a
  date, a supplier or person, or a location. Their twins move with them, and
  each note gains the sibling fields needed. Vary the question phrasing across
  the 10 items rather than one template.
- Make the second paragraph of the five close notes differ.
- Keep exact counts, twins, 3 people near-misses, disjointness from v1, and
  the rule that the host estimate packs every gold passage and every forbidden
  passage.

**S6b-F3 (P3): packaging.**
- Package the dev fixture from a neutral location, not from inside the r1
  candidates folder, for example
  `planning/fixtures/android-test-assets/answer-quality-dev-v1 -> ../answer-quality-dev-v1`.
  That new folder is authorized.
- In the wrapper, before installing, list and extract the staged test APK's
  assets for the dev fixture and the requested candidate round, and compare
  them byte for byte with the bound files. Fail with a distinct reason, for
  example `quality-assets`, without touching the device. Add fake-device
  cases: a missing candidate asset and a modified dev asset.

**S6b-F7 (P3): nits.** Remove the always-true
`check(development == (set == "dev"))` in `AnswerQualityFixture.kt`. Place
`sourcesPromptText` before `retriever` in `ConversationAssistant` (keeping its
default) so the secondary constructor can go, and update call sites only as
needed.

## After the edits

- Re-lock the dev fixture and the candidate file. Rerun both host retrieval
  estimates (yours and QA's `build/qa-s6b-r1-2026-11-23/retrieval-probe-dev.mjs`,
  unchanged) and report per kind.
- Update `docs/P2_REFUSAL_FIX_RESULTS.md`: the new hashes, the candidate
  table, the F2 item review, and a dated round-2 note. Claim nothing about
  device behaviour.
- Run every check in the parent handoff's "Checks before handback", including
  the secureQa app plus test APK assembly and asset inspection. The new
  wrapper asset check must pass against the real staged APK on the host
  (no device needed). No device or emulator run.
