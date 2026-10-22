# P2 Independent Corpus Judgment Reapproval

**Verdict: PASS. Judgment approval: PASS. Split separation: PASS for the stated synthetic scope.**
Approval issued for the exact renewed freeze identified below.

Reviewer: `separate-independent-qa-corpus-reapproval-reviewer-2026-10-22`, the separate Codex QA agent performing this
review, not `implementation-agent`. I did not author or change the fixtures,
judgments, implementation, protocol, or lock, and did not tune any policy.
Completed at `2026-10-21T21:37:47.814Z` (`1792618667814` ms,
obtained from actual `Date.now()`; local date 2026-10-22 in Africa/Kampala).

## Changed Question and Judgment

The sole authorized text change is `dev020n1`:

- Rejected: "Borage stand: How deep are the oil cups?"
- Corrected: "Borage stand: What is the oil cup depth in millimetres?"

The original rejection was justified: "shallow" answers a qualitative depth
question. The corrected wording explicitly requests a numerical depth expressed
in millimetres. "Shallow" specifies neither a magnitude nor a convertible
measurement, and does not contradict a numerical depth. The half-metre figure in
`dev020p` measures vegetation clearance, not cup depth; converting that figure
would not answer the question.

I checked the corrected question against **all 28 development passages**.
Only `dev020p` supplies Borage stand facts. No other record names this entity,
supplies its cup measurement, or explicitly contradicts a depth measurement.
The other apiary records concern inspection/smoker fuel, cage labelling/release,
extraction, and syrup feeding. Other families and the four generic notices supply
no Borage depth detail. Entity/topic overlap alone does not satisfy the rubric.
The unchanged all-zero row is correct across all 28 passages. No remaining
answerability ambiguity was identified in this correction.

## Complete Scope and Continuity

The preserved original report identifies its reviewer as a separate independent
QA judgment-review subagent, explicitly not the author, and records completion
at `2026-10-21T21:28:12.259Z` (`1792618092259` ms). Its hash-bound scope is
the rejected lock below. It expressly documents every text and complete grade
row, cross-record/cross-family zeros, contradictions, distractors, separation,
and comparison with all v1 texts. It reports one blocking judgment defect and
no additional blocking defect, with split separation passing. Its counts and
snapshot hashes independently agree with the actual archived and current data.
Reviewer identity is an audit attestation, not cryptographic authentication.

I also read the actual current full text/judgment sets: every passage, every
query, and each complete ordered grade vector, with vector positions checked
against the original passage order and grade-map keys. I evaluated the zeros
against the other available records as well as checking the positive entries.
This was not a sample or an automatic waiver based on structural validation.

| Split | Queries | Answerable / unanswerable | Passages | Pair judgments | Grade 3 / 0 | Two-positive queries |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Calibration | 120 | 60 / 60 | 39 | 4,680 | 72 / 4,608 | 12 |
| Development | 80 | 40 / 40 | 28 | 2,240 | 48 / 2,192 | 8 |
| Heldout | 120 | 60 / 60 | 42 | 5,040 | 72 / 4,968 | 12 |
| Total | 320 | 160 / 160 | 109 | 11,960 | 192 / 11,768 | 32 |

The 80 base records, 16 explicit-dispute records and 13 fixed notices are intact.
Requested details or explicit disputes warrant the positive grades; mere topic
matches do not. All 32 two-positive queries retain both the source and its
explicit-dispute passage. Generic supersession notices assert no individual
contradiction and correctly remain zero. The other missing-detail negatives
remain unsupported, including numeric speed versus a slowing location,
exposure duration versus illuminance, and dado depth versus joint type.
No further blocking judgment defect was found.

I read all 34 v1 passage texts and 60 v1 query texts, including the non-English
diagnostics, and compared their records and requested facts with the current v2
sets. Source families and the 80 complete base-entity names remain separated:

- Calibration: seed stewardship, stagecraft, small-craft rigging.
- Development: observatory operations, textile embroidery, museum conservation, apiary management.
- Heldout: relief printmaking, trail wayfinding, choral rehearsal, freshwater aquaria, hand bookbinding, woodshop joinery.

The nearby domains do not reuse an underlying record or a close paraphrase of
its requested facts. In particular, v1 observatory visiting/reservations,
costume-measurement delivery and choir venue changes differ from the v2
technical procedures. Shared terms such as Cedar and blue tin do not identify
the same entity or fact. No cross-split or v1 exact/close-paraphrase reuse was
identified.

**The original complete review plus the verified unchanged bytes supports
renewed approval:** its single blocker is resolved by the explicit numerical
request; all other texts, judgments and separation evidence survive exactly.
My current full-set inspection independently corroborates that conclusion.
This is unqualified judgment/separation approval of this exact synthetic corpus,
not approval of the whole application.

