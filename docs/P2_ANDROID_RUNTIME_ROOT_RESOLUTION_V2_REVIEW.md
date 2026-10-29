# Independent QA — root resolution and emulator-first handoff

2026-10-29. **PASS for the bounded implementation and plans.**
The unamended Android runtime route is **not build-ready**. This closes the
source-resolution slice through its documented route-rejection outcome, not
through a complete build recipe. P2 is incomplete; semantic search is disabled.

The implementer transcribed the returned verdicts of the existing primary QA
agent `01a16d6c-1a21-7923-af3d-18ec99667728`. This record is outside the reviewed
lock to preserve its bytes and is not a self-issued execution approval.

## Reviewed identity and findings

- Branch `dev`, HEAD `b35e172eb121c347c5251d58bde80cad4f46bfb2`.
- Initial six-file resolution lock:
  `c0ee6137fd8a17c36f3f08f1cbd10507b494482bb87126b5258a7e7c5fc49fe9`.
- QA found no blocking defects. One nonblocking documentation link returned 404.
- The original lock, document and verdict were retained under
  `build/android-runtime-resolution-v2/review-1/` before correcting only that link.
- Follow-up QA confirmed the exact one-link change, unchanged other five bindings
  and correct previous-lock binding. **Final PASS**, revision-2 lock SHA-256:
  `305a88e4731855fec95bbc2c165111926582864a24d9b4cdfa953c26baed6076`.

## Verification

**Independent QA:** fresh 14-test rerun passed; all 64 source files verified
against hashes and pinned tree/directory metadata; normal and optimized-Python
inspection both returned exit 2 with 103 direct archive declarations. Previous
prerequisite lock/five files and 46 historical snapshot bindings are unchanged.
No tests were repeated for the citation-only follow-up.

**Implementer:** all 93 Python host tests passed, no failures or skips. Evidence
is retained in `build/android-runtime-resolution-v2/host-tests-1.log`, with source
reports, preservation results and local summary in the same directory. The full
93-test result is separate from QA's independent 14-test rerun.

**Not run:** SDK/AAR/APK builds, actual-provider linkage/enforcement, rebuilt
tokenizer/budget proofs, Android/Kotlin tests, emulator installation/workloads
and S24 testing. No dependency was adopted, data deleted or Git history changed.

## What the PASS establishes

The plans correctly preserve unresolved configured/transitive root repository
selection, the unadopted public ML Drift snapshot, both ABI requirements,
reviewed artifact/execution-freeze gates, emulator-first direction and S24
deferral. Static repository syntax does not establish a universal build failure;
source-factory presence does not establish packaged native linkage.

The recommended next decision is the separately reviewed source-pinning
compatibility amendment described in
`planning/P2_ANDROID_RUNTIME_EMULATOR_FIRST_AMENDMENT_V1.md`. Its observed
Bazel-8 ML Drift input is not automatically compatible with the pinned Bazel-7
SDK. No source pin, toolchain upgrade, opaque binary or narrower backend/ABI
scope was approved by these reviews.

The proposed emulator campaign remains conditional on reviewed complete build
closure and concrete artifacts/freeze. This PASS grants no execution permission
and does not establish Android native feasibility or S24 performance acceptance.
