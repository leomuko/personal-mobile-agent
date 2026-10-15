# P2: Memory, Sources and Dark Mode

Proposed implementation plan, 2026-10-15. Dark mode is requested; this document defines the proposed work packages, not completed functionality or approval of unselected dependencies. Preserve the local-first mascot assistant and the existing shared drawer/full-app conversation. Release packaging and the current QA memory policy follow [recorded decisions](DEFERRED_ISSUES.md).

## Outcome and Scope

Make Memory and Sources usable: explicitly import text and text-based PDFs, inspect extracted evidence, search locally, ask source-grounded questions with resolvable citations, and create/correct/forget personal memories. Add consistent System/Light/Dark appearance across the app and both conversation hosts.

No OCR, scanned-PDF interpretation, image understanding, speech transcription, expenses, device actions, external account connections, cloud inference or automatic personal-memory extraction in this phase. OCR/receipt work remains P5; speech remains P3. Images/audio retain their existing storage and preview behavior without implying interpretation. Deferred audio consent/dictation behavior is unchanged.

## Current Foundations

- Existing modules remain `shared`, `ui` and `androidApp`; do not introduce a parallel app architecture or a second conversation database/runtime owner.
- Shared conversation persistence, assistant attempts, bounded chat generation and the internal GPU runtime exist. Source/memory screens currently contain empty-state placeholders.
- `CompanionTheme` always selects a light palette. Conversation bubbles, recording controls, navigation and empty-state icons still contain direct Mint/Forest/white colors; Android launch/window styles are also light-only.
- The verified chat byte bound admits at most approximately 437 bytes for a single ASCII user message at 2048 context/512 output; retained turns reduce that allowance. It is not a practical general document-evidence budget. Do not append retrieved content outside the gate or silently increase context.
- SQLCipher-backed storage and encrypted attachment files exist. The current attachment file interface reads/writes byte arrays; it is not a bounded-streaming document ingestion API.
- No embedding model or PDF text-extraction implementation is selected by this plan. Pin, license-check and verify each dependency before adopting it.

## Entry and Parallel Work

Dark mode, schema work, fake-driven retrieval tests and bounded import development can proceed while the remaining P1 acceptance work is tracked separately. Before another unattended supervised native workload, close host-disconnect handling and test it: interrupted ADB must not leave native QA work running without host supervision. Before enabling native P2 answers, verify prompt budgeting, resource scheduling and the selected embedding runtime on the S24. P1 lifecycle/offline/recovery gaps are not marked passed by P2 development.

Keep `MODEL-001` unchanged throughout this work. Embedding allocation is a separate measured workload; an LLM-only sustained pass does not establish that concurrent embedding/LLM allocation is safe. Do not change normal-build admission defaults or release packaging in this phase.

## P2-01: Dark Mode and Appearance Settings

- Add `ThemeMode.System`, `Light` and `Dark`, defaulting to System, with a three-option single-choice setting under Appearance. Store the non-sensitive preference independently of the encrypted conversation database, behind a shared observable preferences contract and a thin Android private-storage adapter.
- Use one application-scoped preference owner. Shared UI resolves the effective scheme from that preference and system appearance; both conversation hosts, Settings, Memory, Sources, dialogs, attachment previews and capture chrome consume it. Unknown stored values fall back to System; persistence failures are recoverable rather than falsely reported as saved.
- Provide explicit light/dark semantic palettes preserving the approved mint identity and existing geometry. Replace feature-local Mint/Forest/white surfaces, warning colors and selected navigation colors with role-appropriate theme tokens. Keep mascot/image/camera pixels unchanged; do not invert media or the camera feed. Dynamic wallpaper coloring is not part of this slice.
- Android handles day/night launch resources, window backgrounds, status/navigation bar icon contrast and translucent-drawer dimming. Bootstrap appearance without opening personal storage; test forced Dark with a light system setting and vice versa for visible light flashes in app-controlled surfaces. Preserve secure-window flags and drawer translucency.
- Switching appearance updates visible hosts without duplicating generation, clearing drafts, losing scroll/navigation state or restarting recording. Rotation, process restart and system-theme changes retain/resolve the correct preference. No new source/LLM work is triggered by recomposition.
- Acceptance: light/dark screenshots for all existing destinations and key error/disabled/streaming/recording states; main/drawer/capture coverage; 320 dp compact layout, landscape, 200% text and TalkBack checks. Target 4.5:1 normal-text contrast and 3:1 large text/essential non-text controls. Use synthetic screenshots; secure-window tests remain separate from capture-based fixture tests.

