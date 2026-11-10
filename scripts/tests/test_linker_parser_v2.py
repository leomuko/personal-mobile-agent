"""Offline v2 regressions against recorded 155/24-token output; never native QA.

Run with ``python3 -B -m unittest discover -s scripts/tests -p test_linker_parser_v2.py``.
Only stdlib is needed. No test creates files, processes, attempts, or marker claims.
R31/R32 exercise a TEST-ONLY fake boundary, not an implemented runtime bridge.

Counting definition: ASSERTION_COUNT counts each evaluated verify() predicate,
including expected-exception checks and each field check, whether passing or
failing. CASE_LOG has one JSON-serializable row per such explicit assertion;
``kind == 'mutation'`` identifies actual individual mutated-input executions,
``kind == 'accepted'`` identifies parsed witnesses, and ``kind == 'fakeboundary'``
identifies test-only lifecycle scenarios. Family names alone are never counted
as mutation coverage. Fixture/setup checks are labelled ``check``. Repeated suite
runs accumulate; a ledger runner may clear CASE_LOG and set ASSERTION_COUNT = 0.
No unittest assert*, assertRaises, bare assert, or mock implicit assertions hide
additional assertion counts. subTest keeps individual mutations independent.
"""
import base64
from dataclasses import FrozenInstanceError, fields
import hashlib
import importlib.util
import json
from pathlib import Path
import shlex
import sys
import unittest

ROOT = Path(__file__).absolute().parents[2]  # lexical only; never resolve context paths
FIX = ROOT / 'planning/fixtures/linker-parser-contract-v2'
CONTRACT = json.loads((FIX / 'contract-proposal.json').read_text())
PLAN = json.loads((FIX / 'regression-plan.json').read_text())
POSITIVE = (FIX / 'observed-positive.raw.txt').read_bytes()
NEGATIVE = (FIX / 'synthetic-negative.raw.txt').read_bytes()  # CONSTRUCTED, not native evidence
BIND = CONTRACT['sampleBindings']
ATTEMPT = BIND['ATTEMPT']
TARGET = BIND['SYNTHETIC_TARGET']
PRESENT = 'synthetic-exception-present'
ABSENT = 'synthetic-exception-absent'
SELECTED = 'EXACT_SYNTHETIC_PATH_SELECTED'
REJECTED = 'EXACT_SYNTHETIC_PATH_REJECTED'
SUPPORTS = 'SUPPORTS_POLICY_SENSITIVE_VALIDATION_ONLY'
NO_SUPPORT = 'NO_SUPPORT_FOR_POLICY_COUPLING_IN_THIS_COMPARISON'
INCONCLUSIVE = 'INCONCLUSIVE'
DIAGNOSTIC = "clang: error: invalid linker name in argument '--ld-path=" + TARGET + "'"
CC = tuple(shlex.split(POSITIVE.decode().splitlines()[4], comments=False, posix=True))
LD = tuple(shlex.split(POSITIVE.decode().splitlines()[5], comments=False, posix=True))
CASE_LOG = []
ASSERTION_COUNT = 0


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


parser = load_module('linker_parser_v2_regression_runtime',
                     ROOT / 'scripts/diagnostics/linker_parser_v2/parser.py')


def serial(value):
    """Keep ledger bounded and JSON-compatible without losing diagnostic identity."""
    if value is None or type(value) in (bool, int, float, str):
        return value
    if isinstance(value, (list, tuple)):
        return [serial(v) for v in value]
    return repr(value)


def verify(family, case, actual, expected=True, *, kind='check'):
    global ASSERTION_COUNT
    ASSERTION_COUNT += 1
    passed = actual == expected
    CASE_LOG.append(dict(family_id=family, case_id=case, expected=serial(expected),
                         actual=serial(actual), passed=passed, kind=kind))
    if not passed:
        raise AssertionError(f'{family}/{case}: expected {expected!r}, got {actual!r}')


def raises(family, case, action, exception=None, *, kind='mutation'):
    """Count exactly one explicit exception-type assertion per attempted case."""
    exception = exception or parser.ParseError
    try:
        action()
    except Exception as error:
        actual = exception.__name__ if isinstance(error, exception) else type(error).__name__
        verify(family, case, actual, exception.__name__, kind=kind)
        CASE_LOG[-1]['exception_type'] = type(error).__name__
        CASE_LOG[-1]['exception_message'] = str(error)
        return error
    verify(family, case, 'returned without exception', exception.__name__, kind=kind)


def context(attempt=ATTEMPT, target=TARGET):
    return parser.Context(attempt=attempt, synthetic_target=target)


def render(cc=CC, ld=LD, headers=None):
    """Serialize changed words, preserving shlex semantics, never execute them."""
    return ('\n'.join([*(headers or CONTRACT['headerExact']),
                       shlex.join(cc), shlex.join(ld)]) + '\n').encode()


def edit(section, start, count, replacement):
    words = list(CC if section == 'frontend' else LD)
    words[start:start + count] = replacement
    return render(cc=words) if section == 'frontend' else render(ld=words)


def units(section, key=None):
    return [u for u in CONTRACT[section]['orderedUnits'] if key is None or u['key'] == key]


