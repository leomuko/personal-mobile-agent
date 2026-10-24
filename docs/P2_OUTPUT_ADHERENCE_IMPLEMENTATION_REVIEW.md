# Output-Adherence Native Integration Independent Review

Date: 2026-10-24. Reviewer: OpenAI Codex, the user-authorized single primary
independent QA reviewer, reused from the corpus review. No delegation.

**Verdict: FAIL** for `output-adherence-native-integration-and-host-qa`.
One unresolved P2 finding prevents implementation approval. No PASS JSON or
execution approval was created. The prior corpus attestation remains unchanged.

## Finding

### P2: Require Runtime Eligibility Before Accepting Final Delivery

Location: [output-adherence-evidence.mjs:21](/Users/kezileo/Desktop/personal_mobile_agent/scripts/output-adherence-evidence.mjs:21).
Confidence: high; independently reproduced without device access or source edits.

The new classifier checks only that `runtimeDelivery` is a recognized enum. Its
reused serial classifier accepts the final `delivery` and `caseGateAccepted`, but
does not read `runtimeDelivery`. Consequently an internally inconsistent report
can claim a final eligible success even though the recorded runtime delivery is
REVOKED or PENDING. The subsequent shape checks do not reject that promotion.

Reproduction used the existing synthetic six-case factory in
`scripts/tests/output-adherence-evidence.test.mjs`, evaluated in memory. The
unmodified baseline returns `status: passed`. Changing only
`cases[0].runtimeDelivery` to `REVOKED` still returns `status: passed`,
`completedRequests: 6`, `strictValidRequests: 6`, `shapeCorrectRequests: 6`.
Repeating with `PENDING` produces the same false acceptance. All other evidence,
identities, timing, cleanup and output fields remain unchanged.

This is a host evidence-integrity defect. The current on-device harness checks
`result.delivery == ELIGIBLE` through V4CaseGate and was not observed promoting a
revoked result. No native bypass or unsafe device execution was demonstrated.
Nevertheless, the host classifier must reject inconsistent delivery claims rather
than certify them as six eligible requests. Runtime eligibility may be revoked by
the final protocol gate; that gate must never manufacture runtime eligibility.

Minimal suggested correction: require `runtimeDelivery == ELIGIBLE` whenever
final `delivery == ELIGIBLE` or `caseGateAccepted == true`. Reject or block an
inconsistent record. Preserve the valid transition from runtime ELIGIBLE to final
REVOKED when shape, telemetry or protocol timing fails. Do not rewrite the latched
runtime outcome. Add regressions for both REVOKED and PENDING with final eligible
claims, plus the legitimate runtime-eligible/final-revoked blocking case.

Read-only reproduction command, run from the project root:

```sh
node --input-type=module <<'JS'
import {readFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import {classifyAdherence} from './scripts/output-adherence-evidence.mjs';
const text = readFileSync('scripts/tests/output-adherence-evidence.test.mjs', 'utf8');
const prefix = text.slice(0, text.indexOf('\ntest(')).replaceAll(
  /from '(\.\.\/[^']+)'/g,
  (_, p) => `from '${pathToFileURL(resolve('scripts/tests', p)).href}'`);
const {evidence, expected} = await import('data:text/javascript;base64,' +
  Buffer.from(prefix + '\nexport {evidence,expected};').toString('base64'));
for (const state of ['REVOKED', 'PENDING']) {
  const e = evidence();
  e.cases[0].runtimeDelivery = state;
  console.log(state, classifyAdherence(e, expected));
}
JS
```

## Snapshot And Scope

- Workspace: `/Users/kezileo/Desktop/personal_mobile_agent`; branch `dev`.
- Base and HEAD: `57ebf3653578c48cc5670b9c95f8f11b7594b2aa`.
- Three tracked changes: the shared V4 instrumentation harness,
  `scripts/v4-feasibility.mjs` and `scripts/run-p2-embedding-gate.mjs`.
  Untracked output-adherence source, test support, tests and approvals were
  inspected directly. No staged changes or Git mutations.
- Snapshot: 309 file hashes from the actual imported `adherenceSnapshot('.')`.
  Identity computed as SHA-256 of `JSON.stringify(adherenceSnapshot('.'))`:
  `c1204345a41b0e2af30f4ec20a0de395d8203979484bb26ac2095faef6e366b7`.
  The snapshot was identical before and after host verification.
- Corpus preparation lock remains
  `88a985300cd4cc0c100f6541214328e68759558393fa25b86f8fea8a13b888a2`;
  all 14 lock-bound files reproduced unchanged. No corpus reapproval or edits.

