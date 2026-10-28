# P2 escaped-Unicode boundary: host-only diagnosis

2026-10-28, `dev` at `ae678b7ecd3760ba3739bf89127ac5cb8bae2fa1`.

**The source-built bounded-string grammar restricts Unicode escape spelling.**
The failure is reproducible without any tokenizer: the exact pinned `JsonQuote`
AST accepts literal `青` but rejects the equivalent JSON string `"\u9752"` at
its first hexadecimal digit `9`. This isolates a source-level representation
restriction consistent with the retained witness failure. It does not establish
what the packaged GPU implementation did or why Gemma chose newlines.

**Candidate acceptance remains FAILED (historical 39/41, exit 2).** The witness,
strict validator, schemas, whitespace candidate and acceptance criteria are
unchanged. There is no literal-Unicode-only policy, output repair, dependency
patch, native proposal or execution authorization. P2 remains incomplete and
semantic search disabled.

## Pinned source and independent byte probe

The existing source-build pins remain LiteRT-LM 0.17.1 source revision
`5e58e9a0aef7abf7091207a8b1d1063a1c800f08`, LLGuidance 1.3.0, toktrie 1.5.0,
derivre 0.3.8, the same six patches, Cargo.lock and features. The cached derivre
crate SHA-256 is
`786c7c65c4ef0c7deb05de3005e01991612a8f09fe0844fc0969c68b90468ba8`;
every materialized source file was compared with that checksum-verified archive.

Two source locations establish the investigated path:

- `derivre-0.3.8/src/regexbuilder.rs:26–32` explicitly documents that `u` in
  `JsonQuoteOptions.allowed_escapes` permits `\uXXXX` only for ASCII controls
  U+0000–U+001F and DEL U+007F, **not general Unicode**.
- Pinned/patched `llguidance-1.3.0/src/json/compiler.rs:687–694, 808–812` builds
  `JsonQuote(Regex("(?s:.{1,96})"), allowed_escapes="nrbtf\\\"u", raw_mode=false)`
  for the tested bounded string. The unbounded-string path's separate
  `u[a-fA-F0-9]{4}` expression does not establish acceptance in this branch.

The new safe Rust companion calls the pinned regex engine directly on JSON
bytes, without vocabulary, SentencePiece or the C API. It lives only in a copied
host diagnostic workspace and adds no Rust dependency, FFI surface or app code.

All 18 frozen strings are valid under an independent Python JSON parser and
JSON Schema validator. Source predictions are recorded separately from that
validity oracle; they do not replace the failed candidate criteria.

| Equivalent spelling / control | Byte-level observation |
| --- | --- |
| Literal `A`, `青`, `€`, `🙂`, combining `é` | Accepted |
| `\u0041`, `\u9752`, `\u20ac`, `\ud83d\ude42`, `e\u0301` | Rejected |
| `\u0000`, upper/lower `\u000A`, `\n`, upper/lower `\u007F` | Accepted |
| Escaped quote and literal space | Accepted |

The exhaustive 128-value ASCII escape check accepts exactly controls 0–31 and
127. For the three partial byte prefixes, the allowed next bytes are:

| Prefix (opening quote included) | Allowed next ASCII bytes |
| --- | --- |
| `"\u` | `0` only |
| `"\u0` | `0` only |
| `"\u00` | `0`, `1`, `7` |

## C-API, tokenizer and original-witness comparison

The existing reviewed C-API binary and process broker are reused unchanged.
Tokenization uses the same pinned artifact and EOS 1. Raw vocabulary pieces are
the inspected provider boundary; decoded vocabulary is comparison-only.

The final collection contains **70 comparison records: 66 executed C-API
probes and four explicitly unavailable alternate tokenizations**. Of the 66,
42 prefixes replay and 24 are rejected. These are observations, not 42 passes and
24 failed tests. The tokenizer-free probe separately covers all 18 strings.

Both canonical and exact single-ASCII-piece constructions reject minimal
`"\u9752"` at token ID **236819 (`9`)** after `"\u`, under raw and decoded
vocabularies. The canonical position is 2; the single-piece position is 3.
All four masks at that prefix admit only token **236771 (`0`)**, with mask hash
`ecca1b433018c7690028319f7ab2fffde7f9ca0ff504dc86c38c8ec8df17b037`.
The direct byte probe independently admits only byte `0` there.

The full retained witness reproduces **zero-based token position 39, ID 236819**
in all four canonical baseline/candidate × raw/decoded comparisons. Rejection
is in `\u9752`, before the surrogate pair. Masks and tokenizer transcripts match
between raw and decoded within each schema; baseline and candidate transcript
hashes differ because their schema callback inputs differ.

The optional full-witness single-ASCII-piece construction is **unavailable**:
its literal space has no exact ` ` vocabulary piece. All four such comparisons
record that reason and execute no probe. No replacement token, omitted space or
rewritten witness was used. Minimal-string alternate constructions do exist and
were executed. This evidence makes no claim about every possible tokenization
of the full witness.

## Attempts, preservation and evidence

Local evidence is retained under `build/unicode-boundary-v1/`:

- `prepared/` and `initial-byte-results.json`: initial construction, before
  Rust formatting/tests were finalized; retained, not acceptance evidence.
- `prepared-final/`: source-bound companion build with the same dependencies.
- `collection-1/`: stopped after 63 C-API probes when the unavailable full-witness
  alternate raised an error. Report, outputs and error are preserved.
- `collection-2/`: final diagnosis, including explicit unavailable comparisons.
  Its 275 bound files verify; report SHA-256:
  `dcfdd19f2ccc7c3ca34e726c18f4e06919c4caf296570bf88cb7e3f8db4e808a`.
- `preservation.json`: all 20 prior reviewed source/provenance bindings match.
  Both historical execution bundles' **343 bindings each** match current and
  archived copies, including the two APKs. No historical freeze was renewed.

The new investigation lock is
`50d7cb52f88c1b5187582c45c0207167c1a5e712beaf316746c373da0362a575`.
The unchanged strict validator SHA-256 is
`10be77c8586f0ee9931c9c33b965acd3608fe379010afde75e27b1dfd5526000`.
The prior reviewed snapshot remains
`20a167d9d1de9cbb3e825efbdd3557415e6b5bc08f561305b93d6c82309c8ee0`.

## Verification and next gate

Fresh checks passed: **11 Rust tests** (four new byte-probe tests and seven
unchanged FFI/process boundary tests), **53 Python tests**, Rust formatting,
locked/offline release build, default Clippy with warnings denied, identity and
preservation checks. No tests were skipped. The new tests cover exact alternate
round-trips, lock/input tampering before process launch, reference equivalence,
controlled malformed/oversized input failure and the ASCII escape domain.
Reproduction is in `scripts/diagnostics/unicode_boundary/README.md`.

Kotlin tests, Android builds, S24/emulator workloads and Gemma were **not rerun**:
no application, Kotlin, runtime adapter or prior diagnostic files changed.
The new diagnostic/source report is subject to independent QA; a diagnosis is
not candidate approval.

The limitation is isolated at the inspected source-built bounded-string grammar.
It is **not resolved**. Any fix must preserve equivalent JSON Unicode spellings
and the strict validator under the current contract, or follow an explicitly
reviewed architecture decision replacing that contract. No such change is made
or proposed for native execution here. Original S24 mask equivalence, sampled
histories/logits and the reason for Gemma's newline selection remain unproven.
No commits, pushes or branch changes were performed.
