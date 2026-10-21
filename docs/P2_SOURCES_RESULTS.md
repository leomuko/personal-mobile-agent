# P2 Text Sources Pilot

Date: 2026-10-21. Status: independent implementation slice with focused evidence, not P2 acceptance.

## Implemented Scope

- Sources replaces the empty state in the real full-app navigation. The shared header, theme and existing conversation session remain in place. Sources work does not create a second chat or runtime owner.
- Explicit pasted text and system-picked UTF-8 plain text are admitted at up to 64 KiB UTF-8 per source. Malformed UTF-8/UTF-16, empty/NUL-containing content, oversized input and invalid titles are rejected. An initial UTF-8 BOM is removed; other text is preserved. Provider length metadata is not trusted.
- Text and keyword indexes live in the existing SQLCipher database. This path creates no plaintext staging files or protected-file originals. It is not the PDF/streaming-document ingestion path. Picker access is transient, not a retained URI permission or an automatic conversion of composer attachments.
- Schema v5 adds Source, a single immutable text revision per source, inspection chunks, FTS and a separate context eligibility record. UTF-16 spans refer to the saved revision text and never split a surrogate pair. Import publishes text/chunks/index together in one transaction; interrupted/uncommitted imports roll back rather than leaving a resumable file job.
- Rename, inspect, confirmed delete, title/body keyword search and cross-host refresh signals are implemented. Lists/search use keyset pages of at most 50 summaries, without loading full source bodies. Detail reads at most eight 2,048-character chunks per page, below the 64 KiB decoded-text ceiling. Search is literal AND-term matching, not user-supplied FTS syntax, ranked grounding or semantic retrieval.
- The UI distinguishes keyword-ready text from semantic readiness. Deletion confirmation explicitly explains the conservative chat-context reset. Missing, invalid, quota and storage failures are typed; failed edits keep their text for retry. Source selection for chat and Ask About This remain unimplemented, so no source text is silently fed into Gemma.

## Storage Admission And Limits

The pilot enforces the shared 500-source, 1,000-revision and 20,000-chunk ceilings; the one-revision-per-source and 64 KiB intake restrictions are stricter than the eventual P2 capacities. Revision replacement, retained/cited revisions, content-hash deduplication and asynchronous jobs/reservations are not implemented here.

One serial session lane owns source and conversation DB work. Import/rename measure the entire personal DB plus WAL/SHM/journal and the protected `sources-v1` directory. They require 64 MiB transaction headroom inside the 512 MiB library ceiling and retain a separate 512 MiB free-device reserve. Footprint checks repeat during writes, and a quota failure rolls back the source transaction without deleting prior sources. No conversation is evicted. Physical DB pages, journals or pending protected files remain charged while present, including after rollback or logical deletion.

The S24 fixture checks three maximum-size pilot payload shapes against that reserve. This is focused evidence, not a proof of every full-library FTS merge/journal peak. Maximum-capacity and commit-time-growth qualification remain open; do not advertise the eventual full capacity as acceptance-tested. Larger/staged imports still require the approved persisted reservation, job, cleanup and crash-reconciliation design. Existing conversation growth is counted at admission but is not globally capped by this pilot.

The picker uses an interruptible I/O worker, provider CancellationSignal, explicit descriptor close and a ten-second timeout request. A non-cooperating provider may delay native/Binder cleanup; this does not establish a hard cancellation deadline for every OEM/provider. Real provider interruption/process-death cases remain unverified. Input buffers are bounded and cleared, including cancellation at dispatcher handoff.

## Context Deletion Barrier

Deletion revokes the active response ID before the DB mutation. The transaction advances a context epoch and records the latest existing message ID as a fence, removes source/index rows, then the session cancels/joins the active request. Submissions stay blocked through that cleanup. Cancellation exceptions/failures conservatively quarantine future chat until restart; existing runtime quarantine rules are unchanged.

The fence is separate from the attachment/draft epoch. Historical messages, conversation search results, drafts, attachments and independent sources remain visible. Ordinary prompt construction filters out pre-fence user/assistant turns; old retries are rejected and their retry buttons are hidden. Attempt admission checks the captured context epoch, and completion checks its persisted epoch and user-message fence before saving. Explicit newly submitted text may reintroduce information. Memory, history retrieval and summaries do not exist yet and must apply this same eligibility record when implemented.

No source replacement, memory forgetting or grounded-generation integration is claimed. Those later mutations must use the same barrier rather than bypass it.

## Initial Pilot Verification

Local checks passed:

- `:shared:jvmTest`: 96 tests, including strict decoding/spans, SQL paging/search, rollback, migration, context fencing, stale output, retry, cleanup quarantine and buffer ownership.
- `:shared:testAndroidHostTest`: 80 tests; includes shared common tests, not 80 additional unique scenarios.
- `:ui:jvmTest`: four tests, including three Sources presentation-state regressions for reversed read completion, retry/double-save, change signals and persistent import errors.
- `:androidApp:testDebugUnitTest` and `:androidApp:testSecureQaUnitTest`: 15 tests each. Two added stream tests cover byte-sentinel limits, closure, zero-progress and read failure.
- Debug/QA/unsigned-release builds and QA instrumentation compilation passed. Debug lint: zero errors, 27 warnings. This is compilation evidence, not release-distribution approval. Existing deprecated Compose test rules remain elsewhere; new Sources UI tests use v2.
- All 27 Node regressions passed. `git diff --check` passed.

