# P1 Memory Reliability Results

Date: 2026-10-15. Branch: dev. Status: cleanup/policy/telemetry foundation implemented; explicitly authorized Android-pressure QA profile passed five native GPU cycles and the full 20-minute sustained workload. Normal-use calibration/acceptance remains incomplete. [Plan](../planning/P1_MEMORY_RELIABILITY_PLAN.md).

## Implementation

- ModelManager closes retained engines after post-load and in-flight resource rejection, including internal Models-tool requests. Cleanup runs under existing ownership coordination, without recursively awaiting public release. Retry is BUSY until cleanup finishes. Successful close clears ownership; close failure/cooperative timeout leaves ownership uncertain, records cleanupError, quarantines the manager and reports RESTART_REQUIRED. It never retries native close on a poisoned runtime. A close timeout does not terminate noncooperative native code.
- Conversation persistence is unchanged: user turns/drafts remain durable, while failed/interrupted replies do not become completed assistant messages. Chat terminal outcomes wait for cleanup; runtime state exposes RELEASING while retries are BUSY.
- MemoryPolicies.Current centralizes the unchanged baseline floors, load reserve, sample cadence, hysteresis and thermal limits. Loading and generation decisions are separate APIs, though their current floors remain identical. A shared MemoryPressureWindow replaces duplicate monitor policy code.
- Shared trace points include phase, backend, policy ID, runtime status, engine ownership and monotonic event time. Android's opt-in recorder adds capture time, available RAM, pressure threshold, lowMemory, app PSS/RSS and thermal status. Policy polling avoids the more expensive process-memory snapshot. Recording is capped at 10,000 samples; missing/dropped samples prevent a clean QA result. Metadata failures do not justify reusing uncertain native handles or poisoning a successfully closed engine.
- The QA probe exports the installed manager's profile. Host JSON parsing validates its schema/fields; conversation/memory preflight and thermal supervision use it. The legacy model benchmark also exports the profile before starting supervised thermal checks. It retains its existing runtime admission path; the conversation/memory harness retains its extra pre-load reserve.
- New GPU workbench: five repeated structured-chat/cancel/unload cycles by default, followed by 20 minutes of bounded multi-turn requests, periodic cancellation/reload and five-second recovery samples. Synthetic text stays in memory and is not exported. Reports contain counts and resource metadata only. Partial sample snapshots are persisted during the run. An explicit zero-minute mode is smoke only, never sustained acceptance.
- Own-package process exits are exported without names, descriptions or traces. Confirmed OS low-memory exits, native crashes, ANRs and ambiguous SIGKILL are separate classifications. The host summary filters historical entries using the workload start time, validates evidence completeness, and reports retained PSS trends without declaring a leak or approving calibration. A host-generated run ID correlates the workload and before/after probes. Final evidence must be from a successful probe of that run, captured on-device after workload cleanup completed; entry timestamps alone are not evidence of snapshot freshness.

## P2 Review Corrections

- Benchmark prefill telemetry now executes inside the pressure monitor's cleanup-protected block. The new common regression suspends prefill telemetry, cancels, releases and advances virtual time with an unsafe resource signal. It verifies no further polling or stale state mutation after release. This test failed against the reviewed implementation and passes with the fix.
- The final exit collector requires both command success and instrumentation success before exporting evidence. It removes any previous host export, stages the new file, and checks run ID, after phase and capture metadata before promotion. Probe failure makes an otherwise passed supervised run fail, without replacing an existing workload failure code.
- The analyzer requires the host run context, matching workload/probe IDs, workload completion time, and a final capture time no earlier than completion. Old undated reports are not retroactively accepted. Synthetic regressions cover stale/preflight snapshots, missing timestamps, mismatched runs, nonzero probe commands, JUnit failure with command exit zero, and successful fresh export. A complete fake-ADB harness run confirms a passed workload becomes failed when the final probe fails with its older preflight file still present. No real model is involved in these host tests.

## Verification

Passed local:

- Debug APK and shared JVM tests: 67 passed. Shared UI JVM compilation passed in the original foundation run.
- Shared Android-host tests: 52 passed; Android debug unit tests: 6 passed.
- Debug lint: 0 errors, 23 existing warnings.
- Secure-QA app and AndroidTest APK builds; secure-QA unit tests: 6 passed.
- Host preflight/evidence-analysis/collector/wrapper tests: 11 passed.
- Shell syntax and git diff whitespace checks.

