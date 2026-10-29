# P2 Unicode-value grammar correction — host results

2026-10-28, `dev` at `b35e172eb121c347c5251d58bde80cad4f46bfb2` plus this
uncommitted host-only slice. Implementation follows the user's `proceed` after
the independently reviewed V1 proposal. No Android integration or device work.

**The separately versioned host candidate passes 910 frozen compiler checks and
all original 41 expected outcomes.** Default-off runs preserve all 16 baseline
masks and six tokenizer transcripts, both with the option absent and explicitly
false. Independent implementation QA remains the next gate for this report.

The historical candidate remains **FAILED, 39/41**. Its report, failed witness,
locks, schema bytes, raw S24 failures and strict validator are unchanged. A new
host pass does not repair historical results, alter the packaged runtime, explain
Gemma's newline selection or enable semantic search. P2 remains incomplete.

## Implemented scope and provenance

One additional patch changes only a copied LLGuidance 1.3.0
`src/json/compiler.rs`. It adds default-false
`json_allow_general_unicode_escapes`, selecting the corrected branch only for
regex-free value strings. Patterns, constants, fixed property names, dynamic
keys, existing `JsonQuote` and default-off handling keep their original paths.

The helper adapts the upstream algorithm at
`guidance-ai/llguidance@7aa1c3b67ec97cee62dc23123204a4351e9d40d4` (PR #374).
Provenance and the applicable Microsoft MIT notice are retained beside the patch.
The upstream audit and reviewed proposal are unchanged. Adaptations are explicit:

- Use the pinned fixed short-escape policy; do not import the newer configurable
  escape API, Python bindings, dynamic-key rewrites or intervening commits.
- Permit literal DEL and its Unicode-escaped representation in the opt-in path.
- Match one valid Unicode scalar per repetition, including a complete surrogate
  escape pair; reject lone/reversed/malformed pairs. Preserve combining scalars
  without normalization and use checked bound conversions.
- Cache scalar and length expressions in the compiler's existing regex arena.

Dependencies/features, Rust 1.87.0, the original Cargo.lock/40 registry identities,
LLGuidance 1.3.0, toktrie 1.5.0, derivre 0.3.8 and the six existing LiteRT patches
remain pinned. No application dependency, shared Kotlin API, C ABI or FFI code
changed. The existing diagnostic main and owned-process broker are reused byte
for byte. The only extra build target is a private standalone compiler-probe bin.

| Identity | SHA-256 |
| --- | --- |
| Reviewed proposal lock | `099cd96d97ac4646b86e7916fc1db1ee7e09ae9e956d3a97e1dfffaa60edf29c` |
| New input/patch lock | `4e5dd4b9bad7e93bdc300363c7d5000b73d14df7ffdf6c548c0f42ea2bbe9277` |
| Additional compiler patch | `a83792c7c0232d05bcb2d3459d281979a21cf3547faac0d1d4298529d89173cf` |
| Original copied compiler | `bcae5ae216a14848b2d1c5a9a1b7636aabf734d1adf7df07cc7b47b339980751` |
| Patched copied compiler | `5a8f39a3688800ec86a80be4eeeca518991b905a1a3bf8435266cfbb1c12ef5c` |
| Unchanged Cargo.lock | `27dba748a19e0a33336c00870f028e2a4e5d515482f72a42e7a5bff2c845c219` |
| Source-built mask executable | `0c5fde632f0f7991584ad6239095eba4c073c949d7324f84e337ce82fd315ca8` |
| Source-built compiler probe | `7cc073ec02dab911779b9ac179f312a32c298f274c33db3eaeacc65c6dd3d556` |

The new schemas clone the old compact candidates and add only the explicit
Unicode opt-in. Semantic bounds/structure and `whitespace_flexible=false` are
unchanged. The frozen seed is 20261028, with 32 generated scalar sequences. No
candidate parameters, criteria, fixture values or source changed between the
first frozen build and the successful campaign.

## Actual compiler and original-gate results

The byte-alphabet probe uses existing public LLGuidance APIs to compile each
schema and feed its actual parser one byte at a time. It does not test an
independent handwritten regex, call SentencePiece, use Gemma token histories or
add a dependency for LLGuidance's separate development tests. The original
standalone `JsonQuote` diagnosis is still retained and still restricted: the
compiler's selected value-string branch is the correction.

| Frozen byte-alphabet category | Checks |
| --- | ---: |
| Exhaustive ASCII, representative scalar boundaries/noncharacters, length boundaries and deterministic generated spellings | 795 |
| Original 18 reference-valid diagnostic strings | 18 |
| Default-off versions of those strings, absent and false | 36 |
| Malformed escapes, invalid surrogate pairs and illegal raw controls | 46 |
| Checked unsupported/conflicting bounds | 3 |
| Pattern and constant compatibility | 10 |
| Wrong-type/unknown compiler options | 2 |
| **Total, all matched expectations** | **910** |

Outcomes are **702 accepted strings, 203 expected rejections and five identified
compile errors**. They are not 910 accepted strings. The compile errors identify
conflicting bounds, unsupported min/max conversion, wrong Boolean type and
unknown option. Malformed data cannot gain acceptance through generic failure.

The independent Python oracle parses each relevant spelling, checks valid scalar
sequences separately from JSON parse success, and validates decoded scalar length.
The cases cover 1/32/64/96 limits and zero-length, exact and overflow inputs;
paired supplementary scalars count once and combining scalars remain distinct.
All 910 results are complete, in frozen order, in 15 bounded batches.

The original 41 named checks are unchanged in inventory and expected behavior:
compact numeric whitespace stays forbidden, zero remains consumable, wrong digit
and premature EOS stay forbidden, the original 14 structural witnesses complete
under both raw and decoded vocabularies, and all three malformed-option checks
receive identified native rejections. **The original `unicode-escaped` witness
now completes with EOS admitted under both vocabularies.** It was not rewritten,
re-tokenized as a substitute witness or required to use literal Unicode.

The existing host-only Kotlin `GrammarProgressFixturesTest` independently passed
all 14 strict-context outcomes in its one test: exact quotation/UTF-16 spans and
intentional strict rejections remain intact. The strict validator SHA-256 remains
`10be77c8586f0ee9931c9c33b965acd3608fe379010afde75e27b1dfd5526000`.
Grammar admission does not override context validation or prove factual support.

## Resource observations (host only)

Measurements use `/usr/bin/time -l` inside the existing broker's owned process
group, on macOS arm64. Default compile fuel, the 30-second host watchdog, callback
and output budgets, case limits and cleanup rules are unchanged.

| Measurement | Observed |
| --- | --- |
| Actual schema compilation, 910 cases | Median 88 µs; p95 168 µs; maximum 954 µs |
| Byte replay, 905 compiled cases | Median 5 µs; p95 172 µs; maximum 425 µs |
| 15 byte-probe batch processes | Maximum RSS 6.125 MiB; median wall 15 ms; maximum wall 520 ms |
| 50 successful mask-probe processes | Maximum RSS 227.516 MiB; median wall 647.5 ms; maximum 1087 ms |
| Three expected native option rejections | Classified correctly; complete RSS/timing records unavailable after owned error cleanup |

All 53 mask-probe invocations remain in the report. The three deliberately
invalid-option probes terminate through the broker's identified-error cleanup
path before the timer emits a complete record. Their RSS is explicitly null,
not zero or a successful measurement; they are excluded only from the resource
summary, not from the 41-case outcome denominator.

RSS is the diagnostic child process high-water mark; it excludes the separate
Python broker and is not total pipeline memory, S24 PSS or model residency.
Compilation figures include actual parser creation; byte replay is not Gemma
inference or a model-mask latency benchmark. Source-build time and model load are
not substituted for these quantities. No Android or production latency claim
follows from these measurements. The five-second search contract, scoped native
ceilings, context 2048/output reservation 512 and memory/thermal policy stay fixed.

## Retained attempts and verification

Local artifacts under ignored `build/unicode-correction-v1/`:

- `prepared/`: exact copied source, seven-patch composition, frozen inputs and
  unchanged registry lock. Build and Clippy logs are retained.
- `campaign-1/`: infrastructure failure at the first default-off probe because
  the restricted host denied `/usr/bin/time` access to `kern.clockrate`. No
  compatibility/byte/41-case acceptance credit; owned cleanup confirmed. This is
  not a Unicode-quality failure or a discarded candidate selection result.
- `campaign-2/`: same source, binaries and fixture lock, rerun with the required
  host-counter permission. Successful candidate report SHA-256:
  `3479ace7c8c15492cb2ed49d0a9f9b6226a3190c0a096ebaebf8de4f6b1fcb39`.
  All 292 report-bound files verify. No retry changed the candidate or limits.
- `strict-validator-test.log`: the restricted Gradle cache-lock failure, retained.
  `strict-validator-test-permitted.log`: the successful fresh host fixture test
  after access to the installed Gradle cache was permitted.
- `preservation.json`: both prior source snapshots' 29 bindings, both historical
  343-binding execution bundles (current and archived), original 39/41 report and
  all 275 prior Unicode-diagnosis evidence files remain unchanged.

Fresh checks passed: **13 Rust tests**, **63 Python tests**, the **one focused
Kotlin host fixture test** covering 14 structural witnesses, locked/offline
release build, default Clippy with warnings denied, Rust formatting, exact input/
source/baseline identities and whitespace checks. No tests were skipped.
Full JVM/Android-host suites, APK builds, emulator/S24 tests and Gemma workloads
were **not run** for this host diagnostic slice. Earlier passes are historical.

Reproduction and patch provenance are in
`scripts/diagnostics/unicode_correction/README.md`. Sources, fixtures and this
report are bound by `docs/P2_UNICODE_GRAMMAR_CORRECTION_SOURCE_SNAPSHOT.json`.
Independent implementation QA must assess that snapshot before acceptance of this
slice. A host PASS would still require a separate deployment/integration decision:
the published Android AAR has not changed. No native proposal, device execution,
semantic enablement, commit, push or branch change accompanies this work.
