"""Offline structural parser for one sealed Apple driver-output contract.

No native runner, bridge, ownership authority, lease check or publisher exists
here. Context is caller-supplied lexical data, not proof of a reviewed execution
freeze. All results are non-qualifying, including the retained recorded capture.
"""
from dataclasses import dataclass, field
from functools import lru_cache
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import stat

ROOT = Path(__file__).resolve().parents[3]
CONTRACT_PATH = ROOT / 'planning/fixtures/linker-parser-contract-v2/contract-proposal.json'
CONTRACT_SHA256 = 'dffedbf0ab134215a41df3cb3c64de715148282063fcc203156979085a8a46b4'
PROPOSAL_LOCK_SHA256 = '2e22395071ab2574325b74b2f286cd59974c28ee3467681420ec3fc3e56a540f'
PRESENT = 'synthetic-exception-present'
ABSENT = 'synthetic-exception-absent'
SELECTED = 'EXACT_SYNTHETIC_PATH_SELECTED'
REJECTED = 'EXACT_SYNTHETIC_PATH_REJECTED'
OBJECT_BASENAME = r'empty-[A-Za-z0-9_-]{1,64}\.o'


class ParseError(ValueError):
    """Bounded, controlled rejection of context, contract or transcript."""


def _require(value, message):
    if not value:
        raise ParseError(message)


def _canonical_path(value):
    _require(type(value) is str and 0 < len(value) <= 4096, 'invalid path type/length')
    try:
        encoded = value.encode('utf-8', errors='strict')
    except UnicodeError as error:
        raise ParseError('invalid path Unicode') from error
    _require(len(encoded) <= 4096, 'path byte ceiling')
    _require(value.startswith('/') and not value.startswith('//') and '\\' not in value,
             'noncanonical absolute path')
    _require(not any(ord(c) < 32 or ord(c) == 127 for c in value), 'path control character')
    _require(all(p not in ('', '.', '..') for p in value.split('/')[1:]), 'path alias component')
    _require(str(PurePosixPath(value)) == value, 'path lexical normalization forbidden')
    return value


@dataclass(frozen=True)
class _Atom:
    literal: str = None
    slot: str = None
    prefix: str = ''
    suffix: str = ''

    def expand(self, bindings):
        return self.literal if self.literal is not None else self.prefix + bindings[self.slot] + self.suffix


@dataclass(frozen=True)
class _Policy:
    header: tuple
    frontend: tuple
    linker: tuple
    object_index: int
    attempt_parent: str
    target: str
    maximum: int


def _atom(value):
    _require(type(value) is dict, 'invalid contract atom')
    if set(value) == {'literal'}:
        _require(type(value['literal']) is str, 'invalid literal')
        return _Atom(literal=value['literal'])
    _require(set(value) <= {'slot', 'prefix', 'suffix'} and 'slot' in value,
             'invalid contract slot shape')
    _require(value['slot'] in ('ATTEMPT', 'SOURCE', 'OUTPUT', 'OBJECT', 'SYNTHETIC_TARGET'),
             'unknown contract slot')
    _require(all(type(value.get(k, '')) is str for k in ('prefix', 'suffix')), 'invalid affix')
    return _Atom(slot=value['slot'], prefix=value.get('prefix', ''), suffix=value.get('suffix', ''))


def _atoms(section, count):
    result = []
    for unit in section['orderedUnits']:
        _require(unit['tokenStart'] == len(result) and unit['tokenCount'] == len(unit['exactTokens']),
                 'contract unit gap/overlap')
        result.extend(_atom(a) for a in unit['exactTokens'])
    _require(len(result) == section['tokenCount'] == count, 'contract token count')
    return tuple(result)


