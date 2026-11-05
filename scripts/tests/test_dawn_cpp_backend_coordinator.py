import contextlib
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
s=importlib.util.spec_from_file_location('cpp_metadata_coordinator',ROOT/'scripts/diagnostics/dawn_cpp_backend_v1/qualify.py');q=importlib.util.module_from_spec(s);s.loader.exec_module(q)

class BackendMetadataCoordinatorTests(unittest.TestCase):
 def exercise(self,mode='success'):
  tmp=tempfile.TemporaryDirectory();self.addCleanup(tmp.cleanup);root=Path(tmp.name);attempt=root/'metadata-2';clock=[0];writes=[0];links=[0];calls=[];admissions=[0]
  if mode=='prior-uncertain':
   prior=root/'metadata-1';prior.mkdir();(prior/'owner-started.json').write_text('{}');(prior/'outcome.json').write_text('{"cleanupConfirmed":false}')
  f=dict(python='mock-only',attemptParent=str(root),attemptName='metadata-2',environment={},commands=[])
  def admit(*args):
   admissions[0]+=1
   if mode=='review-rejected' or (mode=='late-revalidate' and admissions[0]>1):raise ValueError('review/input changed')
   return f
  def run(*args):
   calls.append(args);clock[0]=20
   if mode=='cleanup-error':raise subprocess.TimeoutExpired('mock-owner',15)
   q.c.write(attempt/'metadata-report.json',dict(status='TOOLCHAIN_METADATA_PASSED_CPP_NOT_EXECUTED',commands=[]),65536)
   return dict(classification='ANALYSIS_COMMAND_SUCCEEDED',cleanupConfirmed=mode!='uncertain',elapsedSeconds=20)
  original=q.c.write;link=q.os.link
  def write(path,value,maximum):
   original(path,value,maximum);writes[0]+=1
   if mode=='late-pending' and Path(path).name=='accepted-metadata.pending.json':clock[0]=301
  def publish(source,target):
   link(source,target);links[0]+=1
   if mode=='interrupt-link':raise KeyboardInterrupt()
   if mode=='late-link':clock[0]=301
  argv=['qualify','--freeze',str(root/'f.json'),'--sha256','synthetic','--review',str(root/'r.json'),'--review-sha256','synthetic','--attempt',str(attempt)]
  with patch.object(q,'ROOT',root),patch.object(q,'now',side_effect=lambda:clock[0]),patch.object(q.base.base,'now',side_effect=lambda:clock[0]),patch.object(q,'admission',side_effect=admit),patch.object(q.owner,'run_owned',side_effect=run),patch.object(q.shutil,'disk_usage',return_value=type('D',(),{'free':4*1024**3})()),patch.object(q.c,'write',side_effect=write),patch.object(q.os,'link',side_effect=publish),patch.object(sys,'argv',argv),contextlib.redirect_stderr(io.StringIO()):
   result=q.main()
  return result,attempt,calls
 def test_success_is_metadata_only_subset(self):
  code,p,calls=self.exercise();self.assertEqual(code,2);v=json.loads((p/'accepted-metadata.json').read_text());self.assertFalse(v['compilerMatrixExecuted']);self.assertTrue(v['cleanupConfirmed']);self.assertEqual(len(calls),1)
 def test_review_rejection_prevents_owner(self):
  code,p,calls=self.exercise('review-rejected');self.assertEqual(code,1);self.assertFalse(p.exists());self.assertEqual(calls,[])
 def test_cleanup_exception_retains_uncertain_terminal_without_acceptance(self):
  code,p,_=self.exercise('cleanup-error');self.assertEqual(code,1);v=json.loads((p/'outcome.json').read_text());self.assertEqual(v['cleanupStatus'],'UNCERTAIN');self.assertFalse((p/'accepted-metadata.json').exists())
 def test_uncertain_cleanup_and_revoked_review_block_acceptance(self):
  for mode in ['uncertain','late-revalidate']:
   with self.subTest(mode=mode):
    code,p,_=self.exercise(mode);self.assertEqual(code,1);self.assertFalse((p/'accepted-metadata.json').exists())
 def test_late_or_interrupted_publication_revokes_owned_links(self):
  for mode in ['late-pending','late-link','interrupt-link']:
   with self.subTest(mode=mode):
    code,p,_=self.exercise(mode);self.assertEqual(code,1);self.assertFalse((p/'accepted-metadata.json').exists());self.assertTrue((p/'outcome.json').exists())
 def test_worker_stops_after_dispatch_failure_and_records_remaining_queries(self):
  with tempfile.TemporaryDirectory() as t:
   attempt=Path(t);f=dict(backendCatalog='synthetic-only',commands=[dict(id='inspect-1',kind='native-inspector'),dict(id='inspect-2',kind='native-inspector')])
   with patch.object(q,'native_rows',return_value=({},[])),patch.object(q,'data',return_value={}),patch.object(q.backend,'dispatch',side_effect=PermissionError('unexpected helper')) as dispatch,patch.object(q,'remaining',return_value=30):
    with self.assertRaises(PermissionError):q.worker(f,attempt,100)
   report=json.loads((attempt/'metadata-report.json').read_text());self.assertEqual(report['status'],'FAILED');self.assertEqual(report['commands'][0]['status'],'FAILED');self.assertEqual(report['notRunCommands'],['inspect-2']);self.assertEqual(dispatch.call_count,1)
 def test_uncertain_prior_owner_blocks_new_attempt(self):
  code,p,calls=self.exercise('prior-uncertain');self.assertEqual(code,1);self.assertFalse(p.exists());self.assertEqual(calls,[])

if __name__=='__main__':unittest.main()
