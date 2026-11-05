# Abseil source-only preparation v1 — actual results

**Source acquisition/preparation PASSED; independent results review pending.
C++ execution remains NOT_READY.** P2 is incomplete; semantic search is disabled.

On 2026-11-05, the single authorized attempt used the exact reviewed R2 freeze
`abcf9101fbec62db93ef836115c04bf9869de00a970812a4db742b676347a90c`
and review `f343cd216ce018fcac3f9496108cffadb6236adc0290007d8e1383470ce7acf0`.
The executable implementation and its93 inputs were unchanged. Historical proposal,
review and readiness snapshots are preserved; this is a subsequent result record.

## Execution and retained evidence

Attempt: `build/dawn-abseil-preparation-v1/attempts/prepare-1/`.
The producer returned exit2, its documented successful source-preparation subset
with C++ blocked; the owned worker returned0 and cleanup was confirmed.

- One original GitHub request followed exactly the frozen302 codeload redirect.
  There were no retries or alternate sources. Transport contained2,391,476 bytes,
  matching SHA256 `6e1aee535473414164bf83e4ebc40240dec71a4701f8a642d906e95bea1aea0c`.
  The complete original transport is retained as `source.tar.gz.part`; the filename
  is not acceptance. The separate hash-bound acceptance receipt is authoritative.
- Complete validation covered1,670 archive headers:1,594 files,75 directories and
  one global PAX header. There are1,669 source entries including the root. No
  subtrees were excluded. Decoded data totaled12,636,160 bytes; gzip EOF/CRC passed.
  Inventory digest: `25427886b82c589cfcb1ed84e957ed525884b9e7aae2dfc09943d65a924171ad`.
- Both frozen hunks matched once at exact lines577 and39, without fuzz, offset,
  reverse application or alternate patch. Full-file before/after identities were
  recorded before private mutation. The unmodified source remained unchanged.
- The exact root Bash/sed command completed against24 BUILD files, making504
  substitutions and24 expected `-e` backups. One file had zero substitutions and
  still received its required backup. The full derivative inventory matched the
  planned changes/backups and every unchanged entry.
- The private source contains11,347,822 regular bytes. The final derivative has
  1,692 non-root entries and11,611,231 regular bytes, including backups. The full
  retained attempt inventory binds3,381 entries and26,443,785 logical regular
  bytes, counting both hardlink names for the publication receipts.

`stage-complete-source.json` is the complete source receipt. `planned-patch.json`,
`stage-root-patch.json`, `planned-rewrite.json`, `stage-BUILD-rewrite.json` and
`stage-final-verification.json` bind transformations and unchanged-file checks.
`command-trace.jsonl` records the single network-denied/write-restricted shell
command. `accepted-preparation.json` and `outcome.json` retain confirmed cleanup;
each still shares its owned pending-file identity. No vendor script ran.

Owned worker plus cleanup elapsed22.559s. Acquisition took7.110s, complete scan
0.093s, private materialization0.384s, clone0.465s, patch0.079s, BUILD rewrite0.147s
and final verification6.997s. Peak worker RSS was36,732,928 bytes. These stage/worker
figures are not the whole parent invocation time. The frozen parent enforced its
original absolute300s deadline before and after publication; a separate exact
parent end-to-end elapsed measurement is not present. The15s cleanup ceiling did
not extend qualification work. All seven preparation stages passed.

## License/version inspection and audit

Read-only inspection of qualified bytes found Apache License2.0, MODULE version
20260526.0, CMake project version20260526/SOVERSION2605.0.0, and config.h LTS date
20260526/patch0. `prepare-1-metadata.json` records their exact file identities.
These observations establish the retained source metadata, not legal distribution
approval, CMake execution or actual root/Bzlmod dependency selection.

The follow-up data audit replayed complete source/derivative inventory equality
and publication-link identity. Its first draft compared root-bearing source and
clone manifests directly, although their root paths intentionally differ. It
stopped before publishing an audit result. That draft/failure is preserved; R2
compares every file entry plus byte totals and passed. This was a data-audit
correction, not a preparation retry or change to frozen executable behavior.

The prior291-test tooling pass and33 focused Python3.9 pass remain relevant to the
unchanged implementation; this execution does not relabel them as fresh reruns.
No additional package was installed. `prepare-1-evidence-inventory.json` binds all
actual retained attempt entries; `prepare-1-result-lock.json` binds reports,
receipts, evidence inventory and the exact implementation/review references.

## Remaining readiness gates

This closes actual source acquisition, complete archive validation and literal
private patch/BUILD preparation, subject to independent results review. It does
not prove broad Bazel-native patch equivalence or actual selected shell/PATH.

Still NOT_READY/NOT_RUN: compiler/helper/native-inspector qualification, the
compiler-selected span/header closure, complete generated-header harness review,
strict C++17/C++20 compile/run cases, FP16/Android effects, actual Bazel suppliers,
both configured ABI graphs, SDK/AAR/APK builds and emulator/S24 workloads. No root
fix was adopted, dependency substituted, Git operation performed or historical
failure removed. The18GiB analysis floor remains unchanged; this source-only job
used the separate1GiB admission. Emulator-first and deferred S24 testing remain.
