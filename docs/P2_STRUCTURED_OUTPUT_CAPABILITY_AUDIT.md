# Pinned Structured-Output Capability Audit

Date: 2026-10-24. Base: `dev` at `ae678b7`.
Scope: host API tests, published AAR inspection and immutable upstream source.
No ADB, native initialization, inference, APK installation or output repair.

## Conclusion

The pinned Android Kotlin API exposes schema-constrained generation, including the
callback API used by our adapter. This is more than a prompt asking for JSON.
There is sufficient static evidence to propose testing the existing output
contract with constrained decoding before changing citation design. There is
**not** evidence yet that its exact schema, GPU path, timing or cancellation work
on the S24. See [ADR 0007](decisions/0007-constrain-evidence-output-before-changing-citations.md).

The previous `oa01` failure happened without this feature enabled. It is evidence
of unconstrained schema non-adherence, not evidence that constrained decoding
failed. The raw result and its strict rejection remain unchanged.

## Artifact Identity

`gradle/libs.versions.toml` pins
`com.google.ai.edge.litertlm:litertlm-android:0.17.1`. The actual cached AAR was
hashed and its class bytecode inspected, rather than inferring Android capability
from current C++ documentation. The public release tag resolves to commit
`5e58e9a0aef7abf7091207a8b1d1063a1c800f08`.

[The machine-readable audit](P2_STRUCTURED_OUTPUT_CAPABILITY_AUDIT.json) records
the AAR hash, classes.jar, both native ABI hashes and 13 source-file hashes.
Source/bytecode agreement is not a reproducible-build proof linking every native
instruction to that commit. Diagnostic strings in the arm64 library corroborate
LLGuidance inclusion; they are not an execution or backend-support test.

## Capability Trace

All following source links use that immutable commit, not `main`:

