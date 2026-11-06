"""Synthetic executor regressions. No native/toolchain/vendor process runs."""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


e = load('executor_regression', 'scripts/diagnostics/dawn_cpp_linker_executor_v1/qualify.py')
fixtures = load('linker_synthetic_fixture', 'scripts/tests/test_dawn_cpp_linker.py')


def results(attempt):
    return [dict(id=j['id'], status='PASSED', result=e.policy.validate(
        j, 'ld\n' if index == 0 else fixtures.synthetic_text(j)))
        for index, j in enumerate(e.plan.commands(attempt))]


class ExecutorAdmissionTests(unittest.TestCase):
    def values(self):
        value = json.loads(e.PROPOSAL.read_text())
        rows = {r['path']: r for r in value['inputs']}
        for path in e.required_inputs(value) - set(rows): rows[path] = e.c.file_row(path)
        freeze = dict(protocol=e.PROTOCOL, scope=e.SCOPE, proposalSha256=e.PROPOSAL_SHA,
                      proposalReviewSha256=e.PROPOSAL_REVIEW_SHA, implementationSha256=e.owner.digest(e.__file__),
                      contract=e.contract(value), backendCatalog='planning/fixtures/dawn-cpp-backend-v1/backend-native-catalog.json',
                      cacheMap='/System/Volumes/Preboot/Cryptexes/OS/System/Library/dyld/dyld_shared_cache_arm64e.map',
                      inputs=list(rows.values()), trees=value['trees'], nativeFiles=value['nativeFiles'])
        # In-memory unit data only, never written as an approval or native freeze.
        review = dict(verdict='PASS', scope=e.SCOPE, freezeSha256='unit-freeze')
        return value, freeze, review

    def admit(self, value, freeze, review, checks):
        real_read = e.q.base.read_json_bound
        def read(path, *args):
            if str(path) == 'unit-review': return review, 'unit-review'
            if str(path) == 'unit-freeze': return freeze, 'unit-freeze'
            return real_read(path, *args)
        with patch.object(e.q.base, 'read_json_bound', side_effect=read), \
             patch.object(e, 'proposal', return_value=value), \
             patch.object(e, 'check_inputs', side_effect=checks), \
             patch.object(e.owner, 'run_owned', side_effect=AssertionError('no owner in admission')) as owner:
            result = e.admission('unit-freeze', 'unit-freeze', 'unit-review', 'unit-review')
            self.assertEqual(owner.call_count, 0)
            return result

    def test_actual_required_set_accepts_complete_in_memory_bindings_without_owner(self):
        value, freeze, review = self.values(); calls = []
        self.assertIs(self.admit(value, freeze, review, lambda *a: calls.append(a)), freeze)
        self.assertEqual(len(calls), 1)

    def test_missing_current_or_historical_dependency_rejects_before_input_checks(self):
        for path in [str(e.HERE / 'qualify.py'), str(e.HERE / 'freeze.py'),
                     str(ROOT / 'AGENTS.md'), str(ROOT / 'docs/CPP_BUILD_SKILLS_PROVENANCE.json'),
                     '/Users/kezileo/.codex/skills/clang/SKILL.md',
                     str(ROOT / 'planning/fixtures/dawn-cpp-toolchain-v1/observed-inspector-helper.json')]:
            value, freeze, review = self.values(); calls = []
            freeze['inputs'] = [r for r in freeze['inputs'] if r['path'] != path]
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, 'unbound executor dependency'):
                self.admit(value, freeze, review, lambda *a: calls.append(a))
            self.assertEqual(calls, [])

    def test_changed_proposal_binding_rejects_before_owner(self):
        value, freeze, review = self.values(); freeze = copy.deepcopy(freeze)
        freeze['inputs'][0]['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'changed proposal input binding'):
            self.admit(value, freeze, review, lambda *a: self.fail('late input checks'))

    def test_recipe_limits_sandbox_and_arguments_cannot_be_changed(self):
        for change in ('deadline', 'sandbox', 'argv', 'profile', 'linker'):
            value, freeze, review = self.values(); freeze = copy.deepcopy(freeze)
            if change == 'deadline': freeze['contract']['seconds'] = 301
            if change == 'sandbox': freeze['contract']['compilerSandbox'] += '(allow process-exec)'
            if change == 'argv': freeze['contract']['commands'][1]['argv'].remove('-###')
            if change == 'profile': freeze['contract']['allowedExecutablePaths'].append(str(e.plan.LD))
            if change == 'linker': freeze['contract']['declaredLinker'] = '/usr/bin/ld'
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, 'changed seven-probe contract'):
                self.admit(value, freeze, review, lambda *a: self.fail('late input checks'))

    def test_duplicate_bindings_changed_trees_and_bad_review_reject(self):
        for change in ('duplicate', 'trees', 'review', 'code'):
            value, freeze, review = self.values(); freeze = copy.deepcopy(freeze)
            if change == 'duplicate': freeze['inputs'].append(freeze['inputs'][0])
            if change == 'trees': freeze['trees'] = []
            if change == 'review': review['scope'] = 'other'
            if change == 'code': freeze['implementationSha256'] = '0' * 64
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.admit(value, freeze, review, lambda *a: self.fail('late input checks'))

    def test_changed_actual_payload_rejects_at_input_verification(self):
        value, freeze, review = self.values()
        with self.assertRaisesRegex(ValueError, 'changed bytes'):
            self.admit(value, freeze, review, lambda *a: (_ for _ in ()).throw(ValueError('changed bytes')))

    def test_actual_hash_verifier_rejects_changed_bound_file_before_owner(self):
        value, freeze, review = self.values()
        real_check = e.check_inputs
        with tempfile.TemporaryDirectory() as d:
            path = Path(d).resolve() / 'bound-input'; path.write_bytes(b'original')
            freeze['inputs'].insert(0, e.c.file_row(path))
            path.write_bytes(b'TAMPERED')
            with self.assertRaisesRegex(ValueError, 'missing/tampered preparation input'):
                self.admit(value, freeze, review, real_check)


