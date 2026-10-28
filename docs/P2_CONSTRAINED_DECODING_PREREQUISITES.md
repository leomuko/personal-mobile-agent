# Constrained-Decoding V2 Prerequisites

2026-10-26. **Host prerequisites passed; native enforcement remains unverified.**
The user approved integration of the bounded nine-request proposal. This report
and the integration authorization do not authorize a device workload.

## Reproducible Reference Environment

`scripts/requirements/constrained-decoding.txt` pins all eight dependencies and
wheel SHA-256 values for **CPython 3.9 on macOS arm64**. The run used Python 3.9.6,
jsonschema 4.25.1, SentencePiece 0.2.0 and protobuf 5.28.3. Other host platforms have
not been qualified by this environment lock. No dependency was added to Android.

From the repository root, with the already downloaded pinned Gemma artifact:

```sh
bash scripts/verify-constrained-decoding-prerequisites.sh
```

The wrapper creates a private virtual environment, installs hash-pinned binary
packages and retrieves two hash-pinned official runtime source files if missing.
It does not download model weights. The proof is removed **before setup**. A failed
setup, test, identity check or proof cannot leave an earlier success admissible.
Critical checks use explicit exceptions rather than optimizable Python assertions.

The local result is `build/constrained-decoding/prerequisites.json`; setup/test
output is retained in `build/constrained-decoding/prerequisites-run.txt`. The
execution gate checks its version, preparation lock, dependency identities,
results and every input/source hash. The later execution freeze also binds the
proof bytes. No device admission follows from a proof by itself.

## Draft-07 Results

Using the [pinned reference API](https://python-jsonschema.readthedocs.io/en/v4.25.1/api/jsonschema/validators/):

- Both positive schemas passed Draft-07 meta-schema validation.
- The deliberately invalid `type` failed meta-schema validation.
- The base sentinel passed both positive schemas.
- All **34 frozen checks against each schema** matched their expected results.
- Eight Python regressions passed, including full-wrapper failed-setup invalidation
  and synthetic one-token counterexamples.

Integer-valued `1.0` follows Draft-07 integer semantics. The 32-emoji quotation
passes the evidence schema's character limit but fails the unchanged strict
validator's byte limit. Schema acceptance is not citation acceptance. Android-host
tests independently replay the base and 34 frozen strict-validator checks; the
original 83-case regression fixture and validator remain unchanged.

## Exhaustive One-Token Proof

The complete 2,588,147,712-byte model was SHA-256 verified. Its 4,689,013-byte
tokenizer section was independently hashed before parsing:

- Model: `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`
- Tokenizer: `e594c8a90eb08d8bda498ff4747977dc827ae0c3c56b5c0d41a605a22d02ef03`

All **262,144 IDs** were examined, including normal, user-defined, byte, control,
unused and unknown tokens. For each ID the verifier checks both decoded output
and the literal piece with metaspace replaced. It also checks all **260
brace-bounded substrings**, preventing boundary-marker stripping from hiding a
complete response. Of **212 parseable JSON candidates**, **zero** satisfy the
frozen evidence schema. Counterexample tests prove either representation, including
an embedded complete object, would reject the proof.

The deterministic decoded-vocabulary digest is
`aba49cf84613bd47aec32c6cf851dfe85b472fdeade1254d775e7f38237aeca6`.
No vocabulary contents or model binaries are exported into Git.

The pinned runtime's [SentencePiece wrapper](https://github.com/google-ai-edge/LiteRT-LM/blob/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/support/tokenizer/sentencepiece_tokenizer.cc)
delegates ordinary ID decoding to SentencePiece and returns literal single control
pieces. Its [streaming detokenizer](https://github.com/google-ai-edge/LiteRT-LM/blob/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/support/tokenizer/buffered_streaming_detokenizer.cc)
buffers/releases decoded text and flushes the remainder; it does not synthesize
citation fields. Both exact upstream files are hash-bound in the proof and retained
locally. Source inspection is not a reproducible native-binary equivalence proof.

This establishes the vocabulary prerequisite for **cd07 only**: one token cannot
contain a complete schema-valid response under the pinned output mapping. It does
not prove GPU enforcement, SDK counter/termination behavior or actual exhaustion.
The native negative guard still requires one measured output token, a nonempty JSON
prefix, terminal completion, strict rejection and confirmed cleanup. Empty output,
EOS-like text or an arbitrary exception cannot pass. The output reservation stays
**512**; only cd07's decode cap is **1**.

## Preserved Boundary

The 14 preparation bindings, original proposal, schemas and cases remain unchanged.
Their historical pending flags are preserved; separate authorization/results
supersede those status statements without rewriting frozen bytes. Native behavior,
S24 feasibility and semantic accuracy are not established. P2 remains incomplete
and semantic search stays disabled.