## P2-02: Prompt Budget and Citation Contract

This gates grounded generation, not dark mode or source-library CRUD.

- Spike a verified on-device tokenizer for the pinned artifact/template, or a materially tighter proven upper bound. Do not assume the pinned SDK exposes preflight counting. Record artifact/tokenizer/template identities and validate Unicode, control-marker rejection and actual native token counts against the chosen contract.
- Keep 2048 total context and the 512 output reservation initially. Define explicit sub-budgets for the question, retained complete turns, confirmed memories, evidence labels/passages, role/template overhead and response. Truncate only at validated text/token boundaries; never discard the current question silently.
- Produce an immutable `GroundedRequest` snapshot with the allowed source-revision/chunk IDs and spans. Extend the existing conversation pipeline; no second generation manager or duplicate prompt templating. Changes to roles/template handling require renewed budget proof.
- Use minimal, bounded evidence excerpts; expand to the relevant source in the UI rather than stuffing documents into the prompt. A typed insufficient-context state offers a narrower question/source selection. History trimming remains whole-turn based.
- Define structured citation output with an allowlist established by retrieval. Accept only supplied source/revision/span references; validate after generation and before persistence. Invalid or missing support must not be rendered as verified citations. Retrievable IDs alone do not prove that a claim is supported; include manual grounding evaluation.
- Source text is untrusted quoted evidence, not instructions. It cannot enable tools, change memory, request external access or override the user's task. Test adversarial documents and delimiter/control-marker input without bypassing validation.
- Exit: representative question + useful evidence + a follow-up fit the verified budget on S24. If this fails, library/search can be tested, but grounded chat remains disabled pending an explicit revised configuration decision and resource rerun.

## P2-03: Persistence and Bounded Import

- Extend the existing encrypted SQLDelight database with Source, SourceRevision, IngestionJob, SourceChunk, Embedding, Memory/Provenance and message-citation relationships. Preserve existing message IDs, roles, attempts, attachments, draft epoch and search results through migration tests. Persist selected source IDs separately from file attachments.
- Repositories own durable sources and memories; exposed state is immutable Flow, and one-shot operations are main-safe suspend APIs. Keep portable job/revision/deletion rules in common code. Android owns picker/provider access, PDF extraction, crypto/filesystem and native embedding adapters.
- Add bounded streaming to protected document storage; do not route large documents through the current whole-byte-array attachment API. Stage uniquely named encrypted files, validate size/type/content hash, promote immutable versions and reconcile DB/file divergence after interruption. Personal extracted text, chunks, vectors and thumbnails remain protected, with backup exclusions and no plaintext debug exports.
- Proposed first limits: 20 MiB per file, 100 PDF pages, 500,000 extracted characters per source, 2,000 chunks per source and one active ingestion job. Enforce before and during processing, including provider streams with unknown/false size. Confirm/revise these limits during the parser spike before enabling import; low disk, malformed/oversized and password-protected PDFs fail with typed recoverable states.
- Support explicit pasted text, UTF-8 plain text and text-based PDF import through Sources. Reuse system picker integration without changing unsent composer attachments automatically into library items. Offer an explicit Add to Sources action for eligible existing attachments, preserving the original relationship and bytes.
- PDF parser selection must prove extraction, page mapping, supported ABIs/license, bounded memory/time and encrypted-input compatibility. If a parser needs seekable plaintext, solve that explicitly within the protected-storage design before accepting it; do not silently create unencrypted temporary PDFs. Scanned/no-text pages report that OCR is unavailable in P2, not successful analysis.
- Deduplicate by content hash without conflating different ownership/revisions. Persist stages/checkpoints: importing, extracting, chunking, keyword-ready, embedding, ready, paused/cancelled and failed. Cancellation releases file/parser handles and removes uncommitted staging data. Reopen reconciles unfinished jobs and resumes only from verified checkpoints/private copies.
- Start with a foreground-owned, checkpointed queue; define pause/resume on host loss explicitly. Do not claim work survives process death merely because its job row survives. Add OS background scheduling only if requirements justify it, without adding an always-on inference service.

