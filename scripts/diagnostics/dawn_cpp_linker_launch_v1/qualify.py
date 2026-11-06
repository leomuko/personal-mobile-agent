#!/usr/bin/env python3
"""Reviewed seven-probe metadata executor; running it requires separate authorization."""
import argparse
import base64
import copy
from contextlib import contextmanager
import importlib.util
import json
import os
from pathlib import Path
import resource
import shutil
import signal
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent


def module(name, path):
    spec = importlib.util.spec_from_file_location('linker_executor_' + name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


preparation = module('preparation', ROOT / 'scripts/diagnostics/dawn_cpp_linker_v1/prepare.py')
policy = module('policy', ROOT / 'scripts/diagnostics/dawn_cpp_linker_v1/policy.py')
plan = preparation.plan
q, c, owner = plan.prior, plan.prior.c, plan.prior.owner
require, now, remaining = q.require, q.now, q.remaining
MIB = 1024 ** 2
PROTOCOL = 'dawn-cpp-linker-launch-executor-v1'
SCOPE = 'reviewed-linker-2-seven-probe-command-scoped-outer-exception'
PROPOSAL = ROOT / 'planning/fixtures/dawn-cpp-linker-v1/probe-freeze-r1.json'
PROPOSAL_SHA = 'fc37dbb00cea5abc4b84c7ddf7445555dc0027bb1c86cbd09b288f1229c92ff3'
PROPOSAL_REVIEW = ROOT / 'planning/fixtures/dawn-cpp-linker-v1/preparation-review.json'
PROPOSAL_REVIEW_SHA = 'b0f679821cc82e730a3d19f990c8ba7d74d211efd9e831b739584cd6e50b1fc3'
FIX = ROOT / 'planning/fixtures/dawn-cpp-linker-launch-v1'


AMENDMENT = FIX / 'amendment.json'
AMENDMENT_SHA = '83b545b7eea24d7a5c9bb1bf3e02708528617eaa04cd3e1ead52a5f82185b015'
AMENDMENT_REVIEW = FIX / 'amendment-review-r1.json'
AMENDMENT_REVIEW_SHA = 'b0eb588b32b74a66491232c171fb7189ebf7e61063881c3268d7242ac35be018'
BASE_FREEZE = ROOT / 'planning/fixtures/dawn-cpp-linker-executor-v1/execution-freeze-r2.json'
BASE_SHA = '0fedb46426268e487cfb18ec55a04b04b0c348db7b5ba956990d03118c0d1d56'
ATTEMPT = ROOT / 'build/dawn-cpp-linker-v1/probe-attempts/linker-2'


def historical_proposal():
    value = preparation.bound(PROPOSAL, PROPOSAL_SHA)
    review = preparation.bound(PROPOSAL_REVIEW, PROPOSAL_REVIEW_SHA)
    require(review['verdict'] == 'PASS' and review['freezeSha256'] == PROPOSAL_SHA
            and review['preparationOnly'] is True, 'missing proposal review')
    require(value['commands'] == plan.commands() and value['compilerSandbox'] == plan.sandbox()
            and value['nativeEnvironment'] == plan.environment(), 'proposal recipe changed')
    return value



def verify_composition():
    value = q.data(HERE / 'composition.json', 65536)
    expected = dict(baseline=ROOT / 'scripts/diagnostics/dawn_cpp_linker_executor_v1/qualify.py',
                    candidate=HERE / 'qualify.py',
                    patch=ROOT / 'build/dawn-cpp-linker-launch-v1/executor-composition.patch')
    for key, path in expected.items():
        row = value[key]
        observed = c.file_row(path)
        require(row['path'] == str(path.relative_to(ROOT))
                and row['bytes'] == observed['bytes'] and row['sha256'] == observed['sha256'],
                'changed/stale executor composition: ' + key)


def proposal():
    verify_composition()
    amendment = preparation.bound(AMENDMENT, AMENDMENT_SHA)
    review = preparation.bound(AMENDMENT_REVIEW, AMENDMENT_REVIEW_SHA)
    require(review['verdict'] == 'PASS' and review['amendmentSha256'] == AMENDMENT_SHA,
            'missing/mismatched launch amendment review')
    require(review['baselineSha256'] == BASE_SHA
            and review['scope'] == amendment['scope']
            and review['documentPath'] == str(ROOT / 'planning/P2_CPP_LINKER_LAUNCH_AMENDMENT_V1.md')
            and owner.digest(review['documentPath']) == review['documentSha256'],
            'changed reviewed launch document/baseline')
    baseline = preparation.bound(BASE_FREEZE, BASE_SHA)
    original = historical_proposal()
    require(baseline['contract'] == contract(original), 'historical R2 contract changed')
    require(amendment['freshAttempt'] == str(ATTEMPT)
            and amendment['oldAttempt'] == baseline['contract']['attempt']
            and amendment['allowedRebaseFields'] == ['attempt', 'commands', 'compilerSandbox', 'nativeEnvironment'],
            'unexpected attempt rebase')
    # Verify the failed attempt and its QA evidence; never rewrite historical inputs.
    for item in amendment['historicalEvidence']:
        path = ROOT / item['path']
        record = preparation.bound(path, item['sha256'])
        require(path.stat().st_size == item['bytes'], 'historical evidence size changed')
        for row in record.get('bindings', []):
            observed = c.file_row(ROOT / row['path'])
            require(observed['bytes'] == row['bytes'] and observed['sha256'] == row['sha256'],
                    'historical result binding changed: ' + row['path'])
    result = copy.deepcopy(baseline['contract'])
    result.update(attempt=str(ATTEMPT), commands=plan.commands(ATTEMPT),
                  compilerSandbox=plan.sandbox(ATTEMPT), nativeEnvironment=plan.environment(ATTEMPT))
    require({key for key in result if result[key] != baseline['contract'][key]}
            == set(amendment['allowedRebaseFields']), 'unapproved contract delta')
    result['inputs'] = copy.deepcopy(baseline['inputs'])
    result['launchProfile'] = copy.deepcopy(amendment['launchProfile'])
    return result


def contract(value):
    keys = ('attempt', 'commands', 'commandCount', 'compilerSandbox', 'nativeEnvironment',
            'allowedExecutablePaths', 'declaredLinker', 'option', 'seconds', 'cleanupSeconds',
            'querySeconds', 'analysisFloorBytes', 'smallAdmissionBytes', 'commandOutputBytes',
            'traceBytes', 'outerLogBytes', 'workerRssBytes', 'manifestBytes', 'compileAllowed',
            'linkAllowed', 'binaryExecutionAllowed', 'deviceAllowed', 'automaticRetries',
            'automaticContinuation', 'python', 'ownerEnvironment', 'trees', 'nativeFiles',
            'sdkRoot', 'resourceRoot')
    return {key: value[key] for key in keys}


def required_inputs(value):
    guidance = q.data(ROOT / 'docs/CPP_BUILD_SKILLS_PROVENANCE.json')
    guidance_paths = {row['path'] for skill in guidance['skills'] for row in skill['files']}
    guidance_paths |= {str(ROOT / skill['license']['retainedPath']) for skill in guidance['skills']}
    return {row['path'] for row in value['inputs']} | guidance_paths | {
        str(PROPOSAL), str(PROPOSAL_REVIEW), str(HERE / 'qualify.py'), str(HERE / 'freeze.py'),
        str(Path(policy.__file__).resolve()), str(Path(preparation.__file__).resolve()),
        str(ROOT / 'AGENTS.md'), str(ROOT / 'docs/CPP_BUILD_SKILLS_PROVENANCE.json'),
        str(ROOT / 'docs/CPP_BUILD_SKILLS_PROVENANCE.md'),
        str(ROOT / 'planning/fixtures/cpp-build-skills-v1/AGENTS.before.txt'),
        str(ROOT / 'planning/fixtures/cpp-build-skills-v1/historical-AGENTS-bindings.json'),
        str(ROOT / 'planning/fixtures/dawn-cpp-backend-v1/backend-native-catalog.json'),
        str(AMENDMENT), str(AMENDMENT_REVIEW), str(BASE_FREEZE),
        str(ROOT / 'planning/fixtures/dawn-cpp-linker-executor-v1/r2-freeze-review.json'),
        str(ROOT / 'planning/fixtures/dawn-cpp-linker-executor-v1/linker-1-result-lock.json'),
        str(ROOT / 'planning/fixtures/dawn-cpp-linker-executor-v1/linker-1-qa-checkpoint.json'),
        str(HERE / 'README.md'), str(HERE / 'composition.json'), str(HERE / 'launch.py'),
        str(ROOT / 'planning/P2_CPP_LINKER_LAUNCH_AMENDMENT_V1.md'),
        str(ROOT / 'scripts/tests/test_dawn_cpp_linker_launch.py'),
        str(ROOT / 'scripts/tests/test_dawn_cpp_linker_launch_invariants.py'),
        str(ROOT / 'scripts/tests/test_dawn_cpp_linker_launch_request.py'),
        str(ROOT / 'build/dawn-cpp-linker-launch-v1/executor-composition.patch')}


def check(deadline):
    remaining(deadline, 300)
    require(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss <= 512 * MIB,
            'executor worker RSS ceiling')


def check_inputs(f, deadline=None):
    if deadline is not None: check(deadline)
    q.check_inputs(f)  # Includes six trees, exact payload hashes/modes and old backend profile.
    if deadline is not None: check(deadline)


def admission(path, sha, review_path, review_sha, deadline=None):
    if deadline is not None: check(deadline)
    f, actual = q.base.read_json_bound(path)
    review, actual_review = q.base.read_json_bound(review_path, 65536)
    require(actual == sha and actual_review == review_sha and review['verdict'] == 'PASS'
            and review['scope'] == SCOPE and review['freezeSha256'] == sha,
            'missing/mismatched executor review')
    value = proposal()
    require(f['protocol'] == PROTOCOL and f['scope'] == SCOPE
            and f['proposalSha256'] == PROPOSAL_SHA and f['proposalReviewSha256'] == PROPOSAL_REVIEW_SHA
            and f['implementationSha256'] == owner.digest(__file__), 'executor identity changed')
    require(f['contract'] == contract(value), 'changed seven-probe contract')
    require(f['amendmentSha256'] == AMENDMENT_SHA
            and f['amendmentReviewSha256'] == AMENDMENT_REVIEW_SHA
            and f['baselineExecutionFreezeSha256'] == BASE_SHA
            and f['launchProfile'] == value['launchProfile'], 'changed launch identity/profile')
    require(f['backendCatalog'] == 'planning/fixtures/dawn-cpp-backend-v1/backend-native-catalog.json'
            and f['cacheMap'] == '/System/Volumes/Preboot/Cryptexes/OS/System/Library/dyld/dyld_shared_cache_arm64e.map',
            'changed native input catalog')
    rows = f['inputs']
    require(len(rows) <= 256 and len({r['path'] for r in rows}) == len(rows), 'input count/duplicate binding')
    indexed = {r['path']: r for r in rows}
    require(required_inputs(value) <= set(indexed), 'unbound executor dependency')
    for row in value['inputs']:
        require(indexed[row['path']] == row, 'changed proposal input binding')
    require(f['trees'] == value['trees'] and f['nativeFiles'] == value['nativeFiles'], 'changed input scope')
    check_inputs(f, deadline)
    return f


def quarantine(parent):
    q.base.quarantine(parent)
    if not parent.exists(): return
    for child in parent.iterdir():
        require(not (child / 'publication-failure.json').exists(), 'uncertain prior publication; quarantined')
        outcome = child / 'outcome.json'
        if outcome.exists():
            v = q.data(outcome, 65536)
            require(v.get('publicationUncertain', False) is False, 'uncertain prior revocation; quarantined')
            require(v.get('publicAcceptancePresent') is not True, 'revocation uncertain; quarantined')


@contextmanager
def interruptions():
    previous = {}
    def stop(signum, frame):
        raise KeyboardInterrupt('interrupted by signal ' + str(signum))
    try:
        for sig in (signal.SIGINT, signal.SIGTERM):
            previous[sig] = signal.signal(sig, stop)
        yield
    finally:
        for sig, handler in previous.items(): signal.signal(sig, handler)


def dispatch(job, attempt, f, deadline, trace):
    check(deadline)
    # The old dispatch role classifier has no diagnostic-only kind. All seven
    # are compiler-role jobs; this copy changes only that classifier, never argv.
    role_job = dict(job, kind='driver-jobs')
    catalog = q.data(ROOT / f['backendCatalog'])
    require(q.backend.sandbox(attempt, q.backend.allowed_paths(catalog, role_job)) == f['contract']['compilerSandbox'],
            'changed compiler-only sandbox')
    text = q.backend.dispatch(role_job, attempt, catalog, remaining(deadline, 30), trace,
                              f['contract']['nativeEnvironment'], absolute_deadline=deadline)
    check(deadline)
    return text


def worker(f, attempt, deadline):
    report = dict(status='RUNNING', commands=[], notRunCommands=[], compilerQualified=False,
                  compileExecuted=False, linkExecuted=False, binaryExecuted=False, rootCause='UNPROVED')
    trace_bytes = 0
    def trace(argv, raw, exitcode, seconds):
        nonlocal trace_bytes
        data = (json.dumps(dict(argv=argv, outputBase64=base64.b64encode(raw).decode(),
                               exitCode=exitcode, seconds=seconds), separators=(',', ':')) + '\n').encode()
        trace_bytes += len(data)
        require(trace_bytes <= 2 * MIB, 'aggregate trace ceiling')
        with (attempt / 'command-trace.jsonl').open('ab') as sink: sink.write(data)
    try:
        check(deadline)
        q.native_rows(f)  # Static byte/alias/load-command check; no inspector is dispatched.
        (attempt / 'empty.cpp').write_bytes(b'// Synthetic empty driver-plan input. No compilation authorized.\n')
        for job in f['contract']['commands']:
            check(deadline)
            row = dict(id=job['id'], status='RUNNING')
            report['commands'].append(row)
            query_started = now()
            query_deadline = min(deadline, query_started + 30)
            text = dispatch(job, attempt, f, query_deadline, trace)
            result = policy.validate(job, text)
            check(query_deadline)
            observed = c.tree(attempt, 16, 8 * MIB)
            require(set(observed['files']) <= {'owner-started.json', 'worker.stdout.log', 'worker.stderr.log',
                    'empty.cpp', 'command-trace.jsonl'}, 'unexpected native output')
            check(query_deadline)
            row.update(status='PASSED', result=result, seconds=now() - query_started)
        report['qualification'] = policy.validate_complete(report['commands'], attempt)
        check_inputs(f, deadline)
        report['status'] = 'DRY_RUN_SELECTION_PASSED_CPP_NOT_EXECUTED'
    except BaseException as error:
        report.update(status='FAILED', failure=type(error).__name__ + ': ' + str(error)[:2048])
        if report['commands'] and report['commands'][-1]['status'] == 'RUNNING':
            report['commands'][-1]['status'] = 'FAILED'
        raise
    finally:
        report['notRunCommands'] = [j['id'] for j in f['contract']['commands']
                                   if j['id'] not in {r['id'] for r in report['commands']}]
        report['commandTraceBytes'] = trace_bytes
        report['peakWorkerRssBytes'] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        c.write(attempt / 'probe-report.json', report, 2 * MIB)


def main():
    started = now()
    deadline = started + 300
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('freeze', 'review', 'attempt'): parser.add_argument('--' + name, type=Path, required=True)
    for name in ('sha256', 'review-sha256'): parser.add_argument('--' + name, required=True)
    parser.add_argument('--worker', action='store_true')
    parser.add_argument('--decision-deadline', type=float)
    args = parser.parse_args()
    created = owner_invoked = False
    outcome = None
    published = []
    with interruptions():
        try:
            if args.worker:
                require(args.decision_deadline is not None and args.decision_deadline <= deadline,
                        'invalid inherited deadline')
                deadline = args.decision_deadline
            else: require(args.decision_deadline is None, 'only workers inherit deadlines')
            f = admission(args.freeze, args.sha256, args.review, args.review_sha256, deadline)
            attempt = args.attempt.absolute()
            require(str(attempt) == f['contract']['attempt'] and attempt.resolve() == attempt
                    and attempt.is_relative_to(ROOT.resolve()), 'unexpected attempt path')
            if args.worker:
                marker = q.data(attempt / 'owner-started.json', 65536)
                require(marker['freezeSha256'] == args.sha256
                        and marker['decisionDeadlineMonotonic'] == deadline
                        and marker['decisionStartedMonotonic'] <= now() < deadline,
                        'missing/mismatched inherited owner')
                worker(f, attempt, deadline)
                return 0
            quarantine(attempt.parent)
            require(not os.path.lexists(attempt) and shutil.disk_usage(ROOT).free >= 1024 * MIB,
                    'fresh attempt/1 GiB small-slice capacity required')
            check(deadline)
            attempt.mkdir(parents=True, mode=0o700)
            created = True
            c.write(attempt / 'owner-started.json', dict(freezeSha256=args.sha256,
                    decisionStartedMonotonic=started, decisionDeadlineMonotonic=deadline), 65536)
            argv = [f['contract']['python'], '-I', '-S', '-B', str(Path(__file__).resolve()),
                    '--freeze', str(args.freeze.resolve()), '--sha256', args.sha256,
                    '--review', str(args.review.resolve()), '--review-sha256', args.review_sha256,
                    '--attempt', str(attempt), '--worker', '--decision-deadline', repr(deadline)]
            check(deadline)
            owner_invoked = True
            outcome = owner.run_owned(argv, ROOT, attempt / 'worker', f['contract']['ownerEnvironment'],
                                      remaining(deadline, 300), 512 * 1024, 15)
            check(deadline)
            require(outcome['classification'] == 'ANALYSIS_COMMAND_SUCCEEDED'
                    and outcome['cleanupConfirmed'] is True, 'worker failed or cleanup unconfirmed')
            admission(args.freeze, args.sha256, args.review, args.review_sha256, deadline)
            report = q.data(attempt / 'probe-report.json', 2 * MIB)
            require(report['status'] == 'DRY_RUN_SELECTION_PASSED_CPP_NOT_EXECUTED'
                    and report['notRunCommands'] == [], 'incomplete probe report')
            policy.validate_complete(report['commands'], attempt)
            check(deadline)
            c.write(attempt / 'accepted-probes.pending.json', dict(freezeSha256=args.sha256,
                    reviewSha256=args.review_sha256, report=c.file_row(attempt / 'probe-report.json'),
                    cleanupConfirmed=True, qualification='DRY_RUN_SELECTION_ONLY',
                    compilerQualified=False, elapsedBeforePublicationSeconds=now() - started), 65536)
            c.write(attempt / 'outcome.pending.json', dict(outcome, freezeSha256=args.sha256,
                    decisionStartedMonotonic=started, decisionDeadlineMonotonic=deadline), 65536)
            for public, source in [(attempt / 'outcome.json', attempt / 'outcome.pending.json'),
                                   (attempt / 'accepted-probes.json', attempt / 'accepted-probes.pending.json')]:
                check(deadline)
                published.append((public, source))  # Track intent before interruptible link creation.
                os.link(source, public)
                check(deadline)
            return 2
        except (ValueError, OSError, KeyError, TypeError, UnicodeError, EOFError,
                KeyboardInterrupt, subprocess.SubprocessError) as error:
            failure = type(error).__name__ + ': ' + str(error)[:2048]
            publication_uncertain = False
            try: q.base.base.revoke_links(published)
            except (ValueError, OSError, KeyboardInterrupt) as revoke:
                publication_uncertain = True
                failure += '; uncertain revocation: ' + str(revoke)[:1024]
            if created:
                confirmed = None if outcome is None else outcome.get('cleanupConfirmed')
                status = ('NOT_APPLICABLE_NO_OWNED_CHILD' if not owner_invoked else
                          ('CONFIRMED' if confirmed is True else 'UNCERTAIN'))
                terminal = dict(classification='PROBES_FAILED_OR_REVOKED', failure=failure,
                    cleanupConfirmed=confirmed, cleanupStatus=status, publicationUncertain=publication_uncertain,
                    workerOutcome=outcome, elapsedSeconds=now() - started, freezeSha256=args.sha256,
                    publicAcceptancePresent=os.path.lexists(attempt / 'accepted-probes.json'))
                destination = attempt / ('publication-failure.json' if os.path.lexists(attempt / 'outcome.json') else 'outcome.json')
                c.write(destination, terminal, 65536)
            print(failure, file=sys.stderr)
            return 1


if __name__ == '__main__':
    raise SystemExit(main())
