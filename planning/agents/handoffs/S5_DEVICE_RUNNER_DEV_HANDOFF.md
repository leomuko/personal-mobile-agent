# S5 device-runner dev handoff: UI test mode, screenshot capture, S5-F6

2026-11-19. From the planner to the dev agent. This handoff is self-contained.

Read [../DEV_AGENT_BRIEF.md](../DEV_AGENT_BRIEF.md) first. Its boundaries,
helper-agent rules and handback format apply. Background:
- [S5_DEV_HANDOFF.md](S5_DEV_HANDOFF.md), including its rulings;
- [../../../docs/P2_PASSAGES_UI_RESULTS.md](../../../docs/P2_PASSAGES_UI_RESULTS.md),
  especially "Planned S24 captures".

**Authorization:** host work only. The user asked for this runner on
2026-11-19. Running it on the S24 is a separate step: the planner asks the user
for a go-ahead after QA.

## Where things stand

- `dev` is at HEAD `caaa5c1`. S5 is committed.
- The S24 SQLCipher gate passed 9 of 9.
- `docs/P2_PASSAGES_UI_RESULTS.md` has an uncommitted planner section recording
  that run. Keep it and append after it.
- Not yet run on a device:
  - `PassagesUiTest`, with 6 cases;
  - the 9 planned captures.
- `scripts/verify-chat-context.sh` has two modes, `measure` and `sqlcipher`.
  Both:
  - build `secureQa`;
  - stage and hash the APKs, checking identity with aapt2;
  - install only the staged copies with `adb install -r`, and never uninstall;
  - run `am instrument` under a deadline with owned-process cleanup;
  - collect evidence and bind the result to the commit.
- The Bash script must stay compatible with Bash 3.2, use explicit
  `check || fail` gates, and pass its fake-adb node tests.

## Part A: S5-F6 fix (P3, ruled)

- Show the "Add a source to use this" hint only when the library is known to
  be empty: `hasSources == false`.
- Keep the chip's enabled rule:
  `ready && (hasSources == true || checked)`. When availability is unknown,
  the chip still cannot be turned on.
- Add a host test that pins all three states:
  - unknown: no hint;
  - empty: hint;
  - has sources: no hint.
- Add an S24 capture of the empty state (already in capture 2).

## Part B: `ui` mode in the run script

- Add `scripts/verify-chat-context.sh ui`. It reuses the build, stage, install,
  deadline, cleanup and binding path unchanged. It runs these classes:
  - `dev.edgecompanion.app.PassagesUiTest`;
  - the new capture class from Part C.

  It requires `OK (<exact count>)` and rejects status codes -1 to -4 through
  the existing `FAILURE_PATTERN`.
- Also run the existing layout regressions that the new composer could affect:
  `ComposerLayoutTest` and `HostRegressionTest`. Include them in the same
  `ui` run with an exact total count, or as a separate class list in the same
  run. State the count you chose.
- **Before you change them, confirm they are safe on a real device.** They
  must not touch the debug or release apps' data, not need a model, and
  not need network. If either one isn't safe, leave it out and say why.
- The deadline is a fixed value suited to UI tests. State it.
- No thermal supervision is needed unless the existing code path requires it;
  say which.
- Pull the screenshots from the device into the run's results directory, as
  `screenshots/NN-name.png`. Then:
  - record each file's sha256 in `evidence.json`, or in an equivalent
    manifest;
  - count the PNGs, and fail the run if the count differs from the expected
    number.
- Use only the existing `.secureqa` package and the staged copies. Never
  uninstall, and never touch other packages.
- Tests in `scripts/tests/verify-chat-context.test.mjs`, using the fake adb:
  - a `ui` pass;
  - a wrong count fails;
  - a `-4` beside `OK` fails;
  - a missing or extra screenshot fails;
  - the screenshot manifest hashes match the pulled files;
  - the existing `measure` and `sqlcipher` tests are unchanged and pass.

## Part C: screenshot capture test

- Add a new instrumentation class, for example `PassagesCaptureTest`, under
  `androidApp/src/androidTest/kotlin/dev/edgecompanion/app/`. It renders the
  real shared composables (`CompanionScreen` and the Sources detail) in both
  host layouts, with **synthetic** state only.
- Follow the existing capture pattern in `AppearancePresentationTest`
  (secure-window aware). Write the PNGs into the test app's own storage, under
  `no_backup` or the existing equivalent, and let the script pull them.
- Produce one PNG for each item in "Planned S24 captures" (1–9), plus the
  variants each item lists. Name them so the mapping is obvious, and record
  this in the results note:
  - item 1: off and on, idle and typing, for each host;
  - item 8: narrow width about 320 dp, recording the actual width;
  - item 7: large font.
- For items that need a real activity lifecycle:
  - item 1's "after reopening the app";
  - item 9's Home and mascot re-entry.

  Either drive them in the instrumentation with the real activity, if that is
  safe and possible in `secureQa`, or capture the closest Compose-level state
  and state the limit plainly in the note. Do not claim lifecycle evidence
  you did not capture.
- Assert nothing about pixels. This class produces evidence for the user's
  review. It may assert that each expected node exists before it captures.
- No personal data, model or network. Use only synthetic titles and text,
  such as "Synthetic project notes".

## Allowed paths

- `scripts/verify-chat-context.sh`
- `scripts/tests/verify-chat-context.test.mjs`
- `androidApp/src/androidTest/kotlin/dev/edgecompanion/app/`: new capture
  class. `PassagesUiTest` may change only if a device-only issue is found, and
  the change must be justified.
- `ui/src/commonMain/kotlin/dev/edgecompanion/ui/conversation/presentation/SourcesPresentation.kt`
  and its test, for Part A
- `docs/P2_PASSAGES_UI_RESULTS.md`: append only, after the planner's section
- `docs/P2_DEVICE_PREP_RESULTS.md`: a short procedure note for `ui` mode

## Out of scope

- Any layout change (S5-F3 waits for the user's decision).
- Any change to the `measure` or `sqlcipher` behaviour.
- New dependencies.
- Device or emulator runs.
- Deferred issues.

## Checks to run

```sh
./gradlew --offline :ui:jvmTest --rerun :shared:jvmTest --rerun :androidApp:assembleDebug :androidApp:lintDebug
./gradlew --offline -PtestBuildType=secureQa :androidApp:assembleSecureQa :androidApp:assembleSecureQaAndroidTest
node --test scripts/tests/verify-chat-context.test.mjs scripts/tests/qa-supervision.test.mjs
/bin/bash -n scripts/verify-chat-context.sh
```

- Run one Gradle invocation at a time.
- After the node tests, no `SYNTHETIC` process may remain.
- Never invoke real adb.

## Report back

Use the dev-brief handback format, plus:
- the exact S24 command for `ui` mode;
- the expected test count and PNG count;
- the deadline;
- the screenshot list mapped to the capture items;
- which lifecycle items are real-activity captures and which are
  Compose-level stand-ins;
- confirmation that only staged `.secureqa` copies are installed.
