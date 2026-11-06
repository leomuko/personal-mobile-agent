# Fresh linker-2 launch

This namespace preserves R2 and linker-1. Its worker, dispatch, deadlines, ownership,
quarantine, cancellation and publication coordinator are unchanged copies of the
reviewed R2 functions. `composition.json` and the invariant tests enumerate that
equivalence. New admission verifies the genuine launch-amendment review, retained
failure evidence and exact fresh-path projection; no old module globals or
archival root is substituted.

`freeze.py` is data-only and exclusively creates the fresh launch freeze. Genuine
implementation QA bound to its hash is required before `qualify.py` can admit work.
The exact CLI flags remain `--freeze`, `--sha256`, `--review`, `--review-sha256`
and `--attempt`, using pinned Python with `-I -S -B`. The attempt must equal the
new frozen contract, not an arbitrary caller-selected directory.

The external `exec_command` request must specify `sandbox_permissions=require_escalated`,
`shell=/bin/zsh`, `login=false`, the reviewed workdir and exact resolved argv,
without a persistent prefix rule. Its two hash arguments come from the reviewed
freeze and genuine implementation review. Record the submitted tool request and
approval outcome; the Python worker cannot prove its own outer permission level.
No launcher tries another permission mode or retries automatically on denial.

The data-only `launch.py` prepares the exact `exec_command` payload after actual
admission, resolving only the freeze/review hash placeholders. It rejects a
consumed attempt and writes only the declared request record. It never submits
the request or claims approval. The host must submit the recorded payload and
retain the real permission/exit outcome separately. No broad Python prefix rule
is part of that payload.

The unchanged inner compiler-only sandbox permits only pinned Clang execution,
denies linker execution/network and restricts writes to linker-2 plus `/dev/null`.
Only its attempt path, command input/output paths and HOME/TMPDIR change. All
seven command IDs and all tool/flag identities, limits and validators stay fixed.

Run once after approval, stop at first failure, retain raw output, remaining
NOT_RUN states, cleanup and publication independently. Success exit 2 means only
dry-run linker-selection agreement, not C++ or Android qualification. Do not
resume linker-1, change global permissions, compile, invoke Bazel or use devices.
P2 is incomplete and semantic search remains disabled.
