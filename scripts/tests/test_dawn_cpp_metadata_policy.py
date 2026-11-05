import importlib.util
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
def load(name):
 s=importlib.util.spec_from_file_location('cpp_'+name,ROOT/'scripts/diagnostics/dawn_cpp_toolchain_v1'/f'{name}.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
p=load('policy');plan=load('plan')

class NativeGraphPolicyTests(unittest.TestCase):
 def node(self,deps=(),rpaths=()):return dict(rawMachO=dict(dependencies=list(deps),rpaths=list(rpaths)))
 def test_root_specific_executable_paths(self):
  n={'/a/bin/tool':self.node(['@rpath/lib.dylib'],['@executable_path/../lib']),'/a/lib/lib.dylib':self.node(['/usr/lib/system'])}
  present=lambda x:str(Path(x)) in ['/a/bin/../lib/lib.dylib','/a/lib/lib.dylib']
  g=p.native_graph(n,'/a/bin/tool',{'/usr/lib/system'},present,lambda x:str(Path(x).resolve()))
  self.assertEqual(g['nodes']['/a/bin/tool'][0]['path'],'/a/lib/lib.dylib')
 def test_unknown_missing_ambiguous_and_system_suppliers_block(self):
  for nodes,present,system in [({'/tool':self.node(['/unknown'])},lambda x:False,set()),({'/tool':self.node(['/usr/lib/missing'])},lambda x:False,set()),({'/tool':self.node(['@rpath/x'],['/a','/b'])},lambda x:True,set()),({'/tool':self.node(['/other'])},lambda x:True,set())]:
   with self.subTest(nodes=nodes),self.assertRaises(ValueError):p.native_graph(nodes,'/tool',system,present,lambda x:x)
 def test_dependency_cycles_are_bounded(self):
  n={'/a':self.node(['/b']),'/b':self.node(['/a'])}
  self.assertEqual(len(p.native_graph(n,'/a',set(),lambda x:True,lambda x:x)['nodes']),2)
 def test_loader_path_is_not_another_executable_path(self):
  n={'/root/exe':self.node(['/libs/one']),'/libs/one':self.node(['@loader_path/two']),'/libs/two':self.node()}
  self.assertIn('/libs/two',p.native_graph(n,'/root/exe',set(),lambda x:x in n,lambda x:x)['nodes'])

class MetadataOutputTests(unittest.TestCase):
 def test_include_search_order_and_framework(self):
  text='#include <...> search starts here:\n /a\n /b (framework directory)\nEnd of search list.\n'
  expected=[dict(path='/a',framework=False),dict(path='/b',framework=True)]
  self.assertEqual(p.include_search(text,expected),expected)
  for changed in [list(reversed(expected)),expected[:1]]:
   with self.assertRaises(ValueError):p.include_search(text,changed)
 def test_include_missing_duplicate_and_diagnostic_fail(self):
  for text in ['', '#include <...> search starts here:\nEnd of search list.\nEnd of search list.', '#include <...> search starts here:\nEnd of search list.\nwarning: unexpected']:
   with self.subTest(text=text),self.assertRaises(ValueError):p.include_search(text,[])
 def config(self):return dict(source='/owned/empty.cpp',output='/owned/test.not-created',standard='c++20',includes=[dict(path='/inc',framework=False)])
 def jobs(self):
  return 'Apple clang version 21.0.0 (synthetic)\nTarget: arm64-apple-macosx26.5.0\nThread model: posix\nInstalledDir: /test/bin\n"/test/bin/clang" "-cc1" "-emit-obj" "-fcxx-exceptions" "-fexceptions" "-pedantic-errors" "-O0" "-triple" "arm64-apple-macosx26.5.0" "-isysroot" "/sdk" "-resource-dir" "/res" "-I" "/inc" "-main-file-name" "empty.cpp" "-std=c++20" "-x" "c++" "-o" "/owned/empty-abcd.o" "/owned/empty.cpp"\n"/test/bin/ld" "-syslibroot" "/sdk" "-arch" "arm64" "-lc++" "-lSystem" "-lto_library" "/test/lib/libLTO.dylib" "-platform_version" "macos" "26.5" "26.5" "-o" "/owned/test.not-created" "/owned/empty-abcd.o"\n'
 def validate(self,text):return p.driver_jobs(text,'/test/bin/clang','/test/bin/ld','/sdk','/res',self.config())
 def test_exact_driver_helpers_target_and_runtime(self):self.assertEqual(len(self.validate(self.jobs())),2)
 def test_substituted_helpers_sysroot_response_files_and_jobs_rejected(self):
  original=self.jobs()
  for text in [original.replace('/test/bin/ld','/other/ld'),original.replace('/sdk','/other/sdk'),original.replace('/res','/other/res'),original+'"/unknown"\n',original.replace('"-cc1"','"@args"'),original.replace('26.5.0','26.6.0'),original.replace('"-lc++"','"-lstdc++"'),original+'warning: unknown\n']:
   with self.subTest(text=text),self.assertRaises(ValueError):self.validate(text)
 def test_unfrozen_libraries_plugins_includes_and_archives_rejected(self):
  for inserted in ['"-load" "/unfrozen/plugin.dylib"','"-internal-isystem" "/unfrozen/include"','"-I/unfrozen/include"','"-fmodule-file=/unfrozen/a.pcm"','"-isysroot" "/sdk"']:
   text=self.jobs().replace('"-cc1"','"-cc1" '+inserted)
   with self.subTest(inserted=inserted),self.assertRaises(ValueError):self.validate(text)
  for inserted in ['"-lUNFROZEN"','"-framework" "Unfrozen"','"-L" "/unfrozen/lib"','"/unfrozen/extra.o"','"-rpath" "/unfrozen"','"-lc++"']:
   text=self.jobs().replace('"-lSystem"','"-lSystem" '+inserted)
   with self.subTest(inserted=inserted),self.assertRaises(ValueError):self.validate(text)
 def test_predeclared_cpp_exception_defaults_required_without_duplicates(self):
  self.assertEqual(len(self.validate(self.jobs())),2)
  for text in [self.jobs().replace('"-fcxx-exceptions" ',''),self.jobs().replace('"-fexceptions" ',''),self.jobs().replace('"-fexceptions"','"-fexceptions" "-fexceptions"'),self.jobs().replace('"-fcxx-exceptions"','"-fno-exceptions"')]:
   with self.subTest(text=text[:140]),self.assertRaises(ValueError):self.validate(text)
 def test_dump_paths_are_owned_and_bound_to_output_profile(self):
  for value in ['/unowned/out','/owned/unrelated','/owned/../outside/']:
   bad=self.jobs().replace('"-cc1"','"-cc1" "-dumpdir" "'+value+'"')
   with self.subTest(value=value),self.assertRaises(ValueError):self.validate(bad)
  for value in ['/owned/','/owned/test.not-created-']:
   self.assertEqual(len(self.validate(self.jobs().replace('"-cc1"','"-cc1" "-dumpdir" "'+value+'"'))),2)
 def test_opposing_math_options_rejected_in_both_orders(self):
  for pair in [('"-fmath-errno"','"-fno-math-errno"'),('"-fno-math-errno"','"-fmath-errno"')]:
   with self.subTest(pair=pair),self.assertRaises(ValueError):self.validate(self.jobs().replace('"-cc1"','"-cc1" '+' '.join(pair)))
 def test_version_fields_and_all_diagnostics_rejected(self):
  good='Apple clang version 21.0.0 (synthetic)\nTarget: arm64-apple-darwin25.6.0\nThread model: posix\nInstalledDir: /test/bin\n'
  self.assertEqual(p.compiler_identity(good,'/test/bin',r'arm64-apple-darwin[0-9]+(?:\.[0-9]+){2}')['threadModel'],'posix')
  for bad in [good+'warning: bad\n',good+'error: bad\n',good.replace('Thread model: posix','garbage Thread model: posix'),good.replace('InstalledDir: /test/bin','InstalledDir: /unknown'),good+good]:
   with self.subTest(bad=bad),self.assertRaises(ValueError):p.compiler_identity(bad,'/test/bin',r'arm64-apple-darwin[0-9]+(?:\.[0-9]+){2}')
 def test_exact_frozen_command_counts_and_no_compilation(self):
  jobs=plan.commands('/owned/metadata-1',['/native/a','/native/b'])
  self.assertEqual(len(jobs),19)
  self.assertEqual(sum(x['kind']=='driver-jobs' for x in jobs),6)
  self.assertEqual(sum(x['kind']=='include-search' for x in jobs),6)
  for job in jobs:
   if job['kind']=='driver-jobs':self.assertIn('-###',job['argv'])
   if job['kind']=='include-search':self.assertIn('-E',job['argv']);self.assertNotIn('-c',job['argv'])
  for _,_,std in plan.PROFILES:
   args=plan.flags('candidate',std);self.assertIn('-pedantic-errors',args);self.assertIn('-std='+std,args)

if __name__=='__main__':unittest.main()
