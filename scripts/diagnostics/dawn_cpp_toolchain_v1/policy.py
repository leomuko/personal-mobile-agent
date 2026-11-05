"""Pure fail-closed metadata policy; no native execution or C++ compilation."""
from pathlib import Path
import re
import shlex


def require(ok,message):
    if not ok:raise ValueError(message)


def native_graph(nodes,root,system_paths,present=lambda p:Path(p).is_file(),resolve=lambda p:str(Path(p).resolve())):
    root=resolve(root);todo=[(root,())];seen=set();result={}
    while todo:
        path,inherited=todo.pop();key=(path,inherited)
        if key in seen:continue
        seen.add(key);require(len(seen)<=128,'native graph context ceiling')
        require(path in nodes,'unfrozen native input: '+path);raw=nodes[path]['rawMachO'];local=[]
        def expand(value):
            if value.startswith('@loader_path/'):return str(Path(path).parent/value[13:])
            if value.startswith('@executable_path/'):return str(Path(root).parent/value[17:])
            require(not value.startswith('@'),'unsupported rpath');return value
        for value in raw['rpaths']:
            local.append(expand(value))
        search=tuple(dict.fromkeys(local+list(inherited)));require(len(search)<=64,'native rpath ceiling')
        edges=[]
        for dep in raw['dependencies']:
            if dep.startswith(('/usr/lib/','/System/Library/')):
                require(dep in system_paths,'unknown system-cache dependency: '+dep)
                edges.append(dict(name=dep,kind='PINNED_SYSTEM_CACHE'));continue
            if dep.startswith('@rpath/'):candidates=[str(Path(p)/dep[7:]) for p in search]
            else:candidates=[expand(dep)]
            found={resolve(p) for p in candidates if present(p)}
            require(len(found)==1,'missing/ambiguous native dependency: '+dep)
            child=found.pop();require(child in nodes,'unfrozen native dependency: '+child)
            edges.append(dict(name=dep,kind='BUNDLED',path=child));todo.append((child,search))
        previous=result.get(path)
        require(previous is None or previous==edges,'context-dependent native supplier')
        result[path]=edges
    return dict(executable=root,nodes=result)


def include_search(text,expected):
    lines=text.splitlines();start='#include <...> search starts here:';end='End of search list.'
    require(lines.count(start)==1 and lines.count(end)==1,'missing/duplicate include-search markers')
    first=lines.index(start)+1;last=lines.index(end);require(first<=last,'reversed include markers')
    observed=[]
    for line in lines[first:last]:
        value=line.strip();framework=value.endswith(' (framework directory)')
        if framework:value=value[:-22]
        require(value.startswith('/') and not any(c in value for c in ('\x00','\n')),'invalid include path')
        observed.append(dict(path=str(Path(value).resolve()),framework=framework))
    require(observed==expected,'unexpected include order/root')
    require(not any(re.search(r'(^|\s)(?:fatal )?error:|warning:',x) for x in lines),'metadata diagnostics')
    return observed


def reject_diagnostics(text):
    require(len(text.encode())<=256*1024 and not re.search(r"(?:^|[\s:])(?:warning|error|fatal error):",text,re.I),'metadata diagnostic/byte ceiling')


def compiler_identity(text,installed,target_pattern):
    reject_diagnostics(text);lines=[x.strip() for x in text.splitlines() if x.strip()]
    require(len(lines)==4 and re.fullmatch(r'Apple clang version [0-9]+(?:\.[0-9]+)+(?: \([^\r\n]*\))?',lines[0]) is not None,'unexpected compiler version structure')
    expected=['Target: ','Thread model: ','InstalledDir: ']
    for line,key in zip(lines[1:],expected):require(line.startswith(key),'missing/duplicate compiler identity field')
    target=lines[1][len(expected[0]):];require(re.fullmatch(target_pattern,target) is not None,'unexpected compiler identity target')
    require(lines[2]=='Thread model: posix' and str(Path(lines[3][len(expected[2]):]).resolve())==installed,'unexpected compiler identity location/thread model')
    return dict(version=lines[0],target=target,threadModel='posix',installedDir=installed)


# Closed, pre-query option schema. An unlisted real default is a blocking result,
# never permission to expand the schema automatically from a native transcript.
CC_SINGLE=set(('-cc1 -emit-obj -fcxx-exceptions -fexceptions -pedantic-errors -O0 -disable-free -clear-ast-before-backend -disable-llvm-verifier -discard-value-names -mconstructor-aliases -pic-is-pie -fno-rounding-math -mrelax-all -funwind-tables=2 -fno-strict-return -fno-omit-frame-pointer -fencode-extended-block-signature -fblocks -fregister-global-dtors-with-atexit -fdeprecated-macro -fgnuc-version=4.2.1 -fmax-type-align=16 -fno-implicit-modules -fno-modules -faddrsig -D__GCC_HAVE_DWARF2_CFI_ASM=1 -fno-color-diagnostics -nostdsysteminc -nobuiltininc -nostdinc++ -fmath-errno -fno-math-errno -fno-signed-zeros -fno-trapping-math -fno-verbose-asm -fno-use-cxa-atexit -fno-common -fno-delete-null-pointer-checks -fskip-odr-check-in-gmf').split())
CC_VALUES={'-mrelocation-model':{'pic'},'-pic-level':{'2'},'-mframe-pointer':{'non-leaf','all','none'},'-ffp-contract':{'on','off','fast'},'-ferror-limit':{'19'},'-stack-protector':{'1'},'-debugger-tuning':{'lldb'},'-target-cpu':{'apple-m1','generic'},'-target-abi':{'darwinpcs'}}
TARGET_FEATURES=set(('v8.1a v8.2a v8.3a v8.4a v8.5a v8.6a v8a neon fp-armv8 aes sha2 sha3 sm4 crc fullfp16 fp16fml dotprod ras lse rdm rcpc rand i8mm bf16 pauth perfmon ccdp dit ssbs predres fptoint f64mm fuse-aes').split())


def driver_jobs(text,clang,linker,sdk,resource,expected=None):
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
    require(str(Path(cc[0]).resolve())==clang and str(Path(ld[0]).resolve())==linker,'unexpected compiler/linker helper')
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
    require(str(Path(ldopts.get('-lto_library','')).resolve())==str(Path(linker).parent.parent/'lib/libLTO.dylib'),'unexpected LTO helper')
    require(str(Path(ldopts.get('-o','')).resolve())==output and libraries==['-lc++','-lSystem'],'unexpected output/runtime selection')
    platform=ldopts.get('-platform_version');require(platform is not None and platform[0]=='macos' and platform[1] in ('26.5','26.5.0') and platform[2] in ('26.5','26.5.0'),'unexpected linker platform version')
    runtime=str(Path(resource)/'lib/darwin/libclang_rt.osx.a')
    require(files in ([str(object_path)],[str(object_path),runtime]),'unfrozen linker input/archive')
    return jobs
