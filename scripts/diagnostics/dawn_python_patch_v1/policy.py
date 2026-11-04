"""Pure admission and generated-output checks; no vendor execution."""
from pathlib import Path

HEADERS=(
 'include/dawn/webgpu.h','include/dawn/wire/client/webgpu.h','include/dawn/dawn_proc_table.h',
 'include/dawn/webgpu_cpp.h','include/dawn/wire/client/webgpu_cpp.h',
 'include/dawn/webgpu_cpp_print.h','include/dawn/wire/client/webgpu_cpp_print.h',
 'include/webgpu/webgpu_cpp_chained_struct.h')
AFFECTED=('include/dawn/webgpu_cpp.h','include/dawn/wire/client/webgpu_cpp.h')


def require(ok,message):
    if not ok:raise ValueError(message)


def candidate(facts):
    require(facts['version']==[3,13,13] and facts['implementation']=='cpython' and
            facts['machine']=='arm64' and facts['platform']=='darwin' and facts['bits']==64 and
            facts['SOABI']=='cpython-313-darwin' and facts['GILDisabled']==0,'wrong candidate interpreter/ABI')
    require(facts['isolated'] and facts['noSite'] and facts['noBytecode'],'candidate isolation flags missing')


def output_files(root):
    root=Path(root);names={};total=0
    for p in root.rglob('*'):
        require(not p.is_symlink(),'generated symlink forbidden')
        if p.is_file():
            name=p.relative_to(root).as_posix()
            require(name in HEADERS and len(names)<8,'extra generated output')
            size=p.stat().st_size;total+=size
            require(size<=8*1024**2 and total<=16*1024**2,'generated output ceiling')
            names[name]=p.read_bytes()
        else:require(p.is_dir(),'special generated output')
    require(set(names)==set(HEADERS),'missing generated output')
    return names


def transformed(data,operations):
    text=data.decode('utf-8',errors='strict')
    for operation in operations:
        require(text.count(operation['old'])==operation['proposedExpectedMatches'],'unexpected generated patch match count')
        text=text.replace(operation['old'],operation['new'])
    return text.encode('utf-8')


def compare_outputs(control,candidate_outputs,operations):
    require(set(control)==set(candidate_outputs)==set(HEADERS),'wrong generated inventory')
    for name in HEADERS:
        expected=transformed(control[name],operations) if name in AFFECTED else control[name]
        require(candidate_outputs[name]==expected,'generated control/candidate mismatch: '+name)


def repeatable(first,second):
    require(set(first)==set(second)==set(HEADERS) and all(first[n]==second[n] for n in HEADERS),'nonreproducible generated output')


def workspace(source,root,target,inventory):
    current=inventory(root,2048,32*1024**2)
    for name,row in source['files'].items():require(current['files'].get(name)==row,'generator modified source')
    allowed=set(HEADERS[:3] if target=='headers' else HEADERS)
    extra={name:row for name,row in current['files'].items() if name not in source['files']}
    emitted=set()
    for name,row in extra.items():
        require(name=='_out' or name.startswith('_out/'),'generator extra workspace output')
        if row['kind']=='directory':
            require(any(('_out/'+path).startswith(name+'/') for path in allowed),'extra generated directory')
        else:
            require(row['kind']=='file' and name[5:] in allowed,'extra generated file/link')
            emitted.add(name[5:])
    require(emitted==allowed,'missing generated target output')
