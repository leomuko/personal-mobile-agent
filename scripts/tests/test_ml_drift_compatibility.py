"""Host-only guards for the configured-analysis runner; no Bazel or device execution."""
import copy
import hashlib
import importlib.util
import json
import os
import signal
import subprocess
import time
import types
from pathlib import Path
import sys
import tempfile
import zipfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/diagnostics/ml_drift_compatibility/analyze.py'
spec = importlib.util.spec_from_file_location('ml_compatibility', SCRIPT)
runner = importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
prepare_spec = importlib.util.spec_from_file_location('compatibility_prepare', SCRIPT.with_name('prepare.py'))
prepare = importlib.util.module_from_spec(prepare_spec);prepare_spec.loader.exec_module(prepare)
CANDIDATE = json.loads((runner.FIXTURES / 'candidate.json').read_text())


def freeze():
    return {'bazelPath': '/isolated/bazel-7.6.1', 'javaHome': '/isolated/java',
            'repositoryCache': '/isolated/repository-cache',
            'targets': CANDIDATE['analysisTargets']}


def same_receipt():
    return {'present': True, 'identity': {'kind': 'http_archive',
        'urls': [CANDIDATE['archive']['url']], 'sha256': CANDIDATE['archive']['sha256'],
        'type': CANDIDATE['archive']['type'],
        'strip_prefix': CANDIDATE['archive']['stripPrefix'],
        'repo_mapping': CANDIDATE['repositoryMapping']}}


class InputAndCommandTests(unittest.TestCase):
    def test_changed_freeze_blocks_before_process(self):
        with tempfile.TemporaryDirectory() as name:
            p = Path(name) / 'freeze.json';p.write_text('{}')
            with patch.object(runner.subprocess, 'Popen') as start:
                with self.assertRaisesRegex(ValueError, 'freeze changed'):runner.load_freeze(p, '0' * 64)
                start.assert_not_called()

    def test_missing_and_tampered_files_rejected(self):
        with tempfile.TemporaryDirectory() as name:
            p = Path(name) / 'source';row = {'path': str(p), 'bytes': 4, 'sha256': hashlib.sha256(b'good').hexdigest()}
            with self.assertRaises(ValueError):runner.verify_files([row])
            p.write_bytes(b'evil')
            with self.assertRaises(ValueError):runner.verify_files([row])

    def test_tree_checks_additions_modes_and_content(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name) / 'tree';root.mkdir();p = root / 'a';p.write_bytes(b'a');p.chmod(0o644)
            manifest = Path(name) / 'tree.json'
            manifest.write_text(json.dumps({'files': {'a': {'bytes': 1, 'sha256': hashlib.sha256(b'a').hexdigest(), 'mode': 0o644}}}))
            runner.verify_tree(root, manifest)
            p.chmod(0o755)
            with self.assertRaisesRegex(ValueError, 'mode changed'):runner.verify_tree(root, manifest)
            p.chmod(0o644);(root / 'extra').write_text('extra')
            with self.assertRaisesRegex(ValueError, 'inventory changed'):runner.verify_tree(root, manifest)

    def test_only_query_and_cquery_are_permitted(self):
        for verb in ['build', 'run', 'test', 'clean', 'shutdown']:
            with self.assertRaisesRegex(ValueError, 'unsupported command'):
                runner.command(freeze(), Path('/w'), Path('/o'), verb)

    def test_both_abi_configurations_are_explicit(self):
        for abi, config in runner.ABI_CONFIGS.items():
            args = runner.command(freeze(), Path('/w'), Path('/o'), 'cquery', abi)
            self.assertIn('--config=' + config, args)
            self.assertIn('--batch', args)
            self.assertIn('--nohome_rc', args)
            self.assertIn('--nosystem_rc', args)
            self.assertNotIn('build', args)
        with self.assertRaises(ValueError):runner.command(freeze(), Path('/w'), Path('/o'), 'cquery', 'armeabi-v7a')

    def test_query_cannot_accept_a_hidden_platform(self):
        with self.assertRaises(ValueError):runner.command(freeze(), Path('/w'), Path('/o'), 'query', 'arm64-v8a')


