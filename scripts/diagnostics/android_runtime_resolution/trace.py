#!/usr/bin/env python3
"""Non-executing root-workspace inspection. Never grants artifact/device admission."""
import argparse
import ast
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[3]
CATALOG = ROOT / 'planning/fixtures/android-runtime-resolution-v2/source-inputs.json'
CATALOG_SHA = '36e91508af20749e48500e69f76d0849963f69bb10f610d864b23bfd6b0af090'
CACHE = ROOT / 'build/android-runtime-resolution-v2/verified-inputs'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def load_inputs():
    digest = lambda b: hashlib.sha256(b).hexdigest()
    raw = CATALOG.read_bytes()
    require(digest(raw) == CATALOG_SHA, 'resolution catalog changed')
    value = json.loads(raw)
    lock = ROOT / 'planning/fixtures/android-runtime-build-v1/prerequisite-lock.json'
    require(digest(lock.read_bytes()) == value['previousPrerequisiteLockSha256'], 'prior lock changed')
    for p, h in json.loads(lock.read_text())['files'].items():
        require(digest((ROOT / p).read_bytes()) == h, 'prior reviewed input changed: ' + p)
    spec = importlib.util.spec_from_file_location('preserved_recipe_audit',
        ROOT / 'scripts/diagnostics/android_runtime_recipe/audit.py')
    previous = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(previous)
    previous.inputs()  # Transitive decision-lock bindings; never edit the old audit.
    return value, previous


def verify_blob(row, raw):
    require(len(raw) == row['bytes'] and hashlib.sha256(raw).hexdigest() == row['sha256'],
            'resolution source changed: ' + row['path'])
    blob = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
    require(blob == row['gitBlob'], 'resolution Git blob changed: ' + row['path'])


def target(cache, row):
    require(row['repository'] in ['google-ai-edge/LiteRT-LM', 'tensorflow/tensorflow',
                                 'google-ai-edge/ml-drift'], 'unknown source owner')
    path = PurePosixPath(row['path'])
    require(not path.is_absolute() and '..' not in path.parts, 'unsafe input path')
    result = cache / row['repository'] / path
    require(result.resolve().is_relative_to(cache.resolve()), 'input escapes cache')
    return result


def collect(value, previous, cache):
    for row in value['files']:
        p = target(cache, row)
        if p.exists():
            verify_blob(row, p.read_bytes())
            continue
        require(0 < row['bytes'] <= 512 * 1024, 'source exceeds collection bound')
        url = 'https://raw.githubusercontent.com/' + row['repository'] + '/' + row['revision'] + '/' + row['path']
        with urllib.request.urlopen(url, timeout=20) as response:
            raw = response.read(row['bytes'] + 1)
        verify_blob(row, raw)
        p.parent.mkdir(parents=True, exist_ok=True)
        with p.open('xb') as output:
            output.write(raw)


def literal_calls(text, function):
    """Read syntax only. This is not a Starlark evaluator or macro expansion."""
    result = []
    for node in ast.walk(ast.parse(text)):
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == function:
            result.append(node)
    return result


def named_call(text, name):
    found = []
    for node in ast.walk(ast.parse(text)):
        if isinstance(node, ast.Call) and any(k.arg == 'name' and
                isinstance(k.value, ast.Constant) and k.value.value == name for k in node.keywords):
            found.append(node)
    require(len(found) == 1, 'missing/ambiguous target: ' + name)
    return found[0]


def observed_repository(definitions, name, mappings=None):
    """A mapped literal is still not a configured result; ambiguity never chooses a winner."""
    name = (mappings or {}).get(name, name)
    candidates = [d for d in definitions if d['name'] == name]
    if len(candidates) != 1:
        return {'status': 'UNRESOLVED' if not candidates else 'AMBIGUOUS', 'name': name}
    row = candidates[0]
    return {'status': 'LITERAL_ONLY_NOT_CONFIGURED', 'name': name,
            'immutableIdentityPresent': bool(row.get('revision') and row.get('sha256'))}


