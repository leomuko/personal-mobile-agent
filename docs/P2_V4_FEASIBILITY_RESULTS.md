# P2 V4 S24 Attempt: Blocked At Resource Preflight

Date: 2026-10-22. Status: **BLOCKED, not a native feasibility pass or failure**.

## Scope And Authorization

The user approved the 30-second, eight-request experiment, later authorized S24
execution, and then confirmed the phone was unlocked. The implementation/host-QA
review passed before the execution freeze. The exact code, APKs, calibration packs,
counting proof, acceptance checks and baseline profile were revalidated before
the attempt. No frozen source, artifact, profile or authorization was changed.

Freeze SHA-256:
`c258ce5d9faaebe38c1d95432d646959b1a83918064bc61494571e2c58011403`.
Source snapshot SHA-256 (287 files):
`3d24d57dbc30c3f12839514551905d603e50481af29b280bbaff8039821451c2`.
Device: designated Samsung SM-S928B, serial `R5CX22V8D6B`, unlocked/awake at
pre-admission inspection. No unrelated application or personal data was inspected.

## Retained Attempt

Folder: `benchmarks/results/p2-v4-feasibility-gate-kgPM6M/`.
Run: `a47411d2-1941-4ce8-9530-a05fedc1e458`.
Resource profile: `memory-baseline-v1`, unchanged from the reviewed freeze.

The frozen QA app/test APKs were installed with replacement, preserving app data.
The shared-policy and own-package exit-metadata probes passed: `OK (2 tests)`.
Fresh policy fields matched the freeze. The bounded resource preflight then
returned `LOAD_HEADROOM`; the wrapper exited **2**, `status=blocked`.

| Observation | Result |
| --- | --- |
| Required available RAM before loading | 5.5 GiB (3 GiB floor + 2.5 GiB reserve) |
| Preflight available RAM, 14 samples | 3.2992-3.3478 GiB |
| Android `lowMemory` flag | false |
| Android thermal status | 0 throughout |
| Sampled skin temperature | 32.5-32.8 C |
| Native V4 requests started/completed | 0 / 0 |
| Planned requests not run | 8 |
| Encoder/Gemma loading, generation, verifier validation | Not reached |
| Retry, profile switch or threshold relaxation | None |

The preflight samples ran from 20:48:11.918 to 20:48:26.896, Africa/Kampala
(UTC+03:00). Their raw timestamps remain UTC. The entire wrapper attempt lasted
23893 ms, including APK installation/probes/preflight; that is **not** a V4
request latency or an observation against the 30000 ms inference deadline.

The earlier Android API baseline snapshot reported 3464994816 available bytes,
with `lowMemory=false` and pressure threshold 591396864 bytes. The subsequent
preflight range above is based on `/proc/meminfo`; these are distinct observations,
not a retained-memory or leak measurement. Android's false pressure flag does not
waive this freeze's project-defined 5.5 GiB admission gate.

## Evidence Interpretation

- Passed: host-only freeze admission, designated unlocked-device check, APK
  installation and the two metadata/policy probes.
- Blocked: baseline loading headroom before any model request was admitted.
- Not run: all eight V4 requests, encoder/Gemma allocation sequencing, verifier
  output, 30-second decision/cleanup feasibility, native cancellation or recovery.
- The raw result's `nativeEncoder=true` and `gemmaWorkload=true` describe the
  **planned workload**, not proof that either runtime ran. No encoder result,
  V4 instrumentation output, runtime telemetry or closed supervision is claimed:
  the wrapper stopped before starting that collector and supervisor.
- This is policy rejection, not an observed OOM crash, accuracy failure, latency
  overrun or proof that the S24 cannot run the model.

Retained raw files include `result.json`, `preflight.json`, `memory-baseline.json`,
`memory-policy.json`, `policy-probe.txt`, APK hashes, source hashes, the execution
freeze and separate authorization. The immutable attempt ledger is
`benchmarks/results/v4-execution-ledger/c258ce5d9faaebe38c1d95432d646959b1a83918064bc61494571e2c58011403.json`.
It was not removed or rewritten to permit another attempt.

## Next Decision

No automatic retry or change to the isolated Android-pressure profile was made.
A further attempt needs explicit authorization and reviewed preparation; choosing
another profile requires a correspondingly reviewed implementation/freeze, not
editing this record or bypassing the ledger. Ordinary app policy remains unchanged.

V3's failure remains historical and unchanged. Larger/development/held-out
campaigns and semantic enablement remain blocked. No code changes or commits were
made during this device-test turn; only outcome documentation was updated.
