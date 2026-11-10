# Offline V2 policy FIFO rejection: R2 results

2026-11-10. Producer verification: **PASS**. Independent implementation QA is
recorded separately after this immutable checkpoint. Scope is the policy-loader
fix only; native integration and collection remain excluded.

## Change and regression

The only parser change adds `O_NONBLOCK` to the contract-file open flags.
`O_NOFOLLOW`, descriptor-based regular-file/size validation, stable descriptor/path
identity checks, bounded reads, pinned SHA-256 and immutable cache are retained.
An unopened FIFO with no writer now reaches the file-type check immediately
instead of waiting inside `open()`. No contract, transcript, option or output
validation rule changed.

`scripts/tests/test_linker_policy_fifo.py` uses a fresh isolated Python child
and an owned temporary FIFO without a writer. It requires the exact typed
`contract type/size` rejection, bounds communication to two seconds and always
kills/reaps its owned child before temporary-directory cleanup. It launches no
helpers, uses no process-group or package-wide stopping, and alters no real
contract path. A cleanup error propagates as a test failure.

This subprocess test is separate from the 38-method guarded offline suite,
which continues to prohibit subprocess/native/access calls. No new dependencies
are needed. Python process execution for this regression is not a compiler,
linker, OS access-predicate or device probe.

## Verification

- **Before fix:** the new regression failed after its two-second timeout, with
  owned-child cleanup completed; the failed log is retained.
- **After fix:** the FIFO regression passed under pinned Python 3.9.
- **Focused suite:** all 38 methods passed without errors/failures/skips. All 32
  families, 1,054 mutation actions and 1,837 explicitly counted checks reproduce.
  The ledger SHA-256 remains
  `cef30ce70a1f9ff3a4a31528796dfb5034e770dc13c7d94bcb244e4516ccfca3`.
- **Canonical suite:** 541 methods passed, including the new FIFO test. Its
  existing cached Rust host fixtures remain distinct from native diagnostics.
- **Preservation:** 52 protected Python bindings, 132 historical entries,
  30 proposal bindings and 11 proposal-QA bindings verified. Complete 155/24-word
  replay, valid regular policy loading, existing invalid-file rejection and
  FAILED synthetic-negative handling remain covered by the focused suite.

Logs, commands and receipts are under `build/linker-parser-v2/fifo-fix-r2/`.
The new implementation lock binds current source, tests and retained results.
The extra FIFO test is not folded into the old 1,837-check ledger or reported as
another reviewed regression family.

## Historical identity and limits

R1 source, lock, review, results and QA checkpoint remain unchanged. R1's current
source binding deliberately no longer matches the corrected parser. Verification
does not silently refresh R1 or treat it as current-source admission: its old
parser row is explicitly checked against the retained R1 source snapshot; the
other 26 R1 implementation bindings and all 20 R1 QA bindings still match.
The preservation receipt records that archival mapping. The R2 parser difference
is independently checked to be exactly the added open flag.

No full 245-input/six-inventory/compiler rehash, Android/Kotlin build, native
bridge integration, Clang/linker probe, resumed case, permission change or Git
mutation occurred. Access-1 remains **FAILED / INCONCLUSIVE**, case two
**NOT_RUN**, original grant consumed. **P2 remains incomplete; semantic search
stays disabled.** Later bridge work requires its separate scope and review;
native collection requires its reviewed freeze, admission and authorization.
