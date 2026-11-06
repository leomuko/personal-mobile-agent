# linker-2 preparation and permission boundary

2026-11-06. Producer preparation only; no linker-2 probe or outside-sandbox host
request has been made at this checkpoint. P2 remains incomplete and semantic
search remains disabled. Subsequent reviews and execution outcomes are recorded
separately, preserving this preparation record.

The genuine independent amendment review passed and is copied byte-identically
to `planning/fixtures/dawn-cpp-linker-launch-v1/amendment-review-r1.json`, SHA-256
`b0eb588b32b74a66491232c171fb7189ebf7e61063881c3268d7242ac35be018`.
It binds amendment SHA-256
`83b545b7eea24d7a5c9bb1bf3e02708528617eaa04cd3e1ead52a5f82185b015`.
That proposal-only PASS does not itself approve the implementation or grant host
permission.

The fresh implementation freeze is
`planning/fixtures/dawn-cpp-linker-launch-v1/execution-freeze-r1.json`, SHA-256
`cd30922e6d7d250de7e6af5a17fe2baa6e94b70e42858bbf589569db7a04a812`:
**225 inputs, six inventories**, and the exact linker-2 contract. The original
207 R2 rows remain included without replacement. R2 source, original freeze,
linker-1 and all 17 producer/nine QA bindings remain unchanged.

Only four contract fields change, derived with the old pinned plan functions:
attempt, seven commands' attempt-dependent paths, compiler-sandbox write subpath,
and native HOME/TMPDIR. Flags, suppliers, command IDs/order, validators, resource
limits and all other contract fields remain equal. `composition.json` retains the
minimum-delta patch and hashes; nine critical R2 functions, including main,
dispatch, worker, cancellation, quarantine and publication flow, have identical
ASTs. New admission verifies this provenance, genuine amendment approval, the
reviewed document and retained historical evidence. Native admission receives no
archival root or monkey-patched module globals.

Host checks passed with no failures or skips:

- **37 focused regressions** under pinned isolated Python 3.9, including the 21
  mirrored R2 behavioral tests and fresh-contract/host-request cases.
- **441 canonical host tests** in the existing reference environment. This
  includes the ordinary cached Rust host-test fixture; no new compilation or
  compiler probe follows from that test result.

Focused counts overlap the complete suite. Development logs are retained separately;
the final logs and implementation are bound by the fresh freeze. No dependency,
toolchain, global permission, application code or data changed.

The data-only `launch.py` requires actual reviewed admission before generating one
exact `exec_command` payload. It resolves only the freeze and review SHA arguments,
rejects a consumed attempt, and includes `require_escalated`, `/bin/zsh`,
`login=false`, the fixed workdir and no persistent prefix rule. It never executes
or self-certifies approval. The real host permission request must match this record
and be granted externally. Denial/failure does not trigger another mode or retry.

Before any native run: independent implementation QA must approve this exact freeze,
its genuine stable review must be retained, and actual data-only admission must
pass. The user's next-run instruction supplies project scope, while the host tool
still enforces its permission decision. Keep the inner Clang-only sandbox and
linker/network denial, 300/30/15-second timing, existing output/RSS/storage caps,
single-run limit, first-failure stop and cleanup/publication rules. A repeat can
establish launch success separately from dry-run linker-selection agreement; no
C++/Bazel/runtime/Android acceptance follows. No device or Git mutation is in scope.
