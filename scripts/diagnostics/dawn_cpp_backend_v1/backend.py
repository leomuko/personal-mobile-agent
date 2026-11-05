"""Read-only alias/payload verification and exact, role-specific dispatch profiles."""
import importlib.util
import json
import os
from pathlib import Path
import stat
ROOT=Path(__file__).resolve().parents[3]
s=importlib.util.spec_from_file_location('cpp_backend_prior',ROOT/'scripts/diagnostics/dawn_cpp_toolchain_v1/qualify.py');old=importlib.util.module_from_spec(s);s.loader.exec_module(old)
c=old.c;require=old.require;now=old.now
ANCHOR=old.plan.X;WRAPPER=old.plan.OTOOL;BACKEND=old.plan.BIN/'otool-classic';COMPILER=old.plan.CLANG


def alias_receipt(path,anchor=ANCHOR):
    requested=Path(path).absolute();anchor=Path(anchor).resolve(strict=True)
    require(len(str(requested).encode())<=4096,'alias request ceiling')
    pending=list(requested.parts[1:]);current=Path('/');links=[];seen=set();steps=0
    while pending:
        steps+=1;require(steps<=256,'alias traversal ceiling')
        part=pending.pop(0)
        if part=='.':continue
        if part=='..':current=current.parent;continue
        candidate=current/part;entry=candidate.lstat()
        if stat.S_ISLNK(entry.st_mode):
            require(str(candidate) not in seen and len(links)<32,'cyclic/overlong alias chain');seen.add(str(candidate))
            target=os.readlink(candidate);require(0<len(target.encode())<=1024,'alias target ceiling')
            links.append(dict(path=str(candidate),target=target))
            value=Path(target)
            if value.is_absolute():current=Path('/');pending=list(value.parts[1:])+pending
            else:pending=list(value.parts)+pending
        else:
            require(stat.S_ISDIR(entry.st_mode) if pending else stat.S_ISREG(entry.st_mode),'unexpected alias component type')
            current=candidate
    require(current.is_relative_to(anchor),'native alias escapes pinned toolchain')
    require(0<current.stat().st_size<=1024**3,'native alias payload ceiling')
    payload=c.file_row(current)
    require(requested.resolve(strict=True)==current,'alias resolution changed during pin')
    for link in links:require(Path(link['path']).is_symlink() and os.readlink(link['path'])==link['target'],'alias changed during pin')
    return dict(requested=str(requested),anchor=str(anchor),resolved=str(current),links=links,file=payload)


def verify_aliases(receipts):
    for role,receipt in receipts.items():
        require(alias_receipt(receipt['requested'],receipt['anchor'])==receipt,'changed native alias/payload: '+role)


def expected_profiles(receipts):
    require(set(receipts)=={'wrapper','backend','compiler'},'dispatch role set changed')
    for role,expected in [('wrapper',WRAPPER),('backend',BACKEND),('compiler',COMPILER)]:
        require(receipts[role]['requested']==str(expected),'unapproved dispatch entrypoint')
    def paths(roles):return sorted({receipt[key] for role in roles for receipt in [receipts[role]] for key in ('requested','resolved')})
    return dict(inspector=paths(['wrapper','backend']),compiler=paths(['compiler']))


def check_profiles(catalog):
    require(catalog['executionProfiles']==expected_profiles(catalog['aliases']),'unapproved executable profile')
    require(len(catalog['executionProfiles']['inspector'])==2 and len(catalog['executionProfiles']['compiler'])==1,'unexpected alias dispatch expansion')
    require(catalog['aliases']['wrapper']['resolved']!=catalog['aliases']['backend']['resolved'],'backend aliases wrapper')
    verify_aliases(catalog['aliases'])
    require(catalog['conventionalAlias']['requested']==str(WRAPPER.parent/'otool'),'unexpected conventional alias')
    require(alias_receipt(catalog['conventionalAlias']['requested'],catalog['conventionalAlias']['anchor'])==catalog['conventionalAlias'],'changed conventional alias')


def allowed_paths(catalog,job):
    check_profiles(catalog)
    require(job['kind'] in {'native-inspector','driver-version','resource-dir','linker-path','driver-jobs','include-search'},'unknown metadata command role')
    role='inspector' if job['kind']=='native-inspector' else 'compiler'
    requested=str(Path(job['argv'][0]).absolute())
    require(requested==str(WRAPPER if role=='inspector' else COMPILER),'unexpected metadata entrypoint')
    return catalog['executionProfiles'][role]


def sandbox(attempt,paths):
    require(0<len(paths)<=2 and len(set(paths))==len(paths),'invalid exact dispatch set')
    quoted=lambda p:json.dumps(str(p))
    rules=''.join('(allow process-exec (literal '+quoted(p)+'))' for p in paths)
    return '(version 1)(allow default)(deny network*)(deny file-write*)(allow file-write* (subpath '+quoted(attempt)+'))(allow file-write* (literal "/dev/null"))(deny process-exec)'+rules


def dispatch(job,attempt,catalog,seconds,trace,environment,execute=None,absolute_deadline=None):
    execute=c.metadata_command if execute is None else execute
    require(0<seconds<=30,'dispatch budget outside frozen bounds')
    deadline=now()+seconds
    if absolute_deadline is not None:deadline=min(deadline,absolute_deadline)
    def left():
        value=deadline-now();require(value>0,'dispatch deadline');return min(value,30)
    left();paths=allowed_paths(catalog,job);left()
    argv=['/usr/bin/sandbox-exec','-p',sandbox(attempt,paths),*job['argv']]
    try:return execute(argv,maximum=256*1024,seconds=left(),trace=trace,cwd=attempt,env=environment)
    finally:check_profiles(catalog);left()
