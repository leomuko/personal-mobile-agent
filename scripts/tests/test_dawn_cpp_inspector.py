import importlib.util,json
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
s=importlib.util.spec_from_file_location('cpp_strict_inspector',ROOT/'scripts/diagnostics/dawn_cpp_toolchain_v1/qualify.py');q=importlib.util.module_from_spec(s);s.loader.exec_module(q)
class InspectorAdmissionTests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.oracle=json.loads((ROOT/'planning/fixtures/dawn-cpp-toolchain-v1/retained-inspector-oracle.json').read_text())
 def validate(self,text):return q.inspector.validate(text,self.oracle['source'],self.oracle['structure'])
 def test_preserved_real_inspector_transcript_is_structurally_complete(self):self.assertTrue(self.validate(self.oracle['text']))
 def test_missing_command_kind_size_and_body_are_rejected(self):
  original=self.oracle['text']
  for text in [original.replace('      cmd LC_SEGMENT_64\n','',1),original.replace('  cmdsize 72\n','',1),original.replace('   vmaddr 0x0000000000000000\n','',1),original.replace('Load command 0','Load command 1',1),original+'unexpected field 1\n']:
   with self.subTest(text=text[:180]),self.assertRaises(ValueError):self.validate(text)
 def test_changed_duplicate_structural_fields_rejected(self):
  for text in [self.oracle['text'].replace('cmdsize 72','cmdsize 80',1),self.oracle['text'].replace('cmdsize 72','cmdsize 72\n cmdsize 72',1),self.oracle['text'].replace('segname __PAGEZERO','segname __UNFROZEN',1)]:
   with self.subTest(text=text[:180]),self.assertRaises(ValueError):self.validate(text)
 def test_warnings_and_errors_block_every_output_kind(self):
  nodes={self.oracle['source']:dict(rawMachO={},inspectorStructure=self.oracle['structure'])}
  for suffix in ['\nwarning: injected\n','\nerror: injected\n']:
   job=dict(kind='native-inspector',option='-l',subject=self.oracle['source'])
   with self.subTest(suffix=suffix),self.assertRaises(ValueError):q.validate_output(job,self.oracle['text']+suffix,nodes)
  version='Apple clang version 21.0.0 (synthetic)\nTarget: arm64-apple-darwin25.6.0\nThread model: posix\nInstalledDir: '+str(q.plan.BIN)+'\n'
  for suffix in ('warning: injected\n','error: injected\n'):
   with self.subTest(suffix=suffix),self.assertRaises(ValueError):q.validate_output(dict(kind='driver-version'),version+suffix,{})
 def test_omitted_body_cannot_be_replaced_by_regex_hits(self):
  incomplete=self.oracle['source']+':\n'+'\n'.join('Load command '+str(i) for i in range(len(self.oracle['structure'])))+'\n'
  with self.assertRaises(ValueError):self.validate(incomplete)
if __name__=='__main__':unittest.main()
