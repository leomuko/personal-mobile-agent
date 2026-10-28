#!/usr/bin/env python3
"""Host-only Draft-07 and exhaustive single-token prerequisite checks. No inference."""
import argparse
import copy
import hashlib
import importlib.metadata
import json
from pathlib import Path
import runpy
import struct
import time

from jsonschema import Draft7Validator
from jsonschema.exceptions import SchemaError
import sentencepiece as spm
from sentencepiece import sentencepiece_model_pb2 as pb

LOCK_SHA = "5e8edd23535d26c9985a28fd59e51c29b60974561580c0f807fec0f5b297e1d9"
FIXTURES = Path("planning/fixtures/constrained-decoding-v2")
VERSIONS = {"jsonschema": "4.25.1", "attrs": "26.1.0", "jsonschema-specifications": "2025.9.1",
            "referencing": "0.36.2", "rpds-py": "0.27.1", "typing-extensions": "4.16.0",
            "sentencepiece": "0.2.0", "protobuf": "5.28.3"}
DETOKENIZER_SOURCES = {
    "build/output-contract-audit/sentencepiece_tokenizer.cc": "622dbda6c5c5c51a1d8a293f75a54603393cd420824c9f4372bcbc02d4bbae65",
    "build/output-contract-audit/buffered_streaming_detokenizer.cc": "1c3696cdf069b1ca6d4518bb6012d3639f17a7153da29026902d1735c0c2016c",
}


