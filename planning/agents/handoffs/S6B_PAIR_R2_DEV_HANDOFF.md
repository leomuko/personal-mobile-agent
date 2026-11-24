# S6b verification-pair preparation, fix round (dev round 2)

2026-11-23. From the planner to the dev agent (Codex).
[S6B_PAIR_RUN_DEV_HANDOFF.md](S6B_PAIR_RUN_DEV_HANDOFF.md) and the parent
handoff still apply in full: allowed paths, frozen inputs, no r3, no device
run, adb never invoked. Independent QA:
`build/qa-s6b-pair-2026-11-23/review.md` (verdict FAIL, host scope). Parts B, C
and D passed; do not reopen them beyond what is listed here.

Address every finding by ID: fixed (how), disputed (why) or deferred (why).

## Required

**S6b-P1 (P2): a severe reading can be lost when the monitor is joined.**
`scripts/monitor-model-thermal.mjs` makes the synchronous
`stopSupervisedQa` adb call before writing `safety-stop.json`, and it has no
SIGTERM handler. If `stop_thermal_monitor` (`scripts/verify-chat-context.sh`,
about lines 207–223) sends TERM between a severe sample and that write, the
monitor exits 143 with no marker, and the wrapper publishes `passed`. QA
reproduced this with the real monitor and a synthetic adb
(`build/qa-s6b-pair-2026-11-23/monitor-kill-probe/`).

Fix it in the wrapper. The monitor script is not in the allowed paths, so do
not edit it.
- After the monitor is joined and before any `STATUS=passed`, take one final
  `dumpsys thermalservice` reading on the device. Move or repeat the existing
  `thermal-final.txt` capture so it happens after the join.
- Parse status and SKIN temperature with the same rules the monitor uses, and
  compare them with `severeThermalStatus` and `maxSkinCelsius` from the run's
  `memory-policy.json`. At or above either threshold, fail `thermal-stop`.
- If the reading is unavailable or unparsable, fail closed with a distinct
  reason, for example `thermal-final-unavailable`.
- Apply this to `measure` and `quality` modes. Evidence and the quality
  `precheck` are kept as today.
- Regressions with the fake device, in both modes:
  - a monitor killed mid-stop (no marker) while the final reading shows status 3;
  - the same with SKIN at or above the limit;
  - an unreadable final reading;
  - the clean run and the clean pair still pass.
- If you find a further signal that the device was stopped, such as a stop
  record that the wrapper already pulls, you may also treat it as
  `thermal-stop`. State it in the handback; do not invent new device writes.

## Also in this round

**S6b-P2 (P3): the Kotlin pair-rule test has never run.** In candidate quality
runs, also select `AnswerQualityCandidateTest` in the same instrumentation
invocation, so the S24 pair run executes it with no extra device run. It loads
no model. Update the expected `OK (N tests)` check for candidate runs only.
Non-candidate quality runs are unchanged. Add a fake-device case.

**S6b-P3 (P3): validate cooldown evidence on the host.** `checkRun` (or
`check_quality_report`) requires, for candidate runs:
- one `candidateCooldowns` entry per load, in block order;
- thermal status 0 at load;
- `waitMs` ≤ 900,000;
- non-negative memory values.

A missing or invalid entry fails the run with a distinct reason. Add tests.

**S6b-P4 (P3): results doc.** In `docs/P2_REFUSAL_FIX_RESULTS.md`:
- state that there is no r3 and the experiment does not extend automatically;
- describe the thermal guarantee as it now stands, after P1;
- record that the measure mode's in-run safety-stop reason changed from
  `thermal-monitor` to `thermal-stop`;
- add a dated pair-round-2 note.

## Checks before handback

The same as the pair handoff:
- validate v1 (unchanged output) and the dev fixture;
- the three `node --test` suites;
- the Gradle baseline;
- the r2 and r1 `--assets-only` gates;
- `bash -n` and `git diff --check`.

Report any check the sandbox blocks as **blocked**. Do not work around it; QA
will run it. Confirm that the frozen folders `answer-quality-dev-v1/` and
`grounded-prompt-candidates-r1/` and the r2 files are byte-identical before
and after.
