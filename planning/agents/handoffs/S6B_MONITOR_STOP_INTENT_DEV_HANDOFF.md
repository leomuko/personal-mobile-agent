# S6b follow-up: persist the thermal stop intent before the stop request (dev)

2026-11-23. From the planner to the dev agent (Codex). The user chose to fix
S6b-P5 before the S24 verification-pair run. This is a narrow, **host-only**
change. The parent handoffs' boundaries apply: frozen inputs, no r3, no
device or emulator run, adb never invoked, no commits, no network.

Evidence:
- `build/qa-s6b-pair-r2-2026-11-23/review.md` (S6b-P5, with its
  `monitor-kill-probe/`);
- the user-requested audit
  `build/qa-s6b-p5-decision-2026-11-23/audit-_wl1zt1h/review.txt`.

## The gap

In `scripts/monitor-model-thermal.mjs`, `stopQa()` makes the synchronous
`stopSupervisedQa()` adb call (`scripts/qa-supervision.mjs`) **before** it
writes `safety-stop.json`. If the wrapper's join signal ends the monitor
inside that call, the severe sample is lost. A later final reading just under
both limits (the S24's limits coincide at SKIN 45 °C and status 3) then lets
the run publish `passed`.

Two qualifications from the audit shape the fix:
- A later sub-limit reading cannot recover a lost severe reading.
- Phone-side `THERMAL` rows use Android thermal status only, while the host
  monitor also checks SKIN. The absence of a `THERMAL` row does not establish
  a clean thermal history.

## Required behaviour

1. **Persist the stop intent first.**
   - On a severe sample, or on the signal-unavailable path, the monitor
     durably records the stop event **before** calling `stopSupervisedQa`.
     The record holds the run ID, reason, recorded time, and observed status
     and SKIN, or the error name.
   - Write it atomically: a temporary file in the same folder, `fsync` where
     available, then rename.
   - Prefer writing it as `safety-stop.json` itself, with a delivery field
     such as `"delivery": "pending"`. Every wrapper that tests for the file's
     existence then treats it as a stop with no change. If you choose a
     separate intent file instead, update every consumer.
2. **Keep the first event.** Delivery is recorded separately: update the same
   record atomically with the outcome (`stopRequested: true`, or
   `hostStopError`), or append a delivery record.
   - Later failures, retries or further samples never overwrite or erase the
     first event's reason and values.
   - A delivery failure leaves the stop recorded.
3. **Fail safe if recording fails.**
   - If the intent cannot be written, the monitor still attempts the stop.
   - It then exits non-zero with a distinguishable error on stderr, so the
     wrapper's join treats the monitor as not clean and validation fails.
   - It never exits 0 or 143 after a severe sample.
4. **Consumers.** Check every user of the monitor and of `safety-stop.json`
   still behaves correctly with a pending record:
   - `scripts/verify-chat-context.sh`, `scripts/verify-model-runtime.sh` and
     `scripts/verify-conversation-native.sh`;
   - the probes in `scripts/probe-*.mjs`;
   - `scripts/calibration-investigation.mjs` and
     `scripts/run-p2-embedding-gate.mjs`.

   A pending record must count as a stop everywhere. List each consumer and
   its result in the handback. Change consumers only where needed.
5. **Unchanged:** thresholds, sampling interval, heartbeat or supervision
   behaviour, the wrapper's post-join final reading (keep it), the memory
   policy, frozen fixtures and candidates, and all Kotlin and production code.

Do not claim that this eliminates every failure window. It closes the
demonstrated window around the blocking stop call. A signal between the
sample and the atomic write remains possible but is short; say so.

## Tests (host, synthetic adb)

Add focused tests, in `scripts/tests/qa-supervision.test.mjs` or a new
`scripts/tests/monitor-model-thermal.test.mjs`:
- **The reproduced race:** the monitor is killed with TERM while
  `stopSupervisedQa` is blocked. The intent record exists, with the reason and
  values. Through the wrapper (fake device), the run fails `thermal-stop`,
  even when the final reading is below both limits.
- **A SKIN-only crossing** (status below severe, SKIN at the limit): the
  intent is recorded and the run fails.
- **Delivery failure** (the stop publish throws): the record keeps the first
  event and records the delivery error, and the run fails.
- **Intent write failure** (for example an unwritable folder): the stop is
  still attempted, the monitor exits non-zero, and the run fails.
- **Normal shutdown** with no severe sample: no record, and a clean run still
  passes.
- Existing monitor, supervision and wrapper tests still pass. Do not weaken
  any.

## Allowed paths

`scripts/monitor-model-thermal.mjs`, `scripts/qa-supervision.mjs` (only if
needed), `scripts/tests/qa-supervision.test.mjs`, a new
`scripts/tests/monitor-model-thermal.test.mjs`, `scripts/verify-chat-context.sh`
and `scripts/tests/verify-chat-context.test.mjs` (only if needed), the other
consumers listed above (only if needed for a pending record), and
`docs/P2_REFUSAL_FIX_RESULTS.md` (a dated note).

## Checks before handback

- The three `node --test` suites, plus any new monitor test file, with
  nothing skipped.
- `bash -n` on every changed shell script, and `git diff --check`.
- A hash comparison showing that `planning/fixtures/answer-quality-dev-v1/`,
  `grounded-prompt-candidates-r1/` and `grounded-prompt-candidates-r2/` are
  unchanged.

No Gradle or Android build is needed: no Kotlin or Gradle input changes. If
the sandbox blocks `ps`-dependent tests, report them as **blocked**; QA will
run them.
