# Proposed emulator-first runtime campaign v1

2026-10-29. **Proposal only; not executable or an authorization artifact.**
The current route is not build-ready. This campaign starts only after a reviewed
complete recipe, independently checked control/candidate artifacts, tokenizer
and budget proofs, implementation QA and a concrete reviewed execution freeze.
The user's emulator-first direction does not bypass those gates. S24 is deferred.

## Required freeze

Bind reviewed source, all toolchain/dependency/patch identities, content-addressed
AARs, app/test APKs, schemas, packs, fixture seed, resource profile, expected
outcomes, exact request ledger and stop conditions. Install only verified private
APK copies. Each run gets fresh immutable evidence and scoped supervision.

Keep 2048 context / 512 reserved output tokens and the strict validator. Define
the exact test deadline, including setup, validation and confirmed cleanup, in
the new reviewed protocol. Do not reuse a previous S24 authorization, increase
the five-second search deadline or treat the prior 30-second experiments as a
blanket timing permission. No repairs, retries or unconstrained fallback.

Use the designated isolated arm64 emulator on a compatible host without wiping
existing data or stopping a user-owned emulator. Bind image/API, emulator version,
RAM/storage and backend. Both arm64-v8a and x86_64 must remain in the matching
artifact recipe and static inspection; x86_64 execution requires a compatible
designated host/emulator lane and must otherwise be reported not run.

## Ordered cases to freeze concretely after artifacts exist

1. **Packaging:** resolve QA app/test graphs and ordinary graphs; inspect matching
   wrappers/API/JNI, licenses, both ABI libraries and positive provider linkage.
   Reject duplicates, provider stubs, mixed ABIs or unknown dependency scope.
2. **Stock baseline and fixture seed:** install a matching stock QA APK pair with
   a monotonic version code. Create synthetic history, source text and a draft;
   record logical IDs/content hashes without exporting keys or personal data.
3. **Source control:** replace with its reviewed APK pair, start a fresh process,
   verify seed continuity, local generation, release and unload.
4. **Candidate default-off:** same lifecycle and request with the Unicode option
   absent; compare expected behavior without claiming bit-identical model output.
5. **Candidate opt-in:** strict bounded-string schema with equivalent literal and
   escaped Unicode, including scalar-length boundaries; preserve raw output and
   strict validation. Confirm actual packaged provider/option, not string presence.
6. **Negative admission:** unsupported/mismatched runtime/schema identity must
   reject without free-text fallback or unauthorized native allocation.
7. **Cancellation and timeout:** Stop during loading/generation, deadline expiry,
   late callbacks, confirmed cleanup and fresh-process recovery. Keep ownership
   until confirmed release; uncertainty quarantines further admission.
8. **Data-preserving rollback:** explicit build-time stock selection, verified
   replacement APK pair, fresh process and unchanged synthetic IDs, source,
   history/draft. No uninstall, `pm clear`, key reset, downgrade or JNI hot swap.

Specify CPU and any supported emulator GPU lanes separately. A CPU emulator lane
does not narrow artifact scope to CPU or certify S24 GPU operation. Unsupported
emulator GPU behavior is blocked/not-run evidence, not a physical-device verdict.

Stop at the first unexpected result; retain the failed raw outputs and all not-run
cases. Cleanup is separately confirmed even after timeout invalidates results.
No automatic continuation after supervision loss or uncertain native close.

## Acceptance boundary

Report compatibility, constraint enforcement, cancellation, cleanup and rollback
per artifact/ABI/backend/API. Passing permits only that stated emulator scope.
S24 GPU/memory/thermal/battery acceptance, semantic accuracy, retrieval enablement
and P2 completion remain separate. No device workload is included in the current
source-resolution slice.
