# Complete-header toolchain metadata v1

This isolated path executes only reviewed native inspector queries, compiler
version/resource/helper queries, `-###` dry runs and empty-input `-E -v` include
searches. It never compiles, links or executes the C++ qualification matrix. A
hash-bound PASS review for the exact metadata freeze is required before execution.
No dependencies, global Java/Python/Xcode settings, historical freezes or vendor
headers are changed. The original held harness is preserved.

Every native command uses pinned paths, an empty inherited environment except the
frozen PATH/LANG/LC_ALL and owned HOME/TMPDIR, a network-denied/write-restricted
sandbox, and process-exec admission of only its exact compiler/inspector payload.
The linker is inspected and planned by `-###`, never executed. Unknown native
suppliers, tool selections, target/sysroot/resource paths, response files, extra
jobs, diagnostics and include-order changes fail closed. The actual future compiler
campaign still requires its own reviewed runner/freeze and helper enforcement.

The original absolute POSIX deadline is300s including admission/revalidation and
publication, with15s separate cleanup and30s per command. Logs are256KiB each and
2MiB total; outer worker logs512KiB. SDK/resource read limits are100000entries/2GiB
per frozen tree, combined bound is checked by the concrete freeze review; native
files128/1GiB and OS caches16/8GiB. RSS512MiB, manifests32MiB and small admission1GiB
remain distinct from the unchanged18GiB Bazel-analysis floor. No automatic retry.

Successful exit2 denotes metadata-only qualification with confirmed cleanup.
Uncertain cleanup is retained and quarantines subsequent work. Pending/public
links are tracked before creation and revoked only by matching owned identity.
The output records worker/cleanup time separately from parent decision duration.
One failed command stops the scope; remaining command IDs are NOT_RUN.

The complete-header campaign is separately specified as six positive compiles,
six binary runs and twelve isolated negatives, followed only when eligible by48
explicit-template diagnostic supplements. None are admitted by metadata PASS.
No universal callback/API equivalence claim follows from finite witnesses.
P2 is incomplete, semantic search is disabled and devices remain out of scope.

R1's output policy was rejected before any native execution. Its exact sources,
freeze and counterexamples remain under the rejected-r1-source and independent
review evidence. R2 uses a closed, pre-query frontend/linker option grammar and
profile-specific input/output/include/runtime identities. Unknown options/defaults
are blocking evidence, not automatic permission to expand the accepted set.
Every output kind rejects diagnostics; identity fields are parsed structurally.
Inspector command kinds, sizes, printed fields and sections are checked against
actual pinned command bytes. Human-readable timestamp suffixes and section
reserved3 when unprinted are not independent comparisons. The complete original
transport is still hash-pinned. Compiler-rt archive and SDK runtime stub identities
are pinned as anticipated linker inputs, with no actual link/runtime credit.