class ParserV2Regressions(unittest.TestCase):
    def reject(self, family, case, raw, *, ctx=None, case_id=PRESENT, exit_code=0):
        with self.subTest(family=family, case=case):
            return raises(family, case, lambda: parser.parse_output(
                raw, context=ctx or context(), case_id=case_id, exit_code=exit_code))

    def accept(self, family, case, raw=POSITIVE, *, ctx=None, case_id=PRESENT,
               exit_code=0, classification=SELECTED):
        ctx = ctx or context()
        try:
            value = parser.parse_output(raw, context=ctx, case_id=case_id, exit_code=exit_code)
        except Exception as error:
            verify(family, case, type(error).__name__ + ': ' + str(error),
                   classification, kind='accepted')
        verify(family, case, value.classification, classification, kind='accepted')
        expected = dict(context=ctx, case_id=case_id, raw_exit_code=exit_code,
                        command_status='PASSED' if exit_code == 0 else 'FAILED',
                        terminal_status='OFFLINE_STRUCTURAL_COMPATIBLE' if exit_code == 0 else 'FAILED',
                        raw_sha256=hashlib.sha256(raw).hexdigest(),
                        object_path=shlex.split([line for line in raw.decode().split('\n')
                                                if line.strip(' \t')][-2])[151],
                        scope='OFFLINE_STRUCTURAL_ONLY', real_linker_qualified=False,
                        compiler_qualified=False, acceptance_publication_allowed=False)
        for key, wanted in expected.items():
            verify(family, case + '/' + key, getattr(value, key), wanted)
        verify(family, case + '/frontend-tuple', type(value.frontend_tokens) is tuple)
        verify(family, case + '/linker-tuple', type(value.linker_tokens) is tuple)
        verify(family, case + '/frontend-count', len(value.frontend_tokens), 155)
        verify(family, case + '/linker-count', len(value.linker_tokens), 24)
        return value

    def mutate_unit(self, family, section, unit, variants):
        for label, replacement in variants:
            self.reject(family, unit['id'] + '/' + label,
                        edit(section, unit['tokenStart'], unit['tokenCount'], replacement))

    def test_R01_recorded_positive(self):
        verify('R01', 'actual-raw-sha256', hashlib.sha256(POSITIVE).hexdigest(),
               '73369da40748b9f990dc0feb20805797c168e0253d32dd7323fc940caab1dfea')
        verify('R01', 'actual-raw-bytes', len(POSITIVE), 5622)
        verify('R01', 'all32-plan-families', [r['id'] for r in PLAN['families']],
               [f'R{i:02}' for i in range(1, 33)])
        value = self.accept('R01', 'recorded-actual-positive')
        verify('R01', 'all-frontend-words-retained', value.frontend_tokens, CC)
        verify('R01', 'all-linker-words-retained', value.linker_tokens, LD)
        for section, expected in (('frontend', CC), ('linkerPositive', LD)):
            verify('R01', section + '/fixture-matches-inventory',
                   tuple(t for u in units(section) for t in u['observedTokens']), expected)

    def test_R02_historical_v1_unchanged(self):
        seal = json.loads((ROOT / 'planning/fixtures/linker-executable-access-v1/access-1-result-lock.json').read_text())
        for row in seal['bindings']:
            raw = (ROOT / row['path']).read_bytes()
            verify('R02', row['path'] + '/bytes', len(raw), row['bytes'])
            verify('R02', row['path'] + '/sha256', hashlib.sha256(raw).hexdigest(), row['sha256'])
        # Hash all historical source/evidence rows sealed by the old execution freeze.
        freeze = json.loads((ROOT / 'planning/fixtures/linker-executable-access-v1/execution-freeze-r1.json').read_text())
        for row in freeze['inputs']:
            if row['path'].endswith('.py') and '/scripts/' in row['path']:
                verify('R02', row['path'] + '/old-source-sha256',
                       hashlib.sha256(Path(row['path']).read_bytes()).hexdigest(), row['sha256'])
        old = load_module('linker_v2_historical_policy', ROOT / 'scripts/diagnostics/linker_executable_access_v1/policy.py')
        job = freeze['contract']['commands'][0]
        error = raises('R02', 'v1-still-rejects-recorded-positive',
                       lambda: old.classify(job, POSITIVE, 0), ValueError, kind='check')
        verify('R02', 'first-v1-rejection', str(error),
               'unfrozen frontend option: -Wundef-prefix=TARGET_OS_')
        report = json.loads((Path(ATTEMPT) / 'diagnostic-report.json').read_text())
        verify('R02', 'historical-status', report['status'], 'FAILED')
        verify('R02', 'historical-comparison', report['comparison'], INCONCLUSIVE)
        verify('R02', 'historical-case2-not-run', report['notRunCommands'], [ABSENT])
        trace = json.loads((Path(ATTEMPT) / 'command-trace.jsonl').read_text().splitlines()[0])
        verify('R02', 'recorded-raw-not-rewritten', base64.b64decode(trace['outputBase64']), POSITIVE)

    def test_R03_constructed_negative_failed(self):
        provenance = json.loads((FIX / 'fixture-provenance.json').read_text())['syntheticNegative']
        verify('R03', 'label-is-synthetic', provenance['isSynthetic'])
        verify('R03', 'label-not-native', provenance['nativeEvidence'], False)
        verify('R03', 'synthetic-sha256', hashlib.sha256(NEGATIVE).hexdigest(), provenance['sha256'])
        value = self.accept('R03', 'CONSTRUCTED-negative-not-native', NEGATIVE,
                            case_id=ABSENT, exit_code=1, classification=REJECTED)
        verify('R03', 'diagnostic-remains-in-raw', DIAGNOSTIC.encode() in NEGATIVE)
        verify('R03', 'bare-ld-not-resolved', value.linker_tokens[0], 'ld')

    def test_R04_negative_diagnostic_exact(self):
        line = DIAGNOSTIC.encode() + b'\n'
        mutations = {
            'missing': NEGATIVE.replace(line, b''),
            'duplicate': NEGATIVE.replace(line, line * 2),
            'unknown': NEGATIVE.replace(b'invalid linker name', b'unknown linker name'),
            'wrong-target': NEGATIVE.replace(line, line.replace(b'eligibility-target', b'other-target')),
            'inline-prefix': NEGATIVE.replace(line, b'prefix ' + line),
            'inline-suffix': NEGATIVE.replace(line, line.rstrip(b'\n') + b' extra\n'),
            'inline-job': NEGATIVE.replace(line, b'').replace(b' "ld"', line.rstrip(b'\n') + b' "ld"'),
        }
        for label, raw in mutations.items():
            self.reject('R04', label, raw, case_id=ABSENT, exit_code=1)
        self.reject('R04', 'diagnostic-with-exit0', NEGATIVE, case_id=ABSENT)
        self.reject('R04', 'diagnostic-with-exit2', NEGATIVE, case_id=ABSENT, exit_code=2)
        self.reject('R04', 'selected-with-exit1-no-diagnostic', POSITIVE, case_id=ABSENT, exit_code=1)
        self.reject('R04', 'rejected-supplier-without-diagnostic', NEGATIVE.replace(line, b''), case_id=ABSENT)

    def test_R05_fp_contract(self):
        self.mutate_unit('R05', 'frontend', units('frontend', '-ffp-contract')[0], [
            (v, [f'-ffp-contract={v}']) for v in ('off', 'fast', 'fast-honor-pragmas')
        ] + [('separate', ['-ffp-contract', 'on']), ('last-wins', ['-ffp-contract=off', '-ffp-contract=on'])])

    def test_R06_frame_pointer(self):
        self.mutate_unit('R06', 'frontend', units('frontend', '-mframe-pointer')[0], [
            ('separate', ['-mframe-pointer', 'non-leaf']), ('all', ['-mframe-pointer=all']),
            ('none', ['-mframe-pointer=none'])])

    def test_R07_unwind_tables(self):
        self.mutate_unit('R07', 'frontend', units('frontend', '-funwind-tables')[0], [
            ('zero', ['-funwind-tables=0']), ('two', ['-funwind-tables=2']),
            ('flag-only', ['-funwind-tables']), ('extra', ['-funwind-tables=1'] * 2)])

    def test_R08_linker_version(self):
        self.mutate_unit('R08', 'frontend', units('frontend', '-target-linker-version')[0], [
            ('changed', ['-target-linker-version', '1268']), ('missing-value', ['-target-linker-version']),
            ('orphan', ['1267']), ('extra-value', ['-target-linker-version', '1267', '1267']),
            ('omit-pair', []), ('joined', ['-target-linker-version=1267'])])

    def test_R09_every_scalar_and_form(self):
        # Every semantic scalar, not just those that caused the historical failure.
        for section in ('frontend', 'linkerPositive'):
            for unit in units(section):
                if unit['form'] not in ('joined-value', 'separate-value', 'separate-three-values'):
                    continue
                tokens = unit['observedTokens']
                for offset in range(1 if len(tokens) > 1 else 0, len(tokens)):
                    changed = list(tokens)
                    changed[offset] += '-changed'
                    self.mutate_unit('R09', section, unit, [(f'scalar-{offset}', changed)])
                if len(tokens) == 1:
                    key, value = tokens[0].split('=', 1)
                    variant = [key, value]
                else:
                    variant = [tokens[0] + '=' + tokens[1], *tokens[2:]]
                self.mutate_unit('R09', section, unit, [('wrong-joined-separate-form', variant)])

    def test_R10_each_warning_severity(self):
        warning_units = [u for u in units('frontend') if u['observedTokens'][0].startswith('-W')]
        verify('R10', 'warning-inventory-count', len(warning_units), 6)
        for unit in warning_units:
            token = unit['observedTokens'][0]
            changed = token.replace('-Wno-error=', '-Wno-').replace('-Werror=', '-Wno-error=')
            if changed == token:
                changed = token + '-unknown'
            self.mutate_unit('R10', 'frontend', unit,
                             [('severity-or-name', [changed]), ('remove', []), ('duplicate', [token, token])])
        for token in ('-Wfoo', '-w', '-Wno-error', '-Wno-allocator-wrappers'):
            self.reject('R10', 'extra/' + token, render(cc=(*CC, token)))

    def test_R11_all_frontend_units_and_unknown_switches(self):
        # Full inventory sweep also maps every unmodeled semantic mismatch to a mutation.
        for unit in units('frontend'):
            tokens = unit['observedTokens']
            changed = [tokens[0] + '-unknown', *tokens[1:]]
            self.mutate_unit('R11', 'frontend', unit,
                             [('omit', []), ('duplicate', tokens * 2), ('changed-key', changed)])
        for token in ('-funknown', '-munknown', '-fno-common', '-frounding-math',
                      '-fstrict-return', '-fsized-deallocation', '-fimplicit-modules',
                      '-fno-exceptions', '-fno-cxx-exceptions', '-fmodules', '-ffast-math'):
            self.reject('R11', 'extra/' + token, render(cc=(*CC, token)))

    def test_R12_vendor_six_exact(self):
        vendor = units('frontend', '-clang-vendor-feature')
        verify('R12', 'six-vendor-selectors', len(vendor), 6)
        for unit in vendor:
            token = unit['observedTokens'][0]
            self.mutate_unit('R12', 'frontend', unit, [
                ('changed-name', [token + 'Unknown']), ('drop', []), ('duplicate', [token] * 2),
                ('opposite', [token.replace('=+', '=-')]), ('split', token.split('=', 1))])
        self.reject('R12', 'seventh-vendor-selector', render(cc=(*CC, '-clang-vendor-feature=+unknown')))

    def test_R13_each_of_27_features(self):
        features = units('frontend', '-target-feature')
        verify('R13', '27-feature-values', [u['observedTokens'][1] for u in features],
               CONTRACT['multiplicities']['targetFeatures']['orderedExactValues'])
        for number, unit in enumerate(features):
            key, value = unit['observedTokens']
            self.mutate_unit('R13', 'frontend', unit, [
                ('flip-sign', [key, '-' + value[1:]]), ('change-value', [key, value + '-unknown']),
                ('omit', []), ('add', [key, value, key, '+unknown']),
                ('duplicate', [key, value] * 2)])
            other = features[(number + 1) % len(features)]
            words = list(CC)
            first, second = unit['tokenStart'], other['tokenStart']
            words[first:first + 2], words[second:second + 2] = words[second:second + 2], words[first:first + 2]
            self.reject('R13', unit['id'] + '/reorder', render(cc=words))

    def test_R14_modulemap_positions_and_counts(self):
        flag = '-fno-modulemap-allow-subdirectory-search'
        verify('R14', 'two-exact-positions', [i for i, t in enumerate(CC) if t == flag], [31, 34])
        for index in (31, 34):
            self.reject('R14', f'count1-remove-{index}', edit('frontend', index, 1, []))
            self.reject('R14', f'changed-spelling-{index}', edit('frontend', index, 1, [flag + '-unknown']))
            words = list(CC)
            words[index], words[index + 1] = words[index + 1], words[index]
            self.reject('R14', f'moved-placement-{index}', render(cc=words))
        self.reject('R14', 'count3', edit('frontend', 31, 1, [flag] * 2))
        self.accept('R14', 'exactly-two-structural-only')

    def test_R15_singletons(self):
        for key in ('-cc1', '-O0', '-std', '-fcommon'):
            unit = units('frontend', key)[0]
            self.mutate_unit('R15', 'frontend', unit, [('duplicate', unit['observedTokens'] * 2)])
        self.reject('R15', 'opposite-common', render(cc=(*CC, '-fno-common')))

    def test_R16_each_of_seven_rendered_includes(self):
        includes = [u for u in units('frontend') if u['key'] in ('-I', '-isystem', '-iframework')]
        verify('R16', 'seven-includes', len(includes), 7)
        verify('R16', 'rendered-order', [dict(flag=u['observedTokens'][0], path=u['observedTokens'][1])
                                       for u in includes], CONTRACT['renderedIncludes'])
        for number, unit in enumerate(includes):
            flag, path = unit['observedTokens']
            self.mutate_unit('R16', 'frontend', unit, [
                ('changed-flag', ['-internal-isystem', path]), ('changed-path', [flag, path + '-other']),
                ('dot-alias', [flag, path + '/.']), ('framework-class', ['-I' if flag == '-iframework' else '-iframework', path]),
                ('remove', []), ('duplicate', [flag, path] * 2)])
            other = includes[(number + 1) % len(includes)]
            words = list(CC)
            a, b = unit['tokenStart'], other['tokenStart']
            words[a:a + 2], words[b:b + 2] = words[b:b + 2], words[a:a + 2]
            self.reject('R16', unit['id'] + '/reorder', render(cc=words))
        verify('R16', 'original-driver-includes-not-edited',
               len(CONTRACT['originalDriverIncludes']), 7)

    def test_R17_framework_link_path(self):
        unit = units('linkerPositive', '-F')[0]
        token = unit['observedTokens'][0]
        self.mutate_unit('R17', 'linkerPositive', unit, [
            ('different-path', [token + '-other']), ('separate', ['-F', token[2:]]),
            ('dot-alias', [token + '/.']), ('extra-directory', [token, '-F/tmp/extra'])])

    def test_R18_linker_optimization(self):
        self.mutate_unit('R18', 'linkerPositive', units('linkerPositive', '-O0')[0],
                         [('O1', ['-O1']), ('omit', []), ('duplicate', ['-O0'] * 2)])

    def test_R19_linker_backend_option(self):
        self.mutate_unit('R19', 'linkerPositive', units('linkerPositive', '-mllvm')[0], [
            ('changed', ['-mllvm', '-disable-linkonceodr-outlining']), ('missing-value', ['-mllvm']),
            ('omit-pair', []), ('extra-operand', ['-mllvm', '-enable-linkonceodr-outlining', '-other']),
            ('second-option', ['-mllvm', '-enable-linkonceodr-outlining', '-mllvm', '-other']),
            ('duplicate', ['-mllvm', '-enable-linkonceodr-outlining'] * 2)])

    def test_R20_every_linker_input_and_order(self):
        for unit in units('linkerPositive'):
            tokens = unit['observedTokens']
            self.mutate_unit('R20', 'linkerPositive', unit, [
                ('omit', []), ('duplicate', tokens * 2), ('changed', [tokens[0] + '-other', *tokens[1:]])])
        words = list(LD)
        words[20], words[21] = words[21], words[20]
        self.reject('R20', 'libraries-reversed', render(ld=words))
        for tokens in (['-lOther'], ['-framework', 'Foundation'], ['/tmp/extra.o'],
                       ['/tmp/extra.a'], ['-weak_library', '/tmp/extra.dylib']):
            self.reject('R20', 'extra/' + '/'.join(tokens), render(ld=(*LD, *tokens)))

    def test_R21_real_lto_helper_only(self):
        self.mutate_unit('R21', 'linkerPositive', units('linkerPositive', '-lto_library')[0], [
            ('marker-relative', ['-lto_library', str(Path(TARGET).parent / 'libLTO.dylib')]),
            ('wrong-toolchain', ['-lto_library', '/usr/lib/libLTO.dylib']),
            ('alias', ['-lto_library', LD[3] + '/.'])])

    def test_R22_supplier_identity(self):
        real_ld = CONTRACT['headerExact'][3].removeprefix('InstalledDir: ') + '/ld'
        for supplier in ('ld', './ld', '../ld', '/usr/bin/ld', real_ld, TARGET + '/.', TARGET + '-other'):
            self.reject('R22', 'positive-supplier/' + supplier, edit('linkerPositive', 0, 1, [supplier]))
        for supplier in (TARGET, '/usr/bin/ld', real_ld, './ld'):
            raw = NEGATIVE.replace(b' "ld"', (' ' + json.dumps(supplier)).encode())
            self.reject('R22', 'negative-supplier/' + supplier, raw, case_id=ABSENT, exit_code=1)
        for supplier in ('clang', '/usr/bin/clang', CC[0] + '/.', CC[0] + '++'):
            self.reject('R22', 'compiler/' + supplier, edit('frontend', 0, 1, [supplier]))

    def test_R23_object_global_reference(self):
        original = BIND['OBJECT']
        for suffix in ('a', '_', '-', '0', 'A_0-', 'x' * 64):
            replacement = ATTEMPT + '/empty-' + suffix + '.o'
            raw = POSITIVE.replace(original.encode(), replacement.encode())
            self.accept('R23', 'safe-global-object/' + suffix, raw)
        bad = ['/tmp/empty-safe.o', ATTEMPT + '/empty-.o', ATTEMPT + '/empty-' + 'a' * 65 + '.o',
               ATTEMPT + '/empty-a/b.o', ATTEMPT + '/empty-a\\b.o', ATTEMPT + '/./empty-a.o',
               ATTEMPT + '//empty-a.o', ATTEMPT + '/sub/../empty-a.o', ATTEMPT + '/empty-a.O',
               ATTEMPT + '/empty-a b.o', ATTEMPT + '/empty-é.o']
        for number, path in enumerate(bad):
            self.reject('R23', f'invalid-global-object-{number}', POSITIVE.replace(original.encode(), path.encode()))
        self.reject('R23', 'frontend-linker-object-mismatch',
                    edit('linkerPositive', 19, 1, [ATTEMPT + '/empty-safe.o']))

    def test_R24_source_output_and_directories(self):
        for section, key in (('frontend', '-dumpdir'), ('frontend', '-fdebug-compilation-dir'),
                             ('frontend', '-fcoverage-compilation-dir'), ('frontend', '-main-file-name'),
                             ('linkerPositive', '-o')):
            unit = units(section, key)[0]
            tokens = unit['observedTokens']
            for ending in ('-other', '/.', '/../alias', '//'):
                changed = list(tokens)
                changed[-1] += ending
                self.mutate_unit('R24', section, unit, [(ending, changed)])
        for path in (ATTEMPT + '/other.cpp', ATTEMPT + '/./empty.cpp', '/tmp/empty.cpp'):
            self.reject('R24', 'source/' + path, edit('frontend', 154, 1, [path]))
        self.reject('R24', 'duplicate-source', render(cc=(*CC, CC[-1])))

    def test_R25_exact_header(self):
        for number in range(4):
            for label, mutate in (('changed', lambda s: s + '-changed'),
                                  ('leading-space', lambda s: ' ' + s),
                                  ('trailing-space', lambda s: s + ' ')):
                headers = list(CONTRACT['headerExact'])
                headers[number] = mutate(headers[number])
                self.reject('R25', f'header-{number}/{label}', render(headers=headers))
            headers = list(CONTRACT['headerExact'])
            headers.pop(number)
            self.reject('R25', f'header-{number}/missing', render(headers=headers))
        for label, line in (('other-version', b'Apple clang version 21.0.1 (clang-2100.1.1.101)'),
                            ('other-build', b'Apple clang version 21.0.0 (clang-2100.1.1.102)')):
            self.reject('R25', label, POSITIVE.replace(POSITIVE.splitlines()[0], line))
        self.reject('R25', 'extra-header', render(headers=[*CONTRACT['headerExact'], 'Extra: header']))

    def test_R26_exactly_two_jobs(self):
        lines = POSITIVE.splitlines(keepends=True)
        for label, raw in (
            ('missing-frontend', b''.join(lines[:4] + lines[5:])),
            ('missing-linker', b''.join(lines[:5])), ('third-job', POSITIVE + lines[-1]),
            ('helper-job', POSITIVE + b'"/bin/sh" "helper"\n'),
            ('reversed-jobs', b''.join(lines[:4] + lines[4:][::-1])),
            ('unknown-line', POSITIVE + b'unknown text\n'),
            ('in-process', POSITIVE + b'(in-process)\n'),
            ('response-file', render(cc=(*CC, '@args.rsp'))),
            ('response-linker', render(ld=(*LD, '@args.rsp')))):
            self.reject('R26', label, raw)

    def test_R27_bytes_utf8_controls_and_quoting(self):
        for number in (*range(0, 9), *range(11, 32), 127):
            self.reject('R27', f'forbidden-control-{number:02x}', POSITIVE.replace(b' "-cc1"',
                                                                                   bytes([number]) + b' "-cc1"', 1))
        for label, raw in (
            ('invalid-utf8', POSITIVE + b'\xff\n'), ('utf8-overlong', POSITIVE + b'\xc0\xaf\n'),
            ('utf8-surrogate', POSITIVE + b'\xed\xa0\x80\n'), ('bom', b'\xef\xbb\xbf' + POSITIVE),
            ('crlf', POSITIVE.replace(b'\n', b'\r\n')), ('missing-final-LF', POSITIVE[:-1]),
            ('unclosed-double-quote', POSITIVE + b'"\n'), ('unclosed-single-quote', POSITIVE + b"'\n"),
            ('job-broken-quote', POSITIVE.replace(b'"-cc1"', b'"-cc1', 1)),
            ('trailing-escape', POSITIVE[:-1] + b'\\\n'),
            ('unicode-line-separator', POSITIVE.replace(b'\n', '\u2028'.encode(), 1)),
            ('unicode-blank', POSITIVE + '\u00a0\n'.encode()), ('empty', b''),
            ('over-output-ceiling', POSITIVE + b' ' * 262144 + b'\n')):
            self.reject('R27', label, raw)
        self.accept('R27', 'ASCII-blank-lines-and-tabs',
                    b'\t\n' + POSITIVE.replace(b'\n', b'\n \t\n'))
        self.accept('R27', 'POSIX-shlex-single-quotes', render())
        for label, raw in (('not-bytes-text', POSITIVE.decode()), ('not-bytes-bytearray', bytearray(POSITIVE))):
            self.reject('R27', label, raw)
        for code in (True, False, '0', None, -1, 2, 256):
            self.reject('R27', 'exit-type-value/' + repr(code), POSITIVE, exit_code=code)
        for case_id in ('', 'other', PRESENT + ' ', None):
            self.reject('R27', 'case-id/' + repr(case_id), POSITIVE, case_id=case_id)

    def test_R28_unknown_diagnostics_are_not_filtered(self):
        lines = POSITIVE.splitlines(keepends=True)
        for diagnostic in (b'warning: unknown', b'clang: warning: ignored argument',
                           b'clang: error: other failure', b'ld: warning: unrelated', b'# comment'):
            for position in (0, 1, 4, 5, 6):
                changed = list(lines)
                changed.insert(position, diagnostic + b'\n')
                self.reject('R28', diagnostic.decode() + f'/record-{position}', b''.join(changed))

    def test_R29_lexical_context_rebase(self):
        parent = ATTEMPT.rsplit('/', 1)[0]
        self.accept('R29', 'historical-lexical-context-no-lease')
        for name in ('access-offline-2', 'a', 'a' * 64, 'A_0-'):
            attempt = parent + '/' + name
            ctx = context(attempt)
            raw = POSITIVE.replace(ATTEMPT.encode(), attempt.encode())
            self.accept('R29', 'rebased-context/' + name, raw, ctx=ctx)
            self.reject('R29', 'old-raw-under-rebased/' + name, POSITIVE, ctx=ctx)
            self.reject('R29', 'new-raw-under-old/' + name, raw)
        invalid = [parent, parent + '/', parent + '/.', parent + '/..', parent + '/a/b',
                   parent + '//a', parent + '/a/../b', parent + '/a\\b', parent + '/a b',
                   parent + '/' + 'a' * 65, '/tmp/access-offline-2', 'relative/access-2',
                   parent + '/é', parent + '/a\t', parent + '/a\x00', parent + '/a\x7f']
        for number, attempt in enumerate(invalid):
            with self.subTest(context=attempt):
                raises('R29', f'invalid-context-{number}', lambda a=attempt: context(a), ValueError)
        for target in (TARGET + '/.', TARGET + '-other', 'ld', '/usr/bin/ld', TARGET.replace('/', '//', 1)):
            with self.subTest(target=target):
                raises('R29', 'unsealed-target/' + target, lambda t=target: context(target=t), ValueError)

    def test_R30_no_qualification_and_frozen_results(self):
        values = [self.accept('R30', 'positive-boundary'),
                  self.accept('R30', 'absent-still-selected', case_id=ABSENT),
                  self.accept('R30', 'negative-boundary', NEGATIVE, case_id=ABSENT,
                              exit_code=1, classification=REJECTED)]
        for number, value in enumerate(values):
            for field in fields(value):
                with self.subTest(result=number, field=field.name):
                    raises('R30', f'frozen-result-{number}/{field.name}',
                           lambda v=value, k=field.name: setattr(v, k, None), FrozenInstanceError, kind='check')
        ctx = context()
        for key in ('attempt', 'synthetic_target'):
            raises('R30', 'frozen-context/' + key, lambda k=key: setattr(ctx, k, '/tmp/other'),
                   FrozenInstanceError, kind='check')
        verify('R30', 'positive-negative-comparison', parser.compare_outputs((values[0], values[2])), SUPPORTS)
        verify('R30', 'both-selected-comparison', parser.compare_outputs([values[0], values[1]]), NO_SUPPORT)
        for label, rows in (('empty', []), ('prefix-only', values[:1])):
            verify('R30', label, parser.compare_outputs(rows), INCONCLUSIVE)
        for label, rows in (('wrong-order', [values[2], values[0]]), ('duplicate', [values[0]] * 2),
                            ('too-many', [values[0], values[1], values[2]])):
            raises('R30', label, lambda r=rows: parser.compare_outputs(r))
        rebased = ATTEMPT.rsplit('/', 1)[0] + '/access-offline-2'
        other = self.accept('R30', 'other-context', POSITIVE.replace(ATTEMPT.encode(), rebased.encode()),
                            ctx=context(rebased), case_id=ABSENT)
        raises('R30', 'comparison-context-mix', lambda: parser.compare_outputs([values[0], other]))

    def test_R31_TEST_ONLY_fake_boundary_negative_terminal(self):
        boundary = FakeBoundary()
        rows = boundary.run([(POSITIVE, 0), (NEGATIVE, 1)])
        verify('R31', 'TEST_ONLY-fakeboundary/pair-count', len(rows), 2, kind='fakeboundary')
        verify('R31', 'TEST_ONLY-fakeboundary/diagnostic-comparison', parser.compare_outputs(rows), SUPPORTS,
               kind='fakeboundary')
        verify('R31', 'TEST_ONLY-fakeboundary/negative-command-failed', rows[1].command_status, 'FAILED', kind='fakeboundary')
        verify('R31', 'TEST_ONLY-fakeboundary/negative-terminal-failed', rows[1].terminal_status, 'FAILED', kind='fakeboundary')
        verify('R31', 'TEST_ONLY-fakeboundary/worker-failed', boundary.status, 'FAILED', kind='fakeboundary')
        verify('R31', 'TEST_ONLY-fakeboundary/no-publication', boundary.publications, [], kind='fakeboundary')
        negative_first = self.accept('R31', 'present-case-exit1-is-typed-failed', NEGATIVE,
                                     case_id=PRESENT, exit_code=1, classification=REJECTED)
        second = self.accept('R31', 'case2-input-witness-only', case_id=ABSENT)
        raises('R31', 'first-failed-cannot-compare-second',
               lambda: parser.compare_outputs([negative_first, second]), kind='fakeboundary')
        verify('R31', 'first-failed-prefix-inconclusive', parser.compare_outputs([negative_first]), INCONCLUSIVE,
               kind='fakeboundary')
        boundary = FakeBoundary()
        rows = boundary.run([(NEGATIVE, 1), (POSITIVE, 0)])
        verify('R31', 'TEST_ONLY-fakeboundary/first-failure-stops-case2', boundary.calls, [PRESENT], kind='fakeboundary')
        verify('R31', 'TEST_ONLY-fakeboundary/not-run-case2', boundary.not_run, [ABSENT], kind='fakeboundary')
        verify('R31', 'TEST_ONLY-fakeboundary/first-failed-status', boundary.status, 'FAILED', kind='fakeboundary')
        verify('R31', 'TEST_ONLY-fakeboundary/first-failed-no-publication', boundary.publications, [], kind='fakeboundary')

    def test_R32_TEST_ONLY_fake_ownership_clock_lifecycle(self):
        for guard in ('stop', 'cancel', 'deadline', 'cleanup', 'quarantine', 'ownership',
                      'output', 'trace', 'rss', 'outer', 'manifest', 'bindings', 'query', 'analysis', 'small'):
            for position in (0, 1):
                boundary = FakeBoundary(fault=guard, fault_at=position)
                rows = boundary.run([(POSITIVE, 0), (NEGATIVE, 1)])
                prefix = f'TEST_ONLY-fakeboundary/{guard}/before-case-{position + 1}'
                verify('R32', prefix + '/calls', boundary.calls, [PRESENT][:position], kind='fakeboundary')
                verify('R32', prefix + '/not-run', boundary.not_run, [PRESENT, ABSENT][position:], kind='fakeboundary')
                verify('R32', prefix + '/rows', len(rows), position, kind='fakeboundary')
                verify('R32', prefix + '/failed', boundary.status, 'FAILED', kind='fakeboundary')
                verify('R32', prefix + '/no-publication', boundary.publications, [], kind='fakeboundary')
                verify('R32', prefix + '/cleanup-once', boundary.cleanup_calls, 1, kind='fakeboundary')
                verify('R32', prefix + '/quarantine', boundary.quarantined, guard == 'cleanup', kind='fakeboundary')
        boundary = FakeBoundary()
        boundary.run([(b'invalid\n', 0), (NEGATIVE, 1)])
        verify('R32', 'TEST_ONLY-fakeboundary/parse-failure-stop', boundary.calls, [PRESENT], kind='fakeboundary')
        verify('R32', 'TEST_ONLY-fakeboundary/parse-failure-status', boundary.status, 'FAILED', kind='fakeboundary')
        expected = dict(seconds=300, querySeconds=30, cleanupSeconds=15, commandOutputBytes=262144,
                        traceBytes=2097152, workerRssBytes=536870912, outerLogBytes=524288,
                        manifestBytes=33554432, maximumInputBindings=256,
                        analysisFloorBytes=19327352832, smallAdmissionBytes=1073741824)
        for key, value in expected.items():
            verify('R32', 'unchanged-contract-limit/' + key, CONTRACT['executionControlsUnchanged'][key], value)
        for key, value in CONTRACT['markerIdentityLimits'].items():
            verify('R32', 'identity-limit/' + key, value, key == 'noConcurrentWriterAssumed' or key == 'noMarkerPreparationOrMutationInThisSlice')