The S24's initial policy/pressure and exit probes passed 2 tests. The supervised memory run then blocked at resource-preflight, exit 2, without native model loading: `benchmarks/results/chat-20261015T010118Z-JF28Lu/` (ignored raw reports). It requested 5 cycles/20 minutes, but completed neither because admission was blocked.

Initial Android probe measurements:

| Field | Value |
| --- | --- |
| Available RAM | 2,693,738,496 bytes, approximately 2.51 GiB |
| Android lowMemory | false |
| Android pressure threshold | 591,396,864 bytes, 564 MiB |
| QA process PSS | 119,402,496 bytes |
| QA process RSS | 274,104,320 bytes |
| Thermal status | 0 |

Subsequent host samples remained below the 3 GiB baseline floor. The 5.5 GiB supervised load requirement was also not met. This is project-policy refusal, not an observed native OOM or proof of safety below that floor. Other apps were not closed, device settings were not changed and no cloud path was added.

The final non-native S24 regression run passed 3 tests (approximately 1.6 seconds): MemoryPolicyProbeTest, MemoryExitProbeTest and ModelsPanelTest. It verified profile identity from the actual manager, valid bounded recorder samples with pressure/PSS/RSS data, own-package exit export and existing internal model controls. Full allocation-cycle capture with real Gemma, native post-rejection cleanup verification, repeated recovery trends and the sustained GPU session remain not run under this revised implementation. Earlier GPU evidence does not certify these changes. A calibrated replacement profile is not implemented or approved.

After the P2 review corrections, the rebuilt secure-QA app/test APKs were installed with data preserved. An initial non-native final collector passed MemoryExitProbeTest on the connected S24 (`OK (1 test)`). A paired check then passed the before-policy/exit probes (`OK (2 tests)`) and the final collector (`OK (1 test)`), validating matching run IDs, before/after phases and ordered capture timestamps. Reports: `benchmarks/results/memory-p2-exits-7RaqsS/` (ignored). These checks loaded no model and did not exercise the native workload or conversation database.

Validation commands were run separately for debug and secure-QA: combining `testDebugUnitTest` with `-PtestBuildType=secureQa` initially failed task selection before execution; the correct separate invocations both passed.

## Android-Pressure Automated Run

The explicitly requested `--android-pressure` run on 2026-10-15 bypassed only the host available-RAM floor/reserve. Reports: `benchmarks/results/chat-20261015T014357Z-GFyAs4/` (ignored), run ID `0ecd2e46-98af-42bc-a177-faacccf9c042`. The isolated QA APKs were updated with data preserved; the app's baseline policy was unchanged.

- Passed: all 15 host-script tests; forced debug rebuild, 67 shared JVM tests, 52 shared Android-host tests, 6 app unit tests and debug lint (0 errors, 23 warnings). Shell syntax and diff whitespace checks passed.
- Passed on S24: initial policy/exit probes (2 tests), three fresh policy probes used by the override, and the final exit probe (1 test). All three preflight samples reported `lowMemory=false`, thermal status 0 and approximately 2.73-2.77 GiB available, so host preflight admitted the workload.
- Failed: the native GPU memory test (`No stream: LOW_MEMORY`, script exit 1). Unlike earlier host-blocked attempts, this run initialized the real Gemma GPU engine and reached `LOADED_IDLE`. That sample recorded 3,126,890,496 bytes available (approximately 2.91 GiB), below the app's unchanged 3 GiB post-load/generation floor. It recorded `lowMemory=false` and thermal status 0. No response, completed cycle or sustained workload was produced.
- Cleanup evidence: `POST_RELEASE` recorded `UNLOADED`, no engine owned and no cleanup error. Sampled PSS peaked at 2,029,046,784 bytes (approximately 1.89 GiB), then reached 154,202,112 bytes (approximately 147 MiB) in the final post-release sample. All 43 samples reported `lowMemory=false`, thermal status 0, with no failed/dropped samples. This single load/rejection/release attempt does not establish repeated-cycle recovery or absence of leaks.
- The thermal supervisor sampled a maximum skin temperature of 35.3 C with no safety stop. The successful final exit probe matched the run and was captured after workload completion; no relevant confirmed low-memory kill, native crash, ANR or unclassified SIGKILL was reported. The analyzer correctly retained `workloadPassed=false` and `acceptanceComplete=false` because generation/cancellation/cycle/sustained evidence is incomplete.
- Passed after the workload: 12 focused S24 integration tests across `ModelsPanelTest`, `ModelAssetsTest`, `DependencyHostTest` and `AssistantTopBarTest`. These cover internal control states, trusted artifact metadata/import failure handling, shared session/recreation and header geometry/theme colors. No broad permission-mutating suite or manual lifecycle matrix was run.

