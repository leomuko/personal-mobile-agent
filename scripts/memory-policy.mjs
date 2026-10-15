import { readFileSync } from 'node:fs';

export function validateMemoryPolicy(profile) {
  if (profile.schema !== 1 || !/^[a-z0-9-]+$/.test(profile.id || '') || typeof profile.calibrated !== 'boolean' ||
      typeof profile.enforceAvailableMemoryFloor !== 'boolean' ||
      !/^[a-f0-9]{64}$/.test(profile.modelSha256 || '') || typeof profile.runtimeVersion !== 'string')
    throw new Error('Invalid exported memory policy identity');
  for (const field of ['loadingFloorBytes', 'generationFloorBytes', 'minimumTotalBytes', 'availableMemorySamples',
    'sampleIntervalMs', 'severeThermalStatus', 'maxSkinCelsius', 'contextTokens', 'outputTokens']) {
    if (!Number.isSafeInteger(profile[field]) || profile[field] <= 0) throw new Error(`Invalid policy field: ${field}`);
  }
  if (!Number.isSafeInteger(profile.supervisedLoadReserveBytes) || profile.supervisedLoadReserveBytes < 0 ||
      !Number.isSafeInteger(profile.loadingFloorBytes + profile.supervisedLoadReserveBytes))
    throw new Error('Invalid policy load reserve');
  return Object.freeze(profile);
}

export function readMemoryPolicy(path = process.env.MEMORY_POLICY_REPORT) {
  if (!path) throw new Error('MEMORY_POLICY_REPORT must point to the QA app-exported policy');
  return validateMemoryPolicy(JSON.parse(readFileSync(path, 'utf8')));
}
