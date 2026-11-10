# V2 parser diagnostic bridge

Separate bridge; historical executors, parsers and failed attempts are unchanged.
The new attempt is `access-v2-1` under the existing sealed parent, never access-1.
No native collection is authorized by implementation or freeze preparation.

Both worker and parent derive V2 context from the exact execution contract, never
from stdout, cwd or worker summary fields. The worker parses captures using V2;
the parent independently reparses the byte-bound trace and checks every summary,
exit code, command status, argv/profile, ordering, comparison and remaining cases.
Any unrecognized output fails; expected negatives remain FAILED. No acceptance
publisher or real-linker qualification exists. Both-selected is a diagnostic
collection only. Fake host tests do not establish real process lifecycle behavior.

The same marker and native arguments remain pinned. Only owned attempt paths
are rebased. Both inner profiles keep real ld denied; the present profile's single
synthetic-file exception remains an actual execution permission, not a check-only
rule. Frozen `-###`, inert contents and denied helpers remain required. A marker
receipt pins pre/post identity but does not prove OS-enforced immutability or
exclude transient replacement by a concurrent writer; no concurrent writer is
assumed. Native dispatch is possible only after a genuine reviewed freeze and
separate authorization through the existing command-scoped launch profile.

`freeze.py --snapshot-marker` reads the existing marker and writes a fresh separate
identity receipt; it never creates, modifies or executes the marker. The historical
receipt is preserved. Only an explicitly recorded consistent filesystem-device
number change is permitted; all other attributes must match, and its cause remains
unproved. Independent QA must review the new receipt before admission.
`freeze.py --output <exact fixture path>` validates the pinned inputs and six
inventories without executing native tools. It rejects missing/unexpected inputs,
existing attempts and identity drift. Printing a launch request also executes none.

Ownership, cancellation, 300s decision window, 30s queries, 15s separate cleanup,
256KiB command output, 2MiB traces, 512KiB outer logs, 512MiB worker RSS, 32MiB
manifests, 256 inputs, 1GiB small-slice and separate 18GiB analysis floors remain.
Uncertain cleanup/revocation quarantines subsequent attempts; first failure stops
continuation. No automatic retry or acceptance publication is allowed. The parent
requires confirmed worker cleanup before readmission and trace replay.

P2 is incomplete; semantic search stays disabled. Bridge host preparation proves
no native selection, compilation, Android/Bazel compatibility or device behavior.
