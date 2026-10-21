# P2 Text-Source Semantic Indexing and Hybrid Search

Accepted execution contract, 2026-10-21. Still P2; no other manager/database.
Implement gate-first, with focused prerequisite tests and one slice campaign.
Failure blocks dependent enablement, not justification to weaken limits.

## Blocking Qualification

Freeze synthetic passages, relevance judgments, calibration/evaluation split,
candidate identity and thresholds before measuring embeddings. Recall@5 >= 0.90,
nDCG@10 >= 0.80, and unanswerable-query false-match rate <= 0.05 are proposed
acceptance gates, not achieved results. Calibrate the relevance cutoff only on
calibration queries; report each language separately. Contradictions can be
relevant evidence without being true. Changing head/format/runtime requires new
identity/counting/native evidence and independent held-out qualification, not
tuning against a failed evaluation set.

Initial candidate: existing head 0, 100 dimensions, SDK L2 normalization, response
text for both query/document input, empty query/context branches, 192-token gate.
The fixture declares English as the initial required language; Swahili/Japanese
are diagnostic cross-language tests, not supported-language claims. Do not change
this scope after seeing results. A fixture lock binds all judgments and policies.
Synthetic fixtures/embedding exports are QA-only and never read user Sources.

## Publication and Recovery

Stage immutable generations with expected passage manifests. Publish in one
short transaction only after every vector validates and live revision/deletion,
full encoder identity and quota checks pass. Retries are idempotent. Incomplete
staging stays excluded from search and charged until confirmed cleanup. Migrations
preserve existing sources, messages, drafts, attachments, ordering and search.

Encoder installation/repair requires explicit local import and verified immutable
private backup-excluded assets. Separate asset/runtime/semantic readiness. Never
replace leased assets; reconcile interrupted jobs, staging and reservations before
new work. Uncertain native cleanup retains quarantine. No silent downloads or
release-packaging changes.

## Runtime and Search

Extend ModelManager with encoder-only operations; never load Gemma to search.
At most one passage encode is outstanding. Foreground search/reply preempts bounded
indexing units immediately, with confirmed cleanup before admission. Recording
suppresses automatic resumption until capture ends and host/resources requalify.
Carry real revision/context epochs through admission, persistence and publication.

Results expose requested/executed mode, immutable revision/index snapshot,
semantic coverage and completed/cancelled/timed-out/unavailable/failed status.
Keyword-only is explicit; incomplete library coverage is visible. A timeout is
not a completed empty result. Passage navigation uses exact revision/UTF-16 spans.

Keep batch limits of 128 vectors/2 MiB, top-50 vector heap, 50 keyword candidates,
100 merged candidates, 20 reranking candidates, and at most eight fetched passages/
64 KiB total text. Explicitly bound open cursors. One monotonic five-second deadline
starts at foreground submission, including reclamation/preemption, counting,
loading, encoding, cleanup and retrieval. Recheck eligibility before publishing;
no DB transaction spans native inference. Never advertise an incomplete scan as
exact library-wide results.

## Acceptance and Exclusions

Current-source S24 cancellation/cleanup and foreground preemption precede semantic
enablement. Then frozen relevance evaluation, at least 20 cold and 20 warm queries,
p95 keyword <= 1 s and complete hybrid <= 2 s, five-second hard deadline, reference
100-source/2,000-chunk corpus and maximum reachable admitted-capacity qualification.
Retain failed/blocked attempts and distinguish runtime safety from semantic quality.
One slice campaign does not replace consolidated P2 acceptance.

Grounded Gemma answers/citations, Memory, PDF/OCR and the final mixed sustained P2
campaign remain subsequent work. Preserve the 64 KiB text-import limit, Gemma
2048/512 budget, baseline/isolated-QA memory scope, and deferred release/audio issues.
