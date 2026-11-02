# Isolated repository archive cache v1

Host-only, archive-payload preparation. Pinned source declarations and accepted
SHA-256 identities remain authoritative; a partial transfer is never a cache hit.
No extracted repository trees, compiler outputs or completion markers are reused.
The historical helpers and Java-21 freeze remain unchanged.

`freeze.py` discovers 37 complete retained cache payloads, preserves exact empty
canonical-ID markers and adds five pinned Rust 1.86.0 component URLs. It writes
new files exclusively and executes no downloads/tools. Existing destinations
reject rather than overwrite evidence.

After independent review of the preparation plan, manifest, implementation and
exact preparation-freeze SHA:

```sh
python3 scripts/diagnostics/repository_cache_v1/prepare.py \
  --freeze planning/fixtures/repository-cache-v1/preparation-freeze-r4.json \
  --freeze-sha256 REVIEWED_PREPARATION_SHA \
  --attempt build/repository-cache-v1/preparation-attempts/NEW_ID
```

Each Python worker has a private SHA-bound request and an owned process group.
The original preparation freeze is a retained rejected draft: its URL filter
missed the underscore in x86_64. The R2 filter and actual component URL regression
close that preflight failure; no preparation executed under the original freeze.
R2 was rejected in independent review for late-finalization success credit. R3
checks receipt/report finalization and rejects late preparation evidence during
analysis admission. Both rejected freezes and regression witnesses are preserved.
R4 also starts the whole-preparation clock before input admission, with a delayed
preflight regression. R3 remains retained as an unexecuted intermediate draft.
Failed workers/partials remain retained. A complete receipt plus successful
report is required before analysis. Preparation is 180 seconds per entry,
900 seconds total with final bounded cleanup, and 2 MiB worker logs. Preparation
never executes downloaded tools. No shell/native package installation.

After results review, `freeze.py --prepared ABSOLUTE_ATTEMPT_PATH` binds the
accepted cache/report/receipt, original source/toolchains and reviews into a new
analysis freeze. That freeze must itself pass review before running:

```sh
python3 scripts/diagnostics/repository_cache_v1/analyze.py \
  --freeze planning/fixtures/repository-cache-v1/analysis-freeze.json \
  --freeze-sha256 REVIEWED_ANALYSIS_SHA \
  --attempt build/repository-cache-v1/analysis-attempts/NEW_ID
```

The accepted cache is fully rehashed then copied into a fresh writable cache;
no hardlinks to accepted payloads and no modifications to the historical cache.
Analysis requires 18 GiB free and retains the original three commands, Java
selection, repository guards, 300-second deadlines, 15-second cleanup ceiling
and 16 MiB logs. A failure never permits an unreviewed retry or new archive list.

```sh
build/constrained-decoding-reference/bin/python -W error::ResourceWarning \
  -m unittest discover -s scripts/tests -p test_repository_cache.py -v
```

No SDK builds, app substitution, devices or Git mutations. Preparation, configured
resolution, compatibility and runtime verification are separate gates.
