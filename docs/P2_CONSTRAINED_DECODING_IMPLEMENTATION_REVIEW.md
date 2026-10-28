# Constrained-Decoding V2 Independent Implementation Review

2026-10-26. Reviewer: OpenAI Codex, the retained user-authorized primary independent
QA reviewer. No delegation or substitute reviewer. Scope:
`constrained-decoding-v2-integration-and-host-qa`.

**Verdict: FAIL. Two unresolved P2 findings prevent implementation attestation.**
Host prerequisites reproduced, and the existing canonical suites passed, but the
new cancellation diagnostics and phase guards do not yet meet the approved
protocol. No implementation PASS manifest was created. This review grants no
device execution permission: `deviceExecutionAuthorized: false`.

## Findings

### P2 CD-IMPL-001: Cancellation discards received raw text

High confidence. In
[ConstrainedSession.kt:83](/Users/kezileo/Desktop/personal_mobile_agent/shared/src/androidMain/kotlin/dev/edgecompanion/core/inference/litert/ConstrainedSession.kt:83),
the callback records FIRST_TEXT but enqueues text only while not cancelled. At
[line 101](/Users/kezileo/Desktop/personal_mobile_agent/shared/src/androidMain/kotlin/dev/edgecompanion/core/inference/litert/ConstrainedSession.kt:101),
already queued events are also discarded after cancellation. There is no separate
raw-text diagnostic capture. VerificationRun's diagnosticOutput contains only
text that reached its append method; the collector writes that value into the
report. Immediate delivery revocation is correct, but it currently also destroys
the diagnostic evidence required for cancelled/failed requests.

Independently reproduced against the freshly compiled Android host classes using
an in-memory JShell/reflection fake handle, without vendor initialization or any
source/test file edits. Its send called text with `{"o":`, then cancelled before
the flow drained its queue; cancel supplied the terminal callback. Observations:

```text
events=[Cancelled], closes=1, no quarantine
SETUP_ENTER=2, SETUP_RETURN=3, HANDLE_PUBLISHED=4, SEND_ENTER=5,
FIRST_TEXT=6, CANCEL_SIGNAL=7, TERMINAL=8, SEND_RETURN=9, CLOSED=10
```

No Token event survived despite the text callback completing before cancellation.
The raw text exists in neither the phase trace nor a separate diagnostic buffer.
A direct call to the actual shared guard with these points, CANCELLED/REVOKED,
confirmed cleanup and empty diagnosticOutput returned
`passed=true, classification=TARGETED_CANCELLATION` for cd06. This is a host
counterexample, not a claim that a device campaign has run or passed allocation
checks. The same loss can occur when the cancellation observer reacts to the first
callback before the collector has appended it, or when further callbacks race
with revocation.

Impact: an attempted decoding-cancellation check can lose its actual partial
output and still satisfy the cancellation guard. A later audit cannot reconstruct
what was produced, contrary to the protocol's raw-preservation requirement.

Minimal remediation: retain bounded raw callback diagnostics independently of
eligible delivery and propagate them through cleanup/error reporting. Continue
revoking all user delivery immediately; do not make cancelled text eligible just
to preserve it. Add regressions for text queued before cancellation, cancellation
triggered by FIRST_TEXT before collection, and further text racing cancellation;
assert exact retained diagnostic bytes, revoked delivery and unchanged ownership.
The existing late-callback test covers callbacks after terminal, not this loss.

### P2 CD-IMPL-002: Phase guards accept contradictory temporal evidence

High confidence. Both
[ConstrainedGuard.kt:28](/Users/kezileo/Desktop/personal_mobile_agent/shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/verification/ConstrainedGuard.kt:28)
and
[constrained-decoding-evidence.mjs:50](/Users/kezileo/Desktop/personal_mobile_agent/scripts/constrained-decoding-evidence.mjs:50)
check setup/publication/send-return/close relationships, but omit send-to-first,
send-to-terminal and first-to-terminal relationships. In particular, cd06 checks
FIRST_TEXT <= CANCEL_SIGNAL < TERMINAL without requiring SEND_ENTER <= FIRST_TEXT.
Positive guards only require those points to exist and precede close.

