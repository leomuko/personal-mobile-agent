"""Candidate output contract; preserves every historical validator byte."""
import importlib.util
from pathlib import Path
import shlex

spec = importlib.util.spec_from_file_location('linker_policy_plan', Path(__file__).with_name('plan.py'))
plan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(plan)
require = plan.prior.require


def validate(job, text):
    plan.prior.policy.reject_diagnostics(text)
    if job['kind'] == 'diagnostic-program-lookup':
        require(job.get('qualifiesLinker') is False, 'lookup cannot qualify linker selection')
        require(text in ('ld\n', str(plan.LD) + '\n'), 'unexpected diagnostic lookup output')
        return dict(observed=text[:-1], qualified=False,
                    classification='BARE_NAME_UNRESOLVED' if text == 'ld\n' else 'ABSOLUTE_LOOKUP_ONLY')
    require(job['kind'] == 'driver-jobs' and job.get('declaredLinker') == str(plan.LD),
            'missing pinned linker declaration')
    require(job['argv'].count(plan.OPTION) == 1 and job['argv'].count('-###') == 1,
            'missing explicit selection or dry-run boundary')
    require([x for x in job['argv'] if x.startswith('--ld-path=')] == [plan.OPTION]
            and not any(x.startswith('-fuse-ld') for x in job['argv']), 'conflicting linker selection')
    # Before the old closed grammar, require literal absolute job identities.
    # No relative-name resolution, symlink normalization or PATH interpretation.
    jobs = [shlex.split(line.strip()) for line in text.splitlines() if line.strip().startswith('"')]
    require(len(jobs) == 2 and jobs[0] and jobs[1], 'unexpected dry-run job count')
    require(jobs[0][0] == str(plan.CLANG) and jobs[1][0] == str(plan.LD),
            'ambiguous or unexpected compiler/linker supplier')
    result = plan.prior.validate_output(job, text, {})
    return dict(declaredLinker=str(plan.LD), selectedLinker=jobs[1][0],
                fullHistoricalContract=result, linkerExecuted=False)


def validate_complete(results, attempt=plan.ATTEMPT):
    expected = plan.commands(attempt)
    require(len(results) == len(expected) and [x['id'] for x in results] == [x['id'] for x in expected],
            'incomplete, duplicate or reordered probe results')
    require(all(x.get('status') == 'PASSED' for x in results), 'failed or unfinished probe result')
    require(results[0]['result']['qualified'] is False, 'lookup falsely promoted to qualification')
    for row in results[1:]:
        require(row['result']['declaredLinker'] == row['result']['selectedLinker'] == str(plan.LD)
                and row['result']['linkerExecuted'] is False, 'linker declaration/selection disagreement')
    return dict(status='EXPLICIT_DRY_RUN_SELECTION_ONLY', compilerQualified=False,
                rootCause='UNPROVED', historicalFailureRepaired=False)
