"""Host-only regressions for the separately versioned Unicode investigation."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    'unicode_boundary', ROOT / 'scripts/diagnostics/unicode_boundary/investigate.py')
boundary = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(boundary)
BYTE_PROBE = ROOT / 'build/unicode-boundary-v1/prepared-final/byte-workspace/target/release/unicode-byte-probe'


class IdentityAndOracleTests(unittest.TestCase):
    def test_frozen_inputs_keep_reference_valid_escapes_and_failed_criteria(self):
        cases = boundary.verify_inputs()
        self.assertEqual(len(cases['cases']), 18)
        protocol = json.loads((boundary.FIXTURES / 'protocol.json').read_text())
        self.assertFalse(protocol['changeAcceptanceCriteria'])
        self.assertTrue(protocol['preserveCandidateFailure'])
        self.assertFalse(protocol['deviceExecution'])

    def test_changed_fixture_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            copy = Path(folder) / 'fixtures'
            shutil.copytree(boundary.FIXTURES, copy)
            (copy / 'cases.json').write_text('{}')
            with self.assertRaisesRegex(ValueError, 'fixture changed'):
                boundary.verify_inputs(copy)

    def test_changed_lock_cannot_authorize_replacement(self):
        with tempfile.TemporaryDirectory() as folder:
            copy = Path(folder) / 'fixtures'
            shutil.copytree(boundary.FIXTURES, copy)
            lock = copy / 'investigation-lock.json'
            lock.write_bytes(lock.read_bytes() + b' ')
            with self.assertRaisesRegex(ValueError, 'lock changed'):
                boundary.verify_inputs(copy)

    def test_identity_failure_precedes_any_process(self):
        with patch.object(boundary, 'verify_inputs', side_effect=ValueError('tampered')):
            with patch.object(subprocess, 'Popen') as process:
                with self.assertRaisesRegex(ValueError, 'tampered'):
                    boundary.investigate(Path('unused'), Path('unused'), Path('unused'))
                process.assert_not_called()

    def test_reference_oracle_accepts_both_unicode_spellings(self):
        cases = boundary.verify_inputs()
        schema = (boundary.FIXTURES / 'minimal-string.schema.json').read_text()
        boundary.check_reference(cases, schema)
        by_id = {case['id']: case for case in cases['cases']}
        for literal, escaped in [('literal-ascii', 'escaped-ascii'),
                                 ('literal-bmp', 'escaped-bmp'),
                                 ('literal-euro', 'escaped-euro'),
                                 ('literal-astral', 'escaped-surrogate-pair'),
                                 ('literal-combining', 'escaped-combining')]:
            self.assertEqual(json.loads(by_id[literal]['jsonText']),
                             json.loads(by_id[escaped]['jsonText']))

    def test_alternate_tokenization_requires_exact_piece_and_roundtrip(self):
        class Exact:
            def piece_to_id(self, char): return ord(char)
            def id_to_piece(self, token): return chr(token)
            def decode(self, ids): return ''.join(chr(token) for token in ids)
        self.assertEqual(boundary.literal_ids('"\\u9752"', Exact()),
                         list(map(ord, '"\\u9752"')))
        with self.assertRaisesRegex(ValueError, 'ASCII'):
            boundary.literal_ids('青', Exact())
        class Missing(Exact):
            def id_to_piece(self, token): return '<unk>'
        with self.assertRaisesRegex(ValueError, 'missing literal'):
            boundary.literal_ids('0', Missing())
        class WrongDecode(Exact):
            def decode(self, ids): return 'changed'
        with self.assertRaisesRegex(ValueError, 'roundtrip'):
            boundary.literal_ids('0', WrongDecode())


class ByteProbeIntegrationTests(unittest.TestCase):
    def run_probe(self, payload):
        return subprocess.run([str(BYTE_PROBE)], input=payload, capture_output=True, timeout=10)

    def test_complete_frozen_corpus_matches_source_prediction_not_qualification(self):
        cases = boundary.verify_inputs()
        result = self.run_probe(json.dumps(cases).encode())
        self.assertEqual(result.returncode, 0, result.stderr)
        observed = json.loads(result.stdout)
        self.assertEqual(len(observed['cases']), 18)
        for expected, actual in zip(cases['cases'], observed['cases']):
            self.assertEqual(actual['id'], expected['id'])
            self.assertEqual(actual['accepted'], expected['sourcePredictedByteAcceptance'])
        escaped = next(case for case in observed['cases'] if case['id'] == 'escaped-bmp')
        self.assertEqual(escaped['firstDeadByte'], 3)
        self.assertEqual(observed['prefixes'][0]['allowedNextBytes'], [ord('0')])

    def test_malformed_input_and_count_bounds_return_controlled_failure(self):
        for payload in [b'{', b'{}', b'\xff',
                        json.dumps({'cases': [{}] * 65, 'prefixes': []}).encode(),
                        json.dumps({'cases': [], 'prefixes': ['x'] * 17}).encode()]:
            with self.subTest(payload=payload[:30]):
                result = self.run_probe(payload)
                self.assertEqual(result.returncode, 2)
                self.assertEqual(result.stdout, b'')
                self.assertTrue(result.stderr)

    def test_large_input_is_bounded_and_not_processed(self):
        result = self.run_probe(b' ' * 1_048_577)
        self.assertEqual(result.returncode, 2)
        self.assertIn(b'input bound', result.stderr)
        self.assertEqual(result.stdout, b'')

    def test_all_ascii_escapes_match_control_only_domain(self):
        # Exhaustive finite-domain check, independent of SentencePiece.
        # Batches obey the diagnostic's 64-case cap.
        for first in (0, 64):
            payload = {'cases': [{'id': str(code), 'jsonText': '"\\u%04x"' % code}
                                 for code in range(first, first + 64)], 'prefixes': []}
            result = self.run_probe(json.dumps(payload).encode())
            self.assertEqual(result.returncode, 0, result.stderr)
            for actual in json.loads(result.stdout)['cases']:
                code = int(actual['id'])
                self.assertEqual(actual['accepted'], code < 32 or code == 127)


if __name__ == '__main__':
    unittest.main()
