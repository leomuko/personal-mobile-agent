#!/usr/bin/env python3
"""Metadata-only audit of the public encoder candidate; never handles personal documents."""
import argparse
import hashlib
import json
import struct
from pathlib import Path

import tflite
from flatbuffers import flexbuffers
from flatbuffers.table import Table
from sentencepiece import sentencepiece_model_pb2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path)
    path = parser.parse_args().model
    if path.stat().st_size != 6120274:
        raise SystemExit("Encoder size mismatch")
    raw = path.read_bytes()
    if len(raw) != 6120274 or hashlib.sha256(raw).hexdigest() != "89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749":
        raise SystemExit("Not the pinned public encoder candidate")
    model = tflite.Model.GetRootAsModel(raw, 0)
    graphs = []
    for graph_index in range(model.SubgraphsLength()):
        graph = model.Subgraphs(graph_index)
        def tensor(index):
            value = graph.Tensors(index)
            return {"name": value.Name().decode(), "type": value.Type(),
                    "shape": [value.Shape(i) for i in range(value.ShapeLength())]}
        custom = []
        for index in range(graph.OperatorsLength()):
            operator = graph.Operators(index)
            opcode = model.OperatorCodes(operator.OpcodeIndex())
            name = opcode.CustomCode()
            if name is not None:
                options = operator.CustomOptionsAsNumpy().tobytes() if operator.CustomOptionsLength() else b""
                item = {"name": name.decode(), "optionsBytes": len(options),
                        "optionsSha256": hashlib.sha256(options).hexdigest()}
                if options:
                    try:
                        decoded = flexbuffers.Loads(options)
                        item["optionKeys"] = list(decoded) if isinstance(decoded, dict) else type(decoded).__name__
                    except Exception:
                        item["flexbuffer"] = False
                custom.append(item)
                if name == b"TFSentencepieceTokenizeOp":
                    inputs = []
                    for position in range(operator.InputsLength()):
                        entry = graph.Tensors(operator.Inputs(position))
                        storage = model.Buffers(entry.Buffer())
                        content = storage.DataAsNumpy().tobytes() if storage.DataLength() else b""
                        value = {"name": entry.Name().decode(), "type": entry.Type(), "constantBytes": len(content)}
                        if len(content) > 1024:
                            payload = content[12:] if struct.unpack_from("<3i", content) == (1, 12, len(content)) else content
                            spec = sentencepiece_model_pb2.ModelProto()
                            try:
                                spec.ParseFromString(payload)
                                if len(spec.pieces) >= 256:
                                    value["tokenizer"] = {"sha256": hashlib.sha256(payload).hexdigest(),
                                        "bytes": len(payload), "pieces": len(spec.pieces),
                                        "modelType": spec.trainer_spec.model_type,
                                        "byteFallback": spec.trainer_spec.byte_fallback,
                                        "normalizer": spec.normalizer_spec.name,
                                        "normalizerMapSha256": hashlib.sha256(spec.normalizer_spec.precompiled_charsmap).hexdigest(),
                                        "dummyPrefix": spec.normalizer_spec.add_dummy_prefix}
                            except Exception:
                                value["sentencepieceProto"] = False
                                root = Table(content, struct.unpack_from("<I", content)[0])
                                def vector_length(field):
                                    offset = root.Offset(4 + field * 2)
                                    return root.VectorLen(offset) if offset else 0
                                def flag(field):
                                    offset = root.Offset(4 + field * 2)
                                    return bool(content[root.Pos + offset]) if offset else False
                                value["fastConfig"] = {
                                    "sha256": hashlib.sha256(content).hexdigest(),
                                    "pieceScores": vector_length(7),
                                    "removeExtraWhitespaces": flag(8),
                                    "dummyPrefix": flag(9), "escapeWhitespaces": flag(10),
                                    "normalizerReplacementBytes": vector_length(12),
                                }
                        inputs.append(value)
                    item["inputs"] = inputs
                if name == b"RaggedTensorToTensor":
                    shapes = []
                    for position in range(operator.InputsLength()):
                        entry = graph.Tensors(operator.Inputs(position))
                        storage = model.Buffers(entry.Buffer())
                        content = storage.DataAsNumpy().tobytes() if storage.DataLength() else b""
                        if entry.Type() in (tflite.TensorType.INT32, tflite.TensorType.INT64) and 0 < len(content) <= 32:
                            code = "i" if entry.Type() == tflite.TensorType.INT32 else "q"
                            if len(content) % struct.calcsize(code) == 0:
                                shapes.append({"name": entry.Name().decode(), "values": list(struct.unpack("<" + code * (len(content) // struct.calcsize(code)), content))})
                    item["shapeConstants"] = shapes
        graphs.append({"name": graph.Name().decode(),
                       "inputs": [tensor(graph.Inputs(i)) for i in range(graph.InputsLength())],
                       "outputs": [tensor(graph.Outputs(i)) for i in range(graph.OutputsLength())],
                       "customOperators": custom})
    print(json.dumps({"modelSha256": hashlib.sha256(raw).hexdigest(), "graphs": graphs,
                      "semanticAdoptionReady": False,
                      "remainingGate": "Verified preflight counting for the encoder's normalized 192-token input"}, indent=2))


if __name__ == "__main__":
    main()