## Byte Preservation and Provenance

Direct Buffer equality proved that current development bytes equal archived
development bytes with exactly one occurrence of the authorized question
replaced. Parsed equality independently confirmed no changes to IDs, ordering,
passages, grades, families, metadata or other fields. Calibration, heldout and
protocol files are byte-identical to the archive. Current lock bytes equal
archived lock bytes with only the development SHA replaced.

Every archived file matches `snapshot.json`; archived `review.md` equals the
original `docs/P2_CALIBRATION_JUDGMENT_REVIEW.md` bytes. The original FAIL and
its rejection rationale remain preserved and have not been overwritten.
All 20 implementation hashes match both locks and the actual current files.
Protocol equality with implemented definitions also passes; policies, gates,
memory/supervision source and pinned encoder/tokenizer identities are unchanged.
This verifies frozen identities, not execution of the encoder artifact.

`correction.json` has the expected schema/protocol/query, exact before/after
strings, resolvable archive/report paths, old/new lock and development hashes,
and unchanged-judgment/policy/gate/encoder/implementation flags. Its
pre-collection/no-score declarations agree with the user's explicit account.
I did not inspect collection/result storage to make a broader forensic claim.
No vectors or scores informed this review.

Exact SHA-256 values, computed from file bytes and rechecked before issuance:

| File / identity | SHA-256 |
| --- | --- |
| Current lock | `ed4712c0cb6261b83591f3e6678c71c1565a6270e1388dda529583218872d8d1` |
| Archived lock | `683c371622fa303de564448127341332a9be870fe99abd63ef6e50fe4e4a8140` |
| Current and archived calibration | `241af22ac34423e241133bae04773c8565d78f0f7a2245648e94382853765757` |
| Current development | `0d171bafc54aaeead2c59e1e6dd187494a8994a55c86d60bf050856019b2698a` |
| Archived development | `58ac01fc24908da5fbd02ad6e6b049b06cf96d7ea7ac120bc50811b1f97ef7fe` |
| Current and archived heldout | `599cad04ff2f444842def1c2d249e0f8d5188320e4897514072d8e96ba0c99a6` |
| Current and archived protocol | `537f34bcc80506bc68c150c1563689b51107e093e53f03a33658ccd06134b55a` |
| Archived review and original report | `bdf91fb64964b236648ddd140710e8eb1932f238ea59c0b062ba407028c81374` |
| Archived snapshot.json | `5e78a9bece3fc27c7c6096a14edca8a403b609cbe02065a6e294745878f09483` |
| correction.json | `2fb3ee2c6fc2536df3b927c2c522cf90107e667fa7bd97b64eebeee556881de5` |
| v1 comparison fixture | `32f9d6d231fb5f5acca593eb27e128bc0f09266e311375db82cff100b6325abe` |
| Pinned encoder artifact identity in unchanged protocol | `89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749` |
| Pinned tokenizer identity in unchanged protocol | `ab32418a9a7f2c0c95e3db6233f2fbd8243e2929a84cd5feec9c1b1ca615c420` |

The authoritative unchanged implementation identities, all directly rehashed:

