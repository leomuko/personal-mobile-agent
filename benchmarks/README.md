# Device and Model Measurements

Gemma 4 E2B / LiteRT-LM 0.17.1 has an internal adapter, verified importer, Models panel, and opt-in native harness. Native emulator and S24 smoke have passed, but full S24/Phase 1 acceptance has not. See [current evidence](../docs/PHASE_1_IMPLEMENTATION_RESULTS.md) and the [Phase 1 protocol](../planning/PHASE_1_PLAN.md). Speech/embedding engines remain separate selections.

Develop on the designated emulator first, then run a separate session with the S24 Ultra connected and USB debugging authorized. For either target, select its actual device ID explicitly:

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
adb devices -l
node scripts/device-report.mjs DEVICE_ID
./gradlew :androidApp:assembleSecureQa
adb -s DEVICE_ID install -r androidApp/build/outputs/apk/secureQa/androidApp-secureQa.apk
```

Device reports omit device serial numbers and are stored in ignored `benchmarks/results/`. They distinguish emulators from hardware and mark model measurements `not_run`. Node.js is used by host reporting/supervision scripts, not to build the app.

Open the isolated QA app, then Settings > Local models (internal) > Import and select the pinned model. The importer enforces 2,588,147,712 bytes and the trusted SHA-256, with expected size plus 512 MiB free-space reserve. A provider-side copy needs its own storage too. The ignored host file `models/gemma-4-E2B-it.litertlm` matched the pin on 2026-10-02. Never commit model weights or personal app storage.

Admission requires at least 7 GiB usable device RAM (allowing reservations on nominal 8 GB devices), at least 3 GiB available before load/generation, no low-memory signal and a known thermal state below SEVERE. These are provisional policies, not measured compatibility claims. The dedicated AVD was cold-booted with the user's approval into 8 GB RAM; its existing data partition remains about 6 GB despite the saved 16 GB setting. No data was wiped. Avoid keeping two full model copies on that partition; developer staging directly into the private immutable path is acceptable for the initial native spike, with startup hash verification still required.

During generation the same 3 GiB available-memory floor is sampled once per second. Three consecutive below-floor samples cancel generation; recovery resets the counter. Android `lowMemory`, unknown resource signals and severe thermal pressure cancel on the first monitoring sample. This revised monitor needs new native evidence; earlier benchmark numbers were measured under the earlier policy.

The [P1 memory-reliability work package](../planning/P1_MEMORY_RELIABILITY_PLAN.md) centralizes these unchanged values in `MemoryPolicies.Current` (`memory-baseline-v1`, not calibrated). The installed QA app exports the profile to the host; native-script thermal supervision now requires that export. Post-load memory rejection closes retained engines before retry. The allocation-cycle workbench records Android pressure thresholds separately from app PSS/RSS and system available RAM:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ANDROID_SERIAL=DEVICE_ID bash scripts/verify-memory-reliability.sh
```

Default workbench: GPU, 5 cycles and 20 sustained minutes. `MEMORY_SUSTAINED_MINUTES=0` is a smoke-only option. The default conversation/memory harness retains its 5.5 GiB supervised pre-load gate. Calibration needs a reviewed replacement profile; see [current evidence and blocked acceptance](../docs/P1_MEMORY_RELIABILITY_RESULTS.md).

