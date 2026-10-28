# Constrain Evidence Output Before Changing Citations

Date: 2026-10-24. Status: **recommended next experiment, pending review and scope
approval**. Host-only capability audit and tests are implemented; native wiring
and device execution are not authorized by this ADR.

## Context

The frozen output-adherence experiment stopped after `oa01`: its citation used
`t` instead of required `e` and `q`. The 17,473 ms request had confirmed cleanup,
`lowMemory=false` and thermal status 0. This is a schema-adherence failure, not a
memory or cleanup failure. Five controls remain unrun. Preserve the failed output,
approval records and original validator byte-for-byte.

The [pinned capability audit](../P2_STRUCTURED_OUTPUT_CAPABILITY_AUDIT.md) finds
an existing Kotlin schema-response API and traces it through JNI to LLGuidance.
Our adapter does not currently enable or pass it. No dependency upgrade, custom
JNI bridge or tool execution is necessary to propose testing this capability.
Actual Android/GPU schema enforcement remains unverified.

## Decision For The Next Proposal

Prefer **constrained decoding of the existing evidence-output contract** before
replacing it with evidence-ID selection. This chooses an investigation direction;
it does not declare schema enforcement or semantic accuracy achieved.

Retain the current `p,r,b,e,q` citations and strict validator. Supply an explicitly
versioned, bounded, internally authored JSON Schema to the runtime, not an
instruction merely asking for JSON. Do not let source text choose the schema,
turn it off, register tools or alter runtime configuration. Invalid/unsupported
schema or missing capability must reject; never silently fall back to free text.

Before any new model experiment, independently review one versioned protocol
that fixes the schema bytes, source/model/runtime/backend identities, prompt and
request scope, expected outcomes, deadline, resource profile and first-block rule.
Do not inherit V1's six-request authorization or V4's 30-second permission. Reuse
of exposed synthetic controls, if proposed, must be labeled regression testing,
not independent held-out accuracy evaluation. Leave all unexecuted historical
requests stopped.

## Ownership And Placement

No new manager, database, UI or production search path is needed. A future
internal request would carry a bounded app-owned constraint identity bound to
the generation, exact pack and context validity. Kotlin/JNI SDK types stay in
`androidMain`; the common request boundary must not expose vendor objects.

The existing `ModelManager` retains the complete request reservation, serial
encoder release before Gemma admission, immediate cancel signaling, stale-result
invalidation and confirmed cleanup/quarantine. Models-panel operations remain
isolated. Schema compilation/provider creation is native work, not a free host
precheck: account for its allocation, lifetime and cancellation explicitly. Hold
ownership until cleanup is confirmed even if the user-facing deadline expires.

Keep tools empty, automatic tool calling and thinking disabled, GPU as the tested
candidate, context 2048, output reservation 512 and encoder admission 192. Retain
the existing resource safeguards and ordinary app policy. Search's five-second
contract does not change; a future explicit verifier ceiling needs its own scoped
approval. Include schema setup, reclamation, encoding, inference, validation and
cleanup in that whole-pipeline clock. Preserve the final strict acceptance gate
even if the schema compiler accepts the constraints.

## Prerequisite Tests

1. Verify the exact schema's keyword behavior, not just JSON parsing. Check
   required fields, unknown-key rejection, enum/type/array bounds and schema
   identity. Bind evidence IDs only from delivered input, never QA gold.
2. Preserve the strict validator's span, quotation, revision, Unicode, order and
   aggregate-state checks. Test malformed, duplicate, truncated, wrong-reference
   and schema-valid-but-context-invalid results without repairing them.
3. Test disabled/missing format, native schema compilation error and no free-text
   fallback through the existing boundary. Retain raw failed outputs.
4. Cover streaming, cancel during provider setup/prefill/decoding, timeout, token
   exhaustion/EOS, host loss, late callbacks and uncertain cleanup. An exposed API
   is not a cancellation or completion guarantee.
5. Review host implementation, then freeze exact code/APKs/schema/packs/profile
   and checks. Obtain separate S24 authorization. A structural pass would only
   permit proposing semantic evaluation, not enabling search or closing P2.

## Alternative Not Selected Now

If the constrained path is unavailable or fails its reviewed structural gate,
propose a new evidence-ID contract separately. It would let Gemma classify and
select immutable, preconstructed evidence units, with deterministic application
mapping to revision-bound UTF-16 coordinates and quotations. Units/IDs must be
derived from delivered text before inference, without consulting QA judgments.

Reject unknown/stale/cross-request IDs, duplicate/excess references and invalid
states; never guess an intended ID or manufacture missing evidence. Constructing
an exact citation still does not prove that its content supports the claimed
detail. Such a change needs a new validator/protocol and full semantic tests; it
must not reinterpret historical `t` outputs as successful citations.

An automatic fallback to this alternative is not adopted. Prompt-only retry,
post-hoc output repair, expanded budgets, a runtime upgrade/custom bridge and
relaxed validation are also not part of this decision.

## Current Boundary

This slice changes only host tests and audit/decision documentation. No runtime
mode, schema experiment, new packs, execution freeze or device workload has been
implemented. The next deliverable is a separately reviewed experiment protocol,
not another S24 run. P2 remains incomplete and semantic search disabled. Deferred
release packaging, memory-policy decisions, dictation and audio retention remain
unchanged. Committing these documents would not approve native execution.
