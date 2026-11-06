# C++ and native-build skills: Scout research

2026-11-06. Research only; no skills installed, native qualification performed or
application dependencies changed. A user-requested Research Scout subagent
searched and reviewed four candidate skills. The parent reviewed the recommended
entry points, the two Bazel repository/platform references, both Clang references
and the C++ Pro entry point before making the recommendations below.

## Reusable scout

Project definition: `.codex/agents/project_scout.toml`. TOML parsed with the
existing reference environment's vendored TOML parser; no package was installed.
The agent inherits its model and parent permissions. Shell, apps, plugins,
memories and permission-request capabilities are disabled in its definition.
Instructions require on-demand primary-source research, fact/hypothesis
separation, version-specific findings and no edits, installs, native workloads,
device actions, external messaging or delegated descendants.

The live search used a separately spawned scout with the same research-only
brief. The saved custom definition was not test-dispatched by name in this turn;
TOML validation is not proof of runtime agent discovery or capability enforcement.

Official configuration reference:
https://learn.chatgpt.com/docs/agent-configuration/subagents

## Recommendations

### First choice: Bazel Evidence Engineering

- Repository: `Kingda8/Agent-skills-for-Bazel`
- Revision: `1fdaa839a030421d5e69709bde1bfd72f773b54e`
- Path: `skills/bazel-evidence-engineering`
- Entry point: https://github.com/Kingda8/Agent-skills-for-Bazel/blob/1fdaa839a030421d5e69709bde1bfd72f773b54e/skills/bazel-evidence-engineering/SKILL.md
- Entry SHA256: `854cbbdd2242e973a21ef07c54b087a10f69eaa79a717b2bb709106cc207034e`
- Declared license: MIT; skill-local LICENSE.txt was read.

Good fit for LiteRT-LM dependency resolution, configured graphs, toolchain
selection, artifact provenance and distinguishing analysis from executed proof.
It respects repository pins and explicit action boundaries. Its compatibility
guidance references Bazel 9.2.0; our pinned 7.6.1 remains authoritative. Do not
treat examples as an upgrade instruction or permission to run Bazel. This is a
build-evidence skill, not a C++ language/lifetime expert or linker fix.

### Conditional candidate: Clang

- Repository: `mohitmishra786/low-level-dev-skills`
- Revision: `bdc58472fa9f309ed1b3f7d985a0d8e9bd8f4608`
- Path: `skills/compilers/clang`
- Entry point: https://github.com/mohitmishra786/low-level-dev-skills/blob/bdc58472fa9f309ed1b3f7d985a0d8e9bd8f4608/skills/compilers/clang/SKILL.md
- Entry SHA256: `0e6fcb1d4c4a0eea87a82c587f9644352f3ed809d75aaed0966eaa65372b1057`
- Repository license: MIT; root LICENSE was read.

Useful diagnostic, template-error, static-analysis and compiler reference material.
Do not adopt wholesale. It includes in-place clang-tidy fixes, broad header/lint
examples and optimization advice involving aliasing/vectorization assumptions.
It also uses --show-fixits, which the parent did not find in the consulted current
official Clang argument reference. Flags and Apple-specific claims need checks
against our exact toolchain before use. Scope or correct those instructions before
recommending it as default project guidance; no linker, LTO, sanitizer or compiler
execution is authorized by installing a skill.

### Hold: C++ Pro

- Repository: `Jeffallan/claude-skills`
- Revision: `078a25cc5bfd8011459be7b91d004e900c1d02d3`
- Path: `skills/cpp-pro`
- Entry point: https://github.com/Jeffallan/claude-skills/blob/078a25cc5bfd8011459be7b91d004e900c1d02d3/skills/cpp-pro/SKILL.md
- Declared/repository license: MIT; repository license checked by the scout.

Relevant template, traits and ownership topics, but its mandatory concepts,
sanitizer, warning and optimization policies are a poor default for frozen
C++17/C++20 diagnostic experiments. The scout additionally flagged concurrency
examples for further correctness review; those examples were not independently
reproduced by the parent. No adoption or broad policy change is recommended.

The scout also reviewed `skills/build-systems/bazel` from the same low-level-dev
revision. It is a weaker alternative for this project: command examples include
broad scopes, remote services and relaxed sandboxing. Prefer the evidence-focused
Bazel candidate rather than installing both.

## Boundary and next action

No dedicated general-purpose C++ skill is newly qualified by this search. If a
skill is selected for installation later, use its immutable revision, review the
complete installed directory, retain licenses/hashes and route it narrowly in
sympathy with existing project instructions. Do not install the surrounding
repository, hooks, plugins, MCP servers or runtime packages as a side effect.
No installation was authorized or performed by this research request.

The scout produces research evidence, not independent product QA or execution
authorization. Model interchangeability, P2 quality gates, current native freezes,
the storage floor and emulator/S24 boundaries remain unchanged.
