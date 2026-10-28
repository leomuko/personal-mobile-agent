# Constrained-Decoding V2: S24 Results

2026-10-26. Executed and audited by OpenAI Codex, the same retained primary QA
reviewer, under explicit human authorization. No delegation or parallel phone
workload. This report covers the original attempt and one later human-requested
repeat; it does not claim a separate external review of the executor's work.

**Qualification: BLOCKED.** Both attempts timed out on cd01, with incomplete
raw JSON. Delivery was revoked, cleanup confirmed and cd02-cd09 not run in each
attempt. No successful constrained reply or semantic-accuracy result was obtained.
The authorization/identity/stop-and-preservation evidence audit passed. Optional
post-run device package/hash verification was unavailable after the phone ceased
to be reachable; it is not claimed as passed.

## Observed Result

| Observation | Initial attempt | Explicit repeat |
| --- | ---: | ---: |
| Case attempted | cd01 | cd01 |
| Outcome / delivery | TIMED_OUT / REVOKED | TIMED_OUT / REVOKED |
| Runtime decision | 30030 ms | 30010 ms |
| Reported runtime cleanup stage | 421 ms | 407 ms |
| Whole case through cleanup/classification | 30618 ms | 30625 ms |
| First nonempty text | 16882 ms | 16999 ms |
| Preserved raw output | 308 bytes | 267 bytes |
| Trailing newline characters | 228 | 187 |
| Strict validation | NOT_REACHED | NOT_REACHED |
| Encoder / runtime cleanup | CONFIRMED / CONFIRMED | CONFIRMED / CONFIRMED |
| Final runtime | UNLOADED | UNLOADED |
| Later cases | cd02-cd09 NOT RUN | cd02-cd09 NOT RUN |
| Host wrapper exit | 2, blocked | 2, blocked |

The overrun itself was small: 618/625 ms beyond the required ceiling. Neither
run had a complete response by then. Both outputs began with the fixed S/detail/
sentinel-p citation structure and stopped after the `"b":` key, followed by
newlines without its required integer value or closing object. A host JSON parse
of each retained raw output fails as incomplete. This is a post-run observation;
the runtime outcome remains timeout with validation NOT_REACHED, not a rewritten
schema-rejection verdict.

The whole clock includes identity/admission, encoder work/death, model checking/
load, provider setup, generation, validation/classification and cleanup. The
421/407 ms cleanup fields are individual runtime-cleanup stages, not the complete
post-decision tail. Whole time minus decision was 588/615 ms respectively. Neither
value may be subtracted to manufacture a below-30000 ms success. The shared
ENCODER_TIMEOUT error name here denotes the whole-pipeline deadline: the encoder
worker had already died before Gemma loaded.

Both native harnesses reported `OK (1 test)`. That confirms the harness completed
its reporting path; it does not mean the nine-case qualification passed. Each
host replay reports one finalized case, one case producing text, one timeout and
one failed guard, with zero strict-valid/eligible outputs, positive guards or
expected-negative guards. The `completedRequests: 1` field counts a finalized
attempt, not a completed reply. cd02's compiler rejection, cd04-cd06's targeted
cancellation, cd07's cap-1 exhaustion and the later citation controls remain
untested. No p95, accuracy percentage or semantic conclusion is justified.

Repeated whitespace is the observed failure behavior. The evidence does not
identify its underlying model/tokenizer/compiler cause or prove that native
schema constraints were ignored. Whitespace can be a legal JSON prefix while
the full required response remains unfinished. SDK input/output counters were
not published before cancellation, so their measured values are unavailable,
not zero. Configured caps and reservation identities remained unchanged.

## Authorization, Freezes And Attempt Ownership

Device: Samsung SM-S928B, serial R5CX22V8D6B, Android 16. Before the initial run,
read-only metadata confirmed connected/unlocked and thermal status 0. Both runs
used their own fresh policy/resource preflight and scoped supervision.

The first human instruction explicitly delegated sole execution to this QA agent,
after the freeze correspondence PASS. The first attempt stopped without an
automatic retry. After receiving its result and deadline explanation, the human
then explicitly instructed `run the test again`. That instruction authorized one
additional test under the same limits. A new bundle, correspondence record,
separate human-authorization record and ledger preserved that repeat separately;
the first ledger was not deleted or bypassed.

The repeat freeze differs from the original only in createdAtMs. Its reviewed
source/input/proof map, exact plan/oracles/profile, implementation manifest and
signed APK bytes are identical. No code, schema, deadline or APK was changed to
obtain a different result, and the first failure remains part of the evidence.

| Identity | Initial attempt | Explicit repeat |
| --- | --- | --- |
| Run ID | `43050c47-fb14-46aa-909c-bc93d768f6a0` | `6ad45fe8-2a37-4b6f-afec-138b01125ad1` |
| Run directory | `benchmarks/results/p2-constrained-decoding-gate-qHeLdZ` | `benchmarks/results/p2-constrained-decoding-gate-VFYDVv` |
| Bundle | `benchmarks/results/p2-constrained-decoding-execution-freeze-20261026-1` | `benchmarks/results/p2-constrained-decoding-execution-freeze-20261026-repeat-1` |
| Freeze SHA-256 | `706f33e820924dc5201d92c49d76abbff1fd64a5c21a9f11cc71a01908eeccb6` | `ada0cad3d96d20225ef36ebda0c43585d4bd58e9782847d9c5969bcd75e9dbcc` |
| Device-authorization SHA-256 | `673eabccf457a996fc18cf042751673e43605539f68d515ba4b7abe06005cb63` | `dccbc34ba01cf74ce48977405b03121885ab705bae0af65ec85624e1d8ed4e8f` |
| Host run start UTC | 2026-10-26T17:34:10.058Z | 2026-10-26T17:40:56.911Z |
| Host run finish UTC | 2026-10-26T17:35:00.478Z | 2026-10-26T17:41:46.170Z |

