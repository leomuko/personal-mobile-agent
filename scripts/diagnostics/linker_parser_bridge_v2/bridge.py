"""Freeze-derived V2 parsing shared by worker and independent parent replay."""
from pathlib import Path
import importlib.util
s = importlib.util.spec_from_file_location('v2_bridge_plan', Path(__file__).with_name('plan.py'))
plan = importlib.util.module_from_spec(s); s.loader.exec_module(plan)
parser = plan.load('parser', plan.PARSER)
require = plan.require


def context(frozen, attempt=None):
    contract = frozen['contract']
    require(contract == plan.contract(), 'bridge contract/context substitution')
    if attempt is not None:
        require(str(attempt) == contract['attempt'], 'bridge attempt/context mismatch')
    # Never derive context from compiler output, worker summaries or ambient cwd.
    return parser.Context(contract['attempt'], contract['target']['path'])


def parse(frozen, job, raw, code):
    ctx = context(frozen)
    jobs = frozen['contract']['commands']
    require(job in jobs and type(job['id']) is str, 'unfrozen bridge job')
    return parser.parse_output(raw, context=ctx, case_id=job['id'], exit_code=code)


def summary(parsed):
    require(type(parsed) is parser.ParsedOutput, 'unparsed bridge result')
    return dict(classification=parsed.classification, exitCode=parsed.raw_exit_code,
                commandStatus=parsed.command_status, terminalStatus=parsed.terminal_status,
                context=dict(attempt=parsed.context.attempt, syntheticTarget=parsed.context.synthetic_target),
                rawSha256=parsed.raw_sha256, policySha256=parsed.policy_sha256,
                realLinkerQualified=False, compilerQualified=False, acceptancePublicationAllowed=False)
