#!/usr/bin/env python3
"""Host-only Unicode-escape diagnosis; never changes qualification gates."""
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path

import sentencepiece as spm

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'scripts/diagnostics/mask_replay'))
import run as broker
import grammar_progress

FIXTURES = ROOT / 'planning/fixtures/unicode-boundary-v1'
LOCK_SHA = '50d7cb52f88c1b5187582c45c0207167c1a5e712beaf316746c373da0362a575'
DERIVRE_SHA = '786c7c65c4ef0c7deb05de3005e01991612a8f09fe0844fc0969c68b90468ba8'
MASK_BINARY_SHA = '088d978ea405a2d954efedbc9189a5397ce02a974e9fc35d3f68a7aa0347f995'
REGISTRY = ROOT / 'build/llguidance-mask-replay/cargo-home/registry'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def verify_inputs(folder=FIXTURES):
    raw = (folder / 'investigation-lock.json').read_bytes()
    require(sha(raw) == LOCK_SHA, 'investigation lock changed')
    lock = json.loads(raw)
    for name, digest in lock['files'].items():
        require(sha((folder / name).read_bytes()) == digest,
                'investigation fixture changed: ' + name)
    retained_lock = ROOT / 'planning/fixtures/grammar-progress-v1/preparation-lock.json'
    require(sha(retained_lock.read_bytes()) == lock['retainedCandidateLockSha256'],
            'failed candidate lock changed')
    grammar_progress.verify_fixtures()
    cases = json.loads((folder / 'cases.json').read_text())
    for case in cases['cases']:
        value = json.loads(case['jsonText'])
        require(type(value) is str and value == case['referenceValue']
                and 1 <= len(value) <= 96, 'reference oracle mismatch')
        require(not any(0xd800 <= ord(c) <= 0xdfff for c in value),
                'invalid reference Unicode')
    return cases


def verify_derivre():
    archive = next(REGISTRY.glob('cache/*/derivre-0.3.8.crate'))
    require(sha(archive.read_bytes()) == DERIVRE_SHA, 'derivre archive identity changed')
    source = next(REGISTRY.glob('src/*/derivre-0.3.8'))
    verified = {}
    with tarfile.open(archive, 'r:gz') as package:
        for member in package.getmembers():
            if member.isfile():
                relative = Path(member.name).relative_to('derivre-0.3.8')
                data = package.extractfile(member).read()
                require((source / relative).read_bytes() == data,
                        'derivre materialized source differs: ' + str(relative))
                verified[str(relative)] = sha(data)
    return {'archiveSha256': DERIVRE_SHA, 'materializedFiles': verified}


