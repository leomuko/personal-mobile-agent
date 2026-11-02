#!/usr/bin/env python3
"""Private, archive-bound Bazel server runtime. Never executes Java or Bazel."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import struct
import zipfile

BAZEL_SHA = '45cca81a839d7495258b19ee8371c7b891f350586ef37b9940f7b531eb654cc8'
JAR_SHA = '50e9295e57eebfea7fe0e487e6aae2aaf7e131a6055c1ed12863f4b668a1d249'
RELEASE_SHA = '2aeb383501a09146b53d47ee743734cc73be197f07dbe911074cc795c9964bb5'
PREFIX = 'embedded_tools/jdk/'
CLASS = 'com/google/devtools/build/lib/bazel/Bazel.class'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def metadata_version(data):
    text = data.decode('utf-8', errors='strict')
    matches = re.findall(r'^JAVA_VERSION="([0-9]+(?:\.[0-9]+)*(?:\+[0-9]+)?)"$', text, re.M)
    require(len(matches) == 1, 'unknown or malformed server Java metadata')
    return matches[0]


def class_major(data):
    require(len(data) >= 8 and data[:4] == b'\xca\xfe\xba\xbe', 'malformed server class')
    minor, major = struct.unpack('>HH', data[4:8])
    require(minor == 0 and major >= 45, 'unknown server class version')
    return major


def compatible(version, required_major):
    require(re.fullmatch(r'[0-9]+(?:\.[0-9]+)*(?:\+[0-9]+)?', version) is not None,
            'unknown server Java version')
    feature = int(version.split('.')[0].split('+')[0])
    require(feature >= 9 and feature + 44 >= required_major, 'incompatible server Java class version')


def inventory(root):
    root = Path(root)
    require(root.is_dir() and not root.is_symlink(), 'missing/private JDK root')
    rows = {'.': {'type': 'directory', 'mode': stat.S_IMODE(root.stat().st_mode)}}
    for p in sorted(root.rglob('*')):
        mode = stat.S_IMODE(p.lstat().st_mode)
        name = p.relative_to(root).as_posix()
        if p.is_symlink():
            require(p.resolve(strict=True).is_relative_to(root.resolve()), 'escaping JDK symlink')
            rows[name] = {'type': 'symlink', 'target': os.readlink(p), 'mode': mode}
        elif p.is_dir():
            rows[name] = {'type': 'directory', 'mode': mode}
        else:
            require(p.is_file(), 'unsupported JDK entry')
            rows[name] = {'type': 'file', 'mode': mode, 'bytes': p.stat().st_size, 'sha256': sha(p)}
    return rows


def archive_identity(archive):
    require(sha(archive) == BAZEL_SHA, 'Bazel executable changed')
    files = {}; directories = {'.'}; total = 0
    with zipfile.ZipFile(archive) as z:
        names = [i.filename for i in z.infolist()]
        require(len(names) == len(set(names)), 'duplicate Bazel archive member')
        jar = z.read('A-server.jar')
        require(hashlib.sha256(jar).hexdigest() == JAR_SHA, 'server JAR changed')
        with zipfile.ZipFile(io.BytesIO(jar)) as j:
            require(j.namelist().count(CLASS) == 1, 'missing/ambiguous server class')
            major = class_major(j.read(CLASS))
        for i in z.infolist():
            if not i.filename.startswith(PREFIX):
                continue
            name = i.filename[len(PREFIX):].rstrip('/')
            p = PurePosixPath(name)
            require(name and not p.is_absolute() and '..' not in p.parts and p.as_posix() == name,
                    'unsafe JDK archive path')
            mode = i.external_attr >> 16
            require(stat.S_IMODE(mode) & 0o7022 == 0, 'unsafe JDK archive permissions')
            for parent in p.parents:
                directories.add(parent.as_posix())
            if i.is_dir():
                require(stat.S_IMODE(mode) == 0o755, 'unsupported directory mode')
                directories.add(name)
                continue
            total += i.file_size
            require(total <= 256 * 1024**2 and i.file_size <= 128 * 1024**2, 'JDK expansion bound')
            data = z.read(i)
            if stat.S_ISLNK(mode):
                target = data.decode('utf-8', errors='strict')
                require(not Path(target).is_absolute(), 'absolute JDK symlink')
                files[name] = {'type': 'symlink', 'target': target, 'mode': stat.S_IMODE(mode)}
            else:
                require(stat.S_ISREG(mode), 'unsupported JDK archive type')
                files[name] = {'type': 'file', 'mode': stat.S_IMODE(mode),
                               'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
        require('release' in files and files['release']['sha256'] == RELEASE_SHA, 'JDK release changed')
        require(files.get('bin/java', {}).get('mode') == 0o755, 'missing server Java executable')
        version = metadata_version(z.read(PREFIX + 'release'))
    require(version == '21.0.5' and major == 65, 'unsupported bundled runtime identity')
    compatible(version, major)
    require(not set(files) & directories, 'JDK file/directory collision')
    rows = {p: {'type': 'directory', 'mode': 0o755} for p in directories}
    rows.update(files)
    return {'schema': 1, 'bazelSha256': BAZEL_SHA, 'serverJarSha256': JAR_SHA,
            'serverClassMajor': major, 'javaVersion': version, 'releaseSha256': RELEASE_SHA,
            'entries': dict(sorted(rows.items()))}


def stage(archive, destination, receipt):
    identity = archive_identity(archive)
    require(not destination.exists() and not receipt.exists(), 'preserve previous preparation')
    destination.mkdir(parents=True, mode=0o755); destination.chmod(0o755)
    with zipfile.ZipFile(archive) as z:
        for name, row in identity['entries'].items():
            p = destination / name
            if row['type'] == 'directory':
                p.mkdir(exist_ok=True, parents=True); p.chmod(row['mode'])
            elif row['type'] == 'file':
                with p.open('xb') as out:
                    out.write(z.read(PREFIX + name))
                p.chmod(row['mode'])
        for name, row in identity['entries'].items():
            if row['type'] == 'symlink':
                p = destination / name
                require((p.parent / row['target']).resolve().is_relative_to(destination.resolve()),
                        'escaping JDK symlink')
                p.symlink_to(row['target'])
    require(inventory(destination) == identity['entries'], 'staged JDK differs from executable')
    with receipt.open('x') as out:
        json.dump(identity, out, indent=2); out.write('\n')
    return identity


def preflight(archive, root, receipt):
    identity = archive_identity(archive)
    require(json.loads(receipt.read_text()) == identity, 'server JDK inventory/provenance changed')
    version = metadata_version((root / 'release').read_bytes())
    compatible(version, identity['serverClassMajor'])
    require(version == identity['javaVersion'], 'server Java version changed')
    require(inventory(root) == identity['entries'], 'private server JDK changed/incomplete')
    return {k: v for k, v in identity.items() if k != 'entries'}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--archive', type=Path, required=True)
    p.add_argument('--destination', type=Path, required=True)
    p.add_argument('--receipt', type=Path, required=True)
    a = p.parse_args()
    stage(a.archive, a.destination, a.receipt)


if __name__ == '__main__':
    main()