For the explicitly requested Android-pressure preflight experiment:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ANDROID_SERIAL=DEVICE_ID bash scripts/verify-memory-reliability.sh --android-pressure
```

This opt-in mode skips the host's available-RAM floor and load reserve only after three fresh Android probes report `lowMemory=false`. It also builds the isolated `secureQa` app with `-PandroidPressureMemoryTest=true`, selecting `memory-android-pressure-test-v1`. That test profile bypasses the app's available-RAM floors for loading, post-load admission and in-flight generation while Android reports `lowMemory=false`. OS pressure still cancels on the first monitor sample; missing pressure thresholds/resources, insufficient total RAM and unsafe thermal conditions still block/stop. No automatic fallback/retry is added. False OS pressure is not a guarantee that the next native allocation will succeed.

The profile exports `enforceAvailableMemoryFloor=false`; every sample identifies its policy, and run/result metadata record `preflightMode=android-pressure`. Host preflight rejects a different installed admission mode. Regular debug/release builds cannot select this profile; the default harness explicitly builds `secureQa` with the experiment disabled. An experimental QA APK retains this profile until replaced by a default QA build, so use it only for supervised tests. Neither a workload pass nor this override approves normal-use calibration.

## Opt-In Native Harness

```sh
ANDROID_SERIAL=DEVICE_ID MODEL_BACKEND=CPU bash scripts/verify-model-runtime.sh
ANDROID_SERIAL=DEVICE_ID MODEL_BACKEND=GPU MODEL_REPETITIONS=20 bash scripts/verify-model-runtime.sh
```

The script installs only the isolated QA packages and runs `ModelRuntimeTest` with `nativeModel=true`. Missing assets or unsafe resources are reported as failures, not fake passes. The ordinary secure-QA script excludes this opt-in test. Direct instrumentation skips it unless requested. No permissions, emulator state or device reboot are changed by the native script.

Native QA now requires thermal supervision plus a run-correlated device-local lease; `SUPERVISED_THERMAL=false` is rejected. Use the wrappers rather than supplying `supervisedThermal=true` alone to raw instrumentation. A healthy thermal sample renews a private heartbeat using the device boot clock. Lost/stale supervision requests cleanup and terminates only the isolated QA process, with an independent emergency stop if cleanup hangs. Normal app behavior and memory policies are unchanged. Host/model-memory reports require fresh closed-lease evidence, not just nonempty output. See [supervision implementation and synthetic S24 evidence](../docs/P2_SUPERVISION_RESULTS.md).

Supervision protocol 2 isolates heartbeat, stop and status files by immutable run ID. Host stops publish a scoped request, never a package-wide `force-stop`; device ownership remains locked through cleanup. Rebuild/reinstall matching QA app/test APKs before using these wrappers. Schema-1 supervision proof cannot satisfy the revised gate. Targeted real native initialization/prefill/decoding loss checks and fresh-process recovery passed on the S24 with the explicitly authorized QA pressure profile; see [native phase-loss evidence and limitations](../docs/P2_NATIVE_SUPERVISION_RESULTS.md). Physical transport/host-failure cases and the broader native acceptance matrix remain separate.

Devices/emulators without trusted thermal-status and skin readings now block this native wrapper; use fake-driven tests rather than bypassing supervision. Historical pre-lease reports retain their historical verdicts but do not satisfy the new supervision gate.

Reports under ignored `benchmarks/results/` include APK hash, Git HEAD/tracked diff digest, actual backend/device class (not serial), model/runtime/configuration pins, raw cold/warm times, actual input/output token counts, SDK decode rate, cancel and idle/active release latency, PSS samples every 500 ms, thermal, battery and charging state. Prompts are fixed synthetic fixtures and independent native conversations; generated text and personal chat are not exported. Cancel is followed by another prompt and release/reload. Cold timing excludes asset verification, which warms filesystem pages; OS cache state is explicitly uncontrolled.

Nearest-rank p95 is reported only for 20+ samples. Short runs have null acceptance gates. Failures retain partial evidence; the lease watchdog protects supervised QA from host loss, not every hang while the host continues renewing a healthy lease. Native cancellation/recovery acceptance remains separate because coroutine timeouts alone cannot kill native code. A completed smoke is not full acceptance. The Models panel exposes latest single-prompt metrics; repeated benchmark execution/export is script-driven for now.

An opt-in **synthetic mixed loop** alternates two bounded prompt fixtures with cancellation and reload cycles. It still does not exercise near-context or true multi-turn behavior:

```sh
ANDROID_SERIAL=DEVICE_ID MODEL_BACKEND=GPU MODEL_SUSTAINED_MINUTES=20 \
  SUPERVISED_THERMAL=true bash scripts/verify-model-s24.sh
```

The manager blocks unsafe resource admission; the S24 wrapper separately monitors Android thermal status and HAL skin temperature throughout and stops the isolated QA app at status >= 3, skin >= 45 C, or missing skin readings. The JSON harness records skin as unavailable, so pair it with the wrapper's thermal CSV. Do not substitute battery temperature. USB-powered percentages do not establish energy efficiency; paired idle/active baselines remain physical QA work.

The dedicated S24 session measures CPU/GPU separately with automatic fallback disabled. E4B is deferred until E2B viability. Remaining coverage includes free-form preflight tokenization, near-context and true multi-turn cases, prefill-specific cancellation, full provider import, offline/reboot checks, paired idle/active battery, and post-release memory trends. `workloads.json` remains synthetic quality fixtures. Do not extrapolate emulator performance to the phone or label emulator results as S24 measurements.

See the [Phase 1 plan](../planning/PHASE_1_PLAN.md) for explicit thresholds, percentile sample counts, and the mixed 20-minute workload. Stop at thermal status >= 3 or measured skin >= 45 C; skin may only be available to an authorized test harness. Missing values are unavailable, not zero. Battery measurements require repeated paired idle runs on the phone, with charging state recorded. Keep all results tied to the model hash, runtime revision, device software and workload. A safety-aborted run is not a completed sustained gate.
