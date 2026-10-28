# Constrained Output: Whitespace Investigation

2026-10-27. Host-only diagnosis following the reviewed V2 failures. No ADB,
native model execution, runtime/schema/validator change, deadline increase,
output repair or Git operation. P2 remains incomplete; semantic search is disabled.

## Conclusion

The pinned source path permits arbitrarily repeated JSON whitespace. It masks
disallowed tokens but does not, by itself, require progress to a completed value.
The observed newline tail is therefore consistent with an unfinished but
extendable JSON prefix, rather than proof that constraints were ignored.

The application requests greedy sampling (`topK=1`, temperature 0). A plausible
mechanism is repeated selection of an allowed newline over the required digit.
This is a hypothesis, not a measured explanation: neither run retained sampled
token IDs, allowed-token masks or logits, and terminal token counts were
unavailable. A tokenizer/mask mismatch or another native integration defect
has not been excluded. No root cause is established by this audit.

## Saved Observations

Both raw outputs are preserved exactly. Each reaches the citation's `"b":` key,
then emits only newlines until cancellation. Neither contains the required
integer or a completed object. Strict validation remains `NOT_REACHED`.

| Relative time | Initial attempt | Explicit repeat |
| --- | ---: | ---: |
| Encoder worker death confirmed | 566 ms | 579 ms |
| Gemma load entered | 4416 ms | 4247 ms |
| Conversation/provider setup entered | 13767 ms | 13779 ms |
| Setup returned | 16146 ms | 16414 ms |
| First text | 16882 ms | 16999 ms |
| Deadline decision | 30030 ms | 30010 ms |
| Whole case finalized | 30618 ms | 30625 ms |
| Trailing newline characters | 228 | 187 |

Load entry to setup entry includes the model load path and intervening work;
it is not an independently isolated native load benchmark. Setup-to-return
includes conversation/provider creation, not necessarily the per-request schema
compilation, which occurs through the later send path. None of these stages may
be removed from the approved complete clock.

Cold preparation leaves approximately 13 seconds after first text, and the
remaining output does not finish. More time alone is not an established fix.
`lowMemory=false`, thermal status 0 and confirmed cleanup were recorded in both
runs. No recorded pressure/thermal stop explains these timeouts. The misleading
shared `ENCODER_TIMEOUT` name denotes the whole deadline; the encoder had already
released before Gemma loaded. See the [unchanged results report](P2_CONSTRAINED_DECODING_V2_RESULTS.md).

## Pinned Source Trace

The existing artifact audit pins LiteRT-LM 0.17.1 to inspected source commit
`5e58e9a0aef7abf7091207a8b1d1063a1c800f08`. This remains source correspondence,
not a reproducible native-build proof.

1. The app's `LiteRtLlmEngine` enables the response format, supplies the frozen
   schema, disables thinking/tools and requests greedy sampling. It does not
   supply whitespace restrictions or repetition controls.
