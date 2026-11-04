"""Host orchestration probes only. Never acquires packages or executes vendor code."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
s=importlib.util.spec_from_file_location('dawn_qualification_test',ROOT/'scripts/diagnostics/dawn_python_patch_v1/qualify.py')
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)

class DawnQualificationTests(unittest.TestCase):
    def test_patch_program_is_exact_literal_not_shell(self):
        manifest=json.loads((ROOT/'planning/fixtures/root-dependency-offline-v2/patch-manifest.json').read_text())
        self.assertEqual(len(manifest['commands']),3)
        for command in manifest['commands']:
            program=m.patch_program(command);self.assertIn('generator/templates/api_cpp.h',program)
            with self.assertRaisesRegex(ValueError,'drift'):m.patch_program(dict(command,programSha256='0'*64))
        with self.assertRaises(ValueError):m.patch_program(dict(literalCommand='sh -c bad',programSha256='0'*64))
    def test_extra_download_rejects_before_network(self):
        with patch.object(m.urllib.request,'build_opener') as opener:
            with self.assertRaisesRegex(ValueError,'unapproved'):m.download(dict(filename='extra',bytes=1,sha256='0'*64),Path('/no/file'))
            opener.assert_not_called()
    def test_redirect_has_no_fallback(self):
        with self.assertRaisesRegex(ValueError,'redirect'):m.NoRedirect().redirect_request(None,None,302,'',{},'https://other.invalid')
    def test_admission_requires_all_scripts_and_exact_profile_before_launch(self):
        f=dict(protocol='dawn-python-patch-qualification-v1',admissionMode=m.MODE,python=str(m.catalog.HOST_PYTHON),inputs=[],implementationSha256='0'*64,preparationAccepted='/no/accepted')
        with patch.object(m.owner,'verify_files') as verify:
            with self.assertRaisesRegex(ValueError,'unbound'):m.admission(f)
            verify.assert_not_called()
        f['admissionMode']='full-cpp'
        with self.assertRaisesRegex(ValueError,'scope'):m.admission(f)
    def test_preparation_requires_confirmed_cleanup_and_exact_catalog(self):
        with tempfile.TemporaryDirectory() as t:
            p=Path(t)/'accepted.json';p.write_text(json.dumps(dict(cleanupConfirmed=False,admissionMode=m.MODE,compilerQualified=False)))
            f=dict(preparationAccepted=str(p),preparationAcceptedSha256=hashlib.sha256(p.read_bytes()).hexdigest())
            with self.assertRaisesRegex(ValueError,'admission'):m.preparation(f)
            f['preparationAcceptedSha256']='0'*64
            with self.assertRaisesRegex(ValueError,'changed'):m.preparation(f)
    def test_blocked_cpp_has_no_compiler_entrypoint(self):
        text=(ROOT/'scripts/diagnostics/dawn_python_patch_v1/qualify.py').read_text()
        self.assertNotIn('str(catalog.CLANG)',text)
        self.assertNotIn('subprocess.run',text)
        self.assertIn("CPPPositiveCompiles='NOT_RUN'",text)
    def test_sandbox_denies_network_and_external_writes(self):
        text=m.sandbox_text(Path('/tmp/owned'))
        self.assertIn('(deny network*)',text);self.assertIn('(deny file-write*)',text)
        self.assertIn('(subpath '+json.dumps(str(Path('/tmp/owned').resolve()))+')',text);self.assertNotIn('(allow network',text)

    def test_corrupt_exact_wheel_transport_fails_before_archive_use(self):
        artifact=json.loads((m.FIX/'candidate-inputs.json').read_text())['wheels'][1]
        from unittest.mock import MagicMock
        response=MagicMock();response.status=200;response.geturl.return_value=artifact['url']
        response.read.side_effect=[b'x'*artifact['bytes'],b'']
        response.__enter__.return_value=response
        opener=MagicMock();opener.open.return_value=response
        with tempfile.TemporaryDirectory() as t,patch.object(m.urllib.request,'build_opener',return_value=opener):
            with self.assertRaisesRegex(ValueError,'identity'):m.download(artifact,Path(t)/artifact['filename'])
            self.assertEqual(opener.open.call_count,1)
            self.assertEqual((Path(t)/artifact['filename']).stat().st_size,artifact['bytes'])
    def test_failed_prerequisite_preserves_error_and_not_run_stages(self):
        with tempfile.TemporaryDirectory() as t,patch.object(m,'preparation',side_effect=ValueError('injected prerequisite')):
            with self.assertRaisesRegex(ValueError,'injected'):m.worker({},Path(t))
            report=json.loads((Path(t)/'qualification-report.json').read_text())
            self.assertEqual(report['status'],'FAILED')
            self.assertEqual(report['stages']['prepared-inputs']['status'],'FAILED')
            self.assertEqual(report['stages']['imports-render']['status'],'NOT_RUN')
            self.assertFalse(report['vendorCodeExecuted']);self.assertFalse(report['compilerExecuted'])

class DawnQualificationDeadlineTests(unittest.TestCase):
    def exercise(self,delay=None):
        import contextlib,io,sys
        t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);root=Path(t.name);attempt=root/'attempts/one'
        f=dict(attemptParent=str(attempt.parent),python='synthetic-only',environment={})
        freeze=root/'freeze.json';freeze.write_text(json.dumps(f));clock=[0.0];counts=[0];calls=[]
        real_write=m.catalog.write;real_link=m.os.link
        def admission(value):
            counts[0]+=1
            if delay=='admission' and counts[0]==1:clock[0]=301
            elif delay=='final-revalidation' and counts[0]==2:clock[0]=350
            elif delay=='budget' and counts[0]==1:clock[0]=17
        def worker(argv,cwd,prefix,env,seconds,limit,cleanup):
            calls.append(dict(argv=argv,seconds=seconds,cleanup=cleanup));clock[0]=299 if delay else 20
            (attempt/'qualification-report.json').write_text(json.dumps(dict(status='PYTHON_GENERATION_SUBSET_PASSED_CPP_BLOCKED')))
            if delay=='foreign':(attempt/'accepted-subset.json').write_bytes(b'foreign-prior-evidence')
            return dict(classification='ANALYSIS_COMMAND_SUCCEEDED',cleanupConfirmed=True,elapsedSeconds=clock[0])
        def write(path,value,*args):
            real_write(path,value,*args)
            if delay=='receipt' and Path(path).name=='accepted-subset.pending.json':clock[0]=350
            if delay=='outcome' and Path(path).name=='outcome.pending.json':clock[0]=350
        def link(source,destination):
            real_link(source,destination)
            if delay=='publication' and Path(destination).name=='accepted-subset.json':clock[0]=350
            if delay=='outcome-publication' and Path(destination).name=='outcome.json':clock[0]=350
            if delay=='interrupt-accepted' and Path(destination).name=='accepted-subset.json':raise KeyboardInterrupt()
            if delay=='interrupt-outcome' and Path(destination).name=='outcome.json':raise KeyboardInterrupt()
        argv=['qualify.py','--freeze',str(freeze),'--sha256',hashlib.sha256(freeze.read_bytes()).hexdigest(),'--attempt',str(attempt)]
        with patch.object(sys,'argv',argv),patch.object(m,'admission',side_effect=admission),patch.object(m.owner,'run_owned',side_effect=worker),patch.object(m,'now',side_effect=lambda:clock[0]),patch.object(m.catalog,'write',side_effect=write),patch.object(m.os,'link',side_effect=link),contextlib.redirect_stderr(io.StringIO()):
            result=m.main()
        return result,attempt,calls
    def test_normal_subset_success_requires_cleanup_and_timely_publication(self):
        result,attempt,calls=self.exercise()
        self.assertEqual(result,2);self.assertTrue((attempt/'accepted-subset.json').exists())
        self.assertEqual(calls[0]['cleanup'],15)
        self.assertIn('--decision-deadline',calls[0]['argv'])
    def test_delayed_initial_admission_never_launches_worker(self):
        result,attempt,calls=self.exercise('admission')
        self.assertEqual(result,1);self.assertFalse(attempt.exists());self.assertEqual(calls,[])
    def test_worker_uses_remaining_original_budget(self):
        result,attempt,calls=self.exercise('budget')
        self.assertEqual(result,2);self.assertEqual(calls[0]['seconds'],283)
    def test_delayed_final_admission_cannot_publish(self):
        result,attempt,calls=self.exercise('final-revalidation')
        self.assertEqual(result,1);self.assertFalse((attempt/'accepted-subset.json').exists())
        self.assertIn('deadline',json.loads((attempt/'outcome.json').read_text())['failure'])
    def test_delayed_receipt_and_outcome_serialization_retain_pending_evidence(self):
        for delay,pending in [('receipt','accepted-subset.pending.json'),('outcome','outcome.pending.json')]:
            with self.subTest(delay=delay):
                result,attempt,calls=self.exercise(delay)
                self.assertEqual(result,1);self.assertFalse((attempt/'accepted-subset.json').exists())
                self.assertTrue((attempt/pending).exists())
                self.assertTrue(json.loads((attempt/'outcome.json').read_text())['acceptanceRevoked'])
    def test_late_publication_is_revoked_without_deleting_pending_evidence(self):
        for delay in ['publication','outcome-publication']:
            with self.subTest(delay=delay):
                result,attempt,calls=self.exercise(delay)
                self.assertEqual(result,1);self.assertFalse((attempt/'accepted-subset.json').exists())
                self.assertTrue((attempt/'accepted-subset.pending.json').exists())
                self.assertTrue((attempt/'outcome.pending.json').exists())
                self.assertEqual(json.loads((attempt/'outcome.json').read_text())['classification'],'QUALIFICATION_FAILED_OR_DEADLINE_REVOKED')

    def test_interruption_after_each_link_effect_revokes_actual_public_acceptance(self):
        for delay in ['interrupt-accepted','interrupt-outcome']:
            with self.subTest(delay=delay):
                result,attempt,calls=self.exercise(delay)
                self.assertEqual(result,1);self.assertFalse((attempt/'accepted-subset.json').exists())
                self.assertTrue((attempt/'accepted-subset.pending.json').exists())
                self.assertTrue((attempt/'outcome.pending.json').exists())
                terminal=json.loads((attempt/'outcome.json').read_text())
                self.assertTrue(terminal['acceptanceRevoked']);self.assertFalse(terminal['publicAcceptancePresent'])
    def test_foreign_public_file_is_preserved_and_revocation_not_claimed(self):
        result,attempt,calls=self.exercise('foreign')
        self.assertEqual(result,1)
        self.assertEqual((attempt/'accepted-subset.json').read_bytes(),b'foreign-prior-evidence')
        terminal=json.loads((attempt/'outcome.json').read_text())
        self.assertFalse(terminal['acceptanceRevoked']);self.assertTrue(terminal['publicAcceptancePresent'])


class DawnQualificationClockTests(unittest.TestCase):
    def test_absolute_clock_is_process_shared_with_pinned_host_python(self):
        import subprocess
        before=m.now()
        output=subprocess.check_output([str(m.catalog.HOST_PYTHON),'-I','-S','-B','-c',
            'import time,json; print(json.dumps(dict(now=time.clock_gettime(time.CLOCK_MONOTONIC),local=time.monotonic())))'],timeout=15)
        after=m.now();child=json.loads(output)
        self.assertLessEqual(before,child['now']);self.assertLessEqual(child['now'],after)
    def test_clock_does_not_use_python_process_relative_origin(self):
        with patch.object(m.time,'monotonic',side_effect=AssertionError('local clock used')),patch.object(m.time,'clock_gettime',return_value=1234.5) as clock:
            self.assertEqual(m.now(),1234.5);clock.assert_called_once_with(m.time.CLOCK_MONOTONIC)

class DawnRawMetadataTests(unittest.TestCase):
    def test_only_exact_known_optional_extra_spelling_is_admitted(self):
        original=json.loads((m.FIX/'candidate-inputs.json').read_text())['wheels'][0]
        expected=m.wheel_expectation(original)
        self.assertEqual(original['requiresDist'][1],'Babel>=2.7; extra == "i18n"')
        self.assertEqual(expected['requiresDist'][1],'Babel>=2.7 ; extra == "i18n"')
        self.assertEqual(expected['sha256'],original['sha256']);self.assertEqual(expected['selectedExtras'],[])
        for bad in ['Babel>=2.8; extra == "i18n"','Babel>=2.7 ; extra == "other"','Babel>=2.7']:
            changed=dict(original,requiresDist=['MarkupSafe>=2.0',bad])
            with self.assertRaisesRegex(ValueError,'unapproved'):m.wheel_expectation(changed)
    def test_retained_copy_requires_bound_identity_and_makes_no_network_request(self):
        from unittest.mock import MagicMock
        with tempfile.TemporaryDirectory() as t:
            src=Path(t)/'retained';src.write_bytes(b'synthetic public bytes')
            artifact=dict(filename=m.PINNED[0][0],bytes=src.stat().st_size,sha256=hashlib.sha256(src.read_bytes()).hexdigest())
            f=dict(retainedWheelInputs={artifact['filename']:str(src)},inputs=[dict(path=str(src),bytes=artifact['bytes'],sha256=artifact['sha256'])])
            with patch.object(m,'PINNED',((artifact['filename'],artifact['bytes'],artifact['sha256']),m.PINNED[1])),patch.object(m,'download',side_effect=AssertionError('network retry')):
                result=m.acquire_artifact(artifact,Path(t)/'copy',f,m.now()+300)
            self.assertEqual(result['networkRequests'],0);self.assertEqual((Path(t)/'copy').read_bytes(),src.read_bytes())
            f['inputs']=[]
            with patch.object(m,'PINNED',((artifact['filename'],artifact['bytes'],artifact['sha256']),m.PINNED[1])):
                with self.assertRaisesRegex(ValueError,'unbound'):m.acquire_artifact(artifact,Path(t)/'copy2',f,m.now()+300)
            src.write_bytes(b'corrupt');f['inputs']=[dict(path=str(src),bytes=artifact['bytes'],sha256=artifact['sha256'])]
            with patch.object(m,'PINNED',((artifact['filename'],artifact['bytes'],artifact['sha256']),m.PINNED[1])):
                with self.assertRaisesRegex(ValueError,'changed'):m.acquire_artifact(artifact,Path(t)/'copy3',f,m.now()+300)
