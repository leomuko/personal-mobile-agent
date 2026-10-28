# P2 Constrained-Decoding Protocol V2

Date: 2026-10-24. ID: `evidence-constrained-decoding-v2-draft-1`.
Status: **proposal preparation only; pending independent review and explicit scope
approval**. No native integration, execution freeze or device authorization.

## Question And Preserved Baseline

Can the pinned Kotlin/JNI response-format path preserve the existing citation
structure while retaining bounded ownership, cancellation and cleanup?
[ADR 0007](../docs/decisions/0007-constrain-evidence-output-before-changing-citations.md)
selects this next investigation, not an enforced or enabled runtime mode.

Keep the current strict validator byte-identical:
`10be77c8586f0ee9931c9c33b965acd3608fe379010afde75e27b1dfd5526000`.
Keep all previous protocols, approvals, source/APK bundles, raw outputs, failure
classifications and unused held-out data unchanged. V1 `oa01` remains failed;
V1's other five controls and V4's remaining seven requests stay stopped.
This protocol does not reuse their authorization or ledger.

## Fixed Candidate And Bytes

One candidate only: enable the existing SDK's conversation response-format option
and pass the exact per-request JSON Schema through the callback API. Pin
LiteRT-LM Android 0.17.1 and the audited AAR/native member identities in
`docs/P2_STRUCTURED_OUTPUT_CAPABILITY_AUDIT.json`. Retain Gemma E2B SHA
`181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`, GPU only,
temperature 0, topK 1, topP 1, seed 0, thinking disabled and no tools or fallback.

The [proposal fixtures](fixtures/constrained-decoding-v2/) contain exact UTF-8/LF
files, including the final LF; hash and pass their complete schema bytes unchanged:

- `evidence-output.schema.json`: required fields, types, enum states, bounded
  arrays/strings/integers and no additional object keys. Citations stay `p,r,b,e,q`.
- `keyword-sentinel.schema.json`: intentionally fixed, non-semantic structural
  sentinel. It permits one typed object with one detail and one exact citation.
- `invalid-type.schema.json`: deliberately invalid JSON Schema, but valid JSON,
  for an identified native compiler rejection. Never substitute it into real chat.
- `cases.json`: exact proposed order, inputs, schema choice, decode caps and
  separate review expectations. This is not a device plan.
- `host-keyword-checks.json`: a fixed valid sentinel, its strict-validation
  context and 34 exact host mutation/raw-output checks. This file is never model
  input. Expected reference/strict results are assertions to verify, not results.

Draft-07 is the chosen reference dialect, not a claim that the native compiler
implements it. The candidate uses only `type`, `properties`, `required`,
`additionalProperties`, `enum`, `items`, `minItems`, `maxItems`, `minLength`,
`maxLength`, `minimum`, `maximum` and the dialect declaration. No remote references,
regexes, schema-selected tools or source-controlled configuration are allowed.
Unsupported compilation or ignored tested constraints blocks this candidate; do
not remove keywords, change dialect or weaken checks after observing output.

Limit each schema to 8192 UTF-8 bytes, depth 16 and 256 JSON container/value nodes.
All are immutable internal assets, never source/provider-supplied schemas. Future
implementation must hash-check and bound them before native admission. Count
schema validation/compilation work and allocation, not just model prompt bytes.

## Inputs And Oracle Separation

Use V1's already exposed synthetic inputs only as structural regressions, not new
independent accuracy data. Bind the exact prepared file SHA
`ca7f71bed8a91c3c3a05f369f7ef1b9e085c3dac97c906bc9f624fc65766e068`.
Selectors in `cases.json` take only the referenced control's `input`: its exact
payload, question, detail IDs, immutable originals and delivered UTF-16 intervals.
Do not repack, normalize or alter those payloads. Expected states, reasons and
witnesses remain in separate QA-only review data, never model/encoder input.

The sentinel instead uses its exact explicit synthetic question/payload/context.
Its schema intentionally fixes the output values to test structure; those values
are public test configuration, not a semantic prediction or hidden factual gold.
The sentinel earns no answer-quality credit. Validate it with the unchanged strict
validator and compare the resulting typed object against its fixed schema values;
do not demand one JSON property order or whitespace layout.

The schema for real citation controls does not embed expected states, witness
quotes or gold coordinates. Eligibility, known references and every final citation
must still pass the existing validator and the QA control-shape check. Even a
fully correct structural result is not proof of semantic support.

