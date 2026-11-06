"""Explicit one-call archival routing; every original test/assertion is retained."""
import importlib.util
from pathlib import Path
import types
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('unicode_archival_suite_verify', HERE / 'verify.py')
archive = importlib.util.module_from_spec(spec)
spec.loader.exec_module(archive)
ROOT = archive.ROOT
ORIGINAL = ROOT / 'scripts/tests/test_unicode_correction.py'
ACTIVE = ROOT / 'scripts/current-tests/test_unicode_correction.py'
BEFORE = ('    def test_frozen_fixtures_reproduce_without_scores(self):\n'
          '        candidate.verify_inputs()\n')
AFTER = ('    def test_frozen_fixtures_reproduce_without_scores(self):\n'
         "        load('unicode_archival_verifier', '../unicode_archival_v1/verify.py').verify_archive()\n")
ORIGINAL_MODULE = 'test_unicode_correction'
ACTIVE_MODULE = 'current_unicode_correction_archival_v1'


def flatten(suite):
    for item in suite:
        if isinstance(item, unittest.TestSuite): yield from flatten(item)
        else: yield item


def verified_active_bytes():
    archive.seal()  # Verifies original test/guard bytes and sealed guidance history.
    original = archive.read(ORIGINAL).decode('utf-8')
    archive.require(original.count(BEFORE) == 1, 'missing/ambiguous archival call site')
    active = archive.read(ACTIVE)
    archive.require(active == original.replace(BEFORE, AFTER).encode('utf-8'),
                    'active archival test differs beyond the approved call route')
    return active


def migrate(discovered):
    source = verified_active_bytes()
    module = types.ModuleType(ACTIVE_MODULE)
    module.__file__ = str(ACTIVE)
    exec(compile(source, str(ACTIVE), 'exec'), module.__dict__)
    active = list(flatten(unittest.defaultTestLoader.loadTestsFromModule(module)))
    cases = list(flatten(discovered))
    old_prefix, new_prefix = ORIGINAL_MODULE + '.', ACTIVE_MODULE + '.'
    originals = [c for c in cases if c.id().startswith(old_prefix)]
    expected = archive.seal()['allOriginalMethodsRetained']
    archive.require(len(originals) == len(active) == expected, 'missing original or active archival tests')
    keyed = {c.id()[len(new_prefix):]: c for c in active}
    archive.require(len(keyed) == expected and set(keyed) == {c.id()[len(old_prefix):] for c in originals},
                    'archival test inventory changed')
    result = [keyed[c.id()[len(old_prefix):]] if c.id().startswith(old_prefix) else c for c in cases]
    normalize = lambda c: old_prefix + c.id()[len(new_prefix):] if c.id().startswith(new_prefix) else c.id()
    archive.require([c.id() for c in cases] == [normalize(c) for c in result], 'tests omitted or reordered')
    return unittest.TestSuite(result), dict(protocol='unicode-correction-explicit-archival-verification-v1',
        discoveredTests=len(cases), executedTests=len(result), originalTestsRetained=expected,
        skippedByRouting=0, modifiedCallSites=1, nativeExecutionAuthorized=False)


def full_suite():
    verified_active_bytes()  # Refuse tampered routing before discovery/import.
    discovered = unittest.TestLoader().discover(str(ROOT / 'scripts/tests'), pattern='test_*.py')
    return migrate(discovered)
