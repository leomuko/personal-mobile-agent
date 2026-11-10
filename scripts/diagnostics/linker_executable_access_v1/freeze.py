#!/usr/bin/env python3
"""Prepare the inert marker and seal exact inputs; never create a native attempt."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import importlib.util
s=importlib.util.spec_from_file_location('access_freeze_plan',Path(__file__).with_name('plan.py'))
plan=importlib.util.module_from_spec(s);s.loader.exec_module(plan)
e=plan.load('executor',Path(__file__).with_name('qualify.py'))


def prepare():
    value=plan.proposal();target=value['target']
    root=plan.PREPARATION.parent
    root.mkdir(mode=0o700,exist_ok=True)
    st=root.lstat()
    e.require(not root.is_symlink() and st.st_uid==os.getuid() and (st.st_mode & 0o7777)==0o700,
              'private preparation root required')
    e.require(not os.path.lexists(root/'preparation-failure.json'),'failed preparation retained; no automatic retry')
    e.require(not os.path.lexists(plan.ATTEMPT),'native attempt already exists')
    e.require(not os.path.lexists(plan.PREPARATION),'marker receipt already exists')
    try:
        receipt=e.marker.prepare(Path(target['path']),target['text'].encode())
        result=dict(schema=1,proposalSha256=plan.PROPOSAL_SHA,marker=receipt,nativeQueriesExecuted=0,
                    targetExecutionAllowed=False,osEnforcedImmutabilityEstablished=False)
        e.c.write(plan.PREPARATION,result,65536)
        return result
    except (ValueError,OSError,KeyboardInterrupt) as error:
        e.c.write(root/'preparation-failure.json',dict(status='FAILED',nativeQueriesExecuted=0,
            failure=type(error).__name__+': '+str(error)[:2048],partialInputsRetained=True),65536)
        raise


def create():
    e.quarantine(plan.ATTEMPT.parent)
    e.require(not os.path.lexists(plan.ATTEMPT),'native attempt must remain absent')
    baseline=plan.base_freeze()
    rows={r['path']:copy.deepcopy(r) for r in baseline['inputs']}
    paths=e.required_inputs()|{plan.contract()['target']['path']}
    paths|={str(plan.ROOT/'build/linker-executable-access-v1/focused-python39.log'),
            str(plan.ROOT/'build/linker-executable-access-v1/full-host-suite.log')}
    for path in paths:
        r=e.c.file_row(path)
        e.require(r['path'] not in rows or rows[r['path']]==r,'conflicting input identity')
        rows[r['path']]=r
    f=dict(schema=1,protocol=plan.PROTOCOL,scope=plan.SCOPE,baselineSha256=plan.BASE_SHA,
           proposalSha256=plan.PROPOSAL_SHA,implementationSha256=e.owner.digest(e.__file__),
           contract=plan.contract(),inputs=[rows[k] for k in sorted(rows)],trees=baseline['trees'],
           nativeFiles=baseline['nativeFiles'],backendCatalog=baseline['backendCatalog'],cacheMap=baseline['cacheMap'],
           markerReceipt=e.preparation_receipt(),executionAuthorized=False,acceptancePublicationAllowed=False,
           P2Complete=False,semanticEnablement=False,nativeQueriesExecuted=0,
           launchProfile=dict(hostTool='exec_command',sandbox_permissions='require_escalated',
                             shell='/bin/zsh',login=False,workdir=str(plan.ROOT),
                             persistentRuleRequested=False,maximumParentInvocations=1,automaticFallback=False))
    e.validate_freeze(f);e.q.check_inputs(f)
    return f


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--prepare-marker',action='store_true');p.add_argument('--output',type=Path)
    a=p.parse_args()
    if a.prepare_marker:
        e.require(a.output is None,'marker preparation is separate');prepare();return
    e.require(a.output is not None and a.output.absolute()==plan.FIX/'execution-freeze-r1.json',
              'unexpected freeze output')
    f=create();payload=(json.dumps(f,sort_keys=True,indent=2)+'\n').encode()
    e.require(len(payload)<=32*1024**2,'freeze byte ceiling')
    with a.output.open('xb') as stream:stream.write(payload)
    print(json.dumps(dict(status='AWAITING_INDEPENDENT_IMPLEMENTATION_QA',inputs=len(f['inputs']),
                         freezeSha256=hashlib.sha256(payload).hexdigest(),nativeQueriesExecuted=0)))


if __name__=='__main__':main()
