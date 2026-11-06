# linker-2: sandbox launch succeeds, explicit linker plan fails

2026-11-06. **Host launch succeeded; linker-selection qualification remains
BLOCKED.** This producer report preserves the single reviewed repeat. P2 is
incomplete and semantic search remains disabled. Independent results review is
recorded separately; it does not replace this report or earlier failures.

## Reviewed launch and actual permission

New execution freeze SHA-256:
`cd30922e6d7d250de7e6af5a17fe2baa6e94b70e42858bbf589569db7a04a812`.
Genuine implementation review SHA-256:
`939f63ec9c44d3abdfd01dc53bda348a5568802e5f4210cc8a8b3a5357d4f52e`.
Independent implementation QA passed 37 focused/441 canonical host tests,
225 inputs and six inventories. Those host results preceded native execution;
they are not linker qualification.

The declared data-only launcher performed actual reviewed admission and prepared
the exact host request. The submitted `exec_command` payload used
`sandbox_permissions=require_escalated`, `/bin/zsh`, `login=false`, the reviewed
workdir/argv and no persistent prefix rule. The host tool accepted that request
and started session 90058. The executor was invoked once; its final exit was 1.
The real request and tool outcome are retained separately from the generator's
`approvalGrantedByGenerator=false` record. No global permission setting changed.

The inner sandbox remained compiler-only: only pinned Clang execution, network
and linker execution denied, writes restricted to linker-2 plus `/dev/null`.
Only the four reviewed path-dependent fields changed from R2. Original R2,
linker-1 and its producer/QA records remain unchanged.

## Per-command outcome

| Command | Result |
| --- | --- |
| Diagnostic program lookup | PASSED, output `ld`; explicitly unqualified |
| Control ordinary C++20 explicit dry-run | FAILED, Clang exit 1 |
| Remaining five exact plans | NOT_RUN |
| Cleanup | CONFIRMED |
| Public/pending acceptance | Absent; publication not uncertain |

The diagnostic lookup returned exit 0 and exactly `ld\n`. Its bare name earns no
linker-selection credit. The next command reached Apple Clang 21.0.0, which emitted:

```text
clang: error: invalid linker name in argument '--ld-path=/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/ld'
```

Clang also printed a `-cc1` plan and a linker plan headed by bare `ld`. The command
exited 1, so neither constitutes an accepted dry-run result. Do not normalize that
bare name into the pinned absolute path, repair output, or count it as successful
fallback. The complete raw output is preserved verbatim.

Successful Clang launch and diagnostic output demonstrate that the reviewed host
profile can apply the inner sandbox in this attempt. This is distinct from the
earlier `sandbox_apply` denial. The exact cause of that earlier denial remains
unproved; changing outer mode and fresh attempt identity is not a complete causal
investigation. The reason Clang rejected the absolute linker value is also
**UNPROVED**. No permission probe, unsandboxed native comparison or flag substitution
was authorized or performed to explain it.

## Timing, stopping and cleanup

- Lookup command capture: **0.025124292 s**; full successful lookup query:
  **0.18619799999942188 s**. Capture excludes surrounding alias/profile checks.
- Failed dry-run command capture: **0.012921833999998356 s**. Full failed-query
  duration is not recorded; do not label capture as total dispatch time.
- Owned worker transaction: **13.558126291999999 s**, including its owner setup,
  collection and cleanup.
- Parent terminal elapsed time: **26.280072000001383 s** from its own decision
  start, including admission and owned work. Request approval/preparation and
  separate read-only audits are outside this clock. Exact cleanup duration is
  not separately recorded.

No timeout was demonstrated. Keep the existing 300-second decision, 30-second
query and separate 15-second cleanup rules. Trace size is 10,452 bytes; recorded
peak worker RSS is 108,675,072 bytes, within the frozen limits. These are host
diagnostic timings, not compiler throughput or device performance measurements.

The first unexpected failure stopped the run. Exactly two commands were traced;
five remained NOT_RUN. Cleanup and failed qualification are independent: the
generic parent message says “worker failed or cleanup unconfirmed,” but this
attempt has a failed worker and **confirmed** cleanup. No acceptance was published,
no retry or continuation occurred, and no compilation or actual linker execution
was performed.

## Retained evidence and next boundary

- `build/dawn-cpp-linker-launch-v1/launch-request.json`: exact prepared request,
  with approval explicitly not self-certified by the generator.
- `build/dawn-cpp-linker-launch-v1/execution-linker-2/`: prior project-scope record,
  actual submitted tool payload/result, parent log, decoded raw command outputs,
  structured result and post-run input verification.
- `build/dawn-cpp-linker-v1/probe-attempts/linker-2/`: owner marker/deadline, original
  raw trace, stdout/stderr, probe report and terminal cleanup/publication outcome.
- `planning/fixtures/dawn-cpp-linker-launch-v1/linker-2-result-lock.json`: producer
  binding record; independent results QA is retained separately.

The single next-run grant has been used. Preserve both attempts. Any additional
native investigation needs its own reviewed scope and authorization; do not
resume the remaining plans or relax linker execution denial. A useful next
question is how the pinned driver validates the requested executable under this
profile, not another identical rerun. No diagnosis or remedy is adopted here.

Compilation/C++ matrix, Bazel/configured graphs, runtime builds, emulator/S24/model
workloads, global security changes and Git mutations remain outside this result.
No app data, dependency, toolchain, strict validator or memory policy changed.
