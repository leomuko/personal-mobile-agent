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

## 2026-11-19: repeated comparison (2048, 4096, 8192; three engine-cold iterations each)

Commit `0557e8c`, `secureQa`, GPU, S24 Ultra (serial ending 8D6B), runs back to
back, no retries. Median with range. The long-answer case produced 291 output
tokens in every iteration, so its decode rate is meaningful; the 6-token cases'
decode rates are not reported.

| | 2048 | 4096 | 8192 |
| --- | --- | --- | --- |
| Cold load (near bound) | 9.24 s (9.05–9.38) | 9.58 s (9.08–9.78) | 12.33 s (9.87–13.16) |
| First text, input at the byte limit | 0.77 s, 280 tokens | 1.01 s, 705 tokens | 1.82 s, 1,554 tokens |
| First text, short prompt | 0.62 s (0.56–0.64) | 0.56 s (0.54–0.60) | 0.72 s (0.65–0.75) |
| Cold to first text, short prompt | 12.4 s (12.1–13.9) | 13.2 s (12.1–13.9) | 16.5 s (14.0–17.5) |
| Decode, 291-token answer | 33.5 tok/s (32.0–33.7) | 32.8 tok/s (32.7–35.1) | 30.1 tok/s (28.4–30.8) |
| Peak PSS (near bound) | 2.03 GB (2.01–2.05) | 2.37 GB (2.35–2.38) | 2.78 GB (2.75–2.79) |
| Thermal status / max skin | 0 / 35.4 °C | 0 / 38.7 °C | 0 / 39.0 °C |

Raw results (git-ignored): `benchmarks/results/context-2048-20261118T205011Z-54948-ccDTwI/`,
`context-4096-20261118T205303Z-55462-2KHa0Z/`, `context-8192-20261118T205907Z-56276-1kHP27/`.
An earlier 8192 attempt (`context-8192-20261118T205557Z-55988-mlvT4q/`) stopped
before any model load because the phone was locked; it was rerun once on the
user's instruction after unlocking.

Observations:
- 4096 costs about 0.3 GB more peak PSS than 2048 with no measurable change in
  load time or decode rate.
- 8192 costs about 0.75 GB more than 2048; two of three iterations per case
  loaded in about 12.5 s instead of about 9.5 s, and decode was about 10%
  slower. The cause (memory, thermal or allocation) is not established; the
  8192 run came last and the skin temperature was highest.
- Thermal status stayed 0 throughout. Runs were sequential, not a soak test.

Planner conclusion: the repeated data supports keeping 4096 as the provisional
ordinary default and 8192 as experimental. Limits unchanged: memory floors are
uncalibrated for 4096, OS file cache was warm, one device.