@lru_cache(maxsize=1)
def _policy():
    """Load once into immutable atoms. Disk changes cannot change cached policy."""
    try:
        with os.fdopen(os.open(CONTRACT_PATH, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as source:
            before = os.fstat(source.fileno())
            _require(stat.S_ISREG(before.st_mode) and before.st_size <= 128 * 1024,
                     'contract type/size')
            raw = source.read(128 * 1024 + 1)
            after = os.fstat(source.fileno())
        identity = lambda v: (v.st_dev, v.st_ino, v.st_mode, v.st_size, v.st_mtime_ns, v.st_ctime_ns)
        _require(identity(before) == identity(after) == identity(CONTRACT_PATH.lstat()), 'contract read drift')
        _require(len(raw) <= 128 * 1024 and hashlib.sha256(raw).hexdigest() == CONTRACT_SHA256,
                 'sealed contract hash mismatch')
        value = json.loads(raw.decode('utf-8', errors='strict'))
        _require(value['id'] == 'pinned-apple-driver-output-contract-v2-draft-1', 'wrong contract')
        _require(value['lexer']['outputBytes'] == 262144, 'contract output limit')
        _require(value['slots']['OBJECT']['basenameRegex'] == OBJECT_BASENAME, 'object rule changed')
        front = _atoms(value['frontend'], 155)
        link = _atoms(value['linkerPositive'], 24)
        indices = [i for i, a in enumerate(front) if a.slot == 'OBJECT']
        _require(len(indices) == 1 and sum(a.slot == 'OBJECT' for a in link) == 1, 'object slot scope')
        sample = value['sampleBindings']
        return _Policy(tuple(value['headerExact']), front, link, indices[0],
                       str(PurePosixPath(sample['ATTEMPT']).parent), sample['SYNTHETIC_TARGET'], 262144)
    except ParseError:
        raise
    except (OSError, ValueError, KeyError, TypeError, UnicodeError) as error:
        raise ParseError('unavailable/invalid sealed contract: ' + type(error).__name__) from error


def _validate_context(context, policy):
    _require(type(context) is Context, 'context type must be exact Context')
    attempt = _canonical_path(context.attempt)
    target = _canonical_path(context.synthetic_target)
    path = PurePosixPath(attempt)
    _require(str(path.parent) == policy.attempt_parent
             and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,63}', path.name) is not None,
             'attempt outside narrowly allowed context parent/name')
    _require(target == policy.target, 'synthetic target differs from sealed identity path')


@dataclass(frozen=True)
class Context:
    attempt: str
    synthetic_target: str

    def __post_init__(self):
        _validate_context(self, _policy())


@dataclass(frozen=True)
class ParsedOutput:
    context: Context
    case_id: str
    raw_exit_code: int
    classification: str
    command_status: str
    terminal_status: str
    raw_sha256: str
    object_path: str
    frontend_tokens: tuple
    linker_tokens: tuple
    scope: str = field(default='OFFLINE_STRUCTURAL_ONLY', init=False)
    policy_sha256: str = field(default=CONTRACT_SHA256, init=False)
    real_linker_qualified: bool = field(default=False, init=False)
    compiler_qualified: bool = field(default=False, init=False)
    acceptance_publication_allowed: bool = field(default=False, init=False)


def _match_jobs(frontend, linker, context, negative, policy):
    _require(type(frontend) is tuple and type(linker) is tuple, 'job tuple type')
    _require(len(frontend) == len(policy.frontend) and len(linker) == len(policy.linker),
             'job word count')
    object_path = _canonical_path(frontend[policy.object_index])
    obj = PurePosixPath(object_path)
    _require(str(obj.parent) == context.attempt and re.fullmatch(OBJECT_BASENAME, obj.name) is not None,
             'object outside attempt/basename bounds')
    bindings = dict(ATTEMPT=context.attempt, SOURCE=context.attempt + '/empty.cpp',
                    OUTPUT=context.attempt + '/control-ordinary-c++20.not-created',
                    OBJECT=object_path, SYNTHETIC_TARGET=context.synthetic_target)
    expected_front = tuple(a.expand(bindings) for a in policy.frontend)
    expected_link = tuple(a.expand(bindings) for a in policy.linker)
    if negative:
        expected_link = ('ld',) + expected_link[1:]
    _require(frontend == expected_front, 'frontend ordered contract mismatch')
    _require(linker == expected_link, 'linker ordered contract mismatch')
    return object_path


def parse_output(raw, *, context, case_id, exit_code):
    """Check every decoded word; preserve raw identity and failed negative status."""
    policy = _policy()
    _validate_context(context, policy)
    _require(type(case_id) is str and case_id in (PRESENT, ABSENT), 'unknown diagnostic case')
    _require(type(exit_code) is int and exit_code in (0, 1), 'unexpected exit type/value')
    _require(type(raw) is bytes and 0 < len(raw) <= policy.maximum, 'raw byte type/ceiling')
    _require(raw.endswith(b'\n'), 'missing final LF')
    try:
        text = raw.decode('utf-8', errors='strict')
    except UnicodeError as error:
        raise ParseError('invalid UTF-8') from error
    _require(not any((ord(c) < 32 and c not in '\n\t') or ord(c) == 127 for c in text),
             'forbidden output control/line ending')
    records = [line for line in text.split('\n')[:-1] if line.strip(' \t')]
    diagnostic = "clang: error: invalid linker name in argument '--ld-path=" + context.synthetic_target + "'"
    if exit_code == 1:
        _require(records.count(diagnostic) == 1, 'missing/duplicate exact negative diagnostic')
        # Consume as typed error only in a parsing view; raw bytes remain unchanged.
        records = [line for line in records if line != diagnostic]
    else:
        _require(diagnostic not in records, 'negative diagnostic with exit0')
    _require(len(records) == 6 and tuple(records[:4]) == policy.header, 'header/job record structure')
    try:
        frontend = tuple(shlex.split(records[4], comments=False, posix=True))
        linker = tuple(shlex.split(records[5], comments=False, posix=True))
    except ValueError as error:
        raise ParseError('malformed POSIX job quoting') from error
    object_path = _match_jobs(frontend, linker, context, exit_code == 1, policy)
    negative = exit_code == 1
    return ParsedOutput(context, case_id, exit_code, REJECTED if negative else SELECTED,
                        'FAILED' if negative else 'PASSED',
                        'FAILED' if negative else 'OFFLINE_STRUCTURAL_COMPATIBLE',
                        hashlib.sha256(raw).hexdigest(), object_path, frontend, linker)


def compare_outputs(results):
    """Diagnostic relation only; never an admission, credential or publication."""
    _require(type(results) in (tuple, list) and len(results) <= 2, 'comparison type/count')
    for i, result in enumerate(results):
        _require(type(result) is ParsedOutput and result.case_id == (PRESENT, ABSENT)[i],
                 'comparison order/type/duplicate')
        _validate_context(result.context, _policy())
        _require(result.policy_sha256 == CONTRACT_SHA256 and result.scope == 'OFFLINE_STRUCTURAL_ONLY'
                 and result.real_linker_qualified is False and result.compiler_qualified is False
                 and result.acceptance_publication_allowed is False, 'nonqualifying result boundary')
        negative = result.raw_exit_code == 1
        _require(type(result.raw_exit_code) is int and result.raw_exit_code in (0, 1)
                 and result.classification == (REJECTED if negative else SELECTED)
                 and result.command_status == ('FAILED' if negative else 'PASSED')
                 and result.terminal_status == ('FAILED' if negative else 'OFFLINE_STRUCTURAL_COMPATIBLE'),
                 'result classification/status mismatch')
        _require(result.object_path == _match_jobs(result.frontend_tokens, result.linker_tokens,
                result.context, negative, _policy()), 'result object identity mismatch')
        _require(type(result.raw_sha256) is str and re.fullmatch('[0-9a-f]{64}', result.raw_sha256),
                 'result digest shape')
        if i:
            _require(result.context == results[0].context and results[0].raw_exit_code == 0,
                     'context replacement or second result after first failure')
    if len(results) != 2:
        return 'INCONCLUSIVE'
    return ('SUPPORTS_POLICY_SENSITIVE_VALIDATION_ONLY' if results[1].raw_exit_code == 1 else
            'NO_SUPPORT_FOR_POLICY_COUPLING_IN_THIS_COMPARISON')
