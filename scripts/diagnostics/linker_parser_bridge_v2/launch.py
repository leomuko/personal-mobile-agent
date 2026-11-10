#!/usr/bin/env python3
"""Read-only admission and exact request construction. Never launches a process."""
import argparse
from pathlib import Path
import re
import shlex
import importlib.util
s=importlib.util.spec_from_file_location('v2_bridge_launch_plan',Path(__file__).with_name('plan.py'))
plan=importlib.util.module_from_spec(s);s.loader.exec_module(plan)
e=plan.load('executor',Path(__file__).with_name('qualify.py'))


def request(freeze_sha,review_sha):
    e.require(all(re.fullmatch('[0-9a-f]{64}',h or '') for h in (freeze_sha,review_sha)),'invalid hashes')
    freeze=plan.FIX/'execution-freeze-r2.json';review=plan.FIX/'implementation-review-r2.json'
    f=e.admission(freeze,freeze_sha,review,review_sha)
    e.quarantine(plan.ATTEMPT.parent)
    e.require(not e.os.path.lexists(plan.ATTEMPT),'fresh native attempt required')
    expected=dict(hostTool='exec_command',sandbox_permissions='require_escalated',shell='/bin/zsh',login=False,
                  workdir=str(plan.ROOT),persistentRuleRequested=False,maximumParentInvocations=1,automaticFallback=False)
    e.require(f['launchProfile']==expected,'changed outer launch profile')
    argv=[f['contract']['python'],'-I','-S','-B',str(Path(__file__).with_name('qualify.py')),
          '--freeze',str(freeze),'--sha256',freeze_sha,'--review',str(review),
          '--review-sha256',review_sha,'--attempt',str(plan.ATTEMPT)]
    return dict(nativeQueriesExecuted=0,approvalGranted=False,argv=argv,
                toolRequest=dict(cmd=shlex.join(argv),workdir=str(plan.ROOT),shell='/bin/zsh',login=False,
                    sandbox_permissions='require_escalated',yield_time_ms=1000,max_output_tokens=2000,
                    justification='May I run the independently reviewed two-case synthetic-target diagnostic once, retaining real-linker execution denial and first-failure stopping?'))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--freeze-sha256',required=True);p.add_argument('--review-sha256',required=True)
    a=p.parse_args()
    import json
    print(json.dumps(request(a.freeze_sha256,a.review_sha256),sort_keys=True,indent=2))


if __name__=='__main__':main()