Read the QA instructions and relevant protocol/handoff material. Applied
`kotlin-kmp-code-review`, `kotlin-testing-kmp`, `kotlin-platform-kmp-bridges` and
`kotlin-project-architecture-review`, including applicable concurrency,
architecture/state, data/security, test quality, source-set and resilience
references. No UI or production feature change is asserted by this review.

Reviewed integration working-tree hashes (SHA-256):

| Path | Hash |
| --- | --- |
| `androidApp/src/androidTest/kotlin/dev/edgecompanion/app/OutputAdherenceNativeTest.kt` | `d17d6b6894e91741170e8eab45f0cb96539358cf780743eead384c2aca7b7658` |
| `androidApp/src/androidTest/kotlin/dev/edgecompanion/app/V4FeasibilityTest.kt` | `7597eae004ab72d20d0e6d26b75707abaa2badc33b570960c96469cb5fb9d049` |
| `androidApp/src/test/kotlin/dev/edgecompanion/app/OutputAdherenceShapeTest.kt` | `0e459116e40bade4e3cdaf941bdee5cd762643a5a03c9e542dec3769693c4e29` |
| `androidApp/src/testSupport/kotlin/dev/edgecompanion/app/support/OutputAdherenceShape.kt` | `c1ac714f14e2171fdcc10b80b8671ddc0adccc11296de06dbff89d694174e3b6` |
| `planning/fixtures/output-adherence-v1/integration-authorization.json` | `050274426d6d482bd0da1fc762d98c2f297c3682ac3282badba7bda30e851e4a` |
| `scripts/output-adherence-evidence.mjs` | `18c159c2ab83680a782e1a8eee66b725e73a2e94164b84c3cd4ff184bc8d2afe` |
| `scripts/output-adherence-freeze.mjs` | `c647a423ea4f4eb9e6d997f95a504d476fce1a1be9f7d82e1f35c18bc991a44d` |
| `scripts/output-adherence-gates.mjs` | `5d5ad0f7d026de923f9ae07d1f11fc9d4e70caa3a168f0c3b2c261e919b09cfb` |
| `scripts/run-p2-embedding-gate.mjs` | `f2bad0fd21bc5bdc9d5bf4e12953b71e9ecea13ae0ae17b3cafe248fbe189919` |
| `scripts/tests/output-adherence-evidence.test.mjs` | `a361fd462bb86d1793e27a07a1a58e527fd2077da9e120a6fb0ba0fce4c5ca0d` |
| `scripts/tests/output-adherence-freeze.test.mjs` | `029f2e2092f2165985d51149e0f1162445415c1bb5798436e1875911d45797ea` |
| `scripts/tests/output-adherence-gates.test.mjs` | `ad29c623f834593e5816f817bf63f47f1ba69852ae4959d87266a3f677378d0e` |
| `scripts/v4-feasibility.mjs` | `a9ac04ccbc2ee6c3e7bea5d2bee4274f5c667a573016906a71ed4628ad00dc1c` |

Affected contracts inspected include ModelManager, VerificationCall,
ForegroundPreparation, AndroidForegroundEncoder, MemoryPolicy and its selection,
MemoryTelemetryRecorder, DeviceSupervision, V4CaseGate, v4-execution-freeze,
counting-proof admission, resource preflight, and corresponding host regressions.
The new handoff document is outside the executable snapshot. The parent's ignored
encoder asset copy and read-only device readiness are not adopted as independently
verified evidence here.

## Boundary Assessment

Aside from the finding above, no additional actionable defects were established
in the reviewed changes:

- Existing ModelManager owns the reservation and serial encoder/Gemma lifecycle.
  Native worker death precedes Gemma allocation. Cancellation, uncertain-cleanup
  quarantine, noncancellable cleanup and host-loss supervision remain delegated to
  existing contracts. No second runtime owner or production integration was added.
- OA starts its per-request clock before packing and passes that original start
  into the fixed V4 30000 ms policy. Runtime outcome/delivery and final protocol
  decision/delivery are distinct. Final shape/telemetry acceptance also checks
  elapsed time below 30000 ms and stops the loop on the first blocking case.
- Exact verifier data JSON remains an unchanged substring of the attested pack;
  native code reconstructs and hashes the exact payload. EncoderInput receives
  only the exact question. Question identities, delivered original UTF-16 spans,
  detail order and the 2048/512 prompt budget are checked.
- Oracle states and expected citations travel separately under a hash and are
  consumed by test support after strict runtime validation. They do not enter
  model or encoder input. Shape matching makes no semantic-quality claim.
- Diagnostic strings are passed through without JSON repair, fence stripping,
  field renaming or retries. Rejected fenced output is retained by the host
  classifier tests. Historical artifacts and the strict validator are not edited.
- The pressure profile is isolated by debug + secureQa + explicit build flag.
  Only available-RAM floors are bypassed; required resource signals, OS pressure,
  total RAM, thermal safeguards and supervision remain enforced. The wrapper
  compares the installed exported profile against the freeze.
