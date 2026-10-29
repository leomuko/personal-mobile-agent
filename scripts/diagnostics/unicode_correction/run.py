#!/usr/bin/env python3
"""One frozen host-only compiler backport. No ADB, model loading or output repair."""
import argparse
import copy
import hashlib
import json
import platform
import re
import shutil
import sys
from pathlib import Path

import sentencepiece as spm
from jsonschema import Draft7Validator

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'scripts/diagnostics/mask_replay'))
import run as broker
import grammar_progress as historical
sys.path.insert(0, str(ROOT / 'scripts/diagnostics/unicode_boundary'))
import investigate as boundary

FIXTURES = ROOT / 'planning/fixtures/grammar-progress-v2-unicode'
PROPOSAL = ROOT / 'planning/fixtures/unicode-correction-proposal-v1/proposal-lock.json'
PROPOSAL_SHA = '099cd96d97ac4646b86e7916fc1db1ee7e09ae9e956d3a97e1dfffaa60edf29c'
LOCK_SHA = '4e5dd4b9bad7e93bdc300363c7d5000b73d14df7ffdf6c548c0f42ea2bbe9277'
HERE = Path(__file__).parent
COMPILER = Path('vendor/llguidance-1.3.0/src/json/compiler.rs')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(ok, message):
    if not ok:
        raise ValueError(message)


def verify_inputs(folder=FIXTURES):
    raw = (folder / 'preparation-lock.json').read_bytes()
    require(sha(raw) == LOCK_SHA, 'correction lock changed')
    lock = json.loads(raw)
    for path, digest in lock['files'].items():
        relative = Path(path)
        prefix = FIXTURES.relative_to(ROOT)
        target = folder / relative.relative_to(prefix) if relative.is_relative_to(prefix) else ROOT / relative
        require(sha(target.read_bytes()) == digest, 'correction input changed: ' + path)
    require(sha(PROPOSAL.read_bytes()) == PROPOSAL_SHA, 'reviewed proposal changed')
    for path, digest in json.loads(PROPOSAL.read_text())['files'].items():
        require(sha((ROOT / path).read_bytes()) == digest, 'reviewed proposal binding changed: ' + path)
    historical.verify_fixtures()
    boundary.verify_inputs()
    for snapshot in ['docs/P2_GRAMMAR_PROGRESS_SOURCE_SNAPSHOT.json',
                     'docs/P2_ESCAPED_UNICODE_BOUNDARY_SOURCE_SNAPSHOT.json']:
        for path, digest in json.loads((ROOT / snapshot).read_text()).items():
            require(sha((ROOT / path).read_bytes()) == digest, 'historical source changed: ' + path)
    for name in ['keyword-sentinel', 'evidence-output']:
        old = json.loads((ROOT / 'planning/fixtures/grammar-progress-v1' /
                          (name + '.no-whitespace.schema.json')).read_text())
        current = json.loads((folder / (name + '.schema.json')).read_text())
        require(current['x-guidance'].pop('json_allow_general_unicode_escapes') is True,
                'missing correction opt-in')
        require(current == old, 'candidate changes semantic schema or whitespace policy')
    return json.loads((folder / 'protocol.json').read_text())