Keep one USER message without history, 2048 context and 512 reserved output
tokens. Preserve the verified prompt bound, 1312-byte payload cap and native SDK
input-token reconciliation. The encoder sees only the exact question under its
existing 192-token receipt and immutable artifact/tokenizer identity. Schema
arguments do not grant an extra prompt allowance; any SDK/template-added tokens
must still fit the same request bound or reject. No silent clipping.

## Proposed Nine Requests

Every case starts with Gemma unloaded and no encoder worker. Do not prewarm models
or overlap residency. Record admission/tokenizer caches separately. At most one
native generation is attempted per case; no hidden calibration or retry calls.

| Order | Case | Required observation |
| --- | --- | --- |
| 1 | cd01, fixed keyword sentinel | Despite the contradictory user payload, raw output matches every fixed type/value/cardinality and passes strict validation. This is a point conformance check, not a universal enforcement proof. |
| 2 | cd02, invalid schema | Identified native schema-compilation rejection, zero generated text, revoked delivery and confirmed cleanup; no unconstrained send. |
| 3 | cd03, V1 oa01 input | Strict-valid output plus original S control shape; unknown aliases such as `t` still fail. |
| 4 | cd04, setup-call cancellation | Cancellation lands during the observed SDK constraint-setup call; invalidation and cleanup follow the contract below. |
| 5 | cd05, prefill cancellation | Cancel after prefill submission and before first nonempty text, with no eligible partial or late delivery. |
| 6 | cd06, decoding cancellation | Cancel on the first nonempty text while generation is active, with no eligible partial or late delivery. |
| 7 | cd07, deliberately exhausted decode cap | One-token decode cap, unchanged 512-token reservation; prove token exhaustion and reject any partial output. |
| 8 | cd08, fresh V1 oa02 input | Strict-valid C control shape citing both records after earlier failures/cancellations were cleaned up. |
| 9 | cd09, fresh V1 oa06 input | Strict-valid S shape and Unicode-safe original UTF-16 quotation after fresh admission. |

The one-token cap is an explicit negative-test exception, never a raised limit or
reduced context reservation. Implement it only behind this experiment's immutable
request/config identity, not by weakening the ordinary engine/chat budget checks.
Before approving integration, prove from the pinned tokenizer/vocabulary that a
complete schema-valid response cannot occupy one output token. If that prerequisite
cannot be proved, cd07 is blocked; do not guess or substitute another cap.
Record the cap, reservation, SDK output count and terminal/validator reason.
An arbitrary exception or empty response does not establish token exhaustion.

cd02 similarly requires a distinguishable native constraint-compilation rejection
from the SDK call trace/diagnostic, not any generic load/generation error. If the
SDK cannot provide sufficient evidence, report unavailable/blocked. All other
cases must reject invalid schemas before native work. Only cd02's exact reviewed
invalid-schema hash can enter that test-only negative path.

## Keyword Checks And Host Prerequisites

Before native integration, pin a vetted host reference-schema implementation and
its reproducible setup. Validate both positive schemas against its Draft-07
meta-schema; the deliberate invalid schema must fail. A JSON parser or the SDK's
factory alone is insufficient. No such dependency was installed by this proposal.

Verify the base sentinel first, then every frozen independent check in
`host-keyword-checks.json` against a fresh base. Preserve the raw `1.0` spelling
for its exact-integral-number check. The listed mutations cover missing each required root/detail/span
key; extra keys at each object level; `t` substituted for `e`/`q`; wrong enum values;
string/float/boolean/null offsets; negative/out-of-range integers; empty or excess
details/citations; invalid strings and oversized values. Record which schema
keyword and which strict-validator check reject each case. An exact integral
number such as `1.0` must follow the reference integer semantics, not be mislabeled
as fractional. Duplicate decoded keys, truncated/malformed JSON, wrong references,
quote mismatch, surrogate splits, aggregate-state/order violations and >96-byte
Unicode quotes remain strict-validator checks even where JSON Schema accepts them.
`maxLength` counts Unicode characters, not the existing UTF-8 quote-byte budget.

Also cover missing opt-in/schema, tampered identity, disabled mode, native compiler
error, impossible schema, no silent fallback, queue/owner races, late callbacks,
context epoch changes, host loss, cancellation before handle publication and
uncertain cleanup. A reference-validator pass does not prove LLGuidance supports
the same keywords; report host and native observations separately.

## Cancellation And Phase Evidence

Use one opaque request owner and the existing ModelManager, not a second manager
or privileged panel Cancel. Cancel invalidates the request immediately and signals
the active native owner out-of-band; result reconciliation remains serialized.
Do not admit another request, indexing or panel work until cleanup is confirmed.
Uncertain cleanup quarantines admission and requires the established restart path.

