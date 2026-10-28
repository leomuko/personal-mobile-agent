# Constrained-Decoding V2 Proposal Review

Date: 2026-10-24. Reviewer: OpenAI Codex, the retained user-authorized primary
independent QA reviewer. No delegation or substitute reviewer.

**Verdict: PASS for proposal coherence and the exact prepared bytes, with the
prerequisites below still pending.** No actionable blocking defect was found in
the reviewed proposal or frozen descriptor expectations. This verdict is not
integration approval, reference-schema qualification, an execution freeze or
device permission. The nine requests, 30000 ms ceiling, pressure profile and
one-token negative exception remain PROPOSED, not approved.

## Exact Snapshot

Workspace: `/Users/kezileo/Desktop/personal_mobile_agent`; branch `dev`.
Base and HEAD: `ae678b7ecd3760ba3739bf89127ac5cb8bae2fa1`.
The proposal and fixture files were untracked and inspected directly. No staged
or tracked working-tree changes were present.

Protocol: `evidence-constrained-decoding-v2-draft-1`.
Preparation-lock SHA-256:

`5e8edd23535d26c9985a28fd59e51c29b60974561580c0f807fec0f5b297e1d9`

All 14 bound file hashes were independently recomputed and matched, including
the earlier ADR/audit review, V1 prepared inputs and preparation lock, unchanged
strict validator and both 83-output-fixture bridge files. The new lock is a
proposal lock, not a source/APK execution freeze. Its integration, device,
semantic and reference-validation flags remain false and were not rewritten.

| New bound file | SHA-256 |
| --- | --- |
| `planning/P2_CONSTRAINED_DECODING_PROTOCOL_V2.md` | `c59b6706b5cd390b569d10d99931f0fa93237f36c99584ff133dd730e4dd90b5` |
| `evidence-output.schema.json` | `36232d7c46e2343ad0a5b40c6328e1ff3ff10b6ef76d457a93b245052390e691` |
| `keyword-sentinel.schema.json` | `e10adc86c84b6615df190205cf94b1baae0c0d1ef228dda2b5c5fe999ca5be53` |
| `invalid-type.schema.json` | `041bb8c53280f55f249550a741e5493789a5d49c11d25a69eb00cf9eaccc06ab` |
| `cases.json` | `c7eceaf6fc6f7d56ddcbc881e2378713d242438fafc1f2b857275b49dd0ead11` |
| `host-keyword-checks.json` | `033d6fbd4544cbedf7b80450a29a00c066766c099cf8f8018ce90ecf1af227de` |

Fixture basenames above are under `planning/fixtures/constrained-decoding-v2/`.
The exact lock provides the complete 14-file map. Hashes were stable through this
review. Applied QA_AGENT_PROMPT.md and the previously read Kotlin review/testing,
platform-boundary and ownership criteria to this proposal-only scope.

## Schema And Assertion Assessment

All three assets parse as JSON, retain UTF-8/LF bytes including their final LF,
use only the declared keyword set, contain no remote references and fit the
proposed size/depth/node limits. Independent recursive value-node inspection gave:

| Schema | UTF-8 bytes | Maximum depth | Container/value nodes |
| --- | ---: | ---: | ---: |
| Evidence output | 1290 | 10 | 72 |
| Fixed sentinel | 1189 | 11 | 66 |
| Deliberately invalid type | 110 | 2 | 3 |

Manual Draft-07 inspection found the positive schemas coherent: required keys,
object types, no additional properties, bounded arrays, string bounds, state enums
and numeric bounds align with the stated structural candidate. The sentinel fixes
one S detail, one citation to sentinel-p revision 1, span [0,1), quotation X. Its
base is satisfiable and accepted by the unchanged strict validator. The invalid
type asset is valid JSON with an intentionally invalid schema type; it must not
be treated as an ordinary allowed-schema asset.

