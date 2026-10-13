# Gemma 4 Runtime Foundation with LiteRT-LM

Date: 2026-10-01. Updated: 2026-10-13. Status: accepted direction; internal foundation and S24 native smoke verified, full acceptance pending. Current evidence and limitations are tracked in [implementation results](../PHASE_1_IMPLEMENTATION_RESULTS.md).

## Context

The original release plan preferred llama.cpp/JNI with LiteRT-LM as an alternative. The approved next slice is a bounded Gemma 4 E2B runtime foundation, followed by real assistant conversation integration. This changes the first runtime experiment, not the mascot-based local-first product or the existing KMP/Compose/Koin/SQLDelight architecture.

## Decision

Use LiteRT-LM first, behind `LocalLlmEngine`. Pin `com.google.ai.edge.litertlm:litertlm-android:0.17.1` for the compatibility spike. Google's Maven metadata and the exact POM were checked on 2026-10-01; S24 GPU native smoke subsequently passed with this pin. Full device compatibility and acceptance remain open. Do not use `latest.release`, silently upgrade the pin, or imply that a published SDK guarantees S24 support.

Use the immutable [E2B planning manifest](../../planning/models/gemma-4-e2b-it.json) as the artifact/configuration source of truth. The upstream pointer confirms 2,588,147,712 bytes and SHA-256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` at revision `6e5c4f1e395deb959c494953478fa5cec4b8008f`. The ignored local model download matched this SHA-256 on 2026-10-02. Both the artifact and runtime declare Apache-2.0; retain distribution notices and review transitive notices before distribution.

Start at 2048 configured context tokens with at most 512 output tokens, including template/special-token budgeting. The artifact's 32000-token limit is not the initial allocation. Byte counts use `Long`. LiteRT-LM owns native templating/tokenization; the application owns structured input, context budgets, admission, and user-visible state. P1A enables only text, regardless of the artifact's additional modalities.

## Ownership and Contracts

- Retain the three existing modules. Put portable models/state/admission rules in `shared/commonMain`, Android native/filesystem/resource implementations in `shared/androidMain`, lifecycle entry points in `androidApp`, and Models screen/ViewModel/resources in `ui`.
- One Koin application-scoped manager owns engine/session lifetime. Activities and ViewModels do not hold or close native handles. Dev conversations remain separate from personal conversation persistence.
- Serialize state transitions and generation admission; cancellation signals native work out-of-band. Release awaits native completion, then closes conversation before engine. Ignore stale output without discarding cleanup completion signals.
- Store weights in private no-backup, immutable versioned files. Preflight and continuously enforce streamed import limits; verify exact size/hash before promotion and reconcile metadata/file mismatches on restart. Never replace a loaded path.
- Keep installed asset and transient runtime states distinct. Unknown resources, failed initialization, unsafe conditions, and unresolved cancellation must not be reported as ready.
- Use explicit CPU for a compatible emulator spike. On S24, evaluate GPU and CPU independently. Allow initialization-only GPU-to-CPU fallback after verified cleanup for recoverable errors, never mid-response; disable fallback in benchmarks.
- Native crashes/hangs are not ordinary fallback errors. Timeouts fail the operation without making live handles safe to close. Block reuse until recovery is established.
- No app network permission, cloud fallback, tools, personal-context assembly, or consumer chat replacement is introduced by the spike.

The complete operational, testing, and measurable acceptance contracts are in [Phase 1](../../planning/PHASE_1_PLAN.md).

## Development and Acceptance

Develop on the emulator, then hold a dedicated S24 session. Fake-engine tests prove policy, emulator/native tests prove only their tested configuration, and phone measurements determine S24 readiness. Mark unsupported emulator native paths as blocked rather than passing them with fakes. Do not alter or stop the user's existing emulator to satisfy this ADR.

The first native exercise is verify -> load at 2048 -> stream synthetic text -> cancel while active -> await completion -> release -> reload -> generate again -> release. Complete this before investing in the full Models panel. The spike is not a percentile benchmark or a replacement for the 20-minute physical workload.

The required phone ABI is `arm64-v8a`; existing app ABI filters are `arm64-v8a` and `x86_64`. Inspect the actual AAR/native dependencies, minimum SDK, page-size compatibility, and matching emulator image before marking an ABI supported. No native ABI is certified by this document.

The 0.17.1 POM declares coroutines-android 1.11.0 and kotlin-reflect 2.4.0. The catalog now pins coroutines 1.11.0 and Kotlin 2.4.20. Resolved core/Android dependencies use 1.11.0; shared/local tests cover the scoped dependency change. Do not silently force older runtime dependencies.

## Alternatives and Scope

- llama.cpp/GGUF remains a timeboxed fallback experiment if the pinned LiteRT-LM path fails a compatibility or native-safety gate. Do not implement a second backend speculatively.
- E4B is benchmark-only after E2B is viable; a documented deferral does not block P1A.
- NPU, speculative decoding, downloads/login, speech, OCR reasoning, retrieval, actions, and deferred audio privacy changes remain outside this slice.
- P1A is Runtime Foundation; P1B is Conversation Integration. Work packages use R01-R08, avoiding a second meaning for P1B. The overall P0-P7 roadmap is unchanged.

## Evidence Required Before Completion

Compatibility resolution/build, native ABI inspection, local model hash, shared race/recovery tests, emulator integration, and a separate S24 native/resource acceptance report. Preserve failures and unavailable metrics. App thermal status is the default public signal; skin temperature requires an available authorized test-harness source and is never inferred from battery temperature.

No runtime performance, privacy completion, or physical-device inference result is claimed by this ADR.

## References

- [Pinned artifact and SHA-256](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm)
- [Exact Maven POM](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/0.17.1/litertlm-android-0.17.1.pom)
- [Versioned Kotlin runtime API](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.17.1/docs/api/kotlin/getting_started.md)
- [Runtime license](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.17.1/LICENSE)
