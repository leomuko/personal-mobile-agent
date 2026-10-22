import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { hostTestFiles, migration } from '../host-test-suite.mjs';

function sandbox(run) {
  const root = mkdtempSync(join(tmpdir(), 'archival-tests-'));
  try {
    for (const path of [migration.original, migration.active]) {
      mkdirSync(dirname(join(root, path)), { recursive: true });
      writeFileSync(join(root, path), readFileSync(path));
    }
    return run(root);
  } finally { rmSync(root, { recursive: true, force: true }); }
}

test('canonical discovery replaces only the frozen module and includes every other host test', () => {
  const expected = readdirSync('scripts/tests').filter(name => name.endsWith('.test.mjs')).sort()
    .map(name => `scripts/tests/${name}`).map(path => path === migration.original ? migration.active : path);
  assert.deepEqual(hostTestFiles(), expected);
  assert.equal(new Set(expected).size, expected.length);
  assert.ok(expected.includes('scripts/tests/v3-protocol.test.mjs'));
});

test('migration retains all sixteen tests and leaves earlier regressions byte-identical', () => {
  const original = readFileSync(migration.original, 'utf8');
  const active = readFileSync(migration.active, 'utf8');
  const last = "test('renewed depth freeze preserves rejected bytes and changes only the requested question'";
  assert.equal(active.split(last)[0], original.split(last)[0]);
  assert.equal((active.match(/^test\(/gm) ?? []).length, 16);
  assert.match(active, /assert\.throws\(\(\) => loadLock\(root\), \/Stale/);
  assert.doesNotMatch(active, /test\.(skip|todo|only)\(/);
  assert.doesNotThrow(() => hostTestFiles());
});

test('additional host tests are discovered without changing the migration list', () => sandbox(root => {
  writeFileSync(join(root, 'scripts/tests/future.test.mjs'), '');
  assert.deepEqual(hostTestFiles(root), [migration.active, 'scripts/tests/future.test.mjs']);
}));

test('modified frozen bytes and altered active assertions fail closed', () => sandbox(root => {
  const activePath = join(root, migration.active), originalPath = join(root, migration.original);
  const active = readFileSync(activePath, 'utf8');
  writeFileSync(activePath, active.replace("assert.equal(snapshot.status, 'rejected-before-collection');", ''));
  assert.throws(() => hostTestFiles(root), /beyond approved migration/);
  writeFileSync(activePath, active);
  writeFileSync(originalPath, readFileSync(originalPath, 'utf8') + '\n');
  assert.throws(() => hostTestFiles(root), /Frozen historical test changed/);
}));

test('missing migrated module is an error, never an omitted suite', () => sandbox(root => {
  rmSync(join(root, migration.active));
  assert.throws(() => hostTestFiles(root), /ENOENT/);
}));

test('complete runner refuses altered tests before launching the test process', () => sandbox(root => {
  const marker = join(root, 'executed');
  writeFileSync(join(root, migration.active), `import fs from 'node:fs'; fs.writeFileSync(${JSON.stringify(marker)}, 'ran');`);
  const result = spawnSync(process.execPath, [resolve('scripts/host-test-suite.mjs')], { cwd: root, encoding: 'utf8' });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /beyond approved migration/);
  assert.throws(() => readFileSync(marker), /ENOENT/);
}));
