#!/usr/bin/env python3
"""Owned, reviewed native metadata only; never compiles/links/runs the C++ matrix."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import resource
import shutil
import subprocess
import sys
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent

def module(name,path):
 s=importlib.util.spec_from_file_location('cpp_metadata_'+name,path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m

prior=module('prior',ROOT/'scripts/diagnostics/dawn_cpp_toolchain_v1/qualify.py')
backend=module('backend',HERE/'backend.py');plan=module('plan',HERE/'plan.py')
base=prior.base;c=prior.c;owner=prior.owner;require=prior.require;now=prior.now;remaining=prior.remaining
policy=prior.policy;inspector=prior.inspector
FIX=ROOT/'planning/fixtures/dawn-cpp-backend-v1';MIB=1024**2
PROTOCOL='dawn-cpp-backend-metadata-v1';SCOPE='exact-wrapper-backend-31-query-metadata-only'


def data(path,maximum=32*MIB):return base.read_json(path,maximum)


def check_inputs(f):
 prior.check_inputs(f)
 require(f['backendCatalog']=='planning/fixtures/dawn-cpp-backend-v1/backend-native-catalog.json','unexpected backend catalog')
 catalog=data(ROOT/f['backendCatalog'])
 require(catalog['priorAttempt']=='build/dawn-cpp-toolchain-v1/metadata-attempts/metadata-1' and catalog['priorOutcome']['path']==catalog['priorAttempt']+'/outcome.json','prior attempt provenance changed')
 backend.check_profiles(catalog)
 require(catalog['priorOutcome']['sha256']==owner.digest(ROOT/catalog['priorOutcome']['path']),'prior outcome changed')
 previous=data(ROOT/catalog['priorOutcome']['path'],65536)
 require(previous['cleanupConfirmed'] is True and previous['classification']=='METADATA_FAILED_OR_REVOKED','prior metadata cleanup unconfirmed')
 require(not (ROOT/catalog['priorAttempt']/'accepted-metadata.json').exists(),'prior failed evidence unexpectedly accepted')


def admission(path,sha,review,review_sha):
 f,actual=base.read_json_bound(path);v,actual_review=base.read_json_bound(review,65536)
 require(actual==sha and actual_review==review_sha and v['verdict']=='PASS' and v['scope']==SCOPE and v['freezeSha256']==sha,'missing/mismatched metadata review')
 require(f['protocol']==PROTOCOL and f['scope']==SCOPE and f['implementationSha256']==owner.digest(__file__),'metadata implementation/scope changed')
 require(f['seconds']==300 and f['cleanupSeconds']==15 and f['analysisFloorBytes']==18*1024**3 and f['compileAllowed'] is False and f['linkAllowed'] is False and f['binaryExecutionAllowed'] is False and f['deviceAllowed'] is False,'metadata boundaries changed')
 require(f['python']==str(c.HOST_PYTHON) and f['attemptParent']==str(ROOT/'build/dawn-cpp-toolchain-v1/metadata-attempts') and f['attemptName']=='metadata-2','metadata output/interpreter changed')
 require(f['sdkRoot']==str(plan.SDK) and f['resourceRoot']==str(plan.RESOURCE),'SDK/resource root changed')
 attempt=Path(f['attemptParent'])/f['attemptName']
 require(f['commands']==plan.commands(attempt,f['nativeFiles']),'metadata command recipe changed')
 required={str(HERE/name) for name in ('qualify.py','backend.py','plan.py')}
 required|={str(Path(prior.__file__).resolve()),str(ROOT/f['backendCatalog']),str(backend.BACKEND)}
 required|={str(ROOT/'planning/fixtures/dawn-cpp-toolchain-v1/metadata-execution-freeze-r4.json'),str(ROOT/'planning/fixtures/dawn-cpp-toolchain-v1/observed-inspector-helper.json'),str(ROOT/'planning/fixtures/dawn-cpp-qualification-v1/native-static-inventory.json'),str(ROOT/'build/dawn-cpp-toolchain-v1/metadata-attempts/metadata-1/outcome.json')}
 required|={str(plan.RESOURCE/'lib/darwin/libclang_rt.osx.a'),str((plan.SDK/'usr/lib/libc++.tbd').resolve()),str((plan.SDK/'usr/lib/libSystem.tbd').resolve())}
 required|={str(Path(x.__file__).resolve()) for x in (prior.policy,prior.inspector,prior.plan,base,base.base,base.archive,base.transforms,base.tools,c,c.macho,owner)}
 require(required<={r['path'] for r in f['inputs']},'unbound metadata code dependency')
 check_inputs(f)
 return f


def native_rows(f):
 declaration=data(ROOT/f['backendCatalog'])
 require(set(declaration['files'])==set(f['nativeFiles']) and len(f['nativeFiles'])==8,'backend native declaration scope')
 original=data(ROOT/'planning/fixtures/dawn-cpp-qualification-v1/native-static-inventory.json')
 previous=data(ROOT/'planning/fixtures/dawn-cpp-toolchain-v1/metadata-execution-freeze-r4.json')
 observed=data(ROOT/'planning/fixtures/dawn-cpp-toolchain-v1/observed-inspector-helper.json')
 require(declaration['originalNativeFiles']==previous['nativeFiles'],'original native supplier set changed')
 for path in previous['nativeFiles']:require(declaration['files'][path]==original['files'][path],'original native supplier shadowed')
 require(declaration['files'][str(plan.BACKEND)]==dict(observed['file'],rawMachO=observed['rawMachO']),'backend differs from observed candidate')
 nodes={p:dict(declaration['files'][p]) for p in f['nativeFiles']}
 map_path=Path(f['cacheMap']);require(map_path.stat().st_size<=8*MIB,'system map ceiling')
 system={line.strip() for line in map_path.read_text().splitlines() if line.startswith('/')}
 for p,row in nodes.items():
  require(c.file_row(p)=={k:row[k] for k in ('path','bytes','mode','sha256')},'native payload changed')
  require(c.macho.inspect(p)==row['rawMachO'],'native load commands changed')
  row['inspectorStructure']=inspector.contract(p,row['rawMachO'])
 graphs=[policy.native_graph(nodes,str(exe),system) for exe in (plan.CLANG,plan.LD,plan.OTOOL,plan.BACKEND)]
 require(graphs==declaration['loadCommandGraphs'] and {p for graph in graphs for p in graph['nodes']}==set(nodes),'unexpected native supplier/context')
 return nodes,graphs


validate_output=prior.validate_output


def worker(f,attempt,deadline):
 report=dict(status='RUNNING',commands=[],graphs=[],compileExecuted=False,linkExecuted=False,binaryExecuted=False,compilerMatrixReady=False,BazelSelectionVerified=False)
 attempt=Path(attempt);(attempt/'empty.cpp').write_bytes(b'// Synthetic empty preprocessing/driver metadata input.\n')
 environment=dict(PATH=str(plan.BIN),LANG='C',LC_ALL='C',HOME=str(attempt),TMPDIR=str(attempt))
 trace_bytes=0
 def trace(argv,raw,exitcode,seconds):
  nonlocal trace_bytes
  encoded=(json.dumps(dict(argv=argv,outputBase64=base64.b64encode(raw).decode(),exitCode=exitcode,seconds=seconds),separators=(',',':'))+'\n').encode()
  trace_bytes+=len(encoded);require(trace_bytes<=2*MIB,'aggregate metadata log ceiling')
  with (attempt/'command-trace.jsonl').open('ab') as sink:sink.write(encoded)
 try:
  nodes,graphs=native_rows(f);report['graphs']=graphs
  for job in f['commands']:
   remaining(deadline,300)
   row=dict(id=job['id'],kind=job['kind'],status='RUNNING');report['commands'].append(row)
   declaration=data(ROOT/f['backendCatalog'])
   start=now();text=backend.dispatch(job,attempt,declaration,remaining(deadline,30),trace,environment,absolute_deadline=deadline)
   remaining(deadline,300);row.update(result=validate_output(job,text,nodes),status='PASSED',seconds=now()-start)
   observed=c.tree(attempt,16,8*MIB)
   require(set(observed['files'])<={'owner-started.json','worker.stdout.log','worker.stderr.log','empty.cpp','command-trace.jsonl'},'unexpected metadata artifact/output')
  check_inputs(f);remaining(deadline,300)
  require(int(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)<=512*MIB,'metadata worker RSS ceiling')
  report['status']='TOOLCHAIN_METADATA_PASSED_CPP_NOT_EXECUTED'
 except BaseException as error:
  report.update(status='FAILED',failure=type(error).__name__+': '+str(error)[:2048]);raise
 finally:
  if report['status']=='FAILED' and report['commands'] and report['commands'][-1]['status']=='RUNNING':report['commands'][-1]['status']='FAILED'
  report.update(notRunCommands=[job['id'] for job in f['commands'] if job['id'] not in {row['id'] for row in report['commands']}],commandTraceBytes=trace_bytes,peakWorkerRssBytes=int(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss))
  c.write(attempt/'metadata-report.json',report,2*MIB)
 remaining(deadline,300)


def main():
 start=now();deadline=start+300
 p=argparse.ArgumentParser(description=__doc__)
 for name in ('freeze','review','attempt'):p.add_argument('--'+name,type=Path,required=True)
 for name in ('sha256','review-sha256'):p.add_argument('--'+name,required=True)
 p.add_argument('--worker',action='store_true');p.add_argument('--decision-deadline',type=float)
 a=p.parse_args();created=False;outcome=None;published=[]
 try:
  if a.worker:
   require(a.decision_deadline is not None and a.decision_deadline<=deadline,'invalid inherited metadata deadline');deadline=a.decision_deadline
  else:require(a.decision_deadline is None,'only metadata workers inherit deadlines')
  remaining(deadline,300);f=admission(a.freeze,a.sha256,a.review,a.review_sha256);remaining(deadline,300)
  require(a.attempt.resolve()==(Path(f['attemptParent'])/f['attemptName']).resolve() and a.attempt.resolve().is_relative_to(ROOT.resolve()),'unexpected metadata attempt')
  if a.worker:worker(f,a.attempt,deadline);return 0
  base.quarantine(a.attempt.parent)
  require(not a.attempt.exists() and shutil.disk_usage(ROOT).free>=1024*MIB,'fresh metadata attempt/1GiB required')
  remaining(deadline,300);a.attempt.mkdir(parents=True,mode=0o700);created=True
  c.write(a.attempt/'owner-started.json',dict(freezeSha256=a.sha256,decisionStartedMonotonic=start,decisionDeadlineMonotonic=deadline),65536)
  argv=[f['python'],'-I','-S','-B',str(Path(__file__).resolve()),'--freeze',str(a.freeze.resolve()),'--sha256',a.sha256,'--review',str(a.review.resolve()),'--review-sha256',a.review_sha256,'--attempt',str(a.attempt.resolve()),'--worker','--decision-deadline',repr(deadline)]
  outcome=owner.run_owned(argv,ROOT,a.attempt/'worker',f['environment'],remaining(deadline,300),512*1024,15)
  remaining(deadline,300);require(outcome['classification']=='ANALYSIS_COMMAND_SUCCEEDED' and outcome['cleanupConfirmed'] is True,'metadata worker failed/cleanup uncertain')
  admission(a.freeze,a.sha256,a.review,a.review_sha256);remaining(deadline,300)
  report=data(a.attempt/'metadata-report.json',2*MIB);require(report['status']=='TOOLCHAIN_METADATA_PASSED_CPP_NOT_EXECUTED' and len(report['commands'])==len(f['commands']),'incomplete metadata results')
  c.write(a.attempt/'accepted-metadata.pending.json',dict(freezeSha256=a.sha256,reviewSha256=a.review_sha256,report=c.file_row(a.attempt/'metadata-report.json'),cleanupConfirmed=True,wholeDecisionBeforePublicationSeconds=now()-start,compilerMatrixExecuted=False),65536)
  outcome.update(freezeSha256=a.sha256,decisionStartedMonotonic=start,decisionDeadlineMonotonic=deadline)
  c.write(a.attempt/'outcome.pending.json',outcome,65536)
  for public,source in [(a.attempt/'outcome.json',a.attempt/'outcome.pending.json'),(a.attempt/'accepted-metadata.json',a.attempt/'accepted-metadata.pending.json')]:
   remaining(deadline,300);published.append((public,source));os.link(source,public);remaining(deadline,300)
  return 2
 except (ValueError,OSError,KeyError,UnicodeError,EOFError,KeyboardInterrupt,subprocess.SubprocessError) as error:
  failure=type(error).__name__+': '+str(error)[:2048]
  try:base.base.revoke_links(published)
  except (ValueError,OSError,KeyboardInterrupt) as e:failure+='; uncertain revocation: '+str(e)
  if created:c.write(a.attempt/('publication-failure.json' if (a.attempt/'outcome.json').exists() else 'outcome.json'),dict(classification='METADATA_FAILED_OR_REVOKED',workerOutcome=outcome,cleanupConfirmed=None if outcome is None else outcome.get('cleanupConfirmed'),cleanupStatus='CONFIRMED' if outcome is not None and outcome.get('cleanupConfirmed') is True else 'UNCERTAIN',failure=failure,elapsedSeconds=now()-start,freezeSha256=a.sha256,publicAcceptancePresent=os.path.lexists(a.attempt/'accepted-metadata.json')),65536)
  print(failure,file=sys.stderr);return 1

if __name__=='__main__':raise SystemExit(main())