class ExecutorWorkerTests(unittest.TestCase):
    def exercise(self, mode='pass'):
        tmp = tempfile.TemporaryDirectory(); self.addCleanup(tmp.cleanup)
        attempt = Path(tmp.name).resolve(); clock = [0.0]; calls = []
        f = dict(contract=dict(commands=e.plan.commands(attempt)))
        def check(deadline): e.require(clock[0] < deadline, 'deadline')
        def dispatch(job, path, frozen, deadline, trace):
            calls.append(job['id'])
            if mode == 'cancel': raise KeyboardInterrupt('synthetic Stop')
            if mode == 'failed': raise ValueError('unexpected default')
            if mode == 'late': clock[0] = deadline
            return 'ld\n' if job['kind'] == 'diagnostic-program-lookup' else fixtures.synthetic_text(job)
        original_tree = e.c.tree
        def tree(*args):
            result = original_tree(*args)
            if mode == 'late-artifacts': clock[0] = 30
            return result
        error = None
        with patch.object(e, 'now', side_effect=lambda: clock[0]), patch.object(e, 'check', side_effect=check), \
             patch.object(e.q, 'native_rows', return_value=({}, [])), patch.object(e, 'dispatch', side_effect=dispatch), \
             patch.object(e, 'check_inputs', return_value=None), patch.object(e.c, 'tree', side_effect=tree):
            try: e.worker(f, attempt, 300)
            except (ValueError, KeyboardInterrupt) as caught: error = caught
        return json.loads((attempt / 'probe-report.json').read_text()), calls, error

    def test_complete_worker_keeps_diagnostic_unqualified_and_all_six_plans_separate(self):
        report, calls, error = self.exercise()
        self.assertIsNone(error); self.assertEqual(len(calls), 7)
        self.assertEqual(report['status'], 'DRY_RUN_SELECTION_PASSED_CPP_NOT_EXECUTED')
        self.assertFalse(report['commands'][0]['result']['qualified'])
        self.assertFalse(report['compilerQualified']); self.assertEqual(report['notRunCommands'], [])

    def test_first_failure_retains_six_not_run_without_continuation(self):
        report, calls, error = self.exercise('failed')
        self.assertIsInstance(error, ValueError); self.assertEqual(len(calls), 1)
        self.assertEqual(report['commands'][0]['status'], 'FAILED')
        self.assertEqual(len(report['notRunCommands']), 6)

    def test_cancellation_stops_immediately_and_retains_terminal_report(self):
        report, calls, error = self.exercise('cancel')
        self.assertIsInstance(error, KeyboardInterrupt)
        self.assertEqual(len(calls), 1); self.assertEqual(report['status'], 'FAILED')

    def test_late_native_or_artifact_check_cannot_mark_query_passed(self):
        for mode in ('late', 'late-artifacts'):
            with self.subTest(mode=mode):
                report, calls, error = self.exercise(mode)
                self.assertIsInstance(error, ValueError)
                self.assertEqual(report['commands'][0]['status'], 'FAILED')
                self.assertEqual(len(report['notRunCommands']), 6)

    def test_dispatch_retains_exact_compiler_profile_and_argv(self):
        job = e.plan.commands()[0]; original = copy.deepcopy(job); calls = []
        catalog = {}; f = dict(backendCatalog='unit', contract=dict(
            compilerSandbox=e.plan.sandbox(), nativeEnvironment=e.plan.environment()))
        def execute(role_job, *args, **kwargs):
            calls.append((role_job, args, kwargs)); return 'ld\n'
        with patch.object(e, 'check'), patch.object(e, 'remaining', return_value=30), \
             patch.object(e.q, 'data', return_value=catalog), \
             patch.object(e.q.backend, 'allowed_paths', return_value=[str(e.plan.CLANG)]), \
             patch.object(e.q.backend, 'dispatch', side_effect=execute):
            self.assertEqual(e.dispatch(job, e.plan.ATTEMPT, f, 100, None), 'ld\n')
        self.assertEqual(job, original); self.assertEqual(calls[0][0]['argv'], job['argv'])
        self.assertNotIn(str(e.plan.LD), f['contract']['compilerSandbox'])

    def test_extra_exec_permission_blocks_dispatch(self):
        f = dict(backendCatalog='unit', contract=dict(compilerSandbox=e.plan.sandbox(), nativeEnvironment={}))
        with patch.object(e, 'check'), patch.object(e.q, 'data', return_value={}), \
             patch.object(e.q.backend, 'allowed_paths', return_value=[str(e.plan.CLANG), str(e.plan.LD)]), \
             patch.object(e.q.backend, 'dispatch', side_effect=AssertionError('must not dispatch')) as dispatch:
            with self.assertRaisesRegex(ValueError, 'changed compiler-only sandbox'):
                e.dispatch(e.plan.commands()[0], e.plan.ATTEMPT, f, 100, None)
            self.assertEqual(dispatch.call_count, 0)


