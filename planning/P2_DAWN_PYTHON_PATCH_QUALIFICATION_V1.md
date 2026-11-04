# Dawn Python and patch qualification v1

2026-11-04. Bounded host-only prerequisite investigation. Base `dev` checkpoint:
`5bf94adf940d89950996f389f9eff5bb5a543023`. Prepare/review this protocol and exact
manifests before vendor execution. Existing task authorization covers the scoped
implementation and probes after their reviews. No root fixes or Bazel selection
claim follows. Preserve all historical source, metadata, failures and locks.

## Inputs and independent gates

1. **Wheel bytes.** Download only the two exact artifact URLs already recorded in
   `planning/fixtures/root-dependency-offline-v2/package-metadata.json`. No new
   release metadata, dependency resolution, retries, redirects or other downloads.
   Jinja2 3.1.6:134899 bytes, SHA256
   `85ece4451f492d0c13c5dd7c13a64681a86afae63a5f347908daf103ce6d2f67`.
   MarkupSafe 3.0.3 ordinary CPython313 macOS11-arm64:12029 bytes, SHA256
   `116bb52f642a37c115f517494ea5feb03889e04df47eeff5b130b1808ce7c219`.
   Stream to new immutable files, reject excess/short/hash mismatch before ZIP
   inspection. Failures remain failed; no source builds or replacement wheels.
2. **Wheel structure.** Inspect every ZIP member and CRC. Reject encryption,
   links/special files, duplicate/case-fold-colliding names, unsafe/absolute/dot-dot
   paths, non-ASCII names, `.pth`, `sitecustomize.py`, `usercustomize.py` and `.data`
   installation schemes. Canonical relative paths only. Require one matching
   dist-info directory, METADATA/WHEEL/RECORD; exact package/version and expected
   tags/purelib policy. Parse RECORD as strict UTF-8 CSV: every regular member
   appears exactly once; SHA256 URL-safe base64 digest and decimal size match;
   only RECORD's own hash/size are empty. Reject unrecorded payloads and unsupported
   hash algorithms. Preserve license records and native Mach-O identities.
3. **Dependency closure.** Compare METADATA requirements with the retained release
   metadata. Freeze the supported simple version predicates before execution:
   Jinja2 declares `MarkupSafe>=2.0` and the exact inactive literal
   `Babel>=2.7; extra == "i18n"`, with `Provides-Extra: i18n`. The selected-extra
   set is empty; reject any selected extra. Admit only that known inactive marker,
   without a general marker evaluator or Babel acquisition. MarkupSafe has no
   runtime Requires-Dist entries or extras.
   Expected Requires-Python: Jinja2>=3.7, MarkupSafe>=3.9. Any different predicate,
   marker, extra declaration, dependency or distribution layout blocks; do not approximate a
   general dependency resolver or acquire anything else. Tag compatibility is
   separate from successful imports.
4. **Candidate environment.** Pin the retained CPython3.13.13 ordinary-GIL arm64
   runtime, full files/stdlib/native inventory, modes and raw symlink identities.
   The original is the `python_3_13_aarch64-apple-darwin` repository retained from
   the configured-1 attempt. Static patchlevel/sysconfig declarations are candidate
   evidence only. Pin every bundled Mach-O load command/dependency and resolve
   loader/rpath references. System dependencies must be bound to macOS26.6.2 build
   25G83 and SHA256 inventories of the installed arm64e dyld-cache backing files;
   no copying or modifying those caches. Unknown external dependencies block.
   Also bind loader/system inspection tools. No opaque replacement interpreter.
5. **Private execution.** Copy the pinned runtime to a fresh owned attempt without
   modifying original files. Explicit transformation: replace each recorded
   `__pycache__` symlink by an empty owned directory; rewrite the root `python`
   absolute-internal alias to relative `bin/python3`; preserve all other internal
   symlinks and ordinary bytes. Reject other escaping links, `.pyc` payloads or
   unexpected files. No preexisting site packages participate: invoke candidate
   Python with `-I -S -B`, add only the two verified unpacked wheel roots through
   the frozen bootstrap. Never call pip, venv/ensurepip or a package installer.
