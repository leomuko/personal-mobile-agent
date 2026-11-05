# Abseil acquisition/preparation v1

Source-only tooling for the reviewed P2 Abseil/C++ proposal. This directory does
not run source scripts, CMake, a compiler, linker, Bazel, emulator or device.
It does not adopt root fixes or establish actual Bazel supplier selection.

`archive.py` reuses immutable TAR/PAX primitives from the retained offline-v2
scanner, with its own limits and framing identity. It validates the transport SHA
before decoding, all header checksums/payload hashes, canonical/case/Unicode paths,
ancestry and internal links, complete inventories and gzip EOF/CRC. It retains no
tarfile parser member list. Hardlinks, unsupported types/PAX, external/dangling or
cyclic links are blocking inputs; nothing is skipped to obtain a complete receipt.

Materialization is a second verified pass into a fresh private source directory,
with regular files0600/directories0700. Archive permissions remain in the receipt.
Files/links are verified again after staging; no shared source/cache is replaced.
A private derivative receives only the two frozen contiguous hunk replacements.
Each requires exactly one match at its frozen line; contexts, pre/postimage hashes
and planned changes are recorded before writing. Other files must remain equal.
This proves the specified literal effects, not all Bazel-native patch behavior.

The exact retained root BUILD command runs once through pinned stock Bash/sed in
a network-denied/write-restricted sandbox. No alias, GNU replacement or alternate
command is selected. The synthetic macOS probe established `-e` backup suffix,
including backup creation on zero-substitution targets. All three glob depths must
match real regular files; backup collisions and links in glob ancestry block.
The complete postimage must include exactly the expected backups and other-file
equality. Backups remain present and charged against the write reservation.

The coordinator requires an independent review attestation whose SHA, verdict,
freeze SHA and exact source-only scope match. It enforces an inherited POSIX
CLOCK_MONOTONIC deadline through publication; cleanup allowance admits no extra
qualification. Cleanup exceptions retain a failed outcome with UNCERTAIN status.
Prior uncertain owners quarantine the attempt parent; no override or automatic
retry. Publication intents are registered before hardlinks and revoked only by
owned pending identity. Pending/failed evidence is retained.

An eventual explicit invocation uses the pinned host Python3.9 with `-I -S -B`:

```text
prepare.py --freeze <reviewed-freeze.json> --sha256 <freeze-sha>
  --review <independent-review.json> --review-sha256 <review-sha>
  --attempt <exact-frozen-attempt-parent>/<exact-frozen-name>
```

Exit2 denotes only complete source/preparation plus confirmed cleanup, with C++
readiness false. Exit1 fails admission/preparation/publication; inspect outcome and
quarantine before any further work. A worker's successful exit0 alone is never
acceptance. No real archive acquisition has occurred while preparing this freeze.

Tests are synthetic and require no source download:

```sh
PYTHONDONTWRITEBYTECODE=1 build/constrained-decoding-reference/bin/python -B \
  -W error::ResourceWarning -m unittest discover -s scripts/tests \
  -p 'test_dawn_abseil_*.py' -v
```

Frozen ceilings:300s decision/15s cleanup; each shell command30s, each HTTP request
15s, one original URL with at most one exact codeload redirect and no retries;
16MiB compressed/128MiB decoded/10000 headers,8MiB per file; names1024 bytes/depth64;
PAX64KiB each/1MiB total; names12MiB/frames32MiB;384MiB final candidate including
backups;512MiB aggregate writes with2MiB reserved logs/terminal evidence;512MiB
worker RSS,32MiB per manifest. Small admission requires1GiB free. The18GiB Bazel
analysis floor remains separate and unchanged.

An independent freeze review is necessary but does not automatically run a job.
Compiler/harness/native-tool qualification and complete-header execution remain
separate. P2 remains incomplete; semantic search stays disabled.
