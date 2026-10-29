"""Deterministic synthetic inputs; generation never reads candidate scores or results."""
import json
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SEED = 20261028
SAMPLES = 32

def escaped_scalar(char, upper=False):
    value = ord(char)
    values = [value] if value <= 0xffff else [0xd800 + ((value - 0x10000) >> 10), 0xdc00 + ((value - 0x10000) & 1023)]
    return ''.join('\\u' + format(value, '04X' if upper else '04x') for value in values)

def spell(value, style):
    if style == 'literal':
        return json.dumps(value, ensure_ascii=False)
    return '"' + ''.join(escaped_scalar(char, i % 2 == 1) if style == 'unicode' or i % 2 == 0
                         else json.dumps(char, ensure_ascii=False)[1:-1]
                         for i, char in enumerate(value)) + '"'

def schema(bound=96, minimum=1):
    return {'type': 'string', 'minLength': minimum, 'maxLength': bound,
            'x-guidance': {'whitespace_flexible': False, 'json_allow_general_unicode_escapes': True}}

def generate():
    rows = []
    def add(case_id, text, config, expected, kind='unicode'):
        rows.append({'id': case_id, 'kind': kind, 'jsonText': text, 'schema': config,
                     'expected': expected})
    def variants(label, value, bound=96):
        for style in ['literal', 'unicode', 'mixed']:
            add(label + '-' + style, spell(value, style), schema(bound), 1 <= len(value) <= bound)
    for code in range(128):
        variants('ascii-' + str(code), chr(code))
    for code in [0x80, 0x7ff, 0x800, 0xd7ff, 0xe000, 0xfffd, 0xfffe, 0xffff,
                 0x10000, 0x1f642, 0x10ffff, 0x301, 0x9752, 0x200d, 0xfe0f]:
        variants('scalar-' + str(code), chr(code))
    for bound in [1, 32, 64, 96]:
        for length in sorted({0, 1, max(0, bound - 1), bound, bound + 1}):
            for number, alphabet in enumerate(['A', '青', '🙂', 'e\u0301', 'A🙂\n\x7f']):
                value = (alphabet * (length + 1))[:length]
                variants(f'boundary-{bound}-{length}-{number}', value, bound)
    random_source = random.Random(SEED)
    for sample in range(SAMPLES):
        size = random_source.choice([0, 1, 2, 31, 32, 63, 64, 95, 96, 97])
        # Construct valid scalar values directly, with no filtering or surrogate discard.
        value = ''.join(chr(n if n < 0xd800 else n + 0x800)
                        for n in (random_source.randrange(0x110000 - 0x800) for _ in range(size)))
        variants('generated-' + str(sample), value)
    retained = json.loads((ROOT / 'planning/fixtures/unicode-boundary-v1/cases.json').read_text())
    for case in retained['cases']:
        add('retained-' + case['id'], case['jsonText'], schema(), True, 'retained-valid')
        for mode in ['absent', 'false']:
            config = schema()
            if mode == 'absent':
                config['x-guidance'].pop('json_allow_general_unicode_escapes')
            else:
                config['x-guidance']['json_allow_general_unicode_escapes'] = False
            add('compat-' + mode + '-' + case['id'], case['jsonText'], config,
                case['sourcePredictedByteAcceptance'], 'default-off')
    invalid = ['"\\u975"', '"\\u97G2"', '"\\uD800"', '"\\uDC00"', '"\\uDC00\\uD800"',
               '"\\uD800A"', '"\\uD800\\u0041"', '"\\uD800\\uDBFF"', '"\\uD800\\udc0"',
               '"\\uD800\\uDC0Z"', '"\\x41"', '"\\v"', '"unterminated', '"\\U00009752"']
    invalid += ['"' + chr(code) + '"' for code in range(32)]
    for number, text in enumerate(invalid):
        add('malformed-' + str(number), text, schema(), False, 'malformed')
    for number, config in enumerate([schema(1, 2), schema(0x100000000, 0x100000000), schema(0x100000000, 1)]):
        add('invalid-bound-' + str(number), '"A"', config, 'compile-error', 'invalid-bound')
        rows[-1]['expectedErrorContains'] = 'maxLength' if number == 2 else 'minLength'
    for opt in [False, True]:
        config = schema(1);config['pattern'] = '^A$'
        config['x-guidance']['json_allow_general_unicode_escapes'] = opt
        add('pattern-literal-' + str(opt), '"A"', config, True, 'pattern-compat')
        add('pattern-overflow-' + str(opt), '"AA"', config, False, 'pattern-compat')
        add('pattern-escape-old-path-' + str(opt), '"\\u0041"', config, False, 'pattern-compat')
        constant = {'const': 'A', 'x-guidance': {'json_allow_general_unicode_escapes': opt}}
        add('constant-literal-' + str(opt), '"A"', constant, True, 'constant-compat')
        add('constant-old-path-' + str(opt), '"\\u0041"', constant, False, 'constant-compat')
    for label, value in [('wrong-type', 'true'), ('unknown', True)]:
        config = schema()
        config['x-guidance']['json_allow_general_unicode_escapes' if label == 'wrong-type' else 'unsupported_option'] = value
        add('option-' + label, '"A"', config, 'compile-error', 'invalid-option')
        rows[-1]['expectedErrorContains'] = 'invalid type' if label == 'wrong-type' else 'unknown field'
    return {'schema': 1, 'seed': SEED, 'generatedSamples': SAMPLES,
            'scope': 'synthetic compiler checks, no model outputs or semantic judgments', 'cases': rows}
