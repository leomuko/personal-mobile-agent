# ML Drift compatibility analysis v1 — host-profile blocker

2026-10-29. **Frozen analysis profile incompatible; ML Drift compatibility
UNPROVED. No complete runtime recipe. P2 incomplete; semantic search disabled.**

## Scope and review gates

User handoff authorized the ML Drift source-pinning investigation after amendment
review, with emulator-first direction and no artifact builds, devices or commits.
Amendment R1 and revised R2 received independent PASS from the retained primary
QA agent. R2 binds archive type, full repository-attribute guards and complete
Git-blob source transport; the earlier reviewed bytes remain preserved.

The initial runner review found four defects; follow-ups identified tuple-default
normalization and a cleanup-ceiling mismatch. All six findings were fixed with
regressions before any Bazel execution. Their rejected sources, reports and
original freeze remain retained, not replaced by later passes.

| Gate | Reviewed identity / result |
| --- | --- |
| Amendment R2 | `f65b253e2a404ab3967d0503d30f3141174e24a3ceb4323c73b7bb1afbb2b3a8`, PASS |
| Original analysis freeze | `8214f94feb8d2591da396ae103f87d76438ed1c2657b92bde2e74a27be57d031`, rejected for cleanup contract mismatch; retained |
| Executed analysis freeze R2 | `5da08299b99c822ffcfcb3b9b5eae85594a782fc0f6d66b7621ca5d3c582b4aa`, independent PASS before execution |
| Pre-execution QA | All 87 input files and 77,275 complete tree entries rehashed; 28 focused tests passed |
| Full host suite | 121 tests passed, zero failures/skips; includes the 28 focused tests |

Independent review approved the bounded runner/freeze, not a passing execution
result or runtime compatibility. Final independent review of the executed
evidence could not complete because the retained QA agent reported its usage
limit. **That review remains BLOCKED/PENDING; no final-results PASS is claimed.**

## Exact prepared inputs

- SDK `5e58e9a0aef7abf7091207a8b1d1063a1c800f08`: all 1,355 Git blobs,
  563,721,250 source bytes, zero exclusions. Canonical source tar SHA-256
  `912c0330e29469c17c404e18b536c43aa2940bc7308ef548d5a0381180ee49fc`.
- All 45 genuine LFS pointers remain pointer bytes. Their payloads were not
  qualified or substituted; selected unresolved payloads would block closure.
- ML Drift `2d7fbbd45d8940b5fc7cea1f79e59d9d796164e2`: 883 verified source
  blobs; archive SHA-256 `074ba94182ba04c7fbdba3f76faea1370417e49f0397552ea6e3344d90d5d77f`;
  Apache-2.0. Standalone MODULE dependencies were not imported.
- Bazel 7.6.1 macOS ARM64 executable SHA-256
  `45cca81a839d7495258b19ee8371c7b891f350586ef37b9940f7b531eb654cc8`,
  matched its official release checksum.
- NDK r28b / 28.1.13356709: official package size/SHA-1 matched; SHA-256
  `76d33f698302dce31f59b86e9ed82e87b530f10cc52cbaeb44207473689be309`.
- Selected server JVM: installed Temurin 17.0.20.1+1. Private Android SDK preserves
  the installed platform/build-tool selection. SDK hermetic Python remains 3.13.
  Full SDK/NDK/JDK/private-SDK tree inventories bind 1,355/8,364/456/67,100 entries.

Source/toolchain preparation remains under `build/ml-drift-compatibility-v1/`.
Two bounded source-archive retrieval failures, a denied hard-link preparation
attempt and runner test/review failures are retained as infrastructure or test
failures. Full source was recovered only from complete verified blobs plus
missing pinned Git blobs; no partial archive became accepted SDK evidence.

## Actual commands and results

Retained attempt: `build/ml-drift-compatibility-v1/attempts/configured-1/`.
The runner executed exactly the reviewed batch commands with the frozen server
JVM, root rc, environment, dedicated cache, target expression and ABI flags.

| Request | Exit | Elapsed incl. cleanup | Observed stage | Dependency result |
| --- | ---: | ---: | --- | --- |
| Root repository `query` | 1 | 1.641 s | Java server startup failed | No repository receipt |
| ARM64 `cquery`, requested `android_arm64` | 1 | 0.564 s | Same startup failure | **No configured ARM64 graph** |
| x86_64 `cquery`, requested `android_x86_64` | 1 | 0.469 s | Same startup failure | **No configured x86_64 graph** |

All owned process-group cleanup was confirmed. The wrapper returned exit 2,
meaning retained bounded investigation evidence, **not success**. No timeout or
log-budget overrun occurred. The cleanup implementation uses one shared frozen
15-second deadline; no native/model deadline was changed.

Every stderr records `UnsupportedClassVersionError`: the Bazel server class has
class-file major **65**, while Java 17 accepts only **61**. Read-only inspection
of the retained `A-server.jar` independently confirms major 65. Its bytes and
the embedded JDK release metadata match members of the original verified Bazel
self-extracting ZIP. That bundled JDK reports Java **21.0.5**; it was not executed.

The failure occurred before root WORKSPACE evaluation. Because inventory failed,
the guarded ML Drift overlay was never selected. Both requested ABI commands
used the unamended root. There is no evidence here of actual root repository
precedence, platform/feature selection, consumed ML Drift interfaces or a native
dependency incompatibility. Target existence and historical static findings do
not substitute for the missing configured graphs.

## Route decision

**Reject the frozen Java-17 server profile for further execution.** This is a
host-toolchain incompatibility decision, not a rejection of ML Drift source.
Configuration remains blocked and the complete-runtime-recipe gate is unmet.

Recommend one alternative: review using **the same Bazel 7.6.1 binary's bundled
Java 21.0.5 for the server**, with SDK dependency/compilation declarations kept
unchanged and their resolved Java toolchains checked explicitly. See the
[separate correction proposal](../planning/P2_BAZEL_SERVER_JVM_CORRECTION_PROPOSAL_V1.md).
It defines the exact boundary and tradeoffs; it is not adopted or authorized by
the execution failure or a generic continuation. A new reviewed freeze/attempt
must precede a retry. No Bazel/dependency upgrade, opaque replacement or narrower
ABI/backend scope was silently applied.

## Verification matrix and preserved boundaries

**Passed:** source/archive identities; reviewed amendment/runner/freeze;
121 host tests; immutable attempts; actual commands matched the freeze; cleanup.

**Failed:** all three Java server launches under the selected Java-17 profile.

**Blocked:** final independent evidence review (QA usage limit); configured root
selection; both ABI graphs; ML Drift/LiteRT interface checks; complete recipe.

**Not run:** candidate overlay analysis, SDK/C++ compilation, AAR/APK assembly,
signing, provider linkage/enforcement, tokenizer/budget revalidation, emulator
installation/workloads, S24 access, model workloads, semantic search or commits.

Full SDK Cargo locks, original patches, approved host compiler correction,
historical snapshots and strict validator remain preserved. Emulator campaign
prerequisites are updated in a versioned supplement; physical-device acceptance
remains deferred. No application source or SDK substitution changed.
