# P2 Grammar Progress: Host-Only Candidate Results

2026-10-28. Work performed on `dev`, preserving unrelated changes.

**Implementation and host checks completed; candidate acceptance FAILED.**
The single tested `x-guidance.whitespace_flexible=false` policy removes the
tested numeric-boundary whitespace continuation, but the frozen escaped-Unicode
witness is not admitted by either baseline or candidate reconstruction. No
witness was removed, output repaired, criterion relaxed or policy retuned.
There is **no native-experiment proposal**. P2 remains incomplete and semantic
search disabled. No S24, emulator, Gemma, packaged JNI/GPU, app installation,
production integration, commit, push or branch change occurred in this slice.

## Skills And Boundary Findings

Installed only `rust-c-ffi-safety` and `bash-defensive-patterns` at reviewed
immutable revisions using the Codex installer. Complete directories/resources
were read before installation and installed bytes verified. Provenance, license
review and narrow `AGENTS.md` routing are documented in
[DIAGNOSTIC_SKILLS_PROVENANCE.md](DIAGNOSTIC_SKILLS_PROVENANCE.md).
`rust-hardening` was assessed but not installed/adopted. Its mandatory arithmetic,
lint and feature-matrix policies remain subject to separate user agreement.

The [boundary review](P2_GRAMMAR_PROGRESS_BOUNDARY_REVIEW.md) reports the concrete
findings first: blocking broker writes could outlast the read-side watchdog,
and broken callback output could cause a C-ABI panic abort. Both were reproduced
with owned synthetic processes and corrected narrowly. Regression checks also
cover callback null/alignment/length rules, C header layout, typed interruption,
owned-child cleanup, malformed callback bytes, identity tampering and uncertain
reaping. No broad Rust hardening policy or unrelated shell rewrite was applied.

Original diagnostic workspaces and reviewed host/emulator evidence remain
unchanged. Corrections were built into the new ignored workspace
`build/grammar-progress-v1-final/` with pinned Rust 1.87.0, the same Cargo.lock,
40 registry identities, six patches and dependency feature set. The app's
2048 context / 512 output reservation, sampling assumptions, resource policy,
strict validator and deadlines remain unchanged.

## Frozen Candidate And Comparison

Inputs are separately versioned under
`planning/fixtures/grammar-progress-v1/`. Candidate schemas differ from their
unchanged V2 counterparts only by:

```json
"x-guidance": {"whitespace_flexible": false}
```

Both the fixed sentinel and general evidence-output schema use this one policy;
they are not two competing candidates. Three malformed/unsupported option inputs
are explicit negative checks, not alternate policies. The preparation lock was
written before collection and remains unchanged.

The original observed prefix is retained and tested separately. A new compact
synthetic prefix is constructed from the known sentinel object; it is not a
repaired native response. Baseline and candidate use **that same compact
prefix**. Raw vocabulary pieces remain the inspected provider boundary;
decoded-byte vocabulary is comparison-only. EOS is still 1, derived from the
same pinned metadata/provider rule. Actual S24 sampled token IDs and logits
remain unavailable; these are reconstructed states.

Before evaluating the candidate, the corrected diagnostic reproduced all **16
baseline masks and six tokenizer transcript hashes** from the previous host
replay, byte-for-byte.

| Tested compact numeric boundary | Baseline | Candidate |
| --- | --- | --- |
| `0` available | Yes | Yes |
| `0` consumable | Yes | Yes |
| One newline / two newlines | Allowed | Both disallowed |
| Space | Raw-piece boundary disallows; decoded comparison allows | Disallowed in both |
| Wrong digit `1` | Disallowed | Disallowed |
| Premature EOS | Disallowed | Disallowed |
| Attempt to consume newline | Continues through frozen checkpoints | Rejected before first newline |

The candidate rejects the original prefix's earlier formatting newlines, as
expected. This rejection is not mistaken for a failure at its later numeric
field. Quoted spaces, quote/backslash/slash escapes, escaped control characters
and literal Unicode completions remain admitted in the tested witnesses; their
quotation contents and revision-bound UTF-16 coordinates are unchanged.

## Preserved Acceptance Failure

The complete frozen set contains 14 synthetic structural witnesses. An unchanged
Kotlin validator independently accepts nine and rejects five deliberately wrong
quotation/revision/span/Unicode-boundary/aggregate outputs. Both raw and decoded
grammar paths are tested; grammar admission of a fabricated citation does not
override those strict rejections or establish semantic support.

The `unicode-escaped` witness is valid JSON and passes the unchanged strict
validator, but canonical token reconstruction is rejected at zero-based token
position 39, ID 236819 (`9`), following `"q":"\u`. The attempted quotation is
`\u9752\ud83d\ude42 e\u0301`, representing the same text as the admitted literal
Unicode witness. **Rejection occurs in the first Unicode escape, before the
surrogate-pair escapes are reached.** This is not evidence of a specific
surrogate-pair defect.

A separately retained focused comparison obtains the exact same rejection in
all four combinations: baseline/candidate × raw/decoded vocabulary. Therefore
this run does not establish that the whitespace option introduced the failure.
It also does not establish that every possible tokenization of that JSON is
rejected. The frozen required reconstruction still failed, and was not
reclassified as acceptable because the baseline shares the limitation.