Each bundle contains its unchanged execution-freeze.json,
independent-freeze-review.json and separately authored device-authorization.json.
Human chat instruction is the permission provenance; neither implementation nor
freeze review confers device permission. The review manifests retain
deviceExecutionAuthorized=false. Exactly one ledger entry per freeze is retained
under `benchmarks/results/constrained-decoding-ledger/<freeze-sha256>.json`, bound
to the corresponding run ID and absolute run directory.

Common reviewed snapshot: 341 entries,
`158630554bae1071105b3b50d24bed342d142178b51a71780601b983da4f9d9b`.
Profile:
`83c6e3f2ac535be423bfd0e359546098c51743be3bbf52353ca065d87ff85113`.
Plan:
`a9b500fa81b3871eb9e5c7f441317ec2fb9cb969c55b1f68ae30c34eadeba294`.
All nine exact inputs/schemas and oracle separation remained fixed. Limits were
nine ordered requests, each strictly below 30000 ms including cleanup, GPU/2048
context/512 reserved output tokens, cd07-only decode cap 1, and the isolated
memory-android-pressure-test-v1 profile. cd01 retained decode cap 512.

Both wrappers passed admitConstrainedExecution before device work, verified and
installed only the private frozen QA app/test pair with data-preserving install-r,
then reserved no additional attempt for that freeze. App SHA-256:
`cdd57923977f25bb335a54b679e05be6ea29b396d1c178d0c54f7c210a0670fe`;
test SHA-256:
`57da1c7fdd5e706065c02b451e1c6adff21a93c904a368c14aa34ae728848403`.
No rebuild, uninstall, unrelated app modification, emulator change or data wipe.

## Cleanup, Resources And Supervision

Confirmed worker death preceded Gemma load: 566/579 ms after request start versus
load entry at 4416/4247 ms. No encoder/Gemma residency overlap was observed.
Telemetry reported zero failed/dropped samples and lowMemory=false/thermal=0
throughout both retained case traces. Minimum sampled available RAM was
2481352704/2978836480 bytes. The explicit QA profile permits the available-RAM
floor bypass; this does not establish allocation safety for ordinary app policy.
Maximum sampled app PSS was 2169811968/2211741696 bytes, not a continuous heap
peak or leak measurement.

The host thermal monitor recorded maximum skin readings 36.9/36.4 C and thermal
status 0. Each run retained seven run-correlated supervision heartbeats and a
fresh status closed/reason null. Its cleanupConfirmed=false is the unused
loss-callback flag on normal supervision close, not failed normal runtime cleanup.
The runtime/encoder/finalCleanup records separately confirm cleanup. No safety
stop or 40-second emergency record occurred. The deadline cancellation generated
the recorded Task cancelled native callback; this is not cd02's schema error or
a passed targeted-cancellation case.

allocationValid=false does not itself diagnose unsafe memory or overlapping
workers: the allocation gate also requires total time below 30000 ms and rejects
these late cases. Actual timing, death/load order and resource samples were
reviewed separately rather than turning that aggregate flag into a new cause.

After both reports were captured, an optional read-only installed-package/hash
and original-device-report recheck returned device not found. No reconnect or
workload retry followed. That check is **unavailable**, recorded in the repeat's
post-run-readonly.json. The saved reports already contain confirmed cleanup and
closed supervision before this later loss of connectivity; no claim is made
about device state after disconnection.

## Preservation And Audit

Host-replayed classifyConstrained on both saved reports: exact match to each
blocked result. Verified run/freeze/plan/profile/authorization identities,
closed-supervision freshness, ledgers, all recorded source hashes and all 343
frozen source/input/proof/APK bindings per bundle. Current constrainedSnapshot
still matches the reviewed map. The original FAIL, follow-up PASS and original
freeze-review report/manifest remain unchanged.

Each run directory retains result.json, encoder.json, instrumentation.txt,
supervision.json, source-sha256.json, exact copied freeze/authorization,
APKs' hashes, separate plan/oracle, policy/preflight evidence and thermal/
heartbeat traces. The exact model strings were also saved, without trimming,
normalization, repair or added newline, as cd01-model-output.raw.txt. Their
received UTF-16-unit counts and canonical hex match the original reports;
diagnosticTruncated=false in both.

| Artifact | Initial SHA-256 | Repeat SHA-256 |
| --- | --- | --- |
| encoder.json | `7ff0db75e65a634ab3a56bc01edcbc387982d1a4c3049de591c0df94b0c78a9a` | `c0fb39c6571fdeed60556b5baf4289b634742ebafa879786df78ccacecc3c25f` |
| cd01-model-output.raw.txt | `3d96e874505dc7fca68472a3003b61fbbebcc95a09cf14518902b5602054a67b` | `fc67a0c0c860736c338a09c90e64e4085ed75dc38a9ca5122efba5f4bd96eb79` |

Passed: reviewed admission/identities, private frozen APK handling, first-block
stopping, preserved diagnostics, confirmed cleanup and host evidence replay.
Blocked: complete constrained-output qualification on cd01, twice.
Not run: cd02-cd09 in either attempt, further repetitions or any fixes.
Unavailable: optional post-disconnection device package recheck and SDK terminal
token counters for the timed-out requests.

New authored artifacts are this results document, the two explicit device
authorizations, the repeat bundle/correspondence report and its attestation, and
normal ignored run/ledger/raw-audit outputs. No production source, test,
configuration, schema, original frozen byte, prior report or Git state changed.
There was no internal retry, output repair or unconstrained fallback. Further
native work requires another explicit human instruction. Both failures remain
preserved; semantic enablement is false and P2 remains incomplete.
