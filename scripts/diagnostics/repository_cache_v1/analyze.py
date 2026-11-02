#!/usr/bin/env python3
"""Clone a sealed archive cache, then reuse the reviewed three-command analysis."""
import argparse
import importlib.util
import json
from pathlib import Path
import shutil
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]


def module(name, path):
    s = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(s); s.loader.exec_module(value); return value


previous = module('reviewed_server_analysis', HERE.parent / 'bazel_server_jvm_v1/analyze.py')
prep = module('archive_preparation', HERE / 'prepare.py')
require = prep.require


def load_freeze(path, expected):
    f, candidate = previous.load_freeze(path, expected)
    require(f['cacheAnalysisProtocol'] == 'repository-archive-cache-v1' and
            f['cacheRunnerSha256'] == prep.sha(__file__), 'cache runner changed')
    bound = {r['path'] for r in f['inputs']}
    require({str(HERE / 'prepare.py'), str(HERE / 'analyze.py'), f['cacheReceipt'],
             f['cacheManifest'], f['preparationReport'], f['preparationFreeze']} <= bound,
            'unbound cache/preparation inputs')
    manifest = json.loads(Path(f['cacheManifest']).read_text())
    receipt = json.loads(Path(f['cacheReceipt']).read_text())
    report = json.loads(Path(f['preparationReport']).read_text())
    require(report.get('preparationDeadlineSeconds') == 900 and
            type(report.get('elapsedSeconds')) in [int, float] and
            0 <= report['elapsedSeconds'] < 900, 'over-budget/unknown preparation timing')
    require(report['completed'] is True and report['manifestSha256'] == prep.sha(f['cacheManifest']) and
            receipt['manifestSha256'] == report['manifestSha256'] and
            report['freezeSha256'] == prep.sha(f['preparationFreeze']), 'incomplete/mismatched preparation')
    require([r['sha256'] for r in manifest['entries']] == [r['sha256'] for r in receipt['entries']],
            'prepared manifest/receipt identity mismatch')
    require(len(report['entries']) == len(receipt['entries']) and
            all(r['accepted'] and r['worker']['cleanupConfirmed'] and
                r['worker']['classification'] == 'ANALYSIS_COMMAND_SUCCEEDED' for r in report['entries']),
            'failed preparation cannot enable analysis')
    require(all(r['sha256'] == c['sha256'] and r['cache'] == c
                for r, c in zip(report['entries'], receipt['entries'])), 'preparation outcome identity mismatch')
    prep.verify_cache(Path(f['cacheSeed']), receipt)
    require(f['minimumFreeBytes'] == 18 * 1024**3, 'analysis storage preflight changed')
    require(Path(f['repositoryCache']).resolve().is_relative_to(ROOT / 'build/repository-cache-v1'),
            'analysis working cache outside isolated root')
    return f, candidate


def execute(f, candidate, attempt):
    require(not attempt.exists() and not Path(f['repositoryCache']).exists(), 'preserve prior analysis/cache')
    require(shutil.disk_usage(ROOT).free >= f['minimumFreeBytes'], 'BLOCKED_STORAGE: need 18 GiB free')
    receipt = json.loads(Path(f['cacheReceipt']).read_text())
    prep.verify_cache(Path(f['cacheSeed']), receipt)
    # Only byte copies: do not hardlink analysis-writable files to accepted inputs.
    shutil.copytree(f['cacheSeed'], f['repositoryCache'], symlinks=False)
    prep.verify_cache(Path(f['repositoryCache']), receipt)
    return previous.execute(f, candidate, attempt)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--freeze', type=Path, required=True); p.add_argument('--freeze-sha256', required=True)
    p.add_argument('--attempt', type=Path, required=True); a = p.parse_args()
    try:
        f, candidate = load_freeze(a.freeze, a.freeze_sha256)
        f['verifiedFreezeSha256'] = a.freeze_sha256
        execute(f, candidate, a.attempt); return 2
    except (OSError, ValueError, KeyError, previous.base.subprocess.SubprocessError) as error:
        print('Analysis blocked: ' + str(error), file=sys.stderr); return 1


if __name__ == '__main__':
    raise SystemExit(main())