6. **OS isolation.** Pin `/usr/bin/sandbox-exec`; all imports/rendering, patch,
   generator, compiler and callback execution uses a reviewed profile denying
   network and writes outside the attempt (except `/dev/null`). Clear inherited
   Python/DYLD/compiler-path variables; provide a minimal frozen environment and
   owned TMPDIR. If enforcement is unavailable or fails, stop. No unrestricted
   retry. Download is a distinct two-URL phase preceding network-denied probes.

## Exact allowed execution and semantics

Execution is limited to the following frozen commands and synthetic inputs. A
new freeze binds scripts, fixtures, runtime/tool/native/SDK inventories and all
command arguments. Review the implementation/freeze before running them.

- **Import/render:** one candidate process checks exact3.13.13/arm64/Darwin/64-bit,
  SOABI `cpython-313-darwin`, GIL enabled, and loaded module origins. All non-built-in
  Python modules must originate in the copied runtime, verified wheels or frozen
  probe. Require native `markupsafe._speedups`, with exact wheel-member hash;
  pure-Python fallback is not a pass. Exercise escaping, Unicode, autoescape,
  `do`, loop-controls, includes and inheritance through frozen synthetic templates.
  Use explicit expected strings and module identities; importing alone is not
  render compatibility. No notes, user templates or personal files.
- **Three patch programs:** review their complete literal Python programs and
  sequential six replacements against the qualified `api_cpp.h`. Expected counts
  `1/1/4/1/1/1` and every existing pre/postimage hash remain fixed. Execute exactly
  those three approved Python programs, using the candidate interpreter on a
  private candidate source copy, without a shell. No arbitrary macro evaluation.
  Final template SHA256 remains
  `160001ac2245ddec19c7965014a5cdb9da86937754cfdc2d2719208c4f3f31a4`.
- **Generator:** explicitly authorize the qualified
  `generator/dawn_json_generator.py`, `generator_lib.py`,
  `webgpu_docs_utility.py` and their verified template/JSON closure. Copy the
  qualified105 source files into independent control/candidate workspaces. Run
  each `headers` and `cpp_headers` target twice for each variant: eight generator
  invocations, all relative arguments matching the pinned Dawn BUILD. Require
  the actual eight generated headers, no extra outputs and byte reproducibility.
  The BUILD declares five but its commands do not filter the generator outputs.
  The exact inventory is `include/dawn/webgpu.h`,
  `include/dawn/wire/client/webgpu.h`, `include/dawn/dawn_proc_table.h`,
  `include/dawn/webgpu_cpp.h`, `include/dawn/wire/client/webgpu_cpp.h`,
  `include/dawn/webgpu_cpp_print.h`,
  `include/dawn/wire/client/webgpu_cpp_print.h`, and
  `include/webgpu/webgpu_cpp_chained_struct.h`.
  Six unaffected headers must match control exactly. BOTH ordinary and wire-client
  `webgpu_cpp.h` outputs must equal the same frozen literal transformations applied
  to their respective control generated text; no whitespace/path/output
  normalization, deletion or five-file projection to obtain a pass.
  Also prepare the 26 checked-in `include/**/*.h` files recorded in
  `source-include-inventory.json`, from the same retained qualified archive under
  an8MiB combined/1MiB per-file selection cap. Hash/validate that inventory before
  extraction; materialize only exact recorded regular files. This includes the
  genuine `include/webgpu/webgpu.h` forwarding header, enum-class bitmask helper,
  and `include/dawn/wire/dawn_wire_export.h`. Keep source include and generated
  include directories distinct; generated headers precede checked-in includes.
  Do not fabricate wrappers, delete includes, generate stubs or edit vendor headers.
  Any additional consumed include must already belong to the frozen source,
  Abseil or SDK inventories; missing closure blocks before callback execution.