class RepositoryTests(unittest.TestCase):
    def test_absence_requires_successful_explicit_receipt(self):
        self.assertEqual(runner.repository_policy({'present': False, 'identity': None}, CANDIDATE),
                         'INSERT_AFTER_ROOT')
        for value in [None, {}, {'present': False, 'identity': {}}, {'present': 'false'}]:
            with self.assertRaises(ValueError):runner.repository_policy(value, CANDIDATE)

    def test_identical_source_can_be_reused(self):
        self.assertEqual(runner.repository_policy(same_receipt(), CANDIDATE), 'REUSE_IDENTICAL')

    def test_bazel_tuple_defaults_reuse_in_python_and_generated_helper(self):
        receipt = same_receipt()
        for key, value in runner.DEFAULT_ATTRIBUTES.items():
            receipt['identity'][key] = tuple(value) if type(value) is list else value
        self.assertEqual(runner.repository_policy(receipt, CANDIDATE), 'REUSE_IDENTICAL')
        def fail(message):raise ValueError(message)
        namespace = {'load': lambda *a: None, 'native': types.SimpleNamespace(existing_rule=lambda n: receipt['identity']),
                     'json': types.SimpleNamespace(encode=json.dumps), 'fail': fail,
                     'http_archive': lambda **kw: self.fail('identical repository was replaced')}
        exec(runner.helper(CANDIDATE, True), namespace)
        namespace['inspect_root']()
        receipt['identity']['patches'] = ('//:unapproved.patch',)
        with self.assertRaisesRegex(ValueError, 'transformation'):runner.repository_policy(receipt, CANDIDATE)
        with self.assertRaisesRegex(ValueError, 'transformation'):namespace['inspect_root']()

    def test_different_identity_or_mapping_cannot_be_shadowed(self):
        for k in ['kind', 'urls', 'sha256', 'strip_prefix', 'repo_mapping']:
            r = same_receipt();r['identity'][k] = 'changed'
            with self.assertRaisesRegex(ValueError, 'conflicting'):runner.repository_policy(r, CANDIDATE)

    def test_transforms_and_unknown_attributes_are_rejected_in_python_and_helper(self):
        for key, value in [('patch_cmds', ['touch modified']), ('patches', ['//:patch']),
                           ('build_file', '//:replacement'), ('workspace_file_content', 'changed'),
                           ('remote_file_urls', {'x': ['https://example.invalid']}), ('future_attribute', '')]:
            r = same_receipt();r['identity'][key] = value
            with self.assertRaises(ValueError):runner.repository_policy(r, CANDIDATE)
            def fail(message):raise ValueError(message)
            namespace = {'load': lambda *a: None, 'native': types.SimpleNamespace(existing_rule=lambda n: r['identity']),
                         'json': types.SimpleNamespace(encode=json.dumps), 'fail': fail,
                         'http_archive': lambda **kw: self.fail('conflicting repository was inserted')}
            exec(runner.helper(CANDIDATE, True), namespace)
            with self.assertRaisesRegex(ValueError, 'CONFLICT'):namespace['inspect_root']()

    def test_missing_or_duplicated_receipt_is_not_absence(self):
        receipt = runner.MARKER + json.dumps({'present': False, 'identity': None})
        self.assertFalse(runner.receipt_from('DEBUG file: ' + receipt)['present'])
        for text in ['', receipt + '\n' + receipt]:
            with self.assertRaises(ValueError):runner.receipt_from(text)

    def test_overlay_is_after_existing_root_and_base_bytes_unchanged(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name);source = root / 'sdk';source.mkdir();(source / 'WORKSPACE').write_text('tf_workspace0()\n')
            runner.stage_workspace(source, root / 'attempt', CANDIDATE, True)
            text = (root / 'attempt/WORKSPACE').read_text()
            self.assertLess(text.index('tf_workspace0'), text.index('inspect_root()'))
            self.assertEqual((source / 'WORKSPACE').read_text(), 'tf_workspace0()\n')
            helper = (root / 'attempt/edge_ml_drift_analysis.bzl').read_text()
            self.assertIn('native.existing_rule', helper)
            self.assertNotIn('MODULE.bazel', helper)
            self.assertNotIn('override_repository', helper)

    def test_existing_attempt_is_preserved(self):
        with tempfile.TemporaryDirectory() as name:
            p = Path(name)
            with self.assertRaisesRegex(ValueError, 'attempt already exists'):
                runner.execute({}, CANDIDATE, p)


