# Constrained-Decoding V2 Execution-Freeze Correspondence Review

2026-10-26. Reviewer: OpenAI Codex, the retained user-authorized primary independent
QA reviewer. No delegation or substitute reviewer.

**Verdict: PASS for `constrained-decoding-v2-execution-freeze`.** No correspondence
defect was found. This attests the exact bundle, reviewed implementation, inputs,
proof, profile and APK identities. It does not attest native enforcement,
feasibility, accuracy or device readiness, and grants no device permission:
`deviceExecutionAuthorized: false`. Semantic enablement remains false.

## Immutable Identities

Bundle: `benchmarks/results/p2-constrained-decoding-execution-freeze-20261026-1`.
Actual `execution-freeze.json`: 87822 bytes, SHA-256:

```text
706f33e820924dc5201d92c49d76abbff1fd64a5c21a9f11cc71a01908eeccb6
```

Workspace branch/base/HEAD remain `dev` /
`ae678b7ecd3760ba3739bf89127ac5cb8bae2fa1`. No staged changes.
The artifact was prepared at 2026-10-26T17:13:57.051Z.

| Binding | SHA-256 |
| --- | --- |
| Reviewed 341-entry canonical snapshot | `158630554bae1071105b3b50d24bed342d142178b51a71780601b983da4f9d9b` |
| Implementation-review JSON | `4ead04211e7a202e51b03612498946d020c1a9b6de3f5254cd0291ebf8f78366` |
| Resource profile | `83c6e3f2ac535be423bfd0e359546098c51743be3bbf52353ca065d87ff85113` |
| Exact native-input plan | `a9b500fa81b3871eb9e5c7f441317ec2fb9cb969c55b1f68ae30c34eadeba294` |
| Separate QA oracle | `65df913395e13342e4b930889b226e74bb7e68c90758aa0cb97ea109477f912f` |
| Prerequisite proof | `f0379e674493288965e0db082f87f167ba69181c817b073d02fd306d68d33920` |
| Integration authorization, not device permission | `927c8eb7c70281ebab851eef0b27a0e88910b1c65aff86d7c1491b73f508d2b2` |
| Preparation lock | `5e8edd23535d26c9985a28fd59e51c29b60974561580c0f807fec0f5b297e1d9` |

Independently recomputed `constrainedSnapshot('.')`, compared all entries with
the follow-up reviewed map and frozen sources map, and hashed its exact
`JSON.stringify` representation. All match. Independently hashed all 341 frozen
source/input/proof copies and both frozen APKs against their freeze entries and
current working files: **343/343 match**. The snapshot directory has no extra
files or symlinks. Replaying snapshot and plan preparation against the frozen
root also reproduces the same map, plan and oracle. No build or proof was
regenerated to make these comparisons pass.

The freeze embeds the exact existing implementation manifest, whose hash matches
reviewSha256. Its scope, PASS, snapshot and false execution flag match. The report
bindings also match unchanged local reports: original FAIL
`d2bee3f6ac09da4c3da69770df5628c6593b58a72925f8c3b6fe9d5ab6cba098`,
and follow-up PASS
`9c46828f735110781a60bdd59cf358dc1c3bff76909b636f279f6c3ed015569c`.

## Exact Inputs, Profile And Acceptance

The frozen plan has exactly cd01-cd09 in order. Every entry's key set matches the
input whitelist; payload, encoder question and complete schema strings rehash to
their recorded identities. Schema bytes retain their final LF. Schema identities:
sentinel `e10adc86c84b6615df190205cf94b1baae0c0d1ef228dda2b5c5fe999ca5be53`,
invalid type `041bb8c53280f55f249550a741e5493789a5d49c11d25a69eb00cf9eaccc06ab`,
evidence `36232d7c46e2343ad0a5b40c6328e1ff3ff10b6ef76d457a93b245052390e691`.
Only cd02 uses the deliberate invalid schema; cd01 uses the public fixed sentinel;
the other seven use the evidence schema. No per-control gold state, witness or
review object is passed as payload/question/schema. The separately hashed oracle
contains the original S/C/S control judgments for cd03/cd08/cd09. Typed sentinel
comparison remains separate and earns no semantic credit.

| Case | Input bound | Bound plus reservation | Decode cap |
| --- | ---: | ---: | ---: |
| cd01 | 346 | 858 | 512 |
| cd02 | 1175 | 1687 | 512 |
| cd03 | 1175 | 1687 | 512 |
| cd04 | 1175 | 1687 | 512 |
| cd05 | 1290 | 1802 | 512 |
| cd06 | 1290 | 1802 | 512 |
| cd07 | 1175 | 1687 | 1 |
| cd08 | 1290 | 1802 | 512 |
| cd09 | 1173 | 1685 | 512 |

Each input bound reproduces 224 + UTF8(payload). Context remains 2048 and every
case reserves 512 output tokens, including cd07. The encoder limit remains 192.
These bounds are admission identities, not newly measured native token counts.
The unchanged strict-validator hash is
`10be77c8586f0ee9931c9c33b965acd3608fe379010afde75e27b1dfd5526000`.

The frozen profile exactly matches current validated policy export:
`memory-android-pressure-test-v1`, available-RAM-floor enforcement false,
minimum total RAM 7516192768 bytes, three available-memory samples, 1000 ms
sample interval, severe thermal status 3 and maximum skin temperature 45 C.
The recorded 3 GiB floors remain recorded policy fields; bypassing them does not
waive OS lowMemory, required signals, thermal/skin stops, allocation evidence or
ownership/cleanup. Model SHA remains
`181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`,
runtime 0.17.1, GPU candidate, deterministic sampler, thinking/tools disabled.

