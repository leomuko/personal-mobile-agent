# Private Bazel server JVM correction — execution contract v1

2026-10-30. Implements the user-authorized server correction following independent
PASS for the preceding failure and proposal. This supplements the proposal; it
does not rewrite the old pending-review record, freeze, sources or failed attempt.

## Decision and scope

Select Java **21.0.5 bundled in the already verified Bazel 7.6.1 executable** only
for `--server_javabase`. Extract directly from that executable into a new private
directory. Do not use a mutable Bazel installation directory or install Java.
Global JAVA_HOME, shell defaults, Gradle and Android Studio remain unchanged.
The frozen command environment retains Java 17 exactly. Compilation declarations,
source/SDK/Cargo locks, dependency pins, GPU scope and both ABI profiles remain
unchanged. Resolved Java compilation toolchains still require configured evidence.

The prior independent review is recorded separately in
`planning/fixtures/bazel-server-jvm-v1/previous-results-review.json`; its source
is the retained QA summary, not an implementer-created independent verdict.

## Implementation and admission

The old `ml_drift_compatibility` runner, preparation code and tests stay byte-for-
byte unchanged. A versioned wrapper reuses its repository policies, workspace
staging, bounded process supervision and command restrictions.

`server_jvm.py` verifies the entire Bazel executable, embedded server JAR, class
header and release metadata. It binds every private JDK file, permission,
directory and internal symlink to the archive. Missing, modified, unknown or
incompatible identities reject before process launch. The wrapper repeats the
JDK preflight before each command, and retains the entire old compilation
environment. JVM launch selection is not proof of compilation compatibility.

The same root query and two ABI cqueries are admitted. Per-node configured
output adds Java language/runtime/toolchain option fields; absent values are
explicitly unresolved, and options alone do not establish provider/interface
compatibility. Inspect successful graph labels and runtime/compiler suppliers
before a compatibility claim. No fourth diagnostic invocation is preauthorized.

## Limits and review

- Exactly three commands: root inventory, ARM64 cquery, x86_64 cquery.
- Per invocation: 300-second decision deadline, 15-second total cleanup ceiling,
  and 16 MiB combined retained log cap. Unknown cleanup stops further admission.
- Original repository receipt/precedence guard remains; failed inventory never
  authorizes inserting the ML Drift repository.
- Focused and existing host tests precede independent implementation/freeze
  review. Execute only the new exact reviewed freeze into a new attempt identity.
- Preserve all outputs, including failures and partial dependency downloads.

Report launch correction, actual dependency resolution and compatibility
separately. A later failure cannot authorize another JVM/dependency switch,
limit relaxation or narrowed ABI/backend scope. If blocked, identify the next
specific prerequisite and route decision; if configuration closes, complete
the separate control/candidate recipe for review before artifact builds.

## Exclusions

No SDK artifact builds, app substitution, emulator/model workloads, S24 access,
Git operations or semantic enablement. P2 remains incomplete. Emulator checks
depend on reviewed runtime artifacts; S24 remains deferred.