Independent host reproductions, each starting from an otherwise valid fixture:

- Materialized the existing nine-case syntheticEvidence helper in memory. Baseline
  classified `passed`. Moved cd06 FIRST_TEXT to SEND_ENTER minus 5 ms and
  CANCEL_SIGNAL to SEND_ENTER minus 4 ms, leaving all other evidence intact:
  the complete campaign still classified `passed`.
- Moved cd01 FIRST_TEXT to TERMINAL plus 1 ms, still before CLOSED: the complete
  campaign again classified `passed`.
- Invoked the compiled shared guard with the first probe's corresponding
  inconsistent ordering: `passed=true, classification=TARGETED_CANCELLATION`.

These probes mutate only synthetic in-memory evidence, not repository fixtures.
They demonstrate validation gaps, not observed native timestamp corruption.

Impact: a missed or incorrectly correlated decoding boundary, or a contradictory
positive trace, can qualify instead of blocking. Temporal evidence is central to
this experiment; a nominal phase label is explicitly insufficient.

Minimal remediation: enforce the necessary partial order in both classifiers:
publication <= send entry <= first text <= terminal <= close whenever the relevant
points exist, with send entry <= terminal even without text. Constrain each
cancellation to its appropriate interval as well. Preserve valid synchronous
callbacks: terminal can occur before send returns, so do not impose an artificial
SEND_RETURN-before-TERMINAL rule. Add shared and Node regressions for the two
counterexamples and missing/reversed send/terminal boundaries.

## Reviewed Snapshot And Scope

Workspace: `/Users/kezileo/Desktop/personal_mobile_agent`; branch `dev`.
Base/HEAD: `ae678b7ecd3760ba3739bf89127ac5cb8bae2fa1`. No staged changes.
The six tracked modifications and the relevant untracked files were inspected
directly; an empty committed diff was not treated as the review scope.

Independently imported `constrainedSnapshot()` from
`scripts/constrained-decoding-freeze.mjs`, recomputed all 339 bindings and hashed
`JSON.stringify(constrainedSnapshot('.'))`:

```text
7c9026d54501e9a7e2a4d316e7c83cbd986018abbe8d4c38420eaaac67a63374
```

This exactly matches the supplied snapshot and its sources map in
`build/constrained-decoding/review-snapshot.json`. That file's SHA-256 is
`a22ecf4dcd23fee4fb00727e129ab0ddec8d0680d9f9911ee9148990eaf3725f`.
Snapshot membership includes tracked and untracked executable sources/tests,
configuration, preparation dependencies and the prerequisite proof. The review
report itself is outside that executable map. No bound source changed during review.

Preparation lock remains
`5e8edd23535d26c9985a28fd59e51c29b60974561580c0f807fec0f5b297e1d9`;
all 14 bindings match. Its historical false flags were preserved. Separate
integration authorization hash:
`927c8eb7c70281ebab851eef0b27a0e88910b1c65aff86d7c1491b73f508d2b2`.
It matches nine cases, strictly below 30000 ms including cleanup, the isolated
pressure profile and cd07-only cap 1 with 512 reserved tokens. It does not grant
device permission.

Content hashes of the principal affected implementation files, relative to the
workspace above; all are also bound by the full 339-file snapshot:

