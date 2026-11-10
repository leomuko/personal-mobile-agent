"""Supplemental offline policy/context integrity checks; no native subprocesses."""
import ast
from dataclasses import FrozenInstanceError
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'scripts/diagnostics/linker_parser_v2/parser.py'
FIX = ROOT / 'planning/fixtures/linker-parser-contract-v2'
CASE_LOG = []
ASSERTION_COUNT = 0


def load(name):
    spec = importlib.util.spec_from_file_location(name, SOURCE)
    value = importlib.util.module_from_spec(spec)
    sys.modules[name] = value
    spec.loader.exec_module(value)
    return value


class IntegrityTests(unittest.TestCase):
    def verify(self, condition, label):
        global ASSERTION_COUNT
        ASSERTION_COUNT += 1
        self.assertTrue(condition, label)

    def record(self, name, assertions_before):
        CASE_LOG.append(dict(family='INTEGRITY', case=name, expected='PASS', actual='PASS',
                             assertions=ASSERTION_COUNT - assertions_before))

    def fixture(self, module):
        values = json.loads((FIX / 'contract-proposal.json').read_text())['sampleBindings']
        return module.Context(values['ATTEMPT'], values['SYNTHETIC_TARGET'])

    def test_unreviewed_missing_oversized_and_symlink_contracts_reject(self):
        for mutation in ('changed', 'missing', 'oversized', 'symlink', 'directory'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as tmp:
                count = ASSERTION_COUNT
                module = load('v2_integrity_' + mutation)
                path = Path(tmp).resolve() / 'contract.json'
                if mutation == 'changed': path.write_bytes((FIX / 'contract-proposal.json').read_bytes() + b' ')
                if mutation == 'oversized': path.write_bytes(b'x' * (128 * 1024 + 1))
                if mutation == 'symlink': path.symlink_to(FIX / 'contract-proposal.json')
                if mutation == 'directory': path.mkdir()
                module.CONTRACT_PATH = path
                error = None
                try: module._policy()
                except module.ParseError as caught: error = caught
                self.verify(error is not None, 'invalid contract must reject before parsing')
                self.record('contract-' + mutation, count)

    def test_loaded_policy_remains_pinned_and_new_load_rejects_disk_mutation(self):
        count = ASSERTION_COUNT
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp).resolve() / 'contract.json'
            path.write_bytes((FIX / 'contract-proposal.json').read_bytes())
            module = load('v2_integrity_cached')
            module.CONTRACT_PATH = path
            context = self.fixture(module)
            policy = module._policy()
            path.write_bytes(path.read_bytes().replace(b'-ffp-contract=on', b'-ffp-contract=off'))
            result = module.parse_output((FIX / 'observed-positive.raw.txt').read_bytes(),
                                         context=context, case_id=module.PRESENT, exit_code=0)
            self.verify(result.policy_sha256 == module.CONTRACT_SHA256, 'cache remains reviewed policy')
            self.verify(module._policy() is policy, 'cached immutable instance remains stable')
            fresh = load('v2_integrity_fresh_mutated')
            fresh.CONTRACT_PATH = path
            error = None
            try: fresh._policy()
            except fresh.ParseError as caught: error = caught
            self.verify(error is not None, 'fresh load rejects mutated contract')
        self.record('cache-drift-cannot-widen-policy', count)

    def test_policy_atoms_and_context_have_no_normal_mutation_path(self):
        count = ASSERTION_COUNT
        module = load('v2_integrity_immutable')
        policy = module._policy()
        for obj, key, replacement in ((policy, 'target', '/usr/bin/ld'),
                                      (policy.frontend[0], 'literal', '/bin/sh'),
                                      (self.fixture(module), 'attempt', '/tmp/elsewhere')):
            error = None
            try: setattr(obj, key, replacement)
            except FrozenInstanceError as caught: error = caught
            self.verify(error is not None, 'normal mutation denied')
        self.verify(type(policy.frontend) is tuple and type(policy.linker) is tuple, 'immutable word arrays')
        self.record('immutable-policy-context', count)

    def test_invalid_context_data_are_typed_rejections_without_resolution(self):
        module = load('v2_integrity_context')
        base = self.fixture(module)
        for value in (None, 12, True, '', 'relative', '//server/path', base.attempt + '/',
                      base.attempt + '/..', base.attempt.replace('/access-1', '//access-1'),
                      base.attempt + '\\escape', base.attempt + '\x00', base.attempt + '\ud800'):
            with self.subTest(value=repr(value)):
                count = ASSERTION_COUNT
                error = None
                try: module.Context(value, base.synthetic_target)
                except module.ParseError as caught: error = caught
                self.verify(error is not None, 'invalid context is controlled ParseError')
                self.record('context-' + repr(value), count)

    def test_result_replacement_cannot_forge_comparison_or_qualification(self):
        count = ASSERTION_COUNT
        module = load('v2_integrity_result')
        context = self.fixture(module)
        selected = module.parse_output((FIX / 'observed-positive.raw.txt').read_bytes(),
                                       context=context, case_id=module.PRESENT, exit_code=0)
        rejected = module.parse_output((FIX / 'synthetic-negative.raw.txt').read_bytes(),
                                       context=context, case_id=module.ABSENT, exit_code=1)
        from dataclasses import replace
        for altered in (replace(rejected, terminal_status='PASSED'),
                        replace(rejected, raw_exit_code=0, classification=module.SELECTED,
                                command_status='PASSED', terminal_status='OFFLINE_STRUCTURAL_COMPATIBLE'),
                        replace(rejected, object_path=context.attempt + '/empty-other.o'),
                        replace(rejected, frontend_tokens=list(rejected.frontend_tokens))):
            error = None
            try: module.compare_outputs([selected, altered])
            except module.ParseError as caught: error = caught
            self.verify(error is not None, 'forged output rejected on status/tokens/object')
        error = None
        try: replace(selected, real_linker_qualified=True)
        except ValueError as caught: error = caught
        self.verify(error is not None, 'qualification not constructor-configurable')
        self.record('forged-results-no-qualification', count)

    def test_package_has_no_runner_publisher_or_native_import_surface(self):
        count = ASSERTION_COUNT
        tree = ast.parse(SOURCE.read_text())
        imports = {alias.name.split('.')[0] for node in ast.walk(tree)
                   if isinstance(node, ast.Import) for alias in node.names}
        self.verify(not imports & {'subprocess', 'ctypes', 'socket', 'signal', 'resource'}, 'no native/work owner imports')
        forbidden = {'system', 'access', 'execv', 'execve', 'posix_spawn', 'fork',
                     'chmod', 'mkdir', 'unlink', 'rename', 'publish', 'run_owned'}
        calls = {node.func.attr for node in ast.walk(tree)
                 if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)}
        self.verify(not calls & forbidden, 'no process, publishing or mutation calls')
        module = load('v2_integrity_surface')
        self.verify(not any(hasattr(module, key) for key in ('dispatch', 'worker', 'main', 'publish', 'admission')),
                    'offline parser only')
        self.record('no-native-bridge-surface', count)


if __name__ == '__main__': unittest.main()
