# P2 Abseil/C++ qualification proposal v1

Status: **proposal only; no acquisition, patch execution or C++ execution authorized**.
Complete dependency qualification remains BLOCKED. P2 is incomplete; semantic
search stays disabled. Emulator-first and deferred S24 testing are unchanged.

## Objective and prior evidence

Qualify the exact root-selected Abseil preparation and the complete generated Dawn
ordinary/wire-client headers under strict host C++20/C++17. This is a standalone
host investigation, not root-fix adoption, Android compatibility or proof of Bazel
supplier/tool selection. Keep the successful R5 Python/generation campaign and all
prior failures unchanged. Do not rerun its generators just to prepare this slice.

The cleanup-boundary revision catches subprocess cleanup exceptions and records a
failed outcome with cleanup UNCERTAIN when the owner did not return confirmation.
Its mocked regression must pass before any new execution freeze. It does not prove
that an actual uncertain worker was killed. Quarantine an uncertain attempt; no
automatic retry, reuse or subsequent workload until independent cleanup is confirmed.

## Pinned identities and remaining preparation gates

Machine-readable static observations are in
`planning/fixtures/dawn-cpp-qualification-v1/`. These are data-only pins, not a
reviewed executable build recipe. No compiler, linker, patch, sed or vendor code
was invoked while collecting them.

1. **Abseil source:** use only root repository `com_google_absl`, archive
   `https://github.com/abseil/abseil-cpp/archive/20260526.0.tar.gz`, SHA256
   `6e1aee535473414164bf83e4ebc40240dec71a4701f8a642d906e95bea1aea0c`,
   prefix `abseil-cpp-20260526.0`. The copied local-search observations are historical
   evidence from the prior block, not a new exhaustive cache search. Tag spelling
   alone is not integrity proof; bytes
   and complete archive validation remain NOT_RUN. The other retained repository
   named `abseil-cpp` at revision9ac7062 is not a substitute.
2. **Root patch:** retain exact `PATCH.abseil`,886 bytes, SHA256
   `2d14482f889029bb6c3332ee925eb058e784ce35115b5e9222c43185c9d5dbc5`.
   Its two hunks remove base BUILD visibility entries and comment an x86 CRC SIMD
   define in the intrinsics branch. Span-only host tests cannot qualify those
   broader BUILD/CRC/Android effects.
3. **Patch semantics:** the retained Bazel7.6.1 `http.bzl` defaults to `-p0` and
   empty `patch_tool`; `utils.bzl` uses the native patch path for that configuration,
   before `patch_cmds`. Pin both rule files and the root WORKSPACE. Do not assume
   `-p1`, invoke Bazel, accept fuzzy/reversed patches or substitute a newer source.
   A standalone preparation implementation needs reviewed exact contexts and
   before/after hashes for both hunks; do not claim it independently reproduces all
   Bazel-native behavior without a reference equivalence decision.
4. **BUILD rewrite:** preserve exactly:
   `sed -i -e 's|@googletest|@com_google_googletest|g' absl/*/BUILD* absl/*/*/BUILD* absl/*/*/*/BUILD*`.
   Freeze matched paths, counts, tool/shell identity and backup-file behavior after
   source validation. macOS sed's argument handling must be checked on synthetic
   fixtures in a reviewed preparation scope. Do not silently replace the command
   with another implementation or infer actual Bazel PATH selection from local
   tool presence. An unresolved tool/command mismatch blocks preparation.
5. **Compiler/SDK:** static pins identify Xcode26.6 build17F112, the installed
   `clang++` alias and clang/ld payloads, macOS SDK26.5, and resource directory21.
   Full SDK/resource inventories contain50077 entries/849499659 regular bytes.
   Symlinks must remain internal and non-dangling. Raw Mach-O pins identify11 native
   files/223263920 bytes plus the existing pinned OS-cache inputs. This is raw
   static parsing, not independent inspector/runtime closure qualification.
