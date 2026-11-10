"""Separate V2 diagnostic recipe; importing performs no native dispatch or writes."""
import copy
import importlib.util
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent

def load(name, path):
    spec = importlib.util.spec_from_file_location('parser_bridge_v2_' + name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module

old = load('historical_plan', ROOT / 'scripts/diagnostics/linker_executable_access_v1/plan.py')
q, c, owner, require = old.q, old.c, old.owner, old.require
PROTOCOL = 'linker-parser-v2-diagnostic-bridge-v1'
SCOPE = 'v2-two-synthetic-target-plans-no-real-linker-qualification'
FIX = ROOT / 'planning/fixtures/linker-parser-bridge-v2'
ATTEMPT = ROOT / 'build/linker-executable-access-v1/native-attempts/access-v2-1'
PREPARATION = ROOT / 'build/linker-parser-bridge-v2/marker-receipt-r1.json'
SOURCE = old.SOURCE
CLANG, LD = old.CLANG, old.LD
PARSER = ROOT / 'scripts/diagnostics/linker_parser_v2/parser.py'
PARSER_SHA = 'dc86bf48b3def39bcec4340436554b15b4e31cf45a95b98ee5df05ced4e0b830'
PARSER_LOCK = ROOT / 'planning/fixtures/linker-parser-v2-implementation-v1/implementation-lock-r2.json'
PARSER_LOCK_SHA = 'c1ebf9dc0bca9ce1a1594fe170a39d780071e568f07d50666e643c21478f40b8'
PARSER_QA = ROOT / 'planning/fixtures/linker-parser-v2-implementation-v1/qa-checkpoint-r2.json'


def contract():
    prior = old.contract()
    target = prior['target']['path']
    argv = old.prior.plan.commands(ATTEMPT)[1]['argv']
    argv = [('--ld-path=' + target) if x == old.prior.plan.OPTION else x for x in argv]
    present = q.backend.sandbox(ATTEMPT, [str(CLANG), target])
    absent = old.prior.plan.sandbox(ATTEMPT)
    commands = copy.deepcopy(prior['commands'])
    for job, profile in zip(commands, (present, absent)):
        job.update(argv=list(argv), profile=profile)
    result = copy.deepcopy(prior)
    result.update(attempt=str(ATTEMPT), commands=commands,
                  nativeEnvironment=old.prior.plan.environment(ATTEMPT))
    # Only owned paths are rebased. No argument, option or permission is added.
    rebased = json.loads(json.dumps(prior).replace(str(old.ATTEMPT), str(ATTEMPT)))
    require(result == rebased, 'unexpected bridge recipe delta')
    return result


def parser_review():
    old.bound(PARSER_LOCK, PARSER_LOCK_SHA)
    review = q.data(PARSER_QA)
    require(review['verdict'] == 'PASS' and review['findings'] == []
            and review['implementationLockSha256'] == PARSER_LOCK_SHA
            and review['nativeExecutionAuthorized'] is False, 'missing V2 parser review')
    require(owner.digest(PARSER) == PARSER_SHA, 'reviewed parser changed')


def base_freeze():
    return old.base_freeze()
