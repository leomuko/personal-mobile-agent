# Linker executor R2 — correction, guidance and readiness

2026-11-06. **R2 host implementation is complete; independent R2 review and
canonical admission remain pending in this producer snapshot. No native execution
is authorized or performed.** P2 remains incomplete; semantic search is disabled.

## R1 finding and correction

The retained primary reviewer completed R1 review before becoming usage-limited.
Its genuine FAIL verdict reproduced CPP-EXEC-001: an outcome could report
publicationUncertain=true, cleanupConfirmed=true and no public acceptance link,
yet quarantine admitted the parent. No R1 PASS attestation was issued and no
canonical positive admission was attempted for the rejected implementation.

R2 explicitly rejects that flag independently of link presence and native cleanup.
The regression follows the full sequence: owned links were unlinked, revocation
returned uncertainty, a failed terminal outcome persisted and later admission was
blocked. Unknown/foreign publication and uncertain cleanup remain quarantined.
R1 source/freeze/report/log bytes remain in the archived source snapshot; its
review, reproducer and executable freeze are retained unchanged. No old verdict
or acceptance criteria were rewritten.

## Requested development guidance

The two requested Bazel/Clang skills were installed with the supplied immutable
revisions and helper. Both expected entry hashes match; all 19 installed files
match pinned upstream bytes. Both MIT licenses are retained. Provenance documents
record all identities, reviewed resources, routing and exclusions. A separate
supporting developer independently checked the local hashes, licenses, baseline
configuration comparisons, scoped routing and 13 historical AGENTS binding records.
That supporting verification is not executor approval.

The new AGENTS routing is an authorized source delta. Its prior bytes are preserved;
older analysis freezes and QA snapshots remain unchanged. Only R2 binds the new
AGENTS/provenance/licenses and all installed guidance files. No automatic Bazel
upgrade, compiler option/policy change, sanitizer/LTO, linker substitution, fix-it,
hook, plugin, MCP connection or runtime package was introduced. Guidance does not
resolve the linker result or authorize execution. Existing diagnostic-skills
provenance remains unchanged.

## Host results and evidence limits

The **21 focused executor tests passed** under pinned isolated Python 3.9, including
the new quarantine regression and missing guidance/AGENTS binding rejection.
The full existing reference suite reported **388 tests: 387 passes, one error**.
That one error is the unchanged historical Unicode-campaign source guard rejecting
`AGENTS.md` after the authorized routing amendment. Its failed log is retained;
the old guard, test and frozen campaign bytes were not relaxed or refreshed.
It is not an all-green full-suite claim. A separately reviewed archival-test
migration remains a follow-up, not an implicit native-execution permission.
An earlier R2 unit-test reader-mock error is also retained separately; its overly
broad fake reader was narrowed and the final focused run passes.

Data-only R2 freeze preparation passed with **207 bound inputs and six complete
inventories**. Freeze SHA-256:
`0fedb46426268e487cfb18ec55a04b04b0c348db7b5ba956990d03118c0d1d56`.
The exact seven probes, approved proposal, compiler-only sandbox and 300s/30s/15s
budgets are unchanged. No future probe directory, native owner, compiler/linker
query, SDK/Bazel build or device workload was created by preparation. The 1 GiB
small floor never lowers the separate 18 GiB analysis floor.

## Independent review and model design boundary

After the primary R1 review, another developer agent was separately assigned R2
implementation QA. It must issue a genuine stable review for the new freeze only
if approved, then exercise the actual admission function with real readers/hashes
and instrumented execution boundaries. No unit approval is promoted to a real
attestation. This snapshot does not claim that pending review or proof passed.

The two MODEL_* design documents preserve user-selected compatible models and
existing conversation/database/runtime ownership. Artifact/runtime/tokenizer/
template/budget/modality/decoding identities and per-capability qualification are
separate. A second configuration with its own verified budget is required before
interchangeability claims. Exact benchmark inputs/raw responses and separate
relevance, sufficiency, citation-formatting, latency/memory/cleanup measurements
remain required, with benchmark/integration/runtime/model-task/unresolved failure
attribution. No second model or application feature was implemented or acquired.

A later seven-probe run requires separate explicit authorization after reviewed
executable-freeze admission. Absolute --ld-path may still fail its executability
check; no permission diagnostics are added. A pass would prove dry-run identity
agreement only. All 72 C++ cases, runtime artifacts, Android acceptance and full P2
quality remain separate. No Git mutation or device workload occurred. Emulator-first
validation and deferred S24 testing remain unchanged.
