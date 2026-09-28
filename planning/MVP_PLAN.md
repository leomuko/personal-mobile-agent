# Personal AI Companion: Product and Implementation Direction

Revised 24 September 2026 following the user's clarification. This supersedes the earlier note-and-reminder MVP. The filename is retained for continuity; this document now describes the intended product and its implementation sequence.

Implementation update, 26 September 2026: the user approved the four screenshot boards and named a Galaxy S24 Ultra as the initial test device. [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) is now the authoritative execution plan, including voice, scanning, local memory, polishing, and actions in the complete Android release. Its milestones supersede the preliminary sequence below. [Approved references](design/approved/README.md).

## Product Definition

A personal AI assistant that lives on the user's device as a floating mascot. The user can ask questions, think through decisions, share information, and keep an ongoing conversation. The assistant runs its model locally and uses user-authorized information to provide more relevant help over time.

The user can interact through a lightweight mascot drawer or open the full application. Both surfaces belong to the same assistant and continue the same conversation. Notes, reminders, and other actions are capabilities within that experience.

The user's Muse AI comparison describes the desired conversational assistant experience. It does not establish any dependency on, or claim about, that product's implementation.

## Core Experience

1. Tap the mascot wherever the platform allows its presence.
2. Continue the existing conversation by typing or, when local voice input is implemented, speaking.
3. Ask follow-up questions naturally; the assistant retains the topic, references, and relevant earlier context.
4. Give the assistant access to selected information when a better answer needs it.
5. Receive an answer grounded in the conversation and available sources, with source references when personal or external information was used.
6. Collapse the drawer and return later without losing the conversation.
7. Open the full app for more space, older conversations, source inspection, and memory or connection management.

The assistant should answer ordinary questions and discuss ideas without requiring a tool invocation. It should also recognize when the answer needs information it does not have, explain that gap, and offer an appropriate way to obtain it. It must not pretend to have read an unconnected account.

## Product Commitments

| Area | Intended behavior |
| --- | --- |
| Mascot | Primary everyday entry point, with a recognizable character and subtle states |
| Conversation | Ongoing local history, natural follow-ups, streamed responses, interruption and resumption |
| Personal context | User preferences, relevant previous discussions, and authorized source information |
| Information access | User-shared material, selected local sources, web information, and eventually Gmail and other integrations |
| Local ownership | Conversation history, imported content, summaries, and retrieval indexes stored on the device |
| Full app | A larger view of the same assistant, with conversations, memory, sources, and settings |
| Actions | Supported device and service actions as the assistant develops; clear permission and execution boundaries |
| Platform foundation | Kotlin Multiplatform shared logic, native platform integrations; Android is the first implementation target from the original review |
| Input | Text, local voice conversation, and receipt/document photo intake in the approved Android release |

Mascot interaction, conversation continuity, and usable personal context define the first companion experience. Delivery milestones sequence implementation; they do not replace this product with a capture utility.

## Conversation Continuity and Memory

Continuous conversation is a product behavior, not a requirement to keep the model running or every previous message inside its context window.

Maintain four separate forms of local state:

| State | Contents | Use |
| --- | --- | --- |
| Conversation history | Original messages, timestamps, attachments/references, tool results, interrupted response state | Restore the actual conversation across drawer dismissal, app switching, and restart |
| Working context | Recent turns, a rolling summary of earlier turns, active task, relevant retrieved passages | Fit the current request within the selected model's context budget |
| Personal memory | Explicit preferences, user-confirmed facts, ongoing projects, and provenance | Personalize later discussions without requiring the user to repeat everything |
| Source library | Selected documents, shared content, imported email, source metadata, and local search indexes | Retrieve evidence for a particular question |

Persist history incrementally and rebuild working context when the model loads again. Keep original messages as the authoritative record; summaries are derived and may omit or distort details. Retrieve the original passage when precision matters.

The default experience resumes the current conversation. Provide explicit new-conversation and history controls in the full app. A new conversation resets the working discussion while approved personal memory remains available; a temporary conversation can opt out of saving and personal memory.

An explicit "Remember that..." request can create an editable memory entry. Candidate preferences inferred from conversation are suggestions until confirmed, especially when sensitive or uncertain. Do not turn quoted documents, another person's statements, or assistant guesses into facts about the user.

Every durable memory carries its origin and update time. The user can inspect, correct, or forget it. Corrections take precedence over older information. Deleting content invalidates related summaries, search entries, embeddings, and derived memory unless the user explicitly retained an independent fact. Existing saved assistant replies can still quote a source; deletion controls must state what they remove and offer removal of those conversation records too.

## Giving the Assistant Information

Support several explicit routes into the local source library:

- Share text, links, or supported files from another app into the assistant.
- Select local documents or other supported data through platform-provided access.
- Connect a supported service such as Gmail through its official authorization flow.
- Enable web lookup when a question needs external or current information.

