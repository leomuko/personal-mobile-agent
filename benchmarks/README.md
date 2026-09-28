# Phase 0 Measurements

No model performance results are claimed by this scaffold. The language/speech/embedding engines have not yet been integrated or selected. The app exposes its actual device identity in Settings. The shared module contains the inference boundary and a validated benchmark-sample type.

With the S24 Ultra connected and USB debugging authorized:

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
adb devices -l
node scripts/device-report.mjs DEVICE_ID
./gradlew :androidApp:installDebug
```

Device reports omit device serial numbers and are stored in ignored `benchmarks/results/`. They distinguish emulators from hardware and mark model measurements `not_run`. Node.js is needed only for the optional report script, not to build the app.

Next experiments: integrate a pinned llama.cpp JNI backend, choose two licensed 1B-4B model candidates, verify model hashes and record their chat templates, then run `workloads.json` on the S24 Ultra. Compare warm/cold response, resource use, and quality. Run speech and embedding experiments separately. Do not extrapolate emulator performance to the phone.

See the [implementation plan](../planning/IMPLEMENTATION_PLAN.md) for target thresholds and the mixed 20-minute thermal workload. Battery measurements require repeated paired idle runs on the phone. Keep all results tied to the model hash, runtime revision, device software and workload.
