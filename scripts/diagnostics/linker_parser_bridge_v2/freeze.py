#!/usr/bin/env python3
"""Capture read-only marker identity and freeze inputs; never dispatch a process."""
import argparse
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path

s=importlib.util.spec_from_file_location('bridge_freeze_plan',Path(__file__).with_name('plan.py'))
plan=importlib.util.module_from_spec(s);s.loader.exec_module(plan)
e=plan.load('executor',Path(__file__).with_name('qualify.py'))


def marker_snapshot():
    e.require(not os.path.lexists(plan.ATTEMPT),'proposed attempt must remain absent')
    prior=e.q.data(plan.old.PREPARATION,65536)
    spec=plan.old.proposal()['target']
    actual=e.marker.inspect(Path(spec['path']),spec['text'].encode())
    # The observed host change is not silently relabeled as the old identity.
    # Only device numbers may differ; all other old file/parent fields must agree.
    def without_device(value):
        if isinstance(value,dict):return {k:without_device(v) for k,v in value.items() if k!='dev'}
        if isinstance(value,list):return [without_device(v) for v in value]
        return value
    e.require(without_device(actual)==without_device(prior['marker']), 'unexpected marker identity drift')
    olddev={x['dev'] for x in prior['marker']['parents']}|{prior['marker']['dev']}
    newdev={x['dev'] for x in actual['parents']}|{actual['dev']}
    e.require(len(olddev)==len(newdev)==1,'ambiguous filesystem device transition')
    value=dict(schema=1,proposalSha256=plan.old.PROPOSAL_SHA,
               priorReceiptSha256=e.owner.digest(plan.old.PREPARATION),marker=actual,
               priorMarkerDevice=prior['marker']['dev'],currentMarkerDevice=actual['dev'],
               observedChange='device-number-only' if actual!=prior['marker'] else 'none',
               cause='UNPROVED',nativeQueriesExecuted=0,markerModified=False,
               osEnforcedImmutabilityEstablished=False)
    e.c.write(plan.PREPARATION,value,65536)
    return value


def create():
    e.quarantine(plan.ATTEMPT.parent)
    e.require(not os.path.lexists(plan.ATTEMPT),'fresh attempt required')
    baseline=plan.base_freeze()
    rows={r['path']:copy.deepcopy(r) for r in baseline['inputs']}
    for path in e.required_inputs()|{plan.contract()['target']['path']}:
        row=e.c.file_row(path)
        e.require(path not in rows or row==rows[path],'conflicting baseline binding')
        rows[path]=row
    f=dict(schema=1,protocol=plan.PROTOCOL,scope=plan.SCOPE,baselineSha256=plan.old.BASE_SHA,
           proposalSha256=plan.old.PROPOSAL_SHA,implementationSha256=e.owner.digest(e.__file__),
           parserSha256=plan.PARSER_SHA,parserLockSha256=plan.PARSER_LOCK_SHA,
           contract=plan.contract(),inputs=[rows[k] for k in sorted(rows)],trees=baseline['trees'],
           nativeFiles=baseline['nativeFiles'],backendCatalog=baseline['backendCatalog'],cacheMap=baseline['cacheMap'],
           markerReceipt=e.preparation_receipt(),executionAuthorized=False,acceptancePublicationAllowed=False,
           P2Complete=False,semanticEnablement=False,nativeQueriesExecuted=0,
           launchProfile=dict(hostTool='exec_command',sandbox_permissions='require_escalated',
                             shell='/bin/zsh',login=False,workdir=str(plan.ROOT),
                             persistentRuleRequested=False,maximumParentInvocations=1,automaticFallback=False))
    e.validate_freeze(f)
    e.q.check_inputs(f)
    e.q.native_rows(f)  # Static Mach-O and supplier inspection, no native command.
    return f


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--snapshot-marker',action='store_true');p.add_argument('--output',type=Path)
    a=p.parse_args()
    if a.snapshot_marker:
        e.require(a.output is None,'identity capture is separate');marker_snapshot();return
    e.require(a.output is not None and a.output==plan.FIX/'execution-freeze-r2.json','unexpected freeze output')
    value=create();raw=(json.dumps(value,sort_keys=True,indent=2)+'\n').encode()
    e.require(len(raw)<=32*1024**2,'freeze byte ceiling')
    with a.output.open('xb') as stream:stream.write(raw)
    print(json.dumps(dict(status='AWAITING_INDEPENDENT_IMPLEMENTATION_QA',inputs=len(value['inputs']),
                         freezeSha256=hashlib.sha256(raw).hexdigest(),nativeQueriesExecuted=0)))


if __name__=='__main__':main()