2. The pinned provider invokes `llg_new_constraint_json`, using default
   constraint initialization. Its [provider source](https://github.com/google-ai-edge/LiteRT-LM/blob/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/runtime/components/constrained_decoding/llg_constraint_provider.cc)
   and [decoder source](https://github.com/google-ai-edge/LiteRT-LM/blob/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/runtime/components/constrained_decoding/constrained_decoder.cc)
   trace schema compilation and token-mask application. The non-host logits
   path copies logits to host memory, masks, and writes back. Its contribution
   to these runs' latency is not separately measured.
3. The pinned [Cargo lock](https://github.com/google-ai-edge/LiteRT-LM/blob/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/Cargo.lock)
   selects LLGuidance 1.3.0 and toktrie 1.5.0. Both downloaded source archives
   match their Cargo SHA-256 checksums. The WORKSPACE's five LLGuidance patches
   replace `is_multiple_of` expressions with equivalent remainder tests; they
   do not change the inspected JSON whitespace rule. The toktrie patch likewise
   changes a divisibility expression.
4. In LLGuidance 1.3.0 `src/json/compiler.rs`, default options are
   `whitespace_flexible=true` and `whitespace_pattern=None`. The compiler's
   skip expression is `[\x20\x0A\x0D\x09]+`. Its JSON entry point and grammar
   construction route through these defaults. The frozen sentinel has no
   `x-guidance` override. This demonstrates a source-level whitespace allowance,
   not a replay of the exact native token mask at `"b":`.
5. A separate tokenizer concern remains worth testing: the inspected
   SentencePiece `GetTokens()` returns raw vocabulary pieces, which the provider
   passes as token bytes; the inspected LLGuidance FFI constructs its trie from
   those bytes without decoding them. For example, the pinned space piece is
   `▁`, while its decoded text is a space. This difference is not itself a
   reproduced defect in the observed numeric state. The digit `0` and newline
   pieces examined here have matching raw/decoded bytes. Do not claim this
   concern caused the loop without a faithful mask/tokenizer replay.

The compiler also exposes whitespace options through `x-guidance` in its source.
This is an investigation lead only. Adding such an override would change frozen
schema bytes and require a new reviewed protocol and renewed prerequisite checks.
No override, suppression, sampling change or fallback is adopted here.

## Host Checks

The ignored diagnostic program is
`build/constrained-whitespace-audit/analyze.py`. It was run with the existing
reference environment; no new dependency was installed or compiled.

- Verified both saved raw-output SHA-256 values, equality with the case's
  diagnostic text, lack of truncation, incomplete JSON and exact failure field.
- Constructed eight **new synthetic** sentinel objects with 0, 1, 2, 16, 187,
  228, 512 and 4096 newlines before the integer. All parse to the same value and
  pass the unchanged Draft-07 reference schema. These are separate explanatory
  witnesses; no saved response was completed, reclassified or credited.
- Rejected four synthetic sentinel values for `b`: -1, 1, string `"0"`, and null.
- Verified the exact embedded tokenizer SHA-256 and five round trips. Digit `0`
  is token 236771 with literal/decoded text `0`; newline is token 107; two
  newlines are token 108. A newline character count is not a decode token count.
- Retained downloaded source bytes and hashes, the program, exact observation
  offsets and the 12 reference checks in the ignored audit directory.

The reference JSON checks do not execute LLGuidance or establish its actual
mask behavior. Tokenizer inspection does not execute Gemma. No claim of native
schema enforcement, quality, throughput or complete cancellation coverage follows.

Audit result: `build/constrained-whitespace-audit/analysis.json`, SHA-256
`8fe9ad3a1835dc9d1343bc8f965b14aa6a33fa33f2a9199e64d23a00edc1f338`.

Newly inspected dependency identities:

| Artifact | SHA-256 |
| --- | --- |
| Pinned WORKSPACE | `dee68de49f5212a0b12d47d80fb59f63314e74c1655a60e3f11a055d20d0507a` |
| Pinned Cargo.lock | `b95103f9b316784814f1e39cc0124cbc134e61d3f0f27c7ef514408e65b7916a` |
| LLGuidance 1.3.0 source archive | `614b6ece5bc57641b9b727f8a79d338a46584efb943017d0a7454442c0b947fb` |
| toktrie 1.5.0 source archive | `de81c795b1f2e5b7e531fbb587e541e124b47f434af2c427a4ae73ea0d4eca6c` |

## Next Decision

Before proposing another device experiment, use a separately scoped host
reproduction of the pinned grammar/tokenizer boundary to establish whether both
newline and `0` remain allowed at the observed numeric state, and whether raw
vocabulary pieces agree with the bytes used for emitted output. If an equivalent
replay cannot be established, record that limit instead of declaring a root cause.

Any later native proposal must explicitly separate progress/whitespace behavior
from cold-start cost and retain the complete deadline, strict validator,
ownership and cleanup guarantees. It needs a reviewed scope and separate device
authorization. The stopped eight cases stay stopped, both failures remain
failures, and no identical rerun or deadline increase is proposed by this audit.
