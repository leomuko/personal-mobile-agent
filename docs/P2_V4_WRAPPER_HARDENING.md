# V4 Frozen-APK Wrapper Hardening

Date: 2026-10-22. Scope: host wrapper and regression tests only.

## Failure And Fix

V4 admission validated APK hashes, but the later installer read mutable Gradle
outputs. A concurrent build could substitute bytes between those steps.

`prepareV4Apks` now checks the admitted manifest and copies both APKs from its
`snapshot/` bundle to a unique private temporary directory. Only regular,
non-symlinked bundle paths are accepted. The directory is mode 0700; independent,
exclusively created files are mode 0400. Both copies must match the admitted
SHA-256 identities before any ADB access. Neither build outputs nor frozen originals
are modified, linked, installed or rehashed for V4's installation evidence.

`installV4Apks` rechecks the complete private pair before the first install. The
wrapper uses only those paths for installation and their verified identities for
`app.sha256` / `tests.sha256`. Setup failures remove partial copies before reaching
the device error handler. The existing single-attempt ledger remains exclusive;
reservation failure also removes copies without an ADB stop. The device-flow
finally block removes copies on success or error, even if evidence cleanup throws.
Abrupt process/OS termination is not certified by these host checks.

## Fresh Host Verification

- `node --test scripts/tests/v4-feasibility.test.mjs`: 16 passed, no failures/skips.
- `node scripts/host-test-suite.mjs`: 89 passed, no failures/skips. The approved
  archival-v2 migration and executable stale-source rejection remain active.
- Five added regressions cover fake-ADB installation after concurrent build
  replacement, independence from subsequent bundle changes, private-copy corruption,
  missing/corrupt/linked frozen APKs through the complete wrapper, and private-copy
  cleanup after encoder-setup failure. Failure injection covers both APK positions.
- All device interactions in these regressions are synthetic callbacks or fake ADB
  executables; no Android device is accessed or package installed.
- Read-only hash verification passed for all 290 files in the preserved pressure
  bundle (288 sources and two APKs), the raw failed report, diagnostic text and
  current strict validator. JavaScript syntax and `git diff --check` passed.

## Evidence And Remaining Gates

No S24 rerun, Android build or native workload was needed or performed. Earlier
device results remain historical; they do not test the changed wrapper. The saved
S24 attempt's APK hashes match its original freeze, so the race finding does not
invalidate that attempt's evidence or change its blocked result.

The wrapper/source edits intentionally invalidate old freezes for new execution.
No freeze, review approval, device authorization or attempt ledger was refreshed
or reset. The raw failed output and strict validator remain unchanged; the
[separate output-adherence investigation](../planning/P2_OUTPUT_ADHERENCE_INVESTIGATION.md)
does not repair the output or authorize execution. Native feasibility and semantic
enablement remain blocked.
