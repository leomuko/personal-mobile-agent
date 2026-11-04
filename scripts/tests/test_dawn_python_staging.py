"""Synthetic-only private materialization and tamper checks."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
s=importlib.util.spec_from_file_location('dawn_staging_test',ROOT/'scripts/diagnostics/dawn_python_patch_v1/staging.py')
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)

class DawnStagingTests(unittest.TestCase):
    def setUp(self):
        t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);self.root=Path(t.name)
    def test_copy_runtime_replaces_cache_and_rewrites_alias(self):
        src=self.root/'source';(src/'bin').mkdir(parents=True)
        (src/'bin/python3').write_bytes(b'synthetic');os.chmod(src/'bin/python3',0o775)
        (src/'cache').symlink_to('/no/cache');(src/'python').symlink_to(src/'bin/python3')
        spec=dict(sourceRoot=str(src),files=m.catalog.tree(src,10,100)['files'],privateCopyTransformations=[
            dict(path='cache',action='replace-with-private-empty-directory'),
            dict(path='python',action='relative-symlink',target='bin/python3')])
        result=m.runtime(spec,self.root/'private')
        self.assertEqual(result['regularBytes'],9);self.assertTrue((self.root/'private/cache').is_dir())
        self.assertEqual(os.readlink(self.root/'private/python'),'bin/python3')
        m.verify_tree(result)
        (self.root/'private/bin/python3').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError,'drift'):m.verify_tree(result)
    def test_changed_source_and_external_unapproved_link_reject(self):
        src=self.root/'source';src.mkdir();(src/'x').write_bytes(b'pinned')
        spec=dict(sourceRoot=str(src),files=m.catalog.tree(src,10,100)['files'],privateCopyTransformations=[])
        (src/'x').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError,'drift'):m.runtime(spec,self.root/'private')
        (src/'outside').symlink_to('/no/cache');spec['files']=m.catalog.tree(src,10,100)['files']
        with self.assertRaisesRegex(ValueError,'external'):m.runtime(spec,self.root/'private2')
    def test_materialization_refuses_overwrite_and_wrong_hash(self):
        p=self.root/'data';m.put(p,b'original')
        with self.assertRaises(FileExistsError):m.put(p,b'new')
        with self.assertRaisesRegex(ValueError,'changed'):m.verified_bytes(p,dict(bytes=8,sha256='0'*64))
    def test_clone_detects_incomplete_or_changed_inputs(self):
        source=self.root/'source';source.mkdir();(source/'file').write_bytes(b'original')
        receipt=m.catalog.tree(source,10,100)
        copied=m.clone(source,self.root/'copy',receipt)
        self.assertEqual(copied['files'],receipt['files'])
        (source/'file').unlink()
        with self.assertRaisesRegex(ValueError,'drift'):m.clone(source,self.root/'copy2',receipt)
