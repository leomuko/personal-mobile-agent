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

Device reports omit device serial numbers and are stored in ignored `benchmarks/results/`. They distinguish emulators from hardware and mark model measurements `not_run`. Node.js is needed only for the optional report script, not to build the app.

Open the isolated QA app, then Settings > Local models (internal) > Import and select the pinned model. The importer enforces 2,588,147,712 bytes and the trusted SHA-256, with expected size plus 512 MiB free-space reserve. A provider-side copy needs its own storage too. The ignored host file `models/gemma-4-E2B-it.litertlm` matched the pin on 2026-10-02. Never commit model weights or personal app storage.

Admission requires at least 7 GiB usable device RAM (allowing reservations on nominal 8 GB devices), at least 3 GiB available before load/generation, no low-memory signal and a known thermal state below SEVERE. These are provisional policies, not measured compatibility claims. The dedicated AVD was cold-booted with the user's approval into 8 GB RAM; its existing data partition remains about 6 GB despite the saved 16 GB setting. No data was wiped. Avoid keeping two full model copies on that partition; developer staging directly into the private immutable path is acceptable for the initial native spike, with startup hash verification still required.

During generation the same 3 GiB available-memory floor is sampled once per second. Three consecutive below-floor samples cancel generation; recovery resets the counter. Android `lowMemory`, unknown resource signals and severe thermal pressure cancel on the first monitoring sample. This revised monitor needs new native evidence; earlier benchmark numbers were measured under the earlier policy.

## Opt-In Native Harness

```sh
ANDROID_SERIAL=DEVICE_ID MODEL_BACKEND=CPU bash scripts/verify-model-runtime.sh
ANDROID_SERIAL=DEVICE_ID MODEL_BACKEND=GPU MODEL_REPETITIONS=20 bash scripts/verify-model-runtime.sh
```

The script installs only the isolated QA packages and runs `ModelRuntimeTest` with `nativeModel=true`. Missing assets or unsafe resources are reported as failures, not fake passes. The ordinary secure-QA script excludes this opt-in test. Direct instrumentation skips it unless requested. No permissions, emulator state or device reboot are changed by the native script.

Reports under ignored `benchmarks/results/` include APK hash, Git HEAD/tracked diff digest, actual backend/device class (not serial), model/runtime/configuration pins, raw cold/warm times, actual input/output token counts, SDK decode rate, cancel and idle/active release latency, PSS samples every 500 ms, thermal, battery and charging state. Prompts are fixed synthetic fixtures and independent native conversations; generated text and personal chat are not exported. Cancel is followed by another prompt and release/reload. Cold timing excludes asset verification, which warms filesystem pages; OS cache state is explicitly uncontrolled.

Nearest-rank p95 is reported only for 20+ samples. Short runs have null acceptance gates. Failures retain partial evidence; a native hang may require separately authorized process recovery because coroutine timeouts do not kill native code. A completed smoke is not full acceptance. The Models panel exposes latest single-prompt metrics; repeated benchmark execution/export is script-driven for now.

An opt-in **synthetic mixed loop** alternates two bounded prompt fixtures with cancellation and reload cycles. It still does not exercise near-context or true multi-turn behavior:

```sh
ANDROID_SERIAL=DEVICE_ID MODEL_BACKEND=GPU MODEL_SUSTAINED_MINUTES=20 \
  SUPERVISED_THERMAL=true bash scripts/verify-model-s24.sh
```

The manager blocks unsafe resource admission; the S24 wrapper separately monitors Android thermal status and HAL skin temperature throughout and stops the isolated QA app at status >= 3, skin >= 45 C, or missing skin readings. The JSON harness records skin as unavailable, so pair it with the wrapper's thermal CSV. Do not substitute battery temperature. USB-powered percentages do not establish energy efficiency; paired idle/active baselines remain physical QA work.

The dedicated S24 session measures CPU/GPU separately with automatic fallback disabled. E4B is deferred until E2B viability. Remaining coverage includes free-form preflight tokenization, near-context and true multi-turn cases, prefill-specific cancellation, full provider import, offline/reboot checks, paired idle/active battery, and post-release memory trends. `workloads.json` remains synthetic quality fixtures. Do not extrapolate emulator performance to the phone or label emulator results as S24 measurements.

See the [Phase 1 plan](../planning/PHASE_1_PLAN.md) for explicit thresholds, percentile sample counts, and the mixed 20-minute workload. Stop at thermal status >= 3 or measured skin >= 45 C; skin may only be available to an authorized test harness. Missing values are unavailable, not zero. Battery measurements require repeated paired idle runs on the phone, with charging state recorded. Keep all results tied to the model hash, runtime revision, device software and workload. A safety-aborted run is not a completed sustained gate.
