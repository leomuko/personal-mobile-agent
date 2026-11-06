# P2 R2 linker probes: linker-1 retained failure

2026-11-06. One frozen executor invocation was separately authorized by the user
and performed. **Native linker selection remains BLOCKED.** P2 is incomplete and
semantic search remains disabled. This producer report precedes independent
results review; that review is recorded separately without rewriting this record.

## Admission and scope

Executable freeze: `planning/fixtures/dawn-cpp-linker-executor-v1/execution-freeze-r2.json`,
SHA-256 `0fedb46426268e487cfb18ec55a04b04b0c348db7b5ba956990d03118c0d1d56`.
Stable implementation review SHA-256:
`0bc21be52c93cd02c1024ea06de46e9cffacc7b2dfa0a6774fbc341568daf0fc`.
Both are unchanged. Actual read-only admission freshly matched all **207 inputs
and six inventories**, with no subprocess or network event and no attempt created.
Available storage was approximately 4.29 GiB, above the unchanged small-slice
1 GiB floor. The separate 18 GiB analysis floor was not used or changed.

The user authorized one diagnostic lookup followed by six dry-run plans, with
the existing 300-second decision window, 30-second query windows, separate
15-second cleanup allowance and first-failure stop. The run used pinned Python
3.9 with `-I -S -B`, the exact reviewed arguments and unchanged compiler-only
sandbox. No escalation, permission change, alternate arguments or retry occurred.
The six explicit absolute `--ld-path` plans were never reached.

## Actual outcome

| Check | Result |
| --- | --- |
| Parent executor exit | 1, `PROBES_FAILED_OR_REVOKED` |
| Diagnostic lookup | FAILED; dispatch exit 71 |
| Six dry-run plans | All NOT_RUN |
| Cleanup | CONFIRMED |
| Public acceptance | Absent; publication not uncertain |
| Linker-selection qualification | Not established |

The retained raw output is exactly:

```text
sandbox-exec: sandbox_apply: Operation not permitted
```

The trace records `/usr/bin/sandbox-exec` with the frozen profile and diagnostic
Clang argv. It failed while applying the profile, with no Clang output and no
successful compiler launch established. This is an observed sandbox-launch
failure, not a linker-selection mismatch, dependency incompatibility, C++ failure
or timeout. The underlying reason for the OS denial is not proved. An interaction
with the enclosing execution sandbox is a possible explanation, not a tested
conclusion; no unsandboxed comparison or permission probe was authorized or run.

The first dispatch took **0.012187 seconds**. The owner reports **13.030691 seconds**
for worker plus cleanup. The parent terminal record reports **25.931496 seconds**
since its own decision start, including its admission and owned work. These are
different timing scopes, not compiler performance measurements. None reached a
deadline. The command trace is 803 bytes and recorded worker peak RSS is
110,329,856 bytes, within the existing limits.

The worker stopped on this first failure. No later query was dispatched, no
acceptance hardlink was published and no retry was attempted. Cleanup confirmation
is retained independently of the failed qualification. No source changes were
made to repair or reinterpret this result.

## Retained evidence

- `build/dawn-cpp-linker-executor-v1/execution-linker-1/`: user authorization
  record, exact parent command, fresh admission, parent log, decoded raw output
  and structured producer result.
- `build/dawn-cpp-linker-v1/probe-attempts/linker-1/`: owner start/deadline,
  raw base64 command trace, worker stdout/stderr, per-command probe report and
  terminal cleanup/publication outcome.
- `planning/fixtures/dawn-cpp-linker-executor-v1/linker-1-result-lock.json`:
  immutable producer bindings for this attempt and report.

Host implementation tests were not repeated: source is unchanged and the reviewed
404-test baseline remains historical supporting evidence, not a new run. No
compilation, actual linker execution, Bazel/SDK/artifact build, model or device
workload, or Git mutation occurred. Existing historical campaigns are preserved.

## Next boundary

Independent QA must review the retained attempt and distinguish evidence integrity
from qualification. Do not resume the six plans or reuse `linker-1`. Any proposed
repeat requires an explicitly reviewed host launch profile, a fresh reviewed
attempt identity and separate authorization. Keep the inner compiler-only sandbox,
linker execution denial, frozen acceptance and resource limits unchanged unless a
new decision explicitly changes them. No such change is implemented here.

Even a later seven-probe pass would establish dry-run selection agreement only.
Remaining metadata, all 72 C++ cases, configured Bazel graphs, runtime artifacts
and emulator acceptance remain later gates; S24 testing stays deferred.
