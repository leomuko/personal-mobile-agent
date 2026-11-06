# Reviewed linker metadata executor v1

This executable is intentionally separate from the frozen preparation namespace.
Its presence is not permission to run it. Only the approved seven-probe recipe is
supported; no compiler, linker, SDK build or model workload ran during host QA.

`freeze.py --output <absolute execution-freeze-r1.json>` performs data-only checks
and exclusively writes the new freeze. The input set retains all proposal bindings
and adds this implementation, tests, design contracts and host evidence. No owner,
worker, sandbox or native tool is started by freeze creation or `admission(...)`.

After genuine independent review and canonical read-only admission PASS, a separate
user authorization is still required before invoking `qualify.py` with exact
`--freeze`, `--sha256`, `--review`, `--review-sha256` and `--attempt` arguments.
Use the frozen pinned Python with `-I -S -B`. No convenience launcher bypasses
the review or changes an immutable attempt identity. Parent success exit 2 means
dry-run selection only, never complete compiler qualification or product readiness.

The existing owned worker handles process-group stop/cleanup. The old compiler
dispatch receives a copy whose role is compiler; all original argv, sandbox bytes
and output validation stay unchanged. No ld, shell, inspector or directory exec
allowlist is added. The worker writes a bounded raw trace and per-query statuses;
the parent requires confirmed cleanup and a complete validated report before
time-checked public hardlinks. Cleanup exceptions retain failure evidence and
quarantine. Uncertain publication is distinct from uncertain native cleanup.

No output repair, permission probe, implicit retry or remaining-query continuation.
All whole/per-query deadlines, cleanup allowances and resource/storage limits are
unchanged. The broader model compatibility and benchmarking plans are design work,
not new model support. P2 remains incomplete; semantic search remains disabled.

R2 preserves R1's rejected freeze and code snapshot, explicitly quarantines any
`publicationUncertain` outcome even with no public link, and binds the approved
build-skill guidance/routing additions. Its freeze builder now writes only the
fresh `execution-freeze-r2.json`; it cannot overwrite R1. Guidance does not change
the compiler recipe, native acceptance or dependencies. R2 still needs independent
PASS review and canonical admission before separately authorized execution.