| Path | SHA-256 |
| --- | --- |
| `shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/Inference.kt` | `d236cd712e5f72766014cec8ac2ffba4388c58866964b87b646eb8de33c99962` |
| `shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/ModelManager.kt` | `ff9b8c58855a412bf743b4cd48700e38439477fe9318557ba8db2812118ab0ba` |
| `shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/verification/ConstrainedDecoding.kt` | `f93ba955304cce52b84bdd38fbc477c416ccff9da85f2c255f847c630158286c` |
| `shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/verification/ConstrainedGuard.kt` | `691585d57889dcdc7f5e82ff7213986ea1d72fca7bac61025fc1dd035ffa18b3` |
| `shared/src/androidMain/kotlin/dev/edgecompanion/core/inference/litert/LiteRtLlmEngine.kt` | `9a58fc3cd3e41492d98c54e8364a1a8ec90452dcaed14e856a9d3811c6a3b150` |
| `shared/src/androidMain/kotlin/dev/edgecompanion/core/inference/litert/ConstrainedSession.kt` | `c0dcfaaf539a63d5419139a408902cf6265f30af856ef83e0fd68c729511f317` |
| `androidApp/src/main/kotlin/dev/edgecompanion/app/di/ModelModule.kt` | `8b46bb8db1a09853115e4e949cb3de0b5b7192b977780ee771b1861d06016c2e` |
| `androidApp/src/androidTest/kotlin/dev/edgecompanion/app/ConstrainedDecodingNativeTest.kt` | `811e7bc76b2b5692169b40a737d360a1673073a94f78ae9425c8b2ba036aea90` |
| `androidApp/src/testSupport/kotlin/dev/edgecompanion/app/support/ConstraintAllocationGate.kt` | `e6dce16896611863e68d2a9e3f270f8bf52140efbd1e9c85ce40156d435ffaa3` |
| `androidApp/src/testSupport/kotlin/dev/edgecompanion/app/support/ScopedDeadlineStop.kt` | `432af31135c576f4e54b479431278962a20771980293e71ebf908f6b3750651e` |
| `scripts/constrained-decoding-gates.mjs` | `b279bd92c8f67c307d6a2d60f6dfed978a380bbb99fb90e4c82ee93ffe8f7f7d` |
| `scripts/constrained-decoding-freeze.mjs` | `cfd9ef0a272b790d28ea1c117bcfa6d0e7536de100b435d9d057a80276530633` |
| `scripts/constrained-decoding-evidence.mjs` | `171da9856d30a4857b6d2e0f7407e02eb249237c0627a7dc5e0decf22b2d78dc` |
| `scripts/run-p2-embedding-gate.mjs` | `351a2f6ef14335b2bd384b5963e5c501abc19c9e03fd1358a7512aa8963da4b9` |
| `scripts/verify-constrained-decoding.py` | `23cdb1775c5a10853c4c9f9e4b20ed331fac35aa20a5c72ad419f50486f09ce2` |
| `scripts/verify-constrained-decoding-prerequisites.sh` | `34f6a1475df2655f7a773e6655eddbde1b356ef01715bb4bb4f2ef46986f304b` |
| `scripts/requirements/constrained-decoding.txt` | `c445bcf9720222bdf9619a2a614cb584261cd75a8b173e23f0938321bfd18134` |
| `scripts/tests/constrained-decoding.test.mjs` | `2ae8494c99bf0f673d11831d960ee698a4283c62baee3b40467481a654cdfa93` |
| `scripts/tests/test_constrained_decoding.py` | `96d86963fd2983968e91efc78e98a12973c57654c282169446f2903fa61de236` |
| `shared/src/androidHostTest/kotlin/dev/edgecompanion/core/inference/litert/ConstrainedSessionTest.kt` | `ff73611ee4886f79b9a5fff1398a88c0dfc734bbce0e92970a6c642b44b345f0` |
| `shared/src/androidHostTest/kotlin/dev/edgecompanion/core/inference/litert/ConstrainedFixturesTest.kt` | `1e3402c5b84ce375ec3b3eb3f438267f4784d6a681d18cca1b4c9237dc837c11` |
| `shared/src/commonTest/kotlin/dev/edgecompanion/core/inference/verification/ConstrainedGuardTest.kt` | `33794fbdec327a289039e75c3c7dd04129b3ce7c5cb193ab78dbb3c13b761d4d` |
| `shared/src/commonTest/kotlin/dev/edgecompanion/core/inference/ForegroundHandoffTest.kt` | `40bafe6d510de141aa0587415f087b4c842a032333f82819ab91c7288821ac3d` |
| `androidApp/src/test/kotlin/dev/edgecompanion/app/ConstraintAllocationGateTest.kt` | `1332853b270cdb39b78e171994a2eb5b2831b2b4ead148fe862bf2879f581b1e` |
| `androidApp/src/test/kotlin/dev/edgecompanion/app/ScopedDeadlineStopTest.kt` | `e451a43f3e29912137e89ad4f93fad75b93fa0a85b3327f096326efa12a177e1` |

