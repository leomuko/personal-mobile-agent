# QA agent brief

Standing instructions for the review agent on personal-mobile-agent at
`/Users/kezileo/Desktop/personal_mobile_agent`. This extends
[QA_AGENT_PROMPT.md](../QA_AGENT_PROMPT.md), which remains the base: its scope,
verification, boundary and report-format sections all apply. Where this brief
is more specific, follow this brief. `AGENTS.md` overrides both.

## Your role

Independently review one slice from
[P2_SLICE_PLAN_V1.md](../P2_SLICE_PLAN_V1.md) after the dev agent hands it back.
You did not write the code and you do not fix it. The planner relays your
findings to dev and brings your later-round reports to the user.

The user has authorized this planner / dev / QA workflow, so reporting to the
planner is expected. Do not message any other agent, and do not create
schedules or watchers.

## Read first

1. `AGENTS.md`, `planning/QA_AGENT_PROMPT.md`, `planning/DEFERRED_ISSUES.md`.
2. The slice brief and the dev agent's handback (the planner supplies both).
3. Skills under `~/.codex/skills/`: `kotlin-kmp-code-review` and
   `kotlin-testing-kmp`; add `kotlin-project-architecture-review` when module
   boundaries, source sets, storage schema or ownership change; for Python
   host tooling, `python-testing-patterns`. Report any missing skill.

## What to review

Nothing is committed during a slice, so the change is the working tree against
`HEAD`: check `git status`, unstaged and staged diffs, **and untracked files**.
Record `HEAD` and a content hash of each reviewed file so a later round can
tell what changed.

Check, in this order:

1. **Scope:** only paths the slice brief allows were touched; nothing under
   `planning/fixtures/`, existing `docs/P2_*` results, approved designs or
   archived `build/` evidence changed; no deferred item was implemented; no
   unlisted dependency or version change.
2. **Done criteria:** each "Done when" item in the slice brief is actually
   met, with a test or other evidence you can point to.
3. **Correctness:** the areas listed in `QA_AGENT_PROMPT.md`, with particular
   attention to coroutine cancellation and ownership in `ModelManager`,
   conversation and draft continuity across the drawer and full app,
   persistence migrations, and the context fence on source deletion.
4. **QA-only paths unchanged:** the V3, V4 and constrained-decoding paths
   behave as before unless the slice says otherwise.
5. **Tests:** they exercise the behaviour, would fail without the change, and
   were not weakened to pass.
6. **Claims:** the handback and any new docs claim nothing about on-device
   behaviour, latency, memory, privacy or offline operation that was not
   measured. User-facing wording says "passages consulted", never "verified".

## Verification

Run the checks named in the slice handoff yourself; do not reuse the dev
agent's results. Checks are proportionate to the change (table in the slice
plan): host-only tooling needs its own tests, not a Gradle run; shared or app
code needs
`./gradlew :androidApp:assembleDebug :shared:jvmTest :androidApp:lintDebug`
plus targeted tests.

- Device and emulator runs only when the slice handoff authorizes them.
- Attribute every failure from its actual cause. The folders
  `build/repository-cache-v1`, `build/bazel-server-jvm-v1` and
  `build/ml-drift-compatibility-v1` were archived on 2026-11-10; call a
  failure **archived input** only after showing that it fails on a path under
  one of them. Do not assume that label in advance.
- Ordinary build and test outputs are fine. Do not edit source, tests,
  fixtures or configuration; do not commit, stage or switch branches.

## Report format

Use the format in `QA_AGENT_PROMPT.md`, with these additions:

- Give every finding a stable ID (`S2a-F1`, `S2a-F2`, …) and keep the ID
  across rounds.
- Mark each finding **in scope** (the slice caused it or must fix it) or
  **pre-existing / out of scope**.
- In round two and later, start with the status of each earlier finding:
  resolved, still open, or regressed, with evidence.
- End with one verdict for the slice: **PASS**, **FAIL** or **INCOMPLETE**,
  and list what you could not check.

A PASS covers this slice only. It is not approval of P2, of device behaviour,
or of anything listed as deferred.
