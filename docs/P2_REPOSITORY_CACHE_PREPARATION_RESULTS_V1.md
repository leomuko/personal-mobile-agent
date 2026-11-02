# Repository archive preparation v1 — results

2026-11-02. Host-only. **One reviewed preparation attempt completed; dependency
resolution, compilation and runtime compatibility remain unproved.**

## Reviewed preparation and retained corrections

Exact executed R4 freeze:
`4423d511ecd52e2cbcb7b7d09189657dded12993db3e6f05e529bc573b602d58`.
Independent review passed 23 focused tests and the original finalization probes,
verified 32 direct bindings and 42 entries, and closed RC-PREP-001.
The complete current host suite passed **159 tests**, zero failures/skips.

R1's real-manifest preflight rejected the underscore in an x86_64 filename;
no preparation ran. R2 was rejected for late-finalization success credit. R3 was
an unexecuted intermediate correction. R4 includes admission and all receipt/
report finalization in its 900-second decision budget, and analysis independently
rejects over-budget preparation evidence. All earlier freezes, source snapshots,
the independent failing probe and corrected regressions remain retained.

## Actual attempt

`build/repository-cache-v1/preparation-attempts/prepared-1/` contains the raw
report, receipt, private SHA-bound worker inputs, logs, partial-stage bytes and
accepted archive cache. The parent exited **0**. All 42 workers exited successfully
with owned cleanup confirmed. There were no retries or alternative sources.

Reported preparation elapsed: **156.466964083 seconds**, covering input admission,
workers, cleanup, publication and receipt finalization. This statistic is captured
before the final report write; that write and its following deadline check are
enforced within the same 900-second ceiling but are not separately timed. Later
Bazel analysis is excluded.
The prepared cache contains **721,042,126 payload bytes**: 37 original verified
payloads (588,050,410 bytes) and five new pinned downloads (132,991,716 bytes).
Original caches and partial outputs were not modified.

| New Rust 1.86.0 archive | Bytes | Worker elapsed including cleanup |
| --- | ---: | ---: |
| rustfmt, aarch64-apple-darwin | 1,593,128 | 5.121 s |
| llvm-tools, aarch64-apple-darwin | 49,487,232 | 47.011 s |
| rust-std, aarch64-apple-darwin | 27,873,668 | 26.254 s |
| rust-std, aarch64-linux-android | 25,052,188 | 36.756 s |
| rust-std, x86_64-linux-android | 28,985,500 | 37.908 s |

The manifest binds the unchanged rules_rust 0.61.0 component hashes and URL
template. Payloads and canonical-ID markers were copied without transformations.
No extracted tree or repository-completion marker was imported. No downloaded
compiler/generator binary was executed during preparation: workers used the
frozen Python interpreter and stdlib only.

The receipt is all-or-nothing admission evidence together with the completed,
in-budget final report. Partial files are outside the accepted cache; failed or
interrupted attempts cannot authorize analysis. The accepted cache is a fixed
seed; subsequent analysis must verify and copy it into a separate writable cache.

## Additional cause evidence

The verified 49,487,232-byte Rust LLVM-tools archive establishes that the old
40,016,824-byte temporary file is an exact prefix, missing **9,470,408 bytes**.
Together with the earlier LLVM-source prefix comparison, this confirms incomplete
archive acquisition in those two retained transfers, before extraction of those
archives. It does not reconstruct historical network/thread timing or establish
that every repository timeout had the same cause. The crate-index generator
still cannot be blamed from the retained files alone.

## Evidence and next gate

`planning/fixtures/repository-cache-v1/preparation-outcomes.json` binds the raw
report, receipt, every worker request/log, archive identities and prefix proof.
The preparation-result lock binds the code, plan, manifest, freezes and results.
The independently produced preparation-results verdict is stored separately as
`preparation-results-review.json`; this report grants itself no approval.

Next, after preparation-results review, create and review the analysis freeze.
The warmed seed and preparation timing must be disclosed. Root inventory and
both ABI queries retain their original 300-second decisions, 15-second cleanup
ceiling and 16 MiB log cap. A repository receipt and configured graphs remain
mandatory before any dependency-resolution claim. Actual suppliers, ABI/features,
consumed interfaces and Java toolchains remain compatibility gates.

No SDK/AAR/APK builds, app substitution, Java/default configuration changes,
emulator/model workloads, S24 access or commits. P2 remains incomplete and
semantic search disabled.
