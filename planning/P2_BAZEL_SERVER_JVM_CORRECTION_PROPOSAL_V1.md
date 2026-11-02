# Bazel server JVM correction proposal v1

2026-10-29. Identity `bazel-761-server-jvm-correction-v1`.
**Recommended for review; not adopted and not an execution authorization.**

## Observed blocker

The exact SHA-verified Bazel 7.6.1 macOS ARM64 executable contains `Bazel.class`
with class-file major 65. The reviewed analysis freeze explicitly selected
Temurin 17.0.20.1+1, whose JVM accepts major 61. All three frozen commands stopped
at Java startup, before root WORKSPACE evaluation. No ML Drift configuration or
source/API incompatibility was established.

This is a documented incompatibility of **that analysis host profile**, not a
reason to change model, SDK, ABI or runtime behavior. The attempted profile is
rejected for further use. Preserve its freeze, argv, raw logs and report.

## One recommended alternative

Use the **same Bazel 7.6.1 executable's bundled Java 21.0.5 for its server**, under
a separately reviewed toolchain decision and new analysis freeze. Do not upgrade
Bazel, ML Drift, LiteRT-LM or any SDK dependency. Do not silently replace the
server JVM in the old freeze or rerun under its identity.

The executable is a self-extracting ZIP. Read-only inspection confirms:

| Input | SHA-256 |
| --- | --- |
| Existing Bazel 7.6.1 executable | `45cca81a839d7495258b19ee8371c7b891f350586ef37b9940f7b531eb654cc8` |
| Embedded `A-server.jar` | `50e9295e57eebfea7fe0e487e6aae2aaf7e131a6055c1ed12863f4b668a1d249` |
| Embedded JDK release metadata, Java 21.0.5 | `2aeb383501a09146b53d47ee743734cc73be197f07dbe911074cc795c9964bb5` |

Those embedded bytes match the extracted copies retained from the failed
attempt. The bundled JVM has **not** been executed for this proposal. A release
string alone is not a complete toolchain identity or proof that a retry works.

## Required decision and checks before a retry

1. Independently review the exact server-runtime change. The current handoff
   treats additional toolchain changes as decisions; a generic continuation is
   not substituted for this specific review/authorization boundary.
2. Stage a private immutable copy of the embedded JDK directly from the verified
   executable; verify every byte/mode/internal symlink against its archive.
   Do not consume mutable Bazel installation-output paths in the new freeze.
3. Add a host-only preflight comparing the server class-file requirement with
   the selected server JVM version before launching commands. Test incompatible,
   unknown, tampered and supported identities without executing model code.
4. Keep SDK compilation/toolchain **declarations** and all dependency versions
   unchanged. Preserve Java 17 selection where it is explicitly part of the
   SDK compilation configuration. `--server_javabase` is not proof of Java
   source/target level or of the resolved compilation runtime: inspect those
   configured toolchains and reject an unintended change.
5. Bind the new server JVM separately from compiler/runtime toolchains, retain
   both ABI profiles, source locks, repository guards and the same finite command
   scope. Create/review a new freeze and attempt identity before execution.

The tradeoff is a new, explicitly qualified server runtime, an additional full
JVM inventory and fresh analysis evidence. It avoids a speculative Bazel upgrade,
new SDK baseline or changes to the compiler patch. The SDK's Java compilation
selection must be verified rather than assumed unaffected.

If that decision is not accepted, keep the route blocked; no alternative server,
opaque GPU component, dependency pin or CPU-only scope is automatically selected.

## Scope retained

This proposal changes documentation only. It grants no retry, SDK artifact build,
app substitution, emulator/model workload, S24 access, data deletion or commit.
Configured dependency resolution, C++ interface compatibility, compilation and
runtime qualification remain separate unproved gates. P2 is incomplete and
semantic search remains disabled.
