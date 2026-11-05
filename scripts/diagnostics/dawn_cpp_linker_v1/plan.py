"""Preparation-only linker investigation recipe. No native execution entry point."""
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location(
    'linker_prior_metadata', ROOT / 'scripts/diagnostics/dawn_cpp_backend_v1/qualify.py')
prior = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prior)
base_plan = prior.prior.plan
CLANG, LD, BIN = base_plan.CLANG, base_plan.LD, base_plan.BIN
ATTEMPT = ROOT / 'build/dawn-cpp-linker-v1/probe-attempts/linker-1'
PROTOCOL = 'dawn-cpp-linker-selection-investigation-v1'
SCOPE = 'one-diagnostic-lookup-six-explicit-linker-dry-runs'
OPTION = '--ld-path=' + str(LD)


def commands(attempt=ATTEMPT):
    attempt = Path(attempt)
    result = [dict(
        id='diagnostic-program-lookup', kind='diagnostic-program-lookup',
        qualifiesLinker=False,
        argv=[str(CLANG), '--driver-mode=g++', '--no-default-config',
              '-B' + str(BIN), '-print-prog-name=ld'])]
    for variant, header, standard in base_plan.PROFILES:
        name = variant + '-' + header + '-' + standard
        source, output = str(attempt / 'empty.cpp'), str(attempt / (name + '.not-created'))
        result.append(dict(
            id='explicit-jobs-' + name, kind='driver-jobs', profile=name,
            expectedStandard=standard, expectedIncludes=base_plan.includes(variant),
            declaredLinker=str(LD), source=source, output=output,
            argv=[str(CLANG), *base_plan.flags(variant, standard), OPTION,
                  '-###', '-x', 'c++', source, '-o', output]))
    return result


def sandbox(attempt=ATTEMPT):
    # Exactly the existing compiler-only profile: ld is NEVER executable here.
    return prior.backend.sandbox(attempt, [str(CLANG)])


def environment(attempt=ATTEMPT):
    return dict(PATH=str(BIN), LANG='C', LC_ALL='C', HOME=str(attempt), TMPDIR=str(attempt))
