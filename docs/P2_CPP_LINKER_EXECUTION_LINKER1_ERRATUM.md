# linker-1 timing clarification

2026-11-06. Addresses QA finding `LINKER1-RESULTS-001`. This is a narrow
interpretation correction; the original producer report, JSON, result lock and
raw attempt remain byte-identical. No execution, tests or implementation changes
follow from this clarification.

In `P2_CPP_LINKER_EXECUTION_RESULTS_V1.md`, the **0.012187-second** value called
the first dispatch is the **command-capture interval**, recorded by
`catalog.py:metadata_command`. The exact value is **0.012186625 seconds**. It
excludes the alias/profile verification surrounding capture in `backend.py` and
therefore does not measure the full query or dispatch. The retained producer
`execution-result.json` field named `dispatchSeconds` refers to that same raw
trace interval, not a separately measured whole-dispatch duration.

The **13.030690959-second** owner measurement covers its owned worker transaction,
including owner setup, collection and cleanup. The **25.931495999997423-second**
parent measurement starts at the executor's own decision start. Pre-execution,
post-execution and independent data-only admission timings are separate. Exact
cleanup duration is not separately recorded. None of these values is a compiler
performance measurement.

Failure classification is unchanged: the sandbox wrapper reported
`sandbox_apply: Operation not permitted`, the diagnostic failed, six plans remain
NOT_RUN, cleanup is confirmed and no acceptance was published. Successful Clang
execution and linker selection remain unverified; the cause of the OS denial is
unproved. No retry or continuation is authorized by this erratum. P2 remains
incomplete and semantic search remains disabled.