The script override works as intended, but completing native generation still requires an explicitly reviewed app-side calibration decision. This failure is a project-policy rejection after native initialization, not evidence of an OS out-of-memory kill. No policy threshold was relaxed during this test run.

## App-Side Android-Pressure Test Profile

The user subsequently authorized extending the override to the app for supervised testing. `--android-pressure` now builds the isolated QA app with `-PandroidPressureMemoryTest=true`, injecting shared `memory-android-pressure-test-v1` into both ModelManager and its telemetry recorder. The profile exports `enforceAvailableMemoryFloor=false` and bypasses loading, post-load and monitored-generation RAM floors only while Android reports `lowMemory=false`. OS pressure still interrupts on the first monitor sample; thermal checks, minimum total RAM, required resource/pressure-threshold signals, cleanup and quarantine remain intact. No CPU fallback or automatic retry was added.

Normal debug/release and default QA builds retain the 3 GiB baseline. Experimental selection requires debug + `secureQa` + the explicit build flag; the harness always sets the flag explicitly and rejects mismatched exported admission modes. The installed experimental QA APK retains this policy until replaced by a default QA build. False OS pressure is not an allocation guarantee or calibration approval.

Verification after this change:

- Passed: 16 host-script tests, 70 shared JVM tests, 55 shared Android-host tests, 7 debug app unit tests and 7 experimental secure-QA app unit tests. Debug/secure-QA lint both reported 0 errors and 23 warnings. Debug and experimental QA app/test APK builds, shell syntax and diff whitespace checks passed.
- New regressions cover both admission stages below the baseline floor, immediate OS-pressure cancellation/release, thermal/unknown/unsupported-device rejection, all internal-build selection conditions, telemetry/profile identity and host/app mode matching. Synthetic wrapper tests verify explicit true/false Gradle flag propagation.
- Passed S24 smoke run: `benchmarks/results/chat-20261015T014915Z-OPqsDi/` (ignored), run ID `315c66bc-fbe1-43e7-8cca-d2309cf1b738`. Command used `MEMORY_CYCLES=3 MEMORY_SUSTAINED_MINUTES=0` with `--android-pressure`. All three real GPU load/chat/cancel/unload cycles completed; each recovery observation recorded `UNLOADED`. Instrumentation passed in approximately 59 seconds, and the analyzer reported `workloadPassed=true`, no issues, and `acceptanceComplete=false`.
- All 144 resource samples reported `lowMemory=false` and thermal status 0, with no failed/dropped samples. All required telemetry stages were present and identified the experimental profile. The thermal supervisor's highest skin sample was 37.2 C. Final own-package exit evidence matched the run and followed cleanup, with no relevant confirmed low-memory kill, native crash, ANR or unclassified SIGKILL reported.
- Five-second post-release PSS observations were 528,616,448; 555,494,400; and 492,494,848 bytes. The final later sample was 172,443,648 bytes; peak sampled PSS was 2,139,973,632 bytes. These observations are not a leak diagnosis or proof of long-term memory safety.
- Native prefill/decoding samples in this smoke run stayed above 3 GiB (minimum 3,347,177,472 bytes). The run verifies experimental selection and native cycles, but does not demonstrate native generation below the old gate; that behavior is covered by deterministic policy/manager tests.

At the smoke-test checkpoint, the five-cycle/20-minute sustained workload remained outstanding; it was subsequently completed as recorded below. A full permission/lifecycle QA matrix and native fault injection of Android lowMemory pressure remain outstanding.

## Full Sustained GPU Test

Passed on the attached S24 Ultra on 2026-10-15 using `memory-android-pressure-test-v1`, GPU only, 2048 context tokens and 512 reserved output tokens. No app/script code or policy thresholds changed during this run. Raw metadata reports: `benchmarks/results/chat-20261015T015540Z-Pz6qnT/` (ignored), run ID `27f34e3e-b1d9-466d-a8d1-2dcaa1669f3d`.

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ANDROID_SERIAL=DEVICE_ID MEMORY_CYCLES=5 MEMORY_SUSTAINED_MINUTES=20 \
  bash scripts/verify-memory-reliability.sh --android-pressure