Separately read documentation outside the executable snapshot:
`docs/P2_CONSTRAINED_DECODING_PREREQUISITES.md` hash
`dc2282ccbc681004cd467684100fe27708dd0a9d7b2694a033dbfaca503fee7d`,
and `docs/P2_CONSTRAINED_DECODING_IMPLEMENTATION.md` hash
`33fe149b23d4f6f15142e052ba8504d44edc8279bc9678a0c46f83cafa552c74`.
The latter's raw-preservation claim is limited by CD-IMPL-001.

## Sound Boundaries And Remaining Native Questions

Applied AGENTS.md, QA_AGENT_PROMPT.md, Kotlin code review/testing skills and relevant
concurrency, security, KMP bridge and architecture references. This is a focused
review of the new path and affected callers, not a new whole-product review.

Shared configuration/guards remain vendor-free; Android owns the SDK seam and
application wiring, and instrumentation/testSupport owns collection/watchdog work.
ModelManager remains the resource owner. The QA flag is required in both manager
and adapter; disabled requests reject before reclamation. Exact case/schema/pack
and question hashing, platform rehashing, GPU/2048/512 checks and SDK counter checks
preserve the candidate boundaries. Ordinary chat has no implicit constrained mode
or unconstrained fallback.

Pending setup handles remain owned until return/close. Unknown synchronous setup
or send throws quarantine rather than inventing quiescence; terminal callbacks,
late callbacks, close failures, context invalidation and panel exclusion have host
coverage. The generation cancellation signal does not acquire the setup/send lock.
The two findings above concern evidence preservation/acceptance and do not establish
an observed native ownership escape.

The runtime, allocation and collector checks reject a whole duration at or above
30000 ms, including cleanup. The separate 40000 ms watchdog is an emergency scoped
stop, not accepted inference time. Its close/expire exclusion and the existing
device-supervision process stop were inspected and their host tests passed. This
does not prove a hung native call will respond; no native call was made.

The prepared nine entries reproduce the exact prior inputs and schemas. Oracles
are separately serialized and hashed, and only payload/question/schema are sent
to the encoder/model. The fixed sentinel remains public structural test data.
Expected compiler rejection requires the specific native prefix and invalid type;
generic generation failure is insufficient. The one-token case retains the full
reservation and requires a token, nonempty JSON prefix, terminal and rejection.
Actual native errors, keyword support, counter meaning, cancellation windows,
schema/template token accounting and cleanup remain unqualified until later
separately authorized evidence.

Freeze/admission code requires a current implementation PASS, independent freeze
correspondence and a separate serial/profile/freeze-bound authorization. Attempt
reservation is exclusive. Verified private APK staging precedes ADB and uses the
frozen pair despite mutable build-output races; inherited corruption/symlink and
pair-validation host checks passed. Synthetic freeze/authorization fixtures used
by tests were confined to temporary roots and removed. No real V2 freeze or
authorization was created by this review.

## Independent Verification

- **PASS:** canonical `node scripts/host-test-suite.mjs`, exit 0: 126 tests,
  126 passed, zero failures/skips. This includes the 11 new constrained Node tests.
- **PASS:** `build/constrained-decoding-reference/bin/python -O -m unittest
  discover -s scripts/tests -p test_constrained_decoding.py`, exit 0: eight tests.
  The package-installing wrapper was not executed; its failed-setup invalidation
  test uses a confined fake interpreter.
- **PASS:** directly called the verifier's run function in memory using the
  existing pinned reference environment and compared its output with the saved
  proof, excluding only createdAtMs. All other fields matched. No proof was
  overwritten and no dependency was installed. Two positive meta-schemas, one
  deliberate negative, two base instances and all 34 checks against each schema
  reproduced. Full model/tokenizer hashes reproduced; all 262144 IDs, both
  representations, 260 brace substrings and 212 parseable candidates reproduced
  zero schema-valid single-token output. Decoded-vocabulary digest:
  `aba49cf84613bd47aec32c6cf851dfe85b472fdeade1254d775e7f38237aeca6`.
  Saved proof SHA-256:
  `f0379e674493288965e0db082f87f167ba69181c817b073d02fd306d68d33920`.
