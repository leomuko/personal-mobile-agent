# P2 Prerequisite: Device-Local QA Supervision

Implemented 2026-10-15 after the appearance review; ownership-race revision verified 2026-10-18. This is the next safety prerequisite before P2 tokenizer/parser/embedding work, not source ingestion or grounded chat implementation. No Gemma inference, model installation, personal conversation clearing, recording, USB disconnection or device-wide setting change was performed for the synthetic probes below.

## Contract and Placement

- `SupervisionLease` is pure Kotlin in `androidApp/src/testSupport/kotlin`, compiled into local tests and instrumentation only. It does not ship in the app. Device file/process APIs stay in `androidTest` support; host ADB/thermal orchestration stays under `scripts/`.
- Each wrapper supplies a fresh run ID. A successful thermal observation and a live wrapper parent permit the monitor to publish a monotonically sequenced, atomic private heartbeat. The timestamp is from `/proc/uptime` on the device, not the host wall clock. The readiness CSV row is written only after heartbeat publication succeeds.
- Before native work, the device guard requires a valid current-run heartbeat. Missing, malformed, foreign-run, replayed, future-clock or expired evidence fails closed. Polling the same heartbeat does not extend its lifetime; a lost lease cannot be revived by reconnecting.
- Lease: 20 seconds from the heartbeat's device boot time; poll: 500 ms. Heartbeat I/O is on a separate thread from expiry decisions. Loss requests `ModelManager.release()` under existing ownership coordination. A separate emergency thread terminates the isolated QA process after at most a 5-second cleanup wait, even if cleanup or loss-report writing is blocked. Thread scheduling/OS failures are not a hard real-time guarantee.
- Termination after lease loss is deliberate even if cleanup succeeds, so no test loop resumes under expired supervision. The guard checks debug + secure windows + `.secureqa` before activation. Ordinary app processes, permissions, stored model assets, memory thresholds and backend rules are unchanged.
- Native conversation, memory-reliability and runtime tests acquire the guard around their entire workload and cleanup. Runtime QA now rejects unsupervised invocation. Plain build/UI suites do not opt in or start watchdog probes.
- Clean-close proof is written only after guard threads stop. The wrapper and memory analyzer require a fresh `closed` proof with matching run ID and lease configuration; a native report saying `passed` is insufficient. Host stops publish only to the requesting run's mailbox; they no longer issue package-wide `force-stop`. A separate ownership read followed by `force-stop` was racy and did not protect a newer run.

The watchdog is an emergency supervision boundary, not an implementation of deferred app audio consent or a new production inference scheduler. A native hang while a healthy host continues renewing its lease remains separate native recovery work.

## Initial Verification (2026-10-15)

Passed local checks: shared JVM tests, shared Android-host tests, UI palette test, app debug/secure-QA unit tests, debug/release builds, debug lint and debug/secure-QA instrumentation compilation. New focused coverage includes 10 appearance tests (two deterministic reversed-dispatch regressions) and three pure supervision-policy tests. Lint retains existing warnings.

Host regressions: **21 tests passed**, covering supervisor identity/orphan loss, unavailable clocks, ADB failure, atomic heartbeat publication, stale/missing/foreign/unclosed proof, run-scoped emergency stops, memory-summary rejection and the existing final-exit evidence regression. Run `node --test scripts/tests/qa-supervision.test.mjs scripts/tests/memory-reliability.test.mjs scripts/tests/chat-resource-preflight.test.mjs`. Shell syntax, Node syntax and whitespace checks also passed.

S24 Ultra SM-S928B, Android 16, synthetic watchdog proof at ignored `benchmarks/results/supervision-KIOlrd/`:

| Scenario | Result | Observed End-To-End Time |
| --- | --- | --- |
| Healthy lease, short synthetic work | JUnit OK (1 test), guard closed | 2,377 ms |
| No further heartbeat, cooperative fake cleanup | QA process terminated, EXPIRED, cleanup confirmed | 20,479 ms |
| No further heartbeat, deliberately hung fake cleanup | QA process killed, EXPIRED, cleanup unconfirmed | 25,493 ms |

