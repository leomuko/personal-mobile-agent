# ML Drift compatibility — analysis only

Implements the reviewed `ml-drift-source-compatibility-analysis-v1` amendment.
This directory cannot build an SDK/APK or run a device/model. P2 remains incomplete;
semantic search remains disabled. Independent review and an exact input freeze
precede every configured analysis attempt.

## Inputs and preparation

`planning/fixtures/ml-drift-compatibility-v1/` records the candidate, amendment,
source/toolchain catalog and analysis freeze. Historical locks remain unchanged.

- SDK source: all 1,355 original Git blobs at the pinned revision, including
  assets and 45 genuine LFS pointers. No exclusions or invented placeholders.
- ML Drift: all 883 source blobs, pinned archive SHA and Apache-2.0 license.
- Bazel 7.6.1 macOS ARM64 binary, checked against its official release checksum.
- Exact NDK r28b, 28.1.13356709; official SDK metadata SHA-1 and retained SHA-256.
- Temurin Java 17.0.20.1+1 and unchanged installed Android SDK platform/build-tool
  selection copied into an isolated root. Full tree inventories bind their bytes.

An upstream source archive can contain smudged LFS payloads. These are **not**
the Git pointer blobs. `prepare.py collect-sdk` fetches every frozen Git blob,
using the Git blob API for pointers; `canonical-sdk` recreates the complete,
deterministic source tar. Unresolved LFS payloads remain a build/runtime blocker
if selected; configured analysis does not turn them into verified binaries.

```sh
python3 scripts/diagnostics/ml_drift_compatibility/prepare.py collect-sdk \
  --catalog planning/fixtures/ml-drift-compatibility-v1/source-toolchain-catalog.json \
  --sha256 CATALOG_SHA_FROM_REVIEW --destination build/ml-drift-fresh-sdk
python3 scripts/diagnostics/ml_drift_compatibility/prepare.py canonical-sdk \
  --catalog planning/fixtures/ml-drift-compatibility-v1/source-toolchain-catalog.json \
  --sha256 CATALOG_SHA_FROM_REVIEW --source build/ml-drift-fresh-sdk \
  --output build/ml-drift-fresh-sdk.tar.gz
```

`unpack` verifies an archive checksum before extracting to a **new** directory,
rejects traversal/duplicates/escaping symlinks and preserves failed preparations.
`inventory` records complete regular-file bytes/modes and internal symlinks.
No global toolchain installation or package-manager execution is performed.
Paths in an existing freeze bind this machine's exact prepared inputs. A fresh
preparation needs its own reviewed freeze; do not rewrite old paths/evidence to
claim the old freeze authorizes it.

## Runner and guards

```sh
python3 scripts/diagnostics/ml_drift_compatibility/analyze.py \
  --freeze planning/fixtures/ml-drift-compatibility-v1/analysis-freeze-v2.json \
  --freeze-sha256 SHA_FROM_INDEPENDENT_FREEZE_REVIEW \
  --attempt build/ml-drift-compatibility-v1/attempts/NEW_ATTEMPT_ID
```

Only `query` and `cquery` argv are constructed. User/system rc files and arbitrary
overrides are excluded; the pinned root rc is explicit. Existing root setup
executes before the repository receipt. An inventory failure cannot authorize
insertion. Identical reuse requires the **full** receipt, exact archive/type/
mapping and no transformations/unknown attributes. Known list defaults normalize
Bazel tuple containers without accepting changed values. The helper independently
rechecks this policy, and never imports the standalone MODULE dependency graph.

After successful inventory, the guarded overlay may be analyzed. Otherwise the
two configured commands use only the unamended root and record that limitation.
Per-node output requests actual CPU/platform/crosstool/features; requested ABI
flags are not themselves proof of successful configuration.

The retained R2 attempt failed Java server startup before workspace evaluation;
see `docs/P2_ML_DRIFT_COMPATIBILITY_RESULTS_V1.md`. This command documents its
reproduction, not permission or a recommendation for an identical retry. The
original `analysis-freeze.json` was rejected and remains preserved.

Each invocation has a frozen 300-second decision deadline, at most 16 MiB retained
combined stdout/stderr, and bounded owned-group cleanup. Pipes bound retained
bytes before writing. Signal ownership starts before spawn and remains through
cleanup; interruption cannot leak a child or silently admit the next command.
Successful completion is observed before deadline credit. Uncertain cleanup
stops further admission. Reports, stdout/stderr and prior attempts are preserved.

Exit 1 is an input/interruption/infrastructure failure. Exit 2 means the bounded
investigation returned evidence that still requires configured-graph review;
it is never runtime qualification. Configuration, compilation and runtime
acceptance are distinct. A complete runtime recipe and its review must precede
control/candidate builds. Emulator and later S24 gates remain separate.

## Tests and retained failures

```sh
python3 -m unittest discover -s scripts/tests -p 'test_ml_drift_compatibility.py' -v
```

Synthetic tests exercise metadata conflicts, tuple reuse, signals during spawn
and active supervision, capped output, late completion, immutable reports and
archive safety. They do not execute Bazel or a model. The initial four runner
findings, tuple-default follow-up and source retrieval/preparation failures are
retained under `build/ml-drift-compatibility-v1/`; fixes do not replace them.
