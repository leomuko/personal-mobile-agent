"""Local data and synthetic subprocess checks. No candidate/SDK execution."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
s=importlib.util.spec_from_file_location('dawn_catalog_test',ROOT/'scripts/diagnostics/dawn_python_patch_v1/catalog.py')
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)


class DawnCatalogTests(unittest.TestCase):
    def setUp(self):
        t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);self.root=Path(t.name)
    def test_tree_binds_files_modes_and_raw_links_without_following(self):
        p=self.root/'data';p.write_bytes(b'content');(self.root/'alias').symlink_to('data')
        (self.root/'cache').symlink_to('/no/such/cache')
        result=m.tree(self.root,10,100)
        self.assertEqual(result['regularBytes'],7)
        self.assertEqual(result['files']['data']['sha256'],hashlib.sha256(b'content').hexdigest())
        self.assertEqual(result['files']['cache']['target'],'/no/such/cache')
        self.assertEqual(result['files']['alias']['kind'],'symlink')
    def test_tree_entry_byte_and_special_file_limits(self):
        (self.root/'data').write_bytes(b'content')
        with self.assertRaisesRegex(ValueError,'byte'):m.tree(self.root,10,1)
        with self.assertRaisesRegex(ValueError,'entry'):m.tree(self.root,0,100)
        os.mkfifo(self.root/'fifo')
        with self.assertRaisesRegex(ValueError,'special'):m.tree(self.root,10,100)
    def test_unreadable_subtree_is_not_omitted(self):
        child=self.root/'hidden';child.mkdir();(child/'needed').write_bytes(b'must be inventoried')
        original=m.os.scandir
        def denied(path):
            if Path(path)==child:raise PermissionError('injected unreadable subtree')
            return original(path)
        with patch.object(m.os,'scandir',side_effect=denied):
            with self.assertRaises(PermissionError):m.tree(self.root,10,100)
    def test_bounded_metadata_output_and_trace(self):
        trace=[]
        output=m.metadata_command([sys.executable,'-c','print("safe")'],trace=lambda *args:trace.append(args))
        self.assertEqual(output,'safe\n');self.assertEqual(trace[0][2],0)
        with self.assertRaisesRegex(ValueError,'output ceiling'):
            m.metadata_command([sys.executable,'-c','print("x"*10000)'],maximum=32,trace=lambda *args:trace.append(args))
        self.assertEqual(len(trace[-1][1]),32)
    def test_metadata_timeout_kills_and_records_child(self):
        trace=[]
        with self.assertRaisesRegex(ValueError,'deadline'):
            m.metadata_command([sys.executable,'-c','import time; time.sleep(5)'],seconds=.05,trace=lambda *args:trace.append(args))
        self.assertEqual(trace[0][2],-9)
    def test_metadata_nonzero_failure_retains_output(self):
        trace=[]
        with self.assertRaisesRegex(ValueError,'failed'):
            m.metadata_command([sys.executable,'-c','print("failure witness");raise SystemExit(2)'],trace=lambda *args:trace.append(args))
        self.assertEqual(trace[0][1],b'failure witness\n');self.assertEqual(trace[0][2],2)
    def test_zero_exit_after_deadline_is_rejected(self):
        stream=types.SimpleNamespace(fileno=lambda:99,close=lambda:None)
        process=types.SimpleNamespace(stdout=stream,returncode=0,poll=lambda:0,wait=lambda **kw:0)
        active=[True]
        selector=types.SimpleNamespace(register=lambda *a:None,get_map=lambda:{1:1} if active[0] else {},
            select=lambda *a:[(types.SimpleNamespace(fileobj=stream),1)],unregister=lambda *a:active.__setitem__(0,False),close=lambda:None)
        trace=[]
        with patch.object(m.subprocess,'Popen',return_value=process),patch.object(m.selectors,'DefaultSelector',return_value=selector), \
             patch.object(m.os,'set_blocking'),patch.object(m.os,'read',return_value=b''), \
             patch.object(m.time,'monotonic',side_effect=[0,0,0,16,16]):
            with self.assertRaisesRegex(ValueError,'deadline'):
                m.metadata_command(['synthetic-only'],trace=lambda *a:trace.append(a))
        self.assertEqual(trace[0][2],0);self.assertEqual(trace[0][3],16)
    def test_json_ceiling_is_enforced(self):
        p=self.root/'result'
        with self.assertRaisesRegex(ValueError,'serialization'):m.write(p,{'a':'x'*100},10)
        self.assertLessEqual(p.stat().st_size,10)
    def graph(self,dependencies):
        executable=self.root/'python'
        self.object(executable,dependencies,['@executable_path/lib'])
        def inspect(args):
            path=Path(args[-1]);raw=m.macho.inspect(path);out=str(path)+':\n'
            if '-l' in args:
                for i,name in enumerate(raw['loadNames']):out+='Load command '+str(i)+'\n      name '+name+' (offset 24)\n'
                for j,rp in enumerate(raw['rpaths'],len(raw['loadNames'])):out+='Load command '+str(j)+'\n      path '+rp+' (offset 12)\n'
                return out
            return out+''.join('\t'+d+' (compatibility version 1.0.0, current version 1.0.0)\n' for d in raw['libraryNames'])
        return executable,inspect
    def object(self,path,dependencies,rpaths):
        commands=[]
        for kind,values,start in [(0xc,dependencies,24),(0x8000001c,rpaths,12)]:
            for value in values:
                size=(start+len(value.encode())+1+7)//8*8
                commands.append((struct.pack('<III',kind,size,start)+b'\0'*(start-12)+value.encode()+b'\0').ljust(size,b'\0'))
        block=b''.join(commands)
        path.parent.mkdir(exist_ok=True,parents=True)
        path.write_bytes(struct.pack('<IiiIIIII',0xfeedfacf,0x0100000c,0,2,len(commands),len(block),0,0)+block)
    def test_native_system_closure_requires_pinned_cache_identity(self):
        executable,inspect=self.graph(['/usr/lib/libSystem.B.dylib'])
        good=m.closure([executable],executable,[self.root],{'/usr/lib/libSystem.B.dylib'},inspect)
        self.assertEqual(good['files'][str(executable.resolve())]['dependencies'][0]['kind'],'system-cache')
        with self.assertRaisesRegex(ValueError,'pinned cache'):m.closure([executable],executable,[self.root],set(),inspect)
    def test_native_missing_or_escaping_dependency_rejects(self):
        executable,inspect=self.graph(['@rpath/missing.dylib'])
        with self.assertRaisesRegex(ValueError,'missing/ambiguous'):m.closure([executable],executable,[self.root],set(),inspect)
        executable,inspect=self.graph(['/no/such/library'])
        with self.assertRaisesRegex(ValueError,'missing/ambiguous'):m.closure([executable],executable,[self.root],set(),inspect)
    def test_empty_and_inconsistent_native_inspector_reject(self):
        executable,inspect=self.graph(['/usr/lib/libSystem.B.dylib'])
        with self.assertRaisesRegex(ValueError,'inspector header'):m.closure([executable],executable,[self.root],set(),lambda args:'')
        def incomplete(args):return str(args[-1])+':\n' if '-L' in args else inspect(args)
        with self.assertRaisesRegex(ValueError,'list/load-byte'):m.closure([executable],executable,[self.root],set(),incomplete)
        def hidden(args):return str(args[-1])+':\nLoad command 0\nLoad command 1\n' if '-l' in args else inspect(args)
        with self.assertRaisesRegex(ValueError,'load-byte'):m.closure([executable],executable,[self.root],set(),hidden)
    def test_inherited_loader_path_uses_executable_origin(self):
        executable,inspect=self.graph([])
        self.object(executable,[],['@loader_path/lib'])
        plugin=self.root/'nested/deep/plugin';dependency=self.root/'lib/required'
        self.object(plugin,['@rpath/required'],[]);self.object(dependency,['/usr/lib/libSystem.B.dylib'],[])
        result=m.closure([plugin],executable,[self.root],{'/usr/lib/libSystem.B.dylib'},inspect)
        self.assertIn(str(dependency.resolve()),result['files'])
    def test_loader_expansion_and_all_macho_headers_recognized(self):
        self.assertEqual(m.expand('@loader_path/../lib',Path('/tmp/runtime/bin'),Path('/tmp/exec')),Path('/tmp/runtime/bin/../lib'))
        for magic in (b'\xca\xfe\xba\xbf',b'\xcf\xfa\xed\xfe'):
            p=self.root/'native';p.write_bytes(magic);self.assertTrue(m.native(p))
    def test_python_profile_does_not_admit_missing_cpp_dependencies(self):
        fixture=self.root/'fixtures';fixture.mkdir();runtime=self.root/'runtime';runtime.mkdir()
        (runtime/'data.py').write_bytes(b'public runtime fixture')
        inventory=fixture/'candidate-runtime-inventory.json'
        inventory.write_text(json.dumps(dict(sourceRoot=str(runtime),files=m.tree(runtime,100,1024)['files'])))
        (fixture/'candidate-inputs.json').write_text(json.dumps(dict(runtimeInventory=dict(path=str(inventory),sha256=m.file_row(inventory)['sha256']))))
        (fixture/'cpp-prerequisite-block.json').write_text(json.dumps(dict(status='BLOCKED',acquisitionAuthorized=False,
            archive=dict(sha256='6e1aee535473414164bf83e4ebc40240dec71a4701f8a642d906e95bea1aea0c'))))
        caches=self.root/'caches';caches.mkdir()
        (caches/'dyld_shared_cache_arm64e.map').write_text('/usr/lib/libSystem.B.dylib\n')
        attempt=self.root/'attempt';attempt.mkdir();original=m.file_row
        def row(path):
            path=Path(path)
            if path.is_file():return original(path)
            return dict(path=str(path),bytes=1,mode=0o755,sha256='0'*64)
        def inspect(argv,**kwargs):return '26.6.2\n' if '-productVersion' in argv else '25G83\n'
        with patch.object(m,'FIX',fixture),patch.object(m,'CACHES',caches),patch.object(m,'file_row',side_effect=row), \
             patch.object(m,'metadata_command',side_effect=inspect),patch.object(m,'closure',return_value=dict(files={},totalBytes=0)) as native:
            m.prepare(attempt)
        result=json.loads((attempt/'prepared-catalog.json').read_text())
        self.assertEqual(result['admissionMode'],'python-generation-cpp-blocked-v1')
        self.assertEqual(result['compilerPrerequisites']['status'],'BLOCKED')
        self.assertIsNone(result['compilerNativeClosure']);self.assertEqual(result['toolchainInventories'],[])
        self.assertFalse(result['compilerExecuted']);self.assertFalse(result['vendorCodeExecuted'])
        self.assertEqual(len(native.call_args_list),2)
        self.assertTrue(all(m.CLANG not in call.args[0] for call in native.call_args_list))


if __name__=='__main__':unittest.main()