def prepare(source_workspace, out):
    verify_inputs()
    broker.verify_workspace(source_workspace)
    pins = verify_derivre()
    require(not out.exists(), 'retain prior preparation; select a new output directory')
    out.mkdir(parents=True)
    work = out / 'byte-workspace'
    shutil.copytree(source_workspace / 'workspace', work,
                    ignore=shutil.ignore_patterns('target'))
    cargo = work / 'Cargo.toml'
    cargo.write_bytes(cargo.read_bytes() + b'\n[[bin]]\nname = "unicode-byte-probe"\npath = "unicode_byte_probe.rs"\n')
    shutil.copyfile(Path(__file__).with_name('byte_probe.rs'), work / 'unicode_byte_probe.rs')
    # Same registry identities and features; only a standalone safe Rust bin is added.
    require(broker.parse_lock((work / 'Cargo.lock').read_text()) ==
            broker.parse_lock((source_workspace / 'workspace/Cargo.lock').read_text()),
            'dependency identities changed')
    manifest = {
        'schema': 1, 'scope': 'host-only-source-build-unicode-diagnosis',
        'sourceWorkspace': str(source_workspace),
        'sourcePreparationSha256': sha((source_workspace / 'preparation.json').read_bytes()),
        'investigationLockSha256': LOCK_SHA, 'derivre': pins,
        'sourceFiles': {str(p.relative_to(work)): sha(p.read_bytes())
                        for p in sorted(work.rglob('*')) if p.is_file()},
    }
    (out / 'preparation.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


class AlternateTokenizationUnavailable(ValueError):
    """No exact alternate exists under this diagnostic's declared construction."""


def literal_ids(text, processor):
    require(text.isascii(), 'literal-token alternate is restricted to ASCII JSON spelling')
    ids = [processor.piece_to_id(c) for c in text]
    missing = sorted({c for i, c in zip(ids, text) if processor.id_to_piece(i) != c})
    if missing:
        raise AlternateTokenizationUnavailable('missing literal vocabulary piece: ' + repr(missing))
    if processor.decode(ids) != text:
        raise AlternateTokenizationUnavailable('literal alternate does not roundtrip')
    return ids


def check_reference(cases, schema):
    """Independent JSON/schema oracle. Native rejection never changes this verdict."""
    import jsonschema
    validator = jsonschema.Draft202012Validator(json.loads(schema))
    for case in cases['cases']:
        value = json.loads(case['jsonText'])
        require(value == case['referenceValue'], 'reference value changed')
        validator.validate(value)


def investigate(source_workspace, prepared, out):
    cases = verify_inputs()
    prep = broker.verify_workspace(source_workspace)
    grammar_progress.verify_prepared_metadata(prep, {'eos': 1})
    meta = json.loads((prepared / 'preparation.json').read_text())
    require(meta['investigationLockSha256'] == LOCK_SHA
            and meta['derivre'] == verify_derivre(), 'prepared dependency identity changed')
    require(meta['sourcePreparationSha256'] == sha((source_workspace / 'preparation.json').read_bytes()),
            'source preparation changed')
    for name, digest in meta['sourceFiles'].items():
        require(sha((prepared / 'byte-workspace' / name).read_bytes()) == digest,
                'prepared source changed: ' + name)
    require(sha((prepared / 'byte-workspace/unicode_byte_probe.rs').read_bytes()) ==
            sha(Path(__file__).with_name('byte_probe.rs').read_bytes()), 'probe source copy differs')
    require(not out.exists(), 'retain existing report')
    out.mkdir(parents=True)
    report = {
        'schema': 1, 'scope': 'escaped-unicode-host-boundary-diagnosis', 'status': 'running',
        'investigationLockSha256': LOCK_SHA, 'candidateAcceptance': 'remains-failed',
        'nativeModelExecution': False, 'deviceExecution': False, 'semanticEnablement': False,
        'diagnosticSourceHashes': {p.name: sha(p.read_bytes()) for p in
                                 [Path(__file__), Path(__file__).with_name('byte_probe.rs')]},
        'cases': [],
    }
    try:
        inputs = out / 'inputs'
        inputs.mkdir()
        for path in FIXTURES.iterdir():
            if path.is_file():
                shutil.copyfile(path, inputs / path.name)
        for name in ['raw.vocab', 'decoded.vocab', 'tokenizer.bin']:
            data = (source_workspace / 'assets' / name).read_bytes()
            require(sha(data) == prep['files']['assets/' + name], 'source asset identity changed')
            (inputs / name).write_bytes(data)
        old_binary = source_workspace / 'workspace/target/release/edge-mask-replay'
        require(sha(old_binary.read_bytes()) == MASK_BINARY_SHA,
                'reviewed constraint binary differs')
        byte_binary = prepared / 'byte-workspace/target/release/unicode-byte-probe'
        for source, name in [(old_binary, 'mask-probe'), (byte_binary, 'byte-probe')]:
            (inputs / name).write_bytes(source.read_bytes())
            (inputs / name).chmod(0o700)
        report['inputHashes'] = {p.name: sha(p.read_bytes()) for p in inputs.iterdir() if p.is_file()}
        report['preparationSha256'] = sha((prepared / 'preparation.json').read_bytes())
        processor = spm.SentencePieceProcessor(model_file=str(inputs / 'tokenizer.bin'))
        # Safe Rust only, bounded stdin/output, no child processes inside this probe.
        byte_run = subprocess.run([str(inputs / 'byte-probe')], input=json.dumps(cases).encode(),
                                  capture_output=True, timeout=30, check=False)
        (out / 'byte-probe.stdout.json').write_bytes(byte_run.stdout)
        (out / 'byte-probe.stderr').write_bytes(byte_run.stderr)
        require(byte_run.returncode == 0, 'byte probe failed: ' + str(byte_run.returncode))
        byte_results = json.loads(byte_run.stdout)
        require(len(byte_results['cases']) == len(cases['cases']), 'incomplete byte results')
        for case, observed in zip(cases['cases'], byte_results['cases']):
            require(observed['id'] == case['id'], 'byte case order mismatch')
        report['byteResults'] = byte_results
        report['predictionAgreement'] = all(a['sourcePredictedByteAcceptance'] == b['accepted']
                                           for a, b in zip(cases['cases'], byte_results['cases']))
        schema = (inputs / 'minimal-string.schema.json').read_text()
        check_reference(cases, schema)
        report['referenceValidCases'] = len(cases['cases'])
        command = [str(inputs / 'mask-probe')]

        def one(case_id, schema, text, mode, segmentation, watch):
            try:
                ids = processor.encode(text) if segmentation == 'canonical' else literal_ids(text, processor)
            except AlternateTokenizationUnavailable as error:
                # Not a grammar verdict: no prefix was submitted and no tokens fabricated.
                report['cases'].append({'id': case_id, 'status': 'tokenization-unavailable',
                                        'reason': str(error), 'nativeProbeExecuted': False})
                return
            require(processor.decode(ids) == text, 'reconstructed token bytes differ')
            cfg = {'vocabulary': str(inputs / (mode + '.vocab')), 'eos': 1, 'schema': schema,
                   'prefix': ids, 'watch': watch, 'newlineCounts': [0]}
            result = broker.run_probe(command, cfg, processor, out / case_id)
            record = {'id': case_id, 'status': result['result']['status'], 'prefixTokens': len(ids),
                      'resultPath': case_id + '/result.json',
                      'tokenizerTranscriptSha256': result['tokenizerTranscriptSha256']}
            if result['result']['status'] == 'prefix-rejected':
                position = result['result']['position']
                record.update({'rejectedPosition': position,
                               'rejectedToken': result['result']['token'],
                               'prefixBeforeRejection': processor.decode(ids[:position])})
            else:
                record['mask'] = result['result']['states'][0]['mask']
            report['cases'].append(record)

        for case in cases['cases']:
            segmentations = ['canonical', 'literal-ascii'] if case['literalAsciiAlternative'] else ['canonical']
            for mode in ['raw', 'decoded']:
                for segmentation in segmentations:
                    one(case['id'] + '-' + mode + '-' + segmentation, schema,
                        case['jsonText'], mode, segmentation, [1])
        watch = [processor.piece_to_id(c) for c in '01279dDfF']
        for number, text in enumerate(cases['prefixes']):
            for mode in ['raw', 'decoded']:
                for segmentation in ['canonical', 'literal-ascii']:
                    one('escape-prefix-' + str(number) + '-' + mode + '-' + segmentation,
                        schema, text, mode, segmentation, watch)
        witnesses = ROOT / 'planning/fixtures/grammar-progress-v1/witnesses.json'
        witness = next(w for w in json.loads(witnesses.read_text())['witnesses']
                       if w['id'] == 'unicode-escaped')
        schemas = [
            ('baseline', ROOT / 'planning/fixtures/constrained-decoding-v2/evidence-output.schema.json'),
            ('candidate', ROOT / 'planning/fixtures/grammar-progress-v1/evidence-output.no-whitespace.schema.json'),
        ]
        for label, path in schemas:
            for mode in ['raw', 'decoded']:
                for segmentation in ['canonical', 'literal-ascii']:
                    one('retained-witness-' + label + '-' + mode + '-' + segmentation,
                        path.read_text(), witness['output'], mode, segmentation, [1])
        expected = 2 * sum(2 if c['literalAsciiAlternative'] else 1 for c in cases['cases']) + 12 + 8
        require(len(report['cases']) == expected, 'incomplete CAPI comparisons')
        report['status'] = 'diagnosis-collected'
        return report
    except BaseException as error:
        report['status'] = 'investigation-failed'
        report['error'] = str(error)
        raise
    finally:
        report['files'] = {str(p.relative_to(out)): sha(p.read_bytes())
                           for p in sorted(out.rglob('*')) if p.is_file()}
        (out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['prepare', 'run'])
    parser.add_argument('--workspace', type=Path, default=ROOT / 'build/grammar-progress-v1-final')
    parser.add_argument('--prepared', type=Path, default=ROOT / 'build/unicode-boundary-v1/prepared-final')
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()
    if args.mode == 'prepare':
        value = prepare(args.workspace.resolve(), args.prepared.resolve())
        print(json.dumps({'scope': value['scope'], 'prepared': str(args.prepared),
                          'sourceFiles': len(value['sourceFiles'])}))
    else:
        if args.out is None:
            parser.error('--out is required for run')
        value = investigate(args.workspace.resolve(), args.prepared.resolve(), args.out.resolve())
        print(json.dumps({'status': value['status'], 'probes': len(value['cases']),
                          'candidateAcceptance': value['candidateAcceptance']}))
