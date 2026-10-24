# Output-Adherence Execution Freeze Correspondence Review

Date: 2026-10-24. Reviewer: OpenAI Codex, the retained user-authorized primary
independent QA reviewer. No delegation.

**Verdict: PASS** for the actual frozen artifact correspondence below. No
unresolved findings or artifact mismatches. This is an identity/build-evidence
review, not a repeat of the implementation review or device authorization.

## Exact Bindings

Freeze: `benchmarks/results/p2-output-adherence-execution-freeze-20261024-1/execution-freeze.json`.
Workspace branch: `dev`; HEAD `57ebf3653578c48cc5670b9c95f8f11b7594b2aa`.

| Identity | SHA-256 |
| --- | --- |
| Exact execution-freeze file bytes | `e90c8492826ed045d5c902cc4755d2c718d7d3e94022bca97ca316f176e1b747` |
| Executable source snapshot | `4672d0caf4206a026dbcea7f9f6f71c30fef49df29234a1bd59194cc497486d5` |
| Resource profile object | `83c6e3f2ac535be423bfd0e359546098c51743be3bbf52353ca065d87ff85113` |
| Implementation-review JSON file bytes | `b33194c7382925643b275ef833734a44edf6837f98164352463fdf35f1988c87` |
| Exact device input plan object | `e47385ee9c4a8b6d602656e33d40299393ef7fc1d22b216c88afad15e8f406e8` |
| Separate QA oracle object | `4de0eb0ef2c0af2de99128ede3edf8e2a527e6dcaebbee1226f5ff3042393591` |
| Original preparation-lock file | `88a985300cd4cc0c100f6541214328e68759558393fa25b86f8fea8a13b888a2` |

Object hashes use compact `JSON.stringify` serialization, matching the reviewed
freeze implementation. The source identity was independently recomputed from the
imported `adherenceSnapshot('.')`, not copied from the manifest.

All 309 source entries match current workspace bytes and their frozen copies.
The snapshot directory contains exactly those 309 files plus the two declared
APKs, without extra files or symbolic links. The embedded implementation review
deeply equals the actual PASS JSON, its raw-file hash matches `reviewSha256`, its
scope is `output-adherence-native-integration-and-host-qa`, and its snapshot and
false device-authorization flag match. The corrected classifier is included under
the already approved snapshot.

## APK Correspondence

| Frozen APK path below `snapshot/` | Bytes | SHA-256 |
| --- | ---: | --- |
| `androidApp/build/outputs/apk/secureQa/androidApp-secureQa.apk` | 209213783 | `5a1003525e9856e7853aa36c6b4c5ef88ff1c4f2633d757bb152e14999ee628a` |
| `androidApp/build/outputs/apk/androidTest/secureQa/androidApp-secureQa-androidTest.apk` | 4586661 | `edf219a2fe5cf2f7c5fd8567ad76807606fc226cb8c74a8e95217de412dbdd83` |

Both frozen APKs match their declared hashes and current assembled counterparts.
Static APK inspection independently confirms:

- App package `dev.edgecompanion.app.secureqa`; build type `secureQa`;
  DEBUG=true, SECURE_WINDOWS=true and ANDROID_PRESSURE_MEMORY_TEST=true in
  packaged BuildConfig. Min SDK 31, target SDK 36; arm64-v8a is packaged.
- Test package `dev.edgecompanion.app.secureqa.test`, with AndroidJUnitRunner
  targeting `dev.edgecompanion.app.secureqa`.
- Packaged OutputAdherenceNativeTest selects OUTPUT_ADHERENCE and calls the
  shared serial harness. The compiled harness contains the OA protocol, exact
  preparation/validator identities, pressure-profile selection, separate oracle
  path/hash, runtime-delivery fields, shape check and 30000 ms constants.
- Both signatures verify. Their common signing-certificate SHA-256 is
  `141f567bca4935a9e5b80196dcac6d7fcf5515d5cc79622d903c415fdae003c2`.

These checks establish hash and packaged-build correspondence. No rebuild,
installation or device behavior was used as evidence. This is not an independent
reproducible-build proof of every bytecode instruction.

## Plan, Oracle And Scope

