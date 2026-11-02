# Root-dependency compatibility amendment v1

2026-11-02. **Proposal only; not adopted.** Scope: the retained SDK/LiteRT/ML
Drift root closure. No Bazel retry, SDK artifact build, app substitution, device
work, version upgrade or commit is authorized by this document. Both blockers
can affect both ABIs; first-error ordering is not ABI exclusivity.

## Baseline and boundaries

Keep SDK `5e58e9a0aef7abf7091207a8b1d1063a1c800f08`, LiteRT
`9fe5be45564c868408e6514c8aabb83e211a0911`, and ML Drift
`2d7fbbd45d8940b5fc7cea1f79e59d9d796164e2`. Preserve the full Cargo/Bazel
locks, approved Unicode compiler patch, strict validator, Java-21 server and
Java-17 compilation environment, ARM64/x86_64 scope and GPU/WebGPU dependencies.

The reviewed prior attempt established a root receipt and selected the candidate,
then stopped on undefined Dawn and FP16's default-label mismatch. Preserve that
attempt, caches, result locks and FAIL history. There are no complete configured
graphs, consumed-interface qualification or Android runtime artifacts.

## Proposed FP16 bridge

Preserve the existing **`@FP16//:FP16`** supplier at revision
`4dfe081cf6bcd15db339cf2680b9281b8451eeb3`, archive SHA
`d973501a40c55126b31accc2d9f08d931ec3cc190c0430309a5e341d3c0ce32a`.
Do not edit its source/BUILD or register another supplier under that name.

Add a new, reserved deterministic repository `edge_fp16_bridge_v1` exporting
exactly a public `fp16` alias to `@FP16//:FP16`. Change **only ML Drift's** proposed
mapping from `@fp16 -> @FP16` to `@fp16 -> @edge_fp16_bridge_v1`. Preserve every
other mapping and original ML Drift source byte. This bridges the default target
name as well as the repository name, without changing the actual header supplier.

Before declaration, reject missing/different FP16 supplier, revision, BUILD,
headers or alias target. Reject any preexisting bridge unless its complete
definition and immutable identity match; never shadow another root repository.
Keep the existing guarded ML Drift receipt/admission policy and extend its frozen
expected mapping explicitly. The old analysis freeze remains historical.

### Consumed interface and gates

The retained ML Drift tree's direct FP16 use is in `ml_drift/common/types.h`:
`#include "fp16.h"`, `fp16_ieee_from_fp32_value(float)` and
`fp16_ieee_to_fp32_value(uint16_t)`. The root header exports matching static-inline
signatures through `include/fp16.h -> fp16/fp16.h`. The fallback `half` stores
`uint16_t bits` with `alignas(2)`; ARM native-half builds bypass this header branch
but still retain the declared Bazel dependency. The standalone MODULE's older
FP16 expectation is not evidence that the selected root revision is equivalent.

Static signature correspondence makes the bridge a candidate, not a passed API
gate. After adoption/implementation authorization, require:

- Forced-fallback host probe: constructor/assignment/float conversion, size and
  alignment; consumed header/include closure and declarations must be unchanged.
- Exhaust all 65,536 binary16 inputs against an independent IEEE conversion
  reference: exact finite/zero/infinity round trips; NaNs remain NaNs under a
  frozen classification policy, without assuming payload round-trip preservation.
- Fixed float32 boundary fixtures: signed zero, subnormals, ties-to-even,
  normal/subnormal transitions, largest finite value, overflow, infinities/NaNs.
- Both actual Android cross-toolchain compile/interface checks, including ARM's
  native-half and portable fallback paths. No host result certifies Android ABI.

No replacement FP16 revision, native-branch shortcut or changed numeric tolerance
may be selected merely to pass these tests. Failures require review.

## Proposed Dawn recipe

The immutable archive/commit, exact upstream BUILD, patch strings, pre/postimages
and tool identities belong in the dependency and patch manifests. Bounded source
inspection is a separately reviewed prerequisite; missing data remains blocking.
Retain tag `v20260720.160313` as provenance, resolve its commit, and prefer that
immutable commit archive plus full SHA over the upstream macro's mutable tag URL.
The strip-prefix change must be explicit. Do not choose another Dawn version.

The reviewed source-only v1 attempt **failed**, after 67.871 seconds, at its
60,000-member cap; cleanup was confirmed. Its retained ref resolves to
`466163dc7bf94146ac9a921b86cc3ed08184ebb8`. The 28,737,866-byte archive has
observed SHA `4ee2687c6f4b2b1f30e70028763201c898a763989f7aafcb284e197ab0a4735b`.
These are candidate identities, not a passed whole-source receipt. The walk did
not finish; no PyPI metadata requests followed the failure. Wheel hashes and the
actual configured generator runtime remain unresolved. **This recipe is not
adoption-ready.** The separate offline-v2 proposal is an unapproved next step,
not a continuation or automatic limit expansion under v1.

Register a guarded root `dawn` only after checking absence or exact full identity;
conflicting URLs, hashes, revisions, BUILD, patches, toolchains, mappings or
unknown attributes reject. This does not import Dawn/ML Drift's standalone MODULE
dependency graph. The qualified BUILD source is
`@litert//third_party/dawn:dawn.BUILD` (that package exports the file). Do not copy
the unqualified `//third_party/dawn` label into the SDK root context.

