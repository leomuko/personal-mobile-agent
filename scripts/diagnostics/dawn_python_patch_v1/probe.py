"""Frozen bootstrap for isolated candidate-only imports/rendering/generation."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import platform
import runpy
import sys
import sysconfig
import struct

spec=importlib.util.spec_from_file_location('dawn_probe_policy',Path(__file__).with_name('policy.py'))
policy=importlib.util.module_from_spec(spec);spec.loader.exec_module(policy)


def require(ok,message):
    if not ok: raise ValueError(message)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--packages',type=Path,required=True)
    p.add_argument('--runtime',type=Path,required=True)
    p.add_argument('--source',type=Path)
    p.add_argument('--report',type=Path,required=True)
    p.add_argument('--target',choices=['headers','cpp_headers','imports'],required=True)
    p.add_argument('--output',type=Path)
    a=p.parse_args()
    policy.candidate(dict(version=list(sys.version_info[:3]),implementation=sys.implementation.name,
        machine=platform.machine(),platform=sys.platform,bits=struct.calcsize('P')*8,
        SOABI=sysconfig.get_config_var('SOABI'),GILDisabled=sysconfig.get_config_var('Py_GIL_DISABLED'),
        isolated=bool(sys.flags.isolated),noSite=bool(sys.flags.no_site),noBytecode=sys.dont_write_bytecode))
    runtime=a.runtime.resolve();packages=a.packages.resolve()
    require(Path(sys.executable).resolve().is_relative_to(runtime),'interpreter outside private runtime')
    sys.path.insert(0,str(packages))
    import jinja2
    import markupsafe
    import markupsafe._speedups as speedups
    require(jinja2.__version__=='3.1.6','wrong Jinja2 runtime')
    require(Path(jinja2.__file__).resolve().is_relative_to(packages) and
            Path(markupsafe.__file__).resolve().is_relative_to(packages) and
            Path(speedups.__file__).resolve().is_relative_to(packages),'package/native origin mismatch')
    require(markupsafe._escape_inner is speedups._escape_inner,'native MarkupSafe fallback detected')
    require(str(markupsafe.escape('"\'&<>'))=='&#34;&#39;&amp;&lt;&gt;','escaping mismatch')
    templates={'base':'B[{% block content %}base{% endblock %}]',
               'child':'{% extends "base" %}{% block content %}{% include "leaf" %}{% endblock %}',
               'leaf':'{{ value }}'}
    env=jinja2.Environment(loader=jinja2.DictLoader(templates),autoescape=True,
                         extensions=['jinja2.ext.do','jinja2.ext.loopcontrols'])
    require(env.get_template('child').render(value='青🛰<&')=='B[青🛰&lt;&amp;]','inherit/include/Unicode mismatch')
    require(env.from_string('{% set xs=[] %}{% for n in [1,2,3] %}{% if n==2 %}{% continue %}{% endif %}{% do xs.append(n) %}{% endfor %}{{ xs|join(",") }}').render()=='1,3',
            'do/loopcontrols mismatch')
    if a.target!='imports':
        require(a.source is not None and a.output is not None,'generator arguments missing')
        source=a.source.resolve();sys.path.insert(0,str(source/'generator'))
        sys.argv=['generator/dawn_json_generator.py','--dawn-json','src/dawn/dawn.json',
                  '--wire-json','src/dawn/dawn_wire.json','--template-dir','generator/templates',
                  '--targets',a.target,'--output-dir',str(a.output)]
        try:runpy.run_path('generator/dawn_json_generator.py',run_name='__main__')
        except SystemExit as e:require(e.code in (0,None),'generator nonzero exit')
    else:source=None
    origins={}
    allowed=[runtime,packages,Path(__file__).resolve().parent]
    if source:allowed.append(source)
    for name,module in sorted(sys.modules.items()):
        origin=getattr(getattr(module,'__spec__',None),'origin',None)
        if origin in ('built-in','frozen'):
            origins[name]=origin;continue
        path=getattr(module,'__file__',None)
        if not path:continue
        q=Path(path).resolve()
        require(any(q.is_relative_to(root) for root in allowed),'unexpected import origin: '+name)
        origins[name]=str(q)
    result=dict(interpreter=sys.version,executable=sys.executable,SOABI=sysconfig.get_config_var('SOABI'),
                target=a.target,origins=origins,nativeSpeedups=str(Path(speedups.__file__).resolve()),
                nativeSha256=hashlib.sha256(Path(speedups.__file__).read_bytes()).hexdigest(),
                renderChecksPassed=True,selectedExtras=[],BazelSelectionVerified=False)
    with a.report.open('x',encoding='utf-8') as out:json.dump(result,out,sort_keys=True,ensure_ascii=False);out.write('\n')


if __name__=='__main__':main()
