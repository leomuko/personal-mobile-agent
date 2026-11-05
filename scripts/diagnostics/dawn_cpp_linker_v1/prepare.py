#!/usr/bin/env python3
"""Read-only pinning and data-only freeze creation. Never dispatches native tools."""
import argparse
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('linker_prepare_plan', HERE / 'plan.py')
plan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(plan)
q, ROOT = plan.prior, plan.ROOT
require, c = q.require, q.c
FIX = ROOT / 'planning/fixtures/dawn-cpp-linker-v1'
PRIOR = ROOT / 'planning/fixtures/dawn-cpp-backend-v1'
R2_SHA = '52a0e72aaff0cc6f77ab2c8a22ba4af61c679b95bba3844a5ec4ce07a1973869'
RESULT_SHA = 'e7d723c508e0814809283a5cc623523ce86474c14072a1bb40c16f4797acbbf2'
NEEDLES = (b'ld-path=', b'print-prog-name=', b'_access\x00')
MIB = 1024 ** 2


def bound(path, expected):
    value, actual = q.base.read_json_bound(path)
    require(actual == expected, 'changed prior evidence: ' + str(path))
    return value


def verify_rows(rows):
    require(len(rows) <= 256 and len({r['path'] for r in rows}) == len(rows), 'input count/duplicates')
    for row in rows:
        path = Path(row['path'])
        require(path.is_absolute(), 'nonabsolute frozen input')
        require(c.file_row(path) == row, 'changed input: ' + str(path))


def scan(path, expected, block_bytes=2 * MIB):
    """Bounded string-occurrence observation, not an option/API behavior proof."""
    require(type(block_bytes) is int and 64 <= block_bytes <= 2 * MIB, 'scan block ceiling')
    require(c.file_row(path) == expected and 0 < expected['bytes'] <= 256 * MIB, 'binary pin/size')
    offsets = {n.decode('ascii').replace('\x00', '\\0'): [] for n in NEEDLES}
    omitted = {key: False for key in offsets}
    carry = b''
    consumed = 0
    overlap = max(map(len, NEEDLES)) - 1
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(block_bytes), b''):
            consumed += len(chunk)
            require(consumed <= expected['bytes'], 'binary grew during scan')
            h.update(chunk)
            window = carry + chunk
            start_offset = consumed - len(window)
            for needle in NEEDLES:
                key = needle.decode('ascii').replace('\x00', '\\0')
                if omitted[key]:
                    continue
                pos = 0
                while True:
                    pos = window.find(needle, pos)
                    if pos < 0:
                        break
                    absolute = start_offset + pos
                    if absolute not in offsets[key]:
                        if len(offsets[key]) == 16:
                            omitted[key] = True
                            break
                        offsets[key].append(absolute)
                    pos += len(needle)
            carry = window[-overlap:]
    require(consumed == expected['bytes'] and h.hexdigest() == expected['sha256']
            and c.file_row(path) == expected, 'binary changed during scan')
    return dict(file=expected, observations=offsets, additionalOccurrencesOmitted=omitted,
                completeOccurrenceInventory=False, stringOccurrencesOnly=True,
                executableBehaviorProved=False, nativeExecutionOccurred=False)


def historical_inputs():
    previous = bound(PRIOR / 'metadata-execution-freeze-r2.json', R2_SHA)
    q.check_inputs(previous)  # Actual 133 inputs + six full trees; no owner/dispatch.
    result = bound(PRIOR / 'metadata-2-result-lock.json', RESULT_SHA)
    extra = []
    for row in result['files']:
        path = ROOT / row['path']
        actual = c.file_row(path)
        require(actual['bytes'] == row['bytes'] and actual['sha256'] == row['sha256'], 'changed metadata-2')
        extra.append(actual)
    report = q.data(ROOT / 'build/dawn-cpp-toolchain-v1/metadata-attempts/metadata-2/metadata-report.json')
    outcome = q.data(ROOT / 'build/dawn-cpp-toolchain-v1/metadata-attempts/metadata-2/outcome.json')
    require(report['failure'] == 'ValueError: unexpected selected linker'
            and outcome['cleanupConfirmed'] is True and not outcome['publicAcceptancePresent'],
            'prior failure/cleanup changed')
    require(not os.path.lexists(ROOT / 'build/dawn-cpp-toolchain-v1/metadata-attempts/metadata-2/accepted-metadata.json'),
            'prior failure unexpectedly accepted')
    return previous, extra


