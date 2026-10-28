import ast
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/verify-constrained-decoding.py"
v = runpy.run_path(str(SCRIPT))


class ConstrainedPrerequisites(unittest.TestCase):
    def test_frozen_hashes_and_all_reference_checks(self):
        v["frozen"](ROOT)
        _, report = v["reference_checks"](ROOT)
        self.assertEqual(report["mutationInstancesPerSchema"], 34)

    def test_integral_decimal_and_unicode_length_follow_draft7(self):
        checks = v["read_json"](ROOT / v["FIXTURES"] / "host-keyword-checks.json")
        validator, _ = v["reference_checks"](ROOT)
        for name in ("integral-decimal-is-an-integer", "unicode-byte-overflow"):
            case = next(x for x in checks["cases"] if x["id"] == name)
            self.assertTrue(validator.is_valid(v["materialize"](checks["baseOutput"], case)))

    def test_mutations_are_independent(self):
        base = {"d": [{"i": "a"}]}
        self.assertEqual(v["materialize"](base, {"mutation": {"op": "remove", "path": "/d/0/i"}}), {"d": [{}]})
        self.assertEqual(base, {"d": [{"i": "a"}]})

    def test_any_single_token_counterexample_blocks_proof(self):
        validator, _ = v["reference_checks"](ROOT)
        valid = '{"o":"M","d":[{"i":"a","s":"M","e":[]}]}'
        for pieces, decoded in (([valid], ["invalid"]), (["invalid"], [valid]), (["prefix" + valid + "suffix"], ["invalid"])):
            with self.subTest(pieces=pieces), self.assertRaisesRegex(SystemExit, "single vocabulary token"):
                v["single_token_proof"](pieces, lambda i: decoded[i], validator)

    def test_all_ids_including_empty_and_special_are_visited(self):
        validator, _ = v["reference_checks"](ROOT)
        calls = []
        def decode(i):
            calls.append(i)
            return ["", "{}", "false", "<eos>"][i]
        proof = v["single_token_proof"](["<pad>", "{}", "false", "<eos>"], decode, validator)
        self.assertEqual(calls, [0, 1, 2, 3])
        self.assertEqual(proof["vocabularyEntries"], 4)
        self.assertEqual(proof["schemaValidVariants"], 0)

    def test_failure_removes_stale_proof_even_under_optimization(self):
        with tempfile.TemporaryDirectory() as folder:
            proof = Path(folder) / "proof.json"
            proof.write_text('{"status":"passed"}')
            outcome = subprocess.run([sys.executable, "-O", str(SCRIPT), "--root", folder,
                                      "--output", str(proof)], capture_output=True, timeout=10)
            self.assertNotEqual(outcome.returncode, 0)
            self.assertFalse(proof.exists())

    def test_critical_checks_cannot_be_optimized_away(self):
        self.assertFalse(any(isinstance(n, ast.Assert) for n in ast.walk(ast.parse(SCRIPT.read_text()))))

    def test_full_wrapper_invalidates_success_before_failed_dependency_setup(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "scripts").mkdir()
            wrapper = root / "scripts/verify-constrained-decoding-prerequisites.sh"
            wrapper.write_bytes((ROOT / "scripts/verify-constrained-decoding-prerequisites.sh").read_bytes())
            proof = root / "build/constrained-decoding/prerequisites.json"
            proof.parent.mkdir(parents=True)
            proof.write_text('{"status":"passed"}')
            python = root / "fake/bin/python"
            python.parent.mkdir(parents=True)
            python.write_text("#!/bin/sh\nexit 19\n")
            python.chmod(0o700)
            result = subprocess.run(["bash", str(wrapper)], env={**os.environ, "CONSTRAINED_REFERENCE_VENV": str(python.parents[1])},
                                    capture_output=True, timeout=10)
            self.assertEqual(result.returncode, 19)
            self.assertFalse(proof.exists())


if __name__ == "__main__":
    unittest.main()
