#!/usr/bin/env python3
"""Server-only Java correction; three bounded, frozen query/cquery commands."""
import argparse
import importlib.util
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result)
    return result


base = module('retained_ml_drift_analysis', HERE.parent / 'ml_drift_compatibility/analyze.py')
jvm = module('private_bazel_server_jvm', HERE / 'server_jvm.py')
require = base.require
JAVA_FIELDS = ['java_language_version', 'tool_java_language_version', 'java_runtime_version',
               'tool_java_runtime_version', 'javabase', 'host_javabase', 'java_toolchain',
               'host_java_toolchain']


def load_freeze(path, expected):
    freeze, candidate = base.load_freeze(path, expected)
    bound = {r['path'] for r in freeze['inputs']}
    require(freeze['serverJvmProtocol'] == 'bazel-761-private-server-jvm-v1', 'unsupported server protocol')
    require(freeze['serverRunnerSha256'] == base.digest(__file__), 'server wrapper changed')
    require({str(HERE / 'server_jvm.py'), str(HERE / 'analyze.py'), freeze['serverJdkReceipt'],
             str(ROOT / 'scripts/tests/test_bazel_server_jvm.py')} <= bound,
            'missing server correction implementation/receipt bindings')
    require(freeze['maximumInvocations'] == 3 and freeze['perInvocationSeconds'] == 300 and
            freeze['cleanupCeilingSeconds'] == 15 and freeze['logLimitBytes'] == 16 * 1024**2,
            'analysis bounds changed')
    require(freeze['javaInspectionFields'] == JAVA_FIELDS, 'Java compilation inspection changed')
    previous = Path(freeze['previousAnalysisFreezePath'])
    require(str(previous) in bound and base.digest(previous) == freeze['previousAnalysisFreezeSha256'],
            'unbound previous analysis freeze')
    old = json.loads(previous.read_text())
    require(freeze['environment'] == old['environment'], 'compilation environment changed')
    for key in ['sdkRoot', 'targets', 'abis', 'bazelPath', 'amendmentLockSha256']:
        require(freeze[key] == old[key], 'source/dependency scope changed: ' + key)
    require(freeze['javaHome'] != old['javaHome'] and freeze['javaHome'] == freeze['serverJavaHome'],
            'server selection not separate from compilation environment')
    root = Path(freeze['javaHome'])
    require(root.resolve().is_relative_to(ROOT / 'build') and not root.is_symlink(), 'server JDK not private')
    freeze['serverPreflight'] = jvm.preflight(Path(freeze['bazelPath']), root, Path(freeze['serverJdkReceipt']))
    return freeze, candidate


def command(freeze, workspace, output, verb, abi=None):
    args = base.command(freeze, workspace, output, verb, abi)
    if verb == 'cquery':
        index = next(i for i, arg in enumerate(args) if arg.startswith('--starlark:expr='))
        args[index] += ''.join(' + "\\t' + key + '=" + str(build_options(target).get("//command_line_option:' +
                              key + '", "unresolved") if build_options(target) else "unconfigured")'
                              for key in JAVA_FIELDS)
    return args


def execute(freeze, candidate, attempt):
    # Repeat immediately before staging/spawn; callers cannot bypass load_freeze's gate.
    preflight = jvm.preflight(Path(freeze['bazelPath']), Path(freeze['javaHome']),
                             Path(freeze['serverJdkReceipt']))
    require(not attempt.exists(), 'attempt already exists; preserve failed evidence')
    require(attempt.resolve().is_relative_to(Path(freeze['attemptParent']).resolve()),
            'attempt path outside frozen analysis root')
    attempt.mkdir(parents=True)
    report = {'scope': 'configured-analysis-only', 'abis': {}, 'artifactBuilds': False,
              'deviceAccess': False, 'configuredCompatibilityPassed': False,
              'analysisFreezeSha256': freeze['verifiedFreezeSha256'],
              'amendmentLockSha256': freeze['amendmentLockSha256'],
              'toolchainFacts': freeze['toolchainFacts'], 'serverPreflight': preflight,
              'compilationEnvironmentUnchanged': True,
              'resolvedJavaCompilationToolchains': 'UNRESOLVED_PENDING_CONFIGURED_GRAPH_REVIEW'}
    def save():
        (attempt / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    def run(workspace, label, verb, abi=None):
        jvm.preflight(Path(freeze['bazelPath']), Path(freeze['javaHome']), Path(freeze['serverJdkReceipt']))
        result = base.run_owned(command(freeze, workspace, attempt / (label + '-output'), verb, abi),
            workspace, attempt / label, dict(freeze['environment']), freeze['perInvocationSeconds'],
            freeze['logLimitBytes'], freeze['cleanupCeilingSeconds'])
        require(result['cleanupConfirmed'], 'uncertain owned process cleanup')
        return result
    try:
        workspace = attempt / 'baseline'
        base.stage_workspace(Path(freeze['sdkRoot']), workspace, candidate, False)
        report['baseline'] = run(workspace, 'inventory', 'query'); save()
        adopted = False
        if report['baseline']['classification'] == 'ANALYSIS_COMMAND_SUCCEEDED':
            b = report['baseline']
            raw = Path(b['stderr']).read_text(errors='replace') + Path(b['stdout']).read_text(errors='replace')
            receipt = base.receipt_from(raw); report['repositoryReceipt'] = receipt
            report['repositoryPolicy'] = base.repository_policy(receipt, candidate)
            workspace = attempt / 'guarded'
            base.stage_workspace(Path(freeze['sdkRoot']), workspace, candidate, True)
            adopted = True
        for abi in base.ABI_CONFIGS:
            result = run(workspace, abi, 'cquery', abi)
            result.update(guardedOverlaySelectedForAttempt=adopted,
                          requestedConfiguration=base.ABI_CONFIGS[abi], compilationVerified=False,
                          runtimeVerified=False, configuredDependencyEvidenceComplete=False)
            report['abis'][abi] = result; save()
        report['routeDecision'] = 'BLOCKED_PENDING_CONFIGURED_GRAPH_REVIEW'
    except (ValueError, OSError, base.subprocess.SubprocessError) as error:
        report['failure'] = str(error)
        for abi in base.ABI_CONFIGS:
            report['abis'].setdefault(abi, {'classification': 'NOT_RUN', 'reason': str(error)})
        raise
    finally:
        save()
    return report


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--freeze', type=Path, required=True)
    p.add_argument('--freeze-sha256', required=True)
    p.add_argument('--attempt', type=Path, required=True)
    args = p.parse_args(argv)
    try:
        freeze, candidate = load_freeze(args.freeze, args.freeze_sha256)
        freeze['verifiedFreezeSha256'] = args.freeze_sha256
        execute(freeze, candidate, args.attempt)
        return 2  # Evidence still requires review; no artifact/runtime qualification.
    except (ValueError, OSError, KeyError, base.subprocess.SubprocessError) as error:
        print('Analysis blocked: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
