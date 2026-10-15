# P1 Memory Reliability

Accepted 2026-10-15. This work package precedes P2; it does not change the product scope or replace P1B conversation ownership. Cleanup, telemetry and the versioned baseline policy are implemented. The authorized Android-pressure test profile passed five GPU cycles and the full 20-minute sustained workload (501 sustained requests); normal-use calibration/acceptance remains incomplete. See [results](../docs/P1_MEMORY_RELIABILITY_RESULTS.md), including an interrupted first attempt and the host-disconnect supervision limitation.

## Order and Ownership

1. Cleanup and regression tests: ModelManager owns native handles and serializes cleanup with the existing operation mutex. A rejected post-load attempt closes its engine before publishing its terminal outcome. Admission remains BUSY until cleanup finishes. Failed or uncertain close quarantines the manager and requires process restart. Never await the public release method from inside its operation lock.
2. Allocation telemetry: shared code emits lifecycle/ownership markers; Android supplies pressure thresholds, available RAM, app PSS/RSS and thermal status. QA recording is opt-in, bounded and excludes prompts, responses, files, keys and heap/exit traces. Record baseline, loading, loaded idle, prefill, decoding, cancellation and post-release stages.
3. Shared policy: MemoryPolicies.Current is the authoritative versioned profile. Loading and generation rules are explicit. Android probes export the installed app's profile as JSON; host preflight and thermal supervision consume that export rather than separate numeric constants.
4. S24 calibration: start on GPU with repeated load/chat/cancel/unload cycles, five-second recovery observations and a 20-minute bounded multi-turn workload. Preserve blocked/failed attempts, retained-memory observations and classified exits. No automatic CPU fallback or inference retry.

## Unchanged Baseline

Profile: `memory-baseline-v1`, not calibrated.

- Loading floor: 3 GiB available.
- Generation floor: 3 GiB available, including the post-load check.
- Supervised pre-load reserve: an additional 2.5 GiB; 5.5 GiB total before loading.
- Total device RAM: at least 7 GiB usable.
- Generation polling: once per second; three consecutive low-available-memory samples trigger cancellation, with recovery resetting the counter.
- OS lowMemory, unknown required signals and severe thermal status trigger cancellation on the first generation-monitor sample, without that debounce.
- Thermal supervisor: stop at status 3 or skin 45 C; missing signals block/stop rather than pass.

The 2.5 GiB reserve is inherited from the previous PSS target and is not a measured allocation bound. This work makes that assumption versioned and visible, not validated. Android pressure thresholds are recorded but do not replace tested growth reserves. Linux MemAvailable preflight samples are advisory; app admission uses fresh Android MemoryInfo. The host's lowMemory flag is explicitly identified as the initial Android-probe snapshot, not a live callback stream.

## Calibration Gate

### Authorized Test-Only Override

On 2026-10-15, the user authorized bypassing the host and app available-memory floors for supervised testing while Android reports `lowMemory=false`. `verify-memory-reliability.sh --android-pressure` now builds only the isolated `secureQa` variant with `androidPressureMemoryTest=true`. Shared profile `memory-android-pressure-test-v1` exports `enforceAvailableMemoryFloor=false`; numeric baseline floors remain recorded but are not enforced for loading, post-load admission or monitored generation. Unknown required signals (including a missing/invalid Android pressure threshold), minimum total RAM and thermal checks remain enforced. OS `lowMemory=true` cancels on the first monitoring sample. Cleanup/quarantine behavior is unchanged.

The default app profile remains `memory-baseline-v1` with the 3 GiB gates. Experimental selection requires all of debug, `secureQa` and the explicit build flag; the harness rebuilds without that flag for baseline runs. The experimental APK remains experimental until replaced. This authorization permits measurement, not normal-use calibration or a safety claim; native OOM/crash risk remains despite `lowMemory=false`.

Keep baseline values until measurements support an explicitly reviewed replacement profile. Candidate profiles must be tied to model SHA, runtime, backend, device/software, context and output limits. Do not lower safeguards merely to obtain a pass.

Require:

- Unit evidence for rejected-load cleanup, close failure/timeout, retry during cleanup, cancel/release races, cancellation during suspended prefill telemetry without retained pressure polling, and stage-specific policy boundaries.
- Android probe evidence for exported-profile identity and valid pressure/PSS/RSS samples.
- Confirmed release after each native cycle, no duplicated closes or reuse of quarantined handles.
- Retained-memory analysis across repeated and sustained workloads, distinguishing allocator/page-cache behavior, telemetry overhead and system-wide pressure from native resource retention. Exact recovery of global available RAM is not required and no leak is inferred from a single delta.
- Exit evidence correlated to the host-generated run ID and the workload's start/completion times. The final probe must succeed, identify the after phase and capture its snapshot after workload cleanup; probe failure or an older file cannot pass via stale evidence. Known low-memory kills, native crashes and ANRs are distinct from ambiguous SIGKILL; missing/ambiguous evidence cannot certify clean acceptance.
- Successful supervised native evidence with complete telemetry, no silent fallback/retry and a separately reviewed memory-growth policy. A script's workload pass does not itself approve calibration.

Coroutine close timeouts are cooperative. Blocking native close cannot safely be forcibly interrupted/reused in-process; retain quarantine and require separate process recovery when necessary.

Memory/source features remain P2. OCR/receipt understanding remains P5. Deferred audio consent/dictation behavior is unchanged.
