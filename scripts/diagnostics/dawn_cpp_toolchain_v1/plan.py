"""Exact native metadata command recipe. This module executes no native tools."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
X=Path('/Applications/Xcode.app/Contents/Developer')
BIN=X/'Toolchains/XcodeDefault.xctoolchain/usr/bin'
CLANG=BIN/'clang';LD=BIN/'ld';OTOOL=BIN/'llvm-otool'
SDK=(X/'Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk').resolve()
RESOURCE=(BIN.parent/'lib/clang/21/include').resolve().parent
CPP=SDK/'usr/include/c++/v1'
PYTHON_STAGE=ROOT/'build/dawn-python-patch-v1/qualification-attempts/python-3'
ABSEIL=ROOT/'build/dawn-abseil-preparation-v1/attempts/prepare-1/candidate'
PROFILES=[(v,h,std) for h in ('ordinary','wire') for v,std in (('control','c++20'),('candidate','c++20'),('candidate','c++17'))]


def includes(variant):
    source=PYTHON_STAGE/(variant+'-repeat-0')
    return [dict(path=str(source/'_out/include'),framework=False),dict(path=str(ROOT/'build/dawn-cpp-toolchain-v1/checked-in-include'),framework=False),
            dict(path=str(ABSEIL),framework=False),dict(path=str(CPP),framework=False),
            dict(path=str(RESOURCE/'include'),framework=False),dict(path=str(SDK/'usr/include'),framework=False),
            dict(path=str(SDK/'System/Library/Frameworks'),framework=True)]


def flags(variant,std):
    args=['--driver-mode=g++','--no-default-config','-target','arm64-apple-macos26.5','-isysroot',str(SDK),
          '-resource-dir',str(RESOURCE),'-B'+str(BIN),'-stdlib=libc++','-fintegrated-as','-fno-modules','-fno-implicit-modules',
          '-nostdinc','-nostdinc++','-std='+std,'-pedantic-errors','-O0','-g0','-fno-color-diagnostics']
    for index,row in enumerate(includes(variant)):
        key='-I' if index<3 else ('-iframework' if row['framework'] else '-isystem')
        args.extend([key,row['path']])
    return args


def commands(attempt,native_files):
    attempt=Path(attempt);jobs=[]
    for path in sorted(native_files):
        for option in ('-L','-l'):
            jobs.append(dict(id='inspect-'+str(len(jobs)+1),kind='native-inspector',argv=[str(OTOOL),'-arch','arm64',option,path],subject=path,option=option))
    jobs += [dict(id='driver-version',kind='driver-version',argv=[str(CLANG),'--driver-mode=g++','--no-default-config','--version']),
             dict(id='resource-dir',kind='resource-dir',argv=[str(CLANG),'--driver-mode=g++','--no-default-config','-print-resource-dir']),
             dict(id='linker-path',kind='linker-path',argv=[str(CLANG),'--driver-mode=g++','--no-default-config','-B'+str(BIN),'-print-prog-name=ld'])]
    for variant,header,std in PROFILES:
        name=variant+'-'+header+'-'+std;common=[str(CLANG),*flags(variant,std)]
        jobs.append(dict(id='jobs-'+name,kind='driver-jobs',profile=name,expectedStandard=std,expectedIncludes=includes(variant),source=str(attempt/'empty.cpp'),output=str(attempt/(name+'.not-created')),argv=[*common,'-###','-x','c++',str(attempt/'empty.cpp'),'-o',str(attempt/(name+'.not-created'))]))
        jobs.append(dict(id='includes-'+name,kind='include-search',profile=name,expectedIncludes=includes(variant),argv=[*common,'-E','-v','-x','c++',str(attempt/'empty.cpp'),'-o','/dev/null']))
    return jobs
