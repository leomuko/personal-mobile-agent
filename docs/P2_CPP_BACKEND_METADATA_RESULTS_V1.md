# Backend metadata-2 — retained execution results

**Native metadata qualification FAILED at linker selection.** The one explicitly
authorized R2 run stopped at its first failure. No retry or acceptance publication
occurred. P2 remains incomplete; semantic search stays disabled.

## Exact execution

Freeze R2 SHA-256:
`52a0e72aaff0cc6f77ab2c8a22ba4af61c679b95bba3844a5ec4ce07a1973869`.
Review SHA-256:
`f4050d3d6e0cea858da1b4d8fe08aa7f133ee9ecc4c6f8d75580c3008bf40bd7`.
The retained `metadata-2-r2-launch.json` records the exact authorized controller
argv. This is separate from R1's earlier pre-owner rejection; that evidence and
metadata-1 remain unchanged.

The actual attempt is `build/dawn-cpp-toolchain-v1/metadata-attempts/metadata-2`.
The controller exited 1. Eighteen queries passed: all sixteen native inspector
checks, the Clang version query and resource-directory query. The nineteenth
query, `linker-path`, executed and exited 0, but output validation failed.
The twelve remaining driver-plan/include-search queries are NOT_RUN.

## Exact blocker

The retained final trace invokes pinned Clang with:
`--driver-mode=g++ --no-default-config -B/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin -print-prog-name=ld`.
It returned the bare name `ld` followed by a newline. The existing validator
requires the selected linker to resolve to the pinned absolute linker path and
rejected this output with `ValueError: unexpected selected linker`.

This proves a selection-contract mismatch under the exact frozen invocation and
sandbox profile. It does not establish why Clang returned a bare name, qualify
another linker, or justify resolving the name through an ambient PATH. No linker
executed. Any investigation of driver lookup, profile effects or an explicit
selection contract must be reviewed separately, without repairing this output.

## Ownership, timing and limitations

The parent outcome records **cleanupConfirmed=true / CONFIRMED** and no public
acceptance. Its generic error text `metadata worker failed/cleanup uncertain` is
a disjunctive diagnostic, not evidence of uncertain cleanup in this run. The worker
exited 1 after its validator failure; cleanup confirmation remains authoritative.

Parent decision time was 31.237136 seconds, within the 300-second ceiling.
The worker-owner result reports 16.4490345 seconds including cleanup; this is not
whole-parent time or an isolated cleanup duration. The failed linker command's
native trace reports 0.011715875 seconds, distinct from guarded query time.
All retained traces and report output remain unrepaired.

The inspector queries passed their existing validators in the explicitly scoped
profile. They do not constitute an OS-wide exec audit or complete compiler/helper
qualification. No C++ matrix case, compilation, linking, test binary, SDK/Bazel
build or device workload ran. All 72 C++ cases remain outside this scope. The
18 GiB analysis floor, emulator-first order and deferred S24 tests are unchanged.

## Verification and next boundary

Independent results review is pending in this frozen producer report. The earlier
343 producer host tests and 52 independently rerun affected tests are historical
preparation evidence; they were not rerun during this unchanged-source execution.
The new result lock binds the actual attempt, exact freeze/review and this report.

Retain this failure. Next assess linker lookup and the frozen selection contract
from the observed evidence before proposing any changed native query or profile.
Do not automatically retry, widen the sandbox, accept a bare name, run the remaining
queries or proceed to the 72-case C++ matrix. No Git mutation occurred.
