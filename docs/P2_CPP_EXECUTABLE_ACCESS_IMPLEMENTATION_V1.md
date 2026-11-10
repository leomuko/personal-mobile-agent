# Synthetic executable-access diagnostic: implementation status

2026-11-07. **Host implementation only; no native collection authorized.** This
implements the separately reviewed two-case proposal without changing historical
executors, real-linker validators or either failed native attempt.

## Implemented boundary

The new package `scripts/diagnostics/linker_executable_access_v1/` owns a separate
recipe, marker reader/preparer, closed diagnostic parser, coordinator, freeze
builder and read-only host-request constructor. It imports unchanged utilities
for full input/inventory verification and bounded process-group ownership.

Private preparation exclusively writes the exact 53-byte marker as a single-link
regular file0555. Its receipt binds bytes, mode, owner, inode/device, timestamps
and every parent's mode/owner/inode/device. No-follow descriptor-relative opens
and stable pre/post identities reject aliases, substitutions and tampering.
Failures retain partial inputs. Identity pinning is not OS-enforced immutability:
no concurrent input writer is assumed, and transient replacement cannot be ruled
out by pre/post checks. No access predicate or target execution is used in prep.

The two native argv lists and environment are identical. The positive profile
adds only the marker's literal execution exception; both profiles deny real ld.
That exception is an actual capability increase, not a check-only operation.
The marker must never run: exact `-###` arguments, denied helpers, inert bytes
and input controls are mandatory. No shell, shim, new compiler or flag fallback
is introduced. Preparation and host tests do not exercise Seatbelt dispatch.

A completed exact negative output may be analyzed diagnostically, but the command,
worker and terminal outcome stay FAILED. The first nonzero/unexpected positive
result stops case two. No code path publishes acceptance or qualifies the real
linker. The parent reclassifies raw captures after confirmed worker cleanup and
readmission. A missing, incomplete or inconsistent report cannot become evidence
of a successful comparison. Uncertain cleanup/publication quarantines admission.

The whole decision retains 300 seconds, per-command 30 seconds, separate cleanup
15 seconds, existing byte/RSS/manifest bounds, 1 GiB small-slice preflight and
unchanged 18 GiB analysis floor. Read-only admission validates the complete new
freeze, baseline identities, six inventories, marker and genuine QA review.
Printing a command-scoped outer request grants no permission and runs nothing.

## Development review and remaining gate

Preliminary independent inspection found a missing top-level worker-status check.
Before freezing, the parent was tightened to derive expected terminal status from
raw exits; forged collected/unknown summaries now fail regression tests. The
preliminary report is retained separately and is not final implementation QA.

Host tests, the exact freeze, independent review and admission evidence are
recorded separately under `build/linker-executable-access-v1/` and
`planning/fixtures/linker-executable-access-v1/`. Final numerical results and
hashes belong in the subsequent reviewed readiness record; this document does
not predict them or authorize execution.

An initial test-development run had two mock-routing errors (the stub intercepted
historical lock reads); restricting the stub to the two in-memory unit paths
fixed the fixture. These were not native results or product defects. Original
host tests, historical locks and guards were not altered.

A future run needs the independently reviewed exact freeze, fresh attempt,
read-only admission and separate execution authorization. The old grants are
consumed. Even a contrasting result would support policy-sensitive validation
for this fixture only; the original real-linker cause remains unproved.
Compilation, Bazel/runtime builds and device workloads remain outside scope.
P2 is incomplete and semantic search stays disabled.
