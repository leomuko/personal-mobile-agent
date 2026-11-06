#!/usr/bin/env python3
"""Create only the fresh linker-2 execution freeze, without executing probes."""
import argparse
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('linker_launch_freeze', HERE / 'qualify.py')
e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e)


def create():
    value = e.proposal()
    e.quarantine(e.ATTEMPT.parent)
    e.require(not os.path.lexists(e.ATTEMPT), 'fresh linker-2 attempt required')
    result = dict(schema=1, protocol=e.PROTOCOL, scope=e.SCOPE,
        proposalSha256=e.PROPOSAL_SHA, proposalReviewSha256=e.PROPOSAL_REVIEW_SHA,
        implementationSha256=e.owner.digest(e.__file__), contract=e.contract(value),
        amendmentSha256=e.AMENDMENT_SHA, amendmentReviewSha256=e.AMENDMENT_REVIEW_SHA,
        baselineExecutionFreezeSha256=e.BASE_SHA, launchProfile=value['launchProfile'],
        backendCatalog='planning/fixtures/dawn-cpp-backend-v1/backend-native-catalog.json',
        cacheMap='/System/Volumes/Preboot/Cryptexes/OS/System/Library/dyld/dyld_shared_cache_arm64e.map',
        trees=copy.deepcopy(value['trees']), nativeFiles=copy.deepcopy(value['nativeFiles']),
        revision=1, nativeExecutorImplemented=True, nativeExecutionAuthorized=False,
        nativeQueriesExecuted=0, separateExecutionAuthorizationRequired=True,
        outerPermissionEnforcement='external host tool approval; worker cannot self-certify',
        P2Complete=False, semanticEnablement=False)
    rows = {row['path']: copy.deepcopy(row) for row in value['inputs']}
    paths = [Path(path) for path in e.required_inputs(value)]
    paths += [e.ROOT / 'build/dawn-cpp-linker-launch-v1/focused-python39.log',
              e.ROOT / 'build/dawn-cpp-linker-launch-v1/full-host-suite.log']
    for path in paths:
        row = e.c.file_row(path)
        e.require(row['path'] not in rows or rows[row['path']] == row,
                  'conflicting launch input binding')
        rows[row['path']] = row
    result['inputs'] = [rows[key] for key in sorted(rows)]
    e.require(len(rows) <= 256, 'unchanged input-count ceiling')
    e.check_inputs(result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    e.require(args.output.resolve() == e.FIX / 'execution-freeze-r1.json',
              'unexpected launch freeze destination')
    value = create()
    payload = (json.dumps(value, sort_keys=True, indent=2) + '\n').encode()
    e.require(len(payload) <= 32 * 1024 ** 2, 'freeze size ceiling')
    with args.output.open('xb') as stream:
        stream.write(payload)
    print(json.dumps(dict(status='AWAITING_IMPLEMENTATION_QA',
        freezeSha256=hashlib.sha256(payload).hexdigest(),
        inputs=len(value['inputs']), trees=len(value['trees']), nativeQueriesExecuted=0)))


if __name__ == '__main__':
    main()
