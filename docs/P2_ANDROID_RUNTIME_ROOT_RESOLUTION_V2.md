# Root dependency resolution v2 — route not admitted

2026-10-29. **Unamended route rejected as build-ready; Android feasibility remains
unproved. P2 incomplete; semantic search disabled.** No builds or devices ran.

This supersedes the *next-step interpretation*, not the frozen bytes, of the v1
prerequisite audit. The standalone `ml_drift` declaration was insufficient to
establish root selection. This investigation follows the actual root macro
sequence and records what remains unresolved instead of inferring a build error.

## Reproducible source trace

The new catalog adds 16 pinned text inputs to the previous 48: the SDK TensorFlow
patch, TensorFlow repository helpers and workspace macros, and six files from
an **unadopted** ML Drift snapshot. All 64 source bytes are verified by frozen
length/SHA-256/Git-blob identities. Newly fetched TensorFlow blobs were compared
with pinned Git directory metadata; the ML Drift files match its complete tree.

| Step | Exact source and finding |
| --- | --- |
| Root mode | SDK `.bazelrc` has `common --noenable_bzlmod`; selected root is LiteRT-LM, not LiteRT. |
| Root order | SDK `WORKSPACE` invokes `tf_workspace3`, then 2, 1, 0. |
| TensorFlow pin | `d9a8da74b4c3de28a39ab34ad007838d6bc30c67`, archive SHA-256 `cd46b37c0f722d5a48c0accc9618cc5684c553332b913091023703461f318d9b`. Existing SDK `PATCH.tensorflow` affects protobuf/kissfft/darts build files, not these repository definitions. |
| Vendored selection | TensorFlow `workspace3.bzl` creates `xla` and `tsl` with `tf_vendored` at `third_party/xla` and its `third_party/tsl`. These are vendored at the TensorFlow pin, not independently selected XLA HEAD. |
| Precedence | TensorFlow `third_party/repo.bzl` returns when `native.existing_rule(name)` exists. Repository mappings belong to their particular declaration; they are not global aliases. |
| Direct declarations | Across the eight inspected TF/XLA workspace files, 103 literal archive declarations contain no `ml_drift` registration. XLA workspace files are diagnostic inputs, not automatically executed merely because XLA is vendored. |
| Root LiteRT selection | Root SDK explicitly pins LiteRT `9fe5be45564c868408e6514c8aabb83e211a0911`, archive hash `5dbb113744e103f899c7b1b7c5479126b36a0b7414c3d971185c1f02041bfa39`; its patch command rewrites `@//third_party` labels. No root `ml_drift` override is established by the inspected files. |
| Dependency WORKSPACE | LiteRT's standalone WORKSPACE declaration is not automatically evaluated as the SDK root's repository setup. Its missing URL therefore is not itself a configured-root error. |
| Engine target | `engine_impl` aliases `engine_advanced_impl`; the target is present. No missing-engine-source finding. |
| Constraint provider | Source factory calls `LlgConstraintProvider::Create`, whose BUILD depends on `llguidance_cc`. This is positive source-path evidence, **not** proof of linkage inside an unbuilt AAR. The optional Gemma provider is not inferred mandatory. |

