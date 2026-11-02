"""Synthetic scanner/owner regressions; no real archive scan or network."""
import gzip
import hashlib
import importlib.util
import io
import json
import struct
from pathlib import Path
import sys
import tarfile
import tempfile
import types
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT/'scripts/diagnostics/root_dependency_offline_v2/inspect.py'
spec = importlib.util.spec_from_file_location('offline_inspection_v2_test', SCRIPT)
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
PREFIX = 'dawn-'+m.COMMIT


def pax_field(key, value):
    body = (key+'='+value+'\n').encode()
    n = len(body)+2
    while n != len(body)+len(str(n))+1: n = len(body)+len(str(n))+1
    return str(n).encode()+b' '+body


class OfflineV2Tests(unittest.TestCase):
    def setUp(self):
        t = tempfile.TemporaryDirectory(); self.addCleanup(t.cleanup); self.root = Path(t.name)
    def archive(self, extras=(), before=(), required=True):
        path = self.root/'source.tar.gz'
        with tarfile.open(path, 'w:gz', format=tarfile.USTAR_FORMAT) as out:
            root = tarfile.TarInfo(PREFIX+'/'); root.type = tarfile.DIRTYPE; out.addfile(root)
            rows = list(before)
            if required: rows += [(p, p.encode(), tarfile.REGTYPE, '') for p in m.REQUIRED]
            rows += list(extras)
            for name, data, kind, link in rows:
                h = tarfile.TarInfo(name if kind in (tarfile.XHDTYPE,tarfile.XGLTYPE) else PREFIX+'/'+name)
                h.type, h.linkname, h.size = kind, link, len(data)
                out.addfile(h, io.BytesIO(data))
        return path
    def scan(self, path, **kwargs):
        output = self.root/('selected-'+str(len(list(self.root.glob('selected-*')))))
        return m.scan(path, output, m.Budget(), expected_sha=m.owner.digest(path), expected_bytes=path.stat().st_size, **kwargs)
    def expect_bad(self, path, regex):
        with self.assertRaisesRegex((ValueError,EOFError,OSError,UnicodeError), regex): self.scan(path)

    def test_complete_scan_hashes_every_file_and_never_executes(self):
        p = self.archive([('tests/fixture.py', b'raise RuntimeError("never execute")', tarfile.REGTYPE, '')])
        r = self.scan(p)
        self.assertTrue(r['completeSourceValidated']); self.assertTrue(r['gzipEOFValidated'])
        self.assertFalse(r['sourceExecuted']); self.assertFalse(r['memberListRetained'])
        self.assertEqual(r['types']['file'], len(m.REQUIRED)+1)
        self.assertEqual(len(r['selectedFiles']), len(m.REQUIRED))
        self.assertEqual(r['duplicateTableBytes'], 33816576)
        for f in self.root.glob('selected-*/generator/*.py'): self.assertEqual(f.stat().st_mode & 0o777, 0o600)

    def test_inventory_replays_and_changes_with_content_or_order(self):
        rows = [('other/a', b'a', tarfile.REGTYPE, ''), ('other/b', b'b', tarfile.REGTYPE, '')]
        a = self.scan(self.archive(rows))['inventorySha256']
        self.assertEqual(a, self.scan(self.archive(rows))['inventorySha256'])
        self.assertNotEqual(a, self.scan(self.archive(rows[::-1]))['inventorySha256'])
        rows[0] = ('other/a', b'c', tarfile.REGTYPE, '')
        self.assertNotEqual(a, self.scan(self.archive(rows))['inventorySha256'])

    def test_inventory_matches_independent_exact_fixture_frames(self):
        p=self.archive();raw=gzip.decompress(p.read_bytes());offset=0
        expected=hashlib.sha256(b'edge-dawn-inventory-v2\n')
        for name in ('',*m.REQUIRED):
            header=raw[offset:offset+512];data=name.encode() if name else b''
            record=dict(headerSha256=hashlib.sha256(header).hexdigest(),kind='file' if name else 'directory',
                        path=name,link='',size=len(data),mode=0o644,
                        contentSha256=hashlib.sha256(data).hexdigest() if name else None)
            encoded=json.dumps(record,sort_keys=True,ensure_ascii=False,allow_nan=False,separators=(',',':')).encode()
            expected.update(struct.pack('>I',len(encoded)));expected.update(encoded)
            offset+=512+((len(data)+511)//512)*512
        self.assertEqual(self.scan(p)['inventorySha256'],expected.hexdigest())

    def test_duplicate_unsafe_and_long_names(self):
        for name in ['generator/templates/api_cpp.h', '../escape', '/absolute', 'a//b', 'a/./b', 'a\\b']:
            self.expect_bad(self.archive([(name,b'',tarfile.REGTYPE,'')]), 'duplicate|source path|prefix')
        with self.assertRaisesRegex(ValueError,'long'): m.canonical('a'*1025)

    def test_links_before_and_after_selected_descendant_reject(self):
        link = [('generator', b'', tarfile.SYMTYPE, 'safe')]
        self.expect_bad(self.archive(before=link), 'non-directory ancestor')
        self.expect_bad(self.archive(extras=link), 'non-directory ancestor')

    def test_regular_file_ancestor_before_and_after_selected_descendant(self):
        ancestor=[('generator',b'not a directory',tarfile.REGTYPE,'')]
        self.expect_bad(self.archive(before=ancestor),'non-directory ancestor')
        self.expect_bad(self.archive(extras=ancestor),'non-directory ancestor')

    def test_safe_links_recorded_not_created_and_escaping_rejected(self):
        good = self.scan(self.archive([('alias',b'',tarfile.SYMTYPE,'LICENSE'),
                                      ('hard',b'',tarfile.LNKTYPE,PREFIX+'/LICENSE')]))
        self.assertEqual(good['types']['symlink'],1); self.assertEqual(good['types']['hardlink'],1)
        for kind, link in [(tarfile.SYMTYPE,'../outside'),(tarfile.SYMTYPE,'/tmp/escape'),
                           (tarfile.LNKTYPE,'LICENSE')]:
            self.expect_bad(self.archive([('alias',b'',kind,link)]),'escaping|unsafe|outside')

    def test_fixed_duplicate_storage_and_probe_exhaustion(self):
        n = m.Names(slots=1,probes=1); n.locate('first',insert=True,link=True)
        self.assertEqual(n.storage_bytes,34); self.assertTrue(n.locate('first'))
        with self.assertRaisesRegex(ValueError,'probe'): n.locate('different')
        with self.assertRaisesRegex(ValueError,'probe'): n.locate('different',insert=True)
        with self.assertRaisesRegex(ValueError,'duplicate'): n.locate('first',insert=True)

    def test_pax_valid_local_path_and_global_comment(self):
        before=[('pax-global',pax_field('comment','revision'),tarfile.XGLTYPE,'')]
        extras=[('pax-local',pax_field('path',PREFIX+'/other/renamed'),tarfile.XHDTYPE,''),
                ('other/raw',b'data',tarfile.REGTYPE,'')]
        r=self.scan(self.archive(extras,before))
        self.assertEqual(r['types']['pax-global'],1); self.assertEqual(r['types']['pax-local'],1)

    def test_pax_reserved_numeric_and_charset_validation(self):
        for value in ['-1','+1','1.5','1e2','01','2147483649','9'*40,'']:
            with self.assertRaises(ValueError): m.pax(pax_field('size',value),False)
        for key,value in [('hdrcharset','BINARY'),('hdrcharset','utf-8'),('uid','4294967296'),
                          ('mtime','+1'),('comment','')]:
            with self.assertRaises(ValueError): m.pax(pax_field(key,value),False)
        for key in ('path','linkpath','size'):
            with self.assertRaisesRegex(ValueError,'global reserved'): m.pax(pax_field(key,'1'),True)
        self.assertEqual(m.pax(pax_field('size','0'),False)['size'],'0')

    def test_pax_nonlink_and_nonregular_overrides_rejected(self):
        for key,kind,data in [('linkpath',tarfile.REGTYPE,b'x'),('size',tarfile.DIRTYPE,b'')]:
            attrs=pax_field(key,'0' if key=='size' else 'LICENSE')
            self.expect_bad(self.archive([('local',attrs,tarfile.XHDTYPE,''),('other',data,kind,'')]),'nonlink|nonregular')

    def test_malformed_pax_and_chained_or_unused_local(self):
        for data in [b'999 path=a\n',b'0 x=y\n',b'9 path=x',pax_field('unknown','x'),
                     pax_field('path','a')+pax_field('path','b')]:
            with self.assertRaises(ValueError): m.pax(data,False)
        self.expect_bad(self.archive([('local',pax_field('path',PREFIX+'/other'),tarfile.XHDTYPE,'')]),'unused local')
        self.expect_bad(self.archive([('local',pax_field('path',PREFIX+'/other'),tarfile.XHDTYPE,''),
                                      ('global',pax_field('comment','x'),tarfile.XGLTYPE,'')]),'chained|interrupted')

    def test_payload_crc_truncation_and_nonzero_tail(self):
        original=self.archive().read_bytes()
        for cut in (1,4,12):
            p=self.root/'broken.gz';p.write_bytes(original[:-cut]);self.expect_bad(p,'end|truncat|CRC|Compressed')
        decoded=gzip.decompress(original)
        p.write_bytes(gzip.compress(decoded+b'not-zero'));self.expect_bad(p,'trailing')
        broken=bytearray(original);broken[-8]^=1;p.write_bytes(broken);self.expect_bad(p,'CRC')

    def test_header_checksum_and_unsupported_member(self):
        p=self.archive();data=bytearray(gzip.decompress(p.read_bytes()));data[0]^=1
        p.write_bytes(gzip.compress(data));self.expect_bad(p,'checksum')
        self.expect_bad(self.archive([('fifo',b'',tarfile.FIFOTYPE,'')]),'unsupported')

    def test_header_name_frame_decoded_selected_and_rss_limits(self):
        for key, value, error in [('headers',1,'header ceiling'),('names',1,'metadata ceiling'),
                                 ('frames',1,'hashing ceiling'),('decoded',513,'decoded'),
                                 ('selected',1,'selected'),('rss',1,'RSS'),('hashing',1,'hashing ceiling')]:
            with patch.dict(m.LIMITS,{key:value}): self.expect_bad(self.archive(),error)
        with patch.dict(m.LIMITS,{'pax':1}):
            self.expect_bad(self.archive(before=[('global',pax_field('comment','x'),tarfile.XGLTYPE,'')]),'PAX payload')

    def test_input_hash_prefix_symlink_and_replacement(self):
        p=self.archive()
        with self.assertRaisesRegex(ValueError,'hash'): m.scan(p,self.root/'out',m.Budget(),expected_sha='0'*64,expected_bytes=p.stat().st_size)
        self.expect_bad(self.archive([('wrong',b'',tarfile.REGTYPE,'')],required=False),'required')
        p=self.archive()
        link=self.root/'archive-link';link.symlink_to(p)
        with self.assertRaises(OSError): self.scan(link)
        original=m.descriptor_digest;count=0
        def replace(raw):
            nonlocal count
            digest=original(raw);count+=1
            if count==2:
                tmp=self.root/'new';tmp.write_bytes(p.read_bytes());tmp.replace(p)
            return digest
        with patch.object(m,'descriptor_digest',side_effect=replace): self.expect_bad(p,'replaced|changed during')

    def test_write_budget_and_incremental_receipt_limits(self):
        self.expect_bad_with_budget(self.archive(),m.Budget(1))
        with self.assertRaisesRegex(ValueError,'serialization'):
            m.write_json(self.root/'receipt',{'value':'x'*20},m.Budget(),10)
    def expect_bad_with_budget(self,p,budget):
        with self.assertRaisesRegex(ValueError,'output ceiling'):
            m.scan(p,self.root/'out',budget,expected_sha=m.owner.digest(p),expected_bytes=p.stat().st_size)

    def test_no_metadata_before_complete_scan(self):
        with patch.object(m,'scan',side_effect=ValueError('injected source failure')),patch.object(m,'package_metadata') as fetch:
            with self.assertRaises(ValueError): m.worker({'archive':'unused'},self.root)
        fetch.assert_not_called();self.assertFalse((self.root/'source-validation.json').exists())

    def test_metadata_redirect_scope_and_no_wheel_fetch(self):
        request=m.urllib.request.Request('https://pypi.org/pypi/Jinja2/3.1.6/json')
        for url in [request.full_url,'http://pypi.org/data','https://files.pythonhosted.org/wheel','https://pypi.org/other']:
            with self.assertRaisesRegex(ValueError,'redirect'): m.MetadataOnly().redirect_request(request,None,302,'',{},url)
        def response(url,timeout):
            package,version,filename=next(x for x in m.PACKAGES if '/'+x[0]+'/' in url)
            return io.BytesIO(json.dumps(dict(info=dict(name=package,version=version),urls=[dict(filename=filename,
                yanked=False,packagetype='bdist_wheel',size=123,digests=dict(sha256='1'*64),
                url='https://files.pythonhosted.org/packages/'+filename)])).encode())
        opener=types.SimpleNamespace(open=response)
        with patch.object(m.urllib.request,'build_opener',return_value=opener): result=m.package_metadata(self.root,m.Budget())
        self.assertFalse(result['wheelBytesVerified']);self.assertEqual(len(result['responses']),2)

    def test_failed_or_uncertain_cleanup_cannot_accept(self):
        for status,code,clean in [('TIMED_OUT',0,True),('ANALYSIS_COMMAND_SUCCEEDED',0,False),
                                  ('ANALYSIS_COMMAND_SUCCEEDED',1,True)]:
            with self.assertRaisesRegex(ValueError,'cleanup'):
                m.accept({},self.root,dict(classification=status,exitCode=code,cleanupConfirmed=clean),'freeze')
        self.assertFalse((self.root/'accepted-receipt.json').exists())

    def test_complete_receipt_acceptance_and_selected_tampering(self):
        p=self.archive();selected=self.root/'selected-source'
        source=m.scan(p,selected,m.Budget(),expected_sha=m.owner.digest(p),expected_bytes=p.stat().st_size)
        (self.root/'source-validation.json').write_text(json.dumps(source))
        (self.root/'package-metadata.json').write_text(json.dumps(dict(advertisedWheels=[{},{}],responses=[],wheelBytesVerified=False)))
        outcome=dict(classification='ANALYSIS_COMMAND_SUCCEEDED',exitCode=0,cleanupConfirmed=True)
        with patch.object(m,'ARCHIVE_SHA',m.owner.digest(p)),patch.object(m,'ARCHIVE_BYTES',p.stat().st_size):
            r=m.accept({'inputs':[]},self.root,outcome,'freeze')
            self.assertTrue(r['sourceQualified']);self.assertFalse(r['wheelBytesVerified'])
            (selected/'LICENSE').write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError,'selected source changed'):m.accept({'inputs':[]},self.root,outcome,'freeze')

    def test_owner_atomic_acceptance_after_outcome_and_no_publication_on_write_failure(self):
        f=self.root/'freeze';f.write_text('{}');attempt=self.root/'attempts/one'
        freeze=dict(attemptParent=str(self.root/'attempts'),python=sys.executable,environment={})
        argv=[str(SCRIPT),'--freeze',str(f),'--sha256','hash','--attempt',str(attempt)]
        result=dict(classification='ANALYSIS_COMMAND_SUCCEEDED',exitCode=0,cleanupConfirmed=True)
        original=m.write_json
        def writer(path,value,budget,maximum):
            if path.name=='outcome.json': raise OSError('injected disk full')
            return original(path,value,budget,maximum)
        with patch.object(sys,'argv',argv),patch.object(m,'load_freeze',return_value=freeze), \
             patch.object(m.shutil,'disk_usage',return_value=types.SimpleNamespace(free=2*1024**3)), \
             patch.object(m.owner,'run_owned',return_value=result),patch.object(m,'accept',return_value={'valid':True}), \
             patch.object(m,'write_json',side_effect=writer):
            with self.assertRaisesRegex(OSError,'disk full'):m.main()
        self.assertFalse((attempt/'accepted-receipt.json').exists())
        attempt=attempt.parent/'two';argv[-1]=str(attempt)
        original_link=m.os.link
        def publish(src,dst):
            self.assertTrue((attempt/'outcome.json').exists())
            self.assertEqual(json.loads(Path(src).read_text()),{'valid':True})
            original_link(src,dst)
        with patch.object(sys,'argv',argv),patch.object(m,'load_freeze',return_value=freeze), \
             patch.object(m.shutil,'disk_usage',return_value=types.SimpleNamespace(free=2*1024**3)), \
             patch.object(m.owner,'run_owned',return_value=result) as owned,patch.object(m,'accept',return_value={'valid':True}), \
             patch.object(m.os,'link',side_effect=publish):
            self.assertEqual(m.main(),0)
        self.assertEqual(owned.call_args.args[-3:],(300,2*m.MIB,15))
        self.assertEqual(json.loads((attempt/'accepted-receipt.json').read_text()),{'valid':True})

    def test_malformed_metadata_and_response_ceiling(self):
        opener=types.SimpleNamespace(open=lambda *a,**k:io.BytesIO(b'x'*(m.MIB+1)))
        with patch.object(m.urllib.request,'build_opener',return_value=opener):
            with self.assertRaisesRegex(ValueError,'metadata byte ceiling'):m.package_metadata(self.root,m.Budget())
        self.assertLessEqual((self.root/'jinja2-release.json').stat().st_size,m.MIB)

    def test_pax_aggregate_ceiling(self):
        with patch.dict(m.LIMITS,{'all_pax':1}):
            self.expect_bad(self.archive(before=[('global',pax_field('comment','x'),tarfile.XGLTYPE,'')]),'PAX payload')

    def test_owner_interruption_records_failure_without_acceptance(self):
        f=self.root/'freeze';f.write_text('{}');attempt=self.root/'attempts/one'
        freeze=dict(attemptParent=str(self.root/'attempts'),python=sys.executable,environment={})
        argv=[str(SCRIPT),'--freeze',str(f),'--sha256','hash','--attempt',str(attempt)]
        for error in (KeyboardInterrupt(),ValueError('cleanup uncertain')):
            with patch.object(sys,'argv',argv),patch.object(m,'load_freeze',return_value=freeze), \
                 patch.object(m.shutil,'disk_usage',return_value=types.SimpleNamespace(free=2*1024**3)), \
                 patch.object(m.owner,'run_owned',side_effect=error):
                self.assertEqual(m.main(),1)
            r=json.loads((attempt/'outcome.json').read_text());self.assertIsNone(r['cleanupConfirmed'])
            self.assertFalse((attempt/'accepted-receipt.json').exists())
            attempt=attempt.parent/'two';argv[-1]=str(attempt)


if __name__ == '__main__': unittest.main()
