# Bazel server JVM correction v1 — retained results

2026-10-30. Branch `dev`, base `e47e61b8ccdd400498b4d50cada22fc4176b2176`.
**Launch correction passed; dependency resolution remains blocked.**

## Implementation and reviewed freeze

The user authorized the narrow correction following the independent PASS for
the prior failure/proposal. The previous pending-review record, Java-17 freeze,
failed attempt and source files remain unchanged. The subsequent review is
retained separately in `planning/fixtures/bazel-server-jvm-v1/previous-results-review.json`.

The new wrapper reuses the historical repository and owned-process guards
without modifying them. It selects an archive-derived private Java **21.0.5**
using `--server_javabase`. Its provenance inventory includes 102 files and 26
directories; no symlinks occur in this particular archive. Bytes, file/directory
permissions and internal symlink identities are admission requirements. The
server JAR/class and complete executable identities are independently checked.

Independent implementation/freeze review passed with no findings for:
`c9787682569fb8d694c8e68a714439333b75ca055404fed09540c12cf96bd9a7`.
It verified 99 inputs, 77,377 regular-file/symlink entries across five prepared
trees, 15 focused tests and four additional guard probes. The complete host
suite passed **136 tests**, zero failures/skips, using the existing
`build/constrained-decoding-reference/bin/python` environment. The initial
system-Python attempt had five missing-import errors; that log is retained.
No dependencies were installed to resolve those environment errors.

The independent pre-execution summary is
`build/bazel-server-jvm-v1/qa-implementation-freeze-review-1/qa-summary.json`;
its copy is `planning/fixtures/bazel-server-jvm-v1/implementation-review.json`.

## Executed bounded analysis

Only after exact-freeze PASS, the three authorized commands ran under the new
attempt `build/bazel-server-jvm-v1/attempts/configured-1/`. Raw report/argv and
all six output logs are preserved. Their hashes are recorded in
`planning/fixtures/bazel-server-jvm-v1/configured-outcomes.json`.

| Command | Decision | Total elapsed, including cleanup | Exit | Cleanup |
| --- | --- | ---: | ---: | --- |
| Root repository inventory | TIMED_OUT | 300.483 s | 143 | Confirmed |
| ARM64 configured query | TIMED_OUT | 300.466 s | 143 | Confirmed |
| x86_64 configured query | TIMED_OUT | 300.482 s | 143 | Confirmed |

Each decision deadline stayed **300 seconds**, with the original shared
15-second cleanup ceiling and 16 MiB combined retained stdout/stderr cap.
Elapsed values include cleanup; no separate cleanup-duration statistic was
instrumented. Retained stderr sizes were 6,902 / 16,848 / 16,176 bytes and all
stdout logs were empty. Wrapper exit 2 retains evidence; it is not a pass.

## What the evidence establishes

- **Launch correction passed.** The class-version startup rejection disappeared.
  Bazel ran through root WORKSPACE code and instantiated dependencies, including
  TensorFlow/XLA and, in the configured requests, the pinned Python 3.13 setup.
- **Root inventory failed its deadline.** No complete repository receipt was
  returned. Therefore the guarded ML Drift overlay was never selected. Both ABI
  requests used the unchanged root; failure did not bypass repository admission.
- **No configured graph was produced.** ML Drift target suppliers, repository
  mappings in the selected graph, ABI/features, consumed C++ interfaces and
  resolved Java compilation toolchains remain unproved.
- **Compilation settings were preserved, not certified.** The full original
  source/SDK declarations, dependency locks and Java-17 command environment
  remain byte-identical. The server JVM is separate. Actual compilation
  toolchains could not be inspected because configuration never completed.

This is a repository-setup timeout, not evidence of ML Drift API incompatibility,
a known Bazel 7/8 incompatibility, a Java compilation failure or a proven network
fault. Progress messages and partially materialized repositories are not graphs.

## Next specific blocker and route decision

**Do not start control/candidate runtime builds.** The unchanged root dependency
setup must complete within the reviewed analysis limit before a recipe can be
qualified. At post-attempt inspection, unmarked/incomplete repository directories
remained for `rules_ml_toolchain` (inventory), `llvm-raw` (ARM64), and
`crate_index` plus the host Rust toolchain (x86_64). Their retained directory
observations are in the outcomes record. Absence of a completion marker identifies
unfinished setup; it does not identify whether transfer, extraction, repository
evaluation or a subprocess caused the stall.

Recommended next investigation: trace those exact pinned repository rules and
their retained partial outputs; establish complete source/tool identities and a
bounded, isolated preparation/cache strategy for review before another freeze.
Preserve checksums, full Cargo locks, compiler patch, ABI/backend scope and
repository-precedence checks. Do not increase the analysis deadline or change
JVMs/dependencies to hide this result. No fourth query or retry occurred.

## Verification and independent results review

Passed: 136 host tests; exact pre-execution implementation/freeze review; private
server-JDK preflight; post-execution frozen-input/tree and historical preservation
checks. Those checks launch no additional Bazel command. Evidence is retained in
`build/bazel-server-jvm-v1/post-execution-preservation.json`.

Failed: all three configured-analysis command deadlines. Blocked: full root
inventory, configured dependency graphs, Java toolchain resolution, consumed
interface compatibility and a complete Android runtime build recipe.

The independent executed-results verdict belongs in the separately produced
`planning/fixtures/bazel-server-jvm-v1/results-review.json`; this implementation
report does not grant itself review approval. Pre-execution PASS cannot replace
results review.

Not run: SDK/AAR/APK builds, Kotlin/Android tests, emulator/model workloads or
S24 access. No global Java, Gradle, Android Studio, SDK dependency or Git changes.
Prior failures, strict validator and historical source bindings remain unchanged.
P2 is incomplete; semantic search remains disabled. Emulator work remains
downstream of reviewed runtime artifacts, and S24 testing remains deferred.
