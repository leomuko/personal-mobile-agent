# Linker-selection investigation — host preparation results v1

**Preparation completed; independent proposal review pending. Native qualification
remains BLOCKED.** This slice does not claim a deterministic native linker fix.
P2 remains incomplete; semantic search is disabled.

## Diagnosis and exact proposed change

The preserved metadata-2 witness remains native exit 0 with `ld\n`, followed by
absolute-linker validation rejection. Its compiler prefix/PATH and pinned linker
bytes are recorded, but no syscall or driver-internal trace establishes why lookup
failed. Explanatory upstream LLVM 21.1.0 source separates general program lookup
from linker job selection and checks executability in both relevant paths. That
makes sandbox interaction plausible, not proved. Apple source/binary equivalence
and actual `--ld-path` support remain unverified.

The versioned protocol proposes seven metadata probes: one diagnostic-only lookup
and six `-###` plans using explicit `--ld-path=<pinned absolute ld>`. The latter
cover all original ordinary/wire control/candidate C++17/C++20 profiles. They must
show literal declared/selected linker agreement and pass the unchanged closed old
validator. The old validator still rejects the retained `ld` output. A new diagnostic
classification never repairs or qualifies that historical failure.

No native executor is implemented in this preparation. `prepare.py` is data-only;
it verifies inputs and publishes an exclusive proposal freeze. It cannot dispatch
a probe. A later implementation/execution freeze, admission proof, independent QA
and separate native authorization remain required after review of this proposal.
No probe directory has been created.

## Fresh checks

- 367 host tests passed, zero failures or skips, in the existing diagnostic
  SentencePiece environment. No package installation or global Python change.
- 24 focused tests passed both in the development interpreter and under pinned
  isolated Python 3.9. These exercise exact selection, ambiguous/relative names,
  unknown jobs/options/suppliers, incomplete profile sets, diagnostics, unchanged
  old rejection, changed inputs, exclusive output and bounded static sampling.
- The full isolated Python 3.9 attempt could not import five older SentencePiece
  modules: 316 reported tests, 311 passed and five loader errors. The failure log
  remains separate; the successful full run used the existing reference environment.
- The first new unit run had one test-fixture error involving /var versus
  /private/var canonicalization; its receipt is preserved. The corrected 24-test
  runs pass. This was not a native or historical-validator change.
- A first read-only binary scan stopped at its 16-occurrence ceiling; its failed
  receipt is preserved. The bounded sampler now stores at most 16 offsets and an
  explicit omitted flag, without enlarging the limit or claiming a full inventory.
  It found three `ld-path=` and two `print-prog-name=` occurrences plus 16 sampled
  access-related occurrences with further matches omitted. These are strings,
  not proof of active option behavior or a function/call relationship.
- Actual data-only freeze preparation passed: 163 frozen file inputs (including
  all original 133), six complete inventories and preserved metadata-2 result
  receipts. It created no native owner, worker, child or probe attempt.

## Freeze and limits

Proposal freeze: `planning/fixtures/dawn-cpp-linker-v1/probe-freeze-r1.json`.
SHA-256: `fc37dbb00cea5abc4b84c7ddf7445555dc0027bb1c86cbd09b288f1229c92ff3`.
It binds exact commands, sandbox, environments, input identities, references,
new sources/tests, successful logs and retained preparation failures.

The compiler-only executable profile is unchanged: ld and every additional helper
remain denied. No sandbox widening or unsandboxed comparison is proposed. Existing
300-second decision, 15-second cleanup, 30-second query, output/RSS and storage
limits remain; the 1 GiB small floor never lowers the 18 GiB analysis floor.
Any option/permission/default failure must stop a future campaign without retries.

## Boundaries and remaining work

Passed preparation is not native enforcement, successful option parsing, selected
linker agreement or C++ compatibility. No compiler/linker/vendor tool, SDK/Bazel
build, emulator/S24 workload, source acquisition or Git mutation occurred. Host
unit tests exercised existing tokenizer extensions in their existing environment;
they are not linker/native probe credit. All seven proposed probes and all 72
complete-header C++ cases are NOT_RUN. Emulator-first order and S24 deferral remain.

Independent QA must review the proposal and current freeze/data-only verify path.
Then decide whether to implement this explicit-selection experiment. Do not
continue metadata-2 or silently accept bare names, aliases or unknown defaults.
