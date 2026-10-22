# Archival V2 Host-Test Migration

Date: 2026-10-22. Scope: host checks only; no runtime or frozen-campaign edits.

## Why A Separate Active Module

The final test in `scripts/tests/calibration-policy.test.mjs` mixes two distinct
claims: preservation of historical V2 evidence, and admission of that campaign
under the current runtime. V3 legitimately changed runtime hashes, so current
`loadLock()` must reject the completed V2 lock. That is not corrupted historical
evidence and must not be fixed by renewing the lock or relaxing admission.

The original test module is itself one of V3's preserved artifacts. It remains at
its original path with SHA-256
`35720368cb2999ea192fbca48d398dd563d261171fa36f29a5adea75ebb0240c`.
All fixtures, rejected bytes, lock, approvals and attempts remain unchanged.

## Canonical Current Suite

```sh
node scripts/host-test-suite.mjs
```

Run from the repository root. The runner discovers every `scripts/tests/*.test.mjs`
module and substitutes exactly one active version:
`scripts/current-tests/calibration-policy.test.mjs`. It prints that mapping and
runs Node with an explicit file list. No test-name filters, skips or generated
executable transformations are used.

Before starting any tests it verifies the original bytes and requires the active
file to equal the original with exactly one specified replacement. The first 15
tests and every other assertion in test 16 remain unchanged. Test 16 reads the
archived lock as data, retains its dataset/evidence preservation assertions, and
adds an explicit assertion that current `loadLock()` rejects the stale runtime.
Any other active-module edit, missing file or changed original fails closed.

This is an explicit versioned test-suite migration, not two competing current
implementations. The preserved original is historical-only. Future changes to
these active tests require a separately reviewed migration, not an unnoticed copy
drift. The discovery mechanism automatically includes new ordinary host modules.
Retain the original for the lifetime of its preservation contract; do not delete
it as dead code or update the V3 freeze to make an in-place edit possible.

The old `node --test scripts/tests/*.test.mjs` route is **not** the canonical
current suite: it continues to execute the original historical assumption and
has its known failure. A green canonical result does not mean that old command
has become green. Focused current calibration checks may use
`node --test scripts/current-tests/calibration-policy.test.mjs`.

## Verification

Passed in this change:

- Focused active calibration + migration checks: 22 passed, zero failures/skips.
- Complete canonical host suite: 73 passed, zero failures/skips. All former 67
  tests execute (including the migrated historical check), plus six migration
  regressions. No existing test module was otherwise excluded.
- Failure regressions cover altered frozen bytes, altered active assertions,
  missing active module, future-module discovery and refusal before executing a
  tampered active file. The executable stale-source guard remains unchanged.
- The unchanged V3 preparation checks revalidate all 15 frozen files, 180 pack
  identities, independent preparation approval and 289 preserved V1/V2 artifacts.
- All 29 runtime source files archived with the blocked V3 attempt match their
  recorded hashes. This verifies preservation, not current-source native behavior.
- Syntax checks for the three new JavaScript modules, `git diff --check` and
  whitespace checks covering all seven current-slice files passed.

Expected historical failure, separately rerun:

- `node --test scripts/tests/calibration-policy.test.mjs`: 15 passed / 1 failed,
  zero skips. The preserved original fails at `loadLock()` with
  `Stale/unfrozen investigation`, confirming the documented historical route. The
  same admission rejection is an explicit passing assertion in the active test.

An initial local targeted run caught an extra final newline in the new active
copy (three migration-check failures). The exact-byte guard rejected it as
intended; the copy was corrected before the passing runs. No historical bytes or
admission rules were changed to accommodate it.

Not run: Gradle/Android builds, device checks or native workloads. This change
does not modify Kotlin, Android packaging or model execution. The earlier V3
report's 66-pass/1-fail result remains an accurate historical observation.

## Remaining Block

[ADR 0006](decisions/0006-evidence-verification-latency.md) and the
[V4 draft](../planning/P2_V4_SERIAL_VERIFICATION_FEASIBILITY.md) record the separate
architecture work item. Neither this test migration nor a review PASS approves
the proposed 30-second operation, native execution, semantic enablement, or P2
acceptance. Those require the explicit decisions and gates described there.