Preserve and review **all three** upstream Python patch commands. Their literal
replacement operations must each match exactly the frozen expected preimage
count, in original order. Record the original commands, six literal replacements,
template input/output hashes and a human-reviewable resulting diff. A missing
match, extra match, changed source or unexpected output blocks adoption; no silent
no-op, fabricated repair or modified template version. Never execute arbitrary
command text during proposal inspection.

The retained, unqualified template subset permits static reconstruction only:
the six sequential literal replacements match **1, 1, 4, 1, 1, 1** sites. The
four-site operation affects raw, noexcept-raw, member and noexcept-member callback
traits. The proposed diff preserves all four replacements; it does not silently
reduce them to one. Full source qualification, semantic review and generated/API
checks remain required. No upstream patch command or generator was executed.

### Python and transitive closure

The custom BUILD loads XLA's Python rule wrapper, generates the C and C++ WebGPU
headers from Dawn JSON/templates, and depends on `@jinja2` and Abseil's span
target. Retain exact generated-output names and inspect the actual generator's
imports before considering closure complete. Generated headers are not ordinary
checked-in headers and must not be replaced with placeholders.

The existing custom pip repositories declare Jinja2 **3.1.6** and MarkupSafe
**3.0.3**, but resolve their interpreter as unqualified system `python3`. A direct
alias to their binary wheel outputs cannot establish compatibility with the
generator's actual Python runtime. Assess a **Dawn-private** wheel provider at
those same versions, with complete wheel checksums and explicit existing hermetic
Python-3.13 target, after its identity/ABI is verified. Preserve the current root
pip providers and locks. No package-version or SDK Python change is proposed.

If needed, a separate reserved alias repository exports the default `jinja2`
target to the exact private wheel `:pkg` target. Scope its repository mapping to
Dawn. Check wheel-rule target names/provider types against the pinned rules;
validate transitive MarkupSafe wheel ABI against the actual generator interpreter.
Do not silently assume system and hermetic Python are interchangeable.

Patch interpretation and generator execution are separate identities: bind the
exact patch Python launcher/binary/stdlib dependencies, the generator toolchain,
Jinja/MarkupSafe wheels, XLA wrapper/rules_python and template/JSON inputs. If any
required artifact/tool identity remains unresolved, the recipe is not ready.

## Implementation and focused test plan — after amendment review

1. Add isolated root-adapter tooling beside the existing diagnostics, with a new
   protocol/version. Existing frozen runners, SDK files, caches and locks stay
   unchanged. Materialize only reviewed bridge/recipe files at immutable paths.
2. Validate complete source/patch/tool manifests and all repository receipts before
   registration; no shadowing, mutable build-output inputs or unverified defaults.
3. Test absent/exact/conflicting FP16, bridge, Dawn and Python providers; altered
   header/build/template/tool bytes; missing/duplicate replacement matches; bad
   label context; changed wheel ABI; stale locks; cancelled preparation and late
   publication. Retain all ownership/signal/deadline/output/cleanup regressions.
4. Only after implementation review and capacity resolution, freeze the same root
   inventory and two ABI analyses, requesting supplier/ABI/features and actual
   Java/Python toolchain evidence. No complete graph means no build readiness.
5. Plan separately authorized generator/interface probes: deterministic headers,
   required include paths/types/symbols, cancellation and reproducibility under
   the actual host Python and both Android cross toolchains. A configured graph
   alone does not establish generated-header or numerical correctness.

## Storage/execution and decision

Current free space is about 2.7 GiB. Preserve the **18 GiB analysis floor** and
capacity-plan the measured Dawn archive/expanded tree, wheel inputs, three fresh
roots, staged workspaces and at least a documented reserve before proposing an
execution freeze. The bounded source-only inspection fits within its separate
512 MiB output reservation; it does not authorize a new analysis attempt.

No automatic evidence deletion, cache overwrite or move that invalidates old
absolute-path freezes. Any user-directed archival relocation needs a hash-verified
mapping preserving original manifests and outcomes. No reduced ABI/backend scope.
Retain 300-second analysis decisions, 15-second cleanup and 16 MiB combined logs.

Exit this proposal with an independently reviewed manifest and explicit unresolved
gates, not an implementation-ready claim while immutable recipe/API evidence is
missing. Adoption, implementation, analysis freeze/execution and eventual runtime
artifact recipe remain separate steps. P2 incomplete; emulator validation follows
reviewed artifacts; S24 deferred; semantic search disabled.

Companion deliverables: `P2_ROOT_DEPENDENCY_IMPLEMENTATION_TEST_PLAN_V1.md`,
`P2_ROOT_DEPENDENCY_STORAGE_EXECUTION_PLAN_V1.md`,
`P2_ROOT_DEPENDENCY_OFFLINE_INSPECTION_V2_PROPOSAL.md`, and
`fixtures/root-dependency-amendment-v1/{dependency-manifest,patch-manifest}.json`.