The consolidated replay collected **41 checks: 39 expected outcomes and two
failed acceptance checks**, the raw/decoded versions of this witness. All three
malformed/unsupported option checks produced identified native schema-option
errors. Infrastructure/transport/deadline failures are not counted as successful
option rejections. Final status is `failed-host-acceptance`, exit code **2**.

This is a failed host qualification under the frozen witness set, not a Gemma
accuracy result or proof of universal progress. It does not authorize another
native run, a tokenizer substitution, suppression lists, output repair, sampling
changes, automatic fallback or a deadline increase.

## Verification And Retained Attempts

One consolidated final host campaign was completed after focused prerequisite
tests and an infrastructure repair. All attempts remain separate:

1. `development-check/` preserved the first escaped-Unicode gate failure.
2. `consolidated-host-1/` retained the same quality failures, then encountered a
   process-exit/group-signal reaping race during an expected schema-error check.
   That run is incomplete, not a pass. Its JVM run had two opt-in tokenizer tests
   skipped because their existing fixture environment variable was absent.
3. `consolidated-host-2/` used corrected owned-process reaping and existing
   opt-in tokenizer fixtures. It completed all 41 checks and retained the same
   two Unicode-reconstruction failures.
4. `consolidated-host-3/` added prepared-metadata admission checks and retained
   the same two quality failures. Independent QA then marked its source snapshot
   FAIL for the remaining setup-interruption and numeric-type gaps; that review
   and source map are preserved.
5. `consolidated-host-4/` is the final current-source run after both QA fixes and
   deterministic regressions. It completed the same 41 checks with the same two
   failures. No candidate/schema parameter, witness or gate was tuned to results.

Passed in the final campaign:

- Pinned Rust formatting check, locked/offline release build and default
  Clippy (`--no-deps -- -D warnings`), without enabling extra features.
- **7 Rust tests**, including callback buffers, broken pipes and C ABI layout.
- **43 Python tests**, with zero failures or skips.
- **173 shared JVM and 171 Android-host tests**, freshly executed with zero
  failures, errors or skips. The new Kotlin test checks all 14 frozen witnesses
  against the unchanged strict validator and exact quotation slices.
- Baseline masks/transcripts, candidate numeric-mask restrictions, complete
  S/C/M/U structures, literal Unicode/quotation preservation and identified
  malformed-option rejection, within the explicit observed outcomes above.
- Independent C header layout compilation with `clang -std=c11 -Wall -Wextra
  -Werror`; Rust layout assertions agree.

Not run: nightly Miri/sanitizers, ShellCheck (not installed), Android app builds,
device workloads, packaged SDK enforcement, semantic evaluation or any search/
Gemma latency qualification. A pinned stable/native source build is not a proof
of complete FFI soundness or packaged-binary equivalence.

## Reproduction And Identities

The host-only entry point refuses to overwrite a report directory, pins Rust
1.87.0 and preserves nonzero outcomes. Exact commands used include:

```sh
build/constrained-decoding-reference/bin/python scripts/diagnostics/mask_replay/prepare.py \
  --out build/grammar-progress-v1-final

env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  P2_ENCODER_COUNTING_DIR="$PWD/build/encoder-counting" \
  bash scripts/verify-grammar-progress.sh \
  build/grammar-progress-v1-final \
  build/grammar-progress-v1-final/consolidated-host-4
```

The second command's expected recorded exit is **2**, not success. Preparation
requires the existing local pinned model and verified dependency downloads;
the final campaign uses the existing Python environment and offline Rust/Gradle
dependencies. No model download or device selector is part of these commands.

| Identity | SHA-256 |
| --- | --- |
| Frozen candidate lock | `ac08cdab90f90b959681002270114f35d4e5bfdfa94b89aa167d5b334a9a6749` |
| Sentinel candidate schema | `195b5bff023f8872a55224630685c91da947a3d6a8d788fa5c02dd5bbef4ce2f` |
| Evidence candidate schema | `9a67c6a8e9a57e64f92772d0e735d29eb70147a5193d6d2077e8d3ad53814373` |
| Host diagnostic executable | `088d978ea405a2d954efedbc9189a5397ce02a974e9fc35d3f68a7aa0347f995` |
| Consolidated replay report | `2b2f94787844b2b75c4f124d21bfcc9f489b6a57c21b7780c342ce94eda2a97e` |
| Unchanged strict validator | `10be77c8586f0ee9931c9c33b965acd3608fe379010afde75e27b1dfd5526000` |

The complete [20-file implementation/provenance snapshot](P2_GRAMMAR_PROGRESS_SOURCE_SNAPSHOT.json)
has SHA-256 `20a167d9d1de9cbb3e825efbdd3557415e6b5bc08f561305b93d6c82309c8ee0`.
The results document and snapshot themselves are outside that non-self-referential
map. Exact mask files, tool/configuration/input hashes, tokenizer transcripts,
failed native frames, compiler/test logs and exit statuses are retained under
ignored `build/grammar-progress-v1-final/`. Historical V2 archives, raw failures,
unrun cases, schema locks and executable stale-source guards remain preserved.

Independent QA follow-up **PASS** for the final 20-file snapshot: both original
findings are resolved, with no unresolved findings in this limited code/evidence
scope. QA verified host-4 identities, counts, baseline equivalence and retained
failures. This does not convert candidate acceptance into success. Per the
handoff, stop here without a native proposal while that gate is failed.