Each source record includes its origin, account if relevant, provider/document ID, local import time, source update time when available, and current access status. Retrieval returns passages with source references. The assistant distinguishes a fresh lookup from a cached item and does not present an old local email snapshot as a live inbox view.

Use local full-text retrieval as a baseline and add local embeddings for semantic retrieval. Evaluate answer quality on realistic personal-context questions before choosing the indexing implementation. The architecture includes retrieval from the start; indexing the whole device is not required to make it useful.

Opening the drawer does not grant access to the app behind it. Information arrives through sharing, a supported API, or another user-authorized source. Display source references near the answer without making every conversation turn a permission form.

## Gmail and Other Integrations

Gmail is an explicit future integration, with the connector and local source contracts designed early. Initial Gmail capability should retrieve and synchronize authorized information for local reasoning. Sending, modifying, or deleting mail is a separate capability with separate authorization.

An example future conversation:

> User: What did Sam say about the trip?
> Assistant: Sam suggested leaving on Friday afternoon. [email reference]
> User: Would that work with the plans we discussed?
> Assistant: Compares the email with relevant saved conversation context, identifies conflicts, and explains missing information.
> User: Help me draft a reply.
> Assistant: Drafts locally; sending is available only after that capability is implemented and the user approves it.

The connector owns OAuth, incremental sync, pagination, retries, account boundaries, and revoked/expired access. Normalize imported content into a provider-independent local source format. Store credentials using platform-protected storage, outside model prompts and retrieval indexes.

Let the user select the account, import range, and supported source filters; show local storage use and last successful sync. Those filters limit what the app imports and uses, but may not narrow the permission granted by the provider. Use explicit refresh plus best-effort background synchronization; do not require a remote push infrastructure for the first connector.

