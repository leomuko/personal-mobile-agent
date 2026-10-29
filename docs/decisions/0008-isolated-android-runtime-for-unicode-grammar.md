# ADR 0008 — Deliver corrected Unicode grammar through an isolated Android runtime

2026-10-28. Version `android-unicode-runtime-integration-v1-draft-1`.
Status: **recommended for review; decision, builds, app integration and device
execution are not authorized by this document**. P2 remains incomplete;
semantic search remains disabled.

## Context and decision requested

The isolated host correction passed 910 compiler cases and all original 41 gate
checks. The audit binds both retained QA summaries by explicit path and hash.
Historical 39/41 failures, strict validation and device failures remain unchanged. The host patch
does not change `com.google.ai.edge.litertlm:litertlm-android:0.17.1`.

Recommend a **pinned-source, separately identified SDK build for the existing
secure-QA lane**, with an unpatched source-built control before a patched
candidate. No general SDK update, new runtime manager, JNI shim, database,
conversation behavior or production capability is selected. Keep current
Kotlin/KMP ownership and vendor types behind `androidMain`.

This selects a route to investigate, not a proven build recipe or deployable
runtime. Approval and an implementation instruction would be needed before
writing build/substitution code or producing artifacts. A later S24 experiment
needs its own reviewed protocol, execution freeze and user authorization.

## Inspected source and practical limitations

All source references below are pinned to LiteRT-LM revision
`5e58e9a0aef7abf7091207a8b1d1063a1c800f08` (v0.17.1). The companion audit binds
15 inspected files and current local build/proof identities.

