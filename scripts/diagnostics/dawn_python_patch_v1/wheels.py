"""Inspect wheel data only; no imports, installation or artifact acquisition."""
import base64
import csv
from email.parser import Parser
import hashlib
import io
import os
from pathlib import Path
import re
import stat
import zipfile

MIB = 1024**2


def require(ok, message):
    if not ok: raise ValueError(message)


def digest_file(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as source:
        for data in iter(lambda: source.read(65536), b''): h.update(data)
    return h.hexdigest()


def canonical(name, directory=False):
    require(name.isascii() and 0 < len(name.encode()) <= 1024 and
            all(32 <= ord(c) < 127 for c in name) and '\\' not in name, 'unsafe wheel path')
    if directory and name.endswith('/'): name = name[:-1]
    require(not name.startswith('/') and all(p not in ('', '.', '..') for p in name.split('/')), 'unsafe wheel path')
    require(not any(p.endswith('.data') for p in name.split('/')) and
            not name.endswith('.pth') and name.split('/')[-1] not in ('sitecustomize.py','usercustomize.py'),
            'wheel installation hook/scheme forbidden')
    return name


def headers(data):
    require(len(data) <= MIB, 'wheel metadata ceiling')
    value = Parser().parsestr(data.decode('utf-8', errors='strict'))
    require(not value.defects, 'malformed wheel metadata')
    return value


def exactly(value, key):
    rows = value.get_all(key, [])
    require(len(rows) == 1, 'missing/duplicate wheel field: '+key)
    return rows[0]


def inspect(path, expected):
    """expected is immutable manifest data, never inferred from the wheel."""
    require(expected.get('selectedExtras', []) == [], 'selected extras forbidden')
    path = Path(path); fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as raw:
        start = os.fstat(raw.fileno())
        require(stat.S_ISREG(start.st_mode) and start.st_size == expected['bytes'], 'wheel byte size mismatch')
        h = hashlib.sha256()
        for block in iter(lambda: raw.read(65536), b''): h.update(block)
        require(h.hexdigest() == expected['sha256'], 'wheel SHA256 mismatch')
        raw.seek(0); records = {}; kept = {}; folded = set(); total = 0; native = []
        with zipfile.ZipFile(raw) as z:
            entries = z.infolist(); require(0 < len(entries) <= 512, 'wheel entry ceiling')
            for entry in entries:
                name = canonical(entry.orig_filename, entry.is_dir())
                require(name.casefold() not in folded, 'duplicate/case-fold wheel member'); folded.add(name.casefold())
                require(not entry.flag_bits & 1 and entry.compress_type in (zipfile.ZIP_STORED,zipfile.ZIP_DEFLATED), 'unsupported ZIP encoding')
                mode = entry.external_attr >> 16
                require(stat.S_IFMT(mode) in (0,stat.S_IFREG,stat.S_IFDIR) and
                        (stat.S_IFMT(mode) != stat.S_IFDIR or entry.is_dir()), 'wheel link/special member')
                if entry.is_dir(): require(entry.file_size == 0, 'nonempty wheel directory'); continue
                total += entry.file_size
                require(0 <= entry.file_size <= 8*MIB and total <= 16*MIB, 'wheel decoded ceiling')
                keep = name.split('/')[-1] in ('METADATA','WHEEL','RECORD')
                if keep: require(entry.file_size <= MIB, 'wheel metadata ceiling')
                data = bytearray() if keep else None; h = hashlib.sha256(); count = 0; first = b''
                with z.open(entry) as source:
                    for block in iter(lambda: source.read(65536), b''):
                        count += len(block); require(count <= entry.file_size, 'ZIP payload exceeds declaration')
                        if not first: first = block[:4]
                        h.update(block)
                        if data is not None: data.extend(block)
                require(count == entry.file_size, 'short ZIP payload')
                records[name] = dict(bytes=count,sha256=h.hexdigest(),crc32=entry.CRC)
                if data is not None: kept[name] = bytes(data)
                if first in (b'\xcf\xfa\xed\xfe', b'\xfe\xed\xfa\xcf', b'\xca\xfe\xba\xbe', b'\xbe\xba\xfe\xca'): native.append(name)
        dist = expected['distribution']+'-'+expected['version']+'.dist-info'
        actual_dist = {name.split('/')[0] for name in records if name.split('/')[0].endswith('.dist-info')}
        require(actual_dist == {dist}, 'wrong/ambiguous dist-info identity')
        for filename in ('METADATA','WHEEL','RECORD'): require(dist+'/'+filename in kept, 'wheel metadata missing')
        meta, wheel = headers(kept[dist+'/METADATA']), headers(kept[dist+'/WHEEL'])
        require(exactly(meta,'Name').lower() == expected['package'].lower() and exactly(meta,'Version') == expected['version'], 'distribution identity mismatch')
        require(exactly(wheel,'Wheel-Version') == '1.0' and exactly(wheel,'Root-Is-Purelib') == expected['purelib'] and
                wheel.get_all('Tag',[]) == [expected['tag']], 'unsupported wheel version/tag/ABI')
        require(exactly(meta,'Requires-Python') == expected['requiresPython'] and
                sorted(meta.get_all('Requires-Dist',[])) == sorted(expected['requiresDist']) and
                sorted(meta.get_all('Provides-Extra',[])) == sorted(expected['providesExtra']), 'unapproved dependency/extra predicate')
        seen = set(); record_name = dist+'/RECORD'
        for row in csv.reader(io.StringIO(kept[record_name].decode('utf-8')), strict=True):
            require(len(row) == 3 and len(seen) < 512, 'malformed RECORD row')
            name, digest, size = row; canonical(name)
            require(name in records and name not in seen, 'unrecorded/duplicate RECORD path'); seen.add(name)
            if name == record_name:
                require(digest == '' and size == '', 'RECORD self hash must be empty'); continue
            require(re.fullmatch(r'0|[1-9][0-9]*', size) and len(size) <= 10 and int(size) == records[name]['bytes'], 'RECORD size mismatch')
            require(re.fullmatch(r'sha256=[A-Za-z0-9_-]{43}', digest), 'unsupported RECORD hash')
            expected_digest = base64.urlsafe_b64encode(bytes.fromhex(records[name]['sha256'])).rstrip(b'=').decode()
            require(digest == 'sha256='+expected_digest, 'RECORD hash mismatch')
        require(seen == set(records), 'unrecorded wheel payload')
        end = os.fstat(raw.fileno()); current = os.lstat(path)
        identity = lambda s:(s.st_dev,s.st_ino,s.st_size,s.st_mtime_ns,s.st_ctime_ns)
        require(identity(start) == identity(end) == identity(current) and not path.is_symlink(), 'wheel changed during inspection')
    return dict(artifact=expected,files=records,entries=len(entries),decodedBytes=total,
                nativeMembers=native,byteVerified=True,recordVerified=True,executed=False,installed=False)
