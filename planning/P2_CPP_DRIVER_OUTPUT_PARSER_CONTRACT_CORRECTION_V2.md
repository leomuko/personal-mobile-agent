# P2: pinned Apple driver-output parser correction v2, draft 1

2026-11-07. **Proposal only.** No parser/executor implementation, native collection,
flag change or warning-policy change is authorized by this document. Independent
proposal review precedes implementation. P2 remains incomplete; semantic search
is disabled. Historical access-1 remains FAILED / INCONCLUSIVE.

## Objective and preserved baseline

Correct the complete output contract for one profile: the pinned Apple Clang
21.0.0 (`clang-2100.1.1.101`) **control ordinary C++20, arm64 macOS26.5 synthetic
linker dry-run**. This contract does not extend to the original seven probes,
C++17/20 compilation matrix, Android ABIs, SDK builds or app inference.

Preserve all existing parsers, executors, frozen driver arguments, environment,
warning policy, dependency/toolchain pins, access-1 raw bytes, FAILED outcome and
QA seals. Implement a future v2 parser separately; do not edit v1 or retrofit a
pass into the old attempt. Its raw exit0 and printed marker are observations,
not a qualified positive or evidence of policy contrast. Case two stays NOT_RUN.

The complete QA inventory has **41 unmodeled frontend rows**: one repeated flag
and one orphan-looking linker-version value need structural treatment. The
proposal accounts for these as **39 frontend decisions**, plus three linker
decisions and rendered include order. `mismatch-decisions.json` records all
**43 treatments** individually. `contract-proposal.json` contains every token,
not just the first failing option: **155 frontend / 24 linker words**, grouped
into **107 / 16 ordered units**, with 27 exact target-feature values.

## Complete, closed representation

Use the declarative full sequence in `fixtures/linker-parser-contract-v2/`.
Every unit specifies exact spelling, joined/separate form, value and position.
All counts are exact; no generic last-option-wins, duplicate suppression,
broad `-W*`/`-f*` acceptance or unconsumed tokens. Preserve the existing 256 KiB
raw-output ceiling and strict UTF-8. The lexical contract defines the four exact
header records, exactly two POSIX-shlex job records, permitted whitespace, control
rejection and the sole typed negative diagnostic. Parsing never executes shell
syntax or rewrites retained output.

Only externally frozen context slots may vary: the fresh canonical attempt,
derived source/output/dump/debug/coverage paths, one bounded temporary-object
name shared across both jobs, and the exact identity-pinned synthetic target.
The marker is identity-pinned, not OS-enforced immutable. No concurrent writer
is assumed; pre/post checks cannot exclude transient replacement. Marker
preparation or mutation is outside this slice.
SDK/resource/toolchain and source include paths remain literal pins. Compare
paths lexically; do not resolve aliases or dot components. Retain the v1 bounded
object basename rule, require its literal attempt parent and enforce cross-job
identity. The sample context belongs to historical access-1; it is not a future
native attempt identity. A future context requires a separately reviewed freeze.

### Forms, values and multiplicity

- Joined `-mframe-pointer=non-leaf`, `-ffp-contract=on`, `-funwind-tables=1`,
  `-debugger-tuning=lldb`, `-target-sdk-version=26.5` and `-stdlib=libc++`
  are distinct exact forms. Do not accept the old separate spellings or other
  values. `-target-linker-version 1267` is a separate pair; `1267` is not a source
  input or proof that a particular linker executed.
- Consume each warning and each vendor selector at its declared location. Pin
  the six vendor selector values individually. Do not infer their behavior from
  names or accept arbitrary selectors. The two occurrences of
  `-fno-modulemap-allow-subdirectory-search` are allowed **only** at the two
  declared positions; counts1/3 or repositioning fail.
- Require the entire ordered list of 27 positive target features, including the
  seven missing from v1: `altnzcv`, `ccpp`, `complxnum`, `flagm`, `jsconv`, `sb`
  and `specrestrict`. Opposite signs, duplicates, omissions, additions or reorder
  fail. This is emitted ISA-setting evidence, not hardware or generated-code
  qualification.
- Preserve singleton action/language/optimization/exception/no-implicit-module
  settings. In particular, pin `-fcommon`; do not substitute `-fno-common` or
  silently equate either setting. All other recorded units are equally mandatory.

### Rendered includes and linker inputs

Match the recorded option-kind/path pairs in this exact rendered cc1 order:
three `-isystem` paths (libc++, Clang resource, SDK include), the SDK
`-iframework` path, then three user `-I` paths (generated headers, checked-in
headers, prepared Abseil). Reject changes to kinds, literal paths or order.
The original driver argv remains unchanged. **Rendered token order alone is
not proof of effective header-search precedence or header compatibility.**

