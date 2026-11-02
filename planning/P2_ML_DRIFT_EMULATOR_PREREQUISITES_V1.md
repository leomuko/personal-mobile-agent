# Emulator campaign prerequisite update — ML Drift analysis v1

2026-10-29. Versioned supplement to the existing emulator-first amendment and
campaign; their locked bytes are preserved. **No emulator installation or
workload is authorized by this document. S24 remains deferred.**

The first frozen configured-analysis attempt is blocked at Bazel Java server
startup (server class major 65 versus selected JVM capability 61). No workspace
or ABI dependency graph was evaluated. Review the separately versioned server
JVM correction and a fresh analysis freeze before retrying; do not advance to
artifact or emulator work on the strength of the failed launch. Final independent
review of the retained execution results remains pending because QA hit its
usage limit. Pre-execution runner/freeze PASS does not certify those results.

Before the proposed emulator campaign can become executable, require:

1. Reviewed ML Drift amendment and verified source/toolchain identities.
2. Actual configured dependency evidence for arm64-v8a and x86_64, including
   repository provenance/precedence and the platform/features selected by Bazel.
   Failed root inventory is not proof of absence and cannot permit insertion.
3. Resolution of selected missing inputs, incompatible rules/interfaces and LFS
   payloads. A genuine pointer blob is source identity, not binary qualification.
4. A complete separately reviewed runtime recipe: full SDK dependency locks,
   original patch order plus approved Unicode correction, wrapper/JNI/AAR
   assembly, actual non-stub provider linkage, both ABI libraries, licenses,
   signing and distinct control/candidate/stock-rollback identities.
5. Rebuilt tokenizer/budget and one-token proofs, API/JNI/ELF/packaging checks,
   default-off compatibility and independent implementation QA.
6. A concrete emulator protocol/freeze binding exact APK pairs, emulator/API/
   backend/resource profile, synthetic data, request counts/deadlines, stop
   conditions, cancellation/cleanup and data-preserving rollback.

Configured analysis alone cannot satisfy compilation or provider enforcement.
If this investigation remains blocked, control/candidate builds and emulator
workloads remain not run. No dependency/toolchain upgrade, opaque binary
replacement or ABI/backend reduction is an automatic repair.

Keep both ABI artifact scope. An arm64 emulator on the present host does not
execute x86_64. Emulator CPU or supported GPU checks are compatibility lanes,
not S24 GPU/memory/thermal/battery acceptance. Preserve the existing 2048/512
budget, strict validator, resource safeguards, five-second search contract,
ownership, quarantine and data-preserving rollback rules. Semantic search
remains disabled and P2 incomplete.
