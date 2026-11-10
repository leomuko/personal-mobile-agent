# Offline parser v2

Implementation of the sealed `pinned-apple-driver-output-contract-v2-draft-1`
proposal, for the single control ordinary C++20 / arm64 macOS26.5 synthetic
driver-plan profile. No launcher, native bridge, ownership manager, execution
freeze/admission API, acceptance publisher or filesystem-marker preparer exists
in this package. V1 and historical failed evidence are unchanged.

`Context(attempt, synthetic_target)` is immutable caller-supplied lexical data.
The attempt must be a canonical direct child of the sealed sample attempt parent,
with one bounded safe basename; the target must equal the sealed marker path.
The parser derives all other paths. This checks neither freshness nor marker
leases, symlinks, resource eligibility or native authorization. A future separately
reviewed caller must supply exact frozen context and perform those external checks.
Context/request labels are not ownership credentials. Offline historical and
synthetic contexts create no files or native attempts.

`parse_output(raw, context=..., case_id=..., exit_code=...)` decodes bounded strict
UTF-8, consumes the exact ordered contract, rejects unknown output and returns
immutable tokens, digest, status and non-qualifying results. The one recognized
negative diagnostic is consumed only as typed data in the parsing view; raw bytes
and their digest are preserved. The negative command and terminal statuses stay
FAILED. A positive replay is OFFLINE_STRUCTURAL_COMPATIBLE, not new native evidence
or a repaired historical result.

The sealed JSON is checked by SHA-256 before immutable atom tuples are cached.
Changing the on-disk JSON cannot change an already loaded policy; a fresh load
rejects unreviewed bytes. This is a policy identity check, not full execution-freeze
admission. The marker's identity is not OS-enforced immutability, and no concurrent
writer/transient-replacement guarantee is established by this offline parser.

Every word, form, value, count and order is checked. Only the declared frozen-context
path slots and bounded object basename can vary. The object must agree across jobs.
No input path is resolved or normalized. The parser never accepts broad warning,
frontend, feature or backend-option prefixes. Apple-specific semantics remain
unknown; exact literal containment proves no compiled effects or source/binary
match.

`compare_outputs` checks order, context, result statuses, complete token tuples and
non-qualification before returning a diagnostic relation. It rejects a second
result after a failed first case. A relation between offline/synthetic results is
not a native policy contrast or authorization. All result variants have
`real_linker_qualified`, `compiler_qualified` and
`acceptance_publication_allowed` fixed false.

Tests cover the 32 reviewed regression families with explicit per-mutation case
and assertion accounting. Test-only fake ownership/clock boundaries exercise status
consumption, cancellation, cleanup/quarantine and no-publication expectations.
They implement no bridge and establish no new runtime lifecycle evidence. Full host
checks use the documented existing reference environment; cached Rust host fixtures
remain separate from zero new Clang/linker probes. Later native collection still
requires a reviewed execution freeze, admission and separate authorization.
