import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "record-encoder-counting.py"
SCRIPTS = SCRIPT.parent


class EncoderCountingProofChecks(unittest.TestCase):
    def run_proof(self, *, suite=None, stale=False, changed_source=False, changed_corpus=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            shutil.copyfile(SCRIPT, root / "scripts/record-encoder-counting.py")
            folder = root / "build/encoder-counting"
            folder.mkdir(parents=True)
            (folder / "counting-proof.json").write_text('{"passed": true, "old": true}')
            source = root / "synthetic.kt"
            source.write_text("synthetic original")
            corpus = folder / "reference.tsv"
            corpus.write_text("synthetic corpus")
            digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
            (folder / "reference.json").write_text(json.dumps({
                "createdAtMs": 2000, "sourceSha256": {"synthetic.kt": digest(source)},
                "referenceSha256": digest(corpus),
            }))
            result = root / "shared/build/test-results/jvmTest/TEST-dev.edgecompanion.core.inference.embedding.UseTokenizerReferenceTest.xml"
            result.parent.mkdir(parents=True)
            result.write_text(suite or '<testsuite tests="2" failures="0" errors="0" skipped="0"/>')
            os.utime(result, (1 if stale else 3, 1 if stale else 3))
            if changed_source:
                source.write_text("changed")
            if changed_corpus:
                corpus.write_text("changed")
            completed = subprocess.run([sys.executable, "-O", str(root / "scripts/record-encoder-counting.py")],
                                       capture_output=True, text=True, timeout=10)
            return completed, (folder / "counting-proof.json").exists()

    def test_fresh_unskipped_correlated_results_publish(self):
        completed, published = self.run_proof()
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertTrue(published)

    def test_failed_or_skipped_results_cannot_publish(self):
        for attributes in ('tests="2" failures="1" errors="0" skipped="0"',
                           'tests="2" failures="0" errors="0" skipped="2"',
                           'tests="1" failures="0" errors="0" skipped="0"'):
            with self.subTest(attributes=attributes):
                completed, published = self.run_proof(suite=f"<testsuite {attributes}/>")
                self.assertNotEqual(completed.returncode, 0)
                self.assertFalse(published)

    def test_older_test_results_cannot_publish(self):
        completed, published = self.run_proof(stale=True)
        self.assertNotEqual(completed.returncode, 0)
        self.assertFalse(published)

    def test_changed_source_cannot_publish(self):
        completed, published = self.run_proof(changed_source=True)
        self.assertNotEqual(completed.returncode, 0)
        self.assertFalse(published)

    def test_changed_corpus_cannot_publish(self):
        completed, published = self.run_proof(changed_corpus=True)
        self.assertNotEqual(completed.returncode, 0)
        self.assertFalse(published)


class EncoderCountingWrapperChecks(unittest.TestCase):
    def run_wrapper(self, failure):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scripts = root / "scripts"
            scripts.mkdir()
            for name in ("verify-encoder-counting.sh", "record-encoder-counting.py", "run-encoder-admission.mjs"):
                shutil.copyfile(SCRIPTS / name, scripts / name)
            folder = root / "build/encoder-counting"
            folder.mkdir(parents=True)
            source = root / "synthetic.kt"
            source.write_text("unchanged source")
            corpus = folder / "reference.tsv"
            corpus.write_text("unchanged corpus")
            proof = {"createdAtMs": 2000, "passed": True, "admissionVerified": True,
                     "sourceSha256": {"synthetic.kt": hashlib.sha256(source.read_bytes()).hexdigest()},
                     "referenceSha256": hashlib.sha256(corpus.read_bytes()).hexdigest()}
            (folder / "reference.json").write_text(json.dumps(proof))
            published = folder / "counting-proof.json"
            published.write_text(json.dumps(proof))
            result = root / "shared/build/test-results/jvmTest/TEST-dev.edgecompanion.core.inference.embedding.UseTokenizerReferenceTest.xml"
            result.parent.mkdir(parents=True)
            result.write_text('<testsuite tests="2" failures="0" errors="0" skipped="0"/>')
            os.utime(result, (3, 3))
            python = root / "venv/bin/python"
            python.parent.mkdir(parents=True)
            python.write_text('''#!/bin/sh
echo "$*" >> "$TRACE"
case "$1" in
  -m) phase=install ;;
  */setup-encoder-reference.py) phase=setup ;;
  */prepare-encoder-counting.py) phase=prepare ;;
  */record-encoder-counting.py) exec "$REAL_PYTHON" -O "$@" ;;
  *) exit 99 ;;
esac
if [ "$FAIL_PHASE" = "$phase" ]; then exit 73; fi
''')
            python.chmod(0o755)
            gradle = root / "gradlew"
            gradle.write_text('''#!/bin/sh
echo gradle >> "$TRACE"
if [ "$FAIL_PHASE" = gradle ]; then exit 73; fi
''')
            gradle.chmod(0o755)
            adb = root / "adb-stub"
            adb.write_text('#!/bin/sh\ntouch "$ADB_MARKER"\nexit 99\n')
            adb.chmod(0o755)
            env = {**os.environ, "ENCODER_AUDIT_VENV": str(python.parent.parent), "REAL_PYTHON": sys.executable,
                   "FAIL_PHASE": failure, "TRACE": str(root / "trace"), "ANDROID_SERIAL": "synthetic",
                   "ADB": str(adb), "ADB_MARKER": str(root / "adb-called")}
            completed = subprocess.run(["bash", str(scripts / "verify-encoder-counting.sh"),
                                        str(root / "unused.tflite"), str(root / "unused.tsv")],
                                       env=env, capture_output=True, text=True, timeout=10)
            trace = (root / "trace").read_text()
            if failure:
                self.assertEqual(completed.returncode, 73, completed.stderr)
                self.assertNotIn("record-encoder-counting.py", trace)
                self.assertFalse(published.exists(), "Failed wrapper left the unchanged old success usable")
                admission = subprocess.run(["node", str(scripts / "run-encoder-admission.mjs")],
                                           cwd=root, env=env, capture_output=True, text=True, timeout=10)
                self.assertNotEqual(admission.returncode, 0)
                self.assertFalse((root / "adb-called").exists(), "Stale proof reached device access")
            else:
                self.assertEqual(completed.returncode, 0, completed.stderr)
                self.assertIn("gradle", trace)
                self.assertIn("record-encoder-counting.py", trace)
                self.assertLess(trace.index("gradle"), trace.index("record-encoder-counting.py"))
                fresh = json.loads(published.read_text())
                self.assertTrue(fresh["passed"])
                self.assertGreater(fresh["recordedAtMs"], proof["createdAtMs"])

    def test_every_wrapper_failure_invalidates_old_proof_before_publication(self):
        for phase in ("install", "setup", "prepare", "gradle"):
            with self.subTest(phase=phase):
                self.run_wrapper(phase)

    def test_successful_wrapper_publishes_only_after_gradle(self):
        self.run_wrapper("")


if __name__ == "__main__":
    unittest.main()
