"""Source/fixture and typed-oracle checks for the one host compiler candidate."""
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / 'scripts/diagnostics/unicode_correction'
def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    value = importlib.util.module_from_spec(spec);spec.loader.exec_module(value)
    return value
candidate = load('unicode_correction_runner', 'run.py')
fixtures = load('unicode_correction_fixtures', 'fixtures.py')

class InputTests(unittest.TestCase):
    def test_frozen_fixtures_reproduce_without_scores(self):
        candidate.verify_inputs()
        self.assertEqual(fixtures.generate(), json.loads((candidate.FIXTURES / 'compiler-cases.json').read_text()))
    def test_changed_lock_cannot_authorize_inputs(self):
        with tempfile.TemporaryDirectory() as name:
            target = Path(name) / 'fixtures';shutil.copytree(candidate.FIXTURES, target)
            p = target / 'preparation-lock.json';p.write_bytes(p.read_bytes() + b' ')
            with self.assertRaisesRegex(ValueError, 'lock changed'):candidate.verify_inputs(target)
    def test_changed_cases_block_before_process(self):
        with tempfile.TemporaryDirectory() as name:
            target = Path(name) / 'fixtures';shutil.copytree(candidate.FIXTURES, target)
            (target / 'compiler-cases.json').write_text('{}')
            with patch.object(candidate.broker.subprocess, 'Popen') as native:
                with self.assertRaisesRegex(ValueError, 'input changed'):candidate.verify_inputs(target)
                native.assert_not_called()
    def test_invalid_identity_blocks_prepare_before_subprocess(self):
        with patch.object(candidate, 'verify_inputs', side_effect=ValueError('identity')):
            with patch.object(candidate.broker.subprocess, 'Popen') as native:
                with self.assertRaisesRegex(ValueError, 'identity'):candidate.prepare(Path('unused'), Path('unused'))
                native.assert_not_called()
    def test_generator_preserves_decoded_values_and_scalar_domain(self):
        for case in fixtures.generate()['cases']:
            if case['kind'] == 'unicode':
                value = json.loads(case['jsonText'])
                self.assertFalse(any(0xd800 <= ord(c) <= 0xdfff for c in value))
                self.assertEqual(candidate.oracle(case), case['expected'])
    def test_surrogates_are_not_accepted_by_parse_success_alone(self):
        case = {'jsonText': '"\\ud800"', 'schema': fixtures.schema()}
        self.assertIsInstance(json.loads(case['jsonText']), str)
        self.assertFalse(candidate.oracle(case))
    def test_scalar_count_is_distinct_from_utf16_and_graphemes(self):
        for text, expected in [('"🙂"', True), ('"\\ud83d\\ude42"', True), ('"é"', False), ('"🙂A"', False)]:
            self.assertEqual(candidate.oracle({'jsonText': text, 'schema': fixtures.schema(1)}), expected)
    def test_exact_indexed_patch_cannot_apply_to_wrong_context_or_path(self):
        header = b'--- vendor/llguidance-1.3.0/src/json/compiler.rs\n+++ vendor/llguidance-1.3.0/src/json/compiler.rs\n'
        change = header + b'@@ -2 +2 @@\n-old\n+new\n'
        self.assertEqual(candidate.apply_patch(b'before\nold\nafter\n', change), b'before\nnew\nafter\n')
        with self.assertRaisesRegex(ValueError, 'context'):candidate.apply_patch(b'before\nwrong\nafter\n', change)
        with self.assertRaises(ValueError):candidate.apply_patch(b'old\n', change.replace(b'vendor/', b'../'))
        with self.assertRaisesRegex(ValueError, 'counts'):candidate.apply_patch(b'before\nold\nafter\n', change.replace(b'@@ -2 +2 @@', b'@@ -2,2 +2 @@'))
    def test_candidate_changes_only_explicit_opt_in(self):
        for name in ['keyword-sentinel', 'evidence-output']:
            old = json.loads((ROOT / 'planning/fixtures/grammar-progress-v1' / (name + '.no-whitespace.schema.json')).read_text())
            new = json.loads((candidate.FIXTURES / (name + '.schema.json')).read_text())
            self.assertIs(new['x-guidance'].pop('json_allow_general_unicode_escapes'), True)
            self.assertEqual(new, old)
    def test_upstream_license_and_provenance_are_retained(self):
        text = (HERE / 'LICENSE.upstream').read_text()
        self.assertIn('MIT License', text);self.assertIn('Microsoft Corporation', text)
        self.assertIn('7aa1c3b67ec97cee62dc23123204a4351e9d40d4', (HERE / 'PATCH.unicode-value-strings').read_text())

if __name__ == '__main__':unittest.main()