S24 Ultra focused instrumentation: five tests passed using only synthetic data:

1. SQLCipher migration from schema four preserves message IDs, search, attempts, draft epoch, draft and attachments.
2. Three maximum-size text-payload profiles remain below the per-import physical reserve.
3. Encrypted sources reopen/search/rename/delete, with a durable context fence and preserved unsent draft after reopening.
4. Compact 320 dp, 1.5 font-scale source inspection keeps title/actions separate in Light and Dark.
5. Paste, keyword-ready display, inspection, rename, cancelled deletion and confirmed deletion.

Raw local logs: `build/p2-sources-instrumentation.txt` (initial three-test run) and `build/p2-sources-final-instrumentation.txt` (five-test run). Random test databases/keys are cleaned individually; no production database, source, model or user attachment is reset or exported. Only Assistant (QA) and its test APK were updated. No new native Gemma or encoder workload ran.

An initial host UI test run failed in its teardown because the common `ViewModelStore.put` call did not match the resolved JVM method. Teardown now cancels the test ViewModel scope; all four UI host tests subsequently passed. That failed iteration is not represented as a pass.

A stronger queued-cancellation regression initially failed: the session's independently queued task could start before the cancelled caller reached its cleanup. Storage admission now checks the captured caller context before starting and again after session startup, while joining already-started work before the caller can clear its buffer. Cancelled queued imports do not publish a source.

## Presentation And Intake Review Fixes

The subsequent review identified two defects that were absent from the initial coverage. Both original repros failed in host tests before these fixes and passed afterward:

- Detail content is now tagged with source and revision identity. Selecting/closing a source or changing the query clears the previous detail immediately. A failed read clears detail, and request sequence plus identity checks reject late callbacks. The public detail-page projection returns no body or next-page cursor for a mismatched identity.
- A shared ViewModel write mutex now spans provider loading, persistence and buffer cleanup. The Android picker invokes the reserved load callback instead of reading first and asking for admission afterward. A busy intake never invokes its provider callback and returns an explicit `BUSY` outcome. The UI shows a resource-backed message asking the user to select the file again after the active operation finishes. That import-specific error survives unrelated refreshes and the original save's completion; it clears on the next admitted intake, not on an unrelated read.
- Import outcomes distinguish saved, busy and failed. Accepted buffers are cleared before releasing ownership; a busy byte-array submission leaves the caller's bytes untouched. Cancellation propagates and releases the reservation, so later saves/imports can proceed. No schema, quota, context-fence, model-policy or audio-consent behavior was changed by these fixes.

Fresh follow-up verification:

- Shared JVM: 96 passed; Android-host: 80 passed; UI JVM: 11 passed, including ten Sources state tests. Debug and QA app unit suites: 15 passed each. These are per-target counts, not unique scenarios to sum together.
- Debug/QA builds, QA instrumentation compilation and debug lint passed: zero errors, 27 warnings. Release compilation and the 27 Node regressions above are initial-pilot evidence and were not rerun for this follow-up.
- Seven focused synthetic S24 checks passed: the previous five plus source A-to-B loading/failure isolation and a selected file rejected during another save with a visible, recoverable busy outcome and successful reselection.
- The first device attempt passed the three database tests but failed all four UI tests with unavailable Compose hierarchies while the device keyguard reported `showing=true`. The user unlocked the phone; the complete seven-test rerun then passed. This failed attempt is retained, not silently replaced or counted as a pass.
- Logs: `build/p2-sources-review-fixes-instrumentation.txt` (locked attempt) and `build/p2-sources-review-fixes-unlocked-instrumentation.txt` (seven-test pass).

The controlled provider callbacks are synthetic: these checks do not certify every real document-provider/One UI picker interruption. No Gemma/encoder generation, sustained workload, full-capacity qualification or consolidated P2 campaign ran. Only Assistant (QA) and its test APK were updated; existing personal data/model assets were preserved. No commits or pushes were made.

## Remaining Gates

- Exact encoder counting/normalization/special-token/input-limit proof, or a replacement verified encoder. Arbitrary semantic query/chunk embedding remains disabled.
- Isolated bounded PDF parsing, API 31-34 compatibility, larger originals, replacement/cited revision retention, deduplication, jobs and durable quota reservations.
- Maximum-library capacity and keyword timing qualification, real provider picker/cancellation/restart matrix, cross-process application-death UI restoration and broader accessibility/device QA.
- Explicit Memory CRUD/provenance/forgetting; grounded requests/citations; production foreground/background/Models-panel arbitration; complete retrieval deadline.
- Representative native Gemma budget-v2 prompts and the single consolidated P2 acceptance campaign, including sustained mixed workloads. No extra standalone Gemma smoke was run.

Normal memory thresholds, the explicitly selected QA Android-pressure profile, release packaging and deferred audio behavior are unchanged. No commit or push is authorized by this evidence.
