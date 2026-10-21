# Verified Encoder Admission and Foreground Handoff

Date: 2026-10-21. Status: implemented prerequisite with focused host/S24 evidence;
not semantic-search enablement or P2 acceptance.

## Counting and Receipt

Keep USE-QA v1, MediaPipe tasks-text 1.1.1 and the fixed 192-token graph limit.
The artifact is 6,120,274 bytes with SHA-256
`89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749`.
Its optimized configuration is 384,344 bytes at offset 5,584,856, SHA-256
`ab32418a9a7f2c0c95e3db6233f2fbd8243e2929a84cd5feec9c1b1ca615c420`.
Whole-file verification precedes use of that offset. The host auditor verifies
all three tokenizer branches, BOS/EOS, no reversal/sampling, direct inputs and
their 192-token dense limits. This is not Gemma's unrelated byte bound.

The public TextEmbedder path uses response text and empty query/context inputs.
Both empty branches still count two tokens. We support only this formatting
identity; query-specific USE formatting is not enabled. The common Kotlin port
reproduces the pinned optimized normalizer/trie/lattice, including float32 scores.
The oracle compiles the unmodified pinned upstream C++ implementation and compares
full token IDs, not just lengths or plausible vectors.

The SDK's first embedding head produces 100 finite values with L2 normalization
enabled; the handoff validates dimension and norm, then discards the vector.
No alternate pooling or query-specific head is selected. This pins the measured
candidate behavior, not its suitability for semantic retrieval.

Do not guess the Java/JNI string codec. A supplementary-character mismatch on
the actual S24 invalidated our modified-UTF-8 assumption. QA now creates the
same SDK string packet, reads its exact bytes and immediately disposes packet
and graph. The common counter consumes those bytes. This maps SDK native code
but creates no encoder model/session in the parent. The isolated worker repeats
counting on its own exact SDK packet bytes before initializing TextEmbedder.
New runtimes/devices require transport/reference revalidation.

Reject malformed UTF-16 and more than 16,384 code units before transport; cap
transport at 16,384 bytes, normalized output at 65,536 bytes and trie work at
1,000,000 steps, with cooperative cancellation. Overflow is rejected, never
silently clipped. Internally issued immutable receipts bind input, formatting,
artifact, tokenizer, runtime, transport digest and branch counts. The parent
checks issuer object identity; the worker checks the digest/count independently.

## One Scheduler and Opaque Authority

`ModelManager.prepareChat` is an internal prerequisite entry point, separate
from ordinary text chat. It uses the existing operation mutex and one opaque
lease through resident Gemma release, counting, encoding, encoder cleanup and
Gemma generation. Printable IDs correlate diagnostics; matching IDs cannot
acquire or cancel another lease. The encoder adapter is a platform lifetime
adapter, not another scheduler. No background indexer exists yet.

Panel load, verify, install, release, benchmark and Cancel return BUSY while the
request owns the manager. Internal cleanup runs under existing ownership without
recursively awaiting public release. Host-loss/shutdown has a distinct privileged
path; the normal panel does not have that authority. Idle release remains subject
to the same owner check. No production DI or conversation/Sources UI enables
embedding in this slice.

Deferred cancellation snapshots the generation Job, asset Job and foreground
owner at the checked call site and revalidates their object identities when the
signal executes. An idle or superseded Cancel cannot retarget a newer owner.
Admission stays blocked until the native cancellation signal returns, even if
the cancelled generation has already finished and released its operation lock.

Stop signals the active encoder immediately and separately cancels the owner
coroutine. The first invalidation cause is retained. Results become invalid on
Stop, deadline, context epoch change or confirmed loss of assistant hosts.
Rotation/drawer handoff retains the existing host-coordination policy. A queued
request captures manager lifetime too, and cannot revive after host loss.
Collection after shutdown returns an interrupted terminal outcome.

## Deadline and Cleanup

One monotonic five-second preparation deadline starts at pipeline entry and
includes scheduling delay, resident model reclamation, admission/counting, worker
startup, encoding and confirmed cleanup. Recheck after cleanup and immediately
before Gemma admission. Gemma verification/loading/generation is outside this
preparation window. Full retrieval will also have to fit inside the same window;
these measurements contain no retrieval work and do not prove that contract.

Cancellation/timeout invalidates results immediately. Reservation stays held
while cleanup is PENDING. Binder death plus adapter cleanup confirms the isolated
worker has exited before Gemma engine load; normal close callbacks alone do not.
Uncertain cleanup quarantines subsequent native admission and requires restart.
Cleanup has its own bounded wait; a five-second request deadline does not mean
resources are instantly released. Each collected request emits one terminal
outcome, including a distinct TIMED_OUT result. No text-only fallback hides failure.

Model/session lifetimes do not overlap in this path. SDK shared libraries can
remain mapped and OS memory recovery is not instantaneous. Neither this
exclusion nor sampled PSS proves generic concurrent-residency safety or leak absence.

## Unchanged Limits and Remaining Gates

Gemma remains GPU-first with 2048 context and 512 reserved output tokens. The
prepared path rejects a different output reservation. Its representative native
prompts use budget v2 and record SDK prefill counts, without adding an evidence
allowance. Normal memory policy and deferred release/audio decisions are unchanged.

Versioned vectors, bounded hybrid retrieval, real context-fence integration with
retrieved evidence, cited answers, indexing arbitration, isolated PDF ingestion,
capacity qualification and consolidated P2 acceptance remain separate work.
See [results and reproduction](../P2_ENCODER_HANDOFF_RESULTS.md).
