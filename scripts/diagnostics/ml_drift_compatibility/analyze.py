#!/usr/bin/env python3
"""Frozen Bazel query/cquery investigation; no artifact build or device command."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import shutil
import signal
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parents[3]
FIXTURES = ROOT / 'planning/fixtures/ml-drift-compatibility-v1'
ABI_CONFIGS = {'arm64-v8a': 'android_arm64', 'x86_64': 'android_x86_64'}
MARKER = 'EDGE_MLDRIFT_RECEIPT='
DEFAULT_ATTRIBUTES = {
    'patches': [], 'patch_cmds': [], 'patch_cmds_win': [], 'patch_tool': '',
    'patch_args': ['-p0'], 'build_file': None, 'build_file_content': '',
    'workspace_file': None, 'workspace_file_content': '', 'canonical_id': '',
    'netrc': '', 'auth_patterns': {}, 'remote_patches': {}, 'remote_patch_strip': 0,
    'remote_file_urls': {}, 'remote_file_integrity': {}, 'add_prefix': '',
}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def verify_files(rows):
    for row in rows:
        p = Path(row['path'])
        require(p.is_file() and p.stat().st_size == row['bytes'] and digest(p) == row['sha256'],
                'missing/tampered input: ' + str(p))


def verify_tree(root, manifest):
    root = Path(root)
    expected = json.loads(Path(manifest).read_text())['files']
    observed = {p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file() or p.is_symlink()}
    require(observed == set(expected), 'tree inventory changed: ' + str(root))
    for name, row in expected.items():
        p = root / name
        require(not Path(name).is_absolute() and '..' not in Path(name).parts, 'unsafe tree entry')
        if 'symlink' in row:
            require(p.is_symlink() and os.readlink(p) == row['symlink'], 'symlink changed: ' + name)
            require(p.resolve().is_relative_to(root.resolve()), 'symlink escapes frozen tree')
        else:
            require(not p.is_symlink() and p.stat().st_size == row['bytes'] and digest(p) == row['sha256'],
                    'tree content changed: ' + name)
            require(p.stat().st_mode & 0o777 == row['mode'], 'tree mode changed: ' + name)


def load_freeze(path, expected):
    require(re.fullmatch('[0-9a-f]{64}', expected) is not None and digest(path) == expected,
            'analysis freeze changed')
    value = json.loads(Path(path).read_text())
    require(value['scope'] == 'configured-analysis-only' and value['bazelVersion'] == '7.6.1',
            'unsupported analysis scope/toolchain')
    require(value['abis'] == list(ABI_CONFIGS), 'unsupported ABI scope')
    require(value['artifactBuildAllowed'] is False and value['deviceAllowed'] is False,
            'artifact/device admission forbidden')
    require(value['environment']['HERMETIC_PYTHON_VERSION'] == '3.13', 'SDK Python selection changed')
    verify_files(value['inputs'])
    bound = {r['path'] for r in value['inputs']}
    require({value['bazelPath'], str(Path(value['javaHome']) / 'bin/java'),
             str(FIXTURES / 'candidate.json'), str(FIXTURES / 'amendment-lock.json')} <= bound,
            'missing executable/amendment identity')
    roots = {tree['root'] for tree in value['trees']}
    require({value['sdkRoot'], value['javaHome'], value['environment']['ANDROID_NDK_HOME'],
             value['environment']['ANDROID_HOME']} <= roots, 'missing complete source/toolchain tree')
    for tree in value['trees']:
        require(any(r['path'] == tree['manifest'] for r in value['inputs']), 'unbound tree manifest')
        verify_tree(tree['root'], tree['manifest'])
    require(digest(__file__) == value['implementationSha256'], 'runner implementation changed')
    candidate = json.loads((FIXTURES / 'candidate.json').read_text())
    require(value['targets'] == candidate['analysisTargets'], 'analysis target scope changed')
    require(value['perInvocationSeconds'] <= 300 and value['logLimitBytes'] <= 16 * 1024 * 1024,
            'analysis resource bounds changed')
    require(0 < value['cleanupCeilingSeconds'] <= 15, 'unsupported cleanup ceiling')
    return value, candidate


def repository_policy(receipt, candidate):
    require(isinstance(receipt, dict) and type(receipt.get('present')) is bool,
            'missing/ambiguous repository receipt')
    if not receipt['present']:
        require(receipt.get('identity') is None, 'inconsistent absent repository')
        return 'INSERT_AFTER_ROOT'
    expected = {'kind': 'http_archive', 'urls': [candidate['archive']['url']],
                'sha256': candidate['archive']['sha256'],
                'type': candidate['archive']['type'],
                'strip_prefix': candidate['archive']['stripPrefix'],
                'repo_mapping': candidate['repositoryMapping']}
    raw = receipt.get('identity')
    require(isinstance(raw, dict), 'conflicting/unverifiable existing repository')
    identity = {k: raw.get(k) for k in expected}
    identity['urls'] = list(raw.get('urls', [])) + ([raw['url']] if raw.get('url') else [])
    require(identity == expected, 'conflicting/unverifiable existing repository')
    known = set(expected) | set(DEFAULT_ATTRIBUTES) | {'url', 'name', 'generator_name',
                                                    'generator_function', 'generator_location'}
    require(not set(raw) - known, 'unknown repository attributes')
    require(raw.get('name', 'ml_drift') == 'ml_drift', 'repository name changed')
    for key, default in DEFAULT_ATTRIBUTES.items():
        actual = raw.get(key, default)
        if type(default) is list:
            require(type(actual) in [list, tuple], 'invalid repository sequence: ' + key)
            actual = list(actual)
        require(actual == default, 'unapproved repository transformation: ' + key)
    return 'REUSE_IDENTICAL'


def receipt_from(text):
    values = [json.loads(line.split(MARKER, 1)[1]) for line in text.splitlines() if MARKER in line]
    require(len(values) == 1, 'missing/ambiguous repository receipt')
    return values[0]


def helper(candidate, insert):
    """A guarded root-only repository declaration; never imports standalone MODULE."""
    expected = {'kind': 'http_archive', 'urls': [candidate['archive']['url']],
                'sha256': candidate['archive']['sha256'],
                'type': candidate['archive']['type'],
                'strip_prefix': candidate['archive']['stripPrefix'],
                'repo_mapping': candidate['repositoryMapping']}
    # JSON here contains only strings/lists/dicts, which are also Starlark literals.
    return '''load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
def inspect_root():
    existing = native.existing_rule("ml_drift")
    identity = existing
    print("EDGE_MLDRIFT_RECEIPT=" + json.encode({"present": existing != None, "identity": identity}))
''' + ('''
    expected = ''' + json.dumps(expected, sort_keys=True) + '''
    if existing != None:
        projected = {key: existing.get(key) for key in expected}
        projected["urls"] = list(existing.get("urls", []))
        if existing.get("url", ""):
            projected["urls"] = projected["urls"] + [existing["url"]]
        if projected != expected:
            fail("EDGE_MLDRIFT_CONFLICT: existing repository differs")
        defaults = ''' + repr(DEFAULT_ATTRIBUTES) + '''
        known = list(expected.keys()) + list(defaults.keys()) + ["url", "name", "generator_name", "generator_function", "generator_location"]
        for key in existing:
            if key not in known:
                fail("EDGE_MLDRIFT_CONFLICT: unknown repository attribute " + key)
        for key in defaults:
            actual = existing.get(key, defaults[key])
            if type(defaults[key]) == type([]):
                if type(actual) not in [type([]), type(())]:
                    fail("EDGE_MLDRIFT_CONFLICT: invalid sequence " + key)
                actual = list(actual)
            if actual != defaults[key]:
                fail("EDGE_MLDRIFT_CONFLICT: repository transformation " + key)
        if existing.get("name", "ml_drift") != "ml_drift":
            fail("EDGE_MLDRIFT_CONFLICT: repository name")
    else:
        http_archive(name = "ml_drift", urls = expected["urls"],
                     sha256 = expected["sha256"], type = expected["type"], strip_prefix = expected["strip_prefix"],
                     repo_mapping = expected["repo_mapping"])
''' if insert else '')


def stage_workspace(source, destination, candidate, insert):
    require(not destination.exists(), 'attempt workspace already exists')
    shutil.copytree(source, destination, symlinks=True)
    p = destination / 'edge_ml_drift_analysis.bzl'
    require(not p.exists(), 'analysis helper collides with source')
    with p.open('x') as f:
        f.write(helper(candidate, insert))
    with (destination / 'WORKSPACE').open('a') as f:
        f.write('\nload("//:edge_ml_drift_analysis.bzl", "inspect_root")\ninspect_root()\n')


def command(freeze, workspace, output, verb, abi=None):
    require(verb in ['query', 'cquery'], 'unsupported command')
    require((verb == 'query' and abi is None) or (verb == 'cquery' and abi in ABI_CONFIGS),
            'unsupported configuration')
    args = [freeze['bazelPath'], '--batch', '--nosystem_rc', '--nohome_rc', '--noworkspace_rc',
            '--bazelrc=' + str(workspace / '.bazelrc'), '--server_javabase=' + freeze['javaHome'],
            '--output_user_root=' + str(output), verb,
            '--repository_cache=' + freeze['repositoryCache']]
    if verb == 'query':
        args += ['--output=build', '//external:*']
    else:
        fields = ['cpu', 'platforms', 'crosstool_top', 'features']
        expression = 'str(target.label)' + ''.join(
            ' + "\\t" + str(build_options(target).get("//command_line_option:' + key + '", "") if build_options(target) else "unconfigured")'
            for key in fields)
        args += ['--config=' + ABI_CONFIGS[abi], '--jobs=2', '--output=starlark', '--starlark:expr=' + expression,
                 'deps(set(' + ' '.join(freeze['targets']) + '))']
    return args


def cleanup_owned(process, seconds):
    deadline = time.monotonic() + seconds
    def remaining():return max(0, deadline - time.monotonic())
    process.poll()
    try:os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:pass
    except PermissionError:
        # A denied signal to an exiting root is resolved only by reaping and
        # subsequent confirmation that the entire owned group disappeared.
        process.wait(timeout=min(.25, remaining()))
    try:process.wait(timeout=min(5, remaining()))
    except subprocess.TimeoutExpired:
        try:os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:pass
        try:process.wait(timeout=remaining())
        except subprocess.TimeoutExpired:raise ValueError('owned batch cleanup uncertain; shared deadline exhausted')
    for action in [None, signal.SIGKILL]:
        if action is not None:
            try:os.killpg(process.pid, action)
            except ProcessLookupError:pass
        phase_end = min(deadline, time.monotonic() + 5) if action is None else deadline
        while time.monotonic() < phase_end:
            try:os.killpg(process.pid, 0)
            except ProcessLookupError:return True
            time.sleep(min(.05, max(0, phase_end - time.monotonic())))
    raise ValueError('owned batch cleanup uncertain; shared deadline exhausted')


def run_owned(args, workspace, logbase, env, seconds, limit, cleanup_seconds=15):
    require(threading.current_thread() is threading.main_thread(), 'main-thread signal ownership required')
    start = time.monotonic();status = None;process = None;selector = None
    previous = {};pending = None;phase = 'setup';retained = 0;handles = []
    def interrupted(signum, frame):
        nonlocal pending
        pending = signum
        if phase == 'active':raise ValueError('analysis interrupted by signal ' + str(signum))
    def check_signal():
        if pending is not None:raise ValueError('analysis interrupted by signal ' + str(pending))
    stdout = logbase.with_suffix('.stdout.log');stderr = logbase.with_suffix('.stderr.log')
    try:
        for sig in [signal.SIGINT, signal.SIGTERM]:previous[sig] = signal.signal(sig, interrupted)
        check_signal()
        out = stdout.open('xb');handles.append(out)
        err = stderr.open('xb');handles.append(err)
        check_signal()
        # During spawn, signals record pending interruption without raising before
        # the Popen handle is assigned. Ownership was established above.
        process = subprocess.Popen(args, cwd=workspace, env=env, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, start_new_session=True)
        selector = selectors.DefaultSelector()
        for pipe, destination in [(process.stdout, out), (process.stderr, err)]:
            os.set_blocking(pipe.fileno(), False);selector.register(pipe, selectors.EVENT_READ, destination)
        phase = 'active';check_signal()
        while selector.get_map():
            remaining = seconds - (time.monotonic() - start)
            if remaining <= 0:status = 'TIMED_OUT';break
            for key, event in selector.select(min(remaining, .05)):
                data = os.read(key.fileobj.fileno(), 65536)
                if not data:selector.unregister(key.fileobj);continue
                available = max(0, limit - retained)
                key.data.write(data[:available]);key.data.flush()
                retained += min(available, len(data))
                if len(data) > available:status = 'LOG_LIMIT';break
            if status:break
        if time.monotonic() - start >= seconds:status = 'TIMED_OUT'
        if status is None:
            try:process.wait(timeout=max(.001, seconds - (time.monotonic() - start)))
            except subprocess.TimeoutExpired:status = 'TIMED_OUT'
        if time.monotonic() - start >= seconds:status = 'TIMED_OUT'
    finally:
        phase = 'cleanup'
        try:
            if process is not None:
                clean = cleanup_owned(process, cleanup_seconds)
        finally:
            if selector is not None:selector.close()
            if process is not None:
                process.stdout.close();process.stderr.close()
            for handle in handles:handle.close()
            for sig, old in previous.items():signal.signal(sig, old)
    check_signal()
    return {'exitCode': process.returncode, 'classification': status or
            ('ANALYSIS_COMMAND_SUCCEEDED' if process.returncode == 0 else 'ANALYSIS_COMMAND_FAILED'),
            'elapsedSeconds': time.monotonic() - start, 'cleanupConfirmed': clean,
            'stdout': str(stdout), 'stderr': str(stderr), 'argv': args}


def execute(freeze, candidate, attempt):
    require(not attempt.exists(), 'attempt already exists; preserve failed evidence')
    require(attempt.resolve().is_relative_to(Path(freeze['attemptParent']).resolve()),
            'attempt path outside frozen analysis root')
    attempt.mkdir(parents=True)
    report = {'scope': 'configured-analysis-only', 'abis': {}, 'artifactBuilds': False,
              'deviceAccess': False, 'configuredCompatibilityPassed': False,
              'analysisFreezeSha256': freeze['verifiedFreezeSha256'],
              'amendmentLockSha256': freeze['amendmentLockSha256'],
              'toolchainFacts': freeze['toolchainFacts']}
    path = attempt / 'report.json'
    def save():path.write_text(json.dumps(report, indent=2) + '\n')
    try:
        workspace = attempt / 'baseline'
        stage_workspace(Path(freeze['sdkRoot']), workspace, candidate, False)
        env = dict(freeze['environment'])
        baseline = run_owned(command(freeze, workspace, attempt / 'inventory-output', 'query'),
            workspace, attempt / 'inventory', env, freeze['perInvocationSeconds'], freeze['logLimitBytes'],
            freeze['cleanupCeilingSeconds'])
        report['baseline'] = baseline;save()
        adopted = False
        if baseline['classification'] == 'ANALYSIS_COMMAND_SUCCEEDED':
            raw = Path(baseline['stderr']).read_text(errors='replace') + Path(baseline['stdout']).read_text(errors='replace')
            receipt = receipt_from(raw);report['repositoryReceipt'] = receipt
            report['repositoryPolicy'] = repository_policy(receipt, candidate)
            workspace = attempt / 'guarded'
            stage_workspace(Path(freeze['sdkRoot']), workspace, candidate, True)
            adopted = True
        # If inventory fails, retain actual configured commands against only the
        # unchanged root; failure never authorizes inserting a new repository.
        for abi in ABI_CONFIGS:
            result = run_owned(command(freeze, workspace, attempt / ('output-' + abi), 'cquery', abi),
                workspace, attempt / abi, env, freeze['perInvocationSeconds'], freeze['logLimitBytes'],
                freeze['cleanupCeilingSeconds'])
            result['guardedOverlaySelectedForAttempt'] = adopted
            result['requestedConfiguration'] = ABI_CONFIGS[abi]
            result['compilationVerified'] = False;result['runtimeVerified'] = False
            result['configuredDependencyEvidenceComplete'] = False
            # A later successful graph needs explicit origin/configuration/interface
            # validation before this investigation can report compatibility.
            report['abis'][abi] = result;save()
        report['routeDecision'] = 'BLOCKED_PENDING_CONFIGURED_GRAPH_REVIEW'
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        report['failure'] = str(error)
        for abi in ABI_CONFIGS:
            report['abis'].setdefault(abi, {'classification': 'NOT_RUN', 'reason': str(error)})
        raise
    finally:
        save()
    return report


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--freeze', type=Path, required=True)
    p.add_argument('--freeze-sha256', required=True)
    p.add_argument('--attempt', type=Path, required=True)
    args = p.parse_args()
    try:
        freeze, candidate = load_freeze(args.freeze, args.freeze_sha256)
        freeze['verifiedFreezeSha256'] = args.freeze_sha256
        execute(freeze, candidate, args.attempt)
        return 2  # Commands alone never qualify a runtime build recipe.
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print('Analysis blocked: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
