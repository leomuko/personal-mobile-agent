# Model compatibility and capability contract v1 — design only

2026-11-06. This document preserves the product direction: a local-first personal
assistant using user-selected, compatible models. Gemma is a reference
configuration, not a universal tokenizer, budget, template or capability baseline.
No new model, dependency, runtime, UI selector or acquisition is implemented here.

## Existing ownership and model-specific boundary

Keep the conversation/session and encrypted database as the durable source of
truth. Keep the current runtime owner for admission, scheduling, immediate Stop,
cleanup, host/context invalidation and quarantine. A model adapter must not bypass
that owner or create a parallel conversation store. Preserve continuity across
the mascot drawer and full app.

Portable assistant responsibilities include user intent/confirmation, durable
turns and drafts, source/memory eligibility, permissions, provenance, action
authorization, typed failures and presentation. Platform/runtime adapters own
artifact loading, native APIs, tokenization/template application, backend selection,
streaming transport and decoding enforcement. Vendor objects stay at the platform
edge; no proposed model switch transfers ownership of user data to an SDK.

The existing `ModelManifest`/catalog is currently specific to `.litertlm` and the
pinned Gemma reference. `LocalLlmEngine` is an existing abstraction, not proof of
generic model compatibility. Extending catalog/adapter APIs requires a separate
implementation design, migrations where needed and verification. This document
does not make an arbitrary imported file executable or compatible.

## Configuration identity

Every qualified configuration must bind at least:

- Artifact format, immutable revision, exact bytes/SHA, redistribution/use license,
  architecture, quantization and tensor/modalities metadata.
- Runtime/provider version, build/patch identity, ABI, backend and relevant device
  profile. A CPU pass cannot qualify GPU or a separately built SDK.
- Tokenizer/configuration/normalization, vocabulary, special tokens, exact chat
  template and ownership of its application. Prevent double templating and
  accidental role/control-token injection.
- Artifact maximum and configured context, output reservation, proven counting
  method, input limits and supported decoding options/schema dialect.
- Resource admission profile and cancellation/release expectations. Loading,
  encoder/LLM concurrency and idle residency require their own evidence.

Gemma's 2048 context / 512 reserved output remains the unchanged reference profile.
Its artifact-specific counting proof cannot certify another tokenizer/template.
Any second configuration must pass its own exact-input preflight before native
generation. No silent truncation, budget enlargement, cloud or backend fallback.

## Qualification is a capability matrix

Record each capability as unavailable, untested, qualified, failed, blocked or
revoked for the exact configuration and evaluation scope. There is no global
"AI ready" bit inferred from loading.

| Capability | Independent evidence required |
| --- | --- |
| Installation/loading | Artifact identity, supported ABI/runtime, recoverable failures and cleanup |
| Ordinary text conversation | Correct template/roles/counting, streaming, Stop/retry/persistence and host handoff |
| Retrieval relevance | Versioned encoder/format/chunking configuration and frozen relevance evaluation |
| Evidence understanding | Missing-detail, entity/attribute/unit/time and conflict judgments on delivered evidence |
| Structured output | Native enforcement and valid termination for the actual schema/tokenizer/backend |
| Grounded answers/citations | Semantic support plus immutable references, exact span/quotation validation and durable eligibility |
| Vision/audio | Explicit supported modality, preprocessing, quality, permissions and resource validation |
| Actions | Typed intent parsing plus app-owned permission, preview/confirmation, execution and recovery |

A successful load/chat cannot qualify citations, vision or actions. Grammar-valid
JSON and exact spans cannot independently prove semantic support. A failure of a
benchmark, integration or runtime boundary must not be relabeled a model-task
failure without evidence. Unknown states stay unavailable to dependent features.

## Selection, switching and future comparison

Use explicit user selection among qualified compatible configurations; expose
missing assets/capabilities rather than silently choosing another model. Switching
must invalidate active inference, confirm cleanup, preserve durable turns and
reconstruct bounded context under the new tokenizer/template. It must not replay
pending actions or reinterpret attachments as supported modalities automatically.

Before claiming interchangeability, compare the reference and a separately
reviewed second configuration using the companion benchmark plan. No second
artifact is chosen or acquired by this document. Compare a defined shared
capability set, not every conceivable feature, and retain model-specific limits.

Messages, expenses, calendar assistance and meeting transcription remain roadmap
capabilities. Their permissions, safety, modality and task gates are independent
of this linker experiment. Current PDF, Memory and semantic-search gates remain.
P2 is incomplete; semantic search stays disabled; S24 remains deferred here.
