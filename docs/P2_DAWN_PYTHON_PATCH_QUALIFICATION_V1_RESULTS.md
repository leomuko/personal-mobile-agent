# Dawn Python and patch qualification v1 — results

**Python/generation subset PASSED; complete dependency recipe remains BLOCKED.**
P2 is incomplete and semantic search is disabled. No root fix, Bazel retry, SDK
artifact, emulator/S24 workload or Git operation was performed.

## Recorded qualification

`build/dawn-python-patch-v1/qualification-attempts/python-3/` ran against reviewed
R5 freeze `c87fcc7f6b9a0048e22165f2ea4c2c3997fbbbc81082d3124d8f6c01a124f63f`.
All eleven admitted stages passed. Owned worker plus cleanup elapsed **21.729s**;
the worker report covers **21.560s**. These exclude parent admission/publication
and are not a separately measured total-invocation time. The frozen controller
checks the original public 300s deadline through publication; cleanup was confirmed.
Recorded worker peak RSS was **60,555,264 bytes**, excluding child/combined RSS.
Public exit2 means subset success with `recipeQualified=false`, not full acceptance.

| Gate | Result |
| --- | --- |
| Jinja2 3.1.6 | Exact134899 bytes/SHA; full ZIP/CRC/RECORD passed;31 entries/496892 decoded bytes |
| MarkupSafe 3.0.3 cp313 macOS ARM64 | Exact12029 bytes/SHA; full ZIP/CRC/RECORD passed;14 entries/73708 decoded bytes |
| Candidate runtime | Private CPython3.13.13, ordinary GIL, ARM64/Darwin/64-bit, exact SOABI; pinned files/native dependencies |
| Native extension | MarkupSafe `_speedups` loaded from verified private package; no pure-Python fallback |
| Sandbox | Owned write succeeded; outside write and loopback connect denied with EPERM before candidate code |
| Render probes | Exact escaping, Unicode, autoescape, include/inheritance, do and loopcontrols passed |
| Three patch programs | Executed once each; fixed counts/preimages/postimages passed; no other source changes |
| Eight generator invocations | Both targets × control/candidate × two repeats; all eight headers reproduced |
| Output comparison | Six unaffected headers byte-equal; both affected headers equal the frozen literal transformations |
| Final input recheck | Original runtime, tools, OS caches and all frozen inputs unchanged |

The MarkupSafe native member SHA256 is
`be6bdd965d9d96a939c7b6ce4846e16c021dff0d09515a94924b3d5905263b2b`.
Module origins and exact generated-output hashes are retained. One Jinja2 download
occurred in the preceding attempt; this successful attempt reused those bound
bytes and fetched MarkupSafe once, without redirects or retries. No pip/global
package changes, wheel repairs or extra dependency downloads were performed.

## Preparation and preserved failures

Reviewed read-only R3 preparation inventoried2151 runtime entries/48271743 bytes,
ten runtime native files, one inspector native file and fifteen OS-cache backing
files (5824684071 bytes hashed, not copied). It retained26 inspector commands,
owned elapsed6.569s and confirmed cleanup. These are preparation measurements,
not candidate execution or Android compatibility.

All failed implementations, reviews and execution freezes remain preserved:

- R1 review rejected late public acceptance; R2 added a whole-invocation deadline.
- R2 review rejected an interrupt gap after hardlink creation; R3 tracks intent
  before publication and reports confirmed/uncertain revocation truthfully.
- R3 attempt `python-1` failed before acquisition/vendor execution: pinned host
  CPython3.9 macOS `time.monotonic()` origins were process-relative. R4 uses shared
  POSIX CLOCK_MONOTONIC, with a retained parent/child proof and unchanged limits.
- R4 attempt `python-2` matched Jinja2 bytes but rejected raw METADATA's extra ASCII
  space before the optional Babel marker semicolon. No candidate code ran.
  R5 reviewed only that exact literal for the same hash. The original advertised
  manifest and strict wheel inspector are unchanged; no general normalization or
  additional dependency is allowed. The original failed attempt stays failed.

All execution attempts retained cleanup evidence. Independent implementation/freeze
reviews passed before each admitted launch. The successful result is submitted for
independent results review; no qualification rerun is implied.

## Unclosed gates

The exact root-selected Abseil source is not present in the checked retained
locations. Its acquisition is outside the two-wheel scope. Compiler/SDK/Abseil
preparation is **BLOCKED**: six positive compiler commands, six runs and twelve
negative compiler commands are **NOT_RUN**. The future callback harness is an
unexecuted fixture, with no semantic credit. See the separate static assessment.
Generated text agreement does not prove callback behavior, strict C++17 support
(including unchanged `explicit(false)`), Android interfaces or FP16 numerics.

**Bazel selection of this candidate Python is unproved.** No root dependency
amendment is adopted and no configured ABI graph is produced by this slice. The
separate18GiB analysis floor is unchanged. Emulator testing remains downstream;
S24 testing remains deferred.

Host verification: **257 tests passed**, including60 focused new tests, with no
failures/skips. Kotlin/Android/Rust builds and device tests were not rerun because
this slice changes host Python tooling, synthetic fixtures and documentation only.
