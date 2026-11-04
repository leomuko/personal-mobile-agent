#!/usr/bin/env python3
"""Frozen Python/generation subset. C++ semantics and Bazel selection stay blocked."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import resource
import shlex
import shutil
import sys
import time
import urllib.request

HERE=Path(__file__).resolve().parent

def load(name):
    s=importlib.util.spec_from_file_location('dawn_qualification_'+name,HERE/(name+'.py'))
    module=importlib.util.module_from_spec(s);s.loader.exec_module(module);return module

catalog=load('catalog');staging=load('staging');wheels=load('wheels');policy=load('policy')
ROOT=catalog.ROOT;FIX=catalog.FIX;MIB=1024**2;require=catalog.require;owner=catalog.owner
MODE='python-generation-cpp-blocked-v1'
PINNED=(('jinja2-3.1.6-py3-none-any.whl',134899,'85ece4451f492d0c13c5dd7c13a64681a86afae63a5f347908daf103ce6d2f67'),
        ('markupsafe-3.0.3-cp313-cp313-macosx_11_0_arm64.whl',12029,'116bb52f642a37c115f517494ea5feb03889e04df47eeff5b130b1808ce7c219'))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):raise ValueError('wheel redirects forbidden')


def now():
    # CPython 3.9 macOS time.monotonic has a process-local origin. POSIX
    # CLOCK_MONOTONIC is shared by parent and worker; never transfer a local epoch.
    return time.clock_gettime(time.CLOCK_MONOTONIC)


def remaining(deadline,ceiling):
    seconds=deadline-now()
    require(seconds>0,'whole qualification deadline')
    return min(seconds,ceiling)


def download(artifact,path,deadline=None):
    require((artifact['filename'],artifact['bytes'],artifact['sha256']) in PINNED,'unapproved wheel identity')
    declared=json.loads((FIX/'candidate-inputs.json').read_text())['wheels']
    require(artifact in declared and artifact['url'].startswith('https://files.pythonhosted.org/packages/'),'unapproved wheel URL')
    start=now();deadline=min(deadline if deadline is not None else start+15,start+15);count=0;h=hashlib.sha256()
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
    with opener.open(artifact['url'],timeout=remaining(deadline,15)) as response,Path(path).open('xb') as sink:
        require(response.status==200 and response.geturl()==artifact['url'],'unexpected wheel response')
        while True:
            require(now()<deadline,'wheel HTTP deadline')
            data=response.read(min(65536,artifact['bytes']-count+1))
            if not data:break
            count+=len(data);require(count<=artifact['bytes'],'wheel exceeds pinned size');sink.write(data);h.update(data)
    require(now()<deadline and count==artifact['bytes'] and h.hexdigest()==artifact['sha256'],'wheel transport/identity failure')
    return dict(path=str(path),bytes=count,sha256=h.hexdigest(),seconds=now()-start,retries=0,redirects=0)



def wheel_expectation(artifact):
    expected=dict(artifact)
    if artifact['sha256']==PINNED[0][2]:
        amendment=json.loads((FIX/'wheel-metadata-amendment.json').read_text())
        require(amendment['protocol']=='dawn-wheel-raw-metadata-amendment-v1' and
            amendment['artifactSha256']==PINNED[0][2] and amendment['selectedExtras']==[] and
            amendment['additionalDependenciesAllowed'] is False and amendment['genericNormalizationAllowed'] is False,
            'raw metadata amendment scope changed')
        require(artifact['requiresDist']==amendment['advertisedRequiresDist']==['MarkupSafe>=2.0','Babel>=2.7; extra == "i18n"'] and
            amendment['wheelRequiresDist']==['MarkupSafe>=2.0','Babel>=2.7 ; extra == "i18n"'],'unapproved raw dependency spelling')
        expected['advertisedRequiresDist']=artifact['requiresDist']
        expected['requiresDist']=amendment['wheelRequiresDist']
    return expected


def acquire_artifact(artifact,path,f,deadline):
    require((artifact['filename'],artifact['bytes'],artifact['sha256']) in PINNED,'unapproved retained/acquisition identity')
    retained=f.get('retainedWheelInputs',{})
    if artifact['filename'] not in retained:return download(artifact,path,deadline)
    require(artifact['filename']==PINNED[0][0],'only retained first Jinja2 wheel admitted')
    source=Path(retained[artifact['filename']])
    require(any(row['path']==str(source) and row['bytes']==artifact['bytes'] and row['sha256']==artifact['sha256'] for row in f['inputs']),
        'unbound retained wheel')
    data=staging.verified_bytes(source,artifact,MIB);remaining(deadline,300)
    staging.put(path,data);remaining(deadline,300)
    return dict(kind='VERIFIED_RETAINED_COPY',source=str(source),path=str(path),bytes=len(data),sha256=hashlib.sha256(data).hexdigest(),networkRequests=0)


def preparation(f):
    accepted=Path(f['preparationAccepted']);require(catalog.file_row(accepted)['sha256']==f['preparationAcceptedSha256'],'preparation receipt changed')
    receipt=json.loads(accepted.read_text())
    require(receipt['cleanupConfirmed'] is True and receipt['admissionMode']==MODE and receipt['compilerQualified'] is False,'preparation admission mismatch')
    path=Path(receipt['catalog']['path']);require(catalog.file_row(path)==receipt['catalog'],'prepared catalog changed')
    value=json.loads(path.read_text())
    require(value['admissionMode']==MODE and value['compilerExecuted'] is False,'wrong prepared scope')
    for row in value['systemCaches']+list(value['tools'].values()):
        require(catalog.file_row(row['path'])==row,'OS/tool preparation drift')
    require(catalog.tree(value['runtime']['root'],10000,128*MIB)==value['runtime'],'prepared runtime drift')
    return value


def sandbox_text(attempt):
    # JSON string escaping is compatible with Seatbelt quoted path literals.
    return '(version 1)(allow default)(deny network*)(deny file-write*)(allow file-write* (subpath '+json.dumps(str(attempt.resolve()))+'))(allow file-write* (literal "/dev/null"))'


def patch_program(command):
    words=shlex.split(command['literalCommand'])
    require(len(words)==3 and words[:2]==['python3','-c'],'unexpected patch command shape')
    require(hashlib.sha256(words[2].encode()).hexdigest()==command['programSha256'],'patch program drift')
    return words[2]


def worker(f,attempt,deadline=None):
    started=now();deadline=deadline if deadline is not None else started+300
    remaining(deadline,300)
    report=dict(protocol='dawn-python-patch-qualification-v1',admissionMode=MODE,
        stages={name:{'status':'NOT_RUN'} for name in ('prepared-inputs','wheel-bytes-records','private-runtime','private-packages','wheel-native-closure','qualified-source','sandbox-health','imports-render','three-patch-programs','eight-generators-comparison','final-prepared-inputs')},vendorCodeExecuted=False,compilerExecuted=False,BazelSelectionVerified=False,
        callbackSemantics='BLOCKED_MISSING_PINNED_ABSEIL',CPPPositiveCompiles='NOT_RUN',
        CPPBinaryExecutions='NOT_RUN',CPPNegativeCompiles='NOT_RUN',adopted=False)
    trace_size=0
    def trace(argv,data,code,seconds):
        nonlocal trace_size
        record=dict(argv=argv,outputBase64=base64.b64encode(data).decode(),exitCode=code,seconds=seconds)
        encoded=(json.dumps(record,separators=(',',':'))+'\n').encode();trace_size+=len(encoded)
        require(trace_size<=MIB,'combined child trace ceiling')
        with (attempt/'command-trace.jsonl').open('ab') as sink:sink.write(encoded)
    def command(argv,cwd=None,env=None,seconds=30):
        require(shutil.disk_usage(ROOT).free>=1024*MIB,'needs1GiB before child process')
        result=catalog.metadata_command(argv,maximum=512*1024,seconds=remaining(deadline,seconds),trace=trace,cwd=cwd,env=env)
        remaining(deadline,300)
        staging.limits(attempt);return result
    def stage(name,fn):
        remaining(deadline,300)
        report['stages'][name]={'status':'RUNNING'};start=now()
        try:value=fn()
        except BaseException:
            report['stages'][name].update(status='FAILED',seconds=now()-start);raise
        report['stages'][name].update(status='PASSED',seconds=now()-start)
        catalog.write(attempt/('stage-'+name+'.json'),value)
        remaining(deadline,300)
        return value
    try:
        prepared=stage('prepared-inputs',lambda:preparation(f))
        inputs=json.loads((FIX/'candidate-inputs.json').read_text())
        artifacts=[];receipts=[]
        def acquire():
            transfers=[]
            for artifact in inputs['wheels']:
                path=attempt/artifact['filename'];transfers.append(acquire_artifact(artifact,path,f,deadline));artifacts.append(path)
                receipts.append(wheels.inspect(path,wheel_expectation(artifact)))
            require(sum(x['entries'] for x in receipts)<=512 and sum(x['decodedBytes'] for x in receipts)<=16*MIB,'combined wheel ceiling')
            return dict(transfers=transfers,wheels=receipts)
        stage('wheel-bytes-records',acquire)
        runtime_spec=json.loads((FIX/'candidate-runtime-inventory.json').read_text())
        runtime=stage('private-runtime',lambda:staging.runtime(runtime_spec,attempt/'runtime'))
        packages=stage('private-packages',lambda:staging.packages(artifacts,receipts,attempt/'packages'))
        private_python=attempt/'runtime/bin/python3.13'
        # Wheel native code is structurally inspected and linked to the same pinned OS cache before import.
        def package_native():
            cache_map=next(Path(x['path']) for x in prepared['systemCaches'] if x['path'].endswith('dyld_shared_cache_arm64e.map'))
            system_paths={x.strip() for x in cache_map.read_text().splitlines() if x.startswith('/')}
            seeds=[attempt/'packages'/name for r in receipts for name in r['nativeMembers']]
            require(len(seeds)==1,'expected exactly one MarkupSafe native extension')
            return catalog.closure(seeds,private_python,[attempt/'runtime',attempt/'packages'],system_paths,lambda argv:command(argv,seconds=15))
        stage('wheel-native-closure',package_native)
        source_receipt=json.loads((ROOT/inputs['sourceQualification']['path']).read_text())
        source_root=ROOT/'build/root-dependency-offline-v2/attempts/offline-1/selected-source'
        control=stage('qualified-source',lambda:staging.sources(source_root,source_receipt['selectedFiles'],attempt/'control'))
        bootstrap=attempt/'bootstrap';bootstrap.mkdir()
        for name in ('probe.py','policy.py'):staging.put(bootstrap/name,(HERE/name).read_bytes())
        boot_receipt=catalog.tree(bootstrap,10,MIB)
        env=dict(PATH='/usr/bin:/bin',LANG='C',LC_ALL='C',HOME=str(attempt),TMPDIR=str(attempt),
                 PYTHONDONTWRITEBYTECODE='1',PYTHONUTF8='1')
        sandbox=attempt/'sandbox.sb';staging.put(sandbox,sandbox_text(attempt).encode())
        prefix=['/usr/bin/sandbox-exec','-f',str(sandbox)]
        def health():
            # Trusted host Python only. Expected failures must be EPERM, not missing network service/path.
            outside=attempt.parent/('forbidden-'+attempt.name)
            require(not outside.exists(),'sandbox health sentinel exists')
            code='''import errno,os,socket,sys
ok=sys.argv[1];outside=sys.argv[2]
with open(ok,'x') as f:f.write('ok')
try:open(outside,'x')
except OSError as e:
 if e.errno!=errno.EPERM:raise
else:raise RuntimeError('outside write admitted')
s=socket.socket()
try:s.connect(('127.0.0.1',9))
except OSError as e:
 if e.errno!=errno.EPERM:raise
else:raise RuntimeError('network admitted')
finally:s.close()
'''
            command(prefix+[str(catalog.HOST_PYTHON),'-I','-S','-B','-c',code,str(attempt/'sandbox-ok'),str(outside)],env=env)
            require(not outside.exists() and (attempt/'sandbox-ok').read_text()=='ok','sandbox health mismatch')
            return dict(outsideWriteDenied=True,networkDenied=True,ownedWriteAllowed=True)
        stage('sandbox-health',health)
        def check_inputs():
            owner.verify_files(f['inputs'])
            for x in (runtime,packages,boot_receipt):staging.verify_tree(x)
            require(catalog.file_row(sandbox)['sha256']==hashlib.sha256(sandbox_text(attempt).encode()).hexdigest(),'sandbox drift')
        def candidate(args,cwd=None):
            check_inputs();report['vendorCodeExecuted']=True
            command(prefix+[str(private_python),'-I','-S','-B']+args,cwd=cwd,env=env)
            check_inputs()
        def imports():
            out=attempt/'imports.json'
            candidate([str(bootstrap/'probe.py'),'--packages',str(attempt/'packages'),'--runtime',str(attempt/'runtime'),
                       '--target','imports','--report',str(out)])
            require(out.stat().st_size<=MIB,'import report ceiling');return json.loads(out.read_text())
        stage('imports-render',imports)
        patch=json.loads((ROOT/inputs['patchManifest']['path']).read_text())
        operations=[op for item in patch['commands'] for op in item['operations']]
        def patched():
            current=staging.clone(attempt/'control',attempt/'candidate',control)
            p=attempt/'candidate/generator/templates/api_cpp.h';evidence=[]
            for item in patch['commands']:
                before=p.read_bytes();expected=policy.transformed(before,item['operations'])
                require(hashlib.sha256(before).hexdigest()==item['operations'][0]['preimageSha256'],'patch preimage mismatch')
                candidate(['-c',patch_program(item)],cwd=attempt/'candidate')
                require(p.read_bytes()==expected and hashlib.sha256(expected).hexdigest()==item['operations'][-1]['postimageSha256'],'patch postimage mismatch')
                evidence.append(dict(index=item['index'],preimageSha256=hashlib.sha256(before).hexdigest(),postimageSha256=hashlib.sha256(expected).hexdigest()))
            require(hashlib.sha256(p.read_bytes()).hexdigest()==patch['resultingTextSha256'],'final patch mismatch')
            changed=catalog.tree(attempt/'candidate',1024,8*MIB)
            expected={name:dict(row) for name,row in control['files'].items()}
            expected['generator/templates/api_cpp.h'].update(bytes=p.stat().st_size,sha256=patch['resultingTextSha256'])
            require(changed['files']==expected,'unexpected patch side effect')
            return dict(commands=evidence,source=changed)
        patched_receipt=stage('three-patch-programs',patched)
        def generate():
            outputs={};all_rows={}
            for variant,source in [('control',control),('candidate',patched_receipt['source'])]:
                outputs[variant]=[]
                for repeat in (0,1):
                    workspace=attempt/(variant+'-repeat-'+str(repeat))
                    source_copy=staging.clone(Path(source['root']),workspace,source)
                    for target in ('headers','cpp_headers'):
                        out=attempt/(variant+'-'+str(repeat)+'-'+target+'.json')
                        candidate([str(bootstrap/'probe.py'),'--packages',str(attempt/'packages'),'--runtime',str(attempt/'runtime'),
                            '--source',str(workspace),'--target',target,'--output','_out','--report',str(out)],cwd=workspace)
                        policy.workspace(source_copy,workspace,target,catalog.tree)
                    data=policy.output_files(workspace/'_out');outputs[variant].append(data)
                    all_rows[variant+'-'+str(repeat)]={name:dict(bytes=len(value),sha256=hashlib.sha256(value).hexdigest()) for name,value in data.items()}
                policy.repeatable(*outputs[variant])
            policy.compare_outputs(outputs['control'][0],outputs['candidate'][0],operations)
            return dict(invocations=8,repeatable=True,unaffectedByteEqual=6,affectedExactTransformations=2,outputs=all_rows,
                        callbackBehaviorVerified=False)
        stage('eight-generators-comparison',generate)
        stage('final-prepared-inputs',lambda:preparation(f))
        require(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss<=512*MIB,'qualification RSS ceiling')
        report['status']='PYTHON_GENERATION_SUBSET_PASSED_CPP_BLOCKED'
    except BaseException as e:
        report['status']='FAILED';report['error']=type(e).__name__+': '+str(e)[:2048]
        raise
    finally:
        report['seconds']=now()-started;report['childTraceBytes']=trace_size
        report['peakRssBytes']=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        catalog.write(attempt/'qualification-report.json',report)
    remaining(deadline,300)


def admission(f):
    require(f['protocol']=='dawn-python-patch-qualification-v1' and f['admissionMode']==MODE and
            f['python']==str(catalog.HOST_PYTHON),'qualification scope mismatch')
    required={str(HERE/(name+'.py')) for name in ('qualify','catalog','staging','macho','wheels','policy','probe')}
    required|={str(Path(owner.__file__).resolve()),str(catalog.HOST_PYTHON),str(catalog.OTOOL),'/usr/bin/sandbox-exec'}
    required|={str(ROOT/'planning/fixtures/root-dependency-offline-v2'/name) for name in ('source-validation.json','patch-manifest.json')}
    required|={str(FIX/name) for name in ('candidate-inputs.json','candidate-runtime-inventory.json','cpp-prerequisite-block.json','wheel-metadata-amendment.json')}
    require(required|{f['preparationAccepted']}<={row['path'] for row in f['inputs']},'unbound qualification implementation')
    require(f['implementationSha256']==owner.digest(__file__),'qualification implementation changed')
    require(set(f.get('retainedWheelInputs',{}))<={PINNED[0][0]},'unapproved retained wheel set')
    require(set(f.get('retainedWheelInputs',{}).values())<={row['path'] for row in f['inputs']},'retained wheel not frozen')
    owner.verify_files(f['inputs'])


def revoke_links(published):
    """Revoke only fresh public hardlinks; immutable pending evidence is retained."""
    failures=[]
    for public,pending in reversed(published):
        try:
            if os.path.lexists(public):
                require(not public.is_symlink() and public.samefile(pending),'public acceptance link identity changed')
                public.unlink()
        except (ValueError,OSError) as error:failures.append(str(error))
    require(not failures,'; '.join(failures))


def main():
    started=now();deadline=started+300
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--freeze',type=Path,required=True)
    p.add_argument('--sha256',required=True);p.add_argument('--attempt',type=Path,required=True);p.add_argument('--worker',action='store_true')
    p.add_argument('--decision-deadline',type=float)
    a=p.parse_args();created=False;outcome=None;published=[]
    try:
        if a.worker:
            require(a.decision_deadline is not None and a.decision_deadline<=deadline,'missing/invalid inherited deadline')
            deadline=a.decision_deadline
        else:require(a.decision_deadline is None,'only workers inherit deadlines')
        remaining(deadline,300)
        require(owner.digest(a.freeze)==a.sha256,'execution freeze changed');f=json.loads(a.freeze.read_text());admission(f)
        remaining(deadline,300)
        require(a.attempt.resolve().parent==Path(f['attemptParent']).resolve(),'qualification path mismatch')
        if a.worker:worker(f,a.attempt.resolve(),deadline);return 0
        require(not a.attempt.exists() and shutil.disk_usage(ROOT).free>=1024*MIB,'fresh path/1GiB free required')
        remaining(deadline,300)
        a.attempt.mkdir(mode=0o700,parents=True);created=True
        args=[f['python'],'-I','-S','-B',str(HERE/'qualify.py'),'--worker','--freeze',str(a.freeze.resolve()),'--sha256',a.sha256,'--attempt',str(a.attempt.resolve()),'--decision-deadline',repr(deadline)]
        outcome=owner.run_owned(args,ROOT,a.attempt/'worker',f['environment'],remaining(deadline,300),MIB,15)
        # Cleanup may finish in its 15s allowance; that does not admit further
        # qualification work or success after the original decision deadline.
        remaining(deadline,300)
        require(outcome['classification']=='ANALYSIS_COMMAND_SUCCEEDED' and outcome['cleanupConfirmed'] is True,'qualification worker failed/cleanup uncertain')
        admission(f);remaining(deadline,300)
        report=json.loads((a.attempt/'qualification-report.json').read_text())
        require(report['status']=='PYTHON_GENERATION_SUBSET_PASSED_CPP_BLOCKED','subset did not pass')
        staging.limits(a.attempt);remaining(deadline,300)
        pending=a.attempt/'accepted-subset.pending.json'
        catalog.write(pending,dict(freezeSha256=a.sha256,report=catalog.file_row(a.attempt/'qualification-report.json'),
            cleanupConfirmed=True,compilerQualified=False,BazelSelectionVerified=False,recipeQualified=False,
            decisionStartedMonotonic=started,decisionDeadlineMonotonic=deadline))
        remaining(deadline,300)
        outcome['freezeSha256']=a.sha256;outcome['decisionDeadlineMonotonic']=deadline
        terminal_pending=a.attempt/'outcome.pending.json'
        catalog.write(terminal_pending,outcome,65536);remaining(deadline,300)
        for public,source in [(a.attempt/'outcome.json',terminal_pending),(a.attempt/'accepted-subset.json',pending)]:
            remaining(deadline,300);published.append((public,source));os.link(source,public);remaining(deadline,300)
        return 2
    except (ValueError,OSError,KeyError,KeyboardInterrupt) as e:
        failure=type(e).__name__+': '+str(e)[:2048]
        revocation_confirmed=False
        try:
            revoke_links(published)
            revocation_confirmed=not os.path.lexists(a.attempt/'accepted-subset.json') and all(not os.path.lexists(public) for public,pending in published)
        except (ValueError,OSError,KeyboardInterrupt) as revoke_error:failure+='; uncertain publication revocation: '+str(revoke_error)
        if created:
            terminal=dict(classification='QUALIFICATION_FAILED_OR_DEADLINE_REVOKED',
                workerOutcome=outcome,cleanupConfirmed=None if outcome is None else outcome.get('cleanupConfirmed'),
                failure=failure,freezeSha256=a.sha256,elapsedSeconds=now()-started,
                decisionDeadlineMonotonic=deadline,acceptanceRevoked=revocation_confirmed,
                publicAcceptancePresent=os.path.lexists(a.attempt/'accepted-subset.json'))
            # Retain attempted success serializations. Only fresh public hardlinks
            # were revoked above; their pending bytes are never removed.
            destination=a.attempt/('outcome.json' if not (a.attempt/'outcome.json').exists() else 'publication-failure.json')
            catalog.write(destination,terminal,65536)
        print(failure,file=sys.stderr);return 1


if __name__=='__main__':raise SystemExit(main())
