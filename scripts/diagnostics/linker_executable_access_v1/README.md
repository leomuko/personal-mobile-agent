# Synthetic executable-access comparison

Separate implementation of the reviewed two-case proposal. It does not change
original executors or validators. No native collection is authorized yet.

`freeze.py --prepare-marker` exclusively stages the pinned 53-byte inert marker
(mode 0555), then records payload, file and parent identities. It never executes
an access predicate or native tool. Preparation refuses existing targets and
retains failures. Identity pinning is not OS-enforced immutability; no concurrent
writer is assumed and pre/post checks cannot exclude transient replacement.
The positive profile really permits an execution attempt against this one marker.
No-launch relies on the frozen `-###` command; denied helpers and inert contents
are additional constraints only. Real ld remains denied in both profiles.

After host tests, `freeze.py --output <exact fixture path>` seals the new recipe,
marker receipt, baseline inputs, six inventories and implementation. It performs
read-only byte/inventory checks. Independent QA must bind that freeze before
`launch.py` can admit it and print an exact one-command host request. Printing a
request does not authorize or execute it. The outer permission is command scoped;
no persistent rule or global change is requested. Separate authorization is still
required for native execution.

The coordinator starts one owned worker with a 300-second decision window,
30-second per-case bounds and a separate 15-second cleanup allowance. The worker
checks exact argv/profile/environment and marker identity before and after work.
It records raw output before classification; it cannot run case two after any
first-case failure. No retries, fallback or resumed old plans are implemented.
The local closed job grammar differs from the existing grammar only in literal
synthetic/bare supplier checks and retaining the real toolchain's LTO path.
Recognized negative diagnostics are parsed separately, never repaired in evidence.

A positive selection then exact negative rejection supports policy sensitivity
for this fixture only. The negative command remains FAILED, as do its worker and
terminal outcome. Both-selected means no support in this comparison. Other results
remain inconclusive. The parent independently reclassifies raw traces after
confirmed cleanup and readmission. No path publishes `accepted-probes.json` or
qualifies real ld. Unknown cleanup quarantines subsequent work; publication errors
remain separately recorded. All failures stay local and retained.

Host tests use synthetic transcripts/mocked native dispatch and temporary marker
files. Full tests use `python3 scripts/host-python-test-suite.py`; the original
archival tests and runtime guards remain intact. P2 remains incomplete and
semantic search disabled. Compilation, Bazel, model/device and SDK builds are
outside this scope.
