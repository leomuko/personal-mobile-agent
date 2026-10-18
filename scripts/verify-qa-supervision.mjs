import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export function validSupervision(record, runId, afterMs = 0) {
  return /^[A-Za-z0-9_-]{1,80}$/.test(runId || '') && record?.schema === 2 &&
    record.runId === runId && record.status === 'closed' && record.reason === null &&
    record.leaseMs === 20000 && record.pollMs === 500 && record.cleanupMs === 5000 &&
    Number.isSafeInteger(record.elapsedMs) && record.elapsedMs >= 0 &&
    Number.isSafeInteger(record.capturedAtMs) && record.capturedAtMs > 0 && record.capturedAtMs >= afterMs;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    if (!validSupervision(JSON.parse(readFileSync(process.argv[2], 'utf8')), process.argv[3]))
      throw new Error('No fresh, closed supervision lease for this run');
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
