#!/usr/bin/env python3
"""Host verifier: sentencepiece==0.2.0, protobuf==5.28.3. Exports no model or personal data."""
import hashlib
import json
import random
import argparse
from pathlib import Path
try:
    import sentencepiece as spm
    from sentencepiece import sentencepiece_model_pb2
except ImportError as error:
    raise SystemExit("Missing verifier dependencies. Run bash scripts/verify-chat-budget.sh to install pinned host dependencies.") from error

MODEL_SHA = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
TOKENIZER_SHA = "e594c8a90eb08d8bda498ff4747977dc827ae0c3c56b5c0d41a605a22d02ef03"
TOKENIZER_BYTES = 4689013


def require(condition, message):
    if not condition:
        raise SystemExit("Budget verification failed: " + message)


def verify_digest(actual, expected, label):
    require(actual == expected, label + " SHA-256 mismatch")


def validate_tokenizer(spec):
    require(spec.trainer_spec.model_type == sentencepiece_model_pb2.TrainerSpec.BPE, "tokenizer is not BPE")
    require(spec.trainer_spec.byte_fallback, "byte fallback is disabled")
    require(spec.normalizer_spec.name == "identity" and not spec.normalizer_spec.precompiled_charsmap,
            "normalization is not unmapped identity")
    require(not spec.normalizer_spec.add_dummy_prefix, "dummy prefix is enabled")
    require(not spec.normalizer_spec.remove_extra_whitespaces, "whitespace removal is enabled")
    require(spec.normalizer_spec.escape_whitespaces, "whitespace escaping is disabled")
    require(any(piece.piece == "\u2581" and piece.type == sentencepiece_model_pb2.ModelProto.SentencePiece.NORMAL
                for piece in spec.pieces), "metaspace is not a NORMAL seed token")


def check_budget(count, bound, case):
    require(0 <= count <= bound, case + " exceeded its token bound")


def verify(model):
    with model.open("rb") as source:
        hasher = hashlib.sha256()
        for block in iter(lambda: source.read(1024 * 1024), b""):
            hasher.update(block)
        digest = hasher.hexdigest()
        verify_digest(digest, MODEL_SHA, "model")
        # Offsets belong only to this hash-pinned artifact, never arbitrary imports.
        source.seek(32768)
        tokenizer = source.read(TOKENIZER_BYTES)
    require(len(tokenizer) == TOKENIZER_BYTES, "tokenizer section is truncated")
    tokenizer_digest = hashlib.sha256(tokenizer).hexdigest()
    verify_digest(tokenizer_digest, TOKENIZER_SHA, "tokenizer")
    spec = sentencepiece_model_pb2.ModelProto()
    spec.ParseFromString(tokenizer)
    validate_tokenizer(spec)
    processor = spm.SentencePieceProcessor(model_proto=tokenizer)
    rng = random.Random(0)
    texts = ["", "Hello", "a " * 200, "\u65e5\u672c\u8a9e", "\U0001f642" * 100,
             "\ufdfa", "\x00\t\n", "e\u0301", "\u00a0\u3000"]
    for _ in range(2000):
        points = [rng.randrange(0x110000) for _ in range(rng.randrange(1, 120))]
        texts.append("".join(chr(p) for p in points if not 0xD800 <= p <= 0xDFFF))
    for case, text in enumerate(texts):
        # Mirrors the audited text-only USER template; never used to construct app prompts.
        rendered = "<bos><|turn>user\n" + text.strip() + "<turn|>\n<|turn>model\n"
        bound = 128 + 96 + len(text.encode("utf-8"))
        check_budget(len(processor.encode(rendered)), bound, "single-turn case " + str(case))
    for case, (left, right) in enumerate(zip(texts[:1000], texts[1000:2000])):
        rendered = ("<bos><|turn>user\nHi<turn|>\n<|turn>model\n" + left.strip() +
                    "<turn|>\n<|turn>user\n" + right.strip() + "<turn|>\n<|turn>model\n")
        bound = 128 + 3 * 96 + 2 + len(left.encode("utf-8")) + len(right.encode("utf-8"))
        check_budget(len(processor.encode(rendered)), bound, "history case " + str(case))
    prefill = "Summarize the following synthetic number sequence in one sentence: " + " ".join(str(i) for i in range(1, 385))
    prefill_hash = hashlib.sha256(prefill.encode("utf-8")).hexdigest()
    verify_digest(prefill_hash, "e769e953b92ff0677b861257131627d3558d06de671c645fbfe08bee7bf13f5c", "supervision fixture")
    rendered = "<bos><|turn>user\n" + prefill + "<turn|>\n<|turn>model\n"
    prefill_tokens = len(processor.encode(rendered))
    require(prefill_tokens + 64 + 512 <= 2048, "supervision fixture exceeds context including template allowance/output reserve")
    return {"status": "passed", "modelSha256": digest, "tokenizerSha256": tokenizer_digest,
            "singleTurnCases": len(texts), "historyCases": 1000, "calculator": "gemma4-e2b-byte-bound-v2",
            "supervisionPrefill": {"sha256": prefill_hash, "inputTokens": prefill_tokens,
                                   "templateAllowance": 64, "outputReserve": 512, "contextTokens": 2048}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, default=Path(__file__).resolve().parents[1] / "models/gemma-4-E2B-it.litertlm")
    model = parser.parse_args().model
    if not model.is_file():
        parser.error("Pinned model artifact not found. Supply --model PATH; the verifier does not download weights.")
    try:
        result = verify(model)
    except Exception as error:
        raise SystemExit("Budget verification failed: " + str(error)) from error
    print(json.dumps(result))


if __name__ == "__main__":
    main()
