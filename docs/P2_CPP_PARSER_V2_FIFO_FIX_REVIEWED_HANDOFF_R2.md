# Reviewed V2 policy-loader FIFO fix

2026-11-10. **PASS for the minimal offline fix.** Independent QA found no
actionable issues and closed `V2-PARSER-001`.

The parser adds only `O_NONBLOCK` to contract-file opening. `O_NOFOLLOW`,
descriptor checks, regular-file/size rejection before reading, identity checks,
bounded reads and the pinned digest remain intact. No parsing contract changed.

## Bound checkpoint and verification

R2 lock: `planning/fixtures/linker-parser-v2-implementation-v1/implementation-lock-r2.json`,
SHA-256 `c1ebf9dc0bca9ce1a1594fe170a39d780071e568f07d50666e643c21478f40b8`.
All 25 bindings verified. Base/HEAD remains
`2ead92a2e641adc4565d13074bfb105ad29039c7` on `dev`.

- New bounded FIFO regression: producer RED before the fix, then PASS after it;
  independent pinned Python 3.9 rerun also passed. The owned child was reaped.
- Focused suite: 38 methods passed in producer and independent runs, with all
  32 families, 1,054 mutation actions and 1,837 counted checks unchanged. The
  complete 155/24-token replay and FAILED synthetic negative remain intact.
- Canonical host suite: producer ran 541 passing tests. Independent QA verified
  and inspected that retained result rather than repeating the full suite.

Independent reviewer: Archimedes (`01a1b63a-5ec5-73c1-af98-e2d13cf68664`).
Review, snapshot and verification records are retained in
`build/linker-parser-v2/qa-fifo-fix-r2/`; `qa-checkpoint-r2.json` beside the lock
binds this subsequent verdict without rewriting the lock's pending-review field.
Detailed producer results are in `docs/P2_CPP_PARSER_V2_FIFO_FIX_RESULTS_R2.md`.

R1 remains immutable historical evidence. Its parser row is explicitly verified
against retained R1 source, not admitted as current source. All other 26 R1
implementation bindings, 20 R1 QA bindings and five retained source/doc copies
remain unchanged. The producer repeated the earlier 52/132/30/11 preservation
checks; independent QA inspected that receipt rather than repeating that broader
audit. No full 245-input/six-inventory/compiler rehash was performed.

## Next boundary

This closes the abnormal-file blocking fix. No native bridge integration,
collection, resumed case, compiler/linker/access probe, permission change or Git
mutation occurred. The Python FIFO child is test-only, and fake ownership tests
establish no native lifecycle guarantee.

V2 bridge integration remains separately scoped and reviewed. Later collection
requires an execution freeze, admission and explicit authorization. Access-1
remains **FAILED / INCONCLUSIVE**, its second case **NOT_RUN**, original grant
consumed. **P2 remains incomplete; semantic search stays disabled.**