def require(value, message):
    if not value:
        raise SystemExit("Constrained-decoding prerequisite failed: " + message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def frozen(root):
    lock_bytes = (root / FIXTURES / "preparation-lock.json").read_bytes()
    require(sha(lock_bytes) == LOCK_SHA, "preparation lock mismatch")
    lock = json.loads(lock_bytes)
    for name, digest in lock["files"].items():
        require(sha((root / name).read_bytes()) == digest, "frozen file changed: " + name)
    return lock


def materialize(base, case):
    if "rawOutput" in case:
        return json.loads(case["rawOutput"])
    value = copy.deepcopy(base)
    mutation = case["mutation"]
    pointer = mutation["path"]
    if pointer == "":
        require(mutation["op"] == "replace", "invalid root mutation")
        return mutation["value"]
    keys = [part.replace("~1", "/").replace("~0", "~") for part in pointer.split("/")[1:]]
    target = value
    for key in keys[:-1]:
        target = target[int(key)] if isinstance(target, list) else target[key]
    key = int(keys[-1]) if isinstance(target, list) else keys[-1]
    if mutation["op"] == "remove":
        del target[key]
    else:
        target[key] = copy.deepcopy(mutation["value"])
    return value


def reference_checks(root):
    schemas = {name: read_json(root / FIXTURES / (name + ".schema.json"))
               for name in ("evidence-output", "keyword-sentinel", "invalid-type")}
    for name in ("evidence-output", "keyword-sentinel"):
        Draft7Validator.check_schema(schemas[name])
    try:
        Draft7Validator.check_schema(schemas["invalid-type"])
    except SchemaError:
        pass
    else:
        require(False, "deliberately invalid schema accepted")
    checks = read_json(root / FIXTURES / "host-keyword-checks.json")
    evidence = Draft7Validator(schemas["evidence-output"])
    sentinel = Draft7Validator(schemas["keyword-sentinel"])
    require(evidence.is_valid(checks["baseOutput"]) and sentinel.is_valid(checks["baseOutput"]), "invalid base sentinel")
    records = []
    require(len(checks["cases"]) == 34, "incomplete reference cases")
    for case in checks["cases"]:
        instance = materialize(checks["baseOutput"], case)
        errors = {"sentinel": list(sentinel.iter_errors(instance)), "evidence": list(evidence.iter_errors(instance))}
        for name, field in (("sentinel", "sentinelSchemaValid"), ("evidence", "evidenceSchemaValid")):
            require((not errors[name]) == case["expected"][field], case["id"] + ": " + name + " mismatch")
        records.append({"id": case["id"], "errors": {name: sorted(set(str(error.validator) for error in items))
                                                     for name, items in errors.items()}})
    return evidence, {"positiveMetaSchemas": 2, "negativeMetaSchemas": 1, "baseInstances": 2,
                      "mutationInstancesPerSchema": len(records), "cases": records}


def single_token_proof(pieces, decode, validator):
    """Inspect all IDs, including control/user-defined/byte/unused IDs; no sampling.

    Check the SDK-independent decoded form AND the broader literal-piece form with
    metaspace replaced. Initial whitespace removal cannot turn an invalid JSON
    object into a valid one: JSON loading already ignores surrounding whitespace.
    Byte tokens can emit at most one byte, hence cannot make a complete root object.
    Inspect every brace-bounded substring too: stripping boundary markers cannot
    hide a complete object inside an otherwise non-JSON vocabulary piece.
    """
    digest = hashlib.sha256()
    parseable = valid = substrings = 0
    for index, piece in enumerate(pieces):
        variants = (decode(index), piece.replace("\u2581", " "))
        for variant in variants:
            encoded = variant.encode("utf-8")
            digest.update(struct.pack("<II", index, len(encoded)))
            digest.update(encoded)
            candidates = [variant]
            for begin, char in enumerate(variant):
                if char == "{":
                    for end in range(begin + 1, len(variant)):
                        if variant[end] == "}":
                            candidates.append(variant[begin:end + 1])
                            substrings += 1
            for candidate in candidates:
                try:
                    instance = json.loads(candidate)
                except (ValueError, TypeError):
                    continue
                parseable += 1
                if validator.is_valid(instance):
                    valid += 1
    require(valid == 0, "a single vocabulary token can satisfy the evidence schema")
    return {"vocabularyEntries": len(pieces), "variantsPerEntry": 2, "braceBoundedSubstrings": substrings, "parseableJsonVariants": parseable,
            "schemaValidVariants": valid, "decodedVocabularySha256": digest.hexdigest(),
            "claim": "No single token in this pinned vocabulary yields a complete evidence-schema-valid JSON response"}


def run(root, model):
    for name, version in VERSIONS.items():
        require(importlib.metadata.version(name) == version, name + " version mismatch")
    lock = frozen(root)
    for path, digest in DETOKENIZER_SOURCES.items():
        require(sha((root / path).read_bytes()) == digest, "pinned runtime detokenizer changed: " + path)
    validator, reference = reference_checks(root)
    budget = runpy.run_path(str(root / "scripts/verify-chat-budget.py"))
    model_hash = hashlib.sha256()
    with model.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            model_hash.update(block)
        require(model_hash.hexdigest() == budget["MODEL_SHA"], "model SHA-256 mismatch")
        source.seek(32768)
        tokenizer = source.read(budget["TOKENIZER_BYTES"])
    require(sha(tokenizer) == budget["TOKENIZER_SHA"], "tokenizer SHA-256 mismatch")
    spec = pb.ModelProto()
    spec.ParseFromString(tokenizer)
    budget["validate_tokenizer"](spec)
    require(not spec.HasField("denormalizer_spec") or not spec.denormalizer_spec.precompiled_charsmap,
            "unreviewed output denormalization")
    processor = spm.SentencePieceProcessor(model_proto=tokenizer)
    require(len(spec.pieces) == processor.get_piece_size() == 262144, "vocabulary size mismatch")
    proof = single_token_proof([piece.piece for piece in spec.pieces], lambda i: processor.decode([i]), validator)
    # Recheck mutable files after the long model/vocabulary pass, before publication.
    frozen(root)
    bindings = dict(lock["files"])
    bindings.update(DETOKENIZER_SOURCES)
    for name in ("scripts/verify-constrained-decoding.py", "scripts/verify-constrained-decoding-prerequisites.sh",
                 "scripts/requirements/constrained-decoding.txt", "scripts/verify-chat-budget.py",
                 "scripts/tests/test_constrained_decoding.py"):
        bindings[name] = sha((root / name).read_bytes())
    return {"schema": 1, "protocolId": lock["protocolId"], "status": "passed", "createdAtMs": int(time.time() * 1000),
            "preparationLockSha256": LOCK_SHA, "dependencies": VERSIONS,
            "modelSha256": model_hash.hexdigest(), "tokenizerSha256": sha(tokenizer),
            "runtimeDetokenizerCommit": "5e58e9a0aef7abf7091207a8b1d1063a1c800f08",
            "reference": reference, "oneTokenProof": proof, "files": bindings,
            "nativeEnforcementVerified": False, "deviceExecutionAuthorized": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--model", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.unlink(missing_ok=True)
    result = run(args.root, args.model or args.root / "models/gemma-4-E2B-it.litertlm")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(".tmp")
    temporary.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    temporary.replace(args.output)
    print(json.dumps({"status": "passed", "proof": str(args.output), "oneTokenProof": result["oneTokenProof"]}))


if __name__ == "__main__":
    main()