The linker plan additionally requires literal `-O0`, exactly one
`-mllvm -enable-linkonceodr-outlining` pair, and the joined `-F` path to the
already pinned SDK frameworks directory. No arbitrary forwarded backend options
or extra framework directory is admitted. Pin arch, platform tuple, sysroot,
real-toolchain `libLTO.dylib`, output, matching object, `-lc++`, `-lSystem` and
Clang runtime archive. No extra archive, object, library or helper is allowed.
The positive supplier is the literal synthetic target; the negative supplier is
literal bare `ld` solely as diagnostic output, never resolved or qualified.

## Semantic treatment and explicit decision requested

These are meaningful settings, not ignorable decorations. The decision catalog
separates documented upstream behavior, retained SDK declarations and unresolved
Apple-specific behavior. For example, `-ffp-contract=on` affects floating-point
fusion; frame-pointer/unwind choices, sized deallocation, common storage,
visibility, stack checking, ISA features and linker forwarding can affect code
or linkage. Their exact values must remain visible and checked.

The requested bounded decision is: **permit exact, individually enumerated
unresolved Apple tokens as opaque observed configuration evidence for this
planning-only diagnostic**. They remain consumed mandatory units, not discarded
text. This does not certify harmlessness, C++ behavior, ABI compatibility or
runtime build readiness. If independent review does not accept that containment,
implementation remains blocked pending the specified missing semantic evidence.
No such acceptance is assumed from this draft.

References include public Clang documentation and separately hashed retained SDK
LLVM sources. The latter declare LLVM revision
`ab547095ead5464dc024d66264d9b8a987f429f3` and an existing patch recipe; this slice
does not certify a complete LLVM tree or equivalence to the Apple binary.
Public documentation is explanatory, not proof of vendor implementation.
Unresolved selectors and vendor-specific effects remain explicitly listed in the
reference/decision catalog. No hidden policy bypass or semantic waiver follows. Supplemental Apple ld64
source explains `-O0` and framework search handling; upstream source explains
the outliner operand. These sources are not matched to the selected Apple linker.
The forwarded LLVM operand can affect optimization and must remain exact; its
presence does not prove that LTO or outlining ran.

## Offline replay and regression acceptance after implementation

The observed positive fixture is a byte-exact copy of the 5,622-byte capture,
SHA-256 `73369da40748b9f990dc0feb20805797c168e0253d32dd7323fc940caab1dfea`.
The negative fixture is **constructed test data**, with an inserted exact error
and bare `ld` supplier. It is not a native observation or a repaired old result.
Its provenance is frozen separately.

After proposal approval and implementation authorization:

1. Replay the entire positive under a new v2 parser; record offline structural
   compatibility separately from the unchanged v1 rejection and failed outcome.
2. Execute all 32 regression families in `regression-plan.json`, parameterized
   across every corresponding unit/value. Mutate semantic scalars, warnings,
   vendor selectors, features, counts, paths/order, linker inputs and suppliers;
   reject extra flags/helpers, malformed output and mismatched object references.
   Accept only the explicitly bounded consistent context/object rebases.
3. Check the synthetic negative as a typed exit1 rejection. In any later bridge,
   the command, worker and terminal remain FAILED and no acceptance is published.
   A positive/negative contrast cannot qualify the real linker or prove the
   original cause. Unknown output remains a first-failure stop.
4. Preserve all old source/guard/artifact hashes. Retain ownership, cancellation,
   deadline, cleanup, quarantine and publication protections. No new dependency
   is needed. A future runnable bridge must be separately reviewed and frozen.

This preparation checks fixture identity, full token reconstruction, proposal
coverage and preservation only. **No v2 parser exists yet; these checks are not
parser replay, mutation-test passes or native compatibility evidence.** The old
502-host-test result is historical and was not rerun for proposal-only changes.

## Gates and deliverables

Deliver the reviewed prose, complete grammar delta, semantic/reference catalog,
fixture provenance, regression plan, preparation audit and proposal lock. An
independent review must bind the exact proposal snapshot. Approval covers that
contract only; implementation and its subsequent QA remain distinct.

A later native collection requires a new reviewed execution freeze, fresh attempt
identity, complete admission and separate authorization. Preserve the same driver
arguments and environment modulo reviewed context rebasing, exact profiles,
real-ld execution denial, marker controls, 300-second decision / 30-second command
/ separate15-second cleanup limits, byte/RSS bounds and unchanged storage floors.
No retry, resumed second case, warning-policy change, compilation, Bazel/runtime
build, model/device workload or Git mutation belongs to this proposal slice.
