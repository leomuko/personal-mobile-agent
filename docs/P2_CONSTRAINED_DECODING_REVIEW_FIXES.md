# Constrained-Decoding V2 Review Fixes

2026-10-26. The initial independent FAIL report remains unchanged at
`docs/P2_CONSTRAINED_DECODING_IMPLEMENTATION_REVIEW.md`. These corrections address
CD-IMPL-001 and CD-IMPL-002 within the approved protocol. They require a follow-up
PASS before execution-freeze creation; this document is not that attestation.

## Callback Diagnostics And Revoked Delivery

Callback text is now captured before delivery/cancellation filtering, in a separate
bounded buffer owned by the same request's trace. The trace's points, raw capture
and seal flag share one immutable StateFlow state. Closing seals the capture;
stale callbacks cannot mutate retired evidence. Cleanup/error results carry the
captured prefix, received UTF-16-unit count and explicit truncation flag.

The retained prefix is limited to 1536 UTF-8 bytes; preprocessing copies at most
1537 incoming UTF-16 units. Further input after truncation updates the count without
growing the prefix. Truncation blocks a guard; it is never represented as complete
raw output. A canonical UTF-16 code-unit hex field and standard JSON surrogate
escapes preserve even an interrupted Unicode scalar during file serialization.
This is diagnostic encoding, not repair of a model response.

User delivery is still immediately revoked. Diagnostic text is not fed back into
generation or used to manufacture a successful response. A previously validated
candidate whose final raw capture differs from its delivered validation input is
revoked without replacing the latched decision. Decoding-cancellation guards also
reject empty or truncated diagnostics.

Regressions cover queued text discarded by delivery filtering, FIRST_TEXT-triggered
cancellation before collection, further callbacks during cancellation, callbacks
after terminal, post-close sealing, Unicode fragmentation, size bounds and a raw
mismatch after validation. Exact raw strings/code units remain observable while
eligible output remains absent.

## Temporal Evidence

Both shared and host classifiers now require publication <= send entry <= first
text <= terminal <= close whenever those points exist, and send entry <= terminal
even without text. Each targeted cancellation must still fall within its specific
observed interval. Missing/reversed boundaries block. A terminal callback before
the send method returns remains allowed; no artificial return-before-terminal
ordering or native timing delay was introduced.

The review's two contradictory-time probes have dedicated shared and Node
regressions, alongside missing-terminal and reversed-send/terminal checks. The
schemas, nine inputs, one-token exception, deadlines, memory profile and strict
validator are unchanged. Historical native failures are not reinterpreted.

## Execution Boundary

The user subsequently authorized the S24 experiment, conditional on completing the
review/freeze gates. Connectivity and unlocked-state checks confirmed the designated
S24. No APK installation or Gemma workload has run while these gates are pending.
After the same primary reviewer's follow-up PASS, create and independently verify
the actual freeze and bind the existing user authorization to it. Run only the
nine ordered requests, stopping on the first unexpected failure without retries.
P2 and native qualification remain incomplete; semantic search stays disabled.
