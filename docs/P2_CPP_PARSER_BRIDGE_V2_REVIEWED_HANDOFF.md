# V2 diagnostic bridge: reviewed R2 checkpoint

2026-11-10. **PASS for bridge implementation, host tests and read-only admission.**
Native collection remains unauthorized and untested.

## Completed implementation

The separate V2 worker validates captures with the reviewed parser using context
derived from the exact execution freeze. After confirmed owner cleanup and
readmission, the parent independently replays raw traces and checks context,
argv/profile, order, raw digest, exact typed summaries, exit/terminal statuses,
timing, comparison and not-run cases. Expected negatives remain FAILED. Nothing
qualifies the real linker or publishes acceptance.

The recipe preserves flags, warning policy, marker, profiles, ownership, deadlines,
cleanup, quarantine and historical executors. Only owned paths are rebased to
`access-v2-1`. No original attempt or consumed grant is resumed.

## Verification and review

- **33 focused bridge methods passed** under pinned Python 3.9: 9 freeze/admission
  methods and 24 worker/parent/fake-owner methods.
- **574 canonical host methods passed.** Independent QA inspected the retained
  canonical result rather than repeating that entire suite.
- Independent QA freshly reran the 33 methods and four value-equivalent exit-type
  probes; verified all **246 inputs**, **six inventories**, **eight static native
  payloads/load graphs** and the live marker identity.
- Genuine read-only admission passed against the unchanged independent review.
  The guard recorded **zero process/native/access dispatches**, no permission
  grant and no proposed attempt before or afterward. The launch request was
  constructed and retained, never executed.

Freeze: `planning/fixtures/linker-parser-bridge-v2/execution-freeze-r2.json`,
SHA-256 `e5415753fb22f69537e7628aa33c95cb485a109261bdb24384dc6f6005c303f4`.
Genuine review: `implementation-review-r2.json` beside it, copied byte-for-byte
from independent QA, SHA-256
`7529245f410e9d5ec258d968c2741ffed4ff1a180076f2468a51c59926e12939`.
Reviewer: Archimedes (`01a1b63a-5ec5-73c1-af98-e2d13cf68664`). Review, snapshot
and verification artifacts remain in `build/linker-parser-bridge-v2/qa-implementation-r2/`.

The subsequent producer proof is `build/linker-parser-bridge-v2/read-only-admission-r2.json`;
it is distinct from the preceding independent implementation verdict. The
checkpoint beside the freeze binds both without rewriting either. Producer
details are in `docs/P2_CPP_PARSER_BRIDGE_V2_R2_RESULTS.md`.

## Preserved failures and limits

R1 failed independent review because dictionary equality admitted boolean/float
summary exit codes as integers. R2 explicitly checks exact integer type. Positive
`0 -> False/0.0` and negative `1 -> True/1.0` are now rejected, while correct integer
negative reports remain FAILED. R1 freeze, source, logs, failed verdict and raw
reproduction remain intact. R1 cannot admit current source.

The current marker receipt explicitly records the consistent device-number-only
difference from the old receipt (16777231 to 16777234), cause unproved. Every other
recorded attribute agrees; marker bytes and permissions were untouched. QA verified
the new pin. Exact pre/post identity checks remain; no OS immutability or exclusion
of transient concurrent replacement is claimed.

Preservation evidence retains the 245 historical Access-1 inputs, 52 protected
Python bindings, 132 historical entries, 30/11 proposal bindings and 25/11 parser
R2 implementation/QA bindings. Superseded parser R1 is explicitly checked via its
archive. The earlier audit-helper failure and its streaming correction remain
recorded. Fake native dispatch/owners and cached Rust host fixtures establish no
new actual compiler/linker or runtime lifecycle behavior.

## Next authorization boundary

The retained `launch-request-r2.json` proposes the reviewed two-case comparison
once through the command-scoped outer profile. **It is not permission.** No native
dispatch, resumed case, permission change, compilation, Bazel/build, device workload
or Git mutation occurred. No acceptance artifact exists.

Later collection needs explicit authorization for that exact request, fresh
admission, unchanged inner sandbox and first-failure stopping. Access-1 remains
**FAILED / INCONCLUSIVE**, its second case **NOT_RUN**, grant consumed. Actual
linker selection and compiled effects remain unverified. **P2 is incomplete;
semantic search stays disabled.**