def apply_patch(data, patch):
    """Exact indexed unified hunks; no fuzzy context, path expansion or subprocess."""
    lines = patch.decode().splitlines(keepends=True)
    require(lines[:2] == ['--- ' + str(COMPILER) + '\n', '+++ ' + str(COMPILER) + '\n'],
            'patch touches an unapproved path')
    source = data.decode().splitlines(keepends=True)
    result = []
    cursor = 0
    index = 2
    hunks = 0
    while index < len(lines):
        match = re.fullmatch(r'@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@[^\n]*\n', lines[index])
        require(match is not None, 'malformed patch hunk')
        first = int(match[1]) - 1
        old_count = int(match[2] or 1)
        new_count = int(match[4] or 1)
        require(first >= cursor and first <= len(source), 'overlapping/out-of-bounds patch')
        result.extend(source[cursor:first])
        old, new = [], []
        index += 1
        while index < len(lines) and not lines[index].startswith('@@ '):
            line = lines[index]
            require(line[:1] in [' ', '+', '-'], 'unsupported patch line')
            if line[0] in ' -': old.append(line[1:])
            if line[0] in ' +': new.append(line[1:])
            index += 1
        require(len(old) == old_count and len(new) == new_count, 'patch hunk counts differ')
        require(source[first:first + old_count] == old, 'patch context differs')
        result.extend(new)
        cursor = first + old_count
        hunks += 1
    require(hunks > 0, 'empty patch')
    result.extend(source[cursor:])
    return ''.join(result).encode()


def oracle(case):
    """Independent decoded-value oracle for the opt-in plain-string path."""
    try:
        value = json.loads(case['jsonText'])
    except json.JSONDecodeError:
        return False
    return (type(value) is str and not any(0xd800 <= ord(c) <= 0xdfff for c in value)
            and Draft7Validator(case['schema']).is_valid(value))


def prepare(base, out):
    protocol = verify_inputs()
    old = broker.verify_workspace(base)
    historical.verify_prepared_metadata(old, {'eos': 1})
    require(sha((base / 'preparation.json').read_bytes()) == protocol['basePreparationSha256'], 'unreviewed source preparation')
    derivre = boundary.verify_derivre()
    require(not out.exists(), 'preserve existing preparation')
    out.mkdir(parents=True)
    for name in ['workspace', 'assets', 'downloads']:
        shutil.copytree(base / name, out / name, ignore=shutil.ignore_patterns('target'))
    target = out / 'workspace' / COMPILER
    before = target.read_bytes()
    require(sha(before) == old['files']['workspace/' + str(COMPILER)], 'compiler base differs')
    target.write_bytes(apply_patch(before, (HERE / 'PATCH.unicode-value-strings').read_bytes()))
    shutil.copyfile(HERE / 'compiler_probe.rs', out / 'workspace/compiler_probe.rs')
    cargo = out / 'workspace/Cargo.toml'
    cargo.write_bytes(cargo.read_bytes() + b'\n[[bin]]\nname = "unicode-compiler-probe"\npath = "compiler_probe.rs"\n')
    require((out / 'workspace/Cargo.lock').read_bytes() == (base / 'workspace/Cargo.lock').read_bytes(),
            'Cargo.lock changed')
    meta = {'schema': 1, 'scope': 'host-only-unicode-compiler-candidate', 'base': str(base),
            'basePreparationSha256': sha((base / 'preparation.json').read_bytes()),
            'proposalSha256': PROPOSAL_SHA, 'lockSha256': LOCK_SHA, 'derivre': derivre,
            'sources': {str(p.relative_to(ROOT)): sha(p.read_bytes()) for p in HERE.iterdir() if p.is_file()},
            'files': {str(p.relative_to(out)): sha(p.read_bytes()) for name in ['workspace', 'assets', 'downloads']
                      for p in sorted((out / name).rglob('*')) if p.is_file()}}
    (out / 'preparation.json').write_text(json.dumps(meta, indent=2) + '\n')
    return meta


