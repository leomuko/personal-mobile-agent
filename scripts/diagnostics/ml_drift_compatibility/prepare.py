#!/usr/bin/env python3
"""Prepare verified local analysis inputs. Does not execute tools or modify source caches."""
import argparse
import base64
import concurrent.futures
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import tarfile
import urllib.request
import zipfile


def require(ok, message):
    if not ok:raise ValueError(message)


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda:f.read(1024 * 1024), b''):h.update(block)
    return h.hexdigest()


def safe_path(root, name):
    p = PurePosixPath(name)
    require(not p.is_absolute() and '..' not in p.parts, 'unsafe archive path')
    result = root / p
    require(result.resolve().is_relative_to(root.resolve()), 'archive path escapes root')
    return result


def inventory(root):
    result = {}
    for p in sorted(Path(root).rglob('*')):
        name = p.relative_to(root).as_posix()
        if p.is_symlink():
            require(p.resolve().is_relative_to(Path(root).resolve()), 'external toolchain symlink')
            result[name] = {'symlink': os.readlink(p)}
        elif p.is_file():
            result[name] = {'bytes': p.stat().st_size, 'sha256': sha(p), 'mode': p.stat().st_mode & 0o777}
    return {'files': result}


def unpack(archive, expected, destination):
    require(sha(archive) == expected, 'archive changed')
    require(not destination.exists(), 'destination exists; preserve prior preparation')
    destination.mkdir(parents=True)
    seen = set();links = [];total = 0
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as z:
            for entry in z.infolist():
                name = entry.filename
                p = safe_path(destination, name)
                require(name not in seen, 'duplicate archive member');seen.add(name)
                if entry.is_dir():p.mkdir(parents=True, exist_ok=True);continue
                total += entry.file_size
                require(total <= 8 * 1024**3 and entry.file_size <= 512 * 1024**2, 'archive expansion bound')
                mode = entry.external_attr >> 16
                if stat.S_ISLNK(mode):
                    links.append((p, z.read(entry).decode()));continue
                p.parent.mkdir(parents=True, exist_ok=True)
                with p.open('xb') as f, z.open(entry) as source:shutil.copyfileobj(source, f)
                p.chmod(mode & 0o777 or 0o644)
    else:
        with tarfile.open(archive, 'r:gz') as t:
            for entry in t:
                name = entry.name;p = safe_path(destination, name)
                require(name not in seen, 'duplicate archive member');seen.add(name)
                if entry.isdir():p.mkdir(parents=True, exist_ok=True);continue
                if entry.issym():links.append((p, entry.linkname));continue
                require(entry.isfile(), 'unsupported archive entry')
                total += entry.size
                require(total <= 8 * 1024**3 and entry.size <= 512 * 1024**2, 'archive expansion bound')
                p.parent.mkdir(parents=True, exist_ok=True)
                with p.open('xb') as f, t.extractfile(entry) as source:shutil.copyfileobj(source, f)
                p.chmod(entry.mode & 0o777)
    # Create links last, after ordinary members; never write through a link.
    for p, target in links:
        require(not Path(target).is_absolute() and
                (p.parent / target).resolve().is_relative_to(destination.resolve()), 'unsafe archive symlink')
        p.parent.mkdir(parents=True, exist_ok=True);p.symlink_to(target)
    inventory(destination)  # Reject link chains that escape after all links exist.


def collect_sdk(catalog_path, expected, destination):
    require(sha(catalog_path) == expected, 'source catalog changed')
    catalog = json.loads(catalog_path.read_text())
    require(catalog['sdkRevision'] == '5e58e9a0aef7abf7091207a8b1d1063a1c800f08', 'SDK revision changed')
    entries = catalog['sdkGitBlobs'];require(len(entries) == 1355, 'incomplete SDK tree')
    require(not destination.exists(), 'destination exists; preserve prior preparation')
    destination.mkdir(parents=True)
    def fetch(name):
        row = entries[name];p = safe_path(destination, name)
        require(row['bytes'] <= 64 * 1024**2, 'source blob exceeds collection bound')
        # GitHub raw smudges LFS objects. Retrieve original pointer blobs through
        # the Git API so they remain explicit unresolved binary dependencies.
        if row['lfsPointer']:
            url = 'https://api.github.com/repos/google-ai-edge/LiteRT-LM/git/blobs/' + row['gitBlob']
            with urllib.request.urlopen(url, timeout=30) as response:
                data = base64.b64decode(json.loads(response.read(4096))['content'])
        else:
            url = 'https://raw.githubusercontent.com/google-ai-edge/LiteRT-LM/' + catalog['sdkRevision'] + '/' + name
            with urllib.request.urlopen(url, timeout=45) as response:data = response.read(row['bytes'] + 1)
        require(len(data) == row['bytes'] and hashlib.sha256(data).hexdigest() == row['sha256'], 'source bytes changed')
        require(hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() == row['gitBlob'],
                'Git source identity changed')
        p.parent.mkdir(parents=True, exist_ok=True)
        with p.open('xb') as f:f.write(data)
        p.chmod(row['mode'])
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:list(pool.map(fetch, sorted(entries)))


def canonical_sdk(catalog_path, expected, source, output):
    require(sha(catalog_path) == expected, 'source catalog changed')
    catalog = json.loads(catalog_path.read_text());rows = catalog['sdkGitBlobs']
    require(set(inventory(source)['files']) == set(rows), 'source tree inventory differs')
    with output.open('xb') as dest, gzip.GzipFile(fileobj=dest, mode='wb', mtime=0, filename='') as compressed:
        with tarfile.open(fileobj=compressed, mode='w|', format=tarfile.GNU_FORMAT) as tar:
            for name in sorted(rows):
                p = safe_path(source, name);row = rows[name]
                require(sha(p) == row['sha256'], 'source changed before assembly')
                info = tarfile.TarInfo('LiteRT-LM-' + catalog['sdkRevision'] + '/' + name)
                info.size = row['bytes'];info.mode = row['mode'];info.uid = info.gid = info.mtime = 0
                with p.open('rb') as f:tar.addfile(info, f)
    require(sha(output) == catalog['sdkArchive']['sha256'], 'canonical archive identity differs')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest='command', required=True)
    x = sub.add_parser('unpack');x.add_argument('--archive', type=Path, required=True)
    x.add_argument('--sha256', required=True);x.add_argument('--destination', type=Path, required=True)
    i = sub.add_parser('inventory');i.add_argument('--root', type=Path, required=True)
    i.add_argument('--output', type=Path, required=True)
    for name in ['collect-sdk', 'canonical-sdk']:
        c = sub.add_parser(name);c.add_argument('--catalog', type=Path, required=True)
        c.add_argument('--sha256', required=True)
        if name == 'collect-sdk':c.add_argument('--destination', type=Path, required=True)
        else:
            c.add_argument('--source', type=Path, required=True);c.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    if args.command == 'unpack':unpack(args.archive, args.sha256, args.destination)
    elif args.command == 'inventory':
        with args.output.open('x') as f:json.dump(inventory(args.root), f, indent=2)
    elif args.command == 'collect-sdk':collect_sdk(args.catalog, args.sha256, args.destination)
    else:canonical_sdk(args.catalog, args.sha256, args.source, args.output)


if __name__ == '__main__':main()
