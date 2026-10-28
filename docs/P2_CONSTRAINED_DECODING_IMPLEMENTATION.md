# Constrained-Decoding V2 Integration

2026-10-26. **Internal integration implemented; independent implementation review
and execution-freeze correspondence remain gates. No device execution authorized.**

The user's project-chat instruction approved the nine-request scope, strictly
under 30 seconds including cleanup, isolated Android-pressure QA profile and the
cd07-only one-token negative cap with 512 reserved output tokens. The approval is
recorded separately from the immutable preparation lock in
`planning/fixtures/constrained-decoding-v2/integration-authorization.json`.

## Runtime Boundary

`ConstrainedDecoding` admits only the frozen case/schema/pack/question identities.
It is immutable synthetic configuration, not an ownership credential. The platform
adapter independently rehashes its bytes. ModelManager and the adapter both require
an explicit constrained-QA flag, enabled only for debug + secureQa + the existing
Android-pressure build flag. Ordinary chat remains unconstrained; there is no
automatic fallback from a constrained request. Disabled calls reject before
reserving native work or unloading an existing model.

The existing ModelManager owns reclamation, encoder admission, confirmed worker
cleanup, Gemma admission, cancellation and final release. No second manager,
database, source retrieval or UI feature was introduced. The new request uses the
existing 30-second whole-pipeline verification policy; production search remains
five seconds. Request eligibility is rechecked before provider setup and send, as
well as at existing preparation, streaming and publication boundaries.

Only the QA adapter creates an opted-in conversation and passes the exact
per-request response format to the pinned callback API. Tools/automatic tool
calling and thinking stay disabled; the candidate remains GPU, 2048 context and
512 reserved output tokens. cd07 changes only its decode cap. All other generation
guards remain in place. An unsupported schema/error never triggers an unconstrained
send, retry or output repair.

`ConstrainedSession` is a per-conversation handle seam under the existing adapter
lifecycle lock. It publishes cancellation ownership before setup. Cancellation
does not acquire the setup/send lock; a signal received before handle publication
is retained. A returned handle is closed without sending when cancelled or stale.
An arbitrary synchronous native throw without a terminal callback is uncertain;
the engine is quarantined rather than treating a guessed close as confirmed.

## Nine-Request Harness

The separate, opt-in instrumentation entry point is `ConstrainedDecodingNativeTest`.
Its ordinary invocation skips native work. It requires a hash-bound plan, the
secure QA profile, reviewed execution-freeze identity, explicit device authorization
and current run-scoped supervision. The host runner verifies all admission gates
before ADB/model access, stages private verified frozen APK copies, and reserves
one retained attempt for the freeze.

Each case creates a fresh encoder admission adapter/worker, starts with Gemma
unloaded, and measures worker death before Gemma loading. These are native/admission
states, not claims of flushed OS file caches. Expected states and witnesses are in
a separate oracle artifact; only exact payload/question/schema inputs reach native
models. The sentinel schema is intentionally fixed public structural test data.

Setup, handle publication, send entry/return, first text, terminal, cancellation,
native errors and close are timestamped. Cancellation cases require their actual
signal inside the specified observed boundary. A phase label or missed target
does not pass. Compilation rejection needs the pinned native LLGuidance error
prefix plus the deliberate invalid type and zero generated text; generic errors
do not qualify. Native keyword support and cancellation remain unverified until
separately authorized device evidence exists.

The unchanged strict validator remains authoritative. Positive guards also require
the frozen sentinel/control shape, SDK token bounds, eligible delivery and healthy
allocation/cleanup evidence. Negative guards require revoked delivery. Expected
negative checks are reported separately from replies and abstentions. A blocked
case stops the other cases without retry. Failed/partial raw output is retained.

The monotonic request clock starts before per-request checking/packing and includes
reclamation, encoder work/death, Gemma load, provider/compiler work, generation,
validation, classification and cleanup. At 30,000 ms delivery is invalidated;
cleanup retains ownership. The independent **40-second emergency watchdog** signals
only the current immutable QA supervision directory if JNI remains stuck. Failed
signal publication terminates only the isolated QA process. This is emergency
cleanup, never extra accepted inference time; it cannot make an overrun pass.
Its close/expire guard prevents a retired request from stopping its successor.

## Verification And Next Gate

Host prerequisites are recorded in
[P2_CONSTRAINED_DECODING_PREREQUISITES.md](P2_CONSTRAINED_DECODING_PREREQUISITES.md).
Local logs are under `build/constrained-decoding/`. Unit/host tests cover exact
identities, schemas and strict fixtures, decode-cap isolation, disabled mode,
owner/panel exclusion, pending handles, cancellation, stale callbacks, context and
deadline loss, uncertain cleanup, expected-negative classification, proof failure,
freeze admission, APK races/corruption and authorization before device access.

Implementation-review and freeze scripts are present, but no implementation PASS,
execution freeze or S24 permission is fabricated by the implementation. After a
hash-bound independent implementation PASS, prepare the real code/APK/pack/schema/
proof/profile freeze and obtain independent correspondence review. Device execution
then still needs a separate user authorization. The old six-request and eight-request
permissions cannot authorize this experiment.

No S24 access, APK installation, native inference or commits were performed for
this integration. Historical failed outputs/archives and strict-validator bytes
remain preserved. No performance or schema-enforcement claim follows from host
tests or compilation. P2 remains incomplete and semantic search disabled.