```

- Build/install, initial policy/exit probes, three fresh Android preflight probes, native instrumentation, final exit probe and host analysis passed. Script exit 0; analyzer `workloadPassed=true`, no issues. The full native test ran from 01:56:02.689 to 02:17:43.771 UTC (1301.082 seconds), including initial cycles, 20 sustained minutes and final cleanup.
- All five initial load/chat/cancel/unload cycles completed. The sustained phase completed 501 requests: 401 completed replies and 100 expected user cancellations. It also performed its configured periodic unload/reload after every ten sustained requests. These counts exclude the five completed and five cancelled requests in the initial cycles.
- All 4,183 memory samples were valid, with zero failed/dropped samples, all seven required stages and a consistent experimental policy ID. Every sample reported `lowMemory=false`. Generation ran below the old 3 GiB floor: 11 prefill and 10 decoding samples were below it, with minimum generation available RAM of 3,081,891,840 bytes (approximately 2.87 GiB). These are sample counts, not counts of distinct requests below the floor.
- Peak sampled app PSS was 2,168,888,320 bytes (approximately 2.02 GiB); peak sampled RSS was 1,018,318,848 bytes. Five-second PSS recovery observations for the initial cycles were 551,524,352; 541,132,800; 535,300,096; 537,887,744; and 550,659,072 bytes, not strictly increasing. Across 56 post-release trace groups sampled at least 4.5 seconds after release, the first-ten median was 545,879,552 bytes and the last-ten median was 457,064,960 bytes. Final PSS was 437,889,024 bytes (approximately 418 MiB), with `UNLOADED` and no engine owned. This run does not establish absence of leaks or an allocation headroom guarantee.
- Host thermal supervision recorded 257 samples, a maximum skin temperature of 41.0 C and a maximum thermal status of 1 (light). There was no thermal safety stop. The final own-package exit probe succeeded, matched the run and was captured after cleanup; no relevant confirmed low-memory kill, native crash, ANR or unclassified SIGKILL was reported.
- Battery endpoints were both 41%, with USB power/charging reported at both endpoints; battery temperature rose from 34.5 C to 38.2 C. These observations cannot establish unplugged drain or energy consumption. Device battery metadata and derived review metrics are retained alongside the raw run reports; no prompts or responses were exported.

An earlier attempt, `benchmarks/results/chat-20261015T015411Z-BivhCQ/`, was interrupted by a brief ADB disconnect and failed at instrumentation with exit 255. The phone reconnected, but that run had lost host thermal supervision and could not provide valid final exit evidence. The QA app was manually force-stopped, its absence verified, and partial synthetic telemetry recovered separately before retrying. That attempt is excluded from acceptance. It exposes a harness limitation: an ADB disconnect can leave device-side instrumentation running after host supervision exits; automated device-local handling of host loss remains unimplemented.

The successful retry completes this supervised sustained workload, including measured generation below the old gate. It does not promote the experimental profile into normal app builds: `acceptanceComplete=false` remains intentional. Normal-use calibration, pressure fault injection, wider lifecycle/permission checks and host-disconnect hardening remain separate follow-up work. No baseline floor was changed.

## Commands

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ANDROID_SERIAL=DEVICE_ID bash scripts/verify-memory-reliability.sh

JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ANDROID_SERIAL=DEVICE_ID bash scripts/verify-memory-reliability.sh --android-pressure

node --test scripts/tests/chat-resource-preflight.test.mjs scripts/tests/memory-reliability.test.mjs
```

The supervised script installs only isolated QA APKs, exports policy/pressure/exit metadata, and checks current resources before starting the thermal supervisor and native test. Result directories are unique; blocked/failed attempts are retained. Missing native reports or exits cannot turn an instrumentation success into a clean workload result. Reports include an explicit `acceptanceComplete=false`: retained-memory interpretation and reviewed calibration remain required even after workload completion.

QA-process restarts and instrumentation exits can themselves appear in exit metadata; reason codes are retained rather than automatically attributing every exit to memory. PSS/RSS are sampled app metrics, not all-device allocation peaks. The bounded recorder and test framework contribute to measured PSS. No artificial cache clearing or forced collection is used to manufacture recovery.
