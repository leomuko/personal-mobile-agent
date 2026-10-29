"""Static root-resolution limits and provenance checks; no Starlark execution."""
import copy
import hashlib
import importlib.util
import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/diagnostics/android_runtime_resolution/trace.py'
spec = importlib.util.spec_from_file_location('root_resolution_trace', SCRIPT)
trace = importlib.util.module_from_spec(spec);spec.loader.exec_module(trace)


def sample():
    sdk = 'google-ai-edge/LiteRT-LM';tf = 'tensorflow/tensorflow';alt = 'google-ai-edge/ml-drift'
    s = {(sdk, 'WORKSPACE'): '\n'.join('tf_workspace' + str(n) + '()' for n in [3, 2, 1, 0]),
         (sdk, '.bazelrc'): 'common --noenable_bzlmod\n',
         (sdk, 'runtime/core/BUILD'): 'alias(name="engine_impl", actual=":engine_advanced_impl")',
         (sdk, 'runtime/components/constrained_decoding/constraint_provider_factory.cc'):
             'return LlgConstraintProvider::Create(tokenizer, config);',
         (tf, 'third_party/repo.bzl'): 'if native.existing_rule(name):\n    return\n',
         (alt, 'MODULE.bazel'): 'module(bazel_compatibility = [">=8.0.0"])',
         (alt, '.bazelversion'): '8.0.0\n',
         (alt, 'ml_drift/cl/BUILD'): 'cc_library(name="opencl_wrapper")'}
    for n in range(4):
        s[tf, 'tensorflow/workspace' + str(n) + '.bzl'] = ''
        s[tf, 'third_party/xla/workspace' + str(n) + '.bzl'] = ''
    s[tf, 'tensorflow/workspace3.bzl'] = ('tf_vendored(name="xla", path="third_party/xla")\n'
        'tf_vendored(name="tsl", path="third_party/xla/third_party/tsl")\n')
    return s


class ResolutionTests(unittest.TestCase):
    def test_missing_definition_stays_unresolved_not_build_failed(self):
        r = trace.inspect_sources(sample())
        self.assertEqual(r['mlDriftInDirectInspectedDeclarations']['status'], 'UNRESOLVED')
        self.assertFalse(r['configuredRootResolutionProven'])
        self.assertFalse(r['downstreamMacroClosureProven'])
        self.assertFalse(r['buildRecipeReady'])

    def test_ambiguous_repository_does_not_select_last_writer(self):
        defs = [{'name': 'ml_drift', 'revision': 'first'}, {'name': 'ml_drift', 'revision': 'second'}]
        self.assertEqual(trace.observed_repository(defs, 'ml_drift')['status'], 'AMBIGUOUS')

    def test_explicit_mapping_is_observed_but_not_configured_proof(self):
        r = trace.observed_repository([{'name': 'mapped', 'revision': 'rev', 'sha256': 'hash'}],
                                     'ml_drift', {'ml_drift': 'mapped'})
        self.assertEqual(r['status'], 'LITERAL_ONLY_NOT_CONFIGURED')
        self.assertEqual(r['name'], 'mapped')

    def test_changed_root_macro_order_blocks_result(self):
        s = sample();s['google-ai-edge/LiteRT-LM', 'WORKSPACE'] = 'tf_workspace0()\ntf_workspace3()'
        with self.assertRaisesRegex(ValueError, 'order changed'):trace.inspect_sources(s)

    def test_changed_vendored_path_blocks_result(self):
        s = sample();k = ('tensorflow/tensorflow', 'tensorflow/workspace3.bzl')
        s[k] = s[k].replace('path="third_party/xla"', 'path="other"')
        with self.assertRaisesRegex(ValueError, 'selection changed'):trace.inspect_sources(s)

    def test_missing_or_duplicated_target_blocks_result(self):
        for text in ['', 'alias(name="engine_impl")\nalias(name="engine_impl")']:
            with self.assertRaisesRegex(ValueError, 'missing/ambiguous'):trace.named_call(text, 'engine_impl')

    def test_engine_alias_is_not_a_missing_source_target(self):
        self.assertTrue(trace.inspect_sources(sample())['engineImplAliasExists'])

    def test_new_direct_repository_requires_review_not_automatic_adoption(self):
        s = sample();s['tensorflow/tensorflow', 'tensorflow/workspace2.bzl'] = 'http_archive(name="ml_drift")'
        with self.assertRaisesRegex(ValueError, 'review required'):trace.inspect_sources(s)

    def test_alternative_and_emulator_direction_never_authorize_execution(self):
        r = trace.inspect_sources(sample())
        self.assertFalse(r['alternative']['adopted'])
        self.assertFalse(r['emulatorExecutionAuthorized'])
        self.assertTrue(r['s24Deferred'])
        self.assertFalse(r['semanticSearchEnabled'])


class InputTests(unittest.TestCase):
    def test_new_catalog_preserves_previous_lock(self):
        value, previous = trace.load_inputs()
        self.assertEqual(len(value['files']), 16)
        self.assertFalse(value['observedAlternativeAdopted'])
        self.assertEqual(previous.INPUTS_SHA,
                         '57145ce62d14f232d202f79919b0e841deec4c14df7382296330760fda7ebd81')

    def test_changed_catalog_blocks_before_cache_or_network(self):
        with tempfile.TemporaryDirectory() as name:
            p = Path(name) / 'changed.json';p.write_bytes(trace.CATALOG.read_bytes() + b' ')
            with patch.object(trace, 'CATALOG', p):
                with patch.object(trace.urllib.request, 'urlopen') as fetch:
                    with self.assertRaisesRegex(ValueError, 'catalog changed'):trace.load_inputs()
                    fetch.assert_not_called()

    def test_changed_source_and_git_identity_rejected(self):
        raw = b'source';r = {'path': 'BUILD', 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(),
                           'gitBlob': hashlib.sha1(b'blob 6\0' + raw).hexdigest()}
        trace.verify_blob(r, raw)
        with self.assertRaises(ValueError):trace.verify_blob(r, b'wrong!')
        r['gitBlob'] = '0' * 40
        with self.assertRaisesRegex(ValueError, 'Git blob changed'):trace.verify_blob(r, raw)

    def test_missing_source_never_publishes_report(self):
        with tempfile.TemporaryDirectory() as name:
            report = Path(name) / 'report.json'
            with patch.object(sys, 'argv', [str(SCRIPT), 'inspect', '--cache', name, '--report', str(report)]):
                with patch.object(sys, 'stderr', io.StringIO()):self.assertEqual(trace.main(), 1)
            self.assertFalse(report.exists())

    def test_invalid_owner_and_path_rejected(self):
        for owner, path in [('other/repo', 'BUILD'), ('tensorflow/tensorflow', '../BUILD')]:
            with self.assertRaises(ValueError):trace.target(Path('/tmp/cache'), {'repository': owner, 'path': path})


if __name__ == '__main__':
    unittest.main()
