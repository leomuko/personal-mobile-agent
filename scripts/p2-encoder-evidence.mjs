export function validateEncoderEvidence(evidence, { runId, startedAtMs, modelSha256, policyId }) {
  if (evidence?.schema !== 1 || evidence.runId !== runId || evidence.modelSha256 !== modelSha256 ||
      evidence.runtime !== 'com.google.mediapipe:tasks-text:1.1.1' || evidence.passed !== true ||
      !Number.isSafeInteger(evidence.startedAtMs) || evidence.startedAtMs < startedAtMs ||
      !Number.isSafeInteger(evidence.finishedAtMs) || evidence.finishedAtMs < evidence.startedAtMs ||
      !Array.isArray(evidence.cases) || evidence.cases.length !== 3) return false;
  const modes = ['cold-warm', 'cancel-active-batch', 'fresh-recovery'];
  let dimensions;
  return evidence.cases.every((sample, index) => {
    if (sample.mode !== modes[index] || sample.passed !== true || sample.confirmedDeath !== true ||
        sample.policyId !== policyId || sample.lowMemory !== false ||
        !Number.isSafeInteger(sample.thermalStatus) || sample.thermalStatus < 0 || sample.thermalStatus >= 3 ||
        !Number.isSafeInteger(sample.totalMs) || sample.totalMs < 0 || sample.totalMs > 5000 ||
        !Number.isSafeInteger(sample.heads) || sample.heads < 1 ||
        !Array.isArray(sample.events) || sample.events.length < 2 ||
        sample.events.some((event, position) => !Number.isSafeInteger(event.elapsedMs) || event.elapsedMs < 0 ||
          (position > 0 && event.elapsedMs < sample.events[position - 1].elapsedMs) ||
          !Number.isSafeInteger(event.pssKb) || event.pssKb <= 0 || event.pssKb > 384 * 1024)) return false;
    const phases = sample.events.map(event => event.phase);
    if (sample.mode === 'cancel-active-batch') {
      return JSON.stringify(phases) === JSON.stringify(['CHECKING', 'LOADING', 'ENCODING']) &&
        Number.isSafeInteger(sample.cancelToDeathMs) && sample.cancelToDeathMs >= 0 && sample.cancelToDeathMs <= 2000;
    }
    if (!Number.isSafeInteger(sample.dimensions) || sample.dimensions < 1 || sample.dimensions > 4096 ||
        !Number.isSafeInteger(sample.loadMs) || sample.loadMs < 0 ||
        !Array.isArray(sample.encodeMs) || sample.encodeMs.length !== 2 ||
        sample.encodeMs.some(value => !Number.isSafeInteger(value) || value < 0) ||
        JSON.stringify(phases) !== JSON.stringify(['CHECKING', 'LOADING', 'ENCODING', 'RESULT', 'CLOSED'])) return false;
    dimensions ??= sample.dimensions;
    return sample.dimensions === dimensions;
  });
}

export function validateForegroundEvidence(evidence, { runId, startedAtMs, modelSha256, policyId }) {
  if (evidence?.schema !== 1 || evidence.runId !== runId || evidence.modelSha256 !== modelSha256 ||
      evidence.runtime !== 'com.google.mediapipe:tasks-text:1.1.1' || evidence.passed !== true ||
      evidence.contextTokens !== 2048 || evidence.outputReservation !== 512 || evidence.fullRetrievalVerified !== false ||
      evidence.gemmaCleanup !== 'CONFIRMED' ||
      !Number.isSafeInteger(evidence.startedAtMs) || evidence.startedAtMs < startedAtMs ||
      !Number.isSafeInteger(evidence.finishedAtMs) || evidence.finishedAtMs < evidence.startedAtMs ||
      !Array.isArray(evidence.cases) || evidence.cases.length !== 7) return false;
  const modes = ['cold', 'resident-reclaim', 'cancel-counting', 'cancel-loading', 'cancel-encoding', 'timeout-before-handoff', 'fresh-worker-recovery'];
  const instances = new Set();
  return evidence.cases.every((sample, index) => {
    if (sample.mode !== modes[index] || sample.passed !== true || sample.encoderCleanup !== 'CONFIRMED' ||
        sample.backend !== 'GPU' || (sample.mode === 'cold' && sample.panelIsolationChecked !== true) ||
        sample.policyId !== policyId || sample.lowMemory !== false ||
        !Number.isSafeInteger(sample.thermalStatus) || sample.thermalStatus < 0 || sample.thermalStatus >= 3 ||
        !Number.isSafeInteger(sample.totalMs) || sample.totalMs < 0 || sample.totalMs > 90000) return false;
    if (sample.mode === 'cancel-counting') return sample.nativeEncoderInvoked === false && sample.outcome === 'CANCELLED';
    if (typeof sample.workerInstance !== 'string' || instances.has(sample.workerInstance) || sample.confirmedDeath !== true ||
        !Number.isSafeInteger(sample.encoderDeathAtMs) || !Array.isArray(sample.gemmaLoadStarts) ||
        !Array.isArray(sample.workerEvents) || sample.workerEvents.length < 1 ||
        sample.workerEvents.some(event => !Number.isSafeInteger(event.pssKb) || event.pssKb <= 0 || event.pssKb > 384 * 1024 ||
          !Number.isSafeInteger(event.pid) || event.pid <= 0 || !Number.isSafeInteger(event.monotonicMs) || event.monotonicMs < 0)) return false;
    instances.add(sample.workerInstance);
    if (sample.mode.startsWith('cancel-')) return sample.outcome === 'CANCELLED' && sample.gemmaLoadStarts.length === 0;
    if (sample.mode === 'timeout-before-handoff') return sample.error === 'ENCODER_TIMEOUT' && sample.outcome === 'TIMED_OUT' && sample.gemmaLoadStarts.length === 0;
    return sample.outcome === 'COMPLETED' && Number.isSafeInteger(sample.preparationMs) && sample.preparationMs >= 0 && sample.preparationMs < 5000 &&
      Number.isSafeInteger(sample.inputBound) && sample.inputBound + 512 <= 2048 &&
      Number.isSafeInteger(sample.sdkInputTokens) && sample.sdkInputTokens >= 0 && sample.sdkInputTokens + 512 <= 2048 &&
      sample.gemmaLoadStarts.length > 0 && sample.gemmaLoadStarts.every(time => Number.isSafeInteger(time) && time >= sample.encoderDeathAtMs);
  });
}
