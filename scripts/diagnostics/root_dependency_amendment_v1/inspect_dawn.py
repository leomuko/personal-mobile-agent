#!/usr/bin/env python3
"""One bounded Dawn source inspection; downloaded code is never executed."""
import argparse
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import re
import posixpath
import shutil
import sys
import tarfile
import time
import urllib.request
import urllib.parse

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('retained_inspection_owner', ROOT / 'scripts/diagnostics/ml_drift_compatibility/analyze.py')
owner = importlib.util.module_from_spec(spec); spec.loader.exec_module(owner)
require = owner.require
TAG = 'v20260720.160313'
ATTEMPT_BYTES = 512 * 1024**2
CHILD_BYTES = ATTEMPT_BYTES - 2 * 1024**2 - 64 * 1024


class ByteBudget:
    def __init__(self, maximum=CHILD_BYTES): self.maximum = maximum; self.used = 0
    def charge(self, size):
        require(0 <= size <= self.maximum - self.used, 'aggregate output ceiling')
        self.used += size


def write_json(path, value, budget, maximum=32 * 1024**2):
    data = (json.dumps(value, indent=2, ensure_ascii=False) + '\n').encode('utf-8')
    require(len(data) <= maximum, 'receipt serialization ceiling')
    budget.charge(len(data))
    with path.open('xb') as out: out.write(data)


