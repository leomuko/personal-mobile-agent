# Inspector backend qualification — next-scope proposal v1

**PROPOSAL ONLY. No new native attempt, sandbox adoption or C++ execution approved.**
Preserve the R4 metadata-1 dispatch failure and all previous freezes. The current
compiler/helper qualification is blocked, although static harness preparation and
Abseil source preparation passed their limited reviews.

The pinned llvm-otool wrapper selected otool-classic. The current profile excluded
that execution edge and correctly stopped inspect-1;28 remaining queries did not
run. Read-only candidate identity/dependencies are in observed-inspector-helper.json.
A load-command graph alone cannot establish all subprocesses selected by a wrapper.

Proposed preparation:

1. Verify the exact otool-classic payload, alias chain, native architecture/load
   commands and pinned cache dependencies under its own executable context. Bind
   it to the same Xcode/SDK identities; no tool substitution/version change.
2. Review the complete wrapper-to-backend dispatch and any further required helpers.
   Unknown helpers remain blocking. Do not authorize an entire directory or shell.
3. Specify a per-inspector executable allowlist admitting only verified llvm-otool
   and otool-classic, keeping compiler-only profiles separate. Independently check
   every relevant native payload, including the backend, with retained evidence.
4. If adding the backend's two -L/-l checks, explicitly review the resulting31
   metadata commands (16 inspector +3 driver +6 dry-run +6 include), rather than
   quietly extending the old29-command freeze. Keep300s/15s/30s, log/RSS/storage
   limits, strict language flags and the18GiB analysis floor unchanged.
5. Add dispatch/unknown-helper regressions and review the exact implementation and
   new immutable freeze before any execution. Use a fresh attempt identity and
   retain the old failure; do not resume/overwrite metadata-1.

A later native attempt needs authorization within that concrete reviewed scope.
Stop at its first blocking result. No broad fallback, unconfined retry, cap growth,
C++ matrix, SDK/Bazel/device credit, root adoption or semantic enablement follows.
Only after independently reviewed metadata results can the actual toolchain/helper
receipts be bound into the separately reviewed complete-header execution freeze.
