import copy,importlib.util,json,os,re
from pathlib import Path
import subprocess,tempfile,unittest
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2]
def load(name):
 s=importlib.util.spec_from_file_location('backend_test_'+name,ROOT/'scripts/diagnostics/dawn_cpp_backend_v1'/f'{name}.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
b=load('backend');p=load('plan')

class BackendAliasTests(unittest.TestCase):
 def root(self):
  t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);r=Path(t.name).resolve();(r/'bin').mkdir();return r
 def test_regular_backend_and_leaf_alias_chain_are_pinned(self):
  r=self.root();payload=r/'bin/backend';payload.write_bytes(b'pinned');(r/'alias').symlink_to('bin/backend')
  a=b.alias_receipt(r/'alias',r);self.assertEqual(a['resolved'],str(payload));self.assertEqual(a['links'],[dict(path=str(r/'alias'),target='bin/backend')]);self.assertEqual(a['file']['bytes'],6)
 def test_parent_directory_alias_is_included(self):
  r=self.root();(r/'bin/tool').write_bytes(b'tool');(r/'tools').symlink_to('bin',target_is_directory=True)
  a=b.alias_receipt(r/'tools/tool',r);self.assertTrue(any(x['path']==str(r/'tools') for x in a['links']))
 def test_cycle_dangling_directory_and_escape_reject(self):
  r=self.root();(r/'a').symlink_to('b');(r/'b').symlink_to('a');(r/'missing').symlink_to('absent');(r/'outside').symlink_to('/bin/sh')
  for path in (r/'a',r/'missing',r/'bin',r/'outside'):
   with self.subTest(path=path),self.assertRaises((ValueError,OSError)):b.alias_receipt(path,r)
 def test_changed_link_and_payload_invalidate_the_receipt(self):
  r=self.root();(r/'bin/a').write_bytes(b'same');(r/'bin/b').write_bytes(b'same');(r/'alias').symlink_to('bin/a');receipt=b.alias_receipt(r/'alias',r)
  (r/'alias').unlink();(r/'alias').symlink_to('bin/b')
  with self.assertRaises(ValueError):b.verify_aliases({'role':receipt})
  payload_receipt=b.alias_receipt(r/'bin/b',r);(r/'bin/b').write_bytes(b'changed')
  with self.assertRaises(ValueError):b.verify_aliases({'role':payload_receipt})

