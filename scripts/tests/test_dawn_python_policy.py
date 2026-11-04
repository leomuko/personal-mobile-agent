"""Policy gates before vendor work and exact eight-output comparison."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
s=importlib.util.spec_from_file_location('dawn_policy_test',ROOT/'scripts/diagnostics/dawn_python_patch_v1/policy.py')
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)


class DawnPolicyTests(unittest.TestCase):
    def facts(self):return dict(version=[3,13,13],implementation='cpython',machine='arm64',platform='darwin',bits=64,
        SOABI='cpython-313-darwin',GILDisabled=0,isolated=True,noSite=True,noBytecode=True)
    def test_wrong_interpreter_architecture_gil_or_isolation_rejects(self):
        m.candidate(self.facts())
        for name,value in [('version',[3,13,12]),('implementation','pypy'),('machine','x86_64'),('platform','linux'),
                           ('bits',32),('SOABI','cpython-313t-darwin'),('GILDisabled',1),('isolated',False),('noSite',False),('noBytecode',False)]:
            facts=self.facts();facts[name]=value
            with self.assertRaises(ValueError):m.candidate(facts)
    def pair(self):
        before={n:(b'before' if n in m.AFFECTED else b'unchanged') for n in m.HEADERS}
        after={n:(b'after' if n in m.AFFECTED else b'unchanged') for n in m.HEADERS}
        return before,after,[dict(old='before',new='after',proposedExpectedMatches=1)]
    def test_both_affected_outputs_must_match_transform(self):
        before,after,operations=self.pair();m.compare_outputs(before,after,operations)
        after['include/dawn/wire/client/webgpu_cpp.h']=b'ignored wire output would be wrong'
        with self.assertRaisesRegex(ValueError,'wire/client'):m.compare_outputs(before,after,operations)
    def test_unaffected_changed_and_unexpected_match_count_reject(self):
        before,after,operations=self.pair();after['include/dawn/webgpu.h']=b'changed'
        with self.assertRaisesRegex(ValueError,'mismatch'):m.compare_outputs(before,after,operations)
        before,after,operations=self.pair();operations[0]['proposedExpectedMatches']=2
        with self.assertRaisesRegex(ValueError,'match count'):m.compare_outputs(before,after,operations)
    def test_missing_extra_and_nondeterministic_outputs_reject(self):
        before,after,operations=self.pair();missing=dict(after);missing.pop(m.HEADERS[0])
        with self.assertRaises(ValueError):m.compare_outputs(before,missing,operations)
        extra=dict(after,unexpected=b'data')
        with self.assertRaises(ValueError):m.compare_outputs(before,extra,operations)
        m.repeatable(before,dict(before));changed=dict(before);changed[m.HEADERS[0]]=b'nondeterministic'
        with self.assertRaisesRegex(ValueError,'nonreproducible'):m.repeatable(before,changed)
    def test_exact_filesystem_inventory_is_required(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            for name in m.HEADERS:
                p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b'data')
            self.assertEqual(set(m.output_files(root)),set(m.HEADERS))
            (root/'extra').write_bytes(b'data')
            with self.assertRaisesRegex(ValueError,'extra'):m.output_files(root)
            (root/'extra').unlink();(root/m.HEADERS[0]).unlink()
            with self.assertRaisesRegex(ValueError,'missing'):m.output_files(root)

    def test_generated_workspace_rejects_side_effects(self):
        import hashlib
        source={'files':{'source':{'kind':'file','bytes':1,'sha256':'pinned'}}}
        outputs={'_out':{'kind':'directory'}}
        for name in m.HEADERS[:3]:
            full='_out/'+name
            for parent in Path(full).parents:
                if str(parent)!='.':outputs[str(parent)]={'kind':'directory'}
            outputs[full]={'kind':'file','bytes':1,'sha256':'output'}
        inventory=dict(source['files'],**outputs)
        def tree(*args):return dict(files=inventory)
        m.workspace(source,'/unused','headers',tree)
        inventory['secret']={'kind':'file'}
        with self.assertRaisesRegex(ValueError,'extra workspace'):m.workspace(source,'/unused','headers',tree)
        del inventory['secret'];inventory['source']={'kind':'file','sha256':'changed'}
        with self.assertRaisesRegex(ValueError,'modified source'):m.workspace(source,'/unused','headers',tree)


if __name__=='__main__':unittest.main()
