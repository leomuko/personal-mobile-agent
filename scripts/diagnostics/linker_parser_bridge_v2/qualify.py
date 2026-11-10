#!/usr/bin/env python3
"""Isolated diagnostic executor. Native collection requires separate authorization."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import resource
import shutil
import subprocess
import sys
import importlib.util

HERE=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('access_executor_plan',HERE/'plan.py')
plan=importlib.util.module_from_spec(s);s.loader.exec_module(plan)
marker=plan.load('marker',plan.ROOT/'scripts/diagnostics/linker_executable_access_v1/marker.py')
bridge=plan.load('bridge',HERE/'bridge.py')
ROOT,FIX,ATTEMPT=plan.ROOT,plan.FIX,plan.ATTEMPT
q,c,owner,require=plan.q,plan.c,plan.owner,plan.require
now,remaining=plan.old.prior.now,plan.old.prior.remaining
MIB=1024**2
ERRORS=(ValueError,OSError,KeyError,TypeError,UnicodeError,EOFError,KeyboardInterrupt,subprocess.SubprocessError)


def check(deadline):
    remaining(deadline,300)
    require(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss<=512*MIB,'worker RSS ceiling')


def preparation_receipt():
    value=q.data(plan.PREPARATION,65536)
    proposal=plan.old.proposal()
    require(value['proposalSha256']==plan.old.PROPOSAL_SHA,'marker proposal changed')
    require(value['priorReceiptSha256']==owner.digest(plan.old.PREPARATION), 'prior marker receipt changed')
    marker.verify(Path(proposal['target']['path']),proposal['target']['text'].encode(),value['marker'])
    return value


def required_inputs():
    return {str(HERE/name) for name in ('plan.py','bridge.py','qualify.py','freeze.py','launch.py','README.md')} | {
        str(plan.old.HERE/'plan.py'),str(plan.old.HERE/'marker.py'),str(plan.PARSER),
        str(plan.PARSER_LOCK),str(plan.PARSER_QA),str(bridge.parser.CONTRACT_PATH),
        str(plan.old.PROPOSAL),str(plan.old.STATIC_LOCK),str(plan.old.QA),str(plan.PREPARATION),str(plan.old.PREPARATION),
        str(ROOT/'scripts/tests/test_linker_parser_bridge_v2.py'),
        str(ROOT/'scripts/tests/test_linker_bridge_admission.py'),
        str(ROOT/'planning/P2_CPP_PARSER_BRIDGE_V2_IMPLEMENTATION.md')}


def validate_freeze(f):
    baseline=plan.base_freeze()
    require(f['protocol']==plan.PROTOCOL and f['scope']==plan.SCOPE
            and f['contract']==plan.contract(),'diagnostic contract changed')
    require(f['baselineSha256']==plan.old.BASE_SHA and f['proposalSha256']==plan.old.PROPOSAL_SHA
            and f['implementationSha256']==owner.digest(__file__),'diagnostic identity changed')
    plan.parser_review()
    bridge.context(f)
    require(f['parserSha256']==plan.PARSER_SHA and f['parserLockSha256']==plan.PARSER_LOCK_SHA, 'parser identity changed')
    rows=f['inputs'];indexed={r['path']:r for r in rows}
    require(len(rows)<=256 and len(indexed)==len(rows),'duplicate/excess diagnostic inputs')
    require(required_inputs()|{f['contract']['target']['path']}<=set(indexed),'unbound diagnostic input')
    for row in baseline['inputs']:
        require(indexed.get(row['path'])==row,'changed baseline input')
    require(f['trees']==baseline['trees'] and f['nativeFiles']==baseline['nativeFiles']
            and f['backendCatalog']==baseline['backendCatalog'] and f['cacheMap']==baseline['cacheMap'],
            'changed native/tree/catalog scope')
    require(f['launchProfile']==dict(hostTool='exec_command',sandbox_permissions='require_escalated',
            shell='/bin/zsh',login=False,workdir=str(ROOT),persistentRuleRequested=False,
            maximumParentInvocations=1,automaticFallback=False),'changed outer launch profile')
    require(f['markerReceipt']==preparation_receipt(),'marker receipt changed')
    require(f['executionAuthorized'] is False and f['acceptancePublicationAllowed'] is False,
            'freeze cannot authorize/publish acceptance')


def admission(path,sha,review_path,review_sha,deadline=None):
    if deadline is not None:check(deadline)
    f,actual=q.base.read_json_bound(path)
    review,actual_review=q.base.read_json_bound(review_path,65536)
    require(actual==sha and actual_review==review_sha and review['verdict']=='PASS'
            and review['scope']==plan.SCOPE and review['freezeSha256']==sha,
            'missing/mismatched independent implementation review')
    validate_freeze(f)
    q.check_inputs(f)
    if deadline is not None:check(deadline)
    return f


def quarantine(parent):
    plan.old.prior.quarantine(parent)
    if not parent.exists():return
    for child in parent.iterdir():
        require(not (child/'accepted-probes.json').exists(),'foreign acceptance publication; quarantined')


def dispatch(job,attempt,f,deadline,trace):
    check(deadline)
    # Do not use the old role classifier to widen compiler permissions. Exact
    # closed profiles and argv are independently checked against the new recipe.
    require(job in plan.contract()['commands'],'unapproved diagnostic job')
    preparation_receipt()
    captured=[]
    def capture(argv,raw,exitcode,seconds):
        captured.append((argv,raw,exitcode,seconds));trace(argv,raw,exitcode,seconds)
    argv=['/usr/bin/sandbox-exec','-p',job['profile'],*job['argv']]
    try:
        c.metadata_command(argv,maximum=256*1024,seconds=remaining(deadline,30),trace=capture,
                           cwd=attempt,env=f['contract']['nativeEnvironment'])
    except ValueError as error:
        # Only the exact completed nonzero dispatch is inspectable as a negative.
        # Timeouts, truncation, decoding and cleanup failures always propagate.
        require(str(error)=='metadata command failed: 1' and len(captured)==1
                and captured[0][2]==1,'incomplete/unexpected diagnostic dispatch: '+str(error))
    finally:
        preparation_receipt()
        check(deadline)
    require(len(captured)==1 and captured[0][0]==argv,'missing/mismatched command capture')
    return captured[0][1],captured[0][2]



def outputs(attempt,report=False):
    allowed={'owner-started.json','worker.stdout.log','worker.stderr.log','empty.cpp','command-trace.jsonl'}
    if report:allowed.add('diagnostic-report.json')
    observed=c.tree(attempt,16,8*MIB)
    require(set(observed['files'])<=allowed and all(r['kind']=='file' for r in observed['files'].values()),
            'unexpected generated output/helper/alias')
    source=c.file_row(attempt/'empty.cpp')
    require(source['bytes']==len(plan.SOURCE) and source['sha256']==hashlib.sha256(plan.SOURCE).hexdigest(),
            'source substitution')


def worker(f,attempt,deadline):
    report=dict(status='RUNNING',commands=[],notRunCommands=[],comparison='INCONCLUSIVE',
                realLinkerQualified=False,acceptancePublicationAllowed=False)
    trace_bytes=0
    parsed_results=[]
    bridge.context(f,attempt)
    def trace(argv,raw,exitcode,seconds):
        nonlocal trace_bytes
        data=(json.dumps(dict(argv=argv,outputBase64=base64.b64encode(raw).decode(),exitCode=exitcode,
                              seconds=seconds),separators=(',',':'))+'\n').encode()
        trace_bytes+=len(data);require(trace_bytes<=2*MIB,'aggregate trace ceiling')
        with (attempt/'command-trace.jsonl').open('ab') as sink:sink.write(data)
    try:
        check(deadline);q.native_rows(f);preparation_receipt()
        with (attempt/'empty.cpp').open('xb') as stream:stream.write(plan.SOURCE)
        for job in f['contract']['commands']:
            check(deadline);row=dict(id=job['id'],status='RUNNING');report['commands'].append(row)
            started=now();end=min(deadline,started+30)
            raw,code=dispatch(job,attempt,f,end,trace)
            parsed=bridge.parse(f,job,raw,code)
            parsed_results.append(parsed)
            result=bridge.summary(parsed)
            row['result']=result
            preparation_receipt();check(end)
            outputs(attempt)
            check(end)
            row.update(status='FAILED' if code else 'PASSED',seconds=now()-started)
            if code:
                raise ValueError('negative command retained as terminal failure')
            require(result['classification']=='EXACT_SYNTHETIC_PATH_SELECTED','unexpected positive control')
        report['status']='DIAGNOSTIC_COLLECTED_NOT_QUALIFICATION'
    except BaseException as error:
        report.update(status='FAILED',failure=type(error).__name__+': '+str(error)[:2048])
        if report['commands'] and report['commands'][-1]['status']=='RUNNING':report['commands'][-1]['status']='FAILED'
        raise
    finally:
        report['comparison']=bridge.parser.compare_outputs(parsed_results)
        report['notRunCommands']=[j['id'] for j in f['contract']['commands']
                                if j['id'] not in {r['id'] for r in report['commands']}]
        report['commandTraceBytes']=trace_bytes
        report['peakWorkerRssBytes']=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        c.write(attempt/'diagnostic-report.json',report,2*MIB)


def verify_report(f,attempt):
    """Parent parses captured bytes independently, after cleanup and readmission."""
    bridge.context(f,attempt)
    outputs(attempt,report=True)
    report=q.data(attempt/'diagnostic-report.json',2*MIB)
    trace_path=attempt/'command-trace.jsonl'
    before=c.file_row(trace_path)
    require(before['bytes']<=2*MIB,'trace byte ceiling')
    with trace_path.open('rb') as source:lines=source.read(2*MIB+1)
    require(c.file_row(trace_path)==before,'trace drift during replay')
    require(len(lines)<=2*MIB and lines.endswith(b'\n'),'trace byte ceiling/termination')
    traces=[json.loads(line) for line in lines.splitlines()]
    require(type(report['commands']) is list and len(report['commands'])==len(traces)<=2,'missing/extra diagnostic traces')
    require(report['commandTraceBytes']==len(lines),'trace byte accounting mismatch')
    parsed_results=[]
    for index,(job,row,t) in enumerate(zip(f['contract']['commands'],report['commands'],traces)):
        require(set(t)=={'argv','outputBase64','exitCode','seconds'},'unexpected trace fields')
        require(row['id']==job['id'] and t['argv']==['/usr/bin/sandbox-exec','-p',job['profile'],*job['argv']],
                'trace argv/order mismatch')
        require(type(t['seconds']) in (int,float) and 0<=t['seconds']<=30,'invalid trace timing')
        parsed=bridge.parse(f,job,base64.b64decode(t['outputBase64'],validate=True),t['exitCode'])
        parsed_results.append(parsed)
        require(type(row['result']['exitCode']) is int, 'worker exit-code type mismatch')
        require(row['result']==bridge.summary(parsed) and row['status']==parsed.command_status,
                'trace classification/status mismatch')
        require(all(row['result'][key] is False for key in
                    ('realLinkerQualified','compilerQualified','acceptancePublicationAllowed')),
                'non-boolean qualification/publication claim')
        require(type(row['seconds']) in (int,float) and 0<=row['seconds']<=30,
                'invalid worker timing')
        require(index==0 or parsed_results[0].raw_exit_code==0,'continued after failed first case')
    comparison=bridge.parser.compare_outputs(parsed_results)
    require(report['comparison']==comparison and report['realLinkerQualified'] is False
            and report['acceptancePublicationAllowed'] is False,'worker overclaim')
    require(report['notRunCommands']==[j['id'] for j in f['contract']['commands'][len(traces):]],'wrong not-run list')
    require(len(traces)==2,'incomplete comparison')
    expected_status='FAILED' if any(t['exitCode']!=0 for t in traces) else 'DIAGNOSTIC_COLLECTED_NOT_QUALIFICATION'
    require(report['status']==expected_status,'top-level report/trace terminal mismatch')
    outputs(attempt,report=True)
    return report


def main():
    started=now();deadline=started+300
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('freeze','review','attempt'):p.add_argument('--'+name,type=Path,required=True)
    for name in ('sha256','review-sha256'):p.add_argument('--'+name,required=True)
    p.add_argument('--worker',action='store_true');p.add_argument('--decision-deadline',type=float)
    a=p.parse_args();created=invoked=False;outcome=None
    with plan.old.prior.interruptions():
        try:
            if a.worker:
                require(a.decision_deadline is not None and a.decision_deadline<=deadline,'invalid inherited deadline')
                deadline=a.decision_deadline
            else:require(a.decision_deadline is None,'only worker inherits deadline')
            f=admission(a.freeze,a.sha256,a.review,a.review_sha256,deadline)
            attempt=a.attempt.absolute()
            require(attempt==ATTEMPT and str(attempt)==f['contract']['attempt'] and attempt.resolve()==attempt,
                    'unexpected diagnostic attempt')
            if a.worker:
                v=q.data(attempt/'owner-started.json',65536)
                require(v['freezeSha256']==a.sha256 and v['decisionDeadlineMonotonic']==deadline
                        and v['decisionStartedMonotonic']<=now()<deadline,'missing inherited owner')
                worker(f,attempt,deadline);return 0
            quarantine(attempt.parent)
            require(not os.path.lexists(attempt) and shutil.disk_usage(ROOT).free>=1024*MIB,
                    'fresh attempt/1 GiB capacity required')
            check(deadline);attempt.mkdir(parents=True,mode=0o700);created=True
            c.write(attempt/'owner-started.json',dict(freezeSha256=a.sha256,
                    decisionStartedMonotonic=started,decisionDeadlineMonotonic=deadline),65536)
            argv=[f['contract']['python'],'-I','-S','-B',str(HERE/'qualify.py'),
                  '--freeze',str(a.freeze.resolve()),'--sha256',a.sha256,'--review',str(a.review.resolve()),
                  '--review-sha256',a.review_sha256,'--attempt',str(attempt),'--worker',
                  '--decision-deadline',repr(deadline)]
            check(deadline);invoked=True
            outcome=owner.run_owned(argv,ROOT,attempt/'worker',f['contract']['ownerEnvironment'],
                                    remaining(deadline,300),512*1024,15)
            check(deadline)
            require(outcome['cleanupConfirmed'] is True,'cleanup uncertain')
            admission(a.freeze,a.sha256,a.review,a.review_sha256,deadline)
            report=verify_report(f,attempt)
            failed=report['status']=='FAILED'
            require((failed and outcome['classification']=='ANALYSIS_COMMAND_FAILED') or
                    (not failed and outcome['classification']=='ANALYSIS_COMMAND_SUCCEEDED'),
                    'worker/report terminal mismatch')
            require(not os.path.lexists(attempt/'accepted-probes.json'),'forbidden acceptance publication')
            check(deadline)
            c.write(attempt/'outcome.json',dict(classification='DIAGNOSTIC_FAILED' if failed else 'DIAGNOSTIC_COLLECTED',
                comparison=report['comparison'],cleanupStatus='CONFIRMED',cleanupConfirmed=True,
                workerOutcome=outcome,elapsedSeconds=now()-started,freezeSha256=a.sha256,
                realLinkerQualified=False,acceptancePublished=False),65536)
            check(deadline)
            return 1 if failed else 2
        except ERRORS as error:
            if created:
                confirmed=None if outcome is None else outcome.get('cleanupConfirmed')
                terminal=dict(classification='DIAGNOSTIC_FAILED_OR_REVOKED',failure=type(error).__name__+': '+str(error)[:2048],
                    cleanupConfirmed=confirmed,cleanupStatus=('NOT_APPLICABLE_NO_CHILD' if not invoked else
                        'CONFIRMED' if confirmed is True else 'UNCERTAIN'),workerOutcome=outcome,
                    elapsedSeconds=now()-started,freezeSha256=a.sha256,realLinkerQualified=False,acceptancePublished=False)
                # Never replace a previously written terminal record after a late
                # interruption: retain a separate revocation/uncertainty record.
                dest=attempt/('publication-failure.json' if os.path.lexists(attempt/'outcome.json') else 'outcome.json')
                c.write(dest,terminal,65536)
            print(type(error).__name__+': '+str(error)[:2048],file=sys.stderr);return 1


if __name__=='__main__':raise SystemExit(main())
