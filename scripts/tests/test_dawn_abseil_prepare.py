"""Admission, ownership/deadline and publication probes; HTTP/owner always mocked."""
import contextlib,hashlib,importlib.util,io,json,sys,tempfile,unittest
from pathlib import Path
from unittest.mock import MagicMock,patch
ROOT=Path(__file__).resolve().parents[2]
s=importlib.util.spec_from_file_location('test_abseil_prepare',ROOT/'scripts/diagnostics/dawn_abseil_v1/prepare.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)

class AbseilPrepareTests(unittest.TestCase):
    def setUp(self):
        parent=ROOT/'build/dawn-abseil-preparation-v1/unit-fixtures';parent.mkdir(parents=True,exist_ok=True)
        t=tempfile.TemporaryDirectory(dir=parent);self.addCleanup(t.cleanup);self.root=Path(t.name)
    def exercise(self,mode='ok'):
        attempt=self.root/'attempts/prepare-1';freeze=self.root/'freeze.json';freeze.write_text('{}')
        review=self.root/'review.json';review.write_text('{}');clock=[0.0];count=[0];validations=[0]
        f=dict(attemptParent=str(attempt.parent),attemptName='prepare-1',python='mock-only',environment={})
        def validate(*args):
            validations[0]+=1
            if validations[0]>1:
                if mode=='review-revoked':raise ValueError('review changed')
                m.verify_inputs(f)
            return f,{}
        def worker(*args):
            count[0]+=1;clock[0]=299 if mode!='ok' else 20
            if mode=='cleanup':raise m.subprocess.TimeoutExpired('mock-owner',15)
            if mode=='cancel':raise KeyboardInterrupt()
            m.catalog.write(attempt/'preparation-report.json',dict(status='FAILED' if mode=='failed' else 'ABSEIL_SOURCE_PREPARATION_PASSED_CPP_BLOCKED'))
            return dict(classification='ANALYSIS_COMMAND_SUCCEEDED',cleanupConfirmed=mode!='uncertain')
        def verify(*args):
            if mode=='late-check':clock[0]=350
        real_link=m.os.link;real_write=m.catalog.write
        def write(path,value,*args):
            real_write(path,value,*args)
            if mode=='late-receipt' and Path(path).name=='accepted-preparation.pending.json':clock[0]=350
            if mode=='late-outcome' and Path(path).name=='outcome.pending.json':clock[0]=350
        def link(source,destination):
            real_link(source,destination)
            if Path(destination).name=='accepted-preparation.json':
                if mode=='late-link':clock[0]=350
                if mode=='interrupt-link':raise KeyboardInterrupt()
        argv=['prepare.py','--freeze',str(freeze),'--sha256','synthetic','--review',str(review),'--review-sha256','synthetic','--attempt',str(attempt)]
        with patch.object(sys,'argv',argv),patch.object(m,'validate_freeze',side_effect=validate),patch.object(m,'verify_inputs',side_effect=verify),patch.object(m.owner,'run_owned',side_effect=worker),patch.object(m,'now',side_effect=lambda:clock[0]),patch.object(m.base,'now',side_effect=lambda:clock[0]),patch.object(m.os,'link',side_effect=link),patch.object(m.catalog,'write',side_effect=write),contextlib.redirect_stderr(io.StringIO()):
            result=m.main()
        return result,attempt,count[0]
    def test_complete_preparation_is_only_subset_acceptance_after_confirmed_cleanup(self):
        result,attempt,calls=self.exercise()
        self.assertEqual(result,2);self.assertEqual(calls,1)
        accepted=m.read_json(attempt/'accepted-preparation.json');self.assertFalse(accepted['cppReady']);self.assertFalse(accepted['rootAdopted'])
    def test_worker_failure_cancel_and_uncertain_cleanup_never_accept(self):
        for mode in ['failed','cancel','cleanup','uncertain']:
            with self.subTest(mode=mode):
                self.root=self.root/mode;self.root.mkdir()
                result,attempt,calls=self.exercise(mode)
                self.assertEqual(result,1);self.assertFalse((attempt/'accepted-preparation.json').exists());self.assertEqual(calls,1)
                outcome=m.read_json(attempt/'outcome.json')
                if mode in ['cancel','cleanup','uncertain']:
                    self.assertEqual(outcome['cleanupStatus'],'UNCERTAIN')
                    with self.assertRaisesRegex(ValueError,'quarantined'):m.quarantine(attempt.parent)
    def test_late_validation_and_link_or_interrupted_link_cannot_publish(self):
        for mode in ['late-check','late-link','interrupt-link','late-receipt','late-outcome','review-revoked']:
            with self.subTest(mode=mode):
                self.root=self.root/mode;self.root.mkdir()
                result,attempt,calls=self.exercise(mode)
                self.assertEqual(result,1);self.assertFalse((attempt/'accepted-preparation.json').exists())
                self.assertTrue(m.read_json(attempt/'outcome.json')['acceptanceRevoked'])
    def test_missing_or_mismatched_review_blocks_before_any_owner_or_network(self):
        f=self.root/'f.json';f.write_text('{}');r=self.root/'r.json';r.write_text(json.dumps(dict(verdict='PASS',freezeSha256='wrong',scope='abseil-acquisition-preparation-only')))
        with patch.object(m.owner,'run_owned') as owned,patch.object(m.urllib.request,'build_opener') as opener:
            with self.assertRaisesRegex(ValueError,'review'):m.validate_freeze(f,hashlib.sha256(f.read_bytes()).hexdigest(),r,hashlib.sha256(r.read_bytes()).hexdigest())
            owned.assert_not_called();opener.assert_not_called()
    def test_redirect_scope_is_exact_and_cannot_repeat(self):
        events=[];handler=m.ExactRedirect(events);request=m.urllib.request.Request(m.ORIGIN)
        result=handler.redirect_request(request,None,302,'',{},m.REDIRECT);self.assertEqual(result.full_url,m.REDIRECT)
        for req,target in [(request,m.REDIRECT),(m.urllib.request.Request(m.REDIRECT),m.ORIGIN),(request,'https://other.invalid')]:
            with self.assertRaisesRegex(ValueError,'redirect|event ceiling'):handler.redirect_request(req,None,302,'',{},target)
    def test_corrupt_and_oversized_transfer_are_retained_but_not_promoted(self):
        response=MagicMock();response.status=200;response.geturl.return_value=m.ORIGIN;response.headers={};response.__enter__.return_value=response
        opener=MagicMock();opener.open.return_value=response
        for index,data in enumerate([b'corrupt',b'x'*1024]):
            response.read1.side_effect=[data,b''];partial=self.root/('partial'+str(index))
            with patch.object(m.urllib.request,'build_opener',return_value=opener),patch.dict(m.archive.LIMITS,{'compressed':512}):
                with self.assertRaisesRegex(ValueError,'SHA|ceiling'):m.download(partial,m.archive.primitives.Budget(),m.now()+30,[])
            self.assertFalse((self.root/'accepted-preparation.json').exists())
    def test_shell_command_is_literal_pinned_and_private(self):
        source=self.root/'source';source.mkdir();pins={str(p.resolve()):m.catalog.file_row(p.resolve()) for p in [m.tools.BASH,m.tools.SED,m.tools.SANDBOX]}
        with patch.object(m.tools.catalog,'metadata_command',return_value='') as command:
            result=m.tools.rewrite(source,self.root,pins)
            self.assertEqual(result['command'],m.transforms.COMMAND)
            self.assertEqual(command.call_args.args[0][-1],m.transforms.COMMAND)
            self.assertIn('(deny network*)',command.call_args.args[0][2])
        pins[str(m.tools.SED.resolve())]['sha256']='0'*64
        with patch.object(m.tools.catalog,'metadata_command') as command:
            with self.assertRaisesRegex(ValueError,'pin'):m.tools.rewrite(source,self.root,pins)
            command.assert_not_called()
    def test_scope_contains_no_compiler_or_archive_source_execution(self):
        source=Path(m.__file__).read_text()
        self.assertNotIn('str(catalog.CLANG)',source);self.assertNotIn('subprocess.run',source)
        self.assertIn('sourceExecuted=False',source)

    def test_http_body_uses_single_read_with_remaining_timeout_and_revokes_late_data(self):
        response=MagicMock();response.length=None;clock=[100.0]
        def read(n):clock[0]=116.0;return b'late'
        response.read1.side_effect=read
        with patch.object(m.base,'now',side_effect=lambda:clock[0]):
            with self.assertRaisesRegex(ValueError,'deadline'):m.read_http_block(response,115.0)
        response.fp.raw._sock.settimeout.assert_called_once_with(15.0)
        response.read.assert_not_called()
    def test_http_reader_blocks_unknown_transport_and_handles_complete_content_length(self):
        response=MagicMock();response.length=0
        self.assertEqual(m.read_http_block(response,m.now()+15),b'');response.read1.assert_not_called()
        class Unknown:
            length=None
        with self.assertRaisesRegex(ValueError,'unavailable'):m.read_http_block(Unknown(),m.now()+15)
    def test_missing_terminal_outcome_quarantines_a_started_owner(self):
        parent=self.root/'history';attempt=parent/'prepare-1';attempt.mkdir(parents=True)
        (attempt/'owner-started.json').write_text('{}')
        with self.assertRaisesRegex(ValueError,'quarantined'):m.quarantine(parent)
    def test_stdlib_http_response_socket_shape_and_read1_without_network(self):
        import http.client
        class Raw(io.RawIOBase):
            def __init__(self,sock):self._sock=sock;self.data=io.BytesIO(b'HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc')
            def readable(self):return True
            def readinto(self,buffer):
                data=self.data.read(len(buffer));buffer[:len(data)]=data;return len(data)
        class Socket:
            def __init__(self):self.timeouts=[]
            def makefile(self,mode):return io.BufferedReader(Raw(self))
            def settimeout(self,value):self.timeouts.append(value)
        sock=Socket();response=http.client.HTTPResponse(sock)
        try:
            response.begin();self.assertEqual(m.read_http_block(response,m.now()+15),b'abc')
            self.assertEqual(m.read_http_block(response,m.now()+15),b'')
            self.assertEqual(len(sock.timeouts),1);self.assertGreater(sock.timeouts[0],0);self.assertLessEqual(sock.timeouts[0],15)
        finally:response.close()

    def test_json_size_and_type_are_bounded_before_body_read_or_hash(self):
        import os
        large=self.root/'large.json';large.write_bytes(b'x'*100)
        with patch.object(m.catalog,'file_row',side_effect=AssertionError('unbounded hash')):
            with self.assertRaisesRegex(ValueError,'ceiling'):m.read_json(large,32)
            fifo=self.root/'fifo';os.mkfifo(fifo)
            with self.assertRaisesRegex(ValueError,'type'):m.read_json(fifo,32)
        safe=self.root/'safe.json';safe.write_bytes(b'{"bounded": true}')
        value,digest=m.read_json_bound(safe,32)
        self.assertEqual(value,{'bounded':True});self.assertEqual(digest,hashlib.sha256(safe.read_bytes()).hexdigest())
