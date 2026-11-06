#!/usr/bin/env python3
"""Canonical host Python suite with an explicit, verified archival route."""
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('canonical_unicode_archival_suite',
    ROOT / 'scripts/diagnostics/unicode_archival_v1/suite.py')
routing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(routing)


def main():
    suite, receipt = routing.full_suite()
    print(json.dumps(receipt, sort_keys=True), flush=True)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == '__main__':
    raise SystemExit(main())
