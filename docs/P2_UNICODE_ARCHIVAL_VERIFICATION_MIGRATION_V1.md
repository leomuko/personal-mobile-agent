# P2 Unicode archival verification migration v1

2026-11-06. Implementation and host verification only. P2 remains incomplete;
semantic search is disabled and native linker selection is unverified.

The prior full Python baseline had 387 passing tests and one historical guard
error. Installing the reviewed Bazel/Clang guidance changed `AGENTS.md`; the frozen
Unicode verifier correctly rejected those current bytes. Replacing its expected
hash or editing its frozen test would erase that distinction.

The new explicit archival route preserves the original test, verifier, preparation
locks and source snapshots byte-for-byte. It uses the retained old guidance only
for historical verification. All ten original test bodies still execute, with one
guard-call change in a separately stored active copy. Discovery replaces matching
case instances one-for-one and rejects missing, duplicate or altered inventories.
All other discovered tests execute normally. A pinned current-guidance identity
rejects further changes; non-guidance source drift still reaches the original
checks. The current-source entry point still rejects the historical-guidance
mismatch and is independently tested. No native entry point receives the archival
root.

Use `build/constrained-decoding-reference/bin/python -B scripts/host-python-test-suite.py`
for the migrated full baseline. Raw historical discovery is deliberately unchanged
and still rejects current guidance. This is explicit archival routing, not a skip,
an expected-hash refresh or a current-source acceptance claim.

Producer checks passed:

| Check | Result |
| --- | --- |
| Canonical full host suite | 404 passed; zero failures, errors or skips |
| New archival regressions | 16 passed |
| Original Unicode methods through explicit archival route | 10 passed |
| Focused executor suite under pinned Python 3.9 | 21 passed |
| R2 data-only recheck | 207 inputs and six inventories match |

Focused counts are subsets of the full suite, not additional unique test totals.
Regressions cover unauthorized current guidance and runtime changes, tampered
lineage, unsafe archival paths, missing active cases, altered assertions and
propagation of test failure. No test-library dependency was introduced.

An additional bare, isolated Python 3.9 archival check ran 16 tests and reported
nine errors because that environment lacks the existing SentencePiece dependency
imported by the frozen module. Its failed log is retained separately; the canonical
404-test result uses the existing reference environment with that dependency.
No packages or stubs were installed, and no bare-Python archival compatibility
claim is made. The executor's 21-test Python 3.9 result is independent of this.

The producer logs and supporting binding audit are retained under
`build/unicode-archive-verification-v1/`. The separate result lock binds the new
source, archival route, logs and exact proposed command manifest. Independent
migration QA is recorded separately; the old R2 review is not rewritten.

R2 remains at SHA-256
`0fedb46426268e487cfb18ec55a04b04b0c348db7b5ba956990d03118c0d1d56`.
None of these migration paths is a bound R2 input, and no R2 input changed. Its
existing implementation review and read-only admission proof remain historical
evidence. No new native attempt directory has been created.

The [readiness statement](P2_CPP_LINKER_ARCHIVAL_READINESS_V1.md) identifies the
seven proposed commands and unchanged execution limits. They await separate user
authorization. This migration grants no compilation, Bazel, SDK, emulator or S24
credit; the 72 C++ cases and later qualification remain outside scope.
