# ADR 0008 source-recipe prerequisite audit

The user authorized ADR 0008 implementation with `proceed` after the architecture
review. This tool implements **only source-input collection and prerequisite
inspection**. It cannot build an AAR/APK, select an SDK, sign an artifact, invoke
ADB or run a model. No device execution was authorized in this slice.

Use the KMP build-governance/platform-bridge and testing guidance; Python tests
cover provenance and failure paths. Application source sets remain unchanged.

## Reproduce

Python 3.9+ standard library only. No new Python packages, Rust dependencies or
system tools are required. From the project root:

```sh
python3 scripts/diagnostics/android_runtime_recipe/audit.py collect
python3 scripts/diagnostics/android_runtime_recipe/audit.py check \
  --report build/android-runtime-recipe-v1/prerequisite-report-new.json
python3 -m unittest discover -s scripts/tests -p 'test_android_runtime_recipe.py' -v
```

`collect` explicitly downloads only 48 frozen public **text** inputs, 810,555
bytes total, using immutable Git revisions. It never fetches upstream model
test files, GPU binaries, SDK binaries or a mutable URL from the inspected files.
Each file has frozen length, SHA-256 and Git blob identity. Existing corrupted
inputs fail rather than being repaired or replaced automatically. Partial
collection is not a successful audit. Delete nothing to rerun: valid inputs are
rechecked and reused, and reports require a new path.

`check` is offline. Exit **2** means the sources verified but the recipe is
**BLOCKED**. Exit **1** means missing/tampered inputs, changed declarations or
another infrastructure error. Only `collect` returns zero after source collection;
that does not qualify a build. No command publishes a build-ready or device-ready
proof. Critical checks remain active under `python -O`.

The small declaration inspector handles the specific literal declarations in
these hash-bound inputs; it is **not** a general Starlark evaluator or Bazel
dependency resolver. Changed declarations require a new reviewed audit. This
does not prove a compiler/linker failure or the reachability of every optional
repository from every native target.

## Observed prerequisite

The pinned LiteRT dependency's public `WORKSPACE` declares `ml_drift` without a
source URL or integrity identity. The public GPU accelerator targets depend on
that repository. The alternative `litert_gpu` declaration imports a published
2.1.1 AAR without a checksum; `litert_prebuilts` points at an unhashed `latest`
archive. These are retained observations, **not approved replacement inputs**.

The source Gemma/LLGuidance factory is present. The optional prebuilt
`libGemmaModelConstraintProvider` declaration alone is **not** evidence that the
JSON-schema path requires that binary. No such dependency claim is made here.

See [the prerequisite report](../../../docs/P2_ANDROID_RUNTIME_BUILD_PREREQUISITES.md)
for source references, remaining recipe fields and the next review boundary.