- **Host callback probes:** explicitly include a pinned installed Apple clang++
  driver, its resource inputs, macOS SDK/libc++ headers and native dependencies;
  inspect/hash them without downloading/changing the toolchain. Use actual
  generated headers and pinned Abseil `Span`, not a replacement implementation.
  Compile/run one frozen synthetic harness separately for each affected header
  under control C++20, candidate C++20 and candidate C++17: six positive compiler
  invocations and six runs. Use `CppFTraits`, `CallbackInfoHelper` and `CallbackHelper`
  from the generated header with synthetic integer callback-info/converter types
  to avoid linking a Dawn implementation. Verify all four callback-trait sites:
  raw/noexcept raw and const-member/noexcept const-member, typed and untyped
  tuples, capturing and noncapturing callbacks. Check argument forwarding,
  userdata identity, callback return values, single destruction/allocation and
  mutable/const-byte spans. Exact positive outputs must agree across variants.
  Two compile-only negative cases per affected header/language variant (twelve
  negative invocations) reject capturing callbacks without mode and
  capturing callbacks with explicit userdata; credit only the expected diagnostic
  after positive header compilation succeeds. No GPU, Android or FP16 probe.
  Freeze explicit `-std=c++20 -pedantic-errors` for both C++20 configurations and
  `-std=c++17 -pedantic-errors` for C++17, without any extension-enabling or
  diagnostic-downgrade flags. The qualified template contains `explicit(false)`
  outside these patches. If the C++17 compiler rejects it or any other unchanged
  construct, retain a failed header-compatibility gate; do not accept extensions,
  patch the construct, extract a friendlier header projection or credit unrun
  callback tests. Matching C++20 cases cannot override that failure.

The `std::span` fallback adds an alias inside namespace std. Record its portability
limitations explicitly; host case agreement does not establish standard-level
equivalence, Android ABI compatibility or broad semantic approval. Do not change
that upstream pattern, add header fixes or weaken a failing probe in this slice.

## Bounds, staging and failure rules

### Actual availability amendment: Python/generation admission only

Static preparation found the assumed Abseil source directory absent. The declared
root provider is20260526.0, archive SHA256
`6e1aee535473414164bf83e4ebc40240dec71a4701f8a642d906e95bea1aea0c`,
with the root's `PATCH.abseil` and BUILD-label rewrite. No exact archive or span
header was found in the checked retained build trees/standard user-cache locations.
`cpp-prerequisite-block.json` binds that declaration, patch and observation.

The current profile is explicitly `python-generation-cpp-blocked-v1`. It admits
only complete Python/native preparation, the two wheel downloads, wheel checks,
candidate imports/rendering, three patch programs and eight generator invocations.
These stages do not consume Abseil or a C++ compiler/SDK. Preparation pins the
runtime and OS-native/inspection-tool closures; compiler/SDK/Abseil preparation
is BLOCKED and is not credited. The C++ probes described above remain the required
future matrix, with all eighteen compiler invocations and six runs NOT_RUN.

Do not download Abseil, substitute a local span implementation, run a no-Abseil
variant, or infer callback semantics from text comparison. Stop the C++ branch
before any compiler invocation and retain its typed BLOCKED status even if all
admitted independent stages pass. Full qualification remains incomplete.
Resolving the exact Abseil source/patch recipe and admitting compiler probes needs
a separately reviewed source-acquisition/preparation scope and execution freeze.
No independent Python/generation success can satisfy that missing prerequisite.

The reviewed R1/R2 complete-preparation drafts and findings remain preserved and
unexecuted. This explicit amendment requires protocol/freeze review before the
new profile runs; it does not promote those drafts or weaken any admitted gate.

