"""Complete bounded Abseil TAR validation and verified private materialization."""
import gzip
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import posixpath
import stat
import struct
import unicodedata

ROOT=Path(__file__).resolve().parents[3]
s=importlib.util.spec_from_file_location('abseil_tar_primitives',ROOT/'scripts/diagnostics/root_dependency_offline_v2/inspect.py')
primitives=importlib.util.module_from_spec(s);s.loader.exec_module(primitives)
require=primitives.require
MIB=1024**2
PREFIX='abseil-cpp-20260526.0'
SHA='6e1aee535473414164bf83e4ebc40240dec71a4701f8a642d906e95bea1aea0c'
LIMITS=dict(compressed=16*MIB,decoded=128*MIB,headers=10000,file=8*MIB,pax=65536,allPax=MIB,
            depth=64,names=12*MIB,frames=32*MIB,rss=512*MIB)
REQUIRED=('LICENSE','CMakeLists.txt','MODULE.bazel','absl/types/span.h','absl/base/BUILD.bazel',
          'absl/crc/internal/crc32_x86_arm_combined_simd.h')


class Reader:
    def __init__(self,stream,check):self.stream,self.check,self.total=stream,check,0
    def read(self,n):
        self.check();require(0<=n<=MIB,'archive read ceiling')
        block=self.stream.read(n);self.total+=len(block)
        require(self.total<=LIMITS['decoded'],'archive decoded ceiling');self.check();return block
    def exact(self,n):
        data=self.read(n);require(len(data)==n,'truncated tar header/payload');return data


def digest_file(raw,check):
    raw.seek(0);h=hashlib.sha256()
    for data in iter(lambda:raw.read(MIB),b''):check();h.update(data)
    raw.seek(0);return h.hexdigest()


def validate_tree(rows):
    require('' in rows and rows['']['kind']=='directory','missing archive root directory')
    for name,row in rows.items():
        if not name:continue
        primitives.canonical(name,row['kind']=='directory')
        parts=name.split('/');require(len(parts)<=LIMITS['depth'],'source depth ceiling')
        for index in range(1,len(parts)):
            ancestor='/'.join(parts[:index])
            require(ancestor in rows and rows[ancestor]['kind']=='directory','missing/non-directory source ancestor')
        if row['kind']=='symlink':
            target=posixpath.normpath(posixpath.join(posixpath.dirname(name),row['link']))
            require(target in rows,'dangling source link')
            visited={name}
            for _ in range(LIMITS['depth']):
                require(target not in visited,'cyclic source link');visited.add(target)
                if rows[target]['kind']!='symlink':break
                target=posixpath.normpath(posixpath.join(posixpath.dirname(target),rows[target]['link']))
                require(target in rows,'dangling source link')
            else:raise ValueError('source link-depth ceiling')
    for name in REQUIRED:require(name in rows and rows[name]['kind']=='file' and rows[name]['bytes']>0,'required source missing: '+name)


