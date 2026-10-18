import { execFileSync } from 'node:child_process';

export function supervisionIdentity(env = process.env) {
  const runId = env.QA_SUPERVISION_RUN_ID;
  const parent = Number(env.QA_SUPERVISOR_PID);
  if (!/^[A-Za-z0-9_-]{1,80}$/.test(runId || '') || !Number.isSafeInteger(parent) || parent < 1)
    throw new Error('A run-specific supervisor identity is required');
  return { runId, parent };
}

const quote = value => `'${value.replaceAll("'", "'\"'\"'")}'`;
export function supervisionDirectory(runId) {
  if (!/^[A-Za-z0-9_-]{1,80}$/.test(runId || '')) throw new Error('Invalid supervision identity');
  return `no_backup/qa-supervision/${runId}`;
}

function publish(adb, serial, runId, name, message, execute) {
  const directory = supervisionDirectory(runId);
  const script = `mkdir -p ${directory} && printf %s ${quote(JSON.stringify(message))} > ${directory}/${name}.tmp && mv ${directory}/${name}.tmp ${directory}/${name}.json`;
  execute(adb, ['-s', serial, 'shell', `run-as dev.edgecompanion.app.secureqa sh -c ${quote(script)}`], { timeout: 5000 });
}

export function renewSupervision({ adb, serial, runId, sequence, parent },
    execute = execFileSync, currentParent = () => process.ppid) {
  if (currentParent() !== parent) throw new Error('Host supervisor is no longer the monitor parent');
  if (!/^[A-Za-z0-9_-]{1,80}$/.test(runId || '') || !Number.isSafeInteger(sequence) || sequence < 1)
    throw new Error('Invalid supervision heartbeat');
  const uptime = execute(adb, ['-s', serial, 'shell', 'cat', '/proc/uptime'], { encoding: 'utf8', timeout: 5000 });
  const seconds = Number(/^(\d+(?:\.\d+)?)\s/.exec(uptime)?.[1]);
  const elapsedMs = Math.floor(seconds * 1000);
  if (!Number.isSafeInteger(elapsedMs) || elapsedMs < 0) throw new Error('Device boot clock unavailable');
  if (currentParent() !== parent) throw new Error('Host supervisor exited during the heartbeat');
  const heartbeat = { schema: 2, runId, sequence, elapsedMs };
  publish(adb, serial, runId, 'heartbeat', heartbeat, execute);
  return heartbeat;
}

export function stopSupervisedQa({ adb, serial, runId }, execute = execFileSync) {
  if (!/^[A-Za-z0-9_-]{1,80}$/.test(runId || '')) return { hostStopError: 'InvalidIdentity' };
  try {
    publish(adb, serial, runId, 'stop', { schema: 2, runId, action: 'stop' }, execute);
    return { stopRequested: true };
  } catch (error) { return { hostStopError: error.name }; }
}
