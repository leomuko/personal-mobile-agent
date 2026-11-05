# Dawn qualification cleanup-exception follow-up v1

The failure-recording gap is fixed in the public qualification boundary. It now
catches `subprocess.SubprocessError`, including cleanup `TimeoutExpired`, and
retains a failed terminal `outcome.json`. If the process owner did not return,
`workerOutcome`/`cleanupConfirmed` remain null and `cleanupStatus` is explicitly
`UNCERTAIN`. No accepted or pending acceptance is published. This does not claim
that an uncertain actual process was successfully stopped.

The focused regression injects TimeoutExpired, CalledProcessError and generic
SubprocessError into the owner boundary: one owner call, public exit1, failed
terminal evidence, no acceptance. The retained synthetic probe reproduces the old
83dec19 source escaping TimeoutExpired with an attempt directory but no outcome,
then verifies all three current failure outcomes. No worker/vendor/network command
runs in these tests; owner execution is mocked.

Fresh verification: **258 host tests passed**, including61 focused Dawn tests,
zero failures/skips. Prior deadline/publication, artifact, origin, staging and
metadata checks remain in the suite. `git diff --check` passes.

Historical preservation: all50 result bindings verified against49 committed files
at83dec19 and the unchanged held C++ draft; all4539 recorded evidence entries remain
unchanged. Only the current qualifier and its regression test differ from that
snapshot. The successful Python/generation run remains historical supported
evidence. Old execution freezes reject this changed controller; no source hashes,
freezes, approvals or campaign records were refreshed to make them executable.

Raw probe outcomes/logs and preservation results are retained under
`build/dawn-cleanup-evidence-v1/`; the separately versioned result lock is under
`planning/fixtures/dawn-cleanup-evidence-v1/`. Independent follow-up review is
pending; its attestation will be attached without rewriting this execution snapshot.

The next bounded proposal is
`planning/P2_ABSEIL_CPP_QUALIFICATION_PROPOSAL_V1.md`. Static data-only inventories
pin the exact Abseil archive declaration/root patch and local compiler, SDK,
resource and raw native-library bytes. Abseil payload/license validation, native
inspector/runtime closure, root patch/rewrite semantics, harness review and actual
C++ tests remain BLOCKED/NOT_RUN. The unreviewed draft harness stays uncommitted.
No archive download, compiler/vendor execution, root adoption, Bazel retry, SDK
build, emulator/S24 workload or commit occurred in this follow-up.

P2 remains incomplete; semantic search is disabled. The18GiB analysis floor,
emulator-first order and deferred S24 testing are unchanged.
