# Verified-cache dependency analysis v1 — results and route decision

2026-11-02. Branch `dev`, base `e47e61b8ccdd400498b4d50cada22fc4176b2176`.
**Archive preparation and root inventory passed. Configured analysis is blocked
by concrete root dependency-closure errors; runtime artifact builds remain blocked.**

## Reviewed execution and cache disclosure

Preparation passed independent implementation and results review: 42 payloads,
721,042,126 bytes, reported preparation time 156.466964083 seconds. Its results
document explains the timing capture point and the finalization deadline checks.
The original cache, failed transfers and earlier locked evidence are preserved.

Only after independent exact-freeze PASS, the single three-command attempt ran:
`afb82c30dfd4eed60f9b84610327b22e426806aef1eeb9742432f8a876bd2070`.
The reviewer verified 110 inputs, five trees/77,377 entries, complete seed and
preparation-review bindings. Command scope, source/dependency locks, Java
selection and resource limits were unchanged. The complete host suite passed
159 tests; focused cache tests and injected deadline probes passed separately.

The seed was revalidated and copied into a fresh writable cache. No hardlinks or
extracted repository trees were imported. Preparation timing is separate from
the following command times; cache-copy/admission time was not separately
instrumented and is not included in each command's elapsed statistic.

| Request | Outcome / exit | Elapsed including owned cleanup | Cleanup |
| --- | --- | ---: | --- |
| Root inventory | Succeeded / 0 | 298.254 s | Confirmed |
| ARM64 configured query | Failed / 1 | 99.034 s | Confirmed |
| x86_64 configured query | Failed / 1 | 95.949 s | Confirmed |

All commands completed inside their 300-second decision windows, retaining the
15-second cleanup ceiling and 16 MiB combined stdout/stderr cap. Root stdout was
3,247,442 bytes; both ABI stdout logs were empty. Wrapper exit 2 retains evidence
and does not mean compatibility passed. No fourth invocation or retry occurred.

## Established progress

The root query returned a complete inventory and valid pre-insertion receipt:
`{"identity": null, "present": false}`. Policy was `INSERT_AFTER_ROOT`. Only
after this successful receipt did the guarded workspace declare the exact pinned
ML Drift archive and mapping. Both ABI requests used that guarded workspace.

Each extracted ML Drift tree matches all **883 archive-bound source files** at
`2d7fbbd45d8940b5fc7cea1f79e59d9d796164e2`. This establishes which candidate
source was supplied; it does not establish C++ interface or runtime compatibility.
The prepared ML toolchain, LLVM-source and host Rust inputs allowed the original
crate-index setup to finish and root evaluation to reach the later LiteRT setup.

The 42-entry seed remained unchanged. The working cache ended with 53 verified
payloads totaling 893,727,521 bytes after normal, unchanged Bazel repository
evaluation. Those additional payloads were not merged into the accepted seed.

## Exact reported blockers

### ARM64: Dawn repository missing from the selected root

The query failed at
`@litert//ml_drift_delegate/delegate:buffer_handler_webgpu`, whose BUILD file
references `@dawn//:webgpu_headers` at line 1041. Bazel reported `@dawn` undefined
while analyzing the requested GPU accelerator. This is a repository-resolution
failure before SDK build actions, not an observed GPU runtime failure.

The pinned LiteRT source contains `third_party/dawn/workspace.bzl`: its `repo()`
describes tag `v20260720.160313`, custom `dawn.BUILD`, and **three Python patch
commands**, but supplies **no archive SHA-256**. That declaration was not active
in this root. Merely invoking it would not complete a verified dependency recipe:
the archive identity, label context, transformations, tool identity and transitive
closure need explicit review. No Dawn download, declaration or patch was added.

### x86_64: FP16 repository mapping does not map the default target

`@ml_drift//ml_drift/common:types` declares `deps = ["@fp16"]` at BUILD line 687.
The approved repository mapping resolves the repository to `@FP16`; the shorthand
still requests target `fp16`. The selected root's BUILD file exports **`FP16`**,
not `fp16`, so Bazel fails on `@FP16//:fp16`.

Actual supplier: XLA's FP16 repository, commit
`4dfe081cf6bcd15db339cf2680b9281b8451eeb3`, archive SHA
`d973501a40c55126b31accc2d9f08d931ec3cc190c0430309a5e341d3c0ce32a`.
The standalone ML Drift MODULE lists fp16 `0.0.0-20210320-0a92994`; that graph
was not imported. A target-name adapter alone would not prove header/API
equivalence across these source expectations. No version or BUILD alias changed.

These are the first reported failures for the two runs, **not evidence that each
problem is exclusive to that ABI**. Both requested the unchanged target set.
Partial package/target counts do not establish complete configured graphs.

## Route decision and next explicit decision

**Stop before runtime artifacts.** Root inventory is now established, but no
complete configured graph exists. Actual Java compilation toolchains, complete
ABI/features and consumed interfaces remain unresolved. No compatibility or
build-readiness claim follows from the successful cache preparation.

Recommended next slice: a separately reviewed **root-closure compatibility
amendment** addressing (1) the FP16 target/default-label bridge while preserving
existing repository precedence and source revision, with consumed-header/API
checks; and (2) a fully pinned Dawn declaration/BUILD/patch/tool/transitive recipe
derived from the selected LiteRT source. Assess full implications before adoption.
Do not silently import the standalone MODULE graph, redefine existing repositories,
upgrade versions, remove GPU/WebGPU paths, narrow ABIs or relax deadlines.

The volume had about 2.8 GiB free after the attempt. Existing evidence is retained;
another fresh attempt would need additional storage under the unchanged 18 GiB
preflight. No automatic cleanup or data deletion was performed.

## Preservation, evidence and review

Post-run checks matched all 110 frozen inputs, 77,377 source/toolchain entries,
46 historical bindings and the accepted seed. Both staged workspaces preserve
all 1,355 original Git blobs, allowing only the reviewed WORKSPACE helper suffix;
full Cargo and cargo-bazel locks are unchanged. Logs, receipts, archive/source
bindings and cache state are retained in `analysis-outcomes.json` and the local
attempt directory. The final result lock binds those artifacts and the report.

Independent executed-results review is recorded separately as
`planning/fixtures/repository-cache-v1/results-review.json`; this report does not
self-approve the evidence. Earlier FAIL verdicts and probes remain preserved.

No SDK/AAR/APK build, application substitution, emulator/model workload, S24
access, Java-default change or commit occurred. Ordinary repository configuration
used its existing pinned host tools. P2 remains incomplete; semantic search off.
