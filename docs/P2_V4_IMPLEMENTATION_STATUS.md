# P2 V4 Implementation And Execution Status

Date: 2026-10-22. Internal feasibility only; semantic enablement remains disabled.

## Authorization

The user explicitly approved ADR 0006 and `serial-evidence-verification-v4-draft-1`:
30000 ms for the eight-request experiment only, including completed cleanup. The
same instruction authorized implementation, host QA and execution-freeze creation,
not a production latency target. The subsequent message authorized running those
tests on the connected S24 after these prerequisites are complete.

The original reviewed ADR/draft bytes remain unchanged as provenance. Their
proposal-stage wording is historical; this document and
`planning/fixtures/serial-verification-v4/implementation-authorization.json` record
the later limited approval. That implementation record does not authorize a device
by itself. A separate execution authorization must bind the final reviewed freeze,
exact profile and designated device before the wrapper can access ADB.

## Implemented Boundary

- `ModelManager.verifyEvidenceV4` selects a fixed internal policy over the existing
  request-owned pipeline. It adds no runtime owner, database or production UI.
  Ordinary chat, encoder-only/search preparation and V3 retain their existing
  deadlines and behavior. Context/output remain 2048/512; encoder admission is 192.
- One monotonic clock includes queueing, packing, encoder cleanup, Gemma load,
  prefill/decoding and validation. Exact elapsed 30000 ms is expired. Cleanup
  remains independently pending/confirmed/uncertain. Late cleanup revokes delivery
  without replacing an already-latched decision. Ownership remains held and
  uncertain cleanup quarantines subsequent admission.
- The opt-in `V4FeasibilityTest` requires secure-QA, explicit V4 arguments and the
  baseline build flag. It uses only the four approved calibration packs, first
  with reset admission adapters then cached admission metadata, always with fresh
  isolated encoder workers and Gemma unloaded. It stops at the first blocking
  result; it never fits/selects policies or reads development/held-out questions.
- Host classification binds run, pack, plan, freeze, profile and request IDs. A
  pass requires all eight valid eligible requests, both timing checks, confirmed
  worker death before Gemma load, SDK budgets, output diagnostics, healthy
  allocation telemetry and closed scoped supervision. Structural validity is not
  semantic support. Failures and remaining not-run requests stay visible.
- Freeze creation is host-only, requires an independent review of the exact
  source snapshot, exports the shared baseline profile and binds assembled QA APKs.
  It retains copies of source/assets/APKs, and fails on changes during copying.
  Admission rechecks current code/APKs/proof/plan/profile before ADB. A separate
  authorization is scoped to one retained attempt; failure cannot silently retry.

## Historical Baseline Resource Profile

`memory-baseline-v1`, exported by `V4FreezeProfileTest` from shared
`MemoryPolicies.Current`, not a separately maintained host policy:

- App loading/generation available-RAM floors: 3 GiB.
- Supervised pre-load reserve: 2.5 GiB, hence 5.5 GiB required before loading.
- Minimum total RAM: 7 GiB; OS lowMemory must remain false.
- Existing thermal threshold 3 and skin stop 45 C, required signals, supervision,
  cancellation, confirmed cleanup and quarantine are unchanged.
- QA build uses `-PandroidPressureMemoryTest=false`. No available-RAM bypass is
  authorized by this freeze. If baseline resources block, retain that attempt;
  do not switch to the isolated pressure profile or rebuild/retry automatically.

## Host Verification

Passed before independent review:

- Initial canonical Node suite: 81 tests, zero failures/skips, including eight V4 tests.
- Shared JVM: 154; shared Android-host: 135; app debug and secure-QA: 15 each.
  All passed with zero failures/skips. Counting reference was configured.
- Debug app, secure-QA app/test APK assembly and debug lint completed.
- The unchanged V3 checks reproduce all frozen pack identities and preserved
  campaign bytes; archival migration and live stale-source rejection remain active.