def inspect_sources(sources):
    sdk = sources['google-ai-edge/LiteRT-LM', 'WORKSPACE']
    require('common --noenable_bzlmod' in sources['google-ai-edge/LiteRT-LM', '.bazelrc'],
            'workspace mode changed')
    order = [n.func.id for n in ast.parse(sdk).body if isinstance(n, ast.Expr) and
             isinstance(n.value, ast.Call) for n in [n.value] if isinstance(n.func, ast.Name) and
             n.func.id.startswith('tf_workspace')]
    require(order == ['tf_workspace3', 'tf_workspace2', 'tf_workspace1', 'tf_workspace0'],
            'root macro order changed')
    tf = 'tensorflow/tensorflow'
    vendored = literal_calls(sources[tf, 'tensorflow/workspace3.bzl'], 'tf_vendored')
    vendored_pairs = [{k.arg: ast.literal_eval(k.value) for k in n.keywords} for n in vendored]
    require({'name': 'xla', 'path': 'third_party/xla'} in vendored_pairs and
            {'name': 'tsl', 'path': 'third_party/xla/third_party/tsl'} in vendored_pairs,
            'vendored repository selection changed')
    require('if native.existing_rule(name):' in sources[tf, 'third_party/repo.bzl'],
            'repository precedence changed')
    engine = named_call(sources['google-ai-edge/LiteRT-LM', 'runtime/core/BUILD'], 'engine_impl')
    require(any(k.arg == 'actual' and ast.literal_eval(k.value) == ':engine_advanced_impl'
                for k in engine.keywords), 'engine alias changed')
    provider = sources['google-ai-edge/LiteRT-LM',
                       'runtime/components/constrained_decoding/constraint_provider_factory.cc']
    require('return LlgConstraintProvider::Create' in provider, 'source constraint provider changed')
    inspected = ['tensorflow/workspace' + str(n) + '.bzl' for n in range(4)] + [
        'third_party/xla/workspace' + str(n) + '.bzl' for n in range(4)]
    direct = []
    for path in inspected:
        for func in ['http_archive', 'tf_http_archive']:
            for node in literal_calls(sources[tf, path], func):
                for keyword in node.keywords:
                    if keyword.arg == 'name' and isinstance(keyword.value, ast.Constant):
                        direct.append({'name': keyword.value.value, 'file': path})
    selected = observed_repository(direct, 'ml_drift')
    require(selected['status'] == 'UNRESOLVED', 'repository evidence changed; review required')
    alternative = sources['google-ai-edge/ml-drift', 'MODULE.bazel']
    require('bazel_compatibility = [">=8.0.0"]' in alternative and
            sources['google-ai-edge/ml-drift', '.bazelversion'].strip() == '8.0.0',
            'unadopted alternative toolchain identity changed')
    named_call(sources['google-ai-edge/ml-drift', 'ml_drift/cl/BUILD'], 'opencl_wrapper')
    return {'rootMode': 'WORKSPACE_BZLMOD_DISABLED', 'rootTfMacroOrder': order,
            'vendored': vendored_pairs, 'mlDriftInDirectInspectedDeclarations': selected,
            'directDeclarationsInspected': len(direct), 'engineImplAliasExists': True,
            'sourceLlgFactoryExists': True, 'configuredRootResolutionProven': False,
            'downstreamMacroClosureProven': False,
            'dependencyWorkspaceAutoEvaluation': False,
            'alternative': {'revision': '2d7fbbd45d8940b5fc7cea1f79e59d9d796164e2',
                            'license': 'Apache-2.0', 'standaloneBazel': '8.0.0', 'adopted': False},
            'routeDecision': 'REJECT_UNAMENDED_ROUTE_AS_BUILD_READY',
            'buildRecipeReady': False, 'emulatorExecutionAuthorized': False,
            's24Deferred': True, 'semanticSearchEnabled': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['collect', 'inspect'])
    parser.add_argument('--cache', type=Path, default=CACHE)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    try:
        value, previous = load_inputs()
        if args.command == 'collect':
            require(args.report is None, 'collection cannot publish a resolution result')
            collect(value, previous, args.cache)
            return 0
        old = previous.read_sources(previous.inputs(), previous.CACHE)
        sources = {('google-ai-edge/' + repo, path): text for (repo, path), text in old.items()}
        for row in value['files']:
            raw = target(args.cache, row).read_bytes()
            verify_blob(row, raw)
            key = (row['repository'], row['path'])
            require(key not in sources, 'duplicate source identity')
            sources[key] = raw.decode()
        report = inspect_sources(sources)
        report['sourceInputsSha256'] = CATALOG_SHA
        report['implementationSha256'] = previous.sha(Path(__file__).read_bytes())
        report['sourceFilesVerified'] = len(sources)
        output = json.dumps(report, indent=2) + '\n'
        if args.report:
            with args.report.open('x') as destination:
                destination.write(output)
        print(output, end='')
        return 2
    except (ValueError, OSError, SyntaxError) as error:
        print('Root-resolution inspection failed: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
