# Root-dependency amendment status v1

2026-11-02. **Proposal prepared; complete recipe and adoption blocked.**

The amendment and dependency/patch manifests describe an FP16 target bridge that
preserves `@FP16//:FP16`, and a qualified-label Dawn registration candidate. Static
inspection found the two consumed FP16 function signatures in the existing root
headers. Numeric behavior, generated headers and Android ABI compatibility have
not been tested. Both blockers remain relevant to both ABIs.

## Completed checks and retained failure

- The source-inspection implementation passed its independent R4 review. Its
  focused suite has 13 tests; the completed full Python suite passed **172 tests**
  (`build/root-dependency-amendment-v1/host-tests-1.log`). These are host checks,
  not configured dependency or Android evidence.
- Source attempt `inspection-attempts/source-1` used freeze
  `227fa78297627071ef9d6d8e474b7ed9627e10b462c1b7d2f4fecdc043b819e1`.
  It failed at the **60,000-member archive ceiling**, exit 1, after
  **67.870962333 seconds**, with confirmed process cleanup. The generic retained
  classification is `ANALYSIS_COMMAND_FAILED`; stderr records the member-cap
  exception. No complete source receipt was published.
- GitHub's retained ref and immutable-commit fetch identify the candidate Dawn
  bytes; they do not establish complete archive/inventory qualification. The
  retained subset contains 89 files. Neither planned PyPI metadata request was
  reached after the failure, so wheel checksums remain explicitly unresolved.
- Static literal reconstruction records all three upstream commands and six
  replacement counts **1/1/4/1/1/1**, with pre/postimage hashes and a proposed diff.
  No upstream command, vendor generator or source patch was executed/adopted.

## Deliverables and remaining gates

See the root-dependency compatibility amendment, dependency and patch manifests,
implementation/test plan, storage/execution plan and proposed offline-v2 source
inspection. The latter proposes a different bounded inventory algorithm and cap;
it is **not implemented or authorized by this status report**. The failed v1
attempt remains unchanged. Do not retry it or continue its metadata requests.

Before a complete recipe: qualify the whole Dawn source, pin Python dependencies
and runtime selection, review all four callback-trait replacements, and complete
FP16 numeric/Android and generated-header interface gates. Before any new analysis:
review/adopt the complete amendment, implement and independently review guards,
resolve capacity and prepare a new freeze. About 2.7 GiB was available at the
inspection; the **18 GiB analysis preflight remains unchanged**. No evidence was
deleted or relocated.

No Bazel rerun, SDK/AAR/APK build, app dependency substitution, emulator/model or
S24 workload, or commit occurred in this amendment slice. The source-data fetch
above is recorded separately from those unrun operations. P2 remains incomplete;
emulator tests are downstream, S24 deferred, semantic search disabled.
