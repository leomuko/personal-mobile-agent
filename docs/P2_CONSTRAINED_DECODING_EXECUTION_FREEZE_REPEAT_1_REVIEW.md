# Constrained-Decoding V2 Explicit Repeat: Freeze Correspondence

2026-10-26. Same retained primary independent QA reviewer: OpenAI Codex.
No delegation. **PASS for `constrained-decoding-v2-execution-freeze` only.**
This correspondence review grants no device permission.

After the first run blocked, the human explicitly instructed: `run the test again`.
This is one separately authorized repeat of the same bounded test, not a repair,
an extended deadline, or erasure of the failed attempt. The old freeze, review,
authorization, raw outputs and one-attempt ledger remain preserved. The existing
ledger cannot be reused or deleted, so the repeat has its own immutable bundle
and will require its own authorization and retained ledger.

New bundle: `benchmarks/results/p2-constrained-decoding-execution-freeze-20261026-repeat-1`.
Actual execution-freeze SHA-256:
`ada0cad3d96d20225ef36ebda0c43585d4bd58e9782847d9c5969bcd75e9dbcc`.
Prior freeze:
`706f33e820924dc5201d92c49d76abbff1fd64a5c21a9f11cc71a01908eeccb6`.
Prior run: `43050c47-fb14-46aa-909c-bc93d768f6a0`.

Prepared through the reviewed freeze function. Independently compared every
top-level field with the prior reviewed freeze: only createdAtMs differs.
The source/input/proof map, implementation manifest, plan, oracle, profile,
build recipe and APK pair are identical. Rehashed all 343 new frozen copies
and current counterparts: all match. Recomputed current constrainedSnapshot:
341 entries, SHA-256
`158630554bae1071105b3b50d24bed342d142178b51a71780601b983da4f9d9b`.
Profile SHA-256:
`83c6e3f2ac535be423bfd0e359546098c51743be3bbf52353ca065d87ff85113`.

The exact same signed APK bytes retain the prior manifest/DEX/JNI/private-copy
correspondence evidence; no APK was rebuilt. App SHA-256:
`cdd57923977f25bb335a54b679e05be6ea29b396d1c178d0c54f7c210a0670fe`;
test SHA-256:
`57da1c7fdd5e706065c02b451e1c6adff21a93c904a368c14aa34ae728848403`.

Nine ordered cases, strictly below 30000 ms including cleanup, isolated
memory-android-pressure-test-v1, GPU/2048 context/512 reservation and cd07-only
cap 1 remain unchanged. Stop at the first unexpected/blocking result; no repair,
fallback or internal retry. Native feasibility, schema enforcement and semantic
quality remain unqualified. The first timeout remains a blocking result; a later
repeat cannot erase it or convert these exposed cases into independent accuracy
evidence. No semantic enablement or P2 closure.

This new correspondence record is separate from the new human authorization.
No device workload was performed while preparing/reviewing this bundle. No
source, tests, schema, previous frozen bytes, prior reports or Git state changed.