| Path | SHA-256 |
| --- | --- |
| scripts/calibration-policy.mjs | `21e7bf378a6795a6f36d8e9a05337b59d20571ab350231abc87b1713116f6622` |
| scripts/calibration-data.mjs | `9e03827d1baf410d64a2291daa7665db98c85e090ccba4cae4580b5e253538fe` |
| scripts/calibration-investigation.mjs | `2383e9858259236298b08c81ffebb126eced0117b52c2681ae068a3852578a96` |
| scripts/calibration-collection.mjs | `807a24184d6c28b5b36be31460ba71eda9fa542346d38cda3e3d9613089db75c` |
| scripts/semantic-quality.mjs | `a073e7995e887621b38f355274fe1c86b4d60c9666dc5514468aebf95891f299` |
| scripts/run-p2-embedding-gate.mjs | `1f165513ea6f13cd107705228873c446e3e45f855455f079ed00f75bc5bb8f37` |
| androidApp/src/androidTest/kotlin/dev/edgecompanion/app/CalibrationCollectionTest.kt | `f2e3d56f82af934cf4b62455d1e006ddd33448cc087e29f5f66621b1427b57a6` |
| shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/ModelManager.kt | `2debfc3013abecf87cd9729a4f9cafe8e4f845c74b22187b55ac3332b78dad06` |
| shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/MemoryPolicy.kt | `a7e6f26248be395642808d8c54a82d3c528dd6bd185150c6647ffbf74a01c6e5` |
| shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/embedding/EncodingCall.kt | `0d9e630df292589d3faef9d5aeb8b77192c66af5ebbb40582409e4d947f39435` |
| shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/embedding/ForegroundEncoder.kt | `e95bff3fb5d471fc22944d20efdba06d445d8c8ed2c3870fbd4cad201470a503` |
| shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/embedding/EncoderAdmission.kt | `94d4a7ba7345e5c8991ff769cba1d703ccaa2a96f197d810e2b8600a994674b5` |
| shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/embedding/UseTokenizer.kt | `5ade61789b3766bb03329a8e2c37fc929e99284070a8cfe23bd9c46968be22d8` |
| shared/src/androidMain/kotlin/dev/edgecompanion/core/inference/embedding/AndroidUseAdmission.kt | `87a2be582477d9eff4f3522b8f29e5fa6f37e5f1f4d08a0fbb587fee57af2f03` |
| androidApp/src/secureQa/kotlin/dev/edgecompanion/app/prerequisites/AndroidForegroundEncoder.kt | `c9776a6a7d2632d033fbe303436ceb020fe24f6551053afced948215a062cf3a` |
| androidApp/src/secureQa/kotlin/dev/edgecompanion/app/prerequisites/EmbeddingGateService.kt | `984782fe31f62894e8193195c49fbef82b48bd01dc5715af75b8bda60842ceff` |
| androidApp/src/secureQa/kotlin/dev/edgecompanion/app/prerequisites/SdkPacketTransport.kt | `dedbb5ec79ae343d37a58032f02eeb05b90c1e5c65836929e38177889b369e9f` |
| androidApp/src/androidTest/kotlin/dev/edgecompanion/app/support/DeviceSupervision.kt | `624a90c5e08c7d396639cb7c82bdf7e4edf743df6214cae59d9efde35f6fad62` |
| scripts/qa-supervision.mjs | `ebafec1cd4b6de9b033050c57f5953b17c65b15727ef3c394a37e89b521e8145` |
| scripts/monitor-model-thermal.mjs | `1ac73ad477de2f3574909b97635ab54f4ab7bf3e78e1933dd4af5cdf77bf019c` |

## Verification and Boundaries

- PASS: independent read-only byte/hash/provenance assertions; existing
  `validateDataset` for all splits and `validateSeparation`; protocol-definition
  equality; complete ordered grade-map coverage and population counts.
- PASS: `node --test --test-name-pattern='^renewed depth freeze preserves rejected bytes and changes only the requested question$' scripts/tests/calibration-policy.test.mjs`
  exited 0, one test passed, zero failed/skipped. Only this preservation
  regression ran; no scoring, fitting or vector test ran.
- FAILED/BLOCKED: none in this reapproval scope. The archived rejection remains
  FAIL for its original bytes.
- NOT RUN: Gradle build/JVM/lint, Android/device tests, ADB, native collection,
  vectors, similarity/ranking computations, policy fits or heldout evaluation.
  No benchmark result or native-result artifact was opened. The required policy
  document contains historical v1 headline results; those were encountered there
  and did not inform any judgment. No current score/result evidence was inspected.

Applied installed `kotlin-testing-kmp` and `kotlin-kmp-code-review`, including
`reference/code-quality-and-tests.md`, for evidence and scope discipline.
Reviewed AGENTS, qualification policy, QA prompt, deferrals and configured modules.
This wording-only review changes no Kotlin code, source-set boundary or UI;
architecture escalation and device tests are not required to judge it.

Branch remained `dev`; HEAD/base for this review:
`2f36abda3f72823e1c80c00ec2569ffe9eb3342b`.
Working-tree inputs, including untracked fixtures and source, were reviewed
against the archived rejection rather than inferred from an empty Git diff.
The existing modified wrapper and untracked implementation were only read/hashed.
No commit, push, merge or branch change was performed.

## Limitations and Attestation

The synthetic representativeness limitations remain: shared authoring templates,
conspicuous fictional entity anchors, four correlated queries per base record,
binary 0/3 relevance, short English-only records, weak fixed notices, and
multi-positive examples consisting of copied details plus explicit dispute.
These do not establish broad-domain or natural-conversation performance, graded
ranking quality, multilingual support, grounded answers, privacy/offline
behavior, latency or device compatibility. No performance claim is made.

I issued `planning/fixtures/calibration-policy-v2/independent-review.json`
with schema 2, this reviewer's identity, actual review timestamp, current exact
lock hash, and the lock's dataset mapping. It records independent complete
judgment/separation review and no score inspection. This satisfies the independent
pre-collection judgment gate for this freeze. Supervised collection, development
selection, eligible one-time heldout qualification and later P2 acceptance remain
separate requirements; semantic search is not enabled by this review.

Only this report and the approval JSON were written.
