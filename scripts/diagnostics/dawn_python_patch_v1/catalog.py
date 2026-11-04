#!/usr/bin/env python3
"""Read-only qualification catalog. Never runs Python candidates or compilers."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import resource
import selectors
import stat
import subprocess
import sys
import time

ROOT=Path(__file__).resolve().parents[3]
s=importlib.util.spec_from_file_location('dawn_catalog_owner',ROOT/'scripts/diagnostics/ml_drift_compatibility/analyze.py')
owner=importlib.util.module_from_spec(s);s.loader.exec_module(owner)
native_spec=importlib.util.spec_from_file_location('dawn_macho_metadata',Path(__file__).with_name('macho.py'))
macho=importlib.util.module_from_spec(native_spec);native_spec.loader.exec_module(macho)
require=owner.require
MIB=1024**2
FIX=ROOT/'planning/fixtures/dawn-python-patch-v1'
HOST_PYTHON=Path('/Applications/Xcode.app/Contents/Developer/Library/Frameworks/Python3.framework/Versions/3.9/bin/python3.9')
XCODE=Path('/Applications/Xcode.app/Contents/Developer')
CLANG=XCODE/'Toolchains/XcodeDefault.xctoolchain/usr/bin/clang'
OTOOL=(XCODE/'Toolchains/XcodeDefault.xctoolchain/usr/bin/otool').resolve()
SDK=XCODE/'Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk'
RESOURCE=XCODE/'Toolchains/XcodeDefault.xctoolchain/usr/lib/clang/21.0.0/include'
CACHES=Path('/System/Volumes/Preboot/Cryptexes/OS/System/Library/dyld')
EXTERNAL=ROOT/'build/repository-cache-v1/analysis-attempts/configured-1/x86_64-output/be85e5bf6162e53dd7e04b03defae4eb/external'
ABSL=EXTERNAL/'com_google_absl'
ADMISSION_MODE='python-generation-cpp-blocked-v1'


def file_row(path):
    p=Path(path);h=hashlib.sha256()
    with os.fdopen(os.open(p,os.O_RDONLY|os.O_NOFOLLOW),'rb') as source:
        before=os.fstat(source.fileno());require(stat.S_ISREG(before.st_mode),'not a regular catalog file')
        for block in iter(lambda:source.read(MIB),b''):h.update(block)
        after=os.fstat(source.fileno())
    identity=lambda st:(st.st_dev,st.st_ino,st.st_size,st.st_mode,st.st_mtime_ns,st.st_ctime_ns)
    require(identity(before)==identity(after)==identity(p.lstat()),'catalog file changed during hash')
    return dict(path=str(p),bytes=before.st_size,mode=stat.S_IMODE(before.st_mode),sha256=h.hexdigest())


def tree(root,maximum_entries,maximum_bytes):
    root=Path(root);rows={};total=0
    require(root.is_dir() and not root.is_symlink(),'invalid inventory root')
    def unreadable(error):raise error
    for directory,dirs,files in os.walk(root,followlinks=False,onerror=unreadable):
        dirs.sort();files.sort()
        for name in sorted(dirs+files):
            p=Path(directory)/name;relative=p.relative_to(root).as_posix()
            require(len(rows)<maximum_entries and len(relative.encode())<=1024,'tree name/entry ceiling')
            st=p.lstat();row=dict(mode=stat.S_IMODE(st.st_mode))
            if stat.S_ISLNK(st.st_mode):
                target=os.readlink(p);require(len(target.encode())<=1024,'link name ceiling')
                row.update(kind='symlink',target=target)
            elif stat.S_ISDIR(st.st_mode):row['kind']='directory'
            elif stat.S_ISREG(st.st_mode):
                total+=st.st_size;require(total<=maximum_bytes,'tree byte ceiling')
                row.update(kind='file',bytes=st.st_size,sha256=file_row(p)['sha256'])
            else:raise ValueError('special inventory member')
            rows[relative]=row
    return dict(root=str(root),files=rows,regularBytes=total)


def metadata_command(argv,maximum=MIB,seconds=15,trace=None,cwd=None,env=None):
    """Bounded pipe capture; children inherit the outer owned process group."""
    process=None;selector=None;data=bytearray();start=time.monotonic()
    try:
        process=subprocess.Popen(argv,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,cwd=cwd,env=env)
        selector=selectors.DefaultSelector()
        os.set_blocking(process.stdout.fileno(),False);selector.register(process.stdout,selectors.EVENT_READ)
        while selector.get_map():
            require(time.monotonic()-start<seconds,'metadata command deadline')
            for key,event in selector.select(.02):
                block=os.read(key.fileobj.fileno(),16384)
                if not block:selector.unregister(key.fileobj);continue
                remaining=maximum-len(data);data.extend(block[:remaining])
                require(len(block)<=remaining,'metadata output ceiling')
        process.wait(timeout=max(.001,seconds-(time.monotonic()-start)))
        require(time.monotonic()-start<seconds,'metadata command deadline')
        require(process.returncode==0,'metadata command failed: '+str(process.returncode))
        decoded=bytes(data).decode('utf-8',errors='strict')
    finally:
        if process is not None and process.poll() is None:
            try:process.kill()
            except ProcessLookupError:pass
            process.wait(timeout=2)
        if selector is not None:selector.close()
        if process is not None:process.stdout.close()
        if trace is not None:trace(argv,bytes(data),None if process is None else process.returncode,time.monotonic()-start)
    require(time.monotonic()-start<seconds,'metadata command deadline')
    return decoded


def native(path):
    with Path(path).open('rb') as source:magic=source.read(4)
    return magic in (b'\xcf\xfa\xed\xfe',b'\xfe\xed\xfa\xcf',b'\xce\xfa\xed\xfe',b'\xfe\xed\xfa\xce',
                     b'\xca\xfe\xba\xbe',b'\xbe\xba\xfe\xca',b'\xca\xfe\xba\xbf',b'\xbf\xba\xfe\xca')


def expand(value,loader,executable):
    if value.startswith('@loader_path/'):
        return loader/value[len('@loader_path/'):]
    if value.startswith('@executable_path/'):
        return executable/value[len('@executable_path/'):]
    return Path(value)


def inspector_header(text,path):
    lines=[line for line in text.splitlines() if line.strip()]
    require(lines and re.fullmatch(re.escape(str(path))+r'(?: \(architecture arm64e?\))?:',lines[0]),'native inspector header missing/mismatched')
    return lines[1:]


def inspected_commands(path,raw,inspect_command):
    output=inspect_command([str(OTOOL),'-arch','arm64','-l',str(path)])
    inspector_header(output,path)
    numbers=re.findall(r'^\s*Load command ([0-9]+)\s*$',output,re.M)
    require(numbers==[str(n) for n in range(raw['commandCount'])],'native inspector command count mismatch')
    names=re.findall(r'^\s+name (.+?) \(offset [0-9]+\)',output,re.M)
    rpaths=re.findall(r'^\s+path (.+?) \(offset [0-9]+\)',output,re.M)
    require(names==raw['loadNames'] and rpaths==raw['rpaths'],'native inspector/load-byte disagreement')
    return output


def closure(seeds,executable,allowed_roots,system_paths,inspect_command=metadata_command):
    executable=Path(executable);allowed_roots=[Path(p).resolve() for p in allowed_roots]
    executable=executable.resolve();initial_raw=macho.inspect(executable)
    inspected_commands(executable,initial_raw,inspect_command)
    inherited=initial_raw['rpaths']
    require(len(inherited)<=32 and all(len(x.encode())<=1024 for x in inherited),'native inherited rpath ceiling')
    inherited_bases=[expand(rp,executable.parent,executable.parent) for rp in inherited]
    todo=[Path(p).resolve() for p in seeds];rows={};total=0
    while todo:
        p=todo.pop()
        if str(p) in rows:continue
        require(len(rows)<128,'native closure member ceiling')
        require(any(p.is_relative_to(q) for q in allowed_roots),'native artifact outside allowed roots')
        row=file_row(p);total+=row['bytes'];require(total<=1024*MIB,'native artifact byte ceiling')
        raw=macho.inspect(p)
        listing=inspect_command([str(OTOOL),'-arch','arm64','-L',str(p)])
        listed=[]
        for line in inspector_header(listing,p):
            match=re.fullmatch(r'\s+(.+?) \(compatibility version [^)]+\)',line)
            require(match is not None,'unparsed native inspector dependency row');listed.append(match.group(1))
        require(listed==raw['libraryNames'],'native list/load-byte disagreement')
        commands=inspected_commands(p,raw,inspect_command)
        require(file_row(p)==row,'native input changed during inspection')
        dependencies=raw['dependencies'];rpaths=raw['rpaths']
        require(len(dependencies)<=256 and len(rpaths)<=32 and
                all(len(x.encode())<=1024 for x in dependencies+rpaths),'native load-command ceiling')
        edges=[]
        for dep in dependencies:
            if dep.startswith(('/usr/lib/','/System/Library/')):
                require(dep in system_paths,'system dependency not in pinned cache map: '+dep)
                edges.append(dict(installName=dep,kind='system-cache'));continue
            candidates=[]
            if dep.startswith('@rpath/'):
                for base in [expand(rp,p.parent,executable.parent) for rp in rpaths]+inherited_bases:
                    if not str(base).startswith('@'):candidates.append(base/dep[len('@rpath/'):])
            else:candidates=[expand(dep,p.parent,executable.parent)]
            found={q.resolve() for q in candidates if q.is_file()}
            require(len(found)==1,'missing/ambiguous native dependency: '+dep)
            target=found.pop();require(any(target.is_relative_to(q) for q in allowed_roots),'escaping native dependency')
            edges.append(dict(installName=dep,kind='bundled',path=str(target)));todo.append(target)
        row.update(dependencies=edges,rpaths=rpaths,macho=raw,inspectorTextSha256=hashlib.sha256(commands.encode()).hexdigest())
        rows[str(p)]=row
    return dict(files=rows,totalBytes=total)


def write(path,value,maximum=32*MIB):
    total=0
    with Path(path).open('x') as sink:
        for part in json.JSONEncoder(sort_keys=True,ensure_ascii=False,separators=(',',':')).iterencode(value):
            total+=len(part.encode());require(total+1<=maximum,'catalog serialization ceiling');sink.write(part)
        sink.write('\n')


def prepare(attempt):
    trace_bytes=0
    def trace(argv,data,code,elapsed):
        nonlocal trace_bytes
        row=dict(argv=argv,outputBase64=base64.b64encode(data).decode(),exitCode=code,elapsedSeconds=elapsed)
        encoded=(json.dumps(row,separators=(',',':'))+'\n').encode();trace_bytes+=len(encoded)
        require(trace_bytes<=32*MIB,'inspector trace byte ceiling')
        with (attempt/'inspector-trace.jsonl').open('ab') as sink:sink.write(encoded)
    inspect=lambda argv:metadata_command(argv,trace=trace)
    inputs=json.loads((FIX/'candidate-inputs.json').read_text())
    runtime_spec=ROOT/inputs['runtimeInventory']['path']
    require(file_row(runtime_spec)['sha256']==inputs['runtimeInventory']['sha256'],'runtime specification changed')
    declared=json.loads(runtime_spec.read_text());runtime=tree(Path(declared['sourceRoot']),10000,128*MIB)
    require(runtime['files']==declared['files'],'runtime tree drift')
    block=json.loads((FIX/'cpp-prerequisite-block.json').read_text())
    require(block['status']=='BLOCKED' and block['acquisitionAuthorized'] is False and
            block['archive']['sha256']=='6e1aee535473414164bf83e4ebc40240dec71a4701f8a642d906e95bea1aea0c','invalid C++ prerequisite block')
    tools={str(p):file_row(p) for p in [OTOOL,Path('/usr/bin/sandbox-exec'),Path('/usr/bin/sw_vers'),Path('/usr/lib/dyld')]}
    require(inspect(['/usr/bin/sw_vers','-productVersion']).strip()=='26.6.2' and
            inspect(['/usr/bin/sw_vers','-buildVersion']).strip()=='25G83','OS profile changed')
    cache_files=sorted(p for p in CACHES.iterdir() if p.name.startswith('dyld_shared_cache_arm64e') and p.is_file())
    require(0<len(cache_files)<=16 and sum(p.stat().st_size for p in cache_files)<=8*1024*MIB,'system cache size/count ceiling')
    cache_rows=[file_row(p) for p in cache_files]
    cache_map=CACHES/'dyld_shared_cache_arm64e.map'
    require(cache_map.stat().st_size<=8*MIB,'system cache map ceiling')
    system_paths={line.strip() for line in cache_map.read_text().splitlines() if line.startswith('/')}
    require('/usr/lib/libSystem.B.dylib' in system_paths,'system cache map format unsupported')
    runtime_root=Path(declared['sourceRoot']);seeds=[runtime_root/name for name,row in runtime['files'].items() if row['kind']=='file' and native(runtime_root/name)]
    runtime_closure=closure(seeds,runtime_root/'bin/python3.13',[runtime_root],system_paths,inspect)
    inspector_closure=closure([OTOOL],OTOOL,[XCODE.parent],system_paths,inspect)
    combined={**runtime_closure['files'],**inspector_closure['files']}
    require(len(combined)<=128 and sum(row['bytes'] for row in combined.values())<=1024*MIB,'combined native closure ceiling')
    peak=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    peak=int(peak if sys.platform=='darwin' else peak*1024)
    require(peak<=512*MIB,'preparation peak RSS ceiling')
    result=dict(protocol='dawn-python-patch-preparation-v1',admissionMode=ADMISSION_MODE,runtime=runtime,tools=tools,
                systemCaches=cache_rows,OS=dict(version='26.6.2',build='25G83'),
                runtimeNativeClosure=runtime_closure,inspectorNativeClosure=inspector_closure,
                compilerPrerequisites=block,compilerNativeClosure=None,toolchainInventories=[],
                peakRssBytes=peak,inspectorTraceBytes=trace_bytes,vendorCodeExecuted=False,
                wheelsAcquired=False,compilerExecuted=False,adopted=False)
    write(attempt/'prepared-catalog.json',result)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--freeze',type=Path,required=True);p.add_argument('--sha256',required=True)
    p.add_argument('--attempt',type=Path,required=True);p.add_argument('--worker',action='store_true')
    a=p.parse_args();created=False;outcome=None;ready=False
    try:
        require(owner.digest(a.freeze)==a.sha256,'preparation freeze changed')
        f=json.loads(a.freeze.read_text());owner.verify_files(f['inputs'])
        required={str(Path(__file__).resolve()),str(Path(owner.__file__).resolve()),str(Path(macho.__file__).resolve()),str(HOST_PYTHON),
                  str(OTOOL),str(FIX/'cpp-prerequisite-block.json'),str(FIX/'candidate-inputs.json'),str(FIX/'candidate-runtime-inventory.json')}
        require(f['python']==str(HOST_PYTHON) and required<={row['path'] for row in f['inputs']},'unbound preparation tools/inputs')
        require(f['protocol']=='dawn-python-patch-preparation-v1' and f['implementationSha256']==owner.digest(__file__) and
                f['admissionMode']==ADMISSION_MODE,'preparation implementation/scope changed')
        require(a.attempt.resolve().is_relative_to(Path(f['attemptParent']).resolve()),'invalid preparation path')
        if a.worker:prepare(a.attempt);return 0
        require(not a.attempt.exists() and __import__('shutil').disk_usage(ROOT).free>=1024*MIB,'attempt exists/needs1GiB')
        a.attempt.mkdir(parents=True);created=True
        args=[f['python'],'-I','-S','-B',str(Path(__file__).resolve()),'--worker','--freeze',str(a.freeze.resolve()),'--sha256',a.sha256,'--attempt',str(a.attempt.resolve())]
        outcome=owner.run_owned(args,ROOT,a.attempt/'worker',f['environment'],300,2*MIB,15)
        require(outcome['classification']=='ANALYSIS_COMMAND_SUCCEEDED' and outcome['cleanupConfirmed'] is True,'preparation failed/cleanup uncertain')
        owner.verify_files(f['inputs'])
        require((a.attempt/'prepared-catalog.json').stat().st_size<=32*MIB,'oversized prepared catalog')
        write(a.attempt/'accepted.pending.json',dict(freezeSha256=a.sha256,catalog=file_row(a.attempt/'prepared-catalog.json'),cleanupConfirmed=True,
            admissionMode=ADMISSION_MODE,compilerQualified=False,vendorExecuted=False),65536);ready=True
    except (ValueError,OSError,KeyError,KeyboardInterrupt,subprocess.SubprocessError) as e:
        if outcome is None:outcome=dict(classification='OWNER_FAILED_OR_INTERRUPTED',cleanupConfirmed=None)
        outcome['failure']=type(e).__name__+': '+str(e)[:2048];print(outcome['failure'],file=sys.stderr);return 1
    finally:
        if created:
            outcome['freezeSha256']=a.sha256;write(a.attempt/'outcome.json',outcome,65536)
    if ready:os.link(a.attempt/'accepted.pending.json',a.attempt/'accepted.json');return 0
    return 1


if __name__=='__main__':raise SystemExit(main())
