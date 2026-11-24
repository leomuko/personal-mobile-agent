# S6b round-2 fixes (dev round 3)

2026-11-23. From the planner to the dev agent (Codex). The parent handoff,
[S6B_REFUSAL_FIX_DEV_HANDOFF.md](S6B_REFUSAL_FIX_DEV_HANDOFF.md), and
[S6B_R2_DEV_HANDOFF.md](S6B_R2_DEV_HANDOFF.md) still apply in full. The
independent QA report for round 2 is `build/qa-s6b-r2-2026-11-23/review.md`
(verdict FAIL, host scope). S6b-F1 to F7 are resolved; do not reopen them.

This is round 3 of at most 3 for this slice. Keep the changes to what is
listed. The fixture and candidate file are still not frozen: edit them in
place and re-lock.

Address every finding by ID: fixed (how), disputed (why) or deferred (why).

## Required

**S6b-F8 (P2): missing-detail 06 is answerable from packed text.** The item
asks for the Drossymanthel "equipment repair location". The rewritten close
note's last sentence, `drossymanthel-close.txt:8`, ends "leave the roller for
the repair bench", and both estimates pack it. Reword that sentence so that
nothing in any Drossymanthel note names, implies or locates a repair place.
Alternatively, change the location the item asks for. Then:
- re-run the 18-item audit with the rule extended to **any packed text**, not
  only labelled fields. A sentence in routine remarks that could answer the
  question counts as a match;
- correct the audit row in `docs/P2_REFUSAL_FIX_RESULTS.md`.

## Also in this round

**S6b-F11 (P3): leading questions.** Reword missing-detail 05, 08 and 09 to
neutral direct questions in the same form as the answerable items ("What is
the …?"), with no "if any", "is … recorded" or "does … give". Keep each
item's kind, field and forbidden facts. Check that no yes/no question remains
among the unanswerable items.

**S6b-F10 (P3): aids.**
- Widen the refusal aid to also cover "do not/does not contain", "does not
  include" and "does not give", with tests.
- For near-miss items, also list the asked entity's own packed values of the
  same field family as forbidden facts, within the reuse cap of 4.

**S6b-F9 (P3): label assets-only output.** An `--assets-only` run records
`assetsOnly: true` in `result.json`, and `precheck`, `compare` and `report`
refuse such a directory with a clear message. Add a wrapper test.

**S6b-F5 residual (P3).** Add a host test showing that a `ConversationSession`
built with a non-default `SourcesPromptText` delivers its instruction and
reminder to the runtime in `SOURCES` scope. Use the existing session test
doubles.

## After the edits

- Re-lock the dev fixture. The candidate file should not change; if it does,
  say why.
- Re-run both retrieval estimates (yours and QA's unchanged
  `build/qa-s6b-r1-2026-11-23/retrieval-probe-dev.mjs`) and report per kind.
- Update `docs/P2_REFUSAL_FIX_RESULTS.md`: hashes, the corrected audit, and a
  dated round-3 note.
- Run every check in the parent handoff's "Checks before handback", including
  the `--assets-only` asset gate against the real staged APK. No device or
  emulator run, and adb must never be invoked.
