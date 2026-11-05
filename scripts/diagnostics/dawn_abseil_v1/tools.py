"""Frozen trusted-shell operation; only private BUILD fixtures/source are writable."""
import importlib.util
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[3]
s=importlib.util.spec_from_file_location('abseil_tool_base',ROOT/'scripts/diagnostics/dawn_python_patch_v1/qualify.py')
base=importlib.util.module_from_spec(s);s.loader.exec_module(base)
catalog=base.catalog;require=base.require
COMMAND="sed -i -e 's|@googletest|@com_google_googletest|g' absl/*/BUILD* absl/*/*/BUILD* absl/*/*/*/BUILD*"
BASH=Path('/bin/bash');SED=Path('/usr/bin/sed');SANDBOX=Path('/usr/bin/sandbox-exec')


def verify_tools(pins):
    for p in (BASH,SED,SANDBOX):
        row=pins[str(p.resolve())];require(catalog.file_row(p.resolve())==row,'shell/tool pin changed')


def rewrite(root,attempt,pins,seconds=30,trace=None):
    require(not Path(root).is_symlink(),'rewrite root is a link')
    root,attempt=Path(root).resolve(),Path(attempt).resolve()
    require(root.is_relative_to(attempt),'unowned rewrite root')
    verify_tools(pins)
    profile=base.sandbox_text(attempt)
    args=[str(SANDBOX),'-p',profile,str(BASH),'--noprofile','--norc','-c',COMMAND]
    env=dict(PATH='/usr/bin:/bin',LANG='C',LC_ALL='C',HOME=str(attempt),TMPDIR=str(attempt))
    output=catalog.metadata_command(args,maximum=65536,seconds=seconds,trace=trace,cwd=root,env=env)
    verify_tools(pins)
    return dict(argv=args,command=COMMAND,stdout=output,sourceCodeExecuted=False,compilerExecuted=False)
