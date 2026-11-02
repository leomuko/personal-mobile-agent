#!/usr/bin/env python3
"""Prepare a new server-only freeze; executes no Java or Bazel commands."""
import argparse
import json
from pathlib import Path

from analyze import base, jvm, JAVA_FIELDS, HERE, ROOT, module

prepare = module('retained_input_preparation', HERE.parent / 'ml_drift_compatibility/prepare.py')
OLD_SHA = '5da08299b99c822ffcfcb3b9b5eae85594a782fc0f6d66b7621ca5d3c582b4aa'
PREVIOUS = ROOT / 'planning/fixtures/ml-drift-compatibility-v1/analysis-freeze-v2.json'
FIXTURES = ROOT / 'planning/fixtures/bazel-server-jvm-v1'
BUILD = ROOT / 'build/bazel-server-jvm-v1'


def row(path):
    return {'path': str(path), 'bytes': path.stat().st_size, 'sha256': base.digest(path)}


def create():
    old, _ = base.load_freeze(PREVIOUS, OLD_SHA)
    BUILD.mkdir(parents=True, exist_ok=True); FIXTURES.mkdir(parents=True, exist_ok=True)
    output = FIXTURES / 'analysis-freeze.json'
    base.require(not output.exists(), 'preserve existing freeze')
    jdk = BUILD / 'server-jdk-21.0.5'
    receipt = FIXTURES / 'server-jdk-provenance.json'
    jvm.stage(Path(old['bazelPath']), jdk, receipt)
    manifest = BUILD / 'server-jdk-tree.json'
    with manifest.open('x') as f:
        json.dump(prepare.inventory(jdk), f, indent=2); f.write('\n')
    extra = [PREVIOUS, ROOT / 'planning/fixtures/ml-drift-compatibility-v1/result-lock.json',
             ROOT / 'planning/P2_BAZEL_SERVER_JVM_CORRECTION_PROPOSAL_V1.md',
             ROOT / 'planning/P2_BAZEL_SERVER_JVM_CORRECTION_EXECUTION_V1.md',
             FIXTURES / 'previous-results-review.json', receipt, manifest, jdk / 'bin/java',
             ROOT / 'scripts/tests/test_bazel_server_jvm.py'] + sorted(HERE.glob('*.py'))
    rows = {r['path']: r for r in old['inputs']}
    rows.update({str(p): row(p) for p in extra})
    old.update(serverJvmProtocol='bazel-761-private-server-jvm-v1',
               javaHome=str(jdk), serverJavaHome=str(jdk), serverJdkReceipt=str(receipt),
               serverRunnerSha256=base.digest(HERE / 'analyze.py'), javaInspectionFields=JAVA_FIELDS,
               previousAnalysisFreezePath=str(PREVIOUS), previousAnalysisFreezeSha256=OLD_SHA,
               inputs=[rows[k] for k in sorted(rows)], reviewRevision=1,
               attemptParent=str(BUILD / 'attempts'),
               repositoryCache=str(BUILD / 'isolated-repository-cache'))
    old['trees'].append({'root': str(jdk), 'manifest': str(manifest)})
    old['toolchainFacts']['serverJava'] = 'bundled Java 21.0.5, archive-bound private tree'
    old['toolchainFacts']['java'] = 'compilation environment retains Temurin-17.0.20.1+1; resolved tools unproved'
    with output.open('x') as f:
        json.dump(old, f, indent=2); f.write('\n')
    print(base.digest(output))


if __name__ == '__main__':
    argparse.ArgumentParser(description=__doc__).parse_args()
    create()
