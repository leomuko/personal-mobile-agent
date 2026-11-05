# Backend metadata execution — preflight rejection

**Execution BLOCKED before the owner or any native query started.** The explicitly
authorized single launch used the reviewed R1 freeze, but its input list omitted
one dependency required by the actual admission function. No retry occurred.
P2 remains incomplete; semantic search stays disabled.

Freeze SHA256:
`829f3285fecc43577e072c415e3ced8a1122d15c8b9f46cf2f57f3293f76e2b4`.
Bound review SHA256:
`bac80ac01111de46e32ea2cb0d5baecf4934aa08795fd8e1579024405620403c`.

## Actual result and cause

The controller returned exit1 with exactly
`ValueError: unbound metadata code dependency`.
The returned tool-wall duration was0.142650583s; this is not a native query/runtime
measurement. Its stdout was empty. No owner marker, child trace, stderr file or
metadata-2 directory was created. All31 queries are NOT_RUN; no native query failed
because none began. Cleanup is NOT_APPLICABLE, not uncertain or newly confirmed.
The earlier metadata-1 failure and its confirmed cleanup remain unchanged.

A subsequent read-only evaluation of the actual required-input set identified the
single missing direct binding:
`planning/fixtures/dawn-cpp-toolchain-v1/observed-inspector-helper.json`.
It is1,247 bytes, SHA256
`ad2b8246d698bc622166455033e9ba82621ce4719ddd78f9ab8bc2d9cb75fe76`.
Its bytes already existed in historical evidence, but the guard requires a direct
entry in the execution freeze. Neither indirect provenance nor the previous QA
PASS overrides that condition. The prepared R1 bundle was incomplete for execution.

The diagnostic did not invoke main/admission/owner again or retry the workload.
`admission-r1-failure.json` retains the exact tool-return text and classification,
explicitly distinguished from an owner-generated stderr artifact.
`admission-r1-required-input-diagnostic.json` records the missing-set evaluation.
`build/dawn-cpp-backend-v1/metadata-2-launch.json` retains the exact authorized argv.

## Correction prepared without a native retry

R1, its review, implementation and old failure evidence remain unchanged. R2 adds
the missing known proof-file binding, the regression source and this failure's
provenance. No command, helper/profile, output validator, toolchain, resource
ceiling or implementation source is changed. The metadata-2 identity remains fresh.

Two unit regressions exercise the real admission function: R1 rejects before input
checks/owner, and adding the direct binding reaches input verification. Their
review/input readers are synthetic only; no synthetic approval is written as an
attestation. A positive canonical read-only admission check using the actual R2
freeze and genuine independent review must pass before declaring the correction
ready. That check must not invoke main, owner, dispatch or a native tool.

Fresh host verification:343 tests passed without failures/skips;23 focused backend
tests passed. These checks are corrective preparation, not an execution retry.
Independent review of the failure and corrected freeze remains pending here.

## Boundary

R2 is a new exact freeze, not permission to resume the rejected R1 launch. Its
scope remains31 metadata queries with300s decision/15s cleanup/30s per query. A
new native invocation needs separate authorization after review and the canonical
read-only admission check. Do not weaken the missing-dependency guard, overwrite
old receipts, silently retry, or substitute another profile.

No native inspector/backend/compiler query, C++ case, linker/binary, SDK/Bazel/device
work or commit occurred. All72 C++ cases remain outside this scope. The18GiB
analysis floor, emulator-first direction and deferred S24 testing are unchanged.
