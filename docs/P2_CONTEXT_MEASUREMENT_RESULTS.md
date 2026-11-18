# P2 S2b — S24 context measurement results (first pass)

2026-11-18. Samsung SM-S928B (S24 Ultra, serial ending 8D6B), Android SDK 37,
`secureQa` build from commit `b489d27`, LiteRT-LM 0.17.1, Gemma 4 E2B
(`181938…a63c`), GPU backend, no automatic fallback, no retries. Runs used
`scripts/verify-chat-context.sh` under thermal supervision, one run per profile,
run back to back (OS file cache warm; "cold" means engine-unloaded only).

Raw results (git-ignored):
- `benchmarks/results/passage-20261118T195657Z-21910-sTN6Zv/` — SQLCipher gate
- `benchmarks/results/context-4096-20261118T195934Z-22148-qwa1zL/`
- `benchmarks/results/context-8192-20261118T200224Z-22425-sGRXh7/`

## SQLCipher passage gate

PASS, 3 of 3 instrumented tests (`PassageSqlCipherTest`) on the encrypted
Android database: fresh import with FTS4 `matchinfo` ranking, migration of a
pre-passage schema-5 database with backfill matching a fresh import, and the
third case in that class. This closes the storage gate before S4 relies on
retrieval on Android.

## Context measurement

| Profile | Case | Prompt bytes | Input tokens | Load ms | First text ms | Cold to first text ms | Peak PSS | Thermal |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 4096 | near bound | 3,360 | 705 | 9,892 | 1,161 | 14,512 | 2.35 GB | 0 → 0 |
| 4096 | short control | 39 | 17 | 9,839 | 556 | 14,306 | 2.26 GB | 0 → 0 |
| 8192 | near bound | 7,456 | 1,554 | 9,649 | 1,780 | 14,169 | 2.81 GB | 0 → 0 |
| 8192 | short control | 39 | 17 | 10,193 | 595 | 14,926 | 2.70 GB | 0 → 0 |

Every case completed, the loaded size in runtime state matched the profile,
and cleanup was confirmed (`UNLOADED`, loaded sizes `null`). Skin temperature
stayed between 35 and 38 °C.

## What this shows

- Load time does not grow with context (about 9.6–10.2 s at both sizes).
- 8192 costs about 0.45 GB more peak PSS than 4096 on this device.
- Time to first text rises with real input: 0.56 s at 17 tokens, 1.16 s at
  705, 1.78 s at 1,554.
- The conservative byte bound, not the context, limits ordinary input: a
  prompt at the byte limit is only about 705 real tokens at 4096 and 1,554 at
  8192. Neither run came close to filling its token context.

## Limits of this evidence

- One run per profile; no variance.
- No 2048 baseline in this harness, so the cost over today's default is not
  measured.
- Outputs were 6 tokens, so the decode rates (9.6–11.6 tokens/s) are not
  meaningful.
- Memory admission floors remain uncalibrated for 4096/8192.
- Not file-cache-cold; no sustained-use or thermal-soak run.
- The script writes a `cat` error into `supervision-stop.json` when no loss
  marker exists (harmless, but the file is not JSON).

## Planner recommendation

**4096 as the provisional ordinary default**, pending a short follow-up
(2048 baseline, a longer-answer case for decode rate, three repetitions per
profile, and the `supervision-stop.json` fix). Reasons: 4096 admits about
2.5× today's input (3,360 vs 1,312 bytes), at about 0.45 GB less memory than
8192 and with the same load time. That byte figure is an upper bound on raw
input: how many passages actually fit after the instruction, delimiters,
passage labels and history is for S4 to measure, not promised here. 8192
stays QA-only until S4 shows that more passages improve answers (S6).

**User decision (2026-11-18):** 4096 accepted as the provisional ordinary-chat
default; 512 output reservation kept; 8192 experimental; QA protocol requests
stay 2048/512. A repeated 2048/4096 comparison with longer responses comes
before broader qualification.
The user also asked (2026-11-18) for 8192 to be included in that repeated run
set, alongside 2048 and 4096.

## S2b follow-up implementation (2026-11-18, host only)

No new device data. This section records what changed for the next run set;
see the procedure update in `docs/P2_DEVICE_PREP_RESULTS.md`.

- **Default.** Ordinary chat is 4096 in debug, release and the secureQa
  default (generated BuildConfig checked: debug and release 4096, also with
  `-PchatContextTokens=8192`). 8192 is reachable only in secureQa. QA protocol
  requests, V3/V4, constrained decoding and the Models panel stay 2048/512, so
  a panel load followed by ordinary chat still performs the S2a reload. Memory
  admission floors are **not calibrated for 4096**.
- **Review findings.** APKs are staged into the run directory and only the
  staged, hashed copies are installed (P2). Validation now rejects a report
  whose build type, profile, resident sizes, reserved output, backend/fallback
  or model SHA differ from the request (P2). An absent supervision-loss marker
  is recorded as `absent` instead of being saved as a `cat` error; non-JSON
  evidence fails the run (P3).
- **Fuller measurement.** `measure 2048` adds the baseline; a `long_answer`
  case is the only source of decode rate, counting only iterations with
  ≥ 128 output tokens (shorter ones pass but are excluded; "unavailable" if
  none qualifies); `--repeat N`
  (default 3) gives N engine-cold iterations per case with median and range in
  `summary.json`. Deadline `270 + 630 × N` s (2160 s at N=3).
- **Still open.** No variance, decode-rate or 2048 data until the run set is
  authorized and executed; not file-cache-cold; no thermal soak.