def verify_prepared(work):
    protocol = verify_inputs()
    meta = json.loads((work / 'preparation.json').read_text())
    require(meta['lockSha256'] == LOCK_SHA and meta['proposalSha256'] == PROPOSAL_SHA, 'prepared identity differs')
    require(meta['derivre'] == boundary.verify_derivre(), 'dependency materialization differs')
    base = Path(meta['base'])
    broker.verify_workspace(base)
    require(meta['basePreparationSha256'] == protocol['basePreparationSha256'] ==
            sha((base / 'preparation.json').read_bytes()), 'base preparation changed')
    expected = apply_patch((base / 'workspace' / COMPILER).read_bytes(), (HERE / 'PATCH.unicode-value-strings').read_bytes())
    require((work / 'workspace' / COMPILER).read_bytes() == expected, 'prepared compiler is not the exact backport')
    suffix = b'\n[[bin]]\nname = "unicode-compiler-probe"\npath = "compiler_probe.rs"\n'
    require((work / 'workspace/Cargo.toml').read_bytes() == (base / 'workspace/Cargo.toml').read_bytes() + suffix,
            'dependency/features or binary scope changed')
    for path, digest in meta['sources'].items():
        require(sha((ROOT / path).read_bytes()) == digest, 'diagnostic source changed: ' + path)
    for path, digest in meta['files'].items():
        require(sha((work / path).read_bytes()) == digest, 'prepared source/input changed: ' + path)
    require((work / 'workspace/Cargo.lock').read_bytes() == (ROOT / 'scripts/diagnostics/mask_replay/Cargo.lock').read_bytes(),
            'registry lock differs')
    return meta


def resources(folder):
    text = (folder / 'stderr.txt').read_text(errors='replace')
    match = re.search(r'^\s*(\d+)\s+maximum resident set size\s*$', text, re.M)
    require(match is not None, 'missing per-process RSS measurement')
    return {'maxRssBytes': int(match[1]), 'rssScope': 'whole owned host process; macOS bytes'}