class FakeBoundary:
    """TEST ONLY: fake owner/clock/lifecycle model, never a production bridge.

    All state is in memory. Limits are simulated, not enforced by a native owner;
    these checks document the boundary that a future reviewed bridge must keep.
    No filesystem freshness, immutable marker, lease, or native-execution claim.
    """
    def __init__(self, fault=None, fault_at=0):
        self.fault, self.fault_at = fault, fault_at
        self.calls, self.not_run, self.publications = [], [], []
        self.status, self.cleanup_calls, self.quarantined = 'FAILED', 0, False
        self.now = 0
        self.limits = CONTRACT['executionControlsUnchanged']

    def guard(self, position):
        fault = self.fault if position == self.fault_at else None
        if fault == 'deadline':
            self.now = self.limits['seconds'] + 1
        if fault in ('stop', 'cancel', 'cleanup', 'quarantine', 'ownership'):
            return False
        measurements = dict(output=0, trace=0, rss=0, outer=0, manifest=0, bindings=0, query=0)
        limits = dict(output=self.limits['commandOutputBytes'], trace=self.limits['traceBytes'],
                      rss=self.limits['workerRssBytes'], outer=self.limits['outerLogBytes'],
                      manifest=self.limits['manifestBytes'], bindings=self.limits['maximumInputBindings'],
                      query=self.limits['querySeconds'])
        if fault in measurements:
            measurements[fault] = limits[fault] + 1
        available = self.limits['analysisFloorBytes']
        small_available = self.limits['smallAdmissionBytes']
        if fault == 'analysis':
            available -= 1
        if fault == 'small':
            small_available -= 1
        return (self.now < self.limits['seconds']
                and all(measurements[k] <= limits[k] for k in measurements)
                and available >= self.limits['analysisFloorBytes']
                and small_available >= self.limits['smallAdmissionBytes'])

    def run(self, witnesses):
        rows = []
        self.not_run = [PRESENT, ABSENT]
        try:
            for position, (case_id, (raw, code)) in enumerate(zip((PRESENT, ABSENT), witnesses)):
                if not self.guard(position):
                    break
                self.calls.append(case_id)
                self.not_run.remove(case_id)
                try:
                    parsed = parser.parse_output(raw, context=context(), case_id=case_id, exit_code=code)
                except parser.ParseError:
                    break
                rows.append(parsed)
                if parsed.command_status == 'FAILED':
                    break
            if len(rows) == 2 and all(row.command_status == 'PASSED' for row in rows):
                self.status = 'OFFLINE_STRUCTURAL_COMPATIBLE'
        finally:
            self.cleanup_calls += 1
            self.quarantined = self.fault == 'cleanup'
        return rows


if __name__ == '__main__':
    unittest.main()