Two initial development runs failed and were corrected: an overlong synthetic
release wait exercised the existing five-second cleanup quarantine rather than
the intended late-cleanup boundary; a wrapper fixture omitted an imported V1
fixture. The corrected tests pass without weakening production or frozen rules.
An initial combined Gradle invocation used the debug unit-test task with the
secure-QA test variant selected; checks were rerun with each variant separately.

Independent initial review found two gaps despite those passing tests; its
[original FAIL](P2_V4_FOCUSED_REVIEW.md) is retained. Full per-case acceptance now
runs on-device before continuation, and telemetry clock intervals/order/load
correlation are checked in both device and host gates. An exclusive freeze-keyed
attempt ledger also prevents reformatting authorization or copying the freeze
from turning one approval into repeated workloads. The corrected snapshot is
now approved by the [follow-up review](P2_V4_FOLLOWUP_REVIEW.md). The original
review is retained separately.

Final host checks: 83 Node tests, 12 optimized-Python tests, 154 shared JVM tests,
135 shared Android-host tests, and 19 app unit tests for each debug/secure-QA
variant passed with zero failures/skips. Lint: zero errors, 30 warnings. The QA
APKs were rebuilt after the fixes. System Python lacked `sentencepiece`; the
existing pinned `build/token-verification` environment passed all Python checks.

## Execution Gate

Review and freeze are complete. The authorized attempt is now recorded below.
The user subsequently authorized the designated eight-request test after those
gates; this is not permission for additional workloads, a larger campaign, policy
tuning or semantic enablement. The user confirmed S24 serial `R5CX22V8D6B` was
unlocked; the unlocked/awake state was rechecked before starting the wrapper.

Freeze: `benchmarks/results/p2-v4-execution-freeze-20261022/execution-freeze.json`.
SHA-256: `c258ce5d9faaebe38c1d95432d646959b1a83918064bc61494571e2c58011403`.
Profile: `memory-baseline-v1`; SHA-256
`1998696e00f48a3be6e33a2e4570a5ca34e238b4fe7835118bd9b248a0914daa`.
The directory retains all 287 frozen source/artifact inputs and both APKs.
Its separate S24 authorization records the user's later request without altering
the freeze's `deviceExecutionAuthorized=false` preparation status.

## S24 Outcome

The [retained attempt](P2_V4_FEASIBILITY_RESULTS.md) stopped at resource preflight:
`LOAD_HEADROOM`, exit 2. Available RAM was 3.30-3.35 GiB versus the frozen 5.5 GiB
pre-load requirement; Android reported `lowMemory=false`, thermal status 0.
The two policy/metadata probes passed. No encoder/Gemma request started; all eight
V4 requests remain not run. This is not an OOM or a result against the 30-second
native deadline. No retry, profile change or threshold relaxation occurred.

Run: `a47411d2-1941-4ce8-9530-a05fedc1e458`, folder
`benchmarks/results/p2-v4-feasibility-gate-kgPM6M/`. The freeze-keyed attempt ledger
is preserved. A further attempt requires a separate reviewed decision and explicit
authorization. V3's blocked run and historical reports remain unchanged, and
semantic enablement stays blocked.

## Latest User-Requested Profile Amendment

A separately authorized baseline retry also blocked at 4.39-4.43 GiB available;
its [record](P2_V4_FEASIBILITY_RETRY_1.md) is retained. The user then explicitly
requested lowMemory-based preload admission. The [isolated QA pressure-profile
amendment](P2_V4_PRESSURE_PROFILE.md) uses the existing
`memory-android-pressure-test-v1`, without changing ordinary app policy or other
safeguards. Updated host checks and independent profile review passed, and a new
freeze bound the pressure-profile APKs. Historical baseline freezes and both
ledgers remain intact.

The [pressure-profile S24 result](P2_V4_PRESSURE_RESULTS.md) passed preload
admission and reached Gemma GPU generation. Request one returned Markdown-fenced
JSON and was rejected by the strict validator at 19686 ms; confirmed cleanup and
full request completed in 20138 ms. No usable result was published; seven requests
were not run. No automatic retry or output repair occurred. Semantic enablement
and complete P2 acceptance remain blocked.