def campaign(work, out, reference):
    protocol = verify_inputs()
    meta = verify_prepared(work)
    require(sys.platform == 'darwin' and platform.machine() == 'arm64', 'resource harness is qualified for macOS arm64 only')
    require(not out.exists(), 'preserve existing campaign')
    out.mkdir(parents=True)
    report = {'schema': 1, 'protocolId': protocol['protocolId'], 'lockSha256': LOCK_SHA,
              'status': 'running', 'deviceExecution': False, 'nativeModelExecution': False,
              'semanticEnablement': False, 'historicalCandidate': 'remains-failed-39-of-41',
              'byteCases': [], 'compatibility': [], 'original41': [], 'resources': []}
    try:
        inputs = out / 'inputs'; inputs.mkdir()
        for path in FIXTURES.iterdir():
            if path.is_file(): shutil.copyfile(path, inputs / path.name)
        for name in ['raw.vocab', 'decoded.vocab', 'tokenizer.bin', 'prefix.txt']:
            shutil.copyfile(work / 'assets' / name, inputs / name)
        for name in ['edge-mask-replay', 'unicode-compiler-probe']:
            shutil.copyfile(work / 'workspace/target/release' / name, inputs / name)
            (inputs / name).chmod(0o700)
        report['inputHashes'] = {p.name: sha(p.read_bytes()) for p in inputs.iterdir() if p.is_file()}
        report['preparationSha256'] = sha((work / 'preparation.json').read_bytes())
        processor = spm.SentencePieceProcessor(model_file=str(inputs / 'tokenizer.bin'))
        command = ['/usr/bin/time', '-l', str(inputs / 'edge-mask-replay')]
        def call(case_id, cfg, byte=False):
            folder = out / case_id
            cmd = ['/usr/bin/time', '-l', str(inputs / 'unicode-compiler-probe')] if byte else command
            try:
                result = broker.run_probe(cmd, cfg, None if byte else processor, folder)
            except BaseException:
                try:
                    sample = resources(folder)
                except (OSError, ValueError):
                    sample = {'maxRssBytes': None, 'rssUnavailable': 'failed/interrupted process produced no complete time record'}
                report['resources'].append({'id': case_id, 'failedProbe': True, **sample})
                raise
            report['resources'].append({'id': case_id, 'elapsedMs': result['elapsedMs'], **resources(folder)})
            return result
        # Old six reconstructed prefixes + sixteen masks, with opt-in absent and explicit false.
        old = json.loads((reference / 'report.json').read_text())
        require(sha((reference / 'report.json').read_bytes()) == protocol['baselineReportSha256'], 'unreviewed baseline reference')
        for name, digest in old['files'].items():
            require(sha((reference / name).read_bytes()) == digest, 'baseline evidence changed: ' + name)
        require(len(old['cases']) == 6, 'incomplete baseline reference')
        for explicit in [False, True]:
            masks = 0
            for case in old['cases']:
                original = json.loads((reference / case['path']).read_text())
                cfg = copy.deepcopy(original['config'])
                cfg['vocabulary'] = str(inputs / Path(cfg['vocabulary']).name)
                if explicit:
                    config = json.loads(cfg['schema'])
                    config.setdefault('x-guidance', {})['json_allow_general_unicode_escapes'] = False
                    cfg['schema'] = json.dumps(config, separators=(',', ':'))
                result = call('compat-' + str(explicit) + '-' + case['id'], cfg)
                require(result['result']['status'] == original['result']['status'], 'default-off status differs')
                require(result['tokenizerTranscriptSha256'] == original['tokenizerTranscriptSha256'], 'default-off transcript differs')
                ours = sorted((out / ('compat-' + str(explicit) + '-' + case['id'])).glob('mask-*.bin'))
                retained = sorted((reference / case['id']).glob('mask-*.bin'))
                require([p.name for p in ours] == [p.name for p in retained], 'default-off mask set differs')
                require(all(a.read_bytes() == b.read_bytes() for a, b in zip(ours, retained)), 'default-off masks differ')
                masks += len(ours)
            require(masks == 16, 'missing default-off masks')
            report['compatibility'].append({'explicitFalse': explicit, 'masksMatched': masks, 'transcriptsMatched': 6})
        cases = json.loads((inputs / 'compiler-cases.json').read_text())['cases']
        for offset in range(0, len(cases), 64):
            batch = cases[offset:offset + 64]
            for case in batch:
                if case['kind'] in ['unicode', 'retained-valid', 'malformed']:
                    require(oracle(case) == case['expected'], 'frozen reference oracle differs: ' + case['id'])
            result = call('bytes-' + str(offset // 64), {'cases': batch}, byte=True)
            observed = result['result']['cases']
            require(len(observed) == len(batch), 'incomplete compiler batch')
            for case, value in zip(batch, observed):
                require(case['id'] == value['id'], 'compiler batch identity differs')
                actual = 'compile-error' if value['status'] == 'compile-error' else value['accepted']
                passed = actual == case['expected'] and type(actual) is type(case['expected'])
                if case['expected'] == 'compile-error':
                    passed = passed and case['expectedErrorContains'] in value.get('error', '')
                report['byteCases'].append({**value, 'expected': case['expected'], 'passed': passed})
            require(all(row['passed'] for row in report['byteCases']), 'compiler value/length gate failed')
        original_gate(inputs, processor, call, protocol, report['original41'])
        require([c['id'] for c in report['original41']] == protocol['originalCaseIds'], 'original case inventory changed')
        require(len(report['original41']) == 41 and all(c['accepted'] for c in report['original41']), 'original 41-case gate failed')
        report['status'] = 'passed-host-correction-candidate'
        return report
    except BaseException as error:
        report['status'] = 'failed-host-correction'
        report['error'] = str(error)
        raise
    finally:
        report['files'] = {str(p.relative_to(out)): sha(p.read_bytes()) for p in sorted(out.rglob('*')) if p.is_file()}
        (out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')


def original_gate(inputs, processor, call, protocol, results):
    old = historical.verify_fixtures()
    def probe(case_id, schema, text, mode='raw', counts=None, watch=None):
        ids = processor.encode(text)
        require(processor.decode(ids) == text, 'reconstructed prefix differs')
        return call(case_id, {'vocabulary': str(inputs / (mode + '.vocab')), 'eos': 1, 'schema': schema,
                             'prefix': ids, 'watch': watch or old['numericBoundaryWatch'],
                             'newlineCounts': [0] if counts is None else counts})
    def record(case_id, result, accepted):
        results.append({'id': case_id, 'status': result['result']['status'], 'accepted': bool(accepted)})
    baseline = (ROOT / 'planning/fixtures/constrained-decoding-v2/keyword-sentinel.schema.json').read_text()
    candidate = (inputs / 'keyword-sentinel.schema.json').read_text()
    compact = (ROOT / 'planning/fixtures/grammar-progress-v1/compact-prefix.txt').read_text()
    original = (inputs / 'prefix.txt').read_text()
    allowed = historical.mask_allowed
    for mode in ['raw', 'decoded']:
        case_id = mode + '-baseline-compact';r = probe(case_id, baseline, compact, mode, old['newlineCheckpoints'])
        record(case_id, r, r['result']['status'] == 'replayed' and all(allowed(r, 107, i) and allowed(r, 236771, i) for i in range(6)))
        case_id = mode + '-candidate-compact';r = probe(case_id, candidate, compact, mode)
        record(case_id, r, r['result']['status'] == 'replayed' and allowed(r, 236771) and all(not allowed(r, n) for n in [107, 108, 236743, 236770, 1]))
        case_id = mode + '-candidate-zero';r = probe(case_id, candidate, compact + '0', mode)
        record(case_id, r, r['result']['status'] == 'replayed' and not allowed(r, 236771))
        case_id = mode + '-candidate-newline-consumption';r = probe(case_id, candidate, compact, mode, [0, 1])
        record(case_id, r, r['result']['status'] == 'newline-rejected' and r['result']['after'] == 0)
        case_id = mode + '-candidate-original-prefix';r = probe(case_id, candidate, original, mode)
        record(case_id, r, r['result']['status'] == 'prefix-rejected')
    full = (inputs / 'evidence-output.schema.json').read_text()
    witnesses = json.loads((ROOT / 'planning/fixtures/grammar-progress-v1/witnesses.json').read_text())['witnesses']
    for witness in witnesses:
        Draft7Validator(json.loads(full)).validate(json.loads(witness['output']))
        for mode in ['raw', 'decoded']:
            case_id = mode + '-witness-' + witness['id'];r = probe(case_id, full, witness['output'], mode, watch=[1])
            record(case_id, r, r['result']['status'] == 'replayed' and allowed(r, 1))
    for bad in old['malformedOptionChecks']:
        case_id = 'negative-' + bad['id'];config = json.loads(candidate);config['x-guidance'] = bad['value']
        try:
            probe(case_id, json.dumps(config, separators=(',', ':')), compact)
        except broker.NativeDiagnosticError as error:
            require(bad['errorContains'] in str(error), 'unidentified native option rejection')
            results.append({'id': case_id, 'status': 'identified-native-option-rejection', 'accepted': True, 'error': str(error)})
        else:
            raise ValueError('unsupported option accepted')
    return results


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['prepare', 'run'])
    parser.add_argument('--base', type=Path, default=ROOT / 'build/grammar-progress-v1-final')
    parser.add_argument('--workspace', type=Path, default=ROOT / 'build/unicode-correction-v1/prepared')
    parser.add_argument('--reference', type=Path, default=ROOT / 'build/llguidance-mask-replay/host-final-locked')
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()
    if args.mode == 'prepare':
        value = prepare(args.base.resolve(), args.workspace.resolve())
        print(json.dumps({'scope': value['scope'], 'lockSha256': value['lockSha256']}))
    else:
        if args.out is None: parser.error('--out is required')
        value = campaign(args.workspace.resolve(), args.out.resolve(), args.reference.resolve())
        print(json.dumps({'status': value['status'], 'byteChecks': len(value['byteCases']), 'originalChecks': len(value['original41'])}))
