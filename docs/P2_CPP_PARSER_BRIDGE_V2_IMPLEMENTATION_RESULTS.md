# V2 diagnostic bridge: host implementation results

2026-11-10. **Producer host verification: PASS.** Independent implementation QA
and genuine read-only admission are recorded separately. This is not native
collection evidence or authorization.

## Implemented path

The separate `scripts/diagnostics/linker_parser_bridge_v2/` package derives the
worker and parent parser context from the exact execution-freeze contract. V2
validates each worker capture. After confirmed owner cleanup and readmission,
the parent independently parses raw trace bytes and checks context, argv/profile,
order, exit code, command/terminal statuses, digest, summary, timing bounds,
comparison and not-run cases. Modified or incomplete records cannot become a
completed comparison. Qualification/publication flags require literal false.

The inherited owner, interruption handling, marker pre/post verification, resource
limits, quarantine and first-failure stopping remain. A negative second case stays
FAILED in the command, worker and terminal record even when its comparison is
diagnostically useful. Both-selected is a diagnostic collection, never native
acceptance. No result qualifies real ld, and no acceptance publisher exists.

The exact plan is the historical two-case recipe with only owned attempt paths
rebased to `access-v2-1`. Neither flags nor sandbox permissions were added. Real
ld stays denied. The first profile's existing single inert-marker exception is
a genuine execution permission, not a check-only predicate. No collection has
occurred under it in this slice.

## Host verification

| Check | Result |
| --- | --- |
| Admission/freeze regressions | 8 methods passed |
| Worker, parent replay and fake-owner regressions | 23 methods passed |
| Combined pinned Python 3.9 bridge checks | 31 methods passed; no failures/errors/skips |
| Canonical host suite | 572 methods passed |
| Read-only freeze preparation | 246 inputs, six inventories and static native supplier graphs verified |

Parameterized tests cover context/trace substitution, changed raw words and
digests, invalid exit codes/statuses, reordered/extra/missing traces, wrong
profiles, injected fields/helpers, truncation, symlinks/FIFOs, invalid timing,
admission failures, interruption, deadlines, first-failure stopping, source
substitution, uncertain cleanup, cleanup exceptions, readmission failure,
owner/report mismatch and quarantine. Expected synthetic negatives remain FAILED.
Native dispatch, owner process creation, marker mutation and native-row inspection
are mocked or forbidden in these worker tests. Full native-row inspection during
freeze preparation is static byte/Mach-O reading, not tool execution.

Tests create only owned synthetic temporary contexts and remove them afterward;
the proposed `access-v2-1` attempt remains absent. Their full-format transcripts
are rebased test fixtures, not fresh Apple-driver captures. Fake owners and clocks
prove host logic only. The existing cached Rust host fixtures in the canonical
suite are separate from new Clang/linker/access probes, of which there were zero.

The delegated regression worker was closed without a delivered source checkpoint;
the parent implemented the 23 methods. That handoff record is retained and is not
represented as independent QA.

## Freeze and marker identity

`planning/fixtures/linker-parser-bridge-v2/execution-freeze-r1.json`:
SHA-256 `e1b6a4fe27a97cba45fe932ae181a4292ec7b6a9ea9c2932524d5ee065c4e8f8`.
It binds the reviewed R2 parser and policy, inherited 225-input baseline and new
dependencies within the unchanged 256-input ceiling. The 300/30/15-second
decision/query/separate-cleanup limits and other resource floors/caps are intact.

The original marker receipt reports filesystem device 16777231; current read-only
inspection reports 16777234 for the file and all nine parents. Every other field
agrees. Cause remains unproved. The old receipt and marker were not changed.
A separate receipt captures the complete current identity and binds the old
receipt hash. Snapshot preparation rejects any other drift or inconsistent
device mapping. Admission and dispatch require exact new-receipt equality;
this does not prove OS-enforced immutability or exclude transient concurrent
replacement. The existing no-concurrent-writer limitation remains.

## Preservation and retained development results

The preservation audit verified 52 protected Python bindings, 132 historical
entries, 30 proposal bindings, 11 proposal-QA bindings, all 245 historical Access-1
input rows, the 25 current parser R2 bindings and 11 parser R2 QA bindings. R1's
superseded parser row is explicitly resolved to its retained source archive, not
admitted as current code. No old record or freeze was refreshed.

An initial preservation helper incorrectly reused its 32 MiB manifest reader for
large already-pinned native payloads and failed before publication. Its source and
failure record are preserved. The subsequent helper streams exactly the known
row size with 1 MiB buffers and stable descriptor/path checks; it does not enlarge
the protocol or admit unbound files. Its passing receipt is `preservation-r2.json`.

Logs, receipts, composition patches and source snapshots are under
`build/linker-parser-bridge-v2/`. No native collection, permission changes, builds,
device operations or Git mutations occurred. **Access-1 remains FAILED /
INCONCLUSIVE, case two NOT_RUN and grant consumed. P2 is incomplete; semantic
search stays disabled.** Separate reviewed launch authorization remains necessary.
