# Proposed offline Dawn inspection v2 — review before implementation/execution

2026-11-02. **Not authorized for execution by this proposal.** The v1 acquisition
ended FAILED at its 60,000-member ceiling, with confirmed cleanup after 67.871 s.
No v1 retry, wheel metadata continuation or automatic member-limit increase ran.
Preserve that attempt, its exact code/freeze, partial selected sources and outcome.

## Available candidate and unresolved gate

GitHub's retained ref response resolves `v20260720.160313` to commit
`466163dc7bf94146ac9a921b86cc3ed08184ebb8`. The retained immutable-commit
archive is 28,737,866 bytes, observed SHA-256
`4ee2687c6f4b2b1f30e70028763201c898a763989f7aafcb284e197ab0a4735b`.
Those are candidate identities, **not a passed full-inventory receipt**. The v1
walk stopped before complete archive/EOF validation. Neither package metadata
request was reached. No wheel hashes may be invented or copied from another ABI.

## Proposed algorithm and explicit resource amendment

Avoid retaining a dictionary containing every Dawn member/path. Scan the **same
retained bytes**, with no second Dawn download, through bounded gzip/tar readers.
Validate every member, hash every regular file and consume through gzip EOF/CRC.
Do not omit test/shader subtrees merely to obtain a pass.

Record a deterministic streamed inventory digest with canonical length-framed
UTF-8 records in archive order, plus full records for selected recipe inputs.
Detect duplicate names through a bounded set of SHA-256 name keys: duplicates
always reject; a hypothetical distinct-name collision rejects conservatively.
The full archive hash remains the primary byte identity; the streamed digest is
reproducibility evidence, not a replacement for it.

The proposed, **review-required** member cap is 300,000 instead of v1's 60,000.
Keep 1 KiB per name/link, 2 MiB maximum metadata read, 2 GiB decoded stream,
32 MiB selected sources, 32 MiB receipt, 512 MiB aggregate output bytes, one owned
300-second decision, 15-second cleanup and 2 MiB logs. Explicitly bound retained
name-key memory and total bytes fed into metadata hashing. Reaching any cap
fails; no automatic subsequent expansion or reduced content coverage.

After full offline source validation, the new protocol may separately request
only the two metadata JSON records already proposed for Jinja2 3.1.6 / MarkupSafe
3.0.3. No wheel installation/download, generator execution, SDK patches or root
registration. These requests belong to v2's reviewed scope, not a resumed v1 run.

## Acceptance and authorization

Before implementation: independently review this algorithm, resource amendment,
archive identity, exact manifest encoding and safety/failure cases. Then obtain
implementation/execution authorization and bind its code/tools/inputs in a new
freeze. Focused tests must cover duplicate/unsafe/long names, truncated gzip,
oversized metadata, bounded name-key memory, replay digest, aggregate output,
interruption outcomes and no success promotion after a cap or cleanup failure.

Independent results review must establish a complete source receipt and pin the
two expected wheel identities before the Dawn compatibility recipe is considered
complete. The four-site upstream replacement also needs explicit semantic review;
do not silently reinterpret it as a single-site patch.

No new source version, native adoption, Bazel, build artifact, model/device work,
commit or data deletion. The separate 18 GiB analysis capacity requirement and
300/15/16 MiB analysis limits remain unchanged. P2 and semantic enablement blocked.
