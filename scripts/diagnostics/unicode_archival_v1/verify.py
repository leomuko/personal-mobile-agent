"""Explicit data-only archival view. The historical executable guard is unchanged."""
import hashlib
import json
import os
from pathlib import Path
import stat
import types

ROOT = Path(__file__).resolve().parents[3]
MANIFEST = ROOT / 'planning/fixtures/unicode-archive-verification-v1/manifest.json'
MANIFEST_SHA = 'e79b735f964e0c2b4c6bd7b1e348cc4acef745d96077d8bdcf3e10e09eedee47'
MAX_BYTES = 8 * 1024 ** 2


def require(ok, message):
    if not ok: raise ValueError(message)


def read(path):
    path = Path(path)
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as stream:
        before = os.fstat(stream.fileno())
        require(stat.S_ISREG(before.st_mode) and before.st_size <= MAX_BYTES, 'archive input type/size')
        data = stream.read(MAX_BYTES + 1)
        after = os.fstat(stream.fileno())
    identity = lambda st: (st.st_dev, st.st_ino, st.st_size, st.st_mode, st.st_mtime_ns, st.st_ctime_ns)
    require(len(data) == before.st_size and identity(before) == identity(after) == identity(path.lstat()),
            'archive input changed during read')
    return data


def relative(value):
    raw = os.fspath(value)
    require(isinstance(raw, str) and raw and not Path(raw).is_absolute()
            and '\\' not in raw and not any(ord(c) < 32 for c in raw)
            and all(p not in ('', '.', '..') for p in raw.split('/')), 'unsafe archival logical path')
    return Path(raw)


def seal(manifest=MANIFEST):
    data = read(manifest)
    require(hashlib.sha256(data).hexdigest() == MANIFEST_SHA, 'archival route seal changed')
    value = json.loads(data)
    require(value['scope'] == 'data-only-historical-verification-not-current-native-admission'
            and value['nativeExecutionAuthorized'] is False, 'invalid archive scope')
    override = value['onlyLogicalOverride']
    require(override['logicalPath'] == 'AGENTS.md'
            and override['archivePath'] == 'planning/fixtures/cpp-build-skills-v1/AGENTS.before.txt',
            'unsupported archive override')
    for row in value['lineage']:
        payload = read(ROOT / relative(row['path']))
        require(len(payload) == row['bytes'] and hashlib.sha256(payload).hexdigest() == row['sha256'],
                'archival lineage changed: ' + row['path'])
    return value


class ArchiveRoot:
    """Only the frozen verifier receives this view; no real module global changes."""
    __slots__ = ('_root', '_archived_guidance')

    def __init__(self, root, archived_guidance):
        self._root, self._archived_guidance = Path(root), Path(archived_guidance)

    def __fspath__(self):
        return str(self._root)

    def __truediv__(self, value):
        path = relative(value)
        return self._archived_guidance if path.as_posix() == 'AGENTS.md' else self._root / path


def legacy(value):
    path = ROOT / 'scripts/diagnostics/unicode_correction/run.py'
    expected = next(r for r in value['lineage'] if r['path'] == path.relative_to(ROOT).as_posix())
    source = read(path)
    require(hashlib.sha256(source).hexdigest() == expected['sha256'], 'historical guard changed')
    module = types.ModuleType('unicode_correction_archival_readonly_guard')
    module.__file__ = str(path)
    # Execute the verified frozen Python bytes, not an edited guard or a stale pyc.
    exec(compile(source, str(path), 'exec'), module.__dict__)
    return module


def verify_current():
    """The original verifier runs with its real current-source ROOT and no override."""
    module = legacy(seal())
    return module.verify_inputs()


def verify_archive():
    value = seal()
    override = value['onlyLogicalOverride']
    require(hashlib.sha256(read(ROOT / 'AGENTS.md')).hexdigest() == override['authorizedCurrentSha256'],
            'unauthorized current guidance change')
    archived = ROOT / relative(override['archivePath'])
    require(hashlib.sha256(read(archived)).hexdigest() == override['archivedSha256'], 'archived guidance changed')
    module = legacy(value)
    original = module.verify_inputs
    context = dict(original.__globals__, ROOT=ArchiveRoot(ROOT, archived))
    guard = types.FunctionType(original.__code__, context, original.__name__,
                               original.__defaults__, original.__closure__)
    # Exactly the old guard code and hashes; only its read context is archival.
    protocol = guard()
    require(module.ROOT == ROOT, 'live historical module was mutated')
    return dict(mode='ARCHIVAL_READ_ONLY', logicalOverrides=['AGENTS.md'], protocol=protocol,
                currentNativeAdmission=False, nativeExecutionOccurred=False)