class HttpsScope(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        value = urllib.parse.urlsplit(newurl)
        require(value.scheme == 'https' and value.hostname in ['api.github.com', 'codeload.github.com', 'pypi.org'] and
                value.port in [None, 443] and value.username is None, 'source redirect outside scope')
        return super().redirect_request(request, fp, code, message, headers, newurl)


def fetch(url, path, maximum, budget=None):
    budget = budget or ByteBudget()
    total = 0; h = hashlib.sha256()
    with urllib.request.build_opener(HttpsScope()).open(url, timeout=15) as source, path.open('xb') as out:
        while True:
            b = source.read(1024 * 1024)
            if not b: break
            total += len(b); require(total <= maximum, 'source acquisition byte ceiling')
            budget.charge(len(b))
            out.write(b); h.update(b)
    return {'url': url, 'bytes': total, 'sha256': h.hexdigest()}


def commit_from(responses):
    require(responses and responses[0].get('ref') == 'refs/tags/' + TAG, 'wrong source tag')
    value = responses[0]['object']
    for body in responses[1:]:
        require(value['type'] == 'tag', 'unexpected tag dereference')
        value = body['object']
    require(value['type'] == 'commit' and re.fullmatch('[a-f0-9]{40}', value['sha']), 'unresolved tag commit')
    return value['sha']


class LimitedReader:
    def __init__(self, source): self.source = source; self.total = 0
    def read(self, size):
        require(0 <= size <= 2 * 1024**2, 'archive metadata read ceiling')
        value = self.source.read(size); self.total += len(value)
        require(self.total <= 2 * 1024**3, 'archive expansion ceiling')
        return value


def inspect_archive(archive, commit, output, budget=None):
    budget = budget or ByteBudget()
    prefix = 'dawn-' + commit + '/'; rows = {}; total = 0; selected = 0; names = 0
    with archive.open('rb') as raw, gzip.GzipFile(fileobj=raw) as decoded, \
            tarfile.open(fileobj=(reader := LimitedReader(decoded)), mode='r|') as tar:
        for entry in tar:
            name = entry.name.rstrip('/')
            if name == prefix.rstrip('/') and entry.isdir(): continue
            require(entry.name.startswith(prefix), 'unexpected immutable archive prefix')
            name = entry.name[len(prefix):].rstrip('/'); p = PurePosixPath(name)
            names += len(name.encode('utf-8')) + len(entry.linkname.encode('utf-8'))
            require(len(name.encode('utf-8')) <= 1024 and len(entry.linkname.encode('utf-8')) <= 1024
                    and names <= 4 * 1024**2, 'archive name/link metadata ceiling')
            require(name and not p.is_absolute() and '..' not in p.parts and p.as_posix() == name,
                    'unsafe archive member')
            require(name not in rows and len(rows) < 60000, 'duplicate/member ceiling')
            if entry.isdir(): rows[name] = {'type': 'directory'}; continue
            if entry.issym():
                resolved = posixpath.normpath(str(p.parent / entry.linkname))
                require(not PurePosixPath(entry.linkname).is_absolute() and resolved != '..' and
                        not resolved.startswith('../'), 'escaping archive link')
                rows[name] = {'type': 'symlink', 'target': entry.linkname}; continue
            require(entry.isfile(), 'unsupported archive type')
            total += entry.size; require(total <= 2 * 1024**3, 'uncompressed byte ceiling')
            keep = name.startswith('generator/') or name in ['src/dawn/dawn.json', 'src/dawn/dawn_wire.json'] or p.name.startswith('LICENSE')
            if keep: selected += entry.size; require(selected <= 32 * 1024**2, 'selected source ceiling')
            h = hashlib.sha256(); blocks = []
            with tar.extractfile(entry) as f:
                for b in iter(lambda: f.read(1024 * 1024), b''):
                    h.update(b)
                    if keep: blocks.append(b)
            rows[name] = {'type': 'file', 'bytes': entry.size, 'sha256': h.hexdigest(), 'mode': entry.mode}
            if keep:
                budget.charge(entry.size)
                q = output / name; q.parent.mkdir(parents=True, exist_ok=True)
                with q.open('xb') as f: f.write(b''.join(blocks))
        # Tar end markers can precede the gzip trailer. Consume through gzip EOF
        # so a truncated transfer cannot become an immutable recipe checksum.
        while reader.read(1024 * 1024): pass
    require('generator/templates/api_cpp.h' in rows and 'generator/dawn_json_generator.py' in rows,
            'required generator sources missing')
    return {'stripPrefix': prefix.rstrip('/'), 'members': rows, 'selectedBytes': selected,
            'uncompressedFileBytes': total, 'sourceExecuted': False}


def worker(attempt):
    bodies = []; metadata = []; budget = ByteBudget()
    url = 'https://api.github.com/repos/google/dawn/git/ref/tags/' + TAG
    for index in range(3):
        path = attempt / ('tag-' + str(index) + '.json')
        metadata.append(fetch(url, path, 1024**2, budget)); body = json.loads(path.read_text()); bodies.append(body)
        obj = body['object']; require(re.fullmatch('[a-f0-9]{40}', obj['sha']), 'invalid Git object')
        if obj['type'] == 'commit': break
        require(obj['type'] == 'tag', 'unexpected Git object kind')
        url = 'https://api.github.com/repos/google/dawn/git/tags/' + obj['sha']
    commit = commit_from(bodies)
    archive = attempt / 'dawn-source.tar.gz.part'
    identity = fetch('https://codeload.github.com/google/dawn/tar.gz/' + commit, archive, 256 * 1024**2, budget)
    sources = attempt / 'selected-source'; sources.mkdir()
    manifest = inspect_archive(archive, commit, sources, budget)
    wheels = []
    for package, version, filename in [
        ('Jinja2', '3.1.6', 'jinja2-3.1.6-py3-none-any.whl'),
        ('MarkupSafe', '3.0.3', 'markupsafe-3.0.3-cp313-cp313-macosx_11_0_arm64.whl')]:
        path = attempt / (package.lower() + '-release.json')
        metadata.append(fetch('https://pypi.org/pypi/' + package + '/' + version + '/json', path, 1024**2, budget))
        body = json.loads(path.read_text())
        require(body['info']['name'].lower() == package.lower() and body['info']['version'] == version,
                'unexpected Python package identity')
        candidates = [row for row in body['urls'] if row['filename'].lower() == filename]
        require(len(candidates) == 1 and re.fullmatch('[a-f0-9]{64}', candidates[0]['digests']['sha256']),
                'missing/ambiguous pinned host wheel')
        wheels.append(candidates[0])
    result = {'tag': TAG, 'commit': commit, 'metadata': metadata, 'archive': identity,
              'sourceManifest': manifest, 'hostWheelMetadata': wheels, 'wheelsDownloaded': False,
              'sourceExecuted': False, 'adopted': False}
    write_json(attempt / 'source-receipt.json', result, budget)


def main():
    if len(sys.argv) == 3 and sys.argv[1] == '--worker':
        worker(Path(sys.argv[2])); return 0
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--freeze', type=Path, required=True); p.add_argument('--sha256', required=True)
    p.add_argument('--attempt', type=Path, required=True); a = p.parse_args()
    created = False; result = None
    try:
        require(owner.digest(a.freeze) == a.sha256, 'inspection freeze changed')
        freeze = json.loads(a.freeze.read_text()); owner.verify_files(freeze['inputs'])
        require({str(Path(__file__).resolve()), str(Path(owner.__file__).resolve()), freeze['python']} <=
                {row['path'] for row in freeze['inputs']}, 'unbound inspector/tool identity')
        require(freeze['protocol'] == 'root-dependency-source-inspection-v1' and
                freeze['inspectorSha256'] == owner.digest(__file__), 'inspection code/scope changed')
        require(not a.attempt.exists() and a.attempt.resolve().is_relative_to(Path(freeze['attemptParent']).resolve()),
                'preserve old attempt / invalid inspection path')
        require(shutil.disk_usage(ROOT).free >= 1024**3, 'inspection needs 1 GiB free')
        a.attempt.mkdir(parents=True); created = True
        result = owner.run_owned([freeze['python'], str(Path(__file__).resolve()), '--worker', str(a.attempt)],
            ROOT, a.attempt / 'worker', freeze['environment'], 300, 2 * 1024**2, 15)
        result['freezeSha256'] = a.sha256
        return 0 if result['classification'] == 'ANALYSIS_COMMAND_SUCCEEDED' and result['cleanupConfirmed'] else 1
    except (ValueError, OSError, KeyError, KeyboardInterrupt, owner.subprocess.SubprocessError) as e:
        result = {'classification': 'OWNER_FAILED_OR_INTERRUPTED', 'cleanupConfirmed': None,
                  'failure': type(e).__name__ + ': ' + str(e)[:2048], 'freezeSha256': a.sha256}
        print('Inspection blocked: ' + str(e), file=sys.stderr); return 1
    finally:
        if created:
            write_json(a.attempt / 'outcome.json', result, ByteBudget(64 * 1024), 64 * 1024)


if __name__ == '__main__': raise SystemExit(main())
