import { readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export function summarizeMemoryRun(run, exits, profile, expectedRunId) {
  const issues = [];
  if (run.status !== 'passed') issues.push('native-workload-did-not-pass');
  const keys = Object.keys(profile);
  if (!run.policy || Object.keys(run.policy).length !== keys.length || keys.some(key => run.policy[key] !== profile[key]))
    issues.push('policy-mismatch');
  if (run.backend !== 'GPU' || run.automaticFallback !== false) issues.push('backend-mismatch');
  if (!Number.isSafeInteger(run.requestedCycles) || run.requestedCycles < 3 || run.completedCycles !== run.requestedCycles)
    issues.push('incomplete-cycles');
  if (![0, 20].includes(run.requestedSustainedMinutes) || run.completedSustainedMinutes !== run.requestedSustainedMinutes)
    issues.push('incomplete-sustained-workload');
  const trace = run.telemetry;
  if (!trace || trace.failedSamples !== 0 || trace.droppedSamples !== 0) issues.push('incomplete-telemetry');
  const stages = new Set((trace?.samples || []).map(sample => sample.stage));
  if ((trace?.samples || []).some(sample => ['appPssBytes', 'appRssBytes', 'pressureThresholdBytes'].some(key =>
      !Number.isSafeInteger(sample[key]) || sample[key] <= 0) || sample.policyId !== profile.id || sample.backend !== 'GPU'))
    issues.push('invalid-sample-metadata');
  for (const stage of ['BASELINE', 'LOADING', 'LOADED_IDLE', 'PREFILL', 'DECODING', 'CANCELLING', 'POST_RELEASE']) {
    if (!stages.has(stage)) issues.push(`missing-stage-${stage}`);
  }
  const recovery = (run.observations || []).map(cycle => cycle.postReleasePssBytes);
  if (recovery.length !== run.completedCycles || recovery.some(value => !Number.isSafeInteger(value) || value <= 0))
    issues.push('missing-recovery-measurements');
  if (!Number.isSafeInteger(run.startedAtMs) || run.startedAtMs <= 0 || !Array.isArray(exits.entries) ||
      typeof exits.lowMemoryKillReportSupported !== 'boolean') issues.push('missing-exit-evidence');
  if (typeof expectedRunId !== 'string' || !/^[A-Za-z0-9_-]{1,80}$/.test(expectedRunId) ||
      run.runId !== expectedRunId || exits.runId !== expectedRunId || exits.phase !== 'after')
    issues.push('exit-run-mismatch');
  if (!Number.isSafeInteger(run.finishedAtMs) || run.finishedAtMs < run.startedAtMs ||
      !Number.isSafeInteger(exits.capturedAtMs) || exits.capturedAtMs < run.finishedAtMs)
    issues.push('stale-or-undated-exit-evidence');
  const relevantExits = (exits.entries || []).filter(exit => exit.timestampMs >= run.startedAtMs);
  const concerning = relevantExits.filter(exit => ['confirmed-low-memory', 'native-crash', 'anr', 'sigkill-unclassified'].includes(exit.classification));
  if (concerning.length) issues.push('process-exits-require-review');
  return { schema: 1, runId: run.runId, policyId: profile.id, workloadPassed: issues.length === 0, issues,
    postReleasePssBytes: recovery, strictlyIncreasingPostReleasePss: recovery.length > 1 && recovery.every((value, index) => index === 0 || value > recovery[index - 1]),
    exitClassifications: relevantExits.map(exit => exit.classification),
    sustainedMinutes: run.completedSustainedMinutes ?? 0,
    acceptanceComplete: false, acceptance: 'requires-retained-memory-and-calibration-review' };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const folder = process.argv[2];
  try {
    const read = name => JSON.parse(readFileSync(join(folder, name), 'utf8'));
    const summary = summarizeMemoryRun(read('memory-reliability.json'), read('exits-after.json'), read('memory-policy.json'), read('run-context.json').runId);
    writeFileSync(join(folder, 'memory-summary.json'), JSON.stringify(summary, null, 2) + '\n');
    if (!summary.workloadPassed) process.exitCode = 1;
  } catch (error) { console.error(`Incomplete memory evidence: ${error.message}`); process.exitCode = 1; }
}
