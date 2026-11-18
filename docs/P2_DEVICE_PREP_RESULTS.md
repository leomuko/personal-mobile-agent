# Device-prep: loaded-context observability and S24 test readiness

2026-11-18. Starting checkpoint: `dev` at `9edb6af`. This is host implementation
and compilation only. **No device or emulator commands below were executed.**
S2b measurements and the SQLCipher device gate remain pending separate S24
authorization. Larger contexts remain unmeasured; P2 is incomplete.

**Readiness:** host implementation and both test APKs are complete. Part C is
not ready to claim bootstrap-level isolation from the normal QA database; the
existing runner starts the real QA application before test guards. That scope
question is retained below for the planner, without changing startup or runner
configuration outside the allowed paths.

## Part A: runtime, telemetry and panel

`ModelRuntimeState` adds nullable `loadedContextTokens` and
`loadedOutputTokens`. The private `loadedConfig` setter publishes both fields
at the same assignments that establish or clear manager ownership. Successful
chat/panel loads publish their actual configuration; confirmed cleanup clears
it, including reloads, idle cleanup and public/host-loss release. Loading before
successful native return has null sizes. Uncertain cleanup retains the last
owned configuration and `RESTART_REQUIRED`; it does not claim an unload. A
failed native load never publishes a successful loaded identity.

`MemoryTracePoint` adds nullable `contextTokensAtEvent` and
`outputTokensAtEvent`. `MemoryTelemetryRecorder` writes those exact additive
JSON keys, using JSON null when no configuration was owned at the event. All
old keys and schema numbers remain. These describe the recorded event, not a
claim that periodic samples observe a new lifecycle transition.

New resource strings:

- `model_configuration_loaded`: **Loaded: Context … · Output … · backend**.
- `model_configuration_unloaded`: **Panel configuration (not loaded): Context … · Output … · backend**.
- `model_configuration_unconfirmed`: **Panel configuration (load not confirmed): Context … · Output … · backend**,
  used for loading/failure without a successfully loaded identity, including
  uncertain partial native initialization. Null sizes alone do not prove cleanup.
- `model_test_profile_refused`: explains that test prompts require **2048/512**,
  and asks the operator to unload and load from the panel.

The panel displays runtime sizes when present. A loaded mismatching configuration
with the S2a-F1 `BUSY` refusal displays the new explanation; ordinary busy/error
messages remain. No panel command, scheduling rule or inference behavior changed.

**Memory floors are unchanged and are not calibrated for 4096 or 8192.** The
comment is at shared admission. Baseline policy export still describes its
2048/512 configuration; use the new event/resident fields to identify an extended
experiment. No available-memory floor, pressure behavior, output reservation,
ordinary default or QA-only 2048/512 path was changed.

Consumers inspected: `analyze-memory-reliability.mjs`,
`verify-conversation-native.sh`, the memory-policy/thermal/supervision tooling,
and Android memory probe/reliability tests. Their sample reads tolerate additive
keys; no script or existing test required modification. No existing test was edited.

## Part B: measurement instrumentation

`ChatContextMeasurementTest.gpuBakedProfileNearBoundAndShortControl` requires
explicit opt-in, the isolated secureQa package, the designated `SM-S928B`, an
unlocked device and the existing run-specific supervision lease. It uses the
baked `CHAT_CONTEXT_TOKENS` and GPU only, without fallback or retries.

Two deterministic synthetic requests run through ordinary `generateChat`: one
at the exact conservative single-message byte bound, then one short control.
Both start engine-unloaded. A byte-bound prompt is **not** proof of tokenizer
context saturation. OS file cache is uncontrolled. A test-only measured engine
delegate records successful native load time, first nonempty text, generation
time, input/output tokens and native decode rate without changing production
chat metrics. Before/after runtime snapshots bind results to resident sizes.
The existing recorder supplies periodic sampled PSS/RSS peaks and thermal status.
Release must confirm `UNLOADED` and null sizes. Failures persist before cleanup;
the next fixture is not attempted after failure.

The normal app manager is released before the test owner begins. Do not interact
with the app during collection. This is synthetic instrumentation, not ordinary
chat continuity/performance acceptance.

Reports in the secureQa app's `no_backup/` directory:

- `chat-context-<runId>.json`: case results, failures, resident sizes, cleanup and telemetry.
- `chat-context-<runId>-telemetry.json`: periodic partial trace retained if native work hangs.

### Exact future S24 commands (not run)

Run each profile separately, only after authorization and review. The pinned
model must already be verified in the **secureQa** app; these commands do not
acquire or import it. The runs use `scripts/verify-chat-context.sh`, an
executable `#!/bin/bash` script that works under macOS `/bin/bash` 3.2 and can be
started from zsh. It replaces the earlier paste-in shell function, which needed
`BASHPID` and stopped with "unbound variable" under Bash 3.2 (QA DP-F1).