- [`WORKSPACE`](https://raw.githubusercontent.com/google-ai-edge/LiteRT-LM/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/WORKSPACE)
  exposes crate-universe LLGuidance patch annotations and Android Rust targets.
  The five LLGuidance patches plus the toktrie patch match the host composition.
  The full runtime Cargo.lock contains more than the diagnostic's dependency
  subset; **do not replace it with the host's pruned Cargo.lock**.
- [`kotlin/.../BUILD`](https://raw.githubusercontent.com/google-ai-edge/LiteRT-LM/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/kotlin/java/com/google/ai/edge/litertlm/BUILD)
  and [`jni/BUILD`](https://raw.githubusercontent.com/google-ai-edge/LiteRT-LM/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/kotlin/java/com/google/ai/edge/litertlm/jni/BUILD)
  define separate wrapper and JNI targets. These are not by themselves proof of
  an end-to-end AAR assembly/release recipe.
- The pinned `.bazelversion` specifies **7.6.1**. The pinned Android build guide
  calls for NDK r28b or newer; Android CI selects r28b. A future build must freeze
  one exact NDK revision/checksum rather than interpreting “or newer” dynamically.
- Source WORKSPACE pins SentencePiece **0.2.2**, whereas the existing host counting
  reference uses **0.2.0**. Tokenizer-file identity alone does not prove equal
  tokenization/detokenization or that the reference budget proof still applies.
- The inspected CMake route contains a conditional fallback
  [`gemma_model_constraint_provider.cc`](https://raw.githubusercontent.com/google-ai-edge/LiteRT-LM/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/cmake/patches/stubs/gemma_model_constraint_provider.cc)
  that returns null and warns that constraints are disabled. This does not prove
  all CMake builds lack constraints. It makes that route unselected here and
  requires positive provider/linkage evidence for any chosen build.

No source build, artifact generation or native workload was performed for this
ADR. Compatibility with the published AAR is unproved; a matching tag is not a
reproducible-build certificate. Missing release tooling, unresolved toolchains,
provider stubs or dependency drift block this route rather than justify fallback.

## Artifact strategy: three separate identities

| Artifact | Purpose and acceptance boundary |
| --- | --- |
| Published 0.17.1 | Retain exact current AAR/member hashes as the rollback/reference runtime. Do not overwrite its Maven cache, coordinates, files or historical evidence. |
| Source-built control | Same pinned SDK source and frozen build recipe, original six patches, no Unicode correction. Establish the feasibility of the build/packaging path and expose differences from the published runtime. |
| Source-built candidate | Same recipe, source and runtime dependencies as the control; add only the reviewed Unicode compiler patch and its explicit experiment opt-in. Distinct immutable identity and evidence. |

Build full, internally assembled AARs with matching wrappers and JNI libraries.
Do not overlay a host library, drop an independently built `.so` over a different
SDK, use duplicate-class/native `pickFirst` rules or load two SDK copies in one
process. Public API/JNI signatures must match; control/candidate non-native entry
payloads must match after documented archive-metadata normalization. Any wrapper
code difference beyond deliberate packaging/provenance metadata blocks review.

Keep the existing **arm64-v8a and x86_64** artifact scope. Both must be built from
the same recipe and patch identity; do not combine patched ARM with stock x86.
S24/GPU evidence would cover only its actual ABI/backend. If an arm64-only
feasibility scope becomes necessary, review that change explicitly first.

The host patch's crate path prefixes differ from Bazel patch application roots.
A header/path-only derivative may be needed. Bind both patch hashes and prove
identical hunks, unchanged existing patch order and exact materialized compiler
hashes: control `bcae5ae216a14848b2d1c5a9a1b7636aabf734d1adf7df07cc7b47b339980751`,
candidate `5a8f39a3688800ec86a80be4eeeca518991b905a1a3bf8435266cfbb1c12ef5c`.
No fuzzy rebasing. Preserve the full SDK Cargo registry lock. A generated Bazel
repository-lock change may record added patch metadata, but must not change
resolved crate versions, checksums or features. Unexpected drift requires review.

## Build-closure gate before artifacts

After separate authorization, first produce a reproducible build manifest:

- Complete source/dependency commits, archives and checksums; original full
  Cargo.lock and Bazel repository lock; patch order and materialized source hashes.
- Bazel 7.6.1, Rust 1.87.0 candidate toolchain, exact NDK/Clang/SDK/JDK/Kotlin
  revisions, host platform/image digest, target triples, API levels, link/feature
  flags, wrapper/JNI targets, AAR assembly steps and notices.
- Required LiteRT/GPU dispatch dependencies, ELF SONAME/DT_NEEDED entries, C++
  runtime ownership and output inventory for both ABIs. Link maps/build actions
  must identify the corrected LLGuidance implementation; no stub or duplicate
  provider may satisfy the build.

Rust 1.87.0 is host-qualified, not yet qualified for the full SDK build. Exact
NDK/JDK/container details and complete AAR assembly remain unresolved prerequisites
for this decision. Stop if the closure needs an upgrade, another behavioral
patch, opaque prebuilt, extra native interface or unavailable release tooling.
Freeze and review that build manifest before executing the build recipe; do not
claim the recipe complete by guessing missing release steps. No downloads or
system/toolchain installations are authorized by this ADR itself.

## QA-only Gradle consumption and request identity

Use a content-addressed private repository with distinct control/candidate
coordinates, never `mavenLocal()`, a mutable AAR path or shadowing official Maven
coordinates. Artifact selection requires an explicit QA property and verified
manifest; default selection stays the published SDK.

Apply substitution only to verified **secureQa application and instrumentation
compile/runtime graphs**, accounting for the transitive SDK from
`shared.androidMain`. Avoid global substitution across the KMP Android target.
Use variant/configuration attributes and resolved-graph tests, not requested task
name matching as a security boundary. Require one resolved SDK module per
eligible consumer graph and one runtime copy per ABI across the combined app/test
packaging closure. The tested application APK owns the SDK/JNI runtime; the
instrumentation APK must not embed duplicate SDK classes or another native copy.
The retained instrumentation APK contains no native libraries. Reject unknown
consumers or an incomplete graph allowlist. Prove ordinary debug/release and host-test
consumers retain the published SDK. No new manager, vendor types in common code
or runtime library swapping is needed.

This narrowly isolates a QA artifact choice. It does **not** implement deferred
BUILD-001, remove the existing release native libraries, change release ABI
policy, authorize distribution or promote the pressure profile to ordinary use.
Keep secure-QA identity, package IDs, branding and stored-data boundaries.

The old constraint admission factory binds old schema hashes and must keep
rejecting new bytes. Introduce a separately versioned QA experiment identity
through the existing request boundary when integration is authorized; bind its
runtime build, exact schema/pack and existing generation/context validity.
Do not overwrite old allowlists or make the opt-in available to source text or
normal chat. Default-off behavior remains the normal path. Unsupported options
or unknown runtime identities reject explicitly, with no unconstrained fallback.

## Compatibility checks, ordered before any qualification claim

| Gate | Required evidence |
| --- | --- |
| Source/control closure | Reproduce control and candidate artifacts from frozen inputs, with recorded build provenance and preserved failures. Confirm only approved patch/packaging differences. Bit-identical published binaries are not assumed. |
| Host regression | Preserve 910 compiler and original 41 outcomes and default-off masks/transcripts. Bind any additional boundary corpus before claiming or replaying it. Keep the strict validator and all historical snapshots unchanged. |
| Tokenizer/template bridge | Compare rebuilt runtime raw vocabulary pieces/IDs, EOS/stop configuration, normalization, detokenization and representative actual token IDs against pinned reference data. Revalidate budget-v2 and one-token negative proofs against the rebuilt implementation, not just the unchanged model file. Any mismatch blocks dependent experiments; do not silently downgrade SentencePiece or alter the model. |
| Kotlin/JNI/ELF | Compare API descriptors, coroutine/callback/cancel/close contracts, native registration/symbol signatures, ABI/API levels, page alignment, dynamic dependencies, notices and GPU linkage. Static presence of a diagnostic string is not enforcement evidence. |
| Gradle/APK isolation | Resolve all named QA app/test graphs and ordinary graphs; inspect merged classes/native entries, permissions and ABI inventories. Test default, explicit-control, explicit-candidate, missing/tampered manifest and invalid-scope configurations. No duplicate SDK or mixed ABI implementation. |
| Request/lifecycle | Preserve single ownership, encoder release before Gemma, out-of-band cancellation, late-callback invalidation, complete deadline accounting, quarantine and Models-panel isolation. No native admission before confirmed cleanup. |
| Device compatibility, later | Only under a newly reviewed/authorized protocol: establish source-control loading/generation/cancellation/cleanup first, candidate default-off continuity, then corrected schema enforcement. Retain raw outcomes, resource observations and first-block stopping. No old request authorization is reusable. |
| Rollback | Verify artifact selection reversal and reinstall/restart procedure without uninstall, storage reset or DB migration. Device execution of rollback is itself part of the separately authorized session. |

Existing build/test results do not satisfy new-runtime compatibility. Host native
provider checks may establish source behavior but not packaged Android/GPU
behavior. A new runtime remains unqualified until the applicable gates pass.

## Rollback and failure handling

Rollback is an explicit **build-time return to the retained published SDK**,
with a reviewed rollback APK and fresh process. It is not runtime fallback.
Keep control, candidate and rollback manifests/APKs immutable, with verified
private installation copies and run-specific supervision. Verify both app and
instrumentation APK correspondence before installation.

Use a nondecreasing QA version-code sequence so replacement can preserve app
data; do not rely on uninstall, `pm clear`, key reset or an unapproved downgrade.
No DB schema or model-asset format changes belong to this work. Preserve source
library, conversation, drafts, encryption keys, private model and evidence.

Before replacement, invalidate requests, cancel/release under existing ownership
and await confirmed cleanup. On uncertainty retain quarantine and require the
existing controlled QA process termination/restart path; never hot-swap a loaded
JNI library. Retain failed manifests/outcomes and classify blocked, failed and
not-run checks. A rollback must not turn a failed candidate into a passing result.
No automatic backend, schema, deadline, memory-policy or network fallback.

## Authorization and exit boundary

1. This ADR/audit receives independent architecture review and an explicit user
   decision before build/integration implementation begins.
2. An authorized implementation would first close/freeze the build recipe, then
   build control/candidate and complete host/static/packaging checks. Independent
   implementation review follows; failed prerequisites block dependent steps.
3. Only then may a separately versioned native protocol define exact request
   scope, identities, timing/resource policy, acceptance and rollback checks.
   Review its execution freeze and obtain separate S24 authorization before any
   install, model load or native workload. No request count or deadline increase
   is approved here. Retain 2048 context/512 output, serial ownership, current
   resource safeguards, five-second search and separately scoped native ceilings.

A successful experiment would establish only its stated compatibility/enforcement
scope. Semantic accuracy, retrieval enablement and complete P2 acceptance remain
separate. This ADR changes documentation only: no implementation, runtime build,
SDK substitution, APK installation, device access, tests or commits performed.
