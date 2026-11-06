# Seven-probe linker executor — implementation readiness v1

2026-11-06. **Host implementation complete; independent implementation/admission
review pending. Native execution NOT_RUN and not authorized by this report.**
P2 remains incomplete; semantic search stays disabled.

## Implemented scope

The separate `dawn_cpp_linker_executor_v1` namespace binds the unchanged reviewed
seven-probe proposal and genuine proposal review. Only the original diagnostic
lookup and six explicit absolute-linker `-###` plans are supported. The existing
compiler-only sandbox, dispatch capture, process-group owner and closed output
validators are reused. Linker execution stays denied; no permission diagnostics,
alternate flag retry, compiler workload or continuation of metadata-2 is added.

The parent starts one monotonic 300-second decision deadline before admission;
the worker inherits that exact value and verifies its owner marker. Per-query
30-second budgets include dispatch pinning, capture, validation and artifact
checks. The separate existing 15-second cleanup allowance and all output/RSS/
storage limits remain. SIGINT/SIGTERM stop active stages. A result arriving after
the budget or cancellation cannot become accepted during cleanup/publication.

The first failed query stops the worker and lists later IDs NOT_RUN. The parent
requires a complete validated seven-row report and confirmed cleanup. Pending
receipts are retained; publication intent precedes hardlink creation and only
matching owned public links may be revoked. Cleanup exceptions remain UNCERTAIN
when the existing owner supplies no confirmed result. Uncertain publication also
quarantines admission; foreign objects are not silently deleted.

## Host verification

- Full existing reference-environment suite: **387 tests passed**, zero failures
  or skips. No dependency installation or environment replacement.
- Pinned isolated Python 3.9 focused executor suite: **20 tests passed**, zero
  failures or skips. Unit reviews are in-memory test values, not approval files.
- Regressions cover actual admission required-set checks, changed binding and
  real temporary-file hash rejection before owner creation; deadline/profile/argv
  mutations; cancellation/signals; late outputs; first-failure stopping; incomplete,
  duplicate or failed reports; cleanup exceptions/quarantine; publication races
  and identity-preserving revocation.
- Synthetic worker and coordinator tests injected callbacks instead of native
  dispatch. They do not establish Apple option behavior, runtime cleanup timing
  or a selected linker. Existing reference tests exercise their already installed
  tokenizer extension; this is not new linker/model workload credit.
- Data-only executable freeze creation passed: **176 inputs and six complete
  inventories**, preserving all 163 proposal bindings and old failures. No native
  attempt directory, owner, child or query was created by preparation.

Executable freeze SHA-256:
`e50136ebc062ba7e837c183de1fead550be777fe18ba23568f7021123b6c056b`.
The freeze binds the current executor, freeze builder, tests, logs, proposal,
review and design documents. Actual canonical admission still requires a genuine
independent implementation review for these exact bytes; no synthetic attestation
is used to bypass that gate. Its proof must instrument execution boundaries and
use real readers/hashes. This producer record intentionally precedes that review.

## Model-interchangeability design deliverables

`MODEL_COMPATIBILITY_AND_CAPABILITIES_V1.md` separates portable assistant behavior
from artifact/runtime/tokenizer/template/budget/modality/decoding responsibilities.
It preserves conversation/database/runtime ownership and the broader assistant
roadmap. Qualification is per capability and exact configuration: successful
loading or ordinary chat does not qualify citations, vision or action execution.
The existing catalog remains Gemma-specific; no generic model support is claimed.

`MODEL_CAPABILITY_BENCHMARK_PLAN_V1.md` separates retrieval, evidence sufficiency,
output/citation and resource/timing measurements. It preserves exact inputs/raw
responses, sealed data and first failures, with benchmark/integration/runtime/
model-task/unresolved attribution. A separately reviewed second configuration
with its own tokenizer/template/budget is required before interchangeability
claims. No second model is selected, acquired, installed or tested here.

## Readiness and stop boundary

Implementation and host regressions: PASS. Independent review/canonical admission:
PENDING here. Native execution, linker selection, Apple option behavior and the
cause of bare `ld`: NOT_RUN/UNPROVED. Absolute selection still checks executability
and may fail under the existing profile; no automatic permission widening follows.

After independent review and real read-only admission PASS, the exact seven-probe
run still requires separate user authorization. A future pass establishes only
dry-run selection agreement. Remaining metadata, all 72 C++ cases, runtime builds,
Android qualification and P2 acceptance remain separate. No SDK/Bazel build,
emulator/S24 workload, source/model acquisition, installation or Git mutation
occurred. The 18 GiB analysis floor, emulator-first order and S24 deferral remain.
