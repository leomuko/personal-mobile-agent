"""Exercise actual admission/schema guards; no main/owner/native operation runs."""
import copy,hashlib,importlib.util,json
from pathlib import Path
import unittest
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2]
s=importlib.util.spec_from_file_location('backend_admission_regression',ROOT/'scripts/diagnostics/dawn_cpp_backend_v1/qualify.py');q=importlib.util.module_from_spec(s);s.loader.exec_module(q)
F=ROOT/'planning/fixtures/dawn-cpp-backend-v1'
class BackendAdmissionRegressionTests(unittest.TestCase):
 def fixture(self):
  f=json.loads((F/'metadata-execution-freeze-r1.json').read_text())
  # Unit-only review data: never published as an attestation or executable freeze.
  review=dict(verdict='PASS',scope=q.SCOPE,freezeSha256='synthetic-freeze')
  return f,review
 def run_admission(self,f,review,check_inputs):
  def read(path,*args):return (review,'synthetic-review') if str(path)=='synthetic-review.json' else (f,'synthetic-freeze')
  with patch.object(q.base,'read_json_bound',side_effect=read),patch.object(q,'check_inputs',side_effect=check_inputs):
   return q.admission('synthetic-freeze.json','synthetic-freeze','synthetic-review.json','synthetic-review')
 def test_reviewed_r1_omission_rejects_before_input_checks_or_owner(self):
  f,review=self.fixture();checks=[]
  with patch.object(q.owner,'run_owned',side_effect=AssertionError('owner must not start')) as owner:
   with self.assertRaisesRegex(ValueError,'unbound metadata code dependency'):self.run_admission(f,review,lambda *a:checks.append(a))
   self.assertEqual(checks,[]);self.assertEqual(owner.call_count,0)
 def test_direct_observed_helper_binding_completes_the_required_input_set(self):
  f,review=self.fixture();p=ROOT/'planning/fixtures/dawn-cpp-toolchain-v1/observed-inspector-helper.json'
  self.assertNotIn(str(p),{row['path'] for row in f['inputs']});f['inputs'].append(q.c.file_row(p));checks=[]
  with patch.object(q.owner,'run_owned',side_effect=AssertionError('owner must not start')) as owner:
   admitted=self.run_admission(f,review,lambda *a:checks.append(a))
  self.assertIs(admitted,f);self.assertEqual(len(checks),1);self.assertEqual(owner.call_count,0)

if __name__=='__main__':unittest.main()