The frozen plan, oracle and approval bindings deeply equal a fresh
`loadApprovedAdherence('.')` result. Their hashes reproduce independently.
Every rendered verifier payload equals the original corpus-attested payload
byte-for-byte, including Unicode and original passage/revision intervals.
Question-only encoder hashes and prompt bounds match. Payload sizes in order are
951, 1066, 969, 956, 1038 and 949 UTF-8 bytes. Oracle expectations remain outside
the exact encoder/verifier inputs.

The freeze and plan bind exactly oa01 through oa06 in order, six requests,
30000 ms including cleanup, context/output 2048/512 and encoder admission 192.
The acceptance list retains first-block stopping, the unmodified strict validator,
serial allocation, current counting, healthy telemetry and closed supervision.

The profile deeply equals the current reviewed exported pressure profile:
`memory-android-pressure-test-v1`, calibrated=false, available-memory floors
unenforced, minimum total RAM 7 GiB, sample interval 1000 ms, severe thermal
status 3, skin safeguard 45 C, the pinned model hash/runtime and 2048/512 budget.
Build metadata is exactly secureQa / secureQa / androidPressureMemoryTest=true.
The existing counting-proof gate passes against its bound reference/source hashes;
this does not supply new native SDK counts for these six packs.

## Private-Copy Preparation

Called the reviewed `prepareV4Apks` on this exact, independently checked bundle.
Both private copies matched their frozen hashes; the temporary directory was
0700 and APK files 0400. Exercised `installV4Apks` with an in-process recording
callback only: exactly two `install -r` argument sets referenced those verified
private copies. The callback rehashed each copy. No process or ADB install command
was executed. Both private copies and the temporary directory were removed.

Missing separate execution authorization still rejects admission. No substitute
authorization was created, no attempt ledger was reserved, and the real freeze
continues to say `awaiting-separate-device-authorization` with
`deviceExecutionAuthorized=false`, `semanticEnablement=false` and
`largeCampaignAllowed=false`.

## Preservation And Verification

All checks below passed on the actual artifacts:

- Independent Node assertions over all 311 frozen files, current source/APK
  counterparts, implementation review, exact plan/oracle/profile and scope.
- `aapt2 dump badging`, `apkanalyzer manifest print` / targeted `dex code`, and
  `apksigner verify --print-certs` on both frozen APKs as applicable. Completed
  inspections exited 0. The local apkanalyzer launcher emitted a version-parsing
  warning; an initial unsupported `--help` probe exited 1, followed by successful
  explicit inspection commands. No validation remained blocked.
- Private-copy preparation, recording-only install callback, cleanup and missing
  authorization rejection; zero real install or ADB calls.
- `node scripts/output-adherence-preparation.mjs check`, exit 0: all original
  14 bound files and exact six packs reproduce with their false flags intact.
- Original FAIL report unchanged at SHA-256
  `708bceabd0537e1493d4f3bb3174c0e0002f730c9941ff45bdda7583afabce31`;
  corpus attestation and implementation PASS bindings match their existing bytes.
- Historical V4 failure run `5414a589-fb27-450d-b0a9-539031011329` retains raw
  encoder.json hash `b199e9850da6f9a6a190ec79e4007203cd24f4e9e6f0228c2142d89930ae1365`
  and diagnostic-output hash
  `f0736f212b23a8a0c5f41833b9012d95f3780a90d56a59226ded9c9740775187`.
  No output was transformed. Its pressure freeze remains
  `e30f6cb23860ee47d8c42ac6ae76a3eccdc1d199f3833c3c13d351f06a2dec91`;
  all 288 historical frozen sources and both APKs match their manifest.
  All 289 V1/V2 preservation-manifest files match their lengths and hashes.

No full code review, test-suite rerun, Gradle variant switch, build, ledger
reservation, ADB/native workload, device-readiness check or actual installation
was performed. Previous host QA remains in the implementation review. Deferred
issues and semantic enablement remain unchanged; P2 is still incomplete.

Only authored paths are this report and
`docs/P2_OUTPUT_ADHERENCE_EXECUTION_FREEZE_REVIEW.json`. Source, prior reviews,
locks, actual freeze, frozen copies, build outputs and historical evidence remain
unchanged. No Git mutations or delegation.

The artifact gate passes. The parent's separate freeze-bound device-authorization
record is the next gate; this review does not grant that permission or start the
six-request experiment.
