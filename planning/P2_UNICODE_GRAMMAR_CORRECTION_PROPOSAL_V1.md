# P2 Unicode grammar correction — host-only proposal V1

2026-10-28. Protocol `unicode-grammar-correction-host-v1-draft-1`.
Baseline: `dev` at `b35e172eb121c347c5251d58bde80cad4f46bfb2`.
Status: **proposal for review; no correction implemented or authorized by this document**.
No device experiment is proposed. P2 remains incomplete; semantic search disabled.

## Decision requested

Review a narrow, upstream-informed backport in a **new isolated host build** of
LLGuidance 1.3.0. Keep derivre 0.3.8, toktrie 1.5.0, Rust 1.87.0, Cargo.lock,
features, six existing patches and the application runtime unchanged. Prefer
correcting the plain-string schema compiler over changing derivre's arbitrary
regex-quoting algorithm or upgrading the complete grammar stack.

This proposal is limited to review. A later implementation instruction is needed
before writing a patch, candidate schema, executable runner or test corpus.
Independent proposal approval does not authorize implementation or device work.

## Evidence and available approaches

The [retained investigation](../docs/P2_ESCAPED_UNICODE_BOUNDARY_RESULTS.md)
isolates the pinned bounded-string `JsonQuote` restriction without a tokenizer.
Its failed candidate remains **39/41**, not reclassified by this proposal.

