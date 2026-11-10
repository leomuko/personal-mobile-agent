"""Bounded owned-Python regression for policy FIFOs; no compiler/native probes."""
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'scripts/diagnostics/linker_parser_v2/parser.py'
CHILD = r'''
import importlib.util
from pathlib import Path
import sys

# A fresh module avoids a previously cached valid policy masking this regression.
spec = importlib.util.spec_from_file_location('fifo_policy_child', sys.argv[1])
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
module.CONTRACT_PATH = Path(sys.argv[2])
try:
    module._policy()
except module.ParseError as error:
    if str(error) != 'contract type/size':
        raise SystemExit('unexpected rejection: ' + str(error))
    print('FIFO_REJECTED_BEFORE_READ', flush=True)
else:
    raise SystemExit('FIFO accepted')
'''


class PolicyFifoTests(unittest.TestCase):
    def test_fresh_policy_load_rejects_fifo_without_writer_within_deadline(self):
        with tempfile.TemporaryDirectory(prefix='parser-policy-fifo-') as tmp:
            fifo = Path(tmp) / 'contract.json'
            os.mkfifo(fifo, 0o600)
            self.assertTrue(stat.S_ISFIFO(fifo.lstat().st_mode))
            child = subprocess.Popen(
                [sys.executable, '-I', '-S', '-B', '-c', CHILD, str(SOURCE), str(fifo)],
                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                cwd=tmp, close_fds=True,
            )
            try:
                try:
                    stdout, stderr = child.communicate(timeout=2)
                except subprocess.TimeoutExpired:
                    self.fail('policy load blocked on FIFO without writer for two seconds')
                self.assertEqual(child.returncode, 0, stderr.decode('utf-8', 'replace'))
                self.assertEqual(stdout, b'FIFO_REJECTED_BEFORE_READ\n')
                self.assertEqual(stderr, b'')
            finally:
                # Always reap our single owned child before removing its FIFO.
                # The child never launches helpers and no process group is targeted.
                if child.poll() is None:
                    child.kill()
                child.communicate(timeout=2)


if __name__ == '__main__':
    unittest.main()
