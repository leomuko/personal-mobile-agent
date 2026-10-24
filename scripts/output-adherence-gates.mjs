import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { OA_ID, OA_ROOT, OA_PROTOCOL, OA_VALIDATOR_SHA, buildPreparation, verifyPreparation } from './output-adherence-preparation.mjs';
import { sha256 } from './v3-feasibility.mjs';

export const OA_PREPARATION_SHA = '88a985300cd4cc0c100f6541214328e68759558393fa25b86f8fea8a13b888a2';
export const OA_POLICY = 'memory-android-pressure-test-v1';
export const requireAdherence = (value, message) => { if (!value) throw new Error(message); };

/** Both approvals are checked without touching ADB, model assets or the attempt ledger. */
export function loadApprovedAdherence(base = '.') {
  verifyPreparation(base);
  const read = path => readFileSync(join(base, path));
  const lockBytes = read(`${OA_ROOT}/preparation-lock.json`), lock = JSON.parse(lockBytes);
  requireAdherence(sha256(lockBytes) === OA_PREPARATION_SHA, 'Unreviewed output-adherence preparation');
  const authorizationBytes = read(`${OA_ROOT}/integration-authorization.json`), authorization = JSON.parse(authorizationBytes);
  requireAdherence(authorization.schema === 1 && authorization.protocolId === OA_ID && authorization.approvedBy === 'user' &&
    authorization.preparationLockSha256 === OA_PREPARATION_SHA && authorization.protocolSha256 === sha256(read(OA_PROTOCOL)) &&
    authorization.requests === 6 && authorization.deadlineMs === 30000 && authorization.cleanupWithinDeadline === true &&
    authorization.profileId === OA_POLICY && authorization.integrationApproved === true && authorization.hostQaApproved === true &&
    authorization.corpusAttestationRequired === true && authorization.implementationReviewRequired === true &&
    authorization.executionFreezeRequired === true && authorization.separateDeviceAuthorizationRequired === true &&
    authorization.deviceExecutionAuthorized === false && authorization.productionLatencyTarget === false && authorization.semanticEnablement === false,
  'Missing/mismatched output-adherence integration approval');
  const reviewBytes = read(`${OA_ROOT}/independent-review.json`), review = JSON.parse(reviewBytes);
  requireAdherence(review.schema === 1 && review.protocolId === OA_ID && review.verdict === 'PASS' &&
    review.scope === 'output-adherence-corpus-and-exact-packs' && review.preparationLockSha256 === OA_PREPARATION_SHA &&
    review.preparedSha256 === sha256(read(`${OA_ROOT}/prepared.json`)) && isDeepStrictEqual(review.files, lock.files) &&
    isDeepStrictEqual(review.controls, ['oa01', 'oa02', 'oa03', 'oa04', 'oa05', 'oa06']) &&
    review.nativeIntegrationAuthorized === false && review.deviceExecutionAuthorized === false && review.semanticEnablement === false,
  'Missing/stale independent output-adherence corpus attestation');
  const { prepared } = buildPreparation(base);
  const instructions = read(`${OA_ROOT}/verifier-instructions.txt`).toString('utf8').replace(/\n$/, '');
  // The QA oracle is a separate artifact. No states, witnesses or expected citations enter model input.
  const oracles = { schema: 1, protocolId: OA_ID, preparationLockSha256: OA_PREPARATION_SHA,
    controls: prepared.controls.map(c => ({ id: c.id, state: c.review.expected.state,
      details: c.review.expected.details.map(d => ({ ...d, passageIds: [...d.passageIds] })) })) };
  const plan = { schema: 1, protocolId: OA_ID, scope: 'output-adherence-six-request-feasibility',
    preparationLockSha256: OA_PREPARATION_SHA, policyId: OA_POLICY, androidPressure: true,
    deadlineMs: 30000, cleanupWithinDeadline: true, contextTokens: 2048, outputTokens: 512,
    encoderTokenLimit: 192, strictValidatorSha256: OA_VALIDATOR_SHA, semanticEnablement: false,
    oraclesSha256: sha256(JSON.stringify(oracles)), instructions,
    entries: prepared.controls.map(c => ({ id: c.id, state: 'native-cold', question: c.input.question,
      dataJson: c.input.payload.slice(instructions.length + 6, -9), packSha256: c.input.payloadSha256,
      encoderInputSha256: c.input.encoderInputSha256, inputBound: c.input.inputBound, detailIds: [...c.input.detailIds],
      passages: c.input.passages.map(p => ({ p: p.p, r: p.r, b: p.start, e: p.end, original: p.original })) })) };
  return { plan, oracles, bindings: { preparationLockSha256: OA_PREPARATION_SHA,
    corpusReviewSha256: sha256(reviewBytes), integrationApprovalSha256: sha256(authorizationBytes),
    strictValidatorSha256: OA_VALIDATOR_SHA, planSha256: sha256(JSON.stringify(plan)), oraclesSha256: plan.oraclesSha256 } };
}