Gmail read access can require restricted OAuth scopes and verification even when reasoning occurs on the phone. Confirm the actual distribution requirements during the connector milestone. Gmail supports full and partial synchronization; expired history requires a fresh synchronization of the selected dataset. [Gmail scopes](https://developers.google.com/workspace/gmail/api/auth/scopes), [synchronization guidance](https://developers.google.com/workspace/gmail/api/guides/sync).

Disconnect stops access and further synchronization. Offer a clear choice to retain permitted local copies or remove them. Removing an account's data must also invalidate its retrieval entries and derived context. Local cleanup and provider-side revocation are separate operations and should report their own outcomes.

Add other services through the same contracts as they become useful. Do not assume that connecting one app gives access to the rest of the phone.

## Local Processing and Network Boundaries

The model, conversation state, personal memory, retrieval, and reasoning operate on the device. Network access serves authorized source retrieval, service actions, web lookup, and model installation or updates. There is no automatic cloud-model fallback.

For example, Gmail synchronization contacts Google, then downloaded email can be searched and summarized locally. Offline mode can answer from available local material and explicitly reports when fresh information is unavailable.

A web query can reveal part of a user's question. For queries using personal context, show what will be sent and let the user revise or approve it; do not assume that automatic redaction removes all private information. Do not upload an entire conversation to a search provider.

Protect stored personal information and credentials with platform-backed mechanisms and evaluate encrypted database/file storage during the foundation milestone. Exclude personal content from automatic cloud backup by default, provide deletion and user-controlled export, and keep raw user content out of production logs. Define what happens if encryption keys become unavailable before shipping.

Imported email, documents, and web pages are evidence, not instructions. They cannot grant permissions, alter the assistant's rules, or authorize actions. The application enforces allowed tools independently of model output.

## Interface Direction

### Floating Mascot

Keep the original character-led direction. The user approved the mint robot with coral antenna in the 26 September screenshot references. Build consistent mascot assets with restrained motion and recognizable idle, listening, thinking, and responding states.

The mascot can be moved, collapsed, hidden, and resumed. Its presence does not depend on active model inference. Idle presence should have a low battery cost.

### Conversation Drawer

The drawer is a small ongoing conversation surface: recent messages, a simple composer, send/stop controls, a microphone when local voice is ready, attachment/share intake, and an expand-to-app icon. Source references or an action approval appear only when relevant to the current exchange.

Follow-up questions remain in the same thread. Collapsing the drawer retains both messages and the draft. Define a consistent interruption policy: cancellation preserves completed output and marks partial output as interrupted; opening the full app transfers the active conversation and generation session without starting a duplicate response.

Ordinary answers should remain readable conversation. Action previews can use compact structured controls when dates, recipients, or effects need review.

### Full Application

Open directly into the same active conversation. Provide secondary navigation to conversation history, remembered information, connected sources, and settings. Source details show what is stored locally, last sync, and access controls. Memory details support correction and deletion.

The full application is a standalone launcher entry into the same assistant and data store, not a separate assistant or mandatory setup dashboard for every interaction.

### Voice and Other Intake

Local push-to-talk speech input and optional local spoken replies support the original conversational vision. Plan microphone permission, interruption, transcription correction, and audio lifecycle as a dedicated milestone. Continuous conversation does not require an always-on microphone. Receipt/document photo intake is included in the approved release, with the capture and OCR approach detailed in the implementation plan.

## Technical Foundation

Restore the original KMP direction while keeping platform capabilities explicit.

| Layer | Responsibility |
| --- | --- |
| commonMain | Conversation orchestration, context construction, personal memory rules, retrieval contracts, source metadata, typed tool contracts, shared state |
| Android host | Mascot overlay, Compose drawer/full app, service lifecycle, permissions, secure storage adapters, local inference bindings |
| Future iOS host | Supported native entry surfaces and inference adapters; no assumption of Android-equivalent overlays |
| Local inference | One selected engine integration after evaluating llama.cpp and/or LiteRT-LM against actual conversation workloads |
| Local persistence | SQLDelight/SQLite candidate for history, memory, source metadata, and action records; indexed content and embeddings stored locally |
| Source connectors | Authorized provider access and sync, normalized into the local source library |
| Tool execution | Validation, capability checks, approvals where needed, execution, and observed results |

The model input combines the current request, relevant recent turns, a bounded conversation summary, approved memories, retrieved evidence, and currently available tools. Context assembly must reserve room for output and avoid crowding out the current question.

ConversationController owns the shared conversation and generation lifecycle for both UI surfaces. LocalLlmEngine exposes load, stream, cancel, and release operations. ContextAssembler prepares a bounded prompt. MemoryRepository and SourceRepository own durable records and provenance. Connector adapters report availability, synchronization state, and structured errors.

Tool contracts use typed requests and results. Execution permissions come from application state, not retrieved text or model confidence. Repeated confirmations and retries must not duplicate external actions. Report success from actual execution results.

Choose a model using conversational coherence, follow-up resolution, retrieval grounding, and tool correctness alongside latency, memory, and thermal measurements. A 1B model that parses commands but cannot sustain useful discussion does not satisfy this product. Parameter count and token-throughput targets remain hypotheses until physical-device testing.

## Preliminary Implementation Sequence (Superseded)

| Milestone | Demonstrable outcome |
| --- | --- |
| 1. Mascot and local conversation | Tap the mascot, ask a question, receive a local streamed answer, ask follow-ups, and expand into the same conversation in the full app |
| 2. Durable context and personal memory | Reopen after restart, continue the discussion, remember an explicit preference, inspect/correct it, and use relevant previous context |
| 3. Local information access | Share supported content, index it locally, ask grounded follow-ups with source references, and use cached material offline |
| 4. Voice and external information | Speak through local transcription; perform permitted external lookups with clear source and network state |
| 5. Gmail integration | Authorize an account, synchronize a selected dataset locally, ask contextual questions, refresh, disconnect, and remove imported information |
| 6. Broader assistance | Add useful source connectors and device/service actions, then expand platform coverage |

Start the overlay lifecycle, model evaluation, memory/storage design, and connector authorization investigation early because they shape these milestones. Re-estimate delivery after the first working conversational prototype. The previous 6-8 week utility estimate does not apply to this restored direction.

Milestones 1-3 establish the first coherent companion experience. They must be judged together: a command parser with a mascot is not sufficient evidence of the intended product.

## Validation

- Follow-up references remain correct over realistic multi-turn conversations, including topic changes and corrections.
- Drawer collapse, full-app expansion, process death, and model unloading preserve durable history and do not duplicate responses.
- Context compression retains essential facts; factual claims can be traced back to original messages or sources.
- The assistant uses an explicit preference appropriately and respects correction and forgetting.
- Source-based answers identify the supporting material; unavailable or stale information is acknowledged.
- Offline operation covers conversation and locally cached information after model setup; online operations are attributable to authorized features.
- Provider content cannot trigger unauthorized tools or become an unconfirmed user preference.
- Disconnect, delete, and account switching preserve access boundaries and invalidate affected local context.
- Physical-device tests measure first-response latency, sustained generation, peak memory, idle mascot cost, and heat with realistic history and retrieval loads.
- Testers can keep talking naturally and move between the mascot and full app without restarting the interaction.

Android overlay/service restrictions still need a real-device prototype. Begin the overlay through a visible user action and handle denied access or unavailable surfaces. If a platform constraint threatens the mascot experience, report it as a product decision instead of silently replacing the mascot with a different product. [Android service-start rules](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

## Status of Existing Mockups

The files in design/concepts are retained as historical explorations. Their note and reminder flows represent the rejected utility-focused scope and are not the current screen specification.

The [approved reference set](design/approved/README.md) establishes the mascot, conversation, scanner, memory, polishing, and timer visual direction for implementation. The [generation archive](design/companion-concepts/README.md) retains the earlier prompts and output files. Approval establishes the design direction; the screenshots do not demonstrate implemented capabilities. Gmail screens, when explored, must be marked as future connected-source concepts until implemented.
