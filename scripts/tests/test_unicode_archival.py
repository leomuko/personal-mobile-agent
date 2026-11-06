"""Archival routing tests: unchanged checks, explicit old guidance, no native work."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


archive = load('archive_verification_tests', 'scripts/diagnostics/unicode_archival_v1/verify.py')
routing = load('archive_routing_tests', 'scripts/diagnostics/unicode_archival_v1/suite.py')


class ArchivalVerificationTests(unittest.TestCase):
    def test_explicit_archival_verification_passes_without_execution_authority(self):
        with patch('subprocess.Popen', side_effect=AssertionError('no native child')) as child:
            result = archive.verify_archive()
        self.assertEqual(result['mode'], 'ARCHIVAL_READ_ONLY')
        self.assertEqual(result['logicalOverrides'], ['AGENTS.md'])
        self.assertFalse(result['currentNativeAdmission'])
        self.assertFalse(result['nativeExecutionOccurred'])
        self.assertEqual(child.call_count, 0)

    def test_unmodified_current_source_guard_still_rejects_guidance_delta(self):
        with patch('subprocess.Popen', side_effect=AssertionError('no child')) as child:
            with self.assertRaisesRegex(ValueError, 'historical source changed: AGENTS.md'):
                archive.verify_current()
        self.assertEqual(child.call_count, 0)

    def test_archival_context_does_not_mutate_live_guard_or_native_function_roots(self):
        module = archive.legacy(archive.seal())
        original = module.verify_inputs
        with patch.object(archive, 'legacy', return_value=module):
            archive.verify_archive()
        self.assertIs(module.verify_inputs, original)
        self.assertEqual(original.__globals__['ROOT'], ROOT)
        self.assertEqual(module.prepare.__globals__['ROOT'], ROOT)
        with self.assertRaisesRegex(ValueError, 'historical source changed: AGENTS.md'):
            module.verify_inputs()

    def test_only_exact_guidance_logical_path_is_redirected(self):
        root, old = Path('/synthetic/root'), Path('/synthetic/archive/guidance')
        view = archive.ArchiveRoot(root, old)
        self.assertEqual(view / 'AGENTS.md', old)
        for name in ['other.md', 'docs/AGENTS.md', 'scripts/tool.py', 'docs/青.md', 'AGENTS.md.other']:
            with self.subTest(name=name): self.assertEqual(view / name, root / name)
        self.assertEqual(Path('/synthetic/root/fixtures').relative_to(view), Path('fixtures'))

    def test_unsafe_and_alias_logical_paths_cannot_expand_archive_scope(self):
        view = archive.ArchiveRoot('/root', '/old/guidance')
        for name in ['', '.', '..', '/AGENTS.md', './AGENTS.md', 'x/../AGENTS.md',
                     'AGENTS.md/', 'x//y', 'x\\y', 'x\ny', '\x00']:
            with self.subTest(name=name), self.assertRaises(ValueError): view / name

    def test_every_sealed_lineage_mutation_rejects_before_loading_guard(self):
        value = archive.seal(); real_read = archive.read
        for row in value['lineage']:
            target = ROOT / row['path']
            def changed(path, target=target):
                data = real_read(path)
                return data + b' ' if Path(path) == target else data
            with self.subTest(path=row['path']), patch.object(archive, 'read', side_effect=changed), \
                 patch.object(archive, 'legacy', side_effect=AssertionError('must not load')):
                with self.assertRaisesRegex(ValueError, 'archival lineage changed'):
                    archive.verify_archive()

    def test_archive_cannot_hide_a_new_unauthorized_current_guidance_change(self):
        real_read = archive.read
        def changed(path):
            data = real_read(path)
            return data + b'\nunauthorized change' if Path(path) == ROOT / 'AGENTS.md' else data
        with patch.object(archive, 'read', side_effect=changed):
            with self.assertRaisesRegex(ValueError, 'unauthorized current guidance change'):
                archive.verify_archive()

    def test_non_guidance_runtime_and_fixture_changes_still_reject(self):
        targets = [ROOT / 'shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/verification/EvidenceOutputValidator.kt',
                   ROOT / 'planning/fixtures/grammar-progress-v2-unicode/compiler-cases.json',
                   ROOT / 'scripts/diagnostics/mask_replay/grammar_progress.py']
        real_read = Path.read_bytes
        for target in targets:
            def changed(path, target=target):
                data = real_read(path)
                return data + b'\nunauthorized' if path == target else data
            with self.subTest(path=target), patch.object(Path, 'read_bytes', changed), \
                 patch('subprocess.Popen', side_effect=AssertionError('must not execute')):
                with self.assertRaises(ValueError): archive.verify_archive()

    def test_modified_route_seal_or_override_cannot_authorize_history(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'manifest.json'
            value = json.loads(archive.read(archive.MANIFEST))
            value['onlyLogicalOverride']['logicalPath'] = 'scripts/diagnostics/unicode_correction/run.py'
            path.write_text(json.dumps(value))
            with self.assertRaisesRegex(ValueError, 'route seal changed'): archive.seal(path)

    def test_missing_symlink_and_oversized_archive_inputs_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'data'; path.write_bytes(b'source')
            alias = Path(folder) / 'alias'; alias.symlink_to(path)
            with self.assertRaises(OSError): archive.read(alias)
            with self.assertRaises(OSError): archive.read(path.parent / 'missing')
            with patch.object(archive, 'MAX_BYTES', 3), self.assertRaisesRegex(ValueError, 'type/size'):
                archive.read(path)


class ArchivalSuiteTests(unittest.TestCase):
    def original_suite(self):
        module = load('test_unicode_correction', 'scripts/tests/test_unicode_correction.py')
        return unittest.TestLoader().loadTestsFromModule(module)

    def test_all_ten_frozen_test_bodies_are_retained_and_execute(self):
        original = self.original_suite()
        cases_before = [c.id() for c in routing.flatten(original)]
        active, receipt = routing.migrate(original)
        self.assertEqual(receipt['originalTestsRetained'], 10)
        self.assertEqual(receipt['discoveredTests'], receipt['executedTests'])
        self.assertEqual(receipt['skippedByRouting'], 0)
        normalized = [c.id().replace(routing.ACTIVE_MODULE + '.', routing.ORIGINAL_MODULE + '.')
                      for c in routing.flatten(active)]
        self.assertEqual(cases_before, normalized)
        result = unittest.TestResult(); active.run(result)
        self.assertEqual(result.testsRun, 10)
        self.assertEqual(result.errors, []); self.assertEqual(result.failures, []); self.assertEqual(result.skipped, [])

    def test_unrelated_and_future_cases_are_preserved_without_filtering(self):
        class FutureCase(unittest.TestCase):
            def test_future(self): pass
        future = FutureCase('test_future')
        suite = unittest.TestSuite([self.original_suite(), future])
        migrated, receipt = routing.migrate(suite)
        self.assertEqual(receipt['executedTests'], 11)
        self.assertIs(list(routing.flatten(migrated))[-1], future)

    def test_missing_duplicate_or_reordered_original_inventory_is_not_silently_skipped(self):
        cases = list(routing.flatten(self.original_suite()))
        for bad in [cases[:-1], cases + [cases[0]], [cases[0]] * 10]:
            with self.subTest(ids=[c.id() for c in bad]), self.assertRaises(ValueError):
                routing.migrate(unittest.TestSuite(bad))

    def test_active_assertion_removal_is_rejected_before_test_collection(self):
        real_read = routing.archive.read
        def changed(path):
            data = real_read(path)
            return data.replace(b'        self.assertIn(\'MIT License\', text);', b'        pass;') if Path(path) == routing.ACTIVE else data
        with patch.object(routing.archive, 'read', side_effect=changed), \
             patch.object(unittest.TestLoader, 'discover', side_effect=AssertionError('must not discover')):
            with self.assertRaisesRegex(ValueError, 'differs beyond'): routing.full_suite()

    def test_missing_active_module_is_an_error_not_an_omitted_test(self):
        real_read = routing.archive.read
        def missing(path):
            if Path(path) == routing.ACTIVE: raise FileNotFoundError(path)
            return real_read(path)
        with patch.object(routing.archive, 'read', side_effect=missing):
            with self.assertRaises(FileNotFoundError): routing.full_suite()

    def test_full_runner_propagates_a_failing_case(self):
        runner = load('canonical_python_host_suite_test', 'scripts/host-python-test-suite.py')
        class Failing(unittest.TestCase):
            def test_failure(self): self.fail('synthetic failure must remain visible')
        with patch.object(runner.routing, 'full_suite', return_value=(unittest.TestSuite([Failing('test_failure')]), {})), \
             contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(runner.main(), 1)


if __name__ == '__main__':
    unittest.main()