def candidate(previous, extras):
    freeze = dict(
        schema=1, protocol=plan.PROTOCOL, scope=plan.SCOPE, status='PREPARATION_ONLY',
        nativeExecutorImplemented=False, executionAuthorized=False,
        separateExecutionAuthorizationRequired=True, nativeQueriesExecuted=0,
        priorExecutionFreezeSha256=R2_SHA, priorResultLockSha256=RESULT_SHA,
        attempt=str(plan.ATTEMPT), commands=plan.commands(), commandCount=7,
        compilerSandbox=plan.sandbox(), nativeEnvironment=plan.environment(),
        allowedExecutablePaths=[str(plan.CLANG)], declaredLinker=str(plan.LD),
        option=plan.OPTION, seconds=300, cleanupSeconds=15, querySeconds=30,
        analysisFloorBytes=18 * 1024 ** 3, smallAdmissionBytes=1024 ** 3,
        commandOutputBytes=256 * 1024, traceBytes=2 * MIB, outerLogBytes=512 * 1024,
        workerRssBytes=512 * MIB, manifestBytes=32 * MIB,
        compileAllowed=False, linkAllowed=False, binaryExecutionAllowed=False, deviceAllowed=False,
        automaticRetries=0, automaticContinuation=False, P2Complete=False, semanticEnablement=False,
        python=previous['python'], ownerEnvironment=previous['environment'],
        trees=copy.deepcopy(previous['trees']), nativeFiles=copy.deepcopy(previous['nativeFiles']),
        sdkRoot=previous['sdkRoot'], resourceRoot=previous['resourceRoot'],
        existingValidatorUnchanged=True, prerequisite='reviewed-native-executor-and-exact-execution-freeze-before-authorization')
    rows = {r['path']: r for r in copy.deepcopy(previous['inputs'])}
    for row in extras:
        require(row['path'] not in rows or rows[row['path']] == row, 'conflicting input pin')
        rows[row['path']] = row
    for path in [*sorted(HERE.glob('*.py')), HERE / 'README.md',
                 ROOT / 'scripts/tests/test_dawn_cpp_linker.py',
                 ROOT / 'planning/P2_CPP_LINKER_SELECTION_INVESTIGATION_V1.md',
                 FIX / 'static-driver-observation.json', FIX / 'lookup-witness.json', FIX / 'references.json',
                 FIX / 'host-test-environments.json',
                 ROOT / 'build/dawn-cpp-linker-v1/host-tests.log',
                 ROOT / 'build/dawn-cpp-linker-v1/focused-tests.log',
                 ROOT / 'build/dawn-cpp-linker-v1/focused-python39.log',
                 ROOT / 'build/dawn-cpp-linker-v1/host-tests-isolated-python39-unavailable.log',
                 ROOT / 'build/dawn-cpp-linker-v1/static-scan-development-attempt-1.json',
                 ROOT / 'build/dawn-cpp-linker-v1/development-test-attempt-1.json',
                 PRIOR / 'metadata-execution-freeze-r2.json', PRIOR / 'metadata-2-result-lock.json']:
        row = c.file_row(path)
        require(row['path'] not in rows or rows[row['path']] == row, 'conflicting preparation pin')
        rows[row['path']] = row
    freeze['inputs'] = [rows[k] for k in sorted(rows)]
    return freeze


def verify(freeze):
    previous, extras = historical_inputs()
    require(freeze == candidate(previous, extras), 'changed investigation recipe or pin')
    verify_rows(freeze['inputs'])
    require(not os.path.lexists(plan.ATTEMPT), 'future attempt is not fresh')
    return dict(status='PASS_PREPARATION_ONLY', nativeExecutorImplemented=False,
                inputs=len(freeze['inputs']), trees=len(freeze['trees']), nativeQueriesExecuted=0)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    require(args.output.resolve() == FIX / 'probe-freeze-r1.json', 'unexpected preparation output')
    previous, extras = historical_inputs()
    freeze = candidate(previous, extras)
    receipt = verify(freeze)
    payload = (json.dumps(freeze, indent=2, sort_keys=True) + '\n').encode()
    require(len(payload) <= 32 * MIB, 'freeze ceiling')
    with args.output.open('xb') as sink:
        sink.write(payload)
    print(json.dumps(dict(receipt, freezeSha256=hashlib.sha256(payload).hexdigest())))


if __name__ == '__main__':
    main()
