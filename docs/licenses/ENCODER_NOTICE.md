# Encoder Prerequisite Notices

The optimized normalization, double-array trie and float32 lattice behavior in
`shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/embedding/UseTokenizer.kt`
is a modified Kotlin port of MediaPipe's optimized SentencePiece implementation.

Copyright 2023 The MediaPipe Authors. Licensed under the Apache License, Version
2.0; see [license text](Apache-2.0.txt). The port adds bounded preprocessing,
cancellation and artifact-specific parsing; it does not claim generic tokenizer support.

Upstream revision: `0e152b35eeea3cc9e8b55886428982b82de5925b`.
Exact source/schema paths and SHA-256 values are pinned in
`scripts/encoder-reference/upstream.json`. The host oracle compiles those upstream
C++ sources without changing their tokenization behavior. Its FlatBuffers
v25.9.23 build dependency is also Apache-2.0 and archive-hash pinned there.

The public USE-QA model is separately identified by SHA-256
`89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749`.
Its embedded metadata does not contain license text. Chromium's pinned
[TFHub model provenance](https://chromium.googlesource.com/chromium/src/+/refs/tags/124.0.6367.150/third_party/tfhub_models/README.chromium)
identifies the USE-QA on-device model and Apache-2.0 provenance. This records
upstream provenance, not approval to distribute weights or a release APK.
No model weights or generated embeddings are checked into this repository.
