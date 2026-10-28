import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { sha256, loadFeasibilityPlan } from './v3-feasibility.mjs';

export const CD_ID = 'evidence-constrained-decoding-v2-draft-1';
export const CD_ROOT = 'planning/fixtures/constrained-decoding-v2';
export const CD_LOCK = '5e8edd23535d26c9985a28fd59e51c29b60974561580c0f807fec0f5b297e1d9';
export const CD_POLICY = 'memory-android-pressure-test-v1';
export const CD_PROOF = 'build/constrained-decoding/prerequisites.json';
export const cdCheck = (value, message) => { if (!value) throw new Error(message); };
const EXPECTED_VERSIONS = { jsonschema: '4.25.1', attrs: '26.1.0', 'jsonschema-specifications': '2025.9.1',
  referencing: '0.36.2', 'rpds-py': '0.27.1', 'typing-extensions': '4.16.0', sentencepiece: '0.2.0', protobuf: '5.28.3' };

/** Pure admission: no ADB, native execution, automatic proof generation or permission creation. */
export function loadConstrainedPlan(base = '.') {
  const read = path => readFileSync(join(base, path));
  const json = path => JSON.parse(read(path));
  const lockBytes = read(`${CD_ROOT}/preparation-lock.json`), lock = JSON.parse(lockBytes);
  cdCheck(sha256(lockBytes) === CD_LOCK, 'Unreviewed constrained preparation');
  for (const [path, hash] of Object.entries(lock.files)) cdCheck(sha256(read(path)) === hash, `Changed frozen input: ${path}`);
  cdCheck(sha256(read('docs/P2_CONSTRAINED_DECODING_PROTOCOL_V2_REVIEW.md')) ===
    '33450d928d24b0d34f6b5b64655fd590688cb131ae955fabd0a9564c8a6dbe61', 'Missing/stale independent proposal review');
  const approval = json(`${CD_ROOT}/integration-authorization.json`);
  cdCheck(approval.schema === 1 && approval.protocolId === CD_ID && approval.approvedBy === 'user' &&
    approval.preparationLockSha256 === CD_LOCK && approval.requests === 9 && approval.deadlineMs === 30000 &&
    approval.strictlyBelowDeadline === true && approval.cleanupWithinDeadline === true && approval.profileId === CD_POLICY &&
    approval.negativeCase === 'cd07' && approval.negativeDecodeTokenCap === 1 && approval.reservedOutputTokens === 512 &&
    approval.integrationApproved === true && approval.prerequisitesRequired === true && approval.implementationReviewRequired === true &&
    approval.executionFreezeRequired === true && approval.separateDeviceAuthorizationRequired === true &&
    approval.deviceExecutionAuthorized === false && approval.semanticEnablement === false && approval.productionLatencyTarget === false,
  'Missing/mismatched constrained integration scope');
  const proof = json(CD_PROOF);
  const proofFiles = [...Object.keys(lock.files), 'scripts/verify-constrained-decoding.py',
    'scripts/verify-constrained-decoding-prerequisites.sh', 'scripts/requirements/constrained-decoding.txt',
    'scripts/verify-chat-budget.py', 'scripts/tests/test_constrained_decoding.py',
    'build/output-contract-audit/sentencepiece_tokenizer.cc', 'build/output-contract-audit/buffered_streaming_detokenizer.cc'];
  cdCheck(proof.schema === 1 && proof.status === 'passed' && proof.protocolId === CD_ID && proof.preparationLockSha256 === CD_LOCK &&
    proof.modelSha256 === '181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c' &&
    proof.tokenizerSha256 === 'e594c8a90eb08d8bda498ff4747977dc827ae0c3c56b5c0d41a605a22d02ef03' &&
    proof.runtimeDetokenizerCommit === '5e58e9a0aef7abf7091207a8b1d1063a1c800f08' &&
    isDeepStrictEqual(proof.dependencies, EXPECTED_VERSIONS) && proof.oneTokenProof?.vocabularyEntries === 262144 &&
    proof.oneTokenProof.variantsPerEntry === 2 && proof.oneTokenProof.schemaValidVariants === 0 &&
    proof.oneTokenProof.braceBoundedSubstrings === 260 &&
    proof.oneTokenProof.decodedVocabularySha256 === 'aba49cf84613bd47aec32c6cf851dfe85b472fdeade1254d775e7f38237aeca6' &&
    proof.reference?.positiveMetaSchemas === 2 && proof.reference.negativeMetaSchemas === 1 && proof.reference.baseInstances === 2 &&
    proof.reference.mutationInstancesPerSchema === 34 && proof.reference.cases.length === 34 &&
    proof.nativeEnforcementVerified === false && proof.deviceExecutionAuthorized === false &&
    isDeepStrictEqual(proof.files, Object.fromEntries(proofFiles.map(path => [path, sha256(read(path))]))),
  'Missing/stale constrained prerequisites');
  loadFeasibilityPlan(base); // Retains the current encoder counting proof guard.
  const cases = json(`${CD_ROOT}/cases.json`), prepared = json('planning/fixtures/output-adherence-v1/prepared.json');
  const oracles = { schema: 2, protocolId: CD_ID, controls: [] };
  const entries = cases.cases.map((c, index) => {
    cdCheck(c.id === `cd0${index + 1}`, 'Constrained case order changed');
    const source = c.input.sourceControl ? prepared.controls.find(row => row.id === c.input.sourceControl) : null;
    const input = source?.input || c.input;
    const positive = ['cd03', 'cd08', 'cd09'].includes(c.id);
    oracles.controls.push({ id: c.id, ...(positive ? source.review.expected : {}) });
    const schema = read(`${CD_ROOT}/${c.constraint}`).toString('utf8');
    return { id: c.id, payload: input.payload, question: input.question, detailIds: input.detailIds,
      passages: input.passages.map(p => ({ p: p.p, r: p.r, original: p.original, b: p.b ?? p.start, e: p.e ?? p.end })),
      constraintSchema: schema, schemaSha256: sha256(schema), decodeTokenCap: c.decodeTokenCap,
      packSha256: sha256(input.payload), encoderInputSha256: sha256(input.question), inputBound: 224 + Buffer.byteLength(input.payload) };
  });
  cdCheck(entries.length === 9 && entries.every(e => e.inputBound + 512 <= 2048), 'Invalid constrained plan size/budget');
  const plan = { schema: 2, protocolId: CD_ID, preparationLockSha256: CD_LOCK, requests: 9,
    deadlineMs: 30000, cleanupWithinDeadline: true, profileId: CD_POLICY, contextTokens: 2048,
    reservedOutputTokens: 512, encoderTokenLimit: 192, semanticEnablement: false,
    strictValidatorSha256: lock.files['shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/verification/EvidenceOutputValidator.kt'],
    oraclesSha256: sha256(JSON.stringify(oracles)), entries };
  return { plan, oracles, sentinel: json(`${CD_ROOT}/host-keyword-checks.json`).baseOutput, prerequisiteSha256: sha256(read(CD_PROOF)),
    authorizationSha256: sha256(read(`${CD_ROOT}/integration-authorization.json`)), planSha256: sha256(JSON.stringify(plan)) };
}
