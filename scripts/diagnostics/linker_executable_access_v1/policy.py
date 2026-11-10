"""Diagnostic-only parser; never repairs output or qualifies the real linker.

The closed job grammar is a local copy of the unchanged metadata grammar.
Only literal synthetic/bare supplier comparison and the real LTO input differ.
Raw transcripts are preserved unchanged. Negative diagnostics are separately parsed.
"""
import re
import shlex
from pathlib import Path
import importlib.util
s=importlib.util.spec_from_file_location('access_policy_plan',Path(__file__).with_name('plan.py'))
plan=importlib.util.module_from_spec(s);s.loader.exec_module(plan)
old=plan.q.policy
require=plan.require
CC_SINGLE,CC_VALUES,TARGET_FEATURES=old.CC_SINGLE,old.CC_VALUES,old.TARGET_FEATURES
reject_diagnostics,compiler_identity=old.reject_diagnostics,old.compiler_identity

def diagnostic_jobs(text,clang,linker,sdk,resource,expected=None):
    reject_diagnostics(text);require(expected is not None,'missing complete driver contract')
    source=str(Path(expected['source']).resolve());output=str(Path(expected['output']).resolve());working=str(Path(source).parent)
    jobs=[];header=[]
    for line in text.splitlines():
        line=line.strip()
        if not line:continue
        if line.startswith('"'):
            argv=shlex.split(line);require(argv and not any(x.startswith('@') for x in argv),'driver response file');jobs.append(argv)
        elif line=='(in-process)':continue
        else:header.append(line)
    compiler_identity('\n'.join(header),str(Path(clang).parent),r'arm64-apple-macosx?26\.5(?:\.0)?')
    require(len(jobs)==2,'unexpected driver job count');cc,ld=jobs
    require(cc[0]==clang and ld[0]==linker,'unexpected literal compiler/diagnostic supplier')
    selected={};includes=[];features=set();inputs=[];i=1
    def add(key,value):
        require(key not in selected,'duplicate/contradictory driver option: '+key)
        opposite=('-f'+key[5:]) if key.startswith('-fno-') else ('-fno-'+key[2:] if key.startswith('-f') else None)
        require(opposite not in selected,'opposing frontend options')
        selected[key]=value
    while i<len(cc):
        token=cc[i];i+=1
        if token in CC_SINGLE:add(token,True)
        elif token in ('-triple','-isysroot','-resource-dir','-o','-x','-main-file-name','-dumpdir','-I','-isystem','-internal-isystem','-internal-externc-isystem','-iframework') or token in CC_VALUES or token=='-target-feature':
            require(i<len(cc),'missing driver option value');value=cc[i];i+=1
            if token in ('-I','-isystem','-internal-isystem','-internal-externc-isystem','-iframework'):includes.append(dict(path=str(Path(value).resolve()),framework=token=='-iframework'));continue
            if token=='-target-feature':require(value[:1] in ('+','-') and value[1:] in TARGET_FEATURES and value not in features and ('-' if value[0]=='+' else '+')+value[1:] not in features,'unknown/duplicate target feature');features.add(value);continue
            if token in CC_VALUES:require(value in CC_VALUES[token],'unfrozen compiler scalar option')
            if token=='-dumpdir':
                normalized=str(Path(value).resolve())
                require((value.endswith('/') and normalized==working) or normalized in (output,output+'-'),'unowned/unexpected dump directory/prefix')
            add(token,value)
        elif token.startswith('-std='):add('-std',token[5:])
        elif token.startswith(('-fdebug-compilation-dir=','-fcoverage-compilation-dir=')):
            key,value=token.split('=',1);require(str(Path(value).resolve())==working,'unexpected compiler working directory');add(key,value)
        elif token.startswith('-mlinker-version='):
            require(re.fullmatch(r'-mlinker-version=[0-9]+(?:\.[0-9]+){0,2}',token) is not None,'invalid linker-version declaration');add('-mlinker-version',token.split('=',1)[1])
        elif token.startswith('-'):raise ValueError('unfrozen frontend option: '+token)
        else:inputs.append(str(Path(token).resolve()))
    require(inputs==[source] and selected.get('-cc1') is True and selected.get('-emit-obj') is True and selected.get('-pedantic-errors') is True and selected.get('-O0') is True and selected.get('-fcxx-exceptions') is True and selected.get('-fexceptions') is True,'unexpected frontend input/action/exception mode')
    require(re.fullmatch(r'arm64-apple-macosx?26\.5(?:\.0)?',selected.get('-triple','')) is not None,'unexpected target triple')
    require(str(Path(selected.get('-isysroot','')).resolve())==sdk and str(Path(selected.get('-resource-dir','')).resolve())==resource,'unexpected compiler sysroot/resource')
    require(selected.get('-x')=='c++' and selected.get('-std')==expected['standard'] and selected.get('-main-file-name')==Path(source).name,'unexpected language/source profile')
    require(includes==expected['includes'],'unexpected frontend include order/root')
    object_path=Path(selected.get('-o','')).resolve()
    require(str(object_path.parent)==working and re.fullmatch(r'empty-[A-Za-z0-9_-]{1,64}\.o',object_path.name) is not None,'unowned/unexpected object path')
    ldopts={};files=[];libraries=[];i=1
    singles={'-demangle','-no_deduplicate','-dynamic','-adhoc_codesign','-pie'}
    while i<len(ld):
        token=ld[i];i+=1
        require(token not in ldopts,'duplicate linker option')
        if token in singles:ldopts[token]=True
        elif token in ('-arch','-syslibroot','-lto_library','-o'):
            require(i<len(ld),'missing linker option');ldopts[token]=ld[i];i+=1
        elif token=='-platform_version':
            require(i+2<len(ld),'missing linker platform');ldopts[token]=ld[i:i+3];i+=3
        elif token in ('-lc++','-lSystem'):require(token not in libraries,'duplicate runtime library');libraries.append(token)
        elif token.startswith('-'):raise ValueError('unfrozen linker option/library/framework: '+token)
        else:files.append(str(Path(token).resolve()))
    require(ldopts.get('-arch')=='arm64' and str(Path(ldopts.get('-syslibroot','')).resolve())==sdk,'unexpected linker target/sysroot')
    require(str(Path(ldopts.get('-lto_library','')).resolve())==str(plan.LD.parent.parent/'lib/libLTO.dylib'),'unexpected LTO helper')
    require(str(Path(ldopts.get('-o','')).resolve())==output and libraries==['-lc++','-lSystem'],'unexpected output/runtime selection')
    platform=ldopts.get('-platform_version');require(platform is not None and platform[0]=='macos' and platform[1] in ('26.5','26.5.0') and platform[2] in ('26.5','26.5.0'),'unexpected linker platform version')
    runtime=str(Path(resource)/'lib/darwin/libclang_rt.osx.a')
    require(files in ([str(object_path)],[str(object_path),runtime]),'unfrozen linker input/archive')
    return jobs