class ProcessTests(unittest.TestCase):
    def test_cleanup_uses_one_shared_deadline_for_all_waits(self):
        clock = [100.0]
        class Hung:
            pid = 99999
            def poll(self):return None
            def wait(self, timeout):
                clock[0] += timeout
                raise subprocess.TimeoutExpired('fixture', timeout)
        with patch.object(runner.time, 'monotonic', side_effect=lambda: clock[0]):
            with patch.object(runner.os, 'killpg'):
                with self.assertRaisesRegex(ValueError, 'shared deadline'):
                    runner.cleanup_owned(Hung(), 15)
        self.assertLessEqual(clock[0] - 100, 15)

    def test_simple_owned_host_process_completes(self):
        with tempfile.TemporaryDirectory() as name:
            p = Path(name)
            r = runner.run_owned([sys.executable, '-c', 'print("fixture")'], p, p / 'run', os.environ.copy(), 2, 1024)
            self.assertEqual(r['exitCode'], 0)
            self.assertTrue(r['cleanupConfirmed'])

    def test_timed_out_host_process_is_reaped(self):
        with tempfile.TemporaryDirectory() as name:
            p = Path(name)
            r = runner.run_owned([sys.executable, '-c', 'import time;time.sleep(60)'], p, p / 'run', os.environ.copy(), .1, 1024)
            self.assertEqual(r['classification'], 'TIMED_OUT')
            self.assertTrue(r['cleanupConfirmed'])

    def test_fast_oversized_output_is_not_a_success(self):
        with tempfile.TemporaryDirectory() as name:
            p = Path(name)
            r = runner.run_owned([sys.executable, '-c', 'print("x"*100000)'], p, p / 'run', os.environ.copy(), 2, 1024)
            self.assertEqual(r['classification'], 'LOG_LIMIT')
            self.assertLessEqual((p / 'run.stdout.log').stat().st_size + (p / 'run.stderr.log').stat().st_size, 1024)

    def test_late_observed_completion_cannot_bypass_deadline(self):
        original = runner.selectors.DefaultSelector
        class Delayed(original):
            def select(self, timeout=None):
                time.sleep(.05)
                return super().select(0)
        with tempfile.TemporaryDirectory() as name, patch.object(runner.selectors, 'DefaultSelector', Delayed):
            p = Path(name)
            r = runner.run_owned([sys.executable, '-c', 'print("done")'], p, p / 'run', os.environ.copy(), .025, 1024)
            self.assertEqual(r['classification'], 'TIMED_OUT')

    def test_signal_during_spawn_assignment_is_reaped(self):
        original = runner.subprocess.Popen;children = []
        def spawning(*args, **kwargs):
            process = original(*args, **kwargs);children.append(process)
            os.kill(os.getpid(), signal.SIGTERM)
            return process
        with tempfile.TemporaryDirectory() as name, patch.object(runner.subprocess, 'Popen', spawning):
            p = Path(name)
            with self.assertRaisesRegex(ValueError, 'interrupted'):
                runner.run_owned([sys.executable, '-c', 'import time;time.sleep(60)'], p, p / 'run', os.environ.copy(), 2, 1024)
        self.assertIsNotNone(children[0].returncode)
        with self.assertRaises(ProcessLookupError):os.killpg(children[0].pid, 0)

    def test_supervisor_sigterm_reaps_child(self):
        with tempfile.TemporaryDirectory() as name:
            p = Path(name);pidfile = p / 'child.pid'
            child = 'import os,time;open(' + repr(str(pidfile)) + ',"w").write(str(os.getpid()));time.sleep(60)'
            supervisor = ('import importlib.util,os;from pathlib import Path;'
                's=importlib.util.spec_from_file_location("owned",' + repr(str(SCRIPT)) + ');'
                'm=importlib.util.module_from_spec(s);s.loader.exec_module(m);'
                'm.run_owned(' + repr([sys.executable, '-c', child]) + ',Path(' + repr(name) + '),'
                'Path(' + repr(str(p / 'run')) + '),os.environ.copy(),30,1024)')
            process = subprocess.Popen([sys.executable, '-c', supervisor], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            childpid = None
            try:
                until = time.monotonic() + 5
                while not pidfile.exists() and time.monotonic() < until:time.sleep(.01)
                self.assertTrue(pidfile.exists());childpid = int(pidfile.read_text())
                process.terminate();process.communicate(timeout=10)
                with self.assertRaises(ProcessLookupError):os.kill(childpid, 0)
            finally:
                if process.poll() is None:process.kill();process.communicate()
                if childpid is not None:
                    try:os.killpg(childpid, signal.SIGKILL)
                    except ProcessLookupError:pass

    def test_old_logs_cannot_be_replaced(self):
        with tempfile.TemporaryDirectory() as name:
            p = Path(name);(p / 'run.stdout.log').write_text('preserved')
            with patch.object(runner.subprocess, 'Popen') as process:
                with self.assertRaises(FileExistsError):
                    runner.run_owned(['unused'], p, p / 'run', {}, 2, 1024)
                process.assert_not_called()
            self.assertEqual((p / 'run.stdout.log').read_text(), 'preserved')


class PreparationTests(unittest.TestCase):
    def test_changed_source_catalog_blocks_before_network(self):
        with tempfile.TemporaryDirectory() as name:
            p = Path(name);catalog = p / 'catalog.json';catalog.write_text('{}')
            with patch.object(prepare.urllib.request, 'urlopen') as fetch:
                with self.assertRaisesRegex(ValueError, 'catalog changed'):
                    prepare.collect_sdk(catalog, '0' * 64, p / 'output')
                fetch.assert_not_called()
            self.assertFalse((p / 'output').exists())

    def test_incomplete_source_tree_cannot_be_collected_as_full_sdk(self):
        with tempfile.TemporaryDirectory() as name:
            p = Path(name);catalog = p / 'catalog.json'
            catalog.write_text(json.dumps({'sdkRevision': '5e58e9a0aef7abf7091207a8b1d1063a1c800f08', 'sdkGitBlobs': {}}))
            with self.assertRaisesRegex(ValueError, 'incomplete SDK tree'):
                prepare.collect_sdk(catalog, prepare.sha(catalog), p / 'output')
            self.assertFalse((p / 'output').exists())

    def test_changed_archive_rejected_without_destination(self):
        with tempfile.TemporaryDirectory() as name:
            p = Path(name);archive = p / 'input.zip';archive.write_bytes(b'bad')
            with self.assertRaisesRegex(ValueError, 'archive changed'):
                prepare.unpack(archive, '0' * 64, p / 'output')
            self.assertFalse((p / 'output').exists())

    def test_traversal_never_writes_outside_new_destination(self):
        with tempfile.TemporaryDirectory() as name:
            p = Path(name);archive = p / 'input.zip'
            with zipfile.ZipFile(archive, 'w') as z:z.writestr('../escape', 'no')
            with self.assertRaisesRegex(ValueError, 'unsafe archive path'):
                prepare.unpack(archive, prepare.sha(archive), p / 'output')
            self.assertFalse((p / 'escape').exists())

    def test_preparation_preserves_existing_attempt(self):
        with tempfile.TemporaryDirectory() as name:
            p = Path(name);archive = p / 'input.zip'
            with zipfile.ZipFile(archive, 'w') as z:z.writestr('root/file', 'source')
            out = p / 'output';prepare.unpack(archive, prepare.sha(archive), out)
            with self.assertRaisesRegex(ValueError, 'destination exists'):
                prepare.unpack(archive, prepare.sha(archive), out)
            self.assertEqual((out / 'root/file').read_text(), 'source')

    def test_external_symlink_is_not_frozen_as_a_toolchain(self):
        with tempfile.TemporaryDirectory() as name:
            p = Path(name);out = p / 'tree';out.mkdir();outside = p / 'outside';outside.write_text('outside')
            (out / 'link').symlink_to(outside)
            with self.assertRaisesRegex(ValueError, 'external toolchain symlink'):prepare.inventory(out)


if __name__ == '__main__':unittest.main()
