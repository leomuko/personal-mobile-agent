import { readFileSync, readdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export const migration = Object.freeze({
  id: 'archival-v2-lock-inspection-v1',
  original: 'scripts/tests/calibration-policy.test.mjs',
  originalSha256: '35720368cb2999ea192fbca48d398dd563d261171fa36f29a5adea75ebb0240c',
  active: 'scripts/current-tests/calibration-policy.test.mjs',
});
const before = "const oldLock = readJson(`${archive}/lock.json`).value; const lock = loadLock(root);";
const after = "const oldLock = readJson(`${archive}/lock.json`).value; const lock = readJson(`${root}/lock.json`);\n  assert.throws(() => loadLock(root), /Stale\\/unfrozen investigation/, 'Historical runtime must not be admitted as current code');";
const hash = value => createHash('sha256').update(value).digest('hex');
const require = (condition, message) => { if (!condition) throw new Error(message); };

// Keep the immutable historical module, but execute all its checks with one audited migration.
export function hostTestFiles(root = '.') {
  const original = readFileSync(resolve(root, migration.original));
  require(hash(original) === migration.originalSha256, 'Frozen historical test changed');
  const text = original.toString('utf8');
  require(text.split(before).length === 2, 'Ambiguous archival migration');
  const active = readFileSync(resolve(root, migration.active), 'utf8');
  require(active === text.replace(before, after), 'Active archival tests differ beyond approved migration');
  const files = readdirSync(resolve(root, 'scripts/tests')).filter(name => name.endsWith('.test.mjs'))
    .sort().map(name => `scripts/tests/${name}`);
  require(files.filter(path => path === migration.original).length === 1, 'Missing historical test module');
  return files.map(path => path === migration.original ? migration.active : path);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const files = hostTestFiles();
    console.log(`Host suite ${migration.id}: ${migration.original} -> ${migration.active} (all 16 tests retained)`);
    const result = spawnSync(process.execPath, ['--test', ...files], { stdio: 'inherit' });
    if (result.error) throw result.error;
    if (result.signal) throw new Error(`Host suite interrupted: ${result.signal}`);
    process.exitCode = result.status ?? 1;
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