| Resource | Frozen ceiling |
| --- | ---: |
| Preparation inventory process | 300s +15s owned cleanup,2MiB logs |
| Qualification process (all commands/downloads) | 300s +15s owned cleanup,2MiB logs |
| Individual vendor/compiler/probe command | 30s within the whole deadline |
| Each HTTP request | 15s read timeout within the whole deadline |
| Wheel compressed bytes | Exact134899+12029; no retries/redirects |
| Wheel entries / decoded bytes | 512 entries and16MiB combined |
| ZIP member name / member decoded bytes | 1024 bytes /8MiB |
| RECORD and metadata bytes | 1MiB each, strict bounded parsing |
| One system-inspector command | 15s /1MiB captured output; inherited owned process group |
| Combined inspector trace | 32MiB; preserve bounded output and exit/deadline evidence |
| Candidate runtime inventory | 10000 entries /128MiB regular bytes |
| Runtime or source name/link | 1024 bytes, no escaping non-cache links |
| System-cache inventory | 16 files /8GiB read-only; no cache extraction/copy |
| Toolchain/SDK/Abseil inventory | 100000 entries /2GiB regular bytes, read-only |
| Bundled native/compiler artifacts | 128 files /1GiB aggregate, separately pinned |
| Native load commands per file | 256 dependency names /32 rpaths, each at most1024 bytes |
| Native Mach-O metadata | At most16 fat slices /4096 commands /1MiB command bytes; ARM64 header and inspector evidence must agree |
| Manifest/receipt | 32MiB each; streaming hashing and serialization |
| Attempt writes, including staged runtime/outputs/logs | 512MiB total; reserve logs/outcome before writing |
| Peak worker RSS | 512MiB; reject above the ceiling |
| Small qualification capacity | 1GiB free before each process |

Current free space measured about6.3GiB; the user's earlier2.7GiB observation is
historical. Keep the separate18GiB Bazel-analysis floor. Runtime copy is about48MiB;
wheels below0.15MiB compressed; source workspaces and generated/probe outputs are
charged against the512MiB write budget. Read-only SDK/native-cache hashing does not
require copying their multi-GiB contents. Never free/move evidence automatically.

Fixed manifests include every cache/runtime/toolchain input actually consumed.
Inventory preparation executes no candidate interpreter, generator, patch or
compiler. Pin host inspection/hash tools before using them. Unknown dependencies,
missing identities, capacity/cap exhaustion or unsupported layouts fail the gate.
Resolve each before dependent code executes; no automatic cap/dependency changes.

Use existing reviewed process-group ownership and cleanup. Children inherit the
owned group; timeout/cancel invalidates the result, then cleanup must be confirmed.
No acceptance on uncertain cleanup, unexpected child exit/output or input drift.
Atomic owner publication follows final input checks and outcome persistence.
Retain failed stages and classify later stages NOT_RUN. Separate gate results:
wheel bytes, wheel structure, tag/dependency closure, candidate imports/rendering,
generated-output comparison, and bounded callback behavior. Candidate success
does not prove Bazel selects it. Passes do not qualify complete dependency graphs.

## Required host regressions and final review

Corrupted/short/oversized wheels; CRC/RECORD mismatch; unsafe/duplicate/case-fold
paths; wrong tags/GIL/interpreter/architecture; missing dependencies; altered
runtime/stdlib/native identities; escaping cache links and incorrect transformations;
unexpected patch counts/postimages; extra/nonreproducible generated outputs;
callback-output mismatch; deadline/cancellation/publication/cleanup races; scope
and network isolation failures. Use existing unittest infrastructure and synthetic
fixtures; no new test dependency. Fresh focused tests precede exact-freeze review,
then retain one bounded execution and independent results review.

Keep artifact/runtime manifests, local evidence, updated separately-versioned
dependency/patch manifests and explicit limitations. Full qualification may end in
a retained failure rather than an adopted recipe. FP16 numerics, Android interfaces,
complete configured graphs, root adoption and all device work remain separate.
No Git operations are authorized. P2 incomplete; semantic search disabled.
