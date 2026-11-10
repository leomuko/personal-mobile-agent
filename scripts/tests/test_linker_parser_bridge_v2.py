"""Synthetic V2 worker/parent/owner regressions; no compiler or marker execution."""
import base64
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
s=importlib.util.spec_from_file_location('v2_worker_tests',ROOT/'scripts/diagnostics/linker_parser_bridge_v2/qualify.py')
e=importlib.util.module_from_spec(s);s.loader.exec_module(e)
FIX=ROOT/'planning/fixtures/linker-parser-contract-v2'
PRESENT,ABSENT=e.bridge.parser.PRESENT,e.bridge.parser.ABSENT


class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.stack=contextlib.ExitStack();self.addCleanup(self.stack.close)
        # Only owned temporary directories, never the proposed access-v2-1 attempt.
        parent=e.plan.ATTEMPT.parent
        self.temp=tempfile.TemporaryDirectory(prefix='bridge-synthetic-',dir=parent)
        self.addCleanup(self.temp.cleanup)
        self.attempt=Path(self.temp.name)
        for obj in (e.plan,e.bridge.plan):
            self.stack.enter_context(patch.object(obj,'ATTEMPT',self.attempt))
        self.stack.enter_context(patch.object(e,'ATTEMPT',self.attempt))
        self.f=dict(contract=e.plan.contract())
        self.clock=[0.0]
        self.stack.enter_context(patch.object(e,'now',side_effect=lambda:self.clock[0]))
        def check(deadline):e.require(self.clock[0]<deadline,'synthetic deadline')
        self.stack.enter_context(patch.object(e,'check',side_effect=check))
        self.stack.enter_context(patch.object(e,'remaining',side_effect=lambda d,m: (check(d),min(m,d-self.clock[0]))[1]))
        for obj,name in ((subprocess,'Popen'),(e.os,'access'),(e.c,'metadata_command'),
                         (e.owner,'run_owned'),(e.marker,'verify'),(e.marker,'prepare'),(e.q,'native_rows')):
            self.stack.enter_context(patch.object(obj,name,side_effect=AssertionError('forbidden native boundary '+name)))
        self.calls=[]

    def raw(self,negative=False):
        raw=(FIX/('synthetic-negative.raw.txt' if negative else 'observed-positive.raw.txt')).read_bytes()
        sample=json.loads((FIX/'contract-proposal.json').read_bytes())['sampleBindings']['ATTEMPT']
        return raw.replace(sample.encode(),str(self.attempt).encode())

    def dispatch(self,mode):
        def run(job,attempt,f,deadline,trace):
            self.assertEqual(attempt,self.attempt);self.assertIs(f,self.f)
            self.calls.append(job['id'])
            if mode=='cancel':raise KeyboardInterrupt('synthetic Stop')
            if mode=='timeout':raise subprocess.TimeoutExpired('fake',30)
            negative=mode=='first-negative' or (mode=='negative' and job['id']==ABSENT)
            raw=self.raw(negative);code=int(negative)
            if mode=='code-mismatch':code=1
            argv=['/usr/bin/sandbox-exec','-p',job['profile'],*job['argv']]
            trace(argv,raw,code,0.0)
            if mode=='source-drift':(attempt/'empty.cpp').write_bytes(b'changed')
            if mode=='unexpected-output':(attempt/'helper').write_bytes(b'synthetic')
            if mode=='late-command':self.clock[0]=deadline
            if mode=='returned-raw-mismatch':return raw+b'warning: changed\n',code
            return raw,code
        return run

    def work(self,mode='both-selected'):
        error=None
        with patch.object(e.q,'native_rows',return_value=None), \
             patch.object(e,'preparation_receipt',return_value={'synthetic':True}), \
             patch.object(e,'dispatch',side_effect=self.dispatch(mode)):
            try:e.worker(self.f,self.attempt,300)
            except (ValueError,KeyboardInterrupt,subprocess.SubprocessError) as caught:error=caught
        report=json.loads((self.attempt/'diagnostic-report.json').read_bytes())
        self.assertFalse(report['realLinkerQualified']);self.assertFalse(report['acceptancePublicationAllowed'])
        self.assertFalse((self.attempt/'accepted-probes.json').exists())
        return report,error

    def write_report(self,report):
        # Test-only mutation of synthetic records, never historical evidence.
        (self.attempt/'diagnostic-report.json').write_text(json.dumps(report))

    def traces(self):
        return [json.loads(x) for x in (self.attempt/'command-trace.jsonl').read_bytes().splitlines()]

    def write_traces(self,traces,report):
        raw=b''.join((json.dumps(t)+'\n').encode() for t in traces)
        (self.attempt/'command-trace.jsonl').write_bytes(raw)
        report['commandTraceBytes']=len(raw);self.write_report(report)

    def test_both_selected_worker_and_parent_replay_without_qualification(self):
        report,error=self.work();self.assertIsNone(error)
        self.assertEqual(self.calls,[PRESENT,ABSENT])
        self.assertEqual(report['status'],'DIAGNOSTIC_COLLECTED_NOT_QUALIFICATION')
        self.assertEqual(report['comparison'],'NO_SUPPORT_FOR_POLICY_COUPLING_IN_THIS_COMPARISON')
        self.assertEqual(e.verify_report(self.f,self.attempt),report)
        for row in report['commands']:
            self.assertEqual(row['result']['context']['attempt'],str(self.attempt))
            self.assertFalse(row['result']['realLinkerQualified'])

    def test_negative_remains_failed_with_independently_replayed_comparison(self):
        report,error=self.work('negative');self.assertIsInstance(error,ValueError)
        self.assertEqual(self.calls,[PRESENT,ABSENT])
        self.assertEqual(report['status'],'FAILED')
        self.assertEqual(report['commands'][1]['status'],'FAILED')
        self.assertEqual(report['commands'][1]['result']['terminalStatus'],'FAILED')
        self.assertEqual(report['comparison'],'SUPPORTS_POLICY_SENSITIVE_VALIDATION_ONLY')
        self.assertEqual(e.verify_report(self.f,self.attempt),report)

    def test_first_negative_stops_without_dispatching_second(self):
        report,error=self.work('first-negative');self.assertIsInstance(error,ValueError)
        self.assertEqual(self.calls,[PRESENT]);self.assertEqual(report['notRunCommands'],[ABSENT])
        self.assertEqual(report['status'],'FAILED');self.assertEqual(report['comparison'],'INCONCLUSIVE')
        with self.assertRaises(ValueError):e.verify_report(self.f,self.attempt)

    def test_cancel_and_timeout_stop_first_case_and_retain_report(self):
        for mode,cls in [('cancel',KeyboardInterrupt),('timeout',subprocess.TimeoutExpired)]:
            with self.subTest(mode=mode):
                if (self.attempt/'empty.cpp').exists():(self.attempt/'empty.cpp').unlink()
                (self.attempt/'diagnostic-report.json').unlink(missing_ok=True)
                self.calls=[];report,error=self.work(mode)
                self.assertIsInstance(error,cls);self.assertEqual(self.calls,[PRESENT])
                self.assertEqual(report['status'],'FAILED');self.assertEqual(report['notRunCommands'],[ABSENT])

    def test_invalid_exit_output_or_deadline_stops_first_case(self):
        report,error=self.work('code-mismatch');self.assertIsInstance(error,ValueError)
        self.assertEqual(self.calls,[PRESENT]);self.assertEqual(report['notRunCommands'],[ABSENT])

    def test_deadline_after_capture_cannot_start_second_case(self):
        report,error=self.work('late-command');self.assertIsInstance(error,ValueError)
        self.assertEqual(self.calls,[PRESENT]);self.assertEqual(report['commands'][0]['status'],'FAILED')
        self.assertEqual(report['notRunCommands'],[ABSENT])

    def test_unexpected_native_output_cannot_start_second_case(self):
        report,error=self.work('unexpected-output');self.assertIsInstance(error,ValueError)
        self.assertEqual(self.calls,[PRESENT]);self.assertEqual(report['notRunCommands'],[ABSENT])

    def test_source_substitution_cannot_start_second_case(self):
        report,error=self.work('source-drift');self.assertIsInstance(error,ValueError)
        self.assertEqual(self.calls,[PRESENT]);self.assertEqual(report['notRunCommands'],[ABSENT])

    def test_dispatch_return_and_trace_bytes_cannot_diverge(self):
        report,error=self.work('returned-raw-mismatch');self.assertIsInstance(error,ValueError)
        self.assertEqual(self.calls,[PRESENT]);self.assertEqual(report['notRunCommands'],[ABSENT])

    def test_context_substitution_cannot_start_worker_or_replay(self):
        changed=copy.deepcopy(self.f);changed['contract']['attempt']=str(e.plan.old.ATTEMPT)
        with self.assertRaises(ValueError):e.worker(changed,self.attempt,300)
        self.assertFalse((self.attempt/'empty.cpp').exists())
        self.assertFalse((self.attempt/'diagnostic-report.json').exists())
        with self.assertRaises(ValueError):e.verify_report(changed,self.attempt)

    def test_parent_rejects_each_trace_substitution(self):
        report,error=self.work();self.assertIsNone(error)
        clean=copy.deepcopy(report);original=self.traces()
        mutations=('argv','profile','order','missing','extra','base64','raw-context','raw-word',
                   'exit-bool','exit-nonzero','exit-unsupported','seconds-negative','seconds-nan',
                   'seconds-late','unexpected-field')
        for kind in mutations:
            with self.subTest(kind=kind):
                traces=copy.deepcopy(original);r=copy.deepcopy(clean)
                if kind=='argv':traces[0]['argv'].append('--unexpected')
                if kind=='profile':traces[0]['argv'][2]+='(allow process-exec)'
                if kind=='order':traces.reverse()
                if kind=='missing':traces.pop()
                if kind=='extra':traces.append(traces[0])
                if kind=='base64':traces[0]['outputBase64']='!invalid'
                if kind in ('raw-context','raw-word'):
                    raw=base64.b64decode(traces[0]['outputBase64'])
                    raw=raw.replace(str(self.attempt).encode(),str(e.plan.old.ATTEMPT).encode()) if kind=='raw-context' else raw.replace(b'-ffp-contract=on',b'-ffp-contract=off')
                    traces[0]['outputBase64']=base64.b64encode(raw).decode()
                if kind=='exit-bool':traces[0]['exitCode']=False
                if kind=='exit-nonzero':traces[0]['exitCode']=1
                if kind=='exit-unsupported':traces[0]['exitCode']=2
                if kind=='seconds-negative':traces[0]['seconds']=-1
                if kind=='seconds-nan':traces[0]['seconds']=float('nan')
                if kind=='seconds-late':traces[0]['seconds']=31
                if kind=='unexpected-field':traces[0]['context']='injected'
                self.write_traces(traces,r)
                with self.assertRaises((ValueError,KeyError,TypeError)):e.verify_report(self.f,self.attempt)
        self.write_traces(original,clean)
        self.assertEqual(e.verify_report(self.f,self.attempt),clean)

    def test_parent_rejects_each_worker_summary_or_status_substitution(self):
        clean,error=self.work();self.assertIsNone(error)
        for kind in ('digest','context','exit','status','terminal','classification','flag-true','flag-zero',
                     'comparison','notrun','top-status','publication','real-linker','timing','trace-bytes'):
            with self.subTest(kind=kind):
                r=copy.deepcopy(clean);row=r['commands'][0]
                if kind=='digest':row['result']['rawSha256']='f'*64
                if kind=='context':row['result']['context']['attempt']=str(e.plan.old.ATTEMPT)
                if kind=='exit':row['result']['exitCode']=1
                if kind=='status':row['status']='FAILED'
                if kind=='terminal':row['result']['terminalStatus']='FAILED'
                if kind=='classification':row['result']['classification']='ACCEPTED'
                if kind=='flag-true':row['result']['realLinkerQualified']=True
                if kind=='flag-zero':row['result']['realLinkerQualified']=0
                if kind=='comparison':r['comparison']='SUPPORTS_POLICY_SENSITIVE_VALIDATION_ONLY'
                if kind=='notrun':r['notRunCommands']=[ABSENT]
                if kind=='top-status':r['status']='PASSED'
                if kind=='publication':r['acceptancePublicationAllowed']=True
                if kind=='real-linker':r['realLinkerQualified']=True
                if kind=='timing':row['seconds']=float('inf')
                if kind=='trace-bytes':r['commandTraceBytes']+=1
                self.write_report(r)
                with self.assertRaises(ValueError):e.verify_report(self.f,self.attempt)
        self.write_report(clean);self.assertEqual(e.verify_report(self.f,self.attempt),clean)

    def test_trace_truncation_symlink_fifo_and_unexpected_helper_reject(self):
        clean,error=self.work();self.assertIsNone(error)
        trace=self.attempt/'command-trace.jsonl';raw=trace.read_bytes()
        trace.write_bytes(raw.rstrip(b'\n'))
        with self.assertRaises(ValueError):e.verify_report(self.f,self.attempt)
        trace.write_bytes(raw)
        helper=self.attempt/'unexpected-helper';helper.write_bytes(b'x')
        with self.assertRaises(ValueError):e.verify_report(self.f,self.attempt)
        helper.unlink();trace.unlink();trace.symlink_to(FIX/'observed-positive.raw.txt')
        with self.assertRaises(ValueError):e.verify_report(self.f,self.attempt)
        trace.unlink();e.os.mkfifo(trace,0o600)
        with self.assertRaises(ValueError):e.verify_report(self.f,self.attempt)
        trace.unlink();trace.write_bytes(raw)
        self.assertEqual(e.verify_report(self.f,self.attempt),clean)

    def test_parent_uses_raw_parser_without_trusting_summary_classifier(self):
        report,error=self.work();self.assertIsNone(error)
        with patch.object(e.bridge,'parse',side_effect=ValueError('independent parser rejection')) as parse:
            with self.assertRaisesRegex(ValueError,'independent parser'):e.verify_report(self.f,self.attempt)
        self.assertEqual(parse.call_count,1)

    def test_parent_rejects_boolean_or_float_summary_exit_codes(self):
        clean,error=self.work();self.assertIsNone(error)
        for code in (False,0.0,True,1.0):
            with self.subTest(code=repr(code),kind=type(code).__name__):
                report=copy.deepcopy(clean);report['commands'][0]['result']['exitCode']=code
                self.write_report(report)
                with self.assertRaisesRegex(ValueError,'worker exit-code type mismatch'):
                    e.verify_report(self.f,self.attempt)
        self.write_report(clean)
        self.assertEqual(e.verify_report(self.f,self.attempt),clean)

    def main_case(self,mode='both-selected'):
        self.attempt.rmdir()  # Remove only our empty owned test dir; main must create it exclusively.
        admissions=[];owner_calls=[];replays=[]
        def admit(*args):
            admissions.append(args)
            if mode=='admission-reject' or (mode=='readmission-reject' and len(admissions)==2):raise ValueError('synthetic admission')
            return self.f
        def run(*args):
            owner_calls.append(args)
            if mode=='cleanup-exception':raise subprocess.TimeoutExpired('fake cleanup',15)
            if mode=='owner-cancel':raise KeyboardInterrupt('fake owner Stop')
            report,error=self.work('negative' if mode=='negative' else 'both-selected')
            if error is not None and mode!='negative':raise error
            return dict(cleanupConfirmed=mode!='uncertain-cleanup',
                        classification='ANALYSIS_COMMAND_FAILED' if mode=='negative' else
                        'ANALYSIS_COMMAND_FAILED' if mode=='owner-status-mismatch' else 'ANALYSIS_COMMAND_SUCCEEDED')
        actual_replay=e.verify_report
        def replay(*args):replays.append(args);return actual_replay(*args)
        args=['bridge','--freeze','synthetic-freeze','--sha256','1'*64,'--review','synthetic-review',
              '--review-sha256','2'*64,'--attempt',str(self.attempt)]
        with patch.object(sys,'argv',args),patch.object(e,'admission',side_effect=admit), \
             patch.object(e,'quarantine',return_value=None),patch.object(e.owner,'run_owned',side_effect=run), \
             patch.object(e,'verify_report',side_effect=replay),contextlib.redirect_stderr(io.StringIO()):
            code=e.main()
        result=json.loads((self.attempt/'outcome.json').read_bytes()) if self.attempt.exists() else None
        self.assertFalse((self.attempt/'accepted-probes.json').exists())
        if result is not None:self.assertFalse(result['realLinkerQualified']);self.assertFalse(result['acceptancePublished'])
        return code,result,admissions,owner_calls,replays

    def test_main_both_selected_is_diagnostic_not_acceptance(self):
        code,r,admissions,owners,replays=self.main_case()
        self.assertEqual(code,2);self.assertEqual(r['classification'],'DIAGNOSTIC_COLLECTED')
        self.assertTrue(r['cleanupConfirmed']);self.assertEqual(len(admissions),2)
        self.assertEqual(len(owners),1);self.assertEqual(len(replays),1)

    def test_main_expected_negative_is_failed_not_accepted(self):
        code,r,_,owners,replays=self.main_case('negative')
        self.assertEqual(code,1);self.assertEqual(r['classification'],'DIAGNOSTIC_FAILED')
        self.assertTrue(r['cleanupConfirmed']);self.assertEqual(len(owners),1);self.assertEqual(len(replays),1)

    def test_uncertain_cleanup_prevents_parent_replay_and_readmission(self):
        code,r,admissions,owners,replays=self.main_case('uncertain-cleanup')
        self.assertEqual(code,1);self.assertEqual(r['cleanupStatus'],'UNCERTAIN')
        self.assertEqual(len(admissions),1);self.assertEqual(len(owners),1);self.assertEqual(replays,[])

    def test_cleanup_exception_retains_uncertain_terminal(self):
        code,r,admissions,_,replays=self.main_case('cleanup-exception')
        self.assertEqual(code,1);self.assertEqual(r['cleanupStatus'],'UNCERTAIN')
        self.assertEqual(len(admissions),1);self.assertEqual(replays,[])

    def test_owner_cancel_retains_uncertain_terminal_without_replay(self):
        code,r,_,_,replays=self.main_case('owner-cancel')
        self.assertEqual(code,1);self.assertEqual(r['cleanupStatus'],'UNCERTAIN');self.assertEqual(replays,[])

    def test_failed_admission_creates_no_attempt_or_owner(self):
        code,r,_,owners,replays=self.main_case('admission-reject')
        self.assertEqual(code,1);self.assertIsNone(r);self.assertEqual(owners,[]);self.assertEqual(replays,[])

    def test_readmission_failure_cannot_publish_diagnostic_result(self):
        code,r,admissions,_,replays=self.main_case('readmission-reject')
        self.assertEqual(code,1);self.assertEqual(r['classification'],'DIAGNOSTIC_FAILED_OR_REVOKED')
        self.assertTrue(r['cleanupConfirmed']);self.assertEqual(len(admissions),2);self.assertEqual(replays,[])

    def test_owner_terminal_mismatch_cannot_publish_diagnostic_result(self):
        code,r,_,_,replays=self.main_case('owner-status-mismatch')
        self.assertEqual(code,1);self.assertEqual(r['classification'],'DIAGNOSTIC_FAILED_OR_REVOKED')
        self.assertEqual(len(replays),1)

    def test_quarantine_rejects_uncertain_or_missing_terminal_and_forbidden_acceptance(self):
        with tempfile.TemporaryDirectory() as tmp:
            parent=Path(tmp);prior=parent/'synthetic-prior';prior.mkdir()
            (prior/'owner-started.json').write_text('{}')
            with self.assertRaises(ValueError):e.quarantine(parent)
            (prior/'outcome.json').write_text(json.dumps(dict(cleanupConfirmed=False)))
            with self.assertRaises(ValueError):e.quarantine(parent)
            (prior/'outcome.json').write_text(json.dumps(dict(cleanupConfirmed=True)))
            e.quarantine(parent)
            (prior/'accepted-probes.json').write_text('{}')
            with self.assertRaises(ValueError):e.quarantine(parent)
            (prior/'accepted-probes.json').unlink();(prior/'publication-failure.json').write_text('{}')
            with self.assertRaises(ValueError):e.quarantine(parent)


if __name__=='__main__':unittest.main()
