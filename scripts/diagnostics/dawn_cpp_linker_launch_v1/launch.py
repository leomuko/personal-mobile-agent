#!/usr/bin/env python3
"""Prepare one exact host-tool request after real admission; never execute it."""
import argparse
import importlib.util
import os
from pathlib import Path
import re
import shlex

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('linker_launch_request_executor', HERE / 'qualify.py')
e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e)
OUTPUT = e.ROOT / 'build/dawn-cpp-linker-launch-v1/launch-request.json'


def prepare_request(freeze_sha, review_sha):
    e.require(all(isinstance(h, str) and re.fullmatch('[0-9a-f]{64}', h)
                  for h in (freeze_sha, review_sha)), 'invalid launch hash')
    freeze = e.FIX / 'execution-freeze-r1.json'
    review = e.FIX / 'implementation-review-r1.json'
    f = e.admission(freeze, freeze_sha, review, review_sha)
    e.quarantine(e.ATTEMPT.parent)
    e.require(not os.path.lexists(e.ATTEMPT), 'fresh linker-2 attempt required')
    profile = f['launchProfile']
    e.require(profile['sandbox_permissions'] == 'require_escalated'
              and profile['shell'] == '/bin/zsh' and profile['login'] is False
              and profile['workdir'] == str(e.ROOT) and profile['hostTool'] == 'exec_command'
              and profile['persistentRuleRequested'] is False
              and profile['automaticFallback'] is False
              and profile['maximumParentInvocations'] == 1, 'unapproved outer launch profile')
    args = profile['parentArguments']
    e.require(args == dict(python=f['contract']['python'], pythonFlags=['-I', '-S', '-B'],
        entrypoint=str(HERE / 'qualify.py'), freeze=str(freeze),
        freezeSha256='SHA256_OF_REVIEWED_EXECUTION_FREEZE', review=str(review),
        reviewSha256='SHA256_OF_INDEPENDENT_REVIEW_BOUND_TO_FREEZE', attempt=str(e.ATTEMPT)),
        'unapproved parent argument template')
    argv = [args['python'], *args['pythonFlags'], args['entrypoint'],
            '--freeze', str(freeze), '--sha256', freeze_sha, '--review', str(review),
            '--review-sha256', review_sha, '--attempt', str(e.ATTEMPT)]
    return dict(argv=argv, approvalGrantedByGenerator=False, nativeQueriesExecuted=0,
        toolRequest=dict(cmd=shlex.join(argv), workdir=str(e.ROOT), shell='/bin/zsh',
            login=False, sandbox_permissions='require_escalated',
            justification='May I run the reviewed linker-2 executor once outside Codex restrictions, retaining its inner compiler-only sandbox, seven probes, first-failure stop and cleanup limits?',
            yield_time_ms=1000, max_output_tokens=2000))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--freeze-sha256', required=True)
    parser.add_argument('--review-sha256', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    e.require(args.output.absolute() == OUTPUT and args.output.resolve() == OUTPUT,
              'unexpected host-request output')
    e.require(not os.path.lexists(OUTPUT), 'host request already prepared')
    value = prepare_request(args.freeze_sha256, args.review_sha256)
    e.c.write(OUTPUT, value, 65536)


if __name__ == '__main__':
    main()
