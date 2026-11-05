#!/usr/bin/env python3
"""Reviewed source-only acquisition/preparation; never compiles or executes Abseil."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import resource
import re
import shutil
import subprocess
import stat
import sys
import urllib.request

ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent

def load(name,path):
    s=importlib.util.spec_from_file_location('abseil_prepare_'+name,path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m

base=load('base',ROOT/'scripts/diagnostics/dawn_python_patch_v1/qualify.py')
archive=load('archive',HERE/'archive.py');transforms=load('transforms',HERE/'transforms.py');tools=load('tools',HERE/'tools.py')
require=base.require;catalog=base.catalog;owner=base.owner;now=base.now;remaining=base.remaining
MIB=1024**2;FIX=ROOT/'planning/fixtures/dawn-abseil-preparation-v1'
PROTOCOL='abseil-acquisition-preparation-v1'
ORIGIN='https://github.com/abseil/abseil-cpp/archive/20260526.0.tar.gz'
REDIRECT='https://codeload.github.com/abseil/abseil-cpp/tar.gz/refs/tags/20260526.0'


class ExactRedirect(urllib.request.HTTPRedirectHandler):
    def __init__(self,events):self.events=events;self.count=0
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        require(len(self.events)<3,'HTTP event ceiling')
        self.events.append(dict(url=req.full_url,status=code,location=newurl[:1024],locationTruncated=len(newurl)>1024))
        require(self.count==0 and req.full_url==ORIGIN and newurl==REDIRECT and code in (301,302,303,307,308),'unapproved archive redirect')
        self.count+=1
        return super().redirect_request(req,fp,code,msg,headers,newurl)


def read_json_bound(path,maximum=32*MIB):
    with os.fdopen(os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK),'rb') as source:
        before=os.fstat(source.fileno())
        require(stat.S_ISREG(before.st_mode) and before.st_size<=maximum,'JSON input ceiling/type')
        data=source.read(maximum+1);after=os.fstat(source.fileno());current=os.lstat(path)
        identity=lambda row:(row.st_dev,row.st_ino,row.st_size,row.st_mode,row.st_mtime_ns,row.st_ctime_ns)
        require(len(data)==before.st_size and identity(before)==identity(after)==identity(current),'JSON changed during read')
    return json.loads(data.decode('utf-8',errors='strict')),hashlib.sha256(data).hexdigest()


def read_json(path,maximum=32*MIB):return read_json_bound(path,maximum)[0]



def read_http_block(response,deadline):
    if getattr(response,'length',None)==0:return b''
    try:sock=response.fp.raw._sock
    except AttributeError:raise ValueError('bounded HTTP socket unavailable') from None
    require(callable(getattr(sock,'settimeout',None)) and callable(getattr(response,'read1',None)),'bounded HTTP reader unavailable')
    sock.settimeout(remaining(deadline,15))
    data=response.read1(65536);remaining(deadline,15);return data


def download(path,budget,deadline,events):
    start=now();http_deadline=min(deadline,start+15);total=0;h=hashlib.sha256()
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),ExactRedirect(events))
    request=urllib.request.Request(ORIGIN,headers={'Accept-Encoding':'identity'})
    with opener.open(request,timeout=remaining(http_deadline,15)) as response,Path(path).open('xb') as target:
        require(response.status==200 and response.geturl() in (ORIGIN,REDIRECT),'unexpected archive response')
        require(response.headers.get('Content-Encoding','identity')=='identity','HTTP transformation forbidden')
        declared=response.headers.get('Content-Length')
        if declared is not None:require(declared.isascii() and 0<len(declared)<=10 and declared.isdigit() and 0<int(declared)<=archive.LIMITS['compressed'],'HTTP size ceiling')
        events.append(dict(url=response.geturl(),status=response.status,contentLength=declared))
        while True:
            remaining(http_deadline,15);data=read_http_block(response,http_deadline)
            if not data:break
            total+=len(data);require(total<=archive.LIMITS['compressed'],'compressed archive ceiling')
            budget.charge(len(data));h.update(data);target.write(data)
    remaining(http_deadline,15)
    require(total>0 and h.hexdigest()==archive.SHA,'archive transport SHA mismatch')
    if declared is not None:require(total==int(declared),'HTTP short transfer')
    return dict(bytes=total,sha256=h.hexdigest(),seconds=now()-start,redirects=len(events)-1,retries=0)


def write(path,value,budget):archive.primitives.write_json(Path(path),value,budget)


def verify_inputs(f):
    for row in f['inputs']:
        before=os.lstat(row['path']);require(stat.S_ISREG(before.st_mode) and before.st_size==row['bytes'],'missing/tampered preparation input size/type')
        current=catalog.file_row(row['path'])
        require(current['bytes']==row['bytes'] and current['sha256']==row['sha256'] and
                ('mode' not in row or current['mode']==row['mode']),'missing/tampered preparation input: '+row['path'])


def validate_freeze(path,expected,review,review_sha):
    f,actual=read_json_bound(path);require(actual==expected,'preparation freeze changed')
    r,actual_review=read_json_bound(review,65536)
    require(actual_review==review_sha and r['verdict']=='PASS' and r['freezeSha256']==expected and
            r['scope']=='abseil-acquisition-preparation-only','missing/mismatched independent freeze review')
    require(f['protocol']==PROTOCOL and f['scope']=='source-acquisition-archive-patch-build-preparation-only' and
            f['implementationSha256']==owner.digest(__file__),'preparation scope/implementation changed')
    require(f['archive']==dict(url=ORIGIN,redirect=REDIRECT,sha256=archive.SHA,prefix=archive.PREFIX,maxBytes=16*MIB) and
            f['seconds']==300 and f['cleanupSeconds']==15 and f['analysisFloorBytes']==18*1024**3,'artifact/resource scope changed')
    require(f['compilerAllowed'] is False and f['vendorCodeAllowed'] is False and f['BazelAllowed'] is False and f['deviceAllowed'] is False,'forbidden execution scope')
    required={str(HERE/name) for name in ('prepare.py','archive.py','transforms.py','tools.py')}
    required|={str(Path(module.__file__).resolve()) for module in (base.staging,base.wheels,base.policy,base.catalog.macho)}
    required|={str(Path(base.__file__).resolve()),str(Path(catalog.__file__).resolve()),str(Path(owner.__file__).resolve()),str(Path(archive.primitives.__file__).resolve()),str(base.catalog.HOST_PYTHON)}
    required|={str(FIX/name) for name in ('manifest.json','patch-contexts.json','sed-behavior.json','shell-pins.json','root-abseil.patch')}
    require(required<={row['path'] for row in f['inputs']},'unbound source/preparation dependency')
    require(f['python']==str(catalog.HOST_PYTHON),'host interpreter changed')
    require(isinstance(f['attemptName'],str) and re.fullmatch(r'prepare-[1-9][0-9]{0,2}',f['attemptName']) is not None and Path(f['attemptParent'])==ROOT/'build/dawn-abseil-preparation-v1/attempts','preparation attempt scope changed')
    verify_inputs(f)
    manifest=read_json(FIX/'manifest.json')
    require(manifest['protocol']==PROTOCOL and manifest['archive']==f['archive'] and
            manifest['rootRewrite']==transforms.COMMAND and manifest['rootPatch']['path']==str(FIX/'root-abseil.patch') and manifest['rootPatch']['sha256']==transforms.PATCH_SHA,'preparation manifest changed')
    facts=read_json(FIX/'sed-behavior.json')
    require(facts['status']=='PASSED_SYNTHETIC_ONLY' and facts['backupSuffix']==transforms.BACKUP and facts['rootCommand']==transforms.COMMAND and facts['cleanupConfirmed'] is True,'platform BUILD rewrite not qualified')
    return f,manifest


def quarantine(parent):
    if not Path(parent).exists():return
    count=0
    with os.scandir(parent) as entries:
        for entry in entries:
            count+=1;require(count<=64,'attempt history bound');child=Path(entry.path)
            require(child.is_dir() and not child.is_symlink(),'unrecognized attempt entry')
            marker=child/'owner-started.json'
            if not marker.exists():continue
            terminal=child/'outcome.json'
            require(terminal.is_file() and read_json(terminal,65536).get('cleanupConfirmed') is True,'uncertain prior owner cleanup; preparation quarantined')


def clone(source,destination,budget,check):
    source,destination=Path(source),Path(destination);before=catalog.tree(source,10000,128*MIB)
    destination.mkdir(mode=0o700)
    for name,row in sorted(before['files'].items(),key=lambda pair:(pair[0].count('/'),pair[0])):
        check();target=destination/name
        if row['kind']=='directory':target.mkdir(mode=0o700)
        elif row['kind']=='symlink':budget.charge(len(row['target'].encode()));target.symlink_to(row['target'])
        else:
            data=base.staging.verified_bytes(source/name,row,8*MIB);budget.charge(len(data));base.staging.put(target,data)
    after=catalog.tree(destination,10000,128*MIB)
    require(before['files']==after['files'],'private clone changed source');return after


def worker(f,manifest,attempt,deadline):
    budget=archive.primitives.Budget(510*MIB);events=[];trace_bytes=0
    stages={name:{'status':'NOT_RUN'} for name in ('acquisition','complete-source','private-source','clone','root-patch','BUILD-rewrite','final-verification')}
    report=dict(protocol=PROTOCOL,status='RUNNING',stages=stages,sourceExecuted=False,compilerExecuted=False,
                rootAdopted=False,BazelSelectionVerified=False,cppReady=False)
    def check():remaining(deadline,300)
    def stage(name,action):
        check();stages[name]={'status':'RUNNING'};start=now()
        try:value=action()
        except BaseException:
            stages[name].update(status='FAILED',seconds=now()-start);raise
        stages[name].update(status='PASSED',seconds=now()-start);write(attempt/('stage-'+name+'.json'),value,budget);check();return value
    def trace(argv,data,code,seconds):
        nonlocal trace_bytes
        encoded=(json.dumps(dict(argv=argv,outputBase64=base64.b64encode(data).decode(),exitCode=code,seconds=seconds),separators=(',',':'))+'\n').encode()
        trace_bytes+=len(encoded);require(trace_bytes<=MIB,'child trace ceiling')
        with (attempt/'command-trace.jsonl').open('ab') as target:target.write(encoded)
    try:
        partial=attempt/'source.tar.gz.part'
        stage('acquisition',lambda:download(partial,budget,deadline,events))
        receipt=stage('complete-source',lambda:archive.scan(partial,check))
        stage('private-source',lambda:archive.materialize(partial,receipt,attempt/'source',budget,check))
        original=catalog.tree(attempt/'source',10000,128*MIB)
        candidate=stage('clone',lambda:clone(attempt/'source',attempt/'candidate',budget,check))
        actual_hunks=transforms.parse_patch(Path(manifest['rootPatch']['path']).read_bytes())
        require(actual_hunks==read_json(FIX/'patch-contexts.json')['hunks'],'frozen patch contexts changed')
        patch=transforms.patch_plan(attempt/'candidate',candidate,actual_hunks)
        write(attempt/'planned-patch.json',patch,budget)
        patched=stage('root-patch',lambda:transforms.apply_patch(attempt/'candidate',candidate,patch,budget,check))
        plan=transforms.build_plan(attempt/'candidate',patched);write(attempt/'planned-rewrite.json',plan,budget)
        # Reserve both backup and new-target writes conservatively, without
        # assuming whether the platform preserves an inode or copies it.
        budget.charge(sum(row['beforeBytes']+row['afterBytes'] for row in plan)+8*MIB)
        def rewrite():
            require(shutil.disk_usage(ROOT).free>=1024**3,'needs1GiB before shell')
            operation=tools.rewrite(attempt/'candidate',attempt,manifest['toolPins'],remaining(deadline,30),trace)
            after=transforms.verify_rewrite(attempt/'candidate',patched,plan)
            return dict(operation=operation,matchedFiles=plan,tree=after,backupSuffix=transforms.BACKUP,compilerExecuted=False)
        rewritten=stage('BUILD-rewrite',rewrite)
        def finish():
            verify_inputs(f);require(catalog.tree(attempt/'source',10000,128*MIB)==original,'original source changed')
            require(catalog.tree(attempt/'candidate',22048,384*MIB)==rewritten['tree'],'candidate changed')
            require(catalog.file_row(partial)['sha256']==archive.SHA,'source archive changed')
            require(catalog.tree(attempt,25000,512*MIB)['regularBytes']<=512*MIB,'attempt storage ceiling')
            require(archive.primitives.rss_bytes()<=512*MIB,'worker RSS ceiling')
            return dict(completeInventory=True,unchangedFilesCompared=True,sourceAndCandidateVerified=True,
                        archiveSha256=archive.SHA,compilerReady=False,rootPatchNativeEquivalenceVerified=False)
        stage('final-verification',finish);report['status']='ABSEIL_SOURCE_PREPARATION_PASSED_CPP_BLOCKED'
    except BaseException as error:
        report['status']='FAILED';report['failure']=type(error).__name__+': '+str(error)[:2048];raise
    finally:
        report.update(httpEvents=events,remainingWriteBudget=budget.maximum-budget.used,peakRssBytes=archive.primitives.rss_bytes())
        # Reserved terminal evidence may be written after decision revocation;
        # this never re-admits qualification work or a success publication.
        catalog.write(attempt/'preparation-report.json',report,65536)
    check()


def main():
    started=now();deadline=started+300
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('freeze','review','attempt'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--sha256',required=True);p.add_argument('--review-sha256',required=True)
    p.add_argument('--worker',action='store_true');p.add_argument('--decision-deadline',type=float)
    a=p.parse_args();created=False;outcome=None;published=[]
    try:
        if a.worker:
            require(a.decision_deadline is not None and a.decision_deadline<=deadline,'invalid inherited deadline');deadline=a.decision_deadline
        else:require(a.decision_deadline is None,'only workers inherit deadlines')
        remaining(deadline,300);f,manifest=validate_freeze(a.freeze,a.sha256,a.review,a.review_sha256);remaining(deadline,300)
        require(a.attempt.resolve()==Path(f['attemptParent']).resolve()/f['attemptName'] and a.attempt.resolve().is_relative_to(ROOT.resolve()),'unexpected preparation output')
        if a.worker:worker(f,manifest,a.attempt.resolve(),deadline);return 0
        quarantine(a.attempt.parent)
        require(not a.attempt.exists() and shutil.disk_usage(ROOT).free>=1024**3,'fresh attempt/1GiB required')
        remaining(deadline,300);a.attempt.mkdir(mode=0o700,parents=True);created=True
        catalog.write(a.attempt/'owner-started.json',dict(freezeSha256=a.sha256,deadline=deadline),65536)
        args=[f['python'],'-I','-S','-B',str(Path(__file__).resolve()),'--worker','--freeze',str(a.freeze.resolve()),'--sha256',a.sha256,
              '--review',str(a.review.resolve()),'--review-sha256',a.review_sha256,'--attempt',str(a.attempt.resolve()),'--decision-deadline',repr(deadline)]
        outcome=owner.run_owned(args,ROOT,a.attempt/'worker',f['environment'],remaining(deadline,300),512*1024,15)
        remaining(deadline,300)
        require(outcome['classification']=='ANALYSIS_COMMAND_SUCCEEDED' and outcome['cleanupConfirmed'] is True,'source preparation failed/cleanup uncertain')
        validate_freeze(a.freeze,a.sha256,a.review,a.review_sha256);remaining(deadline,300)
        report=read_json(a.attempt/'preparation-report.json');require(report['status']=='ABSEIL_SOURCE_PREPARATION_PASSED_CPP_BLOCKED','preparation incomplete')
        pending=a.attempt/'accepted-preparation.pending.json'
        catalog.write(pending,dict(freezeSha256=a.sha256,reviewSha256=a.review_sha256,report=catalog.file_row(a.attempt/'preparation-report.json'),
                                  cleanupConfirmed=True,cppReady=False,BazelSelectionVerified=False,rootAdopted=False),65536);remaining(deadline,300)
        outcome['freezeSha256']=a.sha256;catalog.write(a.attempt/'outcome.pending.json',outcome,65536);remaining(deadline,300)
        for public,source in [(a.attempt/'outcome.json',a.attempt/'outcome.pending.json'),(a.attempt/'accepted-preparation.json',pending)]:
            remaining(deadline,300);published.append((public,source));os.link(source,public);remaining(deadline,300)
        return 2
    except (ValueError,OSError,KeyError,UnicodeError,EOFError,KeyboardInterrupt,subprocess.SubprocessError) as error:
        failure=type(error).__name__+': '+str(error)[:2048];revoked=False
        try:
            base.revoke_links(published);revoked=not os.path.lexists(a.attempt/'accepted-preparation.json') and all(not os.path.lexists(public) for public,source in published)
        except (ValueError,OSError,KeyboardInterrupt) as revoke_error:failure+='; uncertain revocation: '+str(revoke_error)
        if created:
            terminal=dict(classification='PREPARATION_FAILED_OR_REVOKED',workerOutcome=outcome,
                          cleanupConfirmed=None if outcome is None else outcome.get('cleanupConfirmed'),
                          cleanupStatus='CONFIRMED' if outcome is not None and outcome.get('cleanupConfirmed') is True else 'UNCERTAIN',
                          acceptanceRevoked=revoked,publicAcceptancePresent=os.path.lexists(a.attempt/'accepted-preparation.json'),
                          failure=failure,elapsedSeconds=now()-started,freezeSha256=a.sha256)
            catalog.write(a.attempt/('outcome.json' if not (a.attempt/'outcome.json').exists() else 'publication-failure.json'),terminal,65536)
        print(failure,file=sys.stderr);return 1


if __name__=='__main__':raise SystemExit(main())
