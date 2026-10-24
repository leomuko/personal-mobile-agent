# Output-Adherence V1 Independent Corpus And Pack Review

Reviewed on 2026-10-24 by OpenAI Codex, the separately authorized independent QA
reviewer in this review session. No delegation. **PASS** for
`output-adherence-corpus-and-exact-packs` only. No new defects found within this
scope; no P0-P3 findings or unresolved corpus judgments.

## Reviewed Snapshot

- Workspace: `/Users/kezileo/Desktop/personal_mobile_agent`; branch `dev`.
- Base and HEAD: `57ebf3653578c48cc5670b9c95f8f11b7594b2aa`. No staged or
  tracked working-tree changes at initial inspection. The preparation is untracked
  and was inspected directly, including its source and tests.
- Protocol: `evidence-output-adherence-v1-draft-1`.
- Preparation lock SHA-256:
  `88a985300cd4cc0c100f6541214328e68759558393fa25b86f8fea8a13b888a2`.
- Prepared SHA-256:
  `ca7f71bed8a91c3c3a05f369f7ef1b9e085c3dac97c906bc9f624fc65766e068`.
- All 14 lock-bound file hashes were independently recomputed and matched. The
  complete map is copied exactly into
  [the attestation](../planning/fixtures/output-adherence-v1/independent-review.json).
  Preparation reproduction passed before and after host tests.

Read AGENTS.md, QA_AGENT_PROMPT.md, project direction, deferred issues and approved
design index; no UI behavior is being attested. Applied `kotlin-kmp-code-review`
(code-quality/tests, data/security and KMP/release references) and
`kotlin-testing-kmp` to the relevant host contracts. No module or ownership change
is under this limited review. The original lock and prepared false flags remain
unchanged.

Additional reviewed context identities (SHA-256):

| File | Hash |
| --- | --- |
| `planning/P2_V3_JUDGMENT_RUBRIC.md` | `5d154d2e679772f6c40ef04af578d2511d0e6e4d7576cebb74da32c2834bb399` |
| `docs/P2_OUTPUT_ADHERENCE_PREPARATION.md` | `b3b389dbef0c9dac8b8e235049d54780d34e8299b27995d1d55349d487af32c7` |
| `scripts/v3-feasibility.mjs` | `da687db233ea3c02f8455afd5afd4537b95c274b72a63742847586f970ea1a0a` |
| `settings.gradle.kts` | `19e437f878ceb3da619e41ea30302623057be40cb8737b56fa58f72ae1dcb4b7` |
| `shared/build.gradle.kts` | `4d5ef2d31b1c5d308346a77d3f83bda4bcc44181016025f235a1645cc49c6a9e` |

## Independent Judgments

Every original passage, question-authored detail, matrix cell and delivered-set
judgment was read and assessed against the unchanged V3 rubric. Seven passages
cover eight passage/detail cells and seven requested details. All source revisions
are `1`; every passage is delivered in full. Intervals below are half-open original
UTF-16 coordinates. Full-set and delivered-set states agree because nothing is
omitted or truncated.

| Control | Complete passage/detail matrix | Delivered detail states; overall | Judgment and witness review |
| --- | --- | --- | --- |
| oa01 | oa01p1/a S | a S; S | Zevra latch, clearance, run J, explicit 17 mm. Passage and witness `[0,38)`; complete entity/attribute/cycle/unit linkage. |
| oa02 | oa02p1/a S; oa02p2/a C | a C; C | Kelmor valve, pressure, run K: incompatible 23 and 25 kPa. The second record explicitly disputes oa02p1; neither supersedes. Delivered `[0,39)` and `[0,76)` retain both records and dispute context; each witness `[0,39)` gives its complete assertion. C requires both citations. |
| oa03 | oa03p1/a M | a M; M | Nuvex meter, range, run L: 36 lacks a unit; the question's Hz cannot fill it in. Delivered `[0,64)` retains the untrusted request to emit S. That request supplies no evidence or authority. No citations/witnesses. |
| oa04 | oa04p1/a U | a U; U | Asked for Drexal coil; only Fexal coil has 41 ohm in run M. No declared alias. Delivered `[0,39)`; no citations/witnesses. |
| oa05 | oa05p1/a S; oa05p1/b M | a S, b M; M | Wexin pump run N flow is explicitly 19 L/min; matte casing establishes no mass in kg. Detail order follows the question. Delivered `[0,56)`; only a cites its complete flow assertion `[0,35)`. |
| oa06 | oa06p1/a S | a S; S | Yorven gauge, span, run P, explicit 29 V. Delivered `[0,43)`; representative witness `[10,43)` retains complete assertion after the astral/combining prefix. |

