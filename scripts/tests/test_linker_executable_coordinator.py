"""Synthetic worker/coordinator regressions; no native processes or real markers."""
import base64
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    'access_coordinator_regression', ROOT / 'scripts/diagnostics/linker_executable_access_v1/qualify.py')
e = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(e)

PRESENT = 'synthetic-exception-present'
ABSENT = 'synthetic-exception-absent'
SUPPORT = 'SUPPORTS_POLICY_SENSITIVE_VALIDATION_ONLY'
NO_SUPPORT = 'NO_SUPPORT_FOR_POLICY_COUPLING_IN_THIS_COMPARISON'


def contract(attempt):
    """In-memory paths only: the synthetic target is never prepared or executed."""
    target = str(attempt.parent / 'never-created-target')
    commands = [dict(id=name, profile='synthetic-profile-' + name,
                     argv=[str(e.plan.CLANG), '--ld-path=' + target, '-###', '-x', 'c++',
                           str(attempt / 'empty.cpp'), '-o', str(attempt / 'never-created-output')])
                for name in (PRESENT, ABSENT)]
    return dict(attempt=str(attempt), commands=commands, python='never-execute-test-python',
                ownerEnvironment={}, nativeEnvironment={})


def transcript(job, negative=False):
    """Complete synthetic grammar witness, never collected compiler output."""
    source, output = job['argv'][-3], job['argv'][-1]
    obj = str(Path(source).parent / 'empty-synthetic.o')
    target = job['argv'][1].split('=', 1)[1]
    cc = [str(e.plan.CLANG), '-cc1', '-emit-obj', '-fcxx-exceptions', '-fexceptions',
          '-pedantic-errors', '-O0', '-triple', 'arm64-apple-macosx26.5.0',
          '-isysroot', str(e.q.plan.SDK), '-resource-dir', str(e.q.plan.RESOURCE)]
    for row in e.plan.prior.plan.base_plan.includes('control'):
        cc.extend(['-iframework' if row['framework'] else '-I', row['path']])
    cc.extend(['-main-file-name', 'empty.cpp', '-std=c++20', '-x', 'c++', '-o', obj, source])
    ld = ['ld' if negative else target, '-syslibroot', str(e.q.plan.SDK), '-arch', 'arm64',
          '-lc++', '-lSystem', '-lto_library', str(e.plan.LD.parent.parent / 'lib/libLTO.dylib'),
          '-platform_version', 'macos', '26.5', '26.5', '-o', output, obj]
    text = ('Apple clang version 21.0.0 (synthetic)\nTarget: arm64-apple-macosx26.5.0\n'
            'Thread model: posix\nInstalledDir: ' + str(e.plan.CLANG.parent) + '\n' +
            ' '.join(map(json.dumps, cc)) + '\n' + ' '.join(map(json.dumps, ld)) + '\n')
    if negative:
        text += "clang: error: invalid linker name in argument '--ld-path=" + target + "'\n"
    return text.encode()


