# ADR 0008 implementation — source-recipe prerequisites

Started 2026-10-28; completion checks 2026-10-29.
**Build closure BLOCKED; P2 incomplete; semantic search disabled.**
This is a prerequisite audit, not a complete build manifest, execution freeze,
runtime compatibility result or device protocol.

## Authorization and completed scope

The latest user instruction `proceed` follows the independent PASS and recommended
implementation order for ADR 0008. It authorizes implementing that route, subject
to its prerequisite gates. The previously reviewed ADR/decision lock remains
unchanged: its `implementationAuthorized: false` field records the proposal's
historical state, not the later user instruction. No approval is backdated.

Implemented source-only tooling and 16 host regressions. The frozen input
catalog identifies 48 public files at the exact SDK and LiteRT revisions,
810,555 bytes total. Each is checked against SHA-256 and the retained Git-tree
blob identity. Actual collection reused already verified source bytes; the
public network retrieval steps and source trees remain in the ignored cache.
The reusable collector can recreate those inputs without fetching weights.

The current source-only audit verifies all 48 inputs and deliberately exits 2
with `buildRecipeStatus: BLOCKED`. This does not masquerade as a successful
build. Missing or changed inputs cannot produce that completed source-audit
report. Reports and existing input bytes cannot be overwritten by the tool.

## Blocking source dependency

