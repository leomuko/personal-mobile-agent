"""Synthetic diagnostic policy/admission checks; no native tools or access probes."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2]

def load(name,path):
 s=importlib.util.spec_from_file_location(name,ROOT/path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m

p=load('access_test_plan','scripts/diagnostics/linker_executable_access_v1/plan.py')
policy=load('access_test_policy','scripts/diagnostics/linker_executable_access_v1/policy.py')
fixture=load('access_prior_fixture','scripts/tests/test_dawn_cpp_linker.py')


def text(job,negative=False):
 old=p.prior.plan.commands(p.ATTEMPT)[1]
 raw=fixture.synthetic_text(old)
 target=next(x.split('=',1)[1] for x in job['argv'] if x.startswith('--ld-path='))
 raw=raw.replace(json.dumps(str(p.LD)),json.dumps('ld' if negative else target))
 if negative:raw="clang: error: invalid linker name in argument '--ld-path="+target+"'\n"+raw
 return raw.encode()


class DiagnosticContractTests(unittest.TestCase):
 def test_contract_is_exact_proposed_case_pair_and_shared_environment(self):
  c=p.contract();v=p.proposal()
  self.assertEqual(c['commands'],v['cases']);self.assertEqual(c['nativeEnvironment'],v['nativeEnvironment'])
  self.assertEqual(c['commands'][0]['argv'],c['commands'][1]['argv'])
  self.assertEqual(c['commands'][0]['profile'],c['commands'][1]['profile']+
   '(allow process-exec (literal '+json.dumps(c['target']['path'])+'))')
  for j in c['commands']:
   self.assertNotIn(str(p.LD),j['allowedExecutablePaths']);self.assertIn('-###',j['argv'])
  self.assertFalse(c['acceptancePublicationAllowed']);self.assertFalse(c['realLinkerExecutionAllowed'])

 def test_target_substitution_flag_loss_and_profile_widening_fail(self):
  original=p.proposal()
  for mutation in ('target','dryrun','profile','order','env'):
   v=copy.deepcopy(original)
   if mutation=='target':v['cases'][0]['argv'][-8]='--ld-path=/usr/bin/ld'
   if mutation=='dryrun':v['cases'][0]['argv'].remove('-###')
   if mutation=='profile':v['cases'][0]['profile']+='(allow process-exec)'
   if mutation=='order':v['cases'].reverse()
   if mutation=='env':v['nativeEnvironment']['PATH']='/usr/bin'
   with self.subTest(mutation=mutation),patch.object(p,'proposal',return_value=v),self.assertRaises(ValueError):p.contract()

 def test_exact_positive_negative_pair_supports_only_diagnostic_hypothesis(self):
  jobs=p.contract()['commands'];rows=[]
  for j,neg in zip(jobs,[False,True]):
   r=policy.classify(j,text(j,neg),1 if neg else 0)
   self.assertFalse(r['qualified']);rows.append(dict(id=j['id'],result=r))
  self.assertEqual(policy.comparison(rows),'SUPPORTS_POLICY_SENSITIVE_VALIDATION_ONLY')

 def test_both_selected_does_not_support_coupling(self):
  rows=[dict(id=j['id'],result=policy.classify(j,text(j),0)) for j in p.contract()['commands']]
  self.assertEqual(policy.comparison(rows),'NO_SUPPORT_FOR_POLICY_COUPLING_IN_THIS_COMPARISON')

 def test_negative_exitcode_alone_unknown_diagnostic_or_extra_jobs_reject(self):
  j=p.contract()['commands'][1];raw=text(j,True)
  for bad,code in [(text(j),1),(raw,2),(raw.replace(b'invalid linker name',b'other failure'),1),
                   (raw+ b'warning: unbound\n',1),(raw+ b'"/bin/sh"\n',1),
                   (raw.replace(b'"ld"',b'"/usr/bin/ld"'),1),(raw*2,1)]:
   with self.subTest(bad=bad[-60:]),self.assertRaises(ValueError):policy.classify(j,bad,code)

 def test_positive_literal_target_and_closed_options_enforced(self):
  j=p.contract()['commands'][0];raw=text(j)
  target=p.contract()['target']['path'].encode()
  for bad in [raw.replace(target,b'ld'),raw.replace(target,str(p.LD).encode()),
              raw.replace(b'"-cc1"',b'"-cc1" "-unknown"'),raw+b'error: nope\n',
              raw+b'\x00',raw+b'\xff']:
   with self.subTest(bad=bad[-60:]),self.assertRaises((ValueError,UnicodeError)):policy.classify(j,bad,0)

 def test_partial_reversed_duplicate_cases_cannot_support_comparison(self):
  jobs=p.contract()['commands'];rows=[dict(id=j['id']) for j in jobs]
  self.assertEqual(policy.comparison(rows[:1]),'INCONCLUSIVE')
  for bad in (rows[::-1],[rows[0]]*2,rows+rows):
   with self.assertRaises(ValueError):policy.comparison(bad)

 def test_local_closed_grammar_diff_has_only_two_diagnostic_changes(self):
  source=(ROOT/'scripts/diagnostics/dawn_cpp_toolchain_v1/policy.py').read_text()
  expected=source[source.index('def driver_jobs('):].strip()
  expected=expected.replace('def driver_jobs(','def diagnostic_jobs(')
  expected=expected.replace("require(str(Path(cc[0]).resolve())==clang and str(Path(ld[0]).resolve())==linker,'unexpected compiler/linker helper')",
    "require(cc[0]==clang and ld[0]==linker,'unexpected literal compiler/diagnostic supplier')")
  expected=expected.replace("str(Path(linker).parent.parent/'lib/libLTO.dylib')","str(plan.LD.parent.parent/'lib/libLTO.dylib')")
  current=(ROOT/'scripts/diagnostics/linker_executable_access_v1/policy.py').read_text()
  actual=current[current.index('def diagnostic_jobs('):current.index('\n\ndef classify(')].strip()
  self.assertEqual(actual,expected)


class AdmissionTests(unittest.TestCase):
 def setUp(self):self.e=load('access_admission_test','scripts/diagnostics/linker_executable_access_v1/qualify.py')

 def test_bad_review_stops_before_marker_and_input_or_owner(self):
  e=self.e
  for review in ({'verdict':'FAIL'},{'verdict':'PASS','scope':'bad','freezeSha256':'f'}):
   with patch.object(e.q.base,'read_json_bound',side_effect=[({},'f'),(review,'r')]),\
        patch.object(e,'validate_freeze',side_effect=AssertionError('too late')),\
        patch.object(e.owner,'run_owned',side_effect=AssertionError('no native')):
    with self.assertRaises(ValueError):e.admission('freeze','f','review','r')

 def test_changed_profile_or_target_rejected_before_input_checks(self):
  e=self.e;baseline=p.base_freeze();contract=p.contract()
  f=dict(protocol=p.PROTOCOL,scope=p.SCOPE,contract=contract,baselineSha256=p.BASE_SHA,
   proposalSha256=p.PROPOSAL_SHA,implementationSha256=e.owner.digest(e.__file__))
  for field in ('profile','target','order','deadline'):
   v=copy.deepcopy(f)
   if field=='profile':v['contract']['commands'][0]['profile']+='(allow process-exec)'
   if field=='target':v['contract']['target']['path']='/usr/bin/ld'
   if field=='order':v['contract']['commands'].reverse()
   if field=='deadline':v['contract']['seconds']=301
   with self.subTest(field=field),self.assertRaisesRegex(ValueError,'contract changed'):e.validate_freeze(v)

 def values(self):
  e=self.e;b=p.base_freeze();c=p.contract()
  rows={r['path']:copy.deepcopy(r) for r in b['inputs']}
  for path in e.required_inputs()|{c['target']['path']}:
   rows.setdefault(path,dict(path=path,bytes=0,mode=420,sha256='unit-only'))
  return dict(protocol=p.PROTOCOL,scope=p.SCOPE,contract=c,baselineSha256=p.BASE_SHA,
   proposalSha256=p.PROPOSAL_SHA,implementationSha256=e.owner.digest(e.__file__),inputs=list(rows.values()),
   trees=b['trees'],nativeFiles=b['nativeFiles'],backendCatalog=b['backendCatalog'],cacheMap=b['cacheMap'],
   markerReceipt={'unit':True},executionAuthorized=False,acceptancePublicationAllowed=False,
   launchProfile=dict(hostTool='exec_command',sandbox_permissions='require_escalated',shell='/bin/zsh',
    login=False,workdir=str(ROOT),persistentRuleRequested=False,maximumParentInvocations=1,automaticFallback=False))

 def test_complete_in_memory_admission_never_invokes_owner(self):
  e=self.e;f=self.values();r=dict(verdict='PASS',scope=p.SCOPE,freezeSha256='f')
  original=e.q.base.read_json_bound
  def read(path,*args):
   if str(path)=='freeze':return f,'f'
   if str(path)=='review':return r,'r'
   return original(path,*args)
  with patch.object(e.q.base,'read_json_bound',side_effect=read),\
       patch.object(e,'preparation_receipt',return_value={'unit':True}),\
       patch.object(e.q,'check_inputs') as checks,\
       patch.object(e.owner,'run_owned',side_effect=AssertionError('no owner')):
   self.assertIs(e.admission('freeze','f','review','r'),f);self.assertEqual(checks.call_count,1)

 def test_missing_input_duplicate_binding_or_changed_baseline_never_admit(self):
  e=self.e
  for mode in ('missing','duplicate','baseline','tree','outer','publication','marker'):
   f=self.values()
   if mode=='missing':f['inputs']=[r for r in f['inputs'] if r['path']!=str(e.HERE/'marker.py')]
   if mode=='duplicate':f['inputs'].append(f['inputs'][0])
   if mode=='baseline':f['inputs'][0]['sha256']='tamper'
   if mode=='tree':f['trees']=[]
   if mode=='outer':f['launchProfile']['automaticFallback']=True
   if mode=='publication':f['acceptancePublicationAllowed']=True
   if mode=='marker':f['markerReceipt']={}
   with self.subTest(mode=mode),patch.object(e,'preparation_receipt',return_value={'unit':True}),\
        self.assertRaises(ValueError):e.validate_freeze(f)

 def test_actual_input_hash_change_rejects_before_owner(self):
  e=self.e;f=self.values();r=dict(verdict='PASS',scope=p.SCOPE,freezeSha256='f')
  original=e.q.base.read_json_bound
  def read(path,*args):
   if str(path)=='freeze':return f,'f'
   if str(path)=='review':return r,'r'
   return original(path,*args)
  with patch.object(e.q.base,'read_json_bound',side_effect=read),\
       patch.object(e,'preparation_receipt',return_value={'unit':True}),\
       patch.object(e.q,'check_inputs',side_effect=ValueError('changed bytes')),\
       patch.object(e.owner,'run_owned',side_effect=AssertionError('no owner')):
   with self.assertRaisesRegex(ValueError,'changed bytes'):e.admission('freeze','f','review','r')

 def test_launch_bad_hash_never_admits_or_runs(self):
  launch=load('access_launch_test','scripts/diagnostics/linker_executable_access_v1/launch.py')
  with patch.object(launch.e,'admission',side_effect=AssertionError('no admission')):
   with self.assertRaises(ValueError):launch.request('bad','bad')


class DispatchAndReplayTests(unittest.TestCase):
 def setUp(self):self.e=load('access_dispatch_test','scripts/diagnostics/linker_executable_access_v1/qualify.py')

 def test_exact_completed_negative_keeps_raw_exit_and_profile(self):
  e=self.e;c=p.contract();job=c['commands'][1];raw=text(job,True);seen=[]
  def execute(argv,**kw):
   self.assertEqual(argv,['/usr/bin/sandbox-exec','-p',job['profile'],*job['argv']])
   self.assertEqual(kw['env'],c['nativeEnvironment'])
   kw['trace'](argv,raw,1,.1);raise ValueError('metadata command failed: 1')
  with patch.object(e,'check'),patch.object(e,'remaining',return_value=30),\
       patch.object(e,'preparation_receipt'),patch.object(e.c,'metadata_command',side_effect=execute):
   self.assertEqual(e.dispatch(job,p.ATTEMPT,dict(contract=c),100,lambda *a:seen.append(a)),(raw,1))
  self.assertEqual(seen[0][1],raw);self.assertEqual(seen[0][2],1)

 def test_timeout_or_cleanup_error_cannot_be_reclassified_as_expected_negative(self):
  e=self.e;c=p.contract();j=c['commands'][1]
  for error in ('metadata command deadline','metadata output ceiling','other failure'):
   def execute(argv,**kw):
    kw['trace'](argv,text(j,True),1,.1);raise ValueError(error)
   with self.subTest(error=error),patch.object(e,'check'),patch.object(e,'remaining',return_value=30),\
        patch.object(e,'preparation_receipt'),patch.object(e.c,'metadata_command',side_effect=execute),\
        self.assertRaises(ValueError):e.dispatch(j,p.ATTEMPT,dict(contract=c),100,lambda *a:None)

 def test_bad_job_or_marker_drift_blocks_dispatch(self):
  e=self.e;c=p.contract();j=copy.deepcopy(c['commands'][0]);j['profile']+='(allow process-exec)'
  with patch.object(e,'check'),patch.object(e.c,'metadata_command',side_effect=AssertionError('no native')):
   with self.assertRaises(ValueError):e.dispatch(j,p.ATTEMPT,dict(contract=c),100,lambda *a:None)
  with patch.object(e,'check'),patch.object(e,'preparation_receipt',side_effect=ValueError('drift')),\
       patch.object(e.c,'metadata_command',side_effect=AssertionError('no native')):
   with self.assertRaisesRegex(ValueError,'drift'):e.dispatch(c['commands'][0],p.ATTEMPT,dict(contract=c),100,lambda *a:None)

 def report(self,attempt):
  import base64
  jobs=p.contract()['commands'];traces=[];rows=[]
  for j,negative in zip(jobs,[False,True]):
   raw=text(j,negative);code=int(negative)
   traces.append(dict(argv=['/usr/bin/sandbox-exec','-p',j['profile'],*j['argv']],
    outputBase64=base64.b64encode(raw).decode(),exitCode=code,seconds=.1))
   rows.append(dict(id=j['id'],status='FAILED' if negative else 'PASSED',result=policy.classify(j,raw,code)))
  r=dict(status='FAILED',commands=rows,notRunCommands=[],comparison=policy.comparison(rows),
   realLinkerQualified=False,acceptancePublicationAllowed=False)
  (attempt/'command-trace.jsonl').write_text(''.join(json.dumps(t)+'\n' for t in traces))
  (attempt/'diagnostic-report.json').write_text(json.dumps(r));(attempt/'empty.cpp').write_bytes(p.SOURCE)
  return r,traces

 def test_parent_replays_pair_without_promoting_failure_to_acceptance(self):
  with tempfile.TemporaryDirectory() as tmp:
   a=Path(tmp).resolve();r,_=self.report(a)
   got=self.e.verify_report(dict(contract=p.contract()),a)
   self.assertEqual(got,r);self.assertEqual(got['status'],'FAILED');self.assertFalse(got['realLinkerQualified'])
   self.assertFalse((a/'accepted-probes.json').exists())

 def test_missing_tampered_reordered_traces_or_foreign_output_reject(self):
  for mode in ('missing','argv','status','claim','extra','source','terminal','unknown'):
   with self.subTest(mode=mode),tempfile.TemporaryDirectory() as tmp:
    a=Path(tmp).resolve();r,traces=self.report(a)
    if mode=='missing':traces.pop()
    if mode=='argv':traces[0]['argv'].append('-c')
    if mode=='status':r['commands'][1]['status']='PASSED'
    if mode=='claim':r['realLinkerQualified']=True
    if mode=='terminal':r['status']='DIAGNOSTIC_COLLECTED_NOT_QUALIFICATION'
    if mode=='unknown':r['status']='UNKNOWN'
    if mode=='extra':(a/'accepted-probes.json').write_text('{}')
    if mode=='source':(a/'empty.cpp').write_text('changed')
    (a/'command-trace.jsonl').write_text(''.join(json.dumps(t)+'\n' for t in traces))
    (a/'diagnostic-report.json').write_text(json.dumps(r))
    with self.assertRaises(ValueError):self.e.verify_report(dict(contract=p.contract()),a)


if __name__=='__main__':unittest.main()
