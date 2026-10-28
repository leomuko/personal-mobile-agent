# Structured-Output Capability Audit Review

Date: 2026-10-24. Reviewer: OpenAI Codex, the retained user-authorized primary
independent QA reviewer. No delegation or substitute reviewer.

**Verdict: PASS for the focused host-only capability audit, SDK boundary tests
and ADR scope.** No actionable findings were identified in the four reviewed
files. This is not native-integration approval, schema qualification, an
execution freeze or device authorization. P2 and semantic enablement remain
blocked by their existing gates.

## Reviewed Snapshot

Workspace: `/Users/kezileo/Desktop/personal_mobile_agent`; branch `dev`.
Base and HEAD: `ae678b7ecd3760ba3739bf89127ac5cb8bae2fa1`.
The four files were untracked and inspected directly; tracked/staged changes were
empty. The parent's final host-verification paragraph is included in this review.

| Reviewed file | SHA-256 |
| --- | --- |
| `docs/P2_STRUCTURED_OUTPUT_CAPABILITY_AUDIT.md` | `b6d7d5868b93d7dca8125eda3d6dc52254a1c4b19d0aad45e0575a9cbb964c3f` |
| `docs/P2_STRUCTURED_OUTPUT_CAPABILITY_AUDIT.json` | `3e8a69006fa6816c6bd9167890dab5388f81af2a8f6c6a40dff74beed510c2a3` |
| `docs/decisions/0007-constrain-evidence-output-before-changing-citations.md` | `7ed5dde2fe45d2ad5155e54aeb168fae2363a8f6896fbb6cf70e1a4fdb0fdfc3` |
| `shared/src/androidHostTest/kotlin/dev/edgecompanion/core/inference/litert/LiteRtResponseFormatAuditTest.kt` | `cda7b89c3f79ac954e2ea5e574e38c235cff7d179a8678ed9360b93694b1e278` |

Review snapshot identity:
`6a2f86a43a6edd6495d9dc281298b929ebeac238b2d220eba4df8478ea2c404c`.
Computed as SHA-256 of compact JSON for the path-to-hash map in the table's order.
These four hashes remained unchanged through review and resumption.

Applied QA_AGENT_PROMPT.md and the previously read Kotlin code-review/testing
skills, including relevant test-quality, security, platform-boundary and ownership
criteria. This review does not repeat the earlier application-wide work.

## Artifact And Upstream Evidence

Independently hashed the actual Gradle-cache
`com.google.ai.edge.litertlm:litertlm-android:0.17.1` AAR and extracted member bytes
in memory. All match the machine-readable audit:

| Artifact | SHA-256 |
| --- | --- |
| AAR | `a8aeaa6b128e9b0f1c7fe5e620932a7030cfff1ac9c0dc133d8c79711de5cf4d` |
| classes.jar | `47c0de5858be5e068f4784bee22c523e8acbd8aa00033154f78dc09138453ac5` |
| arm64-v8a liblitertlm_jni.so | `44f900f3ea6c5224f3395c1548dfa7e825f4412ba3833f994c5c306831a57086` |
| x86_64 liblitertlm_jni.so | `9b0a908ba8ef5e54b3191de963acd4272e1f10aee1df61d9912bb586a35a5d4a` |

