# Parser-contract v2: reviewed handoff

2026-11-07. **Independent proposal QA: PASS. No outstanding findings or proposal
corrections required.** This closes proposal preparation only. Implementation,
V2 replay, mutation tests and native collection have not been authorized or run.

The [complete proposal](../planning/P2_CPP_DRIVER_OUTPUT_PARSER_CONTRACT_CORRECTION_V2.md)
is sealed by `planning/fixtures/linker-parser-contract-v2/proposal-lock-r1.json`,
SHA-256 `2e22395071ab2574325b74b2f286cd59974c28ee3467681420ec3fc3e56a540f`.
All 30 bindings remain unchanged. The proposal covers 155 frontend / 24 linker
words, 107 / 16 ordered units, 43 mismatch treatments, 27 feature values and
32 explicitly planned regression families.

The retained primary reviewer completed the guarded consistency audit: 52
protected Python byte/mode bindings, 132 historical binding entries and the old
V1 rejection. Its usage limit prevented a final verdict. That interruption and
audit remain separate; neither was promoted into an approval.

A fresh independent reviewer then checked all 30 sealed bindings, complete
grammar coverage, forms/values/counts, rendered include structure, linker inputs,
fixture provenance and limits. It accepted exact opaque Apple-token containment
**for this planning-only proposal**. Unknown vendor semantics and compiled effects
remain unqualified. A reviewer metadata-lookup error was corrected and disclosed;
the final bounded audit passed. It did not repeat the full preservation audit,
host suite, 245-input/six-inventory check or compiler-binary hash.

The genuine final review is retained under
`build/linker-parser-contract-v2/qa-proposal-r2-independent/`:

- `review.json`: `f9c25bf0a53c08af4659d0363066279df3ba1e6e8e46348f11ea8244fbb071de`.
- `snapshot.json`: `a2696cd673d2c37f87c884df35feeb182ec034f7fcaa000ad37ec1f090abdf47`.
- `verification-receipt.json`: `d146815b0bd43675c05124833f7a5728e4284c558a1206999c3b8c9d58c0b875`.

`qa-checkpoint-r1.json` binds this handoff, the unchanged proposal lock and both
review histories. It does not refresh historical freezes or qualify native work.

## Next implementation boundary

After implementation authorization, add the separate V2 parser and the complete
recorded-positive replay plus all 32 parameterized regression families. Preserve
every old parser, argument, warning policy and failed artifact. Keep synthetic
negative fixtures explicitly synthetic; a typed expected rejection stays FAILED
and cannot publish acceptance. Independent implementation QA follows. Any later
collection requires a fresh reviewed execution freeze, admission and separate
authorization; no original attempt or second case may be resumed.

No parser/runtime source, compiler flags, permissions or Git history changed in
this proposal slice. Access-1 remains FAILED / INCONCLUSIVE and the original
cause remains unproved. The one-run grant is consumed. P2 remains incomplete;
semantic search is disabled.
