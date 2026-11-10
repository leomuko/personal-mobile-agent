"""Host-only V2 freeze/admission regressions. No native dispatch or marker writes."""
import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
s=importlib.util.spec_from_file_location('bridge_admission_tests',ROOT/'scripts/diagnostics/linker_parser_bridge_v2/freeze.py')
freeze=importlib.util.module_from_spec(s);s.loader.exec_module(freeze)
e=freeze.e


def fixture():
    baseline=e.plan.base_freeze()
    rows={r['path']:copy.deepcopy(r) for r in baseline['inputs']}
    for path in e.required_inputs()|{e.plan.contract()['target']['path']}:
        rows.setdefault(path,dict(path=path,bytes=0,mode=0o644,sha256='0'*64))
    return dict(protocol=e.plan.PROTOCOL,scope=e.plan.SCOPE,baselineSha256=e.plan.old.BASE_SHA,
                proposalSha256=e.plan.old.PROPOSAL_SHA,implementationSha256=e.owner.digest(e.__file__),
                parserSha256=e.plan.PARSER_SHA,parserLockSha256=e.plan.PARSER_LOCK_SHA,
                contract=e.plan.contract(),inputs=list(rows.values()),trees=baseline['trees'],
                nativeFiles=baseline['nativeFiles'],backendCatalog=baseline['backendCatalog'],cacheMap=baseline['cacheMap'],
                markerReceipt={'mocked':'identity-only'},executionAuthorized=False,acceptancePublicationAllowed=False,
                launchProfile=dict(hostTool='exec_command',sandbox_permissions='require_escalated',shell='/bin/zsh',
                                   login=False,workdir=str(ROOT),persistentRuleRequested=False,
                                   maximumParentInvocations=1,automaticFallback=False))