This is not a completed reference meta-schema check. No vetted Draft-07 validator
was installed, substituted or implemented. Both positive meta-schema checks, the
deliberate negative and the 34 reference-schema results remain pending under the
protocol's explicit pre-integration gate.

Manually reviewed every sentinel/evidence-schema expectation in the 34 descriptors.
Their distinctions are consistent: empty S citations, unknown references/revision,
wrong quotation and a 32-character/128-byte Unicode quotation can satisfy the
general structural schema while failing the unchanged context/byte checks. Raw
1.0 represents an integer under Draft-07 semantics and must not be treated as a
fractional boundary. String length does not substitute for UTF-8 byte limits.
These distinctions agree with the official
[Draft-07 validation specification](https://json-schema.org/draft-07/draft-handrews-json-schema-validation-01#rfc.section.6.1.1)
and its
[string-length rule](https://json-schema.org/draft-07/draft-handrews-json-schema-validation-01#rfc.section.6.3.1).

Independently materialized every mutation against a fresh cloned base, preserved
rawOutput when present, and invoked the existing compiled JVM strict validator
in memory with the exact sentinel context and 128 generated tokens. No source or
test file was written. All 34 strictAccepted expectations matched; the separate
base check also passed. Of these 35 review checks, two accepted (base and 1.0)
and 33 rejected: 20 SCHEMA, four JSON, three SPAN, three REFERENCE, one QUOTATION
and two SIZE. This adds independent strict-side evidence only; it does not turn
the pending reference-schema assertions into tested results or change the lock.

The 34 descriptors are not the complete future host qualification suite. Alias
substitution, duplicate decoded keys, truncation, order/aggregation, surrogate
splits, opt-in/identity failures, native errors and owner/phase races remain
explicit host prerequisites. The original 83 synthetic validator outputs are
preserved by hash and remain regression fixtures, not model successes. No claim
is made that all those future host obligations have now been executed.

## Cases, Inputs And Gold Separation

The order is exactly cd01-cd09: four positive structural/control checks and five
expected negative operations. At most one native generation attempt per case
does not imply nine successful generated replies. Correct rejection, cancellation
or exhaustion may pass a guard check while remaining a revoked, non-answer result.

Real-control selectors name only a V1 source control's input; the V1 prepared
file hash matches. Expectations, reasons and witnesses remain outside selected
model/encoder input. No candidate schema embeds the expected per-control state,
gold coordinates or witness quotation. General S/C/M/U enums are the contract,
not a supplied answer. The fixed sentinel's public schema values are explicitly
test configuration and earn no semantic credit.

The exact sentinel payload and referenced V1 payloads fit the existing byte bound:

| Case | Selected input | Payload bytes | Input bound | Plus 512 reservation | Decode cap |
| --- | --- | ---: | ---: | ---: | ---: |
| cd01 | Fixed structural sentinel | 122 | 346 | 858 | 512 |
| cd02 | oa01 | 951 | 1175 | 1687 | 512 |
| cd03 | oa01 | 951 | 1175 | 1687 | 512 |
| cd04 | oa01 | 951 | 1175 | 1687 | 512 |
| cd05 | oa02 | 1066 | 1290 | 1802 | 512 |
| cd06 | oa02 | 1066 | 1290 | 1802 | 512 |
| cd07 | oa01 | 951 | 1175 | 1687 | 1 |
| cd08 | oa02 | 1066 | 1290 | 1802 | 512 |
| cd09 | oa06 | 949 | 1173 | 1685 | 512 |

The input bound is 128 + 96 + UTF8(payload); every total stays below 2048.
Question lengths and delivered intervals/excerpt sizes also fit. These are byte
admission checks, not fresh SDK token counts or encoder admission receipts.
Schema/template-added tokens may not exceed the same bound. Only cd07 has cap 1;
the full output reservation remains 512 for all nine cases. The exception is not
an implicit relaxation of ordinary engine/chat budget contracts.

## Ownership, Timing And Classification

The proposal distinguishes the observed SDK boundaries correctly: provider setup
in createConversation versus per-request schema compilation inside the later send.
Setup cancellation is not engine-load cancellation or proof of interrupting one
specific LLGuidance instruction. The prefill case likewise does not claim an
internal GPU instruction location. Recorded entry, signal, return/publication and
terminal evidence must establish the intended interval. Missed triggers block
without retries, inference sleeps or repeated sampling.

Ownership remains with ModelManager through uncertain/late handle publication,
encoder death before Gemma admission and confirmed cleanup. A cancellation signal
revokes eligibility immediately; an unavailable handle does not release the
reservation. Uncertain completion quarantines admission. Final acceptance cannot
promote ineligible runtime delivery, and a latched outcome is retained separately.

One complete clock includes admission, schema checks/setup/compilation, encoding,
generation, final validation/classification and cleanup. A result or cleanup at
30000 ms blocks, including an otherwise expected negative. The proposed fake-clock
boundary tests and resource/supervision stops are explicit. Cleanup continues
supervised after deadline without earning inference-time or success credit.
Available-RAM floors alone are bypassed under the proposed pressure profile;
ordinary policy, required signals, total RAM and thermal/skin safeguards remain.

Typed/identified cd02 compilation rejection is required; GENERATION_FAILED alone
cannot establish the intended failure. Likewise cd07 requires tokenizer/vocabulary
proof that a complete schema-valid response cannot fit one output token and
specific exhaustion evidence. Empty output or an arbitrary error is insufficient.
The proof is a pre-integration prerequisite, not assumed from the selected cap.
Unavailable evidence blocks instead of silently substituting a cap or retrying.

The stop rule consistently distinguishes expected, safely cleaned negative
operations from unexpected failures. Expected negatives are never successful
replies/abstentions. Failed positives, missed phases, unsafe resources, lost
supervision, unavailable prerequisites, deadline failure and uncertain cleanup
stop subsequent work. Report populations remain separate; no nine-case p95 or
semantic-quality conclusion is permitted.

## Caveats And Remaining Gates

Before any separately authorized native integration, complete the pinned,
reproducible Draft-07 reference setup and schema/assertion checks, the one-token
impossibility proof, and the required host ownership/phase/deadline regressions.
Prove that native compilation errors can be identified without confusing them
with load or generic generation failure. Exact SDK keyword behavior, token
accounting, callback timing, constrained cancellation and safe cleanup remain
unqualified native questions. A reference pass alone would not resolve them.

Human scope approval must separately name nine requests, the one-token negative
exception, complete 30-second ceiling and pressure profile. Later implementation
review, a new verified source/APK/schema/plan/profile freeze, separate designated
S24 authorization and a retained attempt ledger are still required. No previous
six/eight-request authorization is reused. Historical stopped requests remain
stopped; exposed V1 inputs are structural regressions, not fresh accuracy data.

## Verification And Changed Paths

Passed during this review: exact lock and all 14 bindings, schema JSON/LF/size/
keyword/depth/node inspection, nine selected-input budget/interval checks,
descriptor count/identity checks, manual schema-expectation assessment, base plus
34 strict-validator replays, original V1 preparation bindings and 83-output bridge
preservation, git diff --check and empty staged/tracked changes.

Not run: vetted reference meta-schema/instance validation, tokenizer impossibility
proof, new integrated host regressions, builds, broad suites, native schema
compilation/enforcement, model execution, ADB or device work. No dependencies were
installed. Existing false flags and pending statuses accurately retain these gaps.
This proposal PASS must not be presented as passing any of those gates.

Only authored path: `docs/P2_CONSTRAINED_DECODING_PROTOCOL_V2_REVIEW.md`.
No bound file, source, test, historical output, approval or lock was edited.
No Git mutations, delegation, implementation, new freeze or execution approval.
Deferred decisions remain unchanged. P2 is incomplete and semantic search remains
disabled.
