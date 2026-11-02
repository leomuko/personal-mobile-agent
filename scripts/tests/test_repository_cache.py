"""Synthetic cache/preparation regressions. No network, Bazel or native SDK tools."""
import copy
from contextlib import contextmanager
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / 'scripts/diagnostics/repository_cache_v1'
spec = importlib.util.spec_from_file_location('cache_analysis_tests', HERE / 'analyze.py')
a = importlib.util.module_from_spec(spec); spec.loader.exec_module(a)
p = a.prep


def write(path, value): path.write_text(json.dumps(value) + '\n')
def file_row(path): return {'path': str(path), 'bytes': path.stat().st_size, 'sha256': p.sha(path)}


class CacheTests(unittest.TestCase):
    def setUp(self):
        t = tempfile.TemporaryDirectory(); self.addCleanup(t.cleanup); self.root = Path(t.name)
        out = io.BytesIO()
        with zipfile.ZipFile(out, 'w') as z: z.writestr('member', b'known synthetic archive')
        self.source = self.root / 'archive'; self.source.write_bytes(out.getvalue())
        self.h = p.sha(self.source); self.ref = self.root / 'declaration.bzl'; self.ref.write_text(self.h)
        self.row = {'sha256': self.h, 'kind': 'retained-cache', 'source': str(self.source),
                    'maxBytes': self.source.stat().st_size, 'markers': [],
                    'references': [{'path': str(self.ref), 'sha256': p.sha(self.ref)}]}
        self.manifest = self.root / 'manifest.json'
        write(self.manifest, {'protocol': 'repository-archive-cache-v1', 'entries': [self.row]})
        self.cache = self.root / 'cache'; self.cache.mkdir()

    def receipt(self, row): return {'protocol': 'repository-archive-cache-v1', 'entries': [row]}

    def freeze(self):
        python = Path(sys.executable).resolve()
        paths = [self.manifest, Path(p.__file__), Path(p.owned.__file__), python]
        f = {'protocol': 'repository-archive-cache-v1', 'implementationSha256': p.sha(p.__file__),
             'inputs': [file_row(q) for q in paths], 'manifest': str(self.manifest),
             'manifestSha256': p.sha(self.manifest), 'python': str(python),
             'environment': {'PATH': '/usr/bin:/bin', 'PYTHONPYCACHEPREFIX': '/tmp/edge-cache-tests-pycache'},
             'perEntrySeconds': 180, 'totalSeconds': 900, 'cleanupSeconds': 15,
             'logLimitBytes': 2 * 1024**2, 'attemptParent': str(self.root / 'attempts')}
        path = self.root / 'freeze.json'; write(path, f); return path

    def test_verified_payload_publishes_and_receipt_revalidates(self):
        partial = self.root / 'partial'; p.transfer(self.row, partial)
        row = p.publish(self.row, partial, self.cache)
        p.verify_cache(self.cache, self.receipt(row))
        self.assertEqual(row['transformations'], 'NONE'); self.assertFalse(row['extractedTreeAccepted'])

    def test_corrupt_and_truncated_archive_never_publish(self):
        for data in [b'wrong', self.source.read_bytes()[:-5]]:
            partial = self.root / 'bad'; partial.write_bytes(data)
            with self.assertRaisesRegex(ValueError, 'checksum/size mismatch'):
                p.publish(self.row, partial, self.cache)
            self.assertFalse((self.cache / 'content_addressable').exists())

    def test_incomplete_extracted_tree_is_not_a_cache_payload(self):
        self.row['source'] = str(self.root)
        with self.assertRaisesRegex(ValueError, 'regular input'):
            p.transfer(self.row, self.root / 'partial')

    def test_missing_payload_rejects_manifest(self):
        self.source.unlink()
        with self.assertRaises(ValueError): p.validate_manifest(json.loads(self.manifest.read_text()))

    def test_changed_declaration_rejects_manifest(self):
        self.ref.write_text('changed')
        with self.assertRaisesRegex(ValueError, 'declaration'):
            p.validate_manifest(json.loads(self.manifest.read_text()))

    def test_duplicate_or_empty_manifest_rejected(self):
        for rows in [[], [self.row, self.row]]:
            with self.assertRaises(ValueError):
                p.validate_manifest({'protocol': 'repository-archive-cache-v1', 'entries': rows})

    def test_conflicting_cache_entry_is_preserved(self):
        dest = self.cache / 'content_addressable/sha256' / self.h; dest.mkdir(parents=True)
        (dest / 'file').write_bytes(b'conflict')
        with self.assertRaises(FileExistsError): p.publish(self.row, self.source, self.cache)
        self.assertEqual((dest / 'file').read_bytes(), b'conflict')

    def test_empty_conflicting_entry_is_not_overwritten(self):
        (self.cache / 'content_addressable/sha256' / self.h).mkdir(parents=True)
        with self.assertRaises(FileExistsError): p.publish(self.row, self.source, self.cache)

    def test_partial_read_failure_retains_bytes_unaccepted(self):
        class Broken(io.BytesIO):
            def read(self, size):
                if self.tell(): raise OSError('interrupted download')
                return super().read(4)
        row = dict(self.row, kind='download', url='https://static.rust-lang.org/dist/synthetic.tar.xz')
        partial = self.root / 'partial'
        with patch.object(p.urllib.request, 'urlopen', return_value=Broken(b'partial bytes')):
            with self.assertRaisesRegex(OSError, 'interrupted'): p.transfer(row, partial)
        self.assertEqual(partial.read_bytes(), b'part')
        self.assertEqual(list(self.cache.iterdir()), [])

    def test_download_size_limit_is_enforced_before_write(self):
        row = dict(self.row, kind='download', maxBytes=3, url='https://static.rust-lang.org/dist/synthetic.tar.xz')
        with patch.object(p.urllib.request, 'urlopen', return_value=io.BytesIO(b'oversized')):
            with self.assertRaisesRegex(ValueError, 'size limit'): p.transfer(row, self.root / 'partial')
        self.assertEqual((self.root / 'partial').stat().st_size, 0)

    def test_unapproved_url_does_not_reach_network(self):
        row = dict(self.row, kind='download', url='https://unapproved.invalid/file')
        with patch.object(p.urllib.request, 'urlopen') as network:
            with self.assertRaisesRegex(ValueError, 'worker URL'): p.transfer(row, self.root / 'partial')
            network.assert_not_called()

    def test_actual_rust_component_url_shapes_are_admitted(self):
        names = ['rustfmt-1.86.0-aarch64-apple-darwin', 'llvm-tools-1.86.0-aarch64-apple-darwin',
                 'rust-std-1.86.0-aarch64-apple-darwin', 'rust-std-1.86.0-aarch64-linux-android',
                 'rust-std-1.86.0-x86_64-linux-android']
        for index, name in enumerate(names):
            row = dict(self.row, kind='download', url='https://static.rust-lang.org/dist/' + name + '.tar.xz')
            p.validate_manifest({'protocol': 'repository-archive-cache-v1', 'entries': [row]})
            with patch.object(p.urllib.request, 'urlopen', return_value=io.BytesIO(self.source.read_bytes())):
                p.transfer(row, self.root / ('component-' + str(index)))

    def test_cache_missing_extra_and_symlink_entries_rejected(self):
        row = p.publish(self.row, self.source, self.cache); receipt = self.receipt(row)
        f = self.cache / 'content_addressable/sha256' / self.h / 'file'; raw = f.read_bytes(); f.unlink()
        with self.assertRaises(ValueError): p.verify_cache(self.cache, receipt)
        f.symlink_to(self.source)
        with self.assertRaises(ValueError): p.verify_cache(self.cache, receipt)
        f.unlink(); f.write_bytes(raw); f.chmod(0o644)
        (self.cache / 'unlisted').write_text('extra')
        with self.assertRaises(ValueError): p.verify_cache(self.cache, receipt)

    def test_canonical_id_markers_are_preserved(self):
        marker = self.root / ('id-' + '1' * 64); marker.write_bytes(b'')
        self.row['markers'] = [{'name': marker.name, 'source': str(marker), 'bytes': 0, 'sha256': p.sha(marker)}]
        row = p.publish(self.row, self.source, self.cache)
        p.verify_cache(self.cache, self.receipt(row)); self.assertEqual(row['markers'], [marker.name])

    def test_real_synthetic_worker_completes_and_prior_attempt_cannot_be_reused(self):
        f = self.freeze(); attempt = self.root / 'attempts/one'
        with patch.object(p.shutil, 'disk_usage', return_value=types.SimpleNamespace(free=64 * 1024**3)):
            result = p.run(f, p.sha(f), attempt)
            self.assertTrue(result['completed']); self.assertTrue((attempt / 'receipt.json').exists())
            with self.assertRaisesRegex(ValueError, 'preserve prior'): p.run(f, p.sha(f), attempt)

    def test_timeout_propagates_and_leaves_no_success_receipt(self):
        f = self.freeze(); attempt = self.root / 'attempts/one'
        result = {'classification': 'TIMED_OUT', 'cleanupConfirmed': True, 'exitCode': -15}
        with patch.object(p.shutil, 'disk_usage', return_value=types.SimpleNamespace(free=64 * 1024**3)):
            with patch.object(p.owned, 'run_owned', return_value=result):
                with self.assertRaisesRegex(ValueError, 'worker failed'): p.run(f, p.sha(f), attempt)
        self.assertFalse((attempt / 'receipt.json').exists())
        self.assertFalse(json.loads((attempt / 'report.json').read_text())['completed'])

    def test_uncertain_cleanup_propagates_before_next_entry(self):
        f = self.freeze(); attempt = self.root / 'attempts/one'
        with patch.object(p.shutil, 'disk_usage', return_value=types.SimpleNamespace(free=64 * 1024**3)):
            with patch.object(p.owned, 'run_owned', side_effect=ValueError('cleanup uncertain')) as worker:
                with self.assertRaisesRegex(ValueError, 'cleanup uncertain'): p.run(f, p.sha(f), attempt)
                self.assertEqual(worker.call_count, 1)
        self.assertFalse((attempt / 'receipt.json').exists())

    def test_low_analysis_disk_prevents_cache_copy_and_bazel(self):
        with patch.object(a.shutil, 'disk_usage', return_value=types.SimpleNamespace(free=1024)):
            with patch.object(a.previous, 'execute') as native, patch.object(a.shutil, 'copytree') as copytree:
                with self.assertRaisesRegex(ValueError, 'BLOCKED_STORAGE'):
                    a.execute({'repositoryCache': str(self.root / 'work'), 'minimumFreeBytes': 18 * 1024**3}, {}, self.root / 'analysis')
                native.assert_not_called(); copytree.assert_not_called()

    def test_tampered_preparation_freeze_prevents_worker(self):
        f = self.freeze()
        with patch.object(p.owned, 'run_owned') as worker:
            with self.assertRaisesRegex(ValueError, 'freeze changed'): p.run(f, '0' * 64, self.root / 'attempt')
            worker.assert_not_called()

    def delayed_finalization(self, delayed_name):
        f = self.freeze(); attempt = self.root / 'attempts/one'; clock = types.SimpleNamespace(now=0)
        real_open = Path.open
        @contextmanager
        def delayed_open(path, *args, **kwargs):
            with real_open(path, *args, **kwargs) as out:
                yield out
            if path.name == 'receipt.json':
                clock.now = 1000
            elif path.name == 'report.json':
                with real_open(path) as raw: data = json.load(raw)
                if data.get('completed'): clock.now = 1000
        def opening(path, *args, **kwargs):
            mode = args[0] if args else kwargs.get('mode', 'r')
            if path.name == delayed_name and mode in ['x', 'w']: return delayed_open(path, *args, **kwargs)
            return real_open(path, *args, **kwargs)
        def worker(args, *unused):
            Path(args[-1]).write_bytes(self.source.read_bytes())
            return {'classification': 'ANALYSIS_COMMAND_SUCCEEDED', 'cleanupConfirmed': True, 'exitCode': 0}
        with patch.object(p.time, 'monotonic', side_effect=lambda: clock.now), \
             patch.object(p.shutil, 'disk_usage', return_value=types.SimpleNamespace(free=64 * 1024**3)), \
             patch.object(p.owned, 'run_owned', side_effect=worker), patch.object(Path, 'open', opening):
            with self.assertRaisesRegex(ValueError, 'finalization deadline'): p.run(f, p.sha(f), attempt)
        report = json.loads((attempt / 'report.json').read_text())
        self.assertFalse(report['completed']); self.assertEqual(report['elapsedSeconds'], 1000)

    def test_delayed_receipt_cannot_complete_after_deadline(self):
        self.delayed_finalization('receipt.json')

    def test_delayed_final_report_revokes_success(self):
        self.delayed_finalization('report.json')

    def test_preflight_time_counts_before_attempt_creation(self):
        f = self.freeze(); attempt = self.root / 'attempts/late'; clock = types.SimpleNamespace(now=0)
        actual = p.validate_manifest
        def delayed(value):
            actual(value); clock.now = 1000
        with patch.object(p.time, 'monotonic', side_effect=lambda: clock.now), \
             patch.object(p, 'validate_manifest', side_effect=delayed), patch.object(p.owned, 'run_owned') as worker:
            with self.assertRaisesRegex(ValueError, 'admission deadline'): p.run(f, p.sha(f), attempt)
            worker.assert_not_called()
        self.assertFalse(attempt.exists())

    def test_analysis_rejects_late_success_report_before_execution(self):
        fpath = self.freeze(); cache_row = p.publish(self.row, self.source, self.cache)
        receipt = self.root / 'receipt.json'; write(receipt, {'manifestSha256': p.sha(self.manifest), 'entries': [cache_row]})
        report = self.root / 'report.json'
        write(report, {'completed': True, 'preparationDeadlineSeconds': 900, 'elapsedSeconds': 1000})
        f = {'cacheAnalysisProtocol': 'repository-archive-cache-v1', 'cacheRunnerSha256': p.sha(a.__file__),
             'cacheReceipt': str(receipt), 'cacheManifest': str(self.manifest), 'preparationReport': str(report),
             'preparationFreeze': str(fpath), 'inputs': [{'path': str(q)} for q in
             [HERE / 'prepare.py', HERE / 'analyze.py', receipt, self.manifest, report, fpath]]}
        with patch.object(a.previous, 'load_freeze', return_value=(f, {})), patch.object(a.previous, 'execute') as native:
            with self.assertRaisesRegex(ValueError, 'over-budget'): a.load_freeze(fpath, p.sha(fpath))
            native.assert_not_called()


if __name__ == '__main__': unittest.main()
