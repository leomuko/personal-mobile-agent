import ast
import runpy
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "verify-chat-budget.py"
verifier = runpy.run_path(str(SCRIPT))
schema = verifier["sentencepiece_model_pb2"]


class ChatBudgetChecks(unittest.TestCase):
    def test_no_optimizable_assertions(self):
        self.assertFalse(any(isinstance(node, ast.Assert) for node in ast.walk(ast.parse(SCRIPT.read_text()))))

    def test_both_hash_checks_fail_closed(self):
        for label in ("model", "tokenizer"):
            with self.subTest(label=label), self.assertRaisesRegex(SystemExit, "SHA-256 mismatch"):
                verifier["verify_digest"]("incorrect", "expected", label)

    def test_every_tokenizer_contract_is_enforced(self):
        def valid():
            spec = schema.ModelProto()
            spec.trainer_spec.model_type = schema.TrainerSpec.BPE
            spec.trainer_spec.byte_fallback = True
            spec.normalizer_spec.name = "identity"
            spec.normalizer_spec.add_dummy_prefix = False
            spec.normalizer_spec.remove_extra_whitespaces = False
            spec.normalizer_spec.escape_whitespaces = True
            return spec
        verifier["validate_tokenizer"](valid())
        for field, value, target in [
            ("model_type", schema.TrainerSpec.UNIGRAM, "trainer_spec"),
            ("byte_fallback", False, "trainer_spec"),
            ("name", "nmt_nfkc", "normalizer_spec"),
            ("precompiled_charsmap", b"bad", "normalizer_spec"),
            ("add_dummy_prefix", True, "normalizer_spec"),
            ("remove_extra_whitespaces", True, "normalizer_spec"),
            ("escape_whitespaces", False, "normalizer_spec"),
        ]:
            with self.subTest(field=field), self.assertRaises(SystemExit):
                spec = valid()
                setattr(getattr(spec, target), field, value)
                verifier["validate_tokenizer"](spec)

    def test_budget_boundary_and_overflow(self):
        verifier["check_budget"](100, 100, "boundary")
        for count in (101, -1):
            with self.assertRaisesRegex(SystemExit, "token bound"):
                verifier["check_budget"](count, 100, "overflow")

    def test_wrong_artifact_is_rejected_with_python_optimization(self):
        with tempfile.TemporaryDirectory() as folder:
            model = Path(folder) / "synthetic.litertlm"
            model.write_bytes(b"synthetic wrong model")
            for flags in ([], ["-O"]):
                result = subprocess.run([sys.executable, *flags, str(SCRIPT), "--model", str(model)],
                                        capture_output=True, text=True, timeout=10)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("model SHA-256 mismatch", result.stderr)
                self.assertNotIn('"status": "passed"', result.stdout)


if __name__ == "__main__":
    unittest.main()