def scan(path,check=lambda:None,expected_sha=SHA,sink=None):
    """No tarfile member retention and no vendor code execution; all payloads hashed."""
    rows={};folded=set();global_pax={};local_pax=None
    counts={};headers=0;names=0;pax_bytes=0;frames=0
    inventory=hashlib.sha256(b'edge-abseil-inventory-v1\n')
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
    with os.fdopen(fd,'rb') as raw:
        before=os.fstat(raw.fileno())
        require(stat.S_ISREG(before.st_mode) and 0<before.st_size<=LIMITS['compressed'],'archive size/type ceiling')
        require(digest_file(raw,check)==expected_sha,'archive SHA256 mismatch')
        with gzip.GzipFile(fileobj=raw) as gz:
            reader=Reader(gz,check)
            while True:
                header=reader.exact(512)
                if not any(header):
                    require(not any(reader.exact(512)) and local_pax is None,'invalid archive end blocks/PAX')
                    while True:
                        tail=reader.read(MIB)
                        if not tail:break
                        require(not any(tail),'nonzero trailing archive data')
                    require(reader.total%512==0,'unaligned archive end');break
                headers+=1;require(headers<=LIMITS['headers'],'archive header ceiling')
                require(primitives.rss_bytes()<=LIMITS['rss'],'scanner RSS ceiling')
                require(header[257:263] in (b'ustar\0',b'ustar '),'unsupported tar format')
                require(primitives.octal(header[148:156])==sum(header[:148])+8*32+sum(header[156:]),'tar checksum mismatch')
                mode=primitives.octal(header[100:108]);size=primitives.octal(header[124:136])
                require(size<=LIMITS['decoded'] and mode & ~0o777==0,'invalid source size/mode')
                for field in [header[108:116],header[116:124],header[136:148]]:primitives.octal(field)
                name=primitives.text(header[:100]);prefix=primitives.text(header[345:500])
                if prefix:name=prefix+'/'+name
                link=primitives.text(header[157:257]);kind_byte=header[156:157]
                kinds={b'0':'file',b'\0':'file',b'5':'directory',b'2':'symlink',b'g':'pax-global',b'x':'pax-local'}
                require(kind_byte in kinds,'unsupported source member type (including hardlinks)')
                kind=kinds[kind_byte];primitives.canonical(name,kind=='directory')
                payload_hash=None;effective=name;source_size=size
                if kind.startswith('pax-'):
                    require(local_pax is None and not link,'invalid chained PAX/link')
                    pax_bytes+=size;require(size<=LIMITS['pax'] and pax_bytes<=LIMITS['allPax'],'PAX byte ceiling')
                    data=reader.exact(size);payload_hash=hashlib.sha256(data).hexdigest()
                    attrs=primitives.pax(data,kind=='pax-global')
                    if kind=='pax-global':global_pax.update(attrs)
                    else:local_pax=attrs
                else:
                    attrs=dict(global_pax);attrs.update(local_pax or {});local_pax=None
                    if 'size' in attrs:require(kind=='file','PAX size on nonregular source')
                    if 'linkpath' in attrs:require(kind=='symlink','PAX link on nonlink source')
                    size=primitives.decimal(attrs['size'],LIMITS['decoded']) if 'size' in attrs else size
                    effective=primitives.canonical(attrs.get('path',name),kind=='directory')
                    link=attrs.get('linkpath',link)
                    require(effective==PREFIX or effective.startswith(PREFIX+'/'),'source prefix mismatch')
                    effective=effective[len(PREFIX):].lstrip('/')
                    names+=len(effective.encode());require(names<=LIMITS['names'],'source names ceiling')
                    key=unicodedata.normalize('NFC',effective).casefold()
                    require(key not in folded,'duplicate/case/Unicode-collision source path');folded.add(key)
                    require(len(rows)<LIMITS['headers'],'source entry ceiling')
                    if kind=='symlink':
                        require(size==0,'nonempty symlink payload');primitives.safe_text(link)
                        require(not link.startswith('/') and '\\' not in link,'unsafe source link')
                        resolved=posixpath.normpath(posixpath.join(posixpath.dirname(effective),link))
                        require(resolved not in ('..','.') and not resolved.startswith('../'),'escaping source link')
                    else:require(not link,'link text on nonlink source')
                    if kind=='directory':require(size==0,'nonempty source directory')
                    if kind=='file':
                        require(size<=LIMITS['file'],'source file ceiling')
                        h=hashlib.sha256();remaining=size
                        if sink is not None:sink.begin(effective,size)
                        try:
                            while remaining:
                                block=reader.exact(min(MIB,remaining));h.update(block);remaining-=len(block)
                                if sink is not None:sink.feed(block)
                            payload_hash=h.hexdigest()
                        finally:
                            if sink is not None:sink.end()
                    rows[effective]=dict(kind=kind,bytes=size,mode=mode,link=link,sha256=payload_hash)
                padding=(-size)%512
                require(not any(reader.exact(padding)),'nonzero tar padding')
                record=dict(headerSha256=hashlib.sha256(header).hexdigest(),path=effective,kind=kind,mode=mode,
                            bytes=size,rawSize=source_size,link=link,sha256=payload_hash)
                encoded=json.dumps(record,sort_keys=True,ensure_ascii=False,allow_nan=False,separators=(',',':')).encode()
                frames+=4+len(encoded);require(frames<=LIMITS['frames'],'inventory framing ceiling')
                inventory.update(struct.pack('>I',len(encoded)));inventory.update(encoded)
                counts[kind]=counts.get(kind,0)+1
        check();after=os.fstat(raw.fileno());current=os.lstat(path)
        require(primitives.file_state(before)==primitives.file_state(after)==primitives.file_state(current),'archive changed during validation')
    validate_tree(rows)
    return dict(protocol='abseil-complete-source-v1',archiveSha256=expected_sha,archiveBytes=before.st_size,
                inventorySha256=inventory.hexdigest(),entries=rows,headerCount=headers,types=counts,
                decodedBytes=reader.total,framedBytes=frames,paxBytes=pax_bytes,gzipEOFValidated=True,
                completeSourceValidated=True,sourceExecuted=False,peakRssBytes=primitives.rss_bytes())


class FileSink:
    def __init__(self,root,rows,budget,check):self.root,self.rows,self.budget,self.check=Path(root),rows,budget,check;self.file=None
    def begin(self,name,size):
        self.check();require(name in self.rows and self.rows[name]['kind']=='file' and self.rows[name]['bytes']==size,'unvalidated staged member')
        p=self.root/name;self.file=os.fdopen(os.open(p,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600),'wb')
    def feed(self,data):self.check();self.budget.charge(len(data));self.file.write(data)
    def end(self):
        if self.file is not None:self.file.close();self.file=None


def materialize(path,receipt,output,budget,check=lambda:None):
    require(not Path(output).exists(),'preserve existing source destination');output=Path(output);output.mkdir(mode=0o700)
    rows=receipt['entries'];validate_tree(rows)
    for name,row in sorted(rows.items(),key=lambda pair:(pair[0].count('/'),pair[0])):
        check()
        if name and row['kind']=='directory':(output/name).mkdir(mode=0o700)
    observed=scan(path,check,receipt['archiveSha256'],FileSink(output,rows,budget,check))
    require({k:v for k,v in observed.items() if k!='peakRssBytes'}=={k:v for k,v in receipt.items() if k!='peakRssBytes'},'archive receipt changed before staging')
    for name,row in rows.items():
        if row['kind']=='symlink':budget.charge(len(row['link'].encode()));(output/name).symlink_to(row['link'])
    for name,row in rows.items():
        if not name:continue
        p=output/name;st=p.lstat()
        if row['kind']=='file':
            require(stat.S_ISREG(st.st_mode) and st.st_size==row['bytes'],'staged source type/size mismatch')
            with os.fdopen(os.open(p,os.O_RDONLY|os.O_NOFOLLOW),'rb') as source:
                require(digest_file(source,check)==row['sha256'],'staged source content mismatch')
        elif row['kind']=='directory':require(stat.S_ISDIR(st.st_mode),'staged directory changed')
        else:require(stat.S_ISLNK(st.st_mode) and os.readlink(p)==row['link'],'staged link changed')
    return dict(regularPermissions='0600',directoryPermissions='0700',sourceModesPreservedInReceipt=True,
                inventorySha256=receipt['inventorySha256'],sourceExecuted=False)
