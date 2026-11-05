"""Synthetic preparation/contract tests only. No native dispatch or fake approval."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name):
    spec = importlib.util.spec_from_file_location(
        'test_linker_' + name, ROOT / 'scripts/diagnostics/dawn_cpp_linker_v1' / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


p, policy, prep = load('plan'), load('policy'), load('prepare')


def synthetic_text(job):
    """Complete synthetic old-policy witness, never collected native output."""
    quote = json.dumps
    cc = [str(p.CLANG), '-cc1', '-emit-obj', '-fcxx-exceptions', '-fexceptions',
          '-pedantic-errors', '-O0', '-triple', 'arm64-apple-macosx26.5.0',
          '-isysroot', str(p.base_plan.SDK), '-resource-dir', str(p.base_plan.RESOURCE)]
    for row in job['expectedIncludes']:
        cc.extend(['-iframework' if row['framework'] else '-I', row['path']])
    cc.extend(['-main-file-name', 'empty.cpp', '-std=' + job['expectedStandard'],
               '-x', 'c++', '-o', str(Path(job['source']).parent / 'empty-synthetic.o'), job['source']])
    ld = [str(p.LD), '-syslibroot', str(p.base_plan.SDK), '-arch', 'arm64', '-lc++', '-lSystem',
          '-lto_library', str(p.BIN.parent / 'lib/libLTO.dylib'), '-platform_version',
          'macos', '26.5', '26.5', '-o', job['output'], str(Path(job['source']).parent / 'empty-synthetic.o')]
    return ('Apple clang version 21.0.0 (synthetic)\nTarget: arm64-apple-macosx26.5.0\n'
            'Thread model: posix\nInstalledDir: ' + str(p.BIN) + '\n' +
            ' '.join(map(quote, cc)) + '\n' + ' '.join(map(quote, ld)) + '\n')


class LinkerPlanTests(unittest.TestCase):
    def test_exact_seven_queries_preserve_all_six_profiles(self):
        jobs = p.commands()
        self.assertEqual(len(jobs), 7)
        self.assertEqual(jobs[0]['kind'], 'diagnostic-program-lookup')
        self.assertEqual([j['profile'] for j in jobs[1:]],
                         [v + '-' + h + '-' + std for v, h, std in p.base_plan.PROFILES])
        for job, (v, _, std) in zip(jobs[1:], p.base_plan.PROFILES):
            self.assertEqual(job['argv'][1:1 + len(p.base_plan.flags(v, std))], p.base_plan.flags(v, std))
            self.assertEqual(job['argv'].count(p.OPTION), 1)
            self.assertEqual(job['argv'].count('-###'), 1)
            self.assertNotIn('-E', job['argv'])
            self.assertNotIn('-c', job['argv'])
            self.assertEqual(job['declaredLinker'], str(p.LD))

    def test_lookup_is_identical_to_failed_recipe_but_never_qualifies(self):
        old = p.prior.plan.commands(p.ATTEMPT, json.loads(
            (ROOT / 'planning/fixtures/dawn-cpp-backend-v1/metadata-execution-freeze-r2.json').read_text())['nativeFiles'])
        self.assertEqual(p.commands()[0]['argv'], next(j['argv'] for j in old if j['id'] == 'linker-path'))
        self.assertFalse(p.commands()[0]['qualifiesLinker'])

    def test_exact_compiler_sandbox_keeps_linker_and_every_helper_denied(self):
        profile = p.sandbox()
        self.assertEqual(profile, p.prior.backend.sandbox(p.ATTEMPT, [str(p.CLANG)]))
        self.assertEqual(profile.count('(allow process-exec'), 1)
        self.assertIn('(deny process-exec)', profile)
        self.assertNotIn(str(p.LD), profile)
        self.assertNotIn('(allow process-exec (subpath', profile)

    def test_environment_has_no_ambient_compiler_path_or_dyld_overrides(self):
        env = p.environment()
        self.assertEqual(set(env), {'PATH', 'HOME', 'TMPDIR', 'LANG', 'LC_ALL'})
        self.assertEqual(env['PATH'], str(p.BIN))
        self.assertEqual(env['HOME'], str(p.ATTEMPT))


class LinkerOutputTests(unittest.TestCase):
    def test_bare_lookup_remains_diagnostic_and_old_validator_still_rejects(self):
        job = p.commands()[0]
        self.assertEqual(policy.validate(job, 'ld\n')['classification'], 'BARE_NAME_UNRESOLVED')
        self.assertFalse(policy.validate(job, 'ld\n')['qualified'])
        old_job = dict(job, kind='linker-path')
        with self.assertRaisesRegex(ValueError, 'unexpected selected linker'):
            p.prior.validate_output(old_job, 'ld\n', {})

    def test_absolute_lookup_is_not_job_selection_credit(self):
        result = policy.validate(p.commands()[0], str(p.LD) + '\n')
        self.assertFalse(result['qualified'])
        self.assertEqual(result['classification'], 'ABSOLUTE_LOOKUP_ONLY')

    def test_lookup_rejects_alternate_relative_multiple_or_malformed_values(self):
        for value in ['', 'ld', ' ld\n', 'ld\r\n', './ld\n', '/usr/bin/ld\n', str(p.LD) + '\nld\n', 'warning: lookup\nld\n']:
            with self.subTest(value=value), self.assertRaises(ValueError):
                policy.validate(p.commands()[0], value)

    def test_lookup_cannot_be_marked_qualified(self):
        job = dict(p.commands()[0], qualifiesLinker=True)
        with self.assertRaises(ValueError):
            policy.validate(job, 'ld\n')

    def test_all_six_synthetic_plans_require_declared_and_selected_agreement(self):
        for job in p.commands()[1:]:
            with self.subTest(job=job['id']):
                result = policy.validate(job, synthetic_text(job))
                self.assertEqual(result['selectedLinker'], result['declaredLinker'])
                self.assertFalse(result['linkerExecuted'])

    def test_relative_alias_and_other_supplier_rejected_even_if_normalizable(self):
        job = p.commands()[1]
        for value in ['ld', './ld', str(p.BIN / '../bin/ld'), '/usr/bin/ld', str(p.CLANG)]:
            text = synthetic_text(job).replace(json.dumps(str(p.LD)), json.dumps(value))
            with self.subTest(value=value), self.assertRaises(ValueError):
                policy.validate(job, text)

    def test_old_closed_contract_still_rejects_options_helpers_and_diagnostics(self):
        job = p.commands()[1]; text = synthetic_text(job)
        for bad in [text + 'warning: ignored option\n', text + '"/extra/helper"\n',
                    text.replace('"-cc1"', '"@unbound"'),
                    text.replace('"-lc++"', '"-lOTHER"'),
                    text.replace('"-cc1"', '"-cc1" "-unknown-default"')]:
            with self.subTest(bad=bad[-80:]), self.assertRaises(ValueError):
                policy.validate(job, bad)

    def test_missing_conflicting_or_wrong_declared_selection_rejected(self):
        original = p.commands()[1]
        changes = []
        for removed in [p.OPTION, '-###']:
            j = copy.deepcopy(original); j['argv'].remove(removed); changes.append(j)
        for added in [p.OPTION, '--ld-path=/usr/bin/ld', '-fuse-ld=lld']:
            j = copy.deepcopy(original); j['argv'].append(added); changes.append(j)
        changes.append(dict(original, declaredLinker='/usr/bin/ld'))
        for j in changes:
            with self.subTest(argv=j['argv']), self.assertRaises(ValueError):
                policy.validate(j, synthetic_text(original))

    def results(self):
        return [dict(id=j['id'], status='PASSED', result=policy.validate(j, 'ld\n' if i == 0 else synthetic_text(j)))
                for i, j in enumerate(p.commands())]

    def test_complete_results_only_claim_dry_run_agreement(self):
        result = policy.validate_complete(self.results())
        self.assertFalse(result['compilerQualified'])
        self.assertFalse(result['historicalFailureRepaired'])
        self.assertEqual(result['rootCause'], 'UNPROVED')

    def test_incomplete_duplicate_and_reordered_profiles_never_complete(self):
        rows = self.results()
        for bad in [rows[:-1], rows + [rows[-1]], [rows[0], *reversed(rows[1:])], [rows[0]] * 7]:
            with self.subTest(ids=[x['id'] for x in bad]), self.assertRaises(ValueError):
                policy.validate_complete(bad)

    def test_failed_or_disagreeing_result_cannot_publish_agreement(self):
        for field, value in [('status', 'FAILED'), ('selectedLinker', '/unbound/ld'), ('linkerExecuted', True)]:
            rows = self.results()
            if field == 'status': rows[1][field] = value
            else: rows[1]['result'][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                policy.validate_complete(rows)


class LinkerPreparationTests(unittest.TestCase):
    def test_scan_finds_across_blocks_and_retains_offsets_once(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'synthetic-binary'
            content = b'x' * 60 + b'ld-path=' + b'x' * 54 + b'print-prog-name=' + b'_access\0'
            path.write_bytes(content)
            result = prep.scan(path, prep.c.file_row(path), 64)
            for needle in prep.NEEDLES:
                key = needle.decode().replace('\0', '\\0')
                self.assertEqual(result['observations'][key], [content.index(needle)])
            self.assertFalse(result['executableBehaviorProved'])

    def test_scan_repeated_overlap_matches_are_not_double_counted(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'synthetic'
            path.write_bytes(b'x' * 48 + b'ld-path=ld-path=' + b'x' * 80)
            self.assertEqual(prep.scan(path, prep.c.file_row(path), 64)['observations']['ld-path='], [48, 56])

    def test_scan_rejects_byte_drift_and_invalid_memory_bounds(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'synthetic'; path.write_bytes(b'old'); row = prep.c.file_row(path)
            path.write_bytes(b'new')
            with self.assertRaises(ValueError): prep.scan(path, row)
            for n in [0, 63, 2 * prep.MIB + 1, True]:
                with self.subTest(n=n), self.assertRaises(ValueError): prep.scan(path, prep.c.file_row(path), n)

    def test_scan_occurrence_cap_bounds_samples_and_marks_omitted_occurrences(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'synthetic'; path.write_bytes(b'ld-path= ' * 17)
            result = prep.scan(path, prep.c.file_row(path), 64)
            self.assertEqual(len(result['observations']['ld-path=']), 16)
            self.assertTrue(result['additionalOccurrencesOmitted']['ld-path='])
            self.assertFalse(result['completeOccurrenceInventory'])

    def test_changed_missing_and_duplicate_freeze_inputs_reject(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'input'; path.write_bytes(b'original'); row = prep.c.file_row(path)
            prep.verify_rows([row])
            with self.assertRaises(ValueError): prep.verify_rows([row, row])
            path.write_bytes(b'changed')
            with self.assertRaises(ValueError): prep.verify_rows([row])
            path.unlink()
            with self.assertRaises(OSError): prep.verify_rows([row])

    def test_changed_prior_hash_blocks_before_evidence_use(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'old.json'; path.write_text('{}')
            with self.assertRaisesRegex(ValueError, 'changed prior evidence'):
                prep.bound(path, '0' * 64)

    def test_tampered_query_freeze_rejects_without_native_boundary(self):
        f = dict(commands=p.commands(), seconds=300)
        changed = copy.deepcopy(f); changed['commands'][1]['argv'].remove('-###')
        with patch.object(prep, 'historical_inputs', return_value=({}, [])), patch.object(prep, 'candidate', return_value=f), \
             patch.object(prep, 'verify_rows', side_effect=AssertionError('must reject before hashing')):
            with self.assertRaisesRegex(ValueError, 'changed investigation recipe'):
                prep.verify(changed)

    def test_data_only_admission_requires_future_attempt_absent(self):
        f = dict(inputs=[], trees=[])
        with patch.object(prep, 'historical_inputs', return_value=({}, [])), patch.object(prep, 'candidate', return_value=f), \
             patch.object(prep.os.path, 'lexists', return_value=True):
            with self.assertRaisesRegex(ValueError, 'not fresh'): prep.verify(f)

    def test_preparation_cli_never_dispatches_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / 'probe-freeze-r1.json'; out.write_text('preserve')
            with patch.object(prep, 'FIX', Path(d).resolve()), patch.object(prep, 'historical_inputs', return_value=({}, [])), \
                 patch.object(prep, 'candidate', return_value={}), patch.object(prep, 'verify', return_value={}), \
                 patch('sys.argv', ['prepare', '--output', str(out)]), \
                 patch.object(prep.q.owner, 'run_owned', side_effect=AssertionError('no owner')) as owner, \
                 patch.object(prep.q.backend, 'dispatch', side_effect=AssertionError('no dispatch')) as dispatch:
                with self.assertRaises(FileExistsError): prep.main()
                self.assertEqual(owner.call_count, 0); self.assertEqual(dispatch.call_count, 0)
            self.assertEqual(out.read_text(), 'preserve')


if __name__ == '__main__':
    unittest.main()