class BackendDispatchTests(unittest.TestCase):
 def setup_catalog(self):
  t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);r=Path(t.name).resolve();(r/'bin').mkdir()
  files={name:r/'bin'/name for name in ('wrapper','otool-classic','compiler')}
  for name,path in files.items():path.write_bytes(name.encode())
  (r/'bin/otool').symlink_to('wrapper')
  aliases={role:b.alias_receipt(files[name],r) for role,name in [('wrapper','wrapper'),('backend','otool-classic'),('compiler','compiler')]}
  profiles=dict(inspector=sorted([str(files['wrapper']),str(files['otool-classic'])]),compiler=[str(files['compiler'])])
  catalog=dict(aliases=aliases,executionProfiles=profiles,conventionalAlias=b.alias_receipt(r/'bin/otool',r))
  return r,files,catalog
 def call(self,job,root,files,catalog,execute):
  with patch.object(b,'WRAPPER',files['wrapper']),patch.object(b,'BACKEND',files['otool-classic']),patch.object(b,'COMPILER',files['compiler']):
   return b.dispatch(job,root,catalog,1,None,{},execute=execute)
 def test_wrapper_and_backend_permitted_by_exact_inspector_profile(self):
  r,files,c=self.setup_catalog();job=dict(kind='native-inspector',argv=[str(files['wrapper']),'-L','subject']);calls=[]
  def execute(argv,**kwargs):
   calls.append(argv);profile=argv[2]
   self.assertIn('(deny process-exec)',profile)
   self.assertIn('(literal '+json.dumps(str(files['wrapper']))+')',profile)
   self.assertIn('(literal '+json.dumps(str(files['otool-classic']))+')',profile)
   self.assertNotIn('(allow process-exec (subpath',profile);return 'synthetic dispatch only'
  self.assertEqual(self.call(job,r,files,c,execute),'synthetic dispatch only');self.assertEqual(len(calls),1)
 def test_simulated_unexpected_descendant_is_not_in_generated_allowlist(self):
  r,files,c=self.setup_catalog();job=dict(kind='native-inspector',argv=[str(files['wrapper']),'-L','subject'])
  def execute(argv,**kwargs):
   # A host model of the SBPL literals, not a claim of native enforcement.
   paths=[json.loads(value) for value in re.findall(r'\(allow process-exec \(literal ("[^"\n]+")\)\)',argv[2])]
   self.assertEqual(set(paths),{str(files['wrapper']),str(files['otool-classic'])})
   if str(r/'bin/unexpected-helper') not in paths:raise PermissionError('synthetic unknown descendant denied')
   self.fail('unknown helper was admitted')
  with self.assertRaises(PermissionError):self.call(job,r,files,c,execute)
 def test_compiler_profile_never_admits_inspector_or_backend(self):
  r,files,c=self.setup_catalog();job=dict(kind='driver-version',argv=[str(files['compiler']),'--version'])
  def execute(argv,**kwargs):
   self.assertIn('(literal '+json.dumps(str(files['compiler']))+')',argv[2]);self.assertNotIn(str(files['otool-classic']),argv[2]);self.assertNotIn(str(files['wrapper']),argv[2]);return 'synthetic'
  self.call(job,r,files,c,execute)
 def test_unexpected_entrypoint_directory_or_helper_profile_rejects_before_execution(self):
  for change in ('entry','directory','extra-helper','unknown-role'):
   r,files,c=self.setup_catalog();calls=[];job=dict(kind='native-inspector',argv=[str(files['wrapper']),'-L','subject'])
   if change=='entry':job['argv'][0]='/unqualified/helper'
   elif change=='unknown-role':job=dict(kind='unqualified-role',argv=[str(files['compiler'])])
   else:c['executionProfiles']['inspector'].append(str(r/'bin') if change=='directory' else '/unqualified/helper')
   with self.subTest(change=change),self.assertRaises(ValueError):self.call(job,r,files,c,lambda *a,**k:calls.append(a))
   self.assertEqual(calls,[])
 def test_changed_payload_blocks_before_spawn(self):
  r,files,c=self.setup_catalog();files['otool-classic'].write_bytes(b'changed');calls=[];job=dict(kind='native-inspector',argv=[str(files['wrapper']),'-L','subject'])
  with self.assertRaises(ValueError):self.call(job,r,files,c,lambda *a,**k:calls.append(a))
  self.assertEqual(calls,[])
 def test_changed_payload_during_dispatch_invalidates_result(self):
  r,files,c=self.setup_catalog();job=dict(kind='native-inspector',argv=[str(files['wrapper']),'-L','subject'])
  def execute(*a,**k):files['otool-classic'].write_bytes(b'changed');return 'would otherwise pass'
  with self.assertRaises(ValueError):self.call(job,r,files,c,execute)
 def test_pin_time_reduces_native_budget_and_exact_boundary_rejects_success(self):
  r,files,c=self.setup_catalog();job=dict(kind='native-inspector',argv=[str(files['wrapper']),'-L','subject']);clock=[0];original=b.allowed_paths
  def admission(*args):
   paths=original(*args);clock[0]=0.4;return paths
  def execute(*args,**kwargs):
   self.assertAlmostEqual(kwargs['seconds'],0.6);clock[0]=0.9;return 'synthetic'
  with patch.object(b,'now',side_effect=lambda:clock[0]),patch.object(b,'allowed_paths',side_effect=admission):self.assertEqual(self.call(job,r,files,c,execute),'synthetic')
  clock[0]=0
  def late(*args,**kwargs):clock[0]=1;return 'too late'
  with patch.object(b,'now',side_effect=lambda:clock[0]),self.assertRaises(ValueError):self.call(job,r,files,c,late)
 def test_expired_pinning_prevents_native_dispatch(self):
  r,files,c=self.setup_catalog();job=dict(kind='native-inspector',argv=[str(files['wrapper']),'-L','subject']);clock=[0];calls=[];original=b.allowed_paths
  def admission(*args):
   paths=original(*args);clock[0]=1;return paths
  with patch.object(b,'now',side_effect=lambda:clock[0]),patch.object(b,'allowed_paths',side_effect=admission),self.assertRaises(ValueError):self.call(job,r,files,c,lambda *a,**k:calls.append(a))
  self.assertEqual(calls,[])
 def test_dispatch_cleanup_exception_propagates_without_success(self):
  r,files,c=self.setup_catalog();job=dict(kind='native-inspector',argv=[str(files['wrapper']),'-L','subject'])
  def execute(*a,**k):raise subprocess.TimeoutExpired('synthetic-cleanup',1)
  with self.assertRaises(subprocess.TimeoutExpired):self.call(job,r,files,c,execute)
 def test_31_commands_include_exact_backend_pair_with_unchanged_driver_queries(self):
  prior=json.loads((ROOT/'planning/fixtures/dawn-cpp-toolchain-v1/metadata-execution-freeze-r4.json').read_text());paths=sorted(prior['nativeFiles']+[str(p.BACKEND)])
  commands=p.commands('/owned/metadata-2',paths);inspect=[x for x in commands if x['kind']=='native-inspector']
  self.assertEqual(len(commands),31);self.assertEqual(len(inspect),16);self.assertEqual([x['option'] for x in inspect if x['subject']==str(p.BACKEND)],['-L','-l'])
  for x in commands:
   if x['kind']=='driver-jobs':self.assertIn('-###',x['argv'])
   if x['kind']=='include-search':self.assertIn('-E',x['argv'])
  self.assertEqual(len([x for x in commands if x['kind'] in ('driver-version','resource-dir','linker-path')]),3)

if __name__=='__main__':unittest.main()