1. [Config.kt, lines 224-241](https://github.com/google-ai-edge/LiteRT-LM/blob/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/kotlin/java/com/google/ai/edge/litertlm/Config.kt#L224)
   exposes explicit conversation opt-in, disabled by default. The installed class
   has the same field/accessor and constructor option.
2. [ResponseFormat.kt, lines 28-67](https://github.com/google-ai-edge/LiteRT-LM/blob/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/kotlin/java/com/google/ai/edge/litertlm/ResponseFormat.kt#L28)
   exposes JSON-schema and regex factories. JSON's native type value is 2. The
   `JSON_OBJECT` enum name does not mean that the supplied schema is discarded.
   The factory parses JSON and retains its string; it does not compile or prove
   support for schema keywords. Host tests show it also accepts the number `17`.
3. [Engine.kt, lines 149-169](https://github.com/google-ai-edge/LiteRT-LM/blob/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/kotlin/java/com/google/ai/edge/litertlm/Engine.kt#L149)
   forwards the opt-in to native conversation creation and the Kotlin wrapper.
4. [Conversation.kt, lines 81-90 and 282-327](https://github.com/google-ai-edge/LiteRT-LM/blob/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/kotlin/java/com/google/ai/edge/litertlm/Conversation.kt#L282)
   exposes a per-request response format on the callback overload, guards disabled
   opt-in before JNI, and forwards type/schema to native async sending. Tool
   resolution can suppress a format during automatic tool selection; our proposed
   verifier must retain empty tools and `automaticToolCalling=false`. Bytecode
   corroborates the guard, schema getters and native call. Existing cancel/close
   methods remain available, but that is not proof of constrained cancellation.
5. [JNI litertlm.cc, lines 1151-1154 and 1277-1292](https://github.com/google-ai-edge/LiteRT-LM/blob/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/kotlin/java/com/google/ai/edge/litertlm/jni/litertlm.cc#L1151)
   installs the LLGuidance provider on opt-in and maps type 2 to a JSON-schema
   constraint argument. Both async and synchronous paths accept that argument.
6. [conversation.cc, lines 401-419](https://github.com/google-ai-edge/LiteRT-LM/blob/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/runtime/conversation/conversation.cc#L401)
   creates the supplied constraint and attaches it to decoding;
   [llg_constraint_provider.cc, lines 41-55 and 132-156](https://github.com/google-ai-edge/LiteRT-LM/blob/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/runtime/components/constrained_decoding/llg_constraint_provider.cc#L41)
   selects the JSON-schema compiler and propagates compilation errors.
7. [constrained_decoder.cc, lines 31-73](https://github.com/google-ai-edge/LiteRT-LM/blob/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/runtime/components/constrained_decoding/constrained_decoder.cc#L31)
   includes a non-host logits path that copies, masks and writes them back. This
   suggests potential GPU overhead; it does not certify S24 execution or speed.

Our current `LiteRtLlmEngine.kt` creates a conversation without the opt-in and
sends without a response format. It remains unchanged by this audit. Enabling the
tool-calling experimental flag is not a substitute for this schema-specific path.

## Enforceable Versus Validated

Candidate JSON-schema features for a future reviewed experiment are object shape,
required fields, rejection of additional properties, enums, integers and bounded
arrays. Their exact support must be tested; the factory's success is insufficient.
The current citation fields remain `p,r,b,e,q`; accepting `t` is not proposed.

JSON syntax/shape cannot establish that a citation is from an eligible revision,
that offsets are half-open UTF-16 and avoid surrogate splits, that quotation bytes
match, or that an assertion is supported. The unchanged strict validator must
still check duplicate/unknown keys, detail order, citation count/order/uniqueness,
state aggregation, references, spans, quotations, Unicode, bytes and token limits.
Labeled semantic evaluation remains a separate gate after structural feasibility.

Unknowns include schema dialect/keyword handling, early EOS and token exhaustion,
native compilation errors, output streaming, provider initialization allocation
and cancellation during setup. A partial JSON stream is never an accepted result.
All setup, validation and confirmed cleanup must remain inside the proposed
experiment's complete clock, with quarantine on uncertain cleanup.

## Reproduction

Local diagnostic copies/transcripts live under ignored
`build/output-contract-audit/`; no upstream source is vendored into application
code. Public source retrieval initially hit sandbox DNS restrictions and succeeded
with approved network access. No model was downloaded or uploaded.

Resolve the tag with GitHub's `git/ref/tags/v0.17.1` API, then retrieve each source
in the JSON manifest from
`https://raw.githubusercontent.com/google-ai-edge/LiteRT-LM/COMMIT/PATH` and compare
SHA-256. Locate the exact AAR in the Gradle cache or obtain the same Maven version;
verify its recorded hash before inspecting it:

```sh
shasum -a 256 "$AAR"
unzip "$AAR" classes.jar -d "$NEW_AUDIT_DIRECTORY"
"$JAVA_HOME/bin/javap" -c -p -classpath "$NEW_AUDIT_DIRECTORY/classes.jar" \
  com.google.ai.edge.litertlm.ResponseFormat \
  com.google.ai.edge.litertlm.ConversationConfig \
  com.google.ai.edge.litertlm.Conversation \
  com.google.ai.edge.litertlm.Engine
```

Eight added `LiteRtResponseFormatAuditTest` Android-host tests passed against the
pinned SDK: default opt-in, string/map schema factories, enum values, the factory's
non-enforcement boundary, regex identity, disabled synchronous/callback guards,
and the public streaming/cancel/close signatures. No Engine or native handle is
allocated. The guard tests use a zero sentinel and intentionally never call close.
These are SDK boundary checks, not model-generation tests.

```sh
env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  P2_V4_PROFILE=android-pressure \
  ./gradlew :shared:testAndroidHostTest \
  --tests dev.edgecompanion.core.inference.litert.LiteRtResponseFormatAuditTest \
  --offline --console=plain --rerun-tasks
```

Full host regression verification passed with no failures/errors/skips: 143 shared
Android-host tests (the original 135 plus eight new cases), 159 shared JVM tests
and all 115 Node host tests. Both Gradle test targets were freshly executed with
`--rerun-tasks`, not counted from up-to-date tasks. The audit manifest matches the
actual cached AAR, all three recorded members and all 13 downloaded source files.

Independent primary-QA review is pending. Not run in this slice: APK builds,
Android lint, device instrumentation, native schema compilation, enforcement,
latency, allocation or cancellation tests. These host passes are not substitutes.
No new native integration, protocol freeze, device permission, ADB, model execution,
dependency upgrade, production behavior or Git commit accompanies this slice.
Historical failures/validator are preserved; semantic enablement and full P2
acceptance remain blocked. The new test file changes future source snapshots;
old execution freezes are not refreshed or reusable authorizations. Their archived
evidence remains historical, not invalidated by adding a host test.
