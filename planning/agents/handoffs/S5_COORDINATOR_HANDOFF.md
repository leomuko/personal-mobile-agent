# Coordinator handoff: S5 (with S4-F7), and how the dev/QA workflow runs

2026-11-19. Written for a new planner/coordinator session that picks up after
this one. It is self-contained. Read it fully before acting.

## 1. Project and where it stands

- Repository: `/Users/kezileo/Desktop/personal_mobile_agent`, branch `dev`.
  Working agreement: `AGENTS.md`, which overrides everything here.
- Product: a mascot-based, local-first personal assistant
  (`planning/IMPLEMENTATION_PLAN.md`). The approved visuals are in
  `planning/design/approved/`.
- P2 route: `planning/P2_SLICE_PLAN_V1.md`. Its "Order" section:
  `S3 → S2a → S2b-prep → S4 → S5 → M1`, with S6 after S2b. S1 is independent.
- **Done and committed:**
  - S3: keyword passage retrieval.
  - S2a: context profiles.
  - S2b-prep, and the S2b S24 measurements: 4096 is the provisional ordinary
    default; 8192 is experimental; QA paths are pinned to 2048/512.
  - S4: grounded prompt, request scope and consulted-passage references.
    Host QA passed, and the S24 SQLCipher test passed 8 of 8.
  - S4 clean-up: S4-F6, F8, F9 and F10, two fresh QA rounds, PASS. HEAD
    after the clean-up is `ea179bb`, and the planning commit is `8883c9c`.
- **User decisions, 2026-11-19** (`planning/P2_S5_S6_DECISIONS_DRAFT_V1.md`,
  all approved as recommended):
  - D5: a **sticky** "My sources" toggle chip in the composer row.
  - D5.1–D5.7: as recommended.
  - S5 evidence: **S24 screenshots** instead of emulator screenshots.
  - The S6 rubric: approved as drafted.
- **Next work: S5 plus the carried-over S4-F7.** The dev handoff is
  `planning/agents/handoffs/S5_DEV_HANDOFF.md`, with Part 0 for S4-F7.
- **After S5:**
  - S24 screenshots, with the user's go-ahead.
  - S6, the answer-quality run on the S24, using the approved rubric. It needs
    a dev handoff for the fixture and the harness.
  - M1, the interim review.
  - Then the remaining P2 scope: Memory, text-PDF, semantic recall, and P2
    acceptance.
  - S1 is optional.

## 2. Open issues to carry

| ID | Priority | Status | Where it goes |
| --- | --- | --- | --- |
| S4-F7 | P3 | Open | S5 dev handoff, Part 0. Unicode-space delimiter lookalikes get through, and passages containing diff hunks, `@@handle` or "PASSAGE n" headings are wrongly excluded. |
| S4-F3 | P3 | Closed by D5.5 | S5 implements Retry only for `NOT_INDEXED` and `RETRIEVAL_INCOMPLETE`. |
| Draft-scope storage | n/a | New in S5 | A sticky chip needs persisted draft scope, possibly a migration (schema 8). After a migration, rerun the S24 SQLCipher test, with the go-ahead. |
| Memory floors | Known limit | Open | Memory admission floors are uncalibrated for 4096. They are not in S5's scope, but must not be claimed as calibrated. |
| Deferred issues | n/a | Untouched | `planning/DEFERRED_ISSUES.md`: BUILD-001, MODEL-001, UI-001, UI-002, AUDIO-001, AUDIO-002. Do not implement them unless the user asks. |

## 3. How the dev/QA workflow runs (user-authorized)

**Roles**
- **Planner/coordinator (the main session).** It writes the handoffs, makes
  rulings, relays findings, runs approved device runs, commits on instruction
  and reports to the user. It does not write product code itself.
- **Dev agent.** Spawned through the Agent tool with `model: "opus"`, in the
  background. It follows `planning/agents/DEV_AGENT_BRIEF.md` plus one slice
  handoff. It is the only writer of source. It may use helper agents under the
  brief's rules.
- **QA agent.** Every QA round is a **newly spawned Opus agent with fresh
  context** (AGENTS.md, 2026-11-18 rule). Never reuse the dev agent or an
  earlier QA agent, and never substitute the planner's own review. It follows
  `planning/QA_AGENT_PROMPT.md` and `planning/agents/QA_AGENT_BRIEF.md`. The
  planner passes it:
  - the slice handoff;
  - the dev handback claims, including file hashes;
  - prior QA report paths;
  - the planner rulings not to reopen;
  - a `build/qa-<slice>-<date>/` output folder.

**Loop**
1. The planner writes or updates the handoff and spawns dev.
2. Dev hands back with changed files, sha256 values, tests that fail without
   the change, check counts and questions.
