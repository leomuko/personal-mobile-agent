"""Data-only permission-request generation; never launches native tools."""
import copy
import importlib.util
from pathlib import Path
import shlex
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('launch_request_tests',
    ROOT / 'scripts/diagnostics/dawn_cpp_linker_launch_v1/launch.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class HostLaunchRequestTests(unittest.TestCase):
    def values(self):
        value = m.e.proposal()
        return dict(contract=m.e.contract(value), launchProfile=value['launchProfile'])

    def request(self, value):
        with patch.object(m.e, 'admission', return_value=value) as admit, \
             patch.object(m.e, 'quarantine'), patch.object(m.os.path, 'lexists', return_value=False), \
             patch('subprocess.Popen', side_effect=AssertionError('generator never executes')) as child:
            result = m.prepare_request('a' * 64, 'b' * 64)
        self.assertEqual(admit.call_count, 1)
        self.assertEqual(child.call_count, 0)
        return result

    def test_exact_request_uses_real_hashes_one_command_and_no_persistent_allow_rule(self):
        result = self.request(self.values())
        request = result['toolRequest']
        self.assertEqual(shlex.split(request['cmd']), result['argv'])
        self.assertIn('a' * 64, result['argv']); self.assertIn('b' * 64, result['argv'])
        self.assertNotIn('prefix_rule', request)
        self.assertEqual(request['sandbox_permissions'], 'require_escalated')
        self.assertFalse(request['login'])
        self.assertFalse(result['approvalGrantedByGenerator'])
        self.assertEqual(result['nativeQueriesExecuted'], 0)

    def test_malformed_hashes_reject_before_admission(self):
        for value in ['', 'x' * 64, 'a' * 63, 'a' * 65, '$(anything)', None]:
            with self.subTest(value=value), patch.object(m.e, 'admission') as admit:
                with self.assertRaisesRegex(ValueError, 'invalid launch hash'):
                    m.prepare_request(value, 'b' * 64)
                self.assertEqual(admit.call_count, 0)

    def test_missing_or_failed_review_blocks_generation_without_fallback(self):
        with patch.object(m.e, 'admission', side_effect=ValueError('review rejected')) as admit, \
             patch('subprocess.Popen', side_effect=AssertionError('no fallback')) as child:
            with self.assertRaisesRegex(ValueError, 'review rejected'):
                m.prepare_request('a' * 64, 'b' * 64)
        self.assertEqual(admit.call_count, 1); self.assertEqual(child.call_count, 0)

    def test_consumed_attempt_blocks_host_request(self):
        with patch.object(m.e, 'admission', return_value=self.values()), \
             patch.object(m.e, 'quarantine'), patch.object(m.os.path, 'lexists', return_value=True):
            with self.assertRaisesRegex(ValueError, 'fresh linker-2'):
                m.prepare_request('a' * 64, 'b' * 64)

    def test_outer_permission_widening_or_fallback_changes_reject(self):
        for key, value in [('sandbox_permissions', 'use_default'), ('login', True),
                           ('persistentRuleRequested', True), ('automaticFallback', True),
                           ('maximumParentInvocations', 2), ('shell', '/bin/bash')]:
            bad = copy.deepcopy(self.values()); bad['launchProfile'][key] = value
            with self.subTest(field=key), self.assertRaisesRegex(ValueError, 'unapproved outer launch profile'):
                self.request(bad)

    def test_substituted_python_entrypoint_attempt_or_flag_rejects(self):
        for key, value in [('python', '/other/python'), ('entrypoint', '/other/tool.py'),
                           ('attempt', str(m.e.ATTEMPT.parent / 'linker-1')), ('pythonFlags', [])]:
            bad = copy.deepcopy(self.values()); bad['launchProfile']['parentArguments'][key] = value
            with self.subTest(field=key), self.assertRaisesRegex(ValueError, 'unapproved parent argument template'):
                self.request(bad)


if __name__ == '__main__':
    unittest.main()