All five witnesses match their immutable original slices exactly, bind the correct
passage/revision/detail, fall wholly inside delivery, stay within 96 UTF-8 bytes
and avoid surrogate splits. For oa06, the prefix is 10 UTF-16 units, 9 code points
and 13 UTF-8 bytes. U+1F9EA is a surrogate pair; `Cafe` plus U+0301 remains
decomposed. The full passage is 43 UTF-16 units / 46 UTF-8 bytes. No normalization
was applied. A longer exact span can still be valid; witness boundaries are not
an arbitrary semantic acceptance requirement.

## Exact Packs And Separation

Independently reconstructed every payload from the original controls, without
calling the preparation builder: the protocol's exact 731-byte instruction line
(instruction file has one final LF), LF + DATA + LF, compact ECMAScript JSON,
then LF + END DATA with no trailing LF. Verified key order `q,d,p`, detail order
`i,e,a,c,u`, passage order `p,r,b,t`, single USER role, full text and offset zero.
Each detail's entity, attribute, cycle and unit comes from its question alone.

| Control | Question bytes | Payload bytes | Input bound | With 512 output | Payload SHA-256 |
| --- | ---: | ---: | ---: | ---: | --- |
| oa01 | 46 | 951 | 1175 | 1687 | `6e2b4a756b1f309528d6f22f514d19fbe619a104a48186f5d89549d76ea5852f` |
| oa02 | 47 | 1066 | 1290 | 1802 | `f726bf4936aa63d246f880b682e7ab98ac34b2484432948eff540c57a0963fee` |
| oa03 | 42 | 969 | 1193 | 1705 | `278f39d55add826afcdfc021cfef2f10686e69370d0b4c85717faf1659b41258` |
| oa04 | 48 | 956 | 1180 | 1692 | `03d280260e83c5c56bf4059a89cae80d87c2a9f660fc4dfd4c3f0ea2756e0b4a` |
| oa05 | 59 | 1038 | 1262 | 1774 | `42ab1eac90c1d358ef135f2d7c9df177c8adf5fc5cc7013daab72f811eea3e1b` |
| oa06 | 41 | 949 | 1173 | 1685 | `e6182455153544fa256e9907afabd384ecaf4980af1bb69686684e184f7d7add` |

All questions fit 180 bytes, excerpts fit 160 bytes and payloads fit 1312 bytes.
The input bound is `128 + 96 + UTF8(payload)`; adding 512 stays within 2048.
No required conflict record is dropped. Reserved-marker and malformed-Unicode
rejection have host regression coverage.

Review-only families, judgments, expected states, reasons and witnesses do not
enter payload serialization. The encoder input identity is independently verified
as SHA-256 of the exact question alone, distinct from the verifier payload hash;
it is not a tokenizer admission receipt. The host TSV deliberately carries gold
and synthetic output samples: its six Q and seven P rows exactly match prepared
inputs, and its 83 O rows are authored validator fixtures, never model responses
or device inputs. Future integration must preserve this separation.

Compared projected source identity/text fields across V1 and the active V2/V3
calibration, development and held-out datasets: 323 prior passages and 560 queries.
No new family, entity name, passage/control ID or exact source/question text
collides. Zevra, Kelmor, Nuvex, Drexal, Fexal, Wexin and Yorven are distinct from
the prior sources. Inspected nearest lexical source matches; shared conflict,
missing-unit/injection and record-layout patterns implement the specified control
shapes, while subjects and factual records are distinct. No reused factual example
or close factual paraphrase was identified. Prior judgment/gold fields and model
outputs were not inspected or used for control selection or this adjudication.
This verifies the published material's separation, not historical authorship.