def classify(job, raw, exitcode):
    require(isinstance(raw, bytes) and len(raw)<=256*1024,'diagnostic byte ceiling/type')
    require(type(exitcode) is int and exitcode in (0,1),'unexpected exit-code type/value')
    text=raw.decode('utf-8',errors='strict')
    require('\x00' not in text and '\x1b' not in text,'diagnostic control bytes')
    target=next(x[len('--ld-path='):] for x in job['argv'] if x.startswith('--ld-path='))
    expected=dict(source=job['argv'][-3],output=job['argv'][-1],standard='c++20',
                  includes=plan.prior.plan.base_plan.includes('control'))
    error="clang: error: invalid linker name in argument '--ld-path="+target+"'"
    lines=text.splitlines()
    negative=(exitcode==1 and lines.count(error)==1)
    if negative:
        # Only omit the recognized diagnostic from the grammar input. Never alter
        # supplier names, options, quotations or retained raw output.
        remaining='\n'.join(line for line in lines if line!=error)
        diagnostic_jobs(remaining,str(plan.CLANG),'ld',str(plan.q.plan.SDK),str(plan.q.plan.RESOURCE),expected)
        return dict(classification='EXACT_SYNTHETIC_PATH_REJECTED',exitCode=1,qualified=False)
    require(exitcode==0,'unexpected command exit/diagnostic')
    diagnostic_jobs(text,str(plan.CLANG),target,str(plan.q.plan.SDK),str(plan.q.plan.RESOURCE),expected)
    return dict(classification='EXACT_SYNTHETIC_PATH_SELECTED',exitCode=0,qualified=False)


def comparison(rows):
    require(len(rows)<=2 and [r['id'] for r in rows]==['synthetic-exception-present','synthetic-exception-absent'][:len(rows)],
            'duplicate/reordered diagnostic cases')
    if len(rows)!=2 or rows[0].get('result',{}).get('classification')!='EXACT_SYNTHETIC_PATH_SELECTED':
        return 'INCONCLUSIVE'
    second=rows[1].get('result',{}).get('classification')
    if second=='EXACT_SYNTHETIC_PATH_REJECTED': return 'SUPPORTS_POLICY_SENSITIVE_VALIDATION_ONLY'
    if second=='EXACT_SYNTHETIC_PATH_SELECTED': return 'NO_SUPPORT_FOR_POLICY_COUPLING_IN_THIS_COMPARISON'
    return 'INCONCLUSIVE'
