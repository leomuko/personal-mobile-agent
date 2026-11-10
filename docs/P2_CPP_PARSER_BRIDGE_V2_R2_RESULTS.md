# Diagnostic parser bridge V2: R2 host results

2026-11-10. **Producer checks PASS; independent review and read-only admission
are recorded separately.** R2 closes the strict worker-summary exit-type gap
found during R1 independent review. It does not authorize native collection.

## Review finding and correction

The R1 parent correctly parsed the raw trace's integer exit code, but its summary
dictionary equality also accepted `False` or `0.0` in the worker summary where
integer zero was required. Independent QA preserved both reproductions in
`qa-implementation-r1/summary-exit-type-repro.json`. Raw trace validation was not
bypassed, but the serialized summary schema was not exact.

R2 explicitly requires `type(summary['exitCode']) is int` before comparing it to
the independently parsed result. A dedicated regression rejects booleans and
floats and still accepts the correct integer. Another regression verifies that
the superseded R1 freeze cannot admit the changed implementation. Neither the
raw-output parser nor the command profiles/flags changed.

R1 source copies, freeze, logs, producer report and independent finding remain
preserved. The R1 freeze is superseded, not silently refreshed or given native
credit. The exact launch/admission paths now point to R2 freeze/review files.
`access-v2-1` remains a fresh absent attempt; no prior grant is reused.

## Current verification

| Check | Result |
| --- | --- |
| Pinned Python 3.9 focused bridge suite | 33 methods passed, zero failures/errors/skips |
| Worker/parent/fake-owner subset | 24 methods |
| Freeze/admission subset | 9 methods |
| Canonical host suite | 574 methods passed |
| R2 read-only freeze preparation | 246 inputs and six inventories verified; native suppliers inspected as bytes only |

The earlier R1 31/572 passing runs remain historical evidence and did not cover
the newly discovered type cases. Tests use full-format synthetic contexts,
mocked native dispatch and fake owners. Their temporary directories are owned and
removed; they establish no real native cancellation, cleanup or driver-selection
behavior. The canonical suite retains its existing cached Rust host fixtures;
zero new Clang/linker/access probes were run.

R2 freeze:
`planning/fixtures/linker-parser-bridge-v2/execution-freeze-r2.json`, SHA-256
`e5415753fb22f69537e7628aa33c95cb485a109261bdb24384dc6f6005c303f4`.
The same marker, reviewed R2 parser, source/warning policy, argument/profile
recipe, owner, resource limits and no-publication rules remain. Context is
freeze-derived; parent replay independently checks trace bytes and summary/status
correspondence after confirmed cleanup and readmission. Expected negative cases
remain FAILED. Diagnostic collection cannot qualify real ld or publish acceptance.

## Preservation and limitations

The separate current marker receipt preserves and binds the old receipt. Its
observed device-number-only difference is explicit and causally unproved; no
marker bytes or permissions were changed. All other recorded attributes must
agree, and current pre/post checks require exact equality. The existing
no-concurrent-writer and non-OS-immutability limitations remain.

Final preservation evidence is `build/linker-parser-bridge-v2/preservation-final.json`.
It checks the 52 protected Python bindings, 132 historical entries, 30/11 proposal
bindings, all 245 old Access-1 input rows, and 25/11 parser R2 implementation/QA
bindings. Superseded parser R1 is explicitly checked via its archive. The earlier
small-manifest-reader failure and its bounded streaming correction remain retained.

No collection, resumed case, permission change, compilation, Bazel/build, model or
device workload or Git mutation occurred. Access-1 stays **FAILED / INCONCLUSIVE**,
second case **NOT_RUN**, original grant consumed. **P2 remains incomplete;
semantic search stays disabled.** Later native collection still requires separate
authorization through the reviewed launch profile.
