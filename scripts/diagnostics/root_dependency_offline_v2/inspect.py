#!/usr/bin/env python3
"""Bounded retained-source inspection. No vendor code or wheels are executed."""
import argparse
import gzip
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import posixpath
import re
import resource
import shutil
import stat
import struct
import sys
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('offline_v2_owner', ROOT / 'scripts/diagnostics/ml_drift_compatibility/analyze.py')
owner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(owner)
require = owner.require
PROTOCOL = 'root-dependency-offline-inspection-v2'
COMMIT = '466163dc7bf94146ac9a921b86cc3ed08184ebb8'
ARCHIVE_SHA = '4ee2687c6f4b2b1f30e70028763201c898a763989f7aafcb284e197ab0a4735b'
ARCHIVE_BYTES = 28737866
MIB = 1024**2
LIMITS = dict(headers=300000, names=128*MIB, frames=256*MIB, pax=65536,
              all_pax=8*MIB, decoded=2048*MIB, selected=32*MIB, records=4096,
              rss=256*MIB, receipt=32*MIB, hashing=768*MIB)
TABLE_SLOTS = 1048576
MAX_PROBES = 4096
WORKER_BUDGET = 512*MIB - 2*MIB - 2*65536
REQUIRED = ('LICENSE', 'generator/dawn_json_generator.py', 'generator/generator_lib.py',
            'generator/webgpu_docs_utility.py', 'generator/templates/api_cpp.h',
            'src/dawn/dawn.json', 'src/dawn/dawn_wire.json')
PACKAGES = (('Jinja2', '3.1.6', 'jinja2-3.1.6-py3-none-any.whl'),
            ('MarkupSafe', '3.0.3', 'markupsafe-3.0.3-cp313-cp313-macosx_11_0_arm64.whl'))


class Budget:
    def __init__(self, maximum=WORKER_BUDGET): self.maximum, self.used = maximum, 0
    def charge(self, count):
        require(0 <= count <= self.maximum-self.used, 'attempt output ceiling')
        self.used += count


def write_json(path, value, budget, maximum=32*MIB):
    # All objects have already bounded cardinality. Never create an unbounded
    # intermediate serialized copy, and reject before the over-budget write.
    total = 0
    encoder = json.JSONEncoder(ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(',', ':'))
    with path.open('xb') as out:
        for piece in encoder.iterencode(value):
            data = piece.encode('utf-8'); total += len(data)
            require(total <= maximum, 'receipt serialization ceiling')
            budget.charge(len(data)); out.write(data)
        budget.charge(1); require(total+1 <= maximum, 'receipt serialization ceiling'); out.write(b'\n')


