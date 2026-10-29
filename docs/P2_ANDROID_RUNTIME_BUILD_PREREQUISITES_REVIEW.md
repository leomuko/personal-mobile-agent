# Independent QA — ADR 0008 build prerequisites

2026-10-29. **PASS for the source-prerequisite audit implementation. The full
build recipe remains BLOCKED. P2 is incomplete; semantic search stays disabled.**

This records the verdict returned by the existing primary QA agent
`01a16d6c-1a21-7923-af3d-18ec99667728`; the implementer transcribed it after
review. It is not a build, corpus or device-execution approval.

Reviewed on `dev`, HEAD `b35e172eb121c347c5251d58bde80cad4f46bfb2`.
The reviewer found no new defects and bound the verdict to
`planning/fixtures/android-runtime-build-v1/prerequisite-lock.json`, SHA-256
`2e1168f6f0b33ee0410fdf50b35cf635e41da37acefc2343b439ae2ef57583c3`.
All five locked files matched. This review record is outside that lock to avoid
changing reviewed bytes after the verdict. The handoff's pending-review note
describes its pre-review state; this record closes that request.

## Independent checks

- Fresh 16-test prerequisite regression run: passed, no failures or skips.
- All 48 source files: lengths, SHA-256 and complete pinned Git-tree identities
  verified.
- Independent offline audit: exit 2 / BLOCKED, matching the preserved report.
- All 46 historical source bindings: unchanged.
- No builds, downloads, device access or project-source edits by the reviewer.

The reviewer corroborated the unresolved `@ml_drift` source identity for the
inspected GPU route. The optional prebuilt Gemma provider is not established as
a mandatory dependency blocker. Root/TensorFlow repository selection remains
unresolved: this is not a configured Bazel result or evidence that CPU/JNI
compilation fails.

## Implementer checks and retained evidence

All 79 Python host tests passed separately, including the 16 new tests. This is
the implementer's full-suite result, not a claim that QA independently reran 79.

| Retained local evidence | SHA-256 |
| --- | --- |
| `build/android-runtime-recipe-v1/prerequisite-report-1.json` | `720d54c7a98bee4f18320e1a2ed06e3de049d0716040a8ffdcacf0a2f0525f1a` |
| `build/android-runtime-recipe-v1/host-tests-final-1.log` | `06050590c2ed71f2fe0975d0a7ca6e0ac569f57a5ad25dfc1df0917be49ddae5` |
| `build/android-runtime-recipe-v1/historical-preservation-final-1.json` | `144bc1a1eefff44f696639516cd12c73ca93c51059439494d895825616193527` |
| `build/android-runtime-recipe-v1/final-local-summary-1.json` | `cab78980d9e75e1e880843614e65f0a9e74c3b80b8a8482e307ae21ea6cfdf3c` |

The ignored local archives remain preserved. No model binaries, personal data
or signing keys were added to source control. No commits were created.

## Remaining boundary

The approved ADR's source/build-closure prerequisite has not passed. Identify
and review the actual root repository selection, immutable GPU dependency recipe
and complete both-ABI AAR assembly before control/candidate builds. Required
toolchains, signing, packaging and tokenizer compatibility must still be frozen
and checked. No SDK substitution or runtime behavior change was implemented.

S24 protocols, execution freezes and authorization remain separate. This verdict
does not unblock native feasibility, semantic search or complete P2 acceptance.
