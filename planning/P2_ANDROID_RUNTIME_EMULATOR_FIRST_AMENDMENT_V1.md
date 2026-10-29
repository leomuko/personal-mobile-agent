# ADR 0008 validation-order amendment v1

2026-10-29. Identity `android-runtime-emulator-first-amendment-v1`.

Records the user's **emulator-first direction** for the next runtime route.
Preserves ADR 0008 and every prior decision/audit lock as historical bytes.
This amendment does not approve a new dependency, complete a build recipe,
authorize installation/workloads or enable semantic search. P2 is incomplete.

## Direction now recorded

1. Resolve and independently review source/build closure first.
2. Build/inspect separate control, candidate and stock rollback artifacts only
   after that gate. Both arm64-v8a and x86_64 remain required.
3. Use an emulator first for the concrete reviewed compatibility, constraint,
   cancellation, cleanup and data-preserving rollback campaign.
4. Keep **S24 access and testing deferred**. Later physical-device GPU, memory,
   thermal and battery acceptance is a separate campaign and authorization.

Emulator success cannot establish S24 compatibility, performance, thermals,
available RAM or battery behavior. An arm64 emulator on the present host does
not execute or qualify x86_64 code. Both ABI inventories/API/linkage must be
checked; x86_64 execution needs a compatible designated runner. No ABI is removed.

Keep the current ownership, cancellation, cleanup/quarantine, budget, privacy,
resource and strict-validator contracts. No ordinary memory thresholds change.
CPU validation on an emulator, if explicitly in its reviewed campaign, is one
test lane; it is not a CPU-only artifact or a replacement for GPU qualification.

## Present route decision and recommended next decision

**Reject the unamended pinned-source route as build-ready.** The new static
root trace does not establish the complete configured repository selection or
full source/packaging closure. This is an admission decision under ADR 0008,
not a claim that compilation was attempted and failed or source can never exist.

Recommend a **separately reviewed source-pinning compatibility investigation**
using the public ML Drift source, before revising the build recipe. The observed
candidate is `google-ai-edge/ml-drift@2d7fbbd45d8940b5fc7cea1f79e59d9d796164e2`,
Apache-2.0. Six source identities are recorded in the new input catalog. This
candidate is **not adopted**, release-matched or API/ABI-qualified. Its standalone
Bazel 8 / Bzlmod declarations differ from the SDK's Bazel 7.6.1 / WORKSPACE mode;
external-module declarations alone do not prove its BUILD files cannot be used
under Bazel 7.6.1, nor that they can. Do not silently upgrade Bazel or transplant
the dependency graph.

Before implementing such an amendment, review an exact root repository mapping,
source revision/archive checksum/license, required targets and transitive locks.
Preserve full SDK Cargo locks and the approved compiler patch. Demonstrate the
chosen compatibility layer needs no unreviewed behavioral/API/dependency change.
If it does, stop for that architecture decision rather than rebasing by guesswork.

Alternatives requiring their own decisions:

| Alternative | Why it is not selected here |
| --- | --- |
| Source-built JNI plus retained GPU binaries | Could reduce rebuild scope, but admits opaque/mixed-provenance components contrary to the current source-closure boundary; needs precise artifact provenance and compatibility review. |
| A newer officially source-complete SDK | Changes the pinned runtime/API/tokenizer/toolchain baseline; requires fresh auditing and all compatibility proofs. |
| Deterministic application citations from evidence IDs | Avoids this grammar deployment route but changes the output contract; needs a new protocol and validator, preserving historical failures. |
| CPU-only or arm64-only artifact | Narrows the agreed scope; not adopted. |

The recommended source investigation preserves the desired grammar/citation
contract and may close the specific dependency gap with reviewable source.
Public source availability is not evidence of compatibility or a promised result.

## Artifact and rollback invariants

Keep published stock 0.17.1 as immutable rollback/reference. A future source-built
control uses the original six patches; a future candidate adds only the approved
Unicode backport and scoped experiment opt-in. Require distinct content-addressed
coordinates/manifests, matching wrappers/JNI, positive real-provider linkage,
both-ABI checks and unchanged ordinary debug/release SDK resolution. No global
substitution, official-coordinate shadowing or hot-swapping loaded JNI.

Use monotonic QA version codes and matching app/test APK pairs. Rollback must
cancel/invalidate requests, confirm cleanup or quarantine, then replace the QA
APK and start a fresh process without uninstalling, clearing storage or resetting
keys/model assets. No personal data or model binaries belong in Git.

## Gates

The [root-resolution findings](../docs/P2_ANDROID_RUNTIME_ROOT_RESOLUTION_V2.md)
and [proposed emulator campaign](P2_ANDROID_RUNTIME_EMULATOR_CAMPAIGN_V1.md) are
deliverables for independent review. Neither is an execution freeze. A complete
recipe must bind exact toolchains, sources, dependencies, patch materialization,
AAR assembly, notices, packaging, signing and rollback identities before builds.
No S24 action is part of this slice.
