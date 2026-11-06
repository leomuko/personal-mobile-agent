# linker-1 independent results checkpoint

2026-11-06. **Independent results-integrity review: PASS; native qualification:
BLOCKED.** No blocking evidence or execution-integrity defects were found. The
producer records remain unchanged; this is the subsequent review checkpoint.

QA verified all **17 producer bindings** and freshly ran actual data-only
admission against the unchanged R2 freeze and genuine implementation review:
**207 inputs and six inventories matched**, with no native subprocess or network
execution. The single raw trace exactly matches the frozen diagnostic command
and compiler-only sandbox. Its decoded bytes match the retained raw output.

The diagnostic failed at sandbox profile application with exit 71. All six
dry-run plans remain NOT_RUN. Cleanup is CONFIRMED, public and pending acceptance
are absent, and publication is not uncertain. No successful Clang execution or
linker-selection result is established; no timeout or compiler incompatibility
was demonstrated. The underlying reason for the sandbox denial remains unproved.

One nonblocking P3 timing-label finding is addressed by the separate
[erratum](P2_CPP_LINKER_EXECUTION_LINKER1_ERRATUM.md). The 0.012186625-second trace
value measures command capture, excluding surrounding alias checks; the original
report and JSON are preserved. No implementation change or workload rerun was
needed.

Independent evidence is retained in
`build/dawn-cpp-linker-executor-v1/qa-results-linker-1/`, including `review.md`,
`review.json`, `snapshot.json`, the complete result audit and fresh input audit.
The fixture `linker-1-qa-checkpoint.json` binds these records separately from the
immutable producer result lock.

This single authorization has been used. Preserve `linker-1`; do not resume the
six plans. A repeat needs a reviewed launch profile, fresh reviewed attempt
identity and separate authorization. The inner sandbox, linker execution denial,
acceptance and resource limits remain unchanged. No further probes, compilation,
Bazel/SDK/runtime builds, model/device work or Git mutations occurred. P2 remains
incomplete; semantic search is disabled and emulator/S24 qualification remains
outside this result.
