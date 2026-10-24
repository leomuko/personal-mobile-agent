import { isDeepStrictEqual } from 'node:util';
import { OA_ID, OA_VALIDATOR_SHA } from './output-adherence-preparation.mjs';
import { OA_PREPARATION_SHA, OA_POLICY, requireAdherence as check } from './output-adherence-gates.mjs';
import { classifySerialVerification } from './v4-feasibility.mjs';

/** Control-shape check follows native strict validation; it does not replace it or judge semantic support. */
export function matchesAdherenceShape(output, oracle) {
  return output?.o === oracle.state && Array.isArray(output.d) && output.d.length === oracle.details.length &&
    output.d.every((d, i) => d.i === oracle.details[i].i && d.s === oracle.details[i].state && Array.isArray(d.e) &&
      isDeepStrictEqual([...new Set(d.e.map(s => s.p))].sort(), [...oracle.details[i].passageIds].sort()));
}

export function classifyAdherence(evidence, expected) {
  check(expected.plan.protocolId === OA_ID && expected.plan.entries.length === 6 && expected.policyId === OA_POLICY &&
    expected.plan.preparationLockSha256 === OA_PREPARATION_SHA && expected.plan.strictValidatorSha256 === OA_VALIDATOR_SHA &&
    evidence?.strictValidatorSha256 === OA_VALIDATOR_SHA && evidence.oraclesSha256 === expected.plan.oraclesSha256,
  'Wrong output-adherence evidence identity');
  const result = classifySerialVerification(evidence, expected, { schema: 1, protocolId: OA_ID, requests: 6,
    sourceFreeze: OA_PREPARATION_SHA, requestPrefix: 'oa', coldAdmissionCount: 1 });
  for (const [index, c] of evidence.cases.entries()) {
    check(Number.isSafeInteger(c.runtimeDecisionMs) && c.runtimeDecisionMs >= 0 && c.runtimeDecisionMs <= c.decisionMs &&
      ['PENDING', 'ELIGIBLE', 'REVOKED'].includes(c.runtimeDelivery) &&
      c.inputBound === expected.plan.entries[index].inputBound, 'Uncorrelated runtime/control decision or prompt budget');
    check((c.delivery !== 'ELIGIBLE' && c.caseGateAccepted !== true) || c.runtimeDelivery === 'ELIGIBLE',
      'Final delivery cannot promote an ineligible runtime result');
    check(['MATCHED', 'MISMATCH', 'NOT_REACHED'].includes(c.controlShape), 'Missing control-shape classification');
    if (c.outcome === 'STRUCTURALLY_VALID' && c.validation === 'STRUCTURALLY_VALID_NOT_SEMANTICALLY_JUDGED') {
      check(typeof c.diagnosticOutput === 'string' && c.validation === 'STRUCTURALLY_VALID_NOT_SEMANTICALLY_JUDGED',
        'Missing native strict output validation');
      const matched = matchesAdherenceShape(JSON.parse(c.diagnosticOutput), expected.oracles.controls[index]);
      check(c.controlShape === (matched ? 'MATCHED' : 'MISMATCH') &&
        (!c.caseGateAccepted || matched) && (!matched ? c.delivery === 'REVOKED' : true), 'Control-shape claim mismatch');
    } else check(c.controlShape === 'NOT_REACHED' && c.delivery === 'REVOKED' && !c.caseGateAccepted,
      'Failed or cleanup-revoked decision masquerades as a valid control');
  }
  return { ...result, strictValidRequests: evidence.cases.filter(c => c.validation === 'STRUCTURALLY_VALID_NOT_SEMANTICALLY_JUDGED').length,
    shapeCorrectRequests: evidence.cases.filter(c => c.controlShape === 'MATCHED').length,
    semanticQualityQualified: false };
}
