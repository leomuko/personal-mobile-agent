"""Small host regressions for the model-free mask diagnostic."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2]
def module(name):
 spec=importlib.util.spec_from_file_location(name,ROOT/'scripts/diagnostics/mask_replay'/f'{name}.py')
 m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
prepare=module('prepare');run=module('run')
sys.path.insert(0,str(ROOT/'scripts/diagnostics/mask_replay'))
progress=module('grammar_progress')
class MaskReplayTests(unittest.TestCase):
 def test_exact_patch_accounts_for_miscounted_upstream_hunk(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'a';p.write_text('before\nold\nafter\n')
   prepare.apply_exact_patch(Path(d),b'--- a\n+++ a\n@@ -1,2 +1,2 @@\n before\n-old\n+new\n after\n')
   self.assertEqual(p.read_text(),'before\nnew\nafter\n')
 def test_patch_rejects_ambiguous_context(self):
  with tempfile.TemporaryDirectory() as d:
   (Path(d)/'a').write_text('old\nold\n')
   with self.assertRaises(ValueError):prepare.apply_exact_patch(Path(d),b'--- a\n+++ a\n@@ -1 +1 @@\n-old\n+new\n')
 def test_patch_rejects_path_traversal(self):
  with self.assertRaises(ValueError):prepare.apply_exact_patch(Path('/tmp'),b'--- ../a\n+++ ../a\n@@ -1 +1 @@\n-old\n+new\n')
 def test_cargo_reader_retains_version_and_checksum(self):
  self.assertEqual(run.parse_lock('[[package]]\nname = "x"\nversion = "1.2"\nsource = "registry+index"\nchecksum = "abc"\n'),{('x','1.2'):'abc'})
 def test_cargo_reader_rejects_duplicate_identity(self):
  b='[[package]]\nname = "x"\nversion = "1.2"\nsource = "registry+index"\nchecksum = "abc"\n'
  with self.assertRaises(ValueError):run.parse_lock(b+b)
 def test_physical_device_rejected_before_access(self):
  with patch.object(run,'verify_workspace') as verify,patch.object(run.subprocess,'check_output') as adb:
   with self.assertRaises(ValueError):run.execute(Path('missing'),Path('missing'),'R5CX22V8D6B')
   verify.assert_not_called();adb.assert_not_called()
 def test_broker_failure_preserves_attempt(self):
  with tempfile.TemporaryDirectory() as d:
   folder=Path(d)/'attempt'
   with self.assertRaises(ValueError):run.run_probe([sys.executable,'-c','print(\'{"event":"error","message":"synthetic failure"}\')'],{},None,folder)
   self.assertIn('synthetic failure',(folder/'failed.json').read_text())
   self.assertFalse((folder/'result.json').exists())
 def test_broker_timeout_kills_only_owned_process(self):
  with tempfile.TemporaryDirectory() as d:
   folder=Path(d)/'attempt'
   with self.assertRaises(TimeoutError):run.run_probe([sys.executable,'-c','import time;time.sleep(2)'],{},None,folder,timeout=.05)
   self.assertTrue((folder/'failed.json').exists())
 def test_broker_exact_callback_bytes(self):
  class Tokenizer:
   def encode(self,data):
    self.asserted=data;return [107]
  tokenizer=Tokenizer()
  code='import json,sys;json.loads(input());print(json.dumps({"event":"tokenize","bytes":[255,10]}),flush=True);v=json.loads(input());print(json.dumps({"event":"result","status":"prefix-rejected","observed":v["tokens"]}))'
  with tempfile.TemporaryDirectory() as d:
   r=run.run_probe([sys.executable,'-c',code],{},tokenizer,Path(d)/'attempt')
   self.assertEqual(tokenizer.asserted,b'\xff\n');self.assertEqual(r['result']['observed'],[107]);self.assertEqual(r['tokenizerCalls'],1)
class BrokerBoundaryRegressions(unittest.TestCase):
 def test_child_not_reading_reply_cannot_bypass_deadline(self):
  class Tokenizer:
   def encode(self,data):return [238]*len(data)
  code='import json,time;input();print(json.dumps({"event":"tokenize","bytes":[0]*100000}),flush=True);time.sleep(5)'
  import time
  with tempfile.TemporaryDirectory() as d:
   start=time.monotonic()
   with self.assertRaises(TimeoutError):run.run_probe([sys.executable,'-c',code],{},Tokenizer(),Path(d)/'attempt',timeout=.2)
   self.assertLess(time.monotonic()-start,2)
   self.assertIn('write timeout',(Path(d)/'attempt/failed.json').read_text())
 def test_callback_failure_retains_failed_attempt(self):
  class Tokenizer:
   def encode(self,data):raise RuntimeError('synthetic tokenizer failure')
  code='import json,time;input();print(json.dumps({"event":"tokenize","bytes":[10]}),flush=True);time.sleep(5)'
  with tempfile.TemporaryDirectory() as d:
   with self.assertRaisesRegex(RuntimeError,'tokenizer failure'):run.run_probe([sys.executable,'-c',code],{},Tokenizer(),Path(d)/'attempt')
   self.assertTrue((Path(d)/'attempt/failed.json').exists());self.assertFalse((Path(d)/'attempt/result.json').exists())
 def test_invalid_callback_values_reject_before_tokenization(self):
  code='import json;input();print(json.dumps({"event":"tokenize","bytes":[True]}),flush=True)'
  with tempfile.TemporaryDirectory() as d:
   with self.assertRaisesRegex(ValueError,'invalid callback bytes'):run.run_probe([sys.executable,'-c',code],{},None,Path(d)/'attempt')

class GrammarProgressIdentityTests(unittest.TestCase):
 def test_frozen_candidate_changes_only_whitespace_policy(self):
  self.assertEqual(progress.verify_fixtures()['candidate'],{'x-guidance':{'whitespace_flexible':False}})
 def test_schema_tampering_rejects_before_native_work(self):
  import shutil
  with tempfile.TemporaryDirectory() as d:
   target=Path(d)/'fixtures';shutil.copytree(progress.FIXTURES,target)
   p=target/'keyword-sentinel.no-whitespace.schema.json';p.write_text(p.read_text()+' ')
   with self.assertRaisesRegex(ValueError,'candidate input changed'):progress.verify_fixtures(target)
 def test_lock_tampering_cannot_authorize_changed_schema(self):
  import shutil
  with tempfile.TemporaryDirectory() as d:
   target=Path(d)/'fixtures';shutil.copytree(progress.FIXTURES,target)
   p=target/'preparation-lock.json';p.write_text(p.read_text()+' ')
   with self.assertRaisesRegex(ValueError,'lock identity changed'):progress.verify_fixtures(target)
 def test_failed_identity_gate_does_not_start_subprocess(self):
  with patch.object(progress,'verify_fixtures',side_effect=ValueError('tampered')),patch.object(progress.broker.subprocess,'Popen') as native:
   with self.assertRaises(ValueError):progress.campaign(Path('missing'),Path('missing'),Path('missing'))
   native.assert_not_called()

class SignalOwnershipRegression(unittest.TestCase):
 def test_sigterm_cleans_up_owned_native_child_and_preserves_failure(self):
  import os,signal,subprocess,time
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);pidfile=root/'child.pid';attempt=root/'attempt'
   native='import os,time;input();open('+repr(str(pidfile))+',"w").write(str(os.getpid()));time.sleep(5)'
   outer='import sys;sys.path.insert(0,'+repr(str(ROOT/'scripts/diagnostics/mask_replay'))+');import run;from pathlib import Path;run.run_probe([sys.executable,"-c",'+repr(native)+'],{},None,Path('+repr(str(attempt))+'))'
   proc=subprocess.Popen([sys.executable,'-c',outer],stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True)
   try:
    deadline=time.monotonic()+3
    while not pidfile.exists() and time.monotonic()<deadline:time.sleep(.01)
    self.assertTrue(pidfile.exists())
    pid=int(pidfile.read_text());proc.send_signal(signal.SIGTERM);_,error=proc.communicate(timeout=3)
    self.assertNotEqual(proc.returncode,0);self.assertIn(b'DiagnosticInterrupted',error)
    with self.assertRaises(ProcessLookupError):os.kill(pid,0)
    self.assertTrue((attempt/'failed.json').exists());self.assertFalse((attempt/'result.json').exists())
   finally:
    if proc.poll() is None:proc.kill()
    proc.communicate()

class OwnedReapingRegressions(unittest.TestCase):
 def test_denied_signal_after_exit_requires_confirmed_reap(self):
  from unittest.mock import Mock
  process=Mock();process.poll.return_value=None;process.pid=123;process.wait.return_value=2
  with patch.object(run.os,'killpg',side_effect=PermissionError('synthetic exit race')):run.reap_owned(process)
  process.wait.assert_any_call(timeout=.25)
 def test_denied_signal_to_live_child_is_not_claimed_clean(self):
  from unittest.mock import Mock
  import subprocess
  process=Mock();process.poll.return_value=None;process.pid=123;process.wait.side_effect=subprocess.TimeoutExpired('owned',.25)
  with patch.object(run.os,'killpg',side_effect=PermissionError('synthetic deny')):
   with self.assertRaisesRegex(RuntimeError,'cleanup uncertain'):run.reap_owned(process)

class PreparedMetadataIdentityTests(unittest.TestCase):
 def test_exact_eos_and_stop_order_are_admitted(self):
  progress.verify_prepared_metadata({'eos':1,'stopTokenIds':[[1],[50],[106]],'vocabEntries':262144,'nativeGemmaExecution':False},{'eos':1})
 def test_metadata_tampering_blocks_before_subprocess(self):
  from unittest.mock import Mock
  base={'eos':1,'stopTokenIds':[[1],[50],[106]],'vocabEntries':262144,'nativeGemmaExecution':False}
  for change in [{'eos':106},{'eos':True},{'eos':1.0},{'stopTokenIds':[[106],[50],[1]]},{'stopTokenIds':[[True],[50],[106]]},{'stopTokenIds':[[1.0],[50],[106]]},{'vocabEntries':3},{'vocabEntries':262144.0},{'nativeGemmaExecution':True}]:
   value={**base,**change}
   with patch.object(progress,'verify_fixtures',return_value={'eos':1}),patch.object(progress.broker,'verify_workspace',return_value=value),patch.object(progress.broker.subprocess,'Popen') as native:
    with self.assertRaises(ValueError):progress.campaign(Path('unused'),Path('unused'),Path('unused'))
    native.assert_not_called()

class SetupInterruptionRegressions(unittest.TestCase):
 def test_selector_setup_interrupt_reaps_created_child_and_records_failure(self):
  import subprocess
  original=subprocess.Popen;children=[]
  def spawn(*args,**kwargs):
   child=original(*args,**kwargs);children.append(child);return child
  with tempfile.TemporaryDirectory() as d:
   attempt=Path(d)/'attempt'
   with patch.object(run.subprocess,'Popen',side_effect=spawn),patch.object(run.selectors,'DefaultSelector',side_effect=KeyboardInterrupt('synthetic setup interruption')):
    with self.assertRaises(KeyboardInterrupt):run.run_probe([sys.executable,'-c','import time;time.sleep(5)'],{},None,attempt)
   self.assertEqual(len(children),1);self.assertIsNotNone(children[0].poll())
   self.assertEqual(json.loads((attempt/'failed.json').read_text())['cleanup'],'confirmed')
   self.assertFalse((attempt/'result.json').exists())
 def test_signal_between_spawn_and_handle_assignment_is_deferred_then_reaped(self):
  import subprocess,os,signal
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);pidfile=root/'child.pid';attempt=root/'attempt'
   code='''import sys,os,signal
from pathlib import Path
sys.path.insert(0,sys.argv[1]);import run
original=run.subprocess.Popen
def spawn(*args,**kwargs):
 child=original(*args,**kwargs)
 Path(sys.argv[2]).write_text(str(child.pid))
 os.kill(os.getpid(),signal.SIGTERM)
 return child
run.subprocess.Popen=spawn
run.run_probe([sys.executable,"-c","import time;time.sleep(5)"],{},None,Path(sys.argv[3]))
'''
   outer=subprocess.Popen([sys.executable,'-c',code,str(ROOT/'scripts/diagnostics/mask_replay'),str(pidfile),str(attempt)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True)
   try:
    _,error=outer.communicate(timeout=3)
    self.assertIn(b'DiagnosticInterrupted',error);self.assertTrue(pidfile.exists())
    pid=int(pidfile.read_text())
    with self.assertRaises(ProcessLookupError):os.kill(pid,0)
    self.assertEqual(json.loads((attempt/'failed.json').read_text())['cleanup'],'confirmed')
   finally:
    if outer.poll() is None:outer.kill()
    outer.communicate()
    if pidfile.exists():
     try:os.killpg(int(pidfile.read_text()),signal.SIGKILL)
     except ProcessLookupError:pass

if __name__=='__main__':unittest.main()