class ExecutorCoordinatorTests(unittest.TestCase):
    def exercise(self, mode='pass'):
        tmp = tempfile.TemporaryDirectory(); self.addCleanup(tmp.cleanup)
        root = Path(tmp.name).resolve(); attempt = root / 'attempts/linker-1'
        clock = [0.0]; calls = []; admissions = [0]; links = [0]
        if mode == 'prior-uncertain':
            prior = attempt.parent / 'old'; prior.mkdir(parents=True)
            (prior / 'owner-started.json').write_text('{}')
            (prior / 'outcome.json').write_text('{"cleanupConfirmed":false}')
        f = dict(contract=dict(attempt=str(attempt), python='synthetic-only', ownerEnvironment={}))
        def admit(*args):
            admissions[0] += 1
            if mode == 'reject' or (mode == 'changed-after-owner' and admissions[0] > 1):
                raise ValueError('input/review changed')
            if mode == 'cancel-admission': raise KeyboardInterrupt('Stop before owner')
            if mode == 'late-admission': clock[0] = 300
            return f
        def check(deadline): e.require(clock[0] < deadline, 'deadline')
        def left(deadline, maximum):
            check(deadline); return min(maximum, deadline - clock[0])
        def run(*args):
            calls.append(args); clock[0] = 20
            if mode == 'cleanup-error': raise subprocess.TimeoutExpired('owned cleanup', 15)
            if mode == 'cancel-owner': raise KeyboardInterrupt('Stop')
            if mode == 'signal-owner': raise ValueError('analysis interrupted by signal 15')
            rows = results(attempt)
            if mode == 'incomplete': rows.pop()
            if mode == 'failed-result': rows[1]['status'] = 'FAILED'
            if mode == 'duplicate': rows[-1] = rows[0]
            e.c.write(attempt / 'probe-report.json', dict(status='DRY_RUN_SELECTION_PASSED_CPP_NOT_EXECUTED',
                      commands=rows, notRunCommands=[]), 2 * e.MIB)
            if mode == 'late-owner': clock[0] = 300
            return dict(classification='ANALYSIS_COMMAND_FAILED' if mode == 'failed-worker' else 'ANALYSIS_COMMAND_SUCCEEDED',
                        cleanupConfirmed=mode != 'uncertain', elapsedSeconds=20, exitCode=0)
        original_write, original_link = e.c.write, e.os.link
        def write(path, value, maximum):
            original_write(path, value, maximum)
            if Path(path).name == 'accepted-probes.pending.json':
                if mode == 'late-pending': clock[0] = 300
                if mode == 'cancel-pending': raise KeyboardInterrupt('Stop during publication preparation')
        def publish(source, target):
            links[0] += 1
            if mode == 'cancel-before-link': raise KeyboardInterrupt('Stop before link')
            original_link(source, target)
            if mode == 'late-link': clock[0] = 300
            if mode == 'cancel-after-link': raise KeyboardInterrupt('Stop after link')
            if mode == 'foreign-link' and Path(target).name == 'accepted-probes.json':
                Path(target).unlink(); Path(target).write_text('foreign object')
                clock[0] = 300
        argv = ['qualify', '--freeze', 'unit', '--sha256', 'unit', '--review', 'unit',
                '--review-sha256', 'unit', '--attempt', str(attempt)]
        with patch.object(e, 'ROOT', root), patch.object(e, 'now', side_effect=lambda: clock[0]), \
             patch.object(e, 'remaining', side_effect=left), patch.object(e, 'check', side_effect=check), \
             patch.object(e, 'admission', side_effect=admit), patch.object(e.owner, 'run_owned', side_effect=run), \
             patch.object(e.c, 'write', side_effect=write), patch.object(e.os, 'link', side_effect=publish), \
             patch.object(e.shutil, 'disk_usage', return_value=type('D', (), {'free': 4 * 1024 ** 3})()), \
             patch.object(sys, 'argv', argv), contextlib.redirect_stderr(io.StringIO()):
            result = e.main()
        return result, attempt, calls

    def test_success_is_only_seven_probe_dry_run_agreement(self):
        code, attempt, calls = self.exercise()
        self.assertEqual(code, 2); self.assertEqual(len(calls), 1)
        accepted = json.loads((attempt / 'accepted-probes.json').read_text())
        self.assertEqual(accepted['qualification'], 'DRY_RUN_SELECTION_ONLY')
        self.assertFalse(accepted['compilerQualified']); self.assertTrue(accepted['cleanupConfirmed'])
        self.assertEqual(calls[0][-1], 15); self.assertEqual(calls[0][-2], 512 * 1024)
        self.assertEqual(calls[0][4], 300)

    def test_rejection_and_admission_cancel_prevent_owner_creation(self):
        for mode in ('reject', 'cancel-admission', 'late-admission', 'prior-uncertain'):
            with self.subTest(mode=mode):
                code, attempt, calls = self.exercise(mode)
                self.assertEqual(code, 1); self.assertFalse(attempt.exists()); self.assertEqual(calls, [])

    def test_cleanup_exception_cancel_and_signal_retain_uncertain_terminal(self):
        for mode in ('cleanup-error', 'cancel-owner', 'signal-owner', 'uncertain'):
            with self.subTest(mode=mode):
                code, attempt, _ = self.exercise(mode)
                self.assertEqual(code, 1)
                value = json.loads((attempt / 'outcome.json').read_text())
                self.assertEqual(value['cleanupStatus'], 'UNCERTAIN')
                self.assertFalse((attempt / 'accepted-probes.json').exists())
                with self.assertRaises(ValueError): e.quarantine(attempt.parent)

    def test_late_worker_changed_inputs_failed_or_incomplete_results_cannot_publish(self):
        for mode in ('late-owner', 'changed-after-owner', 'incomplete', 'failed-result', 'duplicate', 'failed-worker'):
            with self.subTest(mode=mode):
                code, attempt, _ = self.exercise(mode)
                self.assertEqual(code, 1); self.assertFalse((attempt / 'accepted-probes.json').exists())
                self.assertTrue(json.loads((attempt / 'outcome.json').read_text())['cleanupConfirmed'])

    def test_late_and_cancelled_publication_revokes_owned_links(self):
        for mode in ('late-pending', 'cancel-pending', 'late-link', 'cancel-before-link', 'cancel-after-link'):
            with self.subTest(mode=mode):
                code, attempt, _ = self.exercise(mode)
                self.assertEqual(code, 1); self.assertFalse((attempt / 'accepted-probes.json').exists())
                self.assertTrue((attempt / 'outcome.json').exists())

    def test_foreign_public_object_is_not_deleted_and_quarantines_future_admission(self):
        code, attempt, _ = self.exercise('foreign-link')
        self.assertEqual(code, 1)
        self.assertEqual((attempt / 'accepted-probes.json').read_text(), 'foreign object')
        value = json.loads((attempt / 'outcome.json').read_text())
        self.assertTrue(value['publicationUncertain']); self.assertTrue(value['publicAcceptancePresent'])
        with self.assertRaises(ValueError): e.quarantine(attempt.parent)

    def test_uncertain_revocation_without_public_acceptance_still_quarantines(self):
        original = e.q.base.base.revoke_links
        def uncertain_after_unlinks(published):
            original(published)
            raise OSError('uncertain result after owned unlink effects')
        with patch.object(e.q.base.base, 'revoke_links', side_effect=uncertain_after_unlinks):
            code, attempt, _ = self.exercise('late-link')
        self.assertEqual(code, 1)
        outcome = json.loads((attempt / 'outcome.json').read_text())
        self.assertTrue(outcome['cleanupConfirmed'])
        self.assertTrue(outcome['publicationUncertain'])
        self.assertFalse(outcome['publicAcceptancePresent'])
        self.assertFalse((attempt / 'accepted-probes.json').exists())
        with self.assertRaisesRegex(ValueError, 'uncertain prior revocation'):
            e.quarantine(attempt.parent)

    def test_signal_handlers_restore_after_interruption_scope(self):
        previous = {s: signal.getsignal(s) for s in (signal.SIGINT, signal.SIGTERM)}
        for s in previous:
            with self.subTest(signal=s), self.assertRaises(KeyboardInterrupt):
                with e.interruptions(): signal.getsignal(s)(s, None)
            self.assertEqual(signal.getsignal(s), previous[s])


if __name__ == '__main__':
    unittest.main()
