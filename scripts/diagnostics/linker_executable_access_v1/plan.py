"""Exact separate diagnostic recipe. Importing never prepares inputs or launches tools."""
import copy
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent

def load(name, path):
    spec = importlib.util.spec_from_file_location('exec_access_' + name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value

prior = load('prior', ROOT / 'scripts/diagnostics/dawn_cpp_linker_launch_v1/qualify.py')
q, c, owner, require = prior.q, prior.c, prior.owner, prior.require
PROTOCOL = 'linker-executable-access-comparison-v1'
SCOPE = 'two-synthetic-target-dry-runs-no-real-linker-qualification'
FIX = ROOT / 'planning/fixtures/linker-executable-access-v1'
BASE = ROOT / 'planning/fixtures/dawn-cpp-linker-launch-v1/execution-freeze-r1.json'
BASE_SHA = 'cd30922e6d7d250de7e6af5a17fe2baa6e94b70e42858bbf589569db7a04a812'
PROPOSAL = ROOT / 'planning/fixtures/linker-path-validation-v1/proposal.json'
PROPOSAL_SHA = 'c567fa1d4030408681615eddf5dea76db8049d3f9019a41007188c7db7d731a9'
STATIC_LOCK = ROOT / 'planning/fixtures/linker-path-validation-v1/static-result-lock.json'
STATIC_SHA = 'f6a675e99932afd872b06e2f3ff6620fe75400c50775488007bf80e6c2231801'
QA = ROOT / 'planning/fixtures/linker-path-validation-v1/qa-checkpoint.json'
CLANG, LD = prior.plan.CLANG, prior.plan.LD
ATTEMPT = ROOT / 'build/linker-executable-access-v1/native-attempts/access-1'
PREPARATION = ROOT / 'build/linker-executable-access-v1/marker-receipt.json'
SOURCE = b'// Synthetic empty driver-plan input. No compilation authorized.\n'


def bound(path, sha):
    return prior.preparation.bound(path, sha)


def proposal():
    value = bound(PROPOSAL, PROPOSAL_SHA)
    seal = bound(STATIC_LOCK, STATIC_SHA)
    review = q.data(QA)
    require(review['verdict'] == 'PASS' and review['producerLockSha256'] == STATIC_SHA
            and review['implementationApproved'] is False
            and review['nativeExecutionApproved'] is False, 'missing static proposal review')
    for record in (seal, review):
        for row in record['bindings']:
            observed = c.file_row(ROOT / row['path'])
            require(observed['bytes'] == row['bytes'] and observed['sha256'] == row['sha256'],
                    'changed static/review evidence: ' + row['path'])
    return value


def contract():
    value = proposal()
    baseline = bound(BASE, BASE_SHA)
    original = prior.plan.commands(ATTEMPT)[1]['argv']
    target = value['target']['path']
    expected = [('--ld-path=' + target) if x == prior.plan.OPTION else x for x in original]
    require(value['cases'][0]['argv'] == value['cases'][1]['argv'] == expected,
            'changed synthetic argv or target substitution')
    absent = prior.plan.sandbox(ATTEMPT)
    present = q.backend.sandbox(ATTEMPT, [str(CLANG), target])
    require(value['cases'][0]['profile'] == present and value['cases'][1]['profile'] == absent
            and value['nativeEnvironment'] == prior.plan.environment(ATTEMPT), 'changed diagnostic profiles/environment')
    result = copy.deepcopy(baseline['contract'])
    for key in ('commands', 'commandCount', 'compilerSandbox', 'allowedExecutablePaths', 'declaredLinker', 'option'):
        result.pop(key)
    target_spec=copy.deepcopy(value['target'])
    target_spec.pop('created')  # Historical proposal state is not current preparation state.
    result.update(attempt=str(ATTEMPT), commands=value['cases'], nativeEnvironment=value['nativeEnvironment'],
                  target=target_spec, source=SOURCE.decode(), commandCount=2,
                  acceptancePublicationAllowed=False, realLinkerExecutionAllowed=False)
    require(all(result[k] == v for k,v in value['limits'].items()), 'changed diagnostic limits')
    return result


def base_freeze():
    return bound(BASE, BASE_SHA)
