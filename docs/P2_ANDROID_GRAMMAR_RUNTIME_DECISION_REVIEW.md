# ADR 0008 — independent architecture/decision review

2026-10-28. Retained primary QA agent
`01a16d6c-1a21-7923-af3d-18ec99667728`. This records its read-only review.

## Initial review — FAIL, documentation revisions required

Decision-lock SHA-256:
`857a7cd2cf073f96ed1368eb7107490823d4890d0205dd883aa742bc2f280cca`.

- P2: The QA-summary hash lacked its source path, leading to comparison with a
  different retained summary. The 44 additional-boundary claim had no bound
  retained artifact supporting its inclusion in this decision.
- P3: Packaging wording incorrectly required a native runtime copy in each APK.

The original ADR, audit, lock and finding summary are preserved under
`build/unicode-android-decision-v1/review-failed-1/`. No prior source/evidence was
rewritten. The initial architecture review otherwise found the ownership,
compatibility, build-closure and rollback boundaries appropriate.

## Follow-up — PASS, both findings resolved

Revised decision-lock SHA-256:
`c9185f2fa377a5813c935df49a557554f29e86336d8c0c8d05df63c1c80014c7`.

Verified both explicitly named retained QA paths and their 910/41 scopes:

- `build/unicode-correction-v1/qa-independent-1/qa-summary.json`:
  `8d34530ec862624085ea0a6e9ed456ef18428455b8a9fae07b7a87d815155aa1`.
- `build/unicode-correction-v1/qa-current-review-d4_h787v/qa-summary.json`:
  `c8a6b8e3690e0fcb27e952427f03fac2fd7f2e34fe5df8724be35d3a80804dd6`.

The latter hash was valid for the now-identified latest QA path. The unbound
44-check claim is excluded from this decision's evidence binding; historical
user-provided QA reports are not altered.

Confirmed one resolved SDK per eligible consumer graph and one runtime copy per
ABI across the combined app/test packaging closure. The application owns the
SDK/JNI runtime; instrumentation must not duplicate it. Retained APK hashes and
inventories verify that the instrumentation APK contains no native libraries.

The reviewer verified 15 cached pinned source blobs, published AAR/member hashes,
prior bindings and preservation of the original FAIL. Build closure, SDK
compatibility and tokenizer parity remain unproved prerequisites. No builds,
tests, source edits, Git mutations or device work were performed by the reviewer.

**This is a decision/proposal PASS, not user approval of the decision,
implementation, Android integration, runtime builds or S24 execution.** P2
remains incomplete; semantic search is disabled. The historical 39/41 failure
and separately passing host correction retain their original scopes.