## P2-04: Sources Library and Inspection

- Replace the placeholder with a dense searchable list showing type, title, extraction/index state and recoverable errors. Add import/cancel/retry, rename, source selection and confirmed delete. Separate extracted/keyword-ready from semantic-ready so missing embeddings are not hidden.
- Detail shows original metadata, revision, extracted text, page labels, matched excerpts, linked confirmed memories and Ask About This. Citation navigation opens the exact saved revision/page/span and supports Back to the same chat/scroll position; do not create another chat session.
- Preserve approved shared header/nav behavior and both themes. Empty, processing, unsupported, no-results, failed, deleted and stale-revision states are explicit. Avoid feature-marketing panels or placeholder connection controls.
- Persist normalized text and a defined span coordinate system: UTF-16 offsets into immutable normalized revision text, respecting surrogate boundaries, plus PDF page identity. Persist extraction/normalization version so corrections cannot silently move existing citations.

## P2-05: Local Embeddings and Hybrid Retrieval

- Select the embedding artifact/runtime in a separate spike. Pin exact bytes/hash/revision/license, tokenizer, pooling, dimensions, normalization and query/document formatting. Verify installation, repair, CPU/backend behavior, offline use, incremental peak allocation and cancellation on S24. Do not assume Gemma chat weights are an embedding encoder.
- Preserve the existing runtime owner. Add only the shared resource arbitration needed for chat versus indexing: active capture/user reply outranks requested source processing, which outranks background indexing. Pause/release lower-priority native work before admitting higher-priority work; avoid simultaneously resident GPU sessions unless separately measured and approved.
- Chunk by paragraph/page boundaries using the selected encoder's tokenizer limits. Store embedding identity on every vector; never compare mixed versions/dimensions. Model changes mark old vectors stale and trigger recoverable reindexing.
- Combine encrypted keyword search with exact vector similarity at the reference corpus size, rank/deduplicate and bound candidates before prompt assembly. Do not add an ANN/vector extension until measured need and SQLCipher compatibility are established.
- Keyword search remains usable when embedding assets are absent, with semantic search visibly unavailable. A degraded lexical-only result is not reported as semantic readiness. Installation/indexing does not silently download model assets.
- Proposed performance gate: 100 synthetic sources/2,000 chunks, p95 keyword query <= 1 second and hybrid retrieval <= 2 seconds, excluding LLM generation. Use at least 20 measured queries per cold/warm case on S24; record corpus, tokenizer/model versions, failures and sampled memory. These are targets, not current measurements; review revisions explicitly.

## P2-06: Grounded Chat and Durable Citations

- Extend the existing coordinator/context assembly with selected sources, approved memories and bounded retrieved passages. Start inference only after committed user submission and a stable evidence snapshot. Preserve draft-during-streaming, one active request, Stop, retry and host handoff contracts.
- Save assistant text and validated citation references in the assistant-completion transaction. Do not persist citations from cancelled, partial or failed generations as completed evidence. Assistant-save failure remains distinct from successful inference.
- Missing relevant evidence returns an explicit not-found/insufficient-evidence result instead of an invented supported answer. Clarify when an answer uses only the selected subset or a partially indexed library. Source-grounded mode is an explicit request context; it must not quietly turn unrelated ordinary chat into an assertion about saved sources.
- Follow-up questions retain valid source selection/provenance within budget. Source deletion/revision changes invalidate affected active evidence before cancellation; completion rechecks live revision/deletion state so late callbacks cannot restore deleted sources or persist newly invalid citations.
- Reuse current attachment-only behavior. Ask About This is enabled only once eligible text is available and budgeted; no automatic image/audio interpretation or automatic durable source import.

## P2-07: Explicit Personal Memory

