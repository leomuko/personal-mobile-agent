# P1B: Conversation Integration

Accepted direction: 2026-10-13. Updated 2026-10-14: internal implementation, shared header cleanup and verifier hardening are verified. Focused native/process-restoration checks have prior passing evidence, but supervised S24 native acceptance remains yellow (latest wrapper run blocked for load headroom); broader acceptance remains open. See [implementation evidence](../docs/P1B_IMPLEMENTATION_RESULTS.md).

This supplements the [Phase 1 contracts](PHASE_1_PLAN.md) and preserves the mascot, drawer and full-app conversation. P1B builds a request-specific assistant generation pipeline connecting durable conversation data, bounded prompt construction, streamed local output, cancellation, lifecycle loss and retry. It does not connect `ModelManager.testPrompt()` directly to chat.

## Entry and UI Gates

Items 1-4 below are mandatory before real chat generation reaches the UI. Shared implementation and fake-engine testing can proceed while native prerequisites are resolved. Real generation requires the P1A runtime gate to pass, or specific outstanding limitations to be explicitly reviewed and accepted; the passing native smoke alone does not complete that gate.

Keep generation internal at both the coordinator and runtime boundaries, not solely by hiding a UI control. The existing S24 camera/accessibility/notification harness issues and release native-dependency packaging issue remain separately tracked. This plan does not close them or the deferred audio issues.

## 1. Prompt Budget Gate

- Verify token counting or document and verify a defensible upper-bound calculation for the pinned tokenizer/template. A character/message cap alone does not establish the token budget.
- Include role markers, template/special-token overhead, current user input, retained history and reserved output. Start at 2048 total context tokens with 512 reserved output tokens.
- Reject oversized current input with a typed, recoverable error. Do not silently truncate it or overwrite the user's draft.
- Trim only older complete turns, keeping each user/assistant pair together. Exclude unfinished, failed and cancelled attempts from retained assistant context.
- Build a bounded history window and immutable request snapshot; avoid assembling the entire database into a prompt.
- Keep benchmark fixture prompts separate from chat prompts. The runtime owns native template formatting; the application must not apply the same template a second time.
- Verify boundary cases including Unicode, long unbroken text, role overhead, exact-limit input and output reservation before enabling arbitrary input.

## 2. Runtime Conversation Contract

Add a chat-generation API separate from `testPrompt()` through the runtime owner. Its input includes a generation ID, structured role/content messages and explicit backend/configuration. Its output includes request-correlated streamed text events and exactly one terminal outcome: `completed`, `cancelled`, `interrupted`, `rejected` or `failed`.

- `LOADED` expresses readiness only. Persistence requires the correlated completed outcome, not a runtime-status transition or a nonempty buffer.
- Admission distinguishes rejection from an admitted request. Preserve typed busy/resource/runtime failures without overwriting another request's state.
- Cancel remains out-of-band. Terminal cancellation/release must retain the native-completion and safe-handle-lifetime guarantees established in P1A.
- Ignore stale output by generation ID while still processing native completion needed for cleanup.
- Prevent Models-panel tests and benchmarks from acquiring the runtime while a chat request owns it. Runtime ownership, not button disabling, enforces this rule.
- Provide a narrow fakeable boundary for coordinator tests. Vendor message/session types stay in the Android adapter.

## 3. Conversation Persistence Migration

Add explicit message roles, statuses and turn associations. Existing messages migrate to `USER`; `SYSTEM` remains a future capability rather than a newly inferred role for old data. Preserve existing message IDs and ordering, attachment relationships, saved draft, draft epoch and search indexing.

Split writes into clear operations:

- `submitUserMessage(...)` commits the user message and current attachments, clears only that submitted draft and returns its committed user turn ID.
- `startAssistantTurn(...)` records the attempt's pending status linked to that existing turn.
- `completeAssistantTurn(...)` atomically persists completed assistant text, its linkage/status and search entry. It must not attach pending files, clear a newer draft or advance the draft epoch as a user submission would.
- Cancellation/failure/interruption operations record the attempt's terminal status without inventing a completed assistant message.
- Startup reconciliation marks unfinished attempts interrupted without automatically resuming generation.

Do not start inference when user persistence failed or its commit is unconfirmed. Use returned durable IDs and idempotent writes to avoid duplicate submissions or assistant completion after a refresh/retry failure. Assistant-save failure is distinct from successful generation: show a recoverable save error and do not claim that the response was saved.

