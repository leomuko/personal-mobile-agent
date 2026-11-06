# P2 linker-2 launch amendment v1

2026-11-06. Prepare one reviewed repeat of the same seven-probe experiment using
a command-specific outer execution exception. The failed `linker-1` and R2 source,
freeze, review and result records remain immutable. The OS denial's underlying
cause remains unproved; permission may resolve launch but is not evidence that it
will. No Full access setting, global configuration, toolchain or dependency change
is part of this amendment.

The machine-readable contract is `fixtures/dawn-cpp-linker-launch-v1/amendment.json`.
Its baseline is the exact reviewed R2 freeze. A separate namespace
`scripts/diagnostics/dawn_cpp_linker_launch_v1/` preserves the existing executor and
uses a fresh frozen `linker-2` identity. Only four path-dependent contract fields
are rebuilt with the unchanged pinned plan functions: `attempt`, `commands`,
`compilerSandbox` and `nativeEnvironment`. All other fields remain equal to R2.
The command IDs, order, tools, compiler/linker flags, validation, owner, cancellation,
quarantine and publication requirements are unchanged. Historical dependency and
result hashes remain checked; no archival view enters native admission.

The outer host request uses `exec_command` with `sandbox_permissions=require_escalated`,
`shell=/bin/zsh`, `login=false`, the project workdir and exact reviewed Python argv.
No persistent command-prefix rule is requested. It is a request for a single
command outside Codex's outer restrictions, not an inner-sandbox relaxation. The
tool's approval mechanism must permit it; failure or denial stops the attempt and
does not authorize a default-mode or unsandboxed-native fallback. A worker cannot
self-certify that this external permission was granted.

The inner profile still denies network and linker execution, allows only the
pinned Clang executable, and confines writes to the fresh attempt plus `/dev/null`.
Keep 300 seconds for the decision, 30 per query, separate 15-second cleanup, all
output/RSS/input caps, the 1 GiB small-slice floor and separate 18 GiB analysis
floor. Stop on the first failure; remaining commands are NOT_RUN. No retry or
resumption is automatic. No linker binary, compilation, Bazel, SDK/runtime build,
model or device workload is admitted.

Before native execution: independently review this amendment, implement the narrow
versioned change, test fresh-path derivation and rejection, unchanged worker/owner
behavior, review/hash/dependency guards and exact host invocation. Freeze the
complete implementation and tests, obtain genuine implementation QA bound to that
freeze, then run actual data-only admission. Resolve the two manifest hash
placeholders only from that reviewed freeze and its genuine review; do not create
fabricated reviews or self-referential hashes. Record the actual tool permission
request separately. The user's next-run instruction supplies project scope; the
host permission grant remains a separate prerequisite enforced by the tool.

Retain raw outputs, per-command results, cleanup/publication and independent results
review even on failure. Distinguish host launch success from dry-run linker-selection
agreement, and both from compiler/runtime/Android compatibility. Preserve the old
failure and do not continue its six pending commands. P2 remains incomplete;
semantic search remains disabled, emulator acceptance is downstream and S24 is
deferred. No Git mutation is authorized.
