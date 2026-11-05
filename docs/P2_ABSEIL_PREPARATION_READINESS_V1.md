# Abseil prerequisite preparation v1 — review checkpoint

**Implementation and execution-freeze review pending. No Abseil archive acquisition
or real-source preparation has run. C++ execution remains NOT_READY.**

The new source-only tools are under `scripts/diagnostics/dawn_abseil_v1/`:

- Complete streaming TAR/PAX validation reuses the preserved offline-v2 primitives
  without changing them. It checks the compressed SHA first, every header/payload,
  all paths/ancestry/links, complete inventories and gzip EOF/CRC; no excluded trees
  or retained tarfile member list. Unsupported hardlinks/types/PAX block the input.
- A second verified pass stages private files0600/directories0700, retaining source
  modes in the receipt. The original source is unchanged; a separate derivative
  receives only two exact frozen hunks with unique contexts and zero line offset.
- Before mutation, planned whole-file pre/postimage hashes are calculated from the
  validated immutable archive and recorded. Actual whole-file identities cannot
  be reported before payload acquisition. Every other file/link/mode must compare
  equal. This does not establish broad Bazel-native patch equivalence.
- The exact root BUILD command uses pinned stock Bash/sed, no shell replacement or
  changed expression. All three glob depths must match regular files. Existing
  backups, ambiguous/link ancestry and unexpected output are blocking conditions.
  All backups remain present; quota reservations conservatively include both backup
  and rewritten data, without assuming inode behavior.
- The coordinator checks an independent hash-bound review before any owner/network
  operation and rechecks it after cleanup. An original inherited POSIX deadline
  covers admission, work, serialization and publication. Cleanup exceptions produce
  UNCERTAIN terminal outcomes and quarantine later admissions. Only owned publication
  links may be revoked; pending/failed/foreign evidence is preserved.

## Verification performed

**291 host tests passed** without failures/skips. The33 new tests also passed under
pinned host CPython3.9, covering complete/missing/unsafe/corrupt/truncated archives,
PAX, links, transport identities, seeded payload roundtrip/compression invariance,
interruption, quotas, exact patch contexts/offsets, backup accounting, HTTP reader
shape/time budgets, review rejection, cleanup uncertainty and late/interrupted
publication. HTTP and owner work in unit tests is mocked. No new package dependency.

One explicitly scoped **synthetic macOS sed probe passed**, using three fixture
files, pinned tools, an owned process group and a network/write-restricted sandbox.
It ran the exact root command once and confirmed `-e` backups for all three files,
including a zero-substitution file. Before/after inventories, command trace, outcome
and source snapshots are retained under
`build/dawn-abseil-preparation-v1/sed-probes/probe-1/` and `sed-probe-source/`.
Its R2 freeze is retained; R1 was an unexecuted draft. Cleanup was confirmed and
observed public probe elapsed17.286s, including input rehashing/publication. This
is fixture/tool conformance, not vendor-source or compiler qualification.

The actual probe rehashed its bound OS-cache payloads before/after work. That is a
producer check; it is not a claim that independent QA freshly rehashed those caches.
The probe's executed helper snapshots predate subsequent archive/controller
hardening. The pinned shell/command and observed backup contract remain the same;
final preparation code is separately bound by its new execution freeze.

Historical preservation audit passed all50 bindings (49 committed at83dec19 plus
the unchanged held C++ draft) and4539 retained entries. Old freezes, successful/
failed campaigns, approvals and package records remain unchanged. New tooling does
not refresh an old executable freeze to bypass its stale-source guard.

## Concrete freeze and remaining gates

`planning/fixtures/dawn-abseil-preparation-v1/preparation-freeze-r2.json` binds the
final implementation/tests, exact archive/redirect/checksum/prefix, literal patch
contexts, source pins, synthetic shell evidence, tools and OS-cache identities.
It requests one source-acquisition/preparation attempt, no automatic retry. Its
CLI additionally requires an independent PASS attestation for that exact freeze
SHA and `abseil-acquisition-preparation-only` scope. Review does not auto-run a job.

The sole original URL is the pinned GitHub archive; the only permitted redirect
is its specified codeload tag URL. Neither endpoint was contacted in this slice.
Actual endpoint behavior, byte size, license/version metadata, source inventory,
real patch pre/postimages and BUILD matched paths remain unverified until the
separately invoked reviewed preparation succeeds. Unexpected redirects, source
contexts, limits or platform effects stop; no improvisation or partial acceptance.

Resource ceilings and normalized modes are frozen in the tool README/manifest:
300s decision/15s cleanup,16MiB compressed/128MiB decoded,10000 headers,8MiB files,
512MiB writes/RSS,1GiB small-slice capacity. Actual HTTP operations have a remaining
socket-I/O timeout capped at15s and a15s request decision budget; only the whole
invocation has independent owned-process termination. No claim is made that an OS
resolver/blocking system call can always be interrupted at15s.

Compiler/SDK-native inspection, actual helper/driver selection, harness review,
complete generated headers, strict C++17 and the six-positive/six-run/twelve-negative
matrix remain separate gates. No compiler/linker, vendor script, CMake, Bazel,
SDK build, emulator/S24 workload, root adoption or Git mutation occurred. The
18GiB analysis floor, emulator-first direction and deferred S24 testing remain
unchanged. P2 remains incomplete; semantic search is disabled.