class FreezeTests(unittest.TestCase):
    def test_exact_contract_is_old_recipe_rebased_without_permission_or_option_change(self):
        import json
        prior=e.plan.old.contract()
        expected=json.loads(json.dumps(prior).replace(str(e.plan.old.ATTEMPT),str(e.plan.ATTEMPT)))
        self.assertEqual(e.plan.contract(),expected)
        self.assertNotEqual(e.plan.ATTEMPT,e.plan.old.ATTEMPT)
        self.assertFalse(e.plan.ATTEMPT.exists())

    def test_valid_structural_freeze_checks_with_mocked_marker_and_no_dispatch(self):
        value=fixture()
        with patch.object(e,'preparation_receipt',return_value=value['markerReceipt']), \
             patch.object(e.owner,'run_owned',side_effect=AssertionError('native owner forbidden')), \
             patch.object(e.c,'metadata_command',side_effect=AssertionError('native metadata forbidden')):
            e.validate_freeze(value)
        self.assertLessEqual(len(value['inputs']),256)

    def test_superseded_bridge_r1_freeze_cannot_admit_changed_implementation(self):
        import json
        prior=json.loads((e.plan.FIX/'execution-freeze-r1.json').read_bytes())
        with self.assertRaisesRegex(ValueError,'diagnostic identity changed'):
            e.validate_freeze(prior)

    def test_identity_scope_launch_and_permission_mutations_reject(self):
        original=fixture()
        for kind in ('protocol','scope','baseline','proposal','implementation','parser','parser-lock',
                     'attempt','target','flag','profile','env','launch','authorization','publication',
                     'marker','trees','native','catalog','cache'):
            with self.subTest(kind=kind):
                f=copy.deepcopy(original)
                if kind in ('protocol','scope'):f[kind]='changed'
                elif kind in ('baseline','proposal','implementation','parser'):f[kind+'Sha256']='f'*64
                elif kind=='parser-lock':f['parserLockSha256']='f'*64
                elif kind=='attempt':f['contract']['attempt']=str(e.plan.old.ATTEMPT)
                elif kind=='target':f['contract']['target']['path']='/usr/bin/ld'
                elif kind=='flag':f['contract']['commands'][0]['argv'].remove('-###')
                elif kind=='profile':f['contract']['commands'][0]['profile']+='(allow process-exec)'
                elif kind=='env':f['contract']['nativeEnvironment']['PATH']='/usr/bin'
                elif kind=='launch':f['launchProfile']['persistentRuleRequested']=True
                elif kind=='authorization':f['executionAuthorized']=True
                elif kind=='publication':f['acceptancePublicationAllowed']=True
                elif kind=='marker':f['markerReceipt']={'different':True}
                elif kind=='trees':f['trees']=[]
                elif kind=='native':f['nativeFiles']=[]
                elif kind=='catalog':f['backendCatalog']='changed'
                elif kind=='cache':f['cacheMap']='changed'
                with patch.object(e,'preparation_receipt',return_value=original['markerReceipt']),self.assertRaises(ValueError):
                    e.validate_freeze(f)

    def test_missing_duplicate_excess_and_changed_baseline_inputs_reject(self):
        original=fixture()
        for kind in ('missing-new','missing-old','duplicate','excess','changed-baseline'):
            with self.subTest(kind=kind):
                f=copy.deepcopy(original)
                if kind=='missing-new':f['inputs']=[r for r in f['inputs'] if r['path']!=str(e.HERE/'bridge.py')]
                if kind=='missing-old':f['inputs']=f['inputs'][1:]
                if kind=='duplicate':f['inputs'].append(f['inputs'][0])
                if kind=='excess':f['inputs'].extend(dict(path='/synthetic/'+str(i)) for i in range(257))
                if kind=='changed-baseline':f['inputs'][0]['sha256']='f'*64
                with patch.object(e,'preparation_receipt',return_value=original['markerReceipt']),self.assertRaises(ValueError):
                    e.validate_freeze(f)

    def test_readonly_admission_rejects_mismatched_review_before_input_check(self):
        f=fixture();sha='1'*64;review_sha='2'*64
        review=dict(verdict='PASS',scope=e.plan.SCOPE,freezeSha256=sha)
        for kind in ('valid','freeze-hash','review-hash','verdict','scope','review-freeze','input-drift'):
            with self.subTest(kind=kind):
                r=copy.deepcopy(review)
                if kind in ('verdict','scope'):r[kind]='changed'
                if kind=='review-freeze':r['freezeSha256']='0'*64
                actual='0'*64 if kind=='freeze-hash' else sha
                actual_r='0'*64 if kind=='review-hash' else review_sha
                with patch.object(e.q.base,'read_json_bound',side_effect=[(f,actual),(r,actual_r)]), \
                     patch.object(e,'validate_freeze') as structural, \
                     patch.object(e.q,'check_inputs',side_effect=ValueError('drift') if kind=='input-drift' else None) as check, \
                     patch.object(e.owner,'run_owned',side_effect=AssertionError('no native owner')):
                    if kind=='valid':self.assertIs(e.admission(Path('freeze'),sha,Path('review'),review_sha),f)
                    else:
                        with self.assertRaises(ValueError):e.admission(Path('freeze'),sha,Path('review'),review_sha)
                    self.assertEqual(check.call_count,1 if kind in ('valid','input-drift') else 0)
                    self.assertEqual(structural.call_count,1 if kind in ('valid','input-drift') else 0)

    def test_context_substitution_is_rejected_before_parsing(self):
        f=fixture()
        for kind in ('attempt','target','physical-attempt'):
            with self.subTest(kind=kind):
                altered=copy.deepcopy(f)
                physical=None
                if kind=='attempt':altered['contract']['attempt']=str(e.plan.old.ATTEMPT)
                if kind=='target':altered['contract']['target']['path']='/usr/bin/ld'
                if kind=='physical-attempt':physical=e.plan.old.ATTEMPT
                with self.assertRaises(ValueError):e.bridge.context(altered,physical)

    def test_marker_snapshot_records_device_only_change_without_modifying_marker(self):
        prior=e.q.data(e.plan.old.PREPARATION)
        spec=e.plan.old.proposal()
        observed=copy.deepcopy(prior['marker'])
        observed['dev']+=1
        for parent in observed['parents']:parent['dev']=observed['dev']
        with patch.object(freeze.e.plan.old,'proposal',return_value=spec), \
             patch.object(freeze.e.q,'data',return_value=prior), \
             patch.object(freeze.e.marker,'inspect',return_value=observed), \
             patch.object(freeze.e.marker,'prepare',side_effect=AssertionError('marker mutation forbidden')), \
             patch.object(freeze.e.c,'write') as write:
            value=freeze.marker_snapshot()
        self.assertEqual(value['observedChange'],'device-number-only')
        self.assertEqual(value['marker'],observed)
        self.assertFalse(value['markerModified'])
        self.assertEqual(write.call_count,1)
        self.assertEqual(write.call_args.args[0],e.plan.PREPARATION)

    def test_marker_other_drift_and_ambiguous_device_mapping_block_snapshot(self):
        prior=e.q.data(e.plan.old.PREPARATION);spec=e.plan.old.proposal()
        for kind in ('sha256','ino','mode','parent-inode','extra-parent','mixed-device'):
            with self.subTest(kind=kind):
                actual=copy.deepcopy(prior['marker'])
                if kind=='sha256':actual['sha256']='f'*64
                if kind in ('ino','mode'):actual[kind]+=1
                if kind=='parent-inode':actual['parents'][0]['ino']+=1
                if kind=='extra-parent':actual['parents'].append(actual['parents'][-1])
                if kind=='mixed-device':actual['dev']+=1
                with patch.object(freeze.e.plan.old,'proposal',return_value=spec), \
                     patch.object(freeze.e.q,'data',return_value=prior), \
                     patch.object(freeze.e.marker,'inspect',return_value=actual), \
                     patch.object(freeze.e.c,'write') as write,self.assertRaises(ValueError):freeze.marker_snapshot()
                write.assert_not_called()


if __name__=='__main__':unittest.main()