```sh
export ANDROID_SERIAL='<AUTHORIZED_S24_SERIAL>'
scripts/verify-chat-context.sh measure 4096   # separately authorized
scripts/verify-chat-context.sh measure 8192   # separately authorized; not an automatic continuation
```

What the script does, in order:

- Requires an explicit `ANDROID_SERIAL`; every adb call uses `-s "$ANDROID_SERIAL"`.
- Builds secureQa with `-PchatContextTokens=<profile>` and the baseline memory
  profile (`-PandroidPressureMemoryTest=false`). It records APK hashes, HEAD
  and a digest of the tracked diff.
- Before each `adb install -r`, reads the APK's application ID with `aapt2 dump
  packagename` from the newest Android SDK build-tools under the same SDK root
  as adb (`ANDROID_HOME`, default `~/Library/Android/sdk`). The app APK must be
  `dev.edgecompanion.app.secureqa` and the test APK
  `dev.edgecompanion.app.secureqa.test`. If aapt2 is missing, cannot read the
  APK or reports another ID, the run fails before installing (reason
  `apk-identity`), so `APK_ROOT`/`GRADLE` overrides cannot install another app.
- Installs only the secureQa APK pair with `adb install -r`; a non-zero adb exit
  or output without `Success` fails the run. It never runs uninstall, `pm` or
  data-clear commands, and never names a debug or release package.
- Reuses `export-memory-policy.sh`, `monitor-model-thermal.mjs` and
  `verify-qa-supervision.mjs`. `QA_SUPERVISOR_PID=$$` is the script's own PID.
  The script starts the monitor directly, so the parent check in
  `qa-supervision.mjs` holds.
- Waits up to `THERMAL_READY_SECONDS` (default 15) for the monitor's first
  sample. No sample, a dead monitor or a `safety-stop.json` fails the run
  (reason `thermal-monitor`) before instrumentation starts.
- Runs `am instrument` for `ChatContextMeasurementTest` with a host deadline
  (`CHAT_CONTEXT_DEADLINE_SECONDS`, default 900). The adb client is started
  directly as the background job, so the recorded `instrumentationPid` is the
  host adb client process itself. The deadline does not depend on the device:
  when it passes, the script first stops the heartbeat monitor, then publishes
  the existing run-specific `stopSupervisedQa` stop for this run ID only
  (`deadline-stop.json`). It then waits up to `QA_STOP_GRACE_SECONDS` (default
  30) for the adb client to exit, sends it TERM if it has not, waits for it, and
  pulls whatever evidence exists. Stopping the host client does not by itself
  stop the device test; in this mode the device-side stop and lease do. The run
  is marked `failed` with reason `deadline`. There is no retry.
- The thermal monitor and the adb instrumentation client are stopped and reaped
  by the EXIT trap, including after INT (exit 130) or TERM (exit 143). Every
  gate is an explicit `check || fail <reason>`, because Bash 3.2 `set -e`
  ignores a failing bare `[[ ]]`. Every failure exits non-zero. `result.json`
  records status, reason, stage and exit code.

Evidence goes to `benchmarks/results/context-<profile>-<UTC>-<pid>-XXXXXX/`:
`chat-context.json`, `partial-telemetry.json`, `supervision.json`, and
`supervision-stop.json` if the device recorded a supervision loss. It also holds
`thermal/` (host samples and heartbeats), `thermal-final.txt`, and the
instrumentation, build and install logs.

On the device, each case is written as `"running"` before its native load
starts. A supervision loss (a host deadline stop, a thermal stop or an expired
lease) runs the test's cleanup callback first. That callback writes
`chat-context-<runId>-supervision-stop.json` and marks the report, and any case
still `running`, as `failed` with `supervisionLoss: true`, before the watcher's
bounded hard stop. `ModelManager`'s non-cancellable load is unchanged. A stalled
load is bounded by the host deadline and the supervision hard stop, not by the
test's `withTimeout`.

Do not rebuild concurrently with installation. Keep failed reports and inspect
both cases, matching loaded sizes, supervision, required metrics and confirmed
cleanup before interpreting the run. A successful instrumentation exit is not by
itself a context-selection decision. Selecting the existing Android-pressure QA
profile needs its own explicit run decision; this script does not offer it.

## Part C: SQLCipher passage gate

`PassageSqlCipherTest` requires `secureQa` and `p2PassageSqlCipher=true` before
test-owned database/context access. It creates UUID-named databases with their
own wrapped keys/Keystore aliases and deletes only those identities afterwards.
It does not resolve the app's real database or conversation. Normal secureQa
Application startup still runs before JUnit: `CompanionApplication` starts its
normal QA conversation session and model verification. It can access usual QA
storage independently of these fixture methods. No debug/release storage is
involved. If "never opens the real database" must include application bootstrap,
the planner must scope a separate test-runner/startup isolation change before
execution; that stronger guarantee cannot be supplied by this test's JUnit guard.

Three cases cover fresh import/ranking, direct FTS4 `matchinfo('pcx')`
compatibility, reopen, migration/backfill equivalence, hostile queries, deletion,
epoch invalidation and fixture cleanup. Production passage ranking uses bounded
weighted term matches; `matchinfo` availability is checked separately.

**Version clarification:** sources are introduced by `4.sqm` (schema 4→5), and
passages by `5.sqm` (5→6). Existing sources cannot exist in a schema-4 fixture.
The test follows the existing synthetic downgrade/reopen approach, retaining
existing Sources rows at schema 5, then runs migration 5 and explicit backfill.
No production schema or migration was modified.

Exact future S24 command, separately authorized, **not run**:

```sh
export ANDROID_SERIAL='<AUTHORIZED_S24_SERIAL>'
scripts/verify-chat-context.sh sqlcipher
```

This builds secureQa (default profile), runs `adb install -r` for both APKs, and
runs `am instrument -e class dev.edgecompanion.app.PassageSqlCipherTest -e
p2PassageSqlCipher true` against the secureQa test runner. It applies the
same APK identity check, host deadline (`PASSAGE_DEADLINE_SECONDS`, default
600) and process cleanup. This test has no supervision lease or device-side
stop, so on a deadline only the host adb client is stopped and the run is
recorded as failed (`deadline`). **The device-side test may keep running after
the deadline** until it ends on its own; it touches only its own randomly named
databases in the secureQa app.

Expect **three executed tests**, not skips. Do not substitute a debug/release
package or `connectedDebugAndroidTest`. All Part B/C installs and instrumentation
above target only `dev.edgecompanion.app.secureqa` and its test package. They do
not install, uninstall, clear or query debug/release app data.

## Host verification and limits

All checks used the existing local cache and `--offline`, with one Gradle
invocation at a time. `--no-problems-report` prevented a root diagnostic report;
`--console=plain` was used for retained logs. Test tasks used `--rerun`.

| Command | Result |
| --- | --- |
| `./gradlew --offline :androidApp:assembleDebug :shared:jvmTest --rerun :androidApp:lintDebug` | PASS. Final JVM run: 238 total, 236 passed, two existing tokenizer opt-ins skipped; zero failures/errors. Includes all ten new observability methods. Lint: zero errors, seven warnings. |
| `./gradlew --offline :androidApp:testDebugUnitTest --rerun` | PASS. 28 tests, zero failures/skips; rerun against final sources. |
| `./gradlew --offline :androidApp:assembleDebugAndroidTest` | PASS. All new instrumentation classes compile. |
| `./gradlew --offline -PtestBuildType=secureQa -PchatContextTokens=8192 :androidApp:assembleSecureQa :androidApp:assembleSecureQaAndroidTest` | PASS. App and test APKs compiled, never installed. |
| `git diff --check` | PASS. Read-only Git inspection only. |
| `node --test scripts/tests/verify-chat-context.test.mjs scripts/tests/qa-supervision.test.mjs` | See the QA round 2 and round 3 sections below. |

The initial instrumentation compilation caught a Long/Int comparison in the
new near-bound fixture assertion; it was corrected to compare Long values.
Both test APK compilations then passed. The first sandboxed Gradle launch was
blocked on the existing `~/.gradle` lock path; the same offline checks succeeded
with authorized local-cache access. No network dependency acquisition occurred.
Final local logs:
`/tmp/device-prep-baseline-final.log`, `/tmp/device-prep-app-unit-final.log`,
`/tmp/device-prep-debug-test-apk-final.log`, `/tmp/device-prep-secureqa-8192.log`.
The initial compile failure is retained at `/tmp/device-prep-debug-test-apk.log`.

Static generated-config checks confirm debug stays **2048** and
`dev.edgecompanion.app`; secureQa is **8192**, baseline pressure flag **false**,
and `dev.edgecompanion.app.secureqa`. Dependencies, versions, existing tests,
historical migrations, protected QA paths/pins and planner edits are unchanged.
HEAD remains `9edb6af`; the index is empty. No Git writes occurred.

The new Models panel rendering and recorder JSON instrumentation checks are
compile-only, as are the measurement method and three SQLCipher methods.
No instrumented tests, visual checks, model workloads or device acceptance are
claimed. The larger profiles have no device memory/load/latency evidence here.

### Helpers and self-check

- **Ohm:** wrote only `PassageSqlCipherTest.kt`; reported the schema-version
  clarification, standalone `matchinfo` probe and normal application-startup limit.
- **Faraday:** wrote only `ChatContextMeasurementTest.kt`; corrected the numeric
  comparison and used a test-owned native adapter under the existing manager
  implementation, rather than sharing mutable native handles with the app owner.
- **Peirce:** read-only Part A self-check. Its two findings were addressed:
  unknown initialization uses neutral panel wording, and each verification call
  now requires fresh load/prefill/decode/cleanup events and its expected terminal
  outcome instead of accepting an earlier call's trace.

Main reviewed the helpers' changes and ran all compilation/tests. No helper ran
Gradle, devices, network or Git writes; none started further helpers. This
self-check does not replace the planner's independent QA review.

Potential inconclusive/failed S24 runs: wrong APK/profile or missing opt-in,
missing model, baseline resource rejection, locked/incorrect device, user/app
interference, absent native token/decode metrics, missing thermal/PSS/RSS samples,
dropped/failed telemetry, safety stop or lost supervision, process death, timeout,
unconfirmed cleanup or fixture deletion failure. Retain the first failure;
do not retry automatically. The near-bound fixture and short control are single
observations, not p95, sustained-memory or latency evidence.

## QA round 2 (DP-F1, DP-F2), host only

- **DP-F1:** the S24 procedures are now the executable script
  `scripts/verify-chat-context.sh` (Bash 3.2, `QA_SUPERVISOR_PID=$$`). Before
  the fix, the old function prefix under `/bin/bash` 3.2.57 exited 1 with
  `BASHPID: unbound variable`. Now `bash -n` passes, and the script runs under
  `/bin/bash` with a synthetic adb. The monitor's parent PID equals the
  script's PID and `QA_SUPERVISOR_PID`.
- **DP-F2:** the script enforces a host deadline with a run-scoped stop, as
  described above. On the device, a supervision loss records a failure.
  Regression: with synthetic instrumentation that never finishes and a
  2-second deadline, exactly one `qa-supervision/<runId>/stop.json` is
  published. The monitor is stopped, with no heartbeat after the stop. (The
  round-2 claim that the client was stopped was wrong: only a bash subshell was
  stopped; corrected in round 3, DP-F3.) The partial `chat-context.json` (`running`) and the thermal
  evidence are kept. `result.json` shows `failed`/`deadline`, exit 1.
- `node --test scripts/tests/verify-chat-context.test.mjs scripts/tests/qa-supervision.test.mjs`:
  9 of 9 pass. No device or emulator was used, and no real adb was called.
  The device-side loss recording is compile-only.

## QA round 3 (DP-F3, DP-F4, DP-F5), host only

- **DP-F3:** the instrumentation was started through the `device` shell
  function, so `$!` was a bash subshell and the adb client survived the
  deadline and EXIT/INT/TERM cleanup as an orphan. The script now backgrounds
  `"$ADB" -s "$ANDROID_SERIAL" shell am instrument ...` directly. The synthetic
  adb records its own PID; the tests assert that `instrumentationPid` equals the
  adb client's PID and that it is dead after normal completion, the measure and
  SQLCipher deadlines, and TERM/INT to the script. Each test's teardown fails
  if any synthetic adb process (or the monitor) is still alive after 3 s, and
  kills it so the suite cannot leak. Optional item done: on a deadline the
  monitor is stopped before the stop is published. Residual: if TERM reaches
  the monitor while its own adb heartbeat call is in flight, that short-lived
  child can still finish; the device treats the stop as dominant.
- **DP-F4:** every gate is explicit (`[[ ]] || fail`, `cmd || fail`), including
  build and digest pipelines, installs, the memory-policy export, thermal
  readiness, safety stop, adb exit codes, supervision and report checks. Audit:
  no bare `[[ ]]`/`(( ))` statement remains (a lint test enforces this);
  `$(...)` assignments that can fail are explicit; `pull` and
  `publish_deadline_stop` are deliberately best-effort in `|| true` lists. New
  `/bin/bash` regressions, all `failed` with exit 1: a thermal sample that
  never arrives (`THERMAL_READY_SECONDS=2`, monitor alive), a safety stop at
  readiness, a safety stop written during the run, a failing app install, an
  install that prints `Failure` with exit 0, and non-zero instrumentation exits
  in both modes.
- **DP-F5:** application-ID check with build-tools aapt2 before each install,
  failing closed (see above). Regressions: wrong app ID, wrong test-APK ID
  (after one install), no aapt2, and an unreadable APK are each rejected before
  the corresponding install.
- Against the round-2 script, the round-3 suite fails 16 of 18 tests, and its
  teardown found and killed four orphaned synthetic adb clients. Against the
  round-3 script all pass; see the dev handback for counts. No device,
  emulator or real adb was used. Real adb shell-protocol behaviour when the
  host client is killed remains unverified.
