# Synthetic executable-access comparison: access-1 results

2026-11-07. **FAILED / INCONCLUSIVE.** The single authorized invocation stopped
after its first case. Cleanup is **CONFIRMED**; no acceptance was published.
The second case remains NOT_RUN. No retry or original-plan continuation occurred.

## Authorization and exact scope

The user explicitly replied `proceed` to the request to run the frozen two-case
diagnostic once. Fresh canonical preflight matched all **245 inputs, six
inventories and 28 readiness bindings**, with the marker unchanged and attempt
absent. The submitted request matched the prepared payload. The command-scoped
`require_escalated` launch was accepted as host session **71004**, using `/bin/zsh`,
`login=false`, the exact reviewed working directory and no persistent rule.

The unchanged freeze is
`32182dffa560676d2dda8a52a4bbbed0983ab68f3ab99ebcf98fe9db0913a9fc`;
the genuine implementation review is
`d2e2439d0f376f681f0023f9149adf0b0a097f4cc61ffe540a902ba90d3bcc2a`.
The declared command/environment, real-linker denial, positive-first ordering,
300-second decision window, 30-second case limit and separate 15-second cleanup
allowance remained unchanged. This one-run grant is consumed.

## Retained observations

| Case | Native capture | Frozen validation | Outcome |
| --- | --- | --- | --- |
| Synthetic exception present | Exit 0; two printed jobs, with the second naming the exact synthetic target | Rejected `-Wundef-prefix=TARGET_OS_` as an unfrozen frontend option | FAILED |
| Synthetic exception absent | No invocation | Not evaluated | NOT_RUN |

The exact failure is:

```text
ValueError: unfrozen frontend option: -Wundef-prefix=TARGET_OS_
```

This is a **closed-parser rejection of generated driver output**, not a nonzero
Clang result or a recorded invalid-linker diagnostic. The raw header identifies
Apple Clang 21.0.0 (`clang-2100.1.1.101`). Its 5,622-byte raw output contains the
literal synthetic supplier, but that observation is not a qualified positive
result. The parser stops at its first unlisted option; subsequent defaults have
not been qualified. Adding only this token is not established as sufficient.

The worker report remains FAILED with comparison INCONCLUSIVE. The parent
independently replayed the retained output and rejected the same option, recording
`DIAGNOSTIC_FAILED_OR_REVOKED`, cleanup CONFIRMED and real-linker qualification
false. No output was repaired or reclassified under a changed validator. There
is no positive/negative contrast and no policy-coupling qualification credit.

## Timing and resource scopes

| Measurement | Retained value | Scope |
| --- | --- | --- |
| Parent elapsed | 37.190544 seconds | Parent start to failed terminal-record construction; includes admission, owned work and readmission |
| Owned-worker elapsed | 12.913006 seconds | Owner's recorded worker/cleanup interval |
| Command capture | 0.025769 seconds | Bounded command capture only; excludes surrounding identity and parser validation |
| Peak sampled worker RSS | 112,443,392 bytes | Worker `ru_maxrss`, below the frozen 512 MiB ceiling |

These intervals are not additive. Cleanup was confirmed, but its duration was
not separately isolated. A whole host-tool launch-to-return duration was not
separately measured. No timeout or memory-limit rejection was recorded.

## Evidence and next boundary

Raw files remain under
`build/linker-executable-access-v1/native-attempts/access-1/`: owner marker,
worker logs, empty source, unmodified command trace, diagnostic report and terminal
outcome. The root evidence directory retains preflight, user authorization,
exact submitted request, host invocation and post-run preservation records.
`planning/fixtures/linker-executable-access-v1/access-1-result-lock.json` binds
this report and retained producer evidence. Independent results QA is recorded
separately; it does not alter this attempt or the historical readiness record.

The next bounded investigation is **host-only review of the retained full driver
output against the closed grammar**, covering all unmodeled defaults and their
consumed semantics. Any parser-contract correction needs separate versioning,
regressions, review and a new freeze before a newly authorized collection. Do not
relax the old validator, change warning policy, suppress generated options, resume
case two, or repeat this unchanged attempt. This report authorizes none of that
work automatically.

No source, compiler flags, profiles, dependency pins or Git history changed during
collection. No compilation, Bazel, runtime/model or device workload was invoked.
The original real-linker rejection's cause remains UNPROVED. P2 is incomplete;
semantic search stays disabled.
