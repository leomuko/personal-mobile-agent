#!/usr/bin/env python3
"""Pinned source-only ADR 0008 prerequisite audit. Cannot build or access a device."""
import argparse
import hashlib
import json
import re
import sys
import time
import urllib.request
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[3]
INPUTS = ROOT / 'planning/fixtures/android-runtime-build-v1/source-inputs.json'
INPUTS_SHA = '57145ce62d14f232d202f79919b0e841deec4c14df7382296330760fda7ebd81'
CACHE = ROOT / 'build/android-runtime-recipe-v1/verified-inputs'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def inputs(path=INPUTS):
    raw = path.read_bytes()
    require(sha(raw) == INPUTS_SHA, 'source manifest changed')
    value = json.loads(raw)
    require(value['abis'] == ['arm64-v8a', 'x86_64'], 'ABI scope changed')
    lock = ROOT / 'planning/fixtures/android-runtime-integration-v1/decision-lock.json'
    require(sha(lock.read_bytes()) == value['decisionLockSha256'], 'decision lock changed')
    for name, digest in json.loads(lock.read_text())['files'].items():
        require(sha((ROOT / name).read_bytes()) == digest, 'decision input changed: ' + name)
    return value


def relative(entry):
    repo = entry['repository']
    require(repo in ['LiteRT-LM', 'LiteRT'], 'unknown repository')
    path = PurePosixPath(entry['path'])
    require(not path.is_absolute() and '..' not in path.parts, 'unsafe source path')
    require(re.fullmatch('[0-9a-f]{40}', entry['revision']) is not None, 'unpinned revision')
    return Path(repo) / path


def verify_blob(entry, data):
    require(len(data) == entry['bytes'] and sha(data) == entry['sha256'],
            'source identity mismatch: ' + str(relative(entry)))
    blob = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
    require(blob == entry['gitBlob'], 'Git blob mismatch: ' + entry['path'])


def destination(cache, entry):
    target = cache / relative(entry)
    require(target.resolve().is_relative_to(cache.resolve()), 'cache path escapes root')
    return target


def download(entry):
    # Only the frozen public text inputs; no weights, binary SDKs or mutable URLs.
    url = ('https://raw.githubusercontent.com/google-ai-edge/' + entry['repository'] +
           '/' + entry['revision'] + '/' + entry['path'])
    require(0 < entry['bytes'] <= 512 * 1024, 'source exceeds collection bound')
    start = time.monotonic()
    request = urllib.request.Request(url, headers={'User-Agent': 'edge-companion-build-closure'})
    chunks, total = [], 0
    with urllib.request.urlopen(request, timeout=15) as response:
        while True:
            chunk = response.read(16 * 1024)
            require(time.monotonic() - start < 60, 'source download timed out')
            total += len(chunk)
            require(total <= entry['bytes'], 'source download exceeds frozen size')
            if not chunk:
                break
            chunks.append(chunk)
    data = b''.join(chunks)
    verify_blob(entry, data)
    return data


def collect(value, cache):
    for entry in value['files']:
        target = destination(cache, entry)
        if target.exists():
            verify_blob(entry, target.read_bytes())
            continue
        data = download(entry)
        target.parent.mkdir(parents=True, exist_ok=True)
        # Immutable publication: another collector cannot replace an existing file.
        with target.open('xb') as output:
            output.write(data)


def read_sources(value, cache):
    result = {}
    for entry in value['files']:
        path = destination(cache, entry)
        require(path.is_file(), 'missing source: ' + str(relative(entry)))
        data = path.read_bytes()
        verify_blob(entry, data)
        key = (entry['repository'], entry['path'])
        require(key not in result, 'duplicate input')
        result[key] = data.decode('utf-8')
    return result


def archive_declaration(text, name):
    """Inspect simple literal declarations in the hash-bound sources, not Starlark evaluation."""
    blocks = re.findall(r'http_archive\(\s*\n(.*?)^\s*\)', text, re.M | re.S)
    matches = [block for block in blocks if re.search(
        r'^\s*name\s*=\s*"' + re.escape(name) + r'"\s*,', block, re.M)]
    require(len(matches) == 1, 'missing/ambiguous archive: ' + name)
    return matches[0]


