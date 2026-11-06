# Seven-probe readiness after archival migration

2026-11-06. Prepared for separate execution authorization; no native execution
authorized or performed in this slice. P2 remains incomplete; semantic search is
disabled. A passing future run would establish dry-run selection agreement only.

The unchanged executable freeze is
`planning/fixtures/dawn-cpp-linker-executor-v1/execution-freeze-r2.json`, SHA-256
`0fedb46426268e487cfb18ec55a04b04b0c348db7b5ba956990d03118c0d1d56`.
Its stable independent review is `r2-freeze-review.json` in the same directory,
SHA-256 `0bc21be52c93cd02c1024ea06de46e9cffacc7b2dfa0a6774fbc341568daf0fc`.
The read-only recheck matched all 207 bound inputs and six complete inventories.
New archival test routing changes no bound input, so no replacement R2 freeze was
created. Execution must still perform its existing admission checks at run time.

Exact ordered argv, expected includes and output paths are copied without editing
from `contract.commands` into
`planning/fixtures/unicode-archive-verification-v1/proposed-seven-commands.json`.
The freeze remains authoritative. This command manifest is data, not a shell script.

| Order | Frozen command ID | Purpose |
| --- | --- | --- |
| 1 | `diagnostic-program-lookup` | `-print-prog-name=ld`; no qualification credit |
| 2 | `explicit-jobs-control-ordinary-c++20` | Explicit absolute linker, `-###` plan |
| 3 | `explicit-jobs-candidate-ordinary-c++20` | Explicit absolute linker, `-###` plan |
| 4 | `explicit-jobs-candidate-ordinary-c++17` | Explicit absolute linker, `-###` plan |
| 5 | `explicit-jobs-control-wire-c++20` | Explicit absolute linker, `-###` plan |
| 6 | `explicit-jobs-candidate-wire-c++20` | Explicit absolute linker, `-###` plan |
| 7 | `explicit-jobs-candidate-wire-c++17` | Explicit absolute linker, `-###` plan |

All seven are NOT_RUN. The intended fresh attempt remains absent:
`build/dawn-cpp-linker-v1/probe-attempts/linker-1`.
The existing compiler-only sandbox denies linker execution. Keep the 300-second
decision deadline, 30-second per-query deadline and separate 15-second cleanup
ceiling. Stop at the first failure and record remaining commands as NOT_RUN; no
automatic retry, argument changes, relaxed identity checks or continuation.

The scoped [executor agreement](../planning/P2_CPP_LINKER_EXECUTOR_V1.md) continues
to govern resource limits, cleanup, quarantine and identity-aware publication.
The small-slice 1 GiB floor and separate 18 GiB analysis floor remain unchanged.
Remaining metadata, all 72 C++ cases, root-fix adoption, artifact builds, emulator
and S24 work remain separate. A complete host baseline and preserved admission
proof do not establish native linker selection, C++ or Android compatibility.