- Freeze admission checks corpus/integration approval, implementation review,
  current source snapshot, exact plan/oracle, counting proof, profile and APK
  identities, then separate freeze-bound serial/device permission. Private copies
  of both frozen APKs are hashed before installation; corrupt/linked bundles fail
  before device access. An exclusive ledger prevents silent reuse of a freeze.
- New native code stays in androidTest and shared Android testSupport; pure shape
  behavior has local unit tests. Shared ownership/deadline behavior is exercised
  with deterministic common tests; filesystem fixtures remain in jvmTest. No new
  Compose, navigation, database, module or exported production surface was added.

These are source/host assessments, not verification of a real installed APK,
native behavior, runtime feasibility, performance or semantic quality.

## Fresh Verification

Configured modules, targets and tasks were inspected first. Node is v24.6.0;
Gradle used Android Studio's bundled JBR and cached dependencies in offline mode.
No parent test result was treated as a fresh pass. Gradle tests were forced with
`--rerun-tasks`; XML reports confirm zero failures/errors/skips.

| Check | Independent result | Evidence |
| --- | --- | --- |
| `node scripts/host-test-suite.mjs` | PASS, exit 0; 113 tests, zero failures/skips | Canonical migrated archival route, including OA and V4 tests |
| `:shared:jvmTest` | PASS; 159 tests | `shared/build/test-results/jvmTest/`, fresh timestamps 10:51:27-29 UTC |
| `:shared:testAndroidHostTest` | PASS; 135 tests, freshly executed | `shared/build/test-results/testAndroidHostTest/`, 10:51:29-30 UTC |
| `:androidApp:testSecureQaUnitTest` | PASS; 23 tests | `androidApp/build/test-results/testSecureQaUnitTest/`, 10:51:34 UTC |
| `:androidApp:testDebugUnitTest` | PASS; 23 tests | `androidApp/build/test-results/testDebugUnitTest/`, 10:52:31 UTC |
| `:androidApp:compileSecureQaAndroidTestKotlin` | PASS; fresh compilation | Secure-QA instrumentation sources compiled; no execution |
| `:androidApp:lintDebug` | PASS, 0 errors / 21 warnings | Fresh XML/text/HTML under `androidApp/build/reports/lint-results-debug.*` |
| Preparation reproduction and `git diff --check` | PASS, exit 0 | Exact original preparation and tracked-diff whitespace |
| Independent runtime-delivery negative probe | FAIL acceptance invariant | REVOKED and PENDING each still produce `status: passed` |

Gradle reproduction:

```sh
env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  P2_ENCODER_COUNTING_DIR="$PWD/build/encoder-counting" \
  P2_V4_PROFILE=android-pressure \
  ./gradlew :shared:jvmTest :shared:testAndroidHostTest \
  :androidApp:testSecureQaUnitTest :androidApp:compileSecureQaAndroidTestKotlin \
  :androidApp:lintDebug -PtestBuildType=secureQa -PandroidPressureMemoryTest=true \
  --offline --console=plain --rerun-tasks
env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :androidApp:testDebugUnitTest -PtestBuildType=debug \
  --offline --console=plain --rerun-tasks
env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :androidApp:lintDebug -PtestBuildType=debug \
  --offline --console=plain --rerun-tasks
```

An initial combined Gradle invocation failed task selection because the configured
testBuildType exposes only that variant's unit task. It ran no tests; separate
variant invocations above resolved it. Cache access required sandbox escalation.
Existing UI host-test configuration, Kotlin registry and deprecation warnings
remain. The parent's lint count of 30 was not reproduced; the fresh report has 21.

Not run: ADB, installation, native/device workloads, model loading, SDK native
counting, APK assembly or actual execution-freeze creation. Host tests use
temporary synthetic APKs, approvals and fake command callbacks; they grant no real
permission. No infrastructure blockers remain for the checks performed.

## Next Review And Changes

Correct the delivery-correlation check and add focused regressions, then request
re-review against the resulting source snapshot. A PASS must precede the separate
freeze and frozen APK/source/plan/profile correspondence review. This reviewer
does not grant device execution permission; the user's separate six-request S24
authorization remains distinct. No device readiness was independently checked.

Deferred release packaging, AUDIO-001/AUDIO-002 and broader UI-001/UI-002
acceptance remain untouched. P2 remains incomplete; semantic search is disabled.

Only authored path: `docs/P2_OUTPUT_ADHERENCE_IMPLEMENTATION_REVIEW.md`.
No approval JSON, source/test/fixture edits, original lock changes, device access,
commits, branch operations, pushes or other Git mutations. Ordinary host
build/test outputs were generated. This review runs once and creates no monitor.
