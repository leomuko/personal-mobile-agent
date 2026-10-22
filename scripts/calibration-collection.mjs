import { existsSync, mkdirSync, readFileSync, rmdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { check, jsonHash, readJson } from './calibration-data.mjs';
import { digest } from './semantic-quality.mjs';

const name = plan => {
  check(['calibration', 'development', 'heldout'].includes(plan.split) && Number.isSafeInteger(plan.index) && plan.index >= 0,
    'Invalid collection lease');
  return `${plan.split}-${plan.index}`;
};
const receiptPath = (root, plan) => join(root, '.collection', `${name(plan)}-accepted.json`);

export function reserveBatch(root, plan, runId, folder) {
  const base = join(root, '.collection'); mkdirSync(base, { recursive: true });
  check(!existsSync(receiptPath(root, plan)), 'Successful batch is already sealed; only infrastructure failures may retry');
  const active = join(base, `${name(plan)}-active`);
  mkdirSync(active);
  // A crashed collector leaves this directory in place: recovery is explicit, never a stale-lock timeout.
  return { active, root, plan, runId, folder: resolve(folder) };
}

export function acceptBatch(lease, attemptPath) {
  const file = readJson(attemptPath);
  check(file.value.runId === lease.runId && file.value.batchSha256 === jsonHash(lease.plan) &&
    file.value.status === 'collected', 'Cannot seal foreign/failed attempt');
  writeFileSync(receiptPath(lease.root, lease.plan), JSON.stringify({ schema: 2,
    runId: lease.runId, batchSha256: jsonHash(lease.plan), attemptSha256: file.sha256,
    attemptPath: resolve(attemptPath), folder: lease.folder }, null, 2) + '\n', { flag: 'wx' });
}

export function releaseBatch(lease) { rmdirSync(lease.active); }

export function requireAccepted(root, attempt) {
  const receipt = readJson(receiptPath(root, { split: attempt.split, index: attempt.index })).value;
  check(receipt.schema === 2 && receipt.runId === attempt.runId && receipt.batchSha256 === attempt.batchSha256 &&
    digest(readFileSync(receipt.attemptPath)) === receipt.attemptSha256 &&
    jsonHash(JSON.parse(readFileSync(receipt.attemptPath))) === jsonHash(attempt), 'Attempt differs from sealed successful batch');
}
