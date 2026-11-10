# Proposed executable-access comparison v1

2026-11-06. **Proposal only. No implementation, target preparation or native
execution is authorized by this document.** Preserve linker-1, linker-2, all
existing flags/profiles, strict validators and failed evidence unchanged.

## Question and minimal comparison

Does changing one execution-policy rule change the actual pinned Apple driver's
acceptance of the same absolute `--ld-path` value? Static evidence supports this
hypothesis but does not prove the branch taken by the Apple binary.

Propose **two controlled dry-runs**, not another seven-probe attempt. Both use
the same pinned Clang, the existing ordinary control C++20 flags, `-###`, a fresh
empty source/output identity, and an absolute path to one identity-pinned synthetic
eligibility target. Both commands and environments are identical; only the
synthetic target's sandbox exception differs. Actual `ld` remains denied in both.
The exact proposed data/argv/profiles are in
`fixtures/linker-path-validation-v1/proposal.json` and need independent review
before adoption into an implementation/execution freeze.

The target is a small, fixed ASCII data marker with mode 0555, with no executable
format magic or interpreter directive. It is **not** a copy, alias, shim or wrapper
for the real linker. Pin its complete bytes, hash, mode, owner, single-link regular
file identity and all parent/alias identities. Place it outside the inner writable
output subtree. Require private preparation, unchanged pre/post identities and no
symlinks or extra inputs. It must never be launched. The only native work is Clang
planning; reject generated objects/binaries and unexpected helpers or output.

Identity pinning is not OS-enforced immutability. The coordinator must leave the
private input unchanged and native writes are denied there. An owning process
can still replace it; pre/post checks cannot exclude transient replacement. The
protocol assumes no concurrent input writer, aborts on observed drift and must
retain that limitation. Invalid contents and denied shells add constraints; they
do not replace the reviewed planning-only behavior that forbids launching it.

## Ordered cases and stopping

1. **Synthetic exception present:** compiler-only profile plus one literal
   `process-exec` exception for the identity-pinned inert target. This is an explicit
   proposed policy broadening for that synthetic file, **not** a check-only
   operation. No exception is added for real `ld`, directories, shells or Python.
2. **Synthetic exception absent:** the same profile without that one exception.
   Both still deny real linker execution and network access.

The positive-control case comes first so existing stop-on-nonzero handling can
remain intact: if it fails, case 2 is NOT_RUN. If case 2 returns a nonzero result,
stop immediately and retain the failed terminal record. Do not convert it into
native acceptance. A separate post-run analysis may compare the preserved pair;
expected negative evidence is not repaired or suppressed. No fallback or retry.

Interpretation is frozen before collection:

| Observed pair | Conclusion allowed |
| --- | --- |
| Exception-present selects exact synthetic path; exception-absent rejects that path | Supports policy-sensitive validation for this Apple driver/fixture |
| Both select the synthetic path | Does not support the proposed coupling in this comparison |
| First case rejects, launch fails, or outputs do not match the closed diagnostic contract | Incomplete/inconclusive; second case NOT_RUN on first failure |
| Second case fails for a different reason | Inconclusive; preserve the actual reason |

A positive contrast still does not uniquely prove why the original real-linker
invocation failed. It does establish whether this option's validation can depend
on the execution rule for an otherwise identical file. Do not claim source/binary
equivalence, read-vs-execute errno, an exact Seatbelt hook, or actual-linker support
from that contrast. An inert file may itself be rejected by vendor-specific checks;
that outcome is retained, not worked around.

## Gates and boundaries

First review this proposal. Then implement the minimal separate diagnostic scope,
host-test identity/profile/stop/cleanup/interpretation guards and freeze complete
inputs, exact payload, profiles, environment and source. Obtain genuine independent
implementation QA, actual admission and separate native execution authorization.
The previously consumed one-run permission grants do not apply to this experiment.

Keep the command-specific outer exception, process ownership, cancellation,
quarantine and cleanup/publication protections. Retain the 300-second decision,
30-second command and separate 15-second cleanup ceilings, 256 KiB per-command
output, 2 MiB trace, 512 KiB outer logs, 512 MiB worker RSS, 32 MiB manifests,
256-input ceiling, 1 GiB small-slice floor and separate 18 GiB analysis floor.

Use a separate diagnostic-only classifier. Never weaken the existing real-linker
validator, publish `accepted-probes.json`, normalize bare names, change source
evidence, or qualify the seven original plans. This proposal explicitly changes
the target value for a new experiment; it does not substitute flags into an old
attempt. No actual target file or native attempt is created during preparation.
No new toolchain, artifact acquisition, linker execution, compilation, Bazel/
runtime build, model/device workload, global permission change or Git mutation is
included. P2 remains incomplete and semantic search remains disabled.