class IsolatedTests(unittest.TestCase):
    def setUp(self):
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        # Fail closed if a future code change crosses any native boundary.
        for obj, name in ((subprocess, 'Popen'), (e.os, 'system'), (e.os, 'access'),
                          (e.c, 'metadata_command'), (e.owner, 'run_owned'),
                          (e.marker, 'prepare'), (e.marker, 'verify'), (e.q, 'native_rows')):
            self.stack.enter_context(patch.object(
                obj, name, side_effect=AssertionError('forbidden native/marker boundary: ' + name)))

    def temporary(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        return Path(directory.name).resolve()

    def clock_patches(self, clock):
        def check(deadline):
            e.require(clock[0] < deadline, 'synthetic deadline')
        self.stack.enter_context(patch.object(e, 'now', side_effect=lambda: clock[0]))
        self.stack.enter_context(patch.object(e, 'check', side_effect=check))
        def remaining(deadline, maximum):
            check(deadline)
            return min(maximum, deadline - clock[0])
        self.stack.enter_context(patch.object(e, 'remaining', side_effect=remaining))


class WorkerTests(IsolatedTests):
    def exercise(self, mode='both-selected'):
        attempt = self.temporary()
        frozen = dict(contract=contract(attempt))
        clock, calls, captures = [0.0], [], []
        self.clock_patches(clock)
        original_tree = e.c.tree

        def dispatch(job, path, value, deadline, trace):
            self.assertEqual(path, attempt)
            self.assertIs(value, frozen)
            self.assertEqual(deadline, 30)
            calls.append(job['id'])
            if mode == 'cancel':
                raise KeyboardInterrupt('synthetic Stop')
            if mode == 'timeout':
                raise subprocess.TimeoutExpired('mocked command', 30)
            negative = mode == 'positive-rejected' or (mode == 'negative' and job['id'] == ABSENT)
            raw, code = transcript(job, negative), int(negative)
            argv = ['/usr/bin/sandbox-exec', '-p', job['profile'], *job['argv']]
            trace(argv, raw, code, 1)
            captures.append(raw)
            if mode == 'late-command':
                clock[0] = deadline
            if mode == 'unexpected-output':
                (attempt / 'unexpected-helper').write_bytes(b'synthetic')
            if mode == 'source-drift':
                (attempt / 'empty.cpp').write_bytes(b'changed synthetic source')
            return raw, code

        def tree(*args):
            result = original_tree(*args)
            if mode == 'late-inventory':
                clock[0] = 30
            return result

        error = None
        with patch.object(e.q, 'native_rows', return_value=({}, [])), \
             patch.object(e, 'preparation_receipt', return_value={'synthetic': True}), \
             patch.object(e, 'dispatch', side_effect=dispatch), \
             patch.object(e.c, 'tree', side_effect=tree):
            try:
                e.worker(frozen, attempt, 300)
            except (ValueError, KeyboardInterrupt, subprocess.TimeoutExpired) as caught:
                error = caught
        report = json.loads((attempt / 'diagnostic-report.json').read_text())
        self.assertFalse(report['realLinkerQualified'])
        self.assertFalse(report['acceptancePublicationAllowed'])
        self.assertFalse((attempt / 'accepted-probes.json').exists())
        return report, calls, error, frozen, attempt, captures

    def test_both_selected_runs_in_order_without_qualification(self):
        report, calls, error, frozen, attempt, captures = self.exercise()
        self.assertIsNone(error)
        self.assertEqual(calls, [PRESENT, ABSENT])
        self.assertEqual(report['status'], 'DIAGNOSTIC_COLLECTED_NOT_QUALIFICATION')
        self.assertEqual(report['comparison'], NO_SUPPORT)
        self.assertEqual([row['status'] for row in report['commands']], ['PASSED', 'PASSED'])
        self.assertEqual(report['notRunCommands'], [])
        self.assertEqual(e.verify_report(frozen, attempt), report)
        lines = (attempt / 'command-trace.jsonl').read_bytes()
        self.assertEqual(report['commandTraceBytes'], len(lines))
        self.assertEqual([base64.b64decode(json.loads(line)['outputBase64'])
                          for line in lines.splitlines()], captures)

    def test_rejected_positive_control_stops_before_negative_case(self):
        report, calls, error, _, _, _ = self.exercise('positive-rejected')
        self.assertIsInstance(error, ValueError)
        self.assertEqual(calls, [PRESENT])
        self.assertEqual(report['status'], 'FAILED')
        self.assertEqual(report['commands'][0]['status'], 'FAILED')
        self.assertEqual(report['comparison'], 'INCONCLUSIVE')
        self.assertEqual(report['notRunCommands'], [ABSENT])

    def test_negative_is_failed_but_retains_comparison_support_and_raw_trace(self):
        report, calls, error, frozen, attempt, _ = self.exercise('negative')
        self.assertIsInstance(error, ValueError)
        self.assertEqual(calls, [PRESENT, ABSENT])
        self.assertEqual(report['status'], 'FAILED')
        self.assertEqual([row['status'] for row in report['commands']], ['PASSED', 'FAILED'])
        self.assertEqual(report['comparison'], SUPPORT)
        self.assertEqual(report['notRunCommands'], [])
        self.assertEqual(e.verify_report(frozen, attempt), report)

    def test_cancel_and_timeout_retain_terminal_failure_and_do_not_continue(self):
        for mode, expected in (('cancel', KeyboardInterrupt), ('timeout', subprocess.TimeoutExpired)):
            with self.subTest(mode=mode):
                report, calls, error, _, _, _ = self.exercise(mode)
                self.assertIsInstance(error, expected)
                self.assertEqual(calls, [PRESENT])
                self.assertEqual(report['status'], 'FAILED')
                self.assertEqual(report['commands'][0]['status'], 'FAILED')
                self.assertEqual(report['notRunCommands'], [ABSENT])

    def test_deadline_output_and_source_failures_prevent_second_dispatch(self):
        for mode in ('late-command', 'late-inventory', 'unexpected-output', 'source-drift'):
            with self.subTest(mode=mode):
                report, calls, error, _, _, _ = self.exercise(mode)
                self.assertIsInstance(error, ValueError)
                self.assertEqual(calls, [PRESENT])
                self.assertEqual(report['commands'][0]['status'], 'FAILED')
                self.assertEqual(report['notRunCommands'], [ABSENT])


class CoordinatorTests(IsolatedTests):
    def exercise(self, mode='both-selected'):
        root = self.temporary()
        attempt = root / 'attempts/synthetic-1'
        frozen = dict(contract=contract(attempt))
        clock, calls, admissions = [0.0], [], []
        self.clock_patches(clock)
        if mode.startswith('prior-'):
            old = attempt.parent / 'old'
            old.mkdir(parents=True)
            (old / 'owner-started.json').write_text('{}')
            (old / 'outcome.json').write_text(json.dumps(dict(cleanupConfirmed=mode != 'prior-uncertain')))
            if mode == 'prior-publication':
                (old / 'publication-failure.json').write_text('{}')
            if mode == 'prior-accepted':
                (old / 'accepted-probes.json').write_text('foreign synthetic object')

        def admit(*args):
            admissions.append(args)
            if mode == 'reject' or (mode == 'readmission-failure' and len(admissions) == 2):
                raise ValueError('synthetic admission rejection')
            if mode == 'cancel-admission' or (mode == 'cancel-readmission' and len(admissions) == 2):
                raise KeyboardInterrupt('synthetic admission Stop')
            if mode == 'late-admission':
                clock[0] = 300
            return frozen

        def dispatch(job, path, value, deadline, trace):
            negative = mode == 'negative' and job['id'] == ABSENT
            raw, code = transcript(job, negative), int(negative)
            trace(['/usr/bin/sandbox-exec', '-p', job['profile'], *job['argv']], raw, code, 1)
            return raw, code

        def run(*args):
            calls.append(args)
            if mode == 'cleanup-timeout':
                raise subprocess.TimeoutExpired('mocked owner cleanup', 15)
            if mode == 'cancel-owner':
                raise KeyboardInterrupt('synthetic owner Stop')
            if mode == 'signal-owner':
                raise ValueError('synthetic signal interruption')
            # Exercise the real worker and parent replay in one host process.
            with patch.object(e.q, 'native_rows', return_value=({}, [])), \
                 patch.object(e, 'preparation_receipt', return_value={'synthetic': True}), \
                 patch.object(e, 'dispatch', side_effect=dispatch):
                try:
                    e.worker(frozen, attempt, 300)
                except ValueError:
                    if mode != 'negative':
                        raise
            if mode == 'late-owner':
                clock[0] = 300
            if mode == 'post-cleanup-output':
                (attempt / 'unapproved-helper').write_bytes(b'synthetic')
            if mode == 'post-cleanup-source':
                (attempt / 'empty.cpp').write_bytes(b'synthetic drift')
            if mode == 'foreign-accepted':
                (attempt / 'accepted-probes.json').write_text('foreign synthetic object')
            failed = mode in ('negative', 'terminal-mismatch')
            return dict(classification='ANALYSIS_COMMAND_FAILED' if failed else 'ANALYSIS_COMMAND_SUCCEEDED',
                        cleanupConfirmed=mode != 'uncertain', exitCode=int(failed), elapsedSeconds=20)

        real_write = e.c.write
        def write(path, value, maximum):
            self.assertTrue(Path(path).is_relative_to(root))
            self.assertNotIn('accepted-probes', Path(path).name)
            if Path(path).name == 'outcome.json' and mode == 'cancel-before-publication':
                raise KeyboardInterrupt('synthetic Stop before publication')
            real_write(path, value, maximum)
            if Path(path).name == 'outcome.json':
                if mode == 'late-publication':
                    clock[0] = 300
                if mode == 'cancel-after-publication':
                    raise KeyboardInterrupt('synthetic Stop after publication')

        argv = ['qualify', '--freeze', str(root / 'unit-freeze'), '--sha256', 'unit-freeze',
                '--review', str(root / 'unit-review'), '--review-sha256', 'unit-review',
                '--attempt', str(attempt)]
        stderr = io.StringIO()
        with patch.object(e, 'ROOT', root), patch.object(e, 'ATTEMPT', attempt), \
             patch.object(e, 'admission', side_effect=admit), \
             patch.object(e.owner, 'run_owned', side_effect=run), \
             patch.object(e.c, 'write', side_effect=write), \
             patch.object(e.os, 'link', side_effect=AssertionError('acceptance link forbidden')), \
             patch.object(e.shutil, 'disk_usage', return_value=type('D', (), {'free': 4 * 1024**3})()), \
             patch.object(sys, 'argv', argv), contextlib.redirect_stderr(stderr):
            result = e.main()
        return result, attempt, calls, admissions, stderr.getvalue()

    def test_both_selected_is_diagnostic_only_with_bounded_owner(self):
        code, attempt, calls, admissions, stderr = self.exercise()
        self.assertEqual((code, stderr), (2, ''))
        self.assertEqual(len(calls), 1)
        self.assertEqual(len(admissions), 2)
        self.assertEqual(calls[0][4:], (300, 512 * 1024, 15))
        self.assertEqual(calls[0][0][:4], ['never-execute-test-python', '-I', '-S', '-B'])
        self.assertIn('--worker', calls[0][0])
        outcome = json.loads((attempt / 'outcome.json').read_text())
        self.assertEqual(outcome['classification'], 'DIAGNOSTIC_COLLECTED')
        self.assertEqual(outcome['comparison'], NO_SUPPORT)
        self.assertTrue(outcome['cleanupConfirmed'])
        self.assertFalse(outcome['realLinkerQualified'])
        self.assertFalse(outcome['acceptancePublished'])
        self.assertFalse((attempt / 'accepted-probes.json').exists())

    def test_negative_remains_failed_with_supported_comparison(self):
        code, attempt, calls, admissions, stderr = self.exercise('negative')
        self.assertEqual((code, stderr), (1, ''))
        self.assertEqual((len(calls), len(admissions)), (1, 2))
        outcome = json.loads((attempt / 'outcome.json').read_text())
        self.assertEqual(outcome['classification'], 'DIAGNOSTIC_FAILED')
        self.assertEqual(outcome['comparison'], SUPPORT)
        self.assertFalse(outcome['acceptancePublished'])
        self.assertFalse((attempt / 'accepted-probes.json').exists())

    def test_rejection_cancel_late_admission_and_quarantine_precede_owner(self):
        for mode in ('reject', 'cancel-admission', 'late-admission', 'prior-uncertain',
                     'prior-publication', 'prior-accepted'):
            with self.subTest(mode=mode):
                code, attempt, calls, _, _ = self.exercise(mode)
                self.assertEqual(code, 1)
                self.assertFalse(attempt.exists())
                self.assertEqual(calls, [])

    def test_owner_cleanup_uncertainty_retains_terminal_and_quarantines(self):
        for mode in ('cleanup-timeout', 'cancel-owner', 'signal-owner', 'uncertain'):
            with self.subTest(mode=mode):
                code, attempt, calls, _, _ = self.exercise(mode)
                self.assertEqual((code, len(calls)), (1, 1))
                outcome = json.loads((attempt / 'outcome.json').read_text())
                self.assertEqual(outcome['cleanupStatus'], 'UNCERTAIN')
                self.assertIsNot(outcome['cleanupConfirmed'], True)
                self.assertFalse(outcome['acceptancePublished'])
                self.assertFalse((attempt / 'accepted-probes.json').exists())
                with self.assertRaises(ValueError):
                    e.quarantine(attempt.parent)

    def test_readmission_lateness_output_drift_and_terminal_mismatch_fail_closed(self):
        for mode in ('readmission-failure', 'cancel-readmission', 'late-owner',
                     'post-cleanup-output', 'post-cleanup-source', 'terminal-mismatch'):
            with self.subTest(mode=mode):
                code, attempt, calls, _, _ = self.exercise(mode)
                self.assertEqual((code, len(calls)), (1, 1))
                outcome = json.loads((attempt / 'outcome.json').read_text())
                self.assertEqual(outcome['classification'], 'DIAGNOSTIC_FAILED_OR_REVOKED')
                self.assertEqual(outcome['cleanupStatus'], 'CONFIRMED')
                self.assertTrue(outcome['cleanupConfirmed'])
                self.assertFalse(outcome['acceptancePublished'])
                self.assertFalse((attempt / 'accepted-probes.json').exists())

    def test_late_or_cancelled_publication_retains_original_and_separate_revocation(self):
        for mode in ('late-publication', 'cancel-after-publication'):
            with self.subTest(mode=mode):
                code, attempt, _, _, _ = self.exercise(mode)
                self.assertEqual(code, 1)
                original = json.loads((attempt / 'outcome.json').read_text())
                revoked = json.loads((attempt / 'publication-failure.json').read_text())
                self.assertEqual(original['classification'], 'DIAGNOSTIC_COLLECTED')
                self.assertEqual(revoked['classification'], 'DIAGNOSTIC_FAILED_OR_REVOKED')
                self.assertTrue(revoked['cleanupConfirmed'])
                self.assertFalse(original['acceptancePublished'])
                self.assertFalse(revoked['acceptancePublished'])
                self.assertFalse((attempt / 'accepted-probes.json').exists())
                with self.assertRaises(ValueError):
                    e.quarantine(attempt.parent)

    def test_foreign_acceptance_is_preserved_and_quarantines(self):
        code, attempt, _, _, _ = self.exercise('foreign-accepted')
        self.assertEqual(code, 1)
        self.assertEqual((attempt / 'accepted-probes.json').read_text(), 'foreign synthetic object')
        with self.assertRaises(ValueError):
            e.quarantine(attempt.parent)


if __name__ == '__main__':
    unittest.main()