Bazel documents that external repositories' WORKSPACE files are ignored and
transitive dependencies must be provided by the root:
[Bazel's retained official workspace semantics](https://docs.bazel.build/versions/main/build-ref.html).
Pinned source references:
[SDK root](https://raw.githubusercontent.com/google-ai-edge/LiteRT-LM/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/WORKSPACE),
[TF workspace3](https://raw.githubusercontent.com/tensorflow/tensorflow/d9a8da74b4c3de28a39ab34ad007838d6bc30c67/tensorflow/workspace3.bzl),
[TF repository helper](https://raw.githubusercontent.com/tensorflow/tensorflow/d9a8da74b4c3de28a39ab34ad007838d6bc30c67/third_party/repo.bzl).

### Limits of this trace

The syntax inspector does not execute Starlark, expand every downstream macro,
resolve conditional/transitive external repositories or perform a configured
Bazel query. **The actual configured GPU repository selection is still not
proved.** Absence among direct inspected declarations is not proof of absence
throughout all downstream macros. Both negative and ambiguous observations
block admission rather than inventing a repository winner.

Accordingly, reject the unchanged recipe as *ready*, not as universally
impossible. A complete manifest cannot honestly be published from these inputs.
No CPU/JNI compiler or linker failure is claimed.

## Identified alternative, not adopted

Public [ML Drift source](https://github.com/google-ai-edge/ml-drift/tree/2d7fbbd45d8940b5fc7cea1f79e59d9d796164e2)
exists. The observed immutable snapshot is
`2d7fbbd45d8940b5fc7cea1f79e59d9d796164e2`, with Apache-2.0 LICENSE and
`ml_drift/cl:opencl_wrapper` plus common GPU targets. Exact LICENSE, MODULE,
README, Bazel version and two BUILD hashes are in the v2 catalog.

Its standalone `.bazelversion` is 8.0.0 and MODULE declares `>=8.0.0`. This differs
from SDK 7.6.1 / disabled Bzlmod. No released-SDK correspondence, compatible API,
dependency feature set, two-ABI toolchain closure or rebuilt GPU output has been
established. The snapshot was inspected after resolving its public commit ID;
no floating branch is a proposed executable dependency.

Recommendation: review a source-pinning compatibility amendment before adopting
that source. Do not silently load its MODULE dependencies into the SDK workspace
or change the selected Bazel. Other options and their tradeoffs are in the
[versioned amendment](../planning/P2_ANDROID_RUNTIME_EMULATOR_FIRST_AMENDMENT_V1.md).

## Wrapper/JNI/packaging and outstanding recipe

The SDK Kotlin `litertlm-android` target builds wrapper classes separately from
JNI `litertlm_jni`. JNI reaches the engine alias and source constraint factory.
The upstream sample guide specifies a class JAR and a native `lib/<ABI>` JAR;
this does not define a full matching Maven AAR/POM release pipeline.

A recipe must still freeze: Bazel 7.6.1 or a separately approved change; full-SDK
Rust compatibility at the 1.87.0 candidate; exact NDK/Clang/SDK/JDK/Kotlin/Python
identities; all selected root repository inputs and licenses; untouched full SDK
Cargo.lock/cargo-bazel lock; original six patches plus path-only normalized
Unicode derivative; exact compiler-before/after hashes; link flags/maps and
non-stub provider evidence; both ABI ELF dependencies/alignment; wrapper/JNI API
checks; deterministic AAR assembly, notices, immutable publication and signing.

Keep source-built control, patched candidate and stock rollback distinct as
specified by ADR 0008. No partial manifest with guessed fields counts as a
complete recipe. Tokenizer/budget and actual-provider validation require the
resulting artifacts and remain unrun. The existing host 910/41 result is
historical evidence, not evidence for an Android artifact.

## Tool and results

`scripts/diagnostics/android_runtime_resolution/trace.py` performs collection or
offline syntax inspection only. It verifies the previous prerequisite lock
before importing its helpers. The report records `configuredRootResolutionProven:
false`, rejects route readiness and grants no emulator/device admission.
Reports cannot replace existing evidence. Exit 2 means a completed bounded
inspection with route admission blocked; exit 1 means an input/infrastructure
failure. Critical checks use explicit exceptions, including under `python -O`.

Reproduce from root (Python 3.9+ standard library):

```sh
python3 scripts/diagnostics/android_runtime_recipe/audit.py collect
python3 scripts/diagnostics/android_runtime_resolution/trace.py collect
python3 scripts/diagnostics/android_runtime_resolution/trace.py inspect \
  --report build/android-runtime-resolution-v2/root-trace-report-new.json
python3 -m unittest discover -s scripts/tests -p 'test_android_runtime_resolution.py' -v
```

Local evidence is retained under `build/android-runtime-resolution-v2/`:
source bytes, pinned directory/tree metadata, `root-trace-report-1.json` and
independent corroboration notes. No whole upstream model repository was installed.

**Slice outcome:** documented rejection of the unamended route as build-ready,
with a recommended reviewed source alternative and a proposed emulator campaign.
No dependency adopted, source runtime built, app changed, device accessed, data
deleted or Git operation performed. Independent final QA follows the new lock;
earlier corroboration alone is not final implementation approval.