Independently resolved the official v0.17.1 tag via the
[GitHub tag-reference API](https://api.github.com/repos/google-ai-edge/LiteRT-LM/git/ref/tags/v0.17.1)
to `5e58e9a0aef7abf7091207a8b1d1063a1c800f08`, then fetched all 13 manifest
sources from raw.githubusercontent.com at that commit. Each response's exact
bytes match both the recorded SHA-256 and the local ignored source copy. This
verification used read-only public HTTPS requests and uploaded no project data.

Fresh `javap -c -p` inspection of seven classes corroborates the retained dumps:
ResponseFormat, its Companion and Type, ConversationConfig, Conversation, Engine
and LiteRtLmJni. Comparing corresponding lines after trimming indentation finds
no content difference; the different javap tool versions only changed spacing.
The inspected classes.jar itself matches the AAR member hash above.

Independently found LLGuidance initialization/compilation/mask diagnostics in the
arm64 member without loading it. Those strings corroborate inclusion, not backend
execution, exact source-to-binary equivalence or enforcement success. The audit
correctly discloses that no reproducible-build proof was obtained.

## Claim Assessment

The audit distinguishes the evidence levels correctly:

1. The actual Kotlin API has the explicit default-disabled enableResponseFormat
   option and per-request ResponseFormat on the callback overload used by the
   adapter. Fresh bytecode shows both synchronous and callback disabled-format
   guards before JNI, and forwards the selected type and schema string.
2. The JSON factory parses and retains the supplied string; it does not compile
   the intended schema or establish keyword enforcement. Its native type value
   is 2 despite the JSON_OBJECT enum name. Host acceptance of `17` demonstrates
   why factory success is insufficient. See the pinned
   [ResponseFormat implementation](https://github.com/google-ai-edge/LiteRT-LM/blob/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/kotlin/java/com/google/ai/edge/litertlm/ResponseFormat.kt#L28).
3. The source trace is real: Engine forwards the conversation opt-in; JNI installs
   an LLGuidance provider and maps request type 2 to kJsonSchema. The conversation
   creates the constraint and attaches it to the decode configuration; the
   provider selects the JSON-schema compiler and propagates errors. This is a
   static native-constraint path, not merely a JSON prompt. The schema path is
   distinct from experimental tool-constrained decoding. See the pinned
   [JNI mapping](https://github.com/google-ai-edge/LiteRT-LM/blob/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/kotlin/java/com/google/ai/edge/litertlm/jni/litertlm.cc#L1277),
   [conversation decode configuration](https://github.com/google-ai-edge/LiteRT-LM/blob/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/runtime/conversation/conversation.cc#L401)
   and [LLGuidance provider](https://github.com/google-ai-edge/LiteRT-LM/blob/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/runtime/components/constrained_decoding/llg_constraint_provider.cc#L119).
4. The decoder has a non-host-memory copy/mask/write-back path. Possible GPU
   overhead is explicitly an inference from that code, not measured S24 behavior.
   Exact GPU enforcement, supported schema features, EOS/token exhaustion,
   streaming, native error behavior and constrained cancellation remain untested.
   See the pinned
   [decoder implementation](https://github.com/google-ai-edge/LiteRT-LM/blob/5e58e9a0aef7abf7091207a8b1d1063a1c800f08/runtime/components/constrained_decoding/constrained_decoder.cc#L31).

The current app adapter was inspected and is byte-identical to HEAD, SHA-256
`500b9acdf015c7c4137382467590566d72fef397af76e31bb7cdbf941192f095`.
It enables neither enableResponseFormat nor a per-request response format.
The prior unconstrained oa01 failure therefore does not test this feature.

The tool-selection caveat is also accurately disclosed: SDK resolution can
suppress a request format when automatic tool calling is active with tools.
The future proposal explicitly retains empty tools and automaticToolCalling=false.

## Test And ADR Assessment

All eight tests are appropriately in androidHostTest because they inspect the
Android SDK artifact and Java signatures. Shared tests retain kotlin.test; no
Android API is moved into commonMain. Tests distinguish factory identity, parsing
boundaries, opt-in defaults, enum values, callback signature and disabled guards.

The two Conversation objects use handle 0 only as an unallocated sentinel. Source
and fresh bytecode confirm the tested rejection precedes JNI. No Engine is
constructed, no native handle/model is allocated, and neither sentinel object is
closed. Reflection checks cancel/close availability without invoking them. This
is a host boundary check, not a claim that native cancellation works.

ADR 0007 is a bounded recommendation for the next proposal. It preserves p,r,b,e,q
and the strict validator, internally authored/versioned schema identity,
delivered-input-only evidence IDs, no source-controlled schema/tools, rejection
of unsupported capability, no silent fallback and no repaired historical output.
It requires a separate protocol, review, freeze and S24 authorization, without
inheriting earlier request/deadline permission or restarting stopped controls.

The placement and resource requirements are explicit: app-owned common request
identity rather than vendor objects, SDK types confined to androidMain, existing
ModelManager ownership and serial allocation, immediate cancellation, stale-result
revocation, confirmed cleanup/quarantine, and schema/provider setup included in
allocation accounting and the complete clock. Search's five-second contract is
unchanged; a future explicit-verifier ceiling requires its own approval.

Exact schema dialect/keyword behavior remains a prerequisite, not an assertion.
Structural constraints cannot by themselves establish revision eligibility,
UTF-16 span validity, exact quotations or semantic support. The unchanged strict
validator and later labeled semantic evaluation remain required. The evidence-ID
alternative is separately scoped, not automatic fallback or reinterpretation of t.

No architecture refactor or broader framework is needed for this documentation
and host-test slice; the ADR does not authorize implementation work.

## Verification And Limits

Independently completed:

- PASS: actual cached AAR and all three member hashes, all 13 local source hashes,
  fresh official tag resolution and all 13 upstream response-byte hashes.
- PASS: fresh disassembly of seven classes; retained-dump corroboration and guard
  ordering/type/schema forwarding checks. Arm64 diagnostics verified as static
  evidence only.
- PASS: focused Gradle command below, exit 0, eight tests, zero
  failures/errors/skips. The target was freshly run with --rerun-tasks.
  XML timestamp: 2026-10-24T11:50:58.798Z.
- PASS: reviewed four-file hashes stable, adapter unchanged, git diff --check and
  staged/tracked inspection clean. The strict validator, original preparation lock,
  oa01 raw encoder.json and retained execution-freeze copy preserve their known
  hashes.

```sh
env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  P2_V4_PROFILE=android-pressure \
  ./gradlew :shared:testAndroidHostTest \
  --tests dev.edgecompanion.core.inference.litert.LiteRtResponseFormatAuditTest \
  --offline --console=plain --rerun-tasks
```

XML evidence:
`shared/build/test-results/testAndroidHostTest/TEST-dev.edgecompanion.core.inference.litert.LiteRtResponseFormatAuditTest.xml`.
The current focused output is eight tests, not a fresh 143-test run. Parent-reported
earlier full results are 143 Android-host, 159 JVM and 115 Node passes; they are
separately attributed and not adopted as fresh independent runs here. No broad
suite rerun was necessary to finish this focused audit. Existing Gradle UI-host
configuration and Kotlin-registry warnings did not fail the test command.

No completed verification failed or remains blocked. The web renderer could not
open the tag API, but the independent direct HTTPS check succeeded and verified
the tag plus all source bytes. Runtime/schema-feature unknowns are unperformed
qualification work, not hidden host passes.

Not run: native initialization, native schema compilation, model generation, GPU
enforcement/performance, cancellation/cleanup integration, APK builds, lint, ADB,
installation or device instrumentation. No production source, dependency,
protocol, schema experiment, native freeze or prior evidence was changed. Adding
the host test changes future executable snapshot admission as expected; archived
freezes and their failures remain preserved historical evidence.

Only authored path: `docs/P2_STRUCTURED_OUTPUT_AUDIT_REVIEW.md`. No source/test
fixes, Git mutations, delegation or execution approval. Deferred decisions remain
unchanged. The next deliverable is a separately reviewed experiment proposal,
not a new model run; native enforcement and semantic quality remain unqualified.