For cd04, the setup call is SDK `createConversation` with response formatting
enabled, which constructs the constraint provider. It is not engine loading.
Per-request schema compilation is within the later send call; do not pretend it
has a separate public compilation API. cd05 observes the send/prefill window and
may include schema compilation; it does not prove an internal GPU-prefill location.
For cd04 record monotonic setup-call entry, cancellation signal and setup return,
plus handle publication, terminal outcome and release. A wrapper phase label by
itself is insufficient: cancellation must be between entry and return. This proves
an in-flight SDK setup boundary only, not that a specific LLGuidance instruction
was interrupted. Do not claim native setup is immediately interruptible. If a
handle is not yet available, retain cancellation/ownership until it is returned
and safely closed, or quarantine on uncertain completion. Test this handoff on
host fakes before native admission.

The same temporal proof applies to cd05/cd06. A phase that finishes before the
signal is a missed target, not a pass. Preserve the attempt and stop; no repeated
sampling, artificial inference sleeps or automatic reruns to obtain a good window.
Raw partial text is retained as diagnostics only. Expected cancel/rejection cases
must never acquire ELIGIBLE delivery or a completed assistant message.

## Complete Deadline And Resources

Propose **nine requests, each strictly below 30000 ms through confirmed cleanup**,
under the explicitly declared isolated `memory-android-pressure-test-v1`. This
needs new human scope approval; previous six/eight-request permissions do not
carry over. Thirty seconds is not a production SLO. Search remains five seconds.

Start one monotonic clock at foreground submission, before identity checks,
packing, queueing, reference/schema checks or reclamation. Include resource
admission, counting, encoder loading/encoding and confirmed death, Gemma loading,
constraint-provider construction, schema compilation, prefill, decoding, strict
validation, QA classification and final cleanup. Do not reset or subtract stages.
Record decision time separately from finished cleanup time and retain latched
outcomes. Final eligibility requires runtime eligibility; a protocol check can
revoke it but cannot manufacture it.

At elapsed >=30000 ms invalidate the result and signal cancellation. Cleanup then
continues supervised under existing ownership; timeout does not release a handle.
Cleanup finishing at or after the ceiling blocks the case, even for an expected
negative result. Fake-clock host tests must cover 29999/30000/30001 ms at admission,
after encoder cleanup, before Gemma/schema admission and final publication.
Preserve stop/quarantine evidence; an independent emergency watchdog is not extra
successful inference time. Do not deliberately cross the outer ceiling and call
that a passed timeout test.

OS `lowMemory=true`, missing signals, insufficient total RAM, severe thermal/skin
limits and lost supervision retain their existing stops. Bypass only the declared
QA available-RAM floors, not other checks. Measure provider/tokenizer allocations,
available memory and app PSS separately across setup, generation and release.
Ordinary app policy, release packaging and deferred audio behavior are unchanged.

## Stop, Reporting And Later Approval

An expected, correctly identified schema rejection/cancellation/exhaustion can
pass that case's guard check, but is never counted as a successful reply or
abstention. Stop at the first **unexpected** outcome, invalid positive output,
missed trigger, unavailable prerequisite, deadline/resource/supervision failure
or uncertain cleanup. Report attempted, generated, strict-valid, sentinel-valid,
shape-correct, expected-rejected, cancelled, exhausted, failed, timed-out and
not-run counts separately. Do not hide expected negative operations in accuracy.

Retain exact request/schema/pack identities, format opt-in and native constraint
type, SDK tokens/cap, raw output/error, trigger evidence, monotonic stages,
worker death before Gemma, final ownership/cleanup/runtime status and closed
supervision. Preserve all partial/failed output; no aliases, fence stripping,
field repair, quotation construction or automatic retries. Report min/median/max
with the case type, not nine-case p95 or semantic-quality claims.

First review this proposal and its schema/case bytes. Human approval must name
the nine-request scope, one-token negative exception, complete ceiling and profile.
If approved, implement only the isolated internal path and host regressions; then
obtain independent implementation review. Prepare and independently verify a new
execution freeze binding code, APKs, exact schema/packs, tokenizer proof, profile,
phase observers, classifiers and checks. Install verified private frozen copies.
Obtain separate designated-S24 authorization and one retained attempt ledger.

This preparation lock is not that execution freeze. No feature, native collector,
APK, schema compiler, device authorization or runtime result is implemented here.
Only all nine expected guard checks, safe cleanup and positive structural/control
checks would pass the limited experiment. A pass permits proposing labeled
semantic evaluation, not enabling search, resuming historical requests or closing
P2. Any failure stays preserved and blocks dependent qualification.