Verified the frozen acceptance-bearing sources are exactly those reviewed:
manager whole-request ownership and publication, session pending-handle cleanup
and uncertain-throw quarantine, phase partial ordering, identified compiler error,
one-token negative evidence, bounded callback diagnostics/UTF-16 preservation,
separate oracle shape checks, serial worker death before Gemma, healthy telemetry,
closed supervision and first-block/no-retry behavior. The collector rejects a
case at or above 30000 ms through cleanup and classification. Its independent
40000 ms scoped emergency stop adds no accepted inference time. Expected negative
guards remain separate from successful replies and abstentions. This confirms
the reviewed checks are frozen; it does not demonstrate their native outcomes.

## APK And Build Correspondence

| Frozen artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `androidApp-secureQa.apk` | 209816235 | `cdd57923977f25bb335a54b679e05be6ea29b396d1c178d0c54f7c210a0670fe` |
| `androidApp-secureQa-androidTest.apk` | 4643981 | `57da1c7fdd5e706065c02b451e1c6adff21a93c904a368c14aa34ae728848403` |

Both match current build outputs and their immutable bundle copies. Output metadata
selects secureQa/secureQaAndroidTest. The supplied final follow-up QA assembly log
ends BUILD SUCCESSFUL and lists both packaging tasks. The app/test output file
timestamps are 16:57:42Z/16:57:45Z, before freeze creation. The log's SHA is
`a20c9ab16c430c96e718442070e3d49a0e6750158337f71732ea484927d0e9ab`.
The equivalent APK recipe is:

```sh
./gradlew :androidApp:assembleSecureQa :androidApp:assembleSecureQaAndroidTest -PtestBuildType=secureQa -PandroidPressureMemoryTest=true
```

This review inspected that build evidence, frozen build configuration, generated
configuration and actual APKs; it did not rerun Gradle or claim a reproducible
byte-for-byte source rebuild. The older local-validation.json contains earlier
APK identities and was not used as evidence for this pair.

Fresh host APK inspection established:

- App package `dev.edgecompanion.app.secureqa`, test package `.secureqa.test`,
  minSdk 31/targetSdk 36, debuggable QA manifests. Instrumentation is
  AndroidJUnitRunner targeting exactly the QA app package.
- Both signatures verify with the same debug signing certificate SHA-256
  `141f567bca4935a9e5b80196dcac6d7fcf5515d5cc79622d903c415fdae003c2`.
- Actual app DEX BuildConfig has BUILD_TYPE secureQa, pressure=true and
  SECURE_WINDOWS=true. Its initializer computes DEBUG from parseBoolean("true").
  The encoded pre-initialization default false is not its effective debug flag.
- App DEX contains ConstrainedSession, ConstraintDiagnostics and
  ConstraintTraceState plus the new diagnostic fields/phase guard. Test DEX
  contains ConstrainedDecodingNativeTest, QaJsonDiagnosticsKt, callback-buffer
  fields and the constrained emergency-stop identity. This corroborates the
  follow-up build, rather than accepting a stale pre-fix APK.
- Packaged LiteRT JNI bytes match the audited AAR members exactly:
  arm64-v8a `44f900f3ea6c5224f3395c1548dfa7e825f4412ba3833f994c5c306831a57086`;
  x86_64 `9b0a908ba8ef5e54b3191de963acd4272e1f10aee1df61d9912bb586a35a5d4a`.
  The test APK adds no LiteRT JNI member. These are binary identity checks, not
  proof that the native libraries enforce the reference dialect or schema.

## Host Admission And Preservation

Prepared the actual APK pair through the reviewed private-copy helper, rehashed
both copies, checked directory mode 0700 and file mode 0400, then removed them.
No install callback ran. The later installer must prepare/verify its own private
copies from this same bundle; this review did not leave installable staging files.

Ran the host entry point with this real freeze and no device authorization, using
an intentionally nonexistent ADB executable and a synthetic serial. It rejected
the missing separate authorization before device access or attempt reservation.
No actual ADB process was called. At review time the bundle contained only its
freeze and snapshot, no device authorization, and no retained attempt existed for
this freeze hash. The new correspondence JSON is not an authorization record.

The 343 bindings, implementation/report hashes and freeze bytes were rechecked
for stability. No source, input, proof, APK, freeze or prior report was edited.
Existing preparation false flags remain historical bytes. No old campaign result
was reclassified and no stopped historical request was resumed.

Only authored paths:
`docs/P2_CONSTRAINED_DECODING_EXECUTION_FREEZE_REVIEW.md` and
`benchmarks/results/p2-constrained-decoding-execution-freeze-20261026-1/independent-freeze-review.json`.
Temporary verified-copy outputs were removed. No Git mutation, delegation,
package installation, ADB/device access, native SDK initialization or model
workload. Broad host suites and proof computation were not rerun: the unchanged
reviewed snapshot and prior implementation evidence remain bound, with fresh
artifact/hash/manifest/signature/DEX/private-copy/admission checks reported here.

The next authorization must be separately main-authored and bind this exact
freeze/profile and designated S24 serial. This PASS does not grant that permission,
establish native feasibility or enable semantic search. P2 remains incomplete.