6. **Compiler admission:** review the native graph per executable, resolve every
   loader/rpath edge and selected helper, then independently cross-check with the
   pinned inspector. Freeze tool/version metadata queries before executing them.
   The installed resource directory name does not prove the driver's version.
   Unexpected libraries, helper programs, include roots or changed OS caches block.
   No compiler metadata command has run in this preparation turn.
7. **Generated inputs:** bind the prior R5 eight-header manifests, control and
   candidate output hashes, and the26 checked-in Dawn include headers to the same
   immutable source revision. Materialize complete include roots with generated
   headers first; preserve original forwarding/bitmask/wire-export headers. No
   projections, fake wrappers, removed includes, stubs or regenerated repairs.
8. **Harness:** the existing `callback_probe.cpp` remains an unreviewed draft and
   uncommitted. Its hash is an observation, not approval. Independently review its
   actual calls, counters, return types and expected diagnostics against complete
   generated headers. Freeze source, macros, witnesses and expected stdout only
   after that review. No C++ result may be credited before this gate closes.

## Staged execution contract to review before implementation

**A — acquisition and preparation.** Review a separate executable freeze first.
Acquire only the specified archive, preserving the original URL chain. GitHub's
archive redirect may require an explicit immutable HTTPS codeload allowlist; this
must be frozen before acquisition, not followed generically. No metadata search,
Git clone, alternate tag, dependency upgrade or opaque replacement. Hash exact
compressed bytes before decoding; validate all members and gzip EOF/CRC with a
complete inventory, including license/version/build metadata. No excluded trees.
Freeze expected size when known; otherwise the reviewed acquisition cap below
applies in addition to the exact archive SHA. Keep partial/failed inputs separate
from validated source; do not promote them into a reusable cache.

Stage an immutable unmodified source and a private root-patched derivative. Record
patch/rewrite preimages, postimages, matched paths and all other-file equality.
Only reviewed literal changes are permitted. Validate the actual transitive
`absl/types/span.h` closure against the prepared source. No CMake, configure,
package installation, Bazel repository execution or vendor tests are admitted.
Source/patch/native preparation must pass independent review before compilation.

**B — compiler metadata and command freeze.** After native/tool pins are reviewed,
prepare a small exact list of driver version/dry-run/include-search queries using
synthetic or empty inputs. These execute a compiler driver and therefore require
explicit inclusion in the reviewed freeze. Verify actual frontend/assembler/linker,
include roots, output/signing behavior and runtime library selections. Do not
assume `-B` alone establishes them. Retain all outputs. Unexpected selection or
unsupported flags stop; no silent fallback to system clang/SDK.

Proposed fixed compilation settings: target `arm64-apple-macos26.5` (observed SDK
host deployment default), explicit sysroot/resource directory, pinned libc++,
`-O0 -g0`, and strict `-std=c++20 -pedantic-errors` or
`-std=c++17 -pedantic-errors`. Freeze exact include flags/order and disable inherited
CPATH/CPLUS_INCLUDE_PATH/SDKROOT/DYLD/compiler configuration; no local/user include
roots may enter. Metadata checks must resolve the exact argv before the execution
freeze is declared ready. Host ARM64 is not an Android ABI-scope reduction: both
Android ABIs and GPU/WebGPU paths remain required and unqualified downstream.

**C — complete-header matrix.** Preserve the original base matrix:

| Header | Control | Candidate |
| --- | --- | --- |
| Ordinary generated header | C++20 | C++20 and strict C++17 |
| Wire-client generated header | C++20 | C++20 and strict C++17 |

Six positive compilations, six separately supervised binary runs, and two isolated
negative compile cases per configuration (twelve negative compilations). Run each
negative only after that configuration's complete-header compile and binary pass.
Require the frozen intended diagnostic; missing includes, unrelated template
errors or general compiler failure do not count as correct rejection.

