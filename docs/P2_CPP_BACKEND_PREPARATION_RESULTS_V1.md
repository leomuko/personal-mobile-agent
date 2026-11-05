# Inspector backend preparation v1 — implementation review checkpoint

**Read-only backend/input preparation passed; implementation and31-command freeze
review pending. No new native query ran.** Compiler/helper native qualification
remains BLOCKED. P2 is incomplete; semantic search stays disabled.

This follows the metadata-1 failure without changing its R4 implementation, inputs,
trace, failed outcome or acceptance state. A separate diagnostic namespace,
`scripts/diagnostics/dawn_cpp_backend_v1/`, adds the exact backend dispatch edge
and reuses the unchanged old validators/driver recipe. No source or dependency
version is replaced.

## Static backend and dependency evidence

`backend-native-catalog.json` pins the observed472,320-byte otool-classic payload,
SHA256 `6beb1ad9c4fb7edafd59fddcb093f358f9a250bfe1db2db9f04ed1aacd523a69`,
ARM64 subtype0, its raw load commands and pinned libc++/System cache suppliers.
Four loader/executable/rpath contexts cover compiler, linker, wrapper and backend.
The original seven suppliers remain exactly equal to their old declaration; the
backend is the eighth. This does not establish successful backend execution or
an independently completed native-inspector cross-check.

Alias receipts pin requested paths, every ancestry link, canonical leaves,
bytes/hash/mode and toolchain anchor. The selected three entrypoints are regular
files. The conventional `otool -> llvm-otool` alias is recorded/checked but is not
an additional entrypoint. Cycles, escapes, dangling paths and changed alias or
payload identities reject. Before/after verification is drift detection, not an
atomic OS exec audit.

Execution profiles contain exactly llvm-otool plus otool-classic for inspector
commands, and only clang for compiler commands. No shell, directory, linker or
arbitrary helper is admitted. Actual sandbox dispatch enforcement is NOT_RUN.

## Implementation verification

**341 full host tests passed**, without failures/skips. All21 new focused tests also
passed under pinned host Python3.9. The two executions of the focused set are not
42 distinct tests. No new package dependency was installed.

Coverage includes complete alias receipts, parent symlinks, cycles/dangling/escape,
changed links and payloads, permitted exact dispatch profiles, unknown helpers,
compiler isolation, changed bytes before/during dispatch, cleanup exceptions,
prior-owner quarantine, late/interrupted publication and first-failure stopping.
Dispatch time includes pinning and postverification inside the original per-query
budget and outer absolute deadline; exact-boundary tests reject late success.
Tests use synthetic files and mocked execution; they claim no actual native helper
or OS policy result.

A read-only preservation audit freshly checked all94 R4 inputs, the six full
SDK/resource/header/Abseil trees,29 old result bindings and every entry in the
seven-file metadata-1 attempt. The R4 implementation/source/evidence are unchanged;
old cleanup remains confirmed and no acceptance exists. No metadata-2 directory
has been created. `preservation.json` records this audit.

## Concrete scope and readiness boundary

`metadata-command-manifest.json` defines31 exact future queries:16 inspector calls,
three driver queries, six -### plans and six empty -E/-v searches. The backend's
-L and -l checks are the explicit additions to the old29. Neither the old approval
nor this producer checkpoint silently authorizes that increase.

`metadata-execution-freeze-r1.json` binds the implementation, unchanged old rules,
alias/native catalog, complete inputs, exact argv and a fresh metadata-2 identity.
It preserves300s original decision/15s cleanup/30s per query, log/RSS/storage bounds,
1GiB small admission and the separate18GiB analysis floor. Review revalidation,
prior-owner quarantine, intent-before-link and owned publication revocation remain.

Independent implementation/scope review must pass before a separately authorized
native invocation. Review alone does not start metadata-2. An unknown helper,
selection/default or cleanup result must stop it without fallback, output repair,
cap growth, automatic retry or resumption of the remaining queries.

All72 C++ cases remain unrun/unapproved. No native query, compiler, linker, produced
binary, SDK/Bazel/device workload, acquisition or Git operation occurred in this
preparation. Root adoption, runtime/API equivalence and Android compatibility stay
unqualified; emulator-first direction and deferred S24 testing are unchanged.
