#!/usr/bin/env python3
"""Bounded, verified archive-cache preparation. No extraction or tool execution."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('retained_owned_process', ROOT / 'scripts/diagnostics/ml_drift_compatibility/analyze.py')
owned = importlib.util.module_from_spec(spec); spec.loader.exec_module(owned)
require = owned.require
sha = owned.digest
HEX = re.compile(r'[a-f0-9]{64}')


def regular(path):
    require(path.is_file() and not path.is_symlink(), 'not a complete regular input: ' + str(path))


def verify_file(path, expected, limit):
    regular(path)
    require(0 < path.stat().st_size <= limit and sha(path) == expected,
            'checksum/size mismatch: ' + str(path))


def validate_manifest(value):
    require(value['protocol'] == 'repository-archive-cache-v1', 'unsupported preparation protocol')
    require(0 < len(value['entries']) <= 48, 'empty/oversized manifest')
    hashes = set()
    for row in value['entries']:
        h = row['sha256']
        require(HEX.fullmatch(h) and h not in hashes, 'duplicate/invalid content identity'); hashes.add(h)
        require(row['kind'] in ['retained-cache', 'download'] and row['references'], 'unbound cache input')
        require(0 < row['maxBytes'] <= 512 * 1024**2, 'entry size bound changed')
        for ref in row['references']:
            p = Path(ref['path']); regular(p)
            require(sha(p) == ref['sha256'] and h in p.read_text(errors='strict'), 'changed declaration reference')
        if row['kind'] == 'retained-cache':
            verify_file(Path(row['source']), h, row['maxBytes'])
        else:
            require(row['url'].startswith('https://static.rust-lang.org/dist/') and
                    re.fullmatch(r'https://static\.rust-lang\.org/dist/[a-z0-9_.-]+\.tar\.xz', row['url']),
                    'unapproved download source')
        for marker in row['markers']:
            require(re.fullmatch('id-[a-f0-9]{64}', marker['name']) and marker['bytes'] == 0,
                    'unsupported canonical-ID marker')
            p = Path(marker['source']); regular(p)
            require(p.stat().st_size == 0 and sha(p) == marker['sha256'], 'canonical-ID marker changed')
    require(len(hashes) <= 48 and sum(r['kind'] == 'download' for r in value['entries']) <= 8,
            'preparation scope exceeded')


def transfer(row, output):
    """Worker writes only an unaccepted partial; parent owns timeout and publication."""
    require(not output.exists(), 'preserve prior partial')
    total = 0; h = hashlib.sha256()
    require(HEX.fullmatch(row['sha256']) and 0 < row['maxBytes'] <= 512 * 1024**2,
            'invalid worker identity/bound')
    if row['kind'] == 'retained-cache':
        regular(Path(row['source'])); source = Path(row['source']).open('rb')
    else:
        require(row['kind'] == 'download' and re.fullmatch(
            r'https://static\.rust-lang\.org/dist/[a-z0-9_.-]+\.tar\.xz', row['url']), 'unapproved worker URL')
        source = urllib.request.urlopen(row['url'], timeout=15)
    with source, output.open('xb') as target:
        while True:
            block = source.read(1024 * 1024)
            if not block: break
            total += len(block)
            require(total <= row['maxBytes'], 'stream size limit exceeded')
            target.write(block); h.update(block)
        target.flush(); os.fsync(target.fileno())
    require(total > 0 and h.hexdigest() == row['sha256'], 'stream checksum mismatch')


def publish(row, partial, cache):
    verify_file(partial, row['sha256'], row['maxBytes'])
    dest = cache / 'content_addressable/sha256' / row['sha256']
    # Exclusive reservation: never overwrite even an empty/conflicting entry.
    dest.mkdir(parents=True, exist_ok=False)
    target = dest / 'file'
    with partial.open('rb') as source, target.open('xb') as out:
        shutil.copyfileobj(source, out); out.flush(); os.fsync(out.fileno())
    target.chmod(0o644)
    verify_file(target, row['sha256'], row['maxBytes'])
    for marker in row['markers']:
        with (dest / marker['name']).open('xb') as out:
            out.flush(); os.fsync(out.fileno())
        (dest / marker['name']).chmod(0o644)
    return {'sha256': row['sha256'], 'bytes': target.stat().st_size,
            'markers': [m['name'] for m in row['markers']], 'transformations': 'NONE',
            'extractedTreeAccepted': False}


def verify_cache(cache, receipt):
    require(cache.is_dir() and not cache.is_symlink(), 'missing archive cache')
    expected = {}
    require(0 < len(receipt['entries']) <= 48, 'empty/oversized receipt')
    identities = set()
    for row in receipt['entries']:
        require(HEX.fullmatch(row['sha256']) and row['sha256'] not in identities, 'duplicate/invalid receipt')
        identities.add(row['sha256'])
        rel = 'content_addressable/sha256/' + row['sha256'] + '/'
        expected[rel + 'file'] = (row['sha256'], row['bytes'])
        for name in row['markers']:
            require(re.fullmatch('id-[a-f0-9]{64}', name), 'invalid canonical marker')
            expected[rel + name] = (hashlib.sha256(b'').hexdigest(), 0)
    actual = {p.relative_to(cache).as_posix() for p in cache.rglob('*') if not p.is_dir() or p.is_symlink()}
    require(actual == set(expected), 'cache inventory incomplete/changed')
    for name, (h, size) in expected.items():
        p = cache / name; regular(p)
        require(p.stat().st_size == size and sha(p) == h and p.stat().st_mode & 0o777 == 0o644,
                'cache entry changed: ' + name)


def run(freeze_path, expected, attempt):
    start = time.monotonic()
    require(sha(freeze_path) == expected, 'preparation freeze changed')
    freeze = json.loads(freeze_path.read_text())
    owned.verify_files(freeze['inputs'])
    bound = {r['path'] for r in freeze['inputs']}
    require({str(Path(__file__).resolve()), str(Path(owned.__file__).resolve()),
             freeze['manifest'], freeze['python']} <= bound, 'unbound preparation implementation/tool')
    require(freeze['protocol'] == 'repository-archive-cache-v1' and freeze['implementationSha256'] == sha(__file__),
            'preparation implementation changed')
    require(freeze['perEntrySeconds'] == 180 and freeze['totalSeconds'] == 900 and
            freeze['cleanupSeconds'] == 15 and freeze['logLimitBytes'] == 2 * 1024**2,
            'preparation bounds changed')
    require(attempt.resolve().is_relative_to(Path(freeze['attemptParent']).resolve()) and
            not attempt.exists(), 'preserve prior preparation / unexpected output root')
    manifest_path = Path(freeze['manifest']); require(sha(manifest_path) == freeze['manifestSha256'], 'manifest changed')
    value = json.loads(manifest_path.read_text()); validate_manifest(value)
    require(time.monotonic() - start < freeze['totalSeconds'], 'preparation admission deadline exhausted')
    require(shutil.disk_usage(attempt.parent.parent).free >= 5 * 1024**3, 'insufficient preparation storage')
    attempt.mkdir(parents=True); cache = attempt / 'accepted-cache'; cache.mkdir()
    partials = attempt / 'partials'; partials.mkdir()
    report = {'protocol': freeze['protocol'], 'freezeSha256': expected, 'manifestSha256': freeze['manifestSha256'],
              'entries': [], 'completed': False, 'preparationDeadlineSeconds': freeze['totalSeconds'],
              'extractedTreesAccepted': False, 'artifactBuilds': False}
    accepted_bytes = 0
    def save():
        (attempt / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    try:
        for index, row in enumerate(value['entries']):
            remaining = freeze['totalSeconds'] - (time.monotonic() - start)
            require(remaining > 0, 'preparation total deadline exhausted')
            partial = partials / (row['sha256'] + '.part')
            worker_input = attempt / ('worker-input-' + str(index) + '.json')
            with worker_input.open('x') as out:
                json.dump(row, out, sort_keys=True); out.write('\n')
            result = owned.run_owned([freeze['python'], str(Path(__file__).resolve()), '--worker',
                str(worker_input), sha(worker_input), str(partial)], ROOT, attempt / ('entry-' + str(index)),
                dict(freeze['environment']), min(freeze['perEntrySeconds'], remaining),
                freeze['logLimitBytes'], freeze['cleanupSeconds'])
            record = {'sha256': row['sha256'], 'kind': row['kind'], 'worker': result, 'accepted': False}
            report['entries'].append(record); save()
            require(result['cleanupConfirmed'] and result['classification'] == 'ANALYSIS_COMMAND_SUCCEEDED',
                    'preparation worker failed; partial retained')
            require(time.monotonic() - start < freeze['totalSeconds'], 'preparation total deadline exhausted')
            accepted_bytes += partial.stat().st_size
            require(accepted_bytes <= 1536 * 1024**2, 'total accepted bytes exceeded')
            record['cache'] = publish(row, partial, cache); record['accepted'] = True; save()
        receipt = {'protocol': freeze['protocol'], 'manifestSha256': freeze['manifestSha256'],
                   'entries': [r['cache'] for r in report['entries']]}
        verify_cache(cache, receipt)
        require(time.monotonic() - start < freeze['totalSeconds'], 'preparation total deadline exhausted')
        with (attempt / 'receipt.json').open('x') as out:
            json.dump(receipt, out, indent=2); out.write('\n')
        require(time.monotonic() - start < freeze['totalSeconds'], 'receipt finalization deadline exhausted')
        report['completed'] = True
    except (OSError, ValueError, owned.subprocess.SubprocessError) as error:
        report['failure'] = str(error)
        raise
    finally:
        report['elapsedSeconds'] = time.monotonic() - start; save()
        # A slow final evidence write cannot turn a late preparation into success.
        # Analysis is admitted only after this process exits and its final report
        # has been reviewed; intermediate on-disk state is never admission proof.
        if report['completed'] and time.monotonic() - start >= freeze['totalSeconds']:
            report['completed'] = False
            report['failure'] = 'report finalization deadline exhausted'
            report['elapsedSeconds'] = time.monotonic() - start
            save()
            raise ValueError(report['failure'])
    return report


def main():
    if len(sys.argv) == 5 and sys.argv[1] == '--worker':
        row_path = Path(sys.argv[2]); require(sha(row_path) == sys.argv[3], 'worker input changed')
        transfer(json.loads(row_path.read_text()), Path(sys.argv[4])); return 0
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--freeze', type=Path, required=True); p.add_argument('--freeze-sha256', required=True)
    p.add_argument('--attempt', type=Path, required=True); a = p.parse_args()
    try:
        run(a.freeze, a.freeze_sha256, a.attempt); return 0
    except (OSError, ValueError, KeyError, owned.subprocess.SubprocessError) as error:
        print('Preparation blocked: ' + str(error), file=sys.stderr); return 1


if __name__ == '__main__':
    raise SystemExit(main())
