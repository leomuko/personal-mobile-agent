"""Fresh launch identity and preservation checks; no native dispatch."""
import ast
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


e = load('launch_invariants', 'scripts/diagnostics/dawn_cpp_linker_launch_v1/qualify.py')
fixtures = load('launch_admission_fixtures', 'scripts/tests/test_dawn_cpp_linker_launch.py')


class LaunchIdentityTests(unittest.TestCase):
    def test_only_four_contract_fields_change_with_exact_plan_projection(self):
        baseline = e.preparation.bound(e.BASE_FREEZE, e.BASE_SHA)
        result = e.proposal()
        contract = e.contract(result)
        self.assertEqual({k for k in contract if contract[k] != baseline['contract'][k]},
                         {'attempt', 'commands', 'compilerSandbox', 'nativeEnvironment'})
        self.assertEqual(result['commands'], e.plan.commands(e.ATTEMPT))
        self.assertEqual(result['compilerSandbox'], e.plan.sandbox(e.ATTEMPT))
        self.assertEqual(result['nativeEnvironment'], e.plan.environment(e.ATTEMPT))
        self.assertEqual(result['inputs'], baseline['inputs'])
        self.assertEqual([c['id'] for c in result['commands']],
                         [c['id'] for c in baseline['contract']['commands']])
        self.assertNotEqual(str(e.ATTEMPT), baseline['contract']['attempt'])
        self.assertEqual(result['allowedExecutablePaths'], [str(e.plan.CLANG)])
        self.assertNotIn(str(e.plan.LD), result['compilerSandbox'])

    def test_reviewed_outer_request_is_one_command_without_persistent_rule_or_fallback(self):
        p = e.proposal()['launchProfile']
        self.assertEqual(p['hostTool'], 'exec_command')
        self.assertEqual(p['sandbox_permissions'], 'require_escalated')
        self.assertEqual(p['shell'], '/bin/zsh')
        self.assertFalse(p['login'])
        self.assertEqual(p['workdir'], str(ROOT))
        self.assertFalse(p['persistentRuleRequested'])
        self.assertFalse(p['automaticFallback'])
        self.assertEqual(p['maximumParentInvocations'], 1)
        self.assertEqual(p['parentArguments']['attempt'], str(e.ATTEMPT))
        self.assertEqual(p['parentArguments']['entrypoint'], str(e.HERE / 'qualify.py'))

    def test_all_r2_execution_and_failure_functions_are_exact_ast_copies(self):
        def functions(path):
            return {node.name: ast.dump(node, include_attributes=False)
                    for node in ast.parse(path.read_text()).body
                    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))}
        old = functions(ROOT / 'scripts/diagnostics/dawn_cpp_linker_executor_v1/qualify.py')
        new = functions(e.HERE / 'qualify.py')
        unchanged = ['module', 'contract', 'check', 'check_inputs', 'quarantine',
                     'interruptions', 'dispatch', 'worker', 'main']
        for name in unchanged:
            with self.subTest(function=name):
                self.assertEqual(new[name], old[name])

    def test_baseline_and_failed_attempt_remain_unchanged(self):
        value = e.q.data(e.AMENDMENT)
        self.assertEqual(hashlib.sha256(e.BASE_FREEZE.read_bytes()).hexdigest(), e.BASE_SHA)
        for item in value['historicalEvidence']:
            self.assertEqual(hashlib.sha256((ROOT / item['path']).read_bytes()).hexdigest(), item['sha256'])
        old = ROOT / value['oldAttempt']
        state = json.loads((old / 'outcome.json').read_text())
        self.assertTrue(state['cleanupConfirmed'])
        self.assertFalse(state['publicAcceptancePresent'])

    def test_changed_launch_fields_reject_before_owner(self):
        helper = fixtures.ExecutorAdmissionTests()
        for key in ['amendmentSha256', 'amendmentReviewSha256', 'baselineExecutionFreezeSha256', 'launchProfile']:
            value, frozen, review = helper.values()
            frozen = copy.deepcopy(frozen)
            frozen[key] = {} if key == 'launchProfile' else '0' * 64
            with self.subTest(field=key), self.assertRaisesRegex(ValueError, 'changed launch identity/profile'):
                helper.admit(value, frozen, review, lambda *a: self.fail('must reject before input/owner'))

    def test_omitted_launch_dependencies_reject_before_owner(self):
        helper = fixtures.ExecutorAdmissionTests()
        for path in [e.AMENDMENT, e.AMENDMENT_REVIEW, e.BASE_FREEZE,
                     e.HERE / 'composition.json', e.HERE / 'README.md']:
            value, frozen, review = helper.values()
            frozen['inputs'] = [row for row in frozen['inputs'] if row['path'] != str(path)]
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, 'unbound executor dependency'):
                helper.admit(value, frozen, review, lambda *a: self.fail('no input/owner'))

    def test_bad_amendment_or_review_cannot_be_adopted(self):
        original = e.preparation.bound
        for change in ['review', 'attempt', 'delta']:
            def bound(path, sha, change=change):
                value = copy.deepcopy(original(path, sha))
                if Path(path) == e.AMENDMENT_REVIEW and change == 'review': value['verdict'] = 'FAIL'
                if Path(path) == e.AMENDMENT and change == 'attempt': value['freshAttempt'] = '/other'
                if Path(path) == e.AMENDMENT and change == 'delta': value['allowedRebaseFields'].append('seconds')
                return value
            with self.subTest(change=change), patch.object(e.preparation, 'bound', side_effect=bound), \
                 patch.object(e.owner, 'run_owned', side_effect=AssertionError('no owner')):
                with self.assertRaises(ValueError): e.proposal()

    def test_reviewed_document_mutation_rejects_before_adoption(self):
        original = e.owner.digest
        def digest(path):
            return '0' * 64 if str(path).endswith('P2_CPP_LINKER_LAUNCH_AMENDMENT_V1.md') else original(path)
        with patch.object(e.owner, 'digest', side_effect=digest):
            with self.assertRaisesRegex(ValueError, 'changed reviewed launch document/baseline'):
                e.proposal()

    def test_changed_retained_failure_rejects_before_adoption(self):
        original = e.c.file_row
        target = ROOT / 'build/dawn-cpp-linker-v1/probe-attempts/linker-1/outcome.json'
        def read(path):
            row = dict(original(path))
            if Path(path) == target: row['sha256'] = '0' * 64
            return row
        with patch.object(e.c, 'file_row', side_effect=read):
            with self.assertRaisesRegex(ValueError, 'historical result binding changed'):
                e.proposal()

    def test_stale_composition_rejects_before_amendment_adoption(self):
        original = e.q.data
        def data(path, *args):
            value = copy.deepcopy(original(path, *args))
            if Path(path) == e.HERE / 'composition.json':
                value['candidate']['sha256'] = '0' * 64
            return value
        with patch.object(e.q, 'data', side_effect=data), \
             patch.object(e.preparation, 'bound', side_effect=AssertionError('must reject composition first')):
            with self.assertRaisesRegex(ValueError, 'changed/stale executor composition'):
                e.proposal()


if __name__ == '__main__':
    unittest.main()
