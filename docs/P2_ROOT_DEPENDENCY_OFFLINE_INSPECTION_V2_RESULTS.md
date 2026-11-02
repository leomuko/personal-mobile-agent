# Offline Dawn inspection v2 results

2026-11-02. **Complete source validation passed; package metadata identified.**
This is source-data qualification, not root-fix adoption or Android compatibility.
Independent results review is recorded separately against the result lock.

## Reviewed preparation and execution

The protocol's initial review found two specification gaps (ancestry storage and
reserved PAX semantics); the corrected protocol passed. Implementation R1 then
failed independent synthetic redirect-count and regular-file-ancestor probes.
R2 rejects all redirects and uses the same fixed bitset for every non-directory.
Both findings closed before execution. The protocol copies, source snapshots,
failed reviews and R1 freeze remain preserved.

- Full host suite: **197 tests passed**, no failures/skips, recorded in
  `build/root-dependency-offline-v2/host-tests-2.log`.
- Independent R2 review: **25 focused tests and original probe replays passed**;
  all **31 execution inputs** verified. Review scope includes the final protocol,
  scanner and exact freeze; it does not approve root fixes or dependency adoption.
- Executed freeze:
  `933b2a16ba6666b297e5e0fbb92b616e829f3a65182e378e2fe8fbe45b8557de`.
- Attempt: `build/root-dependency-offline-v2/attempts/offline-1`.
- Exit **0**, `ANALYSIS_COMMAND_SUCCEEDED`, cleanup **confirmed**.
  Owned worker plus cleanup: **4.53266125 seconds**. This includes source scanning
  and both metadata requests; separate phase durations were not recorded. Parent
  preflight/input hashing is outside that measurement. No latency claim for build
  analysis, generation, retrieval or Android follows.

## Complete source receipt

The same retained archive bytes were reused. No Dawn download or tag resolution:

- Commit: `466163dc7bf94146ac9a921b86cc3ed08184ebb8`.
- Compressed bytes: **28,737,866**; SHA-256
  `4ee2687c6f4b2b1f30e70028763201c898a763989f7aafcb284e197ab0a4735b`.
- **101,388 headers**: 99,113 files, 2,095 directories, one symlink, one global
  PAX header and 178 local PAX headers. No excluded subtree or retained member list.
- Decoded stream: **366,325,760 bytes**, including headers/padding/end markers;
  ordinary file payload: **287,917,300 bytes**. Neither is a measured extracted
  filesystem footprint. Gzip EOF/CRC and zero-only tar tail validated.
- Inventory SHA-256:
  `feebeca8fadcf2b8ebed70485bd17dddc2b0f080332602e8d00d07d5b4adaf50`.
- Selected source: **105 files / 966,916 bytes**. All 89 files from failed v1's
  subset match the new qualified copies. This does not promote v1 to success.
- Fixed duplicate/kind storage: **33,816,576 bytes**. Framed metadata:
  **29,623,797 bytes**; total charged metadata hash inputs: **88,346,516 bytes**.
- Source-phase peak RSS sample: **64,241,664 bytes**, below the 256 MiB ceiling.
  The worker also checks RSS after metadata, but does not persist a separate final
  RSS sample; do not present the source sample as measured whole-job peak.

All three upstream patch commands were reconstructed through static literal
operations on the qualified template. Counts remain **1/1/4/1/1/1**, and every
pre/postimage hash matches the preserved proposal. Final hypothetical template
hash remains `160001ac2245ddec19c7965014a5cdb9da86937754cfdc2d2719208c4f3f31a4`.
No patch command or vendor generator was executed; four-site semantics and actual
generated-header/API checks remain pending.

## Python metadata qualification

Only the two frozen PyPI release-JSON GETs were made, after source validation.
Responses and their own SHA-256 identities are retained separately from these
**advertised wheel** identities:

| Candidate | Advertised bytes | Advertised SHA-256 |
| --- | ---: | --- |
| `jinja2-3.1.6-py3-none-any.whl` | 134,899 | `85ece4451f492d0c13c5dd7c13a64681a86afae63a5f347908daf103ce6d2f67` |
| `markupsafe-3.0.3-cp313-cp313-macosx_11_0_arm64.whl` | 12,029 | `116bb52f642a37c115f517494ea5feb03889e04df47eeff5b130b1808ce7c219` |

Wheel bytes were **not downloaded or verified**, and no package was installed.
The CPython-3.13/macOS-arm64 filename is metadata, not proof that the generator
selects that interpreter/toolchain or that its transitive runtime is compatible.
The scanner uses the pinned system Python 3.9 runtime with selected module hashes;
that non-hermetic runtime is separate from prospective generator execution.

## Updated manifests and remaining gates

New dependency/patch manifests are under `planning/fixtures/root-dependency-offline-v2/`.
They derive from, and leave unchanged, the earlier proposal. Metadata-advertised
hashes have their own fields; `wheelSha256` remains null for byte qualification.
The three-command/four-site patch is still unadopted and semantically unapproved.

Still blocked/not run: actual wheel-byte and interpreter/toolchain qualification,
generated headers, FP16 numeric/Android checks, root registration/guards and
complete configured graphs. No root fixes, wheel installation, Bazel retry,
SDK/AAR/APK build, emulator/model/S24 workload or commit occurred.

The small inspection kept its 1 GiB preflight and 512 MiB write ceiling. The
separate **18 GiB analysis floor is unchanged and remains unmet**; no evidence was
deleted or relocated. P2 incomplete; emulator validation downstream, S24 deferred,
semantic search disabled. A complete source receipt does not complete the recipe.