Compare callback arguments/return values, userdata identity, capture handling,
allocation/destruction and mutable/const spans across variants. Exercise all four
raw/noexcept-raw/const-member/noexcept-const-member trait sites, typed and untyped.
Before freezing the matrix, resolve empty/minimum-arity cases and the newly added
explicit template-parameter override concern in the static assessment. Any needed
supplemental probes require separately enumerated argv/expected results and a
reviewed command-count bound; they cannot replace or mask the base24 commands.
Until those witnesses are specified, the execution freeze is NOT_READY.

The unchanged `explicit(false)` or any other incompatible construct may cause the
strict C++17 gate to fail. Preserve that failure, stop dependent work and mark
remaining cases NOT_RUN. No extension acceptance, warning downgrade, changed
language flag, vendor-header patch, friendly projection or deadline increase.
No root adoption follows from a standalone pass.

## Proposed bounds, ownership and evidence

| Resource | Proposed ceiling |
| --- | ---: |
| Acquisition/preparation invocation | 300s decision /15s owned cleanup |
| Compiler metadata invocation | 300s decision /15s cleanup; exact query count frozen first |
| C++ campaign | 300s decision /15s cleanup; base24 commands plus reviewed supplements only |
| Each compiler/probe command | 30s inside its original whole deadline |
| Logs | 256KiB per command /2MiB aggregate; overflow fails |
| Archive compressed bytes | 16MiB and exact pinned SHA; no automatic retry |
| Decoded archive | 128MiB /10000 members; one regular file at most8MiB |
| Member names/links | 1024 bytes; canonical paths, duplicate/case-collision rejection |
| SDK/resource data inventory | 100000 entries /2GiB read-only combined |
| Bundled native inventory | 128 files /1GiB read-only; OS caches16 files /8GiB hashed, not copied |
| Source copies, outputs, binaries, manifests/logs | 512MiB total; explicit reservations before writes |
| One binary | 16MiB; at most96MiB across six positive cases |
| Manifest | 32MiB each |
| Coordinator RSS | 512MiB; child RSS measurements reported separately |
| Small-slice free-space admission | 1GiB, distinct from the unchanged18GiB analysis floor |

Keep original absolute POSIX CLOCK_MONOTONIC deadlines through all admission,
final verification and publication work. Cleanup allowance admits no extra
qualification work. All descendants belong to one owned process group; stop/cancel
invalidates results immediately. Cleanup exceptions retain terminal evidence and
UNCERTAIN status; no acceptance or automatic retry. Revoke only owned publication
links, retain pending bytes and foreign/prior evidence. Confirm cleanup before
admitting later work; uncertainty quarantines the attempt.

Compile/run through a reviewed sandbox denying network and writes outside the
owned attempt. Validate enforcement with trusted synthetic probes first. Freeze
and enforce selected executable/helper identities; an unexpected helper is a
blocking result. Never use an unrestricted retry. Do not delete/relocate evidence,
replace loaded inputs, modify global packages or lower storage thresholds.

## Acceptance and stop boundary

Focused prerequisite tests are permitted while implementing this proposal after
review. Cover corrupt/truncated/unsafe archives, interrupted staging, wrong or
ambiguous native inputs, missing transitive includes, patch/rewrite mismatches,
wrong compiler selection, positive/negative diagnostic classification, deadline
and cancellation/cleanup failure, late publication and changed evidence.

One reviewed final campaign retains every case, timeout and failure; independent
QA binds its results to sources, tools, generated headers and the execution freeze.
It may close standalone host compatibility only. Actual Bazel selection,
configured ARM64/x86_64 graphs, FP16 numerical/Android checks, SDK artifact builds,
emulator compatibility and later S24 performance remain separate gates.

Current deliverable is the reviewed proposal and static pins. No archive download,
patch application, compiler invocation, SDK build, emulator/S24 access, root-fix
adoption or commit is authorized by this document. Review the proposed acquisition
and preparation implementation/freeze separately before execution.
