# V4 Explicit Baseline Retry: Blocked

Date: 2026-10-22. The user explicitly requested another S24 attempt after the
initial preflight rejection. All code, APK, pack, resource and acceptance identities
were unchanged. The new freeze differs only in creation time, carries separate
retry authorization, and links to the first attempt; no old ledger was reset.

Freeze: `43fd1f37090c4760092c57ac21a4e019981768b7cea27704f82688aab89d0030`.
Folder: `benchmarks/results/p2-v4-feasibility-gate-rTvTRb/`.
Run: `35c4cd94-2ad3-4e41-97fd-3c492e371b51`.

The S24 was unlocked/awake. QA APK replacement and both policy/metadata probes
passed. Fifteen preflight samples reported 4.3897-4.4282 GiB available, below the
unchanged 5.5 GiB baseline preload requirement. Android lowMemory remained false,
thermal status 0, skin samples 33.3-33.8 C. The wrapper exited 2, blocked with
`LOAD_HEADROOM`. Its 23531 ms elapsed time covers setup/probes/preflight, not an
inference request. All eight requests remain not run; no encoder/Gemma work began.

Both attempts, authorizations and freeze-keyed ledgers remain retained. No further
baseline retry, automatic profile switch, threshold relaxation or ledger deletion
occurred. This does not demonstrate an OOM, model accuracy failure or a result
against the 30-second deadline.

After this retry, the user explicitly requested OS lowMemory-based preload
admission. That is a separately documented QA-profile change, not a retroactive
pass for either baseline attempt. Ordinary app behavior remains separately scoped.
