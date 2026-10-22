# P2 Independent Corpus Judgment Review

**Judgment approval: FAIL. Split separation: PASS for the stated synthetic scope.**
No approval issued; `independent-review.json` was absent and was not created.
Collection is not authorized by this review.

Reviewer: separate independent QA judgment-review subagent, not
`implementation-agent`; this reviewer did not author these fixtures.
Review completed at `2026-10-21T21:28:12.259Z` (`1792618092259` ms).
This is a complete unscored corpus review, not a code-review approval or sample.

## Blocking Finding

**P2: An intended missing-detail negative already has a qualitative answer.**
In `planning/fixtures/calibration-policy-v2/development.json:2913`, `dev020n1`
asks, "Borage stand: How deep are the oil cups?" The associated `dev020p`
at line 152 says the legs sit in **shallow oil cups**, yet its grade is 0
at line 2938 and the whole query is classified unanswerable.

"Shallow" supplies a depth description. The question does not request a numerical
measurement or units, and the rubric does not restrict answers to numerical
depths. Consequently, the all-zero judgment has an unresolved answerability
ambiguity, beyond mere topic matching. Returning this passage could count as a
false match despite containing the requested qualitative detail, affecting
development eligibility and policy selection. Confidence: high in the ambiguity;
an intended exact-depth interpretation is plausible but unstated.

The author must resolve the wording/judgment boundary before collection, freeze
the resulting inputs under the applicable protocol, and obtain a fresh independent
review. This reviewer changed no query, passage, grade, protocol, or lock and does
not prescribe a replacement grade. Structural validation cannot resolve this.

## Snapshot and Coverage

SHA-256 values computed from exact current file bytes and rechecked before writing:

| Input | SHA-256 |
| --- | --- |
| Current v2 lock | `683c371622fa303de564448127341332a9be870fe99abd63ef6e50fe4e4a8140` |
| v2 protocol | `537f34bcc80506bc68c150c1563689b51107e093e53f03a33658ccd06134b55a` |
| Calibration | `241af22ac34423e241133bae04773c8565d78f0f7a2245648e94382853765757` |
| Development | `58ac01fc24908da5fbd02ad6e6b049b06cf96d7ea7ac120bc50811b1f97ef7fe` |
| Heldout | `599cad04ff2f444842def1c2d249e0f8d5188320e4897514072d8e96ba0c99a6` |
| v1 comparison fixture | `32f9d6d231fb5f5acca593eb27e128bc0f09266e311375db82cff100b6325abe` |

| Split | Queries | Labeled answerable / unanswerable | Passages | Complete pair judgments | Grade 3 / 0 | Two-positive queries |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Calibration | 120 | 60 / 60 | 39 | 4,680 | 72 / 4,608 | 12 |
| Development | 80 | 40 / 40 | 28 | 2,240 | 48 / 2,192 | 8 |
| Heldout | 120 | 60 / 60 | 42 | 5,040 | 72 / 4,968 | 12 |
| Total | 320 | 160 / 160 | 109 | 11,960 | 192 / 11,768 | 32 |

Read every v2 passage, every query and its complete ordered grade row, including
all cross-record and cross-family zeros; did not infer correctness from positives
alone. Structured projections covered every query (120/80/120, checked against
the original arrays). Reviewed 80 base records, 16 conflicting records and 13
fixed notices. Compared all v1 texts: 34 passages and 60 queries, including its
non-English diagnostics. Counts above describe author labels, not approval of
the disputed negative.

## Rubric and Semantic Findings

Positive relevance requires the requested detail or an explicit contradiction of
it. Entity/topic overlap alone is 0. The used 0/3 grades are permitted; grades 1/2
need not occur. The 16 conflict passages quote and dispute the source details:
both base and conflict records are relevant to their detail questions, without
treating the disputed content as confirmed factual support. All 32 multi-positive
queries correctly include these two records. Generic supersession notices supply
no individual requested fact or explicit individual contradiction and remain 0.

Except for the blocker, no additional blocking judgment defect was identified
across the complete matrix. Missing-detail negatives distinguish such things as
`cal029n1`'s numeric speed limit from a location to slow down and `dev011n1`'s
exposure duration from a lux limit. Lexical mismatch includes painter/towing rope,
reefing/reducing sail area, and signatures/sections. Short, entity-anchored
questions remain interpretable. Distinct entities do not inherit another
record's answer simply because the procedure or material is similar.

## Separation and Limitations

Content supports the family partition: calibration covers seed handling,
stagecraft and small-craft rigging; development covers observatory operations,
embroidery, conservation and apiaries; heldout covers relief printing, trail
wayfinding, choral practice, aquaria, bookbinding and joinery. These are distinct
record collections and 80 distinct complete entity names, not just different
family labels. Adjacent activities share vocabulary (stage/choral rehearsal,
embroidery/binding, conservation/paper handling), but no shared underlying record
or close paraphrase of its requested facts was found across splits.

V1's observatory visitor schedule differs from development's technical observing
procedures; its costume-measurement handoff differs from calibration's fastening
and storage details; its choir venue change differs from heldout's vocal and
conducting procedures. Repeated generic words such as "Cedar" and "blue tin"
refer to different entities/facts, not reused source records. No exact or close
paraphrase reuse of v1 examples was identified.

Limitations: all splits share authoring templates and conspicuous fictional entity
anchors. Four questions share each base record; query counts do not represent
independent real-world tasks. Multi-positive coverage consists entirely of copied
details plus explicit dispute, not complementary evidence or competing replacement
values. Fixed notices are weak distractors, although other entity records also
serve as negatives. Only English, short synthetic records and binary relevance
are exercised; no broad-domain, natural-conversation, fine-grade ranking,
grounded-answer or device-performance claim follows.

## Verification and Boundaries

- PASS: existing `validateDataset` on all three splits and `validateSeparation`
  via a host-only Node import; complete valid grade maps, expected label counts,
  no exact duplicate text across splits/v1 and no family-label overlap.
- PASS: protocol/dataset hashes match the lock; all 20 locked implementation
  file hashes match. Hashing is snapshot binding, not another code review.
- FAIL: semantic judgment approval for the development depth ambiguity above.
- NOT RUN: Gradle, ADB, native collection, scoring, fitting and heldout evaluation.
  No embedding, native-result, similarity-score, fit or metric artifact was opened.
  The required handoff document includes historical v1 headline results; those
  were encountered in that document and were not used to judge this corpus.

Applied installed `kotlin-testing-kmp` and `kotlin-kmp-code-review`, including
`reference/code-quality-and-tests.md`, to evidence quality and scope discipline;
read AGENTS, the QA prompt, qualification policy, protocol, lock and deferrals.
No Git actions or production/fixture/lock edits. Only this report was written.
An unqualified judgment PASS has not been obtained; approval and collection
must remain blocked pending resolution and re-review.
