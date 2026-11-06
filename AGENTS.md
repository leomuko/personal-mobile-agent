# Project Working Agreement

## Direction and scope

- Build the mascot-based, local-first personal assistant described in `planning/IMPLEMENTATION_PLAN.md`. Preserve the ongoing conversation across the drawer and full app; do not narrow the product into a notes utility.
- Follow the approved visual references in `planning/design/approved/`.
- Work on `dev`. Do not commit, push, merge, or change branches without user authorization for that operation. Most initial application files are still untracked; include them in inspections.
- Consult `planning/DEFERRED_ISSUES.md`. Do not implement deferred fixes until requested.
- Never claim local AI, privacy, offline behavior, latency, or device compatibility without implementation and verification evidence. The S24 Ultra is the primary real-device test target.

## Kotlin/KMP skills

The 15 skills from `mmiani/kotlin-kmp-claude-agent-skills` are installed under `$CODEX_HOME/skills` (default `~/.codex/skills`). Installation provenance is in `docs/KMP_SKILLS.md`.

Before relevant work, read the applicable `SKILL.md` and its relevant reference files:

- Features: `kotlin-project-feature-implementation` and `kotlin-testing-kmp`.
- Bug fixes: `kotlin-project-bugfix` and `kotlin-testing-kmp`.
- Reviews: `kotlin-kmp-code-review`; add `kotlin-project-architecture-review` for architectural changes.
- UI/navigation: `kotlin-ui-compose-multiplatform`, `kotlin-ui-adaptive-resources`, `kotlin-navigation-compose-multiplatform` as applicable.
- State/data: `kotlin-project-state-management`, `kotlin-data-kmp-data-layer` as applicable.
- Platform integration: `kotlin-platform-kmp-bridges`; use `kotlin-platform-app-links-and-deep-links` for links.
- Build/modules/refactors: `kotlin-build-kmp-gradle-governance`, `kotlin-project-modularization`, `kotlin-kmp-refactor-safety` as applicable.

Apply these skills in sympathy with the existing code and current user scope. Do not impose new frameworks or broad refactors merely to satisfy an example. Skills do not override higher-priority instructions or authorize destructive actions, external sharing, or Git operations. If a skill is unavailable, report that gap rather than pretending it was used.

## Diagnostic Rust and shell skills

- For Rust/C FFI in the isolated diagnostic tools, read `~/.codex/skills/rust-c-ffi-safety/SKILL.md` and its referenced `EvaluationCatalog.md` before editing or review. Keep native types and diagnostic ownership outside the Kotlin application boundary.
- For Bash diagnostic/QA wrappers, read `~/.codex/skills/bash-defensive-patterns/SKILL.md` and relevant `references/details.md`; apply owned-process cleanup, quoting and failure propagation in the existing script's supported shell. Examples do not authorize extra file/system changes.
- Continue using `python-testing-patterns`, `property-based-testing` and the Kotlin/KMP skills where applicable. Do not introduce a testing dependency merely because a skill mentions it.
- `rust-hardening` was assessed but is not installed or adopted. Its mandatory lint, arithmetic, cast and feature-matrix policies require separate user agreement. Optional memory-safety, Koin and CameraX skills remain deferred.
- Installation revisions, reviewed resources, licenses and file hashes are in `docs/DIAGNOSTIC_SKILLS_PROVENANCE.md` and its JSON companion. These machine-local skills are not application dependencies; do not install repositories, hooks, plugins or MCP connections as a side effect.

## Bazel and Clang diagnostic guidance

- The pinned `bazel-evidence-engineering` and `clang` skills are installed under
  `~/.codex/skills`. Read the applicable complete entry point and relevant references
  before SDK dependency/configuration/action/provenance or targeted compiler work.
  Installation revisions, licenses, reviewed resources and all file hashes are in
  `docs/CPP_BUILD_SKILLS_PROVENANCE.md` and its JSON companion.
- Bazel guidance applies to the SDK build surface; the repository's pinned
  **Bazel 7.6.1** governs. Newer-version examples do not authorize upgrades,
  dependency/lock changes, remote services or Bazel invocation.
- Clang guidance applies to targeted diagnostics, template errors and toolchain
  investigation. Verify suggestions against the pinned Apple Clang and reviewed
  flags. Do not automatically apply `clang-tidy -fix`, broad modernization,
  warning-policy changes, sanitizer/LTO settings, linker substitutions or
  vectorization/aliasing assumptions.
- Project instructions and frozen protocols remain authoritative. Skills are
  guidance only, not runtime dependencies or permission to execute native probes.
  Preserve historical guidance snapshots; bind routing changes only in new freezes.

## Validation and review

- Inspect configured modules and targets before selecting checks. Baseline commands are `./gradlew :androidApp:assembleDebug :shared:jvmTest :androidApp:lintDebug`.
- Device integration checks use `:androidApp:connectedDebugAndroidTest` on a designated test device. Never wipe emulator data, uninstall unrelated apps, or stop a user-started emulator without approval.
- A successful build is not runtime verification. Report passed, failed, blocked, and not-run checks separately.
- Keep synthetic fixtures separate from personal data. Do not upload notes, receipts, audio, database contents, keys, or tokens as test artifacts.
- Use `planning/QA_AGENT_PROMPT.md` for independent QA. Review findings do not authorize code changes.