SDK revision `5e58e9a0aef7abf7091207a8b1d1063a1c800f08` pins LiteRT revision
`9fe5be45564c868408e6514c8aabb83e211a0911` and its source-archive hash in
[`WORKSPACE`](https://raw.githubusercontent.com/google-ai-edge/LiteRT-LM/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/WORKSPACE).

That LiteRT revision declares `http_archive(name = "ml_drift", ...)` with no
`url`, `urls`, `sha256` or `integrity` field at lines 399–406 of its public
[`WORKSPACE`](https://raw.githubusercontent.com/google-ai-edge/LiteRT/9fe5be45564c868408e6514c8aabb83e211a0911/WORKSPACE).
The public GPU accelerator
[`BUILD`](https://raw.githubusercontent.com/google-ai-edge/LiteRT/9fe5be45564c868408e6514c8aabb83e211a0911/litert/runtime/accelerators/gpu/BUILD)
depends on `@ml_drift` OpenCL/common targets through its delegate targets.
The required source/identity is therefore unresolved for this inspected source
GPU route. No reviewed source override closes that gap.

Two alternatives in the pinned source are also not acceptable frozen inputs:

- [`third_party/litert_gpu/workspace.bzl`](https://raw.githubusercontent.com/google-ai-edge/LiteRT/9fe5be45564c868408e6514c8aabb83e211a0911/third_party/litert_gpu/workspace.bzl)
  imports `com/google/ai/edge/litert/litert/2.1.1/litert-2.1.1.aar` without an
  integrity hash. Its companion BUILD exports shared libraries. This is a
  binary alternative, not an established source build or approved replacement.
- [`third_party/litert_prebuilts/workspace.bzl`](https://raw.githubusercontent.com/google-ai-edge/LiteRT/9fe5be45564c868408e6514c8aabb83e211a0911/third_party/litert_prebuilts/workspace.bzl)
  references `binaries/latest/litert_prebuilts.zip` without integrity. This is an
  unresolved declaration; static inspection does not prove every JNI action
  fetches or uses it.

These observations do **not** establish that CPU compilation fails, that every
possible Android GPU route is impossible, or that a prebuilt contains the host
correction. No compiler, linker, GPU or model execution result is claimed.
LiteRT-LM's root workspace also invokes TensorFlow dependency macros. Static
inspection of the dependency's standalone WORKSPACE is not a configured Bazel
analysis of root repository selection or overrides. That selection must be
resolved before making any broader build-failure claim; no such override is
established by this audit.

The SDK's JSON-schema factory explicitly constructs source `LlgConstraintProvider`;
its LLGuidance dependencies are present. The separate prebuilt Gemma-model
provider is not automatically a mandatory JSON-schema dependency. Likewise,
the sampler has Android/static and non-Android branches; the presence of a
sampler shared-library name does not prove that binary is needed by this build.

## Recipe fields still open

| Input / check | Current state |
| --- | --- |
| SDK / LiteRT commits and inspected files | Pinned and verified; does not cover the complete transitive build closure. |
| Full SDK Cargo.lock and cargo-bazel-lock.json | Retained and verified; the diagnostic's pruned Cargo.lock is not substituted. |
| Control/candidate compiler patch | Prior host-qualified identity retained. No new SDK source tree materialized or compiled. |
| GPU source closure and provider/link maps | Blocked as described above; no binary replacement selected. |
| Bazel | Source pins 7.6.1; Bazel/Bazelisk absent from PATH. No isolated binary installed. |
| Rust | 1.87.0 host candidate exists; full SDK and both-ABI qualification not run. |
| NDK | Local 27.0.12077973 and 29.0.13846066 observed. CI selects r28b; no exact new recipe/NDK selection frozen or installed. |
| SDK / JDK / Kotlin / Python / host | Local inventory retained. Exact complete build toolchain, compiler distributions and transitive checksums remain unqualified. |
| Both ABIs | arm64-v8a and x86_64 remain required. No arm-only amendment, mixed implementation or CPU fallback. |
| Kotlin/JNI AAR assembly / notices | Separate upstream targets and sample JAR instructions inspected. Complete reproducible AAR assembly not yet established. |
| Signing / version sequence / private repository | Not frozen or implemented. No signing key read, official coordinate shadowed or SDK substitution added. |
| Tokenizer/budget/JNI/API/ELF/Gradle graph compatibility | Not run: requires the approved source-built artifacts first. |

Missing installed tools are recorded separately from the unresolved source
dependency. No space threshold or device-memory policy was invented. The local
free-space observation is not evidence that a build succeeds or fails.

## Verification and preserved evidence

- **Passed:** all 79 Python host tests, including the 16 new regressions, with
  no failures/skips; optimized-Python manifest rejection included. Valid cached
  collection rechecked all 48 inputs. All 46 historical source bindings across
  the three investigation snapshots remain unchanged.
- **Blocked:** offline prerequisite audit, exit 2. Source identities passed;
  complete source GPU/build recipe did not close.
- **Not run:** SDK/control/candidate builds, application or instrumentation APK
  builds, tokenizer parity, provider enforcement, Android/Kotlin tests, devices.
  Application/runtime code and Gradle consumption are unchanged.
- **Independent QA:** requested from the existing primary QA agent. Its final
  outcome must be recorded separately; this document is not self-approval.

Local evidence remains under `build/android-runtime-recipe-v1/`: pinned sources,
`verified-inputs/`, source trees, `prerequisite-report-1.json` and
`environment.json`. Existing evidence is preserved. One full-source archive
transfer was stopped after the tree showed large upstream model test files;
it produced no accepted source archive or build evidence. A speculative source
filename returned 404 during reconnaissance; it was not substituted or counted.
The final 48-file audit contains only verified tree paths.

The new prerequisite lock binds this audit implementation, tests, source catalog
and documentation. It is explicitly **not** a build/execution freeze. Historical
host failures, 910/41 correction results, prior SDK/device freezes, strict
validator and ADR 0008 decision lock remain unchanged.

## Next decision boundary

ADR 0008 says: “Stop if the closure needs an upgrade, another behavioral patch,
opaque prebuilt, extra native interface or unavailable release tooling.”
Accordingly, no control/candidate artifact was built from guessed inputs.

First identify an immutable, reviewable source recipe for the required GPU
dependency and a complete both-ABI AAR assembly path. A binary-only alternative,
dependency change, CPU-only or ABI-scope amendment needs explicit architecture
review before adoption. Merely hashing an opaque binary does not establish the
source/compatibility guarantees selected by ADR 0008.

After closure, freeze/review the complete recipe, build control/candidate, then
run compatibility and implementation QA. A new S24 protocol, reviewed execution
freeze and separate authorization still precede any install or native workload.