- **PASS:** fresh offline `./gradlew :shared:jvmTest :shared:testAndroidHostTest
  :androidApp:testSecureQaUnitTest -PtestBuildType=secureQa
  -PandroidPressureMemoryTest=true --rerun-tasks`, exit 0. XML counts were 170 JVM
  tests (168 passed, two opt-in tokenizer tests skipped), 165 Android-host passed
  and 27 secure-QA app unit tests passed. Then reran both skipped tokenizer tests
  with `P2_ENCODER_COUNTING_DIR=$PWD/build/encoder-counting` and the same variant,
  selecting UseTokenizerReferenceTest: two passed, zero failures/skips. Thus all
  170 JVM cases were independently exercised across the two invocations; the
  latest saved JVM XML contains only that focused pair, not a fresh full suite.
- **PASS:** cached SDK AAR hash remains
  `a8aeaa6b128e9b0f1c7fe5e620932a7030cfff1ac9c0dc133d8c79711de5cf4d`;
  classes.jar and both audited JNI member hashes also match. This is identity
  verification, not native binary/source equivalence or enforcement proof.
- **PASS:** independently rehashed all 601 archived source/APK bindings: 290 in
  `p2-v4-execution-freeze-20261022-android-pressure-1`, 311 in
  `p2-output-adherence-execution-freeze-20261024-1`. Historical freeze hashes remain
  `e30f6cb23860ee47d8c42ac6ae76a3eccdc1d199f3833c3c13d351f06a2dec91`
  and `e90c8492826ed045d5c902cc4755d2c718d7d3e94022bca97ca316f176e1b747`.
  The strict validator remains
  `10be77c8586f0ee9931c9c33b965acd3608fe379010afde75e27b1dfd5526000`.
- **FAIL:** additional independent raw-cancellation and phase-order probes above
  found acceptance/preservation gaps not detected by the existing suites.
- **Not independently rerun:** debug/release APK builds, debug app tests, lint and
  secure-QA instrumentation assembly. Inspected the parent's final baseline/QA
  logs reporting successful builds, 27 debug app tests and lint 0 errors/20
  warnings; those are supplied build evidence, not fresh reviewer executions.
- **Not run/authorized here:** real execution-freeze creation, device admission,
  ADB, installs, model inference, native SDK initialization or S24 workloads.

The first restricted Gradle attempt could not acquire its existing cache lock;
the cache-enabled host retry was allowed. An initial invocation omitted
`-PtestBuildType=secureQa` and failed task selection without running tests; the
corrected invocation above passed. A direct `node --test scripts/tests/*.test.mjs`
invocation produced 125 passes and the known historical calibration-lock failure.
The audited canonical runner substitutes only the explicitly migrated archival
test module, preserves its 16 tests and passes all 126. Neither invocation error
is reported as a new integration defect or hidden as a passing check.

## Disposition And Changed Paths

Resolve CD-IMPL-001 and CD-IMPL-002 with focused regressions, then return to this
same reviewer with the new snapshot. A reviewed implementation, actual build/APK
freeze and independent freeze correspondence must precede any device admission.
The human's request for S24 tests does not turn this failed host review into
execution permission.

Existing deferred BUILD-001, MODEL-001, AUDIO-001/AUDIO-002 and broader physical/UI
acceptance risks remain unchanged and are separate from these new findings. No
UI, navigation, data migration or semantic enablement was introduced or approved
by this review. P2 remains incomplete.

Only authored path: `docs/P2_CONSTRAINED_DECODING_IMPLEMENTATION_REVIEW.md`.
`planning/fixtures/constrained-decoding-v2/implementation-review.json` remains
absent. Production source, tests, configuration, preparation locks, historical
reports and archives were not edited. Verification generated ordinary build/test
outputs only. No Git mutation, delegation, package installation or device access.
