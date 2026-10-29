"""Host-only prerequisite provenance and failure-path regressions; no Android build."""
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/diagnostics/android_runtime_recipe/audit.py'
spec = importlib.util.spec_from_file_location('android_runtime_recipe_audit', SCRIPT)
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def entry(data=b'synthetic source\n'):
    return {'repository': 'LiteRT-LM', 'revision': 'a' * 40, 'path': 'BUILD',
            'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
            'gitBlob': hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()}


def source_declarations():
    return {
        ('LiteRT', 'WORKSPACE'): 'http_archive(\n name = "ml_drift",\n strip_prefix = "ml-drift-main",\n)\n',
        ('LiteRT', 'third_party/litert_gpu/workspace.bzl'):
            'def repo():\n http_archive(\n name = "litert_gpu",\n url = "litert-2.1.1.aar",\n )\n',
        ('LiteRT', 'third_party/litert_prebuilts/workspace.bzl'):
            'http_archive(\n name = "litert_prebuilts",\n url = "/latest/litert_prebuilts.zip",\n)\n',
        ('LiteRT', 'litert/runtime/accelerators/gpu/BUILD'): '"@ml_drift//ml_drift/cl:opencl_wrapper"',
    }


class ProvenanceTests(unittest.TestCase):
    def test_frozen_manifest_binds_both_abis_full_lock_and_exact_revisions(self):
        value = audit.inputs()
        self.assertEqual(value['abis'], ['arm64-v8a', 'x86_64'])
        self.assertEqual(len(value['files']), 48)
        rows = {(e['repository'], e['path']): e for e in value['files']}
        self.assertEqual(rows['LiteRT-LM', 'Cargo.lock']['sha256'],
                         'b95103f9b316784814f1e39cc0124cbc134e61d3f0f27c7ef514408e65b7916a')
        for e in value['files']:
            self.assertEqual(e['revision'], value['sdkRevision' if e['repository'] == 'LiteRT-LM' else 'litertRevision'])

    def test_changed_manifest_rejected_in_optimized_python(self):
        with tempfile.TemporaryDirectory() as name:
            p = Path(name) / 'inputs.json'
            value = json.loads(audit.INPUTS.read_text());value['abis'] = ['arm64-v8a']
            p.write_text(json.dumps(value))
            result = subprocess.run([sys.executable, '-O', '-c',
                'import runpy; from pathlib import Path; m=runpy.run_path(' + repr(str(SCRIPT)) +
                '); m["inputs"](Path(' + repr(str(p)) + '))'], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('source manifest changed', result.stderr)

    def test_source_tamper_and_wrong_git_blob_rejected(self):
        e = entry()
        audit.verify_blob(e, b'synthetic source\n')
        for key in ['bytes', 'sha256', 'gitBlob']:
            changed = copy.deepcopy(e);changed[key] = 0 if key == 'bytes' else '0' * len(e[key])
            with self.assertRaises(ValueError):audit.verify_blob(changed, b'synthetic source\n')

    def test_unpinned_revision_and_unsafe_paths_rejected(self):
        for key, value in [('revision', 'main'), ('path', '../BUILD'), ('path', '/BUILD'), ('repository', 'other')]:
            e = entry();e[key] = value
            with self.assertRaises(ValueError):audit.relative(e)

    def test_cache_symlink_cannot_escape_collection_root(self):
        with tempfile.TemporaryDirectory() as name:
            cache = Path(name) / 'cache';cache.mkdir()
            outside = Path(name) / 'outside';outside.mkdir()
            (cache / 'LiteRT-LM').symlink_to(outside, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, 'escapes'):audit.destination(cache, entry())


class CollectionTests(unittest.TestCase):
    def test_interrupted_download_never_publishes_an_input(self):
        with tempfile.TemporaryDirectory() as name:
            cache = Path(name)
            with patch.object(audit, 'download', side_effect=OSError('interrupted')):
                with self.assertRaises(OSError):audit.collect({'files': [entry()]}, cache)
            self.assertEqual(list(cache.iterdir()), [])

    def test_valid_existing_file_is_reused_without_network(self):
        with tempfile.TemporaryDirectory() as name:
            cache = Path(name);p = cache / 'LiteRT-LM/BUILD';p.parent.mkdir();p.write_bytes(b'synthetic source\n')
            with patch.object(audit, 'download') as download:
                audit.collect({'files': [entry()]}, cache)
                download.assert_not_called()

    def test_corrupt_existing_input_is_not_replaced_or_redownloaded(self):
        with tempfile.TemporaryDirectory() as name:
            cache = Path(name);p = cache / 'LiteRT-LM/BUILD';p.parent.mkdir();p.write_bytes(b'corrupt')
            with patch.object(audit, 'download') as download:
                with self.assertRaises(ValueError):audit.collect({'files': [entry()]}, cache)
                download.assert_not_called()
            self.assertEqual(p.read_bytes(), b'corrupt')

    def test_oversized_response_rejected(self):
        with patch.object(audit.urllib.request, 'urlopen', return_value=io.BytesIO(b'x' * 100)):
            with self.assertRaisesRegex(ValueError, 'frozen size'):audit.download(entry())

    def test_same_size_wrong_response_rejected(self):
        with patch.object(audit.urllib.request, 'urlopen', return_value=io.BytesIO(b'x' * entry()['bytes'])):
            with self.assertRaisesRegex(ValueError, 'identity mismatch'):audit.download(entry())

    def test_missing_source_cannot_emit_a_build_report(self):
        with tempfile.TemporaryDirectory() as name:
            cache = Path(name);report = cache / 'report.json'
            with patch.object(sys, 'argv', [str(SCRIPT), 'check', '--cache', str(cache), '--report', str(report)]):
                with patch.object(sys, 'stderr', io.StringIO()):self.assertEqual(audit.main(), 1)
            self.assertFalse(report.exists())


class ClosureTests(unittest.TestCase):
    def test_source_gap_and_binary_alternatives_remain_distinct(self):
        rows = audit.blockers(source_declarations())
        self.assertEqual([r['status'] for r in rows], ['BLOCKED', 'NOT_ADMITTED', 'UNRESOLVED'])
        self.assertIn('CPU', rows[0]['boundary'])
        self.assertIn('not proof', rows[2]['boundary'])

    def test_missing_or_ambiguous_declaration_cannot_clear_a_gate(self):
        for text in ['', source_declarations()['LiteRT', 'WORKSPACE'] * 2]:
            with self.assertRaisesRegex(ValueError, 'missing/ambiguous'):audit.archive_declaration(text, 'ml_drift')

    def test_changed_dependency_definition_requires_new_review(self):
        sources = source_declarations()
        sources['LiteRT', 'WORKSPACE'] = sources['LiteRT', 'WORKSPACE'].replace(
            ' strip_prefix', ' url = "https://example.invalid/other",\n strip_prefix')
        with self.assertRaisesRegex(ValueError, 'differs'):audit.blockers(sources)

    def test_removing_gpu_edge_does_not_silently_allow_cpu_only_scope(self):
        sources = source_declarations();sources['LiteRT', 'litert/runtime/accelerators/gpu/BUILD'] = ''
        with self.assertRaisesRegex(ValueError, 'edge missing'):audit.blockers(sources)

    def test_previous_blocked_report_cannot_be_overwritten(self):
        with tempfile.TemporaryDirectory() as name:
            p = Path(name) / 'report.json';p.write_text('preserved failure')
            with patch.object(audit, 'audit', return_value={'buildRecipeStatus': 'BLOCKED'}):
                with patch.object(sys, 'argv', [str(SCRIPT), 'check', '--report', str(p)]):
                    with patch.object(sys, 'stderr', io.StringIO()):self.assertEqual(audit.main(), 1)
            self.assertEqual(p.read_text(), 'preserved failure')


if __name__ == '__main__':
    unittest.main()