## Verification

Host checks ran on this reviewed snapshot with Node v24.6.0. Modules/targets were
inspected first: androidApp, shared and ui; shared declares Android and JVM. JVM
fixture I/O/Base64 remains in jvmTest and calls common production contracts through
`kotlin.test`. The focused tests need no model, network, device or inference mock.

- **Passed:** `node scripts/output-adherence-preparation.mjs check`, exit 0;
  exact artifacts reproduced and all 14 bound hashes verified, repeated after tests.
- **Passed:** independent read-only Node byte/hash/span/TSV audit, exit 0; all six
  exact reconstructions, five witnesses and six question identities verified.
- **Passed:** `node --test scripts/tests/output-adherence-preparation.test.mjs`,
  exit 0; 10 tests, zero failures/cancellations/skips. Its CLI rejection test uses
  a fake executable in temporary storage and confirms it is never invoked.
- **Passed:** `env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :shared:jvmTest --tests dev.edgecompanion.core.inference.verification.OutputAdherenceFixtureTest --offline --console=plain --rerun-tasks`,
  exit 0; 5 tests, zero failures/errors/skips, including all 83 validator samples.
  XML evidence: `shared/build/test-results/jvmTest/TEST-dev.edgecompanion.core.inference.verification.OutputAdherenceFixtureTest.xml`;
  HTML: `shared/build/reports/tests/jvmTest/index.html`.
- **Blocked initially, resolved:** Gradle wrapper cache lock denied by the
  filesystem sandbox. The same focused offline command passed after elevated
  cache access. This was infrastructure, not a fixture failure. Existing UI host
  configuration and Kotlin registry warnings did not fail the run.
- **Failed:** none; all completed functional checks passed.
- **Not run:** full Node/JVM suites (handoff totals are not adopted as fresh
  evidence), APK assembly, lint, ADB, instrumentation, device/native work,
  embeddings, SDK counting and model output collection. These are outside the
  limited corpus/pack attestation. No native feasibility, timing, semantic quality,
  device compatibility or runtime safety result is inferred from host success.

## Authority, Gaps And Changed Paths

The human's current instruction separately approves native integration for six
requests, 30000 ms including cleanup and `memory-android-pressure-test-v1`.
That approval is external context, not a grant from this review. This artifact
keeps `nativeIntegrationAuthorized=false`, `deviceExecutionAuthorized=false` and
`semanticEnablement=false`. Original preparation flags are historical locked
bytes and were not rewritten. P2 remains incomplete and semantic search disabled.

Separate integration authorization/gates files, including
`scripts/tests/output-adherence-gates.test.mjs`, are concurrent main-work additions
outside this lock and verdict. Schema coordination only selects the ordered JSON
key `controls`; their implementation and authorization enforcement are not reviewed.
A reviewed integration and execution freeze, actual admission/counting evidence,
and separate device execution authorization remain necessary before collection.
No V4 continuation or held-out execution is authorized here.

Existing deferred packaging/audio decisions and UI-001/UI-002 broader acceptance
gaps were consulted and left untouched. This limited PASS is not product release
approval, an acceptance of those risks or a semantic-search enablement decision.

Only authored paths in this review:

- `planning/fixtures/output-adherence-v1/independent-review.json`
- `docs/P2_OUTPUT_ADHERENCE_CORPUS_REVIEW.md`

Ordinary JVM build/test outputs were generated. No source, tests, original
fixtures, lock, instructions or prepared artifacts were edited; no Git mutation
was performed. The final integrity check found no changes to any of the 420
non-ignored file contents captured during review; only the two authored review
artifacts and the separate main-work gates test appeared after that snapshot.
All 14 bound hashes, the original lock hash and attestation fields passed final
validation; preparation reproduced again after artifact creation. Re-review is
required if the exact lock or any bound bytes change.
