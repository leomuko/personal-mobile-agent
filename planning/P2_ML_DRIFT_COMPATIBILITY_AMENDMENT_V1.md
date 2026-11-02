# ML Drift source-pinning compatibility amendment v1

2026-10-29. Identity `ml-drift-source-compatibility-analysis-v1`.
**Proposed for independent analysis-scope review before implementation/adoption.**
User handoff authorizes preparation and, after amendment review, isolated
configured analysis. No runtime artifact, app SDK substitution, emulator/model
workload, S24 access, semantic enablement or Git operation belongs to this slice.

## Exact candidate and preserved baseline

- SDK: `google-ai-edge/LiteRT-LM@5e58e9a0aef7abf7091207a8b1d1063a1c800f08`.
- LiteRT dependency: `9fe5be45564c868408e6514c8aabb83e211a0911` as selected by SDK.
- ML Drift: `google-ai-edge/ml-drift@2d7fbbd45d8940b5fc7cea1f79e59d9d796164e2`.
- ML Drift source archive SHA-256:
  `074ba94182ba04c7fbdba3f76faea1370417e49f0397552ea6e3344d90d5d77f`,
  1,651,213 bytes. All 883 source blobs were compared with its complete pinned
  Git tree. License: Apache-2.0; exact LICENSE identity is in `candidate.json`.
- Preserve Bazel **7.6.1**, SDK dependency versions/features and **both** ABI
  configurations: `android_arm64` / arm64-v8a and `android_x86_64` / x86_64.
- Preserve full SDK Cargo.lock and cargo-bazel-lock.json, original patches,
  approved host Unicode patch and all historical validator/evidence snapshots.
  The Unicode patch is not applied to the baseline analysis graph.

ML Drift's standalone MODULE declares Bazel >=8 and selects its own dependencies.
That MODULE is **not imported** into the SDK WORKSPACE. Neither compatibility nor
incompatibility is inferred from its version declaration. Analyze the source
BUILD interfaces with the SDK's existing Bazel/rules/dependencies. If additional
versions, rules, source patches or feature changes are required, retain the
failure and stop for a new decision; do not silently merge the MODULE graph.

## Exact overlay and precedence policy

Use only an isolated verified copy of the full SDK root. Production Gradle,
machine-local caches and historical source preparations remain unchanged.

1. Run unmodified root repository inspection first. Root configuration, rc files,
   existing rules, archive identities and relevant mappings must be retained.
2. Only after a successful inspection establishes `ml_drift` absent may the
   analysis overlay append the frozen archive declaration **after** the SDK's
   existing WORKSPACE setup. Do not insert a declaration ahead of TensorFlow
   macros and thereby change their `existing_rule` precedence.
3. An already present declaration may be reused only if archive hash, immutable
   URL, explicit `tar.gz` archive type, strip prefix and repository mapping match
   exactly; record that reuse. Retain the complete existing-rule receipt and
   reject unapproved patches, commands, BUILD/WORKSPACE replacements, remote file
   injection, authentication settings and unknown attributes; matching projected
   archive fields alone is insufficient.
   Any differing or unverifiable declaration blocks analysis. Inspection failure
   is not evidence of absence and cannot authorize insertion.
4. A guard macro rechecks `native.existing_rule("ml_drift")` at execution. Reject
   conflicting declarations; never `override_repository` an existing root rule.
5. The only proposed mapping is `{"@fp16": "@FP16"}`, matching the inspected
   LiteRT standalone declaration. Record it even if unused in selected targets.
   No global mapping or new alias is permitted. Missing `dawn`, rules, FlatBuffers,
   Abseil, platform, Rust or other repositories blocks; do not invent new pins.

`candidate.json` freezes seven analysis targets: matching Kotlin wrapper/JNI,
the LiteRT GPU accelerator and four directly named ML Drift GPU targets. The
configured GPU closure determines their **actual** transitive dependencies;
source-name or unconfigured-target existence does not qualify them.

## Analysis execution freeze

After amendment review, implement/test the runner, then freeze its code and exact
host toolchain **before** execution. Bind SDK/ML archives, Bazel 7.6.1 executable
and official checksum, host/JDK/NDK/SDK identities, platform flags, environment,
rc inputs, repository-cache policy, target expression and expected classifications.
No server, SDK or runtime executable runs during amendment preparation.

The SDK input may be a canonical local tar of **every exact pinned Git blob**,
with no exclusions, deterministic metadata and a verified complete tree. Genuine
LFS pointer blobs remain explicit unresolved binary objects, not substitutes for
their payloads or proof of artifact closure. This transport does not authorize
selected unresolved LFS inputs. It avoids accepting smudged archive binaries as
if they matched the Git pointer identities.

Use Bazel batch mode and an owned analysis process tree/output root. Commands
are restricted to repository `query` and configured `cquery`; no `build`, `run`,
`test`, APK assembly or model execution. Repository configuration/helper actions
must be distinguished from compiling the requested runtime artifacts.

Use a dedicated repository cache. Permit only the pinned root's declared
dependency setup; record resolved download identities. Any mutable/unhashed
required input is not a successful closed recipe. Do not use remote build
caches, credentials, user rc files or arbitrary command-line overrides.

Freeze a finite host-analysis deadline and log-size bound per invocation;
terminate only owned batch processes, confirm cleanup and preserve all attempts.
These host limits do not change search/model deadlines or memory policies.

Run/retain reports for both ABIs even if both are blocked at the same root
prerequisite. Do not repeat a failed attempt with changed inputs under the same
identity. Network/toolchain failures are infrastructure blocks, not proof of
source incompatibility. Unsupported rules/missing configured targets are
classified separately with exact diagnostics and inputs.

## Acceptance and exit

Configuration success requires an affirmative resolved repository receipt and
configured dependency graph for all frozen targets under each ABI. Record
repository origins, selected platforms/features, labels/configuration IDs and
the interfaces consumed by LiteRT. Code zero with empty/mismatched output fails.
Compare consumed headers/types/signatures; only compilation can establish C++
compatibility, and only later runtime tests can establish native behavior.

Add regressions for altered archives/toolchains, missing inputs, conflicting or
ambiguous rules, unsupported ABIs/commands, process cleanup and immutable failed
attempts. Independent implementation QA follows results. Historical sources and
full SDK locks must remain unchanged.

If configured closure succeeds, prepare the **complete** control/candidate/stock
rollback build recipe for separate review before artifact builds. Otherwise
document the precise failure class and recommend one architecture alternative
with tradeoffs; do not turn infrastructure failure into an incompatibility claim.

The emulator-first campaign remains contingent on reviewed artifacts/freeze.
S24 GPU/memory/thermal/battery acceptance remains deferred and separate. P2 is
incomplete; semantic search remains disabled throughout this investigation.
