#!/usr/bin/env python3
"""Discover pinned retained inputs and freeze preparation; no downloads/execution."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
FIX = ROOT / 'planning/fixtures/repository-cache-v1'
BUILD = ROOT / 'build/repository-cache-v1'


def sha(p):
    h = hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda: f.read(1024 * 1024), b''): h.update(b)
    return h.hexdigest()


def row(p):
    return {'path': str(p), 'bytes': p.stat().st_size, 'sha256': sha(p)}


def write(p, value):
    with p.open('x') as f: json.dump(value, f, indent=2); f.write('\n')


def discover():
    FIX.mkdir(parents=True, exist_ok=True); BUILD.mkdir(parents=True, exist_ok=True)
    cache = ROOT / 'build/bazel-server-jvm-v1/isolated-repository-cache/content_addressable/sha256'
    ext = next((ROOT / 'build/bazel-server-jvm-v1/attempts/configured-1/x86_64-output').glob('*/external'))
    sources = {}; hashes = {p.parent.name for p in cache.glob('*/file')}
    refs = {h: [] for h in hashes}
    for top in [ROOT / 'build/ml-drift-compatibility-v1/sdk-full', ext]:
        for p in sorted(top.rglob('*')):
            if not p.is_file() or p.is_symlink() or p.stat().st_size > 8 * 1024**2: continue
            if p.suffix not in ['.bzl', '.bazel', '.json'] and p.name not in ['WORKSPACE', 'BUILD', 'Cargo.lock']: continue
            text = p.read_text(errors='replace')
            for h in set(re.findall('[a-f0-9]{64}', text)) & hashes:
                if not refs[h]:
                    ref = {'path': str(p), 'sha256': sha(p), 'line': text[:text.index(h)].count('\n') + 1,
                           'meaning': 'pinned checksum declaration; not configured-selection proof'}
                    refs[h].append(ref); sources[str(p)] = row(p)
    entries = []
    for h in sorted(hashes):
        p = cache / h / 'file'
        if p.is_symlink() or not refs[h] or sha(p) != h: raise ValueError('unbound/corrupt seed: ' + h)
        markers = []
        for marker in sorted(p.parent.glob('id-*')):
            if marker.stat().st_size != 0 or marker.is_symlink(): raise ValueError('unexpected canonical marker')
            markers.append({'name': marker.name, 'source': str(marker), 'bytes': 0, 'sha256': sha(marker)})
        entries.append({'sha256': h, 'kind': 'retained-cache', 'source': str(p),
                        'maxBytes': p.stat().st_size, 'markers': markers, 'references': refs[h]})
    known = ext / 'rules_rust/rust/known_shas.bzl'; text = known.read_text(); sources[str(known)] = row(known)
    names = [n + '-1.86.0-aarch64-apple-darwin.tar.xz' for n in
             ['rustc', 'cargo', 'clippy', 'rustfmt', 'llvm-tools', 'rust-std']]
    names += ['rust-std-1.86.0-' + abi + '-linux-android.tar.xz' for abi in ['aarch64', 'x86_64']]
    for name in names:
        match = re.search('"' + re.escape(name) + '": "([a-f0-9]{64})"', text)
        if not match: raise ValueError('missing Rust component pin: ' + name)
        h = match.group(1)
        if h in hashes: continue
        entries.append({'sha256': h, 'kind': 'download', 'url': 'https://static.rust-lang.org/dist/' + name,
                        'maxBytes': 512 * 1024**2, 'markers': [],
                        'references': [{'path': str(known), 'sha256': sha(known),
                                        'line': text[:match.start()].count('\n') + 1,
                                        'meaning': 'rules_rust 0.61.0 exact component checksum'}]})
    manifest = {'protocol': 'repository-archive-cache-v1', 'entries': entries,
                'cacheState': '37 retained complete payloads, plus explicitly pinned missing Rust components',
                'transformations': 'NONE; no extracted repository trees imported'}
    write(FIX / 'inputs.json', manifest)
    inputs = list(sources.values()) + [row(p) for p in sorted(HERE.glob('*.py'))]
    inputs += [row(ROOT / 'scripts/tests/test_repository_cache.py'),
               row(ROOT / 'scripts/diagnostics/ml_drift_compatibility/analyze.py'),
               row(ROOT / 'planning/P2_REPOSITORY_CACHE_PREPARATION_V1.md'),
               row(ROOT / 'docs/P2_REPOSITORY_SETUP_FINDINGS_V1.md'), row(FIX / 'inputs.json')]
    python = Path(sys.executable).resolve(); inputs.append(row(python))
    for old in ['bazel-server-jvm-v1/result-lock.json', 'bazel-server-jvm-v1/analysis-freeze.json',
                'bazel-server-jvm-v1/results-review.json']:
        inputs.append(row(ROOT / 'planning/fixtures' / old))
    f = {'protocol': 'repository-archive-cache-v1', 'manifest': str(FIX / 'inputs.json'),
         'manifestSha256': sha(FIX / 'inputs.json'), 'inputs': inputs,
         'implementationSha256': sha(HERE / 'prepare.py'), 'python': str(python),
         'pythonVersion': sys.version, 'perEntrySeconds': 180, 'totalSeconds': 900,
         'cleanupSeconds': 15, 'logLimitBytes': 2 * 1024**2,
         'attemptParent': str(BUILD / 'preparation-attempts'),
         'environment': {'PATH': '/usr/bin:/bin:/usr/sbin:/sbin', 'LANG': 'C', 'LC_ALL': 'C',
                         'PYTHONPYCACHEPREFIX': '/tmp/edge-repository-cache-pycache'}}
    write(FIX / 'preparation-freeze.json', f)
    print('Preparation freeze:', sha(FIX / 'preparation-freeze.json'))


def analysis(prepared):
    previous = ROOT / 'planning/fixtures/bazel-server-jvm-v1/analysis-freeze.json'
    f = json.loads(previous.read_text())
    report = prepared / 'report.json'; receipt = prepared / 'receipt.json'
    if not json.loads(report.read_text())['completed']: raise ValueError('preparation incomplete')
    preparation_freeze = next(p for p in FIX.glob('preparation-freeze*.json')
                              if sha(p) == json.loads(report.read_text())['freezeSha256'])
    paths = [previous, preparation_freeze, FIX / 'inputs.json', report, receipt,
             FIX / 'preparation-review.json', FIX / 'preparation-results-review.json',
             ROOT / 'scripts/tests/test_repository_cache.py'] + sorted(HERE.glob('*.py'))
    inputs = {r['path']: r for r in f['inputs']}; inputs.update({str(p): row(p) for p in paths})
    f.update(cacheAnalysisProtocol='repository-archive-cache-v1', cacheRunnerSha256=sha(HERE / 'analyze.py'),
             cacheSeed=str(prepared / 'accepted-cache'), cacheReceipt=str(receipt),
             cacheManifest=str(FIX / 'inputs.json'), preparationReport=str(report),
             preparationFreeze=str(preparation_freeze),
             repositoryCache=str(BUILD / 'analysis-working-cache-1'),
             attemptParent=str(BUILD / 'analysis-attempts'), minimumFreeBytes=18 * 1024**3,
             inputs=[inputs[k] for k in sorted(inputs)])
    write(FIX / 'analysis-freeze.json', f)
    print('Analysis freeze:', sha(FIX / 'analysis-freeze.json'))


def refresh(previous, output):
    f = json.loads(previous.read_text())
    f['inputs'] = [row(Path(r['path'])) for r in f['inputs']]
    f['implementationSha256'] = sha(HERE / 'prepare.py')
    f['previousPreparationFreezeSha256'] = sha(previous)
    f['previousPreparationFreeze'] = str(previous)
    f['inputs'].append(row(previous))
    write(output, f); print('Preparation freeze:', sha(output))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__); p.add_argument('--prepared', type=Path)
    p.add_argument('--refresh-from', type=Path); p.add_argument('--output', type=Path)
    args = p.parse_args()
    if args.refresh_from:
        if not args.output: p.error('--output required')
        refresh(args.refresh_from.resolve(), args.output.resolve())
    elif args.prepared: analysis(args.prepared.resolve())
    else: discover()