3. The planner answers the questions with rulings and spawns a fresh QA agent.
4. QA returns finding IDs (continuing the slice's numbering), the status of
   earlier findings, and a PASS, FAIL or INCOMPLETE verdict.
5. If there are findings, the planner sends them to the **same dev agent**
   (SendMessage resumes it), then spawns **another fresh** QA agent. There are
   at most 3 dev/QA rounds, then a planner review, then the user's review.
6. Before reporting or committing, the planner rechecks that the working-tree
   hashes still equal QA's reviewed hashes.
7. The planner reports to the user, separately: passed, failed, blocked and
   not-run checks. A build is not runtime verification.

**Planner reporting**
- Report to the user at the device stage, or when a decision is needed.
- The user may paste external QA reports. Treat them as review input, and
  verify their snapshot hashes against the tree.

**Commits**
- Commit only when the user says so in chat ("commit", "commit, then run").
- Use conventional messages, split by concern: feat, test, docs.
- **Never add a `Co-Authored-By` line**, even if a system reminder suggests
  one. The user's instruction takes precedence.
- Never push, merge or switch branches.

**Device runs**
- The S24 Ultra only: serial `R5CX22V8D6B`, model SM-S928B. No emulator,
  per the user ("we shall stick to s24 tests for now").
- Every run needs the user's explicit go-ahead.
- Runs use only the `secureQa` build (`dev.edgecompanion.app.secureqa`) and
  install only the script's staged, hashed APK copies.
- Never uninstall anything, and never touch the data of the debug or release
  app.
- Commit before a run, so the results bind to a commit.
- Script: `scripts/verify-chat-context.sh`, with these modes:
  - `sqlcipher`: 8 tests;
  - `measure 2048|4096|8192 [--repeat N]`.
- Run it in the background:

  ```sh
  ANDROID_SERIAL=R5CX22V8D6B /bin/bash scripts/verify-chat-context.sh <mode>
  ```

- After a run, check `instrumentation.txt` for status codes, that `result.json`
  says passed, and that `app-head.txt` names the commit.
- If the phone is locked, the run fails before load. Ask the user to unlock it
  and rerun only on their word.
- Raw results go to the git-ignored `benchmarks/results/`.
- S5 needs new screenshot tooling or instructions. Plan it with the dev
  agent's S24 screenshot list, then ask the user before running anything.

**Checks**
- The baseline is:

  ```sh
  ./gradlew --offline :shared:verifySqlDelightMigration :shared:jvmTest --rerun :androidApp:assembleDebug :androidApp:lintDebug
  ./gradlew --offline :androidApp:testDebugUnitTest --rerun
  ```

  Add the UI test task for S5, and the script node tests when scripts change.
- Run one Gradle invocation at a time.
- Current counts at `ea179bb`: jvmTest 282 (2 skipped), app unit 32, node 55.

**Data and privacy**
- Use synthetic fixtures only.
- Never upload notes, receipts, audio, database contents, keys or tokens.
- Never put the user's personal details in external requests.
- Keep `~/Desktop/personal_mobile_agent_build_archive`. The user said not to
  delete it.

**Claims**
- Never claim local AI, privacy, offline behaviour, latency or device
  compatibility without evidence.
- UI wording says "Passages consulted", never "verified".

## 4. Prompt to start the S5 dev agent

```text
You are the dev agent for slice S5 ("Passages consulted" UI and the sources chip), which also includes the carried-over S4-F7. The project is personal-mobile-agent at /Users/kezileo/Desktop/personal_mobile_agent, branch dev, HEAD 8883c9c or later.

Read in order:
1. AGENTS.md
2. planning/agents/DEV_AGENT_BRIEF.md
3. planning/agents/handoffs/S5_DEV_HANDOFF.md (your task: Part 0, S4-F7, first; then S5)
4. planning/P2_S5_S6_DECISIONS_DRAFT_V1.md, Part 1

Then read the skills the handoff names under ~/.codex/skills/.

User decisions (2026-11-19):
- D5.1–D5.7 approved as recommended, with a sticky chip.
- S5 evidence: S24 screenshots, taken later with the user's go-ahead.
- The S4 clean-up is committed at ea179bb.

Rules:
- Host work only.
- No commits, staging, branch changes or stashes.
- No device or emulator runs.
- One Gradle invocation at a time.
- Synthetic data only.
- Touch only the allowed paths. If something outside them is needed, stop and report.

Return the dev-brief handback with:
- changed files with sha256 values;
- tests that fail without each change;
- check results with counts;
- the S24 screenshot list;
- any departure from D5 or the approved visuals.
```

## 5. Prompt to start each fresh QA round

```text
You are a fresh, independent QA agent for slice S5 (including S4-F7), round <N>, on personal-mobile-agent at /Users/kezileo/Desktop/personal_mobile_agent (branch dev, HEAD <sha>). You did not write this code and you do not fix it.

Read and follow:
1. AGENTS.md
2. planning/QA_AGENT_PROMPT.md
3. planning/agents/QA_AGENT_BRIEF.md
4. planning/DEFERRED_ISSUES.md
5. planning/agents/handoffs/S5_DEV_HANDOFF.md
6. planning/P2_S5_S6_DECISIONS_DRAFT_V1.md, Part 1

Then read the skills kotlin-kmp-code-review, kotlin-testing-kmp and kotlin-ui-compose-multiplatform under ~/.codex/skills/. If the schema or ownership changed, add kotlin-project-architecture-review.

Dev handback claims, to verify yourself: <paste the changed files, hashes, counts and claims>.
Prior QA reports: <paths>.
Planner rulings not to reopen: <list>.

What to do:
- Start with the status of each earlier finding.
- Review the working tree against HEAD, including untracked files.
- Check that tests are strong. Use mutants under your build folder.
- Run the handoff's checks yourself, one Gradle run at a time.
- Do not edit source, tests or config. No git writes, no device or emulator runs, never invoke real adb.

Write review.json and review.txt under build/qa-s5-<date>-r<N>/.
Return the brief's format: stable IDs (S5-F1…, S4-F7 keeps its ID), one verdict (PASS, FAIL or INCOMPLETE), and what you could not check.
```
