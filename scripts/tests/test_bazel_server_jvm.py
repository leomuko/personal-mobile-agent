"""Server-JVM admission tests; synthetic archives only, no Java/Bazel execution."""
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / 'scripts/diagnostics/bazel_server_jvm_v1'
spec = importlib.util.spec_from_file_location('corrected_analysis', HERE / 'analyze.py')
runner = importlib.util.module_from_spec(spec); spec.loader.exec_module(runner)
jvm = runner.jvm


def zip_bytes(files):
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w') as z:
        for name, (data, mode) in files.items():
            i = zipfile.ZipInfo(name); i.create_system = 3; i.external_attr = mode << 16
            z.writestr(i, data)
    return out.getvalue()


class ServerJvmTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.release = b'JAVA_VERSION="21.0.5"\nMODULES="java.base"\n'
        self.jar = zip_bytes({jvm.CLASS: (b'\xca\xfe\xba\xbe' + struct.pack('>HH', 0, 65), 0o100644)})
        self.archive = self.root / 'bazel'
        self.archive.write_bytes(zip_bytes({
            'A-server.jar': (self.jar, 0o100644),
            jvm.PREFIX + 'release': (self.release, 0o100644),
            jvm.PREFIX + 'bin/java': (b'not an executable', 0o100755),
            jvm.PREFIX + 'lib/modules': (b'pinned modules', 0o100644)}))
        for name, value in [('BAZEL_SHA', jvm.sha(self.archive)),
                            ('JAR_SHA', hashlib.sha256(self.jar).hexdigest()),
                            ('RELEASE_SHA', hashlib.sha256(self.release).hexdigest())]:
            p = patch.object(jvm, name, value); p.start(); self.addCleanup(p.stop)
        self.jdk = self.root / 'private-jdk'; self.receipt = self.root / 'receipt.json'

    def stage(self):
        return jvm.stage(self.archive, self.jdk, self.receipt)

    def test_valid_java21_admission_binds_every_entry_and_permission(self):
        identity = self.stage()
        self.assertEqual(jvm.inventory(self.jdk), identity['entries'])
        self.assertEqual(jvm.preflight(self.archive, self.jdk, self.receipt)['javaVersion'], '21.0.5')
        self.assertEqual(identity['serverClassMajor'], 65)
        self.assertEqual(identity['entries']['bin/java']['mode'], 0o755)

    def test_java17_is_rejected_before_native_launch(self):
        self.stage(); (self.jdk / 'release').write_text('JAVA_VERSION="17.0.20.1"\n')
        with patch.object(runner.base, 'run_owned') as launch:
            with self.assertRaisesRegex(ValueError, 'incompatible server Java'):
                runner.execute({'bazelPath': str(self.archive), 'javaHome': str(self.jdk),
                                'serverJdkReceipt': str(self.receipt)}, {}, self.root / 'attempt')
            launch.assert_not_called()
        self.assertFalse((self.root / 'attempt').exists())

    def test_malformed_unknown_and_duplicate_metadata_rejected(self):
        for raw in [b'', b'JAVA_VERSION=21\n', b'JAVA_VERSION="x"\n',
                    b'JAVA_VERSION="21.0.5"\nJAVA_VERSION="17"\n', b'\xff']:
            with self.subTest(raw=raw), self.assertRaises((ValueError, UnicodeError)):
                jvm.metadata_version(raw)

    def test_class_header_and_preview_rejected(self):
        for raw in [b'', b'badmagic' + b'0' * 8,
                    b'\xca\xfe\xba\xbe' + struct.pack('>HH', 65535, 65)]:
            with self.assertRaises(ValueError): jvm.class_major(raw)

    def test_changed_bazel_blocks_before_staging(self):
        self.archive.write_bytes(self.archive.read_bytes() + b'tamper')
        with self.assertRaisesRegex(ValueError, 'Bazel executable changed'): self.stage()
        self.assertFalse(self.jdk.exists())

    def test_changed_server_jar_rejected_even_with_new_archive_checksum(self):
        with patch.object(jvm, 'JAR_SHA', '0' * 64):
            with self.assertRaisesRegex(ValueError, 'server JAR changed'): self.stage()

    def test_missing_jdk_file_rejected(self):
        self.stage(); (self.jdk / 'lib/modules').unlink()
        with self.assertRaisesRegex(ValueError, 'changed/incomplete'):
            jvm.preflight(self.archive, self.jdk, self.receipt)

    def test_changed_jdk_file_rejected(self):
        self.stage(); (self.jdk / 'lib/modules').write_bytes(b'damaged')
        with self.assertRaisesRegex(ValueError, 'changed/incomplete'):
            jvm.preflight(self.archive, self.jdk, self.receipt)

    def test_changed_file_and_directory_permissions_rejected(self):
        self.stage()
        for name, mode, original in [('bin/java', 0o644, 0o755), ('bin', 0o700, 0o755)]:
            p = self.jdk / name; p.chmod(mode)
            with self.assertRaisesRegex(ValueError, 'changed/incomplete'):
                jvm.preflight(self.archive, self.jdk, self.receipt)
            p.chmod(original)

    def test_incomplete_receipt_rejected_even_if_files_exist(self):
        self.stage(); value = json.loads(self.receipt.read_text()); del value['entries']['lib/modules']
        self.receipt.write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError, 'inventory/provenance changed'):
            jvm.preflight(self.archive, self.jdk, self.receipt)

    def test_extra_file_and_external_symlink_rejected(self):
        self.stage(); p = self.jdk / 'extra'; p.write_text('new')
        with self.assertRaises(ValueError): jvm.preflight(self.archive, self.jdk, self.receipt)
        p.unlink(); p.symlink_to(self.archive)
        with self.assertRaisesRegex(ValueError, 'escaping JDK symlink'):
            jvm.preflight(self.archive, self.jdk, self.receipt)

    def test_prior_preparation_is_never_overwritten(self):
        self.stage(); original = self.receipt.read_bytes()
        with self.assertRaisesRegex(ValueError, 'preserve previous'): self.stage()
        self.assertEqual(self.receipt.read_bytes(), original)

    def test_archive_traversal_is_rejected(self):
        with zipfile.ZipFile(self.archive, 'a') as z:
            z.writestr(jvm.PREFIX + '../outside', b'no')
        with patch.object(jvm, 'BAZEL_SHA', jvm.sha(self.archive)):
            with self.assertRaisesRegex(ValueError, 'unsafe JDK archive path'): self.stage()

    def test_missing_receipt_prevents_main_from_executing(self):
        with patch.object(runner, 'load_freeze', side_effect=ValueError('missing receipt')):
            with patch.object(runner, 'execute') as launch:
                self.assertEqual(runner.main(['--freeze', '/missing', '--freeze-sha256', '0' * 64,
                                              '--attempt', str(self.root / 'attempt')]), 1)
                launch.assert_not_called()

    def test_server_selection_keeps_compilation_environment_and_both_profiles(self):
        f = {'bazelPath': '/private/bazel', 'javaHome': '/private/java21',
             'repositoryCache': '/private/cache', 'targets': ['//:target'],
             'environment': {'JAVA_HOME': '/unchanged/java17'}}
        original = copy.deepcopy(f)
        for abi in runner.base.ABI_CONFIGS:
            args = runner.command(f, Path('/workspace'), Path('/output'), 'cquery', abi)
            self.assertIn('--server_javabase=/private/java21', args)
            self.assertIn('--config=' + runner.base.ABI_CONFIGS[abi], args)
            self.assertTrue(all(field in ' '.join(args) for field in runner.JAVA_FIELDS))
        self.assertEqual(f, original)


if __name__ == '__main__':
    unittest.main()
