# Output-Adherence V1 Native Integration Handoff

Date: 2026-10-24. Branch: `dev`; base `57ebf3653578c48cc5670b9c95f8f11b7594b2aa`.

## Scope And Approval

The user approved native integration for six requests, a 30,000 ms ceiling
including cleanup, and `memory-android-pressure-test-v1`. This is not a production
latency target. The authorization is recorded in
`planning/fixtures/output-adherence-v1/integration-authorization.json`.

The independently delegated corpus reviewer approved all six controls and exact
packs. Its attestation does not approve implementation or device execution. The
original preparation, including its historical false approval flags, is unchanged;
later approvals are separate, hash-bound records.

| Identity | SHA-256 |
| --- | --- |
| Preparation lock | `88a985300cd4cc0c100f6541214328e68759558393fa25b86f8fea8a13b888a2` |
| Corpus attestation | `c9e8a733210cfb5f44fda89f27104b5e21344c150c346cebf3ade0bad1ddda94` |
| Integration authorization | `050274426d6d482bd0da1fc762d98c2f297c3682ac3282badba7bda30e851e4a` |
| Strict validator | `10be77c8586f0ee9931c9c33b965acd3608fe379010afde75e27b1dfd5526000` |
| Derived device input plan | `e47385ee9c4a8b6d602656e33d40299393ef7fc1d22b216c88afad15e8f406e8` |
| Separate QA oracle | `4de0eb0ef2c0af2de99128ede3edf8e2a527e6dcaebbee1226f5ff3042393591` |

## Implementation

- The opt-in `OutputAdherenceNativeTest` reuses the existing serial verification
  harness. Original V4 identities, eight-request scope and approval do not grant
  access to this separately identified six-request experiment.
- Exact frozen input JSON is retained without reserialization. Instructions,
  questions, passage/revision IDs, UTF-16 spans and budget identities are checked.
  Context/output limits remain 2048/512 and encoder admission remains 192 tokens.
- QA expectations travel in a separate hashed oracle artifact. They are used only
  by test support after output validation, never as encoder or Gemma input.
- The existing runtime owner, isolated encoder worker, out-of-band cancellation,
  confirmed cleanup and pressure/thermal safeguards remain in use. The ordinary
  application memory policy is unchanged. No production UI, database or runtime
  ownership implementation was changed for this integration.
- The protocol clock starts before per-request preparation. Runtime decision and
  delivery are recorded separately from final protocol decision and delivery.
  A result is revoked if validation, expected control shape, resource evidence or
  cleanup fails the ceiling. A latched runtime outcome is retained rather than
  rewritten to disguise later cleanup failure. The first blocking result stops
  the campaign; raw output is not repaired, unfenced or retried.
- Host classification reuses lifecycle validation with fixed protocol-specific
  identities. Structural validity and matching a synthetic control shape are not
  a semantic-quality qualification.
- Freeze tooling binds reviewed source, counting proof, exact plan/oracle,
  resource profile and both QA APKs. Installation uses the existing verified
  private-copy mechanism, not mutable build outputs. Admission also requires a
  separate freeze-bound S24 authorization and an exclusive attempt ledger.

## Local Verification

Completed before independent implementation review:

| Check | Result |
| --- | --- |
| `node scripts/host-test-suite.mjs` | 113 passed, zero failures/skips |
| Shared JVM tests | 159 passed, zero failures/errors/skips |
| Shared Android-host tests | 135 recorded passes; Gradle reused up-to-date results |
| Debug app unit tests | 23 passed, zero failures/errors/skips |
| Secure-QA app unit tests | 23 passed, zero failures/errors/skips |
| Debug and pressure-profile secure-QA APK assembly | Passed |
| Secure-QA instrumentation APK assembly | Passed; not device execution |
| Debug lint | Zero errors, 30 warnings |
| Preparation reproduction and approval bindings | Passed; original 14 bound files unchanged |
| Historical pressure-bundle preservation | All 288 sources and both APKs verified |
| Historical failed report/output and strict validator | Hashes unchanged |

The synthetic classifier tests initially had an inconsistent Gemma-load timestamp
in two fixtures. Correcting that synthetic trace made it agree with the frozen
timeline contract. No saved model output or acceptance rule was changed.

The primary independent implementation review found that final eligible delivery
could be accepted alongside a recorded REVOKED or PENDING runtime delivery. The
classifier now rejects either promotion, while preserving legitimate final
revocation of a runtime-eligible result. The first FAIL report is retained in
`P2_OUTPUT_ADHERENCE_IMPLEMENTATION_REVIEW.md`. Seven focused classifier tests and
the expanded 115-test host suite pass. An initial new regression hit the separate
missing-requests guard first; its synthetic setup was corrected to retain all six
requests when asserting a complete campaign. No native output was changed.

The reviewer also freshly reran all 159 JVM, 135 Android-host and 23 tests per app
variant. Its clean lint rerun reported zero errors and 21 warnings, rather than
the earlier incremental count of 30 above. See the review for exact commands and
scope. Focused re-review of the delivery correction subsequently passed; the
hash-bound PASS is in `P2_OUTPUT_ADHERENCE_IMPLEMENTATION_REVIEW.json` and the
follow-up prose is in `P2_OUTPUT_ADHERENCE_IMPLEMENTATION_FOLLOWUP.md`.

Host regressions cover approval separation, stale review/source rejection,
freeze/authorization identity, exclusive attempt reservation, private APK copy
independence and corruption, wrong control shape, strict-output rejection,
deadline/cleanup evidence and rejection before fake ADB access. Synthetic freeze,
review and permission fixtures are temporary test data, not real approvals.

## Review And Device Gates

The user initially requested their own implementation review, then explicitly
authorized reusing the independent corpus reviewer as the primary implementation
QA agent. The implementation and frozen-bundle correspondence reviews passed.
The frozen 309-file snapshot is
`4672d0caf4206a026dbcea7f9f6f71c30fef49df29234a1bd59194cc497486d5`;
freeze SHA is `e90c8492826ed045d5c902cc4755d2c718d7d3e94022bca97ca316f176e1b747`.
The same primary reviewer is retained for evidence review before any separate
reviewer, as requested by the user.

The user separately requested S24 checks. Read-only readiness checks found an
authorized `SM-S928B` on Android 16, thermal status 0 and a normal system memory
summary. That summary does not establish the app's `MemoryInfo.lowMemory` value;
the supervised experiment must obtain fresh admission evidence. No APK was
installed or model loaded during readiness checks. No personal process inventory
is stored as a project fixture.

After those reviews, the user's separate S24 instruction was recorded against the
exact freeze/profile/serial and the supervised experiment ran. It stopped on
`oa01`: strict `SCHEMA` rejection, 17,473 ms including confirmed cleanup, runtime
unloaded, `lowMemory=false` and thermal status 0. The five remaining requests were
not run. See `P2_OUTPUT_ADHERENCE_RESULTS.md` for immutable evidence identities and
the distinction between harness completion and failed qualification. No retry or
expanded campaign is authorized by this result.

P2 remains incomplete, native feasibility remains blocked pending new evidence,
and semantic search remains disabled. Current-source native evidence covers only
the first control's execution and confirmed cleanup, not six-control adherence or
semantic quality. Compilation and host tests alone establish neither.
Deferred release packaging, audio and ordinary memory-policy decisions are
unchanged. No commits or pushes were made.
