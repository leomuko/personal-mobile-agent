# S2a ordinary-chat context profiles

2026-11-16. The shared conversation session supplies a named profile to
`GemmaChatBudget.build` for new and retried ordinary replies. Each profile
reserves 512 tokens for output and is checked against the pinned Gemma
manifest's 32,000-token artifact context. Budget v2 still charges a fixed 128
units plus 96 and the UTF-8 bytes per message.

| Profile | Context tokens | Input bound ceiling | Maximum single ASCII message |
| --- | ---: | ---: | ---: |
| Standard | 2,048 | 1,536 | 1,312 bytes |
| Extended 4096 | 4,096 | 3,584 | 3,360 bytes |
| Extended 8192 | 8,192 | 7,680 | 7,456 bytes |

History selection starts with the most recent complete turn and works
backwards. The request carries the retained turns in chronological order.
Unsupported context and output pairs fail with `CONTEXT_LIMIT`. A default
`ChatRequest` still uses 2048/512, and the prepared foreground path explicitly
retains that admission rule after general budget validation was widened.

`ModelManager` records the successful native load's `ModelConfig` under its
existing operation mutex. It compares the resident backend, context and
output reservation with the request. On the same backend, a size mismatch
cancels idle release and awaits confirmed release, then clears the recorded
config, checks cancellation and loading admission, verifies the model, and
loads the requested config. It records the new config on successful load and
checks cancellation and generation admission before generating. A backend
mismatch in chat returns `BUSY`.

The Models panel uses the manifest's 2048/512 config on the selected backend.
If an extended engine is resident, asking the panel to load the same backend
returns it to the manifest config. A different backend in this case returns
`BUSY`; switching backends still requires a separate unload. An already
loaded manifest-sized engine retains the panel's existing no-op behavior.

Cancellation during confirmed release keeps ownership until release ends and
leaves the engine unloaded. Cancellation during non-cancellable native load
allows the successful load to publish the requested config and prevents chat
generation. An unconfirmed release poisons the manager and returns
`RESTART_REQUIRED`. Fake-engine tests check both cancellation points,
competing admission, memory rejection after reclaim, backend mismatch and
exact release/load order across ordinary, prepared and panel requests.

Set `-PchatContextTokens=2048|4096|8192` on a `secureQa` build. The default is
2048. Invalid values fail Gradle configuration. Generated debug and release
configs use 2048, including when the override is set to 8192. Extended
profiles are unmeasured and remain QA selections pending S2b.

The cached LiteRT-LM Android 0.17.1 AAR contains the public zero-argument
`Conversation.getTokenCount(): Int`. Bytecode checks that the conversation
is alive, then calls `LiteRtLmJni.nativeConversationGetTokenCount(handle)`.
The inspected `Engine`, `Session` and `Conversation` APIs have no public
counter that accepts arbitrary prompt text for preflight. The existing
`BenchmarkInfo.getLastPrefillTokenCount()` reports a previous prefill.

Offline host checks passed. The shared suite had 220 tests, including 218
passes and two existing tokenizer opt-in skips. This includes 34 passage
tests and 43 existing foreground-handoff and verification tests. Migration
verification was up to date. Debug assembly, lint (zero errors, seven
warnings), all 28 app debug unit tests and the `secureQa` assembly with 4096
passed. Generated build config checks confirmed the default and the 8192
override. The 3072 build failed with the expected validation message.
`androidMain` was unchanged, so the optional Android-host task was skipped.

## 2026-11-18: round-2 QA corrections

S2a-F1 is fixed by checking the resident context and output reservation under
the operation mutex before admitting a panel prompt. An extended engine yields
`BUSY` and stays loaded with its existing idle deadline. Standard 2048/512 panel
prompts run normally.

S2a-F2 is fixed by keeping idle cleanup in the timer's own job. It captures the
successful load's `ModelConfig` instance and checks reference identity, the current
idle job, loaded status and absence of active owners under the mutex before
confirmed cleanup. A lazily started job installs its identity before execution.
The cleanup uses the existing releasing counter and release implementation.

Added `panelPromptsRejectExtendedContextsWithoutResettingIdle`,
`panelPromptsUseTheStandardContextAfterOrdinaryChat`,
`expiredIdleReleaseCannotUnloadPanelReplacement`,
`expiredIdleReleaseCannotUnloadChatReplacement` and
`idleReleaseUnloadsCurrentEngineAtItsOwnDeadline`. The race tests use a controlled
dispatcher, virtual time and a gated fake load. Before the fixes, the extended
prompt and both replacement regressions failed. All 15 profile tests pass after
the fixes. Fresh full checks passed with 223 JVM tests, two existing tokenizer
opt-in skips and 28 app unit tests. Migration verification was up to date, debug
assembly passed, and lint reported zero errors and seven warnings. Logs are in
`/tmp/s2a-round2-egej6zam/`. The larger profiles remain unmeasured pending S2b.

## 2026-11-18: round-3 cancellation and idle cleanup

S2a-F3 is fixed by waiting for `cancellationSignals` before taking the operation
mutex, then revalidating the idle job, load instance, loaded status, active owners
and poison state. The wait stays cancellable so public or host-loss release can
take cleanup ownership. Waiting preserves the elapsed idle deadline without
restarting the timer. The releasing counter still excludes a public release in
progress after cancellation has settled.

Added `ModelManagerIdleCancellationTest` with
`idleCleanupWaitsForPendingCancellationAndReleasesExactlyOnce`,
`publicAndHostLossReleaseOwnCleanupWhileCancellationIsPending` and
`replacementSurvivesIdleWaiterAfterCancellationSettles`. Controlled dispatch,
virtual time and a gated cancellation signal check the exact engine events,
including public/host-loss cleanup and panel/chat replacement. The first test
failed on the round-2 implementation, with the engine still loaded. All three
pass after the fix, alongside all 15 profile tests. Fresh full checks passed
with 226 JVM tests, two existing tokenizer opt-in skips and 28 app unit tests.
Migration verification was up to date, debug assembly passed, and lint reported
zero errors and seven warnings. Test sources remain under `commonTest/inference`;
before/after logs are in `/tmp/s2a-round3-43cjbysc/`. Independent QA is pending.
