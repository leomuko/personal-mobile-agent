# V4 Explicit Android-Pressure Profile Amendment

Date: 2026-10-22. Scope: isolated secure-QA experiment only.

## User Decision

After [the initial baseline block](P2_V4_FEASIBILITY_RESULTS.md) and a separately
authorized [baseline retry](P2_V4_FEASIBILITY_RETRY_1.md), the user requested:
"let your only preload requirement be the check for if lowmemory=true".

Use the existing shared `MemoryPolicies.AndroidPressureTest` profile, identity
`memory-android-pressure-test-v1`. This is the previously scoped QA experiment,
not a new production policy. Fixed available-RAM floors and the additional
supervised load reserve no longer gate this QA run. Android lowMemory=true blocks
admission and the first active monitor sample with lowMemory=true cancels work.
Required/valid resource signals, supported-device checks, thermal/skin stops,
serial allocation, out-of-band cancellation, cleanup and quarantine remain active.

Ordinary debug/release continue to use the existing baseline policy. The main
policy constants, ordinary DI selection and five-second search are unchanged.
The 30-second V4 decision/cleanup ceiling, exact eight-pack sequence, 2048/512
Gemma budget, encoder admission and stop-at-first-block rule remain unchanged.

## Implementation And Binding

- The host profile exporter selects the actual shared policy explicitly with
  `P2_V4_PROFILE=android-pressure`. No separately maintained threshold policy.
- Secure-QA APKs use `-PandroidPressureMemoryTest=true`. V4 checks that the build
  flag, manager profile and frozen plan agree; no baseline/pressure mismatch is
  accepted. The wrapper requires explicit `--android-pressure` for this freeze.
- Profile identity and mode are part of the plan, source snapshot, freeze and
  separate device authorization. The new explicit approval is in
  `planning/fixtures/serial-verification-v4/pressure-authorization.json`.
- Both on-device continuation and host evidence validation apply the selected
  profile. They do not falsely reintroduce a 3 GiB success requirement for the
  pressure experiment. Missing/negative memory observations, lowMemory=true,
  thermal violations, incomplete telemetry and uncertain cleanup still fail.

Both baseline attempts and their raw evidence/freezes/ledgers remain unchanged.
The new profile requires its own reviewed freeze and one retained attempt; it
cannot retroactively change either earlier blocked result. No automatic repeated
attempts, personal-data import, semantic enablement or broader campaign is allowed.

## Host Verification

Passed: 84 Node tests, shared JVM and Android-host tests, debug/QA app unit tests,
debug assembly/lint and pressure-profile QA app/test assembly. New regressions
exercise low-RAM/OS-false acceptance, baseline-floor preservation, OS-pressure,
invalid-signal and thermal rejection. Independent profile review passed with no
actionable findings; its receipt is `docs/P2_V4_PRESSURE_REVIEW.json`. The
[subsequent S24 attempt](P2_V4_PRESSURE_RESULTS.md) passed memory admission and
ran one GPU request, then correctly stopped at invalid JSON output. Seven requests
were not run. This does not enable semantic search or establish model accuracy.

The existing pressure profile retains platform compatibility/resource-signal
requirements; "only lowMemory" refers to the **memory-headroom admission rule**,
not permission to disable thermal, ownership, corruption or cleanup checks.
