# P2 repository archive preparation v1

2026-11-02. Host-only implementation/execution scope, subject to independent
strategy, manifest and implementation review before preparation. A separate
reviewed freeze precedes the root/ABI analysis. No runtime artifact or device work.

## Selected approach

The previous failures remain immutable. Import **37 complete cache payloads**
whose SHA-256 keys also occur in retained pinned declaration sources. Preserve
their exact empty Bazel canonical-ID markers. Declaration references identify
checksum provenance; they do not establish configured dependency selection.

Add only the five missing Rust **1.86.0** archives pinned by rules_rust **0.61.0**:
host rustfmt, LLVM tools and standard library; ARM64 Android and x86_64 Android
standard libraries. Download from the unchanged rules_rust static Rust URL
template. Existing cached rustc, cargo and clippy are reused after verification.
The input manifest binds payload hashes, source declaration bytes/line references,
canonical markers, immutable URLs and size ceilings. No version replacement.

Do not reuse any partial transfer or extracted repository directory. Preparation
publishes only checksum-verified cache files, never BUILD files, transformed source,
extracted toolchains or fabricated repository-completion markers. Bazel performs
its own original extraction, patching and repository evaluation later.

## Preparation protocol and limits

- Fresh, exclusively owned attempt; preserve the old caches and all failed work.
- At most 48 entries and eight downloads; this freeze is exactly 42 entries,
  consisting of 37 retained files and five downloads.
- At most 512 MiB per entry and 1.5 GiB accepted payloads in total. Five GiB free
  is required before preparation, allowing retained partials and accepted copies.
- Each transfer has a 180-second decision deadline; the whole preparation has
  a 900-second deadline across workers, cleanup, publication and final checks.
  Final interrupted-worker cleanup may consume the shared 15-second ceiling.
- Worker stdout/stderr is capped at 2 MiB combined, separate from analysis logs.
- Use the reviewed process-group owner. Signal/timeout/uncertain cleanup prevents
  subsequent admission. No shell, Rust tool, JVM, Bazel or archive extraction is
  executed by preparation; only the pinned Python interpreter and stdlib workers.
- Worker requests are privately copied and SHA-bound before launch. Worker
  output remains partial until exact checksum validation and exclusive cache
  publication. Existing—even empty—conflicting entries reject without overwrite.
- A full receipt and `completed=true` report are required. Failed/interrupted
  attempts retain partials and outcomes but cannot authorize analysis. Successful
  inputs remain separate from Bazel's later writable cache.

Archive authenticity/completeness is established by independently pinned full
payload SHA-256, not by accepting a self-computed digest of a partial file. No
new archive transformation/parser is introduced into preparation. Unchanged
Bazel repository rules validate and extract these bytes during later analysis.

## Analysis admission and cache state

After preparation results review, create a new freeze binding the receipt,
report, manifest, code, old freeze/source/toolchain identities and review records.
The seed cache is revalidated in full, then **copied**, not hardlinked, to a fresh
writable analysis cache. Never point Bazel at the immutable accepted seed.
Report exact warm-seed identities and separate preparation/analysis times.

The original commands, repository guards and Java selection remain unchanged:
private server Java 21.0.5; Java-17 compilation environment; root inventory then
ARM64/x86_64 cqueries; 300 seconds each; 15 seconds cleanup; 16 MiB combined
logs. Do not infer compatibility from warm setup or server launch. Actual receipt,
graph suppliers, ABI/features and Java toolchains remain required.

Require **18 GiB free** immediately before copying the analysis cache. Rationale:
the furthest incomplete retained output used approximately 4.4 GiB for one ABI;
three fresh roots can consume over 13 GiB, plus source workspaces, writable cache
and remaining toolchain/repository expansion. This is an explicit storage safety
preflight, not a dependency or runtime-memory policy change. Do not delete old
evidence, share mutable extracted roots, narrow ABI/backend scope or relax the
analysis deadline if storage or further dependency setup blocks.

## Review and exit boundaries

First: host regressions, frozen input/plan review. Then: one bounded preparation
attempt. Review results before a new analysis freeze, then review that freeze
before the same three-command attempt. No unreviewed retry or automatic expansion
of this archive list. A new blocker is retained and reported at its exact stage.

If both configured graphs close, inspect actual suppliers, flags and Java
toolchains and prepare the complete runtime build recipe for separate review.
Otherwise document the remaining blocker without declaring source incompatibility
unless the evidence demonstrates one. No SDK/AAR/APK builds, app substitution,
emulator/model workloads, S24 access or commits. P2 incomplete; semantic search off.