The two loss cases intentionally do not produce a successful JUnit/native result. The host probe passed because the expected failure evidence matched the run and `pidof` confirmed QA was gone. The hung-cleanup case retained `status=lost`, because the independent stop killed the process before a final report; it is not cleanup-success evidence. Normal Assistant was not stopped.

Fresh appearance/host regression after the ordering fix: **OK (14 tests)** in 20.47 seconds. Log: `androidApp/build/p2-appearance-ordering-review.log`. These are new device results, not the previous review's reused logs.

## Ownership-Race Revision (2026-10-18)

- Protocol schema 2 gives every immutable run ID a private `no_backup/qa-supervision/<runId>/` directory containing `heartbeat.json`, `stop.json` and `status.json`. Late stop requests and heartbeat renewals remain in the old directory; they cannot target the new run. Publishing a stop request is not confirmation that the process has stopped.
- `SupervisionOwnership` holds an OS file lock for the entire guard lifetime, including cleanup and status publication. A process-local owner check avoids opening/closing another descriptor while that process owns the lock. A successor is admitted only after clean guard closure or OS release on process death. Uncertain cleanup retains ownership; the emergency stop kills only its own QA process, never a package selected by the host.
- Host wrappers export scoped schema-2 proof. Schema-1 proof is intentionally rejected, so historical supervision evidence is not silently accepted as proof of this revision.
- All **22 host regressions passed**, including ownership changing during stop publication and stale heartbeat publication. All **13 debug app unit tests passed**, including two ownership tests and four lease tests. Debug/release builds, debug lint, secure-QA instrumentation compilation and secure-QA unit tests passed. Shared JVM/Android-host and UI palette checks passed using Gradle's unchanged cached results. Existing lint warnings remain.
- Fresh S24 synthetic results: ignored `benchmarks/results/supervision-tWXNvh/summary.json`, `nativeWorkload=false`, all six scenarios passed:

| Scenario | Result | Observed End-To-End Time |
| --- | --- | --- |
| Healthy lease | Closed, JUnit OK (1 test) | 2,046 ms |
| Handoff, then old-owner heartbeat and stop | New owner remained healthy and closed | 2,636 ms |
| Current-owner scoped stop | STOP_REQUESTED, terminated, cleanup confirmed | 1,906 ms |
| Heartbeat expiry | EXPIRED, terminated, cleanup confirmed | 20,427 ms |
| Heartbeat expiry with hung cleanup | EXPIRED, process killed, cleanup unconfirmed | 25,479 ms |
| Fresh run after forced termination | Closed, JUnit OK (1 test) | 1,785 ms |

The handoff test also rejects overlapping guards before the old owner closes. The hung-cleanup case correctly retains `status=lost`, not a false cleanup-success result. These are supervision/control-flow checks with fake cleanup, not native model acceptance. No sustained native workload ran during this revision.

## Reproduce Without Inference

Build and data-preservingly install the QA app and test APKs using `-PtestBuildType=secureQa`, then run on an authorized connected test device:

```sh
ANDROID_SERIAL=DEVICE node scripts/probe-qa-supervision.mjs
```

This runs only `SupervisionProbeTest`; it uses scoped stop requests and stops renewing heartbeats instead of disabling USB or touching other apps. It intentionally terminates Assistant (QA) in the stop-request and two expiry cases, then checks recovery. Results contain synthetic run IDs, timing and status metadata, not personal payloads.

## Native Follow-Up (2026-10-18)

Targeted real Gemma GPU loss checks during initialization, prefill and decoding, plus fresh-process native recovery, now passed under the explicitly authorized existing QA Android-pressure profile. Initialization required emergency process termination; prefill/decoding cleanup confirmed unload. See [native results, earlier failed attempts and exact scope](P2_NATIVE_SUPERVISION_RESULTS.md). This closes the requested targeted phase-loss gate, not the entire native/mobile acceptance matrix.

## Still Not Run

The updated ordinary native smoke/sustained wrappers, physical cable removal/reconnection, ADB stalls and host SIGKILL on a live native run. Host orphan behavior is covered by injected-parent tests; controlled heartbeat unavailability now has native S24 evidence as well as synthetic watchdog evidence. This is not full P1/P2 acceptance or default memory-policy calibration. Manual system-triggered appearance, cold-launch flashes and real recording/inference continuity also remain open.
