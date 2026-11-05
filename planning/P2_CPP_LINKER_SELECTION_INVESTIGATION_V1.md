# P2 linker-selection investigation v1 — preparation contract

**Preparation only. No native probes are authorized or run in this slice.**
Preserve metadata-1, R1's admission rejection and metadata-2 unchanged. Native
compiler/helper qualification remains BLOCKED. P2 is incomplete; semantic search
is disabled. No application SDK/dependency/validator change or Git operation.

## Witness and supported uncertainty

Metadata-2 passed all 16 inspector queries, version and resource checks. The next
native command exited 0 and returned exactly `ld\n`; the old absolute-linker
validator rejected it. Twelve remaining queries are NOT_RUN; cleanup is confirmed.
The trace already contains a pinned `-B` prefix and controlled PATH. The linker
payload is present and hash-pinned, which alone does not establish lookup success.

Upstream LLVM 21.1.0 is explanatory material, not the source identity of Apple's
clang-2100.1.1.101 binary. No source-to-binary equivalence is claimed. In that
upstream [Driver.cpp](https://github.com/llvm/llvm-project/blob/llvmorg-21.1.0/clang/lib/Driver/Driver.cpp#L2360-L2367),
the program-name query uses general program lookup. That lookup searches prefixes
and program paths using executability checks, then PATH, finally returning the
unresolved name. This is distinct from selecting a linker for a job. The observed
bare name is consistent with unsuccessful lookup; it does not identify the cause.

Upstream [ToolChain.cpp](https://github.com/llvm/llvm-project/blob/llvmorg-21.1.0/clang/lib/Driver/ToolChain.cpp#L998-L1064)
offers `--ld-path` for explicit selection and checks executability even for an
absolute value. Thus this option is a candidate, not a guaranteed workaround.
Upstream [Unix Path.inc](https://github.com/llvm/llvm-project/blob/llvmorg-21.1.0/llvm/lib/Support/Unix/Path.inc#L566-L598)
uses read/execute permission checks plus regular-file checks. A sandbox interaction
is a hypothesis: the retained run has no syscall/errno trace proving it. The
[Darwin linker builder](https://github.com/llvm/llvm-project/blob/llvmorg-21.1.0/clang/lib/Driver/ToolChains/Darwin.cpp#L538-L542)
uses linker-path selection when constructing jobs. These references guide a
bounded experiment; they do not qualify the Apple implementation.

Read-only bounded scanning of the pinned compiler records only offsets of option
and access-related strings. Those occurrences do not establish active option
support, call relationships, a sandbox cause or permission to run a tool.

## Proposed seven-query investigation

New proposed identity: `build/dawn-cpp-linker-v1/probe-attempts/linker-1`.
The directory stays absent during preparation. No remaining metadata-2 query is
resumed. Exact argv, environment and sandbox bytes are in the new probe freeze.

1. One diagnostic repetition of the program-name query in a separate campaign.
   Only `ld\n` or the exact pinned absolute path plus newline is recognized. Both
   remain diagnostic; neither qualifies linker choice. Unexpected output stops.
2. Six `-###` plans, one per original control/candidate, ordinary/wire and C++17/
   C++20 profile. Each adds exactly one `--ld-path=<pinned absolute ld>` to the
   otherwise unchanged flags. There is no alternative flag retry. Every plan must
   select the declared linker literally and pass the complete old closed grammar.

The existing metadata-2 validator remains unchanged and continues rejecting the
saved bare name. The separate diagnostic classifier never converts that failure
to a pass. New plan validation rejects relative paths, aliases, unknown helpers,
extra jobs, response files, diagnostics and conflicting selection flags. A partial
set cannot claim agreement across profiles. No discovery expands permitted output.

Keep the exact compiler-only sandbox: deny process-exec except pinned clang; do
not allow ld, inspector, shell, a directory or an access-only permission exception.
No unsandboxed comparison is proposed. If explicit selection itself fails its
executability check, retain uncertainty and stop. No link/compile/binary executes;
`-###` only constructs plans. No include-search or complete-header case is included.

## Limits, preparation and later execution gates

Preserve 300s from future parent entry through validation/publication, separate
15s cleanup and 30s per query including pin/post-checks; 256 KiB command output,
2 MiB trace, 512 KiB worker logs, 512 MiB worker RSS and 32 MiB manifests. Existing
six-tree/OS-cache/native-input limits, 1 GiB small admission and 18 GiB analysis
floor remain unchanged. No dependency, compiler, SDK, target or backend narrowing.

This preparation verifies the actual 133 old input rows and all six inventories,
pins metadata-2 receipts and adds the isolated sources, tests, references and
witness. Static scanning uses at most 2 MiB blocks plus a fixed overlap, 256 MiB
input and 16 retained offsets per each of three fixed byte strings. An explicit
omitted-occurrences flag prevents complete-inventory claims; no limit is raised.
The first development scan's ceiling failure remains retained. No new binary
or upstream source acquisition is needed. References are explanatory citations,
not reproducibly built Apple sources or new toolchain inputs.

Host regressions cover ambiguity, conflicting flags, declaration/plan agreement,
profile coverage, incomplete/reordered results, source drift, changed pins,
bounded scanning and preservation of the old rejection. No native workload runs.

The probe freeze is a **proposal identity**, not an executable freeze. It explicitly
records `nativeExecutorImplemented=false`. Independent QA reviews it before any
runner is implemented. A later owner/runner must retain immediate stop, confirmed
cleanup/quarantine, no retry, bounded output and publication, and add race/deadline/
cleanup tests. Its exact reviewed execution freeze and canonical read-only admission
must precede separate user authorization. Missing proof blocks dispatch.

## Exit and acceptance limits

Deliver a supported diagnostic or explicit uncertainty, a pinned seven-query
proposal, host regressions and independent preparation QA. No native success is
claimed here. Even a later complete probe pass would establish only declared vs
planned linker agreement within the six profiles, not C++ compatibility, actual
linker execution, root/Bazel/Android behavior or complete compiler qualification.
All 72 C++ cases, SDK builds and device workloads remain outside scope. Emulator
validation remains downstream of reviewed runtime artifacts; S24 remains deferred.
