# Driver-output parser v2: offline implementation results

2026-11-07. **Offline structural compatibility and host regressions: PASS.**
Independent implementation QA is recorded separately. No native bridge,
collection, compiler/linker command, flag change or sandbox change occurred.
Access-1 remains FAILED / INCONCLUSIVE, with its second case NOT_RUN.

## Implementation

`scripts/diagnostics/linker_parser_v2/parser.py` loads the sealed ordered contract
by SHA-256 and caches immutable atoms. Every decoded frontend/linker word, option
form, value, multiplicity and position is checked. It permits no generic warning,
frontend, target-feature or backend-option wildcard. Caller context is frozen
lexical data: one canonical direct-child attempt name under the sealed parent,
the exact sealed target path, derived source/output paths and a bounded shared
object basename. No caller path is resolved or normalized.

This context is not an ownership credential, execution-freeze attestation,
filesystem freshness check or marker lease. A later separately reviewed caller
must establish those properties. Neither the cached policy nor a successful
parse grants native admission. On-disk unreviewed policy bytes cannot widen a
loaded policy, and a fresh policy load rejects their hash.

Immutable results preserve the raw digest and decoded words. Expected exit1
rejections have command and terminal status FAILED. All variants keep compiler,
real-linker qualification and acceptance-publication permissions false. Comparison
rechecks token tuples, status, context, object identity and order; it rejects a
second result after a failed first case. This package contains no process runner,
publisher, bridge, native lifecycle owner or marker preparer.

## Measured host verification

| Check | Observed result |
| --- | --- |
| Focused pinned Python3.9 run | 38 test methods passed; zero failures, errors or skips |
| Reviewed regression coverage | All R01–R32 executed |
| Individual mutation actions | 1,054 executed and rejected as expected |
| Accepted witness parses | 22 checked, including recorded/synthetic and bounded rebases |
| Test-only fake boundary runs | 33 in-memory scenarios; 224 boundary assertions |
| Supplemental integrity scenarios | 21, covering policy/cache, context, forged results and offline-only surface |
| Explicit counted assertions | 1,837: 1,805 reviewed-family checks plus 32 supplemental checks |
| Instrumented function calls | 1,073 parser calls and 15 comparison calls |
| Canonical host suite | 540 test methods passed |

Counts are execution counts, not claims of unique raw transcripts. A mutation
action can change output, context or result state. Accepted witnesses, fake runs
and assertion counts overlap and must not be added into one case total. Each
counted assertion is an evaluated explicit test-helper predicate; tuple equality
counts once. The ledger has 1,826 rows because the main suite records per assertion
and the supplemental suite records per scenario. It is not a case count.

`development2-cases.json` and `development2-result.json` retain the complete
case/assertion ledger and breakdown. Test-only fake owners/clocks simulate
cancellation, first-failure stop, limits, cleanup/quarantine and no-publication
expectations; they establish no actual runtime lifecycle or resource enforcement.
The full suite uses the existing reference environment and retained cached Rust
host fixtures, separate from zero new Clang/linker/access probes. No dependency
installation, native compilation or device work was needed.

An initial report adapter looked for `family` instead of the main ledger's
`family_id`: all 38 methods passed, but the adapter reported incomplete coverage.
Its log, ledger, source and failed report remain preserved. The corrected adapter
counts the existing ledger correctly; its ledger digest matches the first run.
No parser/test weakening, filtering or skipped family was used to obtain PASS.
A separate development probe rejected mutations at all 179 word positions
(180 checks); it overlaps the suite and is not added to its totals.

## Replay and preservation

The entire retained positive capture parses as OFFLINE_STRUCTURAL_COMPATIBLE,
preserving all 155 frontend and 24 linker words. The explicitly constructed
negative fixture parses as a typed rejection with FAILED command/terminal
status. Their computed pair shape is synthetic-only: it establishes no new
native policy contrast, compiler compatibility, semantic support or original
failure cause. The old parser still rejects the same first option, and its
historical outcome was not rewritten.

Fresh preservation checks verified 52 protected Python byte/mode bindings,
132 prior historical binding entries, 30 proposal bindings and 11 proposal-QA
bindings. All old parsers, arguments, warning policy and failed evidence remain
unchanged. This was not a fresh full245-input/six-inventory/compiler-binary audit.

The implementation lock binds source, tests, exact policy/proposal, guarded
runner, passing logs, ledgers, replay and preservation receipts. Evidence is under
`build/linker-parser-v2/`; independent implementation review is a subsequent
record. These are implementation/evidence snapshots, not execution freezes or
permission to collect native output.

Native bridge integration, collection, resumed cases, flags, sandbox changes,
Bazel/runtime builds and devices remain outside this slice. Later work needs
its separately reviewed freeze, admission and authorization. The old run grant
is consumed. Apple-specific compiled effects remain unqualified. P2 remains
incomplete and semantic search is disabled.
