# Diagnostic Skills: Reviewed Local Installation

2026-10-28. Explicitly requested by the P2 grammar-progress handoff. Installed
only the following directories using Codex's `skill-installer` helper with
`--method download` and immutable `--ref` values. No repository checkout, agent,
hook, plugin, MCP connection, runtime package or automatic update was installed.
Existing Kotlin/KMP, Python-testing and property-testing skills remain unchanged.

| Skill | Repository and immutable revision | Installed directory |
| --- | --- | --- |
| Rust/C FFI safety | `ytakano/rust_skills@9692ab77d916cb5aeb7f190651b76861c8a876f0`, `skills/rust-c-ffi-safety` | `/Users/kezileo/.codex/skills/rust-c-ffi-safety` |
| Defensive Bash | `wshobson/agents@0874a27b17a22e2b7c2e2bcd725768e0803cad06`, `plugins/shell-scripting/skills/bash-defensive-patterns` | `/Users/kezileo/.codex/skills/bash-defensive-patterns` |

Complete directories were downloaded to ignored `build/skill-review-20261028/`
and read before installation: both entry points, Rust's `EvaluationCatalog.md`,
and Bash's `references/details.md`. All are Markdown guidance, not executable
scripts. The Bash reference contains illustrative shell snippets; they were
reviewed as examples, not executed. Neither installed directory requires a
network service, credentials, MCP access, hooks or background monitoring. Skill
setup used public GitHub metadata/downloads. Suggested tools such as Miri or
ShellCheck have their own setup requirements; their presence is not assumed.

Every installed upstream file matches the reviewed revision byte-for-byte. The
machine-readable [provenance manifest](DIAGNOSTIC_SKILLS_PROVENANCE.json) records
all relative file paths and SHA-256 values. Bash's root MIT LICENSE is additionally
retained in its installed directory with its exact copyright notice and hash.
The Rust repository/tree and reviewed files contain no declared license; no
license was inferred. This requested machine-local installation does not claim
redistribution rights or add those skill contents to the application repository.

## Assessment Only: rust-hardening

Read the complete `skills/rust-hardening/SKILL.md` and
`templates/Cargo-lints.toml` from the same Rust revision. Their reviewed hashes:

- Entry point: `8d6f7fce4f48854afbb17c419d561b6132ef9fb19f5ef0ca3b0a6070c3fc1ed4`.
- Template: `b96f065dfefd353599f96f9f560a8096583a70e069324ee39881cc73469893fd`.

The policy mandates warning/Clippy gates, broad bans on arithmetic/casts/indexing,
release overflow checks, a feature-matrix policy, suppression governance and
additional tools including nightly Miri. Adopting it would change project lint
and coding policy beyond the requested narrow diagnostic corrections. It is
**not installed, routed or adopted**; separate user agreement is required.
Running applicable formatting/default lint checks in this slice does not adopt
that policy or enable additional dependency features. Optional memory-safety,
Koin and CameraX skill additions remain deferred.

## Routing And Availability

Narrow routing is in `AGENTS.md`: Rust/C FFI diagnostics and Bash/QA wrappers only,
in sympathy with the existing architecture and supported shells. Skills do not
override user authorization, frozen evidence, device boundaries or Git rules.
They become automatically discoverable on the next turn; the reviewed contents
can be read and applied directly during this turn. They remain machine-local
development guidance, not Kotlin/Android dependencies or proof of correctness.
