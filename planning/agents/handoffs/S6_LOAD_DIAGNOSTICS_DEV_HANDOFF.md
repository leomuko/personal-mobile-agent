# S6 load-failure diagnostics (test-only)

2026-11-23. From the planner to the dev agent. On 2026-11-23 the user
authorized this fix and one tuning×1 rerun after QA.

## What happened

Two S24 tuning×1 runs failed identically, about 12–14 s into the first
`manager.load(GPU)` call, at `check(resident(...))` with "4096/512 GPU load
failed". No item ran. The run directories are:
- `benchmarks/results/answer-quality-tuning-20261123T092429Z-37203-rkcAuT`
- `benchmarks/results/answer-quality-tuning-20261123T092517Z-37475-hrOwYu`

The report does not say why the load failed. It records nothing about the
manager's state, its error or command error, the engine load count, or memory
at the load stages.

The planner's hypothesis is unconfirmed: `memoryPolicy.blockedReason(...,
GENERATION)` rejects the load after it completes (floor 3 GiB; baseline
available 4.53 GB), and `cleanupOwnedEngine` follows. Other possibilities:
- the LOADING block;
- `assets.verify()` returning null;
- an engine load error;
- `resident()` failing on `loadedContextTokens` or `loadedOutputTokens`.

## Task: diagnostics only

On any load that is not resident, record the following in
`answer-quality.json` before failing:
- the full `manager.state.value`: status, backend, error, commandError,
  loadedContextTokens, loadedOutputTokens, cleanupError, loadMs;
- `engine.loads` before and after;
- the delegate engine's `load` Result failure type, if any;
- memory snapshots. Record `resources.snapshot()` (available bytes, lowMemory,
  thermal) immediately before the load and immediately after it fails. If
  practical, also wire the `memoryTelemetry` stage recorder, as
  `ChatContextMeasurementTest` does, so BASELINE, LOADING and LOADED_IDLE
  appear.

Also:
- Make the failure message name the observed status and error, for example
  `"4096/512 GPU load failed: BLOCKED LOW_MEMORY"`.
- Record a field that says which gate failed, when it can be told from the
  state.
- Compare with `ChatContextMeasurementTest` and note any construction
  difference that could matter: `appManager.assets` vs koin `ModelAssets`, the
  `manifest` override, `idleMs`, and the missing `memoryTelemetry`. Do **not**
  change behaviour to make the load pass. Report the differences.

## Boundaries

- Allowed paths:
  - `AnswerQualityTest.kt`;
  - `support/AnswerQualityFixture.kt`, only if needed;
  - `scripts/answer-quality.mjs` and its tests, only if the report schema check
    needs the new fields;
  - an appended dated note in `docs/P2_ANSWER_QUALITY_RESULTS.md`.
- **The fixture is frozen** (lock `c07435f8…`). Do not touch anything under
  `planning/fixtures/answer-quality-v1/`.
- No production change. No change to the memory policy, the profile, the
  backend or retries. If the cause is visible from code alone, report it; do not
  fix it.
- Host only. No real adb or device runs. No Git writes. One Gradle invocation
  at a time. Do not edit `build/qa-*` or the run directories.

## Checks

Run the same commands as the base handoff, and confirm that the existing
`measure`, `sqlcipher` and `ui` modes are unchanged.

## Report back

- What changed, with sha256 values.
- The new report fields.
- The construction differences from `ChatContextMeasurementTest`.
- Your reading of which gate most likely failed, labelled as code reading, not
  evidence.
- Check results with counts.
