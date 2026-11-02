"""Synthetic source-inspection guards; no real downloads or upstream execution."""
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import sys
import types
import unittest
import urllib.request
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/diagnostics/root_dependency_amendment_v1/inspect_dawn.py'
s = importlib.util.spec_from_file_location('dawn_inspection', SCRIPT)
m = importlib.util.module_from_spec(s); s.loader.exec_module(m)
COMMIT = '1' * 40


class SourceInspectionTests(unittest.TestCase):
    def setUp(self):
        t = tempfile.TemporaryDirectory(); self.addCleanup(t.cleanup); self.root = Path(t.name)

    def archive(self, extras=()):
        path = self.root / 'source.tar.gz'
        with tarfile.open(path, 'w:gz') as t:
            for name, data in [('generator/templates/api_cpp.h', b'template'),
                               ('generator/dawn_json_generator.py', b'raise Exception("must not execute")'), *extras]:
                row = tarfile.TarInfo('dawn-' + COMMIT + '/' + name); row.size = len(data)
                t.addfile(row, io.BytesIO(data))
        return path

    def test_commit_ref_and_annotated_tag_resolve(self):
        self.assertEqual(m.commit_from([{'ref': 'refs/tags/' + m.TAG, 'object': {'type': 'commit', 'sha': COMMIT}}]), COMMIT)
        self.assertEqual(m.commit_from([{'ref': 'refs/tags/' + m.TAG, 'object': {'type': 'tag', 'sha': '2' * 40}},
                                       {'object': {'type': 'commit', 'sha': COMMIT}}]), COMMIT)

    def test_wrong_tag_unknown_object_or_unresolved_tag_rejected(self):
        for ref, kind in [('refs/tags/other', 'commit'), ('refs/tags/' + m.TAG, 'tree'), ('refs/tags/' + m.TAG, 'tag')]:
            with self.assertRaises(ValueError): m.commit_from([{'ref': ref, 'object': {'type': kind, 'sha': COMMIT}}])

    def test_selected_source_is_only_data(self):
        value = m.inspect_archive(self.archive(), COMMIT, self.root / 'selected')
        self.assertFalse(value['sourceExecuted']); self.assertEqual(len(value['members']), 2)
        self.assertTrue((self.root / 'selected/generator/dawn_json_generator.py').is_file())

    def test_traversal_and_duplicate_members_rejected(self):
        for index, names in enumerate([[('../escape', b'bad')], [('generator/templates/api_cpp.h', b'duplicate')]]):
            with self.assertRaises(ValueError): m.inspect_archive(self.archive(names), COMMIT, self.root / ('selected-' + str(index)))

    def test_mismatched_archive_commit_prefix_rejected(self):
        with self.assertRaisesRegex(ValueError, 'prefix'):
            m.inspect_archive(self.archive(), '2' * 40, self.root / 'selected')

    def test_metadata_read_bound(self):
        with self.assertRaisesRegex(ValueError, 'metadata read ceiling'):
            m.LimitedReader(io.BytesIO(b'')).read(3 * 1024**2)

    def test_truncated_gzip_never_receives_complete_manifest(self):
        p = self.archive(); p.write_bytes(p.read_bytes()[:-4])
        with self.assertRaises((EOFError, OSError, tarfile.TarError)):
            m.inspect_archive(p, COMMIT, self.root / 'selected')

    def test_partial_download_preserved_on_limit(self):
        output = self.root / 'partial'
        opener = type('Opener', (), {'open': lambda *args, **kw: io.BytesIO(b'oversized')})()
        with patch.object(m.urllib.request, 'build_opener', return_value=opener):
            with self.assertRaisesRegex(ValueError, 'byte ceiling'): m.fetch('https://api.github.com/fixed', output, 3)
        self.assertEqual(output.stat().st_size, 0)

    def test_redirects_cannot_downgrade_or_leave_hosts(self):
        for uri in ['http://api.github.com/data', 'https://unapproved.invalid/data', 'https://api.github.com:444/data']:
            with self.assertRaisesRegex(ValueError, 'outside scope'):
                m.HttpsScope().redirect_request(urllib.request.Request('https://api.github.com/fixed'), None, 302, '', {}, uri)

    def test_aggregate_budget_charges_before_writing(self):
        b = m.ByteBudget(3); b.charge(3)
        with self.assertRaisesRegex(ValueError, 'aggregate'): b.charge(1)
        q = self.root / 'receipt.json'
        with self.assertRaisesRegex(ValueError, 'aggregate'): m.write_json(q, {'large': 'value'}, m.ByteBudget(1))
        self.assertFalse(q.exists())

    def test_receipt_serialization_ceiling(self):
        q = self.root / 'receipt.json'
        with self.assertRaisesRegex(ValueError, 'serialization'): m.write_json(q, {'large': 'value'}, m.ByteBudget(), 3)
        self.assertFalse(q.exists())

    def test_long_member_names_are_bounded(self):
        with self.assertRaisesRegex(ValueError, 'metadata ceiling'):
            m.inspect_archive(self.archive([('a' * 1025, b'')]), COMMIT, self.root / 'selected')

    def test_owner_interruption_always_records_outcome(self):
        f = self.root / 'freeze.json'; python = str(Path(sys.executable).resolve())
        paths = [SCRIPT, Path(m.owner.__file__), Path(python)]
        value = {'protocol': 'root-dependency-source-inspection-v1', 'inspectorSha256': m.owner.digest(SCRIPT),
                 'inputs': [{'path': str(p), 'bytes': p.stat().st_size, 'sha256': m.owner.digest(p)} for p in paths],
                 'python': python, 'environment': {}, 'attemptParent': str(self.root / 'attempts')}
        f.write_text(json.dumps(value)); attempt = self.root / 'attempts/one'
        argv = [str(SCRIPT), '--freeze', str(f), '--sha256', m.owner.digest(f), '--attempt', str(attempt)]
        with patch.object(sys, 'argv', argv), patch.object(m.shutil, 'disk_usage', return_value=types.SimpleNamespace(free=2 * 1024**3)), \
                patch.object(m.owner, 'run_owned', side_effect=ValueError('injected interruption')):
            self.assertEqual(m.main(), 1)
        outcome = json.loads((attempt / 'outcome.json').read_text())
        self.assertEqual(outcome['classification'], 'OWNER_FAILED_OR_INTERRUPTED'); self.assertIsNone(outcome['cleanupConfirmed'])


if __name__ == '__main__': unittest.main()