## 4. State Composition

`ConversationController` owns durable persisted conversation data. A shared coordinator, such as `ConversationAssistant`, owns the active generation and orchestration. `ConversationSession` owns their process-lifetime coordination and composes one public immutable `ConversationState` for both hosts. ViewModels forward user actions and observe that state.

- Database snapshots and draft/attachment updates must not erase active generation state.
- Capture submitted input/history independently of the draft the user may edit during streaming.
- Admit at most one active assistant request. Double-submit and attempts from both hosts follow the same admission contract.
- Clear invalidates the active generation before requesting cancellation. Completion writes must verify the relevant conversation epoch/generation ownership, so a late callback cannot recreate cleared messages.
- Keep conversation errors separate from storage errors and localized display strings in presentation resources.
- UI observes the composed session state; it does not start a second engine or independently merge the Models-panel state into conversation history.

## 5. Streaming, Cancel and Retry

For P1B, streamed assistant text is transient active state. Persist assistant text only after successful native completion and a successful assistant-completion transaction. Partial text is not retained across process death or reopen; adopting partial persistence later requires an explicit policy change.

Durable turn/attempt status still records pending, completed, cancelled, failed and interrupted outcomes. On cancellation or interruption, no partial buffer becomes a completed assistant response. The terminal status remains visible so an unanswered user turn does not disappear silently.

Retry reuses the committed user turn, creates a distinct generation/attempt identity, and does not duplicate the user message or its attachments. Specify retryable errors and prevent retries over an uncertain native handle until recovery is confirmed. Stop targets the active request; no automatic sending or automatic resumption on foregrounding.

## 6. Lifecycle and UI Policy

- Preserve active generation across rotation and drawer/full-app handoff through the shared session and existing host-presence coordinator.
- After confirmed loss of all assistant hosts, cancel generation, reconcile its interrupted status and release the runtime safely. Do not treat the transient handoff gap as background loss.
- Apply the existing idle-unload policy once no active request holds the runtime. Cancel during loading must prevent generation from starting afterward.
- Render role-aware user/assistant bubbles, transient streaming text, visible Stop, retry/recovery controls and actual model availability in both hosts.
- Allow editing the next draft during streaming; submitting it follows the one-active-request admission rule.
- Allow attachment-only and mixed submissions and preserve their existing storage/preview behavior. P1B sends text only to inference and clearly indicates that attachment contents are not interpreted. Attachment-only submission must not infer an instruction from a filename or pretend to analyze the file; it can remain a saved user turn without starting text generation.

## Acceptance and Test Placement

Use common tests with controlled fake generation events for orchestration, budgeting, admission and state composition. Use JVM SQLDelight tests for migrations, transactional behavior, search and idempotency. Use focused Android tests for real lifecycle/persistence integration, and designated S24 tests for native generation. Synchronize on observable outcomes rather than delays that conceal race conditions.

Required cases:

- Submit -> committed user turn -> active streaming -> completed assistant text persisted once.
- User-save failure prevents inference; assistant-save failure remains visible and recoverable.
- Draft edits and attachment changes during streaming survive completion without being consumed by the assistant write.
- Double-submit and simultaneous host actions do not create duplicate user messages or generations.
- Clear versus completion, late callbacks and generation replacement cannot resurrect history.
- Cancel, interruption and resource failure do not create a fake completed assistant message.
- Unicode/context overflow and template/output reservations are enforced; trimming removes only older complete turns.
- Process death and reopen preserve committed messages/drafts/attachments, reconcile pending attempts and discard transient partial text.
- Retry reuses the existing user turn without duplicating messages or attachments.
- Migration preserves IDs, ordering, attachments, drafts/epoch and search; historical rows become `USER`.
- Models-panel interference, rotation and drawer/full-app handoff respect the same runtime owner.
- Focused S24: real text prompt, stream, Stop, reload, host handoff and reopen; verify final persisted roles/status and no stored-conversation corruption after blocked/failed model states.

P1B completes only when items 1-4 are implemented and verified, local/migration checks pass, and focused S24 conversation/cancellation/recovery checks pass. Completion does not imply retrieval, OCR, speech transcription, attachment reasoning, actions, Gmail, E4B default support or release acceptance.
