"""Exact patch/rewrite oracles on synthetic source trees, no shell execution."""
import hashlib,importlib.util,json
from pathlib import Path
import tempfile,unittest
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2]
s=importlib.util.spec_from_file_location('test_abseil_transform',ROOT/'scripts/diagnostics/dawn_abseil_v1/transforms.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
PATCH=ROOT/'planning/fixtures/dawn-abseil-preparation-v1/root-abseil.patch'

class AbseilTransformTests(unittest.TestCase):
    def setUp(self):
        t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);self.root=Path(t.name);self.hunks=m.parse_patch(PATCH.read_bytes())
    def put(self,name,data):
        p=self.root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
    def fixture(self):
        for h in self.hunks:self.put(h['target'],b'\n'*(h['oldLine']-1)+h['before'].encode()+b'tail\n')
        self.put('absl/a/b/BUILD.bazel',b'@googletest//:x\n');self.put('absl/a/b/c/BUILD.bazel',b'@googletest//:y\n')
        self.put('untouched',b'unchanged');return m.catalog.tree(self.root,100,1024**2)
    def test_frozen_patch_has_only_the_two_exact_hunks(self):
        self.assertEqual(tuple(h['target'] for h in self.hunks),m.TARGETS)
        self.assertEqual([h['oldLine'] for h in self.hunks],[577,39])
        with self.assertRaisesRegex(ValueError,'SHA'):m.parse_patch(PATCH.read_bytes()+b'changed')
    def test_private_patch_preserves_every_other_file(self):
        before=self.fixture();plan=m.patch_plan(self.root,before,self.hunks)
        class Budget:
            def charge(self,n):pass
        after=m.apply_patch(self.root,before,plan,Budget(),lambda:None)
        self.assertEqual((self.root/'untouched').read_bytes(),b'unchanged')
        for row in plan:self.assertEqual(after['files'][row['target']]['sha256'],row['postimageSha256'])
    def test_context_mismatch_duplicate_and_line_offset_are_not_repaired(self):
        before=self.fixture();h=self.hunks[0];p=self.root/h['target'];original=p.read_bytes()
        for changed in [original.replace(h['before'].encode(),b'wrong context'),original+h['before'].encode(),b'\n'+original]:
            with self.subTest(changed=len(changed)):
                p.write_bytes(changed);tree=m.catalog.tree(self.root,100,1024**2)
                with self.assertRaisesRegex(ValueError,'context|offset'):m.patch_plan(self.root,tree,self.hunks)
    def test_build_plan_includes_all_depths_and_zero_replacement_files(self):
        before=self.fixture();plan=m.build_plan(self.root,before)
        self.assertEqual({len(x['target'].split('/')) for x in plan},{3,4,5})
        self.assertTrue(any(x['matches']==0 for x in plan));self.assertTrue(all(x['backup'].endswith('-e') for x in plan))
    def test_expected_backups_and_unchanged_comparison_are_complete(self):
        before=self.fixture();plan=m.build_plan(self.root,before)
        for row in plan:
            p=self.root/row['target'];data=p.read_bytes();self.put(row['backup'],data);p.write_bytes(data.replace(m.OLD,m.NEW))
        m.verify_rewrite(self.root,before,plan)
        self.put('untouched',b'wrong')
        with self.assertRaisesRegex(ValueError,'unchanged'):m.verify_rewrite(self.root,before,plan)
    def test_backup_collision_unmatched_depth_and_links_reject(self):
        before=self.fixture();self.put('absl/base/BUILD.bazel-e',b'prior')
        with self.assertRaisesRegex(ValueError,'backup'):m.build_plan(self.root,m.catalog.tree(self.root,100,1024**2))
        (self.root/'absl/base/BUILD.bazel-e').unlink();(self.root/'absl/a/b/c/BUILD.bazel').unlink()
        with self.assertRaisesRegex(ValueError,'depth'):m.build_plan(self.root,m.catalog.tree(self.root,100,1024**2))
        (self.root/'absl/alias').symlink_to('base')
        with self.assertRaisesRegex(ValueError,'link'):m.build_plan(self.root,m.catalog.tree(self.root,100,1024**2))

    def test_derived_build_file_ceiling_is_checked_before_any_mutation(self):
        self.fixture();self.put('absl/a/b/BUILD.bazel',b'@googletest'*120)
        before=m.catalog.tree(self.root,100,1024**2)
        with patch.object(m,'MIB',200),self.assertRaisesRegex(ValueError,'derived BUILD'):
            m.build_plan(self.root,before)
        self.assertEqual(m.catalog.tree(self.root,100,1024**2),before)