class Names:
    """Fixed byte arrays: no Python object retained for each source member."""
    def __init__(self, slots=TABLE_SLOTS, probes=MAX_PROBES, charge=None):
        require(slots > 0 and slots & (slots-1) == 0, 'table power of two')
        self.slots, self.probes = slots, probes
        self.charge = charge or (lambda count: None)
        self.keys = bytearray(slots*32)
        self.occupied = bytearray((slots+7)//8)
        self.links = bytearray((slots+7)//8)
    @property
    def storage_bytes(self): return len(self.keys)+len(self.occupied)+len(self.links)
    def locate(self, name, insert=False, link=False):
        encoded = name.encode('utf-8'); self.charge(len(encoded))
        key = hashlib.sha256(encoded).digest()
        start = int.from_bytes(key[:8], 'big') & (self.slots-1)
        for step in range(min(self.probes, self.slots)):
            slot = (start+step) & (self.slots-1); byte, bit = slot//8, 1 << (slot%8)
            if not self.occupied[byte] & bit:
                if not insert: return False
                self.keys[slot*32:(slot+1)*32] = key
                self.occupied[byte] |= bit
                if link: self.links[byte] |= bit
                return False
            if self.keys[slot*32:(slot+1)*32] == key:
                require(not insert, 'duplicate source path/digest')
                return bool(self.links[byte] & bit)
        raise ValueError('duplicate table probe ceiling')


def rss_bytes():
    n = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return int(n if sys.platform == 'darwin' else n*1024)


def check_rss(): require(rss_bytes() <= LIMITS['rss'], 'worker peak RSS ceiling')


class Reader:
    def __init__(self, stream): self.stream, self.total = stream, 0
    def read(self, n):
        require(0 <= n <= MIB, 'parser read ceiling')
        data = self.stream.read(n); self.total += len(data)
        require(self.total <= LIMITS['decoded'], 'decoded archive ceiling')
        return data
    def exact(self, n):
        data = self.read(n); require(len(data) == n, 'truncated tar payload/header')
        return data


def text(raw):
    head, sep, tail = raw.partition(b'\0')
    require(not any(tail), 'nonzero bytes after tar string terminator')
    return head.decode('utf-8', errors='strict')


def octal(raw):
    value = raw.rstrip(b'\0 ').lstrip(b' ')
    require(not value or re.fullmatch(b'[0-7]+', value), 'invalid octal tar field')
    return int(value or b'0', 8)


def safe_text(value):
    require(value and len(value.encode('utf-8')) <= 1024 and
            all(ord(c) >= 32 and ord(c) != 127 for c in value), 'unsafe/long metadata text')
    return value


def canonical(value, trailing=False):
    safe_text(value)
    require('\\' not in value and not value.startswith('/'), 'unsafe source path')
    if trailing and value.endswith('/'): value = value[:-1]
    require(value and all(p not in ('', '.', '..') for p in value.split('/')), 'noncanonical source path')
    return value


def decimal(value, maximum):
    require(len(value) <= 10 and re.fullmatch(r'0|[1-9][0-9]*', value), 'invalid PAX unsigned decimal')
    n = int(value); require(n <= maximum, 'PAX numeric ceiling'); return n


def pax(data, global_header):
    result = {}; offset = 0
    allowed = {'comment', 'path', 'linkpath', 'size', 'mtime', 'atime', 'ctime',
               'uid', 'gid', 'uname', 'gname', 'hdrcharset'}
    while offset < len(data):
        space = data.find(b' ', offset, min(len(data), offset+12))
        require(space > offset, 'malformed PAX length')
        raw = data[offset:space]
        require(re.fullmatch(b'[1-9][0-9]*', raw), 'malformed PAX length')
        length = int(raw); end = offset+length
        require(end <= len(data) and end > space+2 and data[end-1:end] == b'\n', 'malformed PAX record')
        key, sep, value = data[space+1:end-1].partition(b'=')
        require(sep and 0 < len(key) <= 32 and 0 < len(value) <= 1024, 'PAX field ceiling/empty value')
        key, value = key.decode('utf-8'), value.decode('utf-8')
        require(key in allowed and key not in result and len(result) < 16, 'unsupported/duplicate PAX key')
        safe_text(value)
        require(not global_header or key not in {'path', 'linkpath', 'size'}, 'global reserved PAX override')
        if key == 'size': decimal(value, LIMITS['decoded'])
        if key in {'uid', 'gid'}: decimal(value, 2**32-1)
        if key in {'mtime', 'atime', 'ctime'}:
            require(len(value) <= 32 and re.fullmatch(r'-?(0|[1-9][0-9]*)(\.[0-9]{1,9})?', value), 'invalid PAX time')
        if key == 'hdrcharset': require(value == 'UTF-8', 'unsupported PAX charset')
        result[key] = value; offset = end
    require(offset == len(data) and result, 'empty/incomplete PAX payload')
    return result


def file_state(st): return (st.st_dev, st.st_ino, st.st_size, st.st_mtime_ns, st.st_ctime_ns)


def descriptor_digest(raw):
    raw.seek(0); h = hashlib.sha256()
    for block in iter(lambda: raw.read(MIB), b''): h.update(block)
    raw.seek(0); return h.hexdigest()


def scan(archive, output, budget, expected_sha=ARCHIVE_SHA, expected_bytes=ARCHIVE_BYTES, commit=COMMIT):
    metadata_hash_bytes = 0
    def hash_work(n):
        nonlocal metadata_hash_bytes
        metadata_hash_bytes += n
        require(metadata_hash_bytes <= LIMITS['hashing'], 'aggregate metadata hashing ceiling')
    prefix = 'dawn-'+commit; names = Names(charge=hash_work); selected = {}; counts = {}
    inventory = hashlib.sha256(); initial = b'edge-dawn-inventory-v2\n'; inventory.update(initial)
    hash_work(len(initial))
    frames, name_bytes, pax_bytes, selected_bytes, file_bytes, headers = len(initial), 0, 0, 0, 0, 0
    global_pax = {}; local_pax = None
    fd = os.open(archive, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as raw:
        before = os.fstat(raw.fileno())
        require(stat.S_ISREG(before.st_mode) and before.st_size == expected_bytes, 'source archive size/type changed')
        require(descriptor_digest(raw) == expected_sha, 'source archive hash changed')
        with gzip.GzipFile(fileobj=raw) as gz:
            reader = Reader(gz)
            while True:
                header = reader.exact(512)
                if not any(header):
                    require(not any(reader.exact(512)), 'missing second tar end block')
                    require(local_pax is None, 'unused local PAX header')
                    while True:
                        tail = reader.read(MIB)
                        if not tail: break
                        require(not any(tail), 'nonzero trailing archive data')
                    break
                headers += 1; require(headers <= LIMITS['headers'], 'archive header ceiling')
                hash_work(512)
                if headers % 1024 == 0: check_rss()
                require(header[257:263] in (b'ustar\0', b'ustar '), 'unsupported tar format')
                require(octal(header[148:156]) == sum(header[:148])+8*32+sum(header[156:]), 'tar checksum mismatch')
                mode, raw_size = octal(header[100:108]), octal(header[124:136])
                require(raw_size <= LIMITS['decoded'], 'tar size ceiling')
                # Validate numeric fields even when they are not materialized.
                for part in (header[108:116], header[116:124], header[136:148]): octal(part)
                path = text(header[:100]); head_prefix = text(header[345:500])
                if head_prefix: path = head_prefix+'/'+path
                link = text(header[157:257]); kind_byte = header[156:157]
                kinds = {b'0':'file', b'\0':'file', b'5':'directory', b'2':'symlink',
                         b'1':'hardlink', b'g':'pax-global', b'x':'pax-local'}
                require(kind_byte in kinds, 'unsupported tar member type')
                kind = kinds[kind_byte]; size = raw_size; payload_hash = None
                if kind.startswith('pax-'):
                    canonical(path); require(not link, 'PAX header link')
                    require(local_pax is None, 'chained/interrupted local PAX header')
                    pax_bytes += size
                    require(size <= LIMITS['pax'] and pax_bytes <= LIMITS['all_pax'], 'PAX payload ceiling')
                    data = reader.exact(size); hash_work(size); payload_hash = hashlib.sha256(data).hexdigest()
                    attrs = pax(data, kind == 'pax-global')
                    if kind == 'pax-global': global_pax.update(attrs)
                    else: local_pax = attrs
                else:
                    attrs = dict(global_pax); attrs.update(local_pax or {}); local_pax = None
                    if 'size' in attrs:
                        require(kind == 'file', 'PAX size on nonregular member'); size = decimal(attrs['size'], LIMITS['decoded'])
                    if 'linkpath' in attrs: require(kind in ('symlink','hardlink'), 'PAX linkpath on nonlink')
                    path, link = attrs.get('path', path), attrs.get('linkpath', link)
                    path = canonical(path, kind == 'directory')
                    require(path == prefix or path.startswith(prefix+'/'), 'source archive prefix changed')
                    path = path[len(prefix):].lstrip('/')
                    require(path or kind == 'directory', 'root must be directory')
                    if kind != 'file': require(size == 0 and raw_size == 0, 'nonregular member payload')
                    if kind in ('symlink','hardlink'):
                        safe_text(link); require('\\' not in link and not link.startswith('/'), 'unsafe source link')
                        if kind == 'hardlink':
                            canonical(link); require(link.startswith(prefix+'/'), 'hardlink outside archive prefix')
                        else:
                            resolved = posixpath.normpath(posixpath.join(prefix, posixpath.dirname(path), link))
                            require(resolved == prefix or resolved.startswith(prefix+'/'), 'escaping source symlink')
                    else: require(not link, 'link text on nonlink')
                    # The same fixed bit covers every non-directory ancestor,
                    # including an ordinary file encountered after descendants.
                    names.locate(path, insert=True, link=kind != 'directory')
                    if kind == 'file':
                        file_bytes += size; require(file_bytes <= LIMITS['decoded'], 'source file byte ceiling')
                        keep = path.startswith('generator/') or path in REQUIRED or PurePosixPath(path).name.startswith('LICENSE')
                        target = None
                        if keep:
                            selected_bytes += size
                            require(selected_bytes <= LIMITS['selected'] and len(selected) < LIMITS['records'], 'selected source ceiling')
                            budget.charge(size); target = output/path; target.parent.mkdir(parents=True, exist_ok=True)
                        h = hashlib.sha256(); left = size
                        sink = None
                        try:
                            if target: sink = os.fdopen(os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600), 'wb')
                            while left:
                                block = reader.exact(min(left, MIB)); h.update(block); left -= len(block)
                                if sink: sink.write(block)
                        finally:
                            if sink: sink.close()
                        payload_hash = h.hexdigest()
                        if keep: selected[path] = dict(bytes=size, sha256=payload_hash, mode=mode)
                if size % 512:
                    require(not any(reader.exact(512-size%512)), 'nonzero tar payload padding')
                # PAX path/link bytes are charged too; do not hide extension work.
                name_bytes += len(path.encode('utf-8'))+len(link.encode('utf-8'))
                require(name_bytes <= LIMITS['names'], 'aggregate name metadata ceiling')
                record = dict(headerSha256=hashlib.sha256(header).hexdigest(), kind=kind,
                              path=path, link=link, size=size, mode=mode, contentSha256=payload_hash)
                encoded = json.dumps(record, sort_keys=True, ensure_ascii=False, allow_nan=False, separators=(',', ':')).encode('utf-8')
                frames += 4+len(encoded); require(frames <= LIMITS['frames'], 'inventory metadata hashing ceiling')
                hash_work(4+len(encoded))
                inventory.update(struct.pack('>I', len(encoded))); inventory.update(encoded)
                counts[kind] = counts.get(kind, 0)+1
            decoded_bytes = reader.total
        require(all(p in selected for p in REQUIRED), 'required source/license/JSON missing')
        for path in selected:
            parents = ['', *[str(p) for p in PurePosixPath(path).parents if str(p) != '.']]
            require(not any(names.locate(p) for p in parents), 'selected source has non-directory ancestor')
        require(descriptor_digest(raw) == expected_sha and file_state(os.fstat(raw.fileno())) == file_state(before), 'source changed during scan')
        require(file_state(os.lstat(archive)) == file_state(before) and not Path(archive).is_symlink(), 'source path replaced')
    check_rss()
    return dict(protocol=PROTOCOL, completeSourceValidated=True, gzipEOFValidated=True,
                archive=dict(bytes=expected_bytes, sha256=expected_sha, commit=commit),
                inventorySha256=inventory.hexdigest(), headers=headers, types=counts,
                decodedBytes=decoded_bytes, fileBytes=file_bytes, nameBytes=name_bytes,
                framedMetadataBytes=frames, paxPayloadBytes=pax_bytes, selectedBytes=selected_bytes,
                metadataHashBytes=metadata_hash_bytes,
                selectedFiles=selected, duplicateTableBytes=names.storage_bytes,
                peakRssBytes=rss_bytes(), memberListRetained=False, sourceExecuted=False, adopted=False)


class MetadataOnly(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        raise ValueError('metadata redirect forbidden; exactly two GETs, no retries')


def package_metadata(attempt, budget):
    responses, wheels = [], []
    for package, version, filename in PACKAGES:
        url = 'https://pypi.org/pypi/'+package+'/'+version+'/json'
        path = attempt/(package.lower()+'-release.json'); h = hashlib.sha256(); count = 0
        with urllib.request.build_opener(MetadataOnly()).open(url, timeout=15) as src, path.open('xb') as out:
            while True:
                block = src.read(65536)
                if not block: break
                count += len(block); require(count <= MIB, 'package metadata byte ceiling')
                budget.charge(len(block)); out.write(block); h.update(block)
        body = json.loads(path.read_text(encoding='utf-8'))
        require(body['info']['name'].lower() == package.lower() and body['info']['version'] == version, 'package identity mismatch')
        candidates = [x for x in body['urls'] if x['filename'] == filename]
        require(len(candidates) == 1, 'missing/ambiguous wheel metadata')
        row = candidates[0]; address = urllib.parse.urlsplit(row['url'])
        require(not row['yanked'] and row['packagetype'] == 'bdist_wheel' and type(row['size']) is int and row['size'] > 0 and
                re.fullmatch('[0-9a-f]{64}', row['digests']['sha256']) and address.scheme == 'https' and
                address.hostname == 'files.pythonhosted.org' and address.port in (None,443) and
                address.username is None and address.password is None, 'invalid advertised wheel identity')
        responses.append(dict(path=str(path), url=url, bytes=count, sha256=h.hexdigest()))
        wheels.append(dict(package=package, version=version, filename=filename, bytes=row['size'],
                           sha256=row['digests']['sha256'], url=row['url'], identityStatus='METADATA_ONLY_BYTES_NOT_VERIFIED'))
    check_rss()
    return dict(responses=responses, advertisedWheels=wheels, wheelsDownloaded=False,
                wheelBytesVerified=False, interpreterCompatibility='NOT_VERIFIED')


def worker(freeze, attempt):
    budget = Budget()
    source = scan(Path(freeze['archive']), attempt/'selected-source', budget)
    write_json(attempt/'source-validation.json', source, budget, LIMITS['receipt'])
    metadata = package_metadata(attempt, budget)
    write_json(attempt/'package-metadata.json', metadata, budget)
    check_rss()


def load_freeze(path, expected):
    require(owner.digest(path) == expected, 'freeze hash mismatch')
    f = json.loads(path.read_text()); owner.verify_files(f['inputs'])
    bound = {x['path'] for x in f['inputs']}
    require(f['protocol'] == PROTOCOL and f['limits'] == LIMITS and
            {str(Path(__file__).resolve()), str(Path(owner.__file__).resolve()), f['python'], f['archive']} <= bound,
            'scope/input binding mismatch')
    require(f['archiveSha256'] == ARCHIVE_SHA and f['archiveBytes'] == ARCHIVE_BYTES and
            f['inspectionSeconds'] == 300 and f['cleanupSeconds'] == 15 and f['logBytes'] == 2*MIB,
            'frozen scope/resource mismatch')
    return f


def accept(freeze, attempt, outcome, freeze_sha):
    require(outcome['classification'] == 'ANALYSIS_COMMAND_SUCCEEDED' and outcome['exitCode'] == 0 and
            outcome['cleanupConfirmed'] is True, 'worker failed/cleanup uncertain')
    owner.verify_files(freeze['inputs'])
    source_path, metadata_path = attempt/'source-validation.json', attempt/'package-metadata.json'
    require(source_path.stat().st_size <= LIMITS['receipt'] and metadata_path.stat().st_size <= LIMITS['receipt'], 'receipt size')
    s = json.loads(source_path.read_text()); m = json.loads(metadata_path.read_text())
    require(s['completeSourceValidated'] is True and s['gzipEOFValidated'] is True and
            s['archive']['sha256'] == ARCHIVE_SHA and s['archive']['bytes'] == ARCHIVE_BYTES and
            len(m['advertisedWheels']) == 2 and m['wheelBytesVerified'] is False, 'incomplete worker receipt')
    owner.verify_files(m['responses'])
    for name, row in s['selectedFiles'].items():
        p = attempt/'selected-source'/canonical(name)
        require(p.is_file() and not p.is_symlink() and p.stat().st_size == row['bytes'] and owner.digest(p) == row['sha256'], 'selected source changed')
    return dict(protocol=PROTOCOL, freezeSha256=freeze_sha, cleanupConfirmed=True,
                sourceReceiptSha256=owner.digest(source_path), metadataReceiptSha256=owner.digest(metadata_path),
                sourceQualified=True, wheelMetadataQualified=True, wheelBytesVerified=False,
                interpreterCompatibility='NOT_VERIFIED', adopted=False)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--freeze', type=Path, required=True); p.add_argument('--sha256', required=True)
    p.add_argument('--attempt', type=Path, required=True); p.add_argument('--worker', action='store_true')
    a = p.parse_args(); created = False; outcome = None; ready = False
    try:
        f = load_freeze(a.freeze, a.sha256)
        require(a.attempt.resolve().is_relative_to(Path(f['attemptParent']).resolve()), 'attempt outside frozen parent')
        if a.worker: worker(f, a.attempt); return 0
        require(not a.attempt.exists(), 'preserve existing attempt')
        require(shutil.disk_usage(ROOT).free >= 1024**3, 'inspection needs 1GiB; analysis floor remains18GiB')
        a.attempt.mkdir(parents=True); created = True
        argv = [f['python'], str(Path(__file__).resolve()), '--worker', '--freeze', str(a.freeze.resolve()),
                '--sha256', a.sha256, '--attempt', str(a.attempt.resolve())]
        outcome = owner.run_owned(argv, ROOT, a.attempt/'worker', f['environment'], 300, 2*MIB, 15)
        outcome['freezeSha256'] = a.sha256
        accepted = accept(f, a.attempt, outcome, a.sha256)
        write_json(a.attempt/'accepted-receipt.pending.json', accepted, Budget(65536), 65536)
        ready = True
    except (ValueError, OSError, EOFError, KeyError, TypeError, UnicodeError, RecursionError,
            KeyboardInterrupt, owner.subprocess.SubprocessError) as e:
        if outcome is None: outcome = dict(classification='OWNER_FAILED_OR_INTERRUPTED', cleanupConfirmed=None)
        outcome['failure'] = type(e).__name__+': '+str(e)[:2048]
        print(outcome['failure'], file=sys.stderr); return 1
    finally:
        if created:
            outcome['freezeSha256'] = a.sha256
            write_json(a.attempt/'outcome.json', outcome, Budget(65536), 65536)
    if ready:
        # Atomic no-overwrite publication only after the complete owner outcome
        # is durable at the application level. No partial acceptance JSON.
        try: os.link(a.attempt/'accepted-receipt.pending.json', a.attempt/'accepted-receipt.json')
        except OSError as e:
            print('Acceptance publication failed: '+str(e), file=sys.stderr); return 1
        return 0
    return 1


if __name__ == '__main__': raise SystemExit(main())