def blockers(sources):
    drift = archive_declaration(sources['LiteRT', 'WORKSPACE'], 'ml_drift')
    gpu = archive_declaration(sources['LiteRT', 'third_party/litert_gpu/workspace.bzl'], 'litert_gpu')
    prebuilts = archive_declaration(
        sources['LiteRT', 'third_party/litert_prebuilts/workspace.bzl'], 'litert_prebuilts')
    # A future source change is a new reviewed input, not automatic clearance.
    require(not re.search(r'^\s*(urls?|sha256|integrity)\s*=', drift, re.M),
            'ml_drift declaration differs from inspected unresolved prerequisite')
    require('litert-2.1.1.aar' in gpu and not re.search(r'\b(sha256|integrity)\s*=', gpu),
            'GPU binary declaration differs from inspected prerequisite')
    require('/latest/litert_prebuilts.zip' in prebuilts and
            not re.search(r'\b(sha256|integrity)\s*=', prebuilts),
            'prebuilt declaration differs from inspected prerequisite')
    require('@ml_drift//ml_drift/cl:opencl_wrapper' in sources[
        'LiteRT', 'litert/runtime/accelerators/gpu/BUILD'], 'GPU dependency edge missing')
    return [
        {'id': 'GPU_SOURCE_CLOSURE', 'status': 'BLOCKED',
         'evidence': 'LiteRT/WORKSPACE: ml_drift has neither URL nor integrity identity; '
                     'GPU accelerator source targets reference its cl/common targets.',
         'boundary': 'No claim that CPU compilation failed or all possible GPU routes are impossible.'},
        {'id': 'GPU_BINARY_ALTERNATIVE', 'status': 'NOT_ADMITTED',
         'evidence': 'third_party/litert_gpu imports a published 2.1.1 AAR without a hash.',
         'boundary': 'Binary alternative is not a source recipe or authorized dependency replacement.'},
        {'id': 'MUTABLE_PREBUILT_DECLARATION', 'status': 'UNRESOLVED',
         'evidence': 'third_party/litert_prebuilts references latest/litert_prebuilts.zip without integrity.',
         'boundary': 'Static declaration observation, not proof this repository is fetched by every JNI action.'},
    ]


def audit(value, cache):
    sources = read_sources(value, cache)
    sdk_lock = sources['LiteRT-LM', 'Cargo.lock'].encode()
    require(sha(sdk_lock) == 'b95103f9b316784814f1e39cc0124cbc134e61d3f0f27c7ef514408e65b7916a',
            'full SDK lock replaced')
    require(sources['LiteRT-LM', '.bazelversion'].strip() == '7.6.1', 'Bazel identity changed')
    return {
        'schema': 1, 'scope': value['scope'], 'sourceInputsSha256': INPUTS_SHA,
        'auditImplementationSha256': sha(Path(__file__).read_bytes()),
        'sourceFilesVerified': len(sources), 'sdkRevision': value['sdkRevision'],
        'litertRevision': value['litertRevision'], 'abis': value['abis'],
        'buildRecipeStatus': 'BLOCKED', 'blockers': blockers(sources),
        'completeBuildRecipeFrozen': False, 'buildsRun': False,
        'gradleSubstitutionImplemented': False, 'deviceExecutionAuthorized': False,
        'semanticSearchEnabled': False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['collect', 'check'])
    parser.add_argument('--cache', type=Path, default=CACHE)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    try:
        value = inputs()
        if args.command == 'collect':
            require(args.report is None, 'collect cannot publish a build audit')
            collect(value, args.cache)
            print('Verified public source inputs:', len(value['files']))
            return 0
        report = audit(value, args.cache)
        output = json.dumps(report, indent=2) + '\n'
        if args.report:
            # Preserve failed attempts. Never replace a previous report.
            with args.report.open('x') as destination_file:
                destination_file.write(output)
        print(output, end='')
        return 2  # The source audit succeeded; the build prerequisite did not.
    except (ValueError, OSError) as error:
        print('Prerequisite audit failed: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