- Replace the placeholder with confirmed memories, optional pending suggestions, search/filter, edit, origin/provenance, correction and forget. Create from explicit user entry or a reviewed selection from a message/source. An assistant statement or quoted speaker preference does not become a user fact automatically.
- Define a stable memory ID, revision/status, timestamps and provenance links. Corrections supersede the old fact and remove old searchable/vector representations before future recall. Explicit confirmation gates any model-proposed write; ambiguous Remember/Forget requests open a review rather than execute guessed changes.
- Retrieve only confirmed, valid memories. Respect topic/relevance and the same bounded context contract; never inject the entire memory store. User edits/deletes invalidate active context and late completions as source changes do.
- Forget deletes the active fact and its derived indexes. Preserve ordinary historical chat quotations unless the user separately chooses to remove linked history; make that distinction explicit. Clearing conversation history does not silently delete the source library or confirmed memories.

## P2-08: Deletion, Recovery and Acceptance

- Transactionally tombstone deleted/replaced sources and invalidate chunks, vectors, dependent generated summaries and source-backed memories; perform retryable encrypted-file cleanup afterward. User-authored independent memories are not removed merely because another source mentions the same fact. Exclude tombstoned content immediately from retrieval, even before file cleanup finishes.
- Reconcile interrupted imports/indexing, metadata/file promotion, storage exhaustion, stale provider permissions, missing assets, key loss and reindex failure. Never silently reset encrypted personal storage. Preserve existing attachment/history semantics and do not claim forensic flash erasure.
- Keep diagnostics synthetic/metadata-only. Verify encryption coverage for FTS, vectors, files, journals, temporary processing and logs; test clear/delete/reopen and backup exclusions. No provider content, receipts, keys, private DB exports or personal screenshots in test reports.
- Common tests: budgeting, retrieval ranking/filtering, memory confirmation/correction, revision invalidation, scheduling, cancellation, clear/delete/completion races and stale callbacks. JVM SQLDelight tests: migrations, indexes, transactional provenance/citations, idempotency and interruption reconciliation. Android tests: bounded provider/PDF/crypto adapters, preferences and lifecycle. Compose tests: theme resolution, empty/error/indexing states, citation navigation and host-state preservation.
- Retrieval/grounding fixture gate: 50 answerable and 20 unanswerable questions, >= 90% supported answers on the answerable set, >= 95% abstention on unanswerable fixtures, and 100% returned citations resolving to allowed live revision/spans. Include contradictions, irrelevant sources, Unicode, prompt-injection text, deleted sources and corrected memories; citation resolution alone does not satisfy factual support.
- S24 acceptance: import -> inspect -> ask -> follow-up -> citation -> remember -> correct -> forget -> reopen. Exercise process death/reboot, offline operation with assets installed and traffic observation, dark/light/system switching, accessibility, resource-pressure failure and foreground/host transitions. Repeat the supervised 20-minute workload with actual retrieval/indexing arbitration; the LLM-only pass does not certify this mixed workload. Unplugged battery comparison needs a suitable supervised connection arrangement; USB percentage endpoints are not an energy benchmark.
- Record passed, failed, blocked and not-run separately. A green P2 fixture run does not close deferred release packaging, audio consent or untested device claims. Keep normal app memory policy and QA experiment scopes explicit in every native report.

## Delivery Order and Exit Demo

1. Record decisions and close host-disconnect supervision before further unattended native tests.
2. Deliver dark mode independently, with no conversation behavior changes.
3. Resolve budget/parser/embedding prerequisites; build schema and bounded ingestion alongside fake-driven tests.
4. Deliver Sources browsing and keyword search, then semantic readiness and shared scheduling.
5. Enable grounded chat/citations only after the budget/native gates pass.
6. Deliver explicit Memory and cross-feature deletion/recovery.
7. Run complete P2 acceptance and update roadmap/evidence without declaring deferred P1 work complete.

Exit demonstration: in airplane mode with verified assets installed, import a synthetic text/PDF, inspect its extracted page, ask and follow up in the mascot/full app, open each citation, save/correct/forget an explicit memory, delete the source and verify it cannot be recalled after reopening. Repeat key paths in System/Light/Dark, including a switch during streaming without lost draft/output or duplicate inference.
