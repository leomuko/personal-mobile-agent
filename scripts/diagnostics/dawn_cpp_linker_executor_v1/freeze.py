#!/usr/bin/env python3
"""Create an exclusive executor freeze through data checks only. Never runs probes."""
import argparse
import copy
import hashlib
import importlib.util
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('linker_executor_freeze', HERE / 'qualify.py')
executor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(executor)


def create():
    e = executor
    value = e.proposal()
    # The proposal's actual data-only checker also proves the future path absent.
    e.preparation.verify(value)
    f = dict(schema=1, protocol=e.PROTOCOL, scope=e.SCOPE,
             proposalSha256=e.PROPOSAL_SHA, proposalReviewSha256=e.PROPOSAL_REVIEW_SHA,
             implementationSha256=e.owner.digest(e.__file__), contract=e.contract(value),
             backendCatalog='planning/fixtures/dawn-cpp-backend-v1/backend-native-catalog.json',
             cacheMap='/System/Volumes/Preboot/Cryptexes/OS/System/Library/dyld/dyld_shared_cache_arm64e.map',
             trees=copy.deepcopy(value['trees']), nativeFiles=copy.deepcopy(value['nativeFiles']),
             nativeExecutorImplemented=True, nativeExecutionAuthorized=False, nativeQueriesExecuted=0,
             revision=2, correction='Explicit uncertain-publication quarantine and pinned guidance routing',
             separateExecutionAuthorizationRequired=True, P2Complete=False, semanticEnablement=False)
    rows = {r['path']: copy.deepcopy(r) for r in value['inputs']}
    paths = [e.PROPOSAL, e.PROPOSAL_REVIEW,
             e.ROOT / 'planning/fixtures/dawn-cpp-linker-v1/reviewed-checkpoint.json',
             e.ROOT / 'planning/fixtures/dawn-cpp-linker-v1/canonical-verify-proof.json',
             *sorted(HERE.glob('*.py')), HERE / 'README.md',
             e.ROOT / 'scripts/tests/test_dawn_cpp_linker_executor.py',
             e.ROOT / 'planning/P2_CPP_LINKER_EXECUTOR_V1.md',
             e.ROOT / 'planning/MODEL_COMPATIBILITY_AND_CAPABILITIES_V1.md',
             e.ROOT / 'planning/MODEL_CAPABILITY_BENCHMARK_PLAN_V1.md',
             e.ROOT / 'AGENTS.md', e.ROOT / 'docs/CPP_BUILD_SKILLS_PROVENANCE.md',
             e.ROOT / 'docs/CPP_BUILD_SKILLS_PROVENANCE.json',
             e.ROOT / 'planning/fixtures/cpp-build-skills-v1/AGENTS.before.txt',
             e.ROOT / 'planning/fixtures/cpp-build-skills-v1/historical-AGENTS-bindings.json',
             e.FIX / 'execution-freeze-r1.json', e.FIX / 'implementation-result-lock.json',
             e.FIX / 'r1-implementation-review.json', e.FIX / 'r1-reviewed-snapshot.json',
             e.ROOT / 'build/dawn-cpp-linker-executor-v1/rejected-r1-source/snapshot.json',
             e.ROOT / 'build/dawn-cpp-linker-executor-v1/host-tests-r2.log',
             e.ROOT / 'build/dawn-cpp-linker-executor-v1/focused-python39-r2-final.log']
    guidance = e.q.data(e.ROOT / 'docs/CPP_BUILD_SKILLS_PROVENANCE.json')
    paths += [Path(row['path']) for skill in guidance['skills'] for row in skill['files']]
    paths += [e.ROOT / skill['license']['retainedPath'] for skill in guidance['skills']]
    for path in paths:
        row = e.c.file_row(path)
        e.require(row['path'] not in rows or rows[row['path']] == row, 'conflicting frozen input')
        rows[row['path']] = row
    f['inputs'] = [rows[k] for k in sorted(rows)]
    e.require(e.required_inputs(value) <= set(rows), 'incomplete executor dependency set')
    e.check_inputs(f)
    return f


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    executor.require(args.output.resolve() == executor.FIX / 'execution-freeze-r2.json', 'unexpected freeze destination')
    value = create()
    data = (json.dumps(value, indent=2, sort_keys=True) + '\n').encode()
    executor.require(len(data) <= 32 * 1024 ** 2, 'freeze size ceiling')
    with args.output.open('xb') as sink: sink.write(data)
    print(json.dumps(dict(freezeSha256=hashlib.sha256(data).hexdigest(), inputs=len(value['inputs']),
                          trees=len(value['trees']), nativeQueriesExecuted=0, status='AWAITING_IMPLEMENTATION_QA')))


if __name__ == '__main__':
    main()
