"""Host-only USE gate helpers; metadata and synthetic inputs only, never personal text."""
import hashlib
import json
import random
import struct
import subprocess
import time
from pathlib import Path

import tflite
from flatbuffers import flexbuffers

MODEL_SHA = "89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749"
CONFIG_SHA = "ab32418a9a7f2c0c95e3db6233f2fbd8243e2929a84cd5feec9c1b1ca615c420"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def inspect(path):
    require(path.stat().st_size == 6120274, "Encoder size mismatch")
    raw = path.read_bytes()
    require(hashlib.sha256(raw).hexdigest() == MODEL_SHA, "Encoder SHA mismatch")
    model = tflite.Model.GetRootAsModel(raw, 0)
    require(model.SubgraphsLength() == 1, "Unexpected graph count")
    graph = model.Subgraphs(0)
    require(graph.InputsLength() == 3, "Unexpected input count")
    branches = []
    configuration = None
    for index in range(graph.OperatorsLength()):
        op = graph.Operators(index)
        if model.OperatorCodes(op.OpcodeIndex()).CustomCode() != b"TFSentencepieceTokenizeOp":
            continue
        require(op.InputsLength() == 7 and op.OutputsLength() == 2, "Unexpected tokenizer signature")
        constant = lambda position: model.Buffers(graph.Tensors(op.Inputs(position)).Buffer()).DataAsNumpy().tobytes()
        config = constant(0)
        storage = model.Buffers(graph.Tensors(op.Inputs(0)).Buffer())
        require(storage._tab.Vector(storage._tab.Offset(4)) == 5584856, "Pinned tokenizer offset changed")
        require(hashlib.sha256(config).hexdigest() == CONFIG_SHA, "Tokenizer SHA mismatch")
        require(constant(2) == struct.pack("<i", 0) and constant(3) == struct.pack("<f", 0), "Sampling changed")
        require([constant(i) for i in (4, 5, 6)] == [b"\x01", b"\x01", b"\x00"], "Special tokens changed")
        require(flexbuffers.Loads(op.CustomOptionsAsNumpy().tobytes()) == {"Tsplits": 2, "out_type": 2}, "Tokenizer options changed")
        dense = graph.Operators(index + 1)
        require(model.OperatorCodes(dense.OpcodeIndex()).CustomCode() == b"RaggedTensorToTensor", "Unexpected dense conversion")
        require(dense.Inputs(1) == op.Outputs(0), "Dense conversion not bound to tokenizer")
        shape = model.Buffers(graph.Tensors(dense.Inputs(0)).Buffer()).DataAsNumpy().tobytes()
        require(shape == struct.pack("<ii", -1, 192), "Dense token limit changed")
        input_index = next((i for i in range(3) if graph.Inputs(i) == op.Inputs(1)), None)
        require(input_index is not None, "Tokenizer not fed directly by graph input")
        branches.append({"inputIndex": input_index, "inputName": graph.Tensors(op.Inputs(1)).Name().decode(),
                         "tokenizerSha256": CONFIG_SHA, "bos": True, "eos": True, "reverse": False, "limit": 192})
        configuration = config
    require(sorted(b["inputIndex"] for b in branches) == [0, 1, 2], "Missing/duplicate branch")
    return configuration, branches


def corpus():
    values = ["", " ", "\t\n\r", "   alpha   beta   ", "a\u00a0b", "\u212b", "A\u030a", "é", "e\u0301",
              "👩🏽‍💻 🚀", "中文 日本語 العربية हिन्दी", "\x00", "\ufeff", "\u200b", "ﬃ ① ㍍", "identifier_" * 120]
    # Consecutive lengths prove the 191/192/193 boundaries rather than guessing a character ratio.
    values.extend(" hello" * count for count in range(185, 196))
    rng = random.Random(1922048)
    alphabet = list("abc XYZ_0123\t\n ") + ["é", "\u0301", "\u00a0", "中", "😀", "\x00", "ﬃ", "\u200d"]
    values.extend("".join(rng.choice(alphabet) for _ in range(rng.randrange(0, 600))) for _ in range(1000))
    values.extend(chr(code) for code in [1, 9, 31, 127, 0x7ff, 0x800, 0xd7ff, 0xe000, 0xffff, 0x10000, 0x10ffff])
    return values


def create_vectors(model, binary, directory, transport_snapshot):
    config, branches = inspect(model)
    directory.mkdir(parents=True, exist_ok=True)
    config_path = directory / "tokenizer.bin"
    config_path.write_bytes(config)
    texts = corpus()
    require(transport_snapshot.stat().st_size <= 8_000_000, "Snapshot too large")
    transport = [line.split("\t") for line in transport_snapshot.read_text().splitlines()]
    require(len(transport) == len(texts), "Snapshot case count mismatch")
    for text, row in zip(texts, transport):
        require(len(row) == 2 and row[0] == text.encode("utf-8").hex(), "Snapshot not the synthetic corpus")
        require(len(bytes.fromhex(row[1])) <= 16384, "Snapshot byte limit")
    reference = subprocess.run([str(binary.resolve()), str(config_path.resolve())],
                               input="\n".join(row[1] for row in transport) + "\n",
                               text=True, capture_output=True, timeout=60, check=True)
    lines = reference.stdout.splitlines()
    require(len(lines) == len(texts), "Oracle response count mismatch")
    ids = [[int(item) for item in line.split(",")] for line in lines]
    require(all(len(row) >= 2 for row in ids), "Missing BOS/EOS")
    require({191, 192, 193}.issubset(set(map(len, ids))), "Boundary corpus failed to hit exact token limits")
    # TSV carries only generated synthetic inputs and full reference token IDs, never embeddings.
    (directory / "reference.tsv").write_text("".join(text.encode("utf-8").hex() + "\t" + line + "\t" + row[1] + "\n"
                                                       for text, line, row in zip(texts, lines, transport)))
    root = Path(__file__).resolve().parents[1]
    sources = ["shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/embedding/UseTokenizer.kt",
               "shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/embedding/EncoderAdmission.kt",
               "shared/src/jvmTest/kotlin/dev/edgecompanion/core/inference/embedding/UseTokenizerReferenceTest.kt"]
    (directory / "reference.json").write_text(json.dumps({"modelSha256": MODEL_SHA, "tokenizerSha256": CONFIG_SHA,
        "branches": branches, "transport": "mediapipe-packet-bytes-v1", "referenceCases": len(texts), "nativeEncoderInvoked": False,
        "transportSnapshotSha256": hashlib.sha256(transport_snapshot.read_bytes()).hexdigest(),
        "createdAtMs": int(time.time() * 1000),
        "sourceSha256": {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in sources},
        "referenceSha256": hashlib.sha256((directory / "reference.tsv").read_bytes()).hexdigest(),
        "admissionVerified": False, "remainingGate": "Run Kotlin token-ID differential and rejection tests"}, indent=2))
    return len(texts)