Upstream has a relevant implementation at immutable commit
[`7aa1c3b67ec97cee62dc23123204a4351e9d40d4`](https://github.com/guidance-ai/llguidance/commit/7aa1c3b67ec97cee62dc23123204a4351e9d40d4),
PR #374. It adds an opt-in plain-string Unicode-escape path, counts a valid
surrogate pair as one decoded scalar and leaves regex/constant matching on its
existing path. Source at that revision declares LLGuidance 1.7.6 and derivre
0.3.12; neither is the project's pinned diagnostic dependency. Source inspection
is not a successful backport, build, compatibility test or native qualification.

| Approach | Assessment | Proposed disposition |
| --- | --- | --- |
| Adapt the upstream plain-string compiler algorithm to pinned 1.3.0 | Targets the proven failing branch; preserves regex engine, token environment and ABI. Requires source adaptation and complete regressions. | Recommended host-only candidate, subject to review and later authorization. |
| Update to the inspected upstream stack | Broader dependency/API, tokenization, patch and behavior changes. All six LiteRT patches, C header/layout and build features would need renewed compatibility evidence. | Assessed alternative; not selected or authorized. |
| Extend derivre's general `JsonQuote` transform | Must preserve arbitrary pattern/regex semantics over UTF-8, not just decoded string length. Much larger proof obligation than this failing branch. | Not selected. |
| Require literal Unicode, drop the witness, remove bounds, repair output or relax the validator | Changes the accepted contract or hides failure. | Prohibited. |

The pinned compiler lacks upstream's separate configurable-escape field. Do not
cherry-pick #374 blindly or import intervening commits as hidden prerequisites.
The backport would retain the existing fixed escape policy and use its same
short escapes plus general `u` handling; adding a configurable-escape API, Python
bindings, dynamic-key changes or general JSON-schema conformance is outside scope.
If this adaptation requires a dependency/feature/API change, stop for a revised
proposal rather than silently selecting the upgrade alternative.

## Proposed correction boundary

Only a new copied diagnostic workspace may be patched. The implementation
allowlist would be:

- One additional hash-pinned patch against copied
  `vendor/llguidance-1.3.0/src/json/compiler.rs`, including its focused Rust tests.
- A default-false compiler option named `json_allow_general_unicode_escapes` and
  a helper for decoded-scalar string repetition. When explicitly enabled, route
  only regex-free string value schemas through that helper. Validate bounds
  before selecting the path and reuse bounded compiled expressions.
- A separately versioned preparation/runner under
  `scripts/diagnostics/unicode_correction/` and candidate inputs under
  `planning/fixtures/grammar-progress-v2-unicode/`. They must preserve the existing broker's process,
  callback and FFI contracts; no new FFI surface is needed.

For each candidate schema, clone the retained compact schema and add only the
explicit opt-in annotation. Keep its `whitespace_flexible=false` setting and all
semantic schema fields, required keys, enum/type/array/string bounds identical.
Bind the new schema bytes and the additional patch in a new lock. Do not edit the
old V1 candidate or V2 schemas, preparation locks, histories, reviews or runner.

Regex-constrained strings, constants and fixed property-name spellings retain
baseline handling. This is a proposal to fix general Unicode spellings in
regex-free value strings, including `i`, `p`, `r` and `q` in the evidence schema;
it does not claim every equivalent JSON spelling of every schema construct.
An unexpected use of the new path for patterns/constants must fail review.

The upstream literal-character expression also excludes DEL U+007F, although
[JSON permits it unescaped](https://www.rfc-editor.org/rfc/rfc8259.html#section-7).
The opt-in helper must explicitly cover literal DEL and its Unicode-escaped
form; do not copy that restriction while claiming scalar-spelling equivalence.
Raw U+0000–U+001F remain invalid JSON and require escapes. Preserve the pinned
short-escape policy; this proposal does not add a configurable escape API.

The helper must model one decoded Unicode scalar per repeated unit: literal
UTF-8 scalar, permitted short escape, non-surrogate BMP `u` escape, or valid high
plus low surrogate escapes. A pair counts once. Never widen a pattern by
replacing it with an unrestricted Unicode character expression. Use checked bound
conversions; reject unsupported bounds rather than wrapping or dropping them.

## Contract that must remain intact

1. Literal, escaped and mixed representations of the same valid scalar sequence
   must have equal decoded values and equal length acceptance in the selected
   string-value path. Preserve case-insensitive hexadecimal digits, spaces,
   control escapes, quotes, backslashes, BMP, supplementary and combining text.
   Do not normalize NFC/NFD or infer that combining sequences are one character.
2. `minLength`/`maxLength` count decoded Unicode scalar values, not serialized
   bytes, escape digits, grapheme clusters or UTF-16 code units. The existing
   field bounds (including quotation 1–96) remain exact. Test zero and exact
   endpoints, below/above bounds, and `minLength > maxLength` rejection.
3. A surrogate escape pair must be well formed. Invalid hex, truncation, lone
   high/low surrogates, reversed pairs and raw control bytes cannot become valid
   scalar strings. An ordinary JSON parser may preserve lone surrogates, so the
   reference oracle must separately enforce valid scalar sequences instead of
   treating Python parse success as validity evidence.
4. Citation coordinates remain half-open **UTF-16** spans on immutable revisions.
   That is a separate measure from schema scalar length. The unchanged strict
   validator must still reject surrogate-split spans, wrong quotations, stale
   references, duplicate/extra keys, invalid ordering and aggregate states.
5. Context 2048, output reservation 512, EOS 1, pinned tokenizer/vocabulary,
   memory/thermal safeguards, existing host watchdog/fuel/output limits and
   cleanup/quarantine guarantees remain unchanged. This host-only work grants
   no extension of the five-second search or scoped native experiment ceilings.

## Planned implementation and verification order

Only after this proposal is reviewed and implementation is separately authorized:

1. Freeze the new patch/input/test manifest and oracle rules before collecting
   candidate outcomes. Preserve all old baselines and expose one named backport
   candidate. No automatic fallback, dependency bump or candidate retuning.
2. Validate source checksums, exact patch application, unchanged registry lock
   identities/features and C ABI. Build in a new directory. Fail closed on
   context mismatch, unsupported options, wrong schema identity or unavailable
   prerequisites; retain failed attempts.
3. Exercise the **actual compiler-produced expression** at byte level. Do not
   test an independent handwritten regex in place of the patched compiler. Keep
   the original tokenizer-free `JsonQuote` diagnosis intact: that unchanged lower
   layer is still restricted; the selected compiler branch is the correction.
4. Run reference/differential and token-level checks, then the complete existing
   acceptance gate on the new candidate. Use raw pieces as the provider boundary;
   decoded vocabulary remains comparison-only. Reconstructed tokens are never
   presented as sampled Gemma history. Unavailable tokenizations get no credit.
5. Independently review source, new locks, results, failure records and limits.
   A host pass permits discussion of a next step; it does not authorize or prove
   packaged SDK integration, GPU behavior, Gemma completion or semantic search.

### Required test matrix

| Layer | Required checks and pass condition |
| --- | --- |
| Historical preservation | Both prior reviewed source snapshots, original 39/41 report, all 18 retained diagnostic cases, strict-validator hash and both 343-binding execution bundles remain unchanged. |
| Default-off compatibility | Same schemas with the opt-in absent/false preserve old mask and tokenizer-transcript baselines; original escaped witness still demonstrates the old limitation. Unsupported/wrong-typed option inputs reject explicitly. |
| Value/length oracle | Parse equivalent spellings, independently validate scalar sequences, compare decoded values and scalar counts against schema bounds. Test boundaries for 1, 32, 64 and 96; single-scalar tests distinguish a supplementary character from two scalars. |
| Unicode domains | Exhaustive ASCII 0–127; deterministic representative BMP/supplementary boundaries, mixed hex case, combining sequences and noncharacters; generated valid scalar sequences with a frozen seed and sample budget. No surrogate-only values or almost-always-filtered generators. |
| Broken inputs | Malformed/truncated escapes, invalid pairs and illegal raw controls reject; mixed literal/escaped overflow remains overflow. Patterns/constants do not enter the new branch. |
| Full schema / strict validation | Preserve all original structural witnesses, references and UTF-16 coordinates. Keep expected strict rejections even when grammar accepts their structure. The original escaped witness must complete under the corrected candidate, not a replacement witness. |
| Existing 41-case gate | Same assertions: numeric whitespace forbidden, zero consumable, wrong digit/EOS forbidden, valid witnesses complete and strict validator unchanged. Require all 41 expected outcomes; historical result remains failed. Extra tests cannot compensate for a failed original check. |
| Process and resources | Existing callback, bounded I/O, owned-child interruption/reaping, timeout, malformed input, uncertain-cleanup and stale-proof regressions pass. Preserve compile fuel, mask watchdog and bounded-memory rules; measure candidate compilation/mask cost separately. |

A failed expected outcome blocks acceptance. Fixing an implementation defect may
produce a new retained attempt after review; it cannot alter expected outcomes,
remove data, overwrite failures or add an unreviewed alternative. Host success
alone is neither a native experiment proposal nor proof of the original model's
newline-selection mechanism.

## Proposal artifacts and current limits

The companion `planning/fixtures/unicode-correction-proposal-v1/upstream-audit.json`
records immutable upstream/local identities and inspected-file hashes. Its
`proposal-lock.json` binds that audit and this document **for proposal review
only**. These artifacts contain no correction patch, candidate executable,
approval attestation or device authorization.

Public upstream text/metadata was inspected and retained in ignored
`build/unicode-correction-proposal-v1/upstream/`; it was not built or executed.
The upstream MIT license was inspected; a future adapted patch must preserve
applicable provenance and copyright notices. No source is vendored into the app. A source-built host backport cannot modify
the already published LiteRT-LM AAR. Any later deployable SDK/runtime change needs
a separate architecture, build-provenance and compatibility review; host success
is not a drop-in Android fix or permission to replace a packaged native library.

During preparation: no Rust/Python/Kotlin implementation changed, dependency
update occurred, experiment ran, Android build/device access occurred, or Git
mutation was performed. Identity/whitespace checks are documentation checks,
not a new grammar acceptance result. Implementation and native feasibility remain
blocked pending their separate gates; P2 is incomplete and semantic search off.
